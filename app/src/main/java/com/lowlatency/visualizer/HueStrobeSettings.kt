package com.lowlatency.visualizer

/**
 * User-tunable, light-*rendering* parameters for the Hue beat-strobe, exposed via
 * the Advanced Lighting panel. Mic gain, placement and room vary hugely across
 * devices, so these can't be baked in. Read on the Hue sender thread (volatile),
 * set from the UI thread.
 *
 * Note: how *readily* a beat triggers (the audio-presence gate) is no longer here
 * — that's the global Beat Sensitivity in [BeatSettings], shared by the visuals,
 * the lights and haptics so they all react identically. These settings only shape
 * how the lights *look* once a beat is decided.
 */
object HueStrobeSettings {

    // Hand-tuned defaults — the single source of truth for "reset to default".
    const val DEFAULT_COLOUR_SPLIT = 0.5f
    const val DEFAULT_GLOW = 0.5f
    const val DEFAULT_AUDIO_BRIGHTNESS = 0.5f
    const val DEFAULT_AUDIO_FLASH = 0.5f
    const val DEFAULT_HUE_LOOKAHEAD = 0f
    const val DEFAULT_LINK_BEAT_FLASH = true

    // --- Link beat-strobe (Ableton Link on) ---
    /** When false, the lights skip the per-beat flash and rest on colour/glow
     *  while Ableton Link is on (the visuals + haptics still beat). */
    @Volatile var linkBeatFlashEnabled = DEFAULT_LINK_BEAT_FLASH
    /** Higher = less bass needed before the colour goes blue/purple (vs red). */
    @Volatile var colourSplit = DEFAULT_COLOUR_SPLIT
    /** Ambient resting-glow brightness (0 = fully off between beats). */
    @Volatile var restingGlow = DEFAULT_GLOW

    // --- Audio-reactive light show (Ableton Link off) ---
    /** Overall brightness of the spectrum-driven lights. */
    @Volatile var audioBrightness = DEFAULT_AUDIO_BRIGHTNESS
    /** Strength of the white beat-flash punch (0 = pure colour, no flash). */
    @Volatile var audioFlash = DEFAULT_AUDIO_FLASH

    /** Zigbee latency compensation: fire the Hue command this many ms early. */
    @Volatile var hueLookaheadMs = DEFAULT_HUE_LOOKAHEAD

    /** Restore the hand-tuned defaults. */
    fun resetToDefaults() {
        linkBeatFlashEnabled = DEFAULT_LINK_BEAT_FLASH
        colourSplit = DEFAULT_COLOUR_SPLIT
        restingGlow = DEFAULT_GLOW
        audioBrightness = DEFAULT_AUDIO_BRIGHTNESS
        audioFlash = DEFAULT_AUDIO_FLASH
        hueLookaheadMs = DEFAULT_HUE_LOOKAHEAD
    }

    // --- Derived values (read on the sender thread) ---

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
}
