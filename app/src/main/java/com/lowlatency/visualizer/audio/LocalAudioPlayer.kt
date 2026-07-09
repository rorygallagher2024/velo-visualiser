package com.lowlatency.visualizer.audio

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.lowlatency.visualizer.NativeBridge
import com.lowlatency.visualizer.R
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread
import kotlin.math.min

/**
 * Plays a local audio file through the native Oboe output stream, mirroring the
 * decoded PCM into the visualizer ring buffers as it is written (the blocking
 * write paces the decoder, so the Oboe buffer is the session's clock).
 *
 * Threading model — one dedicated decode thread owns EVERYTHING with a
 * lifecycle: the extractor, the codec, and the native playback stream
 * (including its pause/resume/close). The public API only flips flags:
 *
 *   - [play]   stops any previous session, then spawns a fresh decode thread;
 *              all setup (including `setDataSource`, which can hit slow SAF
 *              providers) happens on that thread.
 *   - [pause]  sets a flag; the decode thread pauses the stream and parks.
 *   - [resume] clears the flag and wakes the thread.
 *   - [stop]   clears the running flag, wakes the thread, joins it.
 *
 * Because no other thread ever touches the codec or the stream, there is no
 * teardown race: the decode thread's `finally` block is the single point of
 * release, and `stop()` merely waits for it.
 *
 * The decoder's *output* format is authoritative (not the container's): HE-AAC
 * doubles the sample rate on decode, and some decoders emit float PCM — the
 * stream is opened/reopened from `INFO_OUTPUT_FORMAT_CHANGED`. Surround
 * material is downmixed to stereo before it reaches the DAC.
 *
 * @param onCompletion invoked on the main thread when a track finishes
 *   naturally (never after [stop]).
 * @param onError invoked on the main thread with a short user-facing message
 *   when a session dies (unsupported file, device lost, …).
 */
class LocalAudioPlayer(
    private val context: Context,
    private val onCompletion: () -> Unit = {},
    private val onError: (String) -> Unit = {},
) {

    private val lock = Object()
    private var playbackThread: Thread? = null      // guarded by lock
    private var running = false                     // guarded by lock
    private var pauseRequested = false              // guarded by lock
    @Volatile private var session = 0               // bumped by play()/stop(); stales old callbacks
    private val pendingSeekUs = AtomicLong(NO_SEEK)
    private val mainHandler = Handler(Looper.getMainLooper())

    /** Repeat the track seamlessly instead of completing (oscilloscope loops). */
    @Volatile var looping: Boolean = false

    /** Current position / total duration in microseconds (0 while unknown). */
    @Volatile var positionUs: Long = 0L
        private set
    @Volatile var durationUs: Long = 0L
        private set

    val isActivePlaying: Boolean
        get() = synchronized(lock) { running && !pauseRequested }

    val isSessionRunning: Boolean
        get() = synchronized(lock) { running }

    /** Starts a new playback session, replacing any current one. */
    fun play(uri: Uri) {
        stop()
        val mySession: Int
        synchronized(lock) {
            running = true
            pauseRequested = false
            mySession = ++session
        }
        positionUs = 0L
        durationUs = 0L
        pendingSeekUs.set(NO_SEEK)
        playbackThread = thread(name = "LocalAudioPlayback") { runSession(uri, mySession) }
    }

    fun pause() {
        synchronized(lock) { if (running) pauseRequested = true }
    }

    fun resume() {
        synchronized(lock) {
            if (running && pauseRequested) {
                pauseRequested = false
                lock.notifyAll()
            }
        }
    }

    /** Jumps to [targetUs]; applied on the decode thread (works while paused). */
    fun seekTo(targetUs: Long) {
        val clamped = targetUs.coerceIn(0L, if (durationUs > 0) durationUs else Long.MAX_VALUE)
        pendingSeekUs.set(clamped)
        positionUs = clamped   // optimistic, so the UI doesn't snap back mid-poll
        synchronized(lock) { lock.notifyAll() }
    }

    /**
     * Stops playback and waits for the decode thread to tear itself down. If a
     * wedged audio HAL ever holds the thread past the grace period we abandon
     * it (it owns its resources) rather than free anything under it.
     */
    fun stop() {
        val t: Thread?
        synchronized(lock) {
            session++                     // stale any in-flight completion/error post
            running = false
            pauseRequested = false
            lock.notifyAll()
            t = playbackThread
            playbackThread = null
        }
        t?.join(JOIN_GRACE_MS)
        if (t?.isAlive == true) Log.e(TAG, "Decode thread didn't exit within ${JOIN_GRACE_MS}ms — abandoned.")
    }

    // ----- decode thread ---------------------------------------------------

    private fun runSession(uri: Uri, mySession: Int) {
        var extractor: MediaExtractor? = null
        var codec: MediaCodec? = null
        var completed = false
        var error: String? = null
        try {
            extractor = MediaExtractor().apply { setDataSource(context, uri, null) }
            val format = selectAudioTrack(extractor)
                ?: throw PlaybackFailedException(context.getString(R.string.playback_error_no_audio))
            if (format.containsKey(MediaFormat.KEY_DURATION)) {
                durationUs = format.getLong(MediaFormat.KEY_DURATION)
            }
            codec = createDecoder(format)
            completed = decodeLoop(extractor, codec)
        } catch (e: PlaybackFailedException) {
            Log.e(TAG, "Playback failed: ${e.message}")
            error = e.message
        } catch (e: Exception) {
            Log.e(TAG, "Playback failed", e)
            error = context.getString(R.string.playback_error_generic)
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor?.release() }
            NativeBridge.nativeStopPlayback()
            synchronized(lock) { if (session == mySession) running = false }
        }
        notifyResult(mySession, completed, error)
    }

    /** Runs the decode/render loop. Returns true on natural end-of-stream. */
    private fun decodeLoop(extractor: MediaExtractor, codec: MediaCodec): Boolean {
        val info = MediaCodec.BufferInfo()
        val stream = StreamState()
        var inputDone = false
        while (isRunning()) {
            awaitWhilePaused(extractor, codec)
            if (!isRunning()) break
            if (applyPendingSeek(extractor, codec)) inputDone = false
            if (!inputDone) inputDone = feedInput(extractor, codec)
            when (val outIndex = codec.dequeueOutputBuffer(info, DEQUEUE_TIMEOUT_US)) {
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> stream.reconfigure(codec.outputFormat)
                else -> if (outIndex >= 0 && deliverOutput(codec, outIndex, info, stream)) {
                    if (!looping) return true   // EOS rendered (incl. final samples)
                    applySeek(extractor, codec, 0L)
                    inputDone = false
                }
            }
        }
        return false
    }

    /** Applies a UI-requested seek, if one is pending. Returns true if it seeked. */
    private fun applyPendingSeek(extractor: MediaExtractor, codec: MediaCodec): Boolean {
        val target = pendingSeekUs.getAndSet(NO_SEEK)
        if (target == NO_SEEK) return false
        applySeek(extractor, codec, target)
        return true
    }

    private fun applySeek(extractor: MediaExtractor, codec: MediaCodec, targetUs: Long) {
        extractor.seekTo(targetUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
        codec.flush()   // drop in-flight buffers (also re-arms the codec after EOS)
        positionUs = extractor.sampleTime.coerceAtLeast(0L)
    }

    /** Feeds one encoded sample to the codec. Returns true once input hits EOS. */
    private fun feedInput(extractor: MediaExtractor, codec: MediaCodec): Boolean {
        val inIndex = codec.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
        if (inIndex < 0) return false
        val buffer = codec.getInputBuffer(inIndex)
        if (buffer == null) {   // shouldn't happen; return the buffer and carry on
            codec.queueInputBuffer(inIndex, 0, 0, 0L, 0)
            return false
        }
        val size = extractor.readSampleData(buffer, 0)
        return if (size < 0) {
            codec.queueInputBuffer(inIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            true
        } else {
            codec.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
            extractor.advance()
            false
        }
    }

    /** Converts + pushes one output buffer. Returns true when EOS is reached. */
    private fun deliverOutput(
        codec: MediaCodec,
        index: Int,
        info: MediaCodec.BufferInfo,
        stream: StreamState,
    ): Boolean {
        try {
            if (info.size > 0) {
                // Defensive: some codecs hand out data before signalling the format.
                if (!stream.open) stream.reconfigure(codec.outputFormat)
                val buffer = codec.getOutputBuffer(index)
                if (buffer != null) {
                    val frames = stream.decodeToFloat(buffer, info)
                    positionUs = info.presentationTimeUs
                    if (frames > 0 && !NativeBridge.nativePushPlaybackAudio(stream.pushArray, frames)) {
                        throw PlaybackFailedException(context.getString(R.string.playback_error_output_lost))
                    }
                }
            }
        } finally {
            codec.releaseOutputBuffer(index, false)
        }
        return (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
    }

    /** Parks the decode thread (with the stream paused) until resumed/stopped. */
    private fun awaitWhilePaused(extractor: MediaExtractor, codec: MediaCodec) {
        synchronized(lock) {
            if (!pauseRequested || !running) return
            NativeBridge.nativePausePlayback()
            while (pauseRequested && running) {
                lock.wait()
                // Scrubbing while paused: land the seek now and drop the stale
                // ~35 ms still queued in the stream, so resume starts cleanly.
                if (applyPendingSeek(extractor, codec)) NativeBridge.nativeFlushPlayback()
            }
            if (running) NativeBridge.nativeResumePlayback()
        }
    }

    private fun isRunning(): Boolean = synchronized(lock) { running }

    private fun selectAudioTrack(extractor: MediaExtractor): MediaFormat? {
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            if (format.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                extractor.selectTrack(i)
                return format
            }
        }
        return null
    }

    private fun createDecoder(format: MediaFormat): MediaCodec {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Ask the decoder itself to fold surround layouts down to stereo.
            format.setInteger(MediaFormat.KEY_MAX_OUTPUT_CHANNEL_COUNT, 2)
        }
        val mime = format.getString(MediaFormat.KEY_MIME)
            ?: throw PlaybackFailedException(context.getString(R.string.playback_error_unsupported))
        return MediaCodec.createDecoderByType(mime).apply {
            configure(format, null, null, 0)
            start()
        }
    }

    private fun notifyResult(mySession: Int, completed: Boolean, error: String?) {
        if (error == null && !completed) return   // user-initiated stop: no callback
        mainHandler.post {
            if (session != mySession) return@post   // superseded by a newer play()/stop()
            if (error != null) onError(error) else onCompletion()
        }
    }

    // ----- output-stream state (decode thread only) -------------------------

    /**
     * The live DAC configuration, (re)built from the decoder's *output* format.
     * Owns the scratch buffers so per-buffer work is allocation-free once warm.
     */
    private inner class StreamState {
        var open = false
            private set
        val pushArray: FloatArray get() = if (channels > 2) mixed else scratch

        private var sampleRate = 0
        private var channels = 0                // decoder output channels
        private var pcmEncoding = AudioFormat.ENCODING_PCM_16BIT
        private var scratch = FloatArray(0)     // decoded floats, source layout
        private var mixed = FloatArray(0)       // stereo downmix staging

        fun reconfigure(format: MediaFormat) {
            val rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val ch = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val enc = if (format.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                format.getInteger(MediaFormat.KEY_PCM_ENCODING)
            } else {
                AudioFormat.ENCODING_PCM_16BIT
            }
            if (enc != AudioFormat.ENCODING_PCM_16BIT && enc != AudioFormat.ENCODING_PCM_FLOAT) {
                throw PlaybackFailedException(context.getString(R.string.playback_error_unsupported))
            }
            val unchanged = rate == sampleRate && ch == channels && enc == pcmEncoding
            if (open && unchanged) return
            sampleRate = rate
            channels = ch
            pcmEncoding = enc
            // nativeStartPlayback closes any previous stream first, so a
            // mid-track format change is just a reopen at the new rate.
            open = NativeBridge.nativeStartPlayback(sampleRate, min(channels, 2))
            if (!open) throw PlaybackFailedException(context.getString(R.string.playback_error_output))
        }

        /** Converts one codec buffer into [pushArray]; returns the frame count. */
        fun decodeToFloat(buffer: ByteBuffer, info: MediaCodec.BufferInfo): Int {
            buffer.order(ByteOrder.nativeOrder())
            buffer.position(info.offset)
            buffer.limit(info.offset + info.size)
            val samples: Int
            if (pcmEncoding == AudioFormat.ENCODING_PCM_FLOAT) {
                val floats = buffer.asFloatBuffer()
                samples = floats.remaining()
                ensureScratch(samples)
                floats.get(scratch, 0, samples)
            } else {
                val shorts = buffer.asShortBuffer()
                samples = shorts.remaining()
                ensureScratch(samples)
                for (i in 0 until samples) scratch[i] = shorts.get(i) * SHORT_TO_FLOAT
            }
            if (channels <= 0) return 0
            val frames = samples / channels
            if (channels > 2) downmixToStereo(frames)
            return frames
        }

        private fun ensureScratch(samples: Int) {
            if (scratch.size < samples) scratch = FloatArray(samples)
        }

        /**
         * Pre-T fallback when the decoder wouldn't downmix for us: fold 5.1-style
         * layouts (FL FR C LFE BL BR) into stereo; for anything more exotic just
         * take the front pair.
         */
        private fun downmixToStereo(frames: Int) {
            if (mixed.size < frames * 2) mixed = FloatArray(frames * 2)
            val surround = channels >= 6
            for (f in 0 until frames) {
                val base = f * channels
                var l = scratch[base]
                var r = scratch[base + 1]
                if (surround) {
                    val centre = scratch[base + 2] * CENTRE_MIX
                    l = (l + centre + scratch[base + 4] * CENTRE_MIX) * SURROUND_NORM
                    r = (r + centre + scratch[base + 5] * CENTRE_MIX) * SURROUND_NORM
                }
                mixed[f * 2] = l
                mixed[f * 2 + 1] = r
            }
        }
    }

    private class PlaybackFailedException(message: String) : Exception(message)

    companion object {
        private const val TAG = "LocalAudioPlayer"
        private const val NO_SEEK = Long.MIN_VALUE
        private const val DEQUEUE_TIMEOUT_US = 10_000L
        private const val JOIN_GRACE_MS = 2_000L
        private const val SHORT_TO_FLOAT = 1f / 32768f
        private const val CENTRE_MIX = 0.7071f          // -3 dB centre/surround fold
        private const val SURROUND_NORM = 1f / 2.4142f  // keep the fold clip-safe
    }
}
