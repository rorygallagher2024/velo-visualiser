package com.lowlatency.visualizer.gl

import android.opengl.GLES20
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Visual 15 — "Mandala Pulse".
 *
 * A hypnotic, symmetric mandala pattern that pulses with the audio.
 *   - Lows -> Scale the entire pattern and pulse the core.
 *   - Mids -> Control rotation speed and color shifts.
 *   - Highs -> Add sharp detail "shimmer" to the outer rings.
 */
class MandalaPulseScene : GlScene {

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
            uniform float u_low;
            uniform float u_mid;
            uniform float u_high;
            out vec4 fragColor;

            vec3 palette(float t) {
                return 0.5 + 0.5 * cos(6.28318 * (t + vec3(0.0, 0.33, 0.67)));
            }

            void main() {
                vec2 uv = (gl_FragCoord.xy - 0.5 * u_resolution) / min(u_resolution.x, u_resolution.y);
                
                // Audio-reactive scaling
                uv *= 1.5 - u_low * 0.5;
                
                float r = length(uv);
                float a = atan(uv.y, uv.x);

                // Symmetric folding (mandala)
                float sides = 8.0;
                float tau = 6.28318;
                float ma = mod(a + u_time * 0.2 + u_mid, tau / sides) - tau / (sides * 2.0);
                vec2 p = vec2(cos(ma), sin(ma)) * r;

                // Pattern layers
                float pattern = 0.0;
                pattern += abs(sin(p.x * 10.0 + u_time)) * smoothstep(0.5, 0.45, r);
                pattern += abs(sin(p.y * 20.0 - u_time * 2.0)) * smoothstep(0.3, 0.25, r) * u_high;
                
                // Central core
                float core = smoothstep(0.1 + u_low * 0.1, 0.0, r);
                
                // Ring spikes
                float spikes = step(0.8, fract(r * 10.0 + u_time)) * u_high;

                vec3 col = palette(r * 0.5 + u_mid + u_time * 0.1) * pattern;
                col += vec3(1.0, 0.8, 0.5) * core * (1.0 + u_low * 2.0);
                col += palette(u_mid * 2.0) * spikes * smoothstep(0.6, 0.4, r);

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
    private var uLow = 0
    private var uMid = 0
    private var uHigh = 0

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
    }

    override fun onResize(width: Int, height: Int, aspect: Float) {
        this.width = width.toFloat()
        this.height = height.toFloat()
    }

    override fun draw(pcm: FloatArray, bands: FloatArray, timeSec: Float, dim: Float) {
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glUseProgram(program)
        GLES20.glUniform2f(uResolution, width, height)
        GLES20.glUniform1f(uTime, timeSec)
        GLES20.glUniform1f(uDim, dim)
        GLES20.glUniform1f(uLow, bands[0])
        GLES20.glUniform1f(uMid, bands[1])
        GLES20.glUniform1f(uHigh, bands[2])

        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, quad)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(aPos)
    }
}
