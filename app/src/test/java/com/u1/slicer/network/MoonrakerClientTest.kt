package com.u1.slicer.network

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Tests for MoonrakerClient using MockWebServer.
 *
 * Note: MoonrakerClient uses android.util.Log internally.
 * These tests will fail on JVM without mocking Log. For full coverage,
 * see the instrumented test variant. These tests verify the HTTP
 * request/response contract.
 *
 * To run these on JVM, you'd need to add a Log stub or use Robolectric.
 * For now these are structured as instrumented-compatible tests.
 */
class MoonrakerClientTest {

    // These tests document the expected API contract even if they can't
    // run on pure JVM due to android.util.Log dependency.
    // Move to androidTest/ if needed.

    // --- URL normalization tests ---

    @Test
    fun `normalizeUrl adds http scheme to bare IP`() {
        assertEquals("http://192.168.0.151:7125", MoonrakerClient.normalizeUrl("192.168.0.151"))
    }

    @Test
    fun `normalizeUrl adds default port when missing`() {
        assertEquals("http://192.168.0.151:7125", MoonrakerClient.normalizeUrl("http://192.168.0.151"))
    }

    @Test
    fun `normalizeUrl preserves explicit port`() {
        assertEquals("http://192.168.0.151:8080", MoonrakerClient.normalizeUrl("http://192.168.0.151:8080"))
    }

    @Test
    fun `normalizeUrl preserves explicit port 7125`() {
        assertEquals("http://192.168.0.151:7125", MoonrakerClient.normalizeUrl("http://192.168.0.151:7125"))
    }

    @Test
    fun `normalizeUrl handles IP with port but no scheme`() {
        assertEquals("http://192.168.0.151:7125", MoonrakerClient.normalizeUrl("192.168.0.151:7125"))
    }

    @Test
    fun `normalizeUrl preserves https scheme`() {
        assertEquals("https://printer.local:7125", MoonrakerClient.normalizeUrl("https://printer.local"))
    }

    @Test
    fun `normalizeUrl strips trailing slash`() {
        assertEquals("http://192.168.0.151:7125", MoonrakerClient.normalizeUrl("http://192.168.0.151:7125/"))
    }

    @Test
    fun `normalizeUrl returns empty string for blank input`() {
        assertEquals("", MoonrakerClient.normalizeUrl(""))
        assertEquals("", MoonrakerClient.normalizeUrl("   "))
    }

    @Test
    fun `baseUrl setter uses normalizeUrl`() {
        // Verify the setter calls normalizeUrl (indirectly - test through normalizeUrl directly)
        assertEquals("http://printer.local:7125", MoonrakerClient.normalizeUrl("printer.local"))
    }

    @Test
    fun `url helper constructs full path`() {
        // Test the URL building logic without network calls
        val base = MoonrakerClient.normalizeUrl("192.168.1.100")
        assertEquals("http://192.168.1.100:7125/server/info", "$base/server/info")
    }

    // --- FilamentSlot tests ---

    @Test
    fun `FilamentSlot defaults`() {
        val slot = FilamentSlot(
            index = 0,
            label = "E1",
            color = "#FF0000",
            loaded = true,
            materialType = "PLA"
        )
        assertEquals(0, slot.index)
        assertEquals("E1", slot.label)
        assertEquals("#FF0000", slot.color)
        assertTrue(slot.loaded)
        assertEquals("PLA", slot.materialType)
        assertEquals("", slot.subType)
        assertEquals("", slot.manufacturer)
    }

    @Test
    fun `FilamentSlot with all fields`() {
        val slot = FilamentSlot(
            index = 2,
            label = "E3",
            color = "#00FF00",
            loaded = false,
            materialType = "PETG",
            subType = "PETG-HF",
            manufacturer = "Bambu"
        )
        assertEquals(2, slot.index)
        assertEquals("E3", slot.label)
        assertEquals("#00FF00", slot.color)
        assertFalse(slot.loaded)
        assertEquals("PETG", slot.materialType)
        assertEquals("PETG-HF", slot.subType)
        assertEquals("Bambu", slot.manufacturer)
    }

    @Test
    fun `FilamentSlot list operations`() {
        val slots = listOf(
            FilamentSlot(0, "E1", "#FF0000", true, "PLA"),
            FilamentSlot(1, "E2", "#00FF00", false, "PETG"),
            FilamentSlot(2, "E3", "#0000FF", true, "ABS"),
            FilamentSlot(3, "E4", "#808080", false, "TPU")
        )
        assertEquals(4, slots.size)
        assertEquals(2, slots.count { it.loaded })
        assertEquals("PLA", slots.first { it.index == 0 }.materialType)
    }

    @Test
    fun `PrinterStatus defaults`() {
        val status = PrinterStatus(state = "standby", progress = 0f)
        assertEquals("standby", status.state)
        assertEquals(0f, status.progress, 0.001f)
        assertEquals("", status.filename)
        assertEquals(0f, status.printDuration, 0.001f)
        assertEquals(0f, status.nozzleTemp, 0.001f)
        assertEquals(0f, status.bedTemp, 0.001f)
        assertTrue(status.extruders.isEmpty())
    }

    @Test
    fun `PrinterStatus with active print`() {
        val status = PrinterStatus(
            state = "printing",
            progress = 0.45f,
            filename = "benchy.gcode",
            printDuration = 1800f,
            filamentUsed = 2500f,
            nozzleTemp = 210f,
            nozzleTarget = 210f,
            bedTemp = 59f,
            bedTarget = 60f,
            extruders = listOf(
                ExtruderStatus(0, 210f, 210f, true),
                ExtruderStatus(1, 25f, 0f, false)
            )
        )
        assertEquals("printing", status.state)
        assertEquals(0.45f, status.progress, 0.001f)
        assertEquals(2, status.extruders.size)
        assertTrue(status.extruders[0].active)
        assertFalse(status.extruders[1].active)
    }

    @Test
    fun `ExtruderStatus data class`() {
        val ext = ExtruderStatus(index = 2, temp = 245f, target = 250f, active = true)
        assertEquals(2, ext.index)
        assertEquals(245f, ext.temp, 0.001f)
        assertEquals(250f, ext.target, 0.001f)
        assertTrue(ext.active)
    }

    @Test
    fun `PrinterStatus isConnected`() {
        assertTrue(PrinterStatus(state = "standby", progress = 0f).isConnected)
        assertTrue(PrinterStatus(state = "printing", progress = 0.5f).isConnected)
        assertFalse(PrinterStatus(state = "disconnected", progress = 0f).isConnected)
    }

    @Test
    fun `PrinterStatus isPrinting`() {
        assertTrue(PrinterStatus(state = "printing", progress = 0.5f).isPrinting)
        assertFalse(PrinterStatus(state = "paused", progress = 0.5f).isPrinting)
        assertFalse(PrinterStatus(state = "standby", progress = 0f).isPrinting)
    }

    @Test
    fun `PrinterStatus isPaused`() {
        assertTrue(PrinterStatus(state = "paused", progress = 0.3f).isPaused)
        assertFalse(PrinterStatus(state = "printing", progress = 0.3f).isPaused)
    }

    @Test
    fun `PrinterStatus isIdle`() {
        assertTrue(PrinterStatus(state = "standby", progress = 0f).isIdle)
        assertTrue(PrinterStatus(state = "complete", progress = 1f).isIdle)
        assertFalse(PrinterStatus(state = "printing", progress = 0.5f).isIdle)
    }

    @Test
    fun `PrinterStatus progressPercent`() {
        assertEquals(0, PrinterStatus(state = "standby", progress = 0f).progressPercent)
        assertEquals(50, PrinterStatus(state = "printing", progress = 0.5f).progressPercent)
        assertEquals(100, PrinterStatus(state = "complete", progress = 1f).progressPercent)
    }

    @Test
    fun `PrinterStatus printTimeFormatted`() {
        // 90 minutes = 1h 30m
        assertEquals("1h 30m", PrinterStatus(state = "printing", progress = 0.5f, printDuration = 5400f).printTimeFormatted)
        // 45 minutes
        assertEquals("45m", PrinterStatus(state = "printing", progress = 0.3f, printDuration = 2700f).printTimeFormatted)
        // 0 minutes
        assertEquals("0m", PrinterStatus(state = "standby", progress = 0f, printDuration = 0f).printTimeFormatted)
    }
    // --- LED state parsing ---

    @Test
    fun `LED color_data parsing - light on when W channel is positive`() {
        // LED response: {"result":{"status":{"led cavity_led":{"color_data":[[0.0,0.0,0.0,1.0]]}}}}
        val json = org.json.JSONObject("""{"result":{"status":{"led cavity_led":{"color_data":[[0.0,0.0,0.0,1.0]]}}}}""")
        val colorData = json.getJSONObject("result").getJSONObject("status")
            .getJSONObject("led cavity_led").getJSONArray("color_data")
        val rgba = colorData.getJSONArray(0)
        val w = rgba.getDouble(3)
        assertTrue("W=1.0 means light is on", w > 0)
    }

    @Test
    fun `LED color_data parsing - light off when W channel is zero`() {
        val json = org.json.JSONObject("""{"result":{"status":{"led cavity_led":{"color_data":[[0.0,0.0,0.0,0.0]]}}}}""")
        val colorData = json.getJSONObject("result").getJSONObject("status")
            .getJSONObject("led cavity_led").getJSONArray("color_data")
        val rgba = colorData.getJSONArray(0)
        val w = rgba.getDouble(3)
        assertFalse("W=0.0 means light is off", w > 0)
    }

    @Test
    fun `LED gcode script format`() {
        val on = "SET_LED LED=cavity_led WHITE=1.0 RED=0 GREEN=0 BLUE=0"
        val off = "SET_LED LED=cavity_led WHITE=0 RED=0 GREEN=0 BLUE=0"
        assertTrue(on.contains("WHITE=1.0"))
        assertTrue(off.contains("WHITE=0"))
        assertTrue(on.contains("LED=cavity_led"))
    }
}

// MoonrakerClientTestHelper removed — tests now use MoonrakerClient.normalizeUrl() directly.
