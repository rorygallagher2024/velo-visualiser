package com.lowlatency.visualizer.gl

import android.opengl.GLES20
import android.opengl.GLES30
import android.opengl.GLES31
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.random.Random

/**
 * "Fluid Dynamics" — a 100k-particle system advected by a curl-noise velocity
 * field on the GPU via an OpenGL ES 3.1 compute shader, rendered as additively
 * blended point sprites for a glowing volumetric look.
 *
 * Pipeline each frame:
 *   1. Compute pass: read/update each particle in the SSBO (curl noise + audio
 *      forces), write position/velocity/lifetime back.
 *   2. Memory barrier so the freshly written buffer is visible to vertex fetch.
 *   3. Render pass: draw the same buffer as GL_POINTS with additive blending.
 *
 * The compute shader binds the SSBO at binding=0; the render pass binds the
 * *same* buffer object as a vertex array (the portable way to consume compute
 * output, since vertex-stage SSBO reads aren't guaranteed on ES 3.1).
 *
 * If compute shaders are unavailable (driver < ES 3.1), the scene disables
 * itself and renders nothing rather than crashing.
 */
class FluidScene : GlScene {

    companion object {
        private const val TAG = "FluidScene"
        private const val COUNT = 100_000
        private const val FLOATS_PER = 6          // vec2 pos, vec2 vel, float life, float pad
        private const val STRIDE = FLOATS_PER * 4 // 24 bytes (std430-aligned)
        private const val LOCAL_SIZE = 64

        // ---- Compute shader: curl-noise fluid advection ----
        private val COMPUTE_SRC = """#version 310 es
            layout(local_size_x = $LOCAL_SIZE) in;
            precision highp float;
            precision highp int;

            struct Particle { vec2 pos; vec2 vel; float life; float pad; };
            layout(std430, binding = 0) buffer Particles { Particle particles[]; };

            uniform float u_dt;
            uniform float u_time;
            uniform float u_aspectRatio;   // width / height (foldable-safe)
            uniform float u_low;           // bass  -> exponential swirl intensity
            uniform float u_high;          // highs -> radial outward burst
            uniform int   u_count;

            // --- Ashima 2D simplex noise (McEwan et al., public domain) ---
            vec3 mod289(vec3 x){ return x - floor(x * (1.0/289.0)) * 289.0; }
            vec2 mod289(vec2 x){ return x - floor(x * (1.0/289.0)) * 289.0; }
            vec3 permute(vec3 x){ return mod289(((x*34.0)+1.0)*x); }
            float snoise(vec2 v){
                const vec4 C = vec4(0.211324865405187, 0.366025403784439,
                                   -0.577350269189626, 0.024390243902439);
                vec2 i  = floor(v + dot(v, C.yy));
                vec2 x0 = v -   i + dot(i, C.xx);
                vec2 i1 = (x0.x > x0.y) ? vec2(1.0, 0.0) : vec2(0.0, 1.0);
                vec4 x12 = x0.xyxy + C.xxzz;
                x12.xy -= i1;
                i = mod289(i);
                vec3 p = permute( permute( i.y + vec3(0.0, i1.y, 1.0))
                                          + i.x + vec3(0.0, i1.x, 1.0));
                vec3 m = max(0.5 - vec3(dot(x0,x0), dot(x12.xy,x12.xy),
                                        dot(x12.zw,x12.zw)), 0.0);
                m = m*m; m = m*m;
                vec3 x = 2.0 * fract(p * C.www) - 1.0;
                vec3 h = abs(x) - 0.5;
                vec3 ox = floor(x + 0.5);
                vec3 a0 = x - ox;
                m *= 1.79284291400159 - 0.85373472095314 * (a0*a0 + h*h);
                vec3 g;
                g.x  = a0.x  * x0.x  + h.x  * x0.y;
                g.yz = a0.yz * x12.xz + h.yz * x12.yw;
                return 130.0 * dot(m, g);
            }

            // Curl of a scalar noise potential => divergence-free 2D flow.
            vec2 curlNoise(vec2 p){
                float e = 0.02;
                float n_y1 = snoise(p + vec2(0.0,  e));
                float n_y2 = snoise(p + vec2(0.0, -e));
                float n_x1 = snoise(p + vec2( e, 0.0));
                float n_x2 = snoise(p + vec2(-e, 0.0));
                float dFdy = (n_y1 - n_y2) / (2.0 * e);
                float dFdx = (n_x1 - n_x2) / (2.0 * e);
                return vec2(dFdy, -dFdx);
            }

            float hash11(float p){
                p = fract(p * 0.1031);
                p *= p + 33.33;
                p *= p + p;
                return fract(p);
            }

            void main(){
                uint gid = gl_GlobalInvocationID.x;
                if (gid >= uint(u_count)) return;
                Particle pr = particles[gid];

                // Sample noise in aspect-corrected space so cells stay square
                // regardless of physical screen dimensions (foldables).
                vec2 sp = pr.pos;
                sp.x *= u_aspectRatio;

                vec2 field = curlNoise(sp * 1.5 + vec2(u_time * 0.10));

                // Lows: exponential intensity -> violent swirl on kick hits.
                float lowGain = exp(u_low * 2.5);
                vec2 vel = field * lowGain * 0.6;

                // Highs: sharp radial burst outward from screen center.
                vec2 radial = pr.pos / (length(pr.pos) + 1e-4);
                vel += radial * (u_high * u_high) * 2.0;

                // Smooth toward target velocity, integrate (isotropic: undo the
                // clip-space x compression using aspect so motion looks uniform).
                pr.vel = mix(pr.vel, vel, 0.5);
                pr.pos += vec2(pr.vel.x / u_aspectRatio, pr.vel.y) * u_dt;
                pr.life -= u_dt * 0.25;

                // Respawn dead / out-of-bounds particles at a fresh random spot.
                if (pr.life <= 0.0 || abs(pr.pos.x) > 1.15 || abs(pr.pos.y) > 1.15) {
                    float s = float(gid) + u_time;
                    pr.pos  = vec2(hash11(s * 0.0131) * 2.0 - 1.0,
                                   hash11(s * 0.0307 + 7.0) * 2.0 - 1.0);
                    pr.vel  = vec2(0.0);
                    pr.life = 0.5 + hash11(s * 0.0511 + 3.0) * 1.8;
                }

                particles[gid] = pr;
            }
        """

        // ---- Render: point sprites reading the particle buffer ----
        private val VERTEX_SRC = """#version 310 es
            layout(location = 0) in vec2 a_pos;
            layout(location = 1) in float a_life;
            uniform float u_pointSize;
            out float v_life;
            void main(){
                v_life = a_life;
                gl_Position = vec4(a_pos, 0.0, 1.0);
                gl_PointSize = u_pointSize;
            }
        """

        private val FRAGMENT_SRC = """#version 310 es
            precision highp float;
            in float v_life;
            uniform float u_time;
            uniform float u_mid;     // mids -> palette shift over time
            uniform float u_dim;     // transition fade
            out vec4 fragColor;

            vec3 palette(float t){
                return 0.5 + 0.5 * cos(6.28318 * (t + vec3(0.0, 0.33, 0.67)));
            }

            void main(){
                vec2 c = gl_PointCoord * 2.0 - 1.0;
                float d2 = dot(c, c);
                if (d2 > 1.0) discard;                 // round sprite
                float glow = exp(-d2 * 3.5);           // soft volumetric falloff
                float a = glow * clamp(v_life, 0.0, 1.0) * 0.6 * u_dim;
                vec3 col = palette(u_time * 0.04 + u_mid * 1.2);
                // Additive (SRC_ALPHA, ONE): clustered particles sum past 1.0
                // into HDR glow on the FP16 framebuffer. No tone-mapping.
                fragColor = vec4(col * a, a);
            }
        """
    }

    private var supported = true
    private var computeProg = 0
    private var renderProg = 0
    private var ssbo = 0

    // compute uniforms
    private var ucDt = 0
    private var ucTime = 0
    private var ucAspect = 0
    private var ucLow = 0
    private var ucHigh = 0
    private var ucCount = 0
    // render uniforms
    private var urPointSize = 0
    private var urTime = 0
    private var urMid = 0
    private var urDim = 0

    private var aspect = 1f
    private var height = 1080f
    private var lastTime = -1f

    override fun onCreated() {
        try {
            computeProg = ShaderUtil.buildComputeProgram(COMPUTE_SRC)
            renderProg = ShaderUtil.buildProgram(VERTEX_SRC, FRAGMENT_SRC)
        } catch (e: RuntimeException) {
            supported = false
            Log.e(TAG, "Compute/render program build failed; disabling Fluid scene.", e)
            return
        }

        ucDt = GLES20.glGetUniformLocation(computeProg, "u_dt")
        ucTime = GLES20.glGetUniformLocation(computeProg, "u_time")
        ucAspect = GLES20.glGetUniformLocation(computeProg, "u_aspectRatio")
        ucLow = GLES20.glGetUniformLocation(computeProg, "u_low")
        ucHigh = GLES20.glGetUniformLocation(computeProg, "u_high")
        ucCount = GLES20.glGetUniformLocation(computeProg, "u_count")

        urPointSize = GLES20.glGetUniformLocation(renderProg, "u_pointSize")
        urTime = GLES20.glGetUniformLocation(renderProg, "u_time")
        urMid = GLES20.glGetUniformLocation(renderProg, "u_mid")
        urDim = GLES20.glGetUniformLocation(renderProg, "u_dim")

        initSsbo()
        Log.i(TAG, "Fluid scene ready. GL_VERSION=${GLES20.glGetString(GLES20.GL_VERSION)}")
    }

    private fun initSsbo() {
        // Random starting positions across the screen, zero velocity, random life.
        val data = ByteBuffer.allocateDirect(COUNT * STRIDE)
            .order(ByteOrder.nativeOrder()).asFloatBuffer()
        val rng = Random(1)
        for (i in 0 until COUNT) {
            data.put(rng.nextFloat() * 2f - 1f)   // pos.x
            data.put(rng.nextFloat() * 2f - 1f)   // pos.y
            data.put(0f)                          // vel.x
            data.put(0f)                          // vel.y
            data.put(0.2f + rng.nextFloat() * 1.8f) // life
            data.put(0f)                          // pad
        }
        data.position(0)

        val ids = IntArray(1)
        GLES20.glGenBuffers(1, ids, 0)
        ssbo = ids[0]
        GLES20.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, ssbo)
        GLES20.glBufferData(
            GLES31.GL_SHADER_STORAGE_BUFFER, COUNT * STRIDE, data, GLES20.GL_DYNAMIC_DRAW
        )
        GLES20.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, 0)
    }

    override fun onResize(width: Int, height: Int, aspect: Float) {
        this.aspect = aspect
        this.height = height.toFloat()
    }

    override fun draw(pcm: FloatArray, bands: FloatArray, timeSec: Float, dim: Float) {
        if (!supported) return

        val dt = if (lastTime < 0f) 0.016f else (timeSec - lastTime).coerceIn(0f, 0.05f)
        lastTime = timeSec
        val low = bands[0]; val mid = bands[1]; val high = bands[2]

        // 1. Compute pass — advect every particle.
        GLES20.glUseProgram(computeProg)
        GLES20.glUniform1f(ucDt, dt)
        GLES20.glUniform1f(ucTime, timeSec)
        GLES20.glUniform1f(ucAspect, aspect)
        GLES20.glUniform1f(ucLow, low)
        GLES20.glUniform1f(ucHigh, high)
        GLES20.glUniform1i(ucCount, COUNT)
        GLES30.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 0, ssbo)
        GLES31.glDispatchCompute((COUNT + LOCAL_SIZE - 1) / LOCAL_SIZE, 1, 1)

        // 2. Make compute writes visible to the vertex fetch that follows.
        GLES31.glMemoryBarrier(
            GLES31.GL_SHADER_STORAGE_BARRIER_BIT or GLES31.GL_VERTEX_ATTRIB_ARRAY_BARRIER_BIT
        )

        // 3. Render pass — additive point sprites.
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE)   // additive

        GLES20.glUseProgram(renderProg)
        val pointSize = (height * 0.0045f).coerceIn(2f, 6f) * (1f + low * 1.5f)
        GLES20.glUniform1f(urPointSize, pointSize)
        GLES20.glUniform1f(urTime, timeSec)
        GLES20.glUniform1f(urMid, mid)
        GLES20.glUniform1f(urDim, dim)

        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, ssbo)
        GLES20.glEnableVertexAttribArray(0)
        GLES20.glVertexAttribPointer(0, 2, GLES20.GL_FLOAT, false, STRIDE, 0)
        GLES20.glEnableVertexAttribArray(1)
        GLES20.glVertexAttribPointer(1, 1, GLES20.GL_FLOAT, false, STRIDE, 16)

        GLES20.glDrawArrays(GLES20.GL_POINTS, 0, COUNT)

        GLES20.glDisableVertexAttribArray(0)
        GLES20.glDisableVertexAttribArray(1)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
    }

    override fun onDeactivate() {
        // Explicitly undo our non-default GL state when the user swipes away, so
        // the Oscilloscope/Tunnel are never handed a leaked additive-blend state.
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
    }
}
