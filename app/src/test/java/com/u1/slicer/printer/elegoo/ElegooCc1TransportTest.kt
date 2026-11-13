package com.u1.slicer.printer.elegoo

import com.u1.slicer.printer.TransportCommandResult
import com.u1.slicer.printer.asciiMultipartParts
import kotlinx.coroutines.runBlocking
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

class ElegooCc1TransportTest {
    @Test fun commandTimeoutReportsUncertainFailureInsteadOfCancellationOrRetry() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() { }))
        server.start()
        val transport = ElegooCc1Transport("localhost", discoverMainboard = { "board" },
            websocketUrl = server.url("/websocket").toString(), requestTimeoutMillis = 200)
        try {
            val result = transport.pauseJob()
            assertTrue(result is TransportCommandResult.Failure)
            assertTrue((result as TransportCommandResult.Failure).reason.contains("before retrying"))
            assertEquals("disconnected", transport.status.value.state)
            assertEquals(1, server.requestCount)
        } finally { transport.stop(); server.shutdown() }
    }

    @Test fun testAndPauseShareSocketAndIgnoreUnrelatedAck() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                val request = JSONObject(text).getJSONObject("Data")
                if (request.getInt("Cmd") == 0) {
                    webSocket.send("""{"Status":{"CurrentStatus":[0],"PrintInfo":{"Status":0}}}""")
                } else {
                    webSocket.send("""{"Data":{"RequestID":"unrelated","Data":{"Ack":0}}}""")
                    webSocket.send(JSONObject().put("Data", JSONObject().put("RequestID", request.getString("RequestID"))
                        .put("Data", JSONObject().put("Ack", 2))).toString())
                }
            }
        }))
        server.start()
        val transport = ElegooCc1Transport("localhost", discoverMainboard = { "board" }, websocketUrl = server.url("/websocket").toString())
        try {
            assertNull(transport.testConnection())
            assertEquals("standby", transport.status.value.state)
            assertEquals(TransportCommandResult.Failure("Printer rejected command, code 2"), transport.pauseJob())
            assertEquals(1, server.requestCount)
            assertFalse(transport.capabilities.supportsConsole)
            assertFalse(transport.capabilities.supportsHeaterControl)
        } finally { transport.stop(); server.shutdown() }
    }

    @Test fun uploadUsesChunksWithSameDigestAndNeverOpensSocketOrStarts() = runBlocking {
        val server = MockWebServer()
        repeat(2) { server.enqueue(MockResponse().setBody("""{"code":"000000"}""")) }
        server.start()
        val file = File.createTempFile("cc1upload", ".gcode")
        file.writeBytes(ByteArray(1024 * 1024 + 7) { 65 })
        val transport = ElegooCc1Transport("localhost", discoverMainboard = { "board" }, uploadUrl = server.url("/uploadFile/upload").toString())
        try {
            assertEquals(TransportCommandResult.Success, transport.uploadJob(file, "cube.gcode"))
            val first = server.takeRequest(1, TimeUnit.SECONDS)!!
            val second = server.takeRequest(1, TimeUnit.SECONDS)!!
            assertEquals("POST", first.method)
            assertEquals("/uploadFile/upload", first.path)
            val firstParts = first.asciiMultipartParts()
            val secondParts = second.asciiMultipartParts()
            val expectedDigest = MessageDigest.getInstance("MD5").digest(ByteArray(1048583) { 65 })
                .joinToString("") { "%02x".format(it) }
            listOf(firstParts, secondParts).forEach { parts ->
                assertEquals(setOf("Check", "S-File-MD5", "Offset", "Uuid", "TotalSize", "File"), parts.keys)
                assertEquals("1", parts.getValue("Check").value)
                assertEquals(expectedDigest, parts.getValue("S-File-MD5").value)
                assertEquals("1048583", parts.getValue("TotalSize").value)
                assertEquals("cube.gcode", parts.getValue("File").filename)
            }
            assertEquals("0", firstParts.getValue("Offset").value)
            assertEquals("1048576", secondParts.getValue("Offset").value)
            assertEquals("A".repeat(1048576), firstParts.getValue("File").value)
            assertEquals("AAAAAAA", secondParts.getValue("File").value)
            assertTrue(firstParts.getValue("Uuid").value.matches(Regex("[0-9a-f]{32}")))
            assertEquals(firstParts.getValue("Uuid").value, secondParts.getValue("Uuid").value)
            assertEquals(2, server.requestCount)
        } finally { transport.stop(); file.delete(); server.shutdown() }
    }

    @Test fun rejectedChunkStopsUploadAndUnsafeNameMakesNoRequests() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"code":"123456"}"""))
        server.start()
        val file = File.createTempFile("cc1upload", ".gcode")
        file.writeBytes(ByteArray(1024 * 1024 + 10))
        val transport = ElegooCc1Transport("localhost", discoverMainboard = { "board" }, uploadUrl = server.url("/uploadFile/upload").toString())
        try {
            assertTrue(transport.uploadJob(file, "../bad.gcode") is TransportCommandResult.Failure)
            assertEquals(0, server.requestCount)
            assertTrue(transport.uploadJob(file, "cube.gcode") is TransportCommandResult.Failure)
            assertEquals(1, server.requestCount)
        } finally { transport.stop(); file.delete(); server.shutdown() }
    }
}
