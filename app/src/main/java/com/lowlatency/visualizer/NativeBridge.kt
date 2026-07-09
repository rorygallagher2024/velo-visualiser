package com.lowlatency.visualizer

/**
 * Thin JNI surface to the native Oboe audio engine.
 *
 * Everything here is a direct mapping onto a C++ function in `native-lib.cpp`.
 * Keep this object allocation-free on the hot path — [fillLatestAudioBuffer]
 * is called once per rendered frame (up to 120 Hz).
 */
object NativeBridge {

    init {
        System.loadLibrary("native-lib")
    }

    /** Opens + starts the low-latency Oboe input stream (mic, Unprocessed). */
    external fun nativeStartMicrophone(): Boolean

    /** Stops and closes the capture (mic) stream. Does not touch playback. */
    external fun nativeStop()

    /**
     * Tells the engine which source is live so analysis can apply a per-source
     * gain. Values: [SOURCE_MIC], [SOURCE_SYSTEM], [SOURCE_LOCAL].
     */
    external fun nativeSetInputSource(source: Int)

    // --- Local playback ---------------------------------------------------
    // The playback stream is owned by LocalAudioPlayer's decode thread; every
    // function in this block must be called from that thread only.

    /** Opens + starts the Oboe output stream (playback). Decode thread only. */
    external fun nativeStartPlayback(sampleRate: Int, channelCount: Int): Boolean

    /**
     * Pushes decoded PCM to the speaker (blocking write, which paces the
     * decoder) and mirrors it into the visualizer buffers. Returns false when
     * the stream is dead (disconnected/stalled) so the caller stops decoding.
     */
    external fun nativePushPlaybackAudio(pcm: FloatArray, frames: Int): Boolean

    /** Pauses the playback stream and fades the visual buffers to silence. */
    external fun nativePausePlayback()

    /** Resumes a paused playback stream. */
    external fun nativeResumePlayback()

    /** Drops queued-but-unplayed audio; only valid while paused (seek-in-pause). */
    external fun nativeFlushPlayback()

    /** Stops and closes the playback stream. Decode thread only. */
    external fun nativeStopPlayback()

    /** Actual hardware sample rate negotiated by Oboe (e.g. 48000). */
    external fun nativeGetSampleRate(): Int

    /**
     * Fills the provided caller-owned [outInterleaved] array with the most recent
     * stereo PCM window (Left, Right, Left, Right...). Size should be 2x the mono window.
     */
    external fun fillLatestStereoAudioBuffer(outInterleaved: FloatArray): Int

    /**
     * One-time initialization of the shared DirectByteBuffer. Call this
     * at startup so the native layer can store the address.
     */
    external fun nativeInitializeSharedBuffer(buffer: java.nio.ByteBuffer)

    /**
     * Fills the shared buffer with the latest PCM window. Returns the count.
     */
    external fun fillSharedAudioBuffer(): Int

    /**
     * Single-FFT combined path: fills 3 bands + 128 magnitudes + 128 peaks
     * from one transform. [dt] is the frame delta for peak-fall physics.
     */
    external fun fillLatestAll(bands: FloatArray, magnitudes: FloatArray, peaks: FloatArray, dt: Float): Int

    /**
     * System-audio push path. Called from [AudioCaptureService] with 16-bit
     * interleaved PCM read off an AudioPlaybackCapture-configured AudioRecord.
     * [gain] is applied in the native layer (SIMD optimized).
     */
    external fun nativePushPcm(pcm: ShortArray, frames: Int, channels: Int, gain: Float)

    /**
     * Returns [avgConvTimeUs, lastIntervalMs].
     */
    external fun nativeGetSystemAudioMetrics(): FloatArray

    /**
     * Report GL thread performance metrics.
     * [cpuWorkTimeUs]: thread-time spent on the CPU.
     * [gpuTaskTimeNs]: time-elapsed on the GPU (if available).
     */
    external fun nativeUpdateHardwareLoad(cpuWorkTimeUs: Long, gpuTaskTimeNs: Long, gpuAvailable: Boolean)

    /**
     * Returns [cpuWorkTimeUs, gpuTaskTimeMs].
     */
    external fun nativeGetHardwareLoad(): FloatArray

    // --- Diagnostics ---

    /** Measured period between Oboe audio callbacks in milliseconds. */
    external fun nativeGetAudioCallbackMs(): Float

    // --- Ableton Link (wireless tempo/beat sync) ---

    /** Join/leave the local-network Link session. Call from the UI thread. */
    external fun nativeLinkSetEnabled(enabled: Boolean)

    /**
     * Beats elapsed since the previous call (0, or 1 on a beat boundary). Call
     * once per frame from the GL render thread only — it's realtime-safe.
     */
    external fun nativeLinkPollBeats(): Int

    /** Fractional phase within the current beat (0.0 to 1.0). Any-thread safe. */
    external fun nativeLinkBeatPhase(): Double

    /** Fractional phase within the current bar / 4-beat quantum (0.0 to 1.0). */
    external fun nativeLinkBarPhase(): Double

    /** Current shared session tempo in BPM (0 if unavailable). UI-thread. */
    external fun nativeLinkTempo(): Double

    /** Number of connected Ableton Link peers on the network. UI-thread. */
    external fun nativeLinkPeers(): Int

    /** [nativeSetInputSource] values — keep in sync with AudioEngine::InputSource. */
    const val SOURCE_MIC = 0
    const val SOURCE_SYSTEM = 1
    const val SOURCE_LOCAL = 2
}
