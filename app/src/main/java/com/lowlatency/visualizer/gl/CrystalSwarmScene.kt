package com.lowlatency.visualizer.gl

import android.opengl.GLES20
import com.lowlatency.visualizer.BeatPulse
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.random.Random

/**
 * Flagship Scene: "Crystalline Swarm"
 * A dense 3D particle cloud that morphs between rigid mathematical order (a lattice grid)
 * and chaotic organic flow (curl noise fluid simulation) driven by bass.
 */
class CrystalSwarmScene : GlScene {

    private var program = 0
    private var aParams = 0 // vec4: x, y, z, seed
    private var uAspect = 0
    private var uTime = 0
    private var uDim = 0
    private var uSizeScale = 0
    private var uBass = 0
    private var uMid = 0
    private var uHigh = 0
    private var uEnv = 0
    private var uPhase = 0
    private var uMorph = 0

    private var vbo = 0
    private var particleCount = 0
    private var aspect = 1f
    private var sizeScale = 1f

    private var smoothedBass = 0f
    private var smoothedMid = 0f
    private var smoothedHigh = 0f
    
    // Slow morph tracker so the grid doesn't snap instantly
    private var currentMorph = 0f

    override fun onCreated() {
        program = ShaderUtil.buildProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        aParams = GLES20.glGetAttribLocation(program, "aParams")
        
        uAspect = GLES20.glGetUniformLocation(program, "u_aspect")
        uTime = GLES20.glGetUniformLocation(program, "u_time")
        uDim = GLES20.glGetUniformLocation(program, "u_dim")
        uSizeScale = GLES20.glGetUniformLocation(program, "u_sizeScale")
        uBass = GLES20.glGetUniformLocation(program, "u_bass")
        uMid = GLES20.glGetUniformLocation(program, "u_mid")
        uHigh = GLES20.glGetUniformLocation(program, "u_high")
        uEnv = GLES20.glGetUniformLocation(program, "u_env")
        uPhase = GLES20.glGetUniformLocation(program, "u_phase")
        uMorph = GLES20.glGetUniformLocation(program, "u_morph")

        val data = buildParticles()
        particleCount = data.size / 4

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
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE)   // Additive synesthesia glow

        GLES20.glUseProgram(program)
        
        // Audio processing
        val bass = maxOf(0f, bands[0] - 0.15f) * 1.15f
        val mid = maxOf(0f, bands[1] - 0.15f) * 1.15f
        val high = maxOf(0f, bands[2] - 0.15f) * 1.15f
        
        smoothedBass += (bass - smoothedBass) * 0.15f
        smoothedMid += (mid - smoothedMid) * 0.15f
        smoothedHigh += (high - smoothedHigh) * 0.15f

        // Beat/Link state
        val env = BeatPulse.envelope
        val phase = if (BeatPulse.linkActive) BeatPulse.barPhase else (timeSec * 0.1f) % 1f
        
        // Morph tracking: bass triggers the chaotic morph. 
        // We want it to surge fast and decay smoothly.
        val targetMorph = minOf(1.0f, smoothedBass * 2.0f)
        currentMorph += (targetMorph - currentMorph) * 0.08f

        GLES20.glUniform1f(uAspect, aspect)
        GLES20.glUniform1f(uTime, timeSec)
        GLES20.glUniform1f(uDim, dim)
        GLES20.glUniform1f(uSizeScale, sizeScale)
        GLES20.glUniform1f(uBass, smoothedBass)
        GLES20.glUniform1f(uMid, smoothedMid)
        GLES20.glUniform1f(uHigh, smoothedHigh)
        GLES20.glUniform1f(uEnv, env)
        GLES20.glUniform1f(uPhase, phase)
        GLES20.glUniform1f(uMorph, currentMorph)

        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo)
        GLES20.glEnableVertexAttribArray(aParams)
        GLES20.glVertexAttribPointer(aParams, 4, GLES20.GL_FLOAT, false, 16, 0)

        GLES20.glDrawArrays(GLES20.GL_POINTS, 0, particleCount)

        GLES20.glDisableVertexAttribArray(aParams)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)

        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
    }

    private fun buildParticles(): FloatArray {
        // Create a 3D grid/lattice
        val gridSize = 32 // 32x32x32 = 32,768 particles
        val out = ArrayList<Float>(gridSize * gridSize * gridSize * 4)
        val rnd = Random(0xCAFE)
        
        val step = 2.0f / (gridSize - 1)
        
        for (x in 0 until gridSize) {
            for (y in 0 until gridSize) {
                for (z in 0 until gridSize) {
                    val px = -1.0f + x * step
                    val py = -1.0f + y * step
                    val pz = -1.0f + z * step
                    val seed = rnd.nextFloat()
                    
                    // Slightly jitter the grid to make it feel organic even when ordered
                    val jitter = 0.01f
                    out.add(px + (rnd.nextFloat() * 2 - 1) * jitter)
                    out.add(py + (rnd.nextFloat() * 2 - 1) * jitter)
                    out.add(pz + (rnd.nextFloat() * 2 - 1) * jitter)
                    out.add(seed)
                }
            }
        }
        return out.toFloatArray()
    }

    companion object {
        private val VERTEX_SHADER = """#version 300 es
            layout(location = 0) in vec4 aParams; // x, y, z, seed
            
            uniform float u_aspect;
            uniform float u_time;
            uniform float u_sizeScale;
            uniform float u_bass;
            uniform float u_mid;
            uniform float u_high;
            uniform float u_env;
            uniform float u_phase;
            uniform float u_morph;
            
            out vec3 v_pos;
            out float v_energy;
            out float v_depth;
            out float v_beat;

            void main() {
                vec3 gridPos = aParams.xyz;
                float seed = aParams.w;

                // 1) Idle: Very slow, peaceful breathing and rotation.
                // We space out the grid so it feels like a vast space.
                vec3 pos = gridPos * 2.0; 

                // 2) Active (Beats/Bass): A gentle, majestic wave passes through the lattice
                // instead of chaotic, fast noise.
                float wave1 = sin(pos.x * 1.5 + u_time * 0.3) * cos(pos.z * 1.5 - u_time * 0.2);
                float wave2 = sin(pos.y * 2.0 - u_time * 0.4);
                
                // Bass makes the waves taller and more pronounced
                vec3 waveOffset = vec3(wave2, wave1, wave1 * wave2) * 0.3 * (1.0 + u_bass * 3.0);
                pos += waveOffset;
                
                // Mid/Highs cause individual points to gently drift outward from the center
                float dist = length(pos) + 0.01;
                pos += (pos / dist) * (u_mid + u_high) * 0.2 * seed;

                // 3D Camera Rotation (slowly spinning, peaceful space)
                float angleY = u_time * 0.05 + u_phase * 6.28318;
                float angleX = sin(u_time * 0.03) * 0.2 + 0.4; // Gentle downward tilt
                
                // Rot Y
                float cY = cos(angleY), sY = sin(angleY);
                float xRot = pos.x * cY - pos.z * sY;
                float zRot1 = pos.x * sY + pos.z * cY;
                pos.x = xRot;
                pos.z = zRot1;

                // Rot X
                float cX = cos(angleX), sX = sin(angleX);
                float yRot = pos.y * cX - pos.z * sX;
                float zRot = pos.y * sX + pos.z * cX;
                pos.y = yRot;
                pos.z = zRot;

                // Simple perspective projection
                float camZ = 5.0 - u_bass * 0.5; // Slight punch-in on bass
                float zDepth = camZ + pos.z;
                float proj = 1.0 / max(zDepth, 0.1);
                
                vec2 ndc = vec2(pos.x * proj, pos.y * proj) * 2.5;
                ndc.x /= u_aspect;

                gl_Position = vec4(ndc, 0.0, 1.0);

                // Point size: very small and crisp at idle, blooming large on beats
                float baseSize = mix(1.0, 2.5, seed);
                float sz = baseSize * u_sizeScale * proj;
                
                // Beat envelope expands points for a beautiful flash without noise
                sz *= 1.0 + u_env * 3.0 + u_high * 2.0;
                gl_PointSize = max(sz, 1.0);

                // Pass to fragment shader
                v_pos = gridPos; // pass ORIGINAL grid pos for stable spatial coloring
                
                // We separate energy (used for brightness) and beat (used for color shifts)
                v_energy = u_bass * 0.4 + u_mid * 0.3 + u_high * 0.3;
                v_beat = u_env;
                v_depth = proj;
            }
        """

        private val FRAGMENT_SHADER = """#version 300 es
            precision highp float;
            uniform float u_dim;
            uniform float u_phase;
            
            in vec3 v_pos;
            in float v_energy;
            in float v_beat;
            in float v_depth;
            
            out vec4 fragColor;

            // Highly saturated, vibrant Tetris Effect palette
            vec3 palette(float t) {
                vec3 a = vec3(0.5, 0.5, 0.5);
                vec3 b = vec3(0.5, 0.5, 0.5);
                vec3 c = vec3(1.0, 1.0, 1.0);
                vec3 d = vec3(0.00, 0.33, 0.67);
                return a + b * cos(6.28318 * (c * t + d));
            }

            void main() {
                vec2 c = gl_PointCoord * 2.0 - 1.0;
                float d = dot(c, c);
                if (d > 1.0) discard;
                
                // Gaussian core for a soft, magical glow
                float core = exp(-d * 3.5);

                // Map 3D spatial position into a 1D scalar for the color palette
                // We use v_beat to shift the color spectrum dramatically on beats,
                // and u_phase to slowly rotate the palette over time.
                float spatialHue = (v_pos.x * 0.3 + v_pos.y * 0.4 + v_pos.z * 0.3) + u_phase + v_beat * 0.5;
                
                // Get the extremely saturated base color
                vec3 col = palette(spatialHue);

                // Instead of mixing with white, we just multiply the color's brightness
                // This keeps it vividly colorful even during intense energy/beats.
                float brightness = 1.0 + v_energy * 2.5 + v_beat * 3.0;
                col *= brightness;

                // Apply particle shape and global dim
                col *= core * v_depth * u_dim;

                // Ensure deep blacks at idle by keeping base brightness low when energy is low
                float idleDim = 0.5 + v_energy * 0.5;
                col *= idleDim;

                fragColor = vec4(col, 1.0);
            }
        """
    }
}
