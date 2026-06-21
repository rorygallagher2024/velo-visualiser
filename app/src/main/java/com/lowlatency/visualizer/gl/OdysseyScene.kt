package com.lowlatency.visualizer.gl

import android.opengl.GLES20
import android.opengl.GLES30
import com.lowlatency.visualizer.BeatPulse
import kotlin.math.PI
import kotlin.math.sin

/**
 * One self-contained world inside [OdysseyScene]. Movements render their content
 * into whatever framebuffer the master has bound (full brightness — the master
 * applies `dim` and transition blending during the composite). They own their
 * GL state (blend etc.) and read a shared [travel] so the journey feels
 * continuous across cuts.
 */
interface OdysseyMovement {
    /** False => the scheduler skips this world (e.g. compute/raymarch unsupported). */
    val supported: Boolean get() = true
    fun onCreated()
    fun onResize(width: Int, height: Int, aspect: Float)
    fun draw(time: Float, bands: FloatArray, travel: Float, beat: Float)
}

/**
 * "Odyssey" — the flagship journey. A single endless flight that morphs through
 * distinct generative worlds and cross-fades between them, preferring to land
 * each cut on a beat so it feels musical (MilkDrop-style "mini adventure").
 *
 * Architecture: each world is an [OdysseyMovement]. The master renders the active
 * world into an offscreen FP16 buffer, and during a transition renders the
 * incoming world into a second buffer, then composites `mix(A, B, t)` to the
 * screen with an exposure surge so each cut reads as a dive through light. A
 * shared `travel` accumulator (bass-accelerated) keeps the flight continuous.
 *
 * Inherits the engine's HDR bloom for free: the renderer wraps this scene with
 * the PostProcessor, so the worlds' >1.0 output blooms. The currently-bound FBO
 * is captured at draw time, so no renderer changes are needed.
 */
class OdysseyScene : GlScene {

    private val movements: List<OdysseyMovement> = listOf(
        NebulaMovement(),
        TerrainMovement(),
        CathedralMovement(),
        WarpMovement(),
    )

    private var current = 0
    private var target = 0
    private var transStart = -1f
    private var lastSwitch = -1f       // <0 => not yet started (lazy-init on first draw)
    private var prevBeatCount = 0

    private var travel = 0f
    private var speed = 1f
    private var lastTime = -1f

    private var w = 1
    private var h = 1
    private var aspect = 1f

    private var fboA = 0; private var texA = 0
    private var fboB = 0; private var texB = 0
    private var fbosReady = false

    private var compositeProg = 0
    private var uTexA = 0; private var uTexB = 0
    private var uMix = 0; private var uFlash = 0; private var uDim = 0

    override fun onCreated() {
        compositeProg = ShaderUtil.buildProgram(COMPOSITE_VS, COMPOSITE_FS)
        uTexA = GLES20.glGetUniformLocation(compositeProg, "u_texA")
        uTexB = GLES20.glGetUniformLocation(compositeProg, "u_texB")
        uMix = GLES20.glGetUniformLocation(compositeProg, "u_mix")
        uFlash = GLES20.glGetUniformLocation(compositeProg, "u_flash")
        uDim = GLES20.glGetUniformLocation(compositeProg, "u_dim")
        movements.forEach { it.onCreated() }
    }

    override fun onResize(width: Int, height: Int, aspect: Float) {
        w = width.coerceAtLeast(1); h = height.coerceAtLeast(1); this.aspect = aspect
        allocateFbos()
        movements.forEach { it.onResize(width, height, aspect) }
    }

    override fun draw(pcm: FloatArray, bands: FloatArray, timeSec: Float, dim: Float) {
        val dt = if (lastTime < 0f) 0.016f else (timeSec - lastTime).coerceIn(0f, 0.05f)
        lastTime = timeSec

        // Shared flight: bass accelerates the journey; travel is monotonic.
        speed += ((1f + bands[0] * 2.0f) - speed) * 0.05f
        travel += speed * dt * BASE_FLOW

        // Lazy-start the world timer when the scene first runs (the global clock is
        // already well past 0 by the time the user selects Odyssey).
        if (lastSwitch < 0f) { lastSwitch = timeSec; prevBeatCount = BeatPulse.beatCount }

        updateSchedule(timeSec)
        val beat = BeatPulse.envelope

        // Fallback for devices where the FP16 FBOs are incomplete: draw the active
        // world straight to the target, no cross-fade.
        if (!fbosReady) {
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            movements[current].draw(timeSec, bands, travel, beat)
            return
        }

        val target0 = IntArray(1)
        GLES20.glGetIntegerv(GLES20.GL_FRAMEBUFFER_BINDING, target0, 0)

        val transitioning = transStart >= 0f
        var mix = 0f
        var flash = 0f
        if (transitioning) {
            val tt = ((timeSec - transStart) / TRANS_SEC).coerceIn(0f, 1f)
            mix = tt * tt * (3f - 2f * tt)                 // smoothstep
            flash = sin((tt * PI).toFloat()) * FLASH_MAX   // bump, peaks mid-cut
            if (tt >= 1f) {
                current = target; transStart = -1f; lastSwitch = timeSec
            }
        }

        renderWorldTo(fboA, movements[current], timeSec, bands, travel, beat)
        if (transitioning) renderWorldTo(fboB, movements[target], timeSec, bands, travel, beat)

        // Composite to the renderer's (post) target.
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, target0[0])
        GLES20.glViewport(0, 0, w, h)
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glUseProgram(compositeProg)
        bindTex(0, texA, uTexA)
        bindTex(1, if (transitioning) texB else texA, uTexB)
        GLES20.glUniform1f(uMix, mix)
        GLES20.glUniform1f(uFlash, flash)
        GLES20.glUniform1f(uDim, dim)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 3)
    }

    /** Decide when to begin the next cut: every [MOVEMENT_SEC], snapped to a beat. */
    private fun updateSchedule(timeSec: Float) {
        val bc = BeatPulse.beatCount
        val beatNow = bc != prevBeatCount
        prevBeatCount = bc
        if (transStart >= 0f) return

        val elapsed = timeSec - lastSwitch
        if (elapsed < MOVEMENT_SEC) return
        // Land the cut on the next beat once due; bail out after a short grace.
        if (!beatNow && elapsed < MOVEMENT_SEC + BEAT_GRACE) return

        val next = pickNext()
        if (next != current) {
            target = next
            transStart = timeSec
        } else {
            lastSwitch = timeSec   // nothing else to switch to; reset the timer
        }
    }

    private fun pickNext(): Int {
        var n = current
        for (i in movements.indices) {
            n = (n + 1) % movements.size
            if (n != current && movements[n].supported) return n
        }
        return current
    }

    private fun renderWorldTo(
        fbo: Int, m: OdysseyMovement, time: Float, bands: FloatArray, travel: Float, beat: Float,
    ) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo)
        GLES20.glViewport(0, 0, w, h)
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        m.draw(time, bands, travel, beat)
    }

    private fun bindTex(unit: Int, tex: Int, sampler: Int) {
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + unit)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex)
        GLES20.glUniform1i(sampler, unit)
    }

    private fun allocateFbos() {
        releaseFbos()
        texA = createColorTexture(w, h); fboA = createFbo(texA)
        texB = createColorTexture(w, h); fboB = createFbo(texB)
        fbosReady = fboA != 0 && fboB != 0
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
    }

    private fun releaseFbos() {
        if (texA != 0) GLES20.glDeleteTextures(1, intArrayOf(texA), 0)
        if (texB != 0) GLES20.glDeleteTextures(1, intArrayOf(texB), 0)
        if (fboA != 0) GLES20.glDeleteFramebuffers(1, intArrayOf(fboA), 0)
        if (fboB != 0) GLES20.glDeleteFramebuffers(1, intArrayOf(fboB), 0)
        texA = 0; texB = 0; fboA = 0; fboB = 0
    }

    private fun createColorTexture(width: Int, height: Int): Int {
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ids[0])
        GLES30.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, GLES30.GL_RGBA16F, width, height, 0,
            GLES20.GL_RGBA, GLES30.GL_HALF_FLOAT, null
        )
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        return ids[0]
    }

    private fun createFbo(tex: Int): Int {
        val ids = IntArray(1)
        GLES20.glGenFramebuffers(1, ids, 0)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, ids[0])
        GLES20.glFramebufferTexture2D(
            GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, tex, 0
        )
        val ok = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) == GLES20.GL_FRAMEBUFFER_COMPLETE
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        return if (ok) ids[0] else 0
    }

    override fun onDeactivate() {
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        // Restart the journey from the opener next time the scene is entered.
        current = 0; target = 0; transStart = -1f
        lastSwitch = -1f; lastTime = -1f
    }

    companion object {
        private const val BASE_FLOW = 1.4f       // world units / second at rest
        private const val MOVEMENT_SEC = 26f     // min seconds per world before a cut
        private const val BEAT_GRACE = 3f        // switch anyway if no beat lands in time
        private const val TRANS_SEC = 1.8f       // cross-fade duration
        private const val FLASH_MAX = 0.6f       // exposure surge at the peak of a cut

        private const val COMPOSITE_VS = """#version 300 es
            out vec2 v_uv;
            void main() {
                vec2 p = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));
                v_uv = p;
                gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
            }
        """

        private const val COMPOSITE_FS = """#version 300 es
            precision highp float;
            in vec2 v_uv;
            uniform sampler2D u_texA;
            uniform sampler2D u_texB;
            uniform float u_mix;
            uniform float u_flash;
            uniform float u_dim;
            out vec4 o;
            void main() {
                vec3 a = texture(u_texA, v_uv).rgb;
                vec3 b = texture(u_texB, v_uv).rgb;
                vec3 c = mix(a, b, u_mix);
                c += u_flash;                 // dive-through-light surge (blooms in post)
                o = vec4(c * u_dim, 1.0);
            }
        """
    }
}
