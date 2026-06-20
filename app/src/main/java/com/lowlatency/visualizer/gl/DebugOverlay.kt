package com.lowlatency.visualizer.gl

import android.opengl.GLES20
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Minimalist, Teenage Engineering-inspired FPS counter overlay.
 * Renders tiny technical text at the top-right of the screen.
 * 
 * Uses explicit geometry for maximum compatibility across devices.
 */
class DebugOverlay {

    companion object {
        private const val VS = """#version 300 es
            layout(location = 0) in vec2 aPos;
            void main() { gl_Position = vec4(aPos, 0.0, 1.0); }
        """

        private const val FS = """#version 300 es
            precision highp float;
            uniform vec2 u_res;
            uniform float u_fps;
            out vec4 fragColor;

            float digit(vec2 p, int n) {
                if (p.x < 0.0 || p.x > 1.0 || p.y < 0.0 || p.y > 1.0) return 0.0;
                int bits = 0;
                if (n == 0) bits = 31599; else if (n == 1) bits = 9362; else if (n == 2) bits = 29671;
                else if (n == 3) bits = 29391; else if (n == 4) bits = 23497; else if (n == 5) bits = 31183;
                else if (n == 6) bits = 31215; else if (n == 7) bits = 29257; else if (n == 8) bits = 31727;
                else if (n == 9) bits = 31687;
                ivec2 ip = ivec2(p * vec2(3.0, 5.0));
                return float((bits >> (ip.x + ip.y * 3)) & 1);
            }

            void main() {
                // Moving significantly left to clear center/corner punch cameras
                vec2 p = gl_FragCoord.xy;
                p.x = u_res.x - p.x - 240.0; // 240px margin from right
                p.y = u_res.y - p.y - 60.0;  // Keep 60px from top
                
                // Expanding active area to prevent clipping
                if (p.x < 0.0 || p.x > 180.0 || p.y < 0.0 || p.y > 40.0) discard;

                p /= 24.0; // scale
                
                float d = 0.0;
                int ifps = int(u_fps + 0.5);
                
                // FIXED DIGIT EXTRACTION: Ensure we handle 1-3 digits correctly
                // Thousands/Hundreds
                if (ifps >= 100) d += digit(p - vec2(0.0, 0.0), (ifps / 100) % 10);
                
                // Tens (show 0 if >= 100, e.g. "105")
                if (ifps >= 10)  d += digit(p - vec2(1.3, 0.0), (ifps / 10) % 10);
                
                // Units (always show)
                d += digit(p - vec2(2.6, 0.0), ifps % 10);
                
                // TE Orange with higher intensity
                vec3 ink = vec3(0.941, 0.325, 0.11); 
                fragColor = vec4(ink, d * 1.0);
            }
        """
    }

    private var program = 0
    private var aPos = 0
    private var uRes = 0
    private var uFps = 0

    // Explicit quad geometry for a full-screen pass to catch the fragment shader logic.
    // Full screen because the fragment shader calculates the position itself.
    private val quad: FloatBuffer = ByteBuffer
        .allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        .apply { put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)); position(0) }

    fun onCreated() {
        program = ShaderUtil.buildProgram(VS, FS)
        aPos = GLES20.glGetAttribLocation(program, "aPos")
        uRes = GLES20.glGetUniformLocation(program, "u_res")
        uFps = GLES20.glGetUniformLocation(program, "u_fps")
    }

    fun draw(width: Int, height: Int, fps: Float) {
        GLES20.glUseProgram(program)
        GLES20.glUniform2f(uRes, width.toFloat(), height.toFloat())
        GLES20.glUniform1f(uFps, fps)
        
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        
        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, quad)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(aPos)
    }
}
