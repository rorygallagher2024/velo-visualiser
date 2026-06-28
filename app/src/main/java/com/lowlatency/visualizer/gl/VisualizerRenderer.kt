package com.lowlatency.visualizer.gl

import android.content.Context
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.util.Log
import com.lowlatency.visualizer.BeatBus
import com.lowlatency.visualizer.BeatDetector
import com.lowlatency.visualizer.BeatPulse
import com.lowlatency.visualizer.BeatSettings
import com.lowlatency.visualizer.GlowSettings
import com.lowlatency.visualizer.LinkSync
import com.lowlatency.visualizer.NativeBridge
import com.lowlatency.visualizer.ThemeSettings
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Owns the visual scenes and drives the per-frame data pull.
 *
 * Every frame it grabs the latest PCM window and FFT bands straight from the
 * native engine (zero-alloc fills), then renders the active scene. A swipe
 * triggers a short, smooth fade-out → swap → fade-in transition between scenes.
 */
class VisualizerRenderer(context: Context) : GLSurfaceView.Renderer {

    private val appContext = context.applicationContext

    companion object {
        private const val TAG = "VisualizerRenderer"
        const val DEFAULT_SCENE = 8       // Raw Oscilloscope — shown on startup
        private const val POINTS = 1024
        private const val TRANSITION_SEC = 0.45f   // total fade duration
        private const val PUNCH_FALL = 3.5f        // HDR beat-punch decay rate (~0.3 s)

        // First-run intro timeline (VELO particle ignite → breathe → shatter).
        private const val INTRO_IGNITE_SEC = 1.1f
        private const val INTRO_HOLD_SEC = 1.0f
        private const val INTRO_SHATTER_SEC = 1.2f
        private const val INTRO_TOTAL_SEC = INTRO_IGNITE_SEC + INTRO_HOLD_SEC + INTRO_SHATTER_SEC
        private const val INTRO_BLOOM = 1.5f       // forced glow during the intro

        // Process-static: the intro plays once per cold start. It survives surface
        // recreation (warm resume, rotation) so returning to a still-resident app
        // skips it, but resets when the process is killed and relaunched.
        @Volatile private var introPlayedThisProcess = false

        // OLED burn-in idle gate (deliberately gentle — the UNPROCESSED mic is
        // quiet, so the threshold sits just above its noise floor and the delay
        // is long enough not to dim during normal pauses in speech/music).
        private const val SILENCE_PEAK = 0.008f    // raw-PCM peak below this = silent
        private const val BASS_LP_A = 0.03f        // one-pole LP coeff (~230 Hz @ 48 kHz)
        private const val MIC_NOISE_FLOOR = 0.006f // mic peak below this = silence (bass ratio)
        private const val LEVEL_DECAY = 0.93f      // loudness-follower peak-hold falloff
        private const val BEAT_ANTIC_START = 0.70f // phase where the pre-beat swell begins
        private const val BEAT_ANTIC_AMOUNT = 0.45f// how strong the swell gets before the snap
        private const val TWO_PI = 6.2831855f
        // Drop/build detection works on a *relative* dB jump above a rolling
        // baseline, so it self-calibrates to any volume or dynamic range.
        private const val DROP_FALL_TAU = 1.5f     // baseline settles fast into a breakdown
        private const val DROP_RISE_TAU = 4.0f     // …but rises slowly, preserving the drop spike
        private const val DROP_DB_JUMP = 7.0f      // dB rise above baseline that counts as a drop
        private const val DROP_DB_MIN = -34.0f     // ignore jumps within the near-silent floor
        private const val DROP_DB_FLOOR = 1e-4f    // linear floor so log10 never sees 0
        private const val DROP_COOLDOWN = 2.0f     // min seconds between surges
        private const val DROP_DECAY = 1.4f        // surge envelope fade time (s)
        // Link bar-phase enrichment (applied in post to enriched scenes only).
        private const val BAR_BREATH_AMOUNT = 0.18f// bloom swell depth across the bar
        private const val SURGE_BLOOM = 1.6f       // extra bloom at full drop surge
        private const val SURGE_EXPOSURE = 0.4f    // extra exposure at full drop surge
        private const val IDLE_DELAY_SEC = 20.0f   // silence before dimming starts
        private const val IDLE_RAMP_SEC = 5.0f     // fade-out duration
        private const val IDLE_MIN_ALPHA = 0.45f   // dimmed floor (gentle, still legible)
    }

    // Reused per-frame — no allocation in the loop.
    private val pcm = FloatArray(POINTS)
    private val bands = FloatArray(3)

    // Shared buffer for zero-copy audio transfer to scenes/GPU.
    private val sharedAudioBuffer = java.nio.ByteBuffer.allocateDirect(POINTS * 4)
        .order(java.nio.ByteOrder.nativeOrder())
    val sharedAudioFloatBuffer: java.nio.FloatBuffer = sharedAudioBuffer.asFloatBuffer()

    private val scenes = arrayOfNulls<GlScene>(31)
    private val scenesToLoad = mutableListOf<Int>()
    private var loadFrameCounter = 0

    private fun createScene(index: Int): GlScene {
        return when (index) {
            0 -> OscilloscopeScene()
            1 -> TunnelScene()
            2 -> FluidScene()
            3 -> LaserArrayScene()
            4 -> CircularSpectrumScene()
            5 -> BarSpectrumScene()
            6 -> SpectralBloomScene()
            7 -> StarscapeScene()
            8 -> RawScopeScene()
            9 -> SpectrogramScene()
            10 -> BeatFireworksScene()
            11 -> PhyllotaxisScene()
            12 -> ElectricIrisScene()
            13 -> MandalaPulseScene()
            14 -> AudioWebScene()
            15 -> TopographicRidgeScene()
            16 -> LedMatrixScene()
            17 -> MechanicalMeterScene()
            18 -> BeatPulseScene()
            19 -> MandelboxScene()
            20 -> ReactionDiffusionScene()
            21 -> ChladniPlateScene()
            22 -> StrangeAttractorScene()
            23 -> PlasmaStormScene()
            24 -> AuroraDriftScene()
            25 -> OdysseyScene()
            26 -> LogoParticleScene()
            27 -> CrystalSwarmScene()
            28 -> LedMatrix3DScene()
            29 -> LiquidLightScene()
            30 -> SpectralCanyonScene()
            else -> RawScopeScene()
        }
    }

    private fun getOrInitScene(index: Int): GlScene {
        val idx = index.coerceIn(0, scenes.size - 1)
        return scenes[idx] ?: createScene(idx).also {
            it.onCreated()
            it.onResize(surfaceW, surfaceH, aspect)
            scenes[idx] = it
            scenesToLoad.remove(idx)
        }
    }

    private fun tryBackgroundLoad() {
        // NEW: Never load in the background while the intro is playing to ensure 0ms impact on animation
        if (scenesToLoad.isEmpty() || introActive) return

        // Slow cadence: one scene every 8 frames (~5ms work every 66ms @ 120Hz)
        if (++loadFrameCounter % 8 != 0) return

        val idx = scenesToLoad.removeAt(0)
        if (scenes[idx] == null) {
            scenes[idx] = createScene(idx).also {
                it.onCreated()
                it.onResize(surfaceW, surfaceH, aspect)
            }
        }
    }

    private var current = DEFAULT_SCENE
    private var target = DEFAULT_SCENE
    private var pendingTarget = -1              // scene requested mid-transition (honored on completion)

    private var surfaceW = 1
    private var surfaceH = 1
    private var aspect = 1f

    private var startNanos = 0L
    private var transitionStart = -1f          // <0 => no transition in progress
    private var activeTransitionSec = TRANSITION_SEC   // duration of the in-flight fade (per request)
    private var swapped = false

    // Optional per-frame audio tap (e.g. the Hue light controller). Called on the
    // GL thread with [low, mid, high]; must be allocation-free / non-blocking.
    @Volatile var bandsSink: ((Float, Float, Float) -> Unit)? = null

    // Optional per-frame raw-PCM tap for beat/onset detection (haptics). Gets the
    // reused PCM array — read it synchronously, don't retain it. Separate from the
    // FFT bands so beat detection never affects the visuals' tuning.
    @Volatile var pcmBeatSink: ((FloatArray) -> Unit)? = null

    // Fired on the GL thread on each Ableton Link beat (only when Link sync is on).
    // Used to drive haptics off Link's network clock instead of audio onset.
    @Volatile var onLinkBeat: (() -> Unit)? = null

    // Diagnostic tap (GL thread): a detected drop/build surge (any mode). Drives
    // the Settings "Drop" diagnostic light. (Bar position is read from BeatPulse.)
    @Volatile var onDrop: (() -> Unit)? = null

    // HDR bloom + theme post-processing. Glow strength and theme are read from
    // the global GlowSettings/ThemeSettings. When glow is off AND the theme is
    // the default, a non-bypass scene draws straight to the screen (zero overhead).
    private val post = PostProcessor()

    // Beat-driven HDR "punch": on each kick it spikes the bloom/streak/exposure
    // so highlights flash to peak luminance (extra nits on HDR, clean white on
    // SDR), then decays. Respects the user's Beat Sensitivity preset + source.
    private val hdrBeat = BeatDetector()
    private var hdrPunch = 0f
    private var lastFrameSec = 0f

    // Shared beat-bus producer state (GL thread only). Measures audio presence
    // from the raw PCM so the gate is identical across visuals, lights, haptics.
    private var bassLp = 0f             // one-pole low-pass state (bass/treble split)
    private var levelFollow = 0f        // peak-hold + decay loudness follower
    private var bassRatioSmooth = 0.5f  // lightly smoothed bass fraction
    private var slowDb = 0f             // asymmetric rolling dB baseline (drop detection)
    private var dropSurge = 0f          // drop/build surge envelope, decays toward 0
    private var dropCooldown = 0f       // seconds until another surge may fire

    // Performance diagnostics (written on GL thread, read on UI thread).
    @Volatile var fps = 0f
    @Volatile var frameTimeMs = 0f

    // Burn-in idle gate state (u_burnInProtectAlpha, folded into `dim`).
    // Toggleable from the settings menu; set on the UI thread, read on GL thread.
    @Volatile var burnInEnabled = true
    private var lastActiveSec = 0f
    private var burnInAlpha = 1f

    // Hardware load measurement
    private var cpuThreadTimeStart = 0L

    // First-run intro (VELO particle cloud). Set before the surface is created;
    // read on the GL thread. When disabled (reduced-motion) the app boots
    // straight into the visualizer.
    @Volatile var introEnabled = true
    // Fired once on the GL thread when the intro completes (or is skipped/disabled).
    @Volatile var onIntroFinished: (() -> Unit)? = null
    @Volatile var introActive = false
        private set
    private val introScene = IntroLogoScene()
    private var introStartSec = -1f
    private var introNotified = false

    /** Number of selectable scenes (for index wrapping by the view). */
    val sceneCount: Int get() = scenes.size

    /** True if the active (or transitioning-to) scene opts out of the menu blur. */
    val activeSceneSuppressesBlur: Boolean
        get() = scenes.getOrNull(current)?.suppressMenuBlur == true ||
            scenes.getOrNull(target)?.suppressMenuBlur == true

    /**
     * Transition to an explicit scene index (used by both swipe and the menu's
     * visualizer selector). Ignored if already there or mid-transition.
     */
    fun requestScene(index: Int, transitionSec: Float = TRANSITION_SEC) {
        val clamped = ((index % scenes.size) + scenes.size) % scenes.size
        if (transitionStart >= 0f) {
            // Mid-transition: remember the most recent request and apply it once the
            // current fade finishes, so the renderer always lands where the UI points.
            pendingTarget = clamped
            return
        }
        if (clamped == current) return
        target = clamped
        activeTransitionSec = transitionSec
        transitionStart = nowSec()
        swapped = false
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)     // pure black
        startNanos = System.nanoTime()
        lastFrameSec = 0f
        lastActiveSec = 0f
        transitionStart = -1f
        pendingTarget = -1
        swapped = false
        loadFrameCounter = 0
        hdrPunch = 0f

        NativeBridge.nativeInitializeSharedBuffer(sharedAudioBuffer)

        // Clear existing scenes to force them to re-initialize their GL resources
        // (programs, VBOs) in getOrInitScene now that the context is new.
        scenes.fill(null)

        post.onCreated()
        introActive = introEnabled && !introPlayedThisProcess
        introStartSec = -1f
        introNotified = false
        if (introActive) {
            // NB: introPlayedThisProcess is set on *completion* (drawIntro), not here,
            // so an intro interrupted by a surface teardown (e.g. backgrounding) replays
            // instead of being skipped half-way.
            introScene.onCreated()
        }
        Log.i(TAG, "Surface created. Sample rate=${NativeBridge.nativeGetSampleRate()}")
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        // Foldable resize: reset viewport and recompute aspect for every scene
        // so geometry never stretches when the device folds/unfolds.
        surfaceW = width
        surfaceH = height
        aspect = width.toFloat() / height.toFloat()
        GLES20.glViewport(0, 0, width, height)
        post.resize(width, height)
        
        // Re-populate background load queue on surface reset
        scenesToLoad.clear()
        for (i in scenes.indices) {
            if (scenes[i] == null) scenesToLoad.add(i)
            else scenes[i]?.onResize(width, height, aspect)
        }

        introScene.onResize(width, height, aspect)
        Log.i(TAG, "Surface resized to ${width}x$height (aspect=$aspect)")
    }

    override fun onDrawFrame(gl: GL10?) {
        cpuThreadTimeStart = android.os.SystemClock.currentThreadTimeMillis()

        // Pull the freshest audio data from the native ring buffer.
        NativeBridge.fillSharedAudioBuffer()

        // Populate the legacy PCM array for sinks/scenes that still need it.
        sharedAudioFloatBuffer.position(0)
        sharedAudioFloatBuffer.get(pcm, 0, pcm.size)

        val t = nowSec()
        val dt = (t - lastFrameSec).coerceIn(0f, 0.1f)
        lastFrameSec = t

        // Single FFT: bands + 128-bin spectrum in one native call.
        SpectrumData.sharedBuffer = sharedAudioBuffer
        NativeBridge.fillLatestAll(bands, SpectrumData.magnitudes, SpectrumData.peaks, dt)

        // ---- Shared beat bus (producer) ------------------------------------
        // Measure audio *presence* from the raw PCM: absolute peak (loudness) and
        // a volume-independent bass/treble balance (colour). This is the single
        // gate that decides whether a beat "counts" — for the visuals, the Hue
        // lights and haptics alike — so they all react to the music identically.
        run {
            var peak = 0f; var lp = bassLp; var bassAcc = 0f; var trebAcc = 0f
            for (s in pcm) {
                val a = if (s < 0f) -s else s; if (a > peak) peak = a
                lp += BASS_LP_A * (s - lp); bassAcc += lp * lp
                val tre = s - lp; trebAcc += tre * tre
            }
            bassLp = lp
            val inv = 1f / pcm.size
            val bassRms = sqrt(bassAcc * inv); val trebRms = sqrt(trebAcc * inv)
            // Peak-hold + slow decay so the level reads like a steady VU bar.
            levelFollow = if (peak > levelFollow) peak else levelFollow * LEVEL_DECAY
            BeatBus.level = levelFollow
            // The bass ratio is meaningless in silence (≈0.5 noise vs noise), so 0 it.
            val rawRatio = if (peak < MIC_NOISE_FLOOR) 0f else bassRms / (bassRms + trebRms + 1e-6f)
            bassRatioSmooth += 0.25f * (rawRatio - bassRatioSmooth)
            BeatBus.bassRatio = bassRatioSmooth
            // The gate: smoothstep(base, full, level) → 0..1 intensity.
            val base = BeatSettings.levelBase; val full = BeatSettings.levelFull
            val g = ((levelFollow - base) / (full - base + 1e-6f)).coerceIn(0f, 1f)
            BeatBus.loudness = g * g * (3f - 2f * g)
        }

        // Drop/build surge: the big moment the beat grid can't see. We track the
        // level in dB against an asymmetric rolling baseline — quick to fall into a
        // breakdown, slow to rise — and fire when the level leaps well above it. A
        // *relative* dB jump self-calibrates to any volume or dynamic range; a slow
        // set-volume ride is absorbed by the baseline, so only a fast lift fires.
        val levelDb = 20f * log10(max(BeatBus.level, DROP_DB_FLOOR))
        val tau = if (levelDb < slowDb) DROP_FALL_TAU else DROP_RISE_TAU
        slowDb += (levelDb - slowDb) * (dt / tau).coerceIn(0f, 1f)
        dropCooldown = (dropCooldown - dt).coerceAtLeast(0f)
        if (levelDb - slowDb > DROP_DB_JUMP && levelDb > DROP_DB_MIN && dropCooldown <= 0f) {
            dropSurge = 1f
            dropCooldown = DROP_COOLDOWN
            slowDb = levelDb            // consume the jump so the loud section can't re-fire
            onDrop?.invoke()
        }
        dropSurge = (dropSurge - dt / DROP_DECAY).coerceAtLeast(0f)
        BeatPulse.surge = dropSurge

        // Beat decision. Source = Ableton Link's network clock when sync is on,
        // otherwise the audio onset detector. Either way the beat only "counts"
        // when the gate is open, and the punch is scaled by loudness so quiet
        // passages pulse subtly instead of snapping off at a hard threshold.
        var barPhaseNow = 0f
        if (LinkSync.enabled) {
            val rawBeat = NativeBridge.nativeLinkPollBeats() > 0
            barPhaseNow = NativeBridge.nativeLinkBarPhase().toFloat().coerceIn(0f, 1f)
            // Manual downbeat alignment: Link knows the beat grid but not where the
            // musical "1" is, so let the user shift the bar by whole beats.
            if (LinkSync.barOffsetBeats != 0) {
                barPhaseNow = (barPhaseNow + LinkSync.barOffsetBeats * 0.25f) % 1f
            }
            // Phase-locked envelope: (1 - phase)² peaks on each beat and decays to
            // the next, so the visuals stay rock-solid to the grid even if the
            // discrete poll jitters. Gated by loudness.
            val phase = NativeBridge.nativeLinkBeatPhase().toFloat().coerceIn(0f, 1f)
            val decay = (1f - phase) * (1f - phase)
            val env = if (!LinkSync.anticipateBeat) decay else {
                // Anticipation: because Link tells us when the next beat lands, build
                // a subtle swell over the tail of the beat, then let the hit snap to
                // full and decay. The mic can't do this — it has no future to read.
                val a = ((phase - BEAT_ANTIC_START) / (1f - BEAT_ANTIC_START)).coerceIn(0f, 1f)
                val antic = a * a * (3f - 2f * a) * BEAT_ANTIC_AMOUNT
                if (antic > decay) antic else decay
            }
            hdrPunch = env * BeatBus.loudness
            if (rawBeat) {
                onLinkBeat?.invoke()                       // ungated: diagnostic dot + Hue timing
                if (BeatBus.gateOpen) BeatBus.beatCount++  // gated: visuals + haptics
            }
        } else {
            val beat = hdrBeat.update(pcm) && BeatBus.gateOpen
            if (beat) {
                hdrPunch = BeatBus.loudness
                BeatBus.beatCount++
            } else {
                hdrPunch = (hdrPunch - dt * PUNCH_FALL).coerceAtLeast(0f)
            }
        }

        // Publish the beat for beat-reactive scenes (e.g. Beat Pulse).
        BeatPulse.envelope = hdrPunch
        BeatPulse.beatCount = BeatBus.beatCount
        BeatPulse.linkActive = LinkSync.enabled
        BeatPulse.barPhase = barPhaseNow

        // Forward the bands to any tap (Hue light sync) — cheap, non-blocking.
        bandsSink?.invoke(bands[0], bands[1], bands[2])
        // Tick the per-frame tap (haptics) — it reads the gated beat off BeatBus.
        pcmBeatSink?.invoke(pcm)

        if (dt > 0f) {
            frameTimeMs = dt * 1000f
            fps = (fps * 0.9f) + ((1f / dt) * 0.1f)
        }

        // First-run intro owns the frame until the VELO cloud has shattered into
        // the live visualizer; everything below is the normal render path.
        if (introActive) {
            drawIntro(t)
            return
        }

        // u_burnInProtectAlpha is folded into `dim` so it dims every scene's
        // final color uniformly (incl. any static baseline) with no extra plumbing.
        // NB: updateTransition may swap `current`, so resolve the scene afterward.
        val dim = updateTransition(t) * updateBurnInGate(t)
        // A scene picked mid-transition was deferred; now the fade is done, go there.
        if (transitionStart < 0f && pendingTarget >= 0) {
            val next = pendingTarget
            pendingTarget = -1
            requestScene(next)
        }
        val scene = getOrInitScene(current)

        val glow = GlowSettings.strength
        val theme = ThemeSettings.preset
        val usePost = post.isReady && !scene.bypassPostProcessing &&
            (glow.enabled || ThemeSettings.isGraded)
        if (usePost) {
            post.beginScene()                    // render into the offscreen HDR buffer
        } else {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            GLES20.glViewport(0, 0, surfaceW, surfaceH)
        }

        // CRITICAL state isolation: every frame we restore a known-clean GL
        // baseline BEFORE the scene draws. This guarantees the Fluid scene's
        // additive blending (and any depth/cull state) can never leak between
        // scenes (or into the post passes).
        resetGlState()
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        scene.draw(pcm, bands, t, dim)

        // Slowly warm up the other scenes in the background (only after intro)
        tryBackgroundLoad()

        if (usePost) {
            // Instrument scenes (oscilloscope, bars, spectrum…) opt out of ALL the
            // musical-accent layers so their glow stays an honest readout of the
            // signal. Enriched (reactive/immersive) scenes get the beat punch; the
            // bar breath + drop surge are the opt-in EXPERIMENTAL extras on top.
            val enriched = scene.respondsToBeat
            val extras = enriched && LinkSync.experimentalEnrich
            val punch = if (enriched) hdrPunch else 0f
            val surge = if (extras) dropSurge else 0f
            // Bar-synced "breath": a slow bloom swell anchored to the musical
            // downbeat (Link only) — phrasing you can't get from sound alone.
            val breath = if (extras && BeatPulse.linkActive)
                (0.5f + 0.5f * cos(barPhaseNow * TWO_PI)) * BAR_BREATH_AMOUNT * BeatBus.loudness
            else 0f
            // Bloom carries the punch (0 when glow is off); exposure lifts gently.
            val bloomI = if (glow.enabled)
                (1.0f + punch * 1.8f + breath + surge * SURGE_BLOOM) * glow.intensity else 0f
            val exposure = 1.0f + punch * 0.3f + surge * SURGE_EXPOSURE
            post.present(
                bloomI, exposure,
                theme.hueShift, theme.saturation, theme.tintR, theme.tintG, theme.tintB,
            )
        }

        // Finalise load measurements
        val cpuWorkUs = (android.os.SystemClock.currentThreadTimeMillis() - cpuThreadTimeStart) * 1000L
        NativeBridge.nativeUpdateHardwareLoad(cpuWorkUs, 0, false)
    }

    /**
     * Whether the intro will actually run on the next surface creation — true on a
     * cold start with the intro enabled, false on a warm resume (already played
     * this process) or when reduced-motion disabled it. Lets the Activity decide
     * to hold the runtime-permission dialog back until the intro has finished.
     */
    fun willPlayIntro(): Boolean = introEnabled && !introPlayedThisProcess

    /**
     * Skip the intro: jump straight to the shatter phase so the dissolve still
     * plays briefly, then hands off to the visualizer. Runs on the GL thread.
     */
    fun skipIntro() {
        if (!introActive) return
        val t = nowSec()
        introStartSec = t - (INTRO_IGNITE_SEC + INTRO_HOLD_SEC)
    }

    /**
     * Drive one intro frame: ignite (chaos → glyph) → breathe → shatter. During
     * the shatter phase the live default scene fades up behind the particles, so
     * the wordmark literally dissolves into the visualizer. Forces the HDR bloom
     * path on regardless of the user's glow setting.
     */
    private fun drawIntro(t: Float) {
        if (introStartSec < 0f) introStartSec = t
        val e = t - introStartSec

        val assembleRaw = (e / INTRO_IGNITE_SEC).coerceIn(0f, 1f)
        val inv = 1f - assembleRaw
        val assemble = 1f - inv * inv * inv                       // easeOutCubic
        val disperse = ((e - INTRO_IGNITE_SEC - INTRO_HOLD_SEC) / INTRO_SHATTER_SEC).coerceIn(0f, 1f)

        val holdAmt = when {
            e < INTRO_IGNITE_SEC -> 0f
            e < INTRO_IGNITE_SEC + INTRO_HOLD_SEC -> 1f
            else -> 1f - disperse
        }
        val intensity = when {
            e < INTRO_IGNITE_SEC -> assembleRaw
            e < INTRO_IGNITE_SEC + INTRO_HOLD_SEC -> 1f + 0.10f * kotlin.math.sin(e * 3.0f)
            else -> (1f - disperse) * (1f + 0.7f * kotlin.math.exp(-disperse * 5f))  // flash then fade
        }

        val usePost = post.isReady
        if (usePost) {
            post.beginScene()
        } else {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            GLES20.glViewport(0, 0, surfaceW, surfaceH)
        }
        resetGlState()
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        // Reveal the visualizer behind the falling letters during the shatter.
        if (disperse > 0f) {
            getOrInitScene(DEFAULT_SCENE).draw(pcm, bands, t, disperse * disperse)
            resetGlState()
        }

        // Background loading is deferred until AFTER intro finishes (handled in tryBackgroundLoad)
        tryBackgroundLoad()

        introScene.draw(assemble, holdAmt, disperse, intensity, t)

        if (usePost) {
            val theme = ThemeSettings.preset
            post.present(
                INTRO_BLOOM, 1f,
                theme.hueShift, theme.saturation, theme.tintR, theme.tintG, theme.tintB,
            )
        }

        if (e >= INTRO_TOTAL_SEC) {
            introActive = false
            introPlayedThisProcess = true       // only now is the intro truly "played"
            current = DEFAULT_SCENE
            target = DEFAULT_SCENE
            introScene.release()
            if (!introNotified) {
                introNotified = true
                onIntroFinished?.invoke()
            }
        }
    }

    /**
     * Burn-in idle gate: after [IDLE_DELAY_SEC] of near-silence, fade toward
     * [IDLE_MIN_ALPHA] over [IDLE_RAMP_SEC]; snap back to full instantly on any
     * transient above [SILENCE_PEAK].
     */
    private fun updateBurnInGate(t: Float): Float {
        if (!burnInEnabled) {
            lastActiveSec = t        // keep the clock current so it doesn't snap-dim when re-enabled
            burnInAlpha = 1f
            return 1f
        }

        var peak = 0f
        for (s in pcm) { val a = abs(s); if (a > peak) peak = a }

        burnInAlpha = if (peak > SILENCE_PEAK) {
            lastActiveSec = t
            1f                                              // instant snap-back
        } else {
            val idle = t - lastActiveSec
            if (idle <= IDLE_DELAY_SEC) 1f
            else {
                val into = ((idle - IDLE_DELAY_SEC) / IDLE_RAMP_SEC).coerceIn(0f, 1f)
                1f - into * (1f - IDLE_MIN_ALPHA)           // 1.0 -> 0.15
            }
        }
        return burnInAlpha
    }

    /** Hard-reset blend / depth / cull to the baseline the legacy scenes expect. */
    private fun resetGlState() {
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glDepthMask(true)
        GLES20.glDisable(GLES20.GL_CULL_FACE)
    }

    /** Drives the fade-out → swap → fade-in and returns the brightness to use. */
    private fun updateTransition(t: Float): Float {
        if (transitionStart < 0f) return 1f

        val elapsed = t - transitionStart
        val half = activeTransitionSec * 0.5f

        return when {
            elapsed < half -> 1f - elapsed / half          // fade out outgoing
            elapsed < activeTransitionSec -> {
                if (!swapped) {
                    scenes[current]?.onDeactivate()         // explicit cleanup of the outgoing scene
                    current = target
                    swapped = true
                }
                (elapsed - half) / half                    // fade in incoming
            }
            else -> {                                      // done
                current = target
                transitionStart = -1f
                1f
            }
        }
    }

    private fun nowSec(): Float = (System.nanoTime() - startNanos) / 1_000_000_000f
}
