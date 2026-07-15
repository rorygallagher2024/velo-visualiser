package com.lowlatency.visualizer.gl

import android.opengl.GLES20
import android.opengl.GLES30
import com.lowlatency.visualizer.NativeBridge
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
class LissajousScopeScene : StereoScene {

    override val respondsToBeat get() = false

    companion object {
        // The absolute maximum number of points we can draw from the 8192-sample buffer.
        private const val MAX_POINTS = 8192          // full 42 ms trace at 192 kHz
        // 40 ms trace: 2 ms shy of the ring's 192 kHz capacity, so the oldest
        // samples we read are never the ones being overwritten mid-copy.
        private const val TRACE_SEC = 0.040f
        private const val TRIGGER_SEARCH_SEC = 0.021f // edge hunt over ~one 48 Hz cycle

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

                // Apply aspect ratio correction so perfect circles remain circles,
                // while ensuring it always fits within the screen regardless of portrait/landscape!
                vec2 p = aPos;
                if (u_aspect > 1.0) {
                    p.x /= u_aspect;
                } else {
                    p.y *= u_aspect;
                }
                
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
                
                // Emulate CRT phosphor decay. Because we are drawing a continuous 
                // fixed-length trace again, we need the tail of the trace to fade out 
                // naturally to prevent screen clutter, just like a real analog scope!
                float age = 1.0 - v_t;
                float intensity = exp(-age * 3.5);

                // Provide enough brightness for visibility but prevent HDR bloom from smearing
                vec3 col = phosphorTint * intensity * 1.5;
                
                fragColor = vec4(col * u_dim, 1.0);
            }
        """
    }

    private val verts = FloatArray(MAX_POINTS * 2)
    private val vbuf: FloatBuffer = ByteBuffer
        .allocateDirect(MAX_POINTS * 2 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()

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
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, MAX_POINTS * 2 * 4, null, GLES20.GL_DYNAMIC_DRAW)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
    }

    override fun onResize(width: Int, height: Int, aspect: Float) {
        this.aspect = if (height > 0) width.toFloat() / height else 1f
    }

    // Unused for stereo scene, but required by interface
    override fun draw(pcm: FloatArray, bands: FloatArray, timeSec: Float, dim: Float) = Unit

    override fun drawStereo(pcmStereo: FloatArray, bands: FloatArray, timeSec: Float, dim: Float) {
        val totalPairs = pcmStereo.size / 2
        // The trace is a TIME window, not a point count: oscilloscope-music
        // masters run at 96/192 kHz, and a fixed point budget would quarter the
        // drawn geometry exactly on the material this scene exists for. Every
        // sample in the window is drawn — never decimate XY art.
        val sampleRate = NativeBridge.nativeGetSampleRate().coerceAtLeast(8000)
        val drawPoints = (sampleRate * TRACE_SEC).toInt().coerceAtMost(MAX_POINTS)

        // 1. Hardware Oscilloscope Positive-Edge Trigger (Phase Lock).
        // Anchored to the NEWEST samples: the buffer holds far more history
        // than the trace at low rates, and drawing from its oldest end would
        // lag the audio by over 100 ms. The trigger hunts just ahead of the
        // trace so the beam always ends at "now" (within ~one 48 Hz cycle).
        val searchSpan = minOf(
            (sampleRate * TRIGGER_SEARCH_SEC).toInt(),
            totalPairs - drawPoints - 2,
        ).coerceAtLeast(0)
        val base = (totalPairs - drawPoints - searchSpan).coerceAtLeast(0)
        var startIndex = base
        for (i in base until base + searchSpan) {
            val left1 = pcmStereo[i * 2]
            val left2 = pcmStereo[(i + 1) * 2]
            if (left1 <= 0f && left2 > 0f && pcmStereo[(i + 2) * 2] > 0.01f) {
                startIndex = i
                break
            }
        }

        // 2. Fixed Continuous Trace (~40 ms of signal at any sample rate).
        // Long enough to draw the multiplexed shapes and complex geometry that
        // a short 1-cycle trace would cut off; phase-stable because the source
        // is lossless.
        val limit = minOf(drawPoints, totalPairs - startIndex).coerceAtMost(MAX_POINTS)
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

        GLES20.glLineWidth(1f) // 1px line prevents bloom from smearing dense lines together
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
