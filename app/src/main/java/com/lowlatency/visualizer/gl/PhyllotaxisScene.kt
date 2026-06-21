package com.lowlatency.visualizer.gl

import android.opengl.GLES20
import android.opengl.GLES30
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Visual 13 — "Phyllotaxis Bloom".
 *
 * A sunflower spiral (golden-angle phyllotaxis) of dots. Each dot maps to an FFT
 * bin — center = lows, rim = highs — so its size, brightness and outward push
 * track that band. The whole bloom rotates slowly and pulses with the music.
 *
 * Entirely vertex-shader driven (positions derived from gl_VertexID); the
 * spectrum is sampled via a vertex texture fetch. Cheap and mathematically tidy.
 */
class PhyllotaxisScene : GlScene {

    companion object {
        private const val DOTS = 900
        private const val BINS = 128

        private const val VERTEX_SHADER = """#version 300 es
            uniform float u_time;
            uniform float u_aspect;
            uniform sampler2D u_spectrum;     // BINS x 1
            out vec3 v_col;
            out float v_bright;

            const float GOLDEN = 2.39996323;  // golden angle (radians)
            const float N = float(${DOTS});

            vec3 palette(float t) { return 0.5 + 0.5 * cos(6.28318 * (t + vec3(0.0, 0.33, 0.67))); }

            void main() {
                float i = float(gl_VertexID);
                float frac = i / N;                       // 0 center .. 1 rim
                float r = sqrt(frac);                      // even area distribution
                float theta = i * GOLDEN + u_time * 0.25;

                float spec = texture(u_spectrum, vec2(frac, 0.5)).r;

                float rr = r * (0.82 + spec * 0.5);        // band energy pushes dots outward
                vec2 p = vec2(cos(theta), sin(theta)) * rr;
                p.x /= u_aspect;                            // keep the bloom round
                gl_Position = vec4(p, 0.0, 1.0);

                gl_PointSize = mix(5.0, 16.0, spec) * (1.0 - r * 0.2);
                v_col = palette(frac * 0.8 + spec * 0.3 + u_time * 0.03);
                // Every dot reaches into HDR; loud bins push much brighter.
                v_bright = 1.2 + spec * 2.2;
            }
        """

        private const val FRAGMENT_SHADER = """#version 300 es
            precision highp float;
            uniform float u_dim;
            in vec3 v_col;
            in float v_bright;
            out vec4 fragColor;
            void main() {
                vec2 c = gl_PointCoord * 2.0 - 1.0;
                float d = dot(c, c);
                if (d > 1.0) discard;
                float glow = exp(-d * 2.2);
                fragColor = vec4(v_col * v_bright * glow * u_dim, 1.0);
            }
        """
    }

    private val specUpload: FloatBuffer = ByteBuffer
        .allocateDirect(BINS * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()

    private var program = 0
    private var uTime = 0
    private var uAspect = 0
    private var uSpectrum = 0
    private var uDim = 0
    private var specTex = 0
    private var aspect = 1f

    override fun onCreated() {
        program = ShaderUtil.buildProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        uTime = GLES20.glGetUniformLocation(program, "u_time")
        uAspect = GLES20.glGetUniformLocation(program, "u_aspect")
        uSpectrum = GLES20.glGetUniformLocation(program, "u_spectrum")
        uDim = GLES20.glGetUniformLocation(program, "u_dim")

        val tex = IntArray(1)
        GLES20.glGenTextures(1, tex, 0)
        specTex = tex[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, specTex)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST)
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, GLES30.GL_R32F,
            BINS, 1, 0, GLES30.GL_RED, GLES20.GL_FLOAT, null
        )
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
    }

    override fun onResize(width: Int, height: Int, aspect: Float) {
        this.aspect = aspect
    }

    override fun draw(pcm: FloatArray, bands: FloatArray, timeSec: Float, dim: Float) {
        specUpload.clear(); specUpload.put(SpectrumData.magnitudes).position(0)

        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE)   // additive
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, specTex)
        GLES20.glTexSubImage2D(
            GLES20.GL_TEXTURE_2D, 0, 0, 0, BINS, 1,
            GLES30.GL_RED, GLES20.GL_FLOAT, specUpload
        )
        GLES20.glUniform1i(uSpectrum, 0)
        GLES20.glUniform1f(uTime, timeSec)
        GLES20.glUniform1f(uAspect, aspect)
        GLES20.glUniform1f(uDim, dim)

        GLES20.glDrawArrays(GLES20.GL_POINTS, 0, DOTS)
    }

    override fun onDeactivate() {
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
    }
}
