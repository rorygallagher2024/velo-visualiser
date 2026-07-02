package com.lowlatency.visualizer.gl

import android.opengl.GLES20
import android.opengl.GLES30
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import kotlin.math.exp
import kotlin.math.max

/**
 * "Obsidian" — a reference-grade spectrum analyzer rendered like polished black
 * glass. Raw data, luxury presentation.
 *
 * 64 precision bars over the (already log-spaced) 128-bin spectrum with proper
 * hardware ballistics: instant attack, silky exponential release, and falling
 * peak caps with real gravity — each cap hangs for a beat, then accelerates down
 * until the next transient catches it. The bars reveal a *fixed* vertical
 * gradient as they rise (the classic high-end analyzer look), tips get a crisp
 * lit edge, and the caps burn HDR-white so the bloom pass gives them a glint.
 * Everything mirrors into a depth-faded reflection below a hairline floor seam —
 * the obsidian slab.
 *
 * It's an honest instrument: no beat enrichment ([respondsToBeat] = false), no
 * camera, no time-based animation at all — nothing moves unless the audio moves
 * it. All geometry is static; the only per-frame upload is a 64-texel RG row
 * (bar + cap heights) that the vertex shader stretches the quads with.
 */
class ObsidianScene : GlScene {

    // Honest readout: glow tracks the signal, never an imposed beat or grid.
    override val respondsToBeat get() = false

    companion object {
        private const val BARS = 64
        private const val FLOOR_Y = -0.25f     // glass seam line (NDC)
        private const val H_SCALE = 1.15f      // full-scale bar height (NDC)
        private const val RELEASE = 3.2f       // bar release rate (per second)
        private const val CAP_HOLD = 0.25f     // seconds a cap hangs before falling
        private const val CAP_GRAVITY = 4.5f   // cap fall acceleration (units/s^2)

        private const val BAR_VS = """#version 300 es
            layout(location = 0) in vec4 aBar;   // x(NDC), u(0..1 across bar), v(0..1 up quad), slot01
            uniform sampler2D u_bars;            // R = bar height, G = cap height (64 x 1)
            uniform float u_mode;                // 0 = bar, 1 = cap
            uniform float u_mirror;              // +1 upper image, -0.62 squashed reflection
            out float v_u;
            out float v_abs;                     // absolute height 0..1 at this fragment
            out float v_h;                       // this bar's current height 0..1
            out float v_slot;

            void main() {
                vec2 s = texture(u_bars, vec2(aBar.w, 0.5)).rg;
                float h = u_mode < 0.5 ? s.r : s.g;
                // Bars stretch 0..h; caps are a thin slab riding at the cap height.
                float a = u_mode < 0.5
                    ? aBar.z * max(h, 0.004)
                    : s.g + aBar.z * 0.014 + 0.006;
                float y = ${FLOOR_Y}f + a * ${H_SCALE}f * u_mirror;
                gl_Position = vec4(aBar.x, y, 0.0, 1.0);
                v_u = aBar.y;
                v_abs = a;
                v_h = h;
                v_slot = aBar.w;
            }
        """

        private const val BAR_FS = """#version 300 es
            precision highp float;
            uniform float u_mode;
            uniform float u_fade;                // 1 = upper image, ~0.3 = reflection
            uniform float u_dim;
            in float v_u;
            in float v_abs;
            in float v_h;
            in float v_slot;
            out vec4 fragColor;

            void main() {
                // Crisp anti-aliased bar sides via screen-space derivatives.
                float fw = fwidth(v_u) * 1.5;
                float aa = smoothstep(0.0, fw, v_u) * (1.0 - smoothstep(1.0 - fw, 1.0, v_u));

                vec3 col;
                if (u_mode < 0.5) {
                    // Fixed vertical gradient the bar *reveals* as it rises:
                    // slate base -> cool cyan-white. Premium hardware, not rainbow.
                    col = mix(vec3(0.10, 0.14, 0.20), vec3(0.45, 0.70, 0.95),
                        smoothstep(0.0, 0.70, v_abs));
                    col = mix(col, vec3(1.05, 1.12, 1.22), smoothstep(0.72, 0.98, v_abs));
                    // Lit tip edge: the top ~3% of the bar itself.
                    col += vec3(0.9, 1.0, 1.1) * smoothstep(v_h - 0.035, v_h - 0.005, v_abs) * 0.55;
                    // Bass runs a touch warm, treble a touch cool.
                    col *= mix(vec3(1.06, 0.98, 0.92), vec3(0.92, 1.00, 1.10), v_slot);
                } else {
                    // Peak caps: HDR white so the bloom pass gives them a glint.
                    col = vec3(1.45, 1.50, 1.60);
                }

                // Reflection dims and dissolves with distance below the glass.
                float fade = u_fade < 0.99 ? u_fade * (1.0 - clamp(v_abs * 2.2, 0.0, 1.0)) : 1.0;
                fragColor = vec4(col * aa * fade * u_dim, 1.0);
            }
        """

        private const val FLAT_VS = """#version 300 es
            layout(location = 0) in vec2 aPos;
            void main() { gl_Position = vec4(aPos, 0.0, 1.0); }
        """

        private const val FLAT_FS = """#version 300 es
            precision highp float;
            uniform float u_dim;
            out vec4 fragColor;
            void main() { fragColor = vec4(vec3(0.30, 0.33, 0.38) * u_dim, 1.0); }
        """
    }

    private val display = FloatArray(BARS)
    private val cap = FloatArray(BARS)
    private val capVel = FloatArray(BARS)
    private val capHold = FloatArray(BARS)
    private val barBuf: FloatBuffer = ByteBuffer
        .allocateDirect(BARS * 2 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()

    private var barProg = 0
    private var flatProg = 0
    private var barVbo = 0
    private var barIbo = 0
    private var floorVbo = 0
    private var barTex = 0
    private var indexCount = 0

    private var uBars = 0; private var uMode = 0; private var uMirror = 0
    private var uFade = 0; private var uDim = 0; private var fDim = 0

    private var lastT = -1f

    override fun onCreated() {
        barProg = ShaderUtil.buildProgram(BAR_VS, BAR_FS)
        uBars = GLES20.glGetUniformLocation(barProg, "u_bars")
        uMode = GLES20.glGetUniformLocation(barProg, "u_mode")
        uMirror = GLES20.glGetUniformLocation(barProg, "u_mirror")
        uFade = GLES20.glGetUniformLocation(barProg, "u_fade")
        uDim = GLES20.glGetUniformLocation(barProg, "u_dim")
        flatProg = ShaderUtil.buildProgram(FLAT_VS, FLAT_FS)
        fDim = GLES20.glGetUniformLocation(flatProg, "u_dim")

        buildGeometry()
        barTex = makeBarTexture()
    }

    /** Static quads: one per bar (x positions baked), plus the floor hairline. */
    private fun buildGeometry() {
        val span = 1.92f
        val slotW = span / BARS
        val verts = FloatArray(BARS * 4 * 4)
        val idx = ShortArray(BARS * 6)
        var vi = 0
        var ii = 0
        for (b in 0 until BARS) {
            val slot = (b + 0.5f) / BARS
            val cx = -span / 2f + (b + 0.5f) * slotW
            val hw = slotW * 0.36f                       // 72% duty cycle
            for ((corner, uv) in listOf(0f to 0f, 1f to 0f, 0f to 1f, 1f to 1f)) {
                verts[vi++] = if (corner < 0.5f) cx - hw else cx + hw
                verts[vi++] = corner                      // u across the bar
                verts[vi++] = uv                          // v up the quad
                verts[vi++] = slot
            }
            val v0 = (b * 4).toShort()
            idx[ii++] = v0; idx[ii++] = (v0 + 1).toShort(); idx[ii++] = (v0 + 2).toShort()
            idx[ii++] = (v0 + 1).toShort(); idx[ii++] = (v0 + 3).toShort(); idx[ii++] = (v0 + 2).toShort()
        }
        indexCount = ii
        val vbuf = ByteBuffer.allocateDirect(verts.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer().put(verts).also { it.position(0) }
        val ibuf: ShortBuffer = ByteBuffer.allocateDirect(idx.size * 2)
            .order(ByteOrder.nativeOrder()).asShortBuffer().put(idx).also { it.position(0) }
        val floor = floatArrayOf(-0.97f, FLOOR_Y - 0.0022f, 0.97f, FLOOR_Y - 0.0022f,
            -0.97f, FLOOR_Y + 0.0022f, 0.97f, FLOOR_Y + 0.0022f)
        val fbuf = ByteBuffer.allocateDirect(floor.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer().put(floor).also { it.position(0) }

        val ids = IntArray(3)
        GLES20.glGenBuffers(3, ids, 0)
        barVbo = ids[0]; barIbo = ids[1]; floorVbo = ids[2]
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, barVbo)
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, verts.size * 4, vbuf, GLES20.GL_STATIC_DRAW)
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, barIbo)
        GLES20.glBufferData(GLES20.GL_ELEMENT_ARRAY_BUFFER, idx.size * 2, ibuf, GLES20.GL_STATIC_DRAW)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, floorVbo)
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, floor.size * 4, fbuf, GLES20.GL_STATIC_DRAW)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, 0)
    }

    private fun makeBarTexture(): Int {
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ids[0])
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST)
        GLES30.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, GLES30.GL_RG16F,
            BARS, 1, 0, GLES30.GL_RG, GLES20.GL_FLOAT, null
        )
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        return ids[0]
    }

    override fun onResize(width: Int, height: Int, aspect: Float) {
        // Fixed full-width composition; bar AA adapts via fwidth in the shader.
    }

    /** Hardware analyzer ballistics: instant attack, smooth release, caps with gravity. */
    private fun updateBallistics(dt: Float) {
        val mags = SpectrumData.magnitudes
        val rel = exp(-dt * RELEASE)
        for (b in 0 until BARS) {
            val raw = max(mags[b * 2], mags[b * 2 + 1]).coerceIn(0f, 1f)
            display[b] = max(raw, display[b] * rel)
            if (raw >= cap[b]) {
                cap[b] = raw
                capVel[b] = 0f
                capHold[b] = CAP_HOLD
            } else {
                capHold[b] -= dt
                if (capHold[b] < 0f) {
                    capVel[b] += CAP_GRAVITY * dt
                    cap[b] = max(cap[b] - capVel[b] * dt, display[b])
                }
            }
        }
        barBuf.clear()
        for (b in 0 until BARS) { barBuf.put(display[b]); barBuf.put(cap[b]) }
        barBuf.position(0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, barTex)
        GLES20.glTexSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, BARS, 1, GLES30.GL_RG, GLES20.GL_FLOAT, barBuf)
    }

    override fun draw(pcm: FloatArray, bands: FloatArray, timeSec: Float, dim: Float) {
        if (lastT < 0f) lastT = timeSec
        val dt = (timeSec - lastT).coerceIn(0f, 0.1f)
        lastT = timeSec
        updateBallistics(dt)

        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE)   // additive on black

        GLES20.glUseProgram(barProg)
        GLES20.glUniform1i(uBars, 0)
        GLES20.glUniform1f(uDim, dim)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, barVbo)
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, barIbo)
        GLES20.glEnableVertexAttribArray(0)
        GLES20.glVertexAttribPointer(0, 4, GLES20.GL_FLOAT, false, 0, 0)

        drawPass(mode = 0f, mirror = 1f, fade = 1f)        // bars
        drawPass(mode = 1f, mirror = 1f, fade = 1f)        // caps
        drawPass(mode = 0f, mirror = -0.62f, fade = 0.30f) // reflected bars
        drawPass(mode = 1f, mirror = -0.62f, fade = 0.30f) // reflected caps

        GLES20.glDisableVertexAttribArray(0)
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, 0)

        // The glass seam.
        GLES20.glUseProgram(flatProg)
        GLES20.glUniform1f(fDim, dim)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, floorVbo)
        GLES20.glEnableVertexAttribArray(0)
        GLES20.glVertexAttribPointer(0, 2, GLES20.GL_FLOAT, false, 0, 0)
        GLES30.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(0)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
    }

    private fun drawPass(mode: Float, mirror: Float, fade: Float) {
        GLES20.glUniform1f(uMode, mode)
        GLES20.glUniform1f(uMirror, mirror)
        GLES20.glUniform1f(uFade, fade)
        GLES20.glDrawElements(GLES20.GL_TRIANGLES, indexCount, GLES20.GL_UNSIGNED_SHORT, 0)
    }

    override fun onDeactivate() {
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, 0)
    }
}
