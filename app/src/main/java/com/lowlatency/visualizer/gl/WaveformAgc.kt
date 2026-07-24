package com.lowlatency.visualizer.gl

import kotlin.math.abs

/**
 * Peak-follower reciprocal auto-gain, shared by the abstract PCM-driven scenes
 * (Nebula, Veil, Veil Top-Down, Slipstream, Mandala Pulse, Meridian, Phase
 * Scope).
 *
 * Every one of them wanted the same thing and grew its own copy of it: lift a
 * quiet mic so the shape fills the frame, keep a loud master from blowing out,
 * and never pump. The recipe was identical each time — take the window's peak,
 * aim the gain at [target] / peak, clamp to [[min], [max]], and ease the live
 * gain toward that by [smooth] per frame — so it lives here once instead of
 * seven near-identical blocks. Only the tuning differs, and that stays per scene
 * via the constructor.
 *
 * These are amplitude-*reactivity* helpers, not measurements. Deliberately NOT
 * used by the instruments that must read an absolute level (Mechanical Meter,
 * Level Meter — see [MeterCalibration]), nor by the rolling-loudness waveform
 * history ([BandWaveHistory]); those adapt on a different law, or not at all, on
 * purpose.
 */
class WaveformAgc(
    private val target: Float,
    private val floor: Float = DEFAULT_FLOOR,
    private val smooth: Float = DEFAULT_SMOOTH,
    private val min: Float = DEFAULT_MIN,
    private val max: Float = DEFAULT_MAX,
    initial: Float = DEFAULT_INITIAL,
) {
    /** Smoothed gain to multiply the PCM by; advanced by [update]. */
    var gain: Float = initial
        private set

    /** Absolute peak of the most recent window — some scenes gate on it too. */
    var peak: Float = 0f
        private set

    /** Fold a fresh PCM window in, advancing [gain] and [peak]. Returns [gain]. */
    fun update(pcm: FloatArray): Float {
        var p = 0f
        for (s in pcm) {
            val a = abs(s)
            if (a > p) p = a
        }
        peak = p
        val desired = (target / maxOf(p, floor)).coerceIn(min, max)
        gain += (desired - gain) * smooth
        return gain
    }

    companion object {
        const val DEFAULT_FLOOR = 0.03f
        const val DEFAULT_SMOOTH = 0.08f
        const val DEFAULT_MIN = 3f
        const val DEFAULT_MAX = 150f
        const val DEFAULT_INITIAL = 8f
    }
}
