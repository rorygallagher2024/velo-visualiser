package com.lowlatency.visualizer.gl

import android.opengl.GLES20
import android.opengl.GLES30
import com.lowlatency.visualizer.BeatPulse
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * "Electric Iris" — Volumetric Raymarching Overhaul.
 * Renders a cinematic, 3D gaseous nebula surrounding a perfectly anti-aliased SDF void.
 * 
 * - Lows -> Dilates the SDF pupil void.
 * - Mids -> Churns the 3D FBM coordinates, causing clouds to boil.
 * - Highs/Env -> Triggers crisp SDF lightning arcs striking the pupil edge.
 */
class ElectricIrisScene : GlScene {

    private val quad: FloatBuffer = ByteBuffer
        .allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        .apply { put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)); position(0) }

    private val upload: FloatBuffer = ByteBuffer
        .allocateDirect(BINS * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()

    private var program = 0
    private var aPos = 0
    private var uResolution = 0
    private var uTime = 0
    private var uDim = 0
    private var uLow = 0
    private var uMid = 0
    private var uHigh = 0
    private var uEnv = 0
    private var uPhase = 0
    private var uSpectrum = 0

    private var specTex = 0
    private var width = 1f
    private var height = 1f

    private var smoothedLow = 0f
    private var smoothedMid = 0f
    private var smoothedHigh = 0f

    override fun onCreated() {
        program = ShaderUtil.buildProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        aPos = GLES20.glGetAttribLocation(program, "aPos")
        uResolution = GLES20.glGetUniformLocation(program, "u_resolution")
        uTime = GLES20.glGetUniformLocation(program, "u_time")
        uDim = GLES20.glGetUniformLocation(program, "u_dim")
        uLow = GLES20.glGetUniformLocation(program, "u_low")
        uMid = GLES20.glGetUniformLocation(program, "u_mid")
        uHigh = GLES20.glGetUniformLocation(program, "u_high")
        uEnv = GLES20.glGetUniformLocation(program, "u_env")
        uPhase = GLES20.glGetUniformLocation(program, "u_phase")
        uSpectrum = GLES20.glGetUniformLocation(program, "u_spectrum")

        val tex = IntArray(1)
        GLES20.glGenTextures(1, tex, 0)
        specTex = tex[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, specTex)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, GLES30.GL_R32F,
            BINS, 1, 0, GLES30.GL_RED, GLES20.GL_FLOAT, null
        )
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
    }

    override fun onResize(width: Int, height: Int, aspect: Float) {
        this.width = width.toFloat()
        this.height = height.toFloat()
    }

    override fun draw(pcm: FloatArray, bands: FloatArray, timeSec: Float, dim: Float) {
        if (program == 0) return

        upload.clear()
        upload.put(com.lowlatency.visualizer.gl.SpectrumData.magnitudes)
        upload.position(0)

        val low = maxOf(0f, bands[0] - 0.1f) * 1.5f
        val mid = maxOf(0f, bands[1] - 0.1f) * 1.5f
        val high = maxOf(0f, bands[2] - 0.15f) * 1.5f
        
        smoothedLow += (low - smoothedLow) * 0.15f
        smoothedMid += (mid - smoothedMid) * 0.15f
        smoothedHigh += (high - smoothedHigh) * 0.2f

        val env = BeatPulse.envelope
        val phase = if (BeatPulse.linkActive) BeatPulse.barPhase else (timeSec * 0.1f) % 1f

        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glUseProgram(program)
        
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, specTex)
        GLES20.glTexSubImage2D(
            GLES20.GL_TEXTURE_2D, 0, 0, 0, BINS, 1,
            GLES30.GL_RED, GLES20.GL_FLOAT, upload
        )
        GLES20.glUniform1i(uSpectrum, 0)
        
        GLES20.glUniform2f(uResolution, width, height)
        GLES20.glUniform1f(uTime, timeSec)
        GLES20.glUniform1f(uDim, dim)
        GLES20.glUniform1f(uLow, smoothedLow)
        GLES20.glUniform1f(uMid, smoothedMid)
        GLES20.glUniform1f(uHigh, smoothedHigh)
        GLES20.glUniform1f(uEnv, env)
        GLES20.glUniform1f(uPhase, phase)

        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, quad)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(aPos)
    }

    companion object {
        private const val BINS = 128

        private const val VERTEX_SHADER = """#version 300 es
            layout(location = 0) in vec2 aPos;
            void main() { gl_Position = vec4(aPos, 0.0, 1.0); }
        """

        private const val FRAGMENT_SHADER = """#version 300 es
            precision highp float;
            uniform vec2  u_resolution;
            uniform float u_time;
            uniform float u_dim;
            uniform float u_low;
            uniform float u_mid;
            uniform float u_high;
            uniform float u_env;
            uniform float u_phase;
            uniform sampler2D u_spectrum;
            
            out vec4 fragColor;

            // Hash without Sine for 2D
            float hash12(vec2 p) {
                vec3 p3  = fract(vec3(p.xyx) * .1031);
                p3 += dot(p3, p3.yzx + 33.33);
                return fract((p3.x + p3.y) * p3.z);
            }

            // OPTIMISATION: Converted expensive 3D noise to 2D noise. 
            // We fake the 3D volume by passing 'Z' as a time-phase. Halves GPU load.
            float noise2D(vec2 x) {
                vec2 p = floor(x);
                vec2 f = fract(x);
                f = f * f * (3.0 - 2.0 * f);
                
                float res = mix(
                    mix(hash12(p), hash12(p + vec2(1,0)), f.x),
                    mix(hash12(p + vec2(0,1)), hash12(p + vec2(1,1)), f.x), 
                    f.y
                );
                return res;
            }

            // 2D FBM
            float fbm2D(vec2 p) {
                float f = 0.0;
                float amp = 0.5;
                for (int i = 0; i < 3; i++) {
                    f += amp * noise2D(p);
                    p *= 2.02;
                    amp *= 0.5;
                }
                return f;
            }

            // Premium Cinematic Palette
            vec3 palette(float t) {
                vec3 a = vec3(0.5, 0.5, 0.5);
                vec3 b = vec3(0.5, 0.5, 0.5);
                vec3 c = vec3(1.0, 1.0, 1.0);
                vec3 d = vec3(0.0, 0.33, 0.67);
                return a + b * cos(6.2831853 * (c * t + d));
            }

            // 2D Rotation
            mat2 rot(float a) {
                float s = sin(a), c = cos(a);
                return mat2(c, -s, s, c);
            }

            void main() {
                // Centered UVs, aspect corrected
                vec2 uv = (gl_FragCoord.xy - 0.5 * u_resolution) / min(u_resolution.x, u_resolution.y);
                
                // Beat punch
                uv /= (1.0 + u_env * 0.05);

                // ZOOM OUT: Scale UVs so the visual fits comfortably on inner/outer foldable screens
                uv *= 1.8;

                // Setup Raymarching
                vec3 ro = vec3(0.0, 0.0, -2.5);
                vec3 rd = normalize(vec3(uv, 1.0));

                // Volumetric Accumulation Variables
                vec4 sum = vec4(0.0);
                
                // OPTIMISATION: Dithering to hide banding from lower step count
                float dither = fract(sin(dot(gl_FragCoord.xy, vec2(12.9898, 78.233))) * 43758.5453);
                
                // OPTIMISATION: Dropped to 10 steps (from 12), stepSize increased to cover same depth
                float stepSize = 0.3;
                float t = dither * stepSize;
                
                // Read the actual audio spectrum for a real waveform effect
                float ang = atan(uv.y, uv.x);
                float aMir = abs(fract(ang / 6.2831853) * 2.0 - 1.0);
                float spec = texture(u_spectrum, vec2(aMir, 0.5)).r;
                
                // Audio Reactivity Math
                float pupilRadius = 0.35 + u_low * 0.2 + spec * 0.2;
                float audioChurn = u_mid * 2.0;

                for (int i = 0; i < 10; i++) {
                    vec3 p = ro + rd * t;
                    float r = length(p.xy);

                    float pupilMask = smoothstep(pupilRadius, pupilRadius + 0.15, r);
                    float irisMask = smoothstep(1.5, 1.0, r) * smoothstep(0.5, 0.0, abs(p.z));
                    
                    vec3 warpedP = p;
                    float twistAngle = r * 1.5 - u_time * 0.3 - u_mid;
                    warpedP.xy *= rot(twistAngle);

                    // OPTIMISATION: Use 2D FBM, passing Z as a phase offset to fake 3D depth
                    vec2 noiseDomain = warpedP.xy * 2.5 + vec2(u_time * 0.4 + audioChurn + p.z * 0.5);
                    float dens = fbm2D(noiseDomain);
                    
                    dens = smoothstep(0.4, 0.8, dens);
                    dens *= pupilMask * irisMask;
                    
                    if (dens > 0.01) {
                        vec3 col = palette(r * 0.4 - u_time * 0.1 + u_phase);
                        float limbal = smoothstep(pupilRadius + 0.2, pupilRadius, r);
                        col = mix(col, vec3(1.0, 0.6, 0.1), limbal);

                        // Increased alpha multiplier to compensate for fewer steps
                        float alpha = dens * 0.8;
                        vec4 src = vec4(col * alpha, alpha);
                        sum += src * (1.0 - sum.a);
                    }
                    
                    if (sum.a > 0.99) break;
                    t += stepSize;
                }

                vec3 finalColor = sum.rgb;

                // ==========================================
                // SDF Lightning Arcs (Layered on top)
                // ==========================================
                float rUV = length(uv);
                float distToPupil = abs(rUV - pupilRadius);

                float crackle = sin(ang * 15.0 + u_time * 10.0) * cos(ang * 9.0 - u_time * 6.0) * 0.05;
                float lightningSDF = abs(distToPupil - crackle);
                
                float spark = 0.005 / max(lightningSDF, 0.001);
                float strikePower = u_high * 1.5 + u_env + spec * 2.0;
                float strikeMask = smoothstep(0.4, 0.0, distToPupil);
                
                vec3 lightningColor = vec3(0.6, 0.9, 1.0) * spark * strikePower * strikeMask;
                finalColor += lightningColor;

                finalColor *= 1.0 - 0.4 * smoothstep(0.5, 1.5, rUV);
                finalColor *= u_dim;

                fragColor = vec4(finalColor, 1.0);
            }
        """
    }
}
