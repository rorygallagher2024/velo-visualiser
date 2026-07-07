package com.lowlatency.visualizer.gl

import android.opengl.GLES20
import android.opengl.GLES30
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.abs

/**
 * "Lissajous Scope" — a true stereo XY hardware oscilloscope.
 *
 * Perfect for viewing Jerobeam Fenderson's oscilloscope music. The Left audio
 * channel drives the X axis, and the Right audio channel drives the Y axis.
 * It traces a single continuous glowing line using GL_LINE_STRIP.
 *
 * To prevent OLED burn-in, it applies a subtle slow orbital drift just like
 * the RawScopeScene and OscilloscopeScene.
 */
class LissajousScopeScene : GlScene {

    override val respondsToBeat get() = false

    companion object {
        // Drawing too many points (e.g. 4096) draws 85ms of history simultaneously. 
        // If the shape rotates or morphs, drawing 85ms means you see multiple 
        // "ghost" frames overlapping, creating fuzzy multiple lines. 
        // 1200 points is exactly 25ms of audio at 48kHz, which is the perfect
        // sweet spot to close the shape loops without overlapping past shapes!
        private const val POINTS = 1200

        private const val SCOPE_VS = """#version 300 es
            layout(location = 0) in vec2 aPos;   // XY from Left/Right audio
            uniform float u_time;
            uniform float u_aspect;              // width / height
            uniform float u_count;
            out float v_t;                       // 0..1 along the beam
            
            void main() {
                // OLED burn-in protection orbit (pixels to normalized device coordinates)
                // Roughly +/- 3 pixels of drift.
                vec2 orbit = vec2(
                    sin(u_time * 0.31) + 0.5 * sin(u_time * 0.13 + 1.7),
                    cos(u_time * 0.27) + 0.5 * cos(u_time * 0.19 + 0.9)
                ) * 0.005;

                // Apply aspect ratio correction so perfect circles remain circles.
                // aPos is [-1, 1]. Divide X by aspect ratio to keep it square.
                vec2 p = aPos;
                p.x /= u_aspect;
                
                // Scale slightly to leave a margin
                p *= 0.9;

                gl_Position = vec4(p + orbit, 0.0, 1.0);
                v_t = float(gl_VertexID) / u_count;
            }
        """

        private const val SCOPE_FS = """#version 300 es
            precision highp float;
            uniform float u_dim;
            in float v_t;
            out vec4 fragColor;

            void main() {
                // Classic oscilloscope phosphor green tint
                vec3 phosphorTint = vec3(0.45, 1.0, 0.65);
                
                // Emulate CRT phosphor decay. The newest points (v_t = 1.0) are bright,
                // and the oldest points fade out. This prevents "multiple lines" from
                // time-smearing when the shape rapidly morphs.
                float age = 1.0 - v_t;
                float intensity = exp(-age * 3.0);

                // Add HDR lift for the bloom post-processor
                vec3 col = phosphorTint * intensity * 3.0;
                
                fragColor = vec4(col * u_dim, 1.0);
            }
        """
    }

    private val verts = FloatArray(POINTS * 2)
    private val vbuf: FloatBuffer = ByteBuffer
        .allocateDirect(POINTS * 2 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()

    private var program = 0
    private var aPos = 0
    private var uTime = 0
    private var uAspect = 0
    private var uCount = 0
    private var uDim = 0
    private var vbo = 0
    private var aspect = 1f

    override fun onCreated() {
        program = ShaderUtil.buildProgram(SCOPE_VS, SCOPE_FS)
        aPos = GLES20.glGetAttribLocation(program, "aPos")
        uTime = GLES20.glGetUniformLocation(program, "u_time")
        uAspect = GLES20.glGetUniformLocation(program, "u_aspect")
        uCount = GLES20.glGetUniformLocation(program, "u_count")
        uDim = GLES20.glGetUniformLocation(program, "u_dim")

        val ids = IntArray(1)
        GLES20.glGenBuffers(1, ids, 0)
        vbo = ids[0]
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo)
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, POINTS * 2 * 4, null, GLES20.GL_DYNAMIC_DRAW)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
    }

    override fun onResize(width: Int, height: Int, aspect: Float) {
        this.aspect = if (height > 0) width.toFloat() / height else 1f
    }

    // Unused for stereo scene, but required by interface
    override fun draw(pcm: FloatArray, bands: FloatArray, timeSec: Float, dim: Float) {}

    fun drawStereo(pcmStereo: FloatArray, bands: FloatArray, timeSec: Float, dim: Float) {
        val totalPairs = pcmStereo.size / 2
        var startIndex = 0

        // Hardware Oscilloscope Positive-Edge Trigger:
        // We search the first portion of the buffer for a moment where the Left (X) channel 
        // crosses from negative to positive. By always starting our drawing from the same 
        // phase of the wave, we phase-lock the visual to the audio frequency. 
        // This completely eliminates the "fuzziness" and "multiple lines" caused by the 
        // display frame-rate drifting out of sync with the audio!
        val searchLimit = maxOf(0, totalPairs - POINTS)
        for (i in 0 until searchLimit - 1) {
            val left1 = pcmStereo[i * 2]
            val left2 = pcmStereo[(i + 1) * 2]
            // We require a minimum amplitude threshold (0.01f) to prevent triggering on silent noise
            if (left1 <= 0f && left2 > 0f && pcmStereo[(i + 2) * 2] > 0.01f) {
                startIndex = i
                break
            }
        }

        val limit = minOf(POINTS, totalPairs - startIndex)
        if (limit <= 1) return

        var vi = 0
        for (i in 0 until limit) {
            verts[vi++] = pcmStereo[(startIndex + i) * 2]               // Left -> X
            verts[vi++] = pcmStereo[(startIndex + i) * 2 + 1]           // Right -> Y
        }
        vbuf.clear(); vbuf.put(verts, 0, limit * 2); vbuf.position(0)

        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE)

        GLES20.glUseProgram(program)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo)
        GLES20.glBufferSubData(GLES20.GL_ARRAY_BUFFER, 0, limit * 2 * 4, vbuf)
        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, 0)

        GLES20.glUniform1f(uTime, timeSec)
        GLES20.glUniform1f(uAspect, aspect)
        GLES20.glUniform1f(uCount, (limit - 1).toFloat())
        GLES20.glUniform1f(uDim, dim)

        GLES20.glLineWidth(2f)
        GLES30.glDrawArrays(GLES20.GL_LINE_STRIP, 0, limit)

        GLES20.glDisableVertexAttribArray(aPos)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
    }

    override fun onDeactivate() {
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
    }
}
