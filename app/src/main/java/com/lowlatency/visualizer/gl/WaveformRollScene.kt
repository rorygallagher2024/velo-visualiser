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
 * What makes it read like a real DAW/MiniMeters waveform rather than a blob:
 *
 *  - Slices are built from the actual SAMPLE STREAM (the newest dt worth of
 *    the PCM window each frame), storing signed min and max per ~4.7 ms
 *    column — so the wave is asymmetric and finely striated, and a slice can
 *    never commit empty (columns only complete ON sample boundaries).
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
class WaveformRollScene : GlScene {

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

    // Sample-accurate slice accumulators.
    private var sliceMax = -1f
    private var sliceMin = 1f
    private var sliceSumSq = 0f
    private var samplesInSlice = 0
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

    override fun draw(pcm: FloatArray, bands: FloatArray, timeSec: Float, dim: Float) {
        val dt = if (lastTime < 0f) 0.016f else (timeSec - lastTime).coerceIn(0f, 0.25f)
        lastTime = timeSec

        val sampleRate = NativeBridge.nativeGetSampleRate().coerceAtLeast(8000)
        val samplesPerSlice = (SLICE_SEC * sampleRate).toInt().coerceAtLeast(8)
        consumeSamples(pcm, bands, dt, sampleRate, samplesPerSlice)

        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, historyTex)
        GLES20.glUniform1i(uTex, 0)
        GLES20.glUniform2f(uRes, width, height)
        GLES20.glUniform1f(uTime, timeSec)
        GLES20.glUniform1f(uDim, dim)
        // Fractional head keeps the scroll continuous between slice commits.
        GLES20.glUniform1f(uHead, head + samplesInSlice.toFloat() / samplesPerSlice)

        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, quad)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(aPos)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
    }

    /**
     * Feeds the newest [dt]-worth of the PCM window through the slice
     * accumulators, committing a column exactly when it fills — a slice can
     * therefore never be written from empty accumulators (the old glimmer).
     */
    private fun consumeSamples(
        pcm: FloatArray,
        bands: FloatArray,
        dt: Float,
        sampleRate: Int,
        samplesPerSlice: Int,
    ) {
        val fresh = min(pcm.size, (dt * sampleRate).toInt() + 1)
        if (fresh <= 0) return
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, historyTex)
        var i = pcm.size - fresh
        while (i < pcm.size) {
            val s = pcm[i]
            if (s > sliceMax) sliceMax = s
            if (s < sliceMin) sliceMin = s
            sliceSumSq += s * s
            samplesInSlice++
            if (samplesInSlice >= samplesPerSlice) {
                commitSlice(bands, samplesInSlice)
                samplesInSlice = 0
                sliceMax = -1f
                sliceMin = 1f
                sliceSumSq = 0f
            }
            i++
        }
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
    }

    /** Writes one finished column: auto-gained extents, RMS body, colour. */
    private fun commitSlice(bands: FloatArray, sampleCount: Int) {
        val up = max(sliceMax, 0f)
        val down = max(-sliceMin, 0f)
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
        val ws = wl + wm + wh
        var r = (wl * 1.00f + wm * 0.16f + wh * 0.25f) / ws
        var g = (wl * 0.20f + wm * 0.95f + wh * 0.55f) / ws
        var b = (wl * 0.08f + wm * 0.22f + wh * 1.00f) / ws
        // Dominant highs whiten so hats sparkle instead of going navy.
        val whiten = (wh / ws - 0.5f).coerceAtLeast(0f) * 1.5f
        r += (1f - r) * whiten
        g += (1f - g) * whiten
        b += (1f - b) * whiten
        // ~80 ms colour smoothing: hues sweep like a deck, never flicker.
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

                // There can be more history columns than screen pixels, so a
                // pixel takes the MAX over the slice span it covers (the way
                // DAWs rasterize waveforms) — otherwise narrow peaks breathe
                // as their phase drifts across sampling positions.
                float spp = max(VISIBLE / u_res.x, 1e-3);     // slices per pixel
                float taps = clamp(ceil(spp), 1.0, 4.0);
                float upExt = 0.0;
                float dnExt = 0.0;
                float rmsRaw = 0.0;
                vec3 rgbAcc = vec3(0.0);
                for (int k = 0; k < 4; k++) {
                    if (float(k) >= taps) { break; }
                    float sk = slice + spp * ((float(k) + 0.5) / taps - 0.5);
                    float uk = (sk + 0.5) / SLICES;           // REPEAT wraps the ring
                    vec4 a = texture(u_hist, vec2(uk, 0.25));
                    vec4 b = texture(u_hist, vec2(uk, 0.75));
                    upExt = max(upExt, a.a);
                    dnExt = max(dnExt, b.a);
                    rmsRaw = max(rmsRaw, b.r);
                    rgbAcc += a.rgb;
                }
                vec4 hUp = vec4(rgbAcc / taps, upExt);
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
