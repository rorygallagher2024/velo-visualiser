package com.lowlatency.visualizer.gl

import android.opengl.GLES20
import android.opengl.GLES30
import com.lowlatency.visualizer.BeatPulse
import com.lowlatency.visualizer.NativeBridge
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.abs
import kotlin.math.exp
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
 *    the stereo window each frame), storing signed min and max per ~2.3 ms
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
 *  - THREE TRUE BAND ENVELOPES, the pro-deck look: the sample stream is split
 *    by cheap crossovers (~200 Hz / ~2 kHz, cascaded one-poles) and each
 *    band's own per-slice peak is stored — so the bass is a tall warm body,
 *    mids layer over it, and highs ride in front as short bright needles. A
 *    kick and a hat in one slice are two different SHAPES, not a blended
 *    hue. Layers composite painter-style (front occludes back, slightly
 *    translucent) — additive stacking summed the centre to permanent white
 *    and blew out under bloom. This is also more honest than the
 *    FFT-fraction tint it replaced: the global spectrum at commit time
 *    smeared; band envelopes are the filtered audio at that instant.
 *  - With Ableton Link connected, every beat is etched into the history as a
 *    small axis tick (downbeats taller), so the last nine seconds read as a
 *    bar ruler. Link only: an etched mark is a PERMANENT record, and onset
 *    detection is not reliable enough to write lies into a timeline.
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
    private var sliceLo = 0f
    private var sliceMid = 0f
    private var sliceHi = 0f
    private var samplesInSlice = 0
    // 0 = mono min/max wave, 1 = L-up / R-down split; eased, data-detected.
    private var stereoMix = 0f
    private var head = 0
    private var lastTime = -1f

    // Crossover state (two cascaded one-poles per corner: ~12 dB/oct) and the
    // per-sample band outputs they produce. Coefficients follow the sample rate.
    private var lpA1 = 0f
    private var lpA2 = 0f
    private var lpB1 = 0f
    private var lpB2 = 0f
    private var bandLo = 0f
    private var bandMid = 0f
    private var bandHi = 0f
    private var alphaLo = 0.026f
    private var alphaHi = 0.23f
    private var filterRate = 0

    // Rolling loudness reference for display auto-gain.
    private var agcRef = AGC_FLOOR

    // Link phase trackers: a wrap between commits means this slice holds a beat.
    private var prevBeatPhase = 0.0
    private var prevBarPhase = 0.0

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
        // Filter mode is moot for the shader's texelFetch reads (which bypass
        // filtering deliberately — see the rasterization comment there), but is
        // set anyway so any debug sampling behaves.
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
        ensureBandFilters(sampleRate)
        consumeFrames(pcmStereo, dt, sampleRate, framesPerSlice)

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

    override fun onAudioSourceChanged() {
        // The engine zeroes both visual rings on source teardown
        // (AudioEngine::clearVisualRings), so no stale audio from the previous
        // source can reach the accumulators — resetting the reference is the
        // whole fix. Do NOT add a discard window here: the ring holds genuine
        // new-source audio by the time this fires, and skipping it just punches
        // a gap into the history.
        agcRef = AGC_FLOOR
        lpA1 = 0f
        lpA2 = 0f
        lpB1 = 0f
        lpB2 = 0f
    }

    /** Recompute the crossover coefficients when the sample rate changes. */
    private fun ensureBandFilters(sampleRate: Int) {
        if (sampleRate == filterRate) return
        filterRate = sampleRate
        alphaLo = 1f - exp(-TWO_PI_F * XOVER_LO_HZ / sampleRate)
        alphaHi = 1f - exp(-TWO_PI_F * XOVER_HI_HZ / sampleRate)
    }

    /**
     * Feeds the newest [dt]-worth of the stereo window through the slice
     * accumulators, committing a column exactly when it fills — a slice can
     * therefore never be written from empty accumulators (the old glimmer).
     */
    private fun consumeFrames(
        pcmStereo: FloatArray,
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
                commitSlice(samplesInSlice)
                resetSliceAccumulators()
            }
            i++
        }
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
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
        lpA1 += (mid - lpA1) * alphaLo
        lpA2 += (lpA1 - lpA2) * alphaLo
        lpB1 += (mid - lpB1) * alphaHi
        lpB2 += (lpB1 - lpB2) * alphaHi
        bandLo = lpA2
        bandMid = lpB2 - lpA2
        bandHi = mid - lpB2
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
        val dispLo = (sliceLo * norm * AMP_TARGET).coerceAtMost(1f).pow(AMP_KNEE)
        val dispMid = (sliceMid * norm * AMP_TARGET).coerceAtMost(1f).pow(AMP_KNEE)
        val dispHi = (sliceHi * norm * AMP_TARGET).coerceAtMost(1f).pow(AMP_KNEE)

        texelStaging.clear()
        texelStaging.put(dispLo).put(dispMid).put(dispHi).put(dispUp)
        texelStaging.put(dispRms).put(linkEtchMark()).put(0f).put(dispDown)
        texelStaging.position(0)
        // One column, both rows.
        GLES20.glTexSubImage2D(
            GLES20.GL_TEXTURE_2D, 0, head, 0, 1, 2,
            GLES20.GL_RGBA, GLES20.GL_FLOAT, texelStaging,
        )
        head = (head + 1) % SLICES
    }

    companion object {
        // Density is sized to the widest screen, not a typical one: 1920 visible
        // columns under-resolved any display wider than that (the Fold's inner
        // panel, every phone in landscape), stretching each slice across >1 px —
        // which read as a low-resolution wave. 3840 keeps at least ~1.6 columns
        // per pixel on a 2400 px landscape and supersamples ~3.6x in portrait.
        // NOTE: several constants below are PER-SLICE; halving the slice length
        // halves their time base, so they are derived from real seconds here.
        private const val SLICES = 4096
        private const val VISIBLE_SLICES = 3840f
        private const val SECONDS_PER_SCREEN = 9f
        private const val SLICE_SEC = SECONDS_PER_SCREEN / VISIBLE_SLICES  // ~2.3 ms

        private const val AMP_TARGET = 0.85f        // typical loud rides ~85% height
        private const val AMP_KNEE = 0.85f          // soft knee for quiet detail
        private const val AGC_RISE = 0.20f          // per-slice attack toward new peaks
        private const val AGC_FALL_PER_SLICE = 0.999935f // ~25 s half-life at 2.3 ms/slice
        private const val AGC_FLOOR = 0.02f         // never amplify the noise floor
        private const val STEREO_EASE = 0.01f       // mono-to-stereo mode blend (~1 s)
        private const val XOVER_LO_HZ = 200f        // bass | mid crossover
        private const val XOVER_HI_HZ = 2000f       // mid | high crossover
        private const val TWO_PI_F = (2.0 * Math.PI).toFloat()

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

            const float SLICES  = 4096.0;
            const float VISIBLE = 3840.0;

            // Band layer palette: deck-familiar (warm bass, green mid, ice high).
            // Layers composite painter-style (occlusion, not addition), so these
            // read as themselves at any loudness instead of summing to white.
            const vec3 BASS_COL = vec3(1.00, 0.32, 0.10);
            const vec3 MID_COL  = vec3(0.22, 0.88, 0.36);
            const vec3 HI_COL   = vec3(0.42, 0.72, 1.00);

            int wrapTx(int si) {
                int tx = si % 4096;
                return tx < 0 ? tx + 4096 : tx;
            }

            // The envelopes as CONTINUOUS piecewise-linear functions of slice
            // position: band extents (lo, mid, hi) and the outline (up, dn)
            // interpolated between adjacent committed columns. Everything below
            // is evaluated in SLICE space, never from the pixel grid —
            // screen-space estimates change with sub-pixel scroll phase and
            // glimmer.
            void envAt(float s, out vec3 bands, out float up, out float dn) {
                float fl = floor(s);
                float f = s - fl;
                int a = wrapTx(int(fl));
                int b = wrapTx(int(fl) + 1);
                vec4 a0 = texelFetch(u_hist, ivec2(a, 0), 0);
                vec4 a1 = texelFetch(u_hist, ivec2(a, 1), 0);
                vec4 b0 = texelFetch(u_hist, ivec2(b, 0), 0);
                vec4 b1 = texelFetch(u_hist, ivec2(b, 1), 0);
                bands = mix(a0.rgb, b0.rgb, f);
                up = mix(a0.a, b0.a, f);
                dn = mix(a1.a, b1.a, f);
            }

            void main() {
                // Family burn-in orbit: the whole instrument drifts a few px.
                vec2 orbit = vec2(
                    sin(u_time * 0.31) + 0.5 * sin(u_time * 0.13 + 1.7),
                    cos(u_time * 0.27) + 0.5 * cos(u_time * 0.19 + 0.9)
                ) * 0.0015 * u_res.y;
                vec2 frag = gl_FragCoord.xy - orbit;

                float x01 = frag.x / u_res.x;                 // 0 left … 1 right
                float slice = u_head - 1.0 - (1.0 - x01) * VISIBLE;
                float spp = max(VISIBLE / u_res.x, 1.0);      // slices per pixel

                // Exact rasterization of the envelope over this pixel's span:
                //  - ENDPOINTS: the piecewise-linear envelope at the span edges.
                //    Continuous in scroll position, so a column drifting out of
                //    the span hands its influence over smoothly — nothing pops
                //    frame to frame (the glimmer of every screen-space scheme).
                //  - INTERIOR: exact committed column heights, so a narrow
                //    peak's apex is always reached — interpolation-only reads
                //    made peaks breathe as they scrolled.
                // Adjacent columns therefore connect with true diagonals (the
                // DAW contour), and every height is a constant of the data.
                float sL = slice - 0.5 * spp;
                float sR = min(slice + 0.5 * spp, u_head - 1.0);  // never past the pen
                vec3 bandL; float upL; float dnL;
                envAt(sL, bandL, upL, dnL);
                vec3 bandR; float upR; float dnR;
                envAt(sR, bandR, upR, dnR);
                vec3 bandExt = max(bandL, bandR);
                float upExt = max(upL, upR);
                float dnExt = max(dnL, dnR);
                float beatMark = 0.0;
                int iA = int(ceil(sL));
                for (int k = 0; k < 8; k++) {
                    int si = iA + k;
                    if (float(si) > sR) { break; }
                    int tx = wrapTx(si);
                    vec4 a = texelFetch(u_hist, ivec2(tx, 0), 0);
                    vec4 b = texelFetch(u_hist, ivec2(tx, 1), 0);
                    bandExt = max(bandExt, a.rgb);
                    upExt = max(upExt, a.a);
                    dnExt = max(dnExt, b.a);
                    beatMark = max(beatMark, b.g);
                }

                // The wave lives in a centred band (55% of the height) — an
                // instrument in a space, not wall-to-wall.
                float halfBand = 0.5 * u_res.y * 0.55;
                float yc = (frag.y - 0.5 * u_res.y) / halfBand;
                float ext = yc >= 0.0 ? upExt : dnExt;        // signed peak extents
                float d = abs(yc);
                float aaPx = 1.25 / halfBand;

                // Column tint for the shell and halo: bands mixed by their extents.
                float bSum = bandExt.x + bandExt.y + bandExt.z;
                vec3 waveCol = (BASS_COL * bandExt.x + MID_COL * bandExt.y
                              + HI_COL * bandExt.z) / max(bSum, 1e-4);

                // The true outline as a faint shell — it carries the stereo
                // asymmetry (band layers are mono, the envelope is L/R).
                float shell = smoothstep(ext + aaPx * 2.0, ext - aaPx, d);
                vec3 col = waveCol * shell * 0.16;

                // THREE BAND ENVELOPES, painter-composited — the deck look.
                // Bass painted first, mids over it, highs in front: each layer
                // OCCLUDES what's beneath rather than adding to it. Additive
                // stacking made the centre the SUM of all layers — permanently
                // white on any real music, and with bloom on that white blew
                // out. Convex over-compositing can never exceed a layer's own
                // colour, so the core reads ice-blue over green over a warm
                // bass body, saturated at any loudness. The slight layer
                // translucency lets a hint of the band beneath glow through.
                // Clamped inside the side's envelope so stereo asymmetry holds.
                vec3 be = min(bandExt, vec3(ext));
                float fillLo = smoothstep(be.x + aaPx * 2.0, be.x - aaPx, d);
                float fillMid = smoothstep(be.y + aaPx * 2.0, be.y - aaPx, d);
                float fillHi = smoothstep(be.z + aaPx * 2.0, be.z - aaPx, d);
                vec3 body = vec3(0.0);
                body = mix(body, BASS_COL, fillLo * 0.95);
                body = mix(body, MID_COL, fillMid * 0.85);
                body = mix(body, HI_COL, fillHi * 0.85);
                col += body * 1.05;

                // A faint halo just past the peak edge keeps tips soft even
                // with bloom off.
                float halo = smoothstep(ext + 14.0 * aaPx, ext, d)
                           * (1.0 - shell);
                col += waveCol * halo * 0.10;

                // Loud columns carry HDR headroom so bloom picks out transients.
                col *= 1.0 + 0.4 * upExt * upExt;

                // Link bar ruler: beats etched as small axis ticks, downbeats
                // taller — the history reads as bars. Added before the live-edge
                // multiply below, so fresh etches are born hot like the wave.
                if (beatMark > 0.05) {
                    float tickH = mix(0.05, 0.105, step(0.8, beatMark));
                    float tick = smoothstep(tickH, tickH * 0.55, d);
                    col += vec3(0.55) * tick * beatMark;
                }

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

                // 1-LSB dither: the dim halo and axis gradients band visibly on
                // the direct 8-bit path (bloom off), which reads as compression.
                float dith = fract(sin(dot(frag.xy, vec2(12.9898, 78.233))) * 43758.5453);
                col += (dith - 0.5) * (1.5 / 255.0);

                fragColor = vec4(col * u_dim, 1.0);
            }
        """
    }
}
