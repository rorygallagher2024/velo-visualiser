package com.lowlatency.visualizer.gl

import android.opengl.GLES20
import android.opengl.GLES30
import android.opengl.GLES31
import android.util.Log
import com.lowlatency.visualizer.BeatPulse
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.random.Random

/**
 * "Plasma Storm" — a full-screen curl-noise flow field of ~[COUNT] particles,
 * integrated on the GPU (ES 3.1 compute) and drawn as additively-blended glowing
 * point sprites. The particles drift along an evolving divergence-free noise
 * field, so the cloud forever swirls without ever pooling or tearing.
 *
 * Pipeline each frame (mirrors [StrangeAttractorScene] / FluidScene):
 *   1. Compute pass: advect every particle by curl(noise) for one step, age it,
 *      and respawn it across the screen when it drifts out or ages out.
 *   2. Memory barrier, then draw the same SSBO as GL_POINTS (additive HDR).
 *
 * Audio drives the *storm*, not the field's structure: bass surges the flow speed
 * and, on kicks, blasts a radial impulse outward from the centre (the field
 * "breathes"); mids drift the palette; highs sparkle; beats flare brightness via
 * [BeatPulse]. Colours exceed 1.0 so the bloom pass makes the cloud glow on the
 * FP16 buffer. If compute shaders are unavailable the scene disables itself
 * rather than crashing.
 */
class PlasmaStormScene : GlScene {

    companion object {
        private const val TAG = "PlasmaStorm"
        private const val COUNT = 45_000
        private const val FLOATS_PER = 4          // vec4: xy position, z age, w speed
        private const val STRIDE = FLOATS_PER * 4 // 16 bytes (std430 vec4)
        private const val LOCAL_SIZE = 64
        private const val LIFE = 7.0f             // seconds before a particle recycles

        private val COMPUTE_SRC = """#version 310 es
            layout(local_size_x = $LOCAL_SIZE) in;
            precision highp float;
            precision highp int;

            struct Particle { vec4 p; };   // xy = position (NDC), z = age, w = speed
            layout(std430, binding = 0) buffer Particles { Particle particles[]; };

            uniform float u_dt;
            uniform float u_time;
            uniform float u_speed;     // bass flow multiplier
            uniform float u_beat;      // radial impulse on kicks
            uniform float u_aspect;    // w/h, keeps the noise cells square
            uniform float u_scale;     // spatial frequency of the flow
            uniform int   u_count;

            float hash(vec2 p) { return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453); }
            float hash11(float p) { p = fract(p * 0.1031); p *= p + 33.33; p *= p + p; return fract(p); }

            float vnoise(vec2 p) {
                vec2 i = floor(p), f = fract(p);
                float a = hash(i), b = hash(i + vec2(1.0, 0.0));
                float c = hash(i + vec2(0.0, 1.0)), d = hash(i + vec2(1.0, 1.0));
                vec2 u = f * f * (3.0 - 2.0 * f);
                return mix(mix(a, b, u.x), mix(c, d, u.x), u.y);
            }
            // Divergence-free flow: perpendicular gradient of a scrolling noise field.
            vec2 curl(vec2 p) {
                float e = 0.08;
                float nx1 = vnoise(p + vec2(0.0, e)), nx2 = vnoise(p - vec2(0.0, e));
                float ny1 = vnoise(p + vec2(e, 0.0)), ny2 = vnoise(p - vec2(e, 0.0));
                return vec2(nx1 - nx2, -(ny1 - ny2)) / (2.0 * e);
            }

            void main() {
                uint gid = gl_GlobalInvocationID.x;
                if (gid >= uint(u_count)) return;
                vec4 pr = particles[gid].p;
                vec2 pos = pr.xy;
                float age = pr.z;

                // Sample the flow with square cells (compress x by aspect), scrolling
                // slowly through the third noise dimension via time.
                vec2 np = vec2(pos.x * u_aspect, pos.y) * u_scale + vec2(0.0, u_time * 0.05);
                vec2 vel = curl(np);
                vel += normalize(pos + vec2(1e-4)) * u_beat;   // beat breathes the field outward

                float dt = u_dt * u_speed;
                pos += vel * dt;
                age += u_dt;
                float speed = length(vel);

                // Recycle drifted / aged-out particles to a fresh random spot so the
                // field keeps an even density.
                if (age > ${LIFE} || abs(pos.x) > 1.15 || abs(pos.y) > 1.15) {
                    float s = float(gid) + u_time * 60.0;
                    pos = vec2(hash11(s * 0.013) * 2.2 - 1.1, hash11(s * 0.027 + 5.0) * 2.2 - 1.1);
                    age = hash11(s * 0.07 + 1.0) * 2.0;
                    speed = 0.0;
                }

                particles[gid].p = vec4(pos, age, speed);
            }
        """

        private val VERTEX_SRC = """#version 310 es
            layout(location = 0) in vec4 a_p;   // xy pos, z age, w speed
            uniform float u_pointSize;
            uniform float u_bass;
            out float v_age;
            out float v_speed;

            void main() {
                gl_Position = vec4(a_p.xy, 0.0, 1.0);
                float fast = clamp(a_p.w * 1.5, 0.0, 1.0);
                gl_PointSize = clamp(u_pointSize * (0.6 + fast + u_bass * 1.2), 1.0, 12.0);
                v_age = a_p.z;
                v_speed = a_p.w;
            }
        """

        private val FRAGMENT_SRC = """#version 310 es
            precision highp float;
            in float v_age;
            in float v_speed;
            uniform float u_time;
            uniform float u_mid;
            uniform float u_high;
            uniform float u_dim;
            uniform float u_env;     // beat envelope (BeatPulse)
            out vec4 fragColor;

            vec3 palette(float t) {
                return 0.5 + 0.5 * cos(6.28318 * (t + vec3(0.0, 0.33, 0.67)));
            }

            void main() {
                vec2 c = gl_PointCoord * 2.0 - 1.0;
                float d2 = dot(c, c);
                if (d2 > 1.0) discard;
                float glow = exp(-d2 * 3.2);

                // Fade in at birth and out near death so recycling is invisible.
                float fade = smoothstep(0.0, 0.6, v_age) * (1.0 - smoothstep(${LIFE} - 1.2, ${LIFE}, v_age));

                // Hue rides speed + mids + slow time drift; highs add a white sparkle.
                vec3 col = palette(0.55 + v_speed * 0.6 + u_mid * 0.5 + u_time * 0.02);
                float spark = u_high * 0.6 * step(0.85, fract(sin(v_age * 91.7) * 1000.0));
                col += spark;

                float bright = fade * (0.35 + u_env * 0.8);
                col *= bright * (1.0 + u_env * 1.6);   // HDR (>1) -> blooms on the FP16 buffer
                fragColor = vec4(col * u_dim, 1.0);
            }
        """
    }

    private var supported = true
    private var computeProg = 0
    private var renderProg = 0
    private var ssbo = 0

    private var ucDt = 0; private var ucTime = 0; private var ucSpeed = 0
    private var ucBeat = 0; private var ucAspect = 0; private var ucScale = 0; private var ucCount = 0
    private var urPointSize = 0; private var urBass = 0
    private var urTime = 0; private var urMid = 0; private var urHigh = 0; private var urDim = 0; private var urEnv = 0

    private var aspect = 1f
    private var height = 1080f
    private var lastTime = -1f
    private var speed = 1f

    override fun onCreated() {
        try {
            computeProg = ShaderUtil.buildComputeProgram(COMPUTE_SRC)
            renderProg = ShaderUtil.buildProgram(VERTEX_SRC, FRAGMENT_SRC)
        } catch (e: RuntimeException) {
            supported = false
            Log.e(TAG, "Compute/render build failed; disabling Plasma Storm.", e)
            return
        }
        ucDt = GLES20.glGetUniformLocation(computeProg, "u_dt")
        ucTime = GLES20.glGetUniformLocation(computeProg, "u_time")
        ucSpeed = GLES20.glGetUniformLocation(computeProg, "u_speed")
        ucBeat = GLES20.glGetUniformLocation(computeProg, "u_beat")
        ucAspect = GLES20.glGetUniformLocation(computeProg, "u_aspect")
        ucScale = GLES20.glGetUniformLocation(computeProg, "u_scale")
        ucCount = GLES20.glGetUniformLocation(computeProg, "u_count")
        urPointSize = GLES20.glGetUniformLocation(renderProg, "u_pointSize")
        urBass = GLES20.glGetUniformLocation(renderProg, "u_bass")
        urTime = GLES20.glGetUniformLocation(renderProg, "u_time")
        urMid = GLES20.glGetUniformLocation(renderProg, "u_mid")
        urHigh = GLES20.glGetUniformLocation(renderProg, "u_high")
        urDim = GLES20.glGetUniformLocation(renderProg, "u_dim")
        urEnv = GLES20.glGetUniformLocation(renderProg, "u_env")
        initSsbo()
        Log.i(TAG, "Plasma Storm ready.")
    }

    private fun initSsbo() {
        val data = ByteBuffer.allocateDirect(COUNT * STRIDE)
            .order(ByteOrder.nativeOrder()).asFloatBuffer()
        val rng = Random(7)
        for (i in 0 until COUNT) {
            data.put(rng.nextFloat() * 2.2f - 1.1f)   // x
            data.put(rng.nextFloat() * 2.2f - 1.1f)   // y
            data.put(rng.nextFloat() * LIFE)          // age (staggered so recycling spreads out)
            data.put(0f)                              // speed
        }
        data.position(0)
        val ids = IntArray(1)
        GLES20.glGenBuffers(1, ids, 0)
        ssbo = ids[0]
        GLES20.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, ssbo)
        GLES20.glBufferData(GLES31.GL_SHADER_STORAGE_BUFFER, COUNT * STRIDE, data, GLES20.GL_DYNAMIC_DRAW)
        GLES20.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, 0)
    }

    override fun onResize(width: Int, height: Int, aspect: Float) {
        this.aspect = aspect
        this.height = height.toFloat()
    }

    override fun draw(pcm: FloatArray, bands: FloatArray, magnitudes: FloatArray, peaks: FloatArray, timeSec: Float, dim: Float, sharedBuffer: java.nio.ByteBuffer?) {
        if (!supported) return
        val dt = if (lastTime < 0f) 0.016f else (timeSec - lastTime).coerceIn(0f, 0.05f)
        lastTime = timeSec
        val low = bands[0]; val mid = bands[1]; val high = bands[2]

        // Bass surges the flow; smooth so the storm eases rather than snaps.
        speed += ((1f + low * 2.2f) - speed) * 0.15f
        val beat = BeatPulse.envelope

        // 1. Compute — advect the flow field.
        GLES20.glUseProgram(computeProg)
        GLES20.glUniform1f(ucDt, dt.coerceAtLeast(0.008f))
        GLES20.glUniform1f(ucTime, timeSec)
        GLES20.glUniform1f(ucSpeed, speed)
        GLES20.glUniform1f(ucBeat, beat * 0.9f)
        GLES20.glUniform1f(ucAspect, aspect)
        GLES20.glUniform1f(ucScale, 2.6f + mid * 1.5f)
        GLES20.glUniform1i(ucCount, COUNT)
        GLES30.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 0, ssbo)
        GLES31.glDispatchCompute((COUNT + LOCAL_SIZE - 1) / LOCAL_SIZE, 1, 1)
        GLES31.glMemoryBarrier(
            GLES31.GL_SHADER_STORAGE_BARRIER_BIT or GLES31.GL_VERTEX_ATTRIB_ARRAY_BARRIER_BIT
        )

        // 2. Render — additive glowing points.
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE)
        GLES20.glUseProgram(renderProg)
        GLES20.glUniform1f(urPointSize, (height * 0.0035f).coerceIn(1.5f, 6f))
        GLES20.glUniform1f(urBass, low)
        GLES20.glUniform1f(urTime, timeSec)
        GLES20.glUniform1f(urMid, mid)
        GLES20.glUniform1f(urHigh, high)
        GLES20.glUniform1f(urDim, dim)
        GLES20.glUniform1f(urEnv, beat)

        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, ssbo)
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
