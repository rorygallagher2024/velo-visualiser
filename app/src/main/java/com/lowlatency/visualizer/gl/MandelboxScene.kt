package com.lowlatency.visualizer.gl

import android.opengl.GLES20
import com.lowlatency.visualizer.BeatPulse
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * "Fractal Cathedral" — a ray-marched Mandelbox. An infinitely recursive 3D
 * structure (box-fold + sphere-fold IFS) that reads as vast alien architecture:
 * mesh-like geometry and self-similar fractal detail in one. Real directional
 * lighting + ambient occlusion give it depth; orbit-trap coloring drives a rich
 * drifting palette; volumetric proximity glow blooms the highlights on the HDR
 * pipeline.
 *
 * Audio coupling is deliberately musical, not frantic:
 *   - bass slowly breathes the fold scale (the whole structure morphs/unfolds),
 *   - the beat envelope pulses surface emission and nudges the camera dolly,
 *   - treble adds a fine sparkle to the emissive rim.
 *
 * All audio params are smoothed on the CPU side so motion stays graceful.
 */
class MandelboxScene : GlScene {

    companion object {
        private const val VERT = """#version 300 es
layout(location = 0) in vec2 aPos;
void main() { gl_Position = vec4(aPos, 0.0, 1.0); }
"""

        // ITER  — fold iterations (fractal detail vs. cost)
        // MARCH — ray-march steps (silhouette accuracy vs. cost)
        private const val FRAG = """#version 300 es
precision highp float;

uniform vec2  u_resolution;
uniform float u_time;
uniform float u_dim;
uniform float u_env;        // beat envelope 0..1 (smooth decay)
uniform float u_scale;      // mandelbox fold scale magnitude (bass-breathed)
uniform float u_emit;       // surface emission amount (beat + energy)
uniform float u_spark;      // treble sparkle 0..1
uniform float u_hue;        // slow palette drift

out vec4 fragColor;

#define ITER  10
#define MARCH 84
#define TAU 6.2831853

const float MINR2   = 0.25;   // sphere-fold inner radius^2 (0.5^2)
const float FIXEDR2 = 1.0;    // sphere-fold outer radius^2

// Cosine palette (Inigo Quilez form) — warm gold → magenta → teal as x sweeps.
vec3 palette(float x) {
    return 0.5 + 0.5 * cos(TAU * (vec3(1.0, 1.0, 1.0) * x
                + vec3(0.00, 0.33, 0.55) + u_hue));
}

// Mandelbox distance estimator. `trap` returns the minimum orbit radius hit
// during iteration — used for coloring. `scale` is negative (classic look).
float boxDE(vec3 p, float scale, out float trap) {
    vec3 offset = p;
    float dr = 1.0;
    trap = 1e9;
    for (int i = 0; i < ITER; i++) {
        // Box fold: reflect components outside [-1,1].
        p = clamp(p, -1.0, 1.0) * 2.0 - p;
        // Sphere fold.
        float r2 = max(dot(p, p), 1e-6);
        if (r2 < MINR2) {
            float t = FIXEDR2 / MINR2; p *= t; dr *= t;
        } else if (r2 < FIXEDR2) {
            float t = FIXEDR2 / r2;    p *= t; dr *= t;
        }
        p = p * scale + offset;
        dr = dr * abs(scale) + 1.0;
        trap = min(trap, length(p));
    }
    return length(p) / abs(dr);
}

// Cheap DE wrapper when the trap isn't needed (normals / AO).
float mapD(vec3 p, float scale) {
    float t;
    return boxDE(p, scale, t);
}

vec3 calcNormal(vec3 p, float scale) {
    vec2 e = vec2(0.0009, 0.0);
    return normalize(vec3(
        mapD(p + e.xyy, scale) - mapD(p - e.xyy, scale),
        mapD(p + e.yxy, scale) - mapD(p - e.yxy, scale),
        mapD(p + e.yyx, scale) - mapD(p - e.yyx, scale)));
}

float calcAO(vec3 p, vec3 n, float scale) {
    float occ = 0.0;
    float sca = 1.0;
    for (int i = 0; i < 5; i++) {
        float h = 0.012 + 0.14 * float(i) / 4.0;
        float d = mapD(p + n * h, scale);
        occ += (h - d) * sca;
        sca *= 0.65;
    }
    return clamp(1.0 - 2.2 * occ, 0.0, 1.0);
}

mat2 rot(float a) { float c = cos(a), s = sin(a); return mat2(c, -s, s, c); }

void main() {
    vec2 uv = (gl_FragCoord.xy - 0.5 * u_resolution) / u_resolution.y;

    float scale = -u_scale;   // negative scale → the classic mandelbox

    // Camera: slow orbit + gentle dolly that the beat nudges inward.
    float ct = u_time * 0.07;
    float radius = 5.6 - u_env * 0.35;
    vec3 ro = vec3(sin(ct) * radius, 1.4 * sin(u_time * 0.045), cos(ct) * radius);
    vec3 ta = vec3(0.0, 0.15 * sin(u_time * 0.06), 0.0);

    // Camera basis.
    vec3 fwd = normalize(ta - ro);
    vec3 rgt = normalize(cross(vec3(0.0, 1.0, 0.0), fwd));
    vec3 up  = cross(fwd, rgt);
    // Slow bank roll for cinematic motion.
    float roll = sin(u_time * 0.05) * 0.25;
    vec2 ruv = uv * rot(roll);
    vec3 rd = normalize(ruv.x * rgt + ruv.y * up + 1.5 * fwd);

    // March, accumulating volumetric glow from orbit-trap proximity.
    float t = 0.06;
    float trap = 1e9;
    float glow = 0.0;
    bool  hit = false;
    for (int i = 0; i < MARCH; i++) {
        vec3 pos = ro + rd * t;
        float tr;
        float d = boxDE(pos, scale, tr);
        // Nebular glow: brighter where the ray skims fine structure.
        glow += exp(-tr * 2.3) / (1.0 + t * t * 0.35);
        if (d < 0.00085 * t) { hit = true; trap = tr; break; }
        t += d * 0.9;
        if (t > 16.0) break;
    }
    glow *= 0.06;

    vec3 col = vec3(0.0);

    if (hit) {
        vec3 pos = ro + rd * t;
        vec3 n = calcNormal(pos, scale);
        float ao = calcAO(pos, n, scale);

        // Key + fill + rim.
        vec3 key = normalize(vec3(0.6, 0.75, 0.45));
        float dif = clamp(dot(n, key), 0.0, 1.0);
        float fill = clamp(0.5 + 0.5 * dot(n, vec3(-0.4, 0.2, -0.5)), 0.0, 1.0);
        float fre = pow(1.0 - clamp(dot(n, -rd), 0.0, 1.0), 3.0);

        vec3 base = palette(trap * 0.55 + 0.15);
        vec3 cool = palette(trap * 0.55 + 0.45);

        col  = base * dif * 1.15;
        col += cool * fill * 0.25;
        col *= ao;

        // Emissive rim — pulses with the beat, sparkles with treble.
        float emit = u_emit + u_spark * fre * 1.5;
        col += base * fre * (0.6 + emit * 3.0);

        // Subtle crevice darkening from trap (deep folds read darker).
        col *= 0.55 + 0.45 * smoothstep(0.0, 0.6, trap);

        // Atmospheric depth fog toward a deep indigo.
        vec3 fog = vec3(0.02, 0.02, 0.05);
        col = mix(col, fog, 1.0 - exp(-t * 0.085));
    }

    // Volumetric glow (always added — keeps misses luminous, never empty/broken).
    vec3 glowCol = palette(0.6 + 0.2 * sin(u_time * 0.1));
    col += glowCol * glow * (1.2 + u_env * 2.5);

    // Faint vignette to seat the structure.
    col *= 1.0 - 0.25 * dot(uv, uv);

    fragColor = vec4(max(col, 0.0) * u_dim, 1.0);
}
"""
    }

    private val quad: FloatBuffer = ByteBuffer
        .allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        .apply { put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)); position(0) }

    private var program = 0
    private var aPos = 0
    private var uResolution = 0
    private var uTime = 0
    private var uDim = 0
    private var uEnv = 0
    private var uScale = 0
    private var uEmit = 0
    private var uSpark = 0
    private var uHue = 0

    private var w = 1f
    private var h = 1f

    // Smoothed audio-reactive state (kept off the raw per-frame band values so
    // the geometry breathes gracefully).
    private var scale = 2.0f
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
    }

    override fun onResize(width: Int, height: Int, aspect: Float) {
        w = width.toFloat(); h = height.toFloat()
    }

    override fun draw(pcm: FloatArray, bands: FloatArray, timeSec: Float, dim: Float) {
        val low = bands[0]
        val mid = bands[1]
        val high = bands[2]
        val env = BeatPulse.envelope

        // Bass breathes the fold scale around 2.0 (range ~1.88..2.10). Small
        // window: the mandelbox DE is sensitive, so we keep the morph subtle and
        // heavily smoothed to avoid march artifacts and visual jitter.
        val targetScale = 1.9f + 0.18f * low
        scale += (targetScale - scale) * 0.04f

        // Emission: a baseline driven by overall energy, punched by the beat.
        val targetEmit = 0.15f * mid + 0.6f * env
        emit += (targetEmit - emit) * 0.2f

        // Treble sparkle, lightly smoothed.
        spark += (high - spark) * 0.25f

        // Continuous slow hue drift, gently advanced by treble energy.
        hue += (0.012f + high * 0.04f) * 0.016f

        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glUseProgram(program)
        GLES20.glUniform2f(uResolution, w, h)
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
}
