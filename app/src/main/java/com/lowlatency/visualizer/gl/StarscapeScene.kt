package com.lowlatency.visualizer.gl

import android.opengl.GLES20
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.random.Random

/**
 * Visual 9 — "Starscape".
 *
 * A 3D hyperspace star field flying toward the viewer. Each star loops through
 * depth (0 = far, 1 = near) and expands radially from the centre as it
 * approaches, giving parallax warp streaks. Audio-reactive:
 *   - LOWS  accelerate the warp and enlarge + HDR-boost the stars (the beat
 *           kicks the field into a faster flight),
 *   - HIGHS drive per-star twinkle.
 *
 * GPU points with additive blending over black. Depth is accumulated on the CPU
 * (so bass acceleration is smooth and frame-rate independent) and looped in the
 * vertex shader. Stars fade in at the horizon and out at the screen edge so the
 * depth wrap-around is invisible.
 */
class StarscapeScene : GlScene {

    companion object {
        private const val STARS = 1800
        private const val BASE_SPEED = 0.06f      // depth units / second
        private const val BASS_BOOST = 0.28f      // extra speed at full bass

        private const val VERTEX_SHADER = """#version 300 es
            layout(location = 0) in vec3 aStar;   // xy = direction (-1..1), z = depth seed (0..1)
            uniform float u_travel;
            uniform float u_aspect;
            uniform vec3  u_bands;                 // x=low, y=mid, z=high
            out float v_bright;
            out float v_seed;

            void main() {
                float z = fract(aStar.z + u_travel);          // 0 far .. 1 near
                // Radial expansion: stars rush outward from the centre as z->1.
                float spread = z / (1.0 - z * 0.98);
                vec2 p = aStar.xy * spread;
                p.x /= u_aspect;

                gl_Position = vec4(p, 0.0, 1.0);

                float size = mix(0.6, 3.2, z) * (1.0 + u_bands.x * 2.0);
                gl_PointSize = size;

                // Fade in at the horizon, out at the edge, so the wrap is hidden.
                float fade = smoothstep(0.0, 0.05, z) * (1.0 - smoothstep(0.92, 1.0, z));
                v_bright = z * z * fade;
                v_seed = aStar.x * 43.0 + aStar.y * 71.0;
            }
        """

        private const val FRAGMENT_SHADER = """#version 300 es
            precision highp float;
            uniform float u_time;
            uniform float u_dim;
            uniform vec3  u_bands;
            in float v_bright;
            in float v_seed;
            out vec4 fragColor;

            void main() {
                vec2 c = gl_PointCoord * 2.0 - 1.0;
                float d = dot(c, c);
                if (d > 1.0) discard;
                float glow = exp(-d * 3.0);

                float twinkle = 0.7 + 0.3 * sin(u_time * 6.0 + v_seed);
                twinkle = mix(1.0, twinkle, u_bands.z);        // highs drive twinkle

                // Subtle colour variety from cool white to warm white.
                vec3 tint = mix(vec3(0.6, 0.75, 1.0), vec3(1.0, 0.95, 0.85), fract(v_seed));
                vec3 col = tint * v_bright * glow * twinkle;
                col *= 1.0 + u_bands.x * 2.0;                  // HDR on the beat

                fragColor = vec4(col * u_dim, 1.0);
            }
        """
    }

    private val stars: FloatBuffer = ByteBuffer
        .allocateDirect(STARS * 3 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()

    private var program = 0
    private var aStar = 0
    private var uTravel = 0
    private var uAspect = 0
    private var uTime = 0
    private var uDim = 0
    private var uBands = 0

    private var vbo = 0
    private var aspect = 1f
    private var travel = 0f
    private var lastTime = -1f

    override fun onCreated() {
        program = ShaderUtil.buildProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        aStar = GLES20.glGetAttribLocation(program, "aStar")
        uTravel = GLES20.glGetUniformLocation(program, "u_travel")
        uAspect = GLES20.glGetUniformLocation(program, "u_aspect")
        uTime = GLES20.glGetUniformLocation(program, "u_time")
        uDim = GLES20.glGetUniformLocation(program, "u_dim")
        uBands = GLES20.glGetUniformLocation(program, "u_bands")

        // Random directions + depth seeds. xy in [-1,1] is the radial direction
        // from screen centre; z is the star's starting depth.
        val rnd = Random(0x5EED)
        val data = FloatArray(STARS * 3)
        var i = 0
        repeat(STARS) {
            data[i++] = rnd.nextFloat() * 2f - 1f
            data[i++] = rnd.nextFloat() * 2f - 1f
            data[i++] = rnd.nextFloat()
        }
        stars.clear(); stars.put(data); stars.position(0)

        val ids = IntArray(1)
        GLES20.glGenBuffers(1, ids, 0)
        vbo = ids[0]
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo)
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, data.size * 4, stars, GLES20.GL_STATIC_DRAW)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
    }

    override fun onResize(width: Int, height: Int, aspect: Float) {
        this.aspect = aspect
    }

    override fun draw(pcm: FloatArray, bands: FloatArray, timeSec: Float, dim: Float) {
        val dt = if (lastTime < 0f) 0.016f else (timeSec - lastTime).coerceIn(0f, 0.05f)
        lastTime = timeSec

        // Accumulate flight distance — bass accelerates the warp.
        travel = (travel + (BASE_SPEED + bands[0] * BASS_BOOST) * dt) % 1f

        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE)   // additive glow

        GLES20.glUseProgram(program)
        GLES20.glUniform1f(uTravel, travel)
        GLES20.glUniform1f(uAspect, aspect)
        GLES20.glUniform1f(uTime, timeSec)
        GLES20.glUniform1f(uDim, dim)
        GLES20.glUniform3f(uBands, bands[0], bands[1], bands[2])

        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo)
        GLES20.glEnableVertexAttribArray(aStar)
        GLES20.glVertexAttribPointer(aStar, 3, GLES20.GL_FLOAT, false, 0, 0)
        GLES20.glDrawArrays(GLES20.GL_POINTS, 0, STARS)
        GLES20.glDisableVertexAttribArray(aStar)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
    }

    override fun onDeactivate() {
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
    }
}
