package com.lowlatency.visualizer

/**
 * App-wide flag for Ableton Link tempo/beat sync, read on the GL thread by
 * [com.lowlatency.visualizer.gl.VisualizerRenderer]. A single global holder
 * (like [GlowSettings]/[BeatSettings]) avoids plumbing it through the surface view.
 *
 * When enabled, the beat that drives the HDR bloom punch and haptics comes from
 * Link's network clock instead of audio onset detection — the microphone still
 * drives the visuals themselves; Link only controls *when* the beat lands.
 *
 * The actual Link session lives in native code ([NativeBridge.nativeLinkSetEnabled]);
 * this just mirrors the on/off state for the render loop to branch on cheaply.
 */
object LinkSync {
    @Volatile var enabled: Boolean = false

    /**
     * When true (and Link is on), the visual beat-punch *swells into* each beat
     * before snapping on the hit — an anticipatory build the mic can't do, since
     * only Link's shared clock knows where the next beat lands. User-toggleable.
     */
    @Volatile var anticipateBeat: Boolean = true

    /**
     * Manual downbeat alignment, in whole beats (0..3). Link shares the beat grid
     * and tempo but NOT where the musical bar "1" sits, so its phase-0 can land on
     * the "wrong" beat. This shifts the bar reference (BAR light + bar-synced
     * breath) by whole beats so the user can align it to the music. User-set.
     */
    @Volatile var barOffsetBeats: Int = 0
}
