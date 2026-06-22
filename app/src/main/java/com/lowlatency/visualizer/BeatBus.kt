package com.lowlatency.visualizer

/**
 * Single source of truth for the *gated* beat, shared by the visuals, the Hue
 * light strobe and haptics so they all react to the music identically.
 *
 * The producer is the GL render thread ([com.lowlatency.visualizer.gl.VisualizerRenderer]),
 * which each frame measures audio *presence* and decides whether a beat counts.
 * Consumers read these volatiles from their own threads (the Hue sender thread,
 * the GL thread) — single writer, many readers, so `@Volatile` is sufficient.
 *
 * The rule: a beat only "counts" when there is enough audio present. [loudness]
 * expresses that gate as a 0..1 intensity (0 = below the floor → silent), so
 * consumers can scale brightness/punch smoothly instead of switching on/off.
 * This is what keeps the lights and the visuals from beating through a quiet
 * passage while the track (or Ableton Link's clock) keeps running.
 */
object BeatBus {
    /** Smoothed absolute mic loudness (peak-hold + decay), raw presence pre-gate. */
    @Volatile var level: Float = 0f

    /** Volume-independent bass fraction 0..1 — drives the Hue strobe colour. */
    @Volatile var bassRatio: Float = 0.5f

    /** Gate intensity: smoothstep(levelBase, levelFull, level). 0 ⇒ no beat. */
    @Volatile var loudness: Float = 0f

    /** True while there is enough audio present for a beat to count. */
    val gateOpen: Boolean get() = loudness > 0f

    /** Gated discrete beat counter — incremented once per beat that passes the gate. */
    @Volatile var beatCount: Int = 0
}
