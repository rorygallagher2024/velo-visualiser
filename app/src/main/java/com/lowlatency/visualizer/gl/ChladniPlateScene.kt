package com.lowlatency.visualizer.gl

import android.opengl.GLES20
import android.opengl.GLES30
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sin

/**
 * "Cymatics" — a Chladni-plate standing-wave visual using GPU particles.
 * 16,384 particles physically slide away from the vibrating peaks of the plate
 * and collect in the calm nodal lines, creating the classic Chladni figures.
 */
class ChladniPlateScene : GlScene {

    companion object {
        private const val TAG = "ChladniPlateScene"
        private const val SIM_W = 128
        private const val SIM_H = 128
        private const val POINTS_COUNT = SIM_W * SIM_H

        private const val QUAD_VS = """#version 300 es
            layout(location = 0) in vec2 aPos;
            out vec2 v_uv;
            void main() {
                v_uv = aPos * 0.5 + 0.5;
                gl_Position = vec4(aPos, 0.0, 1.0);
            }
        """

        private const val SIM_FS = """#version 300 es
            precision highp float;
            uniform sampler2D u_state;
            uniform float u_n;
            uniform float u_m;
            uniform float u_amp;
            in vec2 v_uv;
            out vec4 o_state;

            const float PI = 3.14159265;

            float chladni(vec2 p, float n, float m) {
                return cos(n * PI * p.x) * cos(m * PI * p.y) - cos(m * PI * p.x) * cos(n * PI * p.y);
            }

            // Pseudo-random function for brownian motion
            float hash(vec2 p) {
                vec3 p3  = fract(vec3(p.xyx) * .1031);
                p3 += dot(p3, p3.yzx + 33.33);
                return fract((p3.x + p3.y) * p3.z);
            }

            void main() {
                vec4 state = texture(u_state, v_uv);
                vec2 pos = state.xy;
                vec2 vel = state.zw;

                // Gradient of the vibration amplitude (abs(s))
                vec2 e = vec2(0.005, 0.0);
                float s = abs(chladni(pos, u_n, u_m));
                float sx = abs(chladni(pos + e.xy, u_n, u_m)) - abs(chladni(pos - e.xy, u_n, u_m));
                float sy = abs(chladni(pos + e.yx, u_n, u_m)) - abs(chladni(pos - e.yx, u_n, u_m));
                vec2 grad = vec2(sx, sy);

                // Particles are pushed away from high vibration (grad) towards zero (nodes)
                // Force increases with audio amplitude
                float force = 1.0 + u_amp * 20.0;
                vel -= grad * force * 0.016;

                // Add slight brownian motion to prevent clumping and keep it alive
                float r1 = hash(pos * 100.0 + u_amp) * 2.0 - 1.0;
                float r2 = hash(pos * 200.0 - u_amp) * 2.0 - 1.0;
                vel += vec2(r1, r2) * (0.05 + s * 0.2); // vibrate more violently at antinodes

                vel *= 0.85; // Friction
                pos += vel * 0.016;

                // Soft bounds (plate edge)
                if (pos.x < -1.0 || pos.x > 1.0) vel.x *= -0.5;
                if (pos.y < -1.0 || pos.y > 1.0) vel.y *= -0.5;
                pos = clamp(pos, vec2(-1.0), vec2(1.0));

                o_state = vec4(pos, vel);
            }
        """

        private const val RENDER_VS = """#version 300 es
            layout(location = 0) in vec2 aUv;
            uniform sampler2D u_state;
            uniform vec2 u_resolution;
            uniform float u_high;
            out float v_speed;
            out float v_vibration;
            
            // Re-evaluate chladni in render to color particles based on where they are
            uniform float u_n;
            uniform float u_m;
            const float PI = 3.14159265;
            float chladni(vec2 p, float n, float m) {
                return cos(n * PI * p.x) * cos(m * PI * p.y) - cos(m * PI * p.x) * cos(n * PI * p.y);
            }

            void main() {
                vec4 state = texture(u_state, aUv);
                vec2 pos = state.xy;
                vec2 vel = state.zw;
                
                v_speed = length(vel);
                v_vibration = abs(chladni(pos, u_n, u_m));
                
                // Keep plate square and centered
                float aspect = u_resolution.x / u_resolution.y;
                vec2 p = pos;
                if (aspect > 1.0) p.x /= aspect;
                else p.y *= aspect;
                
                gl_Position = vec4(p * 0.9, 0.0, 1.0); // 0.9 scale to leave a margin
                gl_PointSize = mix(2.0, 4.0, u_high) + min(v_speed * 5.0, 5.0);
            }
        """

        private const val RENDER_FS = """#version 300 es
            precision highp float;
            uniform vec3 u_color;
            uniform float u_dim;
            uniform float u_hue;
            uniform float u_amp;
            in float v_speed;
            in float v_vibration;
            out vec4 fragColor;

            const float TAU = 6.2831853;
            vec3 palette(float t) {
                return 0.5 + 0.5 * cos(TAU * (vec3(1.0) * t + vec3(0.05, 0.14, 0.24) + u_hue));
            }

            void main() {
                vec2 pc = gl_PointCoord * 2.0 - 1.0;
                float distSq = dot(pc, pc);
                if (distSq > 1.0) discard;
                
                float alpha = (1.0 - distSq) * (0.4 + u_amp * 0.6);
                
                // Color based on vibration and palette drift
                // Particles in nodes (vibration ~0) get a clean, bright sand color.
                // Particles caught in antinodes (vibration > 0) flash with intense colors.
                vec3 col = palette(0.1 + v_vibration * 0.5) * (1.0 + v_speed * 2.0);
                
                fragColor = vec4(col * u_dim * alpha, 1.0);
            }
        """
    }

    private var simProg = 0
    private var renderProg = 0
    
    private var quadVbo = 0
    private var particleUvVbo = 0
    
    private val simTex = IntArray(2)
    private val simFbo = IntArray(2)
    private var simRead = 0
    
    private var sState = 0; private var sN = 0; private var sM = 0; private var sAmp = 0
    private var rState = 0; private var rRes = 0; private var rHigh = 0; private var rN = 0; private var rM = 0
    private var rHue = 0; private var rAmp = 0; private var rDim = 0
    
    private var w = 1f; private var h = 1f
    private var broken = false

    private var pitch = 0.3f
    private var amp = 0f
    private var hue = 0f

    override fun onCreated() {
        simProg = ShaderUtil.buildProgram(QUAD_VS, SIM_FS)
        renderProg = ShaderUtil.buildProgram(RENDER_VS, RENDER_FS)
        
        sState = GLES20.glGetUniformLocation(simProg, "u_state")
        sN = GLES20.glGetUniformLocation(simProg, "u_n")
        sM = GLES20.glGetUniformLocation(simProg, "u_m")
        sAmp = GLES20.glGetUniformLocation(simProg, "u_amp")
        
        rState = GLES20.glGetUniformLocation(renderProg, "u_state")
        rRes = GLES20.glGetUniformLocation(renderProg, "u_resolution")
        rHigh = GLES20.glGetUniformLocation(renderProg, "u_high")
        rN = GLES20.glGetUniformLocation(renderProg, "u_n")
        rM = GLES20.glGetUniformLocation(renderProg, "u_m")
        rHue = GLES20.glGetUniformLocation(renderProg, "u_hue")
        rAmp = GLES20.glGetUniformLocation(renderProg, "u_amp")
        rDim = GLES20.glGetUniformLocation(renderProg, "u_dim")

        val ids = IntArray(2)
        GLES20.glGenBuffers(2, ids, 0)
        quadVbo = ids[0]
        particleUvVbo = ids[1]

        val quad = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
        val qbuf = ByteBuffer.allocateDirect(quad.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().put(quad).also { it.position(0) }
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, quadVbo)
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, quad.size * 4, qbuf, GLES20.GL_STATIC_DRAW)

        val uvs = FloatArray(POINTS_COUNT * 2)
        var i = 0
        for (y in 0 until SIM_H) {
            for (x in 0 until SIM_W) {
                uvs[i++] = (x + 0.5f) / SIM_W.toFloat()
                uvs[i++] = (y + 0.5f) / SIM_H.toFloat()
            }
        }
        val pbuf = ByteBuffer.allocateDirect(uvs.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().put(uvs).also { it.position(0) }
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, particleUvVbo)
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, uvs.size * 4, pbuf, GLES20.GL_STATIC_DRAW)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)

        // Initial positions scattered randomly across the square plate
        val stateData = FloatArray(POINTS_COUNT * 4)
        for (j in 0 until POINTS_COUNT) {
            stateData[j*4 + 0] = (Math.random() * 2.0 - 1.0).toFloat() // x
            stateData[j*4 + 1] = (Math.random() * 2.0 - 1.0).toFloat() // y
            stateData[j*4 + 2] = 0f // vx
            stateData[j*4 + 3] = 0f // vy
        }
        val sbuf = ByteBuffer.allocateDirect(stateData.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().put(stateData).also { it.position(0) }

        for (k in 0..1) {
            val texIds = IntArray(1)
            GLES20.glGenTextures(1, texIds, 0)
            simTex[k] = texIds[0]
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, simTex[k])
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST)
            GLES30.glTexImage2D(
                GLES20.GL_TEXTURE_2D, 0, GLES30.GL_RGBA16F,
                SIM_W, SIM_H, 0, GLES30.GL_RGBA, GLES20.GL_FLOAT, if (k == 0) sbuf else null
            )
            
            val fboIds = IntArray(1)
            GLES20.glGenFramebuffers(1, fboIds, 0)
            simFbo[k] = fboIds[0]
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, simFbo[k])
            GLES20.glFramebufferTexture2D(
                GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, simTex[k], 0
            )
            if (GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) != GLES20.GL_FRAMEBUFFER_COMPLETE) {
                Log.e(TAG, "FBO incomplete — FP16 render targets unsupported; scene disabled")
                broken = true
            }
        }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
    }

    override fun onResize(width: Int, height: Int, aspect: Float) {
        w = width.toFloat(); h = height.toFloat()
    }

    private val prevViewport = IntArray(4)
    private val prevFbo = IntArray(1)

    override fun draw(pcm: FloatArray, bands: FloatArray, timeSec: Float, dim: Float) {
        if (broken) return

        val mags = SpectrumData.magnitudes
        var peakBin = 0
        var peakVal = 0f
        for (i in 2 until mags.size) {
            if (mags[i] > peakVal) { peakVal = mags[i]; peakBin = i }
        }
        if (peakVal > 0.08f) {
            val target = peakBin.toFloat() / (mags.size - 1)
            pitch += (target - pitch) * 0.04f
        }
        amp = if (peakVal > amp) peakVal else amp * 0.92f
        hue += 0.0008f

        val base = 2f + pitch * 8f
        val n = base
        val m = base * 0.62f + 1.7f + 0.8f * sin(timeSec * 0.05f)

        val high = bands[2]

        // 1. SIMULATION PASS
        GLES20.glGetIntegerv(GLES30.GL_FRAMEBUFFER_BINDING, prevFbo, 0)
        GLES20.glGetIntegerv(GLES20.GL_VIEWPORT, prevViewport, 0)
        
        val write = 1 - simRead
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, simFbo[write])
        GLES20.glViewport(0, 0, SIM_W, SIM_H)
        GLES20.glUseProgram(simProg)
        
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, simTex[simRead])
        GLES20.glUniform1i(sState, 0)
        GLES20.glUniform1f(sN, n)
        GLES20.glUniform1f(sM, m)
        GLES20.glUniform1f(sAmp, amp.coerceIn(0f, 1f))
        
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, quadVbo)
        GLES20.glEnableVertexAttribArray(0)
        GLES20.glVertexAttribPointer(0, 2, GLES20.GL_FLOAT, false, 0, 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(0)
        
        simRead = write

        // 2. RENDER PASS
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, prevFbo[0])
        GLES20.glViewport(prevViewport[0], prevViewport[1], prevViewport[2], prevViewport[3])

        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE)
        
        GLES20.glUseProgram(renderProg)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, simTex[simRead])
        GLES20.glUniform1i(rState, 0)
        GLES20.glUniform2f(rRes, w, h)
        GLES20.glUniform1f(rHigh, high)
        GLES20.glUniform1f(rN, n)
        GLES20.glUniform1f(rM, m)
        GLES20.glUniform1f(rHue, hue)
        GLES20.glUniform1f(rAmp, amp.coerceIn(0f, 1f))
        GLES20.glUniform1f(rDim, dim)
        
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, particleUvVbo)
        GLES20.glEnableVertexAttribArray(0)
        GLES20.glVertexAttribPointer(0, 2, GLES20.GL_FLOAT, false, 0, 0)
        GLES20.glDrawArrays(GLES20.GL_POINTS, 0, POINTS_COUNT)
        
        GLES20.glDisableVertexAttribArray(0)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
    }
}
