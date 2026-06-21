package com.lowlatency.visualizer.gl

import android.opengl.GLES20
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Odyssey world 2 — "Terrain". A neon wireframe landscape that flows toward the
 * camera: rows of emissive ridgelines whose heights come from a scrolling value-
 * noise field, lifted by the bass. Rows recycle from the horizon so the flight is
 * endless; the terrain stays stable underfoot (height is sampled in world space,
 * not per-row), so it reads as flying *over* a place rather than a morphing blob.
 */
class TerrainMovement : OdysseyMovement {

    private var program = 0
    private var aGrid = 0
    private var uTravel = 0; private var uAspect = 0; private var uBands = 0; private var uBeat = 0
    private var vbo = 0
    private var aspect = 1f

    override fun onCreated() {
        program = ShaderUtil.buildProgram(VS, FS)
        aGrid = GLES20.glGetAttribLocation(program, "aGrid")
        uTravel = GLES20.glGetUniformLocation(program, "u_travel")
        uAspect = GLES20.glGetUniformLocation(program, "u_aspect")
        uBands = GLES20.glGetUniformLocation(program, "u_bands")
        uBeat = GLES20.glGetUniformLocation(program, "u_beat")

        // Row-major grid: for each row, COLS verts along x. (u, rowIndex) per vert.
        val data = FloatArray(ROWS * COLS * 2)
        var i = 0
        for (r in 0 until ROWS) {
            for (c in 0 until COLS) {
                data[i++] = c / (COLS - 1f)   // u in [0,1]
                data[i++] = r.toFloat()       // row index
            }
        }
        val buf = ByteBuffer.allocateDirect(data.size * 4).order(ByteOrder.nativeOrder())
            .asFloatBuffer().apply { put(data); position(0) }
        val ids = IntArray(1)
        GLES20.glGenBuffers(1, ids, 0)
        vbo = ids[0]
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo)
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, data.size * 4, buf, GLES20.GL_STATIC_DRAW)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
    }

    override fun onResize(width: Int, height: Int, aspect: Float) {
        this.aspect = aspect
    }

    override fun draw(time: Float, bands: FloatArray, travel: Float, beat: Float) {
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE)   // additive glow
        GLES20.glUseProgram(program)
        GLES20.glUniform1f(uTravel, travel)
        GLES20.glUniform1f(uAspect, aspect)
        GLES20.glUniform3f(uBands, bands[0], bands[1], bands[2])
        GLES20.glUniform1f(uBeat, beat)

        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo)
        GLES20.glEnableVertexAttribArray(aGrid)
        GLES20.glVertexAttribPointer(aGrid, 2, GLES20.GL_FLOAT, false, 0, 0)
        // One emissive ridgeline per row.
        for (r in 0 until ROWS) {
            GLES20.glDrawArrays(GLES20.GL_LINE_STRIP, r * COLS, COLS)
        }
        GLES20.glDisableVertexAttribArray(aGrid)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)

        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
    }

    companion object {
        private const val COLS = 112
        private const val ROWS = 96

        // World layout (tuned for portrait; PROJ/CAM set the horizon).
        private const val WIDTH = 26f
        private const val NEARZ = 0.6f
        private const val DEPTH = 17f
        private const val FLOW = 2.2f       // travel -> world depth (flight speed feel)
        private const val HEIGHT = 2.0f
        private const val CAM_HEIGHT = 1.15f
        private const val PROJ = 1.15f
        private const val FX = 0.16f        // noise x frequency
        private const val FZ = 0.16f        // noise z frequency

        private const val VS = """#version 300 es
            layout(location = 0) in vec2 aGrid;   // x = u (0..1), y = rowIndex
            uniform float u_travel;
            uniform float u_aspect;
            uniform vec3  u_bands;
            out float v_h;
            out float v_fade;

            float hash(vec2 p) { return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453); }
            float vnoise(vec2 p) {
                vec2 i = floor(p), f = fract(p);
                float a = hash(i), b = hash(i + vec2(1.0, 0.0));
                float c = hash(i + vec2(0.0, 1.0)), d = hash(i + vec2(1.0, 1.0));
                vec2 u = f * f * (3.0 - 2.0 * f);
                return mix(mix(a, b, u.x), mix(c, d, u.x), u.y);
            }

            const float SPACING = ${DEPTH} / float(${ROWS});

            void main() {
                float u = aGrid.x;
                float row = aGrid.y;
                // Display depth: rows scroll toward the camera and recycle to the horizon.
                float zc = ${NEARZ} + mod(row * SPACING - u_travel * ${FLOW}, ${DEPTH});
                float x = (u - 0.5) * ${WIDTH};
                // Sample height in world space so the landscape stays put as it flows.
                float worldZ = u_travel * ${FLOW} + zc;
                float n = vnoise(vec2(x * ${FX}, worldZ * ${FZ}));
                n = mix(n, vnoise(vec2(x * ${FX} * 2.3, worldZ * ${FZ} * 2.3)), 0.4); // 2 octaves
                float h = (n - 0.5) * ${HEIGHT} * (1.0 + u_bands.x * 1.5);
                float y = h - ${CAM_HEIGHT};

                vec2 sp = vec2(x / zc, y / zc) * ${PROJ};
                sp.x /= u_aspect;
                gl_Position = vec4(sp, 0.0, 1.0);

                float depth01 = (zc - ${NEARZ}) / ${DEPTH};
                v_fade = (1.0 - smoothstep(0.7, 1.0, depth01)) * smoothstep(0.0, 0.06, depth01);
                v_h = h / ${HEIGHT};
            }
        """

        private const val FS = """#version 300 es
            precision highp float;
            in float v_h;
            in float v_fade;
            uniform vec3 u_bands;
            uniform float u_beat;
            out vec4 fragColor;
            void main() {
                float hn = clamp(v_h * 0.5 + 0.5, 0.0, 1.0);
                // Valleys indigo/magenta -> ridges cyan/white.
                vec3 lo = vec3(0.6, 0.1, 0.7);
                vec3 hi = vec3(0.2, 0.9, 1.0);
                vec3 col = mix(lo, hi, hn);
                float ridge = smoothstep(0.6, 1.0, hn);
                col += ridge * vec3(0.4, 0.6, 0.8);            // glowing crests
                float bright = v_fade * (0.5 + u_beat * 0.8) * (1.0 + ridge * 1.6);
                fragColor = vec4(col * bright, 1.0);           // additive HDR -> blooms
            }
        """
    }
}
