package com.lowlatency.visualizer.gl

import android.opengl.GLES20
import android.opengl.GLES30
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Visual 10 — "Raw Oscilloscope".
 *
 * The opposite of the phosphor scope: a single thin 1px polyline drawn straight
 * from the PCM window with a flat linear gain. No glow, no bloom, no soft-clip,
 * no burn-in orbit, no vignette — every sample is plotted as-is so the trace
 * shows all the fine detail of the waveform. Just the signal.
 */
class RawScopeScene : GlScene {

    companion object {
        private const val POINTS = 1024
        private const val GAIN = 2.5f      // flat linear scale — no compression/clipping

        private const val VERTEX_SHADER = """#version 300 es
            layout(location = 0) in float aSample;
            uniform float u_count;
            uniform float u_gain;
            void main() {
                float x = float(gl_VertexID) / (u_count - 1.0) * 2.0 - 1.0;
                gl_Position = vec4(x, aSample * u_gain, 0.0, 1.0);
            }
        """

        // Drive the trace into HDR range: the scope bypasses bloom and draws
        // straight to the FP16 HDR surface, so a value > 1.0 pushes the display
        // to peak nits. Multiplying the whole colour keeps the green hue intact.
        private const val HDR_BOOST = 10.0f

        private const val FRAGMENT_SHADER = """#version 300 es
            precision highp float;
            uniform float u_dim;
            out vec4 fragColor;
            void main() {
                // Flat, on-brand green at max HDR brightness. No glow — the line is the line.
                fragColor = vec4(vec3(0.149, 1.0, 0.549) * ${HDR_BOOST} * u_dim, 1.0);
            }
        """
    }

    private val buffer: FloatBuffer = ByteBuffer
        .allocateDirect(POINTS * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()

    private var program = 0
    private var aSample = 0
    private var uCount = 0
    private var uGain = 0
    private var uDim = 0
    private var vbo = 0

    override fun onCreated() {
        program = ShaderUtil.buildProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        aSample = GLES20.glGetAttribLocation(program, "aSample")
        uCount = GLES20.glGetUniformLocation(program, "u_count")
        uGain = GLES20.glGetUniformLocation(program, "u_gain")
        uDim = GLES20.glGetUniformLocation(program, "u_dim")

        val ids = IntArray(1)
        GLES20.glGenBuffers(1, ids, 0)
        vbo = ids[0]
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo)
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, POINTS * 4, null, GLES20.GL_DYNAMIC_DRAW)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
    }

    // Stay pixel-pure: never route the raw trace through the bloom pipeline.
    override val bypassPostProcessing: Boolean get() = true

    override fun onResize(width: Int, height: Int, aspect: Float) {
        // A 1D trace needs no aspect correction: x spans the full width, y is amplitude.
    }

    override fun draw(pcm: FloatArray, bands: FloatArray, magnitudes: FloatArray, peaks: FloatArray, timeSec: Float, dim: Float, sharedBuffer: java.nio.ByteBuffer?) {
        val n = minOf(pcm.size, POINTS)
        buffer.clear(); buffer.put(pcm, 0, n); buffer.position(0)

        GLES20.glUseProgram(program)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo)
        GLES20.glBufferSubData(GLES20.GL_ARRAY_BUFFER, 0, n * 4, buffer)
        GLES20.glEnableVertexAttribArray(aSample)
        GLES20.glVertexAttribPointer(aSample, 1, GLES20.GL_FLOAT, false, 0, 0)

        GLES20.glUniform1f(uCount, n.toFloat())
        GLES20.glUniform1f(uGain, GAIN)
        GLES20.glUniform1f(uDim, dim)

        GLES20.glLineWidth(1f)                 // thin — most mobile GPUs clamp here anyway
        GLES30.glDrawArrays(GLES20.GL_LINE_STRIP, 0, n)

        GLES20.glDisableVertexAttribArray(aSample)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
    }
}
