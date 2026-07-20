package com.lowlatency.visualizer

/**
 * App-wide flag for "4/4 Music Mode", read on the GL thread by
 * [com.lowlatency.visualizer.gl.VisualizerRenderer]. A single global holder
 * (like [LinkSync]/[BeatSettings]) avoids plumbing it through the surface view.
 *
 * When enabled, the beat that drives the visuals, the lights and the haptics is
 * grid-locked by [FourFourTracker] to a steady four-to-the-floor signature
 * instead of firing on every raw bass onset, so stray hits between the beats are
 * ignored. It is an audio-only alternative to Ableton Link for steady electronic
 * music: no network, no DAW, just the sound in the room. Link still wins when it
 * is on, since its shared clock is exact and this is an estimate.
 */
object FourFourSync {
    @Volatile var enabled: Boolean = false

    /** Live tracker status for the diagnostics overlay: the locked tempo in BPM,
     *  or 0 while it is still searching / not confident. Written by the renderer. */
    @Volatile var statusBpm: Float = 0f
}
