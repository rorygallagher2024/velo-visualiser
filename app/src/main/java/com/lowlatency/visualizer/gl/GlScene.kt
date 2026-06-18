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
     * @param pcm   latest PCM window (length = render window)
     * @param bands [low, mid, high] FFT energies, each 0..1
     * @param timeSec elapsed seconds since start (for animation)
     * @param dim   global brightness 0..1 (transition fade)
     */
    fun draw(pcm: FloatArray, bands: FloatArray, timeSec: Float, dim: Float)

    /**
     * Called once when the renderer transitions away from this scene. Scenes
     * that touch non-default GL state (e.g. additive blending) should undo it
     * here. Runs on the GL thread. Default: no-op.
     */
    fun onDeactivate() {}
}
