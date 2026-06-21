package com.lowlatency.visualizer.gl

import android.opengl.GLES20
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Visual 8 — "Spectral Bloom".
 *
 * A kaleidoscopic, domain-warped plasma mandala that blooms with the music.
 * Two octaves of fractal flow are folded through an N-fold mirror to form a
 * living mandala:
 *   - LOWS  pulse a zoom and ignite an HDR core flare (the "beat bloom"),
 *   - MIDS  rotate the field and morph the petal symmetry,
 *   - HIGHS scatter bright shimmer across the centre.
 *
 * Cheap by design — a handful of value-noise taps per pixel, no raymarch loop —
 * so it sustains 120 Hz. Rendered as a single opaque full-screen fragment pass
 * normalized by the shorter axis, so the mandala stays round and centred at any
 * resolution, aspect, or fold state.
 */
class SpectralBloomScene : GlScene {

    companion object {
        private const val VERTEX_SHADER = """#version 300 es
            layout(location = 0) in vec2 aPos;
            void main() { gl_Position = vec4(aPos, 0.0, 1.0); }
        """

        private const val FRAGMENT_SHADER = """#version 300 es
            precision highp float;
            uniform vec2  u_resolution;
            uniform float u_time;
            uniform float u_dim;
            uniform vec3  u_bands;            // x=low, y=mid, z=high (0..1)
            out vec4 fragColor;

            const float TAU = 6.2831853;

            float hash(vec2 p) {
                p = fract(p * vec2(123.34, 456.21));
                p += dot(p, p + 45.32);
                return fract(p.x * p.y);
            }
            float noise(vec2 p) {
                vec2 i = floor(p), f = fract(p);
                f = f * f * (3.0 - 2.0 * f);
                float a = hash(i),            b = hash(i + vec2(1.0, 0.0));
                float c = hash(i + vec2(0.0, 1.0)), d = hash(i + vec2(1.0, 1.0));
                return mix(mix(a, b, f.x), mix(c, d, f.x), f.y);
            }
            float fbm(vec2 p) {
                float s = 0.0, amp = 0.5;
                for (int i = 0; i < 4; i++) { s += amp * noise(p); p *= 2.0; amp *= 0.5; }
                return s;
            }
            mat2 rot(float a) { float c = cos(a), s = sin(a); return mat2(c, -s, s, c); }
            vec3 palette(float t) {
                return 0.5 + 0.5 * cos(TAU * (t + vec3(0.0, 0.33, 0.67)));
            }

            void main() {
                float low = u_bands.x, mid = u_bands.y, high = u_bands.z;

                // Round + centred on every screen (shorter-axis normalization).
                vec2 uv = (gl_FragCoord.xy - 0.5 * u_resolution)
                          / min(u_resolution.x, u_resolution.y);

                // Bass pulse zoom + slow drift rotation steered by mids.
                uv *= 1.0 - low * 0.22;
                uv = rot(u_time * 0.05 + mid * 0.6) * uv;

                float r = length(uv);
                float a = atan(uv.y, uv.x);

                // Kaleidoscope mirror fold — FIXED petal count. (A mid-driven
                // count snapped between integers, which read as twitchy.)
                float sides = 8.0;
                float sector = TAU / sides;
                a = mod(a, sector);
                a = abs(a - sector * 0.5);
                vec2 p = vec2(cos(a), sin(a)) * r;

                // Domain-warped fractal flow.
                float t = u_time * 0.3;
                vec2 q = vec2(fbm(p * 3.0 + t), fbm(p * 3.0 - t + 5.2));
                float f = fbm(p * 4.0 + q * 1.5 + vec2(0.0, t));

                // Radial energy rings, driven harder on the beat.
                float rings = sin(r * 26.0 - u_time * 3.0 - low * 12.0) * 0.5 + 0.5;
                float v = f * 0.7 + rings * 0.3 * low;

                // Colour: hue flows with the field, the radius, and slow time.
                vec3 col = palette(v + u_time * 0.05 + r * 0.3);
                col *= 0.6 + v * 1.4;                       // contrast
                col *= smoothstep(0.95, 0.1, r);            // vignette to black

                // Highs scatter shimmer across the centre.
                col += high * pow(max(1.0 - r, 0.0), 3.0) * 0.35;

                // Core flare on the bass — restrained so the centre doesn't blow
                // to white (the bloom pipeline amplifies it further).
                float core = exp(-r * 6.5);
                col += core * (0.22 + low * 0.7) * palette(u_time * 0.1 + 0.2);

                col *= 0.7 * (1.0 + low * 0.6);             // overall level + gentle beat lift

                fragColor = vec4(col * u_dim, 1.0);
            }
        """
    }

    private val quad: FloatBuffer = ByteBuffer
        .allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        .apply { put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)); position(0) }

    private var program = 0
    private var aPos = 0
    private var uResolution = 0
    private var uTime = 0
    private var uDim = 0
    private var uBands = 0

    private var width = 1f
    private var height = 1f

    // Smoothed bands — the kaleidoscope's zoom/rotation followed the raw FFT
    // bands, which jitter frame-to-frame; an EMA makes the motion fluid.
    private val smBands = FloatArray(3)
    private var lastTime = -1f

    override fun onCreated() {
        program = ShaderUtil.buildProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        aPos = GLES20.glGetAttribLocation(program, "aPos")
        uResolution = GLES20.glGetUniformLocation(program, "u_resolution")
        uTime = GLES20.glGetUniformLocation(program, "u_time")
        uDim = GLES20.glGetUniformLocation(program, "u_dim")
        uBands = GLES20.glGetUniformLocation(program, "u_bands")
    }

    override fun onResize(width: Int, height: Int, aspect: Float) {
        this.width = width.toFloat()
        this.height = height.toFloat()
    }

    override fun draw(pcm: FloatArray, bands: FloatArray, timeSec: Float, dim: Float) {
        // Smooth the bands (time-based EMA, ~0.25 s) so motion isn't twitchy.
        val dt = if (lastTime < 0f) 0.016f else (timeSec - lastTime).coerceIn(0f, 0.05f)
        lastTime = timeSec
        val k = (dt / 0.25f).coerceIn(0f, 1f)
        for (i in 0..2) smBands[i] += (bands[i] - smBands[i]) * k

        GLES20.glDisable(GLES20.GL_BLEND)    // opaque full-screen pass
        GLES20.glUseProgram(program)
        GLES20.glUniform2f(uResolution, width, height)
        GLES20.glUniform1f(uTime, timeSec)
        GLES20.glUniform1f(uDim, dim)
        GLES20.glUniform3f(uBands, smBands[0], smBands[1], smBands[2])

        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, quad)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(aPos)
    }
}
