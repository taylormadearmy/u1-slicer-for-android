package com.u1.slicer.printer

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.u1.slicer.RequiresExternalFixture
import com.u1.slicer.data.HostType
import com.u1.slicer.data.Printer
import com.u1.slicer.data.PrintersConfig
import com.u1.slicer.data.PrintersRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Device-to-host mock proof for the Phase 6 upload safety boundary. */
@RunWith(AndroidJUnit4::class)
@RequiresExternalFixture("scripts/run-phase6-printer-host-e2e.ps1")
class PrinterHostUploadE2ETest {

    private val targetContext get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val inspectionClient = OkHttpClient()
    private lateinit var printersRepo: PrintersRepository
    private lateinit var printerRepo: PrinterRepository
    private lateinit var gcodeFile: File
    private var originalConfig: PrintersConfig? = null

    private val octoPrint = Printer(
        id = "phase6-octoprint",
        nickname = "Phase 6 OctoPrint mock",
        hostType = HostType.OCTOPRINT,
        moonrakerUrl = OCTOPRINT_URL,
        apiKey = "octo-phase6-key",
    )
    private val prusaLink = Printer(
        id = "phase6-prusalink",
        nickname = "Phase 6 PrusaLink mock",
        hostType = HostType.PRUSALINK,
        moonrakerUrl = PRUSALINK_URL,
        apiKey = "prusa-phase6-key",
    )

    @Before
    fun setUp() = runBlocking {
        val context = targetContext.applicationContext
        printersRepo = PrintersRepository(context)
        originalConfig = printersRepo.config.first()
        printerRepo = PrinterRepository(context, printersRepo, DefaultPrinterTransportFactory())
        gcodeFile = File(context.cacheDir, "phase6-host-upload-e2e.gcode").apply {
            writeText("G28\n")
        }
        clearRequests(OCTOPRINT_URL)
        clearRequests(PRUSALINK_URL)
    }

    @After
    fun tearDown() = runBlocking {
        originalConfig?.let { printersRepo.replace(it) } ?: printersRepo.clearForTest()
        if (::gcodeFile.isInitialized) gcodeFile.delete()
        Unit
    }

    @Test
    fun uploadOnlyDisablesPrintAndConfirmedOctoPrintUsesSeparateStart() = runBlocking {
        bind(octoPrint)

        assertEquals(
            TransportCommandResult.Success,
            printerRepo.uploadOnly(gcodeFile, "octoprint-upload-only.gcode"),
        )
        val octoUploadOnly = requests(OCTOPRINT_URL)
        assertEquals(1, octoUploadOnly.length())
        octoUploadOnly.getJSONObject(0).run {
            assertEquals("POST", getString("method"))
            assertEquals("/api/files/local", getString("path"))
            assertEquals("octo-phase6-key", headers().getString("X-Api-Key"))
            assertTrue(headers().getString("Content-Type").startsWith("multipart/form-data;"))
            assertTrue(getString("body").contains("name=\"file\""))
            assertTrue(getString("body").contains("name=\"print\""))
            assertTrue(getString("body").contains("\r\nfalse\r\n"))
            assertFalse(getString("body").contains("\r\ntrue\r\n"))
        }

        assertEquals(
            TransportCommandResult.Success,
            printerRepo.uploadAndPrint(gcodeFile, "octoprint-confirmed.gcode"),
        )
        val octoConfirmed = requests(OCTOPRINT_URL)
        assertEquals(3, octoConfirmed.length())
        octoConfirmed.getJSONObject(1).run {
            assertEquals("POST", getString("method"))
            assertEquals("/api/files/local", getString("path"))
            assertTrue(getString("body").contains("name=\"print\""))
            assertTrue(getString("body").contains("\r\nfalse\r\n"))
            assertFalse(getString("body").contains("\r\ntrue\r\n"))
        }
        octoConfirmed.getJSONObject(2).run {
            assertEquals("POST", getString("method"))
            val uploadedName = Regex("filename=\"([^\"]+)\"")
                .find(octoConfirmed.getJSONObject(1).getString("body"))!!.groupValues[1]
            assertEquals("/api/files/local/$uploadedName", getString("path"))
            val payload = org.json.JSONObject(getString("body"))
            assertEquals("select", payload.getString("command"))
            assertTrue(payload.getBoolean("print"))
        }

        bind(prusaLink)
        assertEquals(
            TransportCommandResult.Success,
            printerRepo.uploadOnly(gcodeFile, "prusalink-upload-only.gcode"),
        )
        val prusaUploadOnly = requests(PRUSALINK_URL)
        assertEquals(1, prusaUploadOnly.length())
        prusaUploadOnly.getJSONObject(0).run {
            assertEquals("PUT", getString("method"))
            assertTrue(getString("path").startsWith("/api/v1/files/usb/prusalink-upload-only_"))
            assertEquals("prusa-phase6-key", headers().getString("X-Api-Key"))
            assertFalse(headers().has("Print-After-Upload"))
            assertEquals("G28\n", getString("body"))
        }
    }

    @Test fun failedPreflightNeverUploadsOrStartsPrusaLink() = runBlocking {
        bind(prusaLink)
        assertTrue(printerRepo.uploadAndPrint(gcodeFile, "blocked.gcode", validateBeforeStart = { false }) is TransportCommandResult.Failure)
        assertEquals(0, requests(PRUSALINK_URL).length())
        assertEquals(TransportCommandResult.Success, printerRepo.uploadAndPrint(gcodeFile, "allowed.gcode", validateBeforeStart = { true }))
        val recorded = requests(PRUSALINK_URL)
        assertEquals(1, recorded.length())
        assertEquals("?1", recorded.getJSONObject(0).headers().getString("Print-After-Upload"))
    }

    @Test fun printerSwitchAfterOctoUploadPreventsSeparateStart() = runBlocking {
        bind(octoPrint)
        val result = printerRepo.uploadAndPrint(gcodeFile, "switch.gcode", validateBeforeStart = {
            printersRepo.replace(PrintersConfig(printers = listOf(prusaLink), activeId = prusaLink.id))
            true
        })
        assertTrue(result is TransportCommandResult.Failure)
        val recorded = requests(OCTOPRINT_URL)
        assertEquals(1, recorded.length())
        assertEquals("/api/files/local", recorded.getJSONObject(0).getString("path"))
        assertFalse(recorded.getJSONObject(0).getString("body").contains("\r\ntrue\r\n"))
        assertEquals(0, requests(PRUSALINK_URL).length())
    }

    private suspend fun bind(printer: Printer) {
        printersRepo.replace(PrintersConfig(printers = listOf(printer), activeId = printer.id))
        val deadline = System.currentTimeMillis() + 30_000L
        val expectedUrl = normalizePrintHostUrl(printer.moonrakerUrl, printer.hostType)
        while (System.currentTimeMillis() < deadline) {
            if (printerRepo.printerUrl.value == expectedUrl) return
            delay(50)
        }
        throw AssertionError("Timed out waiting for repository to bind to $expectedUrl")
    }

    private fun clearRequests(baseUrl: String) {
        val request = Request.Builder().url("$baseUrl/__requests").delete().build()
        inspectionClient.newCall(request).execute().use { response ->
            assertEquals(200, response.code)
        }
    }

    private fun requests(baseUrl: String): JSONArray {
        val request = Request.Builder().url("$baseUrl/__requests").get().build()
        inspectionClient.newCall(request).execute().use { response ->
            assertEquals(200, response.code)
            return org.json.JSONObject(response.body?.string().orEmpty()).getJSONArray("requests")
        }
    }

    private fun org.json.JSONObject.headers(): org.json.JSONObject = getJSONObject("headers")

    private companion object {
        const val OCTOPRINT_URL = "http://127.0.0.1:18080"
        const val PRUSALINK_URL = "http://127.0.0.1:18081"
    }
}
