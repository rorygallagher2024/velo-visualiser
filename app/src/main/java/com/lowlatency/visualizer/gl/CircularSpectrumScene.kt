package com.lowlatency.visualizer.gl

import android.opengl.GLES20
import android.opengl.GLES30
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Visual 5 — "Circular Spectrum Analyzer".
 *
 * A precise circle in screen centre extruded outward into 128 segmented bars.
 * Bar length maps to the log-scaled (dB-normalized) FFT magnitude per bin so
 * lows don't overpower the visual, and each bar carries a gravity "peak-hold"
 * dot that falls slower than the bar itself.
 *
 * Drawn in a single full-screen fragment pass: the spectrum + peaks are uploaded
 * as a 128x2 float texture (row 0 = magnitude, row 1 = peak) and sampled by the
 * fragment's polar angle.
 */
class CircularSpectrumScene : GlScene {

    // Instrument readout — honest representation of the signal, no beat punch.
    override val respondsToBeat get() = false

    companion object {
        private const val BINS = 128

        private const val VERTEX_SHADER = """#version 300 es
            layout(location = 0) in vec2 aPos;
            void main() { gl_Position = vec4(aPos, 0.0, 1.0); }
        """

        private const val FRAGMENT_SHADER = """#version 300 es
            precision highp float;
            uniform vec2  u_resolution;
            uniform float u_dim;
            uniform sampler2D u_spectrum;     // BINS x 2 : row0 mag, row1 peak
            out vec4 fragColor;

            const float BINS_F = float(${BINS});
            // INNER + MAXLEN (+ peak dot) must stay < 0.5 so the ring never
            // touches the shorter screen edge — see normalization below.
            const float INNER = 0.14;
            const float MAXLEN = 0.30;

            vec3 palette(float t) {
                return 0.5 + 0.5 * cos(6.28318 * (t + vec3(0.0, 0.33, 0.67)));
            }

            void main() {
                // Normalize by the SHORTER axis: the ring stays perfectly round
                // AND fits on every screen — portrait, landscape, or unfolded
                // tablet — without ever clipping off the top/bottom.
                vec2 uv = (gl_FragCoord.xy - 0.5 * u_resolution)
                          / min(u_resolution.x, u_resolution.y);

                float r = length(uv);
                float a = atan(uv.y, uv.x) / 6.28318 + 0.5;   // 0..1 around circle
                float fbin = a * BINS_F;
                float seg = fract(fbin);                       // 0..1 within a bar
                float u = (floor(fbin) + 0.5) / BINS_F;

                float mag  = texture(u_spectrum, vec2(u, 0.25)).r;
                float peak = texture(u_spectrum, vec2(u, 0.75)).r;

                // Angular gap between adjacent bars.
                float barMask = step(0.12, seg) * step(seg, 0.88);

                vec3 col = vec3(0.0);

                // Bar fill, brighter toward the tip (HDR).
                float barTop = INNER + mag * MAXLEN;
                if (r > INNER && r < barTop) {
                    float t = (r - INNER) / MAXLEN;
                    col = palette(t * 0.5 + 0.05) * barMask * (1.0 + t * 1.5);
                }

                // Gravity peak-hold dot.
                float peakR = INNER + peak * MAXLEN;
                if (abs(r - peakR) < 0.008) {
                    col += vec3(1.0) * barMask * 2.0;          // bright white (HDR)
                }

                // Thin inner ring outline.
                col += vec3(0.2, 0.5, 0.8) * smoothstep(0.004, 0.0, abs(r - INNER));

                fragColor = vec4(col * u_dim, 1.0);
            }
        """
    }

    // 128x2 upload buffer: indices [0,BINS) = magnitudes, [BINS,2*BINS) = peaks.
    private val upload: FloatBuffer = ByteBuffer
        .allocateDirect(BINS * 2 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()

    private val quad: FloatBuffer = ByteBuffer
        .allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        .apply { put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)); position(0) }

    private val vbo = IntArray(1)
    private var program = 0
    private var aPos = 0
    private var uResolution = 0
    private var uDim = 0
    private var uSpectrum = 0

    private var specTex = 0
    private var width = 1f
    private var height = 1f

    override fun onCreated() {
        program = ShaderUtil.buildProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        aPos = GLES20.glGetAttribLocation(program, "aPos")
        uResolution = GLES20.glGetUniformLocation(program, "u_resolution")
        uDim = GLES20.glGetUniformLocation(program, "u_dim")
        uSpectrum = GLES20.glGetUniformLocation(program, "u_spectrum")

        GLES20.glGenBuffers(1, vbo, 0)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo[0])
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, quad.capacity() * 4, quad, GLES20.GL_STATIC_DRAW)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)

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
            BINS, 2, 0, GLES30.GL_RED, GLES20.GL_FLOAT, null
        )
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
    }

    override fun onResize(width: Int, height: Int, aspect: Float) {
        this.width = width.toFloat()
        this.height = height.toFloat()
    }

    override fun draw(pcm: FloatArray, bands: FloatArray, timeSec: Float, dim: Float) {
        upload.clear()
        upload.put(SpectrumData.magnitudes)  // row 0
        upload.put(SpectrumData.peaks)       // row 1
        upload.position(0)

        GLES20.glDisable(GLES20.GL_BLEND)    // opaque full-screen pass
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, specTex)
        GLES20.glTexSubImage2D(
            GLES20.GL_TEXTURE_2D, 0, 0, 0, BINS, 2,
            GLES30.GL_RED, GLES20.GL_FLOAT, upload
        )
        GLES20.glUniform1i(uSpectrum, 0)
        GLES20.glUniform2f(uResolution, width, height)
        GLES20.glUniform1f(uDim, dim)

        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo[0])
        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(aPos)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
    }
}
