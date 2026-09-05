package com.u1.slicer.printer

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PrusaLinkTransportTest {
    @Test
    fun `upload uses v1 put shape and API key without starting print`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(201))
            val file = File.createTempFile("prusa", ".gcode").apply { writeText("G28\n") }
            try {
                val result = PrusaLinkTransport(
                    server.url("/").toString(),
                    apiKey = "api",
                    client = OkHttpClient(),
                ).uploadJob(file, "jobs/a file.gcode")

                assertEquals(TransportCommandResult.Success, result)
                val request = server.takeRequest()
                assertEquals("PUT", request.method)
                assertEquals("/api/v1/files/usb/jobs/a%20file.gcode", request.path)
                assertEquals("text/x.gcode", request.getHeader("Content-Type"))
                assertEquals("?1", request.getHeader("Overwrite"))
                assertEquals("api", request.getHeader("X-Api-Key"))
                assertNull(request.getHeader("Print-After-Upload"))
                assertEquals("G28\n", request.body.readUtf8())
            } finally {
                file.delete()
            }
        }
    }

    @Test
    fun `confirmed upload is the only shape with Print-After-Upload`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(201))
            val file = File.createTempFile("prusa", ".gcode")
            try {
                val transport = PrusaLinkTransport(server.url("/").toString(), apiKey = "api", client = OkHttpClient())
                assertEquals(
                    TransportCommandResult.Success,
                    transport.uploadJob(file, "cube.gcode", startAfterUpload = true),
                )
                assertEquals("?1", server.takeRequest().getHeader("Print-After-Upload"))
            } finally {
                file.delete()
            }
        }
    }

    @Test
    fun `digest challenge retries the same request with authorization`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setResponseCode(401).setHeader(
                    "WWW-Authenticate",
                    "Digest realm=\"Printer API\", nonce=\"abc\", qop=\"auth\", opaque=\"opaque-value\"",
                ),
            )
            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"printer":{"state":"IDLE"}}"""))
            val transport = PrusaLinkTransport(
                server.url("/").toString(),
                username = "maker",
                password = "secret",
                client = OkHttpClient(),
            )

            assertEquals("standby", transport.fetchStatus().state)
            assertNull(server.takeRequest().getHeader("Authorization"))
            val authorization = requireNotNull(server.takeRequest().getHeader("Authorization"))
            assertTrue(authorization.startsWith("Digest "))
            assertTrue(authorization.contains("username=\"maker\""))
            assertTrue(authorization.contains("uri=\"/api/v1/status\""))
            assertTrue(authorization.contains("response=\""))
        }
    }

    @Test
    fun `upload maps authentication and conflict errors`() = runBlocking {
        MockWebServer().use { server ->
            val file = File.createTempFile("prusa", ".gcode")
            try {
                server.enqueue(MockResponse().setResponseCode(401))
                val authentication = PrusaLinkTransport(server.url("/").toString(), apiKey = "bad", client = OkHttpClient())
                    .uploadJob(file, "cube.gcode")
                assertTrue(authentication is TransportCommandResult.Failure)
                assertTrue((authentication as TransportCommandResult.Failure).reason.contains("authentication"))

                server.enqueue(MockResponse().setResponseCode(409))
                val conflict = PrusaLinkTransport(server.url("/").toString(), apiKey = "api", client = OkHttpClient())
                    .uploadJob(file, "cube.gcode")
                assertTrue(conflict is TransportCommandResult.Failure)
                assertTrue((conflict as TransportCommandResult.Failure).reason.contains("conflict"))
            } finally {
                file.delete()
            }
        }
    }

    @Test
    fun `status maps official v1 telemetry fields`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """{"printer":{"state":"PRINTING","temp_nozzle":214.9,"target_nozzle":215.0,"temp_bed":59.5,"target_bed":60.0},"job":{"progress":42.0,"time_printing":526,"file":{"display_name":"cube.gcode"}}}""",
                ),
            )
            val status = PrusaLinkTransport(server.url("/").toString(), apiKey = "api", client = OkHttpClient())
                .fetchStatus()

            assertEquals("printing", status.state)
            assertEquals(0.42f, status.progress, 0.0001f)
            assertEquals("cube.gcode", status.filename)
            assertEquals(526f, status.printDuration)
            assertEquals(214.9f, status.nozzleTemp, 0.01f)
            assertEquals(215f, status.nozzleTarget, 0.01f)
            assertEquals(59.5f, status.bedTemp, 0.01f)
            assertEquals(60f, status.bedTarget, 0.01f)
            assertEquals("/api/v1/status", server.takeRequest().path)
        }
    }

    @Test
    fun `status maps terminal and fault states`() = runBlocking {
        MockWebServer().use { server ->
            listOf(
                "PAUSED" to "paused",
                "FINISHED" to "complete",
                "STOPPED" to "cancelled",
                "ERROR" to "error",
                "ATTENTION" to "error",
                "READY" to "standby",
            ).forEach { (prusaState, expected) ->
                server.enqueue(MockResponse().setResponseCode(200).setBody("""{"printer":{"state":"$prusaState"}}"""))
                val status = PrusaLinkTransport(server.url("/").toString(), apiKey = "api", client = OkHttpClient())
                    .fetchStatus()
                assertEquals(expected, status.state)
            }
        }
    }
}
