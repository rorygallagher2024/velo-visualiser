package com.lowlatency.visualizer.gl

import android.opengl.GLES20
import android.opengl.GLES30
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Visual 7 — "Spectrum Bars".
 *
 * A clean, classic bottom-anchored bar spectrum: 48 log-spaced FFT bins drawn
 * as vertical bars with a gravity peak-cap on each. Deliberately simple and
 * legible — the reference visual.
 *
 * Everything is laid out in normalized [0,1] screen space (uv = fragCoord /
 * resolution), so it fills the screen identically at every aspect ratio —
 * portrait, landscape, or unfolded tablet — with no aspect-correction to get
 * wrong. Drawn as a single full-screen fragment pass; the spectrum + peaks ride
 * in a 64x2 float texture sampled by the fragment's x position.
 */
class BarSpectrumScene : GlScene {

    companion object {
        private const val BINS = 48

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
            const float FLOOR_Y = 0.06;       // bottom margin
            const float CEIL_Y  = 0.94;       // top margin (bars never clip)

            vec3 palette(float t) {
                return 0.5 + 0.5 * cos(6.28318 * (t + vec3(0.0, 0.33, 0.67)));
            }

            void main() {
                // Pure normalized screen space — fills any resolution/aspect.
                vec2 uv = gl_FragCoord.xy / u_resolution;

                float fbin = uv.x * BINS_F;
                float seg  = fract(fbin);                  // 0..1 within a bar cell
                float u    = (floor(fbin) + 0.5) / BINS_F;

                float mag  = texture(u_spectrum, vec2(u, 0.25)).r;
                float peak = texture(u_spectrum, vec2(u, 0.75)).r;

                float span   = CEIL_Y - FLOOR_Y;
                float barTop = FLOOR_Y + mag  * span;
                float peakY  = FLOOR_Y + peak * span;

                // Gaps between bars.
                float barMask = step(0.08, seg) * step(seg, 0.92);

                vec3 col = vec3(0.0);

                // Bar fill: hue + brightness ramp up the bar (HDR toward the tip).
                if (uv.y > FLOOR_Y && uv.y < barTop) {
                    float h = (uv.y - FLOOR_Y) / span;
                    col = palette(0.62 - h * 0.45) * barMask * (0.5 + h * 1.5);
                    col *= 1.0 + h * 1.2;
                }

                // Gravity peak-cap.
                if (abs(uv.y - peakY) < 0.006 && peak > 0.01) {
                    col += vec3(1.0) * barMask * 1.8;      // bright cap (HDR)
                }

                // Baseline glow line.
                col += vec3(0.1, 0.35, 0.55) * smoothstep(0.004, 0.0, abs(uv.y - FLOOR_Y));

                fragColor = vec4(col * u_dim, 1.0);
            }
        """
    }

    // 64x2 upload buffer: indices [0,BINS) = magnitudes, [BINS,2*BINS) = peaks.
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
        val magnitudes = SpectrumData.magnitudes
        val peaks = SpectrumData.peaks
        upload.clear()
        for (i in 0 until BINS) {
            val lo = i * 128 / BINS
            val hi = (i + 1) * 128 / BINS
            val n = (hi - lo).coerceAtLeast(1)
            var sum = 0f
            for (j in lo until lo + n) sum += magnitudes[j.coerceAtMost(127)]
            upload.put(sum / n)
        }
        for (i in 0 until BINS) {
            val lo = i * 128 / BINS
            val hi = (i + 1) * 128 / BINS
            val n = (hi - lo).coerceAtLeast(1)
            var sum = 0f
            for (j in lo until lo + n) sum += peaks[j.coerceAtMost(127)]
            upload.put(sum / n)
        }
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
