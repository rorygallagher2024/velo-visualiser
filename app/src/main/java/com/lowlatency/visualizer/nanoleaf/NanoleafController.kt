package com.lowlatency.visualizer.nanoleaf

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

/**
 * Every paired Nanoleaf device at once: one discovery pass, one colour engine
 * ([NanoleafColorEngine]), one sender thread fanning UDP frames out to N panels.
 * Devices are identified by their mDNS id (an IP is just a lease), and pairing
 * needs no picker UI because the physical button *is* the selector —
 * `/api/v1/new` only answers on the device whose pairing window the user is
 * holding open, so we poll every unpaired panel found and whichever one the user
 * walks up to claims the slot.
 */
class NanoleafController(context: Context) {

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val store = NanoleafCredentialStore(appContext)
    private val colours = NanoleafColorEngine()

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

    /** One paired device's runtime state; the credentials are the durable part. */
    private class Session(@Volatile var creds: NanoleafCredentials) {
        @Volatile var reachable = false
        @Volatile var address: InetAddress? = null
        @Volatile var udpPort = NanoleafApi.DEFAULT_UDP_PORT
        val panelIds = mutableListOf<Int>()
    }

    /** What the UI needs to draw one row of the device list. */
    data class DeviceInfo(
        val key: String,
        val label: String,
        val reachable: Boolean,
        val streaming: Boolean,
    )

    // Keyed by NanoleafCredentials.key. Mutated on the main thread except for
    // pairing/claim completions, hence concurrent.
    private val sessions = ConcurrentHashMap<String, Session>()
    @Volatile private var streamTargets: List<Session> = emptyList()

    private var senderThread: Thread? = null
    @Volatile private var running = false
    private var socket: DatagramSocket? = null

    init {
        reloadSessions()
        if (sessions.isNotEmpty()) {
            // Assume pairings are lost until proven reachable.
            currentState = State.UNREACHABLE
            checkReachability()
            if (store.syncEnabled) startSync()
        }
    }

    /** Current devices for the UI, stable order. */
    fun devices(): List<DeviceInfo> = sessions.values
        .sortedBy { it.creds.name.ifEmpty { it.creds.ip } }
        .map {
            DeviceInfo(
                key = it.creds.key,
                label = it.creds.name.ifEmpty { it.creds.ip },
                reachable = it.reachable,
                streaming = running && streamTargets.contains(it),
            )
        }

    fun onBands(low: Float, mid: Float, high: Float) = colours.setBands(low, mid, high)

    fun onLinkBeat() = colours.onLinkBeat()

    /** Rebuild sessions from the store, keeping runtime state where keys match. */
    private fun reloadSessions() {
        val stored = store.loadAll().associateBy { it.key }
        sessions.keys.retainAll(stored.keys)
        for ((key, creds) in stored) {
            val existing = sessions[key]
            if (existing == null) sessions[key] = Session(creds) else existing.creds = creds
        }
    }

    private fun recomputeState() {
        if (pairingActive) return
        currentState = when {
            running -> State.STREAMING
            sessions.isEmpty() -> State.DISCONNECTED
            sessions.values.any { it.reachable } -> State.PAIRED
            else -> State.UNREACHABLE
        }
    }

    // ------------------------------------------------------------------ polling

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

    fun checkReachability() {
        val all = sessions.values.toList()
        if (all.isEmpty()) {
            if (!pairingActive && !isSearching) currentState = State.DISCONNECTED
            return
        }
        thread {
            var anyDown = false
            for (s in all) {
                s.reachable = NanoleafApi.tokenValid(s.creds.ip, s.creds.port, s.creds.authToken)
                if (!s.reachable) anyDown = true
            }
            mainHandler.post {
                recomputeState()
                // A missing device may have moved address; mDNS knows.
                if (anyDown) search(silent = true)
            }
        }
    }

    // ---------------------------------------------------------------- discovery

    private var isSearching = false
    private var lastSearchSilent = false
    private var discoveryListener: NsdManager.DiscoveryListener? = null

    /** One panel seen on the network during the current search. */
    internal data class Discovered(val id: String, val host: String, val port: Int, val name: String)

    // Written from the NSD resolve callback, read when the search settles on the
    // main thread — and an in-flight resolve can still land after discovery stops,
    // so iteration here has to tolerate a concurrent put.
    private val found = ConcurrentHashMap<String, Discovered>()
    private var awaitedIds: Set<String> = emptySet()
    private val settle = Runnable { finishSearch() }

    fun search(silent: Boolean = false) {
        if (isSearching || pairingActive) return
        lastSearchSilent = silent
        if (!silent) currentState = State.SEARCHING
        isSearching = true
        found.clear()
        awaitedIds = sessions.values
            .mapNotNull { it.creds.deviceId.takeIf(String::isNotEmpty) }
            .toSet()

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
                nsdManager.resolveService(service, newResolveListener())
            }
            override fun onServiceLost(service: NsdServiceInfo) {}
            override fun onDiscoveryStopped(serviceType: String) { isSearching = false }
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) { isSearching = false }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) { isSearching = false }
        }

    private fun newResolveListener() =
        object : NsdManager.ResolveListener {
            override fun onResolveFailed(srv: NsdServiceInfo, errorCode: Int) {}
            override fun onServiceResolved(srv: NsdServiceInfo) {
                val host = srv.host?.hostAddress ?: return
                val id = deviceIdOf(srv)
                found[id] = Discovered(id, host, srv.port, srv.serviceName.orEmpty())
                // Every known device answering settles it early; strangers can
                // only matter to a user-initiated scan, which waits the window out.
                val allKnownFound = awaitedIds.isNotEmpty() && found.keys.containsAll(awaitedIds)
                if (lastSearchSilent && allKnownFound) {
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
        if (!isSearching) return
        stopDiscovery()
        reconcile()
    }

    // ---------------------------------------------------------------- reconcile

    /** Decide, once, against everything the network offered. */
    private fun reconcile() {
        val unmatched = HashMap(found)
        adoptKnown(unmatched)
        val legacy = sessions.values.map { it.creds }.filter { it.deviceId.isEmpty() }
        if (legacy.isNotEmpty() && unmatched.isNotEmpty()) {
            val candidates = unmatched.values.toList()
            thread {
                claimLegacy(legacy, candidates)
                mainHandler.post { afterReconcile() }
            }
        } else {
            afterReconcile()
        }
    }

    /** Re-home known devices that answered: same token, whatever address they moved to. */
    private fun adoptKnown(unmatched: MutableMap<String, Discovered>) {
        for (s in sessions.values) {
            val id = s.creds.deviceId
            if (id.isEmpty()) continue
            val d = unmatched.remove(id) ?: continue
            s.reachable = true
            val c = s.creds
            val updated = c.copy(ip = d.host, port = d.port, name = d.name.ifEmpty { c.name })
            if (updated != c) {
                store.upsert(updated)
                s.creds = updated
            }
        }
    }

    /**
     * Off-thread: pairings predating device ids. The token itself says which panel
     * it belongs to, so try it against each candidate until one accepts, then
     * re-key the entry so this never has to be guessed again. A stranger's panel
     * never gets re-paired over an existing entry — failing to claim just leaves
     * the entry offline for the user to forget.
     */
    private fun claimLegacy(legacy: List<NanoleafCredentials>, candidates: List<Discovered>) {
        val taken = mutableSetOf<String>()
        for (creds in legacy) {
            val match = candidates.firstOrNull {
                it.id !in taken && NanoleafApi.tokenValid(it.host, it.port, creds.authToken)
            } ?: continue
            taken += match.id
            val claimed = NanoleafCredentials(
                match.host, creds.authToken, match.port, match.id, match.name
            )
            store.remove(creds.key)
            store.upsert(claimed)
            sessions.remove(creds.key)
            sessions[claimed.key] = Session(claimed).apply { reachable = true }
        }
    }

    private fun afterReconcile() {
        reloadSessions()
        val strangers = found.values.filter { d ->
            sessions.values.none { it.creds.deviceId == d.id }
        }
        if (!lastSearchSilent && strangers.isNotEmpty()) {
            openPairingWindow(strangers)
        } else {
            recomputeState()
        }
    }

    // ------------------------------------------------------------------ pairing

    @Volatile private var pairingActive = false

    /**
     * Poll `/new` on every unpaired panel at once. No picker needed: the endpoint
     * only answers on the device whose pairing window the user is physically
     * holding open, so the button press is the selection.
     */
    private fun openPairingWindow(candidates: List<Discovered>) {
        if (pairingActive) return
        pairingActive = true
        currentState = State.FOUND_UNPAIRED
        thread { runPairingWindow(candidates) }
    }

    private fun runPairingWindow(candidates: List<Discovered>) {
        val deadline = System.currentTimeMillis() + PAIRING_WINDOW_MS
        var paired: NanoleafCredentials? = null
        while (pairingActive && paired == null && System.currentTimeMillis() < deadline) {
            for (d in candidates) {
                val token = NanoleafApi.requestToken(d.host, d.port) ?: continue
                paired = NanoleafCredentials(d.host, token, d.port, d.id, d.name)
                break
            }
            if (paired == null) Thread.sleep(PAIR_POLL_MS)
        }
        pairingActive = false
        val claimed = paired
        mainHandler.post {
            if (claimed != null) {
                store.upsert(claimed)
                sessions[claimed.key] = Session(claimed).apply { reachable = true }
            }
            recomputeState()
        }
    }

    // -------------------------------------------------------------- static state

    fun setStaticState(on: Boolean, bri: Float? = null, hue: Float? = null, sat: Float? = null) {
        val all = store.loadAll()
        if (all.isEmpty()) return
        thread { all.forEach { NanoleafApi.setState(it, on, bri, hue, sat) } }
    }

    // ---------------------------------------------------------------- streaming

    fun startSync() {
        if (running || sessions.isEmpty()) return
        running = true

        thread {
            val ready = sessions.values.mapNotNull { prepareStream(it) }
            if (ready.isEmpty()) {
                currentState = State.ERROR
                running = false
                return@thread
            }
            store.syncEnabled = true
            streamTargets = ready
            socket = DatagramSocket()
            currentState = State.STREAMING
            senderThread = thread { streamLoop(ready) }
        }
    }

    /** Handshake one device for streaming; a failure skips it, not the whole sync. */
    private fun prepareStream(s: Session): Session? = try {
        val panels = NanoleafApi.fetchPanelIds(s.creds)
        val port = if (panels.isEmpty()) null else NanoleafApi.enableExtControl(s.creds)
        if (port == null) {
            null
        } else {
            s.panelIds.clear()
            s.panelIds.addAll(panels)
            s.udpPort = port
            s.address = InetAddress.getByName(s.creds.ip)
            s
        }
    } catch (e: Exception) {
        Log.e(TAG, "Prepare ${s.creds.ip} failed: ${e.message}")
        null
    }

    private fun streamLoop(targets: List<Session>) {
        // V2 frame per device: [NumPanels (2)] then per panel
        // [PanelID (2)] [R] [G] [B] [W] [TransitionTime (2)].
        val frames = targets.map { it to ByteArray(2 + it.panelIds.size * 8) }
        while (running) {
            val colour = colours.nextColour()
            for ((session, buf) in frames) sendFrame(session, buf, colour)
            Thread.sleep(FRAME_MS)
        }
    }

    private fun sendFrame(s: Session, buf: ByteArray, c: NanoleafColorEngine.RGB) {
        val n = s.panelIds.size
        buf[0] = (n shr 8).toByte()
        buf[1] = (n and 0xFF).toByte()
        var o = 2
        for (pId in s.panelIds) {
            buf[o++] = (pId shr 8).toByte()
            buf[o++] = (pId and 0xFF).toByte()
            buf[o++] = c.r.toByte()
            buf[o++] = c.g.toByte()
            buf[o++] = c.b.toByte()
            buf[o++] = 0                       // W
            buf[o++] = 0                       // transition hi: 1 = 100 ms
            buf[o++] = 1                       // transition lo
        }
        val addr = s.address ?: return
        try {
            socket?.send(DatagramPacket(buf, buf.size, addr, s.udpPort))
        } catch (_: Exception) {
        }
    }

    fun stopSync() {
        running = false
        store.syncEnabled = false
        senderThread?.join(1000)
        senderThread = null
        socket?.close()
        socket = null
        streamTargets = emptyList()

        if (currentState == State.STREAMING) recomputeState()

        val all = store.loadAll()
        if (all.isNotEmpty()) {
            thread { all.forEach { NanoleafApi.setState(it, on = false, bri = null, hue = null, sat = null) } }
        }
    }

    fun forget() {
        pairingActive = false
        stopSync()
        store.clear()
        sessions.clear()
        currentState = State.DISCONNECTED
    }

    /** Remove one device; the stream restarts on the rest if it was running. */
    fun forgetDevice(key: String) {
        val wasRunning = running
        if (wasRunning) stopSync()
        store.remove(key)
        reloadSessions()
        recomputeState()
        if (wasRunning && sessions.isNotEmpty()) startSync()
    }

    companion object {
        private const val TAG = "Nanoleaf"
        private const val FRAME_MS = 25L          // ~40 fps
        /** How long to keep listening before deciding what the network holds. */
        private const val SETTLE_MS = 2500L
        /** How long the user has to walk over and hold the power button. */
        private const val PAIRING_WINDOW_MS = 90_000L
        private const val PAIR_POLL_MS = 2000L
    }
}
