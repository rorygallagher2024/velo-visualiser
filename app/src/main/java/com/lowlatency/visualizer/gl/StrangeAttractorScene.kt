package com.lowlatency.visualizer.gl

import android.opengl.GLES20
import android.opengl.GLES30
import android.opengl.GLES31
import android.util.Log
import com.lowlatency.visualizer.BeatPulse
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sin
import kotlin.random.Random

/**
 * "Strange Attractor" — tens of thousands of particles integrating the Aizawa
 * chaotic attractor on the GPU (ES 3.1 compute), traced as additively-blended
 * glowing point sprites under a slowly orbiting camera. Each particle follows
 * the same deterministic flow yet never repeats, so the cloud forever sketches
 * the attractor's ornate sculptural form.
 *
 * Pipeline each frame (mirrors FluidScene):
 *   1. Compute pass: Euler-integrate every particle through the Aizawa ODE for a
 *      few sub-steps; respawn escaped / aged-out particles near the basin.
 *   2. Memory barrier, then draw the same SSBO as GL_POINTS (additive).
 *
 * Audio nudges the *flow*, never the equation's stability: bass speeds the
 * integration (the form surges on kicks) and pulses the zoom; mids/highs drift
 * the palette and add sparkle; beats flare brightness. If compute shaders are
 * unavailable the scene disables itself rather than crashing.
 */
class StrangeAttractorScene : GlScene {

    companion object {
        private const val TAG = "StrangeAttractor"
        private const val COUNT = 70_000
        private const val FLOATS_PER = 4          // vec4: xyz position, w age
        private const val STRIDE = FLOATS_PER * 4 // 16 bytes (std430 vec4)
        private const val LOCAL_SIZE = 64

        private val COMPUTE_SRC = """#version 310 es
            layout(local_size_x = $LOCAL_SIZE) in;
            precision highp float;
            precision highp int;

            struct Particle { vec4 p; };   // xyz = position, w = age
            layout(std430, binding = 0) buffer Particles { Particle particles[]; };

            uniform float u_dt;
            uniform float u_time;
            uniform float u_speed;     // audio flow multiplier
            uniform int   u_count;

            // Aizawa attractor constants.
            const float A = 0.95, B = 0.7, C = 0.6, D = 3.5, E = 0.25, F = 0.1;

            vec3 aizawa(vec3 q) {
                float dx = (q.z - B) * q.x - D * q.y;
                float dy = D * q.x + (q.z - B) * q.y;
                float dz = C + A * q.z - (q.z*q.z*q.z) / 3.0
                         - (q.x*q.x + q.y*q.y) * (1.0 + E * q.z)
                         + F * q.z * (q.x*q.x*q.x);
                return vec3(dx, dy, dz);
            }

            float hash11(float p) {
                p = fract(p * 0.1031); p *= p + 33.33; p *= p + p; return fract(p);
            }

            void main() {
                uint gid = gl_GlobalInvocationID.x;
                if (gid >= uint(u_count)) return;
                Particle pr = particles[gid];
                vec3 q = pr.p.xyz;
                float age = pr.p.w;

                float dt = u_dt * u_speed;
                for (int i = 0; i < 6; i++) { q += aizawa(q) * dt; }   // Euler sub-steps
                age += dt;

                // Respawn aged-out / escaped / NaN particles near the basin so the
                // cloud keeps tracing fresh transients onto the attractor.
                if (age > 6.0 || !(dot(q, q) < 16.0)) {
                    float s = float(gid) + u_time;
                    q = vec3(hash11(s * 0.013) * 0.2 - 0.1,
                             hash11(s * 0.027 + 5.0) * 0.2 - 0.1,
                             hash11(s * 0.041 + 9.0) * 0.2 - 0.1);
                    age = hash11(s * 0.07 + 1.0) * 3.0;
                }

                pr.p = vec4(q, age);
                particles[gid] = pr;
            }
        """

        private val VERTEX_SRC = """#version 310 es
            layout(location = 0) in vec4 a_p;   // xyz pos, w age
            uniform float u_yaw;
            uniform float u_pitch;
            uniform float u_scale;
            uniform float u_aspect;
            uniform float u_pointSize;
            out float v_depth;
            out float v_age;

            void main() {
                // Centre the attractor, then orbit (yaw about Y, pitch about X).
                vec3 p = a_p.xyz - vec3(0.0, 0.0, 0.6);
                float cy = cos(u_yaw), sy = sin(u_yaw);
                p = vec3(cy * p.x + sy * p.z, p.y, -sy * p.x + cy * p.z);
                float cx = cos(u_pitch), sx = sin(u_pitch);
                p = vec3(p.x, cx * p.y - sx * p.z, sx * p.y + cx * p.z);

                float persp = 1.0 / (2.8 + p.z);
                vec2 sp = p.xy * u_scale * persp;
                sp.x /= u_aspect;
                gl_Position = vec4(sp, 0.0, 1.0);
                gl_PointSize = clamp(u_pointSize * persp * 2.8, 1.0, 9.0);
                v_depth = p.z;
                v_age = a_p.w;
            }
        """

        private val FRAGMENT_SRC = """#version 310 es
            precision highp float;
            in float v_depth;
            in float v_age;
            uniform float u_time;
            uniform float u_mid;
            uniform float u_dim;
            uniform float u_env;
            out vec4 fragColor;

            vec3 palette(float t) {
                return 0.5 + 0.5 * cos(6.28318 * (t + vec3(0.0, 0.33, 0.67)));
            }

            void main() {
                vec2 c = gl_PointCoord * 2.0 - 1.0;
                float d2 = dot(c, c);
                if (d2 > 1.0) discard;
                float glow = exp(-d2 * 3.2);
                // Young particles (fresh transients) burn a little brighter.
                float ageF = clamp(1.2 - v_age * 0.12, 0.35, 1.2);
                float a = glow * ageF * (0.42 + u_env * 0.5) * u_dim;
                vec3 col = palette(0.55 + v_depth * 0.22 + u_mid * 0.5 + u_time * 0.03);
                fragColor = vec4(col * a, a);   // additive -> HDR on the FP16 buffer
            }
        """
    }

    private var supported = true
    private var computeProg = 0
    private var renderProg = 0
    private var ssbo = 0

    private var ucDt = 0; private var ucTime = 0; private var ucSpeed = 0; private var ucCount = 0
    private var urYaw = 0; private var urPitch = 0; private var urScale = 0
    private var urAspect = 0; private var urPointSize = 0
    private var urTime = 0; private var urMid = 0; private var urDim = 0; private var urEnv = 0

    private var aspect = 1f
    private var height = 1080f
    private var lastTime = -1f
    private var speed = 1f
    private var zoom = 1.3f

    override fun onCreated() {
        try {
            computeProg = ShaderUtil.buildComputeProgram(COMPUTE_SRC)
            renderProg = ShaderUtil.buildProgram(VERTEX_SRC, FRAGMENT_SRC)
        } catch (e: RuntimeException) {
            supported = false
            Log.e(TAG, "Compute/render build failed; disabling Strange Attractor.", e)
            return
        }
        ucDt = GLES20.glGetUniformLocation(computeProg, "u_dt")
        ucTime = GLES20.glGetUniformLocation(computeProg, "u_time")
        ucSpeed = GLES20.glGetUniformLocation(computeProg, "u_speed")
        ucCount = GLES20.glGetUniformLocation(computeProg, "u_count")
        urYaw = GLES20.glGetUniformLocation(renderProg, "u_yaw")
        urPitch = GLES20.glGetUniformLocation(renderProg, "u_pitch")
        urScale = GLES20.glGetUniformLocation(renderProg, "u_scale")
        urAspect = GLES20.glGetUniformLocation(renderProg, "u_aspect")
        urPointSize = GLES20.glGetUniformLocation(renderProg, "u_pointSize")
        urTime = GLES20.glGetUniformLocation(renderProg, "u_time")
        urMid = GLES20.glGetUniformLocation(renderProg, "u_mid")
        urDim = GLES20.glGetUniformLocation(renderProg, "u_dim")
        urEnv = GLES20.glGetUniformLocation(renderProg, "u_env")
        initSsbo()
        Log.i(TAG, "Strange Attractor ready.")
    }

    private fun initSsbo() {
        val data = ByteBuffer.allocateDirect(COUNT * STRIDE)
            .order(ByteOrder.nativeOrder()).asFloatBuffer()
        val rng = Random(11)
        for (i in 0 until COUNT) {
            data.put(rng.nextFloat() * 0.4f - 0.2f)   // x
            data.put(rng.nextFloat() * 0.4f - 0.2f)   // y
            data.put(rng.nextFloat() * 0.4f - 0.2f)   // z
            data.put(rng.nextFloat() * 6f)            // age (staggered)
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

        // Bass surges the flow; smooth so it eases rather than snaps.
        speed += ((1f + low * 1.6f) - speed) * 0.15f
        zoom += ((1.3f + low * 0.25f) - zoom) * 0.1f

        // 1. Compute — integrate the attractor.
        GLES20.glUseProgram(computeProg)
        GLES20.glUniform1f(ucDt, 0.006f)
        GLES20.glUniform1f(ucTime, timeSec)
        GLES20.glUniform1f(ucSpeed, speed)
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
        GLES20.glUniform1f(urYaw, timeSec * 0.16f)
        GLES20.glUniform1f(urPitch, 0.42f + 0.14f * sin(timeSec * 0.09f))
        GLES20.glUniform1f(urScale, zoom)
        GLES20.glUniform1f(urAspect, aspect)
        GLES20.glUniform1f(urPointSize, (height * 0.004f).coerceIn(1.5f, 6f) * (1f + low * 1.2f + high * 0.6f))
        GLES20.glUniform1f(urTime, timeSec)
        GLES20.glUniform1f(urMid, mid)
        GLES20.glUniform1f(urDim, dim)
        GLES20.glUniform1f(urEnv, BeatPulse.envelope)

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
