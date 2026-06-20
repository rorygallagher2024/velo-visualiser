package com.lowlatency.visualizer.hue

import android.util.Log
import org.bouncycastle.tls.BasicTlsPSKIdentity
import org.bouncycastle.tls.CipherSuite
import org.bouncycastle.tls.DTLSClientProtocol
import org.bouncycastle.tls.DTLSTransport
import org.bouncycastle.tls.PSKTlsClient
import org.bouncycastle.tls.ProtocolVersion
import org.bouncycastle.tls.UDPTransport
import org.bouncycastle.tls.crypto.impl.bc.BcTlsCrypto
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.charset.StandardCharsets
import java.security.SecureRandom

/**
 * Low-latency Hue Entertainment client.
 *
 * Opens a DTLS-PSK session to the bridge on UDP **port 2100** (identity =
 * `username`, PSK = hex-decoded `clientkey`) and streams Hue Stream **v2.0**
 * binary frames. Frames are fire-and-forget UDP — no ACK, no blocking — which
 * is what keeps the light latency in the single-digit-millisecond range.
 *
 * Lifecycle: [connect] once, [send] per frame from a single sender thread,
 * [close] on teardown. Not thread-safe; drive it from one thread.
 */
class HueStreamClient(
    private val bridgeIp: String,
    private val identity: ByteArray,   // username bytes
    private val psk: ByteArray,        // hex-decoded clientkey
    areaId: String,
) {
    private var socket: DatagramSocket? = null
    private var dtls: DTLSTransport? = null
    private var sequence = 0

    @Volatile var packetsSent = 0L
        private set
    @Volatile var packetsFailed = 0L
        private set

    // Pre-built header (everything before the per-channel color data). Only the
    // sequence byte at [SEQ_OFFSET] changes per frame.
    private val header: ByteArray = buildHeader(areaId)

    // Reusable send buffer: header + up to MAX_CHANNELS * 7 bytes/channel.
    private val frame = ByteArray(header.size + MAX_CHANNELS * BYTES_PER_CHANNEL)

    init {
        System.arraycopy(header, 0, frame, 0, header.size)
    }

    /** Establish the DTLS-PSK session. Blocking; call off the main thread. */
    fun connect() {
        val sock = DatagramSocket()
        sock.connect(InetAddress.getByName(bridgeIp), HUE_STREAM_PORT)
        val transport = UDPTransport(sock, MTU)

        val crypto = BcTlsCrypto(SecureRandom())
        val client = object : PSKTlsClient(crypto, BasicTlsPSKIdentity(identity, psk)) {
            override fun getSupportedVersions(): Array<ProtocolVersion> =
                ProtocolVersion.DTLSv12.only()

            override fun getSupportedCipherSuites(): IntArray =
                intArrayOf(CipherSuite.TLS_PSK_WITH_AES_128_GCM_SHA256)
        }

        dtls = DTLSClientProtocol().connect(client, transport)
        socket = sock
        Log.i(TAG, "DTLS stream connected to $bridgeIp:$HUE_STREAM_PORT")
    }

    /**
     * Send one frame. [channelIds] and [rgb] are parallel: rgb is interleaved
     * r,g,b in 0..1, length = channelIds.size * 3. Caller owns the arrays.
     */
    fun send(channelIds: IntArray, rgb: FloatArray) {
        val transport = dtls ?: return
        val n = minOf(channelIds.size, MAX_CHANNELS)

        frame[SEQ_OFFSET] = (sequence++ and 0xFF).toByte()

        var p = header.size
        for (i in 0 until n) {
            frame[p++] = (channelIds[i] and 0xFF).toByte()
            putColor16(frame, p, rgb[i * 3]);     p += 2
            putColor16(frame, p, rgb[i * 3 + 1]); p += 2
            putColor16(frame, p, rgb[i * 3 + 2]); p += 2
        }
        try {
            transport.send(frame, 0, p)
            packetsSent++
        } catch (t: Throwable) {
            packetsFailed++
            Log.w(TAG, "frame send failed: ${t.message}")
        }
    }

    fun close() {
        try { dtls?.close() } catch (_: Throwable) {}
        try { socket?.close() } catch (_: Throwable) {}
        dtls = null
        socket = null
    }

    private fun putColor16(buf: ByteArray, off: Int, value: Float) {
        val v = (value.coerceIn(0f, 1f) * 65535f).toInt()
        buf[off] = ((v shr 8) and 0xFF).toByte()
        buf[off + 1] = (v and 0xFF).toByte()
    }

    private fun buildHeader(areaId: String): ByteArray {
        val proto = "HueStream".toByteArray(StandardCharsets.US_ASCII)   // 9 bytes
        val area = areaId.toByteArray(StandardCharsets.US_ASCII)         // expect 36
        val h = ByteArray(16 + area.size)
        var i = 0
        System.arraycopy(proto, 0, h, 0, proto.size); i += proto.size    // 0..8
        h[i++] = 0x02            // version major
        h[i++] = 0x00            // version minor
        h[i++] = 0x00            // sequence id (overwritten per frame; == SEQ_OFFSET)
        h[i++] = 0x00            // reserved
        h[i++] = 0x00            // reserved
        h[i++] = 0x00            // color space: 0 = RGB
        h[i++] = 0x00            // reserved
        System.arraycopy(area, 0, h, i, area.size)                       // 16..51 (UUID)
        return h
    }

    companion object {
        private const val TAG = "HueStreamClient"
        private const val HUE_STREAM_PORT = 2100
        private const val MTU = 1500
        private const val MAX_CHANNELS = 20
        private const val BYTES_PER_CHANNEL = 7   // 1 id + 3 * 2-byte color
        private const val SEQ_OFFSET = 11         // index of the sequence byte in the header

        /** Decode a hex string (the Hue clientkey) into raw PSK bytes. */
        fun hexToBytes(hex: String): ByteArray {
            val clean = hex.trim()
            val out = ByteArray(clean.length / 2)
            for (i in out.indices) {
                val hi = Character.digit(clean[i * 2], 16)
                val lo = Character.digit(clean[i * 2 + 1], 16)
                out[i] = ((hi shl 4) or lo).toByte()
            }
            return out
        }
    }
}
