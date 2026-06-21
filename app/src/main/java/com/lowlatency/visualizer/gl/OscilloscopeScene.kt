package com.lowlatency.visualizer.gl

import android.opengl.GLES20
import android.opengl.GLES30
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * "Hardware Oscilloscope" — a single continuous trace rendered as a CRT
 * phosphor beam.
 *
 * Rather than drawing line geometry, this is a full-screen fragment pass: the
 * PCM window is uploaded as a 1-row float texture, and for every pixel the
 * shader finds the exact distance to the nearest waveform segment and applies
 * an exponential decay (organic phosphor glow + tight filament core).
 *
 * Reactivity:
 *   - Lows  -> widen the bloom radius and drive chromatic aberration that grows
 *              toward the screen edges on heavy transients.
 *   - Signal velocity -> dim the beam where the trace moves fast vertically
 *              (short dwell time) and brighten it where it concentrates (steady
 *              state), mimicking a real CRT beam depositing energy by dwell.
 *
 * The glow is computed in pixel space and aberration/vignette use u_aspectRatio,
 * so the trace and its halo never stretch when a foldable changes layout.
 */
class OscilloscopeScene : GlScene {

    companion object {
        private const val POINTS = 1024            // matches native render window

        private const val VERTEX_SHADER = """
            attribute vec2 aPos;
            uniform float u_time;
            varying vec2  v_orbit;          // burn-in orbit offset, in pixels
            void main() {
                // OLED burn-in protection: a slow, non-repeating orbit built
                // from incommensurate sine/cosine frequencies (~up to +/-2.25px).
                // The trace samples at this offset (below) so the whole waveform
                // drifts a few pixels and never sits on fixed phosphors.
                vec2 orbit = vec2(
                    sin(u_time * 0.31) + 0.5 * sin(u_time * 0.13 + 1.7),
                    cos(u_time * 0.27) + 0.5 * cos(u_time * 0.19 + 0.9)
                );
                v_orbit = orbit * 1.5;
                gl_Position = vec4(aPos, 0.0, 1.0);
            }
        """

        // ES SL 1.00. Loop bounds are constant as required by the spec.
        private const val FRAGMENT_SHADER = """
            precision highp float;

            uniform sampler2D u_wave;       // POINTS x 1, value = 0.5 + 0.5*pcm
            uniform vec2  u_resolution;
            uniform float u_aspectRatio;    // width / height
            uniform float u_low;            // bass 0..1
            uniform float u_dim;            // transition fade
            uniform float u_gain;           // amplitude scale
            varying vec2  v_orbit;          // burn-in orbit (pixels)

            const float N = float(${POINTS});
            // The UNPROCESSED mic is quiet for speech: lift hard, then soft-clip.
            const float SENSITIVITY = 5.0;

            // tanh() is not available in GLSL ES 1.00, so implement it. Input is
            // bounded (|x| <= SENSITIVITY), so exp() can't overflow; clamp anyway.
            float softClip(float x) {
                float e = exp(2.0 * clamp(x, -10.0, 10.0));
                return (e - 1.0) / (e + 1.0);
            }

            // Decoded, soft-clipped waveform sample. softClip aggressively boosts
            // quiet signals and smoothly limits loud transients (claps) to +/-1.
            float sampleY(int i) {
                float u = (float(i) + 0.5) / N;
                // The texture now holds raw PCM [-1, 1] directly from the native buffer.
                float raw = texture2D(u_wave, vec2(u, 0.5)).r;
                return softClip(raw * SENSITIVITY);
            }

            // Pixel position of sample i, with an optional horizontal shift.
            vec2 samplePx(int i, float xShift) {
                float x = (float(i) / (N - 1.0)) * u_resolution.x + xShift;
                float y = (0.5 + 0.5 * sampleY(i) * u_gain) * u_resolution.y;
                return vec2(x, y);
            }

            float distToSegment(vec2 p, vec2 a, vec2 b) {
                vec2 pa = p - a;
                vec2 ba = b - a;
                float h = clamp(dot(pa, ba) / max(dot(ba, ba), 1e-5), 0.0, 1.0);
                return length(pa - ba * h);
            }

            // Minimum distance from fragment to the trace, scanning a small
            // window of segments around the fragment's x (handles steep
            // transients where neighbouring segments are nearly vertical).
            float traceDistance(vec2 fragPx, float xShift) {
                float fx = (fragPx.x - xShift) / u_resolution.x * (N - 1.0);
                int center = int(fx);
                float best = 1e9;
                for (int k = -3; k <= 3; k++) {
                    int i = center + k;
                    if (i < 0 || i >= ${POINTS} - 1) continue;
                    vec2 a = samplePx(i, xShift);
                    vec2 b = samplePx(i + 1, xShift);
                    best = min(best, distToSegment(fragPx, a, b));
                }
                return best;
            }

            void main() {
                // Sample at the orbit-offset coordinate (burn-in protection).
                vec2 fragPx = gl_FragCoord.xy + v_orbit;

                // Exponential halo radius (bass widens it). Kept tight so this
                // reads as a fine neon filament with a soft glow — clearly
                // distinct from the flat 1px Raw Oscilloscope.
                float bloom = 4.5 * (1.0 + u_low * 1.2);

                // Chromatic aberration grows toward the edges, scaled by bass.
                float edge = abs((fragPx.x / u_resolution.x - 0.5) * 2.0);
                float ca = u_low * 6.0 * edge;   // pixels of R/B separation

                // HDR overdrive: heavy bass drives the core past 1.0 (FP16 fb).
                float hdrGain = 1.0 + u_low * 4.0;

                float dCa = traceDistance(fragPx,  ca);
                float d0  = traceDistance(fragPx, 0.0);
                float dCb = traceDistance(fragPx, -ca);

                // Micro-core: sub-pixel ultra-bright WHITE filament (thin).
                float coreSig = 0.6;
                float core = exp(-(d0 * d0) / (2.0 * coreSig * coreSig));

                // Colored phosphor halo: smooth exponential falloff, per-channel
                // split for the analog high-voltage fringe.
                vec3 phosphorTint = vec3(0.45, 1.0, 0.65);
                vec3 halo = vec3(exp(-dCa / bloom),
                                 exp(-d0  / bloom),
                                 exp(-dCb / bloom)) * phosphorTint;

                // White HDR core + thin colored exponential halo.
                vec3 col = vec3(core * hdrGain) + halo * 0.55;

                // Signal-velocity dimming: short beam dwell on fast transients.
                float fx = fragPx.x / u_resolution.x * (N - 1.0);
                int c = int(fx);
                c = c < 0 ? 0 : (c > ${POINTS} - 2 ? ${POINTS} - 2 : c);
                float vel = abs(sampleY(c + 1) - sampleY(c));
                float dwell = mix(0.35, 1.0, 1.0 / (1.0 + vel * 8.0));
                col *= dwell * u_dim;

                // Subtle aspect-correct vignette for the analog feel.
                vec2 cc = (fragPx / u_resolution - 0.5);
                cc.x *= u_aspectRatio;
                col *= smoothstep(1.15, 0.25, length(cc));

                gl_FragColor = vec4(col, 1.0);
            }
        """
    }

    // Direct buffer reused each frame to upload the waveform texture.
    private val upload: FloatBuffer = ByteBuffer
        .allocateDirect(POINTS * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()

    private val quad: FloatBuffer = ByteBuffer
        .allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        .apply { put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)); position(0) }

    private val vbo = IntArray(1)
    private var program = 0
    private var aPos = 0
    private var uWave = 0
    private var uResolution = 0
    private var uAspect = 0
    private var uLow = 0
    private var uDim = 0
    private var uGain = 0
    private var uTime = 0

    private var texId = 0
    private var width = 1f
    private var height = 1f
    private var aspect = 1f

    override fun onCreated() {
        program = ShaderUtil.buildProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        aPos = GLES20.glGetAttribLocation(program, "aPos")
        uWave = GLES20.glGetUniformLocation(program, "u_wave")
        uResolution = GLES20.glGetUniformLocation(program, "u_resolution")
        uAspect = GLES20.glGetUniformLocation(program, "u_aspectRatio")
        uLow = GLES20.glGetUniformLocation(program, "u_low")
        uDim = GLES20.glGetUniformLocation(program, "u_dim")
        uGain = GLES20.glGetUniformLocation(program, "u_gain")
        uTime = GLES20.glGetUniformLocation(program, "u_time")

        // Static VBO for the full-screen quad.
        GLES20.glGenBuffers(1, vbo, 0)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo[0])
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, quad.capacity() * 4, quad, GLES20.GL_STATIC_DRAW)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)

        // 1-row float texture holding the PCM window.
        val tex = IntArray(1)
        GLES20.glGenTextures(1, tex, 0)
        texId = tex[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST)
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, GLES30.GL_R32F,
            POINTS, 1, 0, GLES30.GL_RED, GLES20.GL_FLOAT, null
        )
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
    }

    override fun onResize(width: Int, height: Int, aspect: Float) {
        this.width = width.toFloat()
        this.height = height.toFloat()
        this.aspect = aspect
    }

    override fun draw(
        pcm: FloatArray,
        bands: FloatArray,
        magnitudes: FloatArray,
        peaks: FloatArray,
        timeSec: Float,
        dim: Float,
        sharedBuffer: ByteBuffer?
    ) {
        GLES20.glDisable(GLES20.GL_BLEND)   // opaque full-screen pass
        GLES20.glUseProgram(program)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId)
        
        // Zero-copy upload: use the DirectByteBuffer directly if available.
        if (sharedBuffer != null) {
            sharedBuffer.position(0)
            GLES20.glTexSubImage2D(
                GLES20.GL_TEXTURE_2D, 0, 0, 0, POINTS, 1,
                GLES30.GL_RED, GLES20.GL_FLOAT, sharedBuffer
            )
        } else {
            upload.clear()
            for (i in 0 until POINTS) { upload.put(pcm[i].coerceIn(-1f, 1f)) }
            upload.position(0)
            GLES20.glTexSubImage2D(
                GLES20.GL_TEXTURE_2D, 0, 0, 0, POINTS, 1,
                GLES30.GL_RED, GLES20.GL_FLOAT, upload
            )
        }
        GLES20.glUniform1i(uWave, 0)

        GLES20.glUniform2f(uResolution, width, height)
        GLES20.glUniform1f(uAspect, aspect)
        GLES20.glUniform1f(uLow, bands[0])
        GLES20.glUniform1f(uDim, dim)
        GLES20.glUniform1f(uGain, 0.85f)
        GLES20.glUniform1f(uTime, timeSec)

        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo[0])
        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(aPos)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
    }
}
