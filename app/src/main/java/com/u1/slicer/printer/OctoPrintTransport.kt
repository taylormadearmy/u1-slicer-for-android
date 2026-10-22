package com.u1.slicer.printer

import com.u1.slicer.data.PrintHostConfig
import com.u1.slicer.network.FilamentSlot
import com.u1.slicer.network.PrinterStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** REST-only OctoPrint host. Upload and explicit file selection/start are separate requests. */
class OctoPrintTransport(
    private val config: PrintHostConfig,
    client: OkHttpClient = OkHttpClient(),
) : PrinterTransport {
    constructor(baseUrl: String, apiKey: String?, client: OkHttpClient = OkHttpClient()) :
        this(PrintHostConfig(baseUrl, apiKey.orEmpty()), client)
    private val client = client.newBuilder().followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false).callTimeout(60, TimeUnit.SECONDS).build()
    private val address = config.url.trim()
    private val baseUrl = (if (address.contains("://")) address else "http://$address")
        .trimEnd('/').plus('/').toHttpUrl()
    override val capabilities = PrinterTransportCapabilities(
        supportsUpload = true, supportsStartJob = true,
        supportsPause = true, supportsResume = true, supportsCancel = true,
    )
    override val status = MutableStateFlow(PrinterStatus("disconnected", 0f))
    override val filamentSlots = MutableStateFlow<List<FilamentSlot>>(emptyList())
    override val cameraState = MutableStateFlow<CameraState>(CameraState.Disabled)
    private var polling: Job? = null

    override suspend fun start(scope: CoroutineScope) {
        if (polling?.isActive == true) return
        polling = scope.launch {
            while (isActive) {
                status.value = try {
                    val job = JSONObject(request("api/job"))
                    val printer = JSONObject(request("api/printer"))
                    parseStatus(job, printer)
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { PrinterStatus("disconnected", 0f) }
                delay(2000)
            }
        }
    }

    override suspend fun stop() {
        polling?.cancel()
        polling = null
        status.value = PrinterStatus("disconnected", 0f)
    }

    override suspend fun testConnection(): String? = try {
        val version = JSONObject(request("api/version"))
        if (version.optString("server").isBlank()) "Invalid OctoPrint version response" else null
    } catch (e: CancellationException) { throw e }
    catch (e: Exception) { e.message ?: "OctoPrint connection failed" }

    override suspend fun uploadJob(file: File, remoteName: String, startAfterUpload: Boolean): TransportCommandResult = command {
        require(!startAfterUpload) { "OctoPrint requires a separate confirmed start request" }
        requireRemoteName(remoteName)
        require(file.isFile && file.length() > 0) { "G-code file is empty or missing" }
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("select", "false").addFormDataPart("print", "false")
            .addFormDataPart("file", remoteName, file.asRequestBody("application/octet-stream".toMediaType()))
            .build()
        request("api/files/local", body)
    }

    override suspend fun startJob(remoteName: String): TransportCommandResult = command {
        requireRemoteName(remoteName)
        request("api/files/local/$remoteName", JSONObject().put("command", "select").put("print", true).body())
    }

    override suspend fun pauseJob() = jobCommand("pause", "pause")
    override suspend fun resumeJob() = jobCommand("pause", "resume")
    override suspend fun cancelJob() = jobCommand("cancel")

    suspend fun fetchStatus(): PrinterStatus {
        val job = JSONObject(request("api/job"))
        val state = job.optString("state", "Offline").lowercase().let {
            when {
                it.contains("paused") || it.contains("pausing") -> "paused"
                it.contains("printing") || it.contains("starting") -> "printing"
                it.contains("error") || it.contains("closed") -> "error"
                it.contains("complete") || it.contains("finished") -> "complete"
                it.contains("offline") -> "disconnected"
                else -> "standby"
            }
        }
        val progress = job.optJSONObject("progress")
        val filament = job.optJSONObject("job")?.optJSONObject("filament")
        return PrinterStatus(
            state = state,
            progress = ((progress?.optDouble("completion", 0.0)?.takeIf { it.isFinite() } ?: 0.0) / 100.0).toFloat().coerceIn(0f, 1f),
            filename = job.optJSONObject("job")?.optJSONObject("file")?.optString("name").orEmpty(),
            printDuration = progress?.optDouble("printTime", 0.0)?.toFloat() ?: 0f,
            filamentUsed = filament?.keys()?.asSequence()?.sumOf { key -> filament.optJSONObject(key)?.optDouble("length", 0.0) ?: 0.0 }?.toFloat() ?: 0f,
        )
    }

    private suspend fun jobCommand(command: String, action: String? = null) = command {
        val payload = JSONObject().put("command", command)
        action?.let { payload.put("action", it) }
        request("api/job", payload.body())
    }

    private suspend fun request(path: String, body: RequestBody? = null): String = withContext(Dispatchers.IO) {
        val builder = Request.Builder().url(requireNotNull(baseUrl.resolve(path)))
        if (config.apiKey.isNotBlank()) builder.header("X-Api-Key", config.apiKey)
        if (body != null) builder.post(body)
        suspendCancellableCoroutine { continuation ->
            val call = client.newCall(builder.build())
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) { continuation.resumeWithException(e) }
                override fun onResponse(call: Call, response: Response) {
                    val result = runCatching {
                        response.use {
                            check(it.isSuccessful) {
                                when (it.code) {
                                    401, 403 -> "OctoPrint authentication failed (HTTP ${it.code})"
                                    409 -> "OctoPrint upload conflict (HTTP 409)"
                                    else -> "OctoPrint returned HTTP ${it.code}"
                                }
                            }
                            it.body?.string().orEmpty()
                        }
                    }
                    result.fold({ continuation.resume(it) }, { continuation.resumeWithException(it) })
                }
            })
        }
    }

    private suspend fun command(action: suspend () -> Unit): TransportCommandResult = try {
        action()
        TransportCommandResult.Success
    } catch (e: CancellationException) { throw e }
    catch (e: Exception) { TransportCommandResult.Failure(e.message ?: "OctoPrint request failed") }

    private fun JSONObject.body() = toString().toRequestBody("application/json".toMediaType())

    companion object {
        internal fun requireRemoteName(name: String) {
            require(name.length <= 255 && name.matches(Regex("[A-Za-z0-9_-][A-Za-z0-9._-]*\\.gcode"))) { "Invalid G-code filename" }
        }

        internal fun parseStatus(job: JSONObject, printer: JSONObject): PrinterStatus {
            val flags = printer.optJSONObject("state")?.optJSONObject("flags") ?: JSONObject()
            val state = when {
                flags.optBoolean("error") || flags.optBoolean("closedOrError") -> "error"
                flags.optBoolean("paused") -> "paused"
                flags.optBoolean("printing") || flags.optBoolean("pausing") -> "printing"
                flags.optBoolean("operational") -> "standby"
                else -> "disconnected"
            }
            val progress = job.optJSONObject("progress")
            val temperature = printer.optJSONObject("temperature")
            val tool = temperature?.optJSONObject("tool0")
            val bed = temperature?.optJSONObject("bed")
            return PrinterStatus(
                state = state,
                progress = (progress?.optDouble("completion", 0.0)?.takeIf { it.isFinite() } ?: 0.0)
                    .toFloat().div(100f).coerceIn(0f, 1f),
                filename = job.optJSONObject("job")?.optJSONObject("file")?.optString("name").orEmpty(),
                printDuration = progress?.optDouble("printTime", 0.0)?.toFloat() ?: 0f,
                nozzleTemp = tool?.optDouble("actual", 0.0)?.toFloat() ?: 0f,
                nozzleTarget = tool?.optDouble("target", 0.0)?.toFloat() ?: 0f,
                bedTemp = bed?.optDouble("actual", 0.0)?.toFloat() ?: 0f,
                bedTarget = bed?.optDouble("target", 0.0)?.toFloat() ?: 0f,
            )
        }
    }
}
