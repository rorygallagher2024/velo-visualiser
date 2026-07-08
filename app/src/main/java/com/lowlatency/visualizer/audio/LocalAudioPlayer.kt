package com.lowlatency.visualizer.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import com.lowlatency.visualizer.NativeBridge
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class LocalAudioPlayer(private val context: Context) {

    private var extractor: MediaExtractor? = null
    private var codec: MediaCodec? = null
    private var playbackThread: Thread? = null
    private val isPlaying = AtomicBoolean(false)
    private val isPaused = AtomicBoolean(false)
    
    private var sampleRate = 48000
    private var channelCount = 2

    fun play(uri: Uri) {
        stop()

        try {
            extractor = MediaExtractor()
            extractor?.setDataSource(context, uri, null)

            var trackIndex = -1
            var format: MediaFormat? = null

            for (i in 0 until (extractor?.trackCount ?: 0)) {
                val f = extractor?.getTrackFormat(i)
                val mime = f?.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("audio/")) {
                    trackIndex = i
                    format = f
                    break
                }
            }

            if (trackIndex < 0 || format == null) {
                Log.e("LocalAudioPlayer", "No audio track found.")
                return
            }

            extractor?.selectTrack(trackIndex)

            val mime = format.getString(MediaFormat.KEY_MIME) ?: return
            sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

            codec = MediaCodec.createDecoderByType(mime)
            codec?.configure(format, null, null, 0)
            codec?.start()

            // Start native output stream
            if (!NativeBridge.nativeStartPlayback(sampleRate, channelCount)) {
                Log.e("LocalAudioPlayer", "Failed to start native playback stream.")
                return
            }

            isPlaying.set(true)
            isPaused.set(false)

            playbackThread = thread(name = "LocalAudioPlaybackThread") {
                decodeLoop()
            }

        } catch (e: Exception) {
            Log.e("LocalAudioPlayer", "Error starting playback", e)
            stop()
        }
    }

    fun pause() {
        if (isPlaying.get()) {
            isPaused.set(true)
        }
    }

    fun resume() {
        if (isPlaying.get()) {
            isPaused.set(false)
        }
    }

    fun stop() {
        isPlaying.set(false)
        playbackThread?.join(1000)
        
        try {
            codec?.stop()
            codec?.release()
        } catch (e: Exception) { }
        
        try {
            extractor?.release()
        } catch (e: Exception) { }
        
        codec = null
        extractor = null
        
        NativeBridge.nativeStop()
    }

    private fun decodeLoop() {
        val codec = this.codec ?: return
        val extractor = this.extractor ?: return
        val bufferInfo = MediaCodec.BufferInfo()
        val timeoutUs = 10000L

        var isEOS = false

        while (isPlaying.get()) {
            if (isPaused.get()) {
                Thread.sleep(50)
                continue
            }

            if (!isEOS) {
                val inIndex = codec.dequeueInputBuffer(timeoutUs)
                if (inIndex >= 0) {
                    val buffer = codec.getInputBuffer(inIndex)
                    if (buffer != null) {
                        val sampleSize = extractor.readSampleData(buffer, 0)
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            isEOS = true
                        } else {
                            codec.queueInputBuffer(inIndex, 0, sampleSize, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
            }

            var outIndex = codec.dequeueOutputBuffer(bufferInfo, timeoutUs)
            while (outIndex >= 0) {
                if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    isPlaying.set(false)
                    break
                }

                val outBuffer = codec.getOutputBuffer(outIndex)
                if (outBuffer != null && bufferInfo.size > 0) {
                    // Extract 16-bit PCM and convert to float
                    outBuffer.position(bufferInfo.offset)
                    outBuffer.limit(bufferInfo.offset + bufferInfo.size)
                    
                    val shortBuffer = outBuffer.asShortBuffer()
                    val numShorts = shortBuffer.remaining()
                    val floatArray = FloatArray(numShorts)
                    
                    for (i in 0 until numShorts) {
                        floatArray[i] = shortBuffer.get(i) / 32768f
                    }
                    
                    val frames = numShorts / channelCount
                    // This is a blocking write in native code, which perfectly paces our decoder!
                    NativeBridge.nativePushPlaybackAudio(floatArray, frames)
                }
                
                codec.releaseOutputBuffer(outIndex, false)
                outIndex = codec.dequeueOutputBuffer(bufferInfo, timeoutUs)
            }
        }
        
        NativeBridge.nativeStop()
    }
}
