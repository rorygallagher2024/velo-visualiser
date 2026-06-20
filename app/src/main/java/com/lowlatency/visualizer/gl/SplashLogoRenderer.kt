package com.lowlatency.visualizer.gl

import android.opengl.GLES20
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.max

/**
 * High-polish OpenGL splash logo renderer.
 * 
 * Features:
 * - HDR Bloom: Characters glow intensely then fade.
 * - Physics: Characters fall away individually at the end of the splash.
 * - TE Aesthetic: Razor sharp technical line-art logo.
 */
class SplashLogoRenderer {

    companion object {
        private const val VS = """#version 300 es
            layout(location = 0) in vec2 aPos;
            layout(location = 1) in float aCharIndex;
            
            uniform vec2 u_res;
            uniform float u_time;
            uniform float u_phase; // 0=holding, 1=falling
            
            out float v_glow;
            out float v_charIdx;

            void main() {
                vec2 p = aPos;
                
                // Falling physics
                if (u_phase > 0.0) {
                    float t = (u_time - u_phase) * 1.5;
                    float delay = aCharIndex * 0.15;
                    float localT = max(0.0, t - delay);
                    p.y -= localT * localT * 4.0; // gravity
                    p.x += sin(localT * 10.0 + aCharIndex) * 0.05 * localT; // wobble
                }

                v_charIdx = aCharIndex;
                gl_Position = vec4(p, 0.0, 1.0);
            }
        """

        private const val FS = """#version 300 es
            precision highp float;
            uniform float u_intensity;
            in float v_charIdx;
            out vec4 fragColor;

            void main() {
                // Signature TE Orange
                vec3 orange = vec3(0.941, 0.325, 0.11);
                // HDR Boost: past 1.0 triggers the post-processor bloom
                fragColor = vec4(orange * u_intensity, 1.0);
            }
        """

        // Simplified line segments for V-E-L-O
        // 4 chars, few lines each
        private val LOGO_VERTS = floatArrayOf(
            // V
            -0.6f, 0.2f, 0f,   -0.45f, -0.2f, 0f,
            -0.45f, -0.2f, 0f, -0.3f, 0.2f, 0f,
            // E
            -0.2f, 0.2f, 1f,   -0.2f, -0.2f, 1f,
            -0.2f, 0.2f, 1f,   0.0f, 0.2f, 1f,
            -0.2f, 0.0f, 1f,   -0.05f, 0.0f, 1f,
            -0.2f, -0.2f, 1f,  0.0f, -0.2f, 1f,
            // L
            0.1f, 0.2f, 2f,    0.1f, -0.2f, 2f,
            0.1f, -0.2f, 2f,   0.3f, -0.2f, 2f,
            // O
            0.4f, 0.2f, 3f,    0.6f, 0.2f, 3f,
            0.6f, 0.2f, 3f,    0.6f, -0.2f, 3f,
            0.6f, -0.2f, 3f,   0.4f, -0.2f, 3f,
            0.4f, -0.2f, 3f,   0.4f, 0.2f, 3f
        )
    }

    private var program = 0
    private var uTime = 0
    private var uPhase = 0
    private var uIntensity = 0
    private var vbo = 0
    
    var active = true
    private var startTime = -1f
    private var fallStartTime = -1f

    fun onCreated() {
        program = ShaderUtil.buildProgram(VS, FS)
        uTime = GLES20.glGetUniformLocation(program, "u_time")
        uPhase = GLES20.glGetUniformLocation(program, "u_phase")
        uIntensity = GLES20.glGetUniformLocation(program, "u_intensity")

        val ids = IntArray(1)
        GLES20.glGenBuffers(1, ids, 0)
        vbo = ids[0]
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo)
        val buf = ByteBuffer.allocateDirect(LOGO_VERTS.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer()
        buf.put(LOGO_VERTS).position(0)
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, LOGO_VERTS.size * 4, buf, GLES20.GL_STATIC_DRAW)
    }

    fun draw(time: Float, isHdrReady: Boolean) {
        if (!active) return
        if (startTime < 0) startTime = time

        val elapsed = time - startTime
        
        // 1. Holding phase (0-3s): Glow pulse
        // 2. Falling phase (3s+): Start dropping
        // 3. End (4.5s): Disable splash
        
        if (elapsed > 4.5f) {
            active = false
            return
        }

        if (elapsed > 3.0f && fallStartTime < 0) {
            fallStartTime = time
        }

        GLES20.glUseProgram(program)
        GLES20.glUniform1f(uTime, time)
        GLES20.glUniform1f(uPhase, if (fallStartTime > 0) fallStartTime else 0f)

        // HDR Intensity: Start at 1.0, spike to 4.0 (intense glow), then fade
        var intensity = 1.0f
        if (elapsed < 1.0f) {
            intensity = 1.0f + elapsed * 3.0f // Initial burn-in
        } else if (elapsed < 3.0f) {
            intensity = 4.0f - (elapsed - 1.0f) * 1.5f // Gradual cool-down to 1.0
        } else {
            intensity = max(0.0f, 1.0f - (elapsed - 3.0f) * 1.0f) // Fade out as it falls
        }
        
        // If HDR isn't engaged, clamp so it doesn't just look "white"
        val finalIntensity = if (isHdrReady) intensity else intensity.coerceIn(0f, 1.0f)
        GLES20.glUniform1f(uIntensity, finalIntensity)

        GLES20.glLineWidth(8f)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo)
        GLES20.glEnableVertexAttribArray(0)
        GLES20.glVertexAttribPointer(0, 2, GLES20.GL_FLOAT, false, 12, 0)
        GLES20.glEnableVertexAttribArray(1)
        GLES20.glVertexAttribPointer(1, 1, GLES20.GL_FLOAT, false, 12, 8)
        
        GLES20.glDrawArrays(GLES20.GL_LINES, 0, LOGO_VERTS.size / 3)
        
        GLES20.glDisableVertexAttribArray(0)
        GLES20.glDisableVertexAttribArray(1)
    }
}
