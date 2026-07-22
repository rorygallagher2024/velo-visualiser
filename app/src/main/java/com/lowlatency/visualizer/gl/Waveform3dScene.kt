package com.lowlatency.visualizer.gl

import android.opengl.GLES20
import com.lowlatency.visualizer.NativeBridge
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * "Waveform 3D" — the rolling waveform's three band envelopes stood up in
 * space: three translucent glowing curtains (lilac highs nearest, violet mids,
 * indigo bass at the back) running away toward a vanishing point, newest audio
 * beside the camera, nine seconds of history receding into haze. The music
 * flows toward you.
 *
 * Rendering is deliberately NOT a raymarch: with the camera beside the lanes,
 * each curtain is a single ray-plane intersection per pixel — three analytic
 * hits, front-to-back over-compositing, done. Depth is continuous (no row
 * quantization), and the envelope is sampled with the same slice-space
 * piecewise-linear scheme as the flat Waveform, so nothing shimmers as the
 * terrain slides toward the camera.
 *
 * Data comes from the shared [BandWaveHistory] — same crossovers, same AGC,
 * same Link etching (beats show as bright strokes through each curtain). This
 * scene and "Waveform" can never disagree about what the wave IS, only about
 * how to stage it.
 */
class Waveform3dScene : StereoScene {

    override val respondsToBeat get() = false   // honest data, staged in space

    private var program = 0
    private var aPos = 0
    private var uRes = 0
    private var uTime = 0
    private var uDim = 0
    private var uHead = 0
    private var uTex = 0

    private var width = 1f
    private var height = 1f

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
        val sampleRate = NativeBridge.nativeGetSampleRate().coerceAtLeast(8000)
        history.consume(pcmStereo, sampleRate)

        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, history.textureId)
        GLES20.glUniform1i(uTex, 0)
        GLES20.glUniform2f(uRes, width, height)
        GLES20.glUniform1f(uTime, timeSec)
        GLES20.glUniform1f(uDim, dim)
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

            // (Keep in sync with WaveformRollScene's copy of this palette.)
            const vec3 BASS_COL = vec3(0.36, 0.10, 0.92);
            const vec3 MID_COL  = vec3(0.66, 0.22, 1.00);
            const vec3 HI_COL   = vec3(0.80, 0.56, 1.00);

            // Stage geometry. Camera at x=0, height CAM_Y, looking down +z with
            // a slight rightward pan and downward tilt; the three curtains are
            // vertical planes x = LANE_X[k] standing on the ground y = 0, their
            // height the band envelope at the depth the ray crosses them.
            // Nearest lane is the SHORTEST band (highs) so it never walls off
            // the scene; the tall indigo bass ridge closes the back.
            const float CAM_Y  = 0.55;
            const float LOOK_X = 0.30;
            const float LOOK_Y = -0.14;
            const float CURTAIN_H = 0.95;      // world height of a full-scale wave
            const float SLICES_PER_Z = 480.0;  // depth -> history mapping
            const float HAZE = 0.26;           // distance fade
            // Margin past the right frame edge where each lane's "now" line
            // sits. A hit on plane x=X at depth z projects at uv.x = X/z - look
            // (exactly), so the birth depth is derived per frame from the REAL
            // aspect ratio: the seam is always just off-screen, every visible
            // pixel has age > 0 (no rough on-screen edge), and the age at the
            // frame edge is ~0 — new audio slides in from the right like the
            // flat Waveform, with no visible latency. A fixed birth depth
            // either parked the seam mid-frame (ugly) or, at the camera plane,
            // hid "now" off-screen for seconds.
            const float EDGE_MARGIN = 0.05;

            int wrapTx(int si) {
                int tx = si % 4096;
                return tx < 0 ? tx + 4096 : tx;
            }

            // Gaussian-weighted envelope: smooth over ±8 slices (~±18ms)
            // to eliminate per-cycle noise and bass comb artifacts.
            // Beat marks use max-pooling so they never get diluted.
            void envAt(float s, out vec3 bands, out float beat) {
                // 17-tap Gaussian, σ ≈ 4 slices
                const float W[9] = float[9](
                    0.1592, 0.1477, 0.1183, 0.0818, 0.0488,
                    0.0251, 0.0111, 0.0043, 0.0014
                );
                
                int c = int(floor(s));
                
                vec3 sum = texelFetch(u_hist, ivec2(wrapTx(c), 0), 0).rgb * W[0];
                float bMax = texelFetch(u_hist, ivec2(wrapTx(c), 1), 0).g;
                
                for (int j = 1; j < 9; j++) {
                    int lo = wrapTx(c - j);
                    int hi = wrapTx(c + j);
                    sum += (texelFetch(u_hist, ivec2(lo, 0), 0).rgb
                          + texelFetch(u_hist, ivec2(hi, 0), 0).rgb) * W[j];
                    bMax = max(bMax, max(
                        texelFetch(u_hist, ivec2(lo, 1), 0).g,
                        texelFetch(u_hist, ivec2(hi, 1), 0).g));
                }
                
                bands = sum;
                beat = bMax;
            }

            void main() {
                // Family burn-in orbit.
                vec2 orbit = vec2(
                    sin(u_time * 0.31) + 0.5 * sin(u_time * 0.13 + 1.7),
                    cos(u_time * 0.27) + 0.5 * cos(u_time * 0.19 + 0.9)
                ) * 0.0015 * u_res.y;
                vec2 uv = (gl_FragCoord.xy - orbit - 0.5 * u_res) / u_res.y;

                // A slow, gentle drift of the gaze — parallax sells the depth.
                float sway = 0.02 * sin(u_time * 0.11);
                vec3 rd = normalize(vec3(uv.x + LOOK_X + sway, uv.y + LOOK_Y, 1.0));

                float laneX[3];
                laneX[0] = 0.30;   // highs — nearest
                laneX[1] = 0.72;   // mids
                laneX[2] = 1.14;   // bass — the back ridge
                vec3 laneCol[3];
                laneCol[0] = HI_COL;
                laneCol[1] = MID_COL;
                laneCol[2] = BASS_COL;

                vec3 col = vec3(0.0);
                float acc = 0.0;
                // The screen's right edge in uv units, this orientation.
                float uvEdge = 0.5 * u_res.x / u_res.y + EDGE_MARGIN;

                for (int k = 0; k < 3; k++) {
                    if (acc > 0.985 || rd.x < 0.02) { break; }
                    float t = laneX[k] / rd.x;
                    float z = t * rd.z;
                    float y = CAM_Y + t * rd.y;
                    // Birth depth: where this lane's now-line projects just past
                    // the right frame edge. Every on-screen pixel is deeper, so
                    // age > 0 everywhere visible and ~0 at the edge itself.
                    float z0 = laneX[k] / (uvEdge + LOOK_X + sway);
                    float age = max((z - z0) * SLICES_PER_Z, 0.0);
                    float slice = u_head - 1.0 - age;
                    // Beyond the stored history: nothing to draw there.
                    if (age > 3800.0) { continue; }

                    vec3 bands; float beat;
                    envAt(slice, bands, beat);
                    float h = bands[k] * CURTAIN_H;

                    // Pixel footprint in world units at this distance, for AA.
                    float aaW = t * 1.8 / u_res.y;

                    float haze = exp(-z * HAZE);
                    // History-edge fade so the far end dissolves, never truncates.
                    haze *= 1.0 - smoothstep(3300.0, 3800.0, age);

                    // A zero-height curtain must draw NOTHING: the fill's
                    // smoothstep at h = 0 still half-covers the first pixel
                    // above the ground line, which painted a glowing baseline
                    // down every empty lane (visible on a pure test tone).
                    float hGate = smoothstep(0.006, 0.028, h);

                    float a = 0.0;
                    vec3 c = laneCol[k];
                    if (y >= 0.0) {
                        // The curtain: translucent fill, denser toward the
                        // ground, with a bright crest line along its top edge.
                        float fill = smoothstep(h + aaW, h - aaW, y);
                        float grad = 1.0 - 0.45 * clamp(y / max(h, 1e-3), 0.0, 1.0);
                        float crest = smoothstep(aaW * 2.6, 0.0, abs(y - h));
                        a = fill * 0.34 * grad + crest * 0.85;
                        // Link beats strike through the curtain as hot strokes.
                        a += fill * beat * 0.55;
                        c += vec3(0.20) * crest;              // crest runs hotter
                    } else {
                        // Polished-floor reflection: same envelope, mirrored,
                        // fading fast with depth below the ground line.
                        float ry = -y;
                        float fill = smoothstep(h + aaW, h - aaW, ry);
                        a = fill * 0.13 * exp(-ry * 2.6);
                    }
                    a *= haze * hGate;
                    // Born hot, like the flat Waveform's pen: the newest audio
                    // glows and cools as it recedes into history.
                    c *= 1.0 + 0.40 * exp(-age / 240.0);

                    col += (1.0 - acc) * a * c;
                    acc += (1.0 - acc) * a;
                }

                // Deep-violet ambience toward the vanishing region, so the
                // curtains dissolve into atmosphere rather than black paper.
                float glow = exp(-abs(uv.y + LOOK_Y) * 6.0)
                           * exp(-max(uv.x + LOOK_X, 0.0) * 1.4);
                col += vec3(0.10, 0.04, 0.22) * glow * 0.5;

                // Dithering is strictly required because the translucent curtains fading 
                // into the distance (haze) will quantize into massive angled bars on an 8-bit screen.
                // We use a 4x4 Bayer Ordered Dither matrix instead of random noise.
                // Ordered dither creates a static geometric crosshatch pattern which the brain
                // ignores as a texture, completely eliminating banding without creating "twinkling dots" or OLED noise.
                const int bayer[16] = int[16](
                    0, 8, 2, 10,
                    12, 4, 14, 6,
                    3, 11, 1, 9,
                    15, 7, 13, 5
                );
                int bx = int(gl_FragCoord.x) % 4;
                int by = int(gl_FragCoord.y) % 4;
                float bVal = float(bayer[by * 4 + bx]) / 16.0;
                col += (bVal - 0.5) * (1.5 / 255.0);

                fragColor = vec4(col * u_dim, 1.0);
            }
        """
    }
}
