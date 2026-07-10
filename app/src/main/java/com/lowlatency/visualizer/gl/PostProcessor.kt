package com.lowlatency.visualizer.gl

import android.opengl.GLES20
import android.opengl.GLES30
import android.util.Log

/**
 * HDR bloom post-processing pipeline.
 *
 * Scenes render into an offscreen FP16 framebuffer instead of straight to the
 * screen. After the scene draws, we:
 *   1. extract the bright (HDR, >threshold) highlights,
 *   2. blur them with a separable Gaussian at QUARTER resolution (cheap),
 *   3. additively composite the blurred glow back over the scene to the screen.
 *
 * All of this happens within a single frame before the buffer swap, so it adds
 * no frame-queuing latency — only a small, bounded GPU cost (kept low by the
 * quarter-res blur). When disabled, the renderer bypasses this entirely and
 * draws straight to the screen as before.
 *
 * Full-screen passes use a single oversized triangle generated from
 * `gl_VertexID` (no VBO/VAO needed).
 */
class PostProcessor {

    private var sceneFbo = 0
    private var sceneTex = 0
    private val bloomFbo = IntArray(2)
    private val bloomTex = IntArray(2)

    private var allocatedW = 0
    private var allocatedH = 0

    private var width = 1
    private var height = 1
    private var bloomW = 1
    private var bloomH = 1

    private var brightProg = 0
    private var blurProg = 0
    private var compositeProg = 0

    private var uUvScaleBright = 0
    private var uUvScaleBlur = 0
    private var uUvScaleComp = 0

    private var uBrightTex = 0
    private var uThreshold = 0
    private var uBlurTex = 0
    private var uBlurDir = 0
    private var uCompScene = 0
    private var uCompBloom = 0
    private var uCompBloomI = 0
    private var uCompExposure = 0
    private var uCompHueShift = 0
    private var uCompSat = 0
    private var uCompTint = 0
    private var uCompPulse = 0
    private var uCompAspect = 0

    // Intro shockwave pulse (see setIntroPulse). All zero outside the intro, so
    // the composite shader's pulse branch never executes on the normal path.
    private var pulseRadius = 0f
    private var pulseAmp = 0f
    private var pulseChroma = 0f

    private var ready = false

    fun onCreated() {
        // The GL context was recreated. Old handles belong to the dead context.
        sceneTex = 0
        sceneFbo = 0
        bloomTex[0] = 0
        bloomTex[1] = 0
        bloomFbo[0] = 0
        bloomFbo[1] = 0
        ready = false
        allocatedW = 0
        allocatedH = 0
        pulseRadius = 0f
        pulseAmp = 0f
        pulseChroma = 0f

        brightProg = ShaderUtil.buildProgram(QUAD_VS, BRIGHT_FS)
        blurProg = ShaderUtil.buildProgram(QUAD_VS, BLUR_FS)
        compositeProg = ShaderUtil.buildProgram(QUAD_VS, COMPOSITE_FS)

        uUvScaleBright = GLES20.glGetUniformLocation(brightProg, "u_uvScale")
        uUvScaleBlur = GLES20.glGetUniformLocation(blurProg, "u_uvScale")
        uUvScaleComp = GLES20.glGetUniformLocation(compositeProg, "u_uvScale")

        uBrightTex = GLES20.glGetUniformLocation(brightProg, "u_tex")
        uThreshold = GLES20.glGetUniformLocation(brightProg, "u_threshold")
        uBlurTex = GLES20.glGetUniformLocation(blurProg, "u_tex")
        uBlurDir = GLES20.glGetUniformLocation(blurProg, "u_dir")
        uCompScene = GLES20.glGetUniformLocation(compositeProg, "u_scene")
        uCompBloom = GLES20.glGetUniformLocation(compositeProg, "u_bloom")
        uCompBloomI = GLES20.glGetUniformLocation(compositeProg, "u_bloomIntensity")
        uCompExposure = GLES20.glGetUniformLocation(compositeProg, "u_exposure")
        uCompHueShift = GLES20.glGetUniformLocation(compositeProg, "u_hueShift")
        uCompSat = GLES20.glGetUniformLocation(compositeProg, "u_saturation")
        uCompTint = GLES20.glGetUniformLocation(compositeProg, "u_tint")
        uCompPulse = GLES20.glGetUniformLocation(compositeProg, "u_pulse")
        uCompAspect = GLES20.glGetUniformLocation(compositeProg, "u_aspect")
    }

    /**
     * Drive the intro shockwave: a radial refraction ring + chromatic pulse in
     * the composite pass. [amplitude] 0 disables it entirely (the shader takes
     * the plain-sample branch), so leave everything at 0 outside the intro.
     */
    fun setIntroPulse(radius: Float, amplitude: Float, chroma: Float) {
        pulseRadius = radius
        pulseAmp = amplitude
        pulseChroma = chroma
    }

    fun resize(width: Int, height: Int) {
        val newW = width.coerceAtLeast(1)
        val newH = height.coerceAtLeast(1)
        
        if (newW <= allocatedW && newH <= allocatedH && ready) {
            this.width = newW
            this.height = newH
            bloomW = (newW / 4).coerceAtLeast(1)
            bloomH = (newH / 4).coerceAtLeast(1)
            return
        }

        this.width = newW
        this.height = newH
        bloomW = (this.width / 4).coerceAtLeast(1)
        bloomH = (this.height / 4).coerceAtLeast(1)
        
        allocatedW = this.width
        allocatedH = this.height
        val allocatedBloomW = bloomW
        val allocatedBloomH = bloomH

        release()

        sceneTex = createColorTexture(allocatedW, allocatedH)
        sceneFbo = createFbo(sceneTex)
        for (i in 0..1) {
            bloomTex[i] = createColorTexture(allocatedBloomW, allocatedBloomH)
            bloomFbo[i] = createFbo(bloomTex[i])
        }
        ready = sceneFbo != 0 && bloomFbo[0] != 0 && bloomFbo[1] != 0
        if (!ready) Log.w(TAG, "Post-processing FBOs incomplete; bloom disabled.")
    }

    /** Bind the offscreen scene buffer; the renderer then clears + draws the scene. */
    fun beginScene() {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, sceneFbo)
        GLES20.glViewport(0, 0, width, height)
    }

    val isReady: Boolean get() = ready

    /**
     * Run bright-pass → blur → composite (+ colour-grade), ending on the default
     * framebuffer (the screen).
     *
     * @param bloomIntensity glow strength (rises on the beat; 0 = glow off)
     * @param exposure       overall scene exposure (a gentle beat lift)
     * @param hueShift       theme hue rotation (radians about the luma axis)
     * @param saturation     theme saturation scale (1 = unchanged)
     * @param tintR/G/B      theme tint multiplier
     */
    fun present(
        bloomIntensity: Float, exposure: Float,
        hueShift: Float, saturation: Float, tintR: Float, tintG: Float, tintB: Float,
    ) {
        // The scene may have left additive blending / depth on — the post passes
        // must run on a clean state.
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glDisable(GLES20.GL_CULL_FACE)

        // 1) Bright-pass: scene -> bloomTex[0] (quarter res).
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, bloomFbo[0])
        GLES20.glViewport(0, 0, bloomW, bloomH)
        GLES20.glUseProgram(brightProg)
        
        val scaleX = width.toFloat() / allocatedW
        val scaleY = height.toFloat() / allocatedH
        GLES20.glUniform2f(uUvScaleBright, scaleX, scaleY)
        
        bindTex(0, sceneTex, uBrightTex)
        GLES20.glUniform1f(uThreshold, BLOOM_THRESHOLD)
        drawTriangle()

        // 2) Separable Gaussian bloom, ping-ponging the two quarter-res buffers.
        GLES20.glUseProgram(blurProg)
        GLES20.glUniform2f(uUvScaleBlur, scaleX, scaleY)
        val texelX = 1f / bloomW
        val texelY = 1f / bloomH
        var srcTex = bloomTex[0]
        var dstFbo = bloomFbo[1]
        for (pass in 0 until BLUR_PASSES) {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, dstFbo)
            GLES20.glViewport(0, 0, bloomW, bloomH)
            bindTex(0, srcTex, uBlurTex)
            GLES20.glUniform2f(uBlurDir, texelX, 0f)
            drawTriangle()
            srcTex = if (dstFbo == bloomFbo[1]) bloomTex[1] else bloomTex[0]
            dstFbo = if (dstFbo == bloomFbo[1]) bloomFbo[0] else bloomFbo[1]

            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, dstFbo)
            GLES20.glViewport(0, 0, bloomW, bloomH)
            bindTex(0, srcTex, uBlurTex)
            GLES20.glUniform2f(uBlurDir, 0f, texelY)
            drawTriangle()
            srcTex = if (dstFbo == bloomFbo[1]) bloomTex[1] else bloomTex[0]
            dstFbo = if (dstFbo == bloomFbo[1]) bloomFbo[0] else bloomFbo[1]
        }
        val finalBloomTex = srcTex   // last buffer written

        // 3) Composite scene + glow -> screen (default framebuffer).
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glViewport(0, 0, width, height)
        GLES20.glUseProgram(compositeProg)
        GLES20.glUniform2f(uUvScaleComp, scaleX, scaleY)
        bindTex(0, sceneTex, uCompScene)
        bindTex(1, finalBloomTex, uCompBloom)
        GLES20.glUniform1f(uCompBloomI, bloomIntensity)
        GLES20.glUniform1f(uCompExposure, exposure)
        GLES20.glUniform1f(uCompHueShift, hueShift)
        GLES20.glUniform1f(uCompSat, saturation)
        GLES20.glUniform3f(uCompTint, tintR, tintG, tintB)
        GLES20.glUniform3f(uCompPulse, pulseRadius, pulseAmp, pulseChroma)
        GLES20.glUniform1f(uCompAspect, width.toFloat() / height.toFloat())
        drawTriangle()
    }

    private fun bindTex(unit: Int, tex: Int, sampler: Int) {
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + unit)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex)
        GLES20.glUniform1i(sampler, unit)
    }

    private fun drawTriangle() {
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 3)
    }

    private fun createColorTexture(w: Int, h: Int): Int {
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        val tex = ids[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex)
        // FP16 keeps HDR (>1.0) values so the bright-pass can extract real
        // overdrive. Falls back implicitly to clamped behaviour if the device
        // can't render to RGBA16F (caught by the FBO completeness check).
        GLES30.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, GLES30.GL_RGBA16F, w, h, 0,
            GLES20.GL_RGBA, GLES30.GL_HALF_FLOAT, null
        )
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        return tex
    }

    private fun createFbo(tex: Int): Int {
        val ids = IntArray(1)
        GLES20.glGenFramebuffers(1, ids, 0)
        val fbo = ids[0]
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo)
        GLES20.glFramebufferTexture2D(
            GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, tex, 0
        )
        val status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        if (status != GLES20.GL_FRAMEBUFFER_COMPLETE) {
            Log.w(TAG, "FBO incomplete: 0x${Integer.toHexString(status)}")
            return 0
        }
        return fbo
    }

    private fun release() {
        if (sceneTex != 0) GLES20.glDeleteTextures(1, intArrayOf(sceneTex), 0)
        if (sceneFbo != 0) GLES20.glDeleteFramebuffers(1, intArrayOf(sceneFbo), 0)
        for (i in 0..1) {
            if (bloomTex[i] != 0) GLES20.glDeleteTextures(1, intArrayOf(bloomTex[i]), 0)
            if (bloomFbo[i] != 0) GLES20.glDeleteFramebuffers(1, intArrayOf(bloomFbo[i]), 0)
            bloomTex[i] = 0; bloomFbo[i] = 0
        }
        sceneTex = 0; sceneFbo = 0
        ready = false
    }

    companion object {
        private const val TAG = "PostProcessor"
        private const val BLOOM_THRESHOLD = 0.82f   // only the brightest cores bloom (tight halo)
        private const val BLUR_PASSES = 2           // H+V iterations (wider, softer glow)

        // Full-screen triangle from gl_VertexID — no attributes/VBO.
        private const val QUAD_VS = """#version 300 es
            uniform vec2 u_uvScale;
            out vec2 v_uv;
            void main() {
                vec2 p = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));
                v_uv = p * u_uvScale;             // covers the valid sub-region of the texture
                gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
            }
        """

        private const val BRIGHT_FS = """#version 300 es
            precision highp float;
            uniform sampler2D u_tex;
            uniform float u_threshold;
            in vec2 v_uv;
            out vec4 o;
            void main() {
                vec3 c = texture(u_tex, v_uv).rgb;
                float lum = max(c.r, max(c.g, c.b));
                float f = clamp((lum - u_threshold) / max(u_threshold, 1e-3), 0.0, 1.0);
                o = vec4(c * f, 1.0);
            }
        """

        private const val BLUR_FS = """#version 300 es
            precision highp float;
            uniform sampler2D u_tex;
            uniform vec2 u_dir;
            in vec2 v_uv;
            out vec4 o;
            void main() {
                // 9-tap Gaussian via 5 linear-filtered samples.
                vec3 sum = texture(u_tex, v_uv).rgb * 0.227027;
                sum += texture(u_tex, v_uv + u_dir * 1.3846).rgb * 0.316216;
                sum += texture(u_tex, v_uv - u_dir * 1.3846).rgb * 0.316216;
                sum += texture(u_tex, v_uv + u_dir * 3.2308).rgb * 0.070270;
                sum += texture(u_tex, v_uv - u_dir * 3.2308).rgb * 0.070270;
                o = vec4(sum, 1.0);
            }
        """

        private const val COMPOSITE_FS = """#version 300 es
            precision highp float;
            uniform sampler2D u_scene;
            uniform sampler2D u_bloom;
            uniform float u_bloomIntensity;
            uniform float u_exposure;
            uniform float u_hueShift;     // theme: radians about the luma axis
            uniform float u_saturation;   // theme: saturation scale
            uniform vec3  u_tint;         // theme: rgb tint
            uniform vec2  u_uvScale;      // valid sub-region of the (over-allocated) FBO
            uniform vec3  u_pulse;        // intro shockwave: x=radius y=refraction z=chroma
            uniform float u_aspect;
            in vec2 v_uv;
            out vec4 o;

            // Cheap hash for output dithering (kills banding on 8-bit surfaces).
            float hash(vec2 p) { return fract(sin(dot(p, vec2(12.9898, 78.233))) * 43758.5453); }

            // Hue rotation about the (1,1,1) luma axis (Rodrigues) — preserves
            // brightness/HDR magnitude while spinning the hue.
            vec3 hueRotate(vec3 c, float a) {
                const vec3 k = vec3(0.57735027);
                float cosA = cos(a);
                return c * cosA + cross(k, c) * sin(a) + k * dot(k, c) * (1.0 - cosA);
            }

            void main() {
                vec2 uv = v_uv;
                vec3 scene;
                if (u_pulse.y > 0.001) {
                    // Intro shockwave: a gaussian ring that refracts the frame
                    // radially, with chromatic aberration strongest on the ring.
                    // The branch is on a uniform (dynamically uniform control
                    // flow), so the normal path pays one compare and nothing else.
                    vec2 p = uv / u_uvScale - 0.5;
                    p.x *= u_aspect;
                    float r = length(p);
                    float band = r - u_pulse.x;
                    float ring = exp(-band * band * 90.0);
                    vec2 dir = (p / max(r, 1e-4)) * vec2(1.0 / u_aspect, 1.0);
                    // Displaced UVs must stay inside [0, u_uvScale] — the FP16
                    // buffer can be over-allocated, and beyond that lies garbage.
                    uv = clamp(uv - dir * ring * band * u_pulse.y, vec2(0.0), u_uvScale);
                    vec2 ca = dir * ring * r * u_pulse.z;
                    scene = vec3(
                        texture(u_scene, clamp(uv + ca, vec2(0.0), u_uvScale)).r,
                        texture(u_scene, uv).g,
                        texture(u_scene, clamp(uv - ca, vec2(0.0), u_uvScale)).b);
                } else {
                    scene = texture(u_scene, uv).rgb;
                }
                vec3 bloom = texture(u_bloom, uv).rgb;   // glow rides the ripple too

                // Bloom carries the beat punch; scene exposure lifts only gently.
                vec3 col = scene * u_exposure + bloom * u_bloomIntensity;

                // Theme grade: saturation -> hue rotation -> tint. (Identity for
                // the default theme: sat 1, hue 0, tint 1.)
                float luma = dot(col, vec3(0.2126, 0.7152, 0.0722));
                col = mix(vec3(luma), col, u_saturation);
                col = hueRotate(col, u_hueShift);
                col *= u_tint;

                col = max(col, 0.0);
                // Dither only above true black so OLED pixels stay fully off.
                float peak = max(col.r, max(col.g, col.b));
                if (peak > 0.5 / 255.0)
                    col += (hash(gl_FragCoord.xy) - 0.5) / 255.0;
                o = vec4(col, 1.0);
            }
        """
    }
}
