package com.lowlatency.visualizer

import android.util.Log
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Bass-onset beat detector.
 *
 * Detects the *attack* of bass hits (the kick), not the bass *level*. In a full
 * mix the bass plays continuously, so a level test never spikes above its own
 * average — which is why level-based detection only fired on isolated taps
 * against silence. The attack, however, is a sudden RISE in bass energy that
 * spikes on every hit even within busy music.
 *
 * Each frame: one-pole low-pass the raw PCM to isolate the bass band, take its
 * RMS, then compute the positive flux (rise vs the previous frame). A beat is a
 * flux spike above an adaptive baseline, gated by a silence floor and a
 * refractory period.
 *
 * Operates purely on the raw waveform — it never touches the FFT bands or
 * SpectrumAnalyzer the visuals use, so their tuning is unaffected.
 */
class BeatDetector(
    private val lpAlpha: Float = 0.025f,     // one-pole LP coeff (~180 Hz @ 48 kHz)
    private val fluxTauSec: Float = 0.4f,    // adaptive baseline time constant
    private val fluxFactor: Float = 1.6f,    // flux must exceed baseline × this
    private val fluxFloor: Float = 0.0015f,  // + absolute onset floor
    private val bassFloor: Float = 0.006f,   // ignore near-silence (bass RMS)
    private val minGapMs: Long = 120L,       // refractory period
    private val debugName: String? = null,   // if set, log levels to "BeatDetector"
) {
    private var prevBass = 0f
    private var fluxAvg = 0f
    private var lastNs = 0L
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

        val now = System.nanoTime()
        val dt = if (lastNs == 0L) 0.016f else ((now - lastNs) / 1_000_000_000.0).toFloat().coerceIn(0f, 0.1f)
        lastNs = now
        fluxAvg += (flux - fluxAvg) * (dt / fluxTauSec).coerceIn(0f, 1f)

        val threshold = fluxAvg * fluxFactor + fluxFloor
        val isBeat = bass > bassFloor &&
            flux > threshold &&
            (now - lastBeatNs) > minGapMs * 1_000_000L
        if (isBeat) lastBeatNs = now

        if (debugName != null) {
            if (isBeat) {
                Log.i(TAG, "[$debugName] BEAT  bass=${f(bass)} flux=${f(flux)} thr=${f(threshold)}")
            } else if (now - lastLogNs > 300_000_000L) {
                lastLogNs = now
                Log.i(TAG, "[$debugName] level bass=${f(bass)} flux=${f(flux)} thr=${f(threshold)}")
            }
        }
        return isBeat
    }

    private fun f(v: Float) = "%.4f".format(v)

    companion object { private const val TAG = "BeatDetector" }
}
