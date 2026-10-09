package com.u1.slicer.printer.elegoo

import com.u1.slicer.network.FilamentSlot
import com.u1.slicer.network.PrinterStatus
import com.u1.slicer.printer.CameraState
import com.u1.slicer.printer.PrinterTransport
import com.u1.slicer.printer.PrinterTransportCapabilities
import com.u1.slicer.printer.TransportCommandResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Unauthenticated LAN protocol for Centauri Carbon CC1, not Carbon 2. */
class ElegooCc1Transport(
    host: String,
    client: OkHttpClient = OkHttpClient(),
    private val discoverMainboard: suspend (String) -> String = ::discoverCc1Mainboard,
    private val websocketUrl: String? = null,
    private val uploadUrl: String? = null,
    private val requestTimeoutMillis: Long = 8000,
) : PrinterTransport {
    private val client = client.newBuilder().followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false).build()
    private val host = normalizeCc1Host(host)
    override val capabilities = PrinterTransportCapabilities(supportsUpload = true, supportsStartJob = true,
        supportsPause = true, supportsResume = true, supportsCancel = true)
    override val status = MutableStateFlow(PrinterStatus("disconnected", 0f))
    override val filamentSlots = MutableStateFlow<List<FilamentSlot>>(emptyList())
    override val cameraState = MutableStateFlow<CameraState>(CameraState.Disabled)
    private val connectionMutex = Mutex()
    private val commandMutex = Mutex()
    private val pending = ConcurrentHashMap<String, CompletableDeferred<Int>>()
    @Volatile private var socket: WebSocket? = null
    @Volatile private var mainboardId = ""
    @Volatile private var statusReply: CompletableDeferred<PrinterStatus>? = null
    private var polling: Job? = null

    override suspend fun start(scope: CoroutineScope) {
        if (polling?.isActive == true) return
        polling = scope.launch(Dispatchers.IO) {
            while (isActive) {
                try { queryStatus() } catch (_: TimeoutCancellationException) { disconnect() }
                catch (cancel: CancellationException) { throw cancel }
                catch (_: Exception) { disconnect() }
                delay(3000)
            }
        }
    }

    override suspend fun stop() {
        polling?.cancel()
        polling = null
        disconnect()
    }

    private fun disconnect() {
        val old = socket
        socket = null
        old?.cancel()
        failPending()
        status.value = PrinterStatus("disconnected", 0f)
    }

    private fun failPending() {
        val failure = IllegalStateException("Elegoo connection closed")
        pending.values.forEach { it.completeExceptionally(failure) }
        pending.clear()
        statusReply?.completeExceptionally(failure)
    }

    private suspend fun connect() = connectionMutex.withLock {
        if (socket != null) return@withLock
        if (mainboardId.isBlank()) mainboardId = discoverMainboard(host)
        val ready = CompletableDeferred<Unit>()
        val candidate = client.newWebSocket(Request.Builder().url(websocketUrl ?: "ws://$host:3030/websocket").build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) { ready.complete(Unit) }
                override fun onMessage(webSocket: WebSocket, text: String) {
                    if (socket !== webSocket) return
                    val frame = ElegooCc1Protocol.parseFrame(text) ?: return
                    ElegooCc1Protocol.status(frame)?.let { status.value = it; statusReply?.complete(it) }
                    ElegooCc1Protocol.acknowledgement(frame)?.let { (id, ack) -> pending[id]?.complete(ack) }
                }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    ready.completeExceptionally(t)
                    if (socket === webSocket) { socket = null; failPending(); status.value = PrinterStatus("disconnected", 0f) }
                }
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    if (socket === webSocket) { socket = null; failPending(); status.value = PrinterStatus("disconnected", 0f) }
                }
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(code, reason)
                }
            })
        socket = candidate
        try { withTimeout(8000) { ready.await() } }
        catch (e: Exception) { if (socket === candidate) socket = null; candidate.cancel(); throw e }
    }

    private suspend fun queryStatus(): PrinterStatus = commandMutex.withLock {
        connect()
        val reply = CompletableDeferred<PrinterStatus>()
        statusReply = reply
        try {
            check(socket?.send(ElegooCc1Protocol.request(ElegooCc1Protocol.Command.STATUS, mainboardId, requestId())) == true)
            withTimeout(requestTimeoutMillis) { reply.await() }
        } finally { statusReply = null }
    }

    override suspend fun testConnection(): String? = try { queryStatus(); null }
    catch (_: TimeoutCancellationException) { disconnect(); "Centauri Carbon did not answer the status request" }
    catch (cancel: CancellationException) { throw cancel }
    catch (e: Exception) { disconnect(); e.message ?: "Could not connect to Centauri Carbon" }

    private suspend fun command(command: ElegooCc1Protocol.Command, data: JSONObject = JSONObject()): TransportCommandResult =
        commandMutex.withLock {
            try {
                connect()
                val id = requestId()
                val reply = CompletableDeferred<Int>()
                pending[id] = reply
                try {
                    check(socket?.send(ElegooCc1Protocol.request(command, mainboardId, id, data)) == true)
                    val ack = withTimeout(requestTimeoutMillis) { reply.await() }
                    if (ack == 0) TransportCommandResult.Success else TransportCommandResult.Failure("Printer rejected command, code $ack")
                } finally { pending.remove(id) }
            } catch (_: TimeoutCancellationException) {
                disconnect()
                TransportCommandResult.Failure("Printer did not acknowledge the command. Check the printer before retrying.")
            } catch (cancel: CancellationException) { throw cancel }
            catch (e: Exception) { disconnect(); TransportCommandResult.Failure(e.message ?: "Command failed") }
        }

    override suspend fun startJob(remoteName: String): TransportCommandResult {
        val data = try { ElegooCc1Protocol.startData(remoteName) }
        catch (e: IllegalArgumentException) { return TransportCommandResult.Failure(e.message ?: "Invalid filename") }
        return command(ElegooCc1Protocol.Command.START, data)
    }
    override suspend fun pauseJob() = command(ElegooCc1Protocol.Command.PAUSE)
    override suspend fun resumeJob() = command(ElegooCc1Protocol.Command.RESUME)
    override suspend fun cancelJob() = command(ElegooCc1Protocol.Command.CANCEL)

    override suspend fun uploadJob(file: File, remoteName: String): TransportCommandResult = withContext(Dispatchers.IO) {
        try {
            ElegooCc1Protocol.validateFilename(remoteName)
            require(file.isFile && file.length() > 0) { "G-code file is empty or missing" }
            connectionMutex.withLock {
                if (mainboardId.isBlank()) mainboardId = discoverMainboard(host)
            }
            val md5 = MessageDigest.getInstance("MD5")
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) { val count = input.read(buffer); if (count < 0) break; md5.update(buffer, 0, count) }
            }
            val digest = md5.digest().joinToString("") { "%02x".format(it) }
            val uploadId = UUID.randomUUID().toString().replace("-", "")
            val size = file.length()
            file.inputStream().use { input ->
                var offset = 0L
                val buffer = ByteArray(1024 * 1024)
                while (offset < size) {
                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                    val count = input.read(buffer, 0, minOf(buffer.size.toLong(), size - offset).toInt())
                    check(count > 0) { "G-code file changed during upload" }
                    val body = MultipartBody.Builder().setType(MultipartBody.FORM)
                        .addFormDataPart("Check", "1").addFormDataPart("S-File-MD5", digest)
                        .addFormDataPart("Offset", offset.toString()).addFormDataPart("Uuid", uploadId)
                        .addFormDataPart("TotalSize", size.toString())
                        .addFormDataPart("File", remoteName, buffer.copyOf(count).toRequestBody("application/octet-stream".toMediaType())).build()
                    client.newCall(Request.Builder().url(uploadUrl ?: "http://$host/uploadFile/upload").post(body).build()).execute().use { response ->
                        check(response.isSuccessful) { "Upload HTTP ${response.code}" }
                        check(JSONObject(response.body?.string() ?: "{}").optString("code") == "000000") { "Printer rejected upload chunk" }
                    }
                    offset += count
                }
            }
            TransportCommandResult.Success
        } catch (cancel: CancellationException) { throw cancel }
        catch (e: Exception) { TransportCommandResult.Failure(e.message ?: "Upload failed") }
    }

    private fun requestId() = UUID.randomUUID().toString().replace("-", "").take(16)
}

internal fun normalizeCc1Host(input: String): String {
    val value = input.trim().removePrefix("http://").removePrefix("https://").trimEnd('/')
    require(value.isNotBlank() && value.none { it.isWhitespace() || it in "/\\?#@:" }) { "Enter the printer hostname or IPv4 address, without a port or path" }
    return value
}

private suspend fun discoverCc1Mainboard(host: String): String = withContext(Dispatchers.IO) {
    DatagramSocket().use { socket ->
        socket.soTimeout = 5000
        val address = InetAddress.getByName(host)
        socket.connect(address, 3000)
        val bytes = "M99999".toByteArray(Charsets.US_ASCII)
        socket.send(DatagramPacket(bytes, bytes.size))
        val response = DatagramPacket(ByteArray(16 * 1024), 16 * 1024)
        socket.receive(response)
        val json = ElegooCc1Protocol.parseFrame(String(response.data, 0, response.length, Charsets.UTF_8))
            ?: error("Invalid Elegoo discovery response")
        ElegooCc1Protocol.discoveryId(json)
    }
}
