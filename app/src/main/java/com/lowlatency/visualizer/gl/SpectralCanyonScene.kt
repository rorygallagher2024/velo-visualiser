package com.lowlatency.visualizer.gl

import android.opengl.GLES20
import android.opengl.GLES30
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import com.lowlatency.visualizer.BeatPulse

/**
 * "Spectral Canyon" — a 3D wireframe landscape sculpted from the music's own
 * recent history.
 *
 * Stripped back to basics: a clean additive wireframe on pure black (no fog).
 * The lateral axis is frequency (the 128-bin spectrum), the **depth axis is
 * time** — each frame the latest spectrum becomes the front row of the mesh and
 * the whole field scrolls back toward the horizon, so the terrain you fly over
 * is literally the last couple of seconds of sound. Height is magnitude; the
 * **per-bin peak-hold** lights glowing caps on the ridges (a transient you heard
 * a moment ago leaves a bright crest that drifts into the distance). A separate
 * **raw-PCM waveform** rides in the foreground as a bright 3D line.
 *
 * Geometry is a vertex-displaced grid sampling a ring-buffer history texture in
 * the vertex shader — cheap, crisp, and reliable (no ray-march artefacts).
 */
class SpectralCanyonScene : GlScene {

    companion object {
        private const val BINS = 128          // spectrum bins (history texture width)
        private const val GRID_W = 100        // mesh columns (frequency resolution)
        private const val GRID_D = 80         // mesh rows (time depth)
        private const val ROWS = GRID_D       // history ring rows == mesh depth (1 row : 1 frame)
        private const val PCM_LEN = 1024      // raw PCM samples (waveform texture width)
        private const val WAVE_N = 256        // waveform line-strip vertices
        private const val SCROLL_RATE = 18f   // history rows committed per second (scroll speed)
        private const val MAX_COMMITS = 4     // cap rows added per frame (low-fps safety)

        // ---- Terrain (wireframe) ----
        private const val TERRAIN_VS = """#version 300 es
            layout(location = 0) in vec2 aGrid;   // (x in [0,1] freq, zt in [0,1] time)
            uniform float u_aspect;
            uniform float u_head;                 // newest ring row index
            uniform float u_rows;
            uniform float u_frac;                 // 0..1 smooth scroll between commits
            uniform sampler2D u_hist;             // R = magnitude, G = peak
            out float v_zt;
            out float v_height;
            out float v_peak;
            out float v_fx;

            void main() {
                float x   = aGrid.x;             // 0..1 across frequency (bass left)
                float zt0 = aGrid.y;             // discrete history row (0 newest .. 1 oldest)
                // Sample the fixed history row for this mesh row (exact texel centre).
                float ring = (u_head - zt0 * (u_rows - 1.0) + 0.5) / u_rows;
                vec2 s = texture(u_hist, vec2(x, ring)).rg;
                float mag = s.r;

                // Smooth scroll: shift the whole mesh back by the fractional progress
                // toward the next committed row (decouples motion from framerate).
                float zt = zt0 + u_frac / (u_rows - 1.0);

                float xc = x * 2.0 - 1.0;         // -1..1
                float depth = mix(1.0, 7.0, zt);  // fake perspective recession
                float persp = 1.0 / depth;
                float px = (xc * 2.1 * persp) / u_aspect;
                float py = -0.42 + mag * 1.7 * persp;

                gl_Position = vec4(px, py, 0.0, 1.0);
                v_zt = zt;
                v_height = mag;
                v_peak = s.g;
                v_fx = x;
            }
        """

        private const val TERRAIN_FS = """#version 300 es
            precision highp float;
            uniform float u_dim;
            uniform float u_env;
            in float v_zt;
            in float v_height;
            in float v_peak;
            in float v_fx;
            out vec4 fragColor;

            vec3 palette(float t) {
                return 0.5 + 0.5 * cos(6.28318 * (t + vec3(0.0, 0.33, 0.60)));
            }

            void main() {
                float fade = pow(1.0 - v_zt, 1.5);          // recede cleanly to black
                vec3 col = palette(0.12 + v_fx * 0.45 + v_height * 0.2);
                col *= 0.45 + v_height * 1.8;               // taller = brighter

                // Peak caps: a recent transient glows on the ridge crest.
                float cap = smoothstep(0.18, 0.6, v_peak);
                col += palette(0.55 + v_fx * 0.4) * cap * (0.6 + u_env * 2.0);

                col *= fade;
                col *= 1.5 + v_height * 2.2;                // HDR lift for bloom
                fragColor = vec4(col * u_dim, 1.0);
            }
        """

        // ---- Foreground raw-PCM waveform (bright 3D line) ----
        private const val WAVE_VS = """#version 300 es
            layout(location = 0) in float aT;     // 0..1 across width
            uniform float u_aspect;
            uniform sampler2D u_pcm;              // R = sample, -1..1
            void main() {
                float w = texture(u_pcm, vec2(aT, 0.5)).r;
                w = w * 3.0; w = w / (1.0 + abs(w));        // soft gain (lifts quiet mic)

                float xc = aT * 2.0 - 1.0;
                float zt = -0.02;                           // the bright leading lip of the canyon
                float depth = mix(1.0, 7.0, zt);
                float persp = 1.0 / depth;
                float px = (xc * 2.1 * persp) / u_aspect;
                float py = -0.42 + w * 0.20;                // rides the baseline the mountains rise from
                gl_Position = vec4(px, py, 0.0, 1.0);
            }
        """

        private const val WAVE_FS = """#version 300 es
            precision highp float;
            uniform float u_dim;
            uniform float u_env;
            out vec4 fragColor;
            void main() {
                vec3 c = vec3(0.55, 0.8, 1.0) * (1.8 + u_env * 2.2);
                fragColor = vec4(c * u_dim, 1.0);
            }
        """
    }

    private val rowBuf: FloatBuffer = ByteBuffer
        .allocateDirect(BINS * 2 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
    private val pcmBuf: FloatBuffer = ByteBuffer
        .allocateDirect(PCM_LEN * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()

    private var terrainProg = 0
    private var tAGrid = 0
    private var tAspect = 0
    private var tHead = 0
    private var tRows = 0
    private var tFrac = 0
    private var tHist = 0
    private var tDim = 0
    private var tEnv = 0

    private var waveProg = 0
    private var wAT = 0
    private var wAspect = 0
    private var wPcm = 0
    private var wDim = 0
    private var wEnv = 0

    private var gridVbo = 0
    private var indexVbo = 0
    private var indexCount = 0
    private var waveVbo = 0

    private var histTex = 0
    private var pcmTex = 0
    private var writeRow = 0
    private var head = 0
    private var lastT = -1f
    private var scrollAccum = 0f
    private var aspect = 1f

    override fun onCreated() {
        terrainProg = ShaderUtil.buildProgram(TERRAIN_VS, TERRAIN_FS)
        tAGrid = GLES20.glGetAttribLocation(terrainProg, "aGrid")
        tAspect = GLES20.glGetUniformLocation(terrainProg, "u_aspect")
        tHead = GLES20.glGetUniformLocation(terrainProg, "u_head")
        tRows = GLES20.glGetUniformLocation(terrainProg, "u_rows")
        tFrac = GLES20.glGetUniformLocation(terrainProg, "u_frac")
        tHist = GLES20.glGetUniformLocation(terrainProg, "u_hist")
        tDim = GLES20.glGetUniformLocation(terrainProg, "u_dim")
        tEnv = GLES20.glGetUniformLocation(terrainProg, "u_env")

        waveProg = ShaderUtil.buildProgram(WAVE_VS, WAVE_FS)
        wAT = GLES20.glGetAttribLocation(waveProg, "aT")
        wAspect = GLES20.glGetUniformLocation(waveProg, "u_aspect")
        wPcm = GLES20.glGetUniformLocation(waveProg, "u_pcm")
        wDim = GLES20.glGetUniformLocation(waveProg, "u_dim")
        wEnv = GLES20.glGetUniformLocation(waveProg, "u_env")

        buildGrid()
        buildWave()
        histTex = makeRingTexture()
        pcmTex = makePcmTexture()
    }

    private fun buildGrid() {
        // Grid vertices (x, zt in [0,1]).
        val verts = FloatArray(GRID_W * GRID_D * 2)
        var vi = 0
        for (r in 0 until GRID_D) {
            for (c in 0 until GRID_W) {
                verts[vi++] = c.toFloat() / (GRID_W - 1)
                verts[vi++] = r.toFloat() / (GRID_D - 1)
            }
        }
        val vbuf = ByteBuffer.allocateDirect(verts.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer().put(verts).also { it.position(0) }

        // Wireframe line indices: horizontal (per row) + depth (per column).
        val idx = ShortArray(GRID_D * (GRID_W - 1) * 2 + GRID_W * (GRID_D - 1) * 2)
        var ii = 0
        for (r in 0 until GRID_D) {
            for (c in 0 until GRID_W - 1) {
                idx[ii++] = (r * GRID_W + c).toShort()
                idx[ii++] = (r * GRID_W + c + 1).toShort()
            }
        }
        for (c in 0 until GRID_W) {
            for (r in 0 until GRID_D - 1) {
                idx[ii++] = (r * GRID_W + c).toShort()
                idx[ii++] = ((r + 1) * GRID_W + c).toShort()
            }
        }
        indexCount = ii
        val ibuf: ShortBuffer = ByteBuffer.allocateDirect(idx.size * 2)
            .order(ByteOrder.nativeOrder()).asShortBuffer().put(idx).also { it.position(0) }

        val ids = IntArray(2)
        GLES20.glGenBuffers(2, ids, 0)
        gridVbo = ids[0]; indexVbo = ids[1]
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, gridVbo)
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, verts.size * 4, vbuf, GLES20.GL_STATIC_DRAW)
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, indexVbo)
        GLES20.glBufferData(GLES20.GL_ELEMENT_ARRAY_BUFFER, idx.size * 2, ibuf, GLES20.GL_STATIC_DRAW)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, 0)
    }

    private fun buildWave() {
        val t = FloatArray(WAVE_N) { it.toFloat() / (WAVE_N - 1) }
        val buf = ByteBuffer.allocateDirect(t.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer().put(t).also { it.position(0) }
        val ids = IntArray(1)
        GLES20.glGenBuffers(1, ids, 0)
        waveVbo = ids[0]
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, waveVbo)
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, t.size * 4, buf, GLES20.GL_STATIC_DRAW)
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

    private fun makePcmTexture(): Int {
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ids[0])
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        val zero = ByteBuffer.allocateDirect(PCM_LEN * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer()
        GLES30.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, GLES30.GL_R16F,
            PCM_LEN, 1, 0, GLES30.GL_RED, GLES20.GL_FLOAT, zero
        )
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        return ids[0]
    }

    override fun onResize(width: Int, height: Int, aspect: Float) {
        this.aspect = aspect
    }

    override fun draw(pcm: FloatArray, bands: FloatArray, timeSec: Float, dim: Float) {
        val env = BeatPulse.envelope

        // Advance the scroll clock (rows/sec), decoupled from framerate.
        if (lastT < 0f) lastT = timeSec
        val dt = (timeSec - lastT).coerceIn(0f, 0.1f)
        lastT = timeSec
        scrollAccum += dt * SCROLL_RATE

        uploadWaveform(pcm)
        commitHistoryRows()
        val frac = scrollAccum

        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE)   // additive on black

        // ---- Terrain wireframe ----
        GLES20.glUseProgram(terrainProg)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, histTex)
        GLES20.glUniform1i(tHist, 0)
        GLES20.glUniform1f(tAspect, aspect)
        GLES20.glUniform1f(tHead, head.toFloat())
        GLES20.glUniform1f(tRows, ROWS.toFloat())
        GLES20.glUniform1f(tFrac, frac)
        GLES20.glUniform1f(tDim, dim)
        GLES20.glUniform1f(tEnv, env)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, gridVbo)
        GLES20.glEnableVertexAttribArray(tAGrid)
        GLES20.glVertexAttribPointer(tAGrid, 2, GLES20.GL_FLOAT, false, 0, 0)
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, indexVbo)
        GLES20.glDrawElements(GLES20.GL_LINES, indexCount, GLES20.GL_UNSIGNED_SHORT, 0)
        GLES20.glDisableVertexAttribArray(tAGrid)

        // ---- Foreground PCM waveform ----
        GLES20.glUseProgram(waveProg)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, pcmTex)
        GLES20.glUniform1i(wPcm, 1)
        GLES20.glUniform1f(wAspect, aspect)
        GLES20.glUniform1f(wDim, dim)
        GLES20.glUniform1f(wEnv, env)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, waveVbo)
        GLES20.glEnableVertexAttribArray(wAT)
        GLES20.glVertexAttribPointer(wAT, 1, GLES20.GL_FLOAT, false, 0, 0)
        GLES20.glDrawArrays(GLES20.GL_LINE_STRIP, 0, WAVE_N)
        GLES20.glDisableVertexAttribArray(wAT)

        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
    }

    /** Upload the live raw waveform every frame so the foreground line stays smooth. */
    private fun uploadWaveform(pcm: FloatArray) {
        val n = if (pcm.size < PCM_LEN) pcm.size else PCM_LEN
        pcmBuf.clear()
        pcmBuf.put(pcm, 0, n)
        while (pcmBuf.position() < PCM_LEN) pcmBuf.put(0f)
        pcmBuf.position(0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, pcmTex)
        GLES20.glTexSubImage2D(
            GLES20.GL_TEXTURE_2D, 0, 0, 0, PCM_LEN, 1,
            GLES30.GL_RED, GLES20.GL_FLOAT, pcmBuf
        )
    }

    /** Commit new spectrum history rows into the ring at the fixed scroll rate. */
    private fun commitHistoryRows() {
        if (scrollAccum < 1f) return
        val mags = SpectrumData.magnitudes
        val peaks = SpectrumData.peaks
        rowBuf.clear()
        for (i in 0 until BINS) { rowBuf.put(mags[i]); rowBuf.put(peaks[i]) }
        rowBuf.position(0)
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
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, 0)
    }
}
