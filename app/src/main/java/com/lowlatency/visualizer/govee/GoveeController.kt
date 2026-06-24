package com.lowlatency.visualizer.govee

import android.graphics.Color
import android.util.Log
import com.lowlatency.visualizer.BeatBus
import com.lowlatency.visualizer.HueStrobeSettings
import com.lowlatency.visualizer.LinkSync
import com.lowlatency.visualizer.NativeBridge
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress

data class GoveeBulb(val ip: String, val device: String) {
    var isSelected: Boolean = false

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is GoveeBulb) return false
        return ip == other.ip
    }

    override fun hashCode(): Int = ip.hashCode()
}

class GoveeController {

    private val bulbs = mutableListOf<GoveeBulb>()
    fun hasSelectedBulbs(): Boolean = synchronized(bulbs) { bulbs.any { it.isSelected } }

    @Volatile private var streaming = false
    val isStreaming: Boolean get() = streaming
    private var senderThread: Thread? = null

    // Audio-reactive state
    private var senderLastBeat: Int = 0
    private var senderFlash: Float = 0f
    private var senderCurrentHue: Float = 0f
    private var senderCurrentSat: Float = 0f
    
    @Volatile private var linkBeatCount = 0L
    private var senderLastLinkBeat = 0L
    private var senderLinkBeatFired = false

    companion object {
        private const val TAG = "GoveeController"
        private const val FLASH_DECAY = 0.85f
        private const val MIN_BRIGHT = 0.05f
        
        // Link constants
        private const val RED_HUE = 350f
        private const val PURPLE_HUE = 280f
        private const val SAT_BASS = 0.95f
        private const val SAT_TREBLE = 0.85f
        private const val MIN_BEAT_AMP = 0.6f

        // Audio constants
        private const val AUDIO_HUE_BASS = 280f   // Purple
        private const val AUDIO_HUE_TREBLE = 180f // Cyan
        private const val AUDIO_SAT = 0.95f
        
        private const val MULTICAST_IP = "239.255.255.250"
        private const val MULTICAST_PORT = 4001
        private const val DISCOVERY_LISTEN_PORT = 4002
        private const val CONTROL_PORT = 4003

        private const val TARGET_FPS = 40
        private const val FRAME_NS = 1_000_000_000L / TARGET_FPS
        private const val SPIN_MARGIN_NS = 2_000_000L
    }

    fun startDiscovery(onBulbFound: (GoveeBulb) -> Unit, onFinished: () -> Unit) {
        Thread {
            try {
                val socket = DatagramSocket(null)
                socket.reuseAddress = true
                socket.bind(InetSocketAddress(DISCOVERY_LISTEN_PORT))
                socket.soTimeout = 1500

                val scanJson = JSONObject().apply {
                    put("msg", JSONObject().apply {
                        put("cmd", "scan")
                        put("data", JSONObject().apply {
                            put("account_topic", "reserve")
                        })
                    })
                }
                
                val txBytes = scanJson.toString().toByteArray()
                val targetAddr = InetAddress.getByName(MULTICAST_IP)
                val txPacket = DatagramPacket(txBytes, txBytes.size, targetAddr, MULTICAST_PORT)

                val rxBytes = ByteArray(1024)
                val rxPacket = DatagramPacket(rxBytes, rxBytes.size)

                for (attempt in 0 until 3) {
                    Log.v(TAG, "Discovery attempt $attempt")
                    try {
                        socket.send(txPacket)
                    } catch (e: Exception) {
                        Log.v(TAG, "Suppressed send error: ${e.message}")
                    }

                    val endOfWindow = System.currentTimeMillis() + 1000L
                    while (true) {
                        val remain = endOfWindow - System.currentTimeMillis()
                        if (remain <= 0) break
                        socket.soTimeout = remain.toInt()
                        try {
                            socket.receive(rxPacket)
                            val jsonString = String(rxPacket.data, 0, rxPacket.length)
                            val json = JSONObject(jsonString)
                            if (json.has("msg")) {
                                val msg = json.getJSONObject("msg")
                                if (msg.optString("cmd") == "scan") {
                                    val data = msg.getJSONObject("data")
                                    val ip = data.getString("ip")
                                    val device = data.getString("device")
                                    val bulb = GoveeBulb(ip, device)
                                    synchronized(bulbs) {
                                        if (!bulbs.contains(bulb)) {
                                            bulbs.add(bulb)
                                            onBulbFound(bulb)
                                        }
                                    }
                                }
                            }
                        } catch (e: java.net.SocketTimeoutException) {
                            break
                        } catch (e: Exception) {
                            Log.v(TAG, "Malformed packet: ${e.message}")
                        }
                    }
                }
                socket.close()
            } catch (e: Exception) {
                Log.e(TAG, "Discovery error: ${e.message}")
            }
            onFinished()
        }.start()
    }

    fun getDiscoveredBulbs(): List<GoveeBulb> = synchronized(bulbs) { bulbs.toList() }

    fun setBulbSelected(ip: String, selected: Boolean) {
        val bulb = synchronized(bulbs) { bulbs.find { it.ip == ip } }
        if (bulb != null && !selected && streaming) {
            Thread {
                sendSetPower(null, bulb.ip, false)
            }.start()
        }
        bulb?.isSelected = selected
    }

    fun enableStreaming() {
        if (streaming) return
        streaming = true
        
        synchronized(bulbs) {
            val selectedBulbs = bulbs.filter { it.isSelected }
            Thread {
                val s = DatagramSocket()
                for (bulb in selectedBulbs) {
                    sendSetPower(s, bulb.ip, true)
                }
                s.close()
            }.start()
        }
        
        senderLastLinkBeat = linkBeatCount
        senderLinkBeatFired = false
        startSender()
    }

    fun disableStreaming() {
        streaming = false
        senderThread?.interrupt()
        senderThread = null
        
        Thread {
            try {
                val socket = DatagramSocket()
                val selectedBulbs = synchronized(bulbs) { bulbs.filter { it.isSelected } }
                for (bulb in selectedBulbs) {
                    sendSetPower(socket, bulb.ip, false)
                }
                socket.close()
            } catch (e: Exception) {
                Log.v(TAG, "Suppressed: ${e.message}")
            }
        }.start()
    }

    fun onBands(low: Float, mid: Float, high: Float) {
        l = low; m = mid; h = high
    }
    
    fun onLinkBeat() {
        linkBeatCount++
    }
    
    @Volatile private var l = 0f
    @Volatile private var m = 0f
    @Volatile private var h = 0f

    private data class GoveeColor(val r: Int, val g: Int, val b: Int)

    private fun startSender() {
        senderThread = Thread {
            val socket = DatagramSocket()
            while (streaming) {
                val t0 = System.nanoTime()
                
                val isLinkOn = LinkSync.enabled
                
                val finalColor = if (isLinkOn) {
                    calculateLinkColor()
                } else {
                    calculateAudioColor(l, m, h)
                }

                val activeBulbs = synchronized(bulbs) { bulbs.filter { it.isSelected } }

                for (bulb in activeBulbs) {
                    sendSetColor(socket, bulb, finalColor.r, finalColor.g, finalColor.b)
                }

                val deadlineNs = t0 + FRAME_NS
                val sleepNs = deadlineNs - System.nanoTime() - SPIN_MARGIN_NS
                if (sleepNs > 0) {
                    try { Thread.sleep(sleepNs / 1_000_000, (sleepNs % 1_000_000).toInt()) }
                    catch (e: InterruptedException) { 
                        Log.v(TAG, "Interrupted: ${e.message}")
                        break 
                    }
                }
                while (System.nanoTime() < deadlineNs) Thread.yield()
            }
            socket.close()
        }
        senderThread?.start()
    }

    private fun calculateLinkColor(): GoveeColor {
        val cfg = HueStrobeSettings
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
                senderFlash = MIN_BEAT_AMP + (1f - MIN_BEAT_AMP) * BeatBus.loudness
            }
        }
        senderFlash *= FLASH_DECAY
        val finalBri = kotlin.math.sqrt((MIN_BRIGHT + cfg.restingGlow * 0.3f + senderFlash).coerceIn(0f, 1f))
        
        return hsvToRgb(senderCurrentHue, senderCurrentSat, finalBri)
    }

    private fun calculateAudioColor(l: Float, m: Float, h: Float): GoveeColor {
        val cfg = HueStrobeSettings
        val bc = BeatBus.beatCount
        if (bc != senderLastBeat) { 
            senderFlash = BeatBus.loudness
            senderLastBeat = bc 
        }
        senderFlash *= FLASH_DECAY
        
        val total = l + m + h + 1e-3f
        val centroid = ((m * 0.5f + h) / total).coerceIn(0f, 1f)
        val energy = (kotlin.math.max(l, kotlin.math.max(m, h)) * cfg.audioBrightMul).coerceIn(0f, 1f)
        val finalBri = MIN_BRIGHT + (1f - MIN_BRIGHT) * kotlin.math.sqrt((energy + senderFlash * 0.7f).coerceIn(0f, 1f))
        val finalSat = (AUDIO_SAT - senderFlash * 0.30f).coerceIn(0.6f, 1f)
        val finalHue = AUDIO_HUE_BASS + (AUDIO_HUE_TREBLE - AUDIO_HUE_BASS) * centroid
        
        return hsvToRgb(finalHue, finalSat, finalBri)
    }

    private fun hsvToRgb(h: Float, s: Float, v: Float): GoveeColor {
        val hsv = floatArrayOf(h, s, v)
        val color = Color.HSVToColor(hsv)
        val r = (color shr 16) and 0xFF
        val g = (color shr 8) and 0xFF
        val b = color and 0xFF
        return GoveeColor(r, g, b)
    }

    private fun sendSetPower(socket: DatagramSocket?, ip: String, isOn: Boolean) {
        try {
            val s = socket ?: DatagramSocket()
            val turnJson = JSONObject().apply {
                put("msg", JSONObject().apply {
                    put("cmd", "turn")
                    put("data", JSONObject().apply {
                        put("value", if (isOn) 1 else 0)
                    })
                })
            }
            val bytes = turnJson.toString().toByteArray()
            s.send(DatagramPacket(bytes, bytes.size, InetAddress.getByName(ip), CONTROL_PORT))
            if (socket == null) s.close()
        } catch (e: Exception) {
            Log.v(TAG, "Suppressed: ${e.message}")
        }
    }

    private fun sendSetColor(socket: DatagramSocket, bulb: GoveeBulb, r: Int, g: Int, b: Int) {
        try {
            val colorJson = JSONObject().apply {
                put("msg", JSONObject().apply {
                    put("cmd", "colorwc")
                    put("data", JSONObject().apply {
                        put("color", JSONObject().apply {
                            put("r", r)
                            put("g", g)
                            put("b", b)
                        })
                        put("colorTemInKelvin", 0)
                    })
                })
            }
            val bytes = colorJson.toString().toByteArray()
            socket.send(DatagramPacket(bytes, bytes.size, InetAddress.getByName(bulb.ip), CONTROL_PORT))
        } catch (e: Exception) {
            Log.v(TAG, "Dropped SetColor packet: ${e.message}")
        }
    }
}
