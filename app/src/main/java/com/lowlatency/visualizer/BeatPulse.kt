package com.lowlatency.visualizer

/**
 * App-wide beat signal published by [com.lowlatency.visualizer.gl.VisualizerRenderer]
 * each frame so individual scenes can react to the beat without changing the
 * GlScene draw signature. The source is Ableton Link's clock when sync is on,
 * otherwise the audio onset detector — so a beat-reactive scene demos Link
 * directly.
 *
 * Both fields are written and read on the GL thread (renderer writes before it
 * calls scene.draw), so this is effectively single-threaded; `@Volatile` is just
 * belt-and-braces.
 */
object BeatPulse {
    /** Beat-punch envelope: snaps to 1.0 on a beat, decays smoothly toward 0. */
    @Volatile var envelope: Float = 0f

    /** Monotonic counter incremented once per beat (for discrete triggers). */
    @Volatile var beatCount: Int = 0
}
