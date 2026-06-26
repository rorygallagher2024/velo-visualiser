package com.lowlatency.visualizer.gl

import android.opengl.GLES20
import android.opengl.GLES30
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.exp
import kotlin.math.max

/**
 * Visual 29 — "Liquid Light".
 *
 * A modern take on the old Winamp / MilkDrop feedback visuals, cranked for
 * colour. The signature MilkDrop trick is frame feedback: each frame the
 * previous frame is sampled through a slowly warping transform (zoom + rotate +
 * swirl) and faded, so everything smears into liquid tunnels and trails. On top
 * of that we draw a rainbow radial waveform straight from the live PCM, so the
 * audio literally carves the light.
 *
 * Pipeline (all at a reduced resolution — softer + cheap):
 *   1. Feedback pass: sample the previous buffer warped + decayed, then add the
 *      hue-cycling PCM waveform ring and a bass core bloom; write the next buffer
 *      (ping-pong RGBA16F, so colour can exceed 1.0 → the renderer's bloom glows).
 *   2. Display pass: saturate + vignette to the renderer's framebuffer (restoring
 *      whatever it had bound — the bloom buffer when post-processing is on).
 *
 * Audio: lows → zoom pulse + core; mids → rotation + hue drift; highs → swirl +
 * waveform shimmer. Envelopes are smoothed here so it flows rather than strobes.
 */
class LiquidLightScene : GlScene {

    companion object {
        private const val TAG = "LiquidLight"
        private const val LONG_EDGE = 720     // feedback-buffer long edge (soft + fast)
        private const val WAVE_N = 512        // PCM samples uploaded around the ring

        private const val VERT = """#version 300 es
out vec2 v_uv;
void main() {
    vec2 p = vec2(gl_VertexID == 1 ? 3.0 : -1.0, gl_VertexID == 2 ? 3.0 : -1.0);
    v_uv = p * 0.5 + 0.5;
    gl_Position = vec4(p, 0.0, 1.0);
}
"""

        // Warp the previous frame, decay it, then add the rainbow PCM ring + core.
        private const val FRAG_FEEDBACK = """#version 300 es
precision highp float;
in vec2 v_uv;
uniform sampler2D u_prev;
uniform sampler2D u_wave;
uniform float u_aspect;
uniform float u_time;
uniform float u_low;
uniform float u_mid;
uniform float u_high;
uniform float u_pulse;
uniform float u_decay;
uniform float u_zoom;
uniform float u_rot;
uniform float u_swirl;
uniform float u_hue;
out vec4 frag;

const float TAU = 6.28318530718;

vec3 rainbow(float t) {
    return 0.5 + 0.5 * cos(TAU * (t + vec3(0.0, 0.33, 0.67)));
}

void main() {
    vec2 p = v_uv - 0.5;
    p.x *= u_aspect;

    float r = length(p);
    float a = atan(p.y, p.x);

    // --- warp the feedback sample (zoom toward centre + rotate + swirl) ---
    float wr = r * u_zoom;
    float wa = a + u_rot + u_swirl * (0.6 - r);
    vec2 wp = vec2(cos(wa), sin(wa)) * wr;
    vec2 wuv = vec2(wp.x / u_aspect, wp.y) + 0.5;
    vec3 prev = texture(u_prev, wuv).rgb * u_decay;

    // --- rainbow radial waveform from the live PCM ---
    float idx = fract(a / TAU + u_time * 0.04);
    float w = texture(u_wave, vec2(idx, 0.5)).r;          // -1..1
    float targetR = 0.20 + u_low * 0.10 + w * (0.12 + u_mid * 0.10);
    float lineW = 0.010 + u_high * 0.012;
    float ring = smoothstep(lineW, 0.0, abs(r - targetR));
    vec3 ringCol = rainbow(idx * 1.5 + u_hue) * ring * (1.0 + u_pulse * 1.8);

    // Accumulate trails + ring, then carve a dark vanishing point at the centre.
    // The centre is the zoom fixed-point, so without this it accumulates every
    // hue and blows out to white — masking it to black keeps the tunnel clean.
    vec3 col = prev + ringCol;
    col *= smoothstep(0.03, 0.13, r);
    frag = vec4(col, 1.0);
}
"""

        // Saturate + vignette the accumulated buffer to the screen.
        private const val FRAG_DISPLAY = """#version 300 es
precision highp float;
in vec2 v_uv;
uniform sampler2D u_state;
uniform float u_aspect;
uniform float u_dim;
out vec4 frag;
void main() {
    vec3 col = texture(u_state, v_uv).rgb;
    float luma = dot(col, vec3(0.299, 0.587, 0.114));
    col = mix(vec3(luma), col, 1.35);                    // punch up saturation
    vec2 p = (v_uv - 0.5) * vec2(u_aspect, 1.0);
    col *= smoothstep(1.25, 0.35, length(p) * 1.15);     // vignette
    frag = vec4(max(col, 0.0) * u_dim, 1.0);
}
"""
    }

    private var ready = false
    private var feedbackProg = 0
    private var displayProg = 0

    private val tex = IntArray(2)
    private val fbo = IntArray(2)
    private var cur = 0
    private var waveTex = 0

    private var simW = 1
    private var simH = 1
    private var surfaceW = 1
    private var surfaceH = 1
    private var aspect = 1f

    // feedback uniforms
    private var ufPrev = 0; private var ufWave = 0; private var ufAspect = 0
    private var ufTime = 0; private var ufLow = 0; private var ufMid = 0; private var ufHigh = 0
    private var ufPulse = 0; private var ufDecay = 0; private var ufZoom = 0
    private var ufRot = 0; private var ufSwirl = 0; private var ufHue = 0
    // display uniforms
    private var udState = 0; private var udAspect = 0; private var udDim = 0

    private val waveBuf: FloatBuffer = ByteBuffer
        .allocateDirect(WAVE_N * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()

    private var sLow = 0f
    private var sMid = 0f
    private var sHigh = 0f
    private var pulse = 0f
    private var hue = 0f
    private var lastT = -1f

    override fun onCreated() {
        try {
            feedbackProg = ShaderUtil.buildProgram(VERT, FRAG_FEEDBACK)
            displayProg = ShaderUtil.buildProgram(VERT, FRAG_DISPLAY)
        } catch (e: RuntimeException) {
            ready = false
            Log.e(TAG, "Program build failed; disabling Liquid Light scene.", e)
            return
        }
        ufPrev = GLES20.glGetUniformLocation(feedbackProg, "u_prev")
        ufWave = GLES20.glGetUniformLocation(feedbackProg, "u_wave")
        ufAspect = GLES20.glGetUniformLocation(feedbackProg, "u_aspect")
        ufTime = GLES20.glGetUniformLocation(feedbackProg, "u_time")
        ufLow = GLES20.glGetUniformLocation(feedbackProg, "u_low")
        ufMid = GLES20.glGetUniformLocation(feedbackProg, "u_mid")
        ufHigh = GLES20.glGetUniformLocation(feedbackProg, "u_high")
        ufPulse = GLES20.glGetUniformLocation(feedbackProg, "u_pulse")
        ufDecay = GLES20.glGetUniformLocation(feedbackProg, "u_decay")
        ufZoom = GLES20.glGetUniformLocation(feedbackProg, "u_zoom")
        ufRot = GLES20.glGetUniformLocation(feedbackProg, "u_rot")
        ufSwirl = GLES20.glGetUniformLocation(feedbackProg, "u_swirl")
        ufHue = GLES20.glGetUniformLocation(feedbackProg, "u_hue")
        udState = GLES20.glGetUniformLocation(displayProg, "u_state")
        udAspect = GLES20.glGetUniformLocation(displayProg, "u_aspect")
        udDim = GLES20.glGetUniformLocation(displayProg, "u_dim")
        waveTex = createWaveTex()
    }

    override fun onResize(width: Int, height: Int, aspect: Float) {
        surfaceW = width; surfaceH = height
        this.aspect = aspect
        if (feedbackProg == 0) return

        val scale = LONG_EDGE.toFloat() / maxOf(width, height).toFloat()
        simW = (width * scale).toInt().coerceAtLeast(2)
        simH = (height * scale).toInt().coerceAtLeast(2)

        releaseTargets()
        for (i in 0..1) {
            tex[i] = createFloatTex(simW, simH)
            fbo[i] = createFbo(tex[i])
        }
        ready = fbo[0] != 0 && fbo[1] != 0 && waveTex != 0
        if (!ready) { Log.w(TAG, "Targets incomplete; scene disabled."); return }

        cur = 0
        // Clear both buffers so feedback starts from black.
        for (i in 0..1) {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo[i])
            GLES20.glViewport(0, 0, simW, simH)
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        }
    }

    override fun draw(pcm: FloatArray, bands: FloatArray, timeSec: Float, dim: Float) {
        if (!ready) return

        val dt = if (lastT < 0f) 0.016f else (timeSec - lastT).coerceIn(0f, 0.1f)
        lastT = timeSec
        sLow = env(sLow, bands[0], 16f, 4f, dt)
        sMid = env(sMid, bands[1], 13f, 3f, dt)
        sHigh = env(sHigh, bands[2], 22f, 6f, dt)
        pulse = max(pulse - dt * 2.0f, 0f)
        if (bands[0] > pulse) pulse = bands[0]
        hue += dt * (0.03f + sMid * 0.12f)

        uploadWave(pcm)

        val prevFbo = IntArray(1)
        GLES20.glGetIntegerv(GLES20.GL_FRAMEBUFFER_BINDING, prevFbo, 0)

        // --- feedback pass: prev (warped+decayed) + ring + core -> next buffer ---
        val dst = 1 - cur
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo[dst])
        GLES20.glViewport(0, 0, simW, simH)
        GLES20.glUseProgram(feedbackProg)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex[cur])
        GLES20.glUniform1i(ufPrev, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, waveTex)
        GLES20.glUniform1i(ufWave, 1)
        GLES20.glUniform1f(ufAspect, simW.toFloat() / simH.toFloat())
        GLES20.glUniform1f(ufTime, timeSec)
        GLES20.glUniform1f(ufLow, sLow)
        GLES20.glUniform1f(ufMid, sMid)
        GLES20.glUniform1f(ufHigh, sHigh)
        GLES20.glUniform1f(ufPulse, pulse)
        GLES20.glUniform1f(ufDecay, 0.94f)
        GLES20.glUniform1f(ufZoom, 0.994f - pulse * 0.030f)   // zoom inward, stronger on beats
        GLES20.glUniform1f(ufRot, dt * (0.10f + sMid * 0.6f)) // rotate, mids spin faster
        GLES20.glUniform1f(ufSwirl, dt * (0.15f + sHigh * 1.2f))
        GLES20.glUniform1f(ufHue, hue)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 3)
        cur = dst

        // --- display pass back to the renderer's framebuffer ---
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, prevFbo[0])
        GLES20.glViewport(0, 0, surfaceW, surfaceH)
        GLES20.glUseProgram(displayProg)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex[cur])
        GLES20.glUniform1i(udState, 0)
        GLES20.glUniform1f(udAspect, aspect)
        GLES20.glUniform1f(udDim, dim)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 3)

        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
    }

    private fun env(current: Float, target: Float, atk: Float, rel: Float, dt: Float): Float {
        val rate = if (target > current) atk else rel
        return current + (target - current) * (1f - exp(-rate * dt))
    }

    private fun uploadWave(pcm: FloatArray) {
        waveBuf.clear()
        if (pcm.isEmpty()) {
            for (i in 0 until WAVE_N) waveBuf.put(0f)
        } else {
            val step = pcm.size.toFloat() / WAVE_N
            for (i in 0 until WAVE_N) {
                waveBuf.put(pcm[(i * step).toInt().coerceIn(0, pcm.size - 1)])
            }
        }
        waveBuf.position(0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, waveTex)
        GLES30.glTexSubImage2D(
            GLES20.GL_TEXTURE_2D, 0, 0, 0, WAVE_N, 1,
            GLES30.GL_RED, GLES20.GL_FLOAT, waveBuf
        )
    }

    private fun createWaveTex(): Int {
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        val t = ids[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, t)
        GLES30.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, GLES30.GL_R32F, WAVE_N, 1, 0,
            GLES30.GL_RED, GLES20.GL_FLOAT, null
        )
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_REPEAT)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        return t
    }

    private fun createFloatTex(w: Int, h: Int): Int {
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        val t = ids[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, t)
        GLES30.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, GLES30.GL_RGBA16F, w, h, 0,
            GLES20.GL_RGBA, GLES30.GL_HALF_FLOAT, null
        )
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        return t
    }

    private fun createFbo(t: Int): Int {
        val ids = IntArray(1)
        GLES20.glGenFramebuffers(1, ids, 0)
        val f = ids[0]
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, f)
        GLES20.glFramebufferTexture2D(
            GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, t, 0
        )
        val status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        if (status != GLES20.GL_FRAMEBUFFER_COMPLETE) {
            Log.w(TAG, "FBO incomplete: 0x${Integer.toHexString(status)}")
            return 0
        }
        return f
    }

    private fun releaseTargets() {
        for (i in 0..1) {
            if (fbo[i] != 0) { GLES20.glDeleteFramebuffers(1, intArrayOf(fbo[i]), 0); fbo[i] = 0 }
            if (tex[i] != 0) { GLES20.glDeleteTextures(1, intArrayOf(tex[i]), 0); tex[i] = 0 }
        }
    }
}
