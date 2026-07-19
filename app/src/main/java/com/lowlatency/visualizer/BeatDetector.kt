package com.lowlatency.visualizer

import android.util.Log

/**
 * Spectral-flux onset detector.
 *
 * Detects percussive onsets (kicks, snares, attacks) as a *broadband* rise in
 * the magnitude spectrum: the sum of positive bin-to-bin changes between frames
 * (half-wave-rectified spectral flux). This is the standard onset function, and
 * it is far more robust than the single-band bass RMS this used to use:
 *
 *   - It reacts to energy arriving in ANY band, so it catches snare/clap-driven
 *     beats and percussive attacks, not just the kick.
 *   - A sustained tone (a held bassline) contributes no flux once steady, so it
 *     no longer masks the transients riding on top of it.
 *   - It reuses the 128-bin spectrum the app already computes every frame, so it
 *     costs one extra pass over 128 floats and no new FFT.
 *
 * The spectrum is passed in (rather than read from `gl.SpectrumData`) so this
 * class stays decoupled from the GL layer. A beat is a flux spike above an
 * adaptive baseline, past a refractory gap, with a small presence floor so it
 * stays quiet in silence.
 *
 * @param sourceScaled apply the user's source-aware sensitivity preset. The 4/4
 *   tempo tracker sets this false: it wants every kick on any source, regardless
 *   of the visual sensitivity, and can freely ignore the false onsets.
 */
class BeatDetector(
    private val fluxTauSec: Float = 0.35f,    // adaptive baseline time constant
    private val fluxFactor: Float = 1.5f,     // flux must exceed baseline × this
    private val fluxFloor: Float = 0.004f,    // + absolute onset floor
    private val energyFloor: Float = 0.012f,  // mean spectrum below this ⇒ silence, no beat
    private val minGapMs: Long = 120L,        // refractory period
    private val sourceScaled: Boolean = true,
    private val debugName: String? = null,
) {
    private var prev: FloatArray? = null
    private var fluxAvg = 0f
    private var lastNs = 0L
    private var lastBeatNs = 0L
    private var lastLogNs = 0L
    private var scratchEnergy = 0f   // mean bin energy from the last spectralFlux pass

    /** Onset strength this frame: flux above its adaptive baseline, rectified.
     *  A continuous novelty signal for the tempo tracker's autocorrelation. */
    var lastNovelty = 0f
        private set

    /** Feed the latest magnitude spectrum every frame; true on a detected onset. */
    fun update(spectrum: FloatArray): Boolean {
        val p = prev
        if (p == null || p.size != spectrum.size) {
            prev = spectrum.copyOf()   // first frame (or a size change): no flux yet
            return false
        }

        val flux = spectralFlux(spectrum, p)   // also updates scratchEnergy + advances prev
        val energy = scratchEnergy

        val now = System.nanoTime()
        val dt = if (lastNs == 0L) 0.016f else ((now - lastNs) / 1_000_000_000.0).toFloat().coerceIn(0f, 0.1f)
        lastNs = now
        fluxAvg += (flux - fluxAvg) * (dt / fluxTauSec).coerceIn(0f, 1f)

        lastNovelty = if (energy > energyFloor && flux > fluxAvg) flux - fluxAvg else 0f

        // Source-aware user sensitivity preset scales the threshold, unless this
        // detector opts out (the tempo tracker).
        val scale = if (sourceScaled) BeatSettings.thresholdScale() else 1f
        val threshold = (fluxAvg * fluxFactor + fluxFloor) * scale
        val isBeat = energy > energyFloor &&
            flux > threshold &&
            (now - lastBeatNs) > minGapMs * 1_000_000L
        if (isBeat) lastBeatNs = now

        logIfDebug(isBeat, flux, threshold, energy, now)
        return isBeat
    }

    /** Half-wave-rectified spectral flux (mean positive bin rise). Also stores the
     *  mean bin energy in [scratchEnergy] and advances the previous-spectrum store. */
    private fun spectralFlux(spectrum: FloatArray, prevSpectrum: FloatArray): Float {
        var flux = 0f
        var energy = 0f
        for (i in spectrum.indices) {
            val v = spectrum[i]
            val d = v - prevSpectrum[i]
            if (d > 0f) flux += d
            energy += v
            prevSpectrum[i] = v
        }
        val n = spectrum.size
        scratchEnergy = energy / n
        return flux / n
    }

    private fun logIfDebug(isBeat: Boolean, flux: Float, threshold: Float, energy: Float, now: Long) {
        val name = debugName ?: return
        if (isBeat) {
            Log.i(TAG, "[$name] BEAT  flux=${f(flux)} thr=${f(threshold)} e=${f(energy)}")
        } else if (now - lastLogNs > 300_000_000L) {
            lastLogNs = now
            Log.i(TAG, "[$name] level flux=${f(flux)} thr=${f(threshold)} e=${f(energy)}")
        }
    }

    private fun f(v: Float) = "%.4f".format(v)

    companion object { private const val TAG = "BeatDetector" }
}
