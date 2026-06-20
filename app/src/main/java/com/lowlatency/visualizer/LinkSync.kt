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
}
