package com.lowlatency.visualizer.gl

import android.opengl.GLES20
import com.lowlatency.visualizer.BeatDetector
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * Visual 12 — "Beat Fireworks".
 *
 * Bass transients launch radial particle bursts over black. Each burst is a ring
 * of sparks with shared hue that fly out, arc under gravity, and fade. A simple
 * CPU particle pool (fixed capacity, round-robin spawn) uploaded to a GL_POINTS
 * VBO each frame; additive blending + the bloom pipeline make the sparks glow.
 */
class BeatFireworksScene : GlScene {

    companion object {
        private const val MAX = 1600
        private const val STRIDE = 4              // x, y, life01, hue
        private const val GRAVITY = 0.55f
        private const val DRAG = 0.7f
        private const val MAX_LIFE = 1.6f
        private const val BURST_MIN = 50
        private const val BURST_MAX = 90

        private const val VERTEX_SHADER = """#version 300 es
            layout(location = 0) in vec4 aData;   // x, y, life01, hue
            uniform float u_aspect;
            out float v_life;
            out float v_hue;
            void main() {
                gl_Position = vec4(aData.x / u_aspect, aData.y, 0.0, 1.0);
                gl_PointSize = mix(1.5, 10.0, aData.z);
                v_life = aData.z;
                v_hue = aData.w;
            }
        """

        private const val FRAGMENT_SHADER = """#version 300 es
            precision highp float;
            uniform float u_dim;
            in float v_life;
            in float v_hue;
            out vec4 fragColor;
            vec3 palette(float t) { return 0.5 + 0.5 * cos(6.28318 * (t + vec3(0.0, 0.33, 0.67))); }
            void main() {
                vec2 c = gl_PointCoord * 2.0 - 1.0;
                float d = dot(c, c);
                if (d > 1.0) discard;
                float glow = exp(-d * 2.5);
                vec3 col = palette(v_hue) * v_life * glow * (1.0 + v_life * 1.5);  // HDR
                fragColor = vec4(col * u_dim, 1.0);
            }
        """
    }

    // Particle pool (parallel arrays).
    private val px = FloatArray(MAX)
    private val py = FloatArray(MAX)
    private val vx = FloatArray(MAX)
    private val vy = FloatArray(MAX)
    private val life = FloatArray(MAX)
    private val hue = FloatArray(MAX)
    private var cursor = 0

    private val upload = FloatArray(MAX * STRIDE)
    private val buffer: FloatBuffer = ByteBuffer
        .allocateDirect(MAX * STRIDE * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()

    private val rnd = Random(0xBEEF)
    private val beat = BeatDetector()
    private var lastTime = -1f

    private var program = 0
    private var aData = 0
    private var uAspect = 0
    private var uDim = 0
    private var vbo = 0
    private var aspect = 1f

    override fun onCreated() {
        program = ShaderUtil.buildProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        aData = GLES20.glGetAttribLocation(program, "aData")
        uAspect = GLES20.glGetUniformLocation(program, "u_aspect")
        uDim = GLES20.glGetUniformLocation(program, "u_dim")

        val ids = IntArray(1)
        GLES20.glGenBuffers(1, ids, 0)
        vbo = ids[0]
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo)
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, MAX * STRIDE * 4, null, GLES20.GL_DYNAMIC_DRAW)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
    }

    override fun onResize(width: Int, height: Int, aspect: Float) {
        this.aspect = aspect
    }

    override fun draw(pcm: FloatArray, bands: FloatArray, magnitudes: FloatArray, peaks: FloatArray, timeSec: Float, dim: Float, sharedBuffer: java.nio.ByteBuffer?) {
        val dt = if (lastTime < 0f) 0.016f else (timeSec - lastTime).coerceIn(0f, 0.05f)
        lastTime = timeSec

        // Bass-onset beat detection on the raw PCM → launch a burst.
        if (beat.update(pcm)) spawnBurst(0.85f)

        // Integrate + pack alive particles.
        var n = 0
        for (i in 0 until MAX) {
            if (life[i] <= 0f) continue
            life[i] -= dt
            if (life[i] <= 0f) continue
            vy[i] -= GRAVITY * dt
            vx[i] -= vx[i] * DRAG * dt
            vy[i] -= vy[i] * DRAG * dt
            px[i] += vx[i] * dt
            py[i] += vy[i] * dt
            val o = n * STRIDE
            upload[o] = px[i]
            upload[o + 1] = py[i]
            upload[o + 2] = (life[i] / MAX_LIFE).coerceIn(0f, 1f)
            upload[o + 3] = hue[i]
            n++
        }

        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE)   // additive
        GLES20.glUseProgram(program)
        GLES20.glUniform1f(uAspect, aspect)
        GLES20.glUniform1f(uDim, dim)

        if (n > 0) {
            buffer.clear(); buffer.put(upload, 0, n * STRIDE).position(0)
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo)
            GLES20.glBufferSubData(GLES20.GL_ARRAY_BUFFER, 0, n * STRIDE * 4, buffer)
            GLES20.glEnableVertexAttribArray(aData)
            GLES20.glVertexAttribPointer(aData, 4, GLES20.GL_FLOAT, false, 0, 0)
            GLES20.glDrawArrays(GLES20.GL_POINTS, 0, n)
            GLES20.glDisableVertexAttribArray(aData)
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        }
    }

    private fun spawnBurst(energy: Float) {
        val cx = (rnd.nextFloat() * 2f - 1f) * 0.7f
        val cy = (rnd.nextFloat() * 2f - 1f) * 0.6f
        val h = rnd.nextFloat()
        val count = BURST_MIN + (rnd.nextFloat() * (BURST_MAX - BURST_MIN)).toInt()
        val speed = 0.5f + energy * 0.7f
        for (k in 0 until count) {
            val i = cursor
            cursor = (cursor + 1) % MAX
            val ang = rnd.nextFloat() * 6.2831853f
            val sp = speed * (0.3f + rnd.nextFloat() * 0.7f)
            px[i] = cx; py[i] = cy
            vx[i] = cos(ang) * sp
            vy[i] = sin(ang) * sp + 0.15f          // slight upward bias
            life[i] = MAX_LIFE * (0.6f + rnd.nextFloat() * 0.4f)
            hue[i] = h + (rnd.nextFloat() - 0.5f) * 0.1f
        }
    }

    override fun onDeactivate() {
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
    }
}
