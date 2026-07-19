package com.lowlatency.visualizer

import android.util.Log
import kotlin.math.exp

/**
 * Self-calibrating spectral-flux onset detector.
 *
 * Onsets are a broadband rise in the magnitude spectrum: the sum of positive
 * bin-to-bin changes between frames (half-wave-rectified spectral flux), over
 * the 128-bin spectrum the app already computes. Flux catches energy arriving in
 * any band (kicks, snares, attacks), and a sustained tone contributes none once
 * steady, so a held bassline no longer masks the transients on top of it.
 *
 * The key robustness trick is that the threshold is **not** an absolute level.
 * Two followers track the flux's own statistics: a slow low-follower (the noise
 * floor) and a fast high-follower (the typical onset level). The detection value
 * is the flux expressed as a *fraction of that local dynamic range*, so it is
 * invariant to the microphone gain and the source. A quiet laptop mic and a hot
 * phone mic both land in the same 0..1 range, and the only tuning constant is a
 * unitless fraction rather than a device-specific level.
 *
 * A beat is that normalized value crossing a threshold, past a refractory gap.
 * Silence is handled downstream by the shared loudness gate and by requiring a
 * minimum dynamic range here.
 *
 * @param sourceScaled apply the user's source-aware sensitivity preset (scales
 *   the fraction threshold). The 4/4 tempo tracker sets this false: it wants
 *   every kick on any source and can freely ignore the false onsets.
 */
class BeatDetector(
    private val minGapMs: Long = 120L,
    private val sourceScaled: Boolean = true,
    private val debugName: String? = null,
) {
    private var prev: FloatArray? = null
    private var weights: FloatArray? = null   // per-bin frequency weighting (kick emphasis)
    private var noiseFloor = 0f       // slow low-follower of flux
    private var fluxPeak = 0f         // fast high-follower of flux
    private var lastBeatNs = 0L
    private var lastLogNs = 0L

    /** Onset strength this frame: flux as a 0..~1 fraction of the local dynamic
     *  range. A continuous, scale-invariant novelty for the tempo tracker. */
    var lastNovelty = 0f
        private set

    /** Feed the latest magnitude spectrum every frame; true on a detected onset. */
    fun update(spectrum: FloatArray): Boolean {
        val p = prev
        if (p == null || p.size != spectrum.size) {
            prev = spectrum.copyOf()   // first frame (or size change): no flux yet
            return false
        }

        val flux = spectralFlux(spectrum, p)

        // Self-calibrating envelopes and the normalized detection function.
        noiseFloor += (flux - noiseFloor) * (if (flux > noiseFloor) NOISE_UP else NOISE_DOWN)
        fluxPeak += (flux - fluxPeak) * (if (flux > fluxPeak) PEAK_UP else PEAK_DOWN)
        val dynamic = fluxPeak - noiseFloor
        val norm = if (dynamic > MIN_DYNAMIC) ((flux - noiseFloor) / dynamic).coerceIn(0f, 2f) else 0f
        lastNovelty = norm

        val now = System.nanoTime()
        val threshold = NORM_THRESHOLD * (if (sourceScaled) BeatSettings.thresholdScale() else 1f)
        val isBeat = norm > threshold &&
            dynamic > MIN_DYNAMIC &&
            (now - lastBeatNs) > minGapMs * 1_000_000L
        if (isBeat) lastBeatNs = now

        logIfDebug(isBeat, flux, norm, now)
        return isBeat
    }

    /** Half-wave-rectified spectral flux (mean positive bin rise), weighted toward
     *  the low bins where the kick lives; advances [prevSpectrum]. */
    private fun spectralFlux(spectrum: FloatArray, prevSpectrum: FloatArray): Float {
        val w = weights ?: buildWeights(spectrum.size).also { weights = it }
        var flux = 0f
        for (i in spectrum.indices) {
            val d = spectrum[i] - prevSpectrum[i]
            if (d > 0f) flux += w[i] * d
            prevSpectrum[i] = spectrum[i]
        }
        return flux / spectrum.size
    }

    /** Frequency weighting that emphasises the low bins where the kick sits. The
     *  128-bin spectrum is log-spaced, so a ~50-150 Hz kick occupies only the
     *  lowest bins; without this its few bins are swamped by the count of mid/high
     *  bins, and a bare four-to-the-floor kick barely registers. */
    private fun buildWeights(n: Int): FloatArray {
        val w = FloatArray(n)
        for (i in 0 until n) w[i] = 1f + LOW_GAIN * exp(-i.toFloat() / LOW_DECAY)
        return w
    }

    private fun logIfDebug(isBeat: Boolean, flux: Float, norm: Float, now: Long) {
        val name = debugName ?: return
        if (isBeat) {
            Log.i(TAG, "[$name] BEAT  norm=${f(norm)} flux=${f(flux)} floor=${f(noiseFloor)} peak=${f(fluxPeak)}")
        } else if (now - lastLogNs > 300_000_000L) {
            lastLogNs = now
            Log.i(TAG, "[$name] level norm=${f(norm)} flux=${f(flux)} floor=${f(noiseFloor)} peak=${f(fluxPeak)}")
        }
    }

    private fun f(v: Float) = "%.4f".format(v)

    companion object {
        private const val TAG = "BeatDetector"
        private const val NOISE_UP = 0.010f    // noise floor rises slowly (onsets don't inflate it)
        private const val NOISE_DOWN = 0.080f  // and falls moderately into quieter sections
        private const val PEAK_UP = 0.400f     // onset-level follower snaps up on a transient
        private const val PEAK_DOWN = 0.020f   // and eases down, holding a stable scale
        private const val MIN_DYNAMIC = 0.002f // below this flux range ⇒ no real transients present
        private const val NORM_THRESHOLD = 0.40f // onset when flux reaches 40% of the local range
        private const val LOW_GAIN = 3.0f      // extra weight on the lowest bins (kick band)
        private const val LOW_DECAY = 25.0f    // bins over which that low emphasis tapers to 1×
    }
}
