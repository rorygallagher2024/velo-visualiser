package com.lowlatency.visualizer.gl

import android.opengl.GLES20
import android.opengl.GLES30
import com.lowlatency.visualizer.BeatPulse
import com.lowlatency.visualizer.NativeBridge
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * The rolling waveform's data model, shared by every scene that renders it
 * ("Waveform" flat, "Waveform 3D" in space): a 4096-column history texture of
 * ~2.3 ms slices built from the actual sample stream.
 *
 * Per column, two texel rows:
 *   row 0 = (bass, mid, high) band envelope extents + signed UP extent
 *   row 1 = (RMS, Link beat mark, unused, signed DOWN extent)
 *
 * Everything data-side lives here — the three-way crossover (~200 Hz / 2 kHz
 * Linkwitz-Riley 4th-order), the display AGC, stereo detection, Link etching,
 * and the batched column uploads — so the scenes can never disagree about
 * what the wave IS, only about how to draw it. Each scene owns its own
 * instance (textures are per-scene GL objects; only the active scene feeds
 * its copy).
 */
class BandWaveHistory {

    var textureId = 0
        private set

    /** Fractional head in slices — sub-column scroll continuity for shaders. */
    var headF = 0f
        private set

    private var head = 0

    // Sample-accurate slice accumulators (mid = (L+R)/2 for the mono shape).
    private var sliceMax = -1f
    private var sliceMin = 1f
    private var sliceSumSq = 0f
    private var sliceL = 0f
    private var sliceR = 0f
    private var sliceDiff = 0f
    private var sliceLo = 0f
    private var sliceMid = 0f
    private var sliceHi = 0f
    private var samplesInSlice = 0
    // 0 = mono min/max wave, 1 = L-up / R-down split; eased, data-detected.
    private var stereoMix = 0f

    // Linkwitz-Riley 4th-order crossovers: two cascaded Butterworth biquads per
    // edge, and every band filtered INDEPENDENTLY. Never derive a band by
    // subtraction ("hi = signal - lowpass"): that leaks through PHASE, not
    // slope — a 439 Hz tone showed up in the subtractive high band at 54%
    // amplitude (|1 - 0.96 at -32 degrees|), a phantom no stopband steepness
    // can remove. One-pole cascades also knee too softly for a visual split.
    private val lowLp1 = Biquad()
    private val lowLp2 = Biquad()
    private val midHp1 = Biquad()
    private val midHp2 = Biquad()
    private val midLp1 = Biquad()
    private val midLp2 = Biquad()
    private val hiHp1 = Biquad()
    private val hiHp2 = Biquad()
    private var bandLo = 0f
    private var bandMid = 0f
    private var bandHi = 0f
    private var filterRate = 0

    // Rolling loudness reference for display auto-gain.
    private var agcRef = AGC_FLOOR

    // Link phase trackers: a wrap between commits means this slice holds a beat.
    private var prevBeatPhase = 0.0
    private var prevBarPhase = 0.0

    // Batched column uploads. One glTexSubImage2D per committed column was
    // ~4 driver round-trips per frame (and ~100 after a hitch); consecutive
    // columns are contiguous in the texture, so each frame's commits upload as
    // a single span instead. Flushed on wrap so the span never splits.
    private val batchRow0 = FloatArray(MAX_BATCH * 4)
    private val batchRow1 = FloatArray(MAX_BATCH * 4)
    private var batchStart = -1
    private var batchCount = 0
    private val batchStaging: FloatBuffer = ByteBuffer
        .allocateDirect(MAX_BATCH * 2 * 4 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()

    /** Allocate the history texture. GL thread, once per scene lifetime. */
    fun createTexture() {
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        textureId = ids[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        val zeros = ByteBuffer.allocateDirect(SLICES * 2 * 4 * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer()
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, GLES30.GL_RGBA16F, SLICES, 2, 0,
            GLES20.GL_RGBA, GLES20.GL_FLOAT, zeros,
        )
        // Filter mode is moot for the shaders' texelFetch reads (which bypass
        // filtering deliberately), but is set anyway so debug sampling behaves.
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_REPEAT)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        head = 0
        headF = 0f
        samplesInSlice = 0
        sliceMax = -1f
        sliceMin = 1f
    }

    /**
     * The engine zeroes both visual rings on source teardown
     * (AudioEngine::clearVisualRings), so no stale audio from the previous
     * source can reach the accumulators — resetting the reference is the
     * whole fix. Do NOT add a discard window here: the ring holds genuine
     * new-source audio by the time this fires, and skipping it just punches
     * a gap into the history.
     */
    fun reset() {
        agcRef = AGC_FLOOR
        lowLp1.reset()
        lowLp2.reset()
        midHp1.reset()
        midHp2.reset()
        midLp1.reset()
        midLp2.reset()
        hiHp1.reset()
        hiHp2.reset()
    }

    // Total ring frames consumed so far, against [StereoRingClock]. -1 = never.
    private var consumedFrames = -1L

    /**
     * Feed the stereo window's genuinely new tail through the slice
     * accumulators, committing a column exactly when it fills — a slice can
     * therefore never be written from empty accumulators (the old glimmer).
     *
     * "New" comes from [StereoRingClock], NOT from frame dt: audio arrives in
     * bursts that never match GL frame timing, so a dt estimate mis-spliced
     * the stream every frame. Each splice is a phase jump — a click — which
     * the band filters rang on, drawing broadband phantom peaks in the bass
     * and high envelopes on even a pure test tone.
     */
    fun consume(pcmStereo: FloatArray, sampleRate: Int) {
        ensureBandFilters(sampleRate)
        val framesPerSlice = (SLICE_SEC * sampleRate).toInt().coerceAtLeast(8)
        val total = pcmStereo.size / 2
        val written = StereoRingClock.totalFrames
        val fresh = if (consumedFrames < 0) {
            total   // first consume: prime from the whole available window
        } else {
            (written - consumedFrames).coerceIn(0L, total.toLong()).toInt()
        }
        consumedFrames = written
        if (fresh > 0) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
            var i = total - fresh
            while (i < total) {
                accumulateFrame(pcmStereo[i * 2], pcmStereo[i * 2 + 1])
                samplesInSlice++
                if (samplesInSlice >= framesPerSlice) {
                    commitSlice(samplesInSlice)
                    resetSliceAccumulators()
                }
                i++
            }
            flushColumns()
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        }
        headF = head + samplesInSlice.toFloat() / framesPerSlice
    }

    /** Recompute the crossover coefficients when the sample rate changes. */
    private fun ensureBandFilters(sampleRate: Int) {
        if (sampleRate == filterRate) return
        filterRate = sampleRate
        val sr = sampleRate.toFloat()
        lowLp1.configureLowPass(XOVER_LO_HZ, sr)
        lowLp2.configureLowPass(XOVER_LO_HZ, sr)
        midHp1.configureHighPass(XOVER_LO_HZ, sr)
        midHp2.configureHighPass(XOVER_LO_HZ, sr)
        midLp1.configureLowPass(XOVER_HI_HZ, sr)
        midLp2.configureLowPass(XOVER_HI_HZ, sr)
        hiHp1.configureHighPass(XOVER_HI_HZ, sr)
        hiHp2.configureHighPass(XOVER_HI_HZ, sr)
    }

    private fun accumulateFrame(l: Float, r: Float) {
        val mid = (l + r) * 0.5f
        if (mid > sliceMax) sliceMax = mid
        if (mid < sliceMin) sliceMin = mid
        sliceSumSq += mid * mid
        val la = if (l < 0f) -l else l
        val ra = if (r < 0f) -r else r
        if (la > sliceL) sliceL = la
        if (ra > sliceR) sliceR = ra
        val diff = if (l - r < 0f) r - l else l - r
        if (diff > sliceDiff) sliceDiff = diff

        splitBands(mid)
        val lo = abs(bandLo)
        val md = abs(bandMid)
        val hi = abs(bandHi)
        if (lo > sliceLo) sliceLo = lo
        if (md > sliceMid) sliceMid = md
        if (hi > sliceHi) sliceHi = hi
    }

    /** Three-way crossover on the mono mid signal into [bandLo]/[bandMid]/[bandHi]. */
    private fun splitBands(mid: Float) {
        bandLo = lowLp2.process(lowLp1.process(mid))
        bandMid = midLp2.process(midLp1.process(midHp2.process(midHp1.process(mid))))
        bandHi = hiHp2.process(hiHp1.process(mid))
    }

    private fun resetSliceAccumulators() {
        samplesInSlice = 0
        sliceMax = -1f
        sliceMin = 1f
        sliceSumSq = 0f
        sliceL = 0f
        sliceR = 0f
        sliceDiff = 0f
        sliceLo = 0f
        sliceMid = 0f
        sliceHi = 0f
    }

    /**
     * 0 = no mark, 0.6 = Link beat, 1.0 = Link downbeat — detected as a phase
     * wrap between slice commits. Etching is Link-ONLY by design: marks are a
     * permanent record, and onset detection would write lies into it.
     */
    private fun linkEtchMark(): Float {
        if (!BeatPulse.linkActive) {
            prevBeatPhase = 0.0
            prevBarPhase = 0.0
            return 0f
        }
        val beatPhase = NativeBridge.nativeLinkBeatPhase()
        val barPhase = NativeBridge.nativeLinkBarPhase()
        var mark = 0f
        if (beatPhase < prevBeatPhase) mark = 0.6f
        if (barPhase < prevBarPhase) mark = 1f
        prevBeatPhase = beatPhase
        prevBarPhase = barPhase
        return mark
    }

    /** Writes one finished column: auto-gained extents, RMS body, band envelopes. */
    private fun commitSlice(sampleCount: Int) {
        // Real stereo shows a genuine L/R difference; upmixed rings are
        // bit-identical, so silence and mono sources stay in min/max mode.
        val stereoTarget = if (sliceDiff > 1e-4f) 1f else 0f
        stereoMix += (stereoTarget - stereoMix) * STEREO_EASE

        val monoUp = max(sliceMax, 0f)
        val monoDown = max(-sliceMin, 0f)
        val up = monoUp + (sliceL - monoUp) * stereoMix
        val down = monoDown + (sliceR - monoDown) * stereoMix
        val peak = max(up, down)
        val rms = kotlin.math.sqrt(sliceSumSq / sampleCount.coerceAtLeast(1))

        // Auto-gain: rise fast so loud material settles inside the frame,
        // fall slowly so breakdowns stay visibly smaller than drops.
        agcRef = if (peak > agcRef) {
            agcRef + (peak - agcRef) * AGC_RISE
        } else {
            max(agcRef * AGC_FALL_PER_SLICE, max(peak, AGC_FLOOR))
        }
        val norm = 1f / max(agcRef, AGC_FLOOR)
        // Soft knee fattens quiet detail without letting peaks clip the frame.
        val dispUp = (up * norm * AMP_TARGET).coerceAtMost(1f).pow(AMP_KNEE)
        val dispDown = (down * norm * AMP_TARGET).coerceAtMost(1f).pow(AMP_KNEE)
        val dispRms = (rms * norm * AMP_TARGET).coerceAtMost(1f).pow(AMP_KNEE)

        // The band envelopes share the master AGC normalization, so their
        // heights stay honestly proportional to each other and to the outline.
        // Each is gated: even 24 dB/oct crossovers leak a few percent, and the
        // AGC's soft knee would lift that residue into a visible phantom
        // curtain on a pure tone.
        val dispLo = displayGate((sliceLo * norm * AMP_TARGET).coerceAtMost(1f).pow(AMP_KNEE))
        val dispMid = displayGate((sliceMid * norm * AMP_TARGET).coerceAtMost(1f).pow(AMP_KNEE))
        val dispHi = displayGate((sliceHi * norm * AMP_TARGET).coerceAtMost(1f).pow(AMP_KNEE))

        if (batchStart < 0) batchStart = head
        val o = batchCount * 4
        batchRow0[o] = dispLo
        batchRow0[o + 1] = dispMid
        batchRow0[o + 2] = dispHi
        batchRow0[o + 3] = dispUp
        batchRow1[o] = dispRms
        batchRow1[o + 1] = linkEtchMark()
        batchRow1[o + 2] = 0f
        batchRow1[o + 3] = dispDown
        batchCount++
        head = (head + 1) % SLICES
        // A wrap or a full batch flushes immediately; the per-frame flush in
        // consume() handles the common case.
        if (batchCount >= MAX_BATCH || head == 0) flushColumns()
    }

    /** Smoothly zero band slivers below ~4% of the frame — crossover residue. */
    private fun displayGate(v: Float): Float {
        val t = ((v - GATE_LO) / (GATE_HI - GATE_LO)).coerceIn(0f, 1f)
        return v * t * t * (3f - 2f * t)
    }

    /** Upload every column committed since the last flush as one span. */
    private fun flushColumns() {
        if (batchCount <= 0) return
        batchStaging.clear()
        batchStaging.put(batchRow0, 0, batchCount * 4)
        batchStaging.put(batchRow1, 0, batchCount * 4)
        batchStaging.position(0)
        GLES20.glTexSubImage2D(
            GLES20.GL_TEXTURE_2D, 0, batchStart, 0, batchCount, 2,
            GLES20.GL_RGBA, GLES20.GL_FLOAT, batchStaging,
        )
        batchStart = -1
        batchCount = 0
    }

    companion object {
        const val SLICES = 4096
        // Sized so the flat Waveform's 3840 visible columns span 9 seconds.
        const val SLICE_SEC = 9f / 3840f            // ~2.3 ms

        private const val AMP_TARGET = 0.85f        // typical loud rides ~85% height
        private const val AMP_KNEE = 0.85f          // soft knee for quiet detail
        private const val AGC_RISE = 0.20f          // per-slice attack toward new peaks
        private const val AGC_FALL_PER_SLICE = 0.999935f // ~25 s half-life at 2.3 ms/slice
        private const val AGC_FLOOR = 0.02f         // never amplify the noise floor
        private const val STEREO_EASE = 0.01f       // mono-to-stereo mode blend (~1 s)
        private const val XOVER_LO_HZ = 200f        // bass | mid crossover
        private const val XOVER_HI_HZ = 2000f       // mid | high crossover
        // Display gate, sized against the WORST crossover residue: a tone just
        // above the low crossover leaks in at -28 dB, which the AGC knee lifts
        // to ~0.058 — so the gate must be fully closed there. A 0.02-0.06 gate
        // passed it at ~95% and the phantom curtain survived. Real band content
        // (kicks ~0.5+, hats ~0.15+) sits above the fade band.
        private const val GATE_LO = 0.05f           // fully closed below this
        private const val GATE_HI = 0.12f           // fully open above this
        // Covers the worst backlog (0.25 s dt clamp ≈ 107 columns) in one span.
        private const val MAX_BATCH = 128
    }

    /**
     * One RBJ biquad section (Butterworth Q). Two cascaded sections make one
     * Linkwitz-Riley 4th-order crossover edge. Direct Form II transposed.
     */
    private class Biquad {
        private var b0 = 1f
        private var b1 = 0f
        private var b2 = 0f
        private var a1 = 0f
        private var a2 = 0f
        private var z1 = 0f
        private var z2 = 0f

        fun configureLowPass(fc: Float, sampleRate: Float) {
            val c = kotlin.math.cos(2.0 * Math.PI * fc / sampleRate).toFloat()
            configure((1f - c) * 0.5f, 1f - c, (1f - c) * 0.5f, c, sampleRate, fc)
        }

        fun configureHighPass(fc: Float, sampleRate: Float) {
            val c = kotlin.math.cos(2.0 * Math.PI * fc / sampleRate).toFloat()
            configure((1f + c) * 0.5f, -(1f + c), (1f + c) * 0.5f, c, sampleRate, fc)
        }

        private fun configure(nb0: Float, nb1: Float, nb2: Float, c: Float, sampleRate: Float, fc: Float) {
            val s = kotlin.math.sin(2.0 * Math.PI * fc / sampleRate).toFloat()
            val alpha = s / (2f * BUTTERWORTH_Q)
            val a0 = 1f + alpha
            b0 = nb0 / a0
            b1 = nb1 / a0
            b2 = nb2 / a0
            a1 = (-2f * c) / a0
            a2 = (1f - alpha) / a0
            reset()
        }

        fun reset() {
            z1 = 0f
            z2 = 0f
        }

        fun process(x: Float): Float {
            val y = b0 * x + z1
            z1 = b1 * x - a1 * y + z2
            z2 = b2 * x - a2 * y
            return y
        }

        companion object {
            private const val BUTTERWORTH_Q = 0.70710678f
        }
    }
}
