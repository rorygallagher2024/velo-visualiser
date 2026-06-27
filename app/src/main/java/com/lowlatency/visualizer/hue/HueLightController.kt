package com.lowlatency.visualizer.hue

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.lowlatency.visualizer.BeatBus
import com.lowlatency.visualizer.LightingSettings
import com.lowlatency.visualizer.LinkSync
import com.lowlatency.visualizer.NativeBridge
import kotlin.concurrent.thread
import kotlin.math.abs

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

    // Incremented on the sender thread each time a beat flash is actually pushed to
    // the bulbs. The Advanced panel polls this to flash a "Lights" diagnostic dot.
    @Volatile var lightBeatCount = 0
        private set

    /** Called every render frame from the GL thread. Must stay allocation-free. */
    fun onBands(low: Float, mid: Float, high: Float) {
        this.low = low; this.mid = mid; this.high = high
    }

    // Incremented on each Ableton Link beat (GL thread). When Link sync is on the
    // sender loop flashes on these (with lookahead) instead of audio onset; the
    // gate/intensity/colour come from the shared BeatBus. Single writer (GL
    // thread); the sender thread only reads it.
    @Volatile private var linkBeatCount = 0
    fun onLinkBeat() { linkBeatCount++ }

    // Audio presence (loudness, bass balance) and the gated beat now live in the
    // shared BeatBus — the same gate the visuals and haptics use. Live values for
    // the Advanced panel's meter (read on the UI thread) read straight off it.
    val currentMicLevel: Float get() = BeatBus.level
    val currentBassRatio: Float get() = BeatBus.bassRatio

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
        activeLightIds = area.lightIds
        setup.setStreamActive(creds, area.id, active = true) { ok ->
            if (!ok) { onResult(false, "Could not start the entertainment stream."); return@setStreamActive }
            startSender(creds, area, onResult)
        }
    }

    // Light IDs of the active area, captured on enable so [disable] can power them
    // off via REST (LIFX/Nanoleaf do the same on a user-initiated stop).
    private var activeLightIds: List<String> = emptyList()

    /**
     * Tear down and re-establish the stream. A DTLS entertainment session dies
     * while the app is backgrounded (Wi-Fi sleeps, the bridge times the session
     * out after ~10s), but UDP sends keep "succeeding" locally — so neither the
     * UI nor [packetsFailed] can tell it's dead. On return to the foreground we
     * rebuild it unconditionally. No-op if nothing was running. [onResult] is
     * posted on the main thread.
     */
    fun restart(area: HueEntertainmentArea, onResult: (Boolean, String?) -> Unit) {
        if (!running && senderThread == null) { main.post { onResult(false, null) }; return }
        thread(name = "hue-restart") {
            disable()                                  // joins sender, closes DTLS, deactivates
            try { Thread.sleep(250) } catch (_: InterruptedException) {}  // let deactivate land first
            main.post { enable(area, onResult) }       // reactivate + fresh DTLS handshake
        }
    }

    /**
     * Stop the sender loop, close DTLS, and deactivate the stream. When [turnOff]
     * is true (a user-initiated stop — sync toggle, system-audio switch, forget),
     * also power the bulbs off once the stream is released, matching LIFX/Nanoleaf.
     * The internal [restart] passes false so a foreground rebuild never blinks the
     * lights off.
     */
    fun disable(turnOff: Boolean = false) {
        if (!running && senderThread == null) return
        running = false
        senderThread?.let { runCatching { it.join(500) } }
        senderThread = null
        client?.close()
        client = null
        store.syncEnabled = false

        val creds = store.loadCredentials()
        val areaId = store.selectedAreaId
        val ids = activeLightIds
        if (creds != null && areaId != null) {
            // Power-off must follow stream release — REST control is ignored while the
            // entertainment session owns the lights.
            setup.setStreamActive(creds, areaId, active = false) {
                if (turnOff && ids.isNotEmpty()) setup.controlLights(creds, ids, on = false)
            }
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
            var lastLinkBeat = linkBeatCount
            var lastBeat = BeatBus.beatCount
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
                    // colour chosen by bass presence. The gate, intensity and
                    // colour all come from the shared BeatBus — the same gate the
                    // visuals use — so the lights and screen stay in lock-step.
                    val cfg = LightingSettings
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

                    // Honour the gate and the user's "disable light beat" toggle.
                    if (shouldFlash && cfg.linkBeatFlashEnabled && BeatBus.gateOpen) {
                        flash = cfg.beatFlashAmp(BeatBus.loudness)
                        lightBeatCount++

                        val ct = ((BeatBus.bassRatio - cfg.bassLo) / (cfg.bassHi - cfg.bassLo)).coerceIn(0f, 1f)
                        val cs = ct * ct * (3f - 2f * ct)
                        val hue = RED_HUE + (PURPLE_HUE - RED_HUE) * cs
                        val sat = SAT_TREBLE + (SAT_BASS - SAT_TREBLE) * cs
                        hsvToRgb(hue, sat, 1f)
                        beatR = hsvOut[0]; beatG = hsvOut[1]; beatB = hsvOut[2]
                    }
                    flash *= FLASH_DECAY
                    // Beat punches on top of the shared resting glow/floor (lights
                    // rest low instead of going dark in quiet parts). The brightness
                    // curve is shared across all brands — presets shape it.
                    val v = cfg.linkBrightnessValue(flash)
                    val r = (beatR * v).coerceIn(0f, 1f)
                    val g = (beatG * v).coerceIn(0f, 1f)
                    val b = (beatB * v).coerceIn(0f, 1f)
                    for (i in channelIds.indices) {
                        rgb[i * 3] = r; rgb[i * 3 + 1] = g; rgb[i * 3 + 2] = b
                    }
                } else {
                    // Audio mode: flash on the very same gated beat the visuals
                    // fire on (shared BeatBus), scaled by loudness; colour follows
                    // the spectrum bands.
                    val bc = BeatBus.beatCount
                    if (bc != lastBeat) { flash = BeatBus.loudness; lastBeat = bc; lightBeatCount++ }
                    flash *= FLASH_DECAY
                    mapColors(channelIds.size, l, m, h, flash, rgb)
                }

                c.send(channelIds, rgb)

                val deadlineNs = t0 + frameNs
                val sleepNs = deadlineNs - System.nanoTime() - SPIN_MARGIN_NS
                if (sleepNs > 0) {
                    try { Thread.sleep(sleepNs / 1_000_000, (sleepNs % 1_000_000).toInt()) }
                    catch (_: InterruptedException) { break }
                }
                // Spin-wait the final margin for precise timing
                while (System.nanoTime() < deadlineNs) Thread.yield()
            }
        }
    }

    /**
     * Spectrum → per-channel RGB. The *colour* is a saturated hue chosen by the
     * music's spectral balance (bass-heavy → red, treble-heavy → blue, through
     * magenta/purple in between); *brightness* tracks energy and the beat flash.
     *
     * Earlier this mapped the three bands straight onto R/G/B — but most music
     * excites bass+mid+treble at once, so R≈G≈B and the lights washed out to a
     * dull white. Driving hue+saturation instead keeps a vivid, club-like colour
     * that shifts with the track, and the beat punches brightness (not white).
     */
    private fun mapColors(count: Int, low: Float, mid: Float, high: Float, flash: Float, out: FloatArray) {
        val total = low + mid + high + 1e-3f
        // Spectral balance 0..1: 0 = all bass, 1 = all treble.
        val centroid = ((mid * 0.5f + high) / total).coerceIn(0f, 1f)
        // Shared brightness curve (dim base tracks energy, beat punches on top) so a
        // preset looks identical across every brand.
        val value = LightingSettings.audioBrightnessValue(low, mid, high, flash)
        // A hard hit only mildly desaturates, so beats pop without going white.
        val sat = (AUDIO_SAT - flash * 0.30f).coerceIn(0.6f, 1f)
        for (i in 0 until count) {
            // Fan channels across a slice of the hue range so a multi-light area
            // isn't a flat wash.
            val spread = if (count > 1) (i.toFloat() / (count - 1) - 0.5f) * AUDIO_CHANNEL_SPREAD else 0f
            val pos = (centroid + spread).coerceIn(0f, 1f)
            val hue = AUDIO_HUE_BASS + (AUDIO_HUE_TREBLE - AUDIO_HUE_BASS) * pos
            hsvToRgb(hue, sat, value)
            out[i * 3] = hsvOut[0]; out[i * 3 + 1] = hsvOut[1]; out[i * 3 + 2] = hsvOut[2]
        }
    }

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
        private const val SPIN_MARGIN_NS = 2_000_000L  // spin-wait the last 2ms for precise timing
        private const val FLASH_DECAY = 0.80f     // per-frame flash falloff (~50 Hz)

        // Brightness floor, beat-flash amplitude and the resting glow are now shared
        // across all brands in LightingSettings (so presets behave identically).

        // Colour endpoints: little bass (breakdown) => light red, enough bass =>
        // blue/purple, blended through pink/magenta over the bass ratio.
        private const val RED_HUE = 360f          // little-bass colour (light red)
        private const val PURPLE_HUE = 265f       // bass-heavy colour (blue/purple)
        private const val SAT_BASS = 1.0f         // vivid blue/purple
        private const val SAT_TREBLE = 0.70f      // lower sat => lighter red

        // Audio-reactive light show (Link off): a continuous, saturated sweep
        // driven by the spectral balance. Stays on the warm→cool club side of the
        // wheel (red → magenta/purple → blue), skipping the murky greens/yellows.
        private const val AUDIO_HUE_BASS = 360f     // bass-heavy => red
        private const val AUDIO_HUE_TREBLE = 220f   // treble-heavy => blue
        private const val AUDIO_SAT = 0.92f         // vivid resting saturation
        private const val AUDIO_CHANNEL_SPREAD = 0.22f  // hue fan across multiple lights
    }
}
