package com.lowlatency.visualizer.lifx

import android.util.Log
import com.lowlatency.visualizer.BeatBus
import com.lowlatency.visualizer.HueStrobeSettings
import com.lowlatency.visualizer.LinkSync
import com.lowlatency.visualizer.NativeBridge
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.sqrt

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
    private var senderThread: Thread? = null

    // Audio-reactive state
    @Volatile private var curL = 0f
    @Volatile private var curM = 0f
    @Volatile private var curH = 0f
    
    // Link state
    @Volatile private var linkBeatCount = 0L

    fun onBands(low: Float, mid: Float, high: Float) {
        curL = low; curM = mid; curH = high
    }

    fun onLinkBeat() {
        linkBeatCount++
    }

    fun startDiscovery(onBulbFound: (LifxBulb) -> Unit, onFinished: () -> Unit) {
        synchronized(bulbs) { bulbs.clear() }
        Thread {
            try {
                val socket = DatagramSocket()
                socket.broadcast = true
                // We will scan for 4 seconds, broadcasting every 1 second.
                // This is much more reliable than sending 3 packets at once,
                // as it gives sleepy bulbs time to wake up and prevents router queue floods.
                // Packet: Device::GetLabel (Type 23)
                val buffer = ByteBuffer.allocate(36).order(ByteOrder.LITTLE_ENDIAN)
                buffer.putShort(36.toShort()) // Size
                buffer.putShort(0x3400.toShort()) // Protocol 1024, Addressable, Tagged
                buffer.putInt(0) // Source
                // Target MAC (8 bytes) = 0 for broadcast
                buffer.putLong(0L)
                buffer.put(ByteArray(6)) // Reserved
                buffer.put(0) // Ack/Res
                buffer.put(0) // Sequence
                buffer.putLong(0L) // Reserved Protocol
                buffer.putShort(23.toShort()) // Type: GetLabel
                buffer.putShort(0.toShort()) // Reserved

                val rxBytes = ByteArray(128)
                val rxPacket = DatagramPacket(rxBytes, rxBytes.size)

                for (attempt in 0 until 4) {
                    try {
                        val bytes = buffer.array()
                        val packet = DatagramPacket(bytes, bytes.size, InetAddress.getByName("255.255.255.255"), 56700)
                        socket.send(packet)
                    } catch (e: Exception) {}

                    val endOfWindow = System.currentTimeMillis() + 1000L
                    while (true) {
                        val remaining = endOfWindow - System.currentTimeMillis()
                        if (remaining <= 0) break
                        
                        socket.soTimeout = remaining.toInt()
                        try {
                            socket.receive(rxPacket)
                            val rxBuffer = ByteBuffer.wrap(rxPacket.data).order(ByteOrder.LITTLE_ENDIAN)
                            if (rxBuffer.limit() < 36) continue
                            
                            val size = rxBuffer.short
                            val protocol = rxBuffer.short
                            val source = rxBuffer.int
                            val mac = ByteArray(8)
                            rxBuffer.get(mac)
                            rxBuffer.position(32)
                            val type = rxBuffer.short
                            
                            if (type == 25.toShort()) { // StateLabel
                                rxBuffer.position(36)
                                val labelBytes = ByteArray(32)
                                rxBuffer.get(labelBytes)
                                val label = String(labelBytes, Charsets.UTF_8).trimEnd('\u0000').ifEmpty { "LIFX Bulb" }
                                val ip = rxPacket.address.hostAddress ?: continue
                                val bulb = LifxBulb(ip, mac, label)
                                synchronized(bulbs) {
                                    if (!bulbs.contains(bulb)) {
                                        bulbs.add(bulb)
                                        onBulbFound(bulb)
                                    }
                                }
                            }
                        } catch (e: java.net.SocketTimeoutException) {
                            break
                        } catch (e: Exception) {
                            // Ignore malformed packets and continue receiving
                        }
                    }
                }
                socket.close()
            } catch (e: Exception) {
                Log.e(TAG, "Discovery error: ${e.message}")
            } finally {
                onFinished()
            }
        }.start()
    }

    fun getDiscoveredBulbs(): List<LifxBulb> {
        synchronized(bulbs) {
            return bulbs.toList()
        }
    }
    
    fun setBulbSelected(ip: String, selected: Boolean) {
        synchronized(bulbs) {
            bulbs.find { it.ip == ip }?.isSelected = selected
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
                val powerBuf = ByteBuffer.allocate(42).order(ByteOrder.LITTLE_ENDIAN)
                val activeBulbs = synchronized(bulbs) { bulbs.filter { it.isSelected } }
                val socket = DatagramSocket()
                for (bulb in activeBulbs) {
                    powerBuf.clear()
                    powerBuf.putShort(42.toShort())
                    powerBuf.putShort(0x1400.toShort())
                    powerBuf.putInt(12345)
                    powerBuf.put(bulb.mac)
                    powerBuf.put(ByteArray(6))
                    powerBuf.put(0)
                    powerBuf.put(0)
                    powerBuf.putLong(0L)
                    powerBuf.putShort(117.toShort()) // SetPower
                    powerBuf.putShort(0.toShort())
                    powerBuf.putShort(0.toShort()) // OFF
                    powerBuf.putInt(0)
                    socket.send(DatagramPacket(powerBuf.array(), 42, InetAddress.getByName(bulb.ip), 56700))
                }
                socket.close()
            } catch (e: Exception) {}
        }.start()
    }

    private fun startSender() {
        senderThread = Thread {
            // Keep a persistent socket for sending to avoid recreation overhead
            val socket = DatagramSocket()
            var flash = 0f
            var lastBeat = BeatBus.beatCount
            var lastLinkBeat = linkBeatCount
            var linkBeatFired = false
            var lightBeatCount = 0L

            val frameNs = 1_000_000_000L / SEND_HZ
            
            val activeBulbs = synchronized(bulbs) { bulbs.filter { it.isSelected } }
            
            // Send SetPower to all selected bulbs to ensure they are on
            val powerBuf = ByteBuffer.allocate(42).order(ByteOrder.LITTLE_ENDIAN)
            for (bulb in activeBulbs) {
                powerBuf.clear()
                powerBuf.putShort(42.toShort()) // Size
                powerBuf.putShort(0x1400.toShort()) // Addressable, Protocol=1024
                powerBuf.putInt(12345) // Source
                powerBuf.put(bulb.mac) // Target
                powerBuf.put(ByteArray(6)) // Reserved
                powerBuf.put(0) // Ack/Res
                powerBuf.put(0) // Sequence
                powerBuf.putLong(0L) // Reserved Protocol
                powerBuf.putShort(117.toShort()) // Type: SetPower
                powerBuf.putShort(0.toShort()) // Reserved
                powerBuf.putShort(65535.toShort()) // Level
                powerBuf.putInt(0) // Duration
                try {
                    socket.send(DatagramPacket(powerBuf.array(), 42, InetAddress.getByName(bulb.ip), 56700))
                } catch (e: Exception) {}
            }

            // Pre-allocate a buffer for SetColor (102) -> 49 bytes
            val buf = ByteBuffer.allocate(49).order(ByteOrder.LITTLE_ENDIAN)

            var currentHue = PURPLE_HUE
            var currentSat = SAT_BASS

            while (streaming) {
                val t0 = System.nanoTime()
                val l = curL; val m = curM; val h = curH
                val isLinkOn = LinkSync.enabled
                
                var finalHue: Float
                var finalSat: Float
                var finalBri: Float

                if (isLinkOn) {
                    val cfg = HueStrobeSettings
                    val bc = linkBeatCount
                    var shouldFlash = false

                    if (cfg.linkBeatFlashEnabled) {
                        val lookaheadMs = cfg.hueLookaheadMs
                        if (bc != lastLinkBeat) {
                            if (!linkBeatFired) shouldFlash = true
                            lastLinkBeat = bc
                            linkBeatFired = false
                        }
                        if (!shouldFlash && !linkBeatFired) {
                            val phase = NativeBridge.nativeLinkBeatPhase()
                            val bpm = NativeBridge.nativeLinkTempo()
                            if (bpm > 0.0) {
                                val msUntilBeat = (1.0 - phase) * 60000.0 / bpm
                                if (msUntilBeat <= lookaheadMs) {
                                    linkBeatFired = true
                                    shouldFlash = true
                                }
                            }
                        }
                    } else {
                        if (bc != lastLinkBeat) {
                            shouldFlash = true
                            lastLinkBeat = bc
                        }
                    }

                    if (shouldFlash && BeatBus.gateOpen) {
                        val ct = ((BeatBus.bassRatio - cfg.bassLo) / (cfg.bassHi - cfg.bassLo)).coerceIn(0f, 1f)
                        val cs = ct * ct * (3f - 2f * ct)
                        currentHue = RED_HUE + (PURPLE_HUE - RED_HUE) * cs
                        currentSat = SAT_TREBLE + (SAT_BASS - SAT_TREBLE) * cs
                        
                        if (cfg.linkBeatFlashEnabled) {
                            flash = MIN_BEAT_AMP + (1f - MIN_BEAT_AMP) * BeatBus.loudness
                            lightBeatCount++
                        }
                    }
                    flash *= FLASH_DECAY
                    finalHue = currentHue
                    finalSat = currentSat
                    finalBri = sqrt((MIN_BRIGHT + cfg.restingGlow * 0.3f + flash).coerceIn(0f, 1f))
                } else {
                    val cfg = HueStrobeSettings
                    val bc = BeatBus.beatCount
                    if (bc != lastBeat) { flash = BeatBus.loudness; lastBeat = bc; lightBeatCount++ }
                    flash *= FLASH_DECAY
                    
                    val total = l + m + h + 1e-3f
                    val centroid = ((m * 0.5f + h) / total).coerceIn(0f, 1f)
                    val energy = (max(l, max(m, h)) * cfg.audioBrightMul).coerceIn(0f, 1f)
                    finalBri = MIN_BRIGHT + (1f - MIN_BRIGHT) * sqrt((energy + flash * 0.7f).coerceIn(0f, 1f))
                    finalSat = (AUDIO_SAT - flash * 0.30f).coerceIn(0.6f, 1f)
                    finalHue = AUDIO_HUE_BASS + (AUDIO_HUE_TREBLE - AUDIO_HUE_BASS) * centroid
                }

                val activeBulbs = synchronized(bulbs) { bulbs.filter { it.isSelected } }
                val numBulbs = activeBulbs.size

                for ((i, bulb) in activeBulbs.withIndex()) {
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
                    
                    // Spread hue across multiple bulbs if needed (audio mode only)
                    val spread = if (!isLinkOn && numBulbs > 1) (i.toFloat() / (numBulbs - 1) - 0.5f) * AUDIO_CHANNEL_SPREAD else 0f
                    val bulbHue = (finalHue + spread * 360f).coerceIn(0f, 360f)
                    
                    val h16 = ((bulbHue / 360f) * 65535f).toInt().toShort()
                    val s16 = (finalSat * 65535f).toInt().toShort()
                    val b16 = (finalBri * 65535f).toInt().toShort()
                    
                    buf.putShort(h16)
                    buf.putShort(s16)
                    buf.putShort(b16)
                    buf.putShort(3500.toShort()) // Kelvin
                    buf.putInt(0) // Duration in ms (0 for instant)
                    
                    val bytes = buf.array()
                    try {
                        socket.send(DatagramPacket(bytes, bytes.size, InetAddress.getByName(bulb.ip), 56700))
                    } catch (e: Exception) {
                        // ignore dropped packets
                    }
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

    companion object {
        private const val TAG = "LifxController"
        private const val SEND_HZ = 50L          
        private const val SPIN_MARGIN_NS = 2_000_000L  
        private const val MIN_BRIGHT = 0.06f      
        private const val FLASH_DECAY = 0.80f     

        private const val MIN_BEAT_AMP = 0.06f    
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
