package com.lowlatency.visualizer.gl

import android.opengl.GLES20
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Visual 16 — "Audio Web".
 *
 * A reactive network of points and connecting lines.
 *   - Lows -> Points vibrate and move faster.
 *   - Mids -> Line brightness and thickness react to audio energy.
 *   - Highs -> Individual points "flare" bright white.
 */
class AudioWebScene : GlScene {

    companion object {
        private const val POINTS_COUNT = 80
        private const val MAX_DIST = 0.35f
        private const val POINT_SIZE = 6.0f

        private const val VERTEX_SHADER = """#version 300 es
            layout(location = 0) in vec2 aPos;
            uniform float u_pointSize;
            void main() {
                gl_Position = vec4(aPos, 0.0, 1.0);
                gl_PointSize = u_pointSize;
            }
        """

        private const val FRAGMENT_SHADER = """#version 300 es
            precision highp float;
            uniform vec3 u_color;
            uniform float u_dim;
            out vec4 fragColor;
            void main() {
                fragColor = vec4(u_color * u_dim, 1.0);
            }
        """
    }

    private class Point(var x: Float, var y: Float, var vx: Float, var vy: Float)

    private val points = Array(POINTS_COUNT) {
        Point(
            Random.nextFloat() * 2f - 1f,
            Random.nextFloat() * 2f - 1f,
            (Random.nextFloat() * 2f - 1f) * 0.1f,
            (Random.nextFloat() * 2f - 1f) * 0.1f
        )
    }

    private val pointBuffer: FloatBuffer = ByteBuffer
        .allocateDirect(POINTS_COUNT * 2 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
    
    // Line buffer: worst case all points connected (too many), 
    // but in practice we'll limit connections per point to keep it sane.
    private val lineBuffer: FloatBuffer = ByteBuffer
        .allocateDirect(POINTS_COUNT * POINTS_COUNT * 2 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()

    private var program = 0
    private var aPos = 0
    private var uPointSize = 0
    private var uColor = 0
    private var uDim = 0
    
    private var aspect = 1f
    private var lastTime = -1f

    override fun onCreated() {
        program = ShaderUtil.buildProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        aPos = GLES20.glGetAttribLocation(program, "aPos")
        uPointSize = GLES20.glGetUniformLocation(program, "u_pointSize")
        uColor = GLES20.glGetUniformLocation(program, "u_color")
        uDim = GLES20.glGetUniformLocation(program, "u_dim")
    }

    override fun onResize(width: Int, height: Int, aspect: Float) {
        this.aspect = aspect
    }

    override fun draw(pcm: FloatArray, bands: FloatArray, timeSec: Float, dim: Float) {
        val dt = if (lastTime < 0f) 0.016f else (timeSec - lastTime).coerceIn(0f, 0.05f)
        lastTime = timeSec

        val low = bands[0]
        val mid = bands[1]
        val high = bands[2]

        // Update points
        pointBuffer.clear()
        for (p in points) {
            val speedMult = 1.0f + low * 2.0f
            p.x += p.vx * dt * speedMult
            p.y += p.vy * dt * speedMult

            // Bounce
            if (p.x < -1f || p.x > 1f) p.vx *= -1f
            if (p.y < -1f || p.y > 1f) p.vy *= -1f
            
            p.x = p.x.coerceIn(-1f, 1f)
            p.y = p.y.coerceIn(-1f, 1f)

            pointBuffer.put(p.x / aspect).put(p.y)
        }
        pointBuffer.position(0)

        // Find connections
        lineBuffer.clear()
        var lineCount = 0
        for (i in 0 until POINTS_COUNT) {
            for (j in i + 1 until POINTS_COUNT) {
                val dx = points[i].x - points[j].x
                val dy = points[i].y - points[j].y
                val distSq = dx * dx + dy * dy
                if (distSq < MAX_DIST * MAX_DIST) {
                    lineBuffer.put(points[i].x / aspect).put(points[i].y)
                    lineBuffer.put(points[j].x / aspect).put(points[j].y)
                    lineCount++
                }
            }
        }
        lineBuffer.position(0)

        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE)
        GLES20.glUseProgram(program)
        GLES20.glUniform1f(uDim, dim)

        // Draw lines
        GLES20.glUniform3f(uColor, 0.2f + mid * 0.3f, 0.5f + mid * 0.5f, 0.8f + mid * 0.2f)
        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, lineBuffer)
        GLES20.glDrawArrays(GLES20.GL_LINES, 0, lineCount * 2)

        // Draw points
        GLES20.glUniform3f(uColor, 1.0f, 1.0f, 1.0f)
        GLES20.glUniform1f(uPointSize, POINT_SIZE + high * 10.0f)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, pointBuffer)
        GLES20.glDrawArrays(GLES20.GL_POINTS, 0, POINTS_COUNT)

        GLES20.glDisableVertexAttribArray(aPos)
    }
}
