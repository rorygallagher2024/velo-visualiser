package com.lowlatency.visualizer.gl

import android.opengl.GLES20
import android.opengl.GLES30
import android.util.Log

/**
 * Odyssey world 3 — "Cathedral". A ray-marched Mandelbox interior that the
 * journey flies *through*: the camera spirals and dollies on the shared [travel]
 * so the structure unfolds like vast alien architecture (mesh-like geometry +
 * self-similar fractal detail at once). Orbit-trap coloring, AO and an emissive
 * rim give it depth; the beat dives the camera inward and pulses the glow.
 *
 * The one heavy world: it ray-marches into a **half-resolution** FP16 buffer and
 * upscales, keeping cost roughly a quarter of full-screen. If the shaders or FBO
 * fail to build it reports [supported] = false and the scheduler skips it.
 */
class CathedralMovement : OdysseyMovement {

    private var marchProg = 0
    private var blitProg = 0
    private var uResolution = 0; private var uTime = 0; private var uTravel = 0
    private var uEnv = 0; private var uScale = 0; private var uEmit = 0
    private var uSpark = 0; private var uHue = 0
    private var uBlitTex = 0

    private var w = 1; private var h = 1
    private var halfW = 1; private var halfH = 1
    private var halfFbo = 0; private var halfTex = 0
    private var halfReady = false
    private var ok = true

    // Smoothed audio state so the geometry breathes gracefully.
    private var scale = 1.95f
    private var emit = 0f
    private var spark = 0f
    private var hue = 0f

    override val supported: Boolean get() = ok

    override fun onCreated() {
        try {
            marchProg = ShaderUtil.buildProgram(FULLSCREEN_VS, MARCH_FS)
            blitProg = ShaderUtil.buildProgram(FULLSCREEN_VS, BLIT_FS)
        } catch (e: RuntimeException) {
            ok = false
            Log.e(TAG, "Cathedral shader build failed; world disabled.", e)
            return
        }
        uResolution = GLES20.glGetUniformLocation(marchProg, "u_resolution")
        uTime = GLES20.glGetUniformLocation(marchProg, "u_time")
        uTravel = GLES20.glGetUniformLocation(marchProg, "u_travel")
        uEnv = GLES20.glGetUniformLocation(marchProg, "u_env")
        uScale = GLES20.glGetUniformLocation(marchProg, "u_scale")
        uEmit = GLES20.glGetUniformLocation(marchProg, "u_emit")
        uSpark = GLES20.glGetUniformLocation(marchProg, "u_spark")
        uHue = GLES20.glGetUniformLocation(marchProg, "u_hue")
        uBlitTex = GLES20.glGetUniformLocation(blitProg, "u_tex")
    }

    override fun onResize(width: Int, height: Int, aspect: Float) {
        w = width.coerceAtLeast(1); h = height.coerceAtLeast(1)
        halfW = (w / 2).coerceAtLeast(1); halfH = (h / 2).coerceAtLeast(1)
        if (!ok) return
        allocateHalf()
    }

    override fun draw(time: Float, bands: FloatArray, travel: Float, beat: Float) {
        if (!ok) return
        val low = bands[0]; val mid = bands[1]; val high = bands[2]

        // Bass breathes the fold scale (the box DE is sensitive — keep it subtle).
        scale += ((1.88f + 0.16f * low) - scale) * 0.04f
        emit += ((0.15f * mid + 0.6f * beat) - emit) * 0.2f
        spark += (high - spark) * 0.25f
        hue += (0.012f + high * 0.04f) * 0.016f

        GLES20.glDisable(GLES20.GL_BLEND)

        if (halfReady) {
            val target = IntArray(1)
            GLES20.glGetIntegerv(GLES20.GL_FRAMEBUFFER_BINDING, target, 0)

            // Ray-march into the half-res buffer.
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, halfFbo)
            GLES20.glViewport(0, 0, halfW, halfH)
            renderMarch(halfW.toFloat(), halfH.toFloat(), time, travel, beat)

            // Upscale into the master's buffer (linear filtering smooths it).
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, target[0])
            GLES20.glViewport(0, 0, w, h)
            GLES20.glUseProgram(blitProg)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, halfTex)
            GLES20.glUniform1i(uBlitTex, 0)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 3)
        } else {
            // Fallback: march straight into the bound buffer at full res.
            renderMarch(w.toFloat(), h.toFloat(), time, travel, beat)
        }
    }

    private fun renderMarch(rw: Float, rh: Float, time: Float, travel: Float, beat: Float) {
        GLES20.glUseProgram(marchProg)
        GLES20.glUniform2f(uResolution, rw, rh)
        GLES20.glUniform1f(uTime, time)
        GLES20.glUniform1f(uTravel, travel)
        GLES20.glUniform1f(uEnv, beat)
        GLES20.glUniform1f(uScale, scale)
        GLES20.glUniform1f(uEmit, emit)
        GLES20.glUniform1f(uSpark, spark)
        GLES20.glUniform1f(uHue, hue)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 3)
    }

    private fun allocateHalf() {
        releaseHalf()
        val tex = IntArray(1)
        GLES20.glGenTextures(1, tex, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex[0])
        GLES30.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, GLES30.GL_RGBA16F, halfW, halfH, 0,
            GLES20.GL_RGBA, GLES30.GL_HALF_FLOAT, null
        )
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        halfTex = tex[0]

        val fbo = IntArray(1)
        GLES20.glGenFramebuffers(1, fbo, 0)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo[0])
        GLES20.glFramebufferTexture2D(
            GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, halfTex, 0
        )
        halfReady = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) == GLES20.GL_FRAMEBUFFER_COMPLETE
        halfFbo = fbo[0]
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
    }

    private fun releaseHalf() {
        if (halfTex != 0) GLES20.glDeleteTextures(1, intArrayOf(halfTex), 0)
        if (halfFbo != 0) GLES20.glDeleteFramebuffers(1, intArrayOf(halfFbo), 0)
        halfTex = 0; halfFbo = 0; halfReady = false
    }

    companion object {
        private const val TAG = "Cathedral"

        // Full-screen triangle (no VBO) — shared by the march and the upscale.
        private const val FULLSCREEN_VS = """#version 300 es
            out vec2 v_uv;
            void main() {
                vec2 p = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));
                v_uv = p;
                gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
            }
        """

        private const val BLIT_FS = """#version 300 es
            precision highp float;
            in vec2 v_uv;
            uniform sampler2D u_tex;
            out vec4 o;
            void main() { o = vec4(texture(u_tex, v_uv).rgb, 1.0); }
        """

        private const val MARCH_FS = """#version 300 es
            precision highp float;
            in vec2 v_uv;
            uniform vec2  u_resolution;
            uniform float u_time;
            uniform float u_travel;
            uniform float u_env;
            uniform float u_scale;
            uniform float u_emit;
            uniform float u_spark;
            uniform float u_hue;
            out vec4 fragColor;

            #define ITER  9
            #define MARCH 60
            #define TAU 6.2831853
            const float MINR2 = 0.25;
            const float FIXEDR2 = 1.0;

            vec3 palette(float x) {
                return 0.5 + 0.5 * cos(TAU * (vec3(1.0) * x + vec3(0.0, 0.33, 0.55) + u_hue));
            }

            float boxDE(vec3 p, float scale, out float trap) {
                vec3 offset = p;
                float dr = 1.0;
                trap = 1e9;
                for (int i = 0; i < ITER; i++) {
                    p = clamp(p, -1.0, 1.0) * 2.0 - p;
                    float r2 = max(dot(p, p), 1e-6);
                    if (r2 < MINR2) { float t = FIXEDR2 / MINR2; p *= t; dr *= t; }
                    else if (r2 < FIXEDR2) { float t = FIXEDR2 / r2; p *= t; dr *= t; }
                    p = p * scale + offset;
                    dr = dr * abs(scale) + 1.0;
                    trap = min(trap, length(p));
                }
                return length(p) / abs(dr);
            }
            float mapD(vec3 p, float scale) { float t; return boxDE(p, scale, t); }
            vec3 calcNormal(vec3 p, float scale) {
                vec2 e = vec2(0.001, 0.0);
                return normalize(vec3(
                    mapD(p + e.xyy, scale) - mapD(p - e.xyy, scale),
                    mapD(p + e.yxy, scale) - mapD(p - e.yxy, scale),
                    mapD(p + e.yyx, scale) - mapD(p - e.yyx, scale)));
            }
            float calcAO(vec3 p, vec3 n, float scale) {
                float occ = 0.0; float sca = 1.0;
                for (int i = 0; i < 5; i++) {
                    float hh = 0.012 + 0.14 * float(i) / 4.0;
                    occ += (hh - mapD(p + n * hh, scale)) * sca;
                    sca *= 0.65;
                }
                return clamp(1.0 - 2.2 * occ, 0.0, 1.0);
            }
            mat2 rot(float a) { float c = cos(a), s = sin(a); return mat2(c, -s, s, c); }

            void main() {
                vec2 uv = (gl_FragCoord.xy - 0.5 * u_resolution) / u_resolution.y;
                float scale = -u_scale;

                // Fly through: camera spirals + dollies on the shared travel; the
                // beat dives it inward.
                float ang = u_travel * 0.16 + u_time * 0.01;
                float radius = 4.6 + 1.0 * sin(u_travel * 0.06) - u_env * 0.55;
                float hgt = 1.3 * sin(u_travel * 0.045);
                vec3 ro = vec3(sin(ang) * radius, hgt, cos(ang) * radius);
                vec3 ta = vec3(0.0, 0.12 * sin(u_time * 0.06), 0.0);

                vec3 fwd = normalize(ta - ro);
                vec3 rgt = normalize(cross(vec3(0.0, 1.0, 0.0), fwd));
                vec3 up  = cross(fwd, rgt);
                float roll = sin(u_time * 0.05) * 0.25;
                vec2 ruv = uv * rot(roll);
                vec3 rd = normalize(ruv.x * rgt + ruv.y * up + 1.5 * fwd);

                float t = 0.06; float trap = 1e9; float glow = 0.0; bool hit = false;
                for (int i = 0; i < MARCH; i++) {
                    vec3 pos = ro + rd * t;
                    float tr;
                    float d = boxDE(pos, scale, tr);
                    glow += exp(-tr * 2.3) / (1.0 + t * t * 0.35);
                    if (d < 0.0014 * t) { hit = true; trap = tr; break; }
                    t += d;
                    if (t > 14.0) break;
                }
                glow *= 0.06;

                vec3 col = vec3(0.0);
                if (hit) {
                    vec3 pos = ro + rd * t;
                    vec3 n = calcNormal(pos, scale);
                    float ao = calcAO(pos, n, scale);
                    vec3 key = normalize(vec3(0.6, 0.75, 0.45));
                    float dif = clamp(dot(n, key), 0.0, 1.0);
                    float fill = clamp(0.5 + 0.5 * dot(n, vec3(-0.4, 0.2, -0.5)), 0.0, 1.0);
                    float fre = pow(1.0 - clamp(dot(n, -rd), 0.0, 1.0), 3.0);
                    vec3 base = palette(trap * 0.55 + 0.15);
                    vec3 cool = palette(trap * 0.55 + 0.45);
                    col  = base * dif * 1.15;
                    col += cool * fill * 0.25;
                    col *= ao;
                    float emit = u_emit + u_spark * fre * 1.5;
                    col += base * fre * (0.6 + emit * 3.0);
                    col *= 0.55 + 0.45 * smoothstep(0.0, 0.6, trap);
                    col = mix(col, vec3(0.02, 0.02, 0.05), 1.0 - exp(-t * 0.085));
                }
                vec3 glowCol = palette(0.6 + 0.2 * sin(u_time * 0.1));
                col += glowCol * glow * (1.2 + u_env * 2.5);
                col *= 1.0 - 0.25 * dot(uv, uv);
                fragColor = vec4(max(col, 0.0), 1.0);
            }
        """
    }
}
