package com.lowlatency.visualizer.audio

import com.lowlatency.visualizer.NativeBridge
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.sin

/**
 * Test-tone source: a continuous stereo sine driven straight through the
 * existing Oboe playback path, so it is audible *and* mirrored into the
 * visualiser rings (and thus the lights) exactly like local file playback.
 *
 * Reuses [NativeBridge.nativeStartPlayback] / [nativePushPlaybackAudio] /
 * [nativeStopPlayback] — no native code of its own. Like [LocalAudioPlayer],
 * every playback call happens on this generator's single thread (the playback
 * stream is single-thread owned); the UI thread only flips the @Volatile
 * targets below.
 *
 * Left and Right carry independent frequencies so the XY scope scenes trace
 * true Lissajous figures; set them equal for a centred mono tone.
 */
class ToneGenerator {

    /** Target frequencies (Hz) and linear amplitude (0..1); read per chunk. */
    @Volatile var leftHz: Float = 440f
    @Volatile var rightHz: Float = 440f
    @Volatile var level: Float = 0.10f

    private val lock = Object()
    private var worker: Thread? = null          // guarded by lock
    private var running = false                 // guarded by lock

    val isRunning: Boolean get() = synchronized(lock) { running }

    fun start() {
        // Retire any previous session first, so exactly one worker ever owns
        // the playback stream and the old stream is fully closed before the
        // new one opens (the stream is single-thread-owned — overlapping
        // workers would touch it concurrently).
        stop()
        synchronized(lock) {
            running = true
            worker = thread(name = "ToneGenerator") { runSession() }
        }
    }

    fun stop() {
        val t = synchronized(lock) {
            running = false
            worker.also { worker = null }
        }
        // The worker fades out and closes its own playback stream; wait it out.
        // Grace matches LocalAudioPlayer and comfortably exceeds a healthy
        // fade-out, so teardown completes before any subsequent start().
        t?.runCatching { join(JOIN_GRACE_MS) }
    }

    private fun runSession() {
        if (!NativeBridge.nativeStartPlayback(SAMPLE_RATE, CHANNELS)) {
            synchronized(lock) { running = false }
            return
        }
        val rate = NativeBridge.nativeGetSampleRate().coerceAtLeast(8000).toFloat()
        val buf = FloatArray(CHUNK_FRAMES * CHANNELS)

        var phaseL = 0.0                 // wrapped phase in turns [0,1)
        var phaseR = 0.0
        var gain = 0f                    // smoothed amplitude (starts silent → no click)

        while (synchronized(lock) { running }) {
            val incL = leftHz / rate
            val incR = rightHz / rate
            val target = level.coerceIn(0f, 1f)
            for (i in 0 until CHUNK_FRAMES) {
                gain += (target - gain) * GAIN_SMOOTH
                buf[i * 2] = (sin(phaseL * TWO_PI) * gain).toFloat()
                buf[i * 2 + 1] = (sin(phaseR * TWO_PI) * gain).toFloat()
                phaseL = (phaseL + incL) % 1.0
                phaseR = (phaseR + incR) % 1.0
            }
            if (!NativeBridge.nativePushPlaybackAudio(buf, CHUNK_FRAMES)) break
        }

        // Short fade-out so stopping never clicks, then close the stream.
        fadeOut(rate, phaseL, phaseR)
        NativeBridge.nativeStopPlayback()
        synchronized(lock) { running = false }
    }

    private fun fadeOut(rate: Float, startPhaseL: Double, startPhaseR: Double) {
        var phaseL = startPhaseL
        var phaseR = startPhaseR
        val incL = leftHz / rate
        val incR = rightHz / rate
        val buf = FloatArray(CHUNK_FRAMES * CHANNELS)
        var gain = level.coerceIn(0f, 1f)
        val step = gain / (FADE_FRAMES.toFloat())
        var remaining = FADE_FRAMES
        while (remaining > 0) {
            for (i in 0 until CHUNK_FRAMES) {
                gain = (gain - step).coerceAtLeast(0f)
                buf[i * 2] = (sin(phaseL * TWO_PI) * gain).toFloat()
                buf[i * 2 + 1] = (sin(phaseR * TWO_PI) * gain).toFloat()
                phaseL = (phaseL + incL) % 1.0
                phaseR = (phaseR + incR) % 1.0
            }
            if (!NativeBridge.nativePushPlaybackAudio(buf, CHUNK_FRAMES)) break
            remaining -= CHUNK_FRAMES
        }
    }

    companion object {
        private const val SAMPLE_RATE = 48_000
        private const val CHANNELS = 2
        private const val CHUNK_FRAMES = 512
        private const val TWO_PI = 2.0 * PI
        private const val GAIN_SMOOTH = 0.002f   // per-sample level ramp (no clicks)
        private const val FADE_FRAMES = 2_048    // ~43 ms fade-out on stop
        private const val JOIN_GRACE_MS = 2_000L // matches LocalAudioPlayer
    }
}
