package com.u1.slicer.printer

import com.u1.slicer.network.FilamentSlot
import com.u1.slicer.network.PrinterStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/** OctoPrint REST transport. Only the confirmed send path may set [startAfterUpload]. */
class OctoPrintTransport(
    private val baseUrl: String,
    private val apiKey: String?,
    private val client: OkHttpClient = OkHttpClient(),
) : PrinterTransport {
    override val capabilities = PrinterTransportCapabilities(
        supportsUpload = true,
        supportsStartJob = true,
        supportsStartOnUpload = true,
        supportsPause = true,
        supportsResume = true,
        supportsCancel = true,
    )

    private val _status = MutableStateFlow(PrinterStatus("disconnected", 0f))
    override val status: Flow<PrinterStatus> = _status.asStateFlow()
    override val filamentSlots: Flow<List<FilamentSlot>> = MutableStateFlow(emptyList())
    override val cameraState: Flow<CameraState> = MutableStateFlow(CameraState.Disabled)
    private var pollingJob: Job? = null

    override suspend fun start(scope: CoroutineScope) {
        if (pollingJob?.isActive == true) return
        pollingJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                _status.value = runCatching { fetchStatus() }.getOrElse { PrinterStatus("disconnected", 0f) }
                delay(2_000)
            }
        }
    }

    override suspend fun stop() {
        pollingJob?.cancel()
        pollingJob = null
        _status.value = PrinterStatus("disconnected", 0f)
    }

    override suspend fun testConnection(): String? = runCatching {
        execute(authorized(Request.Builder().url(url("api/version")).get()).build()).use { response ->
            if (!response.isSuccessful) error("OctoPrint returned HTTP ${response.code}")
        }
        null
    }.getOrElse { it.message ?: "OctoPrint connection failed" }

    override suspend fun uploadJob(
        file: File,
        remoteName: String,
        startAfterUpload: Boolean,
    ): TransportCommandResult = runCatching {
        val normalizedName = remoteName.replace('\\', '/').trimStart('/')
        val fileName = normalizedName.substringAfterLast('/')
        val remotePath = normalizedName.substringBeforeLast('/', "")
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .apply {
                if (startAfterUpload) addFormDataPart("print", "true")
                addFormDataPart("path", remotePath)
            }
            .addFormDataPart("file", fileName, file.asRequestBody("application/octet-stream".toMediaType()))
            .build()
        val request = authorized(Request.Builder().url(url("api/files/local")).post(body)).build()
        execute(request).use { response ->
            if (!response.isSuccessful) return uploadFailure(response.code)
        }
        TransportCommandResult.Success
    }.getOrElse { TransportCommandResult.Failure(it.message ?: "OctoPrint upload failed") }

    override suspend fun startJob(remoteName: String): TransportCommandResult = runCatching {
        val json = JSONObject().put("command", "select").put("print", true)
        val request = authorized(
            Request.Builder()
                .url(url("api/files/local/${encodePath(remoteName)}"))
                .post(json.toString().toRequestBody("application/json".toMediaType())),
        ).build()
        execute(request).use { response ->
            if (!response.isSuccessful) return TransportCommandResult.Failure("OctoPrint start failed (HTTP ${response.code})")
        }
        TransportCommandResult.Success
    }.getOrElse { TransportCommandResult.Failure(it.message ?: "OctoPrint start failed") }

    override suspend fun pauseJob() = jobCommand("pause", "pause")
    override suspend fun resumeJob() = jobCommand("pause", "resume")
    override suspend fun cancelJob() = jobCommand("cancel")

    suspend fun fetchStatus(): PrinterStatus {
        val request = authorized(Request.Builder().url(url("api/job")).get()).build()
        execute(request).use { response ->
            if (!response.isSuccessful) error("OctoPrint status failed (HTTP ${response.code})")
            val root = JSONObject(response.body?.string().orEmpty())
            val state = root.optString("state", "Offline").lowercase().let {
                when {
                    it.contains("paused") || it.contains("pausing") -> "paused"
                    it.contains("printing") || it.contains("starting") -> "printing"
                    it.contains("error") || it.contains("closed") -> "error"
                    it.contains("complete") || it.contains("finished") -> "complete"
                    it.contains("offline") -> "disconnected"
                    else -> "standby"
                }
            }
            val progress = root.optJSONObject("progress")
            val file = root.optJSONObject("job")?.optJSONObject("file")?.optString("name", "").orEmpty()
            val filament = root.optJSONObject("job")?.optJSONObject("filament")
            val filamentUsed = filament?.keys()?.asSequence()?.sumOf { key ->
                filament.optJSONObject(key)?.optDouble("length", 0.0) ?: 0.0
            }?.toFloat() ?: 0f
            return PrinterStatus(
                state = state,
                progress = ((progress?.optDouble("completion", 0.0) ?: 0.0) / 100.0).toFloat(),
                filename = file,
                printDuration = progress?.optDouble("printTime", 0.0)?.toFloat() ?: 0f,
                filamentUsed = filamentUsed,
            )
        }
    }

    private suspend fun jobCommand(command: String, action: String? = null): TransportCommandResult = runCatching {
        val json = JSONObject().put("command", command).apply { action?.let { put("action", it) } }
        val body = json.toString().toRequestBody("application/json".toMediaType())
        execute(authorized(Request.Builder().url(url("api/job")).post(body)).build()).use { response ->
            if (!response.isSuccessful) return TransportCommandResult.Failure("OctoPrint command failed (HTTP ${response.code})")
        }
        TransportCommandResult.Success
    }.getOrElse { TransportCommandResult.Failure(it.message ?: "OctoPrint command failed") }

    private fun uploadFailure(code: Int): TransportCommandResult.Failure = when (code) {
        401, 403 -> TransportCommandResult.Failure("OctoPrint authentication failed (HTTP $code)")
        409 -> TransportCommandResult.Failure("OctoPrint upload conflict (HTTP 409)")
        else -> TransportCommandResult.Failure("OctoPrint upload failed (HTTP $code)")
    }

    private fun encodePath(path: String): String = path.replace('\\', '/').trim('/').split('/')
        .joinToString("/") { URLEncoder.encode(it, StandardCharsets.UTF_8.name()).replace("+", "%20") }

    private fun authorized(builder: Request.Builder): Request.Builder = apiKey?.takeIf { it.isNotBlank() }?.let { builder.header("X-Api-Key", it) } ?: builder
    private fun url(path: String): String = baseUrl.trimEnd('/') + "/" + path.trimStart('/')
    private fun execute(request: Request) = client.newCall(request).execute()
}
