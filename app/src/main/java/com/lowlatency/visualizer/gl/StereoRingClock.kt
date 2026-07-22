package com.lowlatency.visualizer.gl

/**
 * Total frames ever written to the native stereo ring, as of the copy the
 * renderer just made for this frame (see NativeBridge.fillLatestStereoCounted).
 *
 * Consumers that process the stereo window PER SAMPLE (the waveform band
 * split in [BandWaveHistory]) use the delta between frames to consume exactly
 * the new tail. Estimating that tail from wall-clock dt mis-splices the
 * stream every frame — a phase jump that filter banks render as broadband
 * phantom transients ("peaks" in the bass/high bands on a pure test tone).
 */
object StereoRingClock {
    @Volatile var totalFrames = 0L
}
