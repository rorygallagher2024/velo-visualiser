package com.lowlatency.visualizer.gl

import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.util.Log
import com.lowlatency.visualizer.BeatDetector
import com.lowlatency.visualizer.BeatPulse
import com.lowlatency.visualizer.GlowSettings
import com.lowlatency.visualizer.LinkSync
import com.lowlatency.visualizer.NativeBridge
import com.lowlatency.visualizer.ThemeSettings
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.abs

/**
 * Owns the visual scenes and drives the per-frame data pull.
 *
 * Every frame it grabs the latest PCM window and FFT bands straight from the
 * native engine (zero-alloc fills), then renders the active scene. A swipe
 * triggers a short, smooth fade-out → swap → fade-in transition between scenes.
 */
class VisualizerRenderer : GLSurfaceView.Renderer {

    companion object {
        private const val TAG = "VisualizerRenderer"
        const val DEFAULT_SCENE = 8       // Raw Oscilloscope — shown on startup
        private const val POINTS = 1024
        private const val TRANSITION_SEC = 0.45f   // total fade duration
        private const val PUNCH_FALL = 3.5f        // HDR beat-punch decay rate (~0.3 s)

        // OLED burn-in idle gate (deliberately gentle — the UNPROCESSED mic is
        // quiet, so the threshold sits just above its noise floor and the delay
        // is long enough not to dim during normal pauses in speech/music).
        private const val SILENCE_PEAK = 0.008f    // raw-PCM peak below this = silent
        private const val IDLE_DELAY_SEC = 20.0f   // silence before dimming starts
        private const val IDLE_RAMP_SEC = 5.0f     // fade-out duration
        private const val IDLE_MIN_ALPHA = 0.45f   // dimmed floor (gentle, still legible)
    }

    // Reused per-frame — no allocation in the loop.
    private val pcm = FloatArray(POINTS)
    private val bands = FloatArray(3)

    private val scenes = arrayOf<GlScene>(
        OscilloscopeScene(),       // 0
        TunnelScene(),             // 1
        FluidScene(),              // 2
        LaserArrayScene(),         // 3
        CircularSpectrumScene(),   // 4
        BarSpectrumScene(),        // 5
        SpectralBloomScene(),      // 6
        StarscapeScene(),          // 7
        RawScopeScene(),           // 8
        SpectrogramScene(),        // 9
        BeatFireworksScene(),      // 10
        PhyllotaxisScene(),        // 11
        ElectricIrisScene(),       // 12
        MandalaPulseScene(),       // 13
        AudioWebScene(),           // 14
        TopographicRidgeScene(),   // 15
        LedMatrixScene(),          // 16 — TE "Pocket LED" dot-matrix spectrum
        MechanicalMeterScene(),    // 17 — TE "Mechanical Meter" analog needle
        BeatPulseScene(),          // 18 — beat-emphasis demo (Ableton Link)
        MandelboxScene(),          // 19 — "Fractal Cathedral" ray-marched mandelbox
        ReactionDiffusionScene(),  // 20 — Gray-Scott Turing patterns (FBO ping-pong)
        ChladniPlateScene(),       // 21 — "Cymatics" dominant-frequency Chladni plate
        StrangeAttractorScene()    // 22 — Aizawa attractor particle cloud (compute)
    )
    private var current = DEFAULT_SCENE
    private var target = DEFAULT_SCENE

    private var surfaceW = 1
    private var surfaceH = 1
    private var aspect = 1f

    private var startNanos = 0L
    private var transitionStart = -1f          // <0 => no transition in progress
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

    // Performance diagnostics (written on GL thread, read on UI thread).
    @Volatile var fps = 0f
    @Volatile var frameTimeMs = 0f

    // Burn-in idle gate state (u_burnInProtectAlpha, folded into `dim`).
    // Toggleable from the settings menu; set on the UI thread, read on GL thread.
    @Volatile var burnInEnabled = true
    private var lastActiveSec = 0f
    private var burnInAlpha = 1f

    /** Number of selectable scenes (for index wrapping by the view). */
    val sceneCount: Int get() = scenes.size

    /**
     * Transition to an explicit scene index (used by both swipe and the menu's
     * visualizer selector). Ignored if already there or mid-transition.
     */
    fun requestScene(index: Int) {
        if (transitionStart >= 0f) return        // already mid-transition
        val clamped = ((index % scenes.size) + scenes.size) % scenes.size
        if (clamped == current) return
        target = clamped
        transitionStart = nowSec()
        swapped = false
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)     // pure black
        startNanos = System.nanoTime()
        post.onCreated()
        scenes.forEach { it.onCreated() }
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
        scenes.forEach { it.onResize(width, height, aspect) }
        Log.i(TAG, "Surface resized to ${width}x$height (aspect=$aspect)")
    }

    override fun onDrawFrame(gl: GL10?) {
        // Pull the freshest audio data from the native ring buffer + FFT.
        NativeBridge.fillLatestAudioBuffer(pcm)
        NativeBridge.fillLatestFrequencyBands(bands)

        // Forward the bands to any tap (Hue light sync) — cheap, non-blocking.
        bandsSink?.invoke(bands[0], bands[1], bands[2])
        // Forward raw PCM to the beat tap (haptics) for bass-onset detection.
        pcmBeatSink?.invoke(pcm)

        val t = nowSec()
        val dt = (t - lastFrameSec).coerceIn(0f, 0.1f)
        lastFrameSec = t

        if (dt > 0f) {
            frameTimeMs = dt * 1000f
            fps = fps * 0.9f + (1f / dt) * 0.1f
        }

        // HDR beat-punch envelope (spikes on a beat, decays smoothly). The beat
        // source is Ableton Link's network clock when sync is on, otherwise the
        // audio onset detector. Link beats also drive haptics (mic still drives
        // the visuals themselves).
        val beatNow = if (LinkSync.enabled) {
            NativeBridge.nativeLinkPollBeats() > 0
        } else {
            hdrBeat.update(pcm)
        }
        if (beatNow) {
            hdrPunch = 1f
            if (LinkSync.enabled) onLinkBeat?.invoke()
        } else {
            hdrPunch = (hdrPunch - dt * PUNCH_FALL).coerceAtLeast(0f)
        }

        // Publish the beat for beat-reactive scenes (e.g. Beat Pulse).
        BeatPulse.envelope = hdrPunch
        if (beatNow) BeatPulse.beatCount++

        // u_burnInProtectAlpha is folded into `dim` so it dims every scene's
        // final color uniformly (incl. any static baseline) with no extra plumbing.
        // NB: updateTransition may swap `current`, so resolve the scene afterward.
        val dim = updateTransition(t) * updateBurnInGate(t)
        val scene = scenes[current]

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

        if (usePost) {
            // Bloom carries the punch (0 when glow is off); exposure lifts gently.
            val bloomI = if (glow.enabled) (1.0f + hdrPunch * 1.8f) * glow.intensity else 0f
            val exposure = 1.0f + hdrPunch * 0.3f
            post.present(
                bloomI, exposure,
                theme.hueShift, theme.saturation, theme.tintR, theme.tintG, theme.tintB,
            )
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
        val half = TRANSITION_SEC * 0.5f

        return when {
            elapsed < half -> 1f - elapsed / half          // fade out outgoing
            elapsed < TRANSITION_SEC -> {
                if (!swapped) {
                    scenes[current].onDeactivate()         // explicit cleanup of the outgoing scene
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
