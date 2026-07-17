package com.lowlatency.visualizer.gl

import android.opengl.GLES20
import android.opengl.GLES30
import com.lowlatency.visualizer.NativeBridge
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * "Waveform" — a rolling, zoomed-out oscilloscope: ~9 seconds of true
 * min/max waveform history scrolling right to left, coloured by frequency
 * the way DJ software paints its decks (bass reds, mid greens, treble blues,
 * whitening when the highs dominate).
 *
 * What makes it read like a real DAW waveform rather than a blob:
 *
 *  - Slices are built from the actual SAMPLE STREAM (the newest dt worth of
 *    the stereo window each frame), storing signed min and max per ~4.7 ms
 *    column — so the wave is asymmetric and finely striated, and a slice can
 *    never commit empty (columns only complete ON sample boundaries).
 *  - STEREO SPLIT: when the source carries real stereo (detected from the
 *    data — upmixed rings are bit-identical L==R), the upper half becomes the
 *    LEFT channel and the lower half the RIGHT, eased over ~a second so the
 *    mode never pops. Stereo width turns visible as asymmetry: mono kicks are
 *    symmetric spikes, wide pads bloom unevenly, ping-pong delays alternate
 *    sides. Mono sources keep the classic min/max waveform.
 *  - Display amplitude is auto-gained against a rolling loudness reference
 *    (fast to rise, ~25 s to fall, floored above the mic noise floor), so the
 *    quiet Unprocessed mic and full-scale local files both ride ~85% of the
 *    height, in any orientation, while drops still tower over breakdowns.
 *  - Colour uses power-sharpened band dominance — real music holds all three
 *    bands in similar ranges, so a linear blend is permanently green; cubing
 *    the normalized weights lets the winner take the hue.
 *  - The whole instrument rides the family's slow burn-in orbit, and the zero
 *    axis is a dim, end-feathered whisper rather than a hard hairline.
 */
class WaveformRollScene : StereoScene {

    override val respondsToBeat get() = false   // instrument: honest readout

    private var program = 0
    private var aPos = 0
    private var uRes = 0
    private var uTime = 0
    private var uDim = 0
    private var uHead = 0
    private var uTex = 0
    private var historyTex = 0

    private var width = 1f
    private var height = 1f

    // Sample-accurate slice accumulators (mid = (L+R)/2 for the mono shape).
    private var sliceMax = -1f
    private var sliceMin = 1f
    private var sliceSumSq = 0f
    private var sliceL = 0f
    private var sliceR = 0f
    private var sliceDiff = 0f
    private var samplesInSlice = 0
    // 0 = mono min/max wave, 1 = L-up / R-down split; eased, data-detected.
    private var stereoMix = 0f
    // Temporally smoothed colour so hues flow instead of flickering per slice.
    private var smR = 0.3f
    private var smG = 0.6f
    private var smB = 0.4f
    private var head = 0
    private var lastTime = -1f

    // Rolling loudness reference for display auto-gain.
    private var agcRef = AGC_FLOOR

    private val texelStaging: FloatBuffer = ByteBuffer
        .allocateDirect(2 * 4 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()

    private val quad: FloatBuffer = ByteBuffer
        .allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        .apply { put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)); position(0) }

    override fun onCreated() {
        program = ShaderUtil.buildProgram(VS, FS)
        aPos = GLES20.glGetAttribLocation(program, "aPos")
        uRes = GLES20.glGetUniformLocation(program, "u_res")
        uTime = GLES20.glGetUniformLocation(program, "u_time")
        uDim = GLES20.glGetUniformLocation(program, "u_dim")
        uHead = GLES20.glGetUniformLocation(program, "u_head")
        uTex = GLES20.glGetUniformLocation(program, "u_hist")
        createHistoryTexture()
        head = 0
        samplesInSlice = 0
        sliceMax = -1f
        sliceMin = 1f
        lastTime = -1f
    }

    private fun createHistoryTexture() {
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        historyTex = ids[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, historyTex)
        // Two rows per slice: row 0 = colour + upper extent, row 1 = lower.
        val zeros = ByteBuffer.allocateDirect(SLICES * 2 * 4 * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer()
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, GLES30.GL_RGBA16F, SLICES, 2, 0,
            GLES20.GL_RGBA, GLES20.GL_FLOAT, zeros,
        )
        // LINEAR across slices: contours flow (the DAW look) and the scroll
        // interpolates sub-slice instead of snapping — the shimmer's root.
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_REPEAT)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
    }

    override fun onResize(width: Int, height: Int, aspect: Float) {
        this.width = width.toFloat()
        this.height = height.toFloat()
    }

    // Unused: the renderer dispatches StereoScenes through drawStereo.
    override fun draw(pcm: FloatArray, bands: FloatArray, timeSec: Float, dim: Float) = Unit

    override fun drawStereo(pcmStereo: FloatArray, bands: FloatArray, timeSec: Float, dim: Float) {
        val dt = if (lastTime < 0f) 0.016f else (timeSec - lastTime).coerceIn(0f, 0.25f)
        lastTime = timeSec

        val sampleRate = NativeBridge.nativeGetSampleRate().coerceAtLeast(8000)
        val framesPerSlice = (SLICE_SEC * sampleRate).toInt().coerceAtLeast(8)
        consumeFrames(pcmStereo, bands, dt, sampleRate, framesPerSlice)

        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, historyTex)
        GLES20.glUniform1i(uTex, 0)
        GLES20.glUniform2f(uRes, width, height)
        GLES20.glUniform1f(uTime, timeSec)
        GLES20.glUniform1f(uDim, dim)
        // Fractional head keeps the scroll continuous between slice commits.
        GLES20.glUniform1f(uHead, head + samplesInSlice.toFloat() / framesPerSlice)

        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, quad)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(aPos)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
    }

    /**
     * Feeds the newest [dt]-worth of the stereo window through the slice
     * accumulators, committing a column exactly when it fills — a slice can
     * therefore never be written from empty accumulators (the old glimmer).
     */
    private fun consumeFrames(
        pcmStereo: FloatArray,
        bands: FloatArray,
        dt: Float,
        sampleRate: Int,
        framesPerSlice: Int,
    ) {
        val total = pcmStereo.size / 2
        val fresh = min(total, (dt * sampleRate).toInt() + 1)
        if (fresh <= 0) return
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, historyTex)
        var i = total - fresh
        while (i < total) {
            accumulateFrame(pcmStereo[i * 2], pcmStereo[i * 2 + 1])
            samplesInSlice++
            if (samplesInSlice >= framesPerSlice) {
                commitSlice(bands, samplesInSlice)
                resetSliceAccumulators()
            }
            i++
        }
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
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
    }

    private fun resetSliceAccumulators() {
        samplesInSlice = 0
        sliceMax = -1f
        sliceMin = 1f
        sliceSumSq = 0f
        sliceL = 0f
        sliceR = 0f
        sliceDiff = 0f
    }

    /** Writes one finished column: auto-gained extents, RMS body, colour. */
    private fun commitSlice(bands: FloatArray, sampleCount: Int) {
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

        // Power-sharpened dominance: the winning band takes the hue.
        val m = max(bands[0], max(bands[1], bands[2])).coerceAtLeast(1e-3f)
        val wl = (bands[0] / m).pow(3)
        val wm = (bands[1] / m).pow(3)
        val wh = (bands[2] / m).pow(3)
        // Floored: true silence (paused playback clears the rings) yields all-
        // zero bands, and dividing by a zero weight sum mints NaN — which the
        // GPU paints WHITE and which poisons the colour smoother forever.
        val ws = (wl + wm + wh).coerceAtLeast(1e-4f)
        var r = (wl * 1.00f + wm * 0.16f + wh * 0.25f) / ws
        var g = (wl * 0.20f + wm * 0.95f + wh * 0.55f) / ws
        var b = (wl * 0.08f + wm * 0.22f + wh * 1.00f) / ws
        // Dominant highs whiten so hats sparkle instead of going navy.
        val whiten = (wh / ws - 0.5f).coerceAtLeast(0f) * 1.5f
        r += (1f - r) * whiten
        g += (1f - g) * whiten
        b += (1f - b) * whiten
        // ~80 ms colour smoothing: hues sweep like a deck, never flicker.
        if (!smR.isFinite() || !smG.isFinite() || !smB.isFinite()) {
            smR = 0.3f; smG = 0.6f; smB = 0.4f
        }
        smR += (r - smR) * COLOR_SMOOTH
        smG += (g - smG) * COLOR_SMOOTH
        smB += (b - smB) * COLOR_SMOOTH
        // Loud columns carry HDR headroom so bloom picks out transients.
        val lift = 1f + 0.4f * dispUp * dispUp

        texelStaging.clear()
        texelStaging.put(smR * lift).put(smG * lift).put(smB * lift).put(dispUp)
        texelStaging.put(dispRms).put(0f).put(0f).put(dispDown)
        texelStaging.position(0)
        // One column, both rows.
        GLES20.glTexSubImage2D(
            GLES20.GL_TEXTURE_2D, 0, head, 0, 1, 2,
            GLES20.GL_RGBA, GLES20.GL_FLOAT, texelStaging,
        )
        head = (head + 1) % SLICES
    }

    companion object {
        private const val SLICES = 2048
        private const val VISIBLE_SLICES = 1920f
        private const val SECONDS_PER_SCREEN = 9f
        private const val SLICE_SEC = SECONDS_PER_SCREEN / VISIBLE_SLICES  // ~4.7 ms

        private const val AMP_TARGET = 0.85f        // typical loud rides ~85% height
        private const val AMP_KNEE = 0.85f          // soft knee for quiet detail
        private const val AGC_RISE = 0.20f          // per-slice attack toward new peaks
        private const val AGC_FALL_PER_SLICE = 0.99987f  // ~25 s half-life
        private const val AGC_FLOOR = 0.02f         // never amplify the noise floor
        private const val COLOR_SMOOTH = 0.06f      // per-slice hue easing (~80 ms)
        private const val STEREO_EASE = 0.02f       // mono-to-stereo mode blend (~1 s)

        private const val VS = """#version 300 es
            layout(location = 0) in vec2 aPos;
            void main() { gl_Position = vec4(aPos, 0.0, 1.0); }
        """

        private const val FS = """#version 300 es
            precision highp float;
            uniform vec2  u_res;
            uniform float u_time, u_dim, u_head;
            uniform sampler2D u_hist;
            out vec4 fragColor;

            const float SLICES  = 2048.0;
            const float VISIBLE = 1920.0;

            void main() {
                // Family burn-in orbit: the whole instrument drifts a few px.
                vec2 orbit = vec2(
                    sin(u_time * 0.31) + 0.5 * sin(u_time * 0.13 + 1.7),
                    cos(u_time * 0.27) + 0.5 * cos(u_time * 0.19 + 0.9)
                ) * 0.0015 * u_res.y;
                vec2 frag = gl_FragCoord.xy - orbit;

                float x01 = frag.x / u_res.x;                 // 0 left … 1 right
                float slice = u_head - 1.0 - (1.0 - x01) * VISIBLE;

                // A pixel takes the MAX over the exact texels whose centres
                // fall in its span (texelFetch bypasses filtering — the way
                // DAWs rasterize waveforms). Max of FILTERED taps is not the
                // same thing: an interpolated read of a narrow peak varies
                // with sub-texel phase, which made peaks breathe as they
                // scrolled. Exact fetches make every column's height a
                // constant of its committed data.
                float spp = max(VISIBLE / u_res.x, 1.0);      // slices per pixel
                int iLo = int(floor(slice - 0.5 * spp + 0.5));
                int iHi = int(floor(slice + 0.5 * spp + 0.5));
                float upExt = 0.0;
                float dnExt = 0.0;
                float rmsRaw = 0.0;
                vec3 rgbAcc = vec3(0.0);
                float count = 0.0;
                for (int k = 0; k < 6; k++) {
                    int si = iLo + k;
                    if (si > iHi) { break; }
                    int tx = si % 2048;
                    if (tx < 0) { tx += 2048; }
                    vec4 a = texelFetch(u_hist, ivec2(tx, 0), 0);
                    vec4 b = texelFetch(u_hist, ivec2(tx, 1), 0);
                    upExt = max(upExt, a.a);
                    dnExt = max(dnExt, b.a);
                    rmsRaw = max(rmsRaw, b.r);
                    rgbAcc += a.rgb;
                    count += 1.0;
                }
                vec4 hUp = vec4(rgbAcc / max(count, 1.0), upExt);
                vec4 hDn = vec4(rmsRaw, 0.0, 0.0, dnExt);

                // The wave lives in a centred band (55% of the height) — an
                // instrument in a space, not wall-to-wall.
                float halfBand = 0.5 * u_res.y * 0.55;
                float yc = (frag.y - 0.5 * u_res.y) / halfBand;
                float ext = yc >= 0.0 ? hUp.a : hDn.a;        // signed peak extents
                float rmsExt = hDn.r;                          // RMS body extent
                float d = abs(yc);
                float aaPx = 1.25 / halfBand;

                // Two-layer body, the DAW look: a translucent peak envelope
                // with a brighter, whiter RMS core inside it.
                float peakFill = smoothstep(ext + aaPx * 2.0, ext - aaPx, d);
                float bodyFill = smoothstep(rmsExt + aaPx * 2.0, rmsExt - aaPx, d);
                vec3 bodyCol = mix(hUp.rgb, vec3(1.0), 0.22) * 1.18;
                vec3 col = hUp.rgb * peakFill * 0.52
                         + bodyCol * bodyFill * 0.75;

                // A faint halo just past the peak edge keeps tips soft even
                // with bloom off.
                float halo = smoothstep(ext + 14.0 * aaPx, ext, d)
                           * (1.0 - peakFill);
                col += hUp.rgb * halo * 0.10;

                // The live edge: columns are born hot and cool to archival
                // brightness over ~1.2 s of travel — the right edge writes
                // like a chart recorder's pen, and with bloom on each beat
                // visibly lands, then settles as it becomes history. Lighting
                // only: the stored waveform stays an honest record.
                float age = max(u_head - 1.0 - slice, 0.0);
                col *= 1.0 + 0.45 * exp(-age / 256.0);

                // Zero axis: a dim whisper, feathered to nothing at the ends —
                // it rides the orbit and stays far below burn-in territory.
                float axisPx = d * halfBand;
                float ends = smoothstep(0.0, 0.12, x01) * smoothstep(1.0, 0.88, x01);
                col += vec3(0.10) * smoothstep(1.6, 0.4, axisPx) * ends;

                fragColor = vec4(col * u_dim, 1.0);
            }
        """
    }
}
