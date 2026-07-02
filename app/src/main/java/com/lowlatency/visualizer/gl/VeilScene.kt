package com.lowlatency.visualizer.gl

import android.opengl.GLES20
import android.opengl.GLES30
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import com.lowlatency.visualizer.BeatPulse
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * "Veil" — a silk membrane suspended in the dark, running a real wave equation.
 * Music doesn't set its shape; music *strikes* it, and the fabric remembers.
 *
 * The sheet is a GPU simulation (height + velocity in ping-pong FP16 textures,
 * integrated at a fixed 240 Hz substep for frame-rate-independent, CFL-stable
 * waves). Three ways in for the audio:
 *
 *  - **The raw PCM waveform drives the bottom edge as a live boundary
 *    condition** — the signal physically enters the silk and rolls through it
 *    as a wavefront.
 *  - **Spectrum transients strike a "piano line" across the sheet** — each bin
 *    owns an x position (bass left, treble right); a kick drops a stone in the
 *    pond, a hat scatters small rings, and the ripples interfere and reflect.
 *  - **Beats tighten the fabric** — wave speed rides the global envelope, so
 *    ripples visibly quicken on the groove; sustained tones set up standing
 *    waves; silence lets the sheet ring down to near-stillness.
 *
 * Rendered as a fine wireframe (the proven displaced-grid pattern) with a
 * light-is-motion model: brightness rides wave *velocity* and *curvature*, so
 * glowing crests roll through near-black fabric, with a satin sheen from a fixed
 * key light. Low grazing orbit camera. Deep-indigo -> cyan -> white-hot palette.
 */
class VeilScene : GlScene {

    companion object {
        private const val TAG = "VeilScene"
        internal const val SIM_W = 192          // membrane sim resolution
        internal const val SIM_H = 128
        private const val GRID_W = 160          // render wireframe density
        private const val GRID_D = 110
        private const val PCM_PTS = 256
        private const val BINS = 128
        private const val DT_SIM = 1f / 240f    // fixed physics substep (CFL-stable)
        private const val MAX_SUBSTEPS = 4

        // Auto-gain for the PCM edge drive.
        private const val AGC_TARGET = 2.2f
        private const val AGC_FLOOR = 0.03f
        private const val AGC_SMOOTH = 0.08f
    }

    private var simProg = 0
    private var meshProg = 0
    private var quadVbo = 0
    private var gridVbo = 0
    private var gridIbo = 0
    private var indexCount = 0
    private val simTex = IntArray(2)
    private val simFbo = IntArray(2)
    private var simRead = 0
    private var pcmTex = 0
    private var specTex = 0
    private var broken = false

    // Sim uniforms.
    private var sState = 0; private var sPcm = 0; private var sSpec = 0; private var sK = 0
    // Mesh uniforms.
    private var mState = 0; private var mCam = 0; private var mProj = 0
    private var mHgt = 0; private var mDim = 0

    private val camBuf = FloatArray(9)
    private val prevFbo = IntArray(1)
    private val prevViewport = IntArray(4)
    private val prevMags = FloatArray(BINS)
    private val specBuf: FloatBuffer = ByteBuffer
        .allocateDirect(BINS * 2 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
    private val pcmBuf: FloatBuffer = ByteBuffer
        .allocateDirect(PCM_PTS * 2 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()

    private var projX = 0.8f; private var projY = 0.8f
    private var lastT = -1f
    private var simAccum = 0f
    private var agc = 8f

    override fun onCreated() {
        simProg = ShaderUtil.buildProgram(VeilShaders.QUAD_VS, VeilShaders.SIM_FS)
        meshProg = ShaderUtil.buildProgram(VeilShaders.MESH_VS, VeilShaders.MESH_FS)
        sState = GLES20.glGetUniformLocation(simProg, "u_state")
        sPcm = GLES20.glGetUniformLocation(simProg, "u_pcm")
        sSpec = GLES20.glGetUniformLocation(simProg, "u_spec")
        sK = GLES20.glGetUniformLocation(simProg, "u_k")
        mState = GLES20.glGetUniformLocation(meshProg, "u_state")
        mCam = GLES20.glGetUniformLocation(meshProg, "u_cam")
        mProj = GLES20.glGetUniformLocation(meshProg, "u_proj")
        mHgt = GLES20.glGetUniformLocation(meshProg, "u_hgt")
        mDim = GLES20.glGetUniformLocation(meshProg, "u_dim")

        initBuffers()
        for (k in 0..1) {
            simTex[k] = makeTexture(SIM_W, SIM_H, GLES20.GL_LINEAR)
            simFbo[k] = makeFbo(simTex[k])
        }
        pcmTex = makeTexture(PCM_PTS, 1, GLES20.GL_LINEAR)
        specTex = makeTexture(BINS, 1, GLES20.GL_LINEAR)
    }

    private fun initBuffers() {
        val ids = IntArray(3)
        GLES20.glGenBuffers(3, ids, 0)
        quadVbo = ids[0]; gridVbo = ids[1]; gridIbo = ids[2]

        val quad = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
        val qbuf = ByteBuffer.allocateDirect(quad.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer().put(quad).also { it.position(0) }
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, quadVbo)
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, quad.size * 4, qbuf, GLES20.GL_STATIC_DRAW)

        // Wireframe grid (uv in [0,1]^2) + line indices in both directions.
        val verts = FloatArray(GRID_W * GRID_D * 2)
        var vi = 0
        for (r in 0 until GRID_D) {
            for (c in 0 until GRID_W) {
                verts[vi++] = c.toFloat() / (GRID_W - 1)
                verts[vi++] = r.toFloat() / (GRID_D - 1)
            }
        }
        val idx = ShortArray(GRID_D * (GRID_W - 1) * 2 + GRID_W * (GRID_D - 1) * 2)
        var ii = 0
        for (r in 0 until GRID_D) {
            for (c in 0 until GRID_W - 1) {
                idx[ii++] = (r * GRID_W + c).toShort(); idx[ii++] = (r * GRID_W + c + 1).toShort()
            }
        }
        for (c in 0 until GRID_W) {
            for (r in 0 until GRID_D - 1) {
                idx[ii++] = (r * GRID_W + c).toShort(); idx[ii++] = ((r + 1) * GRID_W + c).toShort()
            }
        }
        indexCount = ii
        val vbuf = ByteBuffer.allocateDirect(verts.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer().put(verts).also { it.position(0) }
        val ibuf: ShortBuffer = ByteBuffer.allocateDirect(idx.size * 2)
            .order(ByteOrder.nativeOrder()).asShortBuffer().put(idx).also { it.position(0) }
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, gridVbo)
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, verts.size * 4, vbuf, GLES20.GL_STATIC_DRAW)
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, gridIbo)
        GLES20.glBufferData(GLES20.GL_ELEMENT_ARRAY_BUFFER, idx.size * 2, ibuf, GLES20.GL_STATIC_DRAW)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, 0)
    }

    private fun makeTexture(w: Int, h: Int, filter: Int): Int {
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ids[0])
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, filter)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, filter)
        GLES30.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, GLES30.GL_RG16F,
            w, h, 0, GLES30.GL_RG, GLES20.GL_FLOAT, null
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
        if (GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) != GLES20.GL_FRAMEBUFFER_COMPLETE) {
            Log.e(TAG, "FBO incomplete — FP16 render targets unsupported; scene disabled")
            broken = true
        }
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        return ids[0]
    }

    override fun onResize(width: Int, height: Int, aspect: Float) {
        val a = if (height > 0) width.toFloat() / height else 1f
        val fit = 0.8f * minOf(a, 1f)
        projX = fit / a
        projY = fit
    }

    /** Upload the driven edge (auto-gained PCM) and the piano-line mallets (bin rises). */
    private fun updateAudioTextures(pcm: FloatArray) {
        var peak = 0f
        for (s in pcm) { val a = abs(s); if (a > peak) peak = a }
        agc += ((AGC_TARGET / maxOf(peak, AGC_FLOOR)).coerceIn(3f, 150f) - agc) * AGC_SMOOTH
        val stride = (pcm.size / PCM_PTS).coerceAtLeast(1)
        pcmBuf.clear()
        for (i in 0 until PCM_PTS) {
            val g = pcm[(i * stride).coerceAtMost(pcm.size - 1)] * agc
            pcmBuf.put(g / (1f + abs(g))); pcmBuf.put(0f)
        }
        pcmBuf.position(0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, pcmTex)
        GLES20.glTexSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, PCM_PTS, 1, GLES30.GL_RG, GLES20.GL_FLOAT, pcmBuf)

        // Mallets: attack-only per-bin rise (mag - prevMag), plus the sustained level.
        val mags = SpectrumData.magnitudes
        specBuf.clear()
        for (i in 0 until BINS) {
            val rise = (mags[i] - prevMags[i]).coerceAtLeast(0f)
            specBuf.put(mags[i]); specBuf.put(rise)
            prevMags[i] = mags[i]
        }
        specBuf.position(0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, specTex)
        GLES20.glTexSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, BINS, 1, GLES30.GL_RG, GLES20.GL_FLOAT, specBuf)
    }

    override fun draw(pcm: FloatArray, bands: FloatArray, timeSec: Float, dim: Float) {
        if (broken) return
        if (lastT < 0f) lastT = timeSec
        val dt = (timeSec - lastT).coerceIn(0f, 0.1f)
        lastT = timeSec
        val env = BeatPulse.envelope

        updateAudioTextures(pcm)

        // Fixed-substep physics: CFL-stable and frame-rate independent. The beat
        // envelope tightens the fabric (waves quicken), always inside stability.
        GLES20.glGetIntegerv(GLES30.GL_FRAMEBUFFER_BINDING, prevFbo, 0)
        GLES20.glGetIntegerv(GLES20.GL_VIEWPORT, prevViewport, 0)
        GLES20.glDisable(GLES20.GL_BLEND)
        simAccum += dt
        var steps = 0
        val tension = (0.30f * (1f + env * 0.4f)).coerceAtMost(0.44f)
        while (simAccum >= DT_SIM && steps < MAX_SUBSTEPS) {
            runSimStep(tension)
            simAccum -= DT_SIM
            steps++
        }
        if (steps == MAX_SUBSTEPS) simAccum = 0f   // low-fps: drop backlog, stay stable
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, prevFbo[0])
        GLES20.glViewport(prevViewport[0], prevViewport[1], prevViewport[2], prevViewport[3])

        drawMesh(timeSec, dim)
    }

    private fun runSimStep(tension: Float) {
        val write = 1 - simRead
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, simFbo[write])
        GLES20.glViewport(0, 0, SIM_W, SIM_H)
        GLES20.glUseProgram(simProg)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, simTex[simRead])
        GLES20.glUniform1i(sState, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, pcmTex)
        GLES20.glUniform1i(sPcm, 1)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE2)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, specTex)
        GLES20.glUniform1i(sSpec, 2)
        GLES20.glUniform1f(sK, tension)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, quadVbo)
        GLES20.glEnableVertexAttribArray(0)
        GLES20.glVertexAttribPointer(0, 2, GLES20.GL_FLOAT, false, 0, 0)
        GLES30.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(0)
        simRead = write
    }

    private fun drawMesh(timeSec: Float, dim: Float) {
        // Low grazing orbit: an oscillating yaw so the silk catches the key light.
        buildCamera(
            yaw = 0.25f * sin(timeSec * 0.05f),
            pitch = 0.30f + 0.06f * sin(timeSec * 0.037f),
        )
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE)   // additive on black
        GLES20.glUseProgram(meshProg)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, simTex[simRead])
        GLES20.glUniform1i(mState, 0)
        GLES20.glUniformMatrix3fv(mCam, 1, false, camBuf, 0)
        GLES20.glUniform2f(mProj, projX, projY)
        GLES20.glUniform1f(mHgt, 0.5f)
        GLES20.glUniform1f(mDim, dim)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, gridVbo)
        GLES20.glEnableVertexAttribArray(0)
        GLES20.glVertexAttribPointer(0, 2, GLES20.GL_FLOAT, false, 0, 0)
        GLES20.glLineWidth(1f)
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, gridIbo)
        GLES20.glDrawElements(GLES20.GL_LINES, indexCount, GLES20.GL_UNSIGNED_SHORT, 0)
        GLES20.glDisableVertexAttribArray(0)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, 0)
    }

    /** Column-major pitch*yaw rotation (same construction as Nebula's orbit). */
    private fun buildCamera(yaw: Float, pitch: Float) {
        val cy = cos(yaw); val sy = sin(yaw)
        val cp = cos(pitch); val sp = sin(pitch)
        camBuf[0] = cy; camBuf[1] = sp * sy; camBuf[2] = -cp * sy
        camBuf[3] = 0f; camBuf[4] = cp; camBuf[5] = sp
        camBuf[6] = sy; camBuf[7] = -sp * cy; camBuf[8] = cp * cy
    }

    override fun onDeactivate() {
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
    }
}

/** Veil's GLSL, kept out of the scene class (size guard + readability). */
private object VeilShaders {

    const val QUAD_VS = """#version 300 es
        layout(location = 0) in vec2 aPos;
        out vec2 v_uv;
        void main() {
            v_uv = aPos * 0.5 + 0.5;
            gl_Position = vec4(aPos, 0.0, 1.0);
        }
    """

    /**
     * One fixed 240 Hz substep of the membrane: discrete wave equation
     * (v += k * laplacian, h += v) with damping, mallet strikes along the piano
     * line, and the PCM-driven bottom edge as a hard boundary condition.
     */
    const val SIM_FS = """#version 300 es
        precision highp float;
        uniform sampler2D u_state;      // R = height, G = velocity
        uniform sampler2D u_pcm;        // R = driven-edge sample (auto-gained)
        uniform sampler2D u_spec;       // R = bin magnitude, G = transient rise
        uniform float u_k;              // tension (per-step wave constant, < 0.5)
        in vec2 v_uv;
        out vec4 o_state;

        void main() {
            ivec2 ts = ivec2(${VeilScene.SIM_W}, ${VeilScene.SIM_H});
            ivec2 ij = clamp(ivec2(v_uv * vec2(ts)), ivec2(0), ts - 1);
            vec2 s = texelFetch(u_state, ij, 0).rg;
            float h = s.r;
            float v = s.g;

            float hl = texelFetch(u_state, ivec2(max(ij.x - 1, 0), ij.y), 0).r;
            float hr = texelFetch(u_state, ivec2(min(ij.x + 1, ts.x - 1), ij.y), 0).r;
            float hd = texelFetch(u_state, ivec2(ij.x, max(ij.y - 1, 0)), 0).r;
            float hu = texelFetch(u_state, ivec2(ij.x, min(ij.y + 1, ts.y - 1)), 0).r;
            float lap = hl + hr + hd + hu - 4.0 * h;

            v += u_k * lap;                     // wave propagation
            v *= 0.9971;                        // ring-down (~1.4 s at 240 Hz)

            // Piano mallets: frequency = x, striking a line across the sheet.
            vec2 sp = texture(u_spec, vec2(v_uv.x, 0.5)).rg;
            float dy = (v_uv.y - 0.62) * float(ts.y) / 3.0;
            float line = exp(-dy * dy);
            v += (sp.g * 0.10 + sp.r * 0.004) * line;

            h += v;
            h *= 0.9996;                        // settle any DC drift

            // The live waveform enters through the bottom edge (hard BC).
            float drive = texture(u_pcm, vec2(v_uv.x, 0.5)).r;
            if (ij.y < 2) { h = drive * 0.5; v = 0.0; }

            o_state = vec4(h, v, 0.0, 1.0);
        }
    """

    /** Displaced wireframe with light-is-motion shading inputs from the sim. */
    const val MESH_VS = """#version 300 es
        layout(location = 0) in vec2 aGrid;    // uv in [0,1]^2
        uniform sampler2D u_state;
        uniform mat3  u_cam;
        uniform vec2  u_proj;
        uniform float u_hgt;
        out float v_vel;
        out float v_crest;
        out float v_sheen;
        out vec2  v_q;

        void main() {
            vec2 e = vec2(1.0 / ${VeilScene.SIM_W}.0, 1.0 / ${VeilScene.SIM_H}.0);
            vec2 s = texture(u_state, aGrid).rg;
            float h = s.r;
            float hl = texture(u_state, aGrid - vec2(e.x, 0.0)).r;
            float hr = texture(u_state, aGrid + vec2(e.x, 0.0)).r;
            float hd = texture(u_state, aGrid - vec2(0.0, e.y)).r;
            float hu = texture(u_state, aGrid + vec2(0.0, e.y)).r;
            float lap = hl + hr + hd + hu - 4.0 * h;

            vec3 p = vec3((aGrid.x * 2.0 - 1.0) * 1.35, h * u_hgt, (aGrid.y * 2.0 - 1.0) * 0.85);
            p = u_cam * p;
            float w = 3.4 / (3.4 - p.z);
            gl_Position = vec4(p.xy * w * u_proj, 0.0, 1.0);

            // Satin sheen: a fixed key light over the sheet's slope.
            vec3 n = normalize(vec3((hl - hr) * 24.0, 1.0, (hd - hu) * 24.0));
            vec3 l = normalize(vec3(-0.4, 0.8, 0.45));
            v_sheen = pow(max(dot(n, l), 0.0), 4.0);
            v_vel = abs(s.g) * 22.0;                       // motion -> glow
            v_crest = clamp(-lap * 40.0, 0.0, 1.5);        // curvature -> white crests
            v_q = aGrid;
        }
    """

    const val MESH_FS = """#version 300 es
        precision highp float;
        uniform float u_dim;
        in float v_vel;
        in float v_crest;
        in float v_sheen;
        in vec2  v_q;
        out vec4 fragColor;

        void main() {
            // Light is motion: near-black silk, indigo sheen, cyan wavefronts,
            // white-hot crests.
            vec3 col = vec3(0.10, 0.08, 0.30) * (0.16 + v_sheen * 0.50)
                + vec3(0.00, 0.75, 0.95) * v_vel
                + vec3(1.15, 1.20, 1.35) * v_crest * 0.8;

            // Dissolve at the hem so the sheet floats in the dark.
            float hem = smoothstep(0.0, 0.05, v_q.x) * (1.0 - smoothstep(0.95, 1.0, v_q.x))
                * (1.0 - smoothstep(0.93, 1.0, v_q.y));
            col *= hem * (1.2 + v_vel * 1.2);              // HDR lift for bloom
            fragColor = vec4(col * u_dim, 1.0);
        }
    """
}
