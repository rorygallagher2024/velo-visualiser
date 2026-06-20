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

    /** Stops and closes the active stream. */
    external fun nativeStop()

    /** Actual hardware sample rate negotiated by Oboe (e.g. 48000). */
    external fun nativeGetSampleRate(): Int

    /**
     * Spec method: returns the latest PCM window as a freshly allocated array.
     * Convenient for one-off reads; prefer [fillLatestAudioBuffer] in the loop.
     */
    external fun getLatestAudioBuffer(): FloatArray

    /**
     * Zero-allocation render-loop variant: fills the caller-owned [out] array
     * in place with the most recent samples. Returns the count written.
     */
    external fun fillLatestAudioBuffer(out: FloatArray): Int

    /**
     * Spec method: latest FFT band energies as a fresh array of 3 floats —
     * [0]=Lows, [1]=Mids, [2]=Highs, each in 0..1. Prefer the fill variant
     * below in the render loop.
     */
    external fun getLatestFrequencyBands(): FloatArray

    /**
     * Zero-allocation render-loop variant: fills [out] (length >= 3) with the
     * latest [low, mid, high] band energies. Returns the count written.
     */
    external fun fillLatestFrequencyBands(out: FloatArray): Int

    /**
     * System-audio push path. Called from [AudioCaptureService] with 16-bit
     * interleaved PCM read off an AudioPlaybackCapture-configured AudioRecord.
     */
    external fun nativePushPcm(pcm: ShortArray, frames: Int, channels: Int)

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

    /** Current shared session tempo in BPM (0 if unavailable). UI-thread. */
    external fun nativeLinkTempo(): Double

    /** Number of connected Ableton Link peers on the network. UI-thread. */
    external fun nativeLinkPeers(): Int
}
