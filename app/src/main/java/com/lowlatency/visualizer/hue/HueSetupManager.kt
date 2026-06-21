package com.lowlatency.visualizer.hue

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager
import kotlin.concurrent.thread

/**
 * Orchestrates everything *except* the realtime UDP stream:
 *   - LAN bridge discovery (mDNS `_hue._tcp`, with the cloud N-UPnP endpoint as
 *     a fallback),
 *   - the link-button pairing poll loop that mints a `username` + `clientkey`
 *     (the DTLS PSK),
 *   - enumerating Entertainment Areas (CLIP v2),
 *   - starting / stopping the entertainment stream (CLIP v2 PUT).
 *
 * All network work runs on background threads; callbacks are posted to the main
 * thread.
 *
 * SECURITY NOTE: the bridge presents a self-signed TLS cert on the LAN. We trust
 * it for the *local control* channel (this is what the official Hue SDK does
 * after per-bridge pinning). The realtime light data is separately protected by
 * DTLS-PSK using the clientkey, so the sensitive secret never rides this channel
 * unauthenticated. Do not reuse this client for non-LAN traffic.
 */
class HueSetupManager(context: Context) {

    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val store = HueCredentialStore(context)

    private val http: OkHttpClient = buildLanTrustingClient()
    private val pingClient: OkHttpClient = http.newBuilder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(2, TimeUnit.SECONDS)
        .build()

    // ---------------------------------------------------------------- discovery

    /**
     * Discover bridges for up to [timeoutMs]. Results (deduped by IP) are posted
     * once on the main thread when discovery completes.
     */
    fun discoverBridges(timeoutMs: Long = 6_000, onResult: (List<HueBridge>) -> Unit) {
        val found = LinkedHashMap<String, HueBridge>()

        // --- mDNS via NsdManager ---
        val nsd = appContext.getSystemService(Context.NSD_SERVICE) as? NsdManager
        val discoveryListener = nsd?.let { manager ->
            object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(serviceType: String?) {}
                override fun onDiscoveryStopped(serviceType: String?) {}
                override fun onStartDiscoveryFailed(serviceType: String?, errorCode: Int) {}
                override fun onStopDiscoveryFailed(serviceType: String?, errorCode: Int) {}
                override fun onServiceLost(serviceInfo: NsdServiceInfo?) {}
                override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                    @Suppress("DEPRECATION")
                    manager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(s: NsdServiceInfo?, errorCode: Int) {}
                        override fun onServiceResolved(s: NsdServiceInfo) {
                            val ip = s.host?.hostAddress ?: return
                            synchronized(found) {
                                found.getOrPut(ip) { HueBridge(id = s.serviceName ?: ip, ip = ip) }
                            }
                        }
                    })
                }
            }
        }
        try {
            if (discoveryListener != null) {
                nsd.discoverServices("_hue._tcp.", NsdManager.PROTOCOL_DNS_SD, discoveryListener)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "mDNS discovery failed to start: ${t.message}")
        }

        // --- N-UPnP cloud fallback (needs internet; the bridge must have phoned home) ---
        thread(name = "hue-nupnp") {
            try {
                val req = Request.Builder().url("https://discovery.meethue.com").build()
                http.newCall(req).execute().use { resp ->
                    val bodyStr = resp.body?.string().orEmpty()
                    if (resp.isSuccessful && bodyStr.isNotBlank()) {
                        val arr = JSONArray(bodyStr)
                        for (i in 0 until arr.length()) {
                            val o = arr.getJSONObject(i)
                            val ip = o.optString("internalipaddress").ifBlank { null } ?: continue
                            val id = o.optString("id", ip)
                            synchronized(found) { found.getOrPut(ip) { HueBridge(id, ip) } }
                        }
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "N-UPnP discovery failed: ${t.message}")
            }
        }

        // Collect for the timeout window, then stop mDNS and report.
        main.postDelayed({
            try { if (discoveryListener != null) nsd.stopServiceDiscovery(discoveryListener) } catch (_: Throwable) {}
            onResult(synchronized(found) { found.values.toList() })
        }, timeoutMs)
    }

    // ------------------------------------------------------------------ pairing

    /**
     * Poll the bridge's create-user endpoint until the physical link button is
     * pressed (or [maxSeconds] elapse). On success, persists credentials.
     */
    fun pair(
        bridgeIp: String,
        maxSeconds: Int = 30,
        onCountdown: (secondsLeft: Int) -> Unit,
        onSuccess: (HueCredentials) -> Unit,
        onError: (String) -> Unit,
    ) {
        thread(name = "hue-pair") {
            val payload = JSONObject()
                .put("devicetype", "oscillux#android")
                .put("generateclientkey", true)
                .toString()
                .toRequestBody(JSON)

            var remaining = maxSeconds
            while (remaining > 0) {
                main.post { onCountdown(remaining) }
                try {
                    val req = Request.Builder()
                        .url("https://$bridgeIp/api")
                        .post(payload)
                        .build()
                    http.newCall(req).execute().use { resp ->
                        val arr = JSONArray(resp.body?.string().orEmpty())
                        val first = arr.optJSONObject(0)
                        val success = first?.optJSONObject("success")
                        val error = first?.optJSONObject("error")
                        when {
                            success != null -> {
                                val creds = HueCredentials(
                                    bridgeIp = bridgeIp,
                                    username = success.getString("username"),
                                    clientKey = success.getString("clientkey"),
                                )
                                store.saveCredentials(creds)
                                main.post { onSuccess(creds) }
                                return@thread
                            }
                            // 101 == link button not pressed yet → keep polling.
                            error != null && error.optInt("type") != 101 -> {
                                val desc = error.optString("description", "pairing error")
                                main.post { onError(desc) }
                                return@thread
                            }
                        }
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "pairing poll error: ${t.message}")
                }
                Thread.sleep(1_000)
                remaining--
            }
            main.post { onError("Timed out. Press the bridge button and try again.") }
        }
    }

    // ----------------------------------------------------------- entertainment

    /** Fetch the Entertainment Areas (CLIP v2). */
    fun listEntertainmentAreas(
        creds: HueCredentials,
        onResult: (List<HueEntertainmentArea>) -> Unit,
        onError: (String) -> Unit,
        onAuthError: (() -> Unit)? = null,
    ) {
        thread(name = "hue-areas") {
            try {
                val req = Request.Builder()
                    .url("https://${creds.bridgeIp}/clip/v2/resource/entertainment_configuration")
                    .header("hue-application-key", creds.username)
                    .get()
                    .build()
                http.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        if (resp.code in listOf(401, 403) && onAuthError != null) {
                            main.post { onAuthError() }
                        } else {
                            main.post { onError("HTTP ${resp.code}") }
                        }
                        return@thread
                    }
                    val root = JSONObject(resp.body?.string().orEmpty())
                    val data = root.optJSONArray("data") ?: JSONArray()
                    val areas = ArrayList<HueEntertainmentArea>(data.length())
                    for (i in 0 until data.length()) {
                        val o = data.getJSONObject(i)
                        val id = o.optString("id")
                        val name = o.optJSONObject("metadata")?.optString("name") ?: "Area ${i + 1}"
                        val chArr = o.optJSONArray("channels") ?: JSONArray()
                        val channels = ArrayList<HueChannel>(chArr.length())
                        for (c in 0 until chArr.length()) {
                            channels.add(HueChannel(chArr.getJSONObject(c).optInt("channel_id")))
                        }
                        val lsArr = o.optJSONArray("light_services") ?: JSONArray()
                        val lightIds = ArrayList<String>(lsArr.length())
                        for (ls in 0 until lsArr.length()) {
                            lsArr.getJSONObject(ls).optString("rid").takeIf { it.isNotBlank() }
                                ?.let { lightIds.add(it) }
                        }
                        areas.add(HueEntertainmentArea(id, name, channels, lightIds))
                    }
                    main.post { onResult(areas) }
                }
            } catch (t: Throwable) {
                main.post { onError(t.message ?: "area list failed") }
            }
        }
    }

    /** Start (or stop) the entertainment stream on [areaId] (CLIP v2). */
    fun setStreamActive(
        creds: HueCredentials,
        areaId: String,
        active: Boolean,
        onResult: (Boolean) -> Unit,
    ) {
        thread(name = "hue-stream-${if (active) "start" else "stop"}") {
            val ok = try {
                val body = JSONObject()
                    .put("action", if (active) "start" else "stop")
                    .toString().toRequestBody(JSON)
                val req = Request.Builder()
                    .url("https://${creds.bridgeIp}/clip/v2/resource/entertainment_configuration/$areaId")
                    .header("hue-application-key", creds.username)
                    .put(body)
                    .build()
                http.newCall(req).execute().use { it.isSuccessful }
            } catch (t: Throwable) {
                Log.w(TAG, "setStreamActive($active) failed: ${t.message}")
                false
            }
            main.post { onResult(ok) }
        }
    }

    /**
     * Synchronous (blocking) version of [setStreamActive] for use during app
     * shutdown (onDestroy/onStop). Returns true on success.
     */
    fun setStreamActiveSync(creds: HueCredentials, areaId: String, active: Boolean): Boolean {
        return try {
            val body = JSONObject()
                .put("action", if (active) "start" else "stop")
                .toString().toRequestBody(JSON)
            val req = Request.Builder()
                .url("https://${creds.bridgeIp}/clip/v2/resource/entertainment_configuration/$areaId")
                .header("hue-application-key", creds.username)
                .put(body)
                .build()
            http.newCall(req).execute().use { it.isSuccessful }
        } catch (t: Throwable) {
            Log.w(TAG, "setStreamActiveSync($active) failed: ${t.message}")
            false
        }
    }

    /**
     * Set light state for all lights in [lightIds] via CLIP v2 REST.
     * Pass only the properties you want to change; omitted ones stay as-is.
     */
    fun controlLights(
        creds: HueCredentials,
        lightIds: List<String>,
        on: Boolean? = null,
        brightness: Int? = null,
        mirek: Int? = null,
        x: Double? = null,
        y: Double? = null,
    ) {
        thread(name = "hue-control") {
            val body = JSONObject()
            if (on != null) body.put("on", JSONObject().put("on", on))
            if (brightness != null) body.put("dimming", JSONObject().put("brightness", brightness))
            if (mirek != null) body.put("color_temperature", JSONObject().put("mirek", mirek))
            if (x != null && y != null) body.put("color", JSONObject().put("xy", JSONObject().put("x", x).put("y", y)))
            val bodyStr = body.toString()
            for (id in lightIds) {
                try {
                    val req = Request.Builder()
                        .url("https://${creds.bridgeIp}/clip/v2/resource/light/$id")
                        .header("hue-application-key", creds.username)
                        .put(bodyStr.toRequestBody(JSON))
                        .build()
                    pingClient.newCall(req).execute().close()
                } catch (_: Throwable) {}
            }
        }
    }

    /**
     * Quick reachability check + round-trip latency measurement.  Hits the
     * bridge's CLIP v2 root (lightweight JSON) and returns the wall-clock RTT
     * in milliseconds, or null if the bridge is unreachable.
     */
    fun pingBridge(creds: HueCredentials, onResult: (rttMs: Long?) -> Unit) {
        thread(name = "hue-ping") {
            val rtt = try {
                val req = Request.Builder()
                    .url("https://${creds.bridgeIp}/clip/v2/resource/bridge")
                    .header("hue-application-key", creds.username)
                    .get().build()
                val t0 = System.nanoTime()
                pingClient.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) (System.nanoTime() - t0) / 1_000_000L else null
                }
            } catch (_: Throwable) { null }
            main.post { onResult(rtt) }
        }
    }

    /**
     * Release HTTP client resources. Call on app destruction.
     */
    fun shutdown() {
        try {
            http.dispatcher.executorService.shutdown()
            http.connectionPool.evictAll()
        } catch (t: Throwable) {
            Log.w(TAG, "shutdown failed: ${t.message}")
        }
    }

    // --------------------------------------------------------------- internals

    private fun buildLanTrustingClient(): OkHttpClient {
        val trustAll = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        }
        val ssl = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf(trustAll), SecureRandom())
        }
        return OkHttpClient.Builder()
            .sslSocketFactory(ssl.socketFactory, trustAll)
            .hostnameVerifier { _, _ -> true }   // self-signed LAN cert; CN is the bridge id
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    companion object {
        private const val TAG = "HueSetupManager"
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
