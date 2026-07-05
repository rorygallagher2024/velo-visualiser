package com.lowlatency.visualizer.gl

import android.opengl.GLES20
import com.lowlatency.visualizer.BeatPulse
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

class MechanicalMeterScene : GlScene {

    // Still acts as a mechanical meter, but the internal light responds to the beat.
    override val respondsToBeat get() = true

    companion object {
        private const val VERTEX_SHADER = """#version 300 es
            layout(location = 0) in vec2 aPos;
            void main() { gl_Position = vec4(aPos, 0.0, 1.0); }
        """

        private const val FRAGMENT_SHADER = """#version 300 es
            precision highp float;
            uniform vec2  u_resolution;
            uniform float u_dim;
            uniform float u_needle;
            uniform float u_env;
            uniform float u_time;
            out vec4 fragColor;

            // Palette
            const vec3  FACE_COLOR  = vec3(0.85, 0.85, 0.82); // Warm Aluminum base
            const vec3  INK         = vec3(0.08, 0.08, 0.08); // Deep black print
            const vec3  ACCENT      = vec3(0.9, 0.15, 0.1);   // Bright red needle/hot zone
            const vec3  BULB_COLOR  = vec3(1.0, 0.65, 0.25);  // Warm incandescent

            const vec2  HUB   = vec2(0.0, -0.72);
            const float R_ARC = 1.22;
            const float SWEEP = 0.90;
            const float HOT   = 0.75;
            const vec2  WIN_C = vec2(0.0, -0.165);   
            const vec2  WIN_B = vec2(1.10, 0.76);    
            const float BEZEL = 0.045;               

            float sdSeg(vec2 p, vec2 a, vec2 b) {
                vec2 pa = p - a, ba = b - a;
                float h = clamp(dot(pa, ba) / dot(ba, ba), 0.0, 1.0);
                return length(pa - ba * h);
            }

            float sdRoundBox(vec2 p, vec2 b, float r) {
                vec2 q = abs(p) - b;
                return length(max(q, vec2(0.0))) + min(max(q.x, q.y), 0.0) - r;
            }

            // Hash for noise
            float hash(vec2 q) { return fract(sin(dot(q, vec2(127.1, 311.7))) * 43758.5453); }

            // Brushed metal noise function
            float brushedMetal(vec2 uv) {
                // Stretch the noise horizontally
                vec2 nuv = vec2(uv.x * 2.0, uv.y * 500.0);
                float n = hash(floor(nuv));
                n = mix(n, hash(floor(nuv + vec2(1.0, 0.0))), fract(nuv.x));
                return n * 0.15 + 0.85;
            }

            vec3 evalTick(vec2 uv, float px, int idx, vec3 col) {
                float t = float(idx) / 20.0;
                float ta = (t * 2.0 - 1.0) * SWEEP;
                bool major = (idx % 5 == 0);
                float il = major ? 0.10 : 0.045;
                float ol = major ? 0.035 : 0.012;
                float tw = major ? 0.004 : 0.002;
                vec2 dir = vec2(sin(ta), cos(ta));
                float sd = sdSeg(uv, HUB + dir * (R_ARC - il), HUB + dir * (R_ARC + ol));
                vec3 tc = t >= HOT ? ACCENT : INK;
                return mix(col, tc, smoothstep(tw + px, tw - px, sd));
            }

            void main() {
                vec2 uv = (gl_FragCoord.xy * 2.0 - u_resolution) / min(u_resolution.y, u_resolution.x);
                float fit = min(0.88, max(1.0, u_resolution.x / u_resolution.y) / 1.26);
                uv /= fit;
                float px = 2.0 / (min(u_resolution.y, u_resolution.x) * fit);

                float dWin = sdRoundBox(uv - WIN_C, WIN_B, 0.10);

                // ---- FACEPLATE: Brushed Aluminum ----
                vec3 col = FACE_COLOR;
                // Add horizontal brushed metal texture
                col *= brushedMetal(uv);
                // Add subtle circular milling near the hub
                float distHub = length(uv - HUB);
                col *= 1.0 - 0.04 * sin(distHub * 400.0);

                // ---- LIGHTING: Internal Incandescent Bulb ----
                // The bulb is tucked at the bottom, glowing upwards.
                // It flickers slightly when bass hits (simulating voltage drop)
                float flicker = 1.0 - u_env * 0.12 + sin(u_time * 60.0) * 0.015;
                float lightIntensity = smoothstep(2.4, 0.0, distHub) * flicker;
                vec3 ambientLight = vec3(0.15, 0.15, 0.2); // subtle cool ambient shadow
                vec3 spotLight = BULB_COLOR * lightIntensity * 1.6;
                vec3 illumination = ambientLight + spotLight;

                // ---- PRINTED GRAPHICS ----
                vec3 graphics = col;
                vec2 dv = uv - HUB;
                float ang = atan(dv.x, dv.y);
                float inArc = smoothstep(SWEEP + 0.03, SWEEP - 0.01, abs(ang));

                // Outer arc
                graphics = mix(graphics, INK, smoothstep(px * 1.5, 0.0, abs(distHub - R_ARC)) * inArc);
                
                // Hot zone overlay on outer arc
                float hotAng = (HOT * 2.0 - 1.0) * SWEEP;
                float inHot = step(hotAng, ang) * step(ang, SWEEP + 0.01);
                graphics = mix(graphics, ACCENT, smoothstep(px * 2.0, 0.0, abs(distHub - R_ARC) - 0.006) * inHot);

                // Tick marks
                float tickNorm = (ang / SWEEP + 1.0) * 0.5;
                int ti = clamp(int(floor(tickNorm * 20.0)), 0, 20);
                graphics = evalTick(uv, px, ti, graphics);
                if (ti < 20) graphics = evalTick(uv, px, ti + 1, graphics);

                // Apply lighting to the printed face
                col = graphics * illumination;

                // Edge vignette (deep shadow cast from the bezel onto the sunken dial)
                col *= 1.0 - 0.7 * smoothstep(-0.35, -BEZEL, dWin);

                // ---- NEEDLE (3D with Soft Shadow) ----
                float na = (u_needle * 2.0 - 1.0) * SWEEP;
                vec2 ndir = vec2(sin(na), cos(na));
                vec2 tip = HUB + ndir * (R_ARC + 0.07);
                vec2 tail = HUB - ndir * 0.14;

                // Soft shadow cast by the light coming from the bottom
                // The light pushes the shadow upwards and slightly outwards
                vec2 shadowOffset = vec2(ndir.x * 0.03, 0.06);
                float nds = sdSeg(uv - shadowOffset, tail, tip);
                // Shadow fades out and blurs towards the tip since it's higher off the face
                float shadowBlur = 0.02 + 0.05 * clamp((length(uv - HUB) - 0.3), 0.0, 1.0);
                col = mix(col, col * 0.15, smoothstep(shadowBlur, 0.0, nds) * 0.85);

                // Draw the actual needle
                float nd = sdSeg(uv, tail, tip);
                float along = dot(uv - tail, tip - tail) / dot(tip - tail, tip - tail);
                float taper = mix(0.008, 0.0015, clamp(along, 0.0, 1.0));
                
                // Needle base color
                vec3 needleCol = ACCENT * 0.85;
                // Needle specular highlight (simulate 3D metallic/painted cylinder)
                float needleSpec = smoothstep(taper, 0.0, nd) * smoothstep(0.0, taper*0.5, nd - taper*0.2);
                needleCol += vec3(0.6) * needleSpec;

                col = mix(col, needleCol * illumination, smoothstep(taper + px, taper - px, nd));

                // Counterweight & Hub Pivot
                float cwd = length(uv - (HUB - ndir * 0.10));
                col = mix(col, INK * illumination, smoothstep(0.020 + px, 0.020 - px, cwd));

                // Machined pivot bearing with metallic specular reflection
                float hd = length(uv - HUB);
                float hAng = atan(uv.x - HUB.x, uv.y - HUB.y);
                float lc = 0.5 + 0.5 * cos(hAng * 2.0 + u_time * 0.2); // metallic sheen
                vec3 metalColor = vec3(0.9) * lc * illumination;
                col = mix(col, metalColor, smoothstep(0.075 + px, 0.075 - px, hd));
                col = mix(col, INK * 0.2 * illumination, smoothstep(px, -px, abs(hd - 0.062) - 0.0022));
                col = mix(col, INK * 0.2 * illumination, smoothstep(px, -px, abs(hd - 0.043) - 0.0018));
                col = mix(col, INK * illumination, smoothstep(0.016 + px, 0.016 - px, hd));

                // ---- BEZEL & CASING ----
                // Dark brushed metal bezel
                vec3 bezelCol = vec3(0.12, 0.12, 0.14);
                bezelCol *= brushedMetal(uv.yx); // brushed vertically
                // Inner rim specular highlight (catches the room light)
                float rimLight = smoothstep(0.0, -BEZEL, dWin) * smoothstep(-BEZEL - 0.015, -BEZEL, dWin);
                bezelCol += vec3(0.4) * rimLight * (0.6 + 0.4 * uv.y);
                
                vec3 outCol = vec3(0.0);
                outCol = mix(outCol, bezelCol, smoothstep(px, -px, dWin));
                // Hairline gap
                outCol = mix(outCol, vec3(0.01), smoothstep(px * 1.5, 0.0, abs(dWin + BEZEL)));
                outCol = mix(outCol, col, smoothstep(px, -px, dWin + BEZEL));

                // ---- CURVED GLASS & GLARE ----
                // Add a dynamic curved glass reflection over the window
                float glassCurve = dot(uv - vec2(0.0, 0.2), normalize(vec2(0.4, 1.0)));
                float glassGlare = smoothstep(0.35, 0.0, abs(glassCurve)) * 0.15;
                glassGlare += smoothstep(0.1, 0.0, abs(glassCurve - 0.25)) * 0.3;
                
                // Add an ambient environment reflection to the glass
                float envRefl = max(0.0, 1.0 - length(uv * vec2(0.6, 1.0) - vec2(0.0, -0.1)));
                
                vec3 glassCol = vec3(0.8, 0.9, 1.0) * (glassGlare + envRefl * 0.15);
                
                // Blend glass additively over the face
                outCol += glassCol * smoothstep(px, -px, dWin);

                fragColor = vec4(outCol * u_dim, 1.0);
            }
        """
    }

    private var program = 0
    private var aPos = 0
    private var uResolution = 0
    private var uDim = 0
    private var uNeedle = 0
    private var uEnv = 0
    private var uTime = 0

    private val quad: FloatBuffer = ByteBuffer
        .allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        .apply { put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)); position(0) }

    private var width = 1f
    private var height = 1f

    private var needleLevel = 0f
    private var velocity = 0f
    private var lastTime = -1f
    private var smoothedEnv = 0f

    override fun onCreated() {
        program = ShaderUtil.buildProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        aPos = GLES20.glGetAttribLocation(program, "aPos")
        uResolution = GLES20.glGetUniformLocation(program, "u_resolution")
        uDim = GLES20.glGetUniformLocation(program, "u_dim")
        uNeedle = GLES20.glGetUniformLocation(program, "u_needle")
        uEnv = GLES20.glGetUniformLocation(program, "u_env")
        uTime = GLES20.glGetUniformLocation(program, "u_time")
    }

    override fun onResize(width: Int, height: Int, aspect: Float) {
        this.width = width.toFloat()
        this.height = height.toFloat()
    }

    override fun draw(pcm: FloatArray, bands: FloatArray, timeSec: Float, dim: Float) {
        val dt = if (lastTime < 0f) 0.016f else (timeSec - lastTime).coerceIn(0f, 0.1f)
        lastTime = timeSec

        var sumSq = 0f
        for (s in pcm) sumSq += s * s
        val rms = sqrt(sumSq / pcm.size)

        val gain = 175f
        val targetLevel = (rms * gain).let { if (it < 0.01f) 0f else sqrt(it) }.coerceIn(0f, 1.2f)
        val normalizedTarget = targetLevel / 1.2f

        // Spring-damped needle (more realistic physics)
        val stiffness = 400f
        val damping = 22f
        val force = (targetLevel - needleLevel) * stiffness
        velocity += (force - velocity * damping) * dt
        needleLevel += velocity * dt
        needleLevel = needleLevel.coerceIn(0f, 1.2f)
        val normalizedNeedle = needleLevel / 1.2f

        // Envelope for light flickering (simulates power sag when bass hits)
        val env = BeatPulse.envelope
        smoothedEnv += (env - smoothedEnv) * 12f * dt

        // Mechanical micro-detail: slight flutter
        val tremble = (sin(timeSec * 123f) + 0.6f * sin(timeSec * 287f)) * 0.0025f * normalizedTarget

        GLES20.glUseProgram(program)
        GLES20.glUniform2f(uResolution, width, height)
        GLES20.glUniform1f(uDim, dim)
        GLES20.glUniform1f(uNeedle, (normalizedNeedle + tremble).coerceIn(0f, 1f))
        GLES20.glUniform1f(uEnv, smoothedEnv)
        GLES20.glUniform1f(uTime, timeSec)

        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, quad)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(aPos)
    }
}
