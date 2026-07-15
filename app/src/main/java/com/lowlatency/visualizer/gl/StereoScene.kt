package com.lowlatency.visualizer.gl

/**
 * A scene that consumes the interleaved stereo ring (Left/Right) rather than
 * the mono analysis window — the XY oscilloscope family. The renderer fetches
 * the stereo buffer only for these scenes (no other scene pays that copy) and
 * dispatches [drawStereo] instead of [GlScene.draw].
 */
interface StereoScene : GlScene {
    /**
     * Draw one frame from interleaved stereo PCM (L, R, L, R…).
     * Parameters mirror [GlScene.draw]; [pcmStereo] is the caller-owned stereo
     * window — read it synchronously, don't retain it.
     */
    fun drawStereo(pcmStereo: FloatArray, bands: FloatArray, timeSec: Float, dim: Float)
}
