package com.lowlatency.visualizer

import android.util.Log

/**
 * Multi-band, self-calibrating spectral-flux onset detector, used **only** by the
 * experimental [FourFourTracker]. The reactive path (visuals, lights, haptics)
 * deliberately keeps the simpler, proven bass-RMS [BeatDetector], so turning 4/4
 * mode off restores the original behaviour; all of the experimental onset work
 * lives here, behind that opt-in.
 *
 * Onsets are a rise in the magnitude spectrum, measured as half-wave-rectified
 * spectral flux over the 128-bin spectrum the app already computes. The flux is
 * split into three bands, each calibrated to its **own** statistics:
 *
 *   - low  = the kick (~50-200 Hz),
 *   - mid  = snares, claps, body,
 *   - high = hats and air.
 *
 * Per band, a slow follower tracks the noise floor and a fast one the onset
 * level; the band's detection value is its flux as a fraction of that local
 * dynamic range. Because each band self-normalises, a bare four-to-the-floor
 * kick drives the low band while a kick-less clap break drives the mid band, so
 * the detector follows whichever part of the kit carries the beat. The bands
 * combine with a kick-favouring weight into one onset strength, and the
 * fraction-of-range design keeps the trigger invariant to mic gain and source.
 *
 * @param sourceScaled apply the user's source-aware sensitivity preset. The
 *   tracker sets this false: it wants every beat on any source and can freely
 *   ignore false onsets.
 */
class SpectralOnsetDetector(
    private val minGapMs: Long = 120L,
    private val sourceScaled: Boolean = true,
    private val debugName: String? = null,
) {
    private var prev: FloatArray? = null
    private var b1 = 0                        // low/mid boundary bin (set on first frame)
    private var b2 = 0                        // mid/high boundary bin
    private val bandFlux = FloatArray(BANDS)
    private val noiseFloor = FloatArray(BANDS)
    private val fluxPeak = FloatArray(BANDS)
    private var lastBeatNs = 0L
    private var lastLogNs = 0L

    /** Onset strength this frame: kick-favouring combination of the per-band
     *  normalized fluxes. A continuous, scale-invariant novelty for the tracker. */
    var lastNovelty = 0f
        private set

    /** Feed the latest magnitude spectrum every frame; true on a detected onset. */
    fun update(spectrum: FloatArray): Boolean {
        val p = prev
        if (p == null || p.size != spectrum.size) {
            prev = spectrum.copyOf()   // first frame (or size change): no flux yet
            b1 = (spectrum.size * BAND1).toInt()
            b2 = (spectrum.size * BAND2).toInt()
            return false
        }

        computeBandFlux(spectrum, p)
        val novelty = combineNovelty(spectrum.size)
        lastNovelty = novelty

        val now = System.nanoTime()
        val threshold = COMBINED_THRESHOLD * (if (sourceScaled) BeatSettings.thresholdScale() else 1f)
        val isBeat = novelty > threshold && (now - lastBeatNs) > minGapMs * 1_000_000L
        if (isBeat) lastBeatNs = now

        logIfDebug(isBeat, novelty, now)
        return isBeat
    }

    /** Half-wave-rectified spectral flux, accumulated per band; advances [prevSpectrum]. */
    private fun computeBandFlux(spectrum: FloatArray, prevSpectrum: FloatArray) {
        bandFlux[0] = 0f; bandFlux[1] = 0f; bandFlux[2] = 0f
        for (i in spectrum.indices) {
            val d = spectrum[i] - prevSpectrum[i]
            if (d > 0f) {
                val band = if (i < b1) 0 else if (i < b2) 1 else 2
                bandFlux[band] += d
            }
            prevSpectrum[i] = spectrum[i]
        }
    }

    /** Self-calibrate each band to its own dynamic range, then combine kick-first. */
    private fun combineNovelty(n: Int): Float {
        var combined = 0f
        for (b in 0 until BANDS) {
            val width = when (b) { 0 -> b1; 1 -> b2 - b1; else -> n - b2 }
            val f = if (width > 0) bandFlux[b] / width else 0f
            noiseFloor[b] += (f - noiseFloor[b]) * (if (f > noiseFloor[b]) NOISE_UP else NOISE_DOWN)
            fluxPeak[b] += (f - fluxPeak[b]) * (if (f > fluxPeak[b]) PEAK_UP else PEAK_DOWN)
            val dyn = fluxPeak[b] - noiseFloor[b]
            val norm = if (dyn > MIN_DYNAMIC) ((f - noiseFloor[b]) / dyn).coerceIn(0f, 2f) else 0f
            combined += BAND_WEIGHT[b] * norm
        }
        return combined
    }

    private fun logIfDebug(isBeat: Boolean, novelty: Float, now: Long) {
        val name = debugName ?: return
        if (isBeat) {
            Log.i(TAG, "[$name] BEAT nov=${f(novelty)}")
        } else if (now - lastLogNs > 300_000_000L) {
            lastLogNs = now
            Log.i(TAG, "[$name] level nov=${f(novelty)}")
        }
    }

    private fun f(v: Float) = "%.4f".format(v)

    companion object {
        private const val TAG = "SpectralOnset"
        private const val BANDS = 3
        private const val BAND1 = 0.22f   // low/mid split as a fraction of the log-spaced bins
        private const val BAND2 = 0.55f   // mid/high split
        private val BAND_WEIGHT = floatArrayOf(1.0f, 0.85f, 0.5f)  // favour the kick, include the rest

        private const val NOISE_UP = 0.010f    // per-band noise floor rises slowly
        private const val NOISE_DOWN = 0.080f  // and falls moderately into quieter sections
        private const val PEAK_UP = 0.400f     // onset-level follower snaps up on a transient
        private const val PEAK_DOWN = 0.020f   // and eases down, holding a stable scale
        private const val MIN_DYNAMIC = 0.002f // below this per-band flux range ⇒ nothing present
        private const val COMBINED_THRESHOLD = 0.50f // onset when the combined strength clears this
    }
}
