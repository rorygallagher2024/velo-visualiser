
package com.lowlatency.visualizer.lifx

import android.util.Log
import com.lowlatency.visualizer.BeatBus
import com.lowlatency.visualizer.LightingSettings
import com.lowlatency.visualizer.LinkSync
import com.lowlatency.visualizer.NativeBridge
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class LifxBulb(val ip: String, val mac: ByteArray, val label: String) {
    var isSelected: Boolean = false
    
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is LifxBulb) return false
        return ip == other.ip
    }
    
    override fun hashCode(): Int = ip.hashCode()
}

class LifxController {

    private val bulbs = mutableListOf<LifxBulb>()
    fun hasSelectedBulbs(): Boolean = synchronized(bulbs) { bulbs.any { it.isSelected } }

    @Volatile private var streaming = false
    val isStreaming: Boolean get() = streaming
    private var senderThread: Thread? = null

    // Audio-reactive state
    @Volatile private var curL = 0f
    @Volatile private var curM = 0f
    @Volatile private var curH = 0f
    
    // Link state
    @Volatile private var linkBeatCount = 0L

    // Sender state
    private var senderFlash = 0f
    private var senderLastBeat = 0
    private var senderLastLinkBeat = 0L
    private var senderLinkBeatFired = false
    private var senderCurrentHue = PURPLE_HUE
    private var senderCurrentSat = SAT_BASS
    
    private data class LifxColor(val hue: Float, val sat: Float, val bri: Float)

    fun onBands(low: Float, mid: Float, high: Float) {
        curL = low; curM = mid; curH = high
    }

    fun onLinkBeat() {
        linkBeatCount++
    }

    fun startDiscovery(onBulbFound: (LifxBulb) -> Unit, onFinished: () -> Unit) {
        val oldSelectedMacs = synchronized(bulbs) {
            bulbs.filter { it.isSelected }.map { ByteBuffer.wrap(it.mac) }.toSet()
        }
        synchronized(bulbs) { bulbs.clear() }
        Thread {
            try {
                val socket = DatagramSocket().apply {
                    broadcast = true
                }
                scanNetworkForBulbs(socket) { bulb ->
                    if (oldSelectedMacs.contains(ByteBuffer.wrap(bulb.mac))) {
                        bulb.isSelected = true
                    }
                    onBulbFound(bulb)
                }
                socket.close()
            } catch (e: Exception) {
                Log.e(TAG, "Discovery error: ${e.message}")
            } finally {
                onFinished()
            }
        }.start()
    }

    private fun scanNetworkForBulbs(socket: DatagramSocket, onBulbFound: (LifxBulb) -> Unit) {
        val txPacket = buildDiscoveryPacket()
        val rxBytes = ByteArray(128)
        val rxPacket = DatagramPacket(rxBytes, rxBytes.size)

        for (attempt in 0 until 4) { // 4 attempts
            try {
                socket.send(txPacket)
            } catch (_: Exception) {}

            val endOfWindow = System.currentTimeMillis() + 1000L
            while (true) {
                val remaining = endOfWindow - System.currentTimeMillis()
                if (remaining <= 0) break
                
                socket.soTimeout = remaining.toInt()
                try {
                    socket.receive(rxPacket)
                    processDiscoveryResponse(rxPacket, onBulbFound)
                } catch (_: java.net.SocketTimeoutException) {
                    break
                } catch (_: Exception) {
                    // Ignore malformed packets and continue receiving
                }
            }
        }
    }

    private fun buildDiscoveryPacket(): DatagramPacket {
        val buffer = ByteBuffer.allocate(36).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putShort(36.toShort()) // Size
        buffer.putShort(0x3400.toShort()) // Protocol 1024, Addressable, Tagged
        buffer.putInt(0) // Source
        buffer.putLong(0L) // Target MAC (8 bytes) = 0 for broadcast
        buffer.put(ByteArray(6)) // Reserved
        buffer.put(0) // Ack/Res
        buffer.put(0) // Sequence
        buffer.putLong(0L) // Reserved Protocol
        buffer.putShort(23.toShort()) // Type: GetLabel
        buffer.putShort(0.toShort()) // Reserved
        
        val bytes = buffer.array()
        return DatagramPacket(bytes, bytes.size, InetAddress.getByName("255.255.255.255"), 56700)
    }

    private fun processDiscoveryResponse(rxPacket: DatagramPacket, onBulbFound: (LifxBulb) -> Unit) {
        val rxBuffer = ByteBuffer.wrap(rxPacket.data).order(ByteOrder.LITTLE_ENDIAN)
        if (rxBuffer.limit() < 36) return
        
        rxBuffer.position(8) // Skip size, protocol, source
        val mac = ByteArray(8)
        rxBuffer.get(mac)
        
        rxBuffer.position(32)
        val type = rxBuffer.short
        
        if (type == 25.toShort()) { // StateLabel
            rxBuffer.position(36)
            val labelBytes = ByteArray(32)
            rxBuffer.get(labelBytes)
            val label = String(labelBytes, Charsets.UTF_8).trimEnd('\u0000').ifEmpty { "LIFX Bulb" }
            val ip = rxPacket.address.hostAddress ?: return
            val bulb = LifxBulb(ip, mac, label)
            synchronized(bulbs) {
                if (!bulbs.contains(bulb)) {
                    bulbs.add(bulb)
                    onBulbFound(bulb)
                }
            }
        }
    }
    
    fun setBulbSelected(ip: String, selected: Boolean) {
        val bulb = synchronized(bulbs) {
            bulbs.find { it.ip == ip }?.apply { isSelected = selected }
        }
        
        // If changed while streaming is on, immediately turn the bulb on or off
        if (bulb != null && streaming) {
            Thread {
                sendSetPower(null, bulb.ip, bulb.mac, selected)
            }.start()
        }
    }

    fun enableStreaming() {
        if (streaming) return
        streaming = true
        startSender()
    }

    fun disableStreaming() {
        streaming = false
        senderThread?.join(100)
        senderThread = null

        Thread {
            try {
                val activeBulbs = synchronized(bulbs) { bulbs.filter { it.isSelected } }
                val socket = DatagramSocket()
                for (bulb in activeBulbs) {
                    sendSetPower(socket, bulb.ip, bulb.mac, false)
                }
                socket.close()
            } catch (_: Exception) {}
        }.start()
    }

    private fun startSender() {
        senderThread = Thread {
            // Keep a persistent socket for sending to avoid recreation overhead
            val socket = DatagramSocket()
            senderFlash = 0f
            senderLastBeat = BeatBus.beatCount
            senderLastLinkBeat = linkBeatCount
            senderLinkBeatFired = false
            senderCurrentHue = PURPLE_HUE
            senderCurrentSat = SAT_BASS
            
            val frameNs = 1_000_000_000L / SEND_HZ
            
            // Send SetPower to all selected bulbs to ensure they are on
            for (bulb in synchronized(bulbs) { bulbs.filter { it.isSelected } }) {
                sendSetPower(socket, bulb.ip, bulb.mac, true)
            }

            // Pre-allocate a buffer for SetColor (102) -> 49 bytes
            val buf = ByteBuffer.allocate(49).order(ByteOrder.LITTLE_ENDIAN)

            while (streaming) {
                val t0 = System.nanoTime()
                val l = curL; val m = curM; val h = curH
                val isLinkOn = LinkSync.enabled
                
                val finalColor = if (isLinkOn) {
                    calculateLinkColor()
                } else {
                    calculateAudioColor(l, m, h)
                }

                val activeBulbs = synchronized(bulbs) { bulbs.filter { it.isSelected } }
                val numBulbs = activeBulbs.size

                for ((i, bulb) in activeBulbs.withIndex()) {
                    val spread = if (!isLinkOn && numBulbs > 1) (i.toFloat() / (numBulbs - 1) - 0.5f) * AUDIO_CHANNEL_SPREAD else 0f
                    sendSetColor(socket, buf, bulb, finalColor.hue, finalColor.sat, finalColor.bri, spread)
                }

                val deadlineNs = t0 + frameNs
                val sleepNs = deadlineNs - System.nanoTime() - SPIN_MARGIN_NS
                if (sleepNs > 0) {
                    try { Thread.sleep(sleepNs / 1_000_000, (sleepNs % 1_000_000).toInt()) }
                    catch (_: InterruptedException) { break }
                }
                while (System.nanoTime() < deadlineNs) Thread.yield()
            }
            socket.close()
        }
        senderThread?.start()
    }

    private fun calculateLinkColor(): LifxColor {
        val cfg = LightingSettings
        val bc = linkBeatCount
        var shouldFlash = false

        if (cfg.linkBeatFlashEnabled) {
            val lookaheadMs = cfg.hueLookaheadMs
            if (bc != senderLastLinkBeat) {
                if (!senderLinkBeatFired) shouldFlash = true
                senderLastLinkBeat = bc
                senderLinkBeatFired = false
            }
            if (!shouldFlash && !senderLinkBeatFired) {
                val phase = NativeBridge.nativeLinkBeatPhase()
                val bpm = NativeBridge.nativeLinkTempo()
                if (bpm > 0.0) {
                    val msUntilBeat = (1.0 - phase) * 60000.0 / bpm
                    if (msUntilBeat <= lookaheadMs) {
                        senderLinkBeatFired = true
                        shouldFlash = true
                    }
                }
            }
        } else {
            if (bc != senderLastLinkBeat) {
                shouldFlash = true
                senderLastLinkBeat = bc
            }
        }

        if (shouldFlash && BeatBus.gateOpen) {
            val ct = ((BeatBus.bassRatio - cfg.bassLo) / (cfg.bassHi - cfg.bassLo)).coerceIn(0f, 1f)
            val cs = ct * ct * (3f - 2f * ct)
            senderCurrentHue = RED_HUE + (PURPLE_HUE - RED_HUE) * cs
            senderCurrentSat = SAT_TREBLE + (SAT_BASS - SAT_TREBLE) * cs
            
            if (cfg.linkBeatFlashEnabled) {
                senderFlash = cfg.beatFlashAmp(BeatBus.loudness)
            }
        }
        senderFlash *= FLASH_DECAY
        val finalBri = cfg.linkBrightnessValue(senderFlash)
        return LifxColor(senderCurrentHue, senderCurrentSat, finalBri)
    }

    private fun calculateAudioColor(l: Float, m: Float, h: Float): LifxColor {
        val cfg = LightingSettings
        val bc = BeatBus.beatCount
        if (bc != senderLastBeat) { 
            senderFlash = BeatBus.loudness
            senderLastBeat = bc 
        }
        senderFlash *= FLASH_DECAY

        val total = l + m + h + 1e-3f
        val centroid = ((m * 0.5f + h) / total).coerceIn(0f, 1f)
        val finalBri = cfg.audioBrightnessValue(l, m, h, senderFlash)
        val finalSat = (AUDIO_SAT - senderFlash * 0.30f).coerceIn(0.6f, 1f)
        val finalHue = AUDIO_HUE_BASS + (AUDIO_HUE_TREBLE - AUDIO_HUE_BASS) * centroid
        return LifxColor(finalHue, finalSat, finalBri)
    }


    private fun sendSetPower(socket: DatagramSocket?, ip: String, mac: ByteArray, isOn: Boolean) {
        try {
            val s = socket ?: DatagramSocket()
            val powerBuf = ByteBuffer.allocate(42).order(ByteOrder.LITTLE_ENDIAN)
            powerBuf.putShort(42.toShort())
            powerBuf.putShort(0x1400.toShort())
            powerBuf.putInt(12345)
            powerBuf.put(mac)
            powerBuf.put(ByteArray(6))
            powerBuf.put(0)
            powerBuf.put(0)
            powerBuf.putLong(0L)
            powerBuf.putShort(117.toShort()) // SetPower
            powerBuf.putShort(0.toShort())
            powerBuf.putShort((if (isOn) 65535 else 0).toShort()) // Level
            powerBuf.putInt(0)
            s.send(DatagramPacket(powerBuf.array(), 42, InetAddress.getByName(ip), 56700))
            if (socket == null) s.close()
        } catch (_: Exception) {}
    }

    private fun sendSetColor(socket: DatagramSocket, buf: ByteBuffer, bulb: LifxBulb, finalHue: Float, finalSat: Float, finalBri: Float, spread: Float) {
        buf.clear()
        buf.putShort(49.toShort()) // Size
        buf.putShort(0x1400.toShort()) // Addressable, Protocol=1024
        buf.putInt(12345) // Source
        buf.put(bulb.mac) // Target
        buf.put(ByteArray(6)) // Reserved
        buf.put(0) // Ack/Res
        buf.put(0) // Sequence
        buf.putLong(0L) // Reserved Protocol
        buf.putShort(102.toShort()) // Type: SetColor
        buf.putShort(0.toShort()) // Reserved
        
        // Payload
        buf.put(0) // Reserved
        
        val bulbHue = (finalHue + spread * 360f).coerceIn(0f, 360f)
        
        val h16 = ((bulbHue / 360f) * 65535f).toInt().toShort()
        val s16 = (finalSat * 65535f).toInt().toShort()
        val b16 = (finalBri * 65535f).toInt().toShort()
        
        buf.putShort(h16)
        buf.putShort(s16)
        buf.putShort(b16)
        buf.putShort(3500.toShort()) // Kelvin
        buf.putInt(0) // Duration in ms (0 for instant)
        
        try {
            socket.send(DatagramPacket(buf.array(), 49, InetAddress.getByName(bulb.ip), 56700))
        } catch (_: Exception) {
            // ignore dropped packets
        }
    }

    companion object {
        private const val TAG = "LifxController"
        private const val SEND_HZ = 50L          
        private const val SPIN_MARGIN_NS = 2_000_000L
        private const val FLASH_DECAY = 0.80f

        // Brightness floor / beat amplitude / resting glow are shared in LightingSettings.
        private const val RED_HUE = 360f
        private const val PURPLE_HUE = 265f       
        private const val SAT_BASS = 1.0f         
        private const val SAT_TREBLE = 0.70f      

        private const val AUDIO_HUE_BASS = 360f     
        private const val AUDIO_HUE_TREBLE = 220f   
        private const val AUDIO_SAT = 0.92f         
        private const val AUDIO_CHANNEL_SPREAD = 0.22f 
    }
}
