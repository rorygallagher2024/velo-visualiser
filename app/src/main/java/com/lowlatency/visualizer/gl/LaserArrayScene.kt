package com.lowlatency.visualizer.gl

import android.opengl.GLES20
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Visual 3 — "Volumetric Laser Array".
 *
 * A full-screen raymarch through a dark 3D volume of radial light shafts that
 * emanate from a central vanishing point. Emission is accumulated along each
 * view ray for a genuine volumetric (god-ray) look.
 *
 * Audio:
 *   - Lows  -> beam width + opacity (violent strobe on kicks; pushed into HDR)
 *   - Mids/Highs -> rotation of the whole array (pans/scans the room on beat)
 */
class LaserArrayScene : GlScene {

    companion object {
        private const val VERTEX_SHADER = """#version 300 es
            layout(location = 0) in vec2 aPos;
            void main() { gl_Position = vec4(aPos, 0.0, 1.0); }
        """

        private const val FRAGMENT_SHADER = """#version 300 es
            precision highp float;
            uniform vec2  u_resolution;
            uniform float u_aspectRatio;
            uniform float u_time;
            uniform float u_low;
            uniform float u_mid;
            uniform float u_high;
            uniform float u_dim;
            out vec4 fragColor;

            const float BEAMS = 14.0;

            mat2 rot(float a) { float c = cos(a), s = sin(a); return mat2(c, -s, s, c); }
            vec3 palette(float t) {
                return 0.5 + 0.5 * cos(6.28318 * (t + vec3(0.0, 0.33, 0.67)));
            }

            void main() {
                // Aspect-corrected screen coords -> view ray into the corridor.
                vec2 uv = gl_FragCoord.xy / u_resolution - 0.5;
                uv.x *= u_aspectRatio;

                vec3 ro = vec3(0.0, 0.0, -2.0);
                vec3 rd = normalize(vec3(uv, 1.4));

                // Mids/Highs drive the pan/scan rotation of the array.
                float spin = u_time * 0.15 + u_mid * 2.5 + u_high * 3.0;
                mat2 R = rot(spin);

                float width = mix(0.05, 0.5, u_low);     // lows widen beams
                float opacity = 0.15 + u_low * 1.8;       // lows strobe (HDR, restrained)

                vec3 col = vec3(0.0);
                float t = 0.2;
                // Raymarch: accumulate beam emission through the volume.
                for (int i = 0; i < 40; i++) {
                    vec3 p = ro + rd * t;
                    vec2 q = R * p.xy;                    // rotate the beam field
                    float ang = atan(q.y, q.x);
                    float seg = ang / 6.28318 * BEAMS;
                    float d = abs(fract(seg) - 0.5) * 2.0;       // 0 at a beam centre
                    float beam = exp(-pow(d / max(width, 1e-3), 2.0));
                    float radial = exp(-length(q) * 0.6);        // shafts fade outward
                    float dens = beam * radial;
                    col += palette(ang * 0.15 + u_time * 0.05) * dens * opacity * 0.05;
                    t += 0.12;
                }

                // Central vanishing-point flare (highs brighten the core).
                float r0 = length(uv);
                col += vec3(0.6, 0.8, 1.0) * exp(-r0 * 6.0) * (0.4 + u_high * 3.0);

                // Unbounded HDR output (no tone-mapping) * transition fade.
                fragColor = vec4(col * u_dim, 1.0);
            }
        """
    }

    private val quad: FloatBuffer = ByteBuffer
        .allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        .apply { put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)); position(0) }

    private var program = 0
    private var aPos = 0
    private var uResolution = 0
    private var uAspect = 0
    private var uTime = 0
    private var uLow = 0
    private var uMid = 0
    private var uHigh = 0
    private var uDim = 0

    private var width = 1f
    private var height = 1f
    private var aspect = 1f

    override fun onCreated() {
        program = ShaderUtil.buildProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        aPos = GLES20.glGetAttribLocation(program, "aPos")
        uResolution = GLES20.glGetUniformLocation(program, "u_resolution")
        uAspect = GLES20.glGetUniformLocation(program, "u_aspectRatio")
        uTime = GLES20.glGetUniformLocation(program, "u_time")
        uLow = GLES20.glGetUniformLocation(program, "u_low")
        uMid = GLES20.glGetUniformLocation(program, "u_mid")
        uHigh = GLES20.glGetUniformLocation(program, "u_high")
        uDim = GLES20.glGetUniformLocation(program, "u_dim")
    }

    override fun onResize(width: Int, height: Int, aspect: Float) {
        this.width = width.toFloat()
        this.height = height.toFloat()
        this.aspect = aspect
    }

    override fun draw(pcm: FloatArray, bands: FloatArray, timeSec: Float, dim: Float) {
        GLES20.glDisable(GLES20.GL_BLEND)          // opaque full-screen pass
        GLES20.glUseProgram(program)
        GLES20.glUniform2f(uResolution, width, height)
        GLES20.glUniform1f(uAspect, aspect)
        GLES20.glUniform1f(uTime, timeSec)
        GLES20.glUniform1f(uLow, bands[0])
        GLES20.glUniform1f(uMid, bands[1])
        GLES20.glUniform1f(uHigh, bands[2])
        GLES20.glUniform1f(uDim, dim)

        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, quad)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(aPos)
    }
}
