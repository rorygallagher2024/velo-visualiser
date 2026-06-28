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
 * A clean additive wireframe on pure black, viewed from a high angle so the
 * whole canyon is on screen at once. The lateral axis is frequency (the 128-bin
 * spectrum); the **depth axis is time** — each row is a past spectrum frame,
 * newest at the bottom, scrolling up toward the horizon as it ages. Height is
 * magnitude; the **per-bin peak-hold** lights glowing caps on the ridges, so a
 * transient you heard a moment ago leaves a bright crest that drifts away.
 *
 * Geometry is a vertex-displaced grid that samples a ring-buffer history texture
 * in the vertex shader — cheap, crisp and reliable (no ray-march artefacts).
 */
class SpectralCanyonScene(private val classic: Boolean = false) : GlScene {

    // The bright front lip sits on the bottom edge; the menu blur flips the GL
    // surface and would mirror it to the top. Dim with the scrim only instead.
    override val suppressMenuBlur get() = true

    companion object {
        private const val BINS = 128          // spectrum bins (history texture width)
        private const val GRID_W = 100        // mesh columns (frequency resolution)
        private const val GRID_D = 80         // mesh rows (time depth)
        private const val ROWS = GRID_D       // history ring rows == mesh depth (1 row : 1 frame)
        private const val SCROLL_RATE = 18f   // history rows committed per second (scroll speed)
        private const val MAX_COMMITS = 4     // cap rows added per frame (low-fps safety)

        private const val TERRAIN_VS = """#version 300 es
            layout(location = 0) in vec2 aGrid;   // (x in [0,1] freq, zt in [0,1] time)
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

                // High-angle (near top-down) view: the time axis spreads up the whole
                // screen (newest at the bottom, oldest near the top) with only mild
                // perspective, and the width fills the display with a gentle taper.
                float xc = x * 2.0 - 1.0;                 // -1..1 frequency
                float persp = 1.0 / (1.0 + zt * 1.3);     // mild depth compression
                float px = xc * 0.96 * mix(1.0, 0.72, zt);
                // Height shrinks with distance so far/old ridges flatten into the
                // horizon instead of spiking up to the top edge of the screen.
                float py = -0.9 + zt * 4.0 * persp + mag * 0.55 * persp;

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
            uniform float u_fill;    // 1 = filled-surface pass, 0 = wireframe pass
            uniform float u_classic; // 1 = original cosine palette, 0 = curated gradient
            in float v_zt;
            in float v_height;
            in float v_peak;
            in float v_fx;
            out vec4 fragColor;

            // "Classic" palette: the original IQ cosine rainbow (freq + height).
            vec3 palette(float t) {
                return 0.5 + 0.5 * cos(6.28318 * (t + vec3(0.0, 0.33, 0.60)));
            }

            // Curated valley->peak gradient: deep indigo -> blue -> cyan -> magenta
            // -> white-hot, so height reads as an intentional colour journey.
            vec3 terrainColor(float h) {
                vec3 c = mix(vec3(0.10, 0.05, 0.32), vec3(0.05, 0.40, 0.85), smoothstep(0.0, 0.22, h));
                c = mix(c, vec3(0.00, 0.85, 0.90), smoothstep(0.18, 0.48, h));
                c = mix(c, vec3(0.90, 0.20, 0.85), smoothstep(0.48, 0.78, h));
                c = mix(c, vec3(1.30, 1.05, 1.35), smoothstep(0.82, 1.0, h));
                return c;
            }

            void main() {
                // Fade in from black at the bottom so new rows emerge smoothly (no
                // pop/flicker as they're committed), and dissolve into a dark horizon
                // before the top edge so old rows melt away instead of piling there.
                float fade = smoothstep(0.0, 0.10, v_zt) * (1.0 - smoothstep(0.45, 0.82, v_zt));
                // Two selectable palettes: the original cosine rainbow ("Classic")
                // or the curated indigo -> cyan -> magenta -> white-hot gradient.
                vec3 col, capCol;
                if (u_classic > 0.5) {
                    col = palette(0.12 + v_fx * 0.45 + v_height * 0.2);
                    capCol = palette(0.55 + v_fx * 0.4);
                } else {
                    col = terrainColor(v_height)
                        * mix(vec3(1.10, 0.90, 1.00), vec3(0.85, 1.00, 1.18), v_fx);
                    capCol = vec3(1.10, 1.15, 1.35);
                }
                col *= 0.45 + v_height * 1.8;               // taller = brighter

                // Peak caps flare on the beat via u_env (white-hot in the curated palette).
                float cap = smoothstep(0.18, 0.6, v_peak);
                col += capCol * cap * (0.6 + u_env * 2.0);

                col *= fade;
                col *= 1.5 + v_height * 2.2;                // HDR lift for bloom

                // Fill pass renders dimmer and only on the ridges (valleys stay
                // black, preserving the pure-black background); the wireframe pass
                // (u_fill = 0) stays at full strength on top.
                float fillScale = mix(1.0, 0.2 * smoothstep(0.0, 0.18, v_height), u_fill);
                fragColor = vec4(col * u_dim * fillScale, 1.0);
            }
        """
    }

    private val rowBuf: FloatBuffer = ByteBuffer
        .allocateDirect(BINS * 2 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()

    private var terrainProg = 0
    private var tAGrid = 0
    private var tHead = 0
    private var tRows = 0
    private var tFrac = 0
    private var tHist = 0
    private var tDim = 0
    private var tEnv = 0
    private var tFill = 0
    private var tClassic = 0

    private var gridVbo = 0
    private var indexVbo = 0
    private var indexCount = 0
    private var fillIbo = 0
    private var fillCount = 0

    private var histTex = 0
    private var writeRow = 0
    private var head = 0
    private var lastT = -1f
    private var scrollAccum = 0f

    override fun onCreated() {
        terrainProg = ShaderUtil.buildProgram(TERRAIN_VS, TERRAIN_FS)
        tAGrid = GLES20.glGetAttribLocation(terrainProg, "aGrid")
        tHead = GLES20.glGetUniformLocation(terrainProg, "u_head")
        tRows = GLES20.glGetUniformLocation(terrainProg, "u_rows")
        tFrac = GLES20.glGetUniformLocation(terrainProg, "u_frac")
        tHist = GLES20.glGetUniformLocation(terrainProg, "u_hist")
        tDim = GLES20.glGetUniformLocation(terrainProg, "u_dim")
        tEnv = GLES20.glGetUniformLocation(terrainProg, "u_env")
        tFill = GLES20.glGetUniformLocation(terrainProg, "u_fill")
        tClassic = GLES20.glGetUniformLocation(terrainProg, "u_classic")

        buildGrid()
        histTex = makeRingTexture()
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

        // Filled-surface triangle indices (two triangles per grid cell).
        val tri = ShortArray((GRID_W - 1) * (GRID_D - 1) * 6)
        var ti = 0
        for (r in 0 until GRID_D - 1) {
            for (c in 0 until GRID_W - 1) {
                val v00 = (r * GRID_W + c).toShort()
                val v01 = (r * GRID_W + c + 1).toShort()
                val v10 = ((r + 1) * GRID_W + c).toShort()
                val v11 = ((r + 1) * GRID_W + c + 1).toShort()
                tri[ti++] = v00; tri[ti++] = v01; tri[ti++] = v10
                tri[ti++] = v01; tri[ti++] = v11; tri[ti++] = v10
            }
        }
        fillCount = ti
        val tbuf: ShortBuffer = ByteBuffer.allocateDirect(tri.size * 2)
            .order(ByteOrder.nativeOrder()).asShortBuffer().put(tri).also { it.position(0) }

        val ids = IntArray(3)
        GLES20.glGenBuffers(3, ids, 0)
        gridVbo = ids[0]; indexVbo = ids[1]; fillIbo = ids[2]
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, gridVbo)
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, verts.size * 4, vbuf, GLES20.GL_STATIC_DRAW)
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, indexVbo)
        GLES20.glBufferData(GLES20.GL_ELEMENT_ARRAY_BUFFER, idx.size * 2, ibuf, GLES20.GL_STATIC_DRAW)
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, fillIbo)
        GLES20.glBufferData(GLES20.GL_ELEMENT_ARRAY_BUFFER, tri.size * 2, tbuf, GLES20.GL_STATIC_DRAW)
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
        // No surface-dependent state: the projection fills NDC and is aspect-independent.
    }

    override fun draw(pcm: FloatArray, bands: FloatArray, timeSec: Float, dim: Float) {
        val env = BeatPulse.envelope

        // Advance the scroll clock (rows/sec), decoupled from framerate.
        if (lastT < 0f) lastT = timeSec
        val dt = (timeSec - lastT).coerceIn(0f, 0.1f)
        lastT = timeSec
        scrollAccum += dt * SCROLL_RATE

        commitHistoryRows()
        val frac = scrollAccum

        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE)   // additive on black

        GLES20.glUseProgram(terrainProg)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, histTex)
        GLES20.glUniform1i(tHist, 0)
        GLES20.glUniform1f(tHead, head.toFloat())
        GLES20.glUniform1f(tRows, ROWS.toFloat())
        GLES20.glUniform1f(tFrac, frac)
        GLES20.glUniform1f(tDim, dim)
        GLES20.glUniform1f(tEnv, env)
        GLES20.glUniform1f(tClassic, if (classic) 1f else 0f)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, gridVbo)
        GLES20.glEnableVertexAttribArray(tAGrid)
        GLES20.glVertexAttribPointer(tAGrid, 2, GLES20.GL_FLOAT, false, 0, 0)

        // Fill pass: dim glowing surface under the wireframe (ridges only).
        GLES20.glUniform1f(tFill, 1f)
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, fillIbo)
        GLES20.glDrawElements(GLES20.GL_TRIANGLES, fillCount, GLES20.GL_UNSIGNED_SHORT, 0)

        // Wireframe pass: crisp lines on top.
        GLES20.glUniform1f(tFill, 0f)
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, indexVbo)
        GLES20.glDrawElements(GLES20.GL_LINES, indexCount, GLES20.GL_UNSIGNED_SHORT, 0)

        GLES20.glDisableVertexAttribArray(tAGrid)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, 0)
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
