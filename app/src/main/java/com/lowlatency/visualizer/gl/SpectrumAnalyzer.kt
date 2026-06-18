package com.lowlatency.visualizer.gl

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * CPU FFT spectrum used by the spectrum-driven scenes (Topographic, Circular).
 *
 * The native Oboe engine computes an FFT internally but only exposes three
 * aggregate bands over JNI, and we must not modify the C++ engine. So we run a
 * lightweight radix-2 FFT here over the shared PCM window and produce a
 * log-frequency-binned, smoothed magnitude spectrum (plus gravity peak-hold).
 *
 * Cheap enough for the render thread: a 1024-point FFT is ~5k butterflies/frame.
 */
class SpectrumAnalyzer(val bins: Int = 128, private val fftSize: Int = 1024) {

    private val window = FloatArray(fftSize) {
        (0.5 - 0.5 * cos(2.0 * PI * it / (fftSize - 1))).toFloat()
    }
    private val re = FloatArray(fftSize)
    private val im = FloatArray(fftSize)

    // Amplitude correction: undo the FFT's ~N/2 gain and the Hann window's 0.5
    // coherent gain so a full-scale tone reads ~0 dBFS, independent of FFT size
    // and of how loud the source is. Without this the raw magnitudes carry a
    // fixed ~+48 dB offset and any normal-level (e.g. internal) audio pegs.
    private val ampNorm = 4f / fftSize

    /** Smoothed, normalized magnitudes [0,1], one per log-spaced bin. */
    val magnitudes = FloatArray(bins)
    /** Gravity peak-hold values [0,1] that fall slower than the bars. */
    val peaks = FloatArray(bins)

    fun update(pcm: FloatArray, dt: Float) {
        val n = fftSize
        val m = minOf(pcm.size, n)
        for (i in 0 until m) { re[i] = pcm[i] * window[i]; im[i] = 0f }
        for (i in m until n) { re[i] = 0f; im[i] = 0f }

        fft(re, im)

        val half = n / 2
        val logSpan = half.toDouble()               // log range: bin 1 .. n/2
        for (b in 0 until bins) {
            // Continuous (fractional) FFT-bin positions spanned by this bar.
            val posLo = logSpan.pow(b.toDouble() / bins)
            val posHi = logSpan.pow((b + 1).toDouble() / bins)
            val iLo = floor(posLo).toInt()
            val iHi = floor(posHi).toInt()

            // If the bar spans at least one whole FFT bin, average the energy in
            // it. At the low end the log bands are narrower than a single bin —
            // there, several bars would otherwise snap to the *same* bin and move
            // in lock-step. Interpolate the magnitude at the band centre instead
            // so adjacent low bars differ smoothly.
            val avg = if (iHi > iLo) {
                var sum = 0f; var count = 0
                var k = maxOf(iLo, 1)
                val end = minOf(iHi, half - 1)
                while (k <= end) { sum += magAt(k); count++; k++ }
                if (count > 0) sum / count else magInterp((posLo + posHi) * 0.5, half)
            } else {
                magInterp((posLo + posHi) * 0.5, half)
            }

            // Normalize to a dBFS-like scale, then apply a spectral tilt that
            // boosts +TILT dB/octave above the reference bin (and cuts below it).
            // Music energy slopes ~-3 dB/oct, so without this the low bars
            // dominate and saturate; the tilt flattens that so the bars read
            // evenly across the spectrum.
            val centerBin = logSpan.pow((b + 0.5) / bins)
            val tilt = TILT_DB_PER_OCT * log2((centerBin / TILT_REF_BIN).toFloat())
            val db = 20f * log10(avg * ampNorm + 1e-9f) + tilt
            val target = ((db - FLOOR_DB) / (CEIL_DB - FLOOR_DB)).coerceIn(0f, 1f)

            val cur = magnitudes[b]
            val coeff = if (target > cur) 0.6f else 0.2f   // fast attack, slow decay
            magnitudes[b] = cur + (target - cur) * coeff

            peaks[b] = if (magnitudes[b] >= peaks[b]) magnitudes[b]
            else (peaks[b] - PEAK_FALL * dt).coerceAtLeast(magnitudes[b])
        }
    }

    /** Magnitude of FFT bin [k]. */
    private fun magAt(k: Int): Float = sqrt(re[k] * re[k] + im[k] * im[k])

    /** Linearly-interpolated magnitude at a fractional bin position. */
    private fun magInterp(pos: Double, half: Int): Float {
        val i0 = pos.toInt().coerceIn(1, half - 1)
        val i1 = (i0 + 1).coerceAtMost(half - 1)
        val w = (pos - i0).toFloat().coerceIn(0f, 1f)
        return magAt(i0) * (1f - w) + magAt(i1) * w
    }

    /** In-place iterative radix-2 FFT (fftSize must be a power of two). */
    private fun fft(re: FloatArray, im: FloatArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {                 // bit-reversal permutation
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j or bit
            if (i < j) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }
        var len = 2
        while (len <= n) {
            val ang = -2.0 * PI / len
            val wRe = cos(ang); val wIm = sin(ang)
            var i = 0
            while (i < n) {
                var curRe = 1.0; var curIm = 0.0
                val halfLen = len / 2
                for (k in 0 until halfLen) {
                    val a = i + k
                    val bIdx = a + halfLen
                    val vr = re[bIdx] * curRe - im[bIdx] * curIm
                    val vi = re[bIdx] * curIm + im[bIdx] * curRe
                    re[bIdx] = (re[a] - vr).toFloat()
                    im[bIdx] = (im[a] - vi).toFloat()
                    re[a] = (re[a] + vr).toFloat()
                    im[a] = (im[a] + vi).toFloat()
                    val nRe = curRe * wRe - curIm * wIm
                    curIm = curRe * wIm + curIm * wRe
                    curRe = nRe
                }
                i += len
            }
            len = len shl 1
        }
    }

    companion object {
        private const val PEAK_FALL = 0.4f

        // Level mapping (dBFS-like, after ampNorm). Wide enough to show both the
        // very quiet UNPROCESSED mic and louder internal audio without pegging.
        // FLOOR is the sensitivity knob: lower = quiet sources (the mic) read
        // hotter. CEIL is the anti-peg guard for loud internal audio — leave it
        // put when adjusting sensitivity so internal doesn't start saturating.
        private const val FLOOR_DB = -88f
        private const val CEIL_DB = -12f

        // Spectral-tilt compensation for music's ~-3 dB/oct slope. Reference bin
        // ~= 1 kHz at 48 kHz / 1024-pt FFT (46.9 Hz/bin); below it lows are cut,
        // above it highs are lifted, so the spectrum reads evenly.
        private const val TILT_DB_PER_OCT = 3f
        private const val TILT_REF_BIN = 20.0
    }
}
