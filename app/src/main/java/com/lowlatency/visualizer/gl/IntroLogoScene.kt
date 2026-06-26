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
 * First-run intro: the Velo **V mark** rendered as a GPU particle cloud that
 * **ignites → breathes → falls away into the live visualizer**.
 *
 * The V is the brand symbol (same mark as the launcher icon); the name lives in
 * the static logo lockup, not this animation — so the launch reads as the home-
 * screen V igniting and dissolving straight into the visuals.
 *
 * Unlike the other [GlScene]s this is driven directly by [VisualizerRenderer]'s
 * intro path (not the scene array), so it takes bespoke envelope parameters in
 * [draw] instead of the generic audio signature. It still flows through the HDR
 * FP16 buffer + bloom [PostProcessor], so the particles' >1.0 colour blooms for
 * real and exceeds SDR white on HDR panels — the glow a Canvas view cannot do.
 *
 * The V is rasterised once (stroked chevron → offscreen bitmap), its opaque
 * texels sampled into ~[MAX_PARTICLES] points. Every particle's motion lives in
 * the vertex shader, driven by a handful of uniforms (the Starscape idiom):
 * zero per-frame CPU particle work.
 */
class IntroLogoScene {

    private var program = 0
    private var aTarget = 0
    private var aSeed = 0
    private var aSize = 0
    private var uAspect = 0
    private var uAssemble = 0
    private var uHold = 0
    private var uDisperse = 0
    private var uTime = 0
    private var uIntensity = 0
    private var uSizeScale = 0

    private var vbo = 0
    private var particleCount = 0
    private var aspect = 1f
    private var sizeScale = 1f

    fun onCreated() {
        program = ShaderUtil.buildProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        aTarget = GLES20.glGetAttribLocation(program, "aTarget")
        aSeed = GLES20.glGetAttribLocation(program, "aSeed")
        aSize = GLES20.glGetAttribLocation(program, "aSize")
        uAspect = GLES20.glGetUniformLocation(program, "u_aspect")
        uAssemble = GLES20.glGetUniformLocation(program, "u_assemble")
        uHold = GLES20.glGetUniformLocation(program, "u_hold")
        uDisperse = GLES20.glGetUniformLocation(program, "u_disperse")
        uTime = GLES20.glGetUniformLocation(program, "u_time")
        uIntensity = GLES20.glGetUniformLocation(program, "u_intensity")
        uSizeScale = GLES20.glGetUniformLocation(program, "u_sizeScale")

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

    fun onResize(width: Int, height: Int, aspect: Float) {
        this.aspect = aspect
        // Scale point size to the surface so the cloud has the same visual weight
        // on a phone and a tablet (reference height 1080px).
        sizeScale = (height / 1080f).coerceIn(0.6f, 3f)
    }

    /**
     * Draw the particle cloud for one intro frame.
     *
     * @param assemble  0..1 eased — chaos → glyph convergence (ignite)
     * @param hold      0..1 — breathing-jitter amount during the hold beat
     * @param disperse  0..1 — outward shatter progress
     * @param intensity overall brightness/fade envelope
     * @param timeSec   elapsed seconds (curl-noise animation)
     */
    fun draw(assemble: Float, hold: Float, disperse: Float, intensity: Float, timeSec: Float) {
        if (program == 0 || particleCount == 0) return

        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE)   // additive glow

        GLES20.glUseProgram(program)
        GLES20.glUniform1f(uAspect, aspect)
        GLES20.glUniform1f(uAssemble, assemble)
        GLES20.glUniform1f(uHold, hold)
        GLES20.glUniform1f(uDisperse, disperse)
        GLES20.glUniform1f(uTime, timeSec)
        GLES20.glUniform1f(uIntensity, intensity)
        GLES20.glUniform1f(uSizeScale, sizeScale)

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

        // Leave the baseline blend state clean for whatever the renderer does next.
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
    }

    /** Free GPU resources once the intro is over. */
    fun release() {
        if (vbo != 0) { GLES20.glDeleteBuffers(1, intArrayOf(vbo), 0); vbo = 0 }
        if (program != 0) { GLES20.glDeleteProgram(program); program = 0 }
        particleCount = 0
    }

    /**
     * Rasterise the brand "V" chevron to an offscreen bitmap and sample its
     * opaque pixels into interleaved particle records [targetX, targetY, seed,
     * size]. Targets are in an aspect-preserving NDC space (the shader corrects
     * for screen aspect). Same geometry as the launcher icon: a thick, round-
     * capped V.
     */
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
        private const val MAX_PARTICLES = 7000
        private const val FLOATS_PER_PARTICLE = 4
        private const val MARK_PX = 200f               // nominal raster size of the V
        private const val MARK_NDC_HEIGHT = 0.8f       // V spans ~40% of the vertical NDC

        private val VERTEX_SHADER = """#version 300 es
            layout(location = 0) in vec2 aTarget;   // glyph position (aspect-preserving NDC)
            layout(location = 1) in float aSeed;
            layout(location = 2) in float aSize;
            uniform float u_aspect;
            uniform float u_assemble;   // 0..1 eased: chaos -> target
            uniform float u_hold;       // 0..1 breathe amount
            uniform float u_disperse;   // 0..1 shatter
            uniform float u_time;
            uniform float u_sizeScale;
            out float v_assemble;
            out float v_disperse;
            out float v_hue;

            float hash(vec2 p) { return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453); }
            vec2 hash2(float n) { return fract(sin(vec2(n, n + 1.0)) * vec2(43758.5453, 22578.1459)); }

            float vnoise(vec2 p) {
                vec2 i = floor(p), f = fract(p);
                float a = hash(i), b = hash(i + vec2(1.0, 0.0));
                float c = hash(i + vec2(0.0, 1.0)), d = hash(i + vec2(1.0, 1.0));
                vec2 u = f * f * (3.0 - 2.0 * f);
                return mix(mix(a, b, u.x), mix(c, d, u.x), u.y);
            }
            // Cheap pseudo-curl: perpendicular gradient of value noise -> swirling flow.
            vec2 curl(vec2 p) {
                float e = 0.1;
                float nx1 = vnoise(p + vec2(0.0, e)), nx2 = vnoise(p - vec2(0.0, e));
                float ny1 = vnoise(p + vec2(e, 0.0)), ny2 = vnoise(p - vec2(e, 0.0));
                return vec2(nx1 - nx2, -(ny1 - ny2)) / (2.0 * e);
            }

            void main() {
                vec2 target = aTarget;
                vec2 r = hash2(aSeed * 0.097) * 2.0 - 1.0;

                // Chaos start: scattered wide + swirled by the flow field.
                vec2 chaos = target + r * 1.7 + curl(target * 1.5 + aSeed) * 0.5;
                vec2 pos = mix(chaos, target, u_assemble);

                // Breathing micro-jitter once assembled.
                pos += curl(target * 5.0 + u_time * 0.6) * 0.012 * u_hold;

                // Shatter: fly outward (radial + curl) with a touch of gravity.
                float dsp = u_disperse * u_disperse;
                vec2 dir = normalize(target + r * 0.3 + vec2(1e-3));
                pos += (dir * 1.3 + curl(target * 2.0 + u_time * 1.5) * 1.1) * dsp;
                pos.y -= 0.9 * dsp;

                pos.x /= u_aspect;
                gl_Position = vec4(pos, 0.0, 1.0);

                float sz = aSize * u_sizeScale;
                sz *= mix(0.4, 1.0, u_assemble);    // grow as they converge
                sz *= 1.0 + u_hold * 0.15;          // gentle breathe
                sz *= 1.0 - 0.45 * u_disperse;      // shrink as they fly off
                gl_PointSize = max(sz, 1.0);

                v_assemble = u_assemble;
                v_disperse = u_disperse;

                // Per-particle hue: a smooth rainbow banded across the V (so it
                // reads as a spectrum, not noise), with a little per-particle
                // jitter, a slow drift, and an extra spread as it shatters.
                float baseHue = fract(aTarget.y * 0.55 + aTarget.x * 0.18 + 0.5);
                float jitter = (hash(vec2(aSeed, 7.0)) - 0.5) * 0.06;
                v_hue = baseHue + jitter + u_time * 0.03
                        + u_disperse * (hash(vec2(aSeed, 3.0)) - 0.5) * 0.7;
            }
        """

        private val FRAGMENT_SHADER = """#version 300 es
            precision highp float;
            uniform float u_intensity;
            in float v_assemble;
            in float v_disperse;
            in float v_hue;
            out vec4 fragColor;

            const float TAU = 6.28318530718;
            vec3 rainbow(float h) {
                return 0.5 + 0.5 * cos(TAU * (h + vec3(0.0, 0.33, 0.67)));
            }

            void main() {
                vec2 c = gl_PointCoord * 2.0 - 1.0;
                float d = dot(c, c);
                if (d > 1.0) discard;
                float core = exp(-d * 3.5);

                // Vivid per-particle rainbow. Deepen saturation, stay dim before
                // the V assembles, then keep the colour right through the shatter
                // (intensify rather than wash to white) — a music-visualiser bloom.
                vec3 col = rainbow(v_hue);
                col = mix(vec3(dot(col, vec3(0.333))), col, 1.45);   // saturate
                col *= mix(0.35, 1.0, clamp(v_assemble, 0.0, 1.0));  // ignite up
                col += col * v_disperse * 0.7;                       // flare on dissolve

                // HDR overdrive (>1) so the bloom pass makes the cloud glow.
                col *= core * u_intensity * 1.9;
                fragColor = vec4(col, 1.0);
            }
        """
    }
}
