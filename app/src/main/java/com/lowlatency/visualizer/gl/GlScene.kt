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
     * @param pcm          latest PCM window (legacy array)
     * @param bands        [low, mid, high] FFT energies
     * @param magnitudes   128-bin spectrum magnitudes
     * @param peaks        128-bin spectrum peaks
     * @param timeSec      elapsed seconds
     * @param dim          global brightness 0..1
     * @param sharedBuffer shared DirectByteBuffer containing the PCM window (zero-copy)
     */
    fun draw(
        pcm: FloatArray,
        bands: FloatArray,
        magnitudes: FloatArray,
        peaks: FloatArray,
        timeSec: Float,
        dim: Float,
        sharedBuffer: java.nio.ByteBuffer? = null
    )

    /**
     * Called once when the renderer transitions away from this scene. Scenes
     * that touch non-default GL state (e.g. additive blending) should undo it
     * here. Runs on the GL thread. Default: no-op.
     */
    fun onDeactivate() {}

    /**
     * If true, the renderer draws this scene straight to the screen and skips
     * the bloom post-processing pipeline. Use for scenes that must stay
     * pixel-pure (e.g. the Raw Oscilloscope). Default: false (bloom applies).
     */
    val bypassPostProcessing: Boolean get() = false
}
