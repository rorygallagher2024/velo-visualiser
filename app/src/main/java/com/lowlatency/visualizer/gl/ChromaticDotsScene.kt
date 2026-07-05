package com.lowlatency.visualizer.gl

import android.opengl.GLES20
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.random.Random

/**
 * A scene that shows distinct colourful dots for different elements of the music:
 * Bass, Mid, Treble, Loudness, and Beat.
 * Dots disappear when there isn't enough energy in their respective element.
 */
class ChromaticDotsScene : GlScene {
    private var program = 0
    private var aTarget = 0
    private var aSeed = 0
    private var aSize = 0
    private var aType = 0
    
    private var uAspect = 0
    private var uTime = 0
    private var uDim = 0
    private var uSizeScale = 0
    private var uBass = 0
    private var uMid = 0
    private var uHigh = 0
    private var uLoudness = 0
    private var uBeat = 0

    private var vbo = 0
    private var particleCount = 0
    private var aspect = 1f
    private var sizeScale = 1f

    private var smoothedBass = 0f
    private var smoothedMid = 0f
    private var smoothedHigh = 0f
    private var smoothedLoudness = 0f

    override fun onCreated() {
        program = ShaderUtil.buildProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        aTarget = GLES20.glGetAttribLocation(program, "aTarget")
        aSeed = GLES20.glGetAttribLocation(program, "aSeed")
        aSize = GLES20.glGetAttribLocation(program, "aSize")
        aType = GLES20.glGetAttribLocation(program, "aType")
        
        uAspect = GLES20.glGetUniformLocation(program, "u_aspect")
        uTime = GLES20.glGetUniformLocation(program, "u_time")
        uDim = GLES20.glGetUniformLocation(program, "u_dim")
        uSizeScale = GLES20.glGetUniformLocation(program, "u_sizeScale")
        
        uBass = GLES20.glGetUniformLocation(program, "u_bass")
        uMid = GLES20.glGetUniformLocation(program, "u_mid")
        uHigh = GLES20.glGetUniformLocation(program, "u_high")
        uLoudness = GLES20.glGetUniformLocation(program, "u_loudness")
        uBeat = GLES20.glGetUniformLocation(program, "u_beat")

        val data = buildParticles()
        particleCount = data.size / FLOATS_PER_PARTICLE

        val buf = ByteBuffer.allocateDirect(data.size * 4).order(ByteOrder.nativeOrder())
            .asFloatBuffer().apply { put(data); position(0) }
        val ids = IntArray(1)
        GLES20.glGenBuffers(1, ids, 0)
        vbo = ids[0]
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo)
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, data.size * 4, buf, GLES20.GL_STATIC_DRAW)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
    }

    override fun onResize(width: Int, height: Int, aspect: Float) {
        this.aspect = aspect
        sizeScale = (height / 1080f).coerceIn(0.6f, 3f)
    }

    override fun draw(pcm: FloatArray, bands: FloatArray, timeSec: Float, dim: Float) {
        if (program == 0 || particleCount == 0) return

        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE)

        GLES20.glUseProgram(program)
        GLES20.glUniform1f(uAspect, aspect)
        GLES20.glUniform1f(uTime, timeSec)
        GLES20.glUniform1f(uDim, dim)
        GLES20.glUniform1f(uSizeScale, sizeScale)

        val beat = com.lowlatency.visualizer.BeatPulse.envelope
        val loudness = com.lowlatency.visualizer.BeatBus.loudness

        val bass = maxOf(0f, bands[0] - 0.15f) * 1.15f
        val mid = maxOf(0f, bands[1] - 0.15f) * 1.15f
        val high = maxOf(0f, bands[2] - 0.15f) * 1.15f

        // Exponential smoothing for non-beat elements to prevent twitching
        smoothedBass += (bass - smoothedBass) * 0.15f
        smoothedMid += (mid - smoothedMid) * 0.15f
        smoothedHigh += (high - smoothedHigh) * 0.15f
        smoothedLoudness += (loudness - smoothedLoudness) * 0.15f

        GLES20.glUniform1f(uBass, smoothedBass)
        GLES20.glUniform1f(uMid, smoothedMid)
        GLES20.glUniform1f(uHigh, smoothedHigh)
        GLES20.glUniform1f(uLoudness, smoothedLoudness)
        GLES20.glUniform1f(uBeat, beat)

        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo)
        val stride = FLOATS_PER_PARTICLE * 4
        
        GLES20.glEnableVertexAttribArray(aTarget)
        GLES20.glVertexAttribPointer(aTarget, 2, GLES20.GL_FLOAT, false, stride, 0)
        
        GLES20.glEnableVertexAttribArray(aSeed)
        GLES20.glVertexAttribPointer(aSeed, 1, GLES20.GL_FLOAT, false, stride, 2 * 4)
        
        GLES20.glEnableVertexAttribArray(aSize)
        GLES20.glVertexAttribPointer(aSize, 1, GLES20.GL_FLOAT, false, stride, 3 * 4)
        
        GLES20.glEnableVertexAttribArray(aType)
        GLES20.glVertexAttribPointer(aType, 1, GLES20.GL_FLOAT, false, stride, 4 * 4)

        GLES20.glDrawArrays(GLES20.GL_POINTS, 0, particleCount)

        GLES20.glDisableVertexAttribArray(aTarget)
        GLES20.glDisableVertexAttribArray(aSeed)
        GLES20.glDisableVertexAttribArray(aSize)
        GLES20.glDisableVertexAttribArray(aType)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)

        // Restore baseline state
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
    }

    private fun buildParticles(): FloatArray {
        val count = MAX_PARTICLES
        val rnd = Random(0x42C0FFEE.toInt())
        val out = FloatArray(count * FLOATS_PER_PARTICLE)
        
        for (i in 0 until count) {
            val idx = i * FLOATS_PER_PARTICLE
            // Target uniformly inside [-1, 1] bounds, favoring central clustering for better visual impact
            var x = rnd.nextFloat() * 2f - 1f
            var y = rnd.nextFloat() * 2f - 1f
            x *= rnd.nextFloat() // bring closer to center
            y *= rnd.nextFloat()
            
            out[idx + 0] = x
            out[idx + 1] = y
            out[idx + 2] = rnd.nextFloat() * 1000f   // seed
            out[idx + 3] = 2f + rnd.nextFloat() * 5f // base size
            out[idx + 4] = (i % 5).toFloat()         // type 0..4
        }
        return out
    }

    companion object {
        private const val MAX_PARTICLES = 15000
        private const val FLOATS_PER_PARTICLE = 5

        private val VERTEX_SHADER = """#version 300 es
            layout(location = 0) in vec2 aTarget;
            layout(location = 1) in float aSeed;
            layout(location = 2) in float aSize;
            layout(location = 3) in float aType;
            
            uniform float u_aspect;
            uniform float u_time;
            uniform float u_sizeScale;
            uniform float u_bass;
            uniform float u_mid;
            uniform float u_high;
            uniform float u_loudness;
            uniform float u_beat;
            
            out float v_type;
            out float v_energy;

            float hash(vec2 p) { return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453); }
            vec2 hash2(float n) { return fract(sin(vec2(n, n + 1.0)) * vec2(43758.5453, 22578.1459)); }

            float vnoise(vec2 p) {
                vec2 i = floor(p), f = fract(p);
                float a = hash(i), b = hash(i + vec2(1.0, 0.0));
                float c = hash(i + vec2(0.0, 1.0)), d = hash(i + vec2(1.0, 1.0));
                vec2 u = f * f * (3.0 - 2.0 * f);
                return mix(mix(a, b, u.x), mix(c, d, u.x), u.y);
            }
            vec2 curl(vec2 p) {
                float e = 0.1;
                float nx1 = vnoise(p + vec2(0.0, e)), nx2 = vnoise(p - vec2(0.0, e));
                float ny1 = vnoise(p + vec2(e, 0.0)), ny2 = vnoise(p - vec2(e, 0.0));
                return vec2(nx1 - nx2, -(ny1 - ny2)) / (2.0 * e);
            }

            void main() {
                v_type = aType;
                
                float energy = 0.0;
                float speed = 1.0;
                if (aType < 0.5) {
                    energy = u_bass;
                    speed = 0.5;
                } else if (aType < 1.5) {
                    energy = u_mid;
                    speed = 1.0;
                } else if (aType < 2.5) {
                    energy = u_high;
                    speed = 2.0;
                } else if (aType < 3.5) {
                    energy = u_loudness;
                    speed = 0.8;
                } else {
                    energy = u_beat;
                    speed = 3.0;
                }
                
                v_energy = energy;

                // Completely disappear when there isn't enough energy
                if (energy < 0.02) {
                    gl_PointSize = 0.0;
                    gl_Position = vec4(2.0, 2.0, 2.0, 1.0); // push offscreen just in case
                    return;
                }
                
                vec2 target = aTarget;
                vec2 r = hash2(aSeed * 0.097) * 2.0 - 1.0;

                // Organic breathing motion
                vec2 pos = target + curl(target * 3.0 + u_time * speed) * 0.08 * (1.0 + energy);

                // Specific reactions per type
                if (aType < 0.5) {
                    // Bass: explode outward
                    vec2 dir = normalize(target + vec2(1e-3));
                    pos += dir * energy * 0.4;
                } else if (aType > 3.5) {
                    // Beat: pull toward center sharply on hit
                    pos *= (1.0 - energy * 0.25);
                }

                pos.x /= u_aspect;
                gl_Position = vec4(pos, 0.0, 1.0);

                float sz = aSize * u_sizeScale * (0.5 + energy * 2.5);
                gl_PointSize = max(sz, 1.0);
            }
        """

        private val FRAGMENT_SHADER = """#version 300 es
            precision highp float;
            
            uniform float u_dim;
            
            in float v_type;
            in float v_energy;
            
            out vec4 fragColor;

            void main() {
                vec2 c = gl_PointCoord * 2.0 - 1.0;
                float d = dot(c, c);
                if (d > 1.0) discard;
                
                // Soft gradient point
                float core = exp(-d * 4.0);

                vec3 col = vec3(1.0);
                if (v_type < 0.5) {
                    col = vec3(1.0, 0.2, 0.0); // Bass = Orange/Red
                } else if (v_type < 1.5) {
                    col = vec3(0.2, 1.0, 0.3); // Mid = Green
                } else if (v_type < 2.5) {
                    col = vec3(0.0, 0.6, 1.0); // High = Blue
                } else if (v_type < 3.5) {
                    col = vec3(1.0, 0.0, 0.8); // Loudness = Magenta
                } else {
                    col = vec3(1.0, 1.0, 1.0); // Beat = White
                }
                
                // Become brighter when highly energetic
                col *= (1.0 + v_energy * 0.6);

                // Scale by global dimmer and the point's energy so it fades naturally
                col *= core * v_energy * 2.5 * u_dim;
                
                fragColor = vec4(col, 1.0);
            }
        """
    }
}
