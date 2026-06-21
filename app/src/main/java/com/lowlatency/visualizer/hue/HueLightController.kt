package com.lowlatency.visualizer.hue

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.lowlatency.visualizer.HueStrobeSettings
import com.lowlatency.visualizer.LinkSync
import com.lowlatency.visualizer.NativeBridge
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
    @Volatile var paused = false        // true while app is backgrounded; sender drops to 1 Hz keepalive
    private var senderThread: Thread? = null
    private var client: HueStreamClient? = null

    val isEnabled: Boolean get() = running
    val huePacketsSent: Long get() = client?.packetsSent ?: 0L
    val huePacketsFailed: Long get() = client?.packetsFailed ?: 0L

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
    @Volatile private var meterLevel = 0f
    fun onMicLevel(peak: Float) {
        micLevel = peak
        // Peak-hold + slow decay so the Advanced meter reads as a steady VU bar
        // instead of the spiky raw per-frame peak.
        meterLevel = if (peak > meterLevel) peak else meterLevel * METER_DECAY
    }

    // Volume-independent bass fraction (bassRMS / (bassRMS + trebleRMS)), computed
    // from raw PCM and fed from the GL thread. Drives the Link strobe colour:
    // high => blue/purple, low => light red. Lightly smoothed here.
    @Volatile private var bassRatio = 0.5f
    fun onBassRatio(r: Float) { bassRatio += 0.25f * (r - bassRatio) }

    // Live values for the Advanced panel's meter (read on the UI thread).
    val currentMicLevel: Float get() = meterLevel
    val currentBassRatio: Float get() = bassRatio

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
            // Held beat colour for Link mode, recomputed from bass presence each beat.
            var beatR = 1f; var beatG = 1f; var beatB = 1f
            val frameNs = 1_000_000_000L / SEND_HZ
            var linkBeatFired = false

            while (running) {
                val t0 = System.nanoTime()

                if (paused) {
                    // App is backgrounded — send a silent frame at 1 Hz to keep DTLS alive.
                    for (i in rgb.indices) rgb[i] = 0f
                    c.send(channelIds, rgb)
                    try { Thread.sleep(1000) } catch (_: InterruptedException) { break }
                    continue
                }

                val l = low; val m = mid; val h = high

                if (LinkSync.enabled) {
                    // Beat-strobe: dark between Link beats; on each beat flash a
                    // colour chosen by bass presence. All thresholds come from the
                    // user-tunable HueStrobeSettings (device mic varies a lot).
                    val cfg = HueStrobeSettings
                    val levelBase = cfg.levelBase
                    val levelFull = cfg.levelFull

                    level = max(micLevel, level * LEVEL_DECAY)

                    val lookaheadMs = cfg.hueLookaheadMs
                    val bc = linkBeatCount
                    var shouldFlash = false

                    if (lookaheadMs > 0f) {
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

                    if (shouldFlash && level >= levelBase) {
                        val t = ((level - levelBase) / (levelFull - levelBase)).coerceIn(0f, 1f)
                        val loudness = t * t * (3f - 2f * t)
                        flash = MIN_BEAT_AMP + (1f - MIN_BEAT_AMP) * loudness
                        val ct = ((bassRatio - cfg.bassLo) / (cfg.bassHi - cfg.bassLo)).coerceIn(0f, 1f)
                        val cs = ct * ct * (3f - 2f * ct)
                        val hue = RED_HUE + (PURPLE_HUE - RED_HUE) * cs
                        val sat = SAT_TREBLE + (SAT_BASS - SAT_TREBLE) * cs
                        hsvToRgb(hue, sat, 1f)
                        beatR = hsvOut[0]; beatG = hsvOut[1]; beatB = hsvOut[2]
                    }
                    flash *= FLASH_DECAY
                    // Beat punches on top of the (tunable) ambient floor, so the
                    // lights rest on a low glow instead of going fully dark when
                    // there's no beat (quiet parts / no music).
                    val r = sqrt((cfg.ambientR + beatR * flash).coerceIn(0f, 1f))
                    val g = sqrt((cfg.ambientG + beatG * flash).coerceIn(0f, 1f))
                    val b = sqrt((cfg.ambientB + beatB * flash).coerceIn(0f, 1f))
                    for (i in channelIds.indices) {
                        rgb[i * 3] = r; rgb[i * 3 + 1] = g; rgb[i * 3 + 2] = b
                    }
                } else {
                    val cfg = HueStrobeSettings
                    if (l > cfg.audioBeatThreshold && (l - lastLow) > cfg.audioBeatDelta) flash = 1f
                    flash *= FLASH_DECAY
                    mapColors(channelIds.size, l, m, h, flash * cfg.audioFlashMul, rgb)
                }
                lastLow = l

                c.send(channelIds, rgb)

                val sleepNs = frameNs - (System.nanoTime() - t0)
                if (sleepNs > 0) {
                    try { Thread.sleep(sleepNs / 1_000_000, (sleepNs % 1_000_000).toInt()) }
                    catch (_: InterruptedException) { break }
                }
            }
        }
    }

    /**
     * Spectrum → per-channel RGB. Each channel rotates the spectral emphasis a
     * little (so a multi-light area isn't a flat wash), brightness tracks overall
     * energy, and the beat flash punches all channels toward white.
     */
    private fun mapColors(count: Int, low: Float, mid: Float, high: Float, flash: Float, out: FloatArray) {
        val energy = max(low, max(mid, high))
        val bright = (MIN_BRIGHT + (1f - MIN_BRIGHT) * energy) * HueStrobeSettings.audioBrightMul
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
        private const val FLASH_DECAY = 0.80f     // per-frame flash falloff (~50 Hz)

        // Link beat-strobe — fixed shape constants. The user-tunable thresholds
        // (level gate, bass split, ambient glow) live in HueStrobeSettings.
        private const val LEVEL_DECAY = 0.93f     // per-frame loudness-follower falloff (~50 Hz)
        private const val METER_DECAY = 0.90f     // Advanced meter VU-bar peak-hold falloff
        private const val MIN_BEAT_AMP = 0.06f    // floor for the weakest *active* beat

        // Colour endpoints: little bass (breakdown) => light red, enough bass =>
        // blue/purple, blended through pink/magenta over the bass ratio.
        private const val RED_HUE = 360f          // little-bass colour (light red)
        private const val PURPLE_HUE = 265f       // bass-heavy colour (blue/purple)
        private const val SAT_BASS = 1.0f         // vivid blue/purple
        private const val SAT_TREBLE = 0.70f      // lower sat => lighter red
    }
}
