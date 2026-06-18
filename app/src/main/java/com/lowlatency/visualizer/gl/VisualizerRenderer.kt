package com.lowlatency.visualizer.gl

import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.util.Log
import com.lowlatency.visualizer.NativeBridge
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
        private const val POINTS = 1024
        private const val TRANSITION_SEC = 0.45f   // total fade duration

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
        TopographicScene(),        // 4
        CircularSpectrumScene()    // 5
    )
    private var current = 0
    private var target = 0

    private var surfaceW = 1
    private var surfaceH = 1
    private var aspect = 1f

    private var startNanos = 0L
    private var transitionStart = -1f          // <0 => no transition in progress
    private var swapped = false

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
        scenes.forEach { it.onResize(width, height, aspect) }
        Log.i(TAG, "Surface resized to ${width}x$height (aspect=$aspect)")
    }

    override fun onDrawFrame(gl: GL10?) {
        // CRITICAL state isolation: every frame we restore a known-clean GL
        // baseline BEFORE the scene draws. This guarantees the Fluid scene's
        // additive blending (and any depth/cull state) can never leak into the
        // Oscilloscope/Tunnel — they render exactly as they did pre-Phase-4.
        resetGlState()
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        // Pull the freshest audio data from the native ring buffer + FFT.
        NativeBridge.fillLatestAudioBuffer(pcm)
        NativeBridge.fillLatestFrequencyBands(bands)

        val t = nowSec()
        // u_burnInProtectAlpha is folded into `dim` so it dims every scene's
        // final color uniformly (incl. any static baseline) with no extra plumbing.
        val dim = updateTransition(t) * updateBurnInGate(t)
        scenes[current].draw(pcm, bands, t, dim)
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
