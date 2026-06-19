package com.lowlatency.visualizer.hue

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.lowlatency.visualizer.LinkSync
import kotlin.concurrent.thread
import kotlin.math.abs
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

    // Raw mic loudness (peak of the PCM window), fed from the GL thread. Scales the
    // Link beat-strobe so quiet passages flash subtly. This is the ABSOLUTE level —
    // not the normalized FFT bands, which stay hot even when the track is quiet.
    @Volatile private var micLevel = 0f
    fun onMicLevel(level: Float) { micLevel = level }

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
            var level = 0f      // smoothed loudness follower (Link beat scaling)
            var diagFrame = 0   // throttles the calibration log
            // Held beat colour for Link mode, recomputed from bass presence each beat.
            var beatR = 1f; var beatG = 1f; var beatB = 1f
            val frameNs = 1_000_000_000L / SEND_HZ

            while (running) {
                val t0 = System.nanoTime()
                val l = low; val m = mid; val h = high

                if (LinkSync.enabled) {
                    // Beat-strobe: dark between Link beats; on each beat flash a
                    // colour chosen by bass presence — bass-heavy => purple, treble
                    // => green. Brightness scales with how loud the track is.

                    // Recent-loudness peak follower (fast attack, slow decay) over
                    // the absolute mic level, so a beat in a quiet/near-silent
                    // passage flashes subtly while a loud drop flashes full.
                    level = max(micLevel, level * LEVEL_DECAY)
                    if (++diagFrame % 50 == 0) {
                        Log.d(TAG, String.format("Link mic peak=%.4f level=%.4f", micLevel, level))
                    }

                    val bc = linkBeatCount
                    if (bc != lastLinkBeat) {
                        lastLinkBeat = bc
                        // Loudness gate: below BASE the beat stays dim; above it
                        // ramps quickly to full whack. smoothstep over a narrow band
                        // = near on/off without flicker right at the threshold.
                        val t = ((level - LEVEL_BASE) / (LEVEL_FULL - LEVEL_BASE)).coerceIn(0f, 1f)
                        val loudness = t * t * (3f - 2f * t)
                        flash = MIN_BEAT_AMP + (1f - MIN_BEAT_AMP) * loudness
                        // Bass presence picks a hue: bass-heavy => purple, treble
                        // => green. Interpolating in HUE (not RGB) sweeps through
                        // blue/cyan for the mids instead of a muddy grey.
                        val warmth = (l / (l + h + 1e-3f)).coerceIn(0f, 1f)
                        val hue = GREEN_HUE + (PURPLE_HUE - GREEN_HUE) * warmth
                        hsvToRgb(hue, STROBE_SAT, 1f)
                        beatR = hsvOut[0]; beatG = hsvOut[1]; beatB = hsvOut[2]
                    }
                    flash *= FLASH_DECAY
                    val r = sqrt((beatR * flash).coerceIn(0f, 1f))
                    val g = sqrt((beatG * flash).coerceIn(0f, 1f))
                    val b = sqrt((beatB * flash).coerceIn(0f, 1f))
                    for (i in channelIds.indices) {
                        rgb[i * 3] = r; rgb[i * 3 + 1] = g; rgb[i * 3 + 2] = b
                    }
                } else {
                    // Audio mode: continuous spectrum colour + low-band onset flash.
                    if (l > BEAT_THRESHOLD && (l - lastLow) > BEAT_DELTA) flash = 1f
                    flash *= FLASH_DECAY
                    mapColors(channelIds.size, l, m, h, flash, rgb)
                }
                lastLow = l

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

    // Reused HSV->RGB scratch (written on the sender thread only). h in degrees,
    // s/v in 0..1. Result lands in [hsvOut].
    private val hsvOut = FloatArray(3)
    private fun hsvToRgb(h: Float, s: Float, v: Float) {
        val hh = (((h % 360f) + 360f) % 360f) / 60f
        val c = v * s
        val x = c * (1f - abs(hh % 2f - 1f))
        val m = v - c
        var r = 0f; var g = 0f; var b = 0f
        when (hh.toInt()) {
            0 -> { r = c; g = x }
            1 -> { r = x; g = c }
            2 -> { g = c; b = x }
            3 -> { g = x; b = c }
            4 -> { r = x; b = c }
            else -> { r = c; b = x }
        }
        hsvOut[0] = r + m; hsvOut[1] = g + m; hsvOut[2] = b + m
    }

    companion object {
        private const val TAG = "HueLightController"
        private const val SEND_HZ = 50L          // Hue Entertainment caps ~50–60 Hz
        private const val MIN_BRIGHT = 0.06f      // floor so lights never go fully dark while synced
        private const val BEAT_THRESHOLD = 0.45f  // low-band level that can register as a beat
        private const val BEAT_DELTA = 0.12f      // required rise vs last frame
        private const val FLASH_DECAY = 0.80f     // per-frame flash falloff (~50 Hz)

        // Link beat-strobe hues (degrees). Bass-heavy beats flash purple, treble
        // beats flash green; mids sweep through blue/cyan (hue-space, never grey).
        private const val PURPLE_HUE = 280f
        private const val GREEN_HUE = 135f
        private const val STROBE_SAT = 1.0f       // full saturation for a club feel

        // Loudness gate for the Link beat-strobe (absolute mic PCM peak).
        private const val LEVEL_DECAY = 0.93f     // per-frame loudness-follower falloff (~50 Hz)
        private const val LEVEL_BASE = 0.015f     // mic peak below this => dim beats
        private const val LEVEL_FULL = 0.04f      // mic peak above this => full-brightness beats
        private const val MIN_BEAT_AMP = 0.06f    // dim beat level below BASE (subtle, not off)
    }
}
