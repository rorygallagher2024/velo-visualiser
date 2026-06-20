package com.lowlatency.visualizer.gl

import android.opengl.GLES20
import com.lowlatency.visualizer.BeatPulse
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * "Beat Pulse" — an unmissable beat-emphasis visual, built to demo the Ableton
 * Link sync.
 *
 * On every beat the central core slams open (and blooms hard on HDR) while an
 * expanding shockwave ring fires outward and fades. The beat comes from the
 * global [BeatPulse] signal the renderer publishes — Link's clock when sync is
 * on, audio onset otherwise — so the rings land exactly on the beat the bloom
 * and haptics react to.
 */
class BeatPulseScene : GlScene {

    companion object {
        private const val MAX_RINGS = 8

        private const val VERTEX_SHADER = """#version 300 es
            layout(location = 0) in vec2 aPos;
            void main() { gl_Position = vec4(aPos, 0.0, 1.0); }
        """

        private const val FRAGMENT_SHADER = """#version 300 es
            precision highp float;
            uniform vec2  u_resolution;
            uniform float u_time;
            uniform float u_dim;
            uniform float u_env;            // beat envelope 0..1
            uniform float u_low;            // low-band energy 0..1
            uniform float u_beats[${MAX_RINGS}];   // beat birth times (s), <0 = empty
            out vec4 fragColor;

            const float RING_LIFE  = 1.3;   // seconds a ring stays alive
            const float RING_SPEED = 1.15;  // outward expansion rate

            void main() {
                vec2 uv = gl_FragCoord.xy / u_resolution;
                vec2 p = uv * 2.0 - 1.0;
                p.x *= u_resolution.x / u_resolution.y;   // keep circles round
                float r = length(p);

                vec3 col = vec3(0.0);

                // Whole-screen flash on the beat (subtle lift out of black).
                col += vec3(0.05, 0.025, 0.01) * u_env;

                // Central core: slams open on the beat, with a soft halo. HDR > 1
                // so it blooms to peak luminance on the punch.
                float coreR = 0.10 + u_env * 0.22 + u_low * 0.05;
                float core  = smoothstep(coreR, coreR - 0.05, r);
                float halo  = exp(-pow(r / (coreR + 0.18), 2.0) * 3.0);
                vec3  warm  = mix(vec3(1.0, 0.45, 0.12), vec3(1.0, 0.9, 0.7), u_env);
                col += warm * core * (1.6 + u_env * 5.0);
                col += warm * halo * (0.25 + u_env * 1.2);

                // Expanding shockwave rings — one per recent beat.
                for (int i = 0; i < ${MAX_RINGS}; i++) {
                    float bt = u_beats[i];
                    if (bt < 0.0) continue;
                    float age = u_time - bt;
                    if (age < 0.0 || age > RING_LIFE) continue;
                    float rad   = age * RING_SPEED;
                    float width = 0.018 + age * 0.012;
                    float ring  = exp(-pow((r - rad) / width, 2.0));
                    float fade  = 1.0 - age / RING_LIFE;
                    col += vec3(1.0, 0.7, 0.4) * ring * fade * fade * 2.6;
                }

                fragColor = vec4(col * u_dim, 1.0);
            }
        """
    }

    private val quad: FloatBuffer = ByteBuffer
        .allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        .apply { put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)); position(0) }

    // Recent beat birth times (ring buffer), -1 = empty slot.
    private val beats = FloatArray(MAX_RINGS) { -1f }
    private var head = 0
    private var lastBeatCount = -1

    private var program = 0
    private var aPos = 0
    private var uResolution = 0
    private var uTime = 0
    private var uDim = 0
    private var uEnv = 0
    private var uLow = 0
    private var uBeats = 0

    private var width = 1f
    private var height = 1f

    override fun onCreated() {
        program = ShaderUtil.buildProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        aPos = GLES20.glGetAttribLocation(program, "aPos")
        uResolution = GLES20.glGetUniformLocation(program, "u_resolution")
        uTime = GLES20.glGetUniformLocation(program, "u_time")
        uDim = GLES20.glGetUniformLocation(program, "u_dim")
        uEnv = GLES20.glGetUniformLocation(program, "u_env")
        uLow = GLES20.glGetUniformLocation(program, "u_low")
        uBeats = GLES20.glGetUniformLocation(program, "u_beats")
    }

    override fun onResize(width: Int, height: Int, aspect: Float) {
        this.width = width.toFloat()
        this.height = height.toFloat()
    }

    override fun draw(pcm: FloatArray, bands: FloatArray, timeSec: Float, dim: Float) {
        // Record a fresh ring whenever the global beat counter ticks.
        val count = BeatPulse.beatCount
        if (count != lastBeatCount) {
            lastBeatCount = count
            beats[head] = timeSec
            head = (head + 1) % MAX_RINGS
        }

        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glUseProgram(program)
        GLES20.glUniform2f(uResolution, width, height)
        GLES20.glUniform1f(uTime, timeSec)
        GLES20.glUniform1f(uDim, dim)
        GLES20.glUniform1f(uEnv, BeatPulse.envelope)
        GLES20.glUniform1f(uLow, bands[0])
        GLES20.glUniform1fv(uBeats, MAX_RINGS, beats, 0)

        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, quad)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(aPos)
    }
}
