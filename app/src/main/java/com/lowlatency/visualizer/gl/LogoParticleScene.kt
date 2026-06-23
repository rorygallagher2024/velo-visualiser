package com.lowlatency.visualizer.gl

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.opengl.GLES20
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.random.Random

/**
 * A live visualiser based on the Velo intro logo particle cloud.
 * Particles react to real-time audio bands (bass, mid, high).
 */
class LogoParticleScene : GlScene {

    private var program = 0
    private var aTarget = 0
    private var aSeed = 0
    private var aSize = 0
    private var uAspect = 0
    private var uTime = 0
    private var uDim = 0
    private var uSizeScale = 0
    private var uAccent = 0
    private var uBass = 0
    private var uMid = 0
    private var uHigh = 0

    private var vbo = 0
    private var particleCount = 0
    private var aspect = 1f
    private var sizeScale = 1f

    private var smoothedBass = 0f
    private var smoothedMid = 0f
    private var smoothedHigh = 0f

    private val accent = floatArrayOf(0.80f, 0.80f, 0.85f)

    override fun onCreated() {
        program = ShaderUtil.buildProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        aTarget = GLES20.glGetAttribLocation(program, "aTarget")
        aSeed = GLES20.glGetAttribLocation(program, "aSeed")
        aSize = GLES20.glGetAttribLocation(program, "aSize")
        uAspect = GLES20.glGetUniformLocation(program, "u_aspect")
        uTime = GLES20.glGetUniformLocation(program, "u_time")
        uDim = GLES20.glGetUniformLocation(program, "u_dim")
        uSizeScale = GLES20.glGetUniformLocation(program, "u_sizeScale")
        uAccent = GLES20.glGetUniformLocation(program, "u_accent")
        uBass = GLES20.glGetUniformLocation(program, "u_bass")
        uMid = GLES20.glGetUniformLocation(program, "u_mid")
        uHigh = GLES20.glGetUniformLocation(program, "u_high")

        val data = buildParticles()
        particleCount = data.size / FLOATS_PER_PARTICLE

        val buf = ByteBuffer.allocateDirect(data.size * 4).order(ByteOrder.nativeOrder())
            .asFloatBuffer().apply { put(data); position(0) }
        val ids = IntArray(1)
        GLES20.glGenBuffers(1, ids, 0)
        vbo = ids[0]
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo)
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, data.size * 4, buf, GLES20.GL_STATIC_DRAW)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
    }

    override fun onResize(width: Int, height: Int, aspect: Float) {
        this.aspect = aspect
        sizeScale = (height / 1080f).coerceIn(0.6f, 3f)
    }

    override fun draw(pcm: FloatArray, bands: FloatArray, timeSec: Float, dim: Float) {
        if (program == 0 || particleCount == 0) return

        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE)   // additive glow

        GLES20.glUseProgram(program)
        GLES20.glUniform1f(uAspect, aspect)
        GLES20.glUniform1f(uTime, timeSec)
        GLES20.glUniform1f(uDim, dim)
        GLES20.glUniform1f(uSizeScale, sizeScale)
        GLES20.glUniform3f(uAccent, accent[0], accent[1], accent[2])
        val bass = maxOf(0f, bands[0] - 0.15f) * 1.15f
        val mid = maxOf(0f, bands[1] - 0.15f) * 1.15f
        val high = maxOf(0f, bands[2] - 0.15f) * 1.15f

        // Apply exponential moving average to stop twitching
        smoothedBass += (bass - smoothedBass) * 0.15f
        smoothedMid += (mid - smoothedMid) * 0.15f
        smoothedHigh += (high - smoothedHigh) * 0.15f

        GLES20.glUniform1f(uBass, smoothedBass)
        GLES20.glUniform1f(uMid, smoothedMid)
        GLES20.glUniform1f(uHigh, smoothedHigh)

        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo)
        val stride = FLOATS_PER_PARTICLE * 4
        GLES20.glEnableVertexAttribArray(aTarget)
        GLES20.glVertexAttribPointer(aTarget, 2, GLES20.GL_FLOAT, false, stride, 0)
        GLES20.glEnableVertexAttribArray(aSeed)
        GLES20.glVertexAttribPointer(aSeed, 1, GLES20.GL_FLOAT, false, stride, 2 * 4)
        GLES20.glEnableVertexAttribArray(aSize)
        GLES20.glVertexAttribPointer(aSize, 1, GLES20.GL_FLOAT, false, stride, 3 * 4)

        GLES20.glDrawArrays(GLES20.GL_POINTS, 0, particleCount)

        GLES20.glDisableVertexAttribArray(aTarget)
        GLES20.glDisableVertexAttribArray(aSeed)
        GLES20.glDisableVertexAttribArray(aSize)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)

        // Leave the baseline blend state clean
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
    }

    override fun onDeactivate() {
        // Handled clean up of blend func in draw
    }

    private fun buildParticles(): FloatArray {
        val sw = MARK_PX * 0.30f                 // stroke width
        val vW = MARK_PX * 1.05f                 // arm-to-arm width
        val vH = MARK_PX * 0.98f                 // top-to-point height
        val pad = MARK_PX * 0.16f

        val w = (vW + sw + pad * 2f).toInt().coerceAtLeast(1)
        val h = (vH + sw + pad * 2f).toInt().coerceAtLeast(1)

        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val cx = w * 0.5f
        val vTop = pad + sw * 0.5f
        val vBot = vTop + vH
        val path = Path().apply {
            moveTo(cx - vW * 0.5f, vTop)
            lineTo(cx, vBot)
            lineTo(cx + vW * 0.5f, vTop)
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = sw
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            color = Color.WHITE
        }
        canvas.drawPath(path, paint)

        val pixels = IntArray(w * h)
        bmp.getPixels(pixels, 0, w, 0, 0, w, h)
        bmp.recycle()

        // Collect opaque texel coordinates.
        val opaque = ArrayList<Int>(w * h / 4)
        for (idx in pixels.indices) {
            if ((pixels[idx] ushr 24 and 0xFF) > 128) opaque.add(idx)
        }
        if (opaque.isEmpty()) return FloatArray(0)

        // Stride down to the particle budget for an even spatial spread.
        val stride = (opaque.size / MAX_PARTICLES).coerceAtLeast(1)
        val rnd = Random(0x7E10)
        val pxToNdc = MARK_NDC_HEIGHT / h              // uniform on x & y => no stretch
        val halfW = w * 0.5f
        val halfH = h * 0.5f

        val out = ArrayList<Float>((opaque.size / stride + 1) * FLOATS_PER_PARTICLE)
        var i = 0
        while (i < opaque.size) {
            val idx = opaque[i]
            val px = idx % w
            val py = idx / w
            // Jitter within the texel so the grid never reads as a lattice.
            val nx = (px + rnd.nextFloat() - halfW) * pxToNdc
            val ny = (halfH - py - rnd.nextFloat()) * pxToNdc   // flip y for GL
            out.add(nx)
            out.add(ny)
            out.add(rnd.nextFloat() * 1000f)                    // seed
            out.add(2f + rnd.nextFloat() * 2.5f)                // base point size (px)
            i += stride
        }
        return out.toFloatArray()
    }

    companion object {
        private const val MAX_PARTICLES = 10000 // slightly increased for richer effect
        private const val FLOATS_PER_PARTICLE = 4
        private const val MARK_PX = 200f               // nominal raster size of the V
        private const val MARK_NDC_HEIGHT = 0.8f       // V spans ~40% of the vertical NDC

        private val VERTEX_SHADER = """#version 300 es
            layout(location = 0) in vec2 aTarget;
            layout(location = 1) in float aSeed;
            layout(location = 2) in float aSize;
            uniform float u_aspect;
            uniform float u_time;
            uniform float u_sizeScale;
            uniform float u_bass;
            uniform float u_mid;
            uniform float u_high;
            
            out float v_energy;

            float hash(vec2 p) { return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453); }
            vec2 hash2(float n) { return fract(sin(vec2(n, n + 1.0)) * vec2(43758.5453, 22578.1459)); }

            float vnoise(vec2 p) {
                vec2 i = floor(p), f = fract(p);
                float a = hash(i), b = hash(i + vec2(1.0, 0.0));
                float c = hash(i + vec2(0.0, 1.0)), d = hash(i + vec2(1.0, 1.0));
                vec2 u = f * f * (3.0 - 2.0 * f);
                return mix(mix(a, b, u.x), mix(c, d, u.x), u.y);
            }
            vec2 curl(vec2 p) {
                float e = 0.1;
                float nx1 = vnoise(p + vec2(0.0, e)), nx2 = vnoise(p - vec2(0.0, e));
                float ny1 = vnoise(p + vec2(e, 0.0)), ny2 = vnoise(p - vec2(e, 0.0));
                return vec2(nx1 - nx2, -(ny1 - ny2)) / (2.0 * e);
            }

            void main() {
                vec2 target = aTarget;
                vec2 r = hash2(aSeed * 0.097) * 2.0 - 1.0;

                // Base breathing micro-jitter
                vec2 pos = target + curl(target * 5.0 + u_time * 0.6) * 0.012;

                // Bass explosion/shatter
                // As bass gets higher, push particles outwards along their normal
                float push = u_bass * u_bass * 1.5;
                vec2 dir = normalize(target + r * 0.3 + vec2(1e-3));
                pos += (dir * 0.4 + curl(target * 2.0 + u_time * 1.5) * 0.5) * push;

                pos.x /= u_aspect;
                gl_Position = vec4(pos, 0.0, 1.0);

                float sz = aSize * u_sizeScale;
                sz *= 1.0 + u_mid * 0.5;            // mids make points larger
                sz *= 1.0 + u_high * 0.8;           // highs add sparkles
                gl_PointSize = max(sz, 1.0);

                // Pass energy down to fragment shader for coloring
                v_energy = (u_bass * 0.5 + u_mid * 0.3 + u_high * 0.2) + push;
            }
        """

        private val FRAGMENT_SHADER = """#version 300 es
            precision highp float;
            uniform vec3 u_accent;
            uniform float u_dim;
            uniform float u_bass;
            uniform float u_high;
            in float v_energy;
            out vec4 fragColor;

            void main() {
                vec2 c = gl_PointCoord * 2.0 - 1.0;
                float d = dot(c, c);
                if (d > 1.0) discard;
                float core = exp(-d * 3.5);

                // Start from base accent, mix towards pure white with high energy
                vec3 col = mix(u_accent, vec3(1.5, 1.5, 1.6), min(v_energy * 0.8, 1.0));
                
                // Add a bit of colored sparkle on high frequencies
                col += vec3(0.1, 0.3, 0.5) * u_high;

                // HDR overdrive so the bloom pass makes the cloud glow.
                // Glow intensity pulses with the beat (bass) and dim.
                col *= core * (1.0 + u_bass * 1.5) * 1.5 * u_dim;
                fragColor = vec4(col, 1.0);
            }
        """
    }
}
