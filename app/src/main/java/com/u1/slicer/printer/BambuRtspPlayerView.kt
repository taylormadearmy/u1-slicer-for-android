package com.u1.slicer.printer

import android.content.Context
import android.net.Uri
import android.view.SurfaceView
import android.widget.FrameLayout
import android.util.Log
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import androidx.media3.ui.PlayerView
import java.io.FilterInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.Socket
import java.net.SocketAddress
import java.nio.charset.StandardCharsets
import java.util.ArrayDeque
import javax.net.SocketFactory

/** Small lifecycle-aware view for Bambu's authenticated TLS-backed RTSP feed. */
class BambuRtspPlayerView(context: Context) : FrameLayout(context) {
    private companion object { const val TAG = "BambuRtspPlayer" }
    private val mainHandler = Handler(Looper.getMainLooper())
    private var released = false
    private val restartRunnable = Runnable {
        if (released || !isAttachedToWindow || currentUri == null) return@Runnable
        Log.i(TAG, "restarting ended RTSP stream")
        BambuDiagnostics.record(
            "camera_rtsp_retry",
            details = mapOf("protocol" to "RTSPS", "result" to "preparing"),
        )
        player.seekToDefaultPosition()
        player.prepare()
        player.play()
    }
    private val playerView = PlayerView(context).apply {
        useController = false
        setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
        setShutterBackgroundColor(android.graphics.Color.BLACK)
        resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
    }
    private val player = ExoPlayer.Builder(context).build().apply {
        volume = 0f
        playWhenReady = true
        addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                Log.i(TAG, "playbackState=$playbackState")
                BambuDiagnostics.record(
                    "camera_rtsp_state",
                    details = mapOf("protocol" to "RTSPS", "playbackState" to playbackStateName(playbackState)),
                )
                if (playbackState == Player.STATE_ENDED) scheduleRestart()
            }

            override fun onPlayerError(error: PlaybackException) {
                Log.e(TAG, "RTSP playback failed: ${error.errorCodeName}: ${error.message}", error)
                BambuDiagnostics.record(
                    "camera_rtsp_failed",
                    details = mapOf(
                        "protocol" to "RTSPS",
                        "result" to error.errorCodeName,
                    ) + BambuDiagnostics.errorDetails(error),
                )
                scheduleRestart()
            }
        })
    }
    private var currentUri: String? = null

    init {
        addView(playerView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        playerView.player = player
    }

    fun setStreamUri(uri: String) {
        if (currentUri == uri) return
        mainHandler.removeCallbacks(restartRunnable)
        currentUri = uri
        Log.i(TAG, "opening RTSP stream ${uri.substringBefore('@').substringBefore("://") + "://***@" + uri.substringAfter('@', uri)}")
        BambuDiagnostics.record(
            "camera_rtsp_opened",
            details = mapOf("protocol" to "RTSPS", "result" to "preparing"),
        )
        val mediaSource = RtspMediaSource.Factory()
            .setForceUseRtpTcp(true)
            .setSocketFactory(SdpTolerantSocketFactory(DefaultBambuLanClient.trustAllSocketFactory()))
            .setTimeoutMs(15_000)
            .createMediaSource(MediaItem.fromUri(Uri.parse(uri)))
        player.setMediaSource(mediaSource)
        player.prepare()
    }

    override fun onDetachedFromWindow() {
        mainHandler.removeCallbacks(restartRunnable)
        player.pause()
        playerView.player = null
        super.onDetachedFromWindow()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        playerView.player = player
        if (currentUri != null) {
            player.playWhenReady = true
            if (player.playbackState == Player.STATE_ENDED || player.playbackState == Player.STATE_IDLE) {
                scheduleRestart(delayMs = 0)
            }
        }
    }

    fun release() {
        released = true
        mainHandler.removeCallbacks(restartRunnable)
        playerView.player = null
        player.release()
    }

    private fun scheduleRestart(delayMs: Long = 1_000L) {
        if (released || !isAttachedToWindow || currentUri == null) return
        mainHandler.removeCallbacks(restartRunnable)
        mainHandler.postDelayed(restartRunnable, delayMs)
    }

    private fun playbackStateName(state: Int): String = when (state) {
        Player.STATE_IDLE -> "idle"
        Player.STATE_BUFFERING -> "buffering"
        Player.STATE_READY -> "ready"
        Player.STATE_ENDED -> "ended"
        else -> state.toString()
    }
}

/** Adapts the H2D's non-standard SDP info line without changing message length. */
private class SdpTolerantSocketFactory(private val delegate: javax.net.ssl.SSLSocketFactory) : SocketFactory() {
    override fun createSocket(): Socket = FilteredSocket(delegate.createSocket())
    override fun createSocket(host: String, port: Int): Socket = FilteredSocket(delegate.createSocket(host, port))
    override fun createSocket(host: String, port: Int, localHost: java.net.InetAddress, localPort: Int): Socket =
        FilteredSocket(delegate.createSocket(host, port, localHost, localPort))
    override fun createSocket(host: java.net.InetAddress, port: Int): Socket = FilteredSocket(delegate.createSocket(host, port))
    override fun createSocket(host: java.net.InetAddress, port: Int, localHost: java.net.InetAddress, localPort: Int): Socket =
        FilteredSocket(delegate.createSocket(host, port, localHost, localPort))

    private class FilteredSocket(private val socket: Socket) : Socket() {
        override fun connect(endpoint: SocketAddress?, timeout: Int) = socket.connect(endpoint, timeout)
        override fun getInputStream(): InputStream = SdpInputStream(socket.getInputStream())
        override fun getOutputStream() = socket.getOutputStream()
        override fun setSoTimeout(timeout: Int) = socket.setSoTimeout(timeout)
        override fun getSoTimeout(): Int = socket.soTimeout
        override fun close() = socket.close()
        override fun isConnected(): Boolean = socket.isConnected
    }

    private class SdpInputStream(input: InputStream) : FilterInputStream(input) {
        private val pending = ArrayDeque<Int>()

        override fun read(): Int {
            if (pending.isNotEmpty()) return pending.removeFirst()
            val first = super.read()
            if (first != 'R'.code) return first
            val header = ByteArrayOutputStream()
            header.write(first)
            while (header.size() < 8192) {
                val next = super.read()
                if (next < 0) return first
                header.write(next)
                val bytes = header.toByteArray()
                if (bytes.size >= 4 && bytes.takeLast(4).toByteArray().contentEquals(byteArrayOf(13, 10, 13, 10))) break
            }
            val headerBytes = header.toByteArray()
            val headerText = String(headerBytes, StandardCharsets.ISO_8859_1)
            val match = Regex("(?i)Content-Length:\\s*(\\d+)").find(headerText)
            val contentLength = match?.groupValues?.get(1)?.toIntOrNull() ?: return enqueue(headerBytes)
            val body = ByteArray(contentLength)
            var read = 0
            while (read < contentLength) {
                val count = super.read(body, read, contentLength - read)
                if (count < 0) return enqueue(headerBytes)
                read += count
            }
            val bodyText = String(body, StandardCharsets.ISO_8859_1)
            val filtered = bodyText.split("\r\n")
                .filterNot { it.startsWith("i=") || it.startsWith("a=x-qt-text-inf:") }
                .joinToString("\r\n")
            if (filtered == bodyText) return enqueue(headerBytes + body)
            val newHeader = headerText.replace(Regex("(?i)(Content-Length:\\s*)\\d+"), "${'$'}1${filtered.toByteArray(StandardCharsets.ISO_8859_1).size}")
                .toByteArray(StandardCharsets.ISO_8859_1)
            return enqueue(newHeader + filtered.toByteArray(StandardCharsets.ISO_8859_1))
        }

        private fun enqueue(bytes: ByteArray): Int {
            bytes.drop(1).forEach { pending.addLast(it.toInt() and 0xff) }
            return bytes[0].toInt() and 0xff
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            var count = 0
            while (count < length) {
                val value = read()
                if (value < 0) return if (count == 0) -1 else count
                buffer[offset + count] = value.toByte()
                count++
            }
            return count
        }
    }
}
