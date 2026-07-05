package com.lowlatency.visualizer.gl

import android.opengl.GLES20
import android.util.Log

class TechnoClubScene : GlScene {
    private var program = 0
    private var uResolution = 0
    private var uTime = 0
    private var uDim = 0
    private var uBass = 0
    private var uMid = 0
    private var uHigh = 0
    private var uBeat = 0

    private var w = 1f
    private var h = 1f
    
    private var smoothedBass = 0f
    private var smoothedMid = 0f
    private var smoothedHigh = 0f

    override fun onCreated() {
        try {
            program = ShaderUtil.buildProgram(VS, FS)
            uResolution = GLES20.glGetUniformLocation(program, "u_resolution")
            uTime = GLES20.glGetUniformLocation(program, "u_time")
            uDim = GLES20.glGetUniformLocation(program, "u_dim")
            uBass = GLES20.glGetUniformLocation(program, "u_bass")
            uMid = GLES20.glGetUniformLocation(program, "u_mid")
            uHigh = GLES20.glGetUniformLocation(program, "u_high")
            uBeat = GLES20.glGetUniformLocation(program, "u_beat")
        } catch (e: RuntimeException) {
            Log.e("TechnoClubScene", "Failed to build shader", e)
        }
    }

    override fun onResize(width: Int, height: Int, aspect: Float) {
        w = width.toFloat()
        h = height.toFloat()
    }

    override fun draw(pcm: FloatArray, bands: FloatArray, timeSec: Float, dim: Float) {
        if (program == 0) return

        smoothedBass += (bands[0] - smoothedBass) * 0.2f
        smoothedMid += (bands[1] - smoothedMid) * 0.2f
        smoothedHigh += (bands[2] - smoothedHigh) * 0.2f

        GLES20.glUseProgram(program)
        GLES20.glUniform2f(uResolution, w, h)
        GLES20.glUniform1f(uTime, timeSec)
        GLES20.glUniform1f(uDim, dim)
        GLES20.glUniform1f(uBass, smoothedBass)
        GLES20.glUniform1f(uMid, smoothedMid)
        GLES20.glUniform1f(uHigh, smoothedHigh)
        
        // Pass the beat envelope (0 to 1 decay)
        GLES20.glUniform1f(uBeat, com.lowlatency.visualizer.BeatPulse.envelope)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 3)
    }

    companion object {
        // Vertex shader using a full-screen triangle without VBOs
        private const val VS = """#version 300 es
            void main() {
                vec2 p = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));
                gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
            }
        """

        private const val FS = """#version 300 es
            precision highp float;
            uniform vec2 u_resolution;
            uniform float u_time;
            uniform float u_dim;
            uniform float u_bass;
            uniform float u_mid;
            uniform float u_high;
            uniform float u_beat;
            out vec4 fragColor;

            mat2 rot(float a) {
                float s = sin(a), c = cos(a);
                return mat2(c, -s, s, c);
            }

            float sdHexagon(in vec2 p, in float r) {
                const vec3 k = vec3(-0.866025404, 0.5, 0.577350269);
                p = abs(p);
                p -= 2.0 * min(dot(k.xy, p), 0.0) * k.xy;
                p -= vec2(clamp(p.x, -k.z * r, k.z * r), r);
                return length(p) * sign(p.y);
            }

            void main() {
                vec2 uv = (gl_FragCoord.xy - 0.5 * u_resolution.xy) / u_resolution.y;
                
                // Slow rotation + wobble on beat
                uv *= rot(sin(u_time * 0.15) * 0.2 + u_beat * 0.05);
                
                vec3 col = vec3(0.0);
                
                float r = length(uv);
                float a = atan(uv.y, uv.x);
                
                // Polar to Cartesian for infinite tunnel
                vec2 st = vec2(0.3 / r + u_time * 1.5 + u_bass, a / 3.14159);
                
                // Grid in polar coordinates
                vec2 grid = fract(st * vec2(4.0, 6.0)) - 0.5;
                float lineW = 0.05 + u_bass * 0.05;
                float lines = smoothstep(lineW, 0.0, abs(grid.x)) + smoothstep(lineW, 0.0, abs(grid.y));
                
                // Depth fade for tunnel
                float fade = smoothstep(0.0, 0.8, r);
                
                // Dimmer, monochromatic tunnel
                col += lines * fade * vec3(0.4) * (1.0 + u_bass * 1.5);
                
                // Overlay sharp geometric shapes in the center
                vec2 shapeUv = uv;
                for(int i = 0; i < 4; i++) {
                    float fi = float(i);
                    shapeUv *= rot(u_time * 0.3 + fi * 1.2 + u_beat * 0.15);
                    shapeUv = abs(shapeUv) - (0.05 + u_bass * 0.15 + fi * 0.12);
                    
                    float d = sdHexagon(shapeUv, 0.1 + u_mid * 0.15);
                    
                    // Strobing lines
                    float stroke = smoothstep(0.01 + u_high * 0.02 + u_beat * 0.015, 0.0, abs(d));
                    
                    // Pulse brightness with beat and add slight offset for glitch
                    col += stroke * (1.0 + u_beat * 3.0) * vec3(1.0);
                }
                
                // Glitch horizontal tear on hard beats
                if (u_beat > 0.7 && fract(uv.y * 10.0 + u_time * 50.0) < 0.1) {
                    col.r += 0.5;
                    col.b += 0.5;
                }

                // Extreme strobe on drop or high bass
                float strobeFast = step(0.5, fract(u_time * 30.0));
                col += u_beat * u_bass * strobeFast * 0.3;
                
                // Edge vignette
                col *= 1.0 - length(uv) * 0.7;
                
                col *= u_dim;
                fragColor = vec4(col, 1.0);
            }
        """
    }
}
