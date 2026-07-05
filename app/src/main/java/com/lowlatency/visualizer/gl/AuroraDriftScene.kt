package com.lowlatency.visualizer.gl

import android.opengl.GLES20
import com.lowlatency.visualizer.BeatPulse

/**
 * "Aurora Drift" — a volumetric, flowing aurora visualizer.
 *
 * Rendered as a single full-screen pass using Fractional Brownian Motion (FBM)
 * to simulate sweeping curtains of light in the night sky, complete with a
 * parallax starfield that twinkles to the high frequencies.
 *
 * Reactivity:
 * - Bass & Beat: Drive the height, intensity, and vertical surge of the aurora.
 * - Mids: Shift the underlying color palette organically.
 * - Highs (Treble): Twinkle the stars.
 */
class AuroraDriftScene : GlScene {

    companion object {
        private val VS = """#version 300 es
            out vec2 v_uv;
            void main() {
                vec2 p = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));
                v_uv = p;
                gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
            }
        """

        private val FS = """#version 300 es
            precision highp float;
            in vec2 v_uv;
            uniform float u_time;
            uniform float u_dim;
            uniform float u_bass;
            uniform float u_mid;
            uniform float u_high;
            uniform float u_env;
            uniform float u_aspect;
            out vec4 fragColor;

            // Hash without Sine
            float hash12(vec2 p) {
                vec3 p3  = fract(vec3(p.xyx) * .1031);
                p3 += dot(p3, p3.yzx + 33.33);
                return fract((p3.x + p3.y) * p3.z);
            }

            // 2D Noise
            float noise(vec2 p) {
                vec2 i = floor(p);
                vec2 f = fract(p);
                vec2 u = f*f*(3.0-2.0*f);
                return mix( mix( hash12( i + vec2(0.0,0.0) ), 
                                 hash12( i + vec2(1.0,0.0) ), u.x),
                            mix( hash12( i + vec2(0.0,1.0) ), 
                                 hash12( i + vec2(1.0,1.0) ), u.x), u.y);
            }

            // FBM for organic structure
            float fbm(vec2 p) {
                float v = 0.0;
                float a = 0.5;
                vec2 shift = vec2(100.0);
                mat2 rot = mat2(cos(0.5), sin(0.5), -sin(0.5), cos(0.50));
                for (int i = 0; i < 5; ++i) {
                    v += a * noise(p);
                    p = rot * p * 2.0 + shift;
                    a *= 0.5;
                }
                return v;
            }

            // Custom palette for the aurora
            vec3 getAuroraColor(float t) {
                // Modulate palette by mid frequencies
                float offset = u_mid * 0.7;
                vec3 a = vec3(0.3, 0.5, 0.5);
                vec3 b = vec3(0.5, 0.5, 0.5);
                vec3 c = vec3(1.0, 1.0, 1.0);
                vec3 d = vec3(0.2, 0.40, 0.50) + vec3(0.0, offset * 0.5, offset);
                return a + b * cos(6.28318 * (c * t + d));
            }

            void main() {
                vec2 uv = v_uv;
                uv.x *= u_aspect;
                
                vec3 col = vec3(0.0);
                
                // --- Starfield ---
                // Use a dense grid for stars, but only draw the very brightest points
                float starVal = hash12(uv * 200.0);
                if (starVal > 0.993) {
                    float twinkle = sin(u_time * 3.0 + starVal * 100.0) * 0.5 + 0.5;
                    col += vec3(1.0) * twinkle * (0.5 + u_high * 1.5);
                } else if (starVal > 0.985) {
                    col += vec3(0.6) * (0.2 + u_high * 0.5);
                }

                // --- Aurora Curtains ---
                // Base coordinates modified by time and noise
                vec2 p = uv;
                p.x += u_time * 0.02; // slow horizontal drift
                
                float auroraHeight = 0.35 + u_bass * 0.5 + u_env * 0.4; 
                vec3 auroraCol = vec3(0.0);

                // Layered rendering of aurora ribbons
                for (float i = 0.0; i < 3.0; i++) {
                    vec2 q = p * (1.0 + i * 0.3);
                    q.y += u_time * 0.05;
                    
                    float n = fbm(q + vec2(u_time * 0.02 * (i+1.0), 0.0));
                    
                    // Vertical falloff based on height and noise
                    // The curtain arcs across the sky
                    float shape = smoothstep(0.0, auroraHeight, uv.y + n * 0.5 - 0.2);
                    shape *= smoothstep(auroraHeight + 0.4, auroraHeight - 0.2, uv.y + n * 0.5);
                    
                    // Add vertical streaks to simulate atmospheric collision
                    float streak = smoothstep(0.3, 0.7, noise(vec2(q.x * 8.0, q.y * 0.05)));
                    
                    float curIntensity = shape * (0.3 + streak * 0.5) * (1.0 - i * 0.25);
                    
                    // Modulate color based on layer and noise
                    vec3 layerCol = getAuroraColor(n + i * 0.15 + u_time * 0.02);
                    
                    auroraCol += layerCol * curIntensity;
                }
                
                // Boost intensity based on beat and bass, but keep it constrained to prevent wash-out
                auroraCol *= (1.0 + u_bass * 1.2 + u_env * 0.8);
                
                // Add aurora to background
                col += auroraCol;

                // Add a subtle deep blue gradient background at the bottom
                vec3 bgGlow = vec3(0.0, 0.05, 0.15) * (1.0 - uv.y) * (1.0 + u_bass * 0.5);
                col += bgGlow;

                fragColor = vec4(col * u_dim, 1.0);
            }
        """
    }

    private var program = 0
    private var uTime = 0
    private var uDim = 0
    private var uBass = 0
    private var uMid = 0
    private var uHigh = 0
    private var uEnv = 0
    private var uAspect = 0

    private var smoothedBass = 0f
    private var smoothedMid = 0f
    private var smoothedHigh = 0f
    private var smoothedEnv = 0f
    private var aspect = 1f

    override fun onCreated() {
        program = ShaderUtil.buildProgram(VS, FS)
        uTime = GLES20.glGetUniformLocation(program, "u_time")
        uDim = GLES20.glGetUniformLocation(program, "u_dim")
        uBass = GLES20.glGetUniformLocation(program, "u_bass")
        uMid = GLES20.glGetUniformLocation(program, "u_mid")
        uHigh = GLES20.glGetUniformLocation(program, "u_high")
        uEnv = GLES20.glGetUniformLocation(program, "u_env")
        uAspect = GLES20.glGetUniformLocation(program, "u_aspect")
    }

    override fun onResize(width: Int, height: Int, aspect: Float) {
        this.aspect = aspect
    }

    override fun draw(pcm: FloatArray, bands: FloatArray, timeSec: Float, dim: Float) {
        if (program == 0) return

        val low = bands[0]
        val mid = bands[1]
        val high = bands[2]
        
        // Exponential smoothing to make the aurora movement fluid, not jittery
        smoothedBass += (low - smoothedBass) * 0.04f
        smoothedMid += (mid - smoothedMid) * 0.04f
        smoothedHigh += (high - smoothedHigh) * 0.04f
        
        val env = BeatPulse.envelope
        // Smooth the envelope aggressively because auroras shouldn't snap abruptly
        smoothedEnv += (env - smoothedEnv) * 0.03f

        // Single full-screen pass (opaque, overwrites buffer)
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glUseProgram(program)
        
        GLES20.glUniform1f(uTime, timeSec)
        GLES20.glUniform1f(uDim, dim)
        GLES20.glUniform1f(uBass, smoothedBass)
        GLES20.glUniform1f(uMid, smoothedMid)
        GLES20.glUniform1f(uHigh, smoothedHigh)
        GLES20.glUniform1f(uEnv, smoothedEnv)
        GLES20.glUniform1f(uAspect, aspect)
        
        // Draw full screen quad (3 vertices covers the screen when configured in VS)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 3)
    }

    override val respondsToBeat: Boolean get() = true
}
