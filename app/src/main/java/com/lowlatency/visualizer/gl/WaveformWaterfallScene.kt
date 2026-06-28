package com.lowlatency.visualizer.gl

import android.opengl.GLES20
import android.opengl.GLES30
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import com.lowlatency.visualizer.BeatPulse
import kotlin.math.sqrt

/**
 * "Waterfall Scope" — the raw-PCM sibling of [SpectralCanyonScene].
 *
 * Where the Canyon sculpts a terrain from the 128-bin *spectrum*, this scene
 * keeps a rolling history of the **actual waveform**: each committed row is a
 * frame of raw PCM, drawn as a single thin oscilloscope trace. The traces stack
 * into depth — newest bright at the front (bottom), older ones dimmer and
 * receding toward a dark horizon — so you watch the wave shape itself flow away
 * from you in 3D. Flat 2D scope lines living in a 3D stack; rawer than the FFT.
 *
 * Same machinery as the Canyon: a ring-buffer history texture sampled in the
 * vertex shader, frame-rate-independent scroll, additive glow on pure black.
 * Per-row loudness (RMS) and per-sample crest amplitude drive the brightness, so
 * loud transients flare the whole trace and the wave's peaks glow.
 */
class WaveformWaterfallScene : GlScene {

    // The bright newest trace sits on the bottom edge; the menu blur flips the GL
    // surface and would mirror it to the top. Dim with the scrim only instead
    // (same reasoning as Spectral Canyon's front lip).
    override val suppressMenuBlur get() = true

    companion object {
        private const val POINTS = 256        // samples per trace (history texture width)
        private const val ROWS = 80           // stacked traces (history depth)
        private const val SCROLL_RATE = 20f   // traces committed per second (scroll speed)
        private const val MAX_COMMITS = 4      // cap rows added per frame (low-fps safety)

        private const val TRACE_VS = """#version 300 es
            layout(location = 0) in vec2 aGrid;   // (x in [0,1] along trace, zt in [0,1] time)
            uniform float u_head;                 // newest ring row index
            uniform float u_rows;
            uniform float u_frac;                 // 0..1 smooth scroll between commits
            uniform sampler2D u_hist;             // R = signed sample, G = frame RMS
            out float v_zt;
            out float v_amp;                      // |sample| — crest height
            out float v_rms;                      // per-frame loudness

            const float WAVE_GAIN = 10.0;          // sensitivity: higher = more wiggle for quiet sound

            void main() {
                float x   = aGrid.x;
                float zt0 = aGrid.y;
                // Sample this trace's fixed history row (exact texel centre).
                float ring = (u_head - zt0 * (u_rows - 1.0) + 0.5) / u_rows;
                vec2 s = texture(u_hist, vec2(x, ring)).rg;
                // Boost + soft-clip the raw sample so normal-volume audio wiggles
                // visibly while loud transients (claps) saturate instead of flying
                // off-screen. x/(1+|x|) is a cheap, driver-safe soft saturator.
                float g = s.r * WAVE_GAIN;
                float wave = g / (1.0 + abs(g));

                // Smooth scroll: shift the whole stack back by the fractional
                // progress toward the next committed row (decouples from framerate).
                float zt = zt0 + u_frac / (u_rows - 1.0);

                // Stacked oscilloscope traces receding upward with gentle
                // perspective: newest flat across the bottom, older compressed and
                // settling toward a horizon. The wave rides on each trace's baseline.
                float xc = x * 2.0 - 1.0;
                float persp = 1.0 / (1.0 + zt * 0.85);    // depth compression
                float baseY = -0.80 + zt * 1.58;           // bottom (new) -> top (old)
                float px = xc * mix(1.0, 0.82, zt);        // slight width taper with depth
                float py = baseY + wave * 0.15 * persp;

                gl_Position = vec4(px, py, 0.0, 1.0);
                v_zt = zt;
                v_amp = abs(wave);
                v_rms = s.g;
            }
        """

        private const val TRACE_FS = """#version 300 es
            precision highp float;
            uniform float u_dim;
            uniform float u_env;                  // global beat envelope
            in float v_zt;
            in float v_amp;
            in float v_rms;
            out vec4 fragColor;

            void main() {
                // Depth shaping: a hair of fade-in stops the newest trace popping at
                // the bottom edge; a long fade-out melts old traces into a black
                // horizon instead of piling them against the top edge.
                float fadeIn = smoothstep(0.0, 0.04, v_zt);
                float fadeOut = 1.0 - smoothstep(0.70, 0.96, v_zt);
                float depth = fadeIn * fadeOut;

                // Front-to-back colour journey: electric white-cyan up close,
                // cooling to a deep indigo as the wave recedes.
                vec3 near = vec3(0.65, 1.00, 1.18);
                vec3 far  = vec3(0.12, 0.26, 0.80);
                vec3 col = mix(near, far, smoothstep(0.0, 0.72, v_zt));

                // Brightness: a quiet baseline trace, lifted by the wave's crests
                // (|sample|) and the frame's loudness (RMS), flaring on the beat.
                float bright = 0.30 + v_amp * 1.7 + v_rms * 4.0;
                bright *= 0.8 + u_env * 1.4;
                col *= bright * depth;

                col *= 1.3 + v_amp * 2.2;              // HDR lift so crests bloom
                fragColor = vec4(col * u_dim, 1.0);
            }
        """
    }

    private val rowBuf: FloatBuffer = ByteBuffer
        .allocateDirect(POINTS * 2 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()

    private var traceProg = 0
    private var tAGrid = 0
    private var tHead = 0
    private var tRows = 0
    private var tFrac = 0
    private var tHist = 0
    private var tDim = 0
    private var tEnv = 0

    private var gridVbo = 0
    private var indexVbo = 0
    private var indexCount = 0

    private var histTex = 0
    private var writeRow = 0
    private var head = 0
    private var lastT = -1f
    private var scrollAccum = 0f

    override fun onCreated() {
        traceProg = ShaderUtil.buildProgram(TRACE_VS, TRACE_FS)
        tAGrid = GLES20.glGetAttribLocation(traceProg, "aGrid")
        tHead = GLES20.glGetUniformLocation(traceProg, "u_head")
        tRows = GLES20.glGetUniformLocation(traceProg, "u_rows")
        tFrac = GLES20.glGetUniformLocation(traceProg, "u_frac")
        tHist = GLES20.glGetUniformLocation(traceProg, "u_hist")
        tDim = GLES20.glGetUniformLocation(traceProg, "u_dim")
        tEnv = GLES20.glGetUniformLocation(traceProg, "u_env")

        buildGrid()
        histTex = makeRingTexture()
    }

    private fun buildGrid() {
        // Grid vertices (x along the trace, zt down the stack), both in [0,1].
        val verts = FloatArray(POINTS * ROWS * 2)
        var vi = 0
        for (r in 0 until ROWS) {
            for (c in 0 until POINTS) {
                verts[vi++] = c.toFloat() / (POINTS - 1)
                verts[vi++] = r.toFloat() / (ROWS - 1)
            }
        }
        val vbuf = ByteBuffer.allocateDirect(verts.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer().put(verts).also { it.position(0) }

        // Each row is its own polyline (horizontal segments only — no vertical
        // links, so the traces read as discrete stacked scope lines, not a mesh).
        val idx = ShortArray(ROWS * (POINTS - 1) * 2)
        var ii = 0
        for (r in 0 until ROWS) {
            for (c in 0 until POINTS - 1) {
                idx[ii++] = (r * POINTS + c).toShort()
                idx[ii++] = (r * POINTS + c + 1).toShort()
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

    private fun makeRingTexture(): Int {
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ids[0])
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_REPEAT)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        val zero = ByteBuffer.allocateDirect(POINTS * ROWS * 2 * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer()
        GLES30.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, GLES30.GL_RG16F,
            POINTS, ROWS, 0, GLES30.GL_RG, GLES20.GL_FLOAT, zero
        )
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        return ids[0]
    }

    override fun onResize(width: Int, height: Int, aspect: Float) {
        // No surface-dependent state: the projection fills NDC and is aspect-independent.
    }

    override fun draw(pcm: FloatArray, bands: FloatArray, timeSec: Float, dim: Float) {
        val env = BeatPulse.envelope

        // Advance the scroll clock (traces/sec), decoupled from framerate.
        if (lastT < 0f) lastT = timeSec
        val dt = (timeSec - lastT).coerceIn(0f, 0.1f)
        lastT = timeSec
        scrollAccum += dt * SCROLL_RATE

        commitHistoryRows(pcm)

        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE)   // additive on black

        GLES20.glUseProgram(traceProg)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, histTex)
        GLES20.glUniform1i(tHist, 0)
        GLES20.glUniform1f(tHead, head.toFloat())
        GLES20.glUniform1f(tRows, ROWS.toFloat())
        GLES20.glUniform1f(tFrac, scrollAccum)
        GLES20.glUniform1f(tDim, dim)
        GLES20.glUniform1f(tEnv, env)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, gridVbo)
        GLES20.glEnableVertexAttribArray(tAGrid)
        GLES20.glVertexAttribPointer(tAGrid, 2, GLES20.GL_FLOAT, false, 0, 0)

        GLES20.glLineWidth(1f)
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, indexVbo)
        GLES20.glDrawElements(GLES20.GL_LINES, indexCount, GLES20.GL_UNSIGNED_SHORT, 0)

        GLES20.glDisableVertexAttribArray(tAGrid)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, 0)
    }

    /** Commit new raw-PCM traces into the ring at the fixed scroll rate. */
    private fun commitHistoryRows(pcm: FloatArray) {
        if (scrollAccum < 1f) return
        // Decimate the PCM window down to POINTS columns and measure its loudness.
        val stride = (pcm.size / POINTS).coerceAtLeast(1)
        var sumSq = 0f
        rowBuf.clear()
        for (i in 0 until POINTS) {
            val sample = pcm[(i * stride).coerceAtMost(pcm.size - 1)]
            rowBuf.put(sample); rowBuf.put(0f)       // G filled with RMS below
            sumSq += sample * sample
        }
        val rms = sqrt(sumSq / POINTS)
        for (i in 0 until POINTS) rowBuf.put(i * 2 + 1, rms)
        rowBuf.position(0)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, histTex)
        var commits = 0
        while (scrollAccum >= 1f && commits < MAX_COMMITS) {
            head = writeRow
            rowBuf.position(0)
            GLES20.glTexSubImage2D(
                GLES20.GL_TEXTURE_2D, 0, 0, writeRow, POINTS, 1,
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
