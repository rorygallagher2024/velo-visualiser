package com.lowlatency.visualizer.gl

import android.opengl.GLES20
import com.lowlatency.visualizer.BeatPulse
import com.lowlatency.visualizer.BeatSettings
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
    private var uShowBeats = 0
    private var uEnv = 0

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
        uShowBeats = GLES20.glGetUniformLocation(program, "u_showBeats")
        uEnv = GLES20.glGetUniformLocation(program, "u_env")
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
        GLES20.glUniform1f(uShowBeats, if (BeatSettings.showBeatsOnVisuals) 1f else 0f)
        GLES20.glUniform1f(uEnv, BeatPulse.envelope)

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
            uniform float u_showBeats;
            uniform float u_env;
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

            // Gaussian-weighted envelope: smooth over ±4 slices
            // Now features sub-slice linear interpolation to completely
            // eliminate Z-axis "stairstepping" artifacts.
            void envAt(float s, out vec3 bands, out float beat) {
                // 9-tap Gaussian, σ ≈ 2.5 slices, sum = 1.0
                const float W[5] = float[5](
                    0.2235, 0.1924, 0.1211, 0.0559, 0.0189
                );
                
                int c = int(floor(s));
                float f = fract(s);
                vec3 sum = vec3(0.0);
                
                // Slide the kernel by `f` to smoothly blend Gaussian(c) and Gaussian(c+1).
                // Requires 10 fetches (-4 to +5) for a continuous interpolated blur.
                for (int k = -4; k <= 5; k++) {
                    float w0 = (abs(k) <= 4) ? W[abs(k)] : 0.0;
                    float w1 = (abs(k - 1) <= 4) ? W[abs(k - 1)] : 0.0;
                    float wk = w0 * (1.0 - f) + w1 * f;
                    sum += texelFetch(u_hist, ivec2(wrapTx(c + k), 0), 0).rgb * wk;
                }
                
                bands = sum;
                
                // Beat marks are also smoothly interpolated
                float b0 = texelFetch(u_hist, ivec2(wrapTx(c), 1), 0).g;
                float b1 = texelFetch(u_hist, ivec2(wrapTx(c + 1), 1), 0).g;
                beat = mix(b0, b1, f);
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
                    
                    // Frustum culling: if the ray hits this plane far above the maximum
                    // curtain height (0.95) or far below the reflection, it's invisible.
                    // Skip the expensive Gaussian texture fetches entirely.
                    if (abs(y) > 1.1) { continue; }
                    
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
                    // Fix band mapping: bands[0] is Bass, bands[2] is Highs.
                    // To put Highs in front (lane 0), we must invert the index.
                    float h = bands[2 - k] * CURTAIN_H;

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
                    // Depth colour shift: warm/bright near camera, cool/deep in distance.
                    float depthT = smoothstep(0.0, 2400.0, age);
                    vec3 c = mix(laneCol[k], laneCol[k] * vec3(0.6, 0.5, 1.3), depthT);
                    if (y >= 0.0) {
                        // The curtain: translucent fill, brightest at the top
                        // edge which traces a smooth continuous line.
                        // No separate crest point-sample — that aliased into
                        // scattered bright dots because h jumps between pixels.
                        float fill = smoothstep(h + aaW, h - aaW, y);
                        
                        // Fix: Gradient goes from 0.2 (dim) at ground to 1.0 (bright) at crest
                        float topBright = 0.2 + 0.8 * clamp(y / max(h, 1e-3), 0.0, 1.0);
                        a = fill * 0.85 * topBright;
                        
                        // Link beats strike through the curtain as hot strokes.
                        a += fill * beat * 0.55 * u_showBeats;
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
                    // Max HDR: pushed base multiplier from 1.0 -> 2.5 so the waves bloom heavily.
                    c *= 2.5 + 2.0 * exp(-age / 240.0);

                    col += (1.0 - acc) * a * c;
                    acc += (1.0 - acc) * a;
                }

                // Perspective grid floor: faint lines on the ground plane
                // that scroll with the audio history, anchoring the 3D space.
                if (rd.y < -0.001) {
                    float tFloor = -CAM_Y / rd.y;
                    float gz = tFloor * rd.z;
                    float gx = tFloor * rd.x;
                    float floorHaze = exp(-gz * HAZE) * (1.0 - acc);
                    
                    // Z lines scroll in sync with audio head
                    float zCoord = gz * SLICES_PER_Z + u_head;
                    float zd = fract(zCoord / 120.0);
                    zd = min(zd, 1.0 - zd);  // distance to nearest grid line
                    float zLine = smoothstep(0.02, 0.0, zd);
                    // X lines (static, marking the lane positions)
                    float xd = fract(gx / 0.42);
                    xd = min(xd, 1.0 - xd);
                    float xLine = smoothstep(0.015, 0.0, xd);
                    
                    float grid = max(zLine, xLine) * 0.25 * floorHaze;
                    col += grid * vec3(0.35, 0.15, 0.75);
                }

                // Deep-violet ambience toward the vanishing region, so the
                // curtains dissolve into atmosphere rather than black paper.
                // Beat-reactive: the atmosphere "breathes" with the global beat envelope.
                // This correctly fades to zero when audio stops, doesn't strobe at 40Hz,
                // and already respects the "show beats" setting from the CPU.
                float bassEnergy = u_env;
                
                float glow = exp(-abs(uv.y + LOOK_Y) * 6.0)
                           * exp(-max(uv.x + LOOK_X, 0.0) * 1.4);
                           
                // Massively boosted glow: pushes into HDR bloom territory when bass hits
                float glowIntensity = 0.4 + 2.5 * bassEnergy; 
                col += vec3(0.25, 0.08, 0.55) * glow * glowIntensity;

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
