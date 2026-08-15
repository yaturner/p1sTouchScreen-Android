package com.das.p1stouch.printer.camera

import android.util.Log
import java.io.EOFException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

/**
 * Streams JPEG frames from the printer's bespoke camera protocol (port
 * 6000, not RTSP/MJPEG-over-HTTP or anything standard). Confirmed live
 * against a real P1S and cross-checked against `bambulabs_api`'s
 * `camera_client.py` (ground truth for the wire format -- the two magic
 * header constants' actual meaning is undocumented upstream too; this
 * replicates the bytes rather than reinterpreting them):
 *
 * Auth (sent once, right after the TLS handshake): 80 bytes total --
 * four little-endian uint32s (0x40, 0x3000, 0, 0), then the username
 * NUL-padded to 32 bytes, then the access code NUL-padded to 32 bytes.
 *
 * Frames: a 16-byte header whose first 3 bytes (not 4) are a
 * little-endian payload length, the remaining 13 bytes unused; then
 * exactly that many bytes of JPEG.
 *
 * Unlike the Python client (which infers frame boundaries from how many
 * bytes one `recv()` call happens to return -- a real heuristic, not a
 * protocol guarantee), this reads the exact byte counts the protocol
 * promises via blocking `readFully`-style reads, so it can't be fooled by
 * TCP packet boundaries not lining up with message boundaries.
 */
class CameraStreamClient(
    private val ip: String,
    private val accessCode: String,
    private val username: String = "bblp",
) {
    fun frames(): Flow<ByteArray> = flow {
        while (true) {
            var socket: Socket? = null
            try {
                val sslSocket = connectAndAuth()
                socket = sslSocket
                val input = sslSocket.inputStream
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val frame = readFrame(input)
                    if (frame != null) emit(frame)
                }
            } catch (e: Exception) {
                Log.d(TAG, "camera stream error, reconnecting", e)
            } finally {
                try {
                    socket?.close()
                } catch (e: Exception) {
                    // best-effort cleanup
                }
            }
            delay(RECONNECT_DELAY_MS)
        }
    }.flowOn(Dispatchers.IO)

    private fun connectAndAuth(): SSLSocket {
        val trustAll = arrayOf<TrustManager>(
            object : X509TrustManager {
                override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
                override fun checkClientTrusted(certs: Array<X509Certificate>, authType: String) {}
                override fun checkServerTrusted(certs: Array<X509Certificate>, authType: String) {}
            },
        )
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, trustAll, SecureRandom())

        val raw = Socket()
        raw.connect(InetSocketAddress(ip, PORT), CONNECT_TIMEOUT_MS)
        val sslSocket = sslContext.socketFactory.createSocket(raw, ip, PORT, true) as SSLSocket
        sslSocket.useClientMode = true
        sslSocket.soTimeout = READ_TIMEOUT_MS
        sslSocket.startHandshake()

        sslSocket.outputStream.write(buildAuthPacket())
        sslSocket.outputStream.flush()
        return sslSocket
    }

    private fun buildAuthPacket(): ByteArray {
        val buffer = ByteBuffer.allocate(80).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(0x40)
        buffer.putInt(0x3000)
        buffer.putInt(0)
        buffer.putInt(0)
        putPadded(buffer, username, 32)
        putPadded(buffer, accessCode, 32)
        return buffer.array()
    }

    private fun putPadded(buffer: ByteBuffer, value: String, fieldSize: Int) {
        val bytes = value.toByteArray(Charsets.US_ASCII)
        buffer.put(bytes, 0, minOf(bytes.size, fieldSize))
        repeat(fieldSize - minOf(bytes.size, fieldSize)) { buffer.put(0) }
    }

    private fun readFrame(input: InputStream): ByteArray? {
        val header = ByteArray(16)
        readFully(input, header)
        val payloadSize = (header[0].toInt() and 0xFF) or
            ((header[1].toInt() and 0xFF) shl 8) or
            ((header[2].toInt() and 0xFF) shl 16)
        val payload = ByteArray(payloadSize)
        readFully(input, payload)
        val validSoi = payload.size >= 4 &&
            payload[0] == 0xFF.toByte() && payload[1] == 0xD8.toByte() &&
            payload[2] == 0xFF.toByte() && payload[3] == 0xE0.toByte()
        val validEoi = payload.size >= 2 &&
            payload[payload.size - 2] == 0xFF.toByte() && payload[payload.size - 1] == 0xD9.toByte()
        return if (validSoi && validEoi) payload else null
    }

    private fun readFully(input: InputStream, buffer: ByteArray) {
        var offset = 0
        while (offset < buffer.size) {
            val n = input.read(buffer, offset, buffer.size - offset)
            if (n < 0) throw EOFException("camera stream closed after $offset of ${buffer.size} bytes")
            offset += n
        }
    }

    companion object {
        private const val TAG = "CameraStreamClient"
        private const val PORT = 6000
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS = 15_000
        private const val RECONNECT_DELAY_MS = 5_000L
    }
}
