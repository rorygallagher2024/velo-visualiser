package com.lowlatency.visualizer.audio

import android.os.ParcelFileDescriptor
import java.io.Closeable
import java.io.FileInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.pow
import kotlin.math.roundToInt

/** Thrown for structurally valid AIFF files using an encoding we can't read. */
internal class UnsupportedAiffException(message: String) : IOException(message)

/**
 * Minimal streaming reader for AIFF / AIFF-C PCM — the one common audio
 * container Android's MediaExtractor doesn't handle, and a staple format for
 * oscilloscope music. Parses FORM/COMM/SSND, converts big-endian (or AIFF-C
 * 'sowt' little-endian) integer PCM to interleaved float frames, and seeks by
 * plain frame arithmetic (positional reads — nothing shares file state).
 *
 * Owns the file descriptor: `use {}` it. Decode-thread only, same ownership
 * rules as the rest of the player.
 */
internal class AiffSource private constructor(
    private val pfd: ParcelFileDescriptor,
    private val channel: FileChannel,
) : Closeable {

    var sampleRate = 0
        private set
    var channels = 0
        private set
    var durationUs = 0L
        private set
    val positionUs: Long
        get() = if (sampleRate > 0) framePos * MICROS_PER_SEC / sampleRate else 0L

    private var bitsPerSample = 0
    private var littleEndian = false
    private var totalFrames = 0L
    private var dataStart = 0L
    private var dataBytes = 0L
    private var bytesPerFrame = 0
    private var framePos = 0L
    private var raw = ByteArray(0)

    fun seekTo(targetUs: Long) {
        val frame = if (sampleRate > 0) targetUs * sampleRate / MICROS_PER_SEC else 0L
        framePos = frame.coerceIn(0L, totalFrames)
    }

    /** Reads up to [maxFrames] interleaved frames into [out]. 0 = end of data. */
    fun readFrames(out: FloatArray, maxFrames: Int): Int {
        val want = minOf(maxFrames.toLong(), totalFrames - framePos).toInt()
        if (want <= 0) return 0
        val bytes = want * bytesPerFrame
        if (raw.size < bytes) raw = ByteArray(bytes)
        val buf = ByteBuffer.wrap(raw, 0, bytes)
        val filePos = dataStart + framePos * bytesPerFrame
        while (buf.hasRemaining()) {
            if (channel.read(buf, filePos + buf.position()) <= 0) break
        }
        val frames = buf.position() / bytesPerFrame
        if (frames <= 0) return 0
        convert(out, frames * channels)
        framePos += frames
        return frames
    }

    override fun close() {
        runCatching { channel.close() }
        runCatching { pfd.close() }
    }

    // ----- PCM conversion ----------------------------------------------------

    private fun convert(out: FloatArray, samples: Int) {
        when (bitsPerSample) {
            8 -> for (i in 0 until samples) out[i] = raw[i] * BYTE_SCALE   // AIFF 8-bit is signed
            16 -> convert16(out, samples)
            24 -> convert24(out, samples)
            else -> convert32(out, samples)
        }
    }

    private fun byteOrder(): ByteOrder =
        if (littleEndian) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN

    private fun convert16(out: FloatArray, samples: Int) {
        val shorts = ByteBuffer.wrap(raw).order(byteOrder()).asShortBuffer()
        for (i in 0 until samples) out[i] = shorts.get(i) * SHORT_SCALE
    }

    private fun convert24(out: FloatArray, samples: Int) {
        // Pack the 24 bits into the top of an Int so sign extension is free.
        var b = 0
        for (i in 0 until samples) {
            val v = if (littleEndian) {
                (raw[b + 2].toInt() shl 24) or
                    ((raw[b + 1].toInt() and 0xFF) shl 16) or
                    ((raw[b].toInt() and 0xFF) shl 8)
            } else {
                (raw[b].toInt() shl 24) or
                    ((raw[b + 1].toInt() and 0xFF) shl 16) or
                    ((raw[b + 2].toInt() and 0xFF) shl 8)
            }
            out[i] = v * INT_SCALE
            b += 3
        }
    }

    private fun convert32(out: FloatArray, samples: Int) {
        val ints = ByteBuffer.wrap(raw).order(byteOrder()).asIntBuffer()
        for (i in 0 until samples) out[i] = ints.get(i) * INT_SCALE
    }

    // ----- container parsing --------------------------------------------------

    private fun parse(isAifc: Boolean) {
        val fileSize = channel.size()
        var pos = FORM_HEADER_BYTES.toLong()
        var haveComm = false
        while (pos + CHUNK_HEADER_BYTES <= fileSize) {
            val header = ByteBuffer.allocate(CHUNK_HEADER_BYTES)
            if (!readFully(channel, header, pos)) break
            val id = header.getInt(0)
            val size = header.getInt(4).toLong() and UINT_MASK
            when (id) {
                FOURCC_COMM -> {
                    parseComm(pos + CHUNK_HEADER_BYTES, size, isAifc)
                    haveComm = true
                }
                FOURCC_SSND -> parseSsnd(pos + CHUNK_HEADER_BYTES, size)
            }
            pos += CHUNK_HEADER_BYTES + size + (size and 1L)   // chunks pad to even
        }
        validateStreamable(haveComm)
        bytesPerFrame = channels * (bitsPerSample / 8)
        // Trust the smaller of COMM's frame count and the bytes actually present.
        totalFrames = minOf(totalFrames, dataBytes / bytesPerFrame)
        durationUs = totalFrames * MICROS_PER_SEC / sampleRate
    }

    private fun validateStreamable(haveComm: Boolean) {
        val structureOk = haveComm && dataStart != 0L
        val rateOk = sampleRate > 0
        if (!structureOk || !rateOk) {
            throw UnsupportedAiffException("Malformed AIFF (missing COMM/SSND)")
        }
        if (channels !in 1..2) {
            throw UnsupportedAiffException("Only mono/stereo AIFF is supported")
        }
        val depthOk = bitsPerSample in 8..32 && bitsPerSample % 8 == 0
        if (!depthOk) {
            throw UnsupportedAiffException("Unsupported AIFF bit depth: $bitsPerSample")
        }
    }

    private fun parseComm(dataPos: Long, size: Long, isAifc: Boolean) {
        if (size < COMM_BASE_BYTES) throw UnsupportedAiffException("Truncated COMM chunk")
        val wantAifc = isAifc && size >= COMM_AIFC_BYTES
        val buf = ByteBuffer.allocate(if (wantAifc) COMM_AIFC_BYTES else COMM_BASE_BYTES)
        if (!readFully(channel, buf, dataPos)) throw UnsupportedAiffException("Truncated COMM chunk")
        channels = buf.getShort(0).toInt()
        totalFrames = buf.getInt(2).toLong() and UINT_MASK
        bitsPerSample = buf.getShort(6).toInt()
        sampleRate = extendedToInt(buf, 8)
        if (wantAifc) {
            when (buf.getInt(COMM_BASE_BYTES)) {
                FOURCC_NONE, FOURCC_TWOS -> littleEndian = false
                FOURCC_SOWT -> littleEndian = true
                else -> throw UnsupportedAiffException("Compressed AIFF-C is not supported")
            }
        }
    }

    private fun parseSsnd(dataPos: Long, size: Long) {
        val buf = ByteBuffer.allocate(SSND_HEADER_BYTES)
        if (!readFully(channel, buf, dataPos)) throw UnsupportedAiffException("Truncated SSND chunk")
        val offset = buf.getInt(0).toLong() and UINT_MASK
        dataStart = dataPos + SSND_HEADER_BYTES + offset
        dataBytes = (size - SSND_HEADER_BYTES - offset).coerceAtLeast(0L)
    }

    /** AIFF sample rates are IEEE 754 80-bit extended floats — decode by hand. */
    private fun extendedToInt(buf: ByteBuffer, offset: Int): Int {
        val signExp = buf.getShort(offset).toInt() and 0xFFFF
        val mantissa = buf.getLong(offset + 2)
        val exponent = (signExp and 0x7FFF) - EXT_EXP_BIAS
        val magnitude = mantissa.toULong().toDouble() * 2.0.pow(exponent)
        val value = if (signExp and 0x8000 != 0) -magnitude else magnitude
        return value.roundToInt()
    }

    companion object {
        /**
         * Returns a ready [AiffSource] if [pfd] holds an AIFF/AIFF-C file.
         * Returns null (closing [pfd]) when it's some other format — the
         * caller falls back to MediaExtractor. Throws [UnsupportedAiffException]
         * for AIFF files we can't decode.
         */
        fun probe(pfd: ParcelFileDescriptor): AiffSource? {
            val channel = FileInputStream(pfd.fileDescriptor).channel
            val head = ByteBuffer.allocate(FORM_HEADER_BYTES)
            val formType = try {
                if (readFully(channel, head, 0L) && head.getInt(0) == FOURCC_FORM) {
                    head.getInt(8)
                } else {
                    0
                }
            } catch (_: IOException) {
                0   // unseekable or too short — not ours
            }
            if (formType != FOURCC_AIFF && formType != FOURCC_AIFC) {
                runCatching { channel.close() }
                runCatching { pfd.close() }
                return null
            }
            return try {
                AiffSource(pfd, channel).apply { parse(formType == FOURCC_AIFC) }
            } catch (e: Exception) {
                runCatching { channel.close() }
                runCatching { pfd.close() }
                throw e
            }
        }

        private fun readFully(channel: FileChannel, buf: ByteBuffer, position: Long): Boolean {
            while (buf.hasRemaining()) {
                if (channel.read(buf, position + buf.position()) <= 0) return false
            }
            return true
        }

        private const val FOURCC_FORM = 0x464F524D   // "FORM"
        private const val FOURCC_AIFF = 0x41494646   // "AIFF"
        private const val FOURCC_AIFC = 0x41494643   // "AIFC"
        private const val FOURCC_COMM = 0x434F4D4D   // "COMM"
        private const val FOURCC_SSND = 0x53534E44   // "SSND"
        private const val FOURCC_NONE = 0x4E4F4E45   // "NONE" (BE PCM)
        private const val FOURCC_TWOS = 0x74776F73   // "twos" (BE PCM)
        private const val FOURCC_SOWT = 0x736F7774   // "sowt" (LE PCM)

        private const val FORM_HEADER_BYTES = 12
        private const val CHUNK_HEADER_BYTES = 8
        private const val COMM_BASE_BYTES = 18
        private const val COMM_AIFC_BYTES = 22
        private const val SSND_HEADER_BYTES = 8
        private const val UINT_MASK = 0xFFFFFFFFL
        private const val MICROS_PER_SEC = 1_000_000L

        // 80-bit extended float: exponent bias 16383, plus 63 to treat the
        // 64-bit mantissa as an integer.
        private const val EXT_EXP_BIAS = 16383 + 63

        private const val BYTE_SCALE = 1f / 128f
        private const val SHORT_SCALE = 1f / 32768f
        private const val INT_SCALE = 1f / 2147483648f
    }
}
