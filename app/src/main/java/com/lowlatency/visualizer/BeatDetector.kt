package com.lowlatency.visualizer

import android.util.Log
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Bass-onset beat detector (the reactive path: visuals, lights, haptics).
 *
 * Detects the *attack* of bass hits (the kick), not the bass *level*. Each frame:
 * one-pole low-pass the raw PCM to isolate the bass band, take its RMS, then the
 * positive flux (rise vs the previous frame). This is the simple, proven,
 * kick-focused signal, deliberately kept for the reactive path while the
 * experimental multi-band spectral detector lives in [SpectralOnsetDetector]
 * behind the 4/4 opt-in.
 *
 * The threshold is **self-calibrating** rather than a fixed level: two followers
 * track the flux's own noise floor (slow) and onset level (fast), and a beat is
 * the flux clearing a *fraction of that local dynamic range*. So a quiet laptop
 * mic and a hot phone mic both land in the same range without device-specific
 * constants, which was the whole point of the auto-gain work. Past a refractory
 * gap; silence is handled by requiring a minimum dynamic range and by the shared
 * loudness gate downstream.
 *
 * Operates purely on the raw waveform — it never touches the native FFT bands or
 * the 128-bin spectrum the visuals use, so their tuning is unaffected.
 */
class BeatDetector(
    private val lpAlpha: Float = 0.025f,     // one-pole LP coeff (~180 Hz @ 48 kHz)
    private val minGapMs: Long = 120L,       // refractory period
    private val debugName: String? = null,   // if set, log levels to "BeatDetector"
) {
    private var prevBass = 0f
    private var noiseFloor = 0f              // slow low-follower of the bass flux
    private var fluxPeak = 0f                // fast high-follower of the bass flux
    private var lastBeatNs = 0L
    private var lastLogNs = 0L

    /** Feed the latest PCM window every frame; returns true on a detected beat. */
    fun update(pcm: FloatArray): Boolean {
        // Isolate the bass band with a one-pole low-pass over the window, then RMS.
        var l = 0f
        var sumSq = 0f
        for (s in pcm) {
            l += lpAlpha * (s - l)
            sumSq += l * l
        }
        val bass = sqrt(sumSq / pcm.size)

        // Positive flux = the attack (rise in bass energy since last frame).
        val flux = max(0f, bass - prevBass)
        prevBass = bass

        // Self-calibrating floors: express the flux as a fraction of its own
        // running dynamic range, so the trigger is invariant to mic gain / source.
        noiseFloor += (flux - noiseFloor) * (if (flux > noiseFloor) NOISE_UP else NOISE_DOWN)
        fluxPeak += (flux - fluxPeak) * (if (flux > fluxPeak) PEAK_UP else PEAK_DOWN)
        val dynamic = fluxPeak - noiseFloor
        val norm = if (dynamic > MIN_DYNAMIC) ((flux - noiseFloor) / dynamic).coerceIn(0f, 2f) else 0f

        val now = System.nanoTime()
        // The user's source-aware sensitivity preset scales the fraction threshold.
        val threshold = NORM_THRESHOLD * BeatSettings.thresholdScale()
        val isBeat = norm > threshold &&
            dynamic > MIN_DYNAMIC &&
            (now - lastBeatNs) > minGapMs * 1_000_000L
        if (isBeat) lastBeatNs = now

        if (debugName != null) {
            if (isBeat) {
                Log.i(TAG, "[$debugName] BEAT  norm=${f(norm)} bass=${f(bass)} floor=${f(noiseFloor)} peak=${f(fluxPeak)}")
            } else if (now - lastLogNs > 300_000_000L) {
                lastLogNs = now
                Log.i(TAG, "[$debugName] level norm=${f(norm)} bass=${f(bass)} floor=${f(noiseFloor)} peak=${f(fluxPeak)}")
            }
        }
        return isBeat
    }

    private fun f(v: Float) = "%.4f".format(v)

    companion object {
        private const val TAG = "BeatDetector"
        private const val NOISE_UP = 0.010f    // noise floor rises slowly (onsets don't inflate it)
        private const val NOISE_DOWN = 0.080f  // and falls moderately into quieter sections
        private const val PEAK_UP = 0.400f     // onset-level follower snaps up on a hit
        private const val PEAK_DOWN = 0.020f   // and eases down, holding a stable scale
        private const val MIN_DYNAMIC = 0.003f // below this bass-flux range ⇒ nothing present
        private const val NORM_THRESHOLD = 0.35f // beat when the flux reaches 35% of the local range
    }
}
