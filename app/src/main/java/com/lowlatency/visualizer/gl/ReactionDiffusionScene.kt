package com.lowlatency.visualizer.gl

import android.opengl.GLES20
import android.opengl.GLES30
import android.util.Log
import com.lowlatency.visualizer.BeatPulse
import kotlin.random.Random

/**
 * "Reaction–Diffusion" — a Gray-Scott chemical simulation run on the GPU via FBO
 * ping-pong. Two chemicals (A, B) diffuse and react, spontaneously forming
 * organic Turing patterns (spots, stripes, mazes, coral) that grow and morph.
 *
 * Pipeline each frame (all at a reduced sim resolution for speed + soft look):
 *   1. K simulation sub-steps: read the previous state texture, apply the
 *      Gray-Scott update, write the next — ping-ponging two RGBA16F targets.
 *   2. Display pass: map chemical B through a palette to the screen framebuffer
 *      (which is the bloom offscreen buffer when post-processing is on, so we
 *      restore whatever framebuffer was bound on entry).
 *
 * Audio drives it without destabilising the reaction: mids/highs nudge the
 * feed/kill rates within a known self-sustaining band (morphing the pattern
 * regime), and each beat injects a fresh blob of B that blooms outward.
 */
class ReactionDiffusionScene : GlScene {

    companion object {
        private const val TAG = "ReactionDiffusion"
        private const val MAX_SEEDS = 6
        private const val SIM_STEPS = 14          // sim sub-steps per rendered frame
        private const val SIM_LONG_EDGE = 384     // longest sim-texture edge (perf + softness)

        // Fullscreen triangle, attributeless (gl_VertexID) — shared by all passes.
        private const val VERT = """#version 300 es
out vec2 v_uv;
void main() {
    vec2 p = vec2(gl_VertexID == 1 ? 3.0 : -1.0, gl_VertexID == 2 ? 3.0 : -1.0);
    v_uv = p * 0.5 + 0.5;
    gl_Position = vec4(p, 0.0, 1.0);
}
"""

        // Seed the substrate: A=1 everywhere, plus a scatter of B blobs to kick
        // the reaction off so there's structure before any audio arrives.
        private const val FRAG_INIT = """#version 300 es
precision highp float;
in vec2 v_uv;
uniform float u_seed;
out vec4 frag;
float hash(vec2 p) { return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453); }
void main() {
    float b = 0.0;
    for (int i = 0; i < 14; i++) {
        vec2 c = vec2(hash(vec2(float(i), u_seed)), hash(vec2(float(i) + 9.0, u_seed)));
        c = 0.18 + c * 0.64;                 // keep blobs in the central region
        vec2 d = v_uv - c;
        b += exp(-dot(d, d) / 0.0008);
    }
    frag = vec4(1.0, clamp(b, 0.0, 1.0), 0.0, 1.0);
}
"""

        // Gray-Scott update with a 3x3 Laplacian. B is injected near recent beat
        // seeds on the first sub-step of each frame only.
        private const val FRAG_SIM = """#version 300 es
precision highp float;
in vec2 v_uv;
uniform sampler2D u_prev;
uniform vec2  u_texel;
uniform float u_feed;
uniform float u_kill;
uniform float u_da;
uniform float u_db;
uniform float u_dt;
uniform int   u_inject;
uniform vec3  u_seeds[${MAX_SEEDS}];   // xy = uv centre, z = strength
out vec4 frag;

vec2 stateAt(vec2 uv) { return texture(u_prev, uv).rg; }

void main() {
    vec2 uv = v_uv;
    vec2 c = stateAt(uv);

    vec2 lap = c * -1.0;
    lap += stateAt(uv + u_texel * vec2(-1.0,  0.0)) * 0.2;
    lap += stateAt(uv + u_texel * vec2( 1.0,  0.0)) * 0.2;
    lap += stateAt(uv + u_texel * vec2( 0.0, -1.0)) * 0.2;
    lap += stateAt(uv + u_texel * vec2( 0.0,  1.0)) * 0.2;
    lap += stateAt(uv + u_texel * vec2(-1.0, -1.0)) * 0.05;
    lap += stateAt(uv + u_texel * vec2( 1.0, -1.0)) * 0.05;
    lap += stateAt(uv + u_texel * vec2(-1.0,  1.0)) * 0.05;
    lap += stateAt(uv + u_texel * vec2( 1.0,  1.0)) * 0.05;

    float a = c.r;
    float b = c.g;
    float reaction = a * b * b;
    float a2 = a + (u_da * lap.r - reaction + u_feed * (1.0 - a)) * u_dt;
    float b2 = b + (u_db * lap.g + reaction - (u_kill + u_feed) * b) * u_dt;

    if (u_inject == 1) {
        for (int i = 0; i < ${MAX_SEEDS}; i++) {
            float s = u_seeds[i].z;
            if (s <= 0.0) continue;
            vec2 d = uv - u_seeds[i].xy;
            b2 += s * exp(-dot(d, d) / 0.0009) * 0.9;
        }
    }

    frag = vec4(clamp(a2, 0.0, 1.0), clamp(b2, 0.0, 1.0), 0.0, 1.0);
}
"""

        // Map chemical B to a drifting palette; dense ridges push past 1.0 for HDR
        // bloom, beats lift the whole field.
        private const val FRAG_DISPLAY = """#version 300 es
precision highp float;
in vec2 v_uv;
uniform sampler2D u_state;
uniform float u_dim;
uniform float u_hue;
uniform float u_env;
out vec4 frag;
const float TAU = 6.2831853;
vec3 pal(float t) {
    return 0.5 + 0.5 * cos(TAU * (vec3(1.0) * t + vec3(0.0, 0.33, 0.60) + u_hue));
}
void main() {
    float b = texture(u_state, v_uv).g;
    float v = smoothstep(0.12, 0.50, b);
    vec3 col = pal(0.25 + b * 0.7);
    col *= (0.12 + v * 1.7 + u_env * 1.4);          // HDR ridges + beat lift
    col += vec3(0.010, 0.012, 0.022) * (1.0 - v);   // faint substrate
    frag = vec4(col * u_dim, 1.0);
}
"""
    }

    private var ready = false
    private var initProg = 0
    private var simProg = 0
    private var displayProg = 0

    private val tex = IntArray(2)
    private val fbo = IntArray(2)
    private var cur = 0

    private var simW = 1
    private var simH = 1
    private var surfaceW = 1
    private var surfaceH = 1

    // init uniforms
    private var uiSeed = 0
    // sim uniforms
    private var usPrev = 0; private var usTexel = 0; private var usFeed = 0
    private var usKill = 0; private var usDa = 0; private var usDb = 0
    private var usDt = 0; private var usInject = 0; private var usSeeds = 0
    // display uniforms
    private var udState = 0; private var udDim = 0; private var udHue = 0; private var udEnv = 0

    // Beat seeds (xy uv, z strength), decayed on the CPU each frame.
    private val seeds = FloatArray(MAX_SEEDS * 3)
    private var lastBeatCount = -1
    private val rng = Random(7)

    // Smoothed feed/kill so the pattern morphs gracefully.
    private var feed = 0.037f
    private var kill = 0.060f
    private var hue = 0f

    override fun onCreated() {
        try {
            initProg = ShaderUtil.buildProgram(VERT, FRAG_INIT)
            simProg = ShaderUtil.buildProgram(VERT, FRAG_SIM)
            displayProg = ShaderUtil.buildProgram(VERT, FRAG_DISPLAY)
        } catch (e: RuntimeException) {
            ready = false
            Log.e(TAG, "Program build failed; disabling Reaction-Diffusion scene.", e)
            return
        }
        uiSeed = GLES20.glGetUniformLocation(initProg, "u_seed")
        usPrev = GLES20.glGetUniformLocation(simProg, "u_prev")
        usTexel = GLES20.glGetUniformLocation(simProg, "u_texel")
        usFeed = GLES20.glGetUniformLocation(simProg, "u_feed")
        usKill = GLES20.glGetUniformLocation(simProg, "u_kill")
        usDa = GLES20.glGetUniformLocation(simProg, "u_da")
        usDb = GLES20.glGetUniformLocation(simProg, "u_db")
        usDt = GLES20.glGetUniformLocation(simProg, "u_dt")
        usInject = GLES20.glGetUniformLocation(simProg, "u_inject")
        usSeeds = GLES20.glGetUniformLocation(simProg, "u_seeds")
        udState = GLES20.glGetUniformLocation(displayProg, "u_state")
        udDim = GLES20.glGetUniformLocation(displayProg, "u_dim")
        udHue = GLES20.glGetUniformLocation(displayProg, "u_hue")
        udEnv = GLES20.glGetUniformLocation(displayProg, "u_env")
    }

    override fun onResize(width: Int, height: Int, aspect: Float) {
        surfaceW = width; surfaceH = height
        if (initProg == 0) return   // build failed

        // Reduced sim resolution: longest edge capped, aspect preserved.
        val scale = SIM_LONG_EDGE.toFloat() / maxOf(width, height).toFloat()
        simW = (width * scale).toInt().coerceAtLeast(2)
        simH = (height * scale).toInt().coerceAtLeast(2)

        releaseTargets()
        for (i in 0..1) {
            tex[i] = createFloatTex(simW, simH)
            fbo[i] = createFbo(tex[i])
        }
        ready = fbo[0] != 0 && fbo[1] != 0
        if (!ready) { Log.w(TAG, "Sim FBOs incomplete; scene disabled."); return }

        cur = 0
        seeds.fill(0f)
        seedInitialState()
    }

    /** Render the initial substrate (A=1 + B blobs) into the current target. */
    private fun seedInitialState() {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo[cur])
        GLES20.glViewport(0, 0, simW, simH)
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glUseProgram(initProg)
        GLES20.glUniform1f(uiSeed, rng.nextFloat() * 100f)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 3)
    }

    override fun draw(pcm: FloatArray, bands: FloatArray, magnitudes: FloatArray, peaks: FloatArray, timeSec: Float, dim: Float, sharedBuffer: java.nio.ByteBuffer?) {
        if (!ready) return
        val low = bands[0]; val mid = bands[1]; val high = bands[2]

        // Nudge feed/kill inside a self-sustaining band so the regime morphs
        // (spots <-> stripes <-> maze) without ever dying out. Heavily smoothed.
        val targetFeed = 0.034f + 0.020f * mid
        val targetKill = 0.057f + 0.008f * high
        feed += (targetFeed - feed) * 0.03f
        kill += (targetKill - kill) * 0.03f
        hue += 0.0009f + high * 0.004f

        // Decay existing beat seeds; register a new one on each beat.
        for (i in 0 until MAX_SEEDS) {
            val s = seeds[i * 3 + 2]
            seeds[i * 3 + 2] = if (s > 0.02f) s * 0.78f else 0f
        }
        val beat = BeatPulse.beatCount
        if (beat != lastBeatCount) {
            lastBeatCount = beat
            var slot = 0; var min = Float.MAX_VALUE
            for (i in 0 until MAX_SEEDS) {
                val s = seeds[i * 3 + 2]; if (s < min) { min = s; slot = i }
            }
            seeds[slot * 3] = 0.12f + rng.nextFloat() * 0.76f
            seeds[slot * 3 + 1] = 0.12f + rng.nextFloat() * 0.76f
            seeds[slot * 3 + 2] = 1f
        }

        // Remember the framebuffer the renderer bound (bloom buffer or screen).
        val prevFbo = IntArray(1)
        GLES20.glGetIntegerv(GLES20.GL_FRAMEBUFFER_BINDING, prevFbo, 0)

        // --- Simulation sub-steps ---
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glViewport(0, 0, simW, simH)
        GLES20.glUseProgram(simProg)
        GLES20.glUniform2f(usTexel, 1f / simW, 1f / simH)
        GLES20.glUniform1f(usFeed, feed)
        GLES20.glUniform1f(usKill, kill)
        GLES20.glUniform1f(usDa, 1.0f)
        GLES20.glUniform1f(usDb, 0.5f)
        GLES20.glUniform1f(usDt, 1.0f)
        GLES20.glUniform3fv(usSeeds, MAX_SEEDS, seeds, 0)

        for (step in 0 until SIM_STEPS) {
            val dst = 1 - cur
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo[dst])
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex[cur])
            GLES20.glUniform1i(usPrev, 0)
            GLES20.glUniform1i(usInject, if (step == 0) 1 else 0)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 3)
            cur = dst
        }

        // --- Display pass back to the renderer's framebuffer ---
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, prevFbo[0])
        GLES20.glViewport(0, 0, surfaceW, surfaceH)
        GLES20.glUseProgram(displayProg)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex[cur])
        GLES20.glUniform1i(udState, 0)
        GLES20.glUniform1f(udDim, dim)
        GLES20.glUniform1f(udHue, hue)
        GLES20.glUniform1f(udEnv, BeatPulse.envelope)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 3)

        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
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
        // LINEAR: stencil taps hit exact texel centres (so it's exact for the sim)
        // while the display upscale stays smooth.
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
