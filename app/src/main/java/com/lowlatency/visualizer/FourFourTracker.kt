package com.lowlatency.visualizer

import android.util.Log
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln

/**
 * Real-time 4/4 beat-grid tracker: turns the reactive onset stream into a steady
 * *predicted* grid so the visuals and lights pulse on the quarter notes and
 * ignore stray transients between them.
 *
 * Two stages, deliberately separated:
 *
 *  - **Tempo** comes from *autocorrelation* of a continuous onset-novelty
 *    envelope. The novelty ([BeatDetector.lastNovelty]) is accumulated into
 *    fixed 10 ms bins (so timing is independent of the jittery GL frame rate),
 *    and the buffer is correlated against itself over the lags that correspond
 *    to a musical tempo range. The dominant lag is the beat period. Correlating
 *    the whole envelope shape, rather than the gaps between discrete onsets,
 *    survives missing or extra hits, which is what a median-of-intervals guess
 *    could not. A gentle mid-tempo weighting breaks half/double ambiguity.
 *  - **Phase** comes from a light phase-locked loop that nudges the predicted
 *    grid toward onsets that land near it (the kicks).
 *
 * Confidence is the normalized height of the autocorrelation peak: a strongly
 * periodic signal scores near 1, ambiguous or non-4/4 material scores low. Below
 * the lock threshold the caller falls back to the raw reactive detector, so the
 * worst case is exactly today's behaviour. Hysteresis stops it chattering.
 *
 * Runs on the GL render thread each frame; single-threaded, no locking.
 */
class FourFourTracker {

    /** One frame's result. [beat] is true on the frame a grid line is crossed. */
    data class Tick(
        val beat: Boolean,
        val envelope: Float,   // phase-locked punch: peaks on the beat, decays to the next
        val barPhase: Float,   // 0..1 across a 4-beat bar
        val confident: Boolean,
        val bpm: Float,
    )

    // Own multi-band spectral onset detector, independent of the reactive path's
    // bass-RMS BeatDetector: tempo tracking wants every beat on every source
    // (kick and clap alike) and can freely ignore false onsets. All the
    // experimental onset work lives here, behind the 4/4 opt-in.
    private val detector = SpectralOnsetDetector(sourceScaled = false)

    // Onset-novelty envelope in fixed 10 ms bins (a ring), for frame-rate-free
    // autocorrelation. `lin` is the chronological unpacking, rebuilt per estimate.
    private val env = FloatArray(ENV_LEN)
    private val lin = FloatArray(ENV_LEN)
    private var envHead = 0
    private var envCount = 0
    private var binAccum = 0f
    private var binEndSec = 0.0

    private var periodSec = 0.0
    private var nextBeatSec = 0.0
    private var beatInBar = 0
    private var recalcAtSec = 0.0
    private var lastEmittedBeatSec = 0.0

    private var confidence = 0f      // smoothed autocorr peak height, 0..1
    private var confidentState = false
    private var lastBpm = 0f
    private var scratchPeak = 0f     // peak height from the last bestTempoLag pass
    private val acPeaks = FloatArray(MAX_LAG + 1)   // per-lag autocorr, for sub-bin refinement
    private var lastLogSec = 0.0

    /**
     * @param nowSec   monotonic time in seconds (e.g. System.nanoTime * 1e-9).
     * @param spectrum the latest 128-bin magnitude spectrum (same the visuals read).
     * @param gateOpen whether audio presence is above the silence floor.
     */
    fun update(nowSec: Double, spectrum: FloatArray, gateOpen: Boolean): Tick {
        val onset = detector.update(spectrum)
        accumulateEnvelope(nowSec, if (gateOpen) detector.lastNovelty else 0f)

        if (nowSec >= recalcAtSec) {
            recalcAtSec = nowSec + RECALC_SEC
            recomputeTempo()
        }
        maybeLog(nowSec)

        if (periodSec <= 0.0) return Tick(false, 0f, 0f, false, 0f)

        if (nextBeatSec <= 0.0) nextBeatSec = nowSec + periodSec
        if (onset && gateOpen) alignToOnset(nowSec)

        val beat = advanceGrid(nowSec)

        val within = (1.0 - (nextBeatSec - nowSec) / periodSec).coerceIn(0.0, 1.0)
        val envelope = ((1.0 - within) * (1.0 - within)).toFloat()   // peaks just after the beat
        val barPhase = ((beatInBar + within) / 4.0).toFloat().coerceIn(0f, 1f)

        return Tick(beat, envelope, barPhase, confidentState, lastBpm)
    }

    /** Forget all state (e.g. when the mode is switched on, or the source changes). */
    fun reset() {
        envHead = 0; envCount = 0; binAccum = 0f; binEndSec = 0.0
        periodSec = 0.0; nextBeatSec = 0.0; beatInBar = 0; recalcAtSec = 0.0
        lastEmittedBeatSec = 0.0
        confidence = 0f; confidentState = false; lastBpm = 0f
    }

    // ---- Novelty envelope (fixed 10 ms bins) --------------------------------

    private fun accumulateEnvelope(nowSec: Double, novelty: Float) {
        if (binEndSec <= 0.0) { binEndSec = nowSec + BIN_SEC; binAccum = novelty; return }
        // A long gap (app paused) makes the whole envelope stale: start fresh.
        if (nowSec - binEndSec > ENV_LEN * BIN_SEC) {
            envHead = 0; envCount = 0; binAccum = 0f; binEndSec = nowSec + BIN_SEC
            return
        }
        binAccum += novelty
        while (nowSec >= binEndSec) {
            pushBin(binAccum)
            binAccum = 0f
            binEndSec += BIN_SEC
        }
    }

    private fun pushBin(v: Float) {
        env[envHead] = v
        envHead = (envHead + 1) % ENV_LEN
        if (envCount < ENV_LEN) envCount++
    }

    // ---- Tempo via autocorrelation ------------------------------------------

    private fun recomputeTempo() {
        if (envCount < MIN_ENV) return
        val totalE = unpackEnvelope()
        if (totalE < 1e-6f) return
        val bestLag = bestTempoLag(totalE)   // stores the peak height in scratchPeak
        if (bestLag < 0) return
        updatePeriod(refineLag(bestLag) * BIN_SEC)   // sub-bin peak → precise BPM
        updateConfidence(scratchPeak)
    }

    /** Copy the ring into [lin] in chronological order; return its total energy. */
    private fun unpackEnvelope(): Float {
        val start = (envHead - envCount + ENV_LEN) % ENV_LEN
        var totalE = 0f
        for (i in 0 until envCount) {
            val v = env[(start + i) % ENV_LEN]
            lin[i] = v
            totalE += v * v
        }
        return totalE
    }

    /** Lag of the strongest tempo-weighted autocorrelation peak; peak height → [scratchPeak]. */
    private fun bestTempoLag(totalE: Float): Int {
        var bestLag = -1
        var bestScore = 0f
        var bestPeak = 0f
        for (lag in MIN_LAG..MAX_LAG) {
            var cross = 0f
            var e1 = 0f
            var e2 = 0f
            for (i in lag until envCount) {
                val a = lin[i]
                val b = lin[i - lag]
                cross += a * b
                e1 += a * a
                e2 += b * b
            }
            // True cosine similarity (0..1 bounds, unbiased by lag length)
            val peak = if (e1 > 0f && e2 > 0f) (cross / Math.sqrt((e1 * e2).toDouble())).toFloat() else 0f
            acPeaks[lag] = peak
            val score = peak * tempoWeight(lag)      // weighting only picks the lag
            if (score > bestScore) { bestScore = score; bestLag = lag; bestPeak = peak }
        }
        scratchPeak = bestPeak
        return bestLag
    }

    /** Parabolic interpolation of the autocorrelation peak: fit a parabola through
     *  the winning lag and its two neighbours for the sub-bin peak, so the tempo is
     *  not quantised to whole 10 ms lags (e.g. 128.0 BPM instead of 127.7 / 130.4). */
    private fun refineLag(lag: Int): Double {
        if (lag <= MIN_LAG || lag >= MAX_LAG) return lag.toDouble()
        val y1 = acPeaks[lag - 1]
        val y2 = acPeaks[lag]
        val y3 = acPeaks[lag + 1]
        val denom = y1 - 2f * y2 + y3
        if (abs(denom) < 1e-6f) return lag.toDouble()
        val delta = (0.5f * (y1 - y3) / denom).coerceIn(-0.5f, 0.5f)
        return lag.toDouble() + delta
    }

    private fun updatePeriod(candidate: Double) {
        periodSec = when {
            periodSec <= 0.0 -> candidate                                      // first lock
            abs(periodSec - candidate) > periodSec * RESEED_FRAC -> candidate  // tempo jump
            else -> periodSec + PERIOD_TRACK * (candidate - periodSec)         // gentle drift
        }
        lastBpm = (60.0 / periodSec).toFloat()
    }

    /** Smooth the confidence toward the latest peak height, with lock hysteresis. */
    private fun updateConfidence(peak: Float) {
        confidence += CONF_TRACK * (peak - confidence)
        if (!confidentState && confidence >= CONF_HI) confidentState = true
        else if (confidentState && confidence < CONF_LO) confidentState = false
    }

    /** Rate-limited diagnostic line so the detector can be characterised against
     *  known-tempo tracks via `adb logcat -s FourFour`. */
    private fun maybeLog(nowSec: Double) {
        if (!DEBUG_LOG || nowSec - lastLogSec < 1.0) return
        lastLogSec = nowSec
        Log.i(TAG, "bpm=%.1f conf=%.2f locked=%b env=%d".format(lastBpm, confidence, confidentState, envCount))
    }

    /** Mild log-Gaussian favouring ~120 BPM, to break half/double-tempo ties
     *  without overriding a clear peak at the edges (e.g. drum & bass). */
    private fun tempoWeight(lag: Int): Float {
        val z = ln(lag.toDouble() / PREF_LAG) / WEIGHT_SIGMA
        return exp(-0.5 * z * z).toFloat()
    }

    // ---- Phase-locked loop ---------------------------------------------------

    /** Nudge the grid phase toward an onset that lands near a grid line. */
    private fun alignToOnset(nowSec: Double) {
        val prevBeat = nextBeatSec - periodSec
        val err = if (abs(nowSec - prevBeat) < abs(nowSec - nextBeatSec)) {
            nowSec - prevBeat
        } else {
            nowSec - nextBeatSec
        }
        // err > 0 means the onset is after the predicted beat (grid running early),
        // so move the grid later to meet it. Pull toward the onset, never away.
        if (abs(err) <= periodSec * ALIGN_TOL) nextBeatSec += PHASE_CORRECT * err
    }

    /** Emit any grid lines crossed since last frame; advance the bar counter. */
    private fun advanceGrid(nowSec: Double): Boolean {
        var beat = false
        var guard = 0
        while (nowSec >= nextBeatSec && guard < MAX_CATCHUP) {
            // Prevent PLL stutter: don't emit a beat if we're suspiciously close to the last one
            if (nextBeatSec - lastEmittedBeatSec > periodSec * 0.5) {
                beat = true
                lastEmittedBeatSec = nextBeatSec
            }
            nextBeatSec += periodSec
            beatInBar = (beatInBar + 1) and 3
            guard++
        }
        if (guard >= MAX_CATCHUP) nextBeatSec = nowSec + periodSec   // fell behind: re-seed
        return beat
    }

    companion object {
        private const val TAG = "FourFour"
        private const val DEBUG_LOG = false      // BPM/confidence logging (enable only for tuning)

        private const val BIN_SEC = 0.01         // 10 ms envelope bins (100 Hz)
        private const val ENV_LEN = 512          // ~5.1 s of history
        private const val MIN_ENV = 200          // need ~2 s before estimating

        // Lag range = tempo range. lag(bins) = period(s) / BIN_SEC.
        private const val MIN_LAG = 34           // 0.34 s  ≈ 176 BPM
        private const val MAX_LAG = 71           // 0.71 s  ≈  85 BPM
        private const val PREF_LAG = 50          // 0.50 s  = 120 BPM (weight centre)
        private const val WEIGHT_SIGMA = 0.45    // log-space spread of the weighting

        private const val RECALC_SEC = 0.20      // re-estimate tempo ~5×/s
        private const val RESEED_FRAC = 0.20     // snap period on a >20% jump
        private const val PERIOD_TRACK = 0.30    // else ease toward the new estimate

        private const val ALIGN_TOL = 0.18       // onset within ±18% of a line is on-grid
        private const val PHASE_CORRECT = 0.12   // grid-phase nudge per aligned onset
        private const val MAX_CATCHUP = 4        // cap beats emitted in one frame

        private const val CONF_TRACK = 0.25f     // smoothing of the confidence follower
        private const val CONF_HI = 0.34f        // lock on at/above this peak height
        private const val CONF_LO = 0.22f        // drop out below this
    }
}
