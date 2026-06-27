package com.lowlatency.visualizer

import kotlin.math.max
import kotlin.math.sqrt

/**
 * Shared, user-tunable light-*rendering* parameters. Every lighting integration's
 * sender thread (Hue/LIFX/Nanoleaf/WLED) reads this one object plus the global
 * [BeatBus], so all brands react to the music identically.
 *
 * Reactivity is shaped by a [Preset] — the quick choice in the Lighting Control
 * section — which bundles the base/punch/glow knobs. The Advanced sliders fine-tune
 * those same knobs, which flips the preset to [Preset.CUSTOM]. How *readily* a beat
 * triggers (the audio-presence gate) is the global Beat Sensitivity in
 * [BeatSettings], shared by visuals, lights and haptics; these settings only shape
 * how the lights *look* once a beat is decided.
 *
 * Read on sender threads (volatile), set from the UI thread.
 */
object LightingSettings {

    /** Reactivity presets, from gentle/ambient to hard club-strobe. */
    enum class Preset { SMOOTH, REACTIVE, STROBE, CUSTOM }

    // Hand-tuned defaults — the single source of truth for "reset to default".
    const val DEFAULT_COLOUR_SPLIT = 0.5f
    const val DEFAULT_HUE_LOOKAHEAD = 0f
    const val DEFAULT_LINK_BEAT_FLASH = true
    val DEFAULT_PRESET = Preset.REACTIVE

    // --- Colour / timing (orthogonal to the reactivity dynamics) ---
    /** When false, the lights skip the per-beat flash and rest on colour/glow
     *  while Ableton Link is on (the visuals + haptics still beat). */
    @Volatile var linkBeatFlashEnabled = DEFAULT_LINK_BEAT_FLASH
    /** Higher = less bass needed before the colour goes blue/purple (vs red). */
    @Volatile var colourSplit = DEFAULT_COLOUR_SPLIT
    /** Zigbee latency compensation: fire the light command this many ms early. */
    @Volatile var hueLookaheadMs = DEFAULT_HUE_LOOKAHEAD

    // --- Reactivity dynamics (set by the preset; exposed as Advanced sliders).
    //     Initial values = the default REACTIVE preset; applyPreset overrides them. ---
    /** Resting glow — how lit the lights sit between beats, in BOTH mic and Link
     *  modes (0 = fully dark between beats, e.g. Strobe). */
    @Volatile var restingGlow = 0.22f
    /** Steady-energy gain — how much the base tracks the mic volume. */
    @Volatile var audioBrightness = 0.32f
    /** Beat-punch strength — how hard a beat pops above the base. */
    @Volatile var audioFlash = 0.85f

    /** Active preset; moving any Advanced slider flips this to [Preset.CUSTOM]. */
    @Volatile var preset = DEFAULT_PRESET

    /** Apply a preset's bundle of dynamics (leaves colour/timing untouched). */
    fun applyPreset(p: Preset) {
        when (p) {
            // glow = resting/between-beat level (both modes), bright = steady-volume
            // gain (mic), flash = beat punch. They spread widely so the difference is
            // obvious: Smooth tracks volume with a gentle beat and a lit rest; Reactive
            // sits dim and punches hard; Strobe is dark between beats, pure flash.
            Preset.SMOOTH -> set(glow = 0.50f, bright = 0.75f, flash = 0.40f)
            Preset.REACTIVE -> set(glow = 0.22f, bright = 0.32f, flash = 0.85f)
            Preset.STROBE -> set(glow = 0.0f, bright = 0.0f, flash = 1.0f)
            Preset.CUSTOM -> Unit   // keep current values; just a label
        }
        preset = p
    }

    private fun set(glow: Float, bright: Float, flash: Float) {
        restingGlow = glow
        audioBrightness = bright
        audioFlash = flash
    }

    /** A slider moved — keep the values but mark the bundle as no-longer-a-preset. */
    fun markCustom() { preset = Preset.CUSTOM }

    /** Restore the hand-tuned defaults (colour/timing + the default preset). */
    fun resetToDefaults() {
        linkBeatFlashEnabled = DEFAULT_LINK_BEAT_FLASH
        colourSplit = DEFAULT_COLOUR_SPLIT
        hueLookaheadMs = DEFAULT_HUE_LOOKAHEAD
        applyPreset(DEFAULT_PRESET)
    }

    // --- Derived colour values (read on the sender thread) ---
    private val splitMid: Float get() = 0.75f + (0.40f - 0.75f) * colourSplit
    /** PCM bass ratio below this => full light-red. */
    val bassLo: Float get() = splitMid - 0.12f
    /** PCM bass ratio above this => full blue/purple. */
    val bassHi: Float get() = splitMid + 0.12f

    // --- Derived dynamics (read on the sender thread) ---
    /** Steady-energy gain 0 .. 1.5 — how much sustained volume lights the base.
     *  Crucially reaches 0 (Strobe), so the lights can be dark between beats. */
    private val energyGain: Float get() = audioBrightness * 1.5f
    /** Beat-punch weight 0 .. 1.8 — how hard a beat pops above the base. */
    private val beatPunch: Float get() = audioFlash * 1.8f

    /** Floor for the weakest *active* beat (shared so every brand flashes alike). */
    const val MIN_BEAT_AMP = 0.06f
    private const val LINK_GLOW_SCALE = 0.30f   // restingGlow → Link resting brightness
    private const val AUDIO_REST_SCALE = 0.12f  // restingGlow → mic-mode between-beat floor

    /** Resting/between-beat floor in mic mode, driven by [restingGlow]. */
    private val audioFloor: Float get() = restingGlow * AUDIO_REST_SCALE

    /** Beat-flash amplitude from the gated loudness (Link beat-strobe). */
    fun beatFlashAmp(loudness: Float): Float = MIN_BEAT_AMP + (1f - MIN_BEAT_AMP) * loudness

    /**
     * Audio mode (mic, Link off): a base that tracks the steady energy (scaled by
     * [energyGain]) plus a beat punch, resting on [audioFloor] (from restingGlow).
     * With glow + energyGain at 0 (Strobe) the base collapses to black between beats;
     * with a high gain/glow (Smooth) the lights ride the volume on a lit rest.
     * [flash] is the caller's decayed beat envelope; bands are 0..1. Shared by every brand.
     */
    fun audioBrightnessValue(low: Float, mid: Float, high: Float, flash: Float): Float {
        val energy = max(low, max(mid, high)).coerceIn(0f, 1f)
        val drive = (energy * energyGain + flash * beatPunch).coerceIn(0f, 1f)
        val floor = audioFloor
        return floor + (1f - floor) * sqrt(drive)
    }

    /** Link beat-strobe: a resting glow (from restingGlow), the beat punches on top. */
    fun linkBrightnessValue(flash: Float): Float =
        sqrt((restingGlow * LINK_GLOW_SCALE + flash * beatPunch).coerceIn(0f, 1f))
}
