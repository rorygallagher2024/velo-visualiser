package com.lowlatency.visualizer.gl

import android.opengl.GLES20
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Visual 14 — "Electric Iris".
 *
 * A reactive iris pattern with "electric" arcs.
 *   - Lows -> Pulse the central pupil and iris size.
 *   - Mids -> Rotate and shift colors of the iris segments.
 *   - Highs -> Drive "electric" spikes and flashes in the arcs.
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
            uniform sampler2D u_spectrum;     // BINS x 1
            out vec4 fragColor;

            const float BINS_F = float(${BINS});

            vec3 palette(float t) {
                return 0.5 + 0.5 * cos(6.28318 * (t + vec3(0.0, 0.33, 0.67)));
            }

            float hash(float n) { return fract(sin(n) * 43758.5453123); }

            void main() {
                vec2 uv = (gl_FragCoord.xy - 0.5 * u_resolution) / min(u_resolution.x, u_resolution.y);
                float r = length(uv);
                float a = atan(uv.y, uv.x) / 6.28318 + 0.5;

                // Pulsing central pupil
                float pupilSize = 0.08 + u_low * 0.05;
                float pupil = smoothstep(pupilSize, pupilSize - 0.01, r);

                // Iris segments
                float irisR = 0.15 + u_low * 0.1;
                float irisOuter = irisR + 0.15;
                float segs = 12.0;
                float aRot = a + u_time * 0.1 + u_mid * 0.5;
                float seg = fract(aRot * segs);
                float segMask = step(0.1, seg) * step(seg, 0.9);
                
                vec3 irisCol = vec3(0.0);
                if (r > irisR && r < irisOuter) {
                    float t = (r - irisR) / (irisOuter - irisR);
                    irisCol = palette(t * 0.2 + u_mid) * segMask * (1.0 + t * 2.0);
                }

                // Electric arcs
                float arcR = irisOuter + 0.05;
                float spec = texture(u_spectrum, vec2(a, 0.5)).r;
                float arcMask = 0.0;
                
                // Frequency-driven spikes
                float spike = spec * 0.4 * (1.0 + u_high * 2.0);
                if (r > arcR && r < arcR + spike) {
                    arcMask = smoothstep(arcR + spike, arcR, r);
                }

                // Electric "bolts"
                float bolts = 0.0;
                for(float i=0.0; i<3.0; i++) {
                    float boltA = hash(floor(u_time * 10.0 + i)) * 1.0;
                    float boltDist = abs(a - boltA);
                    if (boltDist < 0.02) {
                        bolts += (1.0 - boltDist / 0.02) * u_high * 2.0;
                    }
                }

                vec3 col = irisCol + pupil * vec3(0.1, 0.1, 0.2);
                col += palette(spec * 0.5 + 0.5) * arcMask * 2.0;
                col += vec3(0.8, 0.9, 1.0) * bolts * smoothstep(arcR + 0.2, arcR, r);

                fragColor = vec4(col * u_dim, 1.0);
            }
        """
    }

    private val analyzer = SpectrumAnalyzer(bins = BINS)
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
    private var uSpectrum = 0

    private var specTex = 0
    private var width = 1f
    private var height = 1f
    private var lastTime = -1f

    override fun onCreated() {
        program = ShaderUtil.buildProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        aPos = GLES20.glGetAttribLocation(program, "aPos")
        uResolution = GLES20.glGetUniformLocation(program, "u_resolution")
        uTime = GLES20.glGetUniformLocation(program, "u_time")
        uDim = GLES20.glGetUniformLocation(program, "u_dim")
        uLow = GLES20.glGetUniformLocation(program, "u_low")
        uMid = GLES20.glGetUniformLocation(program, "u_mid")
        uHigh = GLES20.glGetUniformLocation(program, "u_high")
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
            GLES20.GL_TEXTURE_2D, 0, GLES20.GL_LUMINANCE,
            BINS, 1, 0, GLES20.GL_LUMINANCE, GLES20.GL_FLOAT, null
        )
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
    }

    override fun onResize(width: Int, height: Int, aspect: Float) {
        this.width = width.toFloat()
        this.height = height.toFloat()
    }

    override fun draw(pcm: FloatArray, bands: FloatArray, timeSec: Float, dim: Float) {
        val dt = if (lastTime < 0f) 0.016f else (timeSec - lastTime).coerceIn(0f, 0.05f)
        lastTime = timeSec

        analyzer.update(pcm, dt)
        upload.clear()
        upload.put(analyzer.magnitudes)
        upload.position(0)

        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, specTex)
        GLES20.glTexSubImage2D(
            GLES20.GL_TEXTURE_2D, 0, 0, 0, BINS, 1,
            GLES20.GL_LUMINANCE, GLES20.GL_FLOAT, upload
        )
        GLES20.glUniform1i(uSpectrum, 0)
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
