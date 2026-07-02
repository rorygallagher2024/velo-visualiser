package com.lowlatency.visualizer.gl

import android.opengl.GLES20
import android.opengl.GLES30
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import com.lowlatency.visualizer.BeatPulse
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/**
 * "Nebula" — the flagship. A spiral galaxy sculpted from the music's history,
 * drawing on every audio input at once.
 *
 * ~45k GPU particles form a slowly rotating spiral disc. Each star is pinned to a
 * frequency bin (its colour tint and arm position) and a moment in time (its
 * radius): the newest audio ignites the core and radiates outward through the
 * arms as it ages — a kick drum becomes a luminous ring racing through the
 * nebula (an explicit beat shockwave rides the same radial clock). Height off
 * the galactic plane is energy: silence collapses the disc razor-thin, loud
 * music blooms it into a volumetric cloud. Per-bin peak-holds flare individual
 * stars white-hot; the global beat envelope pumps the core and kicks the
 * rotation; and the raw PCM waveform orbits the rim as a thin "accretion ring"
 * — the live signal encircling its own history.
 *
 * Machinery reused from the proven scenes: the ring-buffer history texture and
 * rate-controlled scroll (Spectral Canyon), auto-gain for the PCM ring (Phase
 * Scope), additive blending on black with an HDR lift for the post-processor's
 * bloom. All animation is in the vertex shader off static attributes — zero
 * per-frame CPU particle work; the only upload is one 128-texel history row and
 * 512 ring samples.
 */
class NebulaScene : GlScene {

    companion object {
        private const val BINS = 128          // spectrum bins (history texture width)
        private const val ROWS = 64           // history depth (core -> rim ≈ 3 s)
        private const val SCROLL_RATE = 20f   // history rows committed per second
        private const val MAX_COMMITS = 4     // low-fps safety cap
        private const val STARS = 45_000      // particles (single draw call)
        private const val ARMS = 3            // spiral arms
        private const val RING_PTS = 512      // PCM accretion-ring vertices
        private const val FLOATS_PER_STAR = 7 // aA(vec4) + aB(vec3), interleaved

        // Auto-gain for the PCM ring (mic-level PCM would otherwise flatten it).
        private const val AGC_TARGET = 3.5f
        private const val AGC_FLOOR = 0.03f
        private const val AGC_SMOOTH = 0.08f
        private const val AGC_MIN = 3f
        private const val AGC_MAX = 150f

        private const val GALAXY_VS = """#version 300 es
            layout(location = 0) in vec4 aA;   // angle0, radius, binNorm, histT
            layout(location = 1) in vec3 aB;   // planeJitter, sizeRand, twinklePhase
            uniform sampler2D u_hist;          // R = magnitude, G = peak-hold
            uniform float u_head;              // newest ring row
            uniform float u_rows;
            uniform float u_frac;              // 0..1 progress toward next commit
            uniform float u_time;
            uniform float u_kick;              // beat-integrated rotation offset
            uniform float u_env;               // global beat envelope
            uniform mat3  u_cam;               // orbit camera (pitch * yaw)
            uniform vec2  u_proj;              // (xScale, yScale) aspect-fitted
            uniform float u_px;                // point-size scale for this viewport
            out float v_mag;
            out float v_pk;
            out float v_rr;                    // 0 core .. 1 rim
            out float v_bin;
            out float v_tw;                    // twinkle factor

            void main() {
                // Sample this star's moment in history. Subtracting (1 - frac)
                // slides the sample point continuously between commits, so energy
                // flows outward smoothly instead of stepping row by row.
                float ring = (u_head - (1.0 - u_frac) - aA.w * (u_rows - 1.0) + 0.5) / u_rows;
                vec2 s = texture(u_hist, vec2(aA.z, ring)).rg;
                float mag = s.r;
                float pk = s.g;

                float rr = clamp((aA.y - 0.14) / 0.98, 0.0, 1.0);
                // Differential rotation (inner stars orbit faster) + beat kick.
                float angle = aA.x + u_kick + u_time * (0.045 + 0.11 * (1.0 - rr));
                // Energy lifts stars off the galactic plane: quiet = thin disc,
                // loud = volumetric cloud. Jitter is signed so it stays symmetric.
                float y = aB.x * (0.02 + mag * 0.38);
                vec3 p = vec3(cos(angle) * aA.y, y, sin(angle) * aA.y);

                p = u_cam * p;
                float w = 3.6 / (3.6 - p.z);          // gentle perspective
                gl_Position = vec4(p.xy * w * u_proj, 0.0, 1.0);

                float flare = smoothstep(0.25, 0.7, pk);
                float sz = 1.4 + mag * 5.5 + flare * 7.0 + u_env * 2.5 * (1.0 - rr);
                gl_PointSize = clamp(sz * w * u_px, 1.0, 18.0);

                // Small stars twinkle; energised ones burn steady.
                float tw = 0.85 + 0.15 * sin(u_time * (1.5 + aB.y * 2.5) + aB.z * 6.2831);
                v_tw = mix(tw, 1.0, smoothstep(0.2, 0.5, mag));
                v_mag = mag;
                v_pk = pk;
                v_rr = rr;
                v_bin = aA.z;
            }
        """

        private const val GALAXY_FS = """#version 300 es
            precision highp float;
            uniform float u_dim;
            uniform float u_env;
            uniform float u_beatAge;           // seconds since last strong beat
            in float v_mag;
            in float v_pk;
            in float v_rr;
            in float v_bin;
            in float v_tw;
            out vec4 fragColor;

            // Curated radial palette: white-gold heart -> magenta -> indigo -> cyan rim.
            vec3 nebulaColor(float rr) {
                vec3 c = mix(vec3(1.25, 1.10, 0.95), vec3(0.90, 0.20, 0.85), smoothstep(0.05, 0.40, rr));
                c = mix(c, vec3(0.25, 0.12, 0.75), smoothstep(0.38, 0.72, rr));
                c = mix(c, vec3(0.00, 0.75, 0.95), smoothstep(0.70, 1.0, rr));
                return c;
            }

            void main() {
                // Soft round sprite with a hot centre (no textures needed).
                float d2 = length(gl_PointCoord * 2.0 - 1.0);
                float shape = smoothstep(1.0, 0.1, d2);
                float hot = smoothstep(0.55, 0.0, d2);

                // Bass warms, treble cools — a subtle frequency identity per star.
                vec3 col = nebulaColor(v_rr)
                    * mix(vec3(1.06, 0.96, 1.00), vec3(0.92, 1.00, 1.12), v_bin);

                float b = 0.20 + v_mag * 1.5 + hot * 0.35;
                b += (1.0 - v_rr) * (1.0 - v_rr) * (0.30 + u_env * 1.1);   // beating heart

                // Beat shockwave: a luminous ring racing core -> rim after each hit.
                float dw = (v_rr - u_beatAge * 0.55) * 9.0;
                b += exp(-dw * dw) * max(0.0, 1.0 - u_beatAge * 0.8) * (0.5 + u_env * 0.8);

                // Peak-hold stars flare white-hot.
                col += vec3(1.15, 1.20, 1.40) * smoothstep(0.25, 0.75, v_pk) * (0.45 + u_env * 0.9);

                col *= b * v_tw;
                col *= 1.30 + v_mag * 1.9;             // HDR lift so the cloud blooms
                fragColor = vec4(col * shape * u_dim, 1.0);
            }
        """

        private const val RING_VS = """#version 300 es
            layout(location = 0) in float aSamp;   // auto-gained PCM sample
            uniform float u_count;
            uniform float u_time;
            uniform float u_kick;
            uniform mat3  u_cam;
            uniform vec2  u_proj;

            void main() {
                float theta = float(gl_VertexID) / u_count * 6.28318
                    + u_time * 0.045 + u_kick;       // co-rotates with the galaxy
                float r = 1.18 + aSamp * 0.09;       // waveform ripples the rim
                vec3 p = vec3(cos(theta) * r, 0.0, sin(theta) * r);
                p = u_cam * p;
                float w = 3.6 / (3.6 - p.z);
                gl_Position = vec4(p.xy * w * u_proj, 0.0, 1.0);
            }
        """

        private const val RING_FS = """#version 300 es
            precision highp float;
            uniform float u_dim;
            uniform float u_env;
            out vec4 fragColor;

            void main() {
                // A quiet, cool accretion band — present, never shouting.
                vec3 col = vec3(0.50, 0.90, 1.05) * (0.30 + u_env * 0.55);
                fragColor = vec4(col * 1.6 * u_dim, 1.0);
            }
        """
    }

    private val rowBuf: FloatBuffer = ByteBuffer
        .allocateDirect(BINS * 2 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
    private val ringBuf: FloatBuffer = ByteBuffer
        .allocateDirect(RING_PTS * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
    private val camBuf = FloatArray(9)

    private var galaxyProg = 0
    private var ringProg = 0
    private var starVbo = 0
    private var ringVbo = 0
    private var histTex = 0

    // Galaxy uniforms.
    private var gHist = 0; private var gHead = 0; private var gRows = 0; private var gFrac = 0
    private var gTime = 0; private var gKick = 0; private var gEnv = 0; private var gCam = 0
    private var gProj = 0; private var gPx = 0; private var gDim = 0; private var gBeatAge = 0

    // Ring uniforms.
    private var rCount = 0; private var rTime = 0; private var rKick = 0; private var rCam = 0
    private var rProj = 0; private var rDim = 0; private var rEnv = 0

    private var writeRow = 0
    private var head = 0
    private var lastT = -1f
    private var scrollAccum = 0f
    private var kick = 0f
    private var beatAt = -99f
    private var lastEnv = 0f
    private var agc = 8f
    private var projX = 0.72f
    private var projY = 0.72f
    private var pxScale = 1f

    override fun onCreated() {
        galaxyProg = ShaderUtil.buildProgram(GALAXY_VS, GALAXY_FS)
        gHist = GLES20.glGetUniformLocation(galaxyProg, "u_hist")
        gHead = GLES20.glGetUniformLocation(galaxyProg, "u_head")
        gRows = GLES20.glGetUniformLocation(galaxyProg, "u_rows")
        gFrac = GLES20.glGetUniformLocation(galaxyProg, "u_frac")
        gTime = GLES20.glGetUniformLocation(galaxyProg, "u_time")
        gKick = GLES20.glGetUniformLocation(galaxyProg, "u_kick")
        gEnv = GLES20.glGetUniformLocation(galaxyProg, "u_env")
        gCam = GLES20.glGetUniformLocation(galaxyProg, "u_cam")
        gProj = GLES20.glGetUniformLocation(galaxyProg, "u_proj")
        gPx = GLES20.glGetUniformLocation(galaxyProg, "u_px")
        gDim = GLES20.glGetUniformLocation(galaxyProg, "u_dim")
        gBeatAge = GLES20.glGetUniformLocation(galaxyProg, "u_beatAge")

        ringProg = ShaderUtil.buildProgram(RING_VS, RING_FS)
        rCount = GLES20.glGetUniformLocation(ringProg, "u_count")
        rTime = GLES20.glGetUniformLocation(ringProg, "u_time")
        rKick = GLES20.glGetUniformLocation(ringProg, "u_kick")
        rCam = GLES20.glGetUniformLocation(ringProg, "u_cam")
        rProj = GLES20.glGetUniformLocation(ringProg, "u_proj")
        rDim = GLES20.glGetUniformLocation(ringProg, "u_dim")
        rEnv = GLES20.glGetUniformLocation(ringProg, "u_env")

        buildStars()
        histTex = makeRingTexture()

        val ids = IntArray(1)
        GLES20.glGenBuffers(1, ids, 0)
        ringVbo = ids[0]
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, ringVbo)
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, RING_PTS * 4, null, GLES20.GL_DYNAMIC_DRAW)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
    }

    /** Static star attributes: spiral placement + per-star randomness (seeded, reproducible). */
    private fun buildStars() {
        val rnd = Random(42)
        val v = FloatArray(STARS * FLOATS_PER_STAR)
        var i = 0
        for (s in 0 until STARS) {
            val arm = s % ARMS
            val t = rnd.nextFloat()                              // history depth == radial age
            val bin = rnd.nextFloat()                            // frequency identity
            val radius = 0.14f + 0.98f * t.pow(0.85f) + (rnd.nextFloat() - 0.5f) * 0.07f
            // Arm angle: spiral wind + a frequency gradient across the arm's width,
            // so bass and treble streak coherently instead of scattering.
            val angle0 = arm * (6.28318f / ARMS) + t * 3.6f +
                (bin - 0.5f) * 0.9f + (rnd.nextFloat() - 0.5f) * 0.55f
            v[i++] = angle0
            v[i++] = radius
            v[i++] = bin
            v[i++] = t
            v[i++] = rnd.nextFloat() + rnd.nextFloat() - 1f      // planeJitter (triangular)
            v[i++] = rnd.nextFloat()                             // sizeRand
            v[i++] = rnd.nextFloat()                             // twinklePhase
        }
        val buf = ByteBuffer.allocateDirect(v.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer().put(v).also { it.position(0) }
        val ids = IntArray(1)
        GLES20.glGenBuffers(1, ids, 0)
        starVbo = ids[0]
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, starVbo)
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, v.size * 4, buf, GLES20.GL_STATIC_DRAW)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
    }

    private fun makeRingTexture(): Int {
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ids[0])
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_REPEAT)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        val zero = ByteBuffer.allocateDirect(BINS * ROWS * 2 * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer()
        GLES30.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, GLES30.GL_RG16F,
            BINS, ROWS, 0, GLES30.GL_RG, GLES20.GL_FLOAT, zero
        )
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        return ids[0]
    }

    override fun onResize(width: Int, height: Int, aspect: Float) {
        // Fit the disc to the short axis so the full galaxy + rim ring is always
        // on screen, portrait or landscape, with a circle staying circular.
        val a = if (height > 0) width.toFloat() / height else 1f
        val fit = 0.72f * minOf(a, 1f)
        projX = fit / a
        projY = fit
        pxScale = height / 1600f
    }

    override fun draw(pcm: FloatArray, bands: FloatArray, timeSec: Float, dim: Float) {
        val env = BeatPulse.envelope

        if (lastT < 0f) lastT = timeSec
        val dt = (timeSec - lastT).coerceIn(0f, 0.1f)
        lastT = timeSec
        scrollAccum += dt * SCROLL_RATE
        commitHistoryRows()

        // Beat state: integrate a rotation kick, and clock the shockwave from the
        // envelope's rising edge past a strong-beat threshold.
        kick = (kick + env * dt * 0.35f) % 6283.1f
        if (env > 0.55f && lastEnv <= 0.55f) beatAt = timeSec
        lastEnv = env
        val beatAge = if (beatAt < 0f) 99f else timeSec - beatAt

        // Orbit camera: slow yaw drift + breathing tilt, shared by both passes.
        buildCamera(yaw = timeSec * 0.03f, pitch = 0.75f + 0.18f * sin(timeSec * 0.05f))

        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE)   // additive on black

        drawGalaxy(timeSec, dim, env, beatAge)
        drawRing(pcm, timeSec, dim, env)

        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
    }

    /** Column-major pitch*yaw rotation, shared by the galaxy and ring passes. */
    private fun buildCamera(yaw: Float, pitch: Float) {
        val cy = cos(yaw); val sy = sin(yaw)
        val cp = cos(pitch); val sp = sin(pitch)
        camBuf[0] = cy; camBuf[1] = sp * sy; camBuf[2] = -cp * sy
        camBuf[3] = 0f; camBuf[4] = cp; camBuf[5] = sp
        camBuf[6] = sy; camBuf[7] = -sp * cy; camBuf[8] = cp * cy
    }

    private fun drawGalaxy(timeSec: Float, dim: Float, env: Float, beatAge: Float) {
        GLES20.glUseProgram(galaxyProg)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, histTex)
        GLES20.glUniform1i(gHist, 0)
        GLES20.glUniform1f(gHead, head.toFloat())
        GLES20.glUniform1f(gRows, ROWS.toFloat())
        GLES20.glUniform1f(gFrac, scrollAccum.coerceIn(0f, 1f))
        GLES20.glUniform1f(gTime, timeSec)
        GLES20.glUniform1f(gKick, kick)
        GLES20.glUniform1f(gEnv, env)
        GLES20.glUniform1f(gBeatAge, beatAge)
        GLES20.glUniformMatrix3fv(gCam, 1, false, camBuf, 0)
        GLES20.glUniform2f(gProj, projX, projY)
        GLES20.glUniform1f(gPx, pxScale)
        GLES20.glUniform1f(gDim, dim)

        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, starVbo)
        GLES20.glEnableVertexAttribArray(0)
        GLES20.glVertexAttribPointer(0, 4, GLES20.GL_FLOAT, false, FLOATS_PER_STAR * 4, 0)
        GLES20.glEnableVertexAttribArray(1)
        GLES20.glVertexAttribPointer(1, 3, GLES20.GL_FLOAT, false, FLOATS_PER_STAR * 4, 16)
        GLES30.glDrawArrays(GLES20.GL_POINTS, 0, STARS)
        GLES20.glDisableVertexAttribArray(0)
        GLES20.glDisableVertexAttribArray(1)
    }

    private fun drawRing(pcm: FloatArray, timeSec: Float, dim: Float, env: Float) {
        // Auto-gain (Phase Scope pattern) so the rim ripples at any input level.
        var peak = 0f
        for (s in pcm) { val a = abs(s); if (a > peak) peak = a }
        val desired = (AGC_TARGET / maxOf(peak, AGC_FLOOR)).coerceIn(AGC_MIN, AGC_MAX)
        agc += (desired - agc) * AGC_SMOOTH

        val stride = (pcm.size / RING_PTS).coerceAtLeast(1)
        ringBuf.clear()
        for (i in 0 until RING_PTS) {
            val g = pcm[(i * stride).coerceAtMost(pcm.size - 1)] * agc
            ringBuf.put(g / (1f + abs(g)))
        }
        ringBuf.position(0)

        GLES20.glUseProgram(ringProg)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, ringVbo)
        GLES20.glBufferSubData(GLES20.GL_ARRAY_BUFFER, 0, RING_PTS * 4, ringBuf)
        GLES20.glEnableVertexAttribArray(0)
        GLES20.glVertexAttribPointer(0, 1, GLES20.GL_FLOAT, false, 0, 0)
        GLES20.glUniform1f(rCount, RING_PTS.toFloat())
        GLES20.glUniform1f(rTime, timeSec)
        GLES20.glUniform1f(rKick, kick)
        GLES20.glUniformMatrix3fv(rCam, 1, false, camBuf, 0)
        GLES20.glUniform2f(rProj, projX, projY)
        GLES20.glUniform1f(rDim, dim)
        GLES20.glUniform1f(rEnv, env)
        GLES20.glLineWidth(1f)
        GLES30.glDrawArrays(GLES30.GL_LINE_LOOP, 0, RING_PTS)
        GLES20.glDisableVertexAttribArray(0)
    }

    /** Commit new spectrum rows into the history ring at the fixed scroll rate. */
    private fun commitHistoryRows() {
        if (scrollAccum < 1f) return
        val mags = SpectrumData.magnitudes
        val peaks = SpectrumData.peaks
        rowBuf.clear()
        for (i in 0 until BINS) { rowBuf.put(mags[i]); rowBuf.put(peaks[i]) }
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, histTex)
        var commits = 0
        while (scrollAccum >= 1f && commits < MAX_COMMITS) {
            head = writeRow
            rowBuf.position(0)
            GLES20.glTexSubImage2D(
                GLES20.GL_TEXTURE_2D, 0, 0, writeRow, BINS, 1,
                GLES30.GL_RG, GLES20.GL_FLOAT, rowBuf
            )
            writeRow = (writeRow + 1) % ROWS
            scrollAccum -= 1f
            commits++
        }
    }

    override fun onDeactivate() {
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
    }
}
