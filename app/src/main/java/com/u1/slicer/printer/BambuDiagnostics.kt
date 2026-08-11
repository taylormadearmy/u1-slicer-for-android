package com.u1.slicer.printer

import com.u1.slicer.data.BambuConfig
import java.security.MessageDigest
import java.util.Locale

/**
 * Redacted, best-effort diagnostics for the Bambu LAN pipeline.
 *
 * [install] is wired to [com.u1.slicer.DiagnosticsStore] by AppContainer. Keeping
 * the recorder here also lets the RTSP view report Media3 failures without
 * threading an Android context or printer credentials through the UI.
 */
object BambuDiagnostics {
    @Volatile
    private var recorder: ((String, Map<String, Any?>) -> Unit)? = null

    fun install(recorder: (String, Map<String, Any?>) -> Unit) {
        this.recorder = recorder
    }

    fun record(
        event: String,
        config: BambuConfig? = null,
        details: Map<String, Any?> = emptyMap(),
    ) {
        val safeDetails = linkedMapOf<String, Any?>()
        if (config != null) {
            safeDetails["model"] = config.model.name
            safeDetails["printerId"] = shortHash(
                config.serial.trim().uppercase(Locale.ROOT).ifBlank { config.ip.trim() },
            )
            val serial = config.serial.trim()
            safeDetails["serialSuffix"] = if (serial.length > 4) serial.takeLast(4) else "len=${serial.length}"
        }
        safeDetails.putAll(details)
        runCatching { recorder?.invoke("bambu_$event", safeDetails) }
    }

    fun errorDetails(error: Throwable, config: BambuConfig? = null): Map<String, Any?> = mapOf(
        "errorType" to error.javaClass.simpleName,
        "errorCategory" to classifyError(error),
        "errorMessage" to redact(error.message, config),
    )

    fun projectId(remoteName: String): String = shortHash(remoteName)

    internal fun redact(message: String?, config: BambuConfig? = null): String {
        var safe = message.orEmpty().replace(Regex("[\\r\\n\\t]+"), " ").trim()
        listOfNotNull(
            config?.accessCode?.takeIf { it.isNotBlank() },
            config?.serial?.takeIf { it.isNotBlank() },
            config?.ip?.takeIf { it.isNotBlank() },
        ).forEach { secret -> safe = safe.replace(secret, "<redacted>", ignoreCase = true) }
        // Paho and socket exceptions can contain endpoints even when no config
        // was available at the reporting call site.
        safe = safe.replace(Regex("(?<![A-Za-z0-9])(?:\\d{1,3}\\.){3}\\d{1,3}(?::\\d+)?"), "<endpoint>")
        return safe.take(MAX_MESSAGE_CHARS)
    }

    internal fun classifyError(error: Throwable): String = when (error) {
        is java.net.UnknownHostException -> "dns"
        is java.net.ConnectException -> "connect"
        is java.net.SocketTimeoutException,
        is kotlinx.coroutines.TimeoutCancellationException -> "timeout"
        is javax.net.ssl.SSLException -> "tls"
        is java.io.EOFException -> "eof"
        is org.eclipse.paho.client.mqttv3.MqttException -> when (error.reasonCode) {
            org.eclipse.paho.client.mqttv3.MqttException.REASON_CODE_FAILED_AUTHENTICATION.toInt(),
            org.eclipse.paho.client.mqttv3.MqttException.REASON_CODE_NOT_AUTHORIZED.toInt() -> "authorization"
            else -> "mqtt"
        }
        is IllegalArgumentException -> "validation"
        is IllegalStateException -> when {
            error.message.orEmpty().contains("FTPS 53", ignoreCase = true) -> "authorization"
            else -> "protocol"
        }
        else -> "other"
    }

    private fun shortHash(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.encodeToByteArray())
        return digest.take(6).joinToString("") { "%02x".format(it) }
    }

    private const val MAX_MESSAGE_CHARS = 320
}
