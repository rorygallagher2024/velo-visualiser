package com.lowlatency.visualizer.gl

import android.opengl.GLES20
import android.opengl.GLES30
import com.lowlatency.visualizer.BeatPulse
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * "Electric Iris" — a large reactive eye. A dark pupil dilates with the bass; the
 * iris body is woven from organic radial fibres whose glow follows the spectrum
 * around the circle (mirrored for bilateral symmetry); a coloured limbal ring
 * pulses on the beat; and electric filaments crackle around the rim, driven by
 * the highs.
 *
 *   - Lows  -> pupil dilation + inner-ring glow
 *   - Mids  -> iris rotation + outer hue
 *   - Highs -> electric crackle around the rim
 *   - Beat  -> limbal-ring flare + a gentle zoom punch
 */
class ElectricIrisScene : GlScene {

    companion object {
        private const val BINS = 128

        private const val VERTEX_SHADER = """#version 300 es
            layout(location = 0) in vec2 aPos;
            void main() { gl_Position = vec4(aPos, 0.0, 1.0); }
        """

        private const val FRAGMENT_SHADER = """#version 300 es
            precision highp float;
            uniform vec2  u_resolution;
            uniform float u_time;
            uniform float u_dim;
            uniform float u_low;
            uniform float u_mid;
            uniform float u_high;
            uniform float u_env;              // beat envelope
            uniform sampler2D u_spectrum;     // BINS x 1
            out vec4 fragColor;

            const float TAU = 6.2831853;

            vec3 palette(float t) {
                return 0.5 + 0.5 * cos(TAU * (t + vec3(0.0, 0.33, 0.67)));
            }

            float hash21(vec2 p) {
                p = fract(p * vec2(123.34, 345.45));
                p += dot(p, p + 34.345);
                return fract(p.x * p.y);
            }
            float vnoise(vec2 p) {
                vec2 i = floor(p), f = fract(p);
                float a = hash21(i), b = hash21(i + vec2(1.0, 0.0));
                float c = hash21(i + vec2(0.0, 1.0)), d = hash21(i + vec2(1.0, 1.0));
                vec2 u = f * f * (3.0 - 2.0 * f);
                return mix(mix(a, b, u.x), mix(c, d, u.x), u.y);
            }

            void main() {
                vec2 uv = (gl_FragCoord.xy - 0.5 * u_resolution) / min(u_resolution.x, u_resolution.y);
                uv /= (1.0 + u_env * 0.06);                  // subtle beat zoom punch
                float r = length(uv);
                float ang = atan(uv.y, uv.x);
                float a01 = ang / TAU + 0.5;
                float aRot = a01 + u_time * 0.02 + u_mid * 0.12;   // mids rotate the iris
                float aMir = abs(fract(aRot) - 0.5) * 2.0;         // bilateral symmetry
                float spec = texture(u_spectrum, vec2(aMir, 0.5)).r;

                float pr = 0.17 + u_low * 0.11;              // pupil dilates with bass
                float outer = 0.45;
                float t = clamp((r - pr) / (outer - pr), 0.0, 1.0);

                vec3 col = vec3(0.0);

                // --- Iris body: organic radial fibres ---
                float irisMask = smoothstep(pr, pr + 0.015, r) * smoothstep(outer, outer - 0.06, r);
                float fib = 130.0;
                float wob = vnoise(vec2(aRot * 8.0, r * 4.0));
                float stri = pow(0.5 + 0.5 * sin(aRot * TAU * 0.5 * fib + wob * 6.0), 3.0);
                float jit = 0.5 + 0.5 * hash21(vec2(floor(aRot * fib), 1.0));
                float radialBreak = mix(0.6, 1.0, vnoise(vec2(aRot * 30.0, r * 10.0)));
                float fibre = stri * jit * radialBreak;

                vec3 inner = vec3(1.0, 0.55, 0.12);          // amber core
                vec3 outerC = palette(0.45 + u_mid * 0.30);  // outer hue follows mids
                vec3 base = mix(inner, outerC, smoothstep(0.0, 1.0, t));
                float shade = (0.18 + fibre * (0.5 + spec * 2.6)) * (0.5 + 0.6 * t);
                col += base * irisMask * shade;

                // Dark band just inside the rim, then the bright limbal ring.
                col = mix(col, col * 0.2, smoothstep(0.045, 0.0, abs(r - (outer - 0.02))));
                col += outerC * smoothstep(0.02, 0.0, abs(r - outer)) * (0.6 + u_env * 1.6);

                // --- Pupil: carve dark, glowing inner ring ---
                float pupilEdge = smoothstep(pr, pr - 0.012, r);
                col *= (1.0 - pupilEdge);
                col += vec3(0.06, 0.02, 0.10) * pupilEdge;   // faint sheen inside pupil
                col += inner * smoothstep(0.014, 0.0, abs(r - pr)) * (0.5 + u_low * 2.5);

                // --- Electric crackle around the rim (highs + beat) ---
                float arcR = outer + 0.06;
                float crk = vnoise(vec2(aRot * 60.0, u_time * 4.0));
                float fil = smoothstep(0.80, 1.0, crk);
                float band = smoothstep(0.08, 0.0, abs(r - arcR));
                col += vec3(0.70, 0.85, 1.0) * fil * band * (u_high * 3.0 + u_env * 1.2);

                col *= 1.0 - 0.35 * smoothstep(0.62, 1.1, r);  // vignette

                fragColor = vec4(max(col, 0.0) * u_dim, 1.0);
            }
        """
    }

    private val upload: FloatBuffer = ByteBuffer
        .allocateDirect(BINS * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()

    private val quad: FloatBuffer = ByteBuffer
        .allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        .apply { put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)); position(0) }

    private var program = 0
    private var aPos = 0
    private var uResolution = 0
    private var uTime = 0
    private var uDim = 0
    private var uLow = 0
    private var uMid = 0
    private var uHigh = 0
    private var uEnv = 0
    private var uSpectrum = 0

    private var specTex = 0
    private var width = 1f
    private var height = 1f

    override fun onCreated() {
        program = ShaderUtil.buildProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        aPos = GLES20.glGetAttribLocation(program, "aPos")
        uResolution = GLES20.glGetUniformLocation(program, "u_resolution")
        uTime = GLES20.glGetUniformLocation(program, "u_time")
        uDim = GLES20.glGetUniformLocation(program, "u_dim")
        uLow = GLES20.glGetUniformLocation(program, "u_low")
        uMid = GLES20.glGetUniformLocation(program, "u_mid")
        uHigh = GLES20.glGetUniformLocation(program, "u_high")
        uEnv = GLES20.glGetUniformLocation(program, "u_env")
        uSpectrum = GLES20.glGetUniformLocation(program, "u_spectrum")

        val tex = IntArray(1)
        GLES20.glGenTextures(1, tex, 0)
        specTex = tex[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, specTex)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, GLES30.GL_R32F,
            BINS, 1, 0, GLES30.GL_RED, GLES20.GL_FLOAT, null
        )
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
    }

    override fun onResize(width: Int, height: Int, aspect: Float) {
        this.width = width.toFloat()
        this.height = height.toFloat()
    }

    override fun draw(pcm: FloatArray, bands: FloatArray, timeSec: Float, dim: Float) {
        upload.clear()
        upload.put(SpectrumData.magnitudes)
        upload.position(0)

        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, specTex)
        GLES20.glTexSubImage2D(
            GLES20.GL_TEXTURE_2D, 0, 0, 0, BINS, 1,
            GLES30.GL_RED, GLES20.GL_FLOAT, upload
        )
        GLES20.glUniform1i(uSpectrum, 0)
        GLES20.glUniform2f(uResolution, width, height)
        GLES20.glUniform1f(uTime, timeSec)
        GLES20.glUniform1f(uDim, dim)
        GLES20.glUniform1f(uLow, bands[0])
        GLES20.glUniform1f(uMid, bands[1])
        GLES20.glUniform1f(uHigh, bands[2])
        GLES20.glUniform1f(uEnv, BeatPulse.envelope)

        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, quad)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(aPos)
    }
}
