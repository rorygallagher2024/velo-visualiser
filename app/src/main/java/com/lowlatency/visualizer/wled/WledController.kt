package com.lowlatency.visualizer.wled

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.lowlatency.visualizer.BeatBus
import com.lowlatency.visualizer.LightingSettings
import com.lowlatency.visualizer.NativeBridge
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.floor

class WledController(private val context: Context) {
    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val discoveredDevices = CopyOnWriteArrayList<WledDevice>()
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    
    // Playback state
    var isStreaming = false
        private set
    private var senderThread: Thread? = null
    private val wledDevices = CopyOnWriteArrayList<WledDevice>()

    // Audio-reactive state
    private var senderLastBeat: Int = 0
    private var senderFlash: Float = 0f
    private var senderCurrentHue: Float = 0f
    private var senderCurrentSat: Float = 0f
    
    @Volatile private var linkBeatCount = 0L
    private var senderLastLinkBeat = 0L
    private var senderLinkBeatFired = false

    companion object {
        private const val TAG = "WledController"
        private const val WLED_SERVICE_TYPE = "_wled._tcp."
        private const val WLED_UDP_PORT = 21324

        // Brightness floor / beat amplitude / resting glow are shared in LightingSettings.

        // Link constants
        private const val RED_HUE = 350f
        private const val PURPLE_HUE = 280f
        private const val SAT_BASS = 0.95f
        private const val SAT_TREBLE = 0.85f

        // Audio constants
        private const val AUDIO_HUE_BASS = 280f   // Purple
        private const val AUDIO_HUE_TREBLE = 190f // Cyan
        private const val SAT_AUDIO = 1.0f
        
        private const val FLASH_DECAY = 0.85f
        private const val MAX_LEDS_PER_PACKET = 490 // Max LEDs that fit in a DRGB packet
    }

    fun startDiscovery(onDeviceFound: (WledDevice) -> Unit, onFinished: () -> Unit) {
        discoveredDevices.clear()
        // Drop devices from any previous scan so re-scanning can't accumulate
        // duplicates in the streaming list (which would double-send packets).
        wledDevices.clear()

        discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {
                Log.d(TAG, "WLED Discovery started")
            }

            override fun onServiceFound(service: NsdServiceInfo) {
                Log.d(TAG, "Found service: ${service.serviceName}")
                nsdManager.resolveService(service, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                        Log.e(TAG, "Resolve failed: $errorCode")
                    }

                    override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                        Log.d(TAG, "Resolved service: ${serviceInfo.serviceName} at ${serviceInfo.host.hostAddress}")
                        val device = WledDevice(serviceInfo.serviceName, serviceInfo.host)
                        if (discoveredDevices.none { it.ip == device.ip }) {
                            discoveredDevices.add(device)
                            wledDevices.add(device)
                            onDeviceFound(device)
                        }
                    }
                })
            }

            override fun onServiceLost(service: NsdServiceInfo) {
                Log.e(TAG, "Service lost: ${service.serviceName}")
            }

            override fun onDiscoveryStopped(serviceType: String) {
                Log.i(TAG, "Discovery stopped: $serviceType")
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "Discovery failed: Error code:$errorCode")
                nsdManager.stopServiceDiscovery(this)
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "Discovery failed: Error code:$errorCode")
                nsdManager.stopServiceDiscovery(this)
            }
        }

        nsdManager.discoverServices(WLED_SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
        
        // Stop discovery after 5 seconds
        Handler(Looper.getMainLooper()).postDelayed({
            try {
                discoveryListener?.let { nsdManager.stopServiceDiscovery(it) }
                discoveryListener = null
                onFinished()
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping discovery", e)
            }
        }, 5000)
    }

    fun setDeviceSelected(ip: String, selected: Boolean) {
        wledDevices.find { it.ip.hostAddress == ip }?.isSelected = selected
    }

    fun hasSelectedDevices(): Boolean {
        return wledDevices.any { it.isSelected }
    }

    fun enableStreaming() {
        if (isStreaming) return
        isStreaming = true
        
        senderLastLinkBeat = linkBeatCount
        senderLinkBeatFired = false
        startSender()
    }

    fun disableStreaming() {
        isStreaming = false
        senderThread?.join(100)
        senderThread = null
    }
    
    fun onLinkBeat() {
        linkBeatCount++
    }
    
    @Volatile private var l = 0f
    @Volatile private var m = 0f
    @Volatile private var h = 0f

    fun onBands(low: Float, mid: Float, high: Float) {
        l = low; m = mid; h = high
    }

    private fun startSender() {
        senderThread = Thread {
            val socket = DatagramSocket()
            // 40 FPS = 25ms per frame
            val frameNs = 25_000_000L
            val buf = ByteArray(2 + (MAX_LEDS_PER_PACKET * 3))
            
            // Header for WLED Protocol 2 (DRGB)
            buf[0] = 2 // DRGB Protocol
            buf[1] = 2 // Timeout in seconds

            while (isStreaming) {
                val t0 = System.nanoTime()
                
                val isLinkOn = com.lowlatency.visualizer.LinkSync.enabled
                val finalColor = if (isLinkOn) calculateLinkColor() else calculateAudioColor(l, m, h)
                
                val r = finalColor.r.toByte()
                val g = finalColor.g.toByte()
                val b = finalColor.b.toByte()
                
                // Fill buffer with same color for all LEDs
                var offset = 2
                for (i in 0 until MAX_LEDS_PER_PACKET) {
                    buf[offset++] = r
                    buf[offset++] = g
                    buf[offset++] = b
                }
                
                val activeDevices = synchronized(wledDevices) { wledDevices.filter { it.isSelected } }
                
                for (device in activeDevices) {
                    try {
                        val packet = DatagramPacket(buf, buf.size, device.ip, WLED_UDP_PORT)
                        socket.send(packet)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to send DRGB packet", e)
                    }
                }

                val deadlineNs = t0 + frameNs
                val sleepNs = deadlineNs - System.nanoTime() - 1_000_000L
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

    private fun calculateLinkColor(): RGB {
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

        return hsvToRgb(senderCurrentHue, senderCurrentSat, finalBri)
    }

    private fun calculateAudioColor(l: Float, m: Float, h: Float): RGB {
        val cfg = LightingSettings
        val bc = BeatBus.beatCount
        if (bc != senderLastBeat) { 
            senderFlash = BeatBus.loudness
            senderLastBeat = bc 
        }

        if (senderFlash > 0.01f) {
            val trebleWeight = (h + m * 0.5f) / (l + m + h + 0.001f)
            val targetHue = AUDIO_HUE_BASS + (AUDIO_HUE_TREBLE - AUDIO_HUE_BASS) * trebleWeight
            senderCurrentHue += (targetHue - senderCurrentHue) * 0.3f
            senderCurrentSat += (SAT_AUDIO - senderCurrentSat) * 0.3f
        }

        senderFlash *= FLASH_DECAY
        // Shared curve: a dim base now tracks the mic energy (previously beat-only),
        // the beat punches on top — identical across all brands.
        val finalBri = cfg.audioBrightnessValue(l, m, h, senderFlash)

        return hsvToRgb(senderCurrentHue, senderCurrentSat, finalBri)
    }

    private fun hsvToRgb(h: Float, s: Float, v: Float): RGB {
        val hi = floor(h / 60f).toInt() % 6
        val f = (h / 60f) - floor(h / 60f)
        val p = v * (1 - s)
        val q = v * (1 - f * s)
        val t = v * (1 - (1 - f) * s)

        var r = 0f; var g = 0f; var b = 0f
        when (hi) {
            0 -> { r = v; g = t; b = p }
            1 -> { r = q; g = v; b = p }
            2 -> { r = p; g = v; b = t }
            3 -> { r = p; g = q; b = v }
            4 -> { r = t; g = p; b = v }
            5 -> { r = v; g = p; b = q }
        }
        return RGB((r * 255).toInt().coerceIn(0, 255), (g * 255).toInt().coerceIn(0, 255), (b * 255).toInt().coerceIn(0, 255))
    }
    
    private data class RGB(val r: Int, val g: Int, val b: Int)
}
