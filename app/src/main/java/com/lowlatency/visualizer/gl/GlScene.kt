package com.lowlatency.visualizer.gl

/**
 * One self-contained visual. The renderer owns several scenes and swaps the
 * active one on a swipe. Every callback runs on the GL thread.
 *
 * `dim` is a global 0..1 brightness applied by the renderer to crossfade
 * smoothly between scenes (fade out → swap → fade in). Each scene must scale
 * its final color by `dim` so transitions look clean against the black clear.
 */
interface GlScene {
    /** Allocate GL resources (programs, buffers). Called once on GL thread. */
    fun onCreated()

    /** Surface size changed — recompute viewport-dependent uniforms (aspect). */
    fun onResize(width: Int, height: Int, aspect: Float)

    /**
     * Draw one frame.
     * @param pcm     latest PCM window
     * @param bands   [low, mid, high] FFT energies, each 0..1
     * @param timeSec elapsed seconds since start (for animation)
     * @param dim     global brightness 0..1 (transition fade)
     */
    fun draw(pcm: FloatArray, bands: FloatArray, timeSec: Float, dim: Float)

    /**
     * Called once when the renderer transitions away from this scene. Scenes
     * that touch non-default GL state (e.g. additive blending) should undo it
     * here. Runs on the GL thread. Default: no-op.
     */
    fun onDeactivate() {}

    /**
     * Called when the audio source changes (e.g. mic to internal). Scenes that
     * maintain long-term state (like auto-gain reference levels) should reset
     * it here so they don't get stuck using a loud reference on a quiet source.
     * Runs on the GL thread. Default: no-op.
     */
    fun onAudioSourceChanged() {}

    /**
     * If true, the renderer draws this scene straight to the screen and skips
     * the bloom post-processing pipeline. Use for scenes that must stay
     * pixel-pure (e.g. the Raw Oscilloscope). Default: false (bloom applies).
     */
    val bypassPostProcessing: Boolean get() = false

    /**
     * Whether this scene receives the musical-accent layers the renderer adds on
     * top of the raw audio: the beat-driven HDR punch, the drop/build surge, and
     * the Ableton Link bar-synced "breath". Default: true.
     *
     * The faithful "instrument" scenes (oscilloscope, bars, spectrum, meter…) set
     * this false so they stay an honest representation of the sound: their glow
     * tracks the actual signal, never an imposed beat or grid — which matters most
     * with Ableton Link, where the beat is a network clock, not the audio. In
     * other words, this is the single flag that marks a scene as "enriched /
     * reactive" versus "pure instrument".
     */
    val respondsToBeat: Boolean get() = true

    /**
     * Opt out of the settings-sheet canvas blur for this scene. Android's
     * [android.graphics.RenderEffect] blur on the GL `SurfaceView` presents the
     * surface vertically flipped while it's active, so a scene with bright content
     * pinned to one screen edge (e.g. Spectral Canyon's front lip) shows a
     * mirrored line at the opposite edge when the menu opens. Such scenes set this
     * true; the menu then dims with the scrim only (no blur) for them. Default: false.
     */
    val suppressMenuBlur: Boolean get() = false
}
