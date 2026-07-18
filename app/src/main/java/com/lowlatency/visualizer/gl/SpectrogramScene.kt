package com.lowlatency.visualizer.gl

import android.opengl.GLES20
import android.opengl.GLES30
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Visual 11 — "Spectrogram".
 *
 * A scrolling time-frequency heatmap: vertical axis = frequency (log-binned),
 * horizontal axis = time, color = magnitude (fire palette). The one visual with
 * *memory* — you watch a beat's structure scroll across the screen.
 *
 * Implemented cheaply with a ring-buffer texture: each frame writes ONE new
 * column (the latest spectrum) at a moving write index via glTexSubImage2D, and
 * the fragment shader samples with a rolling offset so it appears to scroll. No
 * per-frame whole-texture copy.
 */
class SpectrogramScene : GlScene {

    // Instrument readout — honest representation of the signal, no beat punch.
    override val respondsToBeat get() = false

    companion object {
        private const val BINS = 128          // mirrored as BIN_ROWS in the fragment shader
        private const val COLS = 640          // time history; one column per frame (~5 s at 120 Hz)

        private const val VERTEX_SHADER = """#version 300 es
            out vec2 v_uv;
            void main() {
                vec2 p = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));
                v_uv = p;
                gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
            }
        """

        private const val FRAGMENT_SHADER = """#version 300 es
            precision highp float;
            uniform sampler2D u_tex;
            uniform float u_write;     // index of the next column to be written
            uniform float u_cols;
            uniform float u_dim;
            in vec2 v_uv;
            out vec4 fragColor;

            const float BIN_ROWS = 128.0;   // must match BINS on the Kotlin side

            vec3 fire(float m) {
                // black -> deep red -> orange -> yellow -> white (HDR top for bloom)
                return clamp(vec3(m * 1.6, m * 1.6 - 0.5, m * 2.3 - 1.6), 0.0, 2.2);
            }

            void main() {
                // Newest column on the right (uv.x = 1), scrolling left over time.
                float col = (u_write - 1.0) - (1.0 - v_uv.x) * (u_cols - 1.0);
                // Snap time to column centres: the sampler is LINEAR (for the
                // frequency axis) but must never blend across the ring seam,
                // where the newest column sits beside the oldest. Discrete
                // columns also keep the time axis an honest instrument read.
                float texX = (floor(mod(col, u_cols)) + 0.5) / u_cols;
                // Frequency axis: interpolate between bins, with a smoothstep
                // on the fraction so gradients are kink-free, not tent-shaped.
                float fy = v_uv.y * BIN_ROWS - 0.5;
                float row = floor(fy);
                float fr = fy - row;
                fr = fr * fr * (3.0 - 2.0 * fr);
                float m = texture(u_tex, vec2(texX, (row + 0.5 + fr) / BIN_ROWS)).r;
                vec3 c = fire(m) * (0.4 + m * 1.4);
                fragColor = vec4(c * u_dim, 1.0);
            }
        """
    }

    private val column: FloatBuffer = ByteBuffer
        .allocateDirect(BINS * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()

    private var program = 0
    private var uTex = 0
    private var uWrite = 0
    private var uCols = 0
    private var uDim = 0
    private var tex = 0
    private var writeCol = 0

    override fun onCreated() {
        program = ShaderUtil.buildProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        uTex = GLES20.glGetUniformLocation(program, "u_tex")
        uWrite = GLES20.glGetUniformLocation(program, "u_write")
        uCols = GLES20.glGetUniformLocation(program, "u_cols")
        uDim = GLES20.glGetUniformLocation(program, "u_dim")

        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        tex = ids[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_REPEAT)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        // Initialise to zero (silence) so the history starts black. R16F, not
        // R32F: half float is texture-filterable in core ES 3.0 (32-bit float
        // needs OES_texture_float_linear), and magnitudes are tone-mapped 0..1.
        val zero = ByteBuffer.allocateDirect(COLS * BINS * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer()
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, GLES30.GL_R16F,
            COLS, BINS, 0, GLES30.GL_RED, GLES20.GL_FLOAT, zero
        )
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
    }

    override fun onResize(width: Int, height: Int, aspect: Float) {}

    override fun draw(pcm: FloatArray, bands: FloatArray, timeSec: Float, dim: Float) {
        column.clear(); column.put(SpectrumData.magnitudes).position(0)

        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex)
        // Write the newest spectrum as a 1-wide column at writeCol.
        GLES20.glTexSubImage2D(
            GLES20.GL_TEXTURE_2D, 0, writeCol, 0, 1, BINS,
            GLES30.GL_RED, GLES20.GL_FLOAT, column
        )
        writeCol = (writeCol + 1) % COLS

        GLES20.glUniform1i(uTex, 0)
        GLES20.glUniform1f(uWrite, writeCol.toFloat())
        GLES20.glUniform1f(uCols, COLS.toFloat())
        GLES20.glUniform1f(uDim, dim)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 3)
    }
}
