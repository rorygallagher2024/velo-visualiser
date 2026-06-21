package com.lowlatency.visualizer.gl

import android.opengl.GLES20
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.random.Random

/**
 * Odyssey world 1 — "Nebula". A vast, calm cloud of glowing motes drifting past
 * the camera with depth parallax and soft bokeh: the journey's opener. A faint
 * deep-indigo fog backdrop keeps it lush rather than empty even in silence.
 * Stateless (position is a closed-form function of seed + shared travel), so it's
 * cheap and runs everywhere. Cool teal→violet palette, gentle bass lift, twinkle.
 */
class NebulaMovement : OdysseyMovement {

    private var bgProg = 0
    private var ubgTime = 0; private var ubgBeat = 0

    private var program = 0
    private var aSeed = 0
    private var uTravel = 0; private var uAspect = 0; private var uBands = 0
    private var uPointSize = 0; private var uBeat = 0
    private var vbo = 0
    private var aspect = 1f
    private var height = 1080f

    override fun onCreated() {
        bgProg = ShaderUtil.buildProgram(BG_VS, BG_FS)
        ubgTime = GLES20.glGetUniformLocation(bgProg, "u_time")
        ubgBeat = GLES20.glGetUniformLocation(bgProg, "u_beat")

        program = ShaderUtil.buildProgram(VS, FS)
        aSeed = GLES20.glGetAttribLocation(program, "aSeed")
        uTravel = GLES20.glGetUniformLocation(program, "u_travel")
        uAspect = GLES20.glGetUniformLocation(program, "u_aspect")
        uBands = GLES20.glGetUniformLocation(program, "u_bands")
        uPointSize = GLES20.glGetUniformLocation(program, "u_pointSize")
        uBeat = GLES20.glGetUniformLocation(program, "u_beat")

        val data = FloatArray(COUNT * 4)
        val rnd = Random(0x0DDC)
        var i = 0
        repeat(COUNT) {
            data[i++] = rnd.nextFloat() * 2f - 1f      // x spread
            data[i++] = rnd.nextFloat() * 2f - 1f      // y spread
            data[i++] = rnd.nextFloat()                // depth seed 0..1
            data[i++] = rnd.nextFloat()                // per-mote random
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
        this.height = height.toFloat()
    }

    override fun draw(time: Float, bands: FloatArray, travel: Float, beat: Float) {
        // Lush fog backdrop (opaque, kept below the bloom threshold).
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glUseProgram(bgProg)
        GLES20.glUniform1f(ubgTime, time)
        GLES20.glUniform1f(ubgBeat, beat)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 3)

        // Drifting motes (additive glow).
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE)
        GLES20.glUseProgram(program)
        GLES20.glUniform1f(uTravel, travel)
        GLES20.glUniform1f(uAspect, aspect)
        GLES20.glUniform3f(uBands, bands[0], bands[1], bands[2])
        GLES20.glUniform1f(uPointSize, (height * 0.005f).coerceIn(2f, 9f))
        GLES20.glUniform1f(uBeat, beat)

        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo)
        GLES20.glEnableVertexAttribArray(aSeed)
        GLES20.glVertexAttribPointer(aSeed, 4, GLES20.GL_FLOAT, false, 0, 0)
        GLES20.glDrawArrays(GLES20.GL_POINTS, 0, COUNT)
        GLES20.glDisableVertexAttribArray(aSeed)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)

        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
    }

    companion object {
        private const val COUNT = 9000

        private const val BG_VS = """#version 300 es
            out vec2 v_uv;
            void main() {
                vec2 p = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));
                v_uv = p;
                gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
            }
        """

        // Deep-space fog: indigo gradient + two soft drifting nebular blooms. Kept
        // dim so only the motes bloom, but enough that the opener never reads empty.
        private const val BG_FS = """#version 300 es
            precision highp float;
            in vec2 v_uv;
            uniform float u_time;
            uniform float u_beat;
            out vec4 o;
            void main() {
                vec3 top = vec3(0.02, 0.03, 0.07);
                vec3 bot = vec3(0.05, 0.02, 0.10);
                vec3 col = mix(bot, top, v_uv.y);
                vec2 a = vec2(0.35 + 0.05 * sin(u_time * 0.07), 0.6);
                vec2 b = vec2(0.7, 0.4 + 0.05 * cos(u_time * 0.05));
                col += vec3(0.05, 0.10, 0.16) * (0.5 + u_beat * 0.6) * smoothstep(0.45, 0.0, distance(v_uv, a));
                col += vec3(0.12, 0.05, 0.16) * (0.4 + u_beat * 0.5) * smoothstep(0.5, 0.0, distance(v_uv, b));
                o = vec4(col, 1.0);
            }
        """

        private const val VS = """#version 300 es
            layout(location = 0) in vec4 aSeed;   // xy spread, z depth seed, w random
            uniform float u_travel;
            uniform float u_aspect;
            uniform vec3  u_bands;
            uniform float u_pointSize;
            uniform float u_beat;
            out float v_bright;
            out float v_sel;
            void main() {
                float z = fract(aSeed.z - u_travel * 0.03);   // 0 far .. 1 near
                float dist = 0.55 + (1.0 - z) * 3.0;
                vec2 p = aSeed.xy * 2.6;
                vec2 sp = p / dist;
                sp.x /= u_aspect;
                gl_Position = vec4(sp, 0.0, 1.0);
                float fade = smoothstep(0.0, 0.08, z) * (1.0 - smoothstep(0.85, 1.0, z));
                gl_PointSize = clamp(u_pointSize * (1.5 / dist) * (1.0 + u_bands.x * 0.8), 1.0, 13.0);
                v_bright = fade * (0.5 + z * 0.7);
                v_sel = aSeed.w;
            }
        """

        private const val FS = """#version 300 es
            precision highp float;
            in float v_bright;
            in float v_sel;
            uniform vec3 u_bands;
            uniform float u_beat;
            out vec4 fragColor;
            void main() {
                vec2 c = gl_PointCoord * 2.0 - 1.0;
                float d2 = dot(c, c);
                if (d2 > 1.0) discard;
                float glow = exp(-d2 * 2.6);
                float twinkle = 0.75 + 0.25 * sin(v_sel * 50.0 + u_bands.z * 12.0);
                // Cool teal -> violet, with a rare warm ember for variety.
                vec3 cool = mix(vec3(0.25, 0.70, 1.0), vec3(0.55, 0.35, 1.0), v_sel);
                vec3 ember = vec3(1.0, 0.6, 0.35);
                vec3 tint = mix(cool, ember, step(0.93, v_sel));
                vec3 col = tint * glow * v_bright * twinkle * 1.35;
                col *= 1.0 + u_beat * 1.5;        // beat lifts the field (HDR -> bloom)
                fragColor = vec4(col, 1.0);
            }
        """
    }
}
