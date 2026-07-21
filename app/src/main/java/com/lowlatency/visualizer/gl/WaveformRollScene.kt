package com.lowlatency.visualizer.gl

import android.opengl.GLES20
import com.lowlatency.visualizer.NativeBridge
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * "Waveform" — a rolling, zoomed-out oscilloscope: ~9 seconds of true
 * min/max waveform history scrolling right to left, in the ultraviolet
 * band-layer look. The data model (band envelopes, AGC, stereo detection,
 * Link etching) lives in [BandWaveHistory], shared with "Waveform 3D".
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
 *    band's own per-slice peak is stored — so the bass is the tall back
 *    layer, mids over it, and highs ride in front as short bright needles. A
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

    private var width = 1f
    private var height = 1f
    private var lastTime = -1f

    private val history = BandWaveHistory()

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
        history.createTexture()
        lastTime = -1f
    }

    override fun onResize(width: Int, height: Int, aspect: Float) {
        this.width = width.toFloat()
        this.height = height.toFloat()
    }

    // Unused: the renderer dispatches StereoScenes through drawStereo.
    override fun draw(pcm: FloatArray, bands: FloatArray, timeSec: Float, dim: Float) = Unit

    override fun onAudioSourceChanged() {
        history.reset()
    }

    override fun drawStereo(pcmStereo: FloatArray, bands: FloatArray, timeSec: Float, dim: Float) {
        val dt = if (lastTime < 0f) 0.016f else (timeSec - lastTime).coerceIn(0f, 0.25f)
        lastTime = timeSec

        val sampleRate = NativeBridge.nativeGetSampleRate().coerceAtLeast(8000)
        history.consume(pcmStereo, dt, sampleRate)

        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, history.textureId)
        GLES20.glUniform1i(uTex, 0)
        GLES20.glUniform2f(uRes, width, height)
        GLES20.glUniform1f(uTime, timeSec)
        GLES20.glUniform1f(uDim, dim)
        // Fractional head keeps the scroll continuous between slice commits.
        GLES20.glUniform1f(uHead, history.headF)

        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, quad)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(aPos)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
    }

    companion object {
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

            // Band layer palette: ULTRAVIOLET — one hue family, three rungs.
            // Indigo bass (dark) -> violet mid -> lilac high (bright). Bands are
            // told apart by a LUMINANCE ladder as much as hue, deliberately: the
            // theme grade downstream rotates/tints this base (Warm embers it,
            // Cool ices it, Mono strips it to greyscale), and the ladder keeps
            // the layers legible under every one of those, including Mono.
            // NOTE: in painter compositing the HIGH colour owns the whole core
            // of the wave (front, shortest layer), so it must stay a saturated
            // COLOUR — a "pale ice" high of (0.72, 0.90, 1.0) painted the entire
            // centre near-white with bloom off. Tried, reverted.
            // (Keep in sync with Waveform3dScene's copy of this palette.)
            const vec3 BASS_COL = vec3(0.36, 0.10, 0.92);
            const vec3 MID_COL  = vec3(0.66, 0.22, 1.00);
            const vec3 HI_COL   = vec3(0.80, 0.56, 1.00);

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
                // colour, so the core reads bright lilac over violet over a
                // deep indigo body, saturated at any loudness. The slight layer
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

                // Loud columns carry a LITTLE HDR headroom so bloom picks out
                // genuine transients. Kept modest: this stacks with the live-edge
                // boost below, and at 0.4 the pair pushed the right third of the
                // wave to ~2x, washing out under standard bloom.
                col *= 1.0 + 0.18 * upExt * upExt;

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
                col *= 1.0 + 0.28 * exp(-age / 256.0);

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
