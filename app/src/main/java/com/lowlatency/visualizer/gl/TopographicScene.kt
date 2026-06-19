package com.lowlatency.visualizer.gl

import android.opengl.GLES20
import android.opengl.GLES30
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer

/**
 * Visual 4 — "Topographic Bass Matrix".
 *
 * A dense wireframe grid across the bottom half of the screen whose vertices
 * are displaced on the Y axis by the FFT spectrum (sampled in the vertex
 * shader). Sub-bass columns roll as broad hills; high-frequency columns spike
 * into needles. A scrolling value-noise term turns the static ridges into a
 * moving mountain range, and depth-fading sinks the back of the grid into black.
 *
 * Rendered as additive wireframe lines (no depth buffer in our EGL config, so
 * additive over black gives clean order-independent glow).
 */
class TopographicScene : GlScene {

    companion object {
        private const val GRID_W = 96     // columns (frequency axis)
        private const val GRID_D = 48     // depth rows
        private const val BINS = 128

        private const val VERTEX_SHADER = """#version 300 es
            layout(location = 0) in vec2 aGrid;   // (x,z) in [0,1]
            uniform float u_time;
            uniform float u_aspectRatio;
            uniform sampler2D u_spectrum;         // BINS x 1, magnitude 0..1
            out float v_fog;
            out float v_height;

            float hash(vec2 p) { return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453); }
            float vnoise(vec2 p) {
                vec2 i = floor(p), f = fract(p);
                f = f * f * (3.0 - 2.0 * f);
                float a = hash(i), b = hash(i + vec2(1.0, 0.0));
                float c = hash(i + vec2(0.0, 1.0)), d = hash(i + vec2(1.0, 1.0));
                return mix(mix(a, b, f.x), mix(c, d, f.x), f.y);
            }

            void main() {
                float x  = aGrid.x * 2.0 - 1.0;        // -1..1
                float zt = aGrid.y;                    // 0 front .. 1 back

                float spec = texture(u_spectrum, vec2(aGrid.x, 0.5)).r;
                // Scrolling terrain so the range visibly moves toward the viewer.
                float terr = vnoise(vec2(aGrid.x * 6.0, aGrid.y * 6.0 - u_time * 0.8));
                float h = spec * (0.35 + 0.65 * terr);
                // High-frequency columns sharpen into needle spikes.
                h *= mix(1.0, 1.0 + spec * 2.5, aGrid.x);

                // Fake perspective: rows recede + converge toward the centre.
                float depth = mix(1.2, 6.0, zt);
                float persp = 1.0 / depth;
                float px = (x * 1.7 * persp) / u_aspectRatio;   // aspect-correct
                float py = -0.45 + h * 1.3 * persp;

                gl_Position = vec4(px, py, 0.0, 1.0);
                v_fog = zt;
                v_height = h;
            }
        """

        private const val FRAGMENT_SHADER = """#version 300 es
            precision highp float;
            uniform float u_dim;
            in float v_fog;
            in float v_height;
            out vec4 fragColor;

            vec3 palette(float t) {
                return 0.5 + 0.5 * cos(6.28318 * (t + vec3(0.0, 0.33, 0.67)));
            }

            void main() {
                float fade = pow(1.0 - v_fog, 1.6);     // back rows -> absolute dark
                vec3 col = palette(0.55 - v_height * 0.4);
                col *= (0.8 + v_height * 1.4);          // taller = brighter (bright floor)
                col *= fade;
                col *= 1.6 + v_height * 2.5;            // HDR lift on the whole wireframe
                fragColor = vec4(col * u_dim, 1.0);
            }
        """
    }

    private val analyzer = SpectrumAnalyzer(bins = BINS)
    private val specUpload: FloatBuffer = ByteBuffer
        .allocateDirect(BINS * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()

    private var program = 0
    private var aGrid = 0
    private var uTime = 0
    private var uAspect = 0
    private var uSpectrum = 0
    private var uDim = 0

    private var gridVbo = 0
    private var indexVbo = 0
    private var indexCount = 0
    private var specTex = 0
    private var aspect = 1f
    private var lastTime = -1f

    override fun onCreated() {
        program = ShaderUtil.buildProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        aGrid = GLES20.glGetAttribLocation(program, "aGrid")
        uTime = GLES20.glGetUniformLocation(program, "u_time")
        uAspect = GLES20.glGetUniformLocation(program, "u_aspectRatio")
        uSpectrum = GLES20.glGetUniformLocation(program, "u_spectrum")
        uDim = GLES20.glGetUniformLocation(program, "u_dim")

        // --- grid vertices (x,z in [0,1]) ---
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

        // --- wireframe line indices (horizontal + depth) ---
        val idx = ShortArray(GRID_D * (GRID_W - 1) * 2 + GRID_W * (GRID_D - 1) * 2)
        var ii = 0
        for (r in 0 until GRID_D) {                 // horizontal lines
            for (c in 0 until GRID_W - 1) {
                idx[ii++] = (r * GRID_W + c).toShort()
                idx[ii++] = (r * GRID_W + c + 1).toShort()
            }
        }
        for (c in 0 until GRID_W) {                 // depth lines
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

        // --- spectrum texture (sampled in the vertex shader) ---
        val tex = IntArray(1)
        GLES20.glGenTextures(1, tex, 0)
        specTex = tex[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, specTex)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST)
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, GLES30.GL_R32F,
            BINS, 1, 0, GLES30.GL_RED, GLES20.GL_FLOAT, null
        )
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
    }

    override fun onResize(width: Int, height: Int, aspect: Float) {
        this.aspect = aspect
    }

    override fun draw(pcm: FloatArray, bands: FloatArray, timeSec: Float, dim: Float) {
        val dt = if (lastTime < 0f) 0.016f else (timeSec - lastTime).coerceIn(0f, 0.05f)
        lastTime = timeSec

        analyzer.update(pcm, dt)
        specUpload.clear(); specUpload.put(analyzer.magnitudes).position(0)

        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE)   // additive wireframe

        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, specTex)
        GLES20.glTexSubImage2D(
            GLES20.GL_TEXTURE_2D, 0, 0, 0, BINS, 1,
            GLES30.GL_RED, GLES20.GL_FLOAT, specUpload
        )
        GLES20.glUniform1i(uSpectrum, 0)
        GLES20.glUniform1f(uTime, timeSec)
        GLES20.glUniform1f(uAspect, aspect)
        GLES20.glUniform1f(uDim, dim)

        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, gridVbo)
        GLES20.glEnableVertexAttribArray(aGrid)
        GLES20.glVertexAttribPointer(aGrid, 2, GLES20.GL_FLOAT, false, 0, 0)
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, indexVbo)
        GLES20.glDrawElements(GLES20.GL_LINES, indexCount, GLES20.GL_UNSIGNED_SHORT, 0)

        GLES20.glDisableVertexAttribArray(aGrid)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, 0)
    }

    override fun onDeactivate() {
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, 0)
    }
}
