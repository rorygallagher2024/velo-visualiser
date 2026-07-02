package com.lowlatency.visualizer.gl

import android.opengl.GLES20
import android.opengl.GLES30
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import com.lowlatency.visualizer.BeatPulse
import kotlin.math.sqrt

/**
 * "Event Horizon" — a full-screen, ray-traced black hole whose accretion disc is
 * a polar spectrogram of the music.
 *
 * Every pixel marches a light ray through a Schwarzschild-style gravity well
 * (the null-geodesic bending term `-1.5 h² p / r⁵`), so the relativistic optics
 * come out of the integration for free: the far side of the disc lenses into an
 * arc above and below the hole, a photon ring hugs the shadow, and the starfield
 * smears into Einstein arcs behind it.
 *
 * The disc itself is audio: **azimuth is frequency** (bass sweeping to treble,
 * mirrored so the seam is invisible) and **radius is time** — each spectrum frame
 * is committed to the ring-buffer history texture and radiates outward, so a
 * kick drum becomes a luminous wave rolling through curved spacetime. Per-bin
 * peak-holds sparkle white-hot in the Keplerian streaks, doppler beaming
 * brightens the approaching side, the beat envelope flares the disc and pulses
 * the lensing strength itself, and raw PCM loudness stokes the turbulence.
 *
 * The camera orbits slowly and dips through the disc plane — the edge-on pass
 * with the fully lensed halo is the signature moment.
 */
class EventHorizonScene : GlScene {

    companion object {
        private const val BINS = 128          // spectrum bins (history texture width)
        private const val ROWS = 64           // history depth (inner edge -> rim ≈ 3 s)
        private const val SCROLL_RATE = 22f   // history rows committed per second
        private const val MAX_COMMITS = 4     // low-fps safety cap

        private const val QUAD_VS = """#version 300 es
            layout(location = 0) in vec2 aPos;
            out vec2 v_uv;
            void main() {
                v_uv = aPos;
                gl_Position = vec4(aPos, 0.0, 1.0);
            }
        """

        private const val HOLE_FS = """#version 300 es
            precision highp float;
            uniform sampler2D u_hist;          // R = magnitude, G = peak (128 x rows)
            uniform float u_head;
            uniform float u_rows;
            uniform float u_frac;
            uniform float u_time;
            uniform float u_env;               // global beat envelope
            uniform float u_rms;               // instantaneous loudness (turbulence)
            uniform float u_aspect;            // width / height
            uniform float u_dim;
            in vec2 v_uv;
            out vec4 fragColor;

            const float R_IN = 1.55;           // disc inner edge (outside the horizon)
            const float R_OUT = 4.6;           // disc outer rim
            const int   STEPS = 56;

            float hash(vec2 q) { return fract(sin(dot(q, vec2(127.1, 311.7))) * 43758.5453); }

            float vnoise(vec2 q) {
                vec2 i = floor(q);
                vec2 f = fract(q);
                f = f * f * (3.0 - 2.0 * f);
                return mix(mix(hash(i), hash(i + vec2(1.0, 0.0)), f.x),
                           mix(hash(i + vec2(0.0, 1.0)), hash(i + vec2(1.0, 1.0)), f.x), f.y);
            }

            // Curated temperature run: white-gold furnace -> magenta -> violet -> cyan rim.
            vec3 discColor(float rad) {
                vec3 c = mix(vec3(1.30, 1.12, 0.90), vec3(1.00, 0.25, 0.80), smoothstep(0.05, 0.42, rad));
                c = mix(c, vec3(0.30, 0.12, 0.80), smoothstep(0.40, 0.75, rad));
                c = mix(c, vec3(0.00, 0.70, 0.95), smoothstep(0.72, 1.0, rad));
                return c;
            }

            // Emission where a bent ray pierces the disc plane.
            vec3 discEmission(vec3 p, vec3 rd) {
                float r = length(p.xz);
                float rad = (r - R_IN) / (R_OUT - R_IN);
                float ang = atan(p.z, p.x);

                // Polar spectrogram: azimuth = frequency (mirrored, seam-free),
                // radius = history flowing outward (newest at the inner edge).
                float binX = abs(fract(ang / 6.28318) * 2.0 - 1.0);
                float ringY = (u_head - (1.0 - u_frac) - rad * (u_rows - 1.0) + 0.5) / u_rows;
                vec2 s = texture(u_hist, vec2(binX, ringY)).rg;

                // Keplerian streaks: inner material laps the outer, shearing the
                // noise into trailing filaments; loudness stokes the turbulence.
                float sw = ang * 3.0 - u_time * 7.0 * pow(max(r, 0.5), -1.5);
                float n = vnoise(vec2(sw * 1.6, rad * 20.0));
                n = 0.4 + 0.6 * mix(n, n * n * 1.6, clamp(u_rms * 5.0, 0.0, 1.0));

                // Doppler beaming: the side sweeping toward the camera burns brighter.
                vec3 tang = normalize(vec3(-p.z, 0.0, p.x));
                float boost = pow(max(0.0, 1.0 + dot(tang, -rd) * 0.55), 3.0);

                float fade = smoothstep(0.0, 0.06, rad) * (1.0 - smoothstep(0.72, 1.0, rad));
                float heat = mix(1.5, 0.35, smoothstep(0.0, 1.0, rad));   // hotter inward

                float e = (0.30 + s.r * 2.4) * n * boost * heat * fade;
                e *= 0.8 + u_env * 1.5;                    // beat flare
                vec3 col = discColor(clamp(rad, 0.0, 1.0)) * e;
                // Peak-hold sparkle: transient bins glint white-hot in the streaks.
                col += vec3(1.15, 1.20, 1.40) * smoothstep(0.30, 0.80, s.g) * n * fade * (0.5 + u_env);
                return col;
            }

            // Lensed starfield for escaped rays: pinprick stars + faint dust band.
            vec3 background(vec3 dir) {
                vec2 q = vec2(atan(dir.z, dir.x) * 2.5, asin(clamp(dir.y, -1.0, 1.0)) * 5.0);
                vec2 cell = floor(q * 14.0);
                float star = smoothstep(0.995, 1.0, hash(cell)) *
                    smoothstep(0.4, 0.0, length(fract(q * 14.0) - 0.5));
                vec3 tint = mix(vec3(0.9, 0.95, 1.2), vec3(1.2, 0.9, 0.8), hash(cell + 7.0));
                vec3 dust = vec3(0.05, 0.04, 0.10) * exp(-dir.y * dir.y * 9.0);
                return star * tint * 1.6 + dust;
            }

            void main() {
                vec2 uv = vec2(v_uv.x * u_aspect, v_uv.y);

                // Slow orbit that dips through the disc plane (edge-on = the shot).
                float yaw = u_time * 0.05;
                float pit = 0.05 + 0.22 * sin(u_time * 0.047);
                float dist = 7.6 - 0.6 * sin(u_time * 0.037);
                vec3 ro = dist * vec3(cos(pit) * cos(yaw), sin(pit), cos(pit) * sin(yaw));
                vec3 fw = normalize(-ro);
                vec3 rt = normalize(cross(vec3(0.0, 1.0, 0.0), fw));
                vec3 up = cross(fw, rt);
                // Widen the FOV on narrow displays (foldable cover screens) so the
                // full lensed disc always fits; unchanged at aspect >= ~0.87.
                float f = min(1.35, u_aspect * 1.55);
                vec3 rd = normalize(fw * f + uv.x * rt + uv.y * up);

                // Null-geodesic march: v bends by -1.5 h^2 p / r^5 (rs = 1).
                vec3 h = cross(ro, rd);
                float h2 = dot(h, h) * (1.0 + u_env * 0.05);   // lensing breathes on beats
                float bendK = -1.5 * h2;                        // hoisted out of the loop
                vec3 p = ro;
                vec3 v = rd;
                vec3 acc = vec3(0.0);
                float minR = 1e4;
                bool captured = false;
                for (int i = 0; i < STEPS; i++) {
                    float r2 = dot(p, p);
                    float r = sqrt(r2);
                    minR = min(minR, r);
                    if (r < 0.98) { captured = true; break; }
                    if (r2 > 80.0 && dot(p, v) > 0.0) break;
                    // Longer strides far from the hole; fine steps near the photon
                    // sphere where the bending (and the ring) needs the resolution.
                    float dt = 0.10 + 0.30 * smoothstep(1.2, 6.5, r);
                    vec3 pn = p + v * dt;
                    // Disc crossing between steps -> accumulate emission (stays
                    // translucent, so the lensed far side layers over the near).
                    if (p.y * pn.y < 0.0) {
                        vec3 hit = mix(p, pn, abs(p.y) / max(abs(p.y) + abs(pn.y), 1e-5));
                        float hr = length(hit.xz);
                        if (hr > R_IN && hr < R_OUT) acc += discEmission(hit, v);
                    }
                    v += p * (bendK * dt / max(r2 * r2 * r, 1e-4));
                    p = pn;
                }

                vec3 col = acc;
                if (!captured) col += background(normalize(v)) * 0.7;
                // Photon ring: a warm halo where rays graze the photon sphere (1.5 rs).
                col += vec3(1.1, 0.85, 0.65) * exp(-abs(minR - 1.5) * 4.0) * (0.35 + u_env * 0.6);

                col *= 1.25 + u_env * 0.45;                 // HDR lift for bloom
                fragColor = vec4(col * u_dim, 1.0);
            }
        """
    }

    private val rowBuf: FloatBuffer = ByteBuffer
        .allocateDirect(BINS * 2 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()

    private var program = 0
    private var quadVbo = 0
    private var histTex = 0

    private var uHist = 0; private var uHead = 0; private var uRows = 0; private var uFrac = 0
    private var uTime = 0; private var uEnv = 0; private var uRms = 0
    private var uAspect = 0; private var uDim = 0

    private var writeRow = 0
    private var head = 0
    private var lastT = -1f
    private var scrollAccum = 0f
    private var aspect = 1f

    override fun onCreated() {
        program = ShaderUtil.buildProgram(QUAD_VS, HOLE_FS)
        uHist = GLES20.glGetUniformLocation(program, "u_hist")
        uHead = GLES20.glGetUniformLocation(program, "u_head")
        uRows = GLES20.glGetUniformLocation(program, "u_rows")
        uFrac = GLES20.glGetUniformLocation(program, "u_frac")
        uTime = GLES20.glGetUniformLocation(program, "u_time")
        uEnv = GLES20.glGetUniformLocation(program, "u_env")
        uRms = GLES20.glGetUniformLocation(program, "u_rms")
        uAspect = GLES20.glGetUniformLocation(program, "u_aspect")
        uDim = GLES20.glGetUniformLocation(program, "u_dim")

        val quad = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
        val qbuf = ByteBuffer.allocateDirect(quad.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer().put(quad).also { it.position(0) }
        val ids = IntArray(1)
        GLES20.glGenBuffers(1, ids, 0)
        quadVbo = ids[0]
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, quadVbo)
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, quad.size * 4, qbuf, GLES20.GL_STATIC_DRAW)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)

        histTex = makeRingTexture()
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
        this.aspect = if (height > 0) width.toFloat() / height else 1f
    }

    override fun draw(pcm: FloatArray, bands: FloatArray, timeSec: Float, dim: Float) {
        if (lastT < 0f) lastT = timeSec
        val dt = (timeSec - lastT).coerceIn(0f, 0.1f)
        lastT = timeSec
        scrollAccum += dt * SCROLL_RATE
        commitHistoryRows()

        var sumSq = 0f
        for (s in pcm) sumSq += s * s
        val rms = sqrt(sumSq / pcm.size.coerceAtLeast(1))

        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, histTex)
        GLES20.glUniform1i(uHist, 0)
        GLES20.glUniform1f(uHead, head.toFloat())
        GLES20.glUniform1f(uRows, ROWS.toFloat())
        GLES20.glUniform1f(uFrac, scrollAccum.coerceIn(0f, 1f))
        GLES20.glUniform1f(uTime, timeSec)
        GLES20.glUniform1f(uEnv, BeatPulse.envelope)
        GLES20.glUniform1f(uRms, rms)
        GLES20.glUniform1f(uAspect, aspect)
        GLES20.glUniform1f(uDim, dim)

        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, quadVbo)
        GLES20.glEnableVertexAttribArray(0)
        GLES20.glVertexAttribPointer(0, 2, GLES20.GL_FLOAT, false, 0, 0)
        GLES30.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(0)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
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
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
    }
}
