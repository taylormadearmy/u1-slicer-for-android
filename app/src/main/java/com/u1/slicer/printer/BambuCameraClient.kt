package com.u1.slicer.printer

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.u1.slicer.data.BambuConfig
import com.u1.slicer.data.BambuModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.net.Socket
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLSocket

interface BambuCameraClient {
    fun supports(model: BambuModel): Boolean
    /** Returns a native RTSP URI when this model does not expose JPEG frames. */
    fun rtspUri(config: BambuConfig): String? = null
    suspend fun stream(config: BambuConfig, onFrame: (Bitmap) -> Unit)
    fun stop() = Unit
}

object BambuCameraProtocol {
    private const val AUTH_HEADER_MAGIC = 0x40
    private const val AUTH_PACKET_SIZE = 0x50
    private const val AUTH_PACKET_TYPE = 0x3000
    private const val FRAME_HEADER_SIZE = 16

    fun buildAuthPacket(username: String, password: String): ByteArray {
        val packet = ByteArray(AUTH_PACKET_SIZE)
        writeLittleEndianInt(packet, 0, AUTH_HEADER_MAGIC)
        writeLittleEndianInt(packet, 4, AUTH_PACKET_TYPE)
        writeAsciiField(packet, 16, 32, username)
        writeAsciiField(packet, 48, 32, password)
        return packet
    }

    fun readFramePayloadSize(header: ByteArray): Int {
        require(header.size >= FRAME_HEADER_SIZE) { "Frame header must be 16 bytes" }
        return readLittleEndianInt(header, 0)
    }

    fun readLittleEndianInt(bytes: ByteArray, offset: Int): Int {
        return (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)
    }

    private fun writeLittleEndianInt(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value and 0xFF).toByte()
        bytes[offset + 1] = ((value ushr 8) and 0xFF).toByte()
        bytes[offset + 2] = ((value ushr 16) and 0xFF).toByte()
        bytes[offset + 3] = ((value ushr 24) and 0xFF).toByte()
    }

    private fun writeAsciiField(bytes: ByteArray, offset: Int, length: Int, value: String) {
        val encoded = value.encodeToByteArray()
        encoded.copyInto(bytes, destinationOffset = offset, endIndex = encoded.size.coerceAtMost(length))
    }
}

class DefaultBambuCameraClient : BambuCameraClient {
    private val activeSocket = AtomicReference<Socket?>(null)

    override fun supports(model: BambuModel): Boolean =
        model in TCP_JPEG_MODELS || model in RTSP_MODELS

    override fun rtspUri(config: BambuConfig): String? =
        if (config.model in RTSP_MODELS) {
            // Bambu's H2D/X1 stream is RTSPS. Media3 accepts the URI shape
            // and the custom TLS socket factory below supplies the encrypted
            // transport while preserving the rtsps scheme in RTSP auth data.
            "rtsps://bblp:${config.accessCode}@${config.ip}:322/streaming/live/1"
        } else {
            null
        }

    override fun stop() {
        activeSocket.getAndSet(null)?.close()
    }

    override suspend fun stream(config: BambuConfig, onFrame: (Bitmap) -> Unit) = withContext(Dispatchers.IO) {
        runCatching {
            Log.i(TAG, "camera connect tls port=$TCP_JPEG_PORT model=${config.model}")
            BambuDiagnostics.record("camera_tcp_connect", config, mapOf("protocol" to "TLS", "stage" to TCP_JPEG_PORT))
            streamTls(config, onFrame)
        }.getOrElse { tlsError ->
            currentCoroutineContext().ensureActive()
            Log.w(TAG, "camera tls failed: ${tlsError.javaClass.simpleName}: ${tlsError.message}")
            BambuDiagnostics.record(
                "camera_tcp_fallback",
                config,
                mapOf("protocol" to "plain", "result" to "tls_failed") +
                    BambuDiagnostics.errorDetails(tlsError, config),
            )
            Log.i(TAG, "camera fallback plain port=$TCP_JPEG_PORT model=${config.model}")
            streamPlain(config, onFrame)
        }
    }

    private companion object {
        const val TAG = "BambuCamera"
        const val USERNAME = "bblp"
        const val TCP_JPEG_PORT = 6000
        const val SOCKET_TIMEOUT_MS = 15_000
        const val MAX_FRAME_BYTES = 5 * 1024 * 1024
        val TCP_JPEG_MODELS = setOf(BambuModel.P1S, BambuModel.P1P, BambuModel.A1, BambuModel.A1_MINI)
        val RTSP_MODELS = setOf(BambuModel.X1C, BambuModel.X1E, BambuModel.H2D)
    }

    private fun streamTls(config: BambuConfig, onFrame: (Bitmap) -> Unit) {
        val socket = DefaultBambuLanClient.trustAllSocketFactory().createSocket(
            config.ip,
            TCP_JPEG_PORT,
        ) as SSLSocket
        socket.use { sslSocket ->
            activeSocket.set(sslSocket)
            try {
                sslSocket.soTimeout = SOCKET_TIMEOUT_MS
                sslSocket.startHandshake()
                streamSocket(sslSocket.inputStream, sslSocket.outputStream, config, onFrame)
            } finally {
                activeSocket.compareAndSet(sslSocket, null)
            }
        }
    }

    private fun streamPlain(config: BambuConfig, onFrame: (Bitmap) -> Unit) {
        Socket(config.ip, TCP_JPEG_PORT).use { socket ->
            activeSocket.set(socket)
            try {
                socket.soTimeout = SOCKET_TIMEOUT_MS
                streamSocket(socket.inputStream, socket.outputStream, config, onFrame)
            } finally {
                activeSocket.compareAndSet(socket, null)
            }
        }
    }

    private fun streamSocket(
        input: InputStream,
        output: java.io.OutputStream,
        config: BambuConfig,
        onFrame: (Bitmap) -> Unit,
    ) {
        output.write(BambuCameraProtocol.buildAuthPacket(USERNAME, config.accessCode))
        output.flush()
        Log.i(TAG, "camera auth sent")
        val header = ByteArray(16)
        var frames = 0
        while (true) {
            input.readExactly(header)
            val payloadSize = BambuCameraProtocol.readFramePayloadSize(header)
            require(payloadSize in 1..MAX_FRAME_BYTES) { "Unexpected camera frame size: $payloadSize" }
            val payload = ByteArray(payloadSize)
            input.readExactly(payload)
            val bitmap = BitmapFactory.decodeByteArray(payload, 0, payload.size)
            if (bitmap != null) {
                frames += 1
                if (frames == 1) {
                    Log.i(TAG, "camera first frame bytes=$payloadSize")
                    BambuDiagnostics.record(
                        "camera_first_frame",
                        config,
                        mapOf("protocol" to "TCP_JPEG", "bytes" to payloadSize),
                    )
                }
                onFrame(bitmap)
            }
        }
    }
}

private fun InputStream.readExactly(buffer: ByteArray) {
    var offset = 0
    while (offset < buffer.size) {
        val count = read(buffer, offset, buffer.size - offset)
        if (count < 0) throw java.io.EOFException("Unexpected end of camera stream")
        offset += count
    }
}
