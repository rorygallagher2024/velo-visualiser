package com.lowlatency.visualizer.nanoleaf

import android.content.Context
import android.graphics.Color
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.lowlatency.visualizer.BeatBus
import com.lowlatency.visualizer.LightingSettings
import com.lowlatency.visualizer.LinkSync
import com.lowlatency.visualizer.NativeBridge
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import kotlin.concurrent.thread

class NanoleafController(context: Context) {

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val store = NanoleafCredentialStore(appContext)

    var stateListener: ((State) -> Unit)? = null
        set(value) {
            field = value
            value?.invoke(currentState)
        }

    var currentState = State.DISCONNECTED
        private set(value) {
            field = value
            mainHandler.post { stateListener?.invoke(value) }
        }

    enum class State {
        DISCONNECTED,
        SEARCHING,
        FOUND_UNPAIRED,
        PAIRING,
        PAIRED,
        STREAMING,
        UNREACHABLE,
        ERROR
    }

    private var discoveredIp: String? = null
    private var discoveredPort: Int = 16021
    private var panelIds = mutableListOf<Int>()

    // Audio/Link Sync Data
    @Volatile private var low = 0f
    @Volatile private var mid = 0f
    @Volatile private var high = 0f
    @Volatile private var linkBeatCount = 0

    private var senderThread: Thread? = null
    @Volatile private var running = false
    private var socket: DatagramSocket? = null

    init {
        val creds = store.loadCredentials()
        if (creds != null) {
            discoveredIp = creds.ip
            discoveredPort = creds.port
            // We assume pairing is lost until proven reachable
            currentState = State.UNREACHABLE
            checkReachability()
            
            if (store.syncEnabled) {
                startSync()
            }
        }
    }

    private val reachabilityPoller = object : Runnable {
        override fun run() {
            checkReachability()
            mainHandler.postDelayed(this, 5000L)
        }
    }

    private var isPollingReachability = false

    fun startReachabilityPoller() {
        if (isPollingReachability) return
        isPollingReachability = true
        mainHandler.post(reachabilityPoller)
    }

    fun stopReachabilityPoller() {
        isPollingReachability = false
        mainHandler.removeCallbacks(reachabilityPoller)
    }

    fun onBands(low: Float, mid: Float, high: Float) {
        this.low = low
        this.mid = mid
        this.high = high
    }

    fun onLinkBeat() {
        linkBeatCount++
    }

    private var isSearching = false
    private var discoveryListener: NsdManager.DiscoveryListener? = null

    fun search(silent: Boolean = false) {
        if (isSearching) return
        if (!silent) currentState = State.SEARCHING
        isSearching = true

        val nsdManager = appContext.getSystemService(Context.NSD_SERVICE) as NsdManager
        discoveryListener?.let { 
            try { nsdManager.stopServiceDiscovery(it) } catch (_: Exception) {} 
        }
        nsdManager.discoverServices(
            "_nanoleafapi._tcp",
            NsdManager.PROTOCOL_DNS_SD,
            object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(regType: String) {}
                override fun onServiceFound(service: NsdServiceInfo) {
                    nsdManager.resolveService(service, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(srv: NsdServiceInfo, errorCode: Int) {}
                        override fun onServiceResolved(srv: NsdServiceInfo) {
                            val host = srv.host.hostAddress ?: return
                            discoveredIp = host
                            discoveredPort = srv.port
                            nsdManager.stopServiceDiscovery(this@NanoleafController.discoveryListener)
                            
                            val creds = store.loadCredentials()
                            if (creds != null) {
                                if (creds.ip == host && creds.port == srv.port) {
                                    currentState = State.PAIRED
                                } else {
                                    thread {
                                        try {
                                            val url = URL("http://$host:${srv.port}/api/v1/${creds.authToken}/")
                                            val conn = url.openConnection() as HttpURLConnection
                                            conn.connectTimeout = 2000
                                            conn.readTimeout = 2000
                                            val code = conn.responseCode
                                            if (code == 200) {
                                                store.saveCredentials(NanoleafCredentials(host, creds.authToken, srv.port))
                                                currentState = State.PAIRED
                                            } else if (code == 401 || code == 403) {
                                                currentState = State.FOUND_UNPAIRED
                                                startAutoPairing()
                                            } else {
                                                currentState = State.UNREACHABLE
                                            }
                                            conn.disconnect()
                                        } catch (_: Exception) {
                                            currentState = State.UNREACHABLE
                                        }
                                    }
                                }
                            } else {
                                currentState = State.FOUND_UNPAIRED
                                startAutoPairing()
                            }
                        }
                    })
                }
                override fun onServiceLost(service: NsdServiceInfo) {}
                override fun onDiscoveryStopped(serviceType: String) { isSearching = false }
                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) { isSearching = false }
                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) { isSearching = false }
            }.also { discoveryListener = it }
        )
    }

    private var autoPairing = false

    private fun startAutoPairing() {
        if (autoPairing) return
        val ip = discoveredIp ?: return
        autoPairing = true

        thread {
            while (autoPairing && currentState == State.FOUND_UNPAIRED) {
                try {
                    val url = URL("http://$ip:$discoveredPort/api/v1/new")
                    val conn = url.openConnection() as HttpURLConnection
                    conn.requestMethod = "POST"
                    conn.connectTimeout = 2000
                    conn.readTimeout = 2000

                    if (conn.responseCode == 200 || conn.responseCode == 201) {
                        val reader = BufferedReader(InputStreamReader(conn.inputStream))
                        val response = reader.readText()
                        reader.close()

                        val json = JSONObject(response)
                        val token = json.getString("auth_token")

                        store.saveCredentials(NanoleafCredentials(ip, token, discoveredPort))
                        currentState = State.PAIRED
                        autoPairing = false
                    }
                    conn.disconnect()
                } catch (_: Exception) {
                    // Ignore errors during auto-polling
                }
                
                if (autoPairing) {
                    Thread.sleep(2000)
                }
            }
            autoPairing = false
        }
    }

    fun checkReachability() {
        val creds = store.loadCredentials()
        if (creds != null) {
            thread {
                try {
                    val url = URL("http://${creds.ip}:${creds.port}/api/v1/${creds.authToken}/")
                    val conn = url.openConnection() as HttpURLConnection
                    conn.connectTimeout = 2000
                    conn.readTimeout = 2000
                    if (conn.responseCode == 200) {
                        if (currentState == State.DISCONNECTED || currentState == State.UNREACHABLE) {
                            currentState = State.PAIRED
                        }
                    } else {
                        if (currentState == State.PAIRED || currentState == State.STREAMING) {
                            val wasStreaming = currentState == State.STREAMING
                            currentState = State.UNREACHABLE
                            if (wasStreaming) stopSync()
                            search(silent = true)
                        }
                    }
                    conn.disconnect()
                } catch (_: Exception) {
                    if (currentState == State.PAIRED || currentState == State.STREAMING) {
                        val wasStreaming = currentState == State.STREAMING
                        currentState = State.UNREACHABLE
                        if (wasStreaming) stopSync()
                        search(silent = true)
                    }
                }
            }
        } else {
            currentState = State.DISCONNECTED
        }
    }

    fun startSync() {
        val creds = store.loadCredentials() ?: return
        if (running) return
        running = true

        thread {
            try {
                // 1. Fetch Panel Layout
                val url = URL("http://${creds.ip}:${creds.port}/api/v1/${creds.authToken}/")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                
                val reader = BufferedReader(InputStreamReader(conn.inputStream))
                val response = reader.readText()
                reader.close()
                conn.disconnect()

                val json = JSONObject(response)
                val posData = json.getJSONObject("panelLayout").getJSONObject("layout").getJSONArray("positionData")
                
                panelIds.clear()
                for (i in 0 until posData.length()) {
                    val pId = posData.getJSONObject(i).getInt("panelId")
                    if (pId != 0) { // Ignore controller panel if it's 0
                        panelIds.add(pId)
                    }
                }

                if (panelIds.isEmpty()) {
                    currentState = State.ERROR
                    running = false
                    return@thread
                }

                // 2. Enable ExtControl V2
                val putUrl = URL("http://${creds.ip}:${creds.port}/api/v1/${creds.authToken}/effects")
                val putConn = putUrl.openConnection() as HttpURLConnection
                putConn.requestMethod = "PUT"
                putConn.setRequestProperty("Content-Type", "application/json")
                putConn.doOutput = true

                val payload = JSONObject().apply {
                    put("write", JSONObject().apply {
                        put("command", "display")
                        put("animType", "extControl")
                        put("extControlVersion", "v2")
                    })
                }

                putConn.outputStream.write(payload.toString().toByteArray())
                val responseCode = putConn.responseCode
                var udpPort = 60222
                
                if (responseCode == 200 || responseCode == 204) {
                    try {
                        if (responseCode == 200) {
                            val putResp = putConn.inputStream.bufferedReader().readText()
                            val putJson = JSONObject(putResp)
                            if (putJson.has("streamControlPort")) {
                                udpPort = putJson.getInt("streamControlPort")
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("Nanoleaf", "Failed to parse extControl response: ${e.message}")
                    }
                } else {
                    val errResp = putConn.errorStream?.bufferedReader()?.readText()
                    Log.e("Nanoleaf", "Failed to start extControl: $responseCode - $errResp")
                    currentState = State.ERROR
                    running = false
                    return@thread
                }
                putConn.disconnect()

                // 3. Start UDP Streaming
                store.syncEnabled = true
                currentState = State.STREAMING
                socket = DatagramSocket()
                val targetIp = InetAddress.getByName(creds.ip)

                senderThread = thread {
                    streamLoop(targetIp, udpPort)
                }

            } catch (e: Exception) {
                Log.e("Nanoleaf", "Start sync failed: ${e.message}")
                currentState = State.ERROR
                running = false
            }
        }
    }

    fun stopSync() {
        running = false
        store.syncEnabled = false
        senderThread?.join(1000)
        senderThread = null
        socket?.close()
        socket = null
        
        if (currentState == State.STREAMING) {
            currentState = State.PAIRED
        }

        val creds = store.loadCredentials()
        if (creds != null) {
            thread {
                try {
                    val url = URL("http://${creds.ip}:${creds.port}/api/v1/${creds.authToken}/state")
                    val conn = url.openConnection() as HttpURLConnection
                    conn.requestMethod = "PUT"
                    conn.setRequestProperty("Content-Type", "application/json")
                    conn.doOutput = true
                    val payload = JSONObject().apply {
                        put("on", JSONObject().apply {
                            put("value", false)
                        })
                    }
                    conn.outputStream.write(payload.toString().toByteArray())
                    conn.responseCode // execute
                    conn.disconnect()
                } catch (_: Exception) {
                }
            }
        }
    }

    fun forget() {
        stopSync()
        store.clear()
        discoveredIp = null
        currentState = State.DISCONNECTED
    }

    // --- Audio to Color mapping ---

    private val AUDIO_HUE_BASS = 280f
    private val AUDIO_HUE_TREBLE = 190f
    private val SAT_AUDIO = 0.9f
    private val FLASH_DECAY = 0.85f   // brightness floor / glow now shared in LightingSettings

    private var senderFlash = 0f
    private var senderLastBeat = -1L
    private var senderCurrentHue = AUDIO_HUE_BASS
    private var senderCurrentSat = SAT_AUDIO

    data class RGB(val r: Int, val g: Int, val b: Int)

    private fun hsvToRgb(h: Float, s: Float, v: Float): RGB {
        val color = Color.HSVToColor(floatArrayOf(h, s, v))
        return RGB((color shr 16) and 0xFF, (color shr 8) and 0xFF, color and 0xFF)
    }

    private fun calculateAudioColor(l: Float, m: Float, h: Float): RGB {
        val cfg = LightingSettings
        val bc = BeatBus.beatCount.toLong()
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
    private val PURPLE_HUE = 280f

    private fun calculateLinkColor(): RGB {
        val cfg = LightingSettings
        val phase = NativeBridge.nativeLinkBeatPhase().toFloat()
        
        val isLinkOnBeat = phase < 0.1f
        val currentBeat = linkBeatCount

        if (cfg.linkBeatFlashEnabled && BeatBus.gateOpen) {
            if (isLinkOnBeat && currentBeat.toLong() != senderLastBeat) {
                senderFlash = cfg.beatFlashAmp(BeatBus.loudness)
                senderLastBeat = currentBeat.toLong()
            }
        }

        val hue = PURPLE_HUE
        val activeFlash = if (cfg.linkBeatFlashEnabled) senderFlash else 0.5f

        val bri = cfg.linkBrightnessValue(activeFlash)
        senderFlash *= FLASH_DECAY

        return hsvToRgb(hue, 0.9f, bri)
    }

    private fun streamLoop(ip: InetAddress, port: Int) {
        val numPanels = panelIds.size
        // V2 Header: [NumPanels (2 bytes)]
        // V2 Panel: [PanelID (2 bytes)] [R] [G] [B] [W] [TransitionTime (2 bytes)]
        // Total buffer size: 2 + (numPanels * 8)
        val buf = ByteArray(2 + (numPanels * 8))

        while (running) {
            val isLinkOn = LinkSync.enabled
            val finalColor = if (isLinkOn) calculateLinkColor() else calculateAudioColor(low, mid, high)

            val r = finalColor.r.toByte()
            val g = finalColor.g.toByte()
            val b = finalColor.b.toByte()
            val w = 0.toByte()
            // Transition time in 100ms units. 1 = 100ms. 0 = instant.
            val tH = 0.toByte()
            val tL = 1.toByte()

            buf[0] = (numPanels shr 8).toByte()
            buf[1] = (numPanels and 0xFF).toByte()

            var offset = 2
            for (pId in panelIds) {
                buf[offset++] = (pId shr 8).toByte()
                buf[offset++] = (pId and 0xFF).toByte()
                buf[offset++] = r
                buf[offset++] = g
                buf[offset++] = b
                buf[offset++] = w
                buf[offset++] = tH
                buf[offset++] = tL
            }

            try {
                val packet = DatagramPacket(buf, buf.size, ip, port)
                socket?.send(packet)
            } catch (_: Exception) {
            }

            Thread.sleep(25) // ~40 FPS
        }
    }
}
