package com.lowlatency.visualizer.hue

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.lowlatency.visualizer.LinkSync
import kotlin.concurrent.thread
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Ties the Hue pipeline to the audio: takes a lightweight band snapshot from the
 * render loop ([onBands], called on the GL thread — cheap volatile writes) and
 * drives a dedicated ~50 Hz sender thread that maps the spectrum to per-channel
 * colors and pushes them over [HueStreamClient].
 *
 * Networking never touches the GL or main thread: the render loop only updates
 * three volatile floats; the sender thread does the DTLS work.
 */
class HueLightController(context: Context) {

    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val store = HueCredentialStore(context)
    val setup = HueSetupManager(context)

    // Latest bands, written by the GL thread, read by the sender thread.
    @Volatile private var low = 0f
    @Volatile private var mid = 0f
    @Volatile private var high = 0f

    @Volatile private var running = false
    private var senderThread: Thread? = null
    private var client: HueStreamClient? = null

    val isEnabled: Boolean get() = running

    /** Called every render frame from the GL thread. Must stay allocation-free. */
    fun onBands(low: Float, mid: Float, high: Float) {
        this.low = low; this.mid = mid; this.high = high
    }

    // Incremented on each Ableton Link beat (GL thread). When Link sync is on the
    // sender loop flashes on these instead of audio onset — colours still follow
    // the bands. Single writer (GL thread); the sender thread only reads it.
    @Volatile private var linkBeatCount = 0
    fun onLinkBeat() { linkBeatCount++ }

    /**
     * Start streaming to [area]: persist the choice, activate the stream over
     * REST, open DTLS, and spin up the sender loop. [onResult] is posted on the
     * main thread with success + an optional error message.
     */
    fun enable(area: HueEntertainmentArea, onResult: (Boolean, String?) -> Unit) {
        if (running) { onResult(true, null); return }
        val creds = store.loadCredentials()
        if (creds == null) { onResult(false, "Bridge not paired."); return }
        if (area.channels.isEmpty()) { onResult(false, "Area has no light channels."); return }

        store.selectedAreaId = area.id
        setup.setStreamActive(creds, area.id, active = true) { ok ->
            if (!ok) { onResult(false, "Could not start the entertainment stream."); return@setStreamActive }
            startSender(creds, area, onResult)
        }
    }

    /** Stop the sender loop, close DTLS, and deactivate the stream. */
    fun disable() {
        if (!running && senderThread == null) return
        running = false
        senderThread?.let { runCatching { it.join(500) } }
        senderThread = null
        client?.close()
        client = null
        store.syncEnabled = false

        val creds = store.loadCredentials()
        val areaId = store.selectedAreaId
        if (creds != null && areaId != null) {
            setup.setStreamActive(creds, areaId, active = false) { }
        }
    }

    private fun startSender(
        creds: HueCredentials,
        area: HueEntertainmentArea,
        onResult: (Boolean, String?) -> Unit,
    ) {
        val channelIds = IntArray(area.channels.size) { area.channels[it].channelId }
        val rgb = FloatArray(channelIds.size * 3)

        senderThread = thread(name = "hue-sender", priority = Thread.NORM_PRIORITY + 1) {
            val c = HueStreamClient(
                bridgeIp = creds.bridgeIp,
                identity = creds.username.toByteArray(Charsets.US_ASCII),
                psk = HueStreamClient.hexToBytes(creds.clientKey),
                areaId = area.id,
            )
            try {
                c.connect()
            } catch (t: Throwable) {
                Log.e(TAG, "DTLS connect failed", t)
                main.post { onResult(false, "DTLS handshake failed: ${t.message}") }
                runCatching { c.close() }
                return@thread
            }
            client = c
            running = true
            store.syncEnabled = true
            main.post { onResult(true, null) }

            var flash = 0f
            var lastLow = 0f
            var lastLinkBeat = linkBeatCount
            val frameNs = 1_000_000_000L / SEND_HZ

            while (running) {
                val t0 = System.nanoTime()
                val l = low; val m = mid; val h = high

                // Flash source: Ableton Link's beat when sync is on (locked to
                // Traktor), otherwise audio low-band onset. Colours follow the
                // bands either way.
                if (LinkSync.enabled) {
                    val c = linkBeatCount
                    if (c != lastLinkBeat) { flash = 1f; lastLinkBeat = c }
                } else {
                    if (l > BEAT_THRESHOLD && (l - lastLow) > BEAT_DELTA) flash = 1f
                }
                flash *= FLASH_DECAY
                lastLow = l

                mapColors(channelIds.size, l, m, h, flash, rgb)
                c.send(channelIds, rgb)

                val sleepNs = frameNs - (System.nanoTime() - t0)
                if (sleepNs > 0) {
                    try { Thread.sleep(sleepNs / 1_000_000, (sleepNs % 1_000_000).toInt()) }
                    catch (_: InterruptedException) { break }
                }
            }
            runCatching { c.close() }
        }
    }

    /**
     * Spectrum → per-channel RGB. Each channel rotates the spectral emphasis a
     * little (so a multi-light area isn't a flat wash), brightness tracks overall
     * energy, and the beat flash punches all channels toward white.
     */
    private fun mapColors(count: Int, low: Float, mid: Float, high: Float, flash: Float, out: FloatArray) {
        val energy = max(low, max(mid, high))
        val bright = MIN_BRIGHT + (1f - MIN_BRIGHT) * energy
        for (i in 0 until count) {
            val ph = if (count > 1) i.toFloat() / count else 0f
            // Rotate emphasis from bass-warm (red) to treble-cool (blue) per channel.
            val r = lerp(low, high, ph)
            val g = mid
            val b = lerp(high, low, ph)
            // sqrt = mild gamma so low levels remain visible on the lights.
            out[i * 3]     = sqrt(((r + flash) * bright).coerceIn(0f, 1f))
            out[i * 3 + 1] = sqrt(((g + flash * 0.6f) * bright).coerceIn(0f, 1f))
            out[i * 3 + 2] = sqrt(((b + flash * 0.4f) * bright).coerceIn(0f, 1f))
        }
    }

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

    companion object {
        private const val TAG = "HueLightController"
        private const val SEND_HZ = 50L          // Hue Entertainment caps ~50–60 Hz
        private const val MIN_BRIGHT = 0.06f      // floor so lights never go fully dark while synced
        private const val BEAT_THRESHOLD = 0.45f  // low-band level that can register as a beat
        private const val BEAT_DELTA = 0.12f      // required rise vs last frame
        private const val FLASH_DECAY = 0.80f     // per-frame flash falloff (~50 Hz)
    }
}
