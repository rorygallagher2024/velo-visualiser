package com.lowlatency.visualizer.gl

import android.opengl.GLES20
import android.opengl.GLES30
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Teenage-Engineering-inspired visual — "Pocket LED".
 *
 * A chunky dot-matrix LED spectrum analyzer, à la the Pocket Operator / OP-1
 * displays: a fixed grid of square cells, bottom-anchored bars built from
 * stacked lit cells, a single bright "peak-hold" cell hovering above each
 * column, and the unlit cells left faintly glowing so the whole matrix reads
 * as a physical LED panel. Monochrome warm amber so it blooms cleanly on HDR.
 *
 * Like the other spectrum scenes it draws a single full-screen fragment pass in
 * normalized [0,1] space (fills any aspect) and rides the spectrum in a
 * COLS x 2 float texture (row0 = magnitude, row1 = gravity peak).
 */
class LedMatrixScene : GlScene {

    companion object {
        private const val COLS = 24
        private const val ROWS = 14

        private const val VERTEX_SHADER = """#version 300 es
            layout(location = 0) in vec2 aPos;
            void main() { gl_Position = vec4(aPos, 0.0, 1.0); }
        """

        private const val FRAGMENT_SHADER = """#version 300 es
            precision highp float;
            uniform vec2  u_resolution;
            uniform float u_dim;
            uniform sampler2D u_spectrum;     // COLS x 2 : row0 mag, row1 peak
            out vec4 fragColor;

            const float COLS_F = float(${COLS});
            const float ROWS_F = float(${ROWS});
            const float MARGIN = 0.16;        // gap between LED cells

            void main() {
                vec2 uv = gl_FragCoord.xy / u_resolution;   // y=0 at bottom

                float fx = uv.x * COLS_F;
                float fy = uv.y * ROWS_F;
                float cix = floor(fx);
                float ciy = floor(fy);
                vec2 cell = vec2(fract(fx), fract(fy));

                // Square cell shape with a gap (the dark grid between LEDs).
                float inCell = step(MARGIN, cell.x) * step(cell.x, 1.0 - MARGIN) *
                               step(MARGIN, cell.y) * step(cell.y, 1.0 - MARGIN);

                float u    = (cix + 0.5) / COLS_F;
                float mag  = texture(u_spectrum, vec2(u, 0.25)).r;
                float peak = texture(u_spectrum, vec2(u, 0.75)).r;

                float litRows = mag * ROWS_F;
                float isLit   = step(ciy + 0.001, litRows);     // cell within the bar
                float peakRow = floor(peak * ROWS_F - 0.001);
                float isPeak  = step(abs(ciy - peakRow), 0.001) * step(0.02, peak);

                vec3 amber = vec3(1.0, 0.46, 0.12);

                // Unlit cells glow faintly; lit cells brighten toward the bar tip
                // (HDR > 1 at the top so the crest blooms).
                vec3 col = amber * 0.045;
                vec3 lit = amber * (0.85 + (ciy / ROWS_F) * 1.7);
                col = mix(col, lit, isLit);

                // Peak-hold cell: a hot near-white cap that punches through bloom.
                col = mix(col, vec3(1.0, 0.78, 0.45) * 2.3, isPeak);

                fragColor = vec4(col * inCell * u_dim, 1.0);
            }
        """
    }

    private val upload: FloatBuffer = ByteBuffer
        .allocateDirect(COLS * 2 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()

    private val quad: FloatBuffer = ByteBuffer
        .allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        .apply { put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)); position(0) }

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
            COLS, 2, 0, GLES30.GL_RED, GLES20.GL_FLOAT, null
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
        for (i in 0 until COLS) {
            val lo = i * 128 / COLS
            val hi = (i + 1) * 128 / COLS
            val n = (hi - lo).coerceAtLeast(1)
            var sum = 0f
            for (j in lo until lo + n) sum += magnitudes[j.coerceAtMost(127)]
            upload.put(sum / n)
        }
        for (i in 0 until COLS) {
            val lo = i * 128 / COLS
            val hi = (i + 1) * 128 / COLS
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
            GLES20.GL_TEXTURE_2D, 0, 0, 0, COLS, 2,
            GLES30.GL_RED, GLES20.GL_FLOAT, upload
        )
        GLES20.glUniform1i(uSpectrum, 0)
        GLES20.glUniform2f(uResolution, width, height)
        GLES20.glUniform1f(uDim, dim)

        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, quad)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(aPos)
    }
}
