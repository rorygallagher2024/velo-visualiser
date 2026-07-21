package com.lowlatency.visualizer.gl

import android.opengl.GLES20
import android.opengl.GLES30
import com.lowlatency.visualizer.BeatPulse
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
 *  - Colour uses power-sharpened band dominance — real music holds all three
 *    bands in similar ranges, so a linear blend is permanently green; cubing
 *    the normalized weights lets the winner take the hue. The fractions are
 *    stored raw and graded VERTICALLY in the shader: the axis leans low/mid
 *    (solid kick body), the tips lean high (hats read as bright needles above
 *    the body) — a kick and a hat in one slice stay two visible things.
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
    private var samplesInSlice = 0
    // 0 = mono min/max wave, 1 = L-up / R-down split; eased, data-detected.
    private var stereoMix = 0f
    // Temporally smoothed band fractions so hues flow instead of flickering.
    private var smLo = 0.33f
    private var smMid = 0.34f
    private var smHi = 0.33f
    private var head = 0
    private var lastTime = -1f

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

    override fun onAudioSourceChanged() {
        // The engine zeroes both visual rings on source teardown
        // (AudioEngine::clearVisualRings), so no stale audio from the previous
        // source can reach the accumulators — resetting the reference is the
        // whole fix. Do NOT add a discard window here: the ring holds genuine
        // new-source audio by the time this fires, and skipping it just punches
        // a gap into the history.
        agcRef = AGC_FLOOR
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

        // Power-sharpened dominance, stored as normalized band FRACTIONS — the
        // shader turns them into colour, which lets it grade each column
        // vertically (bass body at the axis, treble sparkle at the tips) the way
        // a real deck waveform reads. A single premixed hue can't do that: a
        // kick and a hat in the same 4.7 ms slice just averaged into one colour.
        val m = max(bands[0], max(bands[1], bands[2])).coerceAtLeast(1e-3f)
        val wl = (bands[0] / m).pow(3)
        val wm = (bands[1] / m).pow(3)
        val wh = (bands[2] / m).pow(3)
        // Floored: true silence (paused playback clears the rings) yields all-
        // zero bands, and dividing by a zero weight sum mints NaN — which the
        // GPU paints WHITE and which poisons the colour smoother forever.
        val ws = (wl + wm + wh).coerceAtLeast(1e-4f)
        // ~80 ms smoothing: hues sweep like a deck, never flicker.
        if (!smLo.isFinite() || !smMid.isFinite() || !smHi.isFinite()) {
            smLo = 0.33f; smMid = 0.34f; smHi = 0.33f
        }
        smLo += (wl / ws - smLo) * COLOR_SMOOTH
        smMid += (wm / ws - smMid) * COLOR_SMOOTH
        smHi += (wh / ws - smHi) * COLOR_SMOOTH

        texelStaging.clear()
        texelStaging.put(smLo).put(smMid).put(smHi).put(dispUp)
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
        private const val COLOR_SMOOTH = 0.03f      // per-slice hue easing (~80 ms)
        private const val STEREO_EASE = 0.01f       // mono-to-stereo mode blend (~1 s)

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

            // Max/average over the exact texels whose centres fall in one pixel
            // column's span. texelFetch bypasses filtering deliberately: an
            // interpolated read of a narrow peak varies with sub-texel phase,
            // which made peaks breathe as they scrolled.
            void fetchSpan(float slice, float spp,
                           out float up, out float dn, out float rms,
                           out float beat, out vec3 fr) {
                int iLo = int(floor(slice - 0.5 * spp + 0.5));
                int iHi = int(floor(slice + 0.5 * spp + 0.5));
                up = 0.0; dn = 0.0; rms = 0.0; beat = 0.0;
                fr = vec3(0.0);
                float count = 0.0;
                for (int k = 0; k < 8; k++) {
                    int si = iLo + k;
                    if (si > iHi) { break; }
                    int tx = si % 4096;
                    if (tx < 0) { tx += 4096; }
                    vec4 a = texelFetch(u_hist, ivec2(tx, 0), 0);
                    vec4 b = texelFetch(u_hist, ivec2(tx, 1), 0);
                    up = max(up, a.a);
                    dn = max(dn, b.a);
                    rms = max(rms, b.r);
                    beat = max(beat, b.g);
                    fr += a.rgb;
                    count += 1.0;
                }
                fr /= max(count, 1.0);
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

                float upExt; float dnExt; float rmsRaw; float beatMark; vec3 fr;
                fetchSpan(slice, spp, upExt, dnExt, rmsRaw, beatMark, fr);
                // NOTE: do not derive an edge feather from neighbouring pixel
                // columns' extents (slope-adaptive AA). The covered-slice sets
                // shift as the wave scrolls sub-pixel, so the slope — and with it
                // the edge WIDTH — flickers frame to frame, which reads as a
                // pronounced glimmer along the whole contour. Tried, reverted.

                // The wave lives in a centred band (55% of the height) — an
                // instrument in a space, not wall-to-wall.
                float halfBand = 0.5 * u_res.y * 0.55;
                float yc = (frag.y - 0.5 * u_res.y) / halfBand;
                float ext = yc >= 0.0 ? upExt : dnExt;        // signed peak extents
                float rmsExt = rmsRaw;                         // RMS body extent
                float d = abs(yc);
                float aaPx = 1.25 / halfBand;

                // Vertical band grading — how a deck waveform actually reads.
                // The mix is re-weighted along the column's height: the axis
                // leans into the low/mid mix (solid kick body), the tips lean
                // into the highs (hats become bright needles above the body)
                // — so a kick and a hat in one slice stay two visible things
                // instead of averaging into a single hue.
                float t = smoothstep(0.10, 0.95, d / max(ext, 1e-3));
                vec3 grade = mix(vec3(1.5, 0.9, 0.35), vec3(0.35, 0.8, 1.7), t);
                vec3 w = fr * grade;
                w /= max(w.x + w.y + w.z, 1e-4);
                vec3 hue = vec3(
                    w.x * 1.00 + w.y * 0.16 + w.z * 0.25,
                    w.x * 0.20 + w.y * 0.95 + w.z * 0.55,
                    w.x * 0.08 + w.y * 0.22 + w.z * 1.00
                );
                // Dominant highs whiten so hats sparkle instead of going navy.
                float whiten = max(w.z - 0.5, 0.0) * 1.5;
                hue += (vec3(1.0) - hue) * whiten;
                // Loud columns carry HDR headroom so bloom picks out transients.
                hue *= 1.0 + 0.4 * upExt * upExt;

                // Two-layer body, the DAW look: a translucent peak envelope
                // with a brighter, whiter RMS core inside it.
                float peakFill = smoothstep(ext + aaPx * 2.0, ext - aaPx, d);
                float bodyFill = smoothstep(rmsExt + aaPx * 2.0, rmsExt - aaPx, d);
                vec3 bodyCol = mix(hue, vec3(1.0), 0.22) * 1.18;
                vec3 col = hue * peakFill * 0.52
                         + bodyCol * bodyFill * 0.75;

                // A faint halo just past the peak edge keeps tips soft even
                // with bloom off.
                float halo = smoothstep(ext + 14.0 * aaPx, ext, d)
                           * (1.0 - peakFill);
                col += hue * halo * 0.10;

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
