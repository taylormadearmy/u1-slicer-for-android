package com.u1.slicer.printer

import com.u1.slicer.data.PrintHostConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

class OctoPrintTransportTest {
    @Test fun uploadNeverSelectsOrStartsAndExplicitStartSelectsOnlyNamedFile() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(201).setBody("{}"))
        server.enqueue(MockResponse().setResponseCode(204))
        server.start()
        val file = File.createTempFile("octoupload", ".gcode")
        file.writeText("G1 X1")
        val transport = OctoPrintTransport(PrintHostConfig(server.url("/octo/").toString(), "secret"))
        try {
            assertEquals(TransportCommandResult.Success, transport.uploadJob(file, "cube.gcode"))
            val upload = server.takeRequest(1, TimeUnit.SECONDS)!!
            assertEquals("/octo/api/files/local", upload.path)
            assertEquals("secret", upload.getHeader("X-Api-Key"))
            val parts = upload.asciiMultipartParts()
            assertEquals(setOf("select", "print", "file"), parts.keys)
            assertEquals("false", parts.getValue("select").value)
            assertEquals("false", parts.getValue("print").value)
            assertEquals("cube.gcode", parts.getValue("file").filename)
            assertEquals("G1 X1", parts.getValue("file").value)
            assertEquals(1, server.requestCount)
            assertEquals(TransportCommandResult.Success, transport.startJob("cube.gcode"))
            val start = server.takeRequest(1, TimeUnit.SECONDS)!!
            assertEquals("/octo/api/files/local/cube.gcode", start.path)
            assertEquals("POST", start.method)
            val payload = JSONObject(start.body.readUtf8())
            assertEquals("select", payload.getString("command"))
            assertTrue(payload.getBoolean("print"))
        } finally { file.delete(); server.shutdown() }
    }

    @Test fun pauseResumeAndCancelHaveExplicitActions() = runBlocking {
        val server = MockWebServer()
        repeat(3) { server.enqueue(MockResponse().setResponseCode(204)) }
        server.start()
        val transport = OctoPrintTransport(PrintHostConfig(server.url("/").toString()))
        try {
            assertEquals(TransportCommandResult.Success, transport.pauseJob())
            assertEquals(TransportCommandResult.Success, transport.resumeJob())
            assertEquals(TransportCommandResult.Success, transport.cancelJob())
            val payloads = (0..2).map {
                val request = server.takeRequest(1, TimeUnit.SECONDS)!!
                assertEquals("/api/job", request.path)
                assertNull(request.getHeader("X-Api-Key"))
                JSONObject(request.body.readUtf8())
            }
            assertEquals("pause", payloads[0].getString("action"))
            assertEquals("resume", payloads[1].getString("action"))
            assertEquals("cancel", payloads[2].getString("command"))
            assertFalse(payloads[2].has("action"))
        } finally { server.shutdown() }
    }

    @Test fun redirectsAreNotFollowedAndErrorsDoNotBecomeSuccess() = runBlocking {
        val origin = MockWebServer()
        val other = MockWebServer()
        origin.start(); other.start()
        origin.enqueue(MockResponse().setResponseCode(302).setHeader("Location", other.url("/steal")))
        origin.enqueue(MockResponse().setResponseCode(403))
        val transport = OctoPrintTransport(PrintHostConfig(origin.url("/").toString(), "secret"))
        try {
            assertTrue(transport.pauseJob() is TransportCommandResult.Failure)
            assertTrue(transport.cancelJob() is TransportCommandResult.Failure)
            assertEquals(0, other.requestCount)
            assertEquals(2, origin.requestCount)
        } finally { origin.shutdown(); other.shutdown() }
    }

    @Test fun unsafeFilenameMakesNoNetworkRequest() = runBlocking {
        val server = MockWebServer()
        server.start()
        val transport = OctoPrintTransport(PrintHostConfig(server.url("/").toString()))
        try {
            listOf("../cube.gcode", "a/b.gcode", "a\\b.gcode", "a?print=true.gcode", "a.3mf", "", "x".repeat(256) + ".gcode").forEach {
                assertTrue(transport.startJob(it) is TransportCommandResult.Failure)
            }
            assertEquals(0, server.requestCount)
        } finally { server.shutdown() }
    }

    @Test fun connectionRequiresVersionIdentity() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"server":"1.10.0","api":"0.1"}"""))
        server.enqueue(MockResponse().setBody("{}"))
        server.start()
        val transport = OctoPrintTransport(PrintHostConfig(server.url("/").toString()))
        try {
            assertNull(transport.testConnection())
            assertNotNull(transport.testConnection())
        } finally { server.shutdown() }
    }

    @Test fun cancelledRequestPropagatesCancellationInsteadOfReportingFailure() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        server.start()
        val transport = OctoPrintTransport(PrintHostConfig(server.url("/").toString()))
        try {
            val request = async { transport.testConnection() }
            kotlinx.coroutines.yield()
            assertNotNull(kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { server.takeRequest(2, TimeUnit.SECONDS) })
            request.cancel()
            try { request.await(); fail("Cancellation was swallowed") }
            catch (_: CancellationException) { }
        } finally { server.shutdown() }
    }

    @Test fun statusMapsPausedProgressTemperaturesAndNullCompletion() {
        val job = JSONObject("""{"job":{"file":{"name":"cube.gcode"}},"progress":{"completion":25,"printTime":120}}""")
        val printer = JSONObject("""{"state":{"flags":{"operational":true,"paused":true}},"temperature":{"tool0":{"actual":205,"target":210},"bed":{"actual":60,"target":65}}}""")
        val status = OctoPrintTransport.parseStatus(job, printer)
        assertEquals("paused", status.state)
        assertEquals(0.25f, status.progress, 0f)
        assertEquals("cube.gcode", status.filename)
        assertEquals(205f, status.nozzleTemp, 0f)
        assertEquals(65f, status.bedTarget, 0f)
        job.getJSONObject("progress").put("completion", JSONObject.NULL)
        assertEquals(0f, OctoPrintTransport.parseStatus(job, printer).progress, 0f)
    }

    @Test fun statusDistinguishesErrorPrintingIdleAndDisconnected() {
        fun state(flags: String) = OctoPrintTransport.parseStatus(JSONObject(), JSONObject("""{"state":{"flags":$flags}}""")).state
        assertEquals("error", state("{\"error\":true}"))
        assertEquals("printing", state("{\"printing\":true}"))
        assertEquals("standby", state("{\"operational\":true}"))
        assertEquals("disconnected", state("{}"))
    }
}
