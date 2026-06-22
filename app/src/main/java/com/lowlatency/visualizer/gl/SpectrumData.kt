package com.lowlatency.visualizer.gl

import java.nio.ByteBuffer

/**
 * App-wide spectrum data published by [VisualizerRenderer] each frame.
 * Scenes that need the 128-bin spectrum or the zero-copy PCM buffer read
 * from here directly, keeping the [GlScene.draw] signature narrow.
 *
 * Written and read on the GL thread only (renderer fills before calling
 * scene.draw), so this is effectively single-threaded.
 */
object SpectrumData {
    val magnitudes = FloatArray(128)
    val peaks = FloatArray(128)
    var sharedBuffer: ByteBuffer? = null
}
