package com.lowlatency.visualizer.gl

import android.opengl.GLES20
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * "Deep Geometric Tunnel" — an infinite hexagonal corridor rendered entirely in
 * a fragment shader over a fullscreen quad.
 *
 * Band reactivity:
 *   - Lows -> instantly pulse/expand the tunnel bore radius
 *   - Mids -> smoothly shift the color spectrum of the walls
 *   - Highs-> sparkle / glow boost on the ribs
 *
 * Foldable correctness: u_aspectRatio (width/height) is supplied from
 * onSurfaceChanged so the hexagon stays regular — never squashed — whether the
 * surface is a tall phone or a near-square unfolded panel.
 */
class TunnelScene : GlScene {

    companion object {
        private const val VERTEX_SHADER = """
            attribute vec2 aPos;
            uniform float u_time;
            varying vec2  v_orbit;          // burn-in orbit offset, in pixels
            void main() {
                // OLED burn-in protection: slow non-repeating orbit from
                // incommensurate sine/cosine frequencies (~up to +/-2.25px). The
                // tunnel samples gl_FragCoord at this offset so the whole corridor
                // drifts a few pixels and never sits on fixed phosphors.
                vec2 orbit = vec2(
                    sin(u_time * 0.31) + 0.5 * sin(u_time * 0.13 + 1.7),
                    cos(u_time * 0.27) + 0.5 * cos(u_time * 0.19 + 0.9)
                );
                v_orbit = orbit * 1.5;
                gl_Position = vec4(aPos, 0.0, 1.0);
            }
        """

        private const val FRAGMENT_SHADER = """
            #extension GL_OES_standard_derivatives : enable
            precision highp float;
            uniform vec2  u_resolution;
            uniform float u_aspectRatio;   // width / height (foldable-safe)
            uniform float u_time;
            uniform float u_low;           // bass  -> wave-propagated expansion
            uniform float u_mid;           // mids  -> color shift
            uniform float u_high;          // highs -> ring strobe accents
            uniform float u_dim;           // transition fade
            varying vec2  v_orbit;         // burn-in orbit (pixels)

            // Regular-hexagon distance field (keeps 6-fold symmetry exact).
            float hexDist(vec2 p) {
                p = abs(p);
                return max(dot(p, vec2(0.866025, 0.5)), p.y);
            }

            vec3 palette(float t) {
                return 0.5 + 0.5 * cos(6.28318 * (t + vec3(0.0, 0.33, 0.67)));
            }

            // Analytical anti-aliased grid line at every integer of `c`.
            // Derivative-based width keeps edges crisp at 120Hz and gracefully
            // dissolves lines as they converge toward the vanishing point.
            float aaLine(float c) {
                float f = fract(c);
                float d = min(f, 1.0 - f);          // distance to nearest line
                float w = fwidth(c) * 1.5;          // one-pixel falloff
                return 1.0 - smoothstep(0.0, w, d);
            }

            void main() {
                // Centered coords (sampled at the burn-in orbit offset); correct
                // X by aspect so the hexagon is regular on every screen profile.
                vec2 uv = (gl_FragCoord.xy + v_orbit) / u_resolution - 0.5;
                uv.x *= u_aspectRatio;

                float hd = hexDist(uv);
                float ang = atan(uv.y, uv.x);

                // Base depth coordinate down the corridor: hd -> 0 is infinitely
                // far (screen center), hd large is right at the viewport.
                float bore = 0.16 + u_low * 0.10;
                float z0 = u_time * 0.5 + bore / max(hd, 1e-3);

                // (3) Reactive expansion *skew*: a bass-driven ripple that starts
                // at the viewpoint and travels down Z over time, rather than a
                // uniform scale. The phase advances with z and recedes with time
                // so crests propagate into the depth of the corridor.
                float ripple = u_low * 0.6 * sin(z0 * 3.14159 - u_time * 4.0);
                float z = z0 + ripple;

                // (2) Anti-aliased wireframe: concentric rings + 6 hex ribs.
                float rings = aaLine(z);
                float ribs  = aaLine(ang / 6.28318 * 6.0);
                float wire  = max(rings, ribs * 0.8);

                // Mids smoothly slide the wall color spectrum.
                vec3 col = palette(z * 0.06 + u_mid * 1.5) * wire;

                // (1) Atmospheric fog: walls fade to ABSOLUTE darkness as they
                // recede toward the center (infinite distance), giving true scale.
                float fog = 1.0 - exp(-hd * 3.5);   // 0 at center -> 1 at edges
                col *= fog;

                // Grid breathing: an invisible ~60s LFO undulates the static
                // wireframe brightness so fixed corridor lines never burn in.
                // (The reactive strobe below is intentionally excluded.)
                float breathe = 0.85 + 0.15 * sin(u_time * 0.10472);  // 2*pi/60
                col *= breathe;

                // (4) High-frequency strobe accents: sharp, snappy neon highlights
                // on the structural rings for hats/percussion. Driven into HDR
                // (~5x) so the strobes read as true peak-nit flashes on the FP16
                // framebuffer rather than clipped white.
                float strobe = pow(u_high, 2.0);
                col += rings * strobe * vec3(0.55, 0.85, 1.0) * fog * 5.0;

                // Bass ripple crests overdrive the walls past 1.0 as the wave
                // travels down the corridor.
                col *= 1.0 + u_low * 2.0 * max(ripple, 0.0);

                // No SDR tone-mapping (no Reinhard/ACES): hand the unbounded HDR
                // values straight to the compositor for native peak-nit mapping.
                gl_FragColor = vec4(col * u_dim, 1.0);
            }
        """
    }

    private val quad: FloatBuffer = ByteBuffer
        .allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        .apply {
            put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)); position(0)
        }

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
        // Opaque fullscreen pass — no blending needed.
        GLES20.glDisable(GLES20.GL_BLEND)
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
