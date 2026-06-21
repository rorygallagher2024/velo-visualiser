package com.lowlatency.visualizer.gl

import android.opengl.GLES20
import com.lowlatency.visualizer.BeatPulse
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.random.Random

/**
 * "Aurora Drift" — a calm, emotional flow visualizer in the spirit of Tetris
 * Effect's dreamlike particle scenes (inspired by the art style only).
 *
 * A lush gradient backdrop (never pure black) sits behind thousands of luminous
 * particles that stream along gently undulating currents — coordinated rivers of
 * light rather than a turbulent storm. An IQ cosine palette drifts slowly through
 * cyan / violet / gold, and the music breathes through it: bass quickens the flow
 * and widens the undulation, beats surge the glow ([BeatPulse]), mids drift the
 * hue. Particle cores exceed 1.0 so they bloom on the FP16 HDR buffer.
 *
 * The motion is stateless (closed-form from a per-particle seed + time), so it
 * needs no compute shaders and runs the same on every device — each particle
 * flows left→right and wraps, fading at the edges so the loop is invisible.
 */
class AuroraDriftScene : GlScene {

    companion object {
        private const val COUNT = 6000
        private const val FLOATS_PER = 4          // vec4: phase, laneY, freq, depth
        private const val STRIDE = FLOATS_PER * 4

        // Shared IQ cosine palette — cool, oceanic, cosmic (cyan→violet→gold).
        private const val PALETTE = """
            vec3 palette(float t) {
                return 0.5 + 0.5 * cos(6.2831853 * (vec3(1.0) * t + vec3(0.0, 0.15, 0.35)));
            }
        """

        // ---- Background: full-screen gradient field (one big triangle) ----
        private val BG_VS = """#version 300 es
            out vec2 v_uv;
            void main() {
                vec2 p = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));
                v_uv = p;
                gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
            }
        """

        private val BG_FS = """#version 300 es
            precision highp float;
            in vec2 v_uv;
            uniform float u_time;
            uniform float u_env;
            uniform float u_dim;
            out vec4 o;
            $PALETTE
            void main() {
                // Vertical gradient between two slowly hue-cycling deep tones, kept
                // below the bloom threshold so only the particles glow.
                vec3 top = palette(u_time * 0.015 + 0.55) * 0.16;
                vec3 bot = palette(u_time * 0.015 + 0.10) * 0.07;
                vec3 col = mix(bot, top, v_uv.y);
                // Soft central aura that swells on the beat.
                float r = distance(v_uv, vec2(0.5, 0.55));
                col += palette(u_time * 0.02 + 0.3) * (0.04 + u_env * 0.22) * smoothstep(0.85, 0.0, r);
                o = vec4(col * u_dim, 1.0);
            }
        """

        // ---- Particles: flowing streams of glowing points ----
        private val VS = """#version 300 es
            layout(location = 0) in vec4 a;   // x=phase, y=laneY, z=freq, w=depth
            uniform float u_time;
            uniform float u_speed;   // bass adds flow speed
            uniform float u_amp;     // bass widens undulation
            uniform float u_bass;
            uniform float u_mid;
            uniform float u_env;     // beat envelope
            uniform float u_pointSize;
            out float v_bright;
            out float v_hue;
            out float v_depth;
            void main() {
                float depth = a.w;                       // 0 far .. 1 near (parallax)
                float spd = (0.035 + u_speed) * mix(0.5, 1.4, depth);
                float prog = fract(a.x + u_time * spd);   // 0..1 travel along the current
                float x = prog * 2.4 - 1.2;
                float amp = (0.10 + u_amp) * mix(0.5, 1.2, depth);
                float y = a.y + amp * sin(x * a.z + u_time * 0.7 + a.x * 6.2831);
                y += 0.03 * sin(u_time * 0.3 + a.y * 4.0);  // slow collective bob
                gl_Position = vec4(x, y, 0.0, 1.0);

                float edge = smoothstep(0.0, 0.12, prog) * (1.0 - smoothstep(0.88, 1.0, prog));
                float sz = u_pointSize * mix(0.5, 2.2, depth) * (1.0 + u_bass * 1.2 + u_env * 0.8);
                gl_PointSize = clamp(sz * edge + 0.5, 1.0, 14.0);

                v_bright = edge * mix(0.35, 1.0, depth) * (0.5 + u_env * 0.9);
                v_hue = a.y * 0.25 + u_time * 0.025 + u_mid * 0.35 + depth * 0.15;
                v_depth = depth;
            }
        """

        private val FS = """#version 300 es
            precision highp float;
            in float v_bright;
            in float v_hue;
            in float v_depth;
            uniform float u_dim;
            out vec4 fragColor;
            $PALETTE
            void main() {
                vec2 c = gl_PointCoord * 2.0 - 1.0;
                float d2 = dot(c, c);
                if (d2 > 1.0) discard;
                float glow = exp(-d2 * 3.0);
                vec3 col = palette(v_hue) * glow * v_bright;
                col *= 1.0 + v_depth * 0.8;            // near particles burn brighter (HDR -> bloom)
                fragColor = vec4(col * u_dim, 1.0);
            }
        """
    }

    private var bgProg = 0
    private var prog = 0
    private var vbo = 0

    private var ubgTime = 0; private var ubgEnv = 0; private var ubgDim = 0
    private var uTime = 0; private var uSpeed = 0; private var uAmp = 0
    private var uBass = 0; private var uMid = 0; private var uEnv = 0
    private var uPointSize = 0; private var uDim = 0

    private var height = 1080f
    private var speed = 0f
    private var amp = 0f

    override fun onCreated() {
        bgProg = ShaderUtil.buildProgram(BG_VS, BG_FS)
        prog = ShaderUtil.buildProgram(VS, FS)

        ubgTime = GLES20.glGetUniformLocation(bgProg, "u_time")
        ubgEnv = GLES20.glGetUniformLocation(bgProg, "u_env")
        ubgDim = GLES20.glGetUniformLocation(bgProg, "u_dim")
        uTime = GLES20.glGetUniformLocation(prog, "u_time")
        uSpeed = GLES20.glGetUniformLocation(prog, "u_speed")
        uAmp = GLES20.glGetUniformLocation(prog, "u_amp")
        uBass = GLES20.glGetUniformLocation(prog, "u_bass")
        uMid = GLES20.glGetUniformLocation(prog, "u_mid")
        uEnv = GLES20.glGetUniformLocation(prog, "u_env")
        uPointSize = GLES20.glGetUniformLocation(prog, "u_pointSize")
        uDim = GLES20.glGetUniformLocation(prog, "u_dim")

        val data = FloatArray(COUNT * FLOATS_PER)
        val rnd = Random(0xA0FE)
        var i = 0
        repeat(COUNT) {
            data[i++] = rnd.nextFloat()                 // phase 0..1
            data[i++] = rnd.nextFloat() * 2.1f - 1.05f  // laneY
            data[i++] = 1.5f + rnd.nextFloat() * 2.5f   // undulation frequency
            data[i++] = rnd.nextFloat()                 // depth 0..1
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
        this.height = height.toFloat()
    }

    override fun draw(pcm: FloatArray, bands: FloatArray, timeSec: Float, dim: Float) {
        val low = bands[0]; val mid = bands[1]
        // Smooth the bass-driven flow so the current eases rather than jerks.
        speed += (low * 0.10f - speed) * 0.08f
        amp += (low * 0.12f - amp) * 0.08f
        val env = BeatPulse.envelope

        // 1. Gradient backdrop (opaque, overwrites the cleared buffer).
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glUseProgram(bgProg)
        GLES20.glUniform1f(ubgTime, timeSec)
        GLES20.glUniform1f(ubgEnv, env)
        GLES20.glUniform1f(ubgDim, dim)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 3)

        // 2. Flowing particles (additive glow).
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE)
        GLES20.glUseProgram(prog)
        GLES20.glUniform1f(uTime, timeSec)
        GLES20.glUniform1f(uSpeed, speed)
        GLES20.glUniform1f(uAmp, amp)
        GLES20.glUniform1f(uBass, low)
        GLES20.glUniform1f(uMid, mid)
        GLES20.glUniform1f(uEnv, env)
        GLES20.glUniform1f(uPointSize, (height * 0.006f).coerceIn(2f, 9f))
        GLES20.glUniform1f(uDim, dim)

        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo)
        GLES20.glEnableVertexAttribArray(0)
        GLES20.glVertexAttribPointer(0, 4, GLES20.GL_FLOAT, false, STRIDE, 0)
        GLES20.glDrawArrays(GLES20.GL_POINTS, 0, COUNT)
        GLES20.glDisableVertexAttribArray(0)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
    }

    override fun onDeactivate() {
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
    }
}
