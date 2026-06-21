package com.lowlatency.visualizer.gl

import android.opengl.GLES20
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.random.Random

/**
 * Odyssey world 4 — "Warp". Hyperspace: streaks of light rush outward from a
 * vanishing point, accelerating with the bass and bursting on the beat. The
 * journey's climax, and a natural bridge back to the calm opener.
 *
 * Each streak is a short radial GL_LINE comet (bright head → dim tail) whose
 * length grows with speed/beats. Stateless (position is a closed-form function
 * of seed + shared travel), additive HDR so the streaks bloom.
 */
class WarpMovement : OdysseyMovement {

    private var program = 0
    private var aSeed = 0
    private var uTravel = 0; private var uAspect = 0; private var uStreak = 0; private var uBeat = 0
    private var vbo = 0
    private var aspect = 1f

    override fun onCreated() {
        program = ShaderUtil.buildProgram(VS, FS)
        aSeed = GLES20.glGetAttribLocation(program, "aSeed")
        uTravel = GLES20.glGetUniformLocation(program, "u_travel")
        uAspect = GLES20.glGetUniformLocation(program, "u_aspect")
        uStreak = GLES20.glGetUniformLocation(program, "u_streak")
        uBeat = GLES20.glGetUniformLocation(program, "u_beat")

        // Two verts per streak: tail (w=0) and head (w=1), sharing direction+seed.
        val data = FloatArray(COUNT * 2 * 4)
        val rnd = Random(0x1357)
        var i = 0
        repeat(COUNT) {
            var dx = rnd.nextFloat() * 2f - 1f
            var dy = rnd.nextFloat() * 2f - 1f
            if (dx * dx + dy * dy < 0.02f) { dx = 0.2f; dy = 0.2f }   // avoid dead-centre
            val z = rnd.nextFloat()
            // tail
            data[i++] = dx; data[i++] = dy; data[i++] = z; data[i++] = 0f
            // head
            data[i++] = dx; data[i++] = dy; data[i++] = z; data[i++] = 1f
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
        val streak = 0.07f + bands[0] * 0.10f + beat * 0.08f

        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE)   // additive
        GLES20.glUseProgram(program)
        GLES20.glUniform1f(uTravel, travel)
        GLES20.glUniform1f(uAspect, aspect)
        GLES20.glUniform1f(uStreak, streak)
        GLES20.glUniform1f(uBeat, beat)

        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo)
        GLES20.glEnableVertexAttribArray(aSeed)
        GLES20.glVertexAttribPointer(aSeed, 4, GLES20.GL_FLOAT, false, 0, 0)
        GLES20.glDrawArrays(GLES20.GL_LINES, 0, COUNT * 2)
        GLES20.glDisableVertexAttribArray(aSeed)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)

        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
    }

    companion object {
        private const val COUNT = 1600
        private const val WARP_SPEED = 2.0f

        private const val VS = """#version 300 es
            layout(location = 0) in vec4 aSeed;   // xy direction, z depth seed, w end (0 tail / 1 head)
            uniform float u_travel;
            uniform float u_aspect;
            uniform float u_streak;
            out float v_bright;
            void main() {
                float base = aSeed.z + u_travel * ${WARP_SPEED};
                float zHead = fract(base);
                float zTail = max(zHead - u_streak, 0.002);
                float z = (aSeed.w > 0.5) ? zHead : zTail;

                // Radial rush: streaks accelerate outward from the centre.
                float spread = z / (1.0 - z * 0.985);
                vec2 p = aSeed.xy * spread;
                p.x /= u_aspect;
                gl_Position = vec4(p, 0.0, 1.0);

                float fade = smoothstep(0.0, 0.05, zHead) * (1.0 - smoothstep(0.85, 1.0, zHead));
                // Head bright, tail dim -> comet gradient.
                v_bright = fade * (aSeed.w > 0.5 ? 1.0 : 0.25);
            }
        """

        private const val FS = """#version 300 es
            precision highp float;
            in float v_bright;
            uniform float u_beat;
            out vec4 fragColor;
            void main() {
                // Cool white core; beats burst toward magenta.
                vec3 col = mix(vec3(0.55, 0.75, 1.0), vec3(1.0, 0.55, 0.95), u_beat * 0.8);
                col *= v_bright * (1.05 + u_beat * 1.6);
                col *= 1.7;                          // HDR overdrive -> blooms
                fragColor = vec4(col, 1.0);
            }
        """
    }
}
