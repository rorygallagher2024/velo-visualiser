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
import java.io.OutputStreamWriter
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
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

    /** One panel seen on the network during the current search. */
    private data class Discovered(val id: String, val host: String, val port: Int)

    // Written from the NSD resolve callback, read when the search settles on the
    // main thread — and an in-flight resolve can still land after discovery stops,
    // so iteration here has to tolerate a concurrent put.
    private val found = ConcurrentHashMap<String, Discovered>()
    private val settle = Runnable { finishSearch() }

    fun search(silent: Boolean = false) {
        if (isSearching) return
        if (!silent) currentState = State.SEARCHING
        isSearching = true
        found.clear()

        val nsdManager = appContext.getSystemService(Context.NSD_SERVICE) as NsdManager
        discoveryListener?.let {
            try { nsdManager.stopServiceDiscovery(it) } catch (_: Exception) {}
        }
        try {
            nsdManager.discoverServices(
                "_nanoleafapi._tcp",
                NsdManager.PROTOCOL_DNS_SD,
                newDiscoveryListener(nsdManager).also { discoveryListener = it }
            )
            // Hear out the whole network before deciding. Acting on the first panel
            // to answer made the choice an mDNS race, so a second device could win
            // and the app would try to re-pair away from the one you set up.
            mainHandler.removeCallbacks(settle)
            mainHandler.postDelayed(settle, SETTLE_MS)
        } catch (e: Exception) {
            isSearching = false
        }
    }

    private fun stopDiscovery() {
        mainHandler.removeCallbacks(settle)
        if (!isSearching) return
        isSearching = false
        val nsdManager = appContext.getSystemService(Context.NSD_SERVICE) as NsdManager
        discoveryListener?.let {
            try { nsdManager.stopServiceDiscovery(it) } catch (_: Exception) {}
        }
        discoveryListener = null
    }

    private fun newDiscoveryListener(nsdManager: NsdManager) =
        object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {}
            override fun onServiceFound(service: NsdServiceInfo) {
                nsdManager.resolveService(service, newResolveListener(nsdManager))
            }
            override fun onServiceLost(service: NsdServiceInfo) {}
            override fun onDiscoveryStopped(serviceType: String) { isSearching = false }
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) { isSearching = false }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) { isSearching = false }
        }

    private fun newResolveListener(nsdManager: NsdManager) =
        object : NsdManager.ResolveListener {
            override fun onResolveFailed(srv: NsdServiceInfo, errorCode: Int) {}
            override fun onServiceResolved(srv: NsdServiceInfo) {
                val host = srv.host?.hostAddress ?: return
                val id = deviceIdOf(srv)
                found[id] = Discovered(id, host, srv.port)
                // Our own panel answering settles it; no reason to wait out the window.
                val creds = store.loadCredentials()
                if (creds != null && creds.deviceId.isNotEmpty() && creds.deviceId == id) {
                    mainHandler.post { finishSearch() }
                }
            }
        }

    /**
     * A panel's stable identity. Nanoleaf advertises an `id` TXT record; the mDNS
     * service name is the fallback. Either survives a DHCP lease change, which the
     * IP address does not.
     */
    private fun deviceIdOf(srv: NsdServiceInfo): String {
        val txt = try {
            srv.attributes["id"]?.toString(Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
        return txt?.takeIf { it.isNotBlank() } ?: srv.serviceName.orEmpty()
    }

    private fun finishSearch() {
        stopDiscovery()
        reconcile()
    }

    /** Decide, once, against everything the network offered. */
    private fun reconcile() {
        val creds = store.loadCredentials()
        if (creds == null) {
            // Nothing paired yet. Lowest id wins purely so repeat searches agree with
            // each other; picking between several is the multi-device UI's job.
            found.values.minByOrNull { it.id }?.let { pairWith(it) }
            return
        }
        if (creds.deviceId.isNotEmpty()) {
            val mine = found[creds.deviceId]
            // Any other panel on the network is somebody else's business. Never
            // re-pair over an existing pairing just because a stranger answered.
            if (mine != null) adopt(creds, mine) else if (found.isNotEmpty()) {
                currentState = State.UNREACHABLE
            }
            return
        }
        val candidates = found.values.toList()
        if (candidates.isNotEmpty()) thread { claimLegacyPairing(creds, candidates) }
    }

    /** Re-home a known panel: same token, whatever address it turned up on. */
    private fun adopt(creds: NanoleafCredentials, d: Discovered) {
        discoveredIp = d.host
        discoveredPort = d.port
        if (creds.ip != d.host || creds.port != d.port || creds.deviceId != d.id) {
            store.saveCredentials(NanoleafCredentials(d.host, creds.authToken, d.port, d.id))
        }
        currentState = State.PAIRED
    }

    /**
     * Off-thread: a pairing predating device ids. The token itself says which panel
     * it belongs to, so try it against each until one accepts, then record the id
     * so this never has to be guessed again.
     */
    private fun claimLegacyPairing(creds: NanoleafCredentials, candidates: List<Discovered>) {
        for (d in candidates) {
            if (tokenAccepted(d, creds.authToken)) {
                mainHandler.post { adopt(creds, d) }
                return
            }
        }
        // No panel accepted it. Re-pair only when there is exactly one candidate and
        // so no ambiguity about what "re-pair" means; guessing among several is the
        // behaviour that let the app wander onto the wrong device.
        if (candidates.size == 1) {
            mainHandler.post { pairWith(candidates[0]) }
        } else {
            currentState = State.UNREACHABLE
        }
    }

    private fun tokenAccepted(d: Discovered, token: String): Boolean = try {
        val conn = URL("http://${d.host}:${d.port}/api/v1/$token/")
            .openConnection() as HttpURLConnection
        conn.connectTimeout = 2000
        conn.readTimeout = 2000
        val ok = conn.responseCode == 200
        conn.disconnect()
        ok
    } catch (_: Exception) {
        false
    }

    private fun pairWith(d: Discovered) {
        discoveredIp = d.host
        discoveredPort = d.port
        pairingDeviceId = d.id
        currentState = State.FOUND_UNPAIRED
        startAutoPairing()
    }

    private var autoPairing = false
    private var pairingDeviceId = ""

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

                        store.saveCredentials(
                            NanoleafCredentials(ip, token, discoveredPort, pairingDeviceId)
                        )
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

    fun setStaticState(on: Boolean, bri: Float? = null, hue: Float? = null, sat: Float? = null) {
        val creds = store.loadCredentials() ?: return
        thread {
            try {
                val url = URL("http://${creds.ip}:${creds.port}/api/v1/${creds.authToken}/state")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "PUT"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.doOutput = true
                
                val body = JSONObject()
                body.put("on", JSONObject().put("value", on))
                
                bri?.let { body.put("brightness", JSONObject().put("value", (it * 100).toInt().coerceIn(1, 100))) }
                hue?.let { body.put("hue", JSONObject().put("value", it.toInt().coerceIn(0, 360))) }
                sat?.let { body.put("sat", JSONObject().put("value", (it * 100).toInt().coerceIn(0, 100))) }
                
                val writer = OutputStreamWriter(conn.outputStream)
                writer.write(body.toString())
                writer.flush()
                writer.close()
                
                conn.responseCode
                conn.disconnect()
            } catch (_: Exception) {}
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
        pairingDeviceId = ""
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

    companion object {
        /** How long to keep listening before deciding which panel is ours. */
        private const val SETTLE_MS = 2500L
    }
}
