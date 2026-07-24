package com.lowlatency.visualizer.gl

import android.opengl.GLES20
import android.opengl.GLES30
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import com.lowlatency.visualizer.BeatBus
import com.lowlatency.visualizer.BeatPulse
import kotlin.math.abs
import kotlin.math.sin

/**
 * "Meridian" — the waveform injected into a full 3D game-grade world.
 *
 * An endless night flight down a winding valley under a real moon. Four passes:
 *
 *  1. **Sky** — moon disc + halo, two layers of drifting moonlit clouds, stars
 *     twinkling with the treble, an aurora breathing with the mids, and the
 *     horizon warming on each beat.
 *  2. **Terrain** — a displaced mesh (painter's order, opaque) whose *fragment*
 *     shader recomputes the height field to derive per-pixel normals: Lambert +
 *     specular moonlight gives silver-lit faces and shadowed sides, with
 *     coloured height-fog folding the ridges into the sky. Bass swells the
 *     ridges slowly, like geology.
 *  3. **Water** — a reflective surface filling the valley floor: rippled
 *     normals, Fresnel reflection of the same procedural sky (moon streak,
 *     cloud shimmer), and the **live PCM waveform running through it as the
 *     emissive current**, lighting its own banks. Loudness breathes the light;
 *     it never gates off.
 *  4. **Shards** — moonlit crystals floating along the banks at many depths,
 *     each pinned to a spectrum band: they glow with their music and catch the
 *     moon as they slowly turn. Respawn ahead forever.
 *
 * Continuous by construction: world-space noise under an ever-advancing camera
 * (drops surge the speed, eased), EMA-smoothed audio everywhere, no gates, no
 * history steps. Only the water's emissive current and shard glow are additive
 * — sky/terrain are opaque, so nothing can pile up to white.
 */
class MeridianScene : GlScene {

    companion object {
        private const val GRID_W = 128
        private const val GRID_D = 96
        private const val WATER_W = 8
        private const val WATER_D = 64
        private const val PCM_PTS = 256
        private const val SHARDS = 16
        private const val SPEED = 1.7f
        private const val GATE_SPACING = 55f    // world units between the colossal rings
        private const val AGC_TARGET = 1.6f
        private const val AGC_FLOOR = 0.02f
        private const val AGC_SMOOTH = 0.07f
        private const val AGC_MIN = 2f
        private const val AGC_MAX = 300f
        private const val AGC_INITIAL = 20f
    }

    private var skyProg = 0
    private var terrainProg = 0
    private var waterProg = 0
    private var shardProg = 0
    private var quadVbo = 0
    private var gridVbo = 0; private var gridIbo = 0; private var gridIndexCount = 0
    private var waterVbo = 0; private var waterIbo = 0; private var waterIndexCount = 0
    private var shardVbo = 0; private var shardVerts = 0
    private var pcmTex = 0

    // Sky uniforms.
    private var sTime = 0; private var sTreble = 0; private var sMid = 0
    private var sEnv = 0; private var sAspect = 0; private var sDim = 0
    private var sCloud = 0; private var sAuroraG = 0; private var sRoll = 0
    // Terrain uniforms.
    private var tTravel = 0; private var tCamX = 0; private var tCamY = 0; private var tF = 0
    private var tRoll = 0; private var tValley = 0; private var tRidgeAmp = 0; private var tRidged = 0
    private var tRiverB = 0; private var tEnv = 0; private var tDim = 0
    // Water uniforms.
    private var wTravel = 0; private var wCamX = 0; private var wCamY = 0; private var wF = 0
    private var wTime = 0; private var wRoll = 0; private var wChop = 0
    private var wLoud = 0; private var wEnv = 0; private var wDim = 0; private var wPcm = 0
    private var wGate0 = 0; private var wGate1 = 0
    // Shard uniforms.
    private var hPos = 0; private var hRot = 0; private var hScale = 0; private var hTravel = 0
    private var hCamX = 0; private var hCamY = 0; private var hF = 0; private var hRoll = 0
    private var hGlow = 0; private var hDim = 0
    // Gate uniforms.
    private var gCenter = 0; private var gScale = 0; private var gCamX = 0; private var gCamY = 0
    private var gRoll = 0; private var gF = 0; private var gGlow = 0; private var gDim = 0

    private val pcmBuf: FloatBuffer = ByteBuffer
        .allocateDirect(PCM_PTS * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
    private val offsets = FloatArray(PCM_PTS)

    // Shards: world z, lateral, altitude, scale, band(0/1/2), spin phase.
    private val shardZ = FloatArray(SHARDS)
    private val shardLat = FloatArray(SHARDS)
    private val shardAlt = FloatArray(SHARDS)
    private val shardScale = FloatArray(SHARDS)
    private val shardBand = IntArray(SHARDS)
    private val shardSeed = FloatArray(SHARDS)
    private val shardOrder = Array(SHARDS) { it }

    private var aspect = 1f
    private var lastT = -1f
    private var travel = 0f
    private var speedEase = 1f
    private var bassE = 0f; private var midE = 0f; private var trebE = 0f; private var loudE = 0f
    private val agc = WaveformAgc(
        target = AGC_TARGET,
        floor = AGC_FLOOR,
        smooth = AGC_SMOOTH,
        min = AGC_MIN,
        max = AGC_MAX,
        initial = AGC_INITIAL,
    )

    // Journey structure: a slow energy history morphs the world; a drop blasts
    // it open (vista). The camera banks into curves and rides altitude arcs.
    private var energy = 0.3f
    private var vista = 0f
    private var vistaHold = 0f   // the drop sets this; vista EASES toward it (no camera cuts)
    private var roll = 0f
    private var camX = 0f
    private var valleyU = 1.2f; private var ridgeAmpU = 0.8f; private var ridgedU = 0.5f
    private var cloudU = 0f; private var auroraGU = 0.8f; private var chopU = 1f
    private var gateProg = 0
    private val gateZ = FloatArray(2)

    override fun onCreated() {
        skyProg = ShaderUtil.buildProgram(MeridianShaders.QUAD_VS, MeridianShaders.SKY_FS)
        terrainProg = ShaderUtil.buildProgram(MeridianShaders.TERRAIN_VS, MeridianShaders.TERRAIN_FS)
        waterProg = ShaderUtil.buildProgram(MeridianShaders.WATER_VS, MeridianShaders.WATER_FS)
        shardProg = ShaderUtil.buildProgram(MeridianShaders.SHARD_VS, MeridianShaders.SHARD_FS)
        gateProg = ShaderUtil.buildProgram(MeridianShaders.GATE_VS, MeridianShaders.GATE_FS)
        locateUniforms()
        initBuffers()
        initShards()
        pcmTex = makePcmTexture()
        gateZ[0] = 30f
        gateZ[1] = 30f + GATE_SPACING
    }

    private fun locateUniforms() {
        sTime = loc(skyProg, "u_time"); sTreble = loc(skyProg, "u_treble"); sMid = loc(skyProg, "u_mid")
        sEnv = loc(skyProg, "u_env"); sAspect = loc(skyProg, "u_aspect"); sDim = loc(skyProg, "u_dim")
        sCloud = loc(skyProg, "u_cloud"); sAuroraG = loc(skyProg, "u_auroraG"); sRoll = loc(skyProg, "u_roll")
        tTravel = loc(terrainProg, "u_travel"); tCamX = loc(terrainProg, "u_camX")
        tCamY = loc(terrainProg, "u_camY"); tF = loc(terrainProg, "u_f"); tRoll = loc(terrainProg, "u_roll")
        tValley = loc(terrainProg, "u_valley"); tRidgeAmp = loc(terrainProg, "u_ridgeAmp")
        tRidged = loc(terrainProg, "u_ridged")
        tRiverB = loc(terrainProg, "u_riverB"); tEnv = loc(terrainProg, "u_env"); tDim = loc(terrainProg, "u_dim")
        wTravel = loc(waterProg, "u_travel"); wCamX = loc(waterProg, "u_camX")
        wCamY = loc(waterProg, "u_camY"); wF = loc(waterProg, "u_f")
        wTime = loc(waterProg, "u_time"); wRoll = loc(waterProg, "u_roll"); wChop = loc(waterProg, "u_chop")
        wLoud = loc(waterProg, "u_loud")
        wEnv = loc(waterProg, "u_env"); wDim = loc(waterProg, "u_dim"); wPcm = loc(waterProg, "u_pcm")
        wGate0 = loc(waterProg, "u_gate0"); wGate1 = loc(waterProg, "u_gate1")
        hPos = loc(shardProg, "u_pos"); hRot = loc(shardProg, "u_rot"); hScale = loc(shardProg, "u_scale")
        hTravel = loc(shardProg, "u_travel"); hCamX = loc(shardProg, "u_camX")
        hCamY = loc(shardProg, "u_camY"); hF = loc(shardProg, "u_f"); hRoll = loc(shardProg, "u_roll")
        hGlow = loc(shardProg, "u_glow"); hDim = loc(shardProg, "u_dim")
        gCenter = loc(gateProg, "u_center"); gScale = loc(gateProg, "u_scale")
        gCamX = loc(gateProg, "u_camX"); gCamY = loc(gateProg, "u_camY")
        gRoll = loc(gateProg, "u_roll"); gF = loc(gateProg, "u_f")
        gGlow = loc(gateProg, "u_glow"); gDim = loc(gateProg, "u_dim")
    }

    private fun loc(p: Int, n: String) = GLES20.glGetUniformLocation(p, n)

    private fun initBuffers() {
        val ids = IntArray(4)
        GLES20.glGenBuffers(4, ids, 0)
        quadVbo = ids[0]; gridVbo = ids[1]; waterVbo = ids[2]; shardVbo = ids[3]

        val quad = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
        uploadStatic(quadVbo, quad)
        gridIbo = buildGrid(gridVbo, GRID_W, GRID_D).also { gridIndexCount = it.second }.first
        waterIbo = buildGrid(waterVbo, WATER_W, WATER_D).also { waterIndexCount = it.second }.first
        buildShardMesh()
    }

    /** (x01, z01) grid + far->near triangle indices (painter's order, no depth buffer). */
    private fun buildGrid(vbo: Int, w: Int, d: Int): Pair<Int, Int> {
        val verts = FloatArray(w * d * 2)
        var vi = 0
        for (r in 0 until d) {
            for (c in 0 until w) {
                verts[vi++] = c.toFloat() / (w - 1)
                verts[vi++] = r.toFloat() / (d - 1)
            }
        }
        uploadStatic(vbo, verts)
        val tri = ShortArray((w - 1) * (d - 1) * 6)
        var ti = 0
        for (r in 0 until d - 1) {
            for (c in 0 until w - 1) {
                val v00 = (r * w + c).toShort(); val v01 = (r * w + c + 1).toShort()
                val v10 = ((r + 1) * w + c).toShort(); val v11 = ((r + 1) * w + c + 1).toShort()
                tri[ti++] = v00; tri[ti++] = v01; tri[ti++] = v10
                tri[ti++] = v01; tri[ti++] = v11; tri[ti++] = v10
            }
        }
        val ibuf: ShortBuffer = ByteBuffer.allocateDirect(tri.size * 2)
            .order(ByteOrder.nativeOrder()).asShortBuffer().put(tri).also { it.position(0) }
        val iids = IntArray(1)
        GLES20.glGenBuffers(1, iids, 0)
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, iids[0])
        GLES20.glBufferData(GLES20.GL_ELEMENT_ARRAY_BUFFER, tri.size * 2, ibuf, GLES20.GL_STATIC_DRAW)
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, 0)
        return iids[0] to ti
    }

    /** Octahedron with per-face normals: (pos.xyz, normal.xyz) × 24 verts. */
    private fun buildShardMesh() {
        val p = arrayOf(
            floatArrayOf(0f, 1.4f, 0f), floatArrayOf(0f, -1.4f, 0f),
            floatArrayOf(1f, 0f, 0f), floatArrayOf(-1f, 0f, 0f),
            floatArrayOf(0f, 0f, 1f), floatArrayOf(0f, 0f, -1f),
        )
        val faces = arrayOf(
            intArrayOf(0, 2, 4), intArrayOf(0, 4, 3), intArrayOf(0, 3, 5), intArrayOf(0, 5, 2),
            intArrayOf(1, 4, 2), intArrayOf(1, 3, 4), intArrayOf(1, 5, 3), intArrayOf(1, 2, 5),
        )
        val out = FloatArray(faces.size * 3 * 6)
        var o = 0
        for (f in faces) {
            val a = p[f[0]]; val b = p[f[1]]; val c = p[f[2]]
            val ux = b[0] - a[0]; val uy = b[1] - a[1]; val uz = b[2] - a[2]
            val vx = c[0] - a[0]; val vy = c[1] - a[1]; val vz = c[2] - a[2]
            var nx = uy * vz - uz * vy; var ny = uz * vx - ux * vz; var nz = ux * vy - uy * vx
            val len = kotlin.math.sqrt(nx * nx + ny * ny + nz * nz).coerceAtLeast(1e-5f)
            nx /= len; ny /= len; nz /= len
            for (v in f) {
                out[o++] = p[v][0]; out[o++] = p[v][1]; out[o++] = p[v][2]
                out[o++] = nx; out[o++] = ny; out[o++] = nz
            }
        }
        shardVerts = faces.size * 3
        uploadStatic(shardVbo, out)
    }

    private fun uploadStatic(vbo: Int, data: FloatArray) {
        val buf = ByteBuffer.allocateDirect(data.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer().put(data).also { it.position(0) }
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo)
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, data.size * 4, buf, GLES20.GL_STATIC_DRAW)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
    }

    private fun makePcmTexture(): Int {
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ids[0])
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES30.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, GLES30.GL_R16F,
            PCM_PTS, 1, 0, GLES30.GL_RED, GLES20.GL_FLOAT, null
        )
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        return ids[0]
    }

    private fun initShards() {
        val rnd = kotlin.random.Random(11)
        for (i in 0 until SHARDS) {
            shardZ[i] = 2f + i * 1.6f
            shardLat[i] = (if (i % 2 == 0) -1f else 1f) * (1.3f + rnd.nextFloat() * 1.3f)
            shardAlt[i] = 1.15f + rnd.nextFloat() * 1.1f
            shardScale[i] = 0.10f + rnd.nextFloat() * 0.14f
            shardBand[i] = i % 3
            shardSeed[i] = rnd.nextFloat() * 6.28f
        }
    }

    override fun onResize(width: Int, height: Int, aspect: Float) {
        this.aspect = if (height > 0) width.toFloat() / height else 1f
    }

    /** Smooth every audio feed; upload the river current (EMA'd, auto-gained PCM). */
    private fun updateAudio(pcm: FloatArray, bands: FloatArray, dt: Float) {
        bassE += (bands[0] - bassE) * (dt * 2.5f).coerceIn(0f, 1f)
        midE += (bands[1] - midE) * (dt * 3f).coerceIn(0f, 1f)
        trebE += (bands[2] - trebE) * (dt * 4f).coerceIn(0f, 1f)
        loudE += (BeatBus.loudness - loudE) * (dt * 4f).coerceIn(0f, 1f)
        val gain = agc.update(pcm)
        val n = pcm.size
        pcmBuf.clear()
        for (i in 0 until PCM_PTS) {
            val t01 = i.toFloat() / (PCM_PTS - 1)
            val g = pcm[((1f - t01) * (n - 1)).toInt()] * gain
            offsets[i] += (g / (1f + abs(g)) - offsets[i]) * 0.45f
            pcmBuf.put(offsets[i])
        }
        pcmBuf.position(0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, pcmTex)
        GLES20.glTexSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, PCM_PTS, 1, GLES30.GL_RED, GLES20.GL_FLOAT, pcmBuf)
    }

    /** Kotlin twin of the shaders' meander() — MUST stay identical. */
    private fun meanderK(z: Float): Float =
        (sin(z * 0.11f) * 0.8f + sin(z * 0.043f + 1.7f) * 1.3f) * 0.55f

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

    /**
     * Journey structure: a ~14s energy history morphs the geography (calm = wide
     * basin, soft hills, clear sky; charged = narrow canyon, knife crests, heavy
     * cloud, burning aurora). A drop sets [vista] — the walls blast open for a
     * few seconds. The camera banks into the meander and rides altitude arcs.
     */
    private fun updateWorldState(dt: Float, timeSec: Float): Float {
        val target = (0.45f * loudE + 0.35f * bassE + 0.20f * midE).coerceIn(0f, 1f)
        energy += (target - energy) * (dt / 14f).coerceIn(0f, 1f)
        // The drop arms a target; the applied vista EASES toward it (~0.7s), so
        // the walls part and the camera rises like a crane shot — a step here
        // teleports the camera and reads as a jarring scene cut.
        if (BeatPulse.surge > 0.9f) vistaHold = 1f
        vistaHold = (vistaHold - dt / 6f).coerceAtLeast(0f)
        vista += (vistaHold - vista) * (dt * 1.4f).coerceIn(0f, 1f)

        valleyU = lerp(1.7f, 0.75f, energy) + 1.5f * vista
        ridgeAmpU = lerp(0.55f, 1.30f, energy) * (1f - 0.35f * vista) * (1f + 0.25f * bassE)
        ridgedU = lerp(0.25f, 0.95f, energy)
        cloudU = lerp(-0.08f, 0.16f, energy)
        auroraGU = 0.45f + 1.0f * energy
        chopU = lerp(0.6f, 1.35f, energy)

        speedEase += ((0.85f + 0.35f * energy) * (1f + BeatPulse.surge * 0.9f) - speedEase) *
            (dt * 3f).coerceIn(0f, 1f)
        travel += dt * SPEED * speedEase

        // Flight language: look-ahead into the curve, bank with its slope,
        // rise over calm basins / hug the floor through charged narrows.
        val look = meanderK(travel + 2.2f) - meanderK(travel)
        camX = meanderK(travel) + look * 0.4f
        val slope = (meanderK(travel + 0.7f) - meanderK(travel)) / 0.7f
        roll += ((-slope * 0.5f).coerceIn(-0.18f, 0.18f) - roll) * (dt * 2.5f).coerceIn(0f, 1f)
        return lerp(0.62f, 0.34f, energy) + vista * 0.5f + 0.035f * sin(timeSec * 0.23f)
    }

    override fun draw(pcm: FloatArray, bands: FloatArray, timeSec: Float, dim: Float) {
        if (lastT < 0f) lastT = timeSec
        val dt = (timeSec - lastT).coerceIn(0f, 0.1f)
        lastT = timeSec
        updateAudio(pcm, bands, dt)
        val camY = updateWorldState(dt, timeSec)
        for (i in 0 until SHARDS) {                       // recycle shards ahead forever
            if (shardZ[i] - travel < 1.2f) shardZ[i] += SHARDS * 1.6f
        }
        for (i in gateZ.indices) {                        // gates: fly through, respawn ahead
            if (gateZ[i] - travel < -0.6f) gateZ[i] += gateZ.size * GATE_SPACING
        }
        val env = BeatPulse.envelope
        val fx = 1.15f / aspect
        val fy = 1.15f

        GLES20.glDisable(GLES20.GL_BLEND)
        drawSky(timeSec, env, dim)
        drawTerrain(camY, env, dim, fx, fy)
        drawWater(timeSec, camY, env, dim, fx, fy)
        drawShards(timeSec, camY, dim, fx, fy, bands)
        drawGates(camY, env, dim, fx, fy)
    }

    private fun drawSky(timeSec: Float, env: Float, dim: Float) {
        GLES20.glUseProgram(skyProg)
        GLES20.glUniform1f(sTime, timeSec); GLES20.glUniform1f(sTreble, trebE)
        GLES20.glUniform1f(sMid, midE); GLES20.glUniform1f(sEnv, env)
        GLES20.glUniform1f(sAspect, aspect); GLES20.glUniform1f(sDim, dim)
        GLES20.glUniform1f(sCloud, cloudU); GLES20.glUniform1f(sAuroraG, auroraGU)
        GLES20.glUniform1f(sRoll, roll)
        drawFullscreenQuad()
    }

    private fun drawTerrain(camY: Float, env: Float, dim: Float, fx: Float, fy: Float) {
        GLES20.glUseProgram(terrainProg)
        GLES20.glUniform1f(tTravel, travel); GLES20.glUniform1f(tCamX, camX)
        GLES20.glUniform1f(tCamY, camY); GLES20.glUniform2f(tF, fx, fy)
        GLES20.glUniform1f(tRoll, roll)
        GLES20.glUniform1f(tValley, valleyU); GLES20.glUniform1f(tRidgeAmp, ridgeAmpU)
        GLES20.glUniform1f(tRidged, ridgedU)
        GLES20.glUniform1f(tRiverB, 0.35f + 0.65f * loudE)
        GLES20.glUniform1f(tEnv, env); GLES20.glUniform1f(tDim, dim)
        drawIndexedGrid(gridVbo, gridIbo, gridIndexCount)
    }

    private fun drawWater(timeSec: Float, camY: Float, env: Float, dim: Float, fx: Float, fy: Float) {
        GLES20.glUseProgram(waterProg)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, pcmTex)
        GLES20.glUniform1i(wPcm, 0)
        GLES20.glUniform1f(wTravel, travel); GLES20.glUniform1f(wCamX, camX)
        GLES20.glUniform1f(wCamY, camY)
        GLES20.glUniform2f(wF, fx, fy); GLES20.glUniform1f(wTime, timeSec)
        GLES20.glUniform1f(wRoll, roll); GLES20.glUniform1f(wChop, chopU)
        GLES20.glUniform1f(wLoud, loudE); GLES20.glUniform1f(wEnv, env)
        GLES20.glUniform1f(wDim, dim)
        setWaterGate(wGate0, 0, camY, env)
        setWaterGate(wGate1, 1, camY, env)
        drawIndexedGrid(waterVbo, waterIbo, waterIndexCount)
    }

    private fun drawShards(timeSec: Float, camY: Float, dim: Float, fx: Float, fy: Float, bands: FloatArray) {
        GLES20.glUseProgram(shardProg)
        GLES20.glUniform1f(hTravel, travel)
        GLES20.glUniform1f(hCamX, camX)
        GLES20.glUniform1f(hCamY, camY)
        GLES20.glUniform2f(hF, fx, fy)
        GLES20.glUniform1f(hRoll, roll)
        GLES20.glUniform1f(hDim, dim)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, shardVbo)
        GLES20.glEnableVertexAttribArray(0)
        GLES20.glVertexAttribPointer(0, 3, GLES20.GL_FLOAT, false, 24, 0)
        GLES20.glEnableVertexAttribArray(1)
        GLES20.glVertexAttribPointer(1, 3, GLES20.GL_FLOAT, false, 24, 12)
        // Painter's: far shards first (they float high, so terrain rarely occludes).
        shardOrder.sortedByDescending { shardZ[it] - travel }.forEach { i ->
            val zv = shardZ[i] - travel
            if (zv > 14f) return@forEach
            val bandE = when (shardBand[i]) { 0 -> bassE; 1 -> midE; else -> trebE }
            GLES20.glUniform3f(hPos, shardLat[i], shardAlt[i] + 0.06f * sin(timeSec * 0.7f + shardSeed[i]), zv)
            GLES20.glUniform1f(hRot, timeSec * (0.25f + shardSeed[i] * 0.05f) + shardSeed[i])
            GLES20.glUniform1f(hScale, shardScale[i])
            GLES20.glUniform1f(hGlow, 0.25f + bandE * 1.9f)
            GLES30.glDrawArrays(GLES20.GL_TRIANGLES, 0, shardVerts)
        }
        GLES20.glDisableVertexAttribArray(0)
        GLES20.glDisableVertexAttribArray(1)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
    }

    /** Feed one gate's view-space position + glow to the water so it mirrors
     *  (same glow model as [drawGates], so image and reflection stay in step). */
    private fun setWaterGate(location: Int, i: Int, camY: Float, env: Float) {
        val zv = gateZ[i] - travel
        if (zv > 34f || zv < 0.1f) {
            GLES20.glUniform4f(location, 0f, 0f, 1f, 0f)
            return
        }
        val approach = (1.6f - zv * 0.045f).coerceIn(0.3f, 1.6f)
        val fog = kotlin.math.exp(-zv * 0.10f)
        val glow = (0.45f + energy * 1.1f + env * 0.6f) * approach * fog
        GLES20.glUniform4f(location, meanderK(travel + zv) - camX, 1.15f - camY, zv, glow)
    }

    /** The Gates: colossal luminous rings you fly through — additive, so the
     *  no-depth-buffer draw order can never produce a wrong silhouette. */
    private fun drawGates(camY: Float, env: Float, dim: Float, fx: Float, fy: Float) {
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE)
        GLES20.glUseProgram(gateProg)
        GLES20.glUniform1f(gCamX, camX); GLES20.glUniform1f(gCamY, camY)
        GLES20.glUniform1f(gRoll, roll); GLES20.glUniform2f(gF, fx, fy)
        GLES20.glUniform1f(gDim, dim)
        for (i in gateZ.indices) {
            val zv = gateZ[i] - travel
            if (zv > 34f) continue
            // Glow: energy + beat, blooming as you approach, folded into the fog.
            val approach = (1.6f - zv * 0.045f).coerceIn(0.3f, 1.6f)
            val fog = kotlin.math.exp(-zv * 0.10f)
            GLES20.glUniform3f(gCenter, meanderK(travel + zv), 1.15f, zv.coerceAtLeast(0.05f))
            GLES20.glUniform1f(gScale, 2.4f)
            GLES20.glUniform1f(gGlow, (0.45f + energy * 1.1f + env * 0.6f) * approach * fog)
            drawFullscreenQuadGeometry()
        }
        GLES20.glDisable(GLES20.GL_BLEND)
    }

    /** Draw the shared ±1 quad with whatever program/uniforms are bound. */
    private fun drawFullscreenQuadGeometry() {
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, quadVbo)
        GLES20.glEnableVertexAttribArray(0)
        GLES20.glVertexAttribPointer(0, 2, GLES20.GL_FLOAT, false, 0, 0)
        GLES30.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(0)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
    }

    private fun drawFullscreenQuad() {
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, quadVbo)
        GLES20.glEnableVertexAttribArray(0)
        GLES20.glVertexAttribPointer(0, 2, GLES20.GL_FLOAT, false, 0, 0)
        GLES30.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(0)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
    }

    private fun drawIndexedGrid(vbo: Int, ibo: Int, count: Int) {
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo)
        GLES20.glEnableVertexAttribArray(0)
        GLES20.glVertexAttribPointer(0, 2, GLES20.GL_FLOAT, false, 0, 0)
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, ibo)
        GLES20.glDrawElements(GLES20.GL_TRIANGLES, count, GLES20.GL_UNSIGNED_SHORT, 0)
        GLES20.glDisableVertexAttribArray(0)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, 0)
    }

    override fun onDeactivate() {
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
    }
}
