package com.lowlatency.visualizer.gl

import android.opengl.GLES20
import android.opengl.GLES30
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import com.lowlatency.visualizer.BeatPulse
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * "Slipstream" — a euphoric particle storm riding a musical wind, painted onto a
 * canvas that remembers.
 *
 * Three systems fused, one per inspiration:
 *
 *  1. **Feedback canvas (the MilkDrop DNA).** A half-res FP16 buffer that never
 *     clears: each frame it re-samples *itself* through a warp — slow outward
 *     zoom, rotation, and a curl-noise churn whose strength is the bass — then
 *     fades slightly and takes new paint on top. Everything drawn leaves silk
 *     trails that curve and dissolve.
 *  2. **The storm (the Tetris Effect DNA).** 65,536 particles simulated wholly
 *     on the GPU (position/velocity in ping-pong FP16 textures, integrated by a
 *     fragment pass). They ride a two-octave curl-noise flow field steered by
 *     the music — bass deepens the central vortex, mids/treble drive wind and
 *     shimmer — and every strong beat detonates a radial burst through the field.
 *  3. **Zone shifts (the TE DNA).** Five hand-curated multi-colour palettes
 *     crossfade on a slow cycle; each boundary arrives with an implosion — the
 *     whole field is pulled home and re-erupts in the next zone's colours.
 *
 * Speed is heat: fast particles burn white through the bloom, slow ones cool
 * into the palette. The raw PCM waveform is stamped faintly into the feedback so
 * the live signal ghosts through the trails. Per-frame CPU work is a handful of
 * scalars — the sim is a 256×256 fragment pass and the canvas runs at half
 * resolution, so the whole scene is cheaper per pixel than a ray-march.
 */
class SlipstreamScene : GlScene {

    companion object {
        private const val TAG = "SlipstreamScene"
        internal const val STATE_DIM = 256              // sim texture side (65,536 particles)
        private const val PARTICLES = STATE_DIM * STATE_DIM
        private const val RIBBON_PTS = 256
        private const val ZONE_SEC = 24f                // seconds per colour zone

        // Auto-gain target for the PCM ribbon stamp; follower lives in [WaveformAgc].
        private const val AGC_TARGET = 2.5f

        // Five zones, three stops each (Tetris-Effect bold, not rainbow mush).
        private val ZONES = arrayOf(
            floatArrayOf(1.00f, 0.35f, 0.38f, 0.55f, 0.20f, 1.00f, 0.10f, 0.30f, 1.00f), // neon dusk
            floatArrayOf(0.00f, 0.90f, 0.90f, 0.05f, 0.55f, 0.65f, 1.00f, 0.25f, 0.85f), // reef
            floatArrayOf(1.05f, 0.80f, 0.25f, 0.95f, 0.15f, 0.15f, 1.20f, 1.10f, 0.95f), // solar
            floatArrayOf(0.90f, 0.97f, 1.10f, 0.35f, 0.70f, 1.05f, 0.60f, 0.35f, 1.00f), // ice
            floatArrayOf(0.55f, 1.00f, 0.25f, 0.05f, 0.75f, 0.45f, 1.00f, 0.85f, 0.30f), // jungle
        )
    }

    // --- GL objects -------------------------------------------------------
    private var simProg = 0; private var warpProg = 0; private var particleProg = 0
    private var ribbonProg = 0; private var compositeProg = 0
    private var quadVbo = 0; private var idVbo = 0; private var ribbonVbo = 0
    private val simTex = IntArray(2); private val simFbo = IntArray(2)
    private val cvsTex = IntArray(2); private val cvsFbo = IntArray(2)
    private var simRead = 0; private var cvsRead = 0
    private var broken = false

    // Sim uniforms.
    private var sState = 0; private var sDt = 0; private var sTime = 0; private var sBass = 0
    private var sTreble = 0; private var sBurst = 0; private var sImplode = 0
    // Warp uniforms.
    private var wCanvas = 0; private var wDt = 0; private var wTime = 0
    private var wBass = 0; private var wAspect = 0
    // Particle uniforms.
    private var pState = 0; private var pVa = 0; private var pPx = 0
    private var pPal = 0; private var pPalMix = 0; private var pEnv = 0
    // Ribbon uniforms.
    private var rTime = 0; private var rCol = 0
    // Composite uniforms.
    private var cCanvas = 0; private var cDim = 0

    private val ribbonBuf: FloatBuffer = ByteBuffer
        .allocateDirect(RIBBON_PTS * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
    private val palBuf = FloatArray(18)
    private val prevFbo = IntArray(1)
    private val prevViewport = IntArray(4)

    private var surfaceW = 0; private var surfaceH = 0
    private var viewAspect = 1f
    private var lastT = -1f
    private var burstAt = -99f
    private var lastEnv = 0f
    private val agc = WaveformAgc(target = AGC_TARGET)

    override fun onCreated() {
        simProg = ShaderUtil.buildProgram(SlipstreamShaders.QUAD_VS, SlipstreamShaders.SIM_FS)
        warpProg = ShaderUtil.buildProgram(SlipstreamShaders.QUAD_VS, SlipstreamShaders.WARP_FS)
        particleProg = ShaderUtil.buildProgram(SlipstreamShaders.PARTICLE_VS, SlipstreamShaders.PARTICLE_FS)
        ribbonProg = ShaderUtil.buildProgram(SlipstreamShaders.RIBBON_VS, SlipstreamShaders.RIBBON_FS)
        compositeProg = ShaderUtil.buildProgram(SlipstreamShaders.QUAD_VS, SlipstreamShaders.COMPOSITE_FS)
        locateUniforms()
        initBuffers()
        initSimState()
    }

    private fun locateUniforms() {
        sState = loc(simProg, "u_state"); sDt = loc(simProg, "u_dt"); sTime = loc(simProg, "u_time")
        sBass = loc(simProg, "u_bass"); sTreble = loc(simProg, "u_treble")
        sBurst = loc(simProg, "u_burst"); sImplode = loc(simProg, "u_implode")
        wCanvas = loc(warpProg, "u_canvas"); wDt = loc(warpProg, "u_dt"); wTime = loc(warpProg, "u_time")
        wBass = loc(warpProg, "u_bass"); wAspect = loc(warpProg, "u_aspect")
        pState = loc(particleProg, "u_state"); pVa = loc(particleProg, "u_va"); pPx = loc(particleProg, "u_px")
        pPal = loc(particleProg, "u_pal"); pPalMix = loc(particleProg, "u_palMix"); pEnv = loc(particleProg, "u_env")
        rTime = loc(ribbonProg, "u_time"); rCol = loc(ribbonProg, "u_col")
        cCanvas = loc(compositeProg, "u_canvas"); cDim = loc(compositeProg, "u_dim")
    }

    private fun loc(prog: Int, name: String) = GLES20.glGetUniformLocation(prog, name)

    private fun initBuffers() {
        val ids = IntArray(3)
        GLES20.glGenBuffers(3, ids, 0)
        quadVbo = ids[0]; idVbo = ids[1]; ribbonVbo = ids[2]

        val quad = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
        val qbuf = ByteBuffer.allocateDirect(quad.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer().put(quad).also { it.position(0) }
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, quadVbo)
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, quad.size * 4, qbuf, GLES20.GL_STATIC_DRAW)

        // One float id per particle (safe portable stand-in for gl_VertexID).
        val idArr = FloatArray(PARTICLES) { it.toFloat() }
        val ibuf = ByteBuffer.allocateDirect(idArr.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer().put(idArr).also { it.position(0) }
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, idVbo)
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, idArr.size * 4, ibuf, GLES20.GL_STATIC_DRAW)

        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, ribbonVbo)
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, RIBBON_PTS * 4, null, GLES20.GL_DYNAMIC_DRAW)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
    }

    /** Seed the particle state: a spinning ring around the vortex heart. */
    private fun initSimState() {
        val rnd = Random(7)
        val init = FloatArray(STATE_DIM * STATE_DIM * 4)
        var i = 0
        for (p in 0 until PARTICLES) {
            val a = rnd.nextFloat() * 6.28318f
            val r = 0.15f + 0.9f * rnd.nextFloat()
            init[i++] = cos(a) * r
            init[i++] = sin(a) * r
            init[i++] = -sin(a) * 0.3f
            init[i++] = cos(a) * 0.3f
        }
        val buf = ByteBuffer.allocateDirect(init.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer().put(init).also { it.position(0) }
        for (k in 0..1) {
            simTex[k] = makeTexture(STATE_DIM, STATE_DIM, GLES20.GL_NEAREST, if (k == 0) buf else null)
            simFbo[k] = makeFbo(simTex[k])
        }
    }

    private fun makeTexture(w: Int, h: Int, filter: Int, data: FloatBuffer?): Int {
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ids[0])
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, filter)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, filter)
        GLES30.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, GLES30.GL_RGBA16F,
            w, h, 0, GLES20.GL_RGBA, GLES20.GL_FLOAT, data
        )
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        return ids[0]
    }

    private fun makeFbo(tex: Int): Int {
        val ids = IntArray(1)
        GLES20.glGenFramebuffers(1, ids, 0)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, ids[0])
        GLES20.glFramebufferTexture2D(
            GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, tex, 0
        )
        val status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)
        if (status != GLES20.GL_FRAMEBUFFER_COMPLETE) {
            Log.e(TAG, "FBO incomplete ($status) — FP16 render targets unsupported; scene disabled")
            broken = true
        }
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        return ids[0]
    }

    override fun onResize(width: Int, height: Int, aspect: Float) {
        surfaceW = width; surfaceH = height
        viewAspect = if (height > 0) width.toFloat() / height else 1f
        // (Re)build the half-res feedback canvas pair for the new surface.
        for (k in 0..1) {
            if (cvsTex[k] != 0) {
                GLES20.glDeleteTextures(1, cvsTex, k); GLES20.glDeleteFramebuffers(1, cvsFbo, k)
            }
            cvsTex[k] = makeTexture(width / 2, height / 2, GLES20.GL_LINEAR, null)
            cvsFbo[k] = makeFbo(cvsTex[k])
        }
    }

    override fun draw(pcm: FloatArray, bands: FloatArray, timeSec: Float, dim: Float) {
        if (broken || surfaceW == 0) return
        if (lastT < 0f) lastT = timeSec
        val dt = (timeSec - lastT).coerceIn(0f, 0.1f)
        lastT = timeSec

        val env = BeatPulse.envelope
        if (env > 0.55f && lastEnv <= 0.55f) burstAt = timeSec
        lastEnv = env
        val burst = kotlin.math.exp(-(timeSec - burstAt).coerceAtLeast(0f) * 5.5f)
        updateZones(timeSec)

        // Everything below renders into our own FBOs — save the caller's target.
        GLES20.glGetIntegerv(GLES30.GL_FRAMEBUFFER_BINDING, prevFbo, 0)
        GLES20.glGetIntegerv(GLES20.GL_VIEWPORT, prevViewport, 0)
        GLES20.glDisable(GLES20.GL_BLEND)

        runSim(dt, timeSec, bands, burst)
        runWarp(dt, timeSec, bands[0])
        stampParticles(env)
        stampRibbon(pcm, timeSec)
        cvsRead = 1 - cvsRead

        // Back to the caller's framebuffer for the final composite.
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, prevFbo[0])
        GLES20.glViewport(prevViewport[0], prevViewport[1], prevViewport[2], prevViewport[3])
        composite(dim)
    }

    /** Zone palettes: crossfade near each boundary; the implosion rides the seam. */
    private var implode = 0f
    private fun updateZones(timeSec: Float) {
        val zi = (timeSec / ZONE_SEC).toInt() % ZONES.size
        val zn = (zi + 1) % ZONES.size
        val frac = (timeSec % ZONE_SEC) / ZONE_SEC
        val mixF = ((frac - 0.875f) / 0.125f).coerceIn(0f, 1f)
        for (k in 0 until 9) { palBuf[k] = ZONES[zi][k]; palBuf[9 + k] = ZONES[zn][k] }
        palMix = mixF * mixF * (3f - 2f * mixF)
        // Implosion pulse over the last ~1.5s of the zone: pull home, then release.
        implode = if (frac > 0.94f) sin(((frac - 0.94f) / 0.06f) * Math.PI.toFloat()) else 0f
    }

    private var palMix = 0f

    private fun runSim(dt: Float, timeSec: Float, bands: FloatArray, burst: Float) {
        val write = 1 - simRead
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, simFbo[write])
        GLES20.glViewport(0, 0, STATE_DIM, STATE_DIM)
        GLES20.glUseProgram(simProg)
        bindTex(0, simTex[simRead]); GLES20.glUniform1i(sState, 0)
        GLES20.glUniform1f(sDt, dt)
        GLES20.glUniform1f(sTime, timeSec)
        GLES20.glUniform1f(sBass, bands[0])
        GLES20.glUniform1f(sTreble, bands[2])
        GLES20.glUniform1f(sBurst, burst)
        GLES20.glUniform1f(sImplode, implode)
        drawQuad()
        simRead = write
    }

    private fun runWarp(dt: Float, timeSec: Float, bass: Float) {
        val write = 1 - cvsRead
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, cvsFbo[write])
        GLES20.glViewport(0, 0, surfaceW / 2, surfaceH / 2)
        GLES20.glUseProgram(warpProg)
        bindTex(0, cvsTex[cvsRead]); GLES20.glUniform1i(wCanvas, 0)
        GLES20.glUniform1f(wDt, dt)
        GLES20.glUniform1f(wTime, timeSec)
        GLES20.glUniform1f(wBass, bass)
        GLES20.glUniform1f(wAspect, viewAspect)
        drawQuad()
    }

    private fun stampParticles(env: Float) {
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE)
        GLES20.glUseProgram(particleProg)
        bindTex(0, simTex[simRead]); GLES20.glUniform1i(pState, 0)
        GLES20.glUniform1f(pVa, viewAspect.coerceIn(0.5f, 2f))
        GLES20.glUniform1f(pPx, (surfaceH / 2f) / 800f)
        GLES20.glUniform3fv(pPal, 6, palBuf, 0)
        GLES20.glUniform1f(pPalMix, palMix)
        GLES20.glUniform1f(pEnv, env)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, idVbo)
        GLES20.glEnableVertexAttribArray(0)
        GLES20.glVertexAttribPointer(0, 1, GLES20.GL_FLOAT, false, 0, 0)
        GLES30.glDrawArrays(GLES20.GL_POINTS, 0, PARTICLES)
        GLES20.glDisableVertexAttribArray(0)
    }

    private fun stampRibbon(pcm: FloatArray, timeSec: Float) {
        val gain = agc.update(pcm)
        val stride = (pcm.size / RIBBON_PTS).coerceAtLeast(1)
        ribbonBuf.clear()
        for (i in 0 until RIBBON_PTS) {
            val g = pcm[(i * stride).coerceAtMost(pcm.size - 1)] * gain
            ribbonBuf.put(g / (1f + abs(g)))
        }
        ribbonBuf.position(0)
        GLES20.glUseProgram(ribbonProg)
        GLES20.glUniform1f(rTime, timeSec)
        // Ribbon ghosts in the zone's mid colour, kept faint.
        val m = palMix
        GLES20.glUniform3f(
            rCol,
            (palBuf[3] * (1 - m) + palBuf[12] * m) * 0.20f,
            (palBuf[4] * (1 - m) + palBuf[13] * m) * 0.20f,
            (palBuf[5] * (1 - m) + palBuf[14] * m) * 0.20f,
        )
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, ribbonVbo)
        GLES20.glBufferSubData(GLES20.GL_ARRAY_BUFFER, 0, RIBBON_PTS * 4, ribbonBuf)
        GLES20.glEnableVertexAttribArray(0)
        GLES20.glVertexAttribPointer(0, 1, GLES20.GL_FLOAT, false, 0, 0)
        GLES20.glLineWidth(1f)
        GLES30.glDrawArrays(GLES30.GL_LINE_STRIP, 0, RIBBON_PTS)
        GLES20.glDisableVertexAttribArray(0)
        GLES20.glDisable(GLES20.GL_BLEND)
    }

    private fun composite(dim: Float) {
        GLES20.glUseProgram(compositeProg)
        bindTex(0, cvsTex[cvsRead]); GLES20.glUniform1i(cCanvas, 0)
        GLES20.glUniform1f(cDim, dim)
        drawQuad()
    }

    private fun drawQuad() {
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, quadVbo)
        GLES20.glEnableVertexAttribArray(0)
        GLES20.glVertexAttribPointer(0, 2, GLES20.GL_FLOAT, false, 0, 0)
        GLES30.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(0)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
    }

    private fun bindTex(unit: Int, tex: Int) {
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + unit)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex)
    }

    override fun onDeactivate() {
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
    }
}

/**
 * Slipstream's GLSL, kept out of the scene class so the class stays readable
 * (and under the size guard). Sim + warp + stamp + composite passes.
 */
private object SlipstreamShaders {

    const val QUAD_VS = """#version 300 es
        layout(location = 0) in vec2 aPos;
        out vec2 v_uv;
        void main() {
            v_uv = aPos * 0.5 + 0.5;
            gl_Position = vec4(aPos, 0.0, 1.0);
        }
    """

    /** Particle physics: curl-noise wind + bass vortex + beat bursts + implosion. */
    const val SIM_FS = """#version 300 es
        precision highp float;
        uniform sampler2D u_state;      // xy = pos, zw = vel
        uniform float u_dt;
        uniform float u_time;
        uniform float u_bass;
        uniform float u_treble;
        uniform float u_burst;          // beat impulse 1 -> 0
        uniform float u_implode;        // zone-seam pull 0..1
        in vec2 v_uv;
        out vec4 o_state;

        float hash(vec2 q) { return fract(sin(dot(q, vec2(127.1, 311.7))) * 43758.5453); }

        float vnoise(vec2 q) {
            vec2 i = floor(q);
            vec2 f = fract(q);
            f = f * f * (3.0 - 2.0 * f);
            return mix(mix(hash(i), hash(i + vec2(1.0, 0.0)), f.x),
                       mix(hash(i + vec2(0.0, 1.0)), hash(i + vec2(1.0, 1.0)), f.x), f.y);
        }

        vec2 curl(vec2 q) {
            float e = 0.15;
            return vec2(
                vnoise(q + vec2(0.0, e)) - vnoise(q - vec2(0.0, e)),
                vnoise(q - vec2(e, 0.0)) - vnoise(q + vec2(e, 0.0))
            ) / (2.0 * e);
        }

        void main() {
            vec4 s = texture(u_state, v_uv);
            vec2 pos = s.xy;
            vec2 vel = s.zw;

            // Two-octave musical wind: treble adds shimmer to the fine octave.
            vec2 flow = curl(pos * 1.8 + vec2(u_time * 0.05, -u_time * 0.03));
            flow += 0.5 * curl(pos * 4.2 - vec2(u_time * 0.07, u_time * 0.04));

            float r = length(pos) + 1e-4;
            vec2 rhat = pos / r;
            vec2 tvec = vec2(-rhat.y, rhat.x);

            vec2 force = flow * (0.55 + u_treble * 0.9);
            force += tvec * (0.35 + u_bass * 1.6) * smoothstep(1.6, 0.15, r);   // vortex
            force -= rhat * 0.22 * u_bass * smoothstep(0.25, 0.9, r);           // bass pull
            force += rhat * u_burst * 3.0 * exp(-r * 1.4);                      // beat burst
            force -= rhat * u_implode * 2.4;                                    // zone implosion

            vel = vel * exp(-u_dt * 1.9) + force * u_dt * 1.4;
            float spd = length(vel);
            if (spd > 1.6) vel *= 1.6 / spd;
            pos += vel * u_dt;

            // Recycle: out of bounds, or a slow stochastic respawn keeps the
            // heart of the vortex fed. Reborn on a spinning ring.
            float reseed = hash(v_uv * 37.1 + floor(u_time * 0.4));
            if (abs(pos.x) > 1.75 || abs(pos.y) > 1.75 || reseed > 0.995) {
                float a = hash(v_uv + u_time * 0.013) * 6.28318;
                float rr = 0.15 + 0.45 * hash(v_uv.yx + u_time * 0.007);
                pos = vec2(cos(a), sin(a)) * rr;
                vel = vec2(-sin(a), cos(a)) * (0.25 + 0.35 * hash(v_uv * 3.3));
            }
            o_state = vec4(pos, vel);
        }
    """

    /** The memory: re-sample the canvas through zoom + rotation + bass churn, faded. */
    const val WARP_FS = """#version 300 es
        precision highp float;
        uniform sampler2D u_canvas;
        uniform float u_dt;
        uniform float u_time;
        uniform float u_bass;
        uniform float u_aspect;
        in vec2 v_uv;
        out vec4 fragColor;

        float hash(vec2 q) { return fract(sin(dot(q, vec2(127.1, 311.7))) * 43758.5453); }

        float vnoise(vec2 q) {
            vec2 i = floor(q);
            vec2 f = fract(q);
            f = f * f * (3.0 - 2.0 * f);
            return mix(mix(hash(i), hash(i + vec2(1.0, 0.0)), f.x),
                       mix(hash(i + vec2(0.0, 1.0)), hash(i + vec2(1.0, 1.0)), f.x), f.y);
        }

        vec2 curl(vec2 q) {
            float e = 0.2;
            return vec2(
                vnoise(q + vec2(0.0, e)) - vnoise(q - vec2(0.0, e)),
                vnoise(q - vec2(e, 0.0)) - vnoise(q + vec2(e, 0.0))
            ) / (2.0 * e);
        }

        void main() {
            vec2 c = v_uv - 0.5;
            c.x *= u_aspect;                              // aspect-true space
            // Outward echo (sample inward): trails expand away from the vortex.
            float rot = u_dt * (0.06 + u_bass * 0.10);
            float zoom = exp(-u_dt * 0.12 * (0.4 + u_bass));
            c = mat2(cos(rot), -sin(rot), sin(rot), cos(rot)) * c * zoom;
            // Bass churn: the canvas itself becomes turbulent when the low end hits.
            c += curl(c * 3.0 + u_time * 0.06) * u_dt * (0.03 + u_bass * 0.25);
            c.x /= u_aspect;
            vec2 uvw = c + 0.5;
            float inside = step(0.0, uvw.x) * step(uvw.x, 1.0) * step(0.0, uvw.y) * step(uvw.y, 1.0);
            vec3 prev = texture(u_canvas, uvw).rgb * exp(-u_dt * 1.8) * inside;
            // Hue-preserving ceiling: additive feedback can otherwise run away to
            // white when the field clusters (steady state ~ input / (1 - fade)).
            // A clamp is idempotent, so this stays frame-rate independent.
            float m = max(prev.r, max(prev.g, prev.b));
            if (m > 1.5) prev *= 1.5 / m;
            fragColor = vec4(prev, 1.0);
        }
    """

    const val PARTICLE_VS = """#version 300 es
        layout(location = 0) in float aId;
        uniform sampler2D u_state;
        uniform float u_va;             // aspect for world -> NDC
        uniform float u_px;             // point-size scale for the canvas
        uniform vec3  u_pal[6];         // stops 0-2 = zone A, 3-5 = zone B
        uniform float u_palMix;
        uniform float u_env;
        out vec3 v_col;

        void main() {
            ivec2 st = ivec2(int(mod(aId, ${SlipstreamScene.STATE_DIM}.0)),
                             int(aId / ${SlipstreamScene.STATE_DIM}.0));
            vec4 s = texelFetch(u_state, st, 0);
            gl_Position = vec4(s.x / u_va, s.y, 0.0, 1.0);

            float spd = length(s.zw);
            float hue = fract(aId * 0.61803);
            vec3 a = mix(u_pal[0], u_pal[3], u_palMix);
            vec3 b = mix(u_pal[1], u_pal[4], u_palMix);
            vec3 c = mix(u_pal[2], u_pal[5], u_palMix);
            vec3 col = hue < 0.5 ? mix(a, b, hue * 2.0) : mix(b, c, hue * 2.0 - 1.0);
            // Speed is heat: the fast ones burn white through the bloom.
            col = mix(col, vec3(1.0, 1.05, 1.15), smoothstep(0.5, 1.6, spd));
            v_col = col * (0.15 + spd * 0.4) * (0.8 + u_env * 0.4);
            gl_PointSize = clamp((1.2 + spd * 3.0) * u_px, 1.0, 7.0);
        }
    """

    const val PARTICLE_FS = """#version 300 es
        precision highp float;
        in vec3 v_col;
        out vec4 fragColor;
        void main() {
            float d2 = length(gl_PointCoord * 2.0 - 1.0);
            fragColor = vec4(v_col * smoothstep(1.0, 0.15, d2), 1.0);
        }
    """

    /** Faint live-PCM ribbon wandering the canvas — the raw signal ghosting through. */
    const val RIBBON_VS = """#version 300 es
        layout(location = 0) in float aSamp;
        uniform float u_time;
        void main() {
            float x = float(gl_VertexID) / ${SlipstreamScene.STATE_DIM}.0 * 2.0 - 1.0;
            float y = aSamp * 0.22 + sin(u_time * 0.09) * 0.35;
            gl_Position = vec4(x * 0.98, y, 0.0, 1.0);
        }
    """

    const val RIBBON_FS = """#version 300 es
        precision highp float;
        uniform vec3 u_col;
        out vec4 fragColor;
        void main() { fragColor = vec4(u_col, 1.0); }
    """

    const val COMPOSITE_FS = """#version 300 es
        precision highp float;
        uniform sampler2D u_canvas;
        uniform float u_dim;
        in vec2 v_uv;
        out vec4 fragColor;
        void main() {
            vec3 col = texture(u_canvas, v_uv).rgb;
            fragColor = vec4(col * u_dim, 1.0);
        }
    """
}
