package com.u1.slicer.printer

import com.u1.slicer.network.PrinterStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class OctoPrintTransportTest {
    @Test
    fun `upload sends multipart file and api key without starting print`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(201).setBody("{}"))
            val file = File.createTempFile("octo", ".gcode").apply { writeText("G28\n") }
            try {
                val result = OctoPrintTransport(server.url("/").toString(), "key", OkHttpClient())
                    .uploadJob(file, "folder/cube.gcode")
                assertEquals(TransportCommandResult.Success, result)
                val request = server.takeRequest()
                assertEquals("POST", request.method)
                assertEquals("/api/files/local", request.path)
                assertEquals("key", request.getHeader("X-Api-Key"))
                assertTrue(request.getHeader("Content-Type")!!.startsWith("multipart/form-data;"))
                val body = request.body.readUtf8()
                assertTrue(body.contains("name=\"file\""))
                assertTrue(body.contains("filename=\"cube.gcode\""))
                assertTrue(body.contains("name=\"path\""))
                assertTrue(body.contains("folder"))
                assertFalse(body.contains("name=\"print\""))
            } finally { file.delete() }
        }
    }

    @Test
    fun `confirmed upload and print is the only upload shape with print true`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(201).setBody("{}"))
            val file = File.createTempFile("octo", ".gcode").apply { writeText("G28\n") }
            try {
                val transport = OctoPrintTransport(server.url("/").toString(), "key", OkHttpClient())
                assertEquals(
                    TransportCommandResult.Success,
                    transport.uploadJob(file, "cube.gcode", startAfterUpload = true),
                )
                val body = server.takeRequest().body.readUtf8()
                assertTrue(body.contains("name=\"print\""))
                assertTrue(body.contains("true"))
            } finally { file.delete() }
        }
    }

    @Test
    fun `upload maps auth and conflict failures`() = runBlocking {
        MockWebServer().use { server ->
            val file = File.createTempFile("octo", ".gcode")
            try {
                server.enqueue(MockResponse().setResponseCode(401))
                val authentication = OctoPrintTransport(server.url("/").toString(), "bad", OkHttpClient())
                    .uploadJob(file, "x.gcode")
                assertTrue(authentication is TransportCommandResult.Failure)
                assertTrue((authentication as TransportCommandResult.Failure).reason.contains("authentication"))
                server.enqueue(MockResponse().setResponseCode(409))
                val conflict = OctoPrintTransport(server.url("/").toString(), "key", OkHttpClient())
                    .uploadJob(file, "x.gcode")
                assertTrue(conflict is TransportCommandResult.Failure)
                assertTrue((conflict as TransportCommandResult.Failure).reason.contains("conflict"))
            } finally { file.delete() }
        }
    }

    @Test
    fun `job status maps state and progress`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"state":"Printing","job":{"file":{"name":"cube.gcode"},"filament":{"tool0":{"length":123.5}}},"progress":{"completion":42.5,"printTime":12}}"""))
            val transport = OctoPrintTransport(server.url("/").toString(), "key", OkHttpClient())
            val status = transport.fetchStatus()
            assertEquals(PrinterStatus("printing", .425f, "cube.gcode", 12f, 123.5f), status)
            assertEquals("/api/job", server.takeRequest().path)
        }
    }

    @Test
    fun `job status maps paused idle error and offline states`() = runBlocking {
        MockWebServer().use { server ->
            listOf("Paused" to "paused", "Operational" to "standby", "Error" to "error", "Offline" to "disconnected")
                .forEach { (octoState, expected) ->
                    server.enqueue(MockResponse().setResponseCode(200).setBody("""{"state":"$octoState"}"""))
                    val status = OctoPrintTransport(server.url("/").toString(), "key", OkHttpClient()).fetchStatus()
                    assertEquals(expected, status.state)
                }
        }
    }

    @Test
    fun `separate start selects encoded file and requests printing`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(204))
            val transport = OctoPrintTransport(server.url("/").toString(), "key", OkHttpClient())

            assertEquals(TransportCommandResult.Success, transport.startJob("jobs/my cube.gcode"))
            val request = server.takeRequest()
            assertEquals("/api/files/local/jobs/my%20cube.gcode", request.path)
            assertTrue(request.body.readUtf8().contains("\"print\":true"))
        }
    }

    @Test
    fun `test connection authenticates version request`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
            val transport = OctoPrintTransport(server.url("/").toString(), "key", OkHttpClient())

            assertEquals(null, transport.testConnection())
            assertEquals("key", server.takeRequest().getHeader("X-Api-Key"))
        }
    }
}
