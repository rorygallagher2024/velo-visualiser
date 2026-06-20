package com.lowlatency.visualizer

/**
 * User-tunable parameters for the Ableton Link Hue beat-strobe, exposed via the
 * Advanced panel. Mic gain, placement and room vary hugely across devices, so
 * these can't be baked in. Three simple 0..1 controls map onto the internal
 * thresholds; the defaults reproduce the hand-tuned values. Read on the Hue
 * sender thread (volatile), set from the UI thread.
 */
object HueStrobeSettings {

    // Hand-tuned defaults — the single source of truth for "reset to default".
    const val DEFAULT_SENSITIVITY = 0.75f
    const val DEFAULT_COLOUR_SPLIT = 0.5f
    const val DEFAULT_GLOW = 0.5f
    const val DEFAULT_AUDIO_BRIGHTNESS = 0.5f
    const val DEFAULT_AUDIO_FLASH = 0.5f
    const val DEFAULT_AUDIO_SENS = 0.5f
    const val DEFAULT_HUE_LOOKAHEAD = 0f

    // --- Link beat-strobe (Ableton Link on) ---
    /** Higher = beats trigger at a lower volume (more sensitive). */
    @Volatile var micSensitivity = DEFAULT_SENSITIVITY
    /** Higher = less bass needed before the colour goes blue/purple (vs red). */
    @Volatile var colourSplit = DEFAULT_COLOUR_SPLIT
    /** Ambient resting-glow brightness (0 = fully off between beats). */
    @Volatile var restingGlow = DEFAULT_GLOW

    // --- Audio-reactive light show (Ableton Link off) ---
    /** Overall brightness of the spectrum-driven lights. */
    @Volatile var audioBrightness = DEFAULT_AUDIO_BRIGHTNESS
    /** Strength of the white beat-flash punch (0 = pure colour, no flash). */
    @Volatile var audioFlash = DEFAULT_AUDIO_FLASH
    /** How readily the beat-flash fires (higher = more sensitive). */
    @Volatile var audioSensitivity = DEFAULT_AUDIO_SENS

    /** Zigbee latency compensation: fire the Hue command this many ms early. */
    @Volatile var hueLookaheadMs = DEFAULT_HUE_LOOKAHEAD

    /** Restore the hand-tuned defaults (both modes). */
    fun resetToDefaults() {
        micSensitivity = DEFAULT_SENSITIVITY
        colourSplit = DEFAULT_COLOUR_SPLIT
        restingGlow = DEFAULT_GLOW
        audioBrightness = DEFAULT_AUDIO_BRIGHTNESS
        audioFlash = DEFAULT_AUDIO_FLASH
        audioSensitivity = DEFAULT_AUDIO_SENS
        hueLookaheadMs = DEFAULT_HUE_LOOKAHEAD
    }

    // --- Derived thresholds (read on the sender thread) ---

    /** Mic peak at/above this => full-brightness beats (0.10 insensitive .. 0.008 sensitive). */
    val levelFull: Float get() = 0.10f + (0.008f - 0.10f) * micSensitivity
    /** Mic peak below this => no beat (lights settle on the ambient glow). */
    val levelBase: Float get() = levelFull * 0.4f

    private val splitMid: Float get() = 0.75f + (0.40f - 0.75f) * colourSplit
    /** PCM bass ratio below this => full light-red. */
    val bassLo: Float get() = splitMid - 0.12f
    /** PCM bass ratio above this => full blue/purple. */
    val bassHi: Float get() = splitMid + 0.12f

    // Resting ambient colour (pre-gamma), a soft purple-blue scaled by restingGlow.
    val ambientR: Float get() = restingGlow * 0.012f
    val ambientG: Float get() = restingGlow * 0.006f
    val ambientB: Float get() = restingGlow * 0.028f

    // --- Audio-mode derived values ---
    /** Overall brightness multiplier (0.4 .. 1.6, default 1.0). */
    val audioBrightMul: Float get() = 0.4f + 1.2f * audioBrightness
    /** Beat-flash strength multiplier (0 .. 2, default 1.0). */
    val audioFlashMul: Float get() = audioFlash * 2f
    /** Low-band level needed to register a beat (0.70 .. 0.20, default 0.45). */
    val audioBeatThreshold: Float get() = 0.70f + (0.20f - 0.70f) * audioSensitivity
    /** Required low-band rise to register a beat (0.20 .. 0.05, default 0.125). */
    val audioBeatDelta: Float get() = 0.20f + (0.05f - 0.20f) * audioSensitivity
}
