package com.u1.slicer.printer

import android.util.Log
import com.u1.slicer.data.BambuConfig
import com.u1.slicer.data.BambuModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import javax.net.SocketFactory
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.SSLException
import java.util.concurrent.ConcurrentHashMap

interface BambuFileUploadClient {
    suspend fun upload(config: BambuConfig, file: File, remoteName: String)
}

interface BambuFtpsSessionHandle : AutoCloseable {
    fun upload(file: File, remoteName: String, dataMode: BambuFtpsDataMode)
    fun querySize(remoteName: String, dataMode: BambuFtpsDataMode): Long?
}

enum class BambuFtpsDataMode { PROTECTED, CLEAR }

/**
 * The data connection has been flushed and closed after every byte was copied,
 * but the printer did not send the final 226/250 reply before the control
 * socket timed out. H2D firmware is known to do this while it finishes
 * processing an otherwise complete upload.
 */
internal class BambuFtpsTransferConfirmationTimeout(
    val expectedBytes: Long,
    val transferredBytes: Long,
    cause: SocketTimeoutException,
) : IOException(
    "Timed out awaiting the final FTPS transfer reply after $transferredBytes of $expectedBytes bytes",
    cause,
)

/** A failure received while reading/validating the final reply after STOR. */
internal class BambuFtpsPostTransferReplyFailure(
    val expectedBytes: Long,
    val transferredBytes: Long,
    cause: Exception,
) : IOException(cause.message ?: "FTPS transfer confirmation failed", cause)

/** A precisely phased failure before the post-STOR control reply boundary. */
internal class BambuFtpsPhaseFailure(
    val phase: String,
    val expectedBytes: Long,
    val transferredBytes: Long,
    cause: Exception,
) : IOException("FTPS $phase failed: ${cause.message ?: cause.javaClass.simpleName}", cause)

class DefaultBambuFileUploadClient(
    private val sslSocketFactoryProvider: () -> SSLSocketFactory =
        { DefaultBambuLanClient.trustAllSocketFactory() },
    private val sessionFactory: (BambuConfig, SSLSocketFactory) -> BambuFtpsSessionHandle =
        { config, socketFactory ->
            if (BambuFtpsProtocol.usesLegacyASeriesSession(config.model)) {
                BambuLegacyASeriesFtpsSession(config, socketFactory)
            } else {
                BambuFtpsSession(config, socketFactory)
            }
        },
    private val uploadTimeoutMillis: Long? = null,
) : BambuFileUploadClient {
    private val successfulDataModes = ConcurrentHashMap<String, BambuFtpsDataMode>()

    override suspend fun upload(config: BambuConfig, file: File, remoteName: String) {
        withContext(Dispatchers.IO) {
            withTimeout(uploadTimeoutMillis ?: BambuFtpsProtocol.uploadDeadlineMillis(file.length())) {
                val snapshot = try {
                    // The A-series legacy FTP service is sensitive to the
                    // source stream lifecycle. The generated project is
                    // already closed and immutable when upload is exposed;
                    // preserve the proven v3.3.9 direct-stream behavior for
                    // A1/A1 Mini while retaining the defensive snapshot for
                    // the protected-session families.
                    if (BambuFtpsProtocol.usesLegacyASeriesSession(config.model)) {
                        BambuUploadSnapshot.direct(file)
                    } else {
                        BambuUploadSnapshot.create(file)
                    }
                } catch (error: Exception) {
                    BambuDiagnostics.record(
                        "ftps_finished",
                        config,
                        mapOf(
                            "result" to "failed",
                            "projectId" to BambuDiagnostics.projectId(remoteName),
                            "phase" to "source_snapshot",
                            "sourceBytes" to file.length(),
                        ) + BambuDiagnostics.errorDetails(error, config),
                    )
                    throw error
                }
                val expectedBytes = snapshot.file.length()
                Log.i(TAG, "upload start name=$remoteName bytes=$expectedBytes")
                BambuDiagnostics.record(
                    "ftps_started",
                    config,
                    mapOf(
                        "projectId" to BambuDiagnostics.projectId(remoteName),
                        "bytes" to expectedBytes,
                        "sourceBytes" to snapshot.sourceBytes,
                        "sourceStable" to true,
                    ),
                )
                try {
                    var lastError: Exception? = null
                    val modeKey = "${config.ip}|${config.model}"
                    val candidateModes = BambuFtpsProtocol.candidateDataModes(
                        model = config.model,
                        preferred = successfulDataModes[modeKey],
                    )
                    for ((index, dataMode) in candidateModes.withIndex()) {
                        // A fresh TLS context is required for every FTP control
                        // session. Conscrypt may consume a cached TLS 1.2 ticket
                        // when a later control connection resumes it, leaving no
                        // resumable ticket for that connection's protected data
                        // channel. Bambuddy likewise creates an SSL context per
                        // FTP client and explicitly reuses only its current
                        // control session.
                        try {
                            val attemptSocketFactory = try {
                                sslSocketFactoryProvider()
                            } catch (error: Exception) {
                                throw BambuFtpsPhaseFailure(
                                    phase = "tls_context_create",
                                    expectedBytes = expectedBytes,
                                    transferredBytes = 0L,
                                    cause = error,
                                )
                            }
                            BambuDiagnostics.record(
                                "ftps_attempt",
                                config,
                                mapOf(
                                    "projectId" to BambuDiagnostics.projectId(remoteName),
                                    "dataMode" to dataMode.name,
                                    "attempt" to (index + 1),
                                    "phase" to "control_connect",
                                    "expectedBytes" to expectedBytes,
                                ),
                            )
                            sessionFactory(config, attemptSocketFactory).use { session ->
                                session.upload(snapshot.file, remoteName, dataMode)
                            }
                            successfulDataModes[modeKey] = dataMode
                            Log.i(TAG, "upload complete name=$remoteName mode=$dataMode")
                            BambuDiagnostics.record(
                                "ftps_finished",
                                config,
                                mapOf(
                                    "result" to "success",
                                    "projectId" to BambuDiagnostics.projectId(remoteName),
                                    "dataMode" to dataMode.name,
                                    "phase" to "complete",
                                    "expectedBytes" to expectedBytes,
                                    "transferredBytes" to expectedBytes,
                                ),
                            )
                            return@withTimeout
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (e: Exception) {
                            lastError = e
                            if (BambuFtpsUploadRecovery.confirmationTimedOutAfterCompleteTransfer(e)) {
                                val timeout = e as BambuFtpsTransferConfirmationTimeout
                                successfulDataModes[modeKey] = dataMode
                                Log.w(TAG, "upload data sent for $remoteName; H2D final confirmation timed out")
                                BambuDiagnostics.record(
                                    "ftps_finished",
                                    config,
                                    mapOf(
                                        "result" to "success_unconfirmed_post_data_timeout",
                                        "projectId" to BambuDiagnostics.projectId(remoteName),
                                        "dataMode" to dataMode.name,
                                        "phase" to "post_stor_reply",
                                        "expectedBytes" to timeout.expectedBytes,
                                        "transferredBytes" to timeout.transferredBytes,
                                    ) + BambuDiagnostics.errorDetails(e, config),
                                )
                                return@withTimeout
                            }
                            val shouldVerify = BambuFtpsUploadRecovery.shouldVerifyAfterFailure(e)
                            val remoteSize = if (shouldVerify) {
                                Log.w(TAG, "final transfer reply failed for $remoteName in $dataMode, verifying remote size: ${e.message}")
                                runCatching {
                                    sessionFactory(config, sslSocketFactoryProvider()).use { session ->
                                        session.querySize(remoteName, dataMode)
                                    }
                                }.onFailure {
                                    Log.w(TAG, "remote size verification failed for $remoteName: ${it.message}")
                                }.getOrNull()
                            } else {
                                null
                            }
                            if (shouldVerify) {
                                Log.i(TAG, "remote size verification for $remoteName: remote=$remoteSize expected=$expectedBytes")
                            }
                            if (BambuFtpsUploadRecovery.uploadRecovered(remoteSize, expectedBytes)) {
                                successfulDataModes[modeKey] = dataMode
                                Log.w(TAG, "upload recovered for $remoteName after final-reply failure")
                                BambuDiagnostics.record(
                                    "ftps_finished",
                                    config,
                                    mapOf(
                                        "result" to "recovered_by_remote_size",
                                        "projectId" to BambuDiagnostics.projectId(remoteName),
                                        "dataMode" to dataMode.name,
                                        "phase" to "post_stor_reply",
                                        "remoteSize" to remoteSize,
                                        "expectedBytes" to expectedBytes,
                                        "transferredBytes" to BambuFtpsUploadRecovery.transferredBytes(e),
                                    ),
                                )
                                return@withTimeout
                            }
                            val fallbackMode = candidateModes.getOrNull(index + 1)
                            if (fallbackMode != null && BambuFtpsUploadRecovery.shouldTryAlternateMode(remoteSize, e)) {
                                Log.w(TAG, "retrying upload for $remoteName with fallback data mode $fallbackMode")
                                BambuDiagnostics.record(
                                    "ftps_fallback",
                                    config,
                                    mapOf(
                                        "projectId" to BambuDiagnostics.projectId(remoteName),
                                        "dataMode" to dataMode.name,
                                        "nextDataMode" to fallbackMode.name,
                                        "phase" to BambuFtpsUploadRecovery.failurePhase(e),
                                        "expectedBytes" to expectedBytes,
                                    ) + BambuDiagnostics.errorDetails(e, config),
                                )
                                continue
                            }
                            BambuDiagnostics.record(
                                "ftps_finished",
                                config,
                                mapOf(
                                    "result" to "failed",
                                    "projectId" to BambuDiagnostics.projectId(remoteName),
                                    "dataMode" to dataMode.name,
                                    "phase" to BambuFtpsUploadRecovery.failurePhase(e),
                                    "remoteSize" to remoteSize,
                                    "expectedBytes" to expectedBytes,
                                    "transferredBytes" to BambuFtpsUploadRecovery.transferredBytes(e),
                                ) + BambuDiagnostics.errorDetails(e, config),
                            )
                            throw e
                        }
                    }
                    throw lastError ?: IllegalStateException("Bambu upload failed")
                } finally {
                    snapshot.close()
                }
            }
        }
    }

    private companion object {
        const val TAG = "BambuFtpsClient"
    }
}

internal object BambuFtpsUploadRecovery {
    fun shouldVerifyAfterFailure(error: Exception): Boolean {
        return error is BambuFtpsPostTransferReplyFailure &&
            error.transferredBytes == error.expectedBytes
    }

    fun confirmationTimedOutAfterCompleteTransfer(error: Exception): Boolean =
        error is BambuFtpsTransferConfirmationTimeout &&
            error.transferredBytes == error.expectedBytes

    fun uploadRecovered(remoteSize: Long?, expectedSize: Long): Boolean =
        remoteSize != null && remoteSize == expectedSize

    fun shouldTryAlternateMode(remoteSize: Long?, error: Exception): Boolean =
        remoteSize == 0L ||
            error is SocketTimeoutException ||
            error is SSLException ||
            (error is BambuFtpsPhaseFailure &&
                (error.cause is SocketTimeoutException || error.cause is SSLException)) ||
            error is BambuFtpsPostTransferReplyFailure

    fun transferredBytes(error: Exception): Long? = when (error) {
        is BambuFtpsTransferConfirmationTimeout -> error.transferredBytes
        is BambuFtpsPostTransferReplyFailure -> error.transferredBytes
        is BambuFtpsPhaseFailure -> error.transferredBytes
        else -> null
    }

    fun failurePhase(error: Exception): String = when (error) {
        is BambuFtpsTransferConfirmationTimeout -> "post_stor_reply"
        is BambuFtpsPostTransferReplyFailure -> "post_stor_reply"
        is BambuFtpsPhaseFailure -> error.phase
        else -> "control_or_data_transfer"
    }
}

internal class BambuUploadSnapshot private constructor(
    val file: File,
    val sourceBytes: Long,
    private val ownsFile: Boolean,
) : AutoCloseable {
    override fun close() {
        if (!ownsFile) return
        if (!file.delete() && file.exists()) {
            Log.w("BambuFtpsClient", "Could not delete temporary Bambu upload snapshot")
        }
    }

    companion object {
        fun create(source: File): BambuUploadSnapshot {
            require(source.isFile) { "Bambu project file not found" }
            val sourceBytesBefore = source.length()
            val sourceModifiedBefore = source.lastModified()
            val parent = source.absoluteFile.parentFile
                ?: throw IOException("Bambu project has no parent directory")
            val snapshot = File.createTempFile("bambu-upload-", ".gcode.3mf", parent)
            try {
                source.inputStream().buffered().use { input ->
                    snapshot.outputStream().buffered().use { output ->
                        input.copyTo(output, FTPS_COPY_BUFFER_BYTES)
                        output.flush()
                    }
                }
                val sourceBytesAfter = source.length()
                val sourceModifiedAfter = source.lastModified()
                check(
                    sourceBytesBefore == sourceBytesAfter &&
                        sourceModifiedBefore == sourceModifiedAfter &&
                        snapshot.length() == sourceBytesBefore
                ) {
                    "Bambu project was still being prepared; wait for slicing to finish and retry the upload"
                }
                return BambuUploadSnapshot(snapshot, sourceBytesBefore, ownsFile = true)
            } catch (error: Exception) {
                snapshot.delete()
                throw error
            }
        }

        fun direct(source: File): BambuUploadSnapshot {
            require(source.isFile) { "Bambu project file not found" }
            return BambuUploadSnapshot(source, source.length(), ownsFile = false)
        }

        private const val FTPS_COPY_BUFFER_BYTES = 64 * 1024
    }
}

internal object BambuFtpsUploadFailure {
    fun describe(error: Exception): String = when {
        isAuthorizationFailure(error) ->
            "Bambu upload was not authorized. Check the LAN access code; on current secured firmware, enable " +
                "Developer Mode in the printer's LAN-only settings and refresh the access code."
        error is BambuFtpsPhaseFailure && error.phase == "data_tls_handshake" ->
            "The Bambu secure upload session could not be established. Reconnect to the printer and retry. " +
                "Technical details were saved in diagnostics."
        error is BambuFtpsPhaseFailure && error.phase == "data_write" ->
            "The Bambu upload connection was interrupted after ${error.transferredBytes} of " +
                "${error.expectedBytes} bytes. Retry the upload; technical details were saved in diagnostics."
        error is java.net.SocketTimeoutException ->
            "Bambu upload timed out. Check that the printer is awake and reachable on your local network."
        error is kotlinx.coroutines.TimeoutCancellationException ->
            "Bambu upload timed out. Check that the printer is awake and reachable on your local network."
        else -> error.message ?: "Bambu upload failed"
    }

    private fun isAuthorizationFailure(error: Exception): Boolean {
        val message = error.message?.lowercase().orEmpty()
        return message.contains("ftps 530") ||
            message.contains("ftps 532") ||
            message.contains("not authorized") ||
            message.contains("permission denied") ||
            message.contains("login incorrect")
    }
}

internal object BambuFtpsProtocol {
    private val PASV_REGEX = Regex("""\((\d+),(\d+),(\d+),(\d+),(\d+),(\d+)\)""")

    fun resolvePassiveEndpoint(controlHost: String, reply: String): Pair<String, Int> {
        val match = PASV_REGEX.find(reply)
            ?: throw IllegalStateException("Could not parse PASV reply: $reply")
        val (_, _, _, _, p1, p2) = match.destructured
        val port = p1.toInt() * 256 + p2.toInt()
        return controlHost to port
    }

    fun candidateDataModes(
        model: BambuModel,
        preferred: BambuFtpsDataMode? = null,
    ): List<BambuFtpsDataMode> {
        val candidates = when (model) {
        // Preserve the BambuStudio-compatible A-series order. The original
        // implementation uses a protected passive data socket first and only
        // falls back to a clear socket when the printer rejects the protected
        // channel. A-series has its own legacy session implementation below;
        // this ordering is part of the working v3.3.9 wire contract.
        BambuModel.A1, BambuModel.A1_MINI -> listOf(
            BambuFtpsDataMode.PROTECTED,
            BambuFtpsDataMode.CLEAR,
        )
        else -> listOf(BambuFtpsDataMode.PROTECTED)
        }
        return if (preferred != null && preferred in candidates) {
            listOf(preferred) + candidates.filterNot { it == preferred }
        } else {
            candidates
        }
    }

    internal fun usesLegacyASeriesSession(model: BambuModel): Boolean =
        model == BambuModel.A1 || model == BambuModel.A1_MINI

    /**
     * Bambuddy's lower-bound transfer model: give even a small upload ten
     * minutes, and budget larger archives at a pessimistic 25 KiB/s.
     */
    fun uploadDeadlineMillis(fileSizeBytes: Long): Long {
        val transferMillis = if (fileSizeBytes <= 0L) {
            0L
        } else {
            (fileSizeBytes * 1_000L + MIN_BYTES_PER_SECOND - 1L) / MIN_BYTES_PER_SECOND
        }
        return maxOf(MIN_UPLOAD_DEADLINE_MS, transferMillis)
    }

    private const val MIN_BYTES_PER_SECOND = 25L * 1024L
    private const val MIN_UPLOAD_DEADLINE_MS = 600_000L
}

/**
 * The A1/A1 Mini FTP server uses the original BambuStudio-compatible path.
 * Keep it separate from the H2D session-reuse implementation: these printers
 * are sensitive to the layered implicit-FTPS control socket and data-transfer
 * lifecycle. The protected data attempt is the proven v3.3.9 default, with a
 * clear-channel fallback for firmware variants that require it.
 */
private class BambuLegacyASeriesFtpsSession(
    private val config: BambuConfig,
    private val sslSocketFactory: SSLSocketFactory,
) : BambuFtpsSessionHandle {
    // Keep the old BambuStudio-compatible layered implicit-FTPS socket. The
    // direct createSocket(host, port) form looks equivalent, but A-series
    // firmware accepts the layered socket and can stall after STOR when the
    // direct form is used (the regression observed on A1 Mini).
    private val controlSocket: SSLSocket = createTlsSocket(config.ip, IMPLICIT_FTPS_PORT)
    private val reader = BufferedReader(InputStreamReader(controlSocket.inputStream, StandardCharsets.US_ASCII))
    private val writer = BufferedWriter(OutputStreamWriter(controlSocket.outputStream, StandardCharsets.US_ASCII))

    override fun upload(file: File, remoteName: String, dataMode: BambuFtpsDataMode) {
        val clearData = dataMode == BambuFtpsDataMode.CLEAR
        val expectedBytes = file.length()
        expectReply(readReply(), setOf(220))
        login()
        sendCommand("PBSZ 0", setOf(200))
        sendCommand("PROT ${if (clearData) "C" else "P"}", setOf(200))
        sendCommand("TYPE I", setOf(200))

        val passiveReply = try {
            sendCommand("PASV", setOf(227))
        } catch (error: Exception) {
            throw BambuFtpsPhaseFailure("pasv", expectedBytes, 0L, error)
        }
        val (host, port) = BambuFtpsProtocol.resolvePassiveEndpoint(config.ip, passiveReply.message)
        Log.i("BambuFtpsClient", "A-series PASV host=$host port=$port remote=$remoteName")
        val dataSocket = try {
            createDataSocket(host, port, clearData)
        } catch (error: Exception) {
            throw BambuFtpsPhaseFailure("passive_connect", expectedBytes, 0L, error)
        }
        Log.i("BambuFtpsClient", "A-series passive connected remote=$remoteName")

        var transferredBytes = 0L
        try {
            try {
                sendCommand("STOR $remoteName", setOf(125, 150))
            } catch (error: Exception) {
                throw BambuFtpsPhaseFailure("stor_command", expectedBytes, 0L, error)
            }
            Log.i("BambuFtpsClient", "A-series STOR accepted remote=$remoteName")
            try {
                // This is deliberately the old buffered stream shape. Do not
                // replace it with direct writes or TLS layering for A-series.
                dataSocket.getOutputStream().buffered().use { output ->
                    file.inputStream().use { input ->
                        val buffer = ByteArray(COPY_BUFFER_BYTES)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                            transferredBytes += count
                        }
                    }
                    output.flush()
                }
                Log.i("BambuFtpsClient", "A-series data sent bytes=$transferredBytes remote=$remoteName")
            } catch (error: Exception) {
                throw BambuFtpsPhaseFailure("data_write", expectedBytes, transferredBytes, error)
            }
            if (transferredBytes != expectedBytes) {
                throw IOException(
                    "FTPS data transfer ended after $transferredBytes of $expectedBytes bytes",
                )
            }
            try {
                expectReply(readReply(), setOf(226, 250))
            } catch (error: Exception) {
                throw BambuFtpsPostTransferReplyFailure(expectedBytes, transferredBytes, error)
            }
        } finally {
            runCatching { dataSocket.close() }
        }
        runCatching { sendCommand("QUIT", setOf(221)) }
    }

    override fun querySize(remoteName: String, dataMode: BambuFtpsDataMode): Long? {
        val clearData = dataMode == BambuFtpsDataMode.CLEAR
        expectReply(readReply(), setOf(220))
        login()
        sendCommand("PBSZ 0", setOf(200))
        sendCommand("PROT ${if (clearData) "C" else "P"}", setOf(200))
        sendCommand("TYPE I", setOf(200))
        val reply = sendCommand("SIZE $remoteName", setOf(213))
        sendCommand("QUIT", setOf(221))
        return reply.message.substringAfter(' ', "").trim().toLongOrNull()
    }

    override fun close() {
        runCatching { if (!controlSocket.isClosed) controlSocket.close() }
    }

    private fun login() {
        val userReply = sendCommand("USER bblp", setOf(230, 331))
        if (userReply.code == 331) {
            sendCommand("PASS ${config.accessCode}", setOf(230))
        }
    }

    private fun createDataSocket(host: String, port: Int, clearData: Boolean): Socket {
        if (clearData) {
            return Socket().apply {
                soTimeout = SOCKET_TIMEOUT_MS
                connect(InetSocketAddress(host, port), SOCKET_TIMEOUT_MS)
            }
        }
        return createTlsSocket(host, port)
    }

    private fun createTlsSocket(connectHost: String, connectPort: Int): SSLSocket {
        val rawSocket = Socket().apply {
            soTimeout = SOCKET_TIMEOUT_MS
            connect(InetSocketAddress(connectHost, connectPort), SOCKET_TIMEOUT_MS)
        }
        return try {
            (sslSocketFactory.createSocket(
                rawSocket,
                config.ip,
                IMPLICIT_FTPS_PORT,
                true,
            ) as SSLSocket).apply {
                soTimeout = SOCKET_TIMEOUT_MS
                startHandshake()
            }
        } catch (error: Exception) {
            runCatching { rawSocket.close() }
            throw error
        }
    }

    private fun sendCommand(command: String, expectedCodes: Set<Int>): FtpsReply {
        writer.write(command)
        writer.write("\r\n")
        writer.flush()
        return expectReply(readReply(), expectedCodes)
    }

    private fun readReply(): FtpsReply {
        val firstLine = reader.readLine() ?: throw IllegalStateException("FTPS control channel closed")
        val code = firstLine.take(3).toIntOrNull()
            ?: throw IllegalStateException("Malformed FTPS reply: $firstLine")
        if (firstLine.length >= 4 && firstLine[3] == '-') {
            var line: String
            do {
                line = reader.readLine() ?: throw IllegalStateException("FTPS control channel closed")
            } while (!(line.startsWith(code.toString()) && line.length >= 4 && line[3] == ' '))
            return FtpsReply(code, line)
        }
        return FtpsReply(code, firstLine)
    }

    private fun expectReply(reply: FtpsReply, expectedCodes: Set<Int>): FtpsReply {
        if (reply.code !in expectedCodes) {
            throw IllegalStateException("FTPS ${reply.code}: ${reply.message}")
        }
        return reply
    }

    private data class FtpsReply(val code: Int, val message: String)

    private companion object {
        const val IMPLICIT_FTPS_PORT = 990
        const val SOCKET_TIMEOUT_MS = 15_000
        const val COPY_BUFFER_BYTES = 8 * 1024
    }
}

private class BambuFtpsSession(
    private val config: BambuConfig,
    private val sslSocketFactory: SSLSocketFactory,
) : BambuFtpsSessionHandle {
    private val controlSocket: SSLSocket by lazy(LazyThreadSafetyMode.NONE) { createControlSocket() }
    private val reader = BufferedReader(InputStreamReader(controlSocket.inputStream, StandardCharsets.US_ASCII))
    private val writer = BufferedWriter(OutputStreamWriter(controlSocket.outputStream, StandardCharsets.US_ASCII))

    override fun upload(file: File, remoteName: String, dataMode: BambuFtpsDataMode) {
        expectReply(readReply(), setOf(220))
        login()
        sendCommand("PBSZ 0", setOf(200))
        val clearData = dataMode == BambuFtpsDataMode.CLEAR
        sendCommand("PROT ${if (clearData) "C" else "P"}", setOf(200))
        sendCommand("TYPE I", setOf(200))

        val expectedBytes = file.length()
        val passiveReply = try {
            sendCommand("PASV", setOf(227))
        } catch (error: Exception) {
            throw BambuFtpsPhaseFailure("pasv", expectedBytes, 0L, error)
        }
        val (host, port) = BambuFtpsProtocol.resolvePassiveEndpoint(config.ip, passiveReply.message)
        var transferredBytes = 0L
        // Match FTP_TLS/ftplib ordering used by Bambuddy: establish passive
        // TCP first, send STOR and receive 125/150, then perform the protected
        // data-channel TLS handshake. H2D waits for STOR before speaking TLS.
        val rawDataSocket = try {
            createPassiveDataSocket(
                host = host,
                port = port,
                reuseControlSessionPort = !clearData,
            )
        } catch (error: Exception) {
            throw BambuFtpsPhaseFailure("passive_connect", expectedBytes, 0L, error)
        }
        var dataSocket: Socket? = null
        try {
            try {
                sendCommand("STOR $remoteName", setOf(125, 150))
            } catch (error: Exception) {
                throw BambuFtpsPhaseFailure("stor_command", expectedBytes, 0L, error)
            }
            val activeDataSocket = if (clearData) {
                rawDataSocket
            } else {
                try {
                    protectDataSocket(rawDataSocket)
                } catch (error: Exception) {
                    throw BambuFtpsPhaseFailure("data_tls_handshake", expectedBytes, 0L, error)
                }
            }
            dataSocket = activeDataSocket
            var dataPhase = "data_write"
            var dataCloseFailure: Exception? = null
            try {
                // Keep the legacy A-series transfer byte-for-byte compatible
                // with the path that worked before H2D FTPS session-reuse was
                // added. In particular, BufferedOutputStream is important here:
                // the A1/A1 Mini plain data service resets when every 8 KiB
                // source read is written directly to the socket. H2D and the
                // other protected models retain the explicit phased writer.
                if (clearData) {
                    dataPhase = "data_write"
                    activeDataSocket.getOutputStream().buffered().use { output ->
                        file.inputStream().use { input ->
                            input.copyTo(output)
                            transferredBytes = expectedBytes
                        }
                        dataPhase = "data_channel_flush"
                        output.flush()
                    }
                } else {
                    val output = activeDataSocket.getOutputStream()
                    file.inputStream().buffered().use { input ->
                        val buffer = ByteArray(COPY_BUFFER_BYTES)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                            transferredBytes += count
                        }
                    }
                    dataPhase = "data_channel_flush"
                    output.flush()
                    dataPhase = "data_channel_close"
                    try {
                        output.close()
                    } catch (error: Exception) {
                        // Some firmware does not complete TLS close_notify even
                        // after accepting the file. Keep reading the control reply:
                        // a confirmed 226/250 wins, but close failure plus an
                        // unconfirmed final reply must never be treated as success.
                        dataCloseFailure = error
                        Log.w("BambuFtpsClient", "FTPS data close failed before final reply: ${error.message}")
                    }
                }
            } catch (error: Exception) {
                throw BambuFtpsPhaseFailure(
                    phase = dataPhase,
                    expectedBytes = expectedBytes,
                    transferredBytes = transferredBytes,
                    cause = error,
                )
            }
            if (transferredBytes != expectedBytes) {
                throw IOException(
                    "FTPS data transfer ended after $transferredBytes of $expectedBytes bytes",
                )
            }
            try {
                expectReply(readReply(), setOf(226, 250))
            } catch (timeout: SocketTimeoutException) {
                dataCloseFailure?.let { closeFailure ->
                    closeFailure.addSuppressed(timeout)
                    throw BambuFtpsPhaseFailure(
                        phase = "data_channel_close",
                        expectedBytes = expectedBytes,
                        transferredBytes = transferredBytes,
                        cause = closeFailure,
                    )
                }
                throw BambuFtpsTransferConfirmationTimeout(
                    expectedBytes = expectedBytes,
                    transferredBytes = transferredBytes,
                    cause = timeout,
                )
            } catch (error: Exception) {
                dataCloseFailure?.let(error::addSuppressed)
                throw BambuFtpsPostTransferReplyFailure(expectedBytes, transferredBytes, error)
            }
        } finally {
            // Never let a TLS close_notify problem replace a more precise
            // transfer result or a confirmed 226/250 reply.
            runCatching { (dataSocket ?: rawDataSocket).close() }
        }
        // Once 226/250 is confirmed, a slow/missing QUIT reply cannot make the
        // already completed upload fail.
        runCatching { sendCommand("QUIT", setOf(221)) }
            .onFailure { Log.w("BambuFtpsClient", "FTPS QUIT failed after completed upload: ${it.message}") }
    }

    override fun querySize(remoteName: String, dataMode: BambuFtpsDataMode): Long? {
        expectReply(readReply(), setOf(220))
        login()
        sendCommand("PBSZ 0", setOf(200))
        val clearData = dataMode == BambuFtpsDataMode.CLEAR
        sendCommand("PROT ${if (clearData) "C" else "P"}", setOf(200))
        sendCommand("TYPE I", setOf(200))
        val reply = sendCommand("SIZE $remoteName", setOf(213))
        sendCommand("QUIT", setOf(221))
        return reply.message.substringAfter(' ', "").trim().toLongOrNull()
    }

    override fun close() {
        runCatching { if (!controlSocket.isClosed) controlSocket.close() }
    }

    private fun login() {
        val userReply = sendCommand("USER bblp", setOf(230, 331))
        if (userReply.code == 331) {
            sendCommand("PASS ${config.accessCode}", setOf(230))
        }
    }

    private fun createControlSocket(): SSLSocket {
        return createTlsSocket(config.ip, IMPLICIT_FTPS_PORT)
    }

    private fun createTlsSocket(connectHost: String, connectPort: Int): SSLSocket {
        val rawSocket = createRawSocket(connectHost, connectPort)
        return protectSocket(
            rawSocket = rawSocket,
            tlsPeerHost = connectHost,
            tlsPeerPort = connectPort,
            requireExistingSession = false,
        )
    }

    private fun createRawSocket(connectHost: String, connectPort: Int): Socket =
        Socket().apply {
            soTimeout = SOCKET_TIMEOUT_MS
            connect(InetSocketAddress(connectHost, connectPort), SOCKET_TIMEOUT_MS)
        }

    private fun createPassiveDataSocket(
        host: String,
        port: Int,
        reuseControlSessionPort: Boolean,
    ): Socket = (if (reuseControlSessionPort) {
        BambuSessionReuseSocket(IMPLICIT_FTPS_PORT)
    } else {
        Socket()
    }).apply {
            soTimeout = SOCKET_TIMEOUT_MS
            connect(InetSocketAddress(host, port), SOCKET_TIMEOUT_MS)
        }

    private fun protectDataSocket(rawSocket: Socket): SSLSocket {
        // Conscrypt keys resumable client sessions by the TLS peer host/port.
        // Use the control SSLSession's exact peer identity even though the TCP
        // connection uses the passive port, as required by H2D's reuse policy.
        val controlSession = controlSocket.session
        val controlPeerHost = controlSession.peerHost
            .takeIf { it.isNotBlank() }
            ?: config.ip
        val controlPeerPort = controlSession.peerPort
            .takeIf { it > 0 }
            ?: IMPLICIT_FTPS_PORT
        var cachedSessionCount = 0
        val cachedIds = controlSession.sessionContext.ids
        while (cachedIds.hasMoreElements()) {
            cachedIds.nextElement()
            cachedSessionCount++
        }
        BambuDiagnostics.record(
            "ftps_data_tls_prepare",
            config,
            mapOf(
                "controlPeerHostMatchesConfig" to (controlPeerHost == config.ip),
                "controlPeerPort" to controlPeerPort,
                "controlSessionValid" to controlSession.isValid,
                "controlSessionIdBytes" to controlSession.id.size,
                "cachedSessionCount" to cachedSessionCount,
            ),
        )
        return protectSocket(
            rawSocket = rawSocket,
            tlsPeerHost = controlPeerHost,
            tlsPeerPort = controlPeerPort,
            requireExistingSession = true,
        )
    }

    private fun protectSocket(
        rawSocket: Socket,
        tlsPeerHost: String,
        tlsPeerPort: Int,
        requireExistingSession: Boolean,
    ): SSLSocket {
        try {
            // H2 firmware requires the data connection to resume the control
            // connection's TLS session. Conscrypt indexes client sessions by
            // peer host and port, so both layered sockets use the control peer.
            val tlsSocket = sslSocketFactory.createSocket(
                rawSocket,
                tlsPeerHost,
                tlsPeerPort,
                true,
            ) as SSLSocket
            tlsSocket.soTimeout = SOCKET_TIMEOUT_MS
            if ("TLSv1.2" in tlsSocket.supportedProtocols) {
                // Current Bambu printer FTPS servers negotiate TLS 1.2. Capping
                // the client avoids firmware that aborts a TLS 1.3 offer.
                tlsSocket.enabledProtocols = arrayOf("TLSv1.2")
            }
            if (requireExistingSession) {
                // Bambu's protected FTP data channel rejects a fresh TLS
                // session. Python/Bambuddy can pass the control SSLSession
                // explicitly; JSSE cannot, so force Conscrypt to use the
                // cached session keyed by the control peer identity above.
                tlsSocket.enableSessionCreation = false
            }
            tlsSocket.startHandshake()
            if (requireExistingSession) {
                val controlSession = controlSocket.session
                val dataSession = tlsSocket.session
                BambuDiagnostics.record(
                    "ftps_data_tls_ready",
                    config,
                    mapOf(
                        "protocol" to dataSession.protocol,
                        "cipherSuite" to dataSession.cipherSuite,
                        "sessionIdMatchesControl" to
                            controlSession.id.contentEquals(dataSession.id),
                        "controlSessionIdBytes" to controlSession.id.size,
                        "dataSessionIdBytes" to dataSession.id.size,
                        "newSessionCreationAllowed" to tlsSocket.enableSessionCreation,
                    ),
                )
            }
            return tlsSocket
        } catch (error: Exception) {
            runCatching { rawSocket.close() }
            throw error
        }
    }

    private fun sendCommand(command: String, expectedCodes: Set<Int>): FtpsReply {
        writer.write(command)
        writer.write("\r\n")
        writer.flush()
        return expectReply(readReply(), expectedCodes)
    }

    private fun readReply(): FtpsReply {
        val firstLine = reader.readLine() ?: throw IllegalStateException("FTPS control channel closed")
        val code = firstLine.take(3).toIntOrNull()
            ?: throw IllegalStateException("Malformed FTPS reply: $firstLine")
        if (firstLine.length >= 4 && firstLine[3] == '-') {
            var line: String
            do {
                line = reader.readLine() ?: throw IllegalStateException("FTPS control channel closed")
            } while (!(line.startsWith(code.toString()) && line.length >= 4 && line[3] == ' '))
            return FtpsReply(code, line)
        }
        return FtpsReply(code, firstLine)
    }

    private fun expectReply(reply: FtpsReply, expectedCodes: Set<Int>): FtpsReply {
        if (reply.code !in expectedCodes) {
            throw IllegalStateException("FTPS ${reply.code}: ${reply.message}")
        }
        return reply
    }

    private data class FtpsReply(val code: Int, val message: String)

    /**
     * Android Conscrypt looks up a resumable session using the wrapped raw
     * socket's getPort(), not the TLS peer port supplied to createSocket().
     * Keep the real passive TCP connection and file descriptor, but report the
     * control port solely for that TLS cache lookup. This is the JSSE analogue
     * of Bambuddy/Python passing `session=self.sock.session` explicitly.
     */
    private class BambuSessionReuseSocket(
        private val sessionCachePort: Int,
    ) : Socket() {
        override fun getPort(): Int =
            if (isConnected) sessionCachePort else super.getPort()
    }

    private companion object {
        const val IMPLICIT_FTPS_PORT = 990
        const val SOCKET_TIMEOUT_MS = 60_000
        const val COPY_BUFFER_BYTES = 8 * 1024
    }
}
