package com.lowlatency.visualizer

/**
 * App-wide glow (HDR bloom) strength, read by [com.lowlatency.visualizer.gl.VisualizerRenderer].
 * A single global holder avoids plumbing it through the surface view.
 *
 * OFF disables the bloom pass entirely (zero overhead); the other presets scale
 * the composite bloom intensity.
 */
object GlowSettings {

    enum class Strength(val key: String, val enabled: Boolean, val intensity: Float) {
        OFF("off", false, 0f),
        SUBTLE("subtle", true, 0.5f),
        STANDARD("standard", true, 1.0f),
        INTENSE("intense", true, 1.9f);

        companion object {
            fun fromKey(k: String?): Strength = entries.firstOrNull { it.key == k } ?: STANDARD
        }
    }

    @Volatile var strength: Strength = Strength.STANDARD
}
