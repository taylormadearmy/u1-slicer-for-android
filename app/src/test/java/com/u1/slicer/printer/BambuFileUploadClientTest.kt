package com.u1.slicer.printer

import com.u1.slicer.data.BambuConfig
import com.u1.slicer.data.BambuModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.ArrayDeque
import kotlinx.coroutines.runBlocking

class BambuFileUploadClientTest {

    @Test
    fun `upload recovers control channel close when remote size matches`() {
        val sessions = ArrayDeque<BambuFtpsSessionHandle>().apply {
            add(FakeSession(uploadError = postTransferFailure(17L)))
            add(FakeSession(remoteSize = 17L))
        }
        val client = DefaultBambuFileUploadClient(
            sessionFactory = { _, _ -> sessions.removeFirst() },
        )
        val file = File.createTempFile("bambu-ftps", ".3mf").apply {
            writeText("1234567890abcdefg")
        }

        try {
            runBlocking { client.upload(h2dConfig(), file, "cube.3mf") }
            assertTrue(sessions.isEmpty())
        } finally {
            file.delete()
        }
    }

    @Test
    fun `upload rethrows control channel close when remote size does not match`() {
        val sessions = ArrayDeque<BambuFtpsSessionHandle>().apply {
            add(FakeSession(uploadError = postTransferFailure(17L)))
            add(FakeSession(remoteSize = 5L))
        }
        val client = DefaultBambuFileUploadClient(
            sessionFactory = { _, _ -> sessions.removeFirst() },
        )
        val file = File.createTempFile("bambu-ftps", ".3mf").apply {
            writeText("1234567890abcdefg")
        }

        try {
            val error = runCatching {
                runBlocking { client.upload(h2dConfig(), file, "cube.3mf") }
            }.exceptionOrNull()
            assertEquals("FTPS control channel closed", error?.message)
            assertTrue(sessions.isEmpty())
        } finally {
            file.delete()
        }
    }

    @Test
    fun `upload recovery only applies to known transfer completion failures`() {
        assertTrue(BambuFtpsUploadRecovery.shouldVerifyAfterFailure(
            postTransferFailure(17L),
        ))
        assertFalse(BambuFtpsUploadRecovery.shouldVerifyAfterFailure(
            IllegalStateException("FTPS 426: Failure reading network stream"),
        ))
        assertFalse(BambuFtpsUploadRecovery.shouldVerifyAfterFailure(
            java.net.SocketTimeoutException("Read timed out"),
        ))
        assertFalse(BambuFtpsUploadRecovery.shouldVerifyAfterFailure(
            IllegalStateException("FTPS 530: Login incorrect"),
        ))
    }

    @Test
    fun `socket timeout gets a useful user-facing upload message`() {
        assertEquals(
            "Bambu upload timed out. Check that the printer is awake and reachable on your local network.",
            BambuFtpsUploadFailure.describe(java.net.SocketTimeoutException("Read timed out")),
        )
    }

    @Test
    fun `ftps authorization failure explains access code and developer mode`() {
        val message = BambuFtpsUploadFailure.describe(
            IllegalStateException("FTPS 530: Login incorrect"),
        )

        assertTrue(message.contains("access code"))
        assertTrue(message.contains("Developer Mode"))
    }

    @Test
    fun `data tls failure is concise in ui while details remain diagnostic only`() {
        val error = BambuFtpsPhaseFailure(
            phase = "data_tls_handshake",
            expectedBytes = 17L,
            transferredBytes = 0L,
            cause = javax.net.ssl.SSLException("SESSION_MAY_NOT_BE_CREATED ssl=0x1234"),
        )

        val message = BambuFtpsUploadFailure.describe(error)
        assertTrue(message.contains("secure upload session"))
        assertTrue(message.contains("diagnostics"))
        assertFalse(message.contains("SESSION_MAY_NOT_BE_CREATED"))
        assertFalse(message.contains("0x1234"))
    }

    @Test
    fun `passive endpoint uses control host rather than server-reported host`() {
        val endpoint = BambuFtpsProtocol.resolvePassiveEndpoint(
            controlHost = "192.168.1.88",
            reply = "227 Entering Passive Mode (10,0,0,5,195,80)",
        )

        assertEquals("192.168.1.88", endpoint.first)
        assertEquals(50000, endpoint.second)
    }

    @Test
    fun `a series keeps the bambustudio protected then clear fallback order`() {
        assertEquals(
            listOf(BambuFtpsDataMode.PROTECTED, BambuFtpsDataMode.CLEAR),
            BambuFtpsProtocol.candidateDataModes(BambuModel.A1_MINI),
        )
        assertEquals(
            listOf(BambuFtpsDataMode.PROTECTED),
            BambuFtpsProtocol.candidateDataModes(BambuModel.H2D),
        )
    }

    @Test
    fun `a series uses isolated legacy ftp session while h2d keeps protected session`() {
        assertTrue(BambuFtpsProtocol.usesLegacyASeriesSession(BambuModel.A1))
        assertTrue(BambuFtpsProtocol.usesLegacyASeriesSession(BambuModel.A1_MINI))
        assertFalse(BambuFtpsProtocol.usesLegacyASeriesSession(BambuModel.H2D))
        assertFalse(BambuFtpsProtocol.usesLegacyASeriesSession(BambuModel.X1C))
    }

    @Test
    fun `a series legacy session keeps layered implicit tls wire contract`() {
        val source = listOf(
            File("app/src/main/java/com/u1/slicer/printer/BambuFileUploadClient.kt"),
            File("../app/src/main/java/com/u1/slicer/printer/BambuFileUploadClient.kt"),
            File("src/main/java/com/u1/slicer/printer/BambuFileUploadClient.kt"),
        ).first { it.exists() }.readText()
        val legacy = source.substringAfter("private class BambuLegacyASeriesFtpsSession(")
        assertTrue(legacy.contains("sslSocketFactory.createSocket("))
        assertTrue(legacy.contains("rawSocket"))
        assertTrue(legacy.contains("createDataSocket(host, port, clearData)"))
        assertTrue(legacy.contains("PROT ${'$'}{if (clearData) \"C\" else \"P\"}"))
    }

    @Test
    fun `upload deadline matches bambuddy slow transfer floor`() {
        assertEquals(600_000L, BambuFtpsProtocol.uploadDeadlineMillis(1L))
        assertEquals(600_000L, BambuFtpsProtocol.uploadDeadlineMillis(10L * 1024L * 1024L))
        assertEquals(819_200L, BambuFtpsProtocol.uploadDeadlineMillis(20L * 1024L * 1024L))
    }

    @Test
    fun `each upload attempt gets a fresh tls context for data session reuse`() {
        var providerCalls = 0
        val client = DefaultBambuFileUploadClient(
            sslSocketFactoryProvider = {
                providerCalls++
                javax.net.ssl.SSLSocketFactory.getDefault() as javax.net.ssl.SSLSocketFactory
            },
            sessionFactory = { _, _ -> FakeSession() },
        )
        val file = File.createTempFile("bambu-ftps", ".3mf").apply { writeText("project") }

        try {
            runBlocking { client.upload(h2dConfig(), file, "first.3mf") }
            runBlocking { client.upload(h2dConfig(), file, "second.3mf") }
            assertEquals(2, providerCalls)
        } finally {
            file.delete()
        }
    }

    @Test
    fun `a series uses and caches the successful protected mode`() {
        val firstProtected = FakeSession()
        val secondProtected = FakeSession()
        val sessions = ArrayDeque<BambuFtpsSessionHandle>().apply {
            add(firstProtected)
            add(secondProtected)
        }
        val client = DefaultBambuFileUploadClient(
            sessionFactory = { _, _ -> sessions.removeFirst() },
        )
        val file = File.createTempFile("bambu-ftps", ".3mf").apply { writeText("project") }

        try {
            runBlocking { client.upload(validConfig(), file, "first.3mf") }
            runBlocking { client.upload(validConfig(), file, "second.3mf") }

            assertEquals(listOf(BambuFtpsDataMode.PROTECTED), firstProtected.uploadModes)
            assertEquals(listOf(BambuFtpsDataMode.PROTECTED), secondProtected.uploadModes)
            assertTrue(sessions.isEmpty())
        } finally {
            file.delete()
        }
    }

    @Test
    fun `a series retries clear after protected transfer failure`() {
        val first = FakeSession(
            uploadError = postTransferFailure(17L),
        )
        val sizeProbe = FakeSession(remoteSize = 0L)
        val fallback = FakeSession()
        val sessions = ArrayDeque<BambuFtpsSessionHandle>().apply {
            add(first)
            add(sizeProbe)
            add(fallback)
        }
        val client = DefaultBambuFileUploadClient(
            sessionFactory = { _, _ -> sessions.removeFirst() },
        )
        val file = File.createTempFile("bambu-ftps", ".3mf").apply {
            writeText("1234567890abcdefg")
        }

        try {
            runBlocking { client.upload(validConfig(), file, "cube.3mf") }
            assertEquals(listOf(BambuFtpsDataMode.PROTECTED), first.uploadModes)
            assertEquals(listOf(BambuFtpsDataMode.PROTECTED), sizeProbe.queryModes)
            assertEquals(listOf(BambuFtpsDataMode.CLEAR), fallback.uploadModes)
            assertTrue(sessions.isEmpty())
        } finally {
            file.delete()
        }
    }

    @Test
    fun `post transfer reply timeout succeeds only after every byte was sent`() {
        val sessions = ArrayDeque<BambuFtpsSessionHandle>().apply {
            add(FakeSession(uploadError = confirmationTimeout(
                expected = 17L,
                transferred = 17L,
            )))
        }
        val client = DefaultBambuFileUploadClient(
            sessionFactory = { _, _ -> sessions.removeFirst() },
        )
        val file = File.createTempFile("bambu-ftps", ".3mf").apply {
            writeText("1234567890abcdefg")
        }

        try {
            runBlocking { client.upload(h2dConfig(), file, "cube.3mf") }
            assertTrue("No SIZE retry should be needed for the scoped 226 timeout", sessions.isEmpty())
        } finally {
            file.delete()
        }
    }

    @Test
    fun `partial transfer confirmation timeout is never accepted`() {
        val timeout = confirmationTimeout(expected = 17L, transferred = 16L)
        val sessions = ArrayDeque<BambuFtpsSessionHandle>().apply {
            add(FakeSession(uploadError = timeout))
        }
        val client = DefaultBambuFileUploadClient(
            sessionFactory = { _, _ -> sessions.removeFirst() },
        )
        val file = File.createTempFile("bambu-ftps", ".3mf").apply {
            writeText("1234567890abcdefg")
        }

        try {
            val error = runCatching {
                runBlocking { client.upload(h2dConfig(), file, "cube.3mf") }
            }.exceptionOrNull()
            assertTrue(error is BambuFtpsTransferConfirmationTimeout)
            assertTrue(sessions.isEmpty())
        } finally {
            file.delete()
        }
    }

    @Test
    fun `generic h2d socket timeout cannot be masked by a stale same sized remote file`() {
        val sessions = ArrayDeque<BambuFtpsSessionHandle>().apply {
            add(FakeSession(uploadError = java.net.SocketTimeoutException("Read timed out")))
            add(FakeSession(remoteSize = 17L))
        }
        val client = DefaultBambuFileUploadClient(
            sessionFactory = { _, _ -> sessions.removeFirst() },
        )
        val file = File.createTempFile("bambu-ftps", ".3mf").apply {
            writeText("1234567890abcdefg")
        }

        try {
            val error = runCatching {
                runBlocking { client.upload(h2dConfig(), file, "cube.3mf") }
            }.exceptionOrNull()
            assertTrue(error is java.net.SocketTimeoutException)
            assertEquals("Generic timeouts must not trigger SIZE recovery", 1, sessions.size)
        } finally {
            file.delete()
        }
    }

    @Test
    fun `protected passive socket sends stor before data tls handshake`() {
        val source = listOf(
            File("app/src/main/java/com/u1/slicer/printer/BambuFileUploadClient.kt"),
            File("../app/src/main/java/com/u1/slicer/printer/BambuFileUploadClient.kt"),
            File("src/main/java/com/u1/slicer/printer/BambuFileUploadClient.kt"),
        ).first { it.exists() }.readText()
        val uploadBody = source.substringAfter("private class BambuFtpsSession(").substringAfter(
            "override fun upload(file: File, remoteName: String, dataMode: BambuFtpsDataMode)",
        ).substringBefore("override fun querySize")

        val passiveConnect = uploadBody.indexOf("createPassiveDataSocket(")
        val stor = uploadBody.indexOf("sendCommand(\"STOR \$remoteName\"")
        val tlsHandshake = uploadBody.indexOf("protectDataSocket(rawDataSocket)")
        assertTrue(passiveConnect >= 0)
        assertTrue("STOR must follow passive TCP connect", stor > passiveConnect)
        assertTrue("Protected data TLS must follow the accepted STOR command", tlsHandshake > stor)
        assertTrue(
            "Protected data TLS must require reuse of the cached control session",
            source.contains("tlsSocket.enableSessionCreation = false") &&
                source.contains("class BambuSessionReuseSocket") &&
                source.contains("if (isConnected) sessionCachePort else super.getPort()"),
        )
    }

    @Test
    fun `phased pre transfer timeout exposes stage and cannot use size recovery`() {
        val error = BambuFtpsPhaseFailure(
            phase = "data_tls_handshake",
            expectedBytes = 17L,
            transferredBytes = 0L,
            cause = java.net.SocketTimeoutException("Read timed out"),
        )

        assertEquals("data_tls_handshake", BambuFtpsUploadRecovery.failurePhase(error))
        assertEquals(0L, BambuFtpsUploadRecovery.transferredBytes(error))
        assertFalse(BambuFtpsUploadRecovery.shouldVerifyAfterFailure(error))
    }

    @Test
    fun `upload uses immutable snapshot when source changes after transfer starts`() {
        val file = File.createTempFile("bambu-ftps", ".3mf").apply { writeText("complete-project") }
        var uploadedText: String? = null
        var uploadedOriginalFile = true
        val session = FakeSession(
            onUpload = { uploadFile ->
                uploadedOriginalFile = uploadFile.canonicalFile == file.canonicalFile
                file.writeText("replacement-project")
                uploadedText = uploadFile.readText()
            },
        )
        val client = DefaultBambuFileUploadClient(sessionFactory = { _, _ -> session })

        try {
            runBlocking { client.upload(h2dConfig(), file, "cube.3mf") }
            assertFalse(uploadedOriginalFile)
            assertEquals("complete-project", uploadedText)
        } finally {
            file.delete()
        }
    }

    private fun validConfig(): BambuConfig = BambuConfig(
        ip = "192.168.1.88",
        accessCode = "12345678",
        serial = "A1M123456789012",
        model = BambuModel.A1_MINI,
    )

    private fun h2dConfig(): BambuConfig = validConfig().copy(model = BambuModel.H2D)

    private fun postTransferFailure(bytes: Long): BambuFtpsPostTransferReplyFailure =
        BambuFtpsPostTransferReplyFailure(
            expectedBytes = bytes,
            transferredBytes = bytes,
            cause = IllegalStateException("FTPS control channel closed"),
        )

    private fun confirmationTimeout(
        expected: Long,
        transferred: Long,
    ): BambuFtpsTransferConfirmationTimeout = BambuFtpsTransferConfirmationTimeout(
        expectedBytes = expected,
        transferredBytes = transferred,
        cause = java.net.SocketTimeoutException("Read timed out"),
    )

    private class FakeSession(
        private val uploadError: Exception? = null,
        private val remoteSize: Long? = null,
        private val onUpload: ((File) -> Unit)? = null,
    ) : BambuFtpsSessionHandle {
        val uploadModes = mutableListOf<BambuFtpsDataMode>()
        val queryModes = mutableListOf<BambuFtpsDataMode>()

        override fun upload(file: File, remoteName: String, dataMode: BambuFtpsDataMode) {
            uploadModes += dataMode
            onUpload?.invoke(file)
            uploadError?.let { throw it }
        }

        override fun querySize(remoteName: String, dataMode: BambuFtpsDataMode): Long? {
            queryModes += dataMode
            return remoteSize
        }

        override fun close() = Unit
    }
}
