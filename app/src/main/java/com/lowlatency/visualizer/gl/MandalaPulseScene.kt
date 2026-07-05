package com.lowlatency.visualizer.gl

import android.opengl.GLES20
import android.opengl.GLES30
import android.util.Log
import com.lowlatency.visualizer.BeatBus
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.exp
import kotlin.math.max

/**
 * Visual 15 — "Mandala Pulse" (volumetric energy bloom).
 *
 * A *feedback* mandala.
 * Each frame the previous frame is faded and zoomed radially outward (so light
 * trails out from the centre as glowing rays) and fresh kaleidoscopic energy —
 * soft glowing rings + a hot core + beat shockwaves — is injected on top, in a
 * half-res FP16 ping-pong buffer. A tone-map on present lets the accumulated
 * energy bloom richly without ever clipping to a white disc, and the renderer's
 * bloom finishes the glow.
 *
 * The zoom is purely radial so the n-fold symmetry survives the feedback; only
 * the injected pattern rotates. Audio drives it: bands light the rings, a
 * de-noised bass pulse pushes the outward zoom + core, and gated beats fire the
 * shockwave. A small noise floor is shaved off each band so the mic floor can't
 * twitch it when quiet.
 */
class MandalaPulseScene : GlScene {

    private var fbProg = 0
    private var presentProg = 0
    private var quadVbo = 0
    private val fbo = IntArray(2)
    private val fbTex = IntArray(2)
    private var readIdx = 0
    private var fbW = 0
    private var fbH = 0
    private var broken = false

    // Feedback-pass uniforms.
    private var uPrev = 0; private var uRes = 0; private var uTime = 0
    private var uLow = 0; private var uMid = 0; private var uHigh = 0
    private var uPulse = 0; private var uPunch = 0; private var uShock = 0; private var uEnergy = 0
    // Present-pass uniforms.
    private var pFb = 0; private var pDim = 0

    private val prevFbo = IntArray(1)
    private val prevViewport = IntArray(4)

    // Smoothed, de-noised audio envelopes + peak-held bass pulse + beat shockwave.
    private var sLow = 0f
    private var sMid = 0f
    private var sHigh = 0f
    private var pulse = 0f
    private var shockAge = 9f
    private var lastBeat = 0
    private var lastT = -1f

    private val quad: FloatBuffer = ByteBuffer
        .allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        .apply { put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)); position(0) }

    override fun onCreated() {
        fbProg = ShaderUtil.buildProgram(MandalaShaders.QUAD_VS, MandalaShaders.FEEDBACK_FS)
        presentProg = ShaderUtil.buildProgram(MandalaShaders.QUAD_VS, MandalaShaders.PRESENT_FS)
        uPrev = GLES20.glGetUniformLocation(fbProg, "u_prev")
        uRes = GLES20.glGetUniformLocation(fbProg, "u_res")
        uTime = GLES20.glGetUniformLocation(fbProg, "u_time")
        uLow = GLES20.glGetUniformLocation(fbProg, "u_low")
        uMid = GLES20.glGetUniformLocation(fbProg, "u_mid")
        uHigh = GLES20.glGetUniformLocation(fbProg, "u_high")
        uPulse = GLES20.glGetUniformLocation(fbProg, "u_pulse")
        uPunch = GLES20.glGetUniformLocation(fbProg, "u_punch")
        uShock = GLES20.glGetUniformLocation(fbProg, "u_shock")
        uEnergy = GLES20.glGetUniformLocation(fbProg, "u_energy")
        pFb = GLES20.glGetUniformLocation(presentProg, "u_fb")
        pDim = GLES20.glGetUniformLocation(presentProg, "u_dim")

        val ids = IntArray(1)
        GLES20.glGenBuffers(1, ids, 0)
        quadVbo = ids[0]
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, quadVbo)
        val qb = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder())
            .asFloatBuffer().apply { put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)); position(0) }
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, 8 * 4, qb, GLES20.GL_STATIC_DRAW)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
    }

    override fun onResize(width: Int, height: Int, aspect: Float) {
        // Half-res feedback buffer — cheap, and the soft downscale suits the glow.
        createTargets(max(width / 2, 1), max(height / 2, 1))
    }

    private fun createTargets(w: Int, h: Int) {
        if (fbTex[0] != 0) {
            GLES20.glDeleteTextures(2, fbTex, 0)
            GLES20.glDeleteFramebuffers(2, fbo, 0)
        }
        fbW = w; fbH = h; broken = false
        for (k in 0..1) {
            fbTex[k] = makeTexture(w, h)
            fbo[k] = makeFbo(fbTex[k])
        }
        readIdx = 0
    }

    private fun makeTexture(w: Int, h: Int): Int {
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ids[0])
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES30.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES30.GL_RGBA16F, w, h, 0, GLES20.GL_RGBA, GLES20.GL_FLOAT, null)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        return ids[0]
    }

    private fun makeFbo(tex: Int): Int {
        val ids = IntArray(1)
        GLES20.glGenFramebuffers(1, ids, 0)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, ids[0])
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, tex, 0)
        if (GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) != GLES20.GL_FRAMEBUFFER_COMPLETE) {
            Log.e("MandalaPulse", "FBO incomplete — FP16 render target unsupported; scene disabled")
            broken = true
        }
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        return ids[0]
    }

    /** Frame-rate-independent one-pole envelope: fast toward rising input, slow release. */
    private fun env(current: Float, target: Float, attackPerSec: Float, releasePerSec: Float, dt: Float): Float {
        val rate = if (target > current) attackPerSec else releasePerSec
        return current + (target - current) * (1f - exp(-rate * dt))
    }

    private fun smoothstep(a: Float, b: Float, x: Float): Float {
        val t = ((x - a) / (b - a)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    override fun draw(pcm: FloatArray, bands: FloatArray, timeSec: Float, dim: Float) {
        if (broken || fbW == 0) return
        val first = lastT < 0f
        val dt = if (first) 0.016f else (timeSec - lastT).coerceIn(0f, 0.1f)
        lastT = timeSec
        if (first) lastBeat = BeatBus.beatCount

        // De-noise, smooth, and shape the audio (same gate philosophy as before).
        val low = max(bands[0] - NOISE_FLOOR, 0f)
        val mid = max(bands[1] - NOISE_FLOOR, 0f)
        val high = max(bands[2] - NOISE_FLOOR, 0f)
        sLow = env(sLow, low, 16f, 4f, dt)
        sMid = env(sMid, mid, 13f, 3.2f, dt)
        sHigh = env(sHigh, high, 22f, 6f, dt)
        pulse = max(pulse - dt * 2.4f, 0f)
        if (low > pulse) pulse = low
        val punch = smoothstep(0.12f, 0.5f, pulse)   // knee for the outward-zoom motion
        val energy = sLow * 0.5f + sMid * 0.3f + sHigh * 0.2f

        val beat = BeatBus.beatCount
        if (beat != lastBeat) { lastBeat = beat; shockAge = 0f }
        shockAge = (shockAge + dt).coerceAtMost(2f)

        GLES20.glGetIntegerv(GLES30.GL_FRAMEBUFFER_BINDING, prevFbo, 0)
        GLES20.glGetIntegerv(GLES20.GL_VIEWPORT, prevViewport, 0)
        GLES20.glDisable(GLES20.GL_BLEND)

        // Feedback pass: warp+fade the previous frame and inject fresh energy.
        val write = 1 - readIdx
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo[write])
        GLES20.glViewport(0, 0, fbW, fbH)
        GLES20.glUseProgram(fbProg)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fbTex[readIdx])
        GLES20.glUniform1i(uPrev, 0)
        GLES20.glUniform2f(uRes, fbW.toFloat(), fbH.toFloat())
        GLES20.glUniform1f(uTime, timeSec)
        GLES20.glUniform1f(uLow, sLow)
        GLES20.glUniform1f(uMid, sMid)
        GLES20.glUniform1f(uHigh, sHigh)
        GLES20.glUniform1f(uPulse, pulse)
        GLES20.glUniform1f(uPunch, punch)
        GLES20.glUniform1f(uShock, shockAge)
        GLES20.glUniform1f(uEnergy, energy)
        drawQuad(fbProg)
        readIdx = write

        // Present: restore the renderer's buffer and blit the mandala into it.
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, prevFbo[0])
        GLES20.glViewport(prevViewport[0], prevViewport[1], prevViewport[2], prevViewport[3])
        GLES20.glUseProgram(presentProg)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fbTex[readIdx])
        GLES20.glUniform1i(pFb, 0)
        GLES20.glUniform1f(pDim, dim)
        drawQuad(presentProg)
    }

    private fun drawQuad(program: Int) {
        val a = GLES20.glGetAttribLocation(program, "aPos")
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, quadVbo)
        GLES20.glEnableVertexAttribArray(a)
        GLES20.glVertexAttribPointer(a, 2, GLES20.GL_FLOAT, false, 0, 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(a)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
    }

    override fun onDeactivate() {
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
    }

    companion object {
        private const val NOISE_FLOOR = 0.07f   // band level shaved off as mic noise
    }
}

/** Mandala Pulse GLSL, kept out of the scene class (size guard + readability). */
private object MandalaShaders {

    const val QUAD_VS = """#version 300 es
        layout(location = 0) in vec2 aPos;
        out vec2 v_uv;
        void main() {
            v_uv = aPos * 0.5 + 0.5;
            gl_Position = vec4(aPos, 0.0, 1.0);
        }
    """

    /**
     * Feedback: fade + radially zoom the previous frame (light trails outward as
     * glowing rays), then inject fresh kaleidoscopic energy — soft rings lit by
     * the bands, a hot core on the bass, and a beat shockwave.
     */
    const val FEEDBACK_FS = """#version 300 es
        precision highp float;
        uniform sampler2D u_prev;
        uniform vec2  u_res;
        uniform float u_time, u_low, u_mid, u_high, u_pulse, u_punch, u_shock, u_energy;
        in vec2 v_uv;
        out vec4 o;

        const float TAU = 6.28318530718;
        const float DECAY = 0.82;     // trail persistence (kept low so it can't flood)
        const float INJECT = 0.32;    // fresh-energy gain
        const float N = 6.0;          // kaleidoscopic symmetry

        vec3 pal(float t) {
            return 0.5 + 0.5 * cos(TAU * (t + vec3(0.0, 0.33, 0.67)));
        }

        void main() {
            float asp = u_res.x / u_res.y;
            vec2 c = v_uv - 0.5;
            c.x *= asp;                                  // round, not stretched

            // --- feedback: radial zoom-out (rays) + fade; symmetry-preserving ---
            float zoom = 1.0 - 0.002 - u_punch * 0.018;  // <1 => gentle outward drift
            vec2 wc = c * zoom;
            vec2 wuv = vec2(wc.x / asp, wc.y) + 0.5;
            vec3 prev = texture(u_prev, wuv).rgb * DECAY;

            // --- fresh kaleidoscopic energy ---
            float r = length(c) * 2.0;
            float ang = atan(c.y, c.x) + u_time * 0.06;
            float sector = TAU / N;
            ang = abs(mod(ang, sector) - sector * 0.5);  // mirror fold

            vec3 e = vec3(0.0);
            for (int k = 0; k < 3; k++) {
                float fk = float(k);
                float rr = 0.33 + fk * 0.33;                                 // ring radii
                float band = (k == 0) ? u_low : (k == 1) ? u_mid : u_high;
                float ring = exp(-pow((r - rr) * (5.5 - fk), 2.0));          // soft ring
                float pet = 0.5 + 0.5 * cos(ang * (3.0 + fk * 2.0) - u_time * (0.4 + fk * 0.25));
                float glow = ring * pet * (0.04 + band * 2.4);               // ~0 when quiet
                e += pal(rr * 0.5 + fk * 0.16 + u_time * 0.03) * glow;
            }
            // Hot core on the bass.
            float core = exp(-r * r * 3.4);
            e += pal(0.06 + u_high * 0.3) * core * (0.04 + u_pulse * 1.8);
            // Beat shockwave — a bright ring pinging outward, smeared by the feedback.
            float sr = u_shock * 1.4;
            e += pal(0.12) * exp(-16.0 * (r - sr) * (r - sr)) * exp(-u_shock * 2.6) * 1.5;

            vec3 col = min(prev + e * INJECT, vec3(5.0));  // clamp guards runaway
            o = vec4(col, 1.0);
        }
    """

    /** Present: tone-map the accumulated energy so it blooms without clipping white. */
    const val PRESENT_FS = """#version 300 es
        precision highp float;
        uniform sampler2D u_fb;
        uniform float u_dim;
        in vec2 v_uv;
        out vec4 fragColor;

        void main() {
            vec3 c = max(texture(u_fb, v_uv).rgb, 0.0) * 1.3;   // exposure
            c = (c / (1.0 + c)) * 0.92;                         // reinhard: hard cap, never white
            float l = dot(c, vec3(0.299, 0.587, 0.114));
            c = max(mix(vec3(l), c, 1.3), 0.0);                 // saturated, bold colour
            // Gentle vignette for depth.
            vec2 p = v_uv - 0.5;
            c *= smoothstep(0.95, 0.35, length(p));
            fragColor = vec4(c * u_dim, 1.0);
        }
    """
}
