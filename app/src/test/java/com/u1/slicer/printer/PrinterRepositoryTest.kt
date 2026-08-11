package com.u1.slicer.printer

import com.u1.slicer.data.BambuConfig
import com.u1.slicer.data.BambuModel
import com.u1.slicer.data.Printer
import com.u1.slicer.data.PrinterKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrinterRepositoryTest {

    @Test
    fun buildPrinterUploadFilename_appendsUniqueSuffix() {
        val name = PrinterRepository.buildPrinterUploadFilename("output.gcode", nowMillis = 1234567890L)

        assertEquals("output_1234567890.gcode", name)
    }

    @Test
    fun buildPrinterUploadFilename_sanitizesAndFallsBack() {
        val name = PrinterRepository.buildPrinterUploadFilename("My Slip/Slide #1.gcode", nowMillis = 99L)
        val fallback = PrinterRepository.buildPrinterUploadFilename("   ", nowMillis = 99L)

        assertTrue(name.startsWith("My_Slip_Slide_1_"))
        assertTrue(name.endsWith("_99.gcode"))
        assertEquals("print_99.gcode", fallback)
    }

    // --- F84: upload filename preserves model name (GitHub #138) ---

    @Test
    fun buildPrinterUploadFilename_preservesModelNameFromIssueExamples() {
        val frog = PrinterRepository.buildPrinterUploadFilename("Jumping_frog.3mf", nowMillis = 1L)
        val dragon = PrinterRepository.buildPrinterUploadFilename("Dragon Scale infinity.3mf", nowMillis = 2L)
        val benchy = PrinterRepository.buildPrinterUploadFilename("3DBenchy.stl", nowMillis = 3L)

        assertEquals("Jumping_frog_1.gcode", frog)
        assertEquals("Dragon_Scale_infinity_2.gcode", dragon)
        assertEquals("3DBenchy_3.gcode", benchy)
    }

    @Test
    fun buildBambuProjectUploadFilename_usesBambuSlicedProjectExtension() {
        val project = PrinterRepository.buildBambuProjectUploadFilename(
            "Dragon Scale infinity.3mf",
            nowMillis = 42L,
        )

        assertEquals("Dragon_Scale_infinity_42.gcode.3mf", project)
    }

    @Test
    fun buildBambuProjectUploadFilename_doesNotDuplicateGeneratedProjectSuffix() {
        val project = PrinterRepository.buildBambuProjectUploadFilename(
            "3DBenchy_100.gcode.3mf",
            nowMillis = 42L,
        )

        assertEquals("3DBenchy_100_42.gcode.3mf", project)
    }

    @Test
    fun resolveUploadBaseName_prefersModelNameWhenPresent() {
        val name = PrinterRepository.resolveUploadBaseName(
            modelName = "Jumping_frog.3mf",
            gcodeFileName = "output.gcode"
        )
        assertEquals("Jumping_frog.3mf", name)
    }

    @Test
    fun resolveUploadBaseName_fallsBackToGcodeNameWhenModelNameBlank() {
        assertEquals(
            "output.gcode",
            PrinterRepository.resolveUploadBaseName(modelName = null, gcodeFileName = "output.gcode")
        )
        assertEquals(
            "output.gcode",
            PrinterRepository.resolveUploadBaseName(modelName = "", gcodeFileName = "output.gcode")
        )
        assertEquals(
            "output.gcode",
            PrinterRepository.resolveUploadBaseName(modelName = "   ", gcodeFileName = "output.gcode")
        )
    }

    @Test
    fun resolveUploadBaseName_modelNameWithSpacesPassesThroughSanitizer() {
        // Spaces are preserved at this helper layer; buildPrinterUploadFilename
        // does the actual character sanitization. End-to-end:
        val base = PrinterRepository.resolveUploadBaseName(
            modelName = "Dragon Scale infinity.3mf",
            gcodeFileName = "output.gcode"
        )
        val full = PrinterRepository.buildPrinterUploadFilename(base, nowMillis = 42L)
        assertEquals("Dragon_Scale_infinity_42.gcode", full)
    }

    @Test
    fun shouldRecreateTransport_ignores_metadata_only_moonraker_edits() {
        val current = Printer(
            id = "moonraker-1",
            nickname = "Workshop",
            kind = PrinterKind.MOONRAKER,
            moonrakerUrl = "http://printer.local:7125",
        )
        val updated = current.copy(
            nickname = "Workshop renamed",
            extruderPresets = emptyList(),
        )

        assertFalse(PrinterRepository.shouldRecreateTransport(current, updated))
    }

    @Test
    fun shouldRecreateTransport_detects_provider_connection_changes() {
        val moonraker = Printer(
            id = "moonraker-1",
            nickname = "Workshop",
            kind = PrinterKind.MOONRAKER,
            moonrakerUrl = "http://printer.local:7125",
        )
        val moonrakerUrlChanged = moonraker.copy(moonrakerUrl = "http://other.local:7125")
        val bambu = Printer(
            id = "bambu-1",
            nickname = "P1S",
            kind = PrinterKind.BAMBU_LAN,
            bambu = BambuConfig(
                ip = "192.168.1.88",
                accessCode = "12345678",
                serial = "P1S123ABC",
                model = BambuModel.P1S,
            ),
            extruderPresets = emptyList(),
        )
        val bambuIpChanged = bambu.copy(
            bambu = bambu.bambu!!.copy(ip = "192.168.1.90")
        )

        assertTrue(PrinterRepository.shouldRecreateTransport(moonraker, moonrakerUrlChanged))
        assertTrue(PrinterRepository.shouldRecreateTransport(moonraker, bambu))
        assertTrue(PrinterRepository.shouldRecreateTransport(bambu, bambuIpChanged))
    }
}
