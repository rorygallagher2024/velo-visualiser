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

    // --- Ableton Link enrichment (only meaningful while [linkActive]) ---

    /** True while Ableton Link is on — scenes gate their Link-only extras on this. */
    @Volatile var linkActive: Boolean = false

    /** Phase within the musical bar, 0..1 (one bar = 4 beats). Grid-locked to the
     *  DAW, so scenes can drive slow motion (rotation, hue, drift) that completes
     *  once per bar. 0 when Link is off. */
    @Volatile var barPhase: Float = 0f

    /** Drop/build "surge" envelope 0..1: rises when the mix jumps from a quiet
     *  passage into a loud one (a drop), then decays. Mic-driven, so it works in
     *  both modes; it marks the big moment the beat grid can't. */
    @Volatile var surge: Float = 0f
}
