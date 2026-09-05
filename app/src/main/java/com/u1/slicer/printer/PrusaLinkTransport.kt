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
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID

/** PrusaLink v1 REST transport with API-key or HTTP Digest authentication. */
class PrusaLinkTransport(
    private val baseUrl: String,
    private val apiKey: String? = null,
    private val username: String? = null,
    private val password: String? = null,
    private val client: OkHttpClient = OkHttpClient(),
) : PrinterTransport {
    override val capabilities = PrinterTransportCapabilities(
        supportsUpload = true,
        supportsStartJob = true,
        supportsStartOnUpload = true,
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
                _status.value = runCatching { fetchStatus() }
                    .getOrElse { PrinterStatus("disconnected", 0f) }
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
        executeAuthorized(Request.Builder().url(url("api/version")).get().build()).use { response ->
            if (!response.isSuccessful) error("PrusaLink returned HTTP ${response.code}")
        }
        null
    }.getOrElse { it.message ?: "PrusaLink connection failed" }

    override suspend fun uploadJob(
        file: File,
        remoteName: String,
        startAfterUpload: Boolean,
    ): TransportCommandResult = runCatching {
        val request = Request.Builder()
            .url(url("api/v1/files/usb/${encodePath(remoteName)}"))
            .put(file.asRequestBody("text/x.gcode".toMediaType()))
            .header("Overwrite", "?1")
            .apply {
                // Some older firmware treated any present value as true.
                // Omitting this header is the only safe upload-only shape.
                if (startAfterUpload) header("Print-After-Upload", "?1")
            }
            .build()
        executeAuthorized(request).use { response ->
            if (!response.isSuccessful) return uploadFailure(response.code)
        }
        TransportCommandResult.Success
    }.getOrElse { TransportCommandResult.Failure(it.message ?: "PrusaLink upload failed") }

    override suspend fun startJob(remoteName: String): TransportCommandResult = runCatching {
        val request = Request.Builder()
            .url(url("api/v1/files/usb/${encodePath(remoteName)}"))
            .post(ByteArray(0).toRequestBody(null))
            .build()
        executeAuthorized(request).use { response ->
            if (!response.isSuccessful) {
                return TransportCommandResult.Failure("PrusaLink start failed (HTTP ${response.code})")
            }
        }
        TransportCommandResult.Success
    }.getOrElse { TransportCommandResult.Failure(it.message ?: "PrusaLink start failed") }

    suspend fun fetchStatus(): PrinterStatus {
        val request = Request.Builder().url(url("api/v1/status")).get().build()
        executeAuthorized(request).use { response ->
            if (!response.isSuccessful) error("PrusaLink status failed (HTTP ${response.code})")
            val root = JSONObject(response.body?.string().orEmpty())
            val printer = root.optJSONObject("printer") ?: JSONObject()
            val job = root.optJSONObject("job")
            val progressPercent = job?.optDouble("progress", 0.0) ?: 0.0
            val file = job?.optJSONObject("file")
            return PrinterStatus(
                state = mapState(printer.optString("state", "OFFLINE")),
                progress = (progressPercent / 100.0).toFloat().coerceIn(0f, 1f),
                filename = file?.optString("display_name", file.optString("name", "")).orEmpty(),
                printDuration = job?.optDouble("time_printing", 0.0)?.toFloat() ?: 0f,
                nozzleTemp = printer.optDouble("temp_nozzle", 0.0).toFloat(),
                nozzleTarget = printer.optDouble("target_nozzle", 0.0).toFloat(),
                bedTemp = printer.optDouble("temp_bed", 0.0).toFloat(),
                bedTarget = printer.optDouble("target_bed", 0.0).toFloat(),
            )
        }
    }

    private fun executeAuthorized(request: Request): Response {
        val apiKeyValue = apiKey?.takeIf { it.isNotBlank() }
        if (apiKeyValue != null) {
            return execute(request.newBuilder().header("X-Api-Key", apiKeyValue).build())
        }

        val first = execute(request)
        if (first.code != 401 || username.isNullOrBlank()) return first
        val challenge = first.header("WWW-Authenticate")
        val authorization = challenge?.let { buildDigestAuthorization(request, it) } ?: return first
        first.close()
        return execute(request.newBuilder().header("Authorization", authorization).build())
    }

    private fun buildDigestAuthorization(request: Request, challenge: String): String? {
        if (!challenge.startsWith("Digest", ignoreCase = true)) return null
        val values = DIGEST_PARAMETER.findAll(challenge).associate { match ->
            match.groupValues[1].lowercase(Locale.ROOT) to
                (match.groupValues[2].takeIf { it.isNotEmpty() } ?: match.groupValues[3])
        }
        val realm = values["realm"] ?: return null
        val nonce = values["nonce"] ?: return null
        val algorithm = values["algorithm"]?.uppercase(Locale.ROOT) ?: "MD5"
        if (algorithm != "MD5" && algorithm != "MD5-SESS") return null
        val uri = buildString {
            append(request.url.encodedPath)
            request.url.encodedQuery?.let { append('?').append(it) }
        }
        val cnonce = UUID.randomUUID().toString().replace("-", "")
        val nc = "00000001"
        val initialHa1 = md5("$username:$realm:${password.orEmpty()}")
        val ha1 = if (algorithm == "MD5-SESS") md5("$initialHa1:$nonce:$cnonce") else initialHa1
        val ha2 = md5("${request.method}:$uri")
        val qop = values["qop"]?.split(',')?.map(String::trim)?.firstOrNull { it.equals("auth", true) }
        val digestResponse = if (qop != null) {
            md5("$ha1:$nonce:$nc:$cnonce:$qop:$ha2")
        } else {
            md5("$ha1:$nonce:$ha2")
        }
        return buildString {
            append("Digest username=\"").append(username).append("\"")
            append(", realm=\"").append(realm).append("\"")
            append(", nonce=\"").append(nonce).append("\"")
            append(", uri=\"").append(uri).append("\"")
            append(", response=\"").append(digestResponse).append("\"")
            append(", algorithm=").append(algorithm)
            if (qop != null) {
                append(", qop=").append(qop)
                append(", nc=").append(nc)
                append(", cnonce=\"").append(cnonce).append("\"")
            }
            values["opaque"]?.let { append(", opaque=\"").append(it).append("\"") }
        }
    }

    private fun uploadFailure(code: Int): TransportCommandResult.Failure = when (code) {
        401, 403 -> TransportCommandResult.Failure("PrusaLink authentication failed (HTTP $code)")
        409 -> TransportCommandResult.Failure("PrusaLink upload conflict (HTTP 409)")
        else -> TransportCommandResult.Failure("PrusaLink upload failed (HTTP $code)")
    }

    private fun mapState(value: String): String = when (value.uppercase(Locale.ROOT)) {
        "PRINTING", "BUSY" -> "printing"
        "PAUSED" -> "paused"
        "FINISHED" -> "complete"
        "STOPPED" -> "cancelled"
        "ERROR", "ATTENTION" -> "error"
        "IDLE", "READY" -> "standby"
        else -> "disconnected"
    }

    private fun execute(request: Request): Response = client.newCall(request).execute()
    private fun url(path: String): String = baseUrl.trimEnd('/') + "/" + path.trimStart('/')
    private fun encodePath(path: String): String = path.replace('\\', '/').trim('/').split('/')
        .joinToString("/") { URLEncoder.encode(it, StandardCharsets.UTF_8.name()).replace("+", "%20") }
    private fun md5(value: String): String = MessageDigest.getInstance("MD5")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private companion object {
        val DIGEST_PARAMETER = Regex("""([A-Za-z]+)=(?:\"([^\"]*)\"|([^,\s]+))""")
    }
}
