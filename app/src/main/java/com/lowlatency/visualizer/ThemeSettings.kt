package com.lowlatency.visualizer

/**
 * App-wide colour theme, applied as a final colour-grade in the post-processing
 * composite (so one place themes every bloom-routed scene). Each theme is a hue
 * rotation (radians about the luma axis), a saturation scale, and an RGB tint.
 *
 * SPECTRUM is the identity grade (no change). When a non-default theme is set,
 * the renderer routes scenes through the composite even if glow is off, so the
 * grade still applies. (The Raw Oscilloscope stays pure/ungraded by design.)
 */
object ThemeSettings {

    enum class Theme(
        val key: String,
        val hueShift: Float,
        val saturation: Float,
        val tintR: Float,
        val tintG: Float,
        val tintB: Float,
    ) {
        SPECTRUM("spectrum", 0f, 1.0f, 1f, 1f, 1f),
        NEON("neon", 0f, 1.6f, 1f, 1f, 1f),
        WARM("warm", 0.6f, 1.05f, 1.0f, 0.82f, 0.6f),
        COOL("cool", -0.9f, 1.05f, 0.75f, 0.9f, 1.15f),
        MONO("mono", 0f, 0.0f, 0.55f, 1.0f, 0.7f);

        companion object {
            fun fromKey(k: String?): Theme = entries.firstOrNull { it.key == k } ?: SPECTRUM
        }
    }

    @Volatile var preset: Theme = Theme.SPECTRUM

    /** True when a grade other than the identity is active. */
    val isGraded: Boolean get() = preset != Theme.SPECTRUM
}
