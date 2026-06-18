package com.lowlatency.visualizer

/**
 * App-wide beat-detection sensitivity, shared by every [BeatDetector] (haptics,
 * Beat Fireworks, Starscape flashes). A single global holder avoids plumbing the
 * setting through the renderer into each scene.
 *
 * Sensitivity is expressed as a *threshold multiplier*: higher sensitivity = a
 * lower threshold = fires more readily. The multiplier is **source-aware** —
 * internal/system audio arrives hotter and denser than the mic, so the same
 * preset applies a higher threshold there. The user picks one preset; the app
 * compensates per source.
 */
object BeatSettings {

    enum class Sensitivity(
        val key: String,
        val micScale: Float,
        val internalScale: Float,
    ) {
        LOW("low", 1.8f, 4.0f),
        STANDARD("standard", 1.0f, 2.2f),
        HIGH("high", 0.6f, 1.4f);

        companion object {
            fun fromKey(k: String?): Sensitivity =
                entries.firstOrNull { it.key == k } ?: STANDARD
        }
    }

    @Volatile var preset: Sensitivity = Sensitivity.STANDARD

    /** Set by the UI when the audio source changes. */
    @Volatile var systemAudio: Boolean = false

    /** Multiplier applied to a detector's threshold for the current source. */
    fun thresholdScale(): Float =
        if (systemAudio) preset.internalScale else preset.micScale
}
