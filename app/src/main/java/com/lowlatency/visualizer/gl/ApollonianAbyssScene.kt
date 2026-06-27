package com.lowlatency.visualizer.gl

import android.opengl.GLES20
import android.opengl.GLES30
import android.util.Log
import com.lowlatency.visualizer.BeatPulse
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * "Apollonian Abyss" — a ray-marched Apollonian Gasket fractal creating a sense of 
 * infinite depth and cavernous architecture. Features deep volumetric scattering, 
 * audio-reactive orbit-trap lighting, and a relentless forward motion driven by the high end.
 */
class ApollonianAbyssScene : GlScene {

    companion object {
        private const val TAG = "ApollonianAbyss"

        private const val VERT = """#version 300 es
layout(location = 0) in vec2 aPos;
void main() { gl_Position = vec4(aPos, 0.0, 1.0); }
"""

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

        private const val FRAG = """#version 300 es
precision highp float;

uniform vec2  u_resolution;
uniform float u_time;
uniform float u_dim;
uniform float u_env;        // beat envelope
uniform float u_scale;      // bass-breathed structural morph
uniform float u_emit;       // mid-driven neon intensity
uniform float u_spark;      // treble-driven camera velocity
uniform float u_hue;

out vec4 fragColor;

#define ITER 8
#define MARCH 75
#define TAU 6.2831853

vec3 palette(float x) {
    // Deep neon cyberpunk / synthwave palette
    vec3 a = vec3(0.5, 0.5, 0.5);
    vec3 b = vec3(0.5, 0.5, 0.5);
    vec3 c = vec3(1.0, 1.0, 0.5);
    vec3 d = vec3(0.80, 0.90, 0.30);
    return a + b * cos(TAU * (c * x + d + u_hue));
}

mat2 rot(float a) { float s = sin(a), c = cos(a); return mat2(c, -s, s, c); }

float apollonianDE(vec3 p, out float trap) {
    float scale = 1.0;
    trap = 1e9;
    
    // Smooth morphing of the gasket
    float s = 1.4 + 0.1 * sin(u_time * 0.2) + u_scale * 0.1;

    for (int i = 0; i < ITER; i++) {
        p = -1.0 + 2.0 * fract(0.5 * p + 0.5);
        float r2 = max(dot(p, p), 1e-5);
        
        trap = min(trap, r2);
        
        float k = s / r2;
        p *= k;
        scale *= k;
    }
    
    // Distance estimator for apollonian gasket
    return 0.25 * abs(p.y) / scale;
}

float map(vec3 p) {
    float t;
    return apollonianDE(p, t);
}

vec3 calcNormal(vec3 p) {
    vec2 e = vec2(0.001, 0.0);
    return normalize(vec3(
        map(p + e.xyy) - map(p - e.xyy),
        map(p + e.yxy) - map(p - e.yxy),
        map(p + e.yyx) - map(p - e.yyx)
    ));
}

float calcAO(vec3 p, vec3 n) {
    float occ = 0.0;
    float sca = 1.0;
    for (int i = 0; i < 5; i++) {
        float h = 0.01 + 0.12 * float(i) / 4.0;
        float d = map(p + n * h);
        occ += (h - d) * sca;
        sca *= 0.75;
    }
    return clamp(1.0 - 1.5 * occ, 0.0, 1.0);
}

void main() {
    vec2 uv = (gl_FragCoord.xy - 0.5 * u_resolution) / u_resolution.y;

    // Camera parameters
    float timeObj = u_time * 0.5 + u_spark * 2.0;
    
    vec3 ro = vec3(0.5 + 0.1*sin(timeObj*0.3), 0.5 + 0.1*cos(timeObj*0.4), timeObj);
    vec3 ta = ro + vec3(0.0, 0.0, 1.0); // look straight ahead into the abyss
    
    // Add some subtle camera shake on beat
    ta.xy += vec2(sin(u_time*20.0), cos(u_time*25.0)) * u_env * 0.02;

    vec3 fwd = normalize(ta - ro);
    vec3 rgt = normalize(cross(vec3(0.0, 1.0, 0.0), fwd));
    vec3 up  = cross(fwd, rgt);
    
    // Bank roll
    float roll = sin(u_time * 0.1) * 0.5 + u_spark * 0.2;
    vec2 ruv = uv * rot(roll);
    vec3 rd = normalize(ruv.x * rgt + ruv.y * up + 1.2 * fwd);

    float t = 0.0;
    float trap = 1e9;
    float glow = 0.0;
    bool hit = false;
    
    for (int i = 0; i < MARCH; i++) {
        vec3 pos = ro + rd * t;
        float tr;
        float d = apollonianDE(pos, tr);
        
        // Volumetric glow based on proximity to the structure
        glow += exp(-tr * 3.0) * 0.02 / (1.0 + t*t*0.1);
        
        if (d < 0.001 * t) {
            hit = true;
            trap = tr;
            break;
        }
        t += d;
        if (t > 20.0) break;
    }

    vec3 col = vec3(0.0);

    if (hit) {
        vec3 pos = ro + rd * t;
        vec3 n = calcNormal(pos);
        float ao = calcAO(pos, n);

        // Lighting
        vec3 lig = normalize(vec3(0.8, 0.6, -0.5));
        float dif = clamp(dot(n, lig), 0.0, 1.0);
        float fre = pow(clamp(1.0 + dot(n, rd), 0.0, 1.0), 3.0);
        
        vec3 baseColor = palette(trap * 2.0 + u_time * 0.1);
        
        col = baseColor * (dif * 0.8 + 0.2);
        col *= ao;
        
        // Audio reactive rim lighting
        float emitIntensity = u_emit * 2.0 + u_spark * 3.0;
        col += baseColor * fre * (0.5 + emitIntensity);
        
        // Darken deep crevices
        col *= smoothstep(0.0, 0.2, trap);
    }

    // Heavy atmospheric scattering (abyssal fog)
    vec3 fogColor = vec3(0.02, 0.0, 0.05); // deep indigo/void
    col = mix(col, fogColor, 1.0 - exp(-t * 0.15));

    // Add volumetric structural glow
    col += palette(0.5 + u_time * 0.05) * glow * (1.0 + u_env * 2.0);

    // Vignette
    col *= 1.0 - 0.4 * length(uv);

    fragColor = vec4(max(col, 0.0) * u_dim, 1.0);
}
"""
    }

    private val quad: FloatBuffer = ByteBuffer
        .allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        .apply { put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)); position(0) }

    private var program = 0
    private var blitProg = 0
    private var aPos = 0
    private var uResolution = 0
    private var uTime = 0
    private var uDim = 0
    private var uEnv = 0
    private var uScale = 0
    private var uEmit = 0
    private var uSpark = 0
    private var uHue = 0
    private var uBlitTex = 0

    private var iw = 1; private var ih = 1
    private var w = 1f; private var h = 1f
    private var halfW = 1; private var halfH = 1
    private var halfFbo = 0; private var halfTex = 0
    private var halfReady = false

    private var scale = 0f
    private var emit = 0f
    private var spark = 0f
    private var hue = 0f

    override fun onCreated() {
        program = ShaderUtil.buildProgram(VERT, FRAG)
        aPos = GLES20.glGetAttribLocation(program, "aPos")
        uResolution = GLES20.glGetUniformLocation(program, "u_resolution")
        uTime = GLES20.glGetUniformLocation(program, "u_time")
        uDim = GLES20.glGetUniformLocation(program, "u_dim")
        uEnv = GLES20.glGetUniformLocation(program, "u_env")
        uScale = GLES20.glGetUniformLocation(program, "u_scale")
        uEmit = GLES20.glGetUniformLocation(program, "u_emit")
        uSpark = GLES20.glGetUniformLocation(program, "u_spark")
        uHue = GLES20.glGetUniformLocation(program, "u_hue")

        try {
            blitProg = ShaderUtil.buildProgram(FULLSCREEN_VS, BLIT_FS)
        } catch (e: RuntimeException) {
            Log.e(TAG, "Blit shader build failed; half-res disabled.", e)
        }
        if (blitProg != 0) {
            uBlitTex = GLES20.glGetUniformLocation(blitProg, "u_tex")
        }
    }

    override fun onResize(width: Int, height: Int, aspect: Float) {
        iw = width.coerceAtLeast(1); ih = height.coerceAtLeast(1)
        w = iw.toFloat(); h = ih.toFloat()
        halfW = (iw / 2).coerceAtLeast(1); halfH = (ih / 2).coerceAtLeast(1)
        if (blitProg != 0) allocateHalf()
    }

    override fun draw(pcm: FloatArray, bands: FloatArray, timeSec: Float, dim: Float) {
        val low = bands[0]
        val mid = bands[1]
        val high = bands[2]
        val env = BeatPulse.envelope

        scale += (low - scale) * 0.1f
        emit += (mid - emit) * 0.2f
        spark += (high - spark) * 0.15f
        hue += (0.01f + high * 0.05f) * 0.016f

        GLES20.glDisable(GLES20.GL_BLEND)

        if (halfReady) {
            val target = IntArray(1)
            GLES20.glGetIntegerv(GLES20.GL_FRAMEBUFFER_BINDING, target, 0)

            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, halfFbo)
            GLES20.glViewport(0, 0, halfW, halfH)
            renderMarch(halfW.toFloat(), halfH.toFloat(), timeSec, dim, env)

            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, target[0])
            GLES20.glViewport(0, 0, iw, ih)
            GLES20.glUseProgram(blitProg)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, halfTex)
            GLES20.glUniform1i(uBlitTex, 0)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 3)
        } else {
            renderMarch(w, h, timeSec, dim, env)
        }
    }

    private fun renderMarch(rw: Float, rh: Float, timeSec: Float, dim: Float, env: Float) {
        GLES20.glUseProgram(program)
        GLES20.glUniform2f(uResolution, rw, rh)
        GLES20.glUniform1f(uTime, timeSec)
        GLES20.glUniform1f(uDim, dim)
        GLES20.glUniform1f(uEnv, env)
        GLES20.glUniform1f(uScale, scale)
        GLES20.glUniform1f(uEmit, emit)
        GLES20.glUniform1f(uSpark, spark)
        GLES20.glUniform1f(uHue, hue)

        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, quad)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(aPos)
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
}
