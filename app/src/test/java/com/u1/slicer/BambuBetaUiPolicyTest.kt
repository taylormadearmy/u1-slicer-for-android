package com.u1.slicer

import com.u1.slicer.data.BambuConfig
import com.u1.slicer.data.BambuModel
import com.u1.slicer.data.ExtruderPreset
import com.u1.slicer.data.Printer
import com.u1.slicer.data.PrinterKind
import com.u1.slicer.network.FilamentSlot
import com.u1.slicer.network.FilamentRouting
import com.u1.slicer.network.NozzleSide
import com.u1.slicer.printer.BambuProjectDescriptor
import com.u1.slicer.slice.SliceArtifact
import com.u1.slicer.slice.SlicerTarget
import com.u1.slicer.ui.BambuSlotNozzleRoute
import com.u1.slicer.ui.printer.canChangePrinterKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BambuBetaUiPolicyTest {

    @Test
    fun `bambu map and print requires live ams slots`() {
        assertFalse(
            canBambuMapAndPrint(
                hasProjectFile = true,
                supportsUpload = true,
                supportsStartProject = true,
                hasLiveAmsSlots = false,
            )
        )

        assertTrue(
            canBambuMapAndPrint(
                hasProjectFile = true,
                supportsUpload = true,
                supportsStartProject = true,
                hasLiveAmsSlots = true,
            )
        )
    }

    @Test
    fun `preview bambu project prefers generated slice artifact`() {
        val resolved = resolvePreviewBambuProject(
            latestSliceArtifact = SliceArtifact.BambuProjectArtifact(
                sourceModelName = "Benchy.3mf",
                projectFile = File("build/test-generated.bambu.3mf"),
                plateId = 2,
            ),
            fallbackProject = BambuProjectDescriptor(
                sourceFile = File("build/original.3mf"),
                displayName = "Original.3mf",
                selectedPlateId = 1,
                plateGcodeEntry = "Metadata/plate_1.gcode",
            ),
        )

        assertNotNull(resolved)
        assertEquals("Benchy.3mf", resolved?.displayName)
        assertEquals(2, resolved?.selectedPlateId)
        assertEquals(PreviewBambuProject.Source.GeneratedSliceArtifact, resolved?.source)
        assertEquals(com.u1.slicer.slice.SlicerTarget.BambuA1Mini, resolved?.target)
    }

    @Test
    fun `embedded preview ignores a stale generated slice artifact`() {
        val embedded = BambuProjectDescriptor(
            sourceFile = File("build/canonical-a1-mini.3mf"),
            displayName = "Canonical A1 Mini.3mf",
            selectedPlateId = 1,
            plateGcodeEntry = "Metadata/plate_1.gcode",
        )

        val resolved = resolvePreviewBambuProject(
            latestSliceArtifact = SliceArtifact.BambuProjectArtifact(
                sourceModelName = "Old Benchy.stl",
                projectFile = File("build/stale-benchy.gcode.3mf"),
                plateId = 2,
            ),
            fallbackProject = embedded,
            preferEmbeddedProject = true,
        )

        assertNotNull(resolved)
        assertEquals(embedded.sourceFile, resolved?.projectFile)
        assertEquals(embedded.displayName, resolved?.displayName)
        assertEquals(PreviewBambuProject.Source.EmbeddedGcodeProject, resolved?.source)
    }

    @Test
    fun `generated slice can only be sent to a compatible printer`() {
        val artifact = SliceArtifact.BambuProjectArtifact(
            sourceModelName = "cube.stl",
            projectFile = File("build/cube.gcode.3mf"),
            plateId = 1,
        )
        val a1Mini = Printer(
            id = "a1",
            nickname = "A1 Mini",
            kind = PrinterKind.BAMBU_LAN,
            bambu = BambuConfig("192.168.1.8", "12345678", "A1", BambuModel.A1_MINI),
        )
        val p1s = a1Mini.copy(
            id = "p1s",
            nickname = "P1S",
            bambu = a1Mini.bambu?.copy(model = BambuModel.P1S),
        )
        val u1 = Printer(
            id = "u1",
            nickname = "U1",
            kind = PrinterKind.MOONRAKER,
            moonrakerUrl = "http://printer",
        )

        assertTrue(canSendSliceArtifactToPrinter(artifact, a1Mini))
        assertFalse(canSendSliceArtifactToPrinter(artifact, p1s))
        assertFalse(canSendSliceArtifactToPrinter(artifact, u1))
        assertTrue(canSendSliceArtifactToPrinter(null, u1))
    }

    @Test
    fun `preview accepts only an embedded gcode project as the unsliced fallback`() {
        val resolved = resolvePreviewBambuProject(
            latestSliceArtifact = null,
            fallbackProject = BambuProjectDescriptor(
                sourceFile = File("build/pre-sliced.3mf"),
                displayName = "Pre-sliced.3mf",
                selectedPlateId = 3,
                plateGcodeEntry = "Metadata/plate_3.gcode",
            ),
        )

        assertNotNull(resolved)
        assertEquals(3, resolved?.selectedPlateId)
        assertEquals(PreviewBambuProject.Source.EmbeddedGcodeProject, resolved?.source)
    }

    @Test
    fun `buildBambuSlotPresets expands beyond four live slots`() {
        val printerSlots = listOf(
            FilamentSlot(index = 0, label = "AMS 1", color = "#111111", loaded = true, materialType = "PLA"),
            FilamentSlot(index = 1, label = "AMS 2", color = "#222222", loaded = true, materialType = "PETG"),
            FilamentSlot(index = 2, label = "AMS 3", color = "#333333", loaded = true, materialType = "ABS"),
            FilamentSlot(index = 3, label = "AMS 4", color = "#444444", loaded = true, materialType = "TPU"),
            FilamentSlot(index = 4, label = "AMS 5", color = "#555555", loaded = true, materialType = "PLA-CF"),
            FilamentSlot(index = 5, label = "AMS 6", color = "#666666", loaded = false, materialType = ""),
        )

        val presets = buildBambuSlotPresets(
            printerSlots = printerSlots,
            fallbackPresets = listOf(
                ExtruderPreset(index = 0, color = "#AAAAAA", materialType = "PLA"),
                ExtruderPreset(index = 1, color = "#BBBBBB", materialType = "PLA"),
                ExtruderPreset(index = 2, color = "#CCCCCC", materialType = "PLA"),
                ExtruderPreset(index = 3, color = "#DDDDDD", materialType = "PLA"),
            ),
        )

        assertEquals(6, presets.size)
        assertEquals("#555555", presets[4].color)
        assertEquals("PLA-CF", presets[4].materialType)
        assertEquals(5, presets[5].index)
        assertEquals("Empty", presets[5].materialType)
        assertEquals("AMS 5", presets[4].label)
        assertEquals("AMS 6", presets[5].label)
    }

    @Test
    fun `prepare sync exposes every loaded Bambu route including sparse and external ids`() {
        val slicerPresets = (0..3).map { index ->
            ExtruderPreset(index = index, color = "#AAAAAA", materialType = "PLA")
        }
        val printerSlots = listOf(
            FilamentSlot(index = 0, label = "AMS 1 Tray 1", color = "#111111", loaded = true, materialType = "PLA"),
            FilamentSlot(index = 4, label = "AMS 2 Tray 1", color = "#222222", loaded = true, materialType = "PETG"),
            FilamentSlot(index = 128, label = "AMS-HT 1", color = "#333333", loaded = true, materialType = "PA-CF"),
            FilamentSlot(index = 129, label = "AMS-HT 2", color = "#808080", loaded = false, materialType = "Empty"),
            FilamentSlot(index = 254, label = "External spool", color = "#444444", loaded = true, materialType = "TPU"),
        )

        val choices = buildPrepareSyncPresets(
            activePrinterKind = PrinterKind.BAMBU_LAN,
            printerSlots = printerSlots,
            slicerPresets = slicerPresets,
        )

        assertEquals(listOf(0, 4, 128, 254), choices.map { it.index })
        assertEquals(
            listOf("AMS 1 Tray 1", "AMS 2 Tray 1", "AMS-HT 1", "External spool"),
            choices.map { it.label },
        )
        assertEquals(listOf("PLA", "PETG", "PA-CF", "TPU"), choices.map { it.materialType })
    }

    @Test
    fun `prepare sync does not show four fake presets while Bambu inventory is unavailable`() {
        val choices = buildPrepareSyncPresets(
            activePrinterKind = PrinterKind.BAMBU_LAN,
            printerSlots = emptyList(),
            slicerPresets = (0..3).map(::ExtruderPreset),
        )

        assertTrue(choices.isEmpty())
    }

    @Test
    fun `prepare sync preserves fixed U1 presets`() {
        val slicerPresets = (0..3).map { index -> ExtruderPreset(index = index) }
        val unrelatedPrinterSlots = listOf(
            FilamentSlot(index = 4, label = "Unexpected", color = "#123456", loaded = true, materialType = "ABS"),
        )

        val choices = buildPrepareSyncPresets(
            activePrinterKind = PrinterKind.MOONRAKER,
            printerSlots = unrelatedPrinterSlots,
            slicerPresets = slicerPresets,
        )

        assertEquals(slicerPresets, choices)
    }

    @Test
    fun `validateBambuAmsMapping blocks empty trays before print start`() {
        val message = validateBambuAmsMapping(
            mapping = listOf(0, 1),
            extruderPresets = listOf(
                ExtruderPreset(index = 0, color = "#111111", materialType = "PLA"),
                ExtruderPreset(index = 1, color = "#222222", materialType = "Empty"),
            ),
        )

        assertEquals("AMS 2 is empty. Load filament before starting the print.", message)
        assertEquals(
            "External right is empty. Load filament before starting the print.",
            validateBambuAmsMapping(
                mapping = listOf(255),
                extruderPresets = listOf(
                    ExtruderPreset(
                        index = 255,
                        materialType = "Empty",
                        displayLabel = "External right",
                    ),
                ),
            ),
        )
        assertEquals(
            null,
            validateBambuAmsMapping(
                mapping = listOf(0),
                extruderPresets = listOf(
                    ExtruderPreset(index = 0, color = "#111111", materialType = "PLA"),
                ),
            )
        )
    }

    @Test
    fun `h2d mapping blocks a fixed wrong-side tray but permits FTS and unknown topology`() {
        val presets = listOf(
            ExtruderPreset(index = 0, materialType = "PLA", displayLabel = "Left AMS 1"),
            ExtruderPreset(index = 4, materialType = "PLA", displayLabel = "Right AMS 1"),
            ExtruderPreset(index = 8, materialType = "PLA", displayLabel = "FTS input"),
        )
        val routes = mapOf(
            0 to BambuSlotNozzleRoute(NozzleSide.LEFT),
            4 to BambuSlotNozzleRoute(NozzleSide.RIGHT),
            8 to BambuSlotNozzleRoute(NozzleSide.LEFT, switchable = true),
        )

        assertEquals(
            "Filament 1 is assigned to the left nozzle, but Right AMS 1 feeds the right nozzle.",
            validateBambuAmsMapping(
                mapping = listOf(4),
                extruderPresets = presets,
                requiredNozzleSides = listOf(NozzleSide.LEFT),
                slotNozzleRoutes = routes,
            ),
        )
        assertEquals(
            null,
            validateBambuAmsMapping(
                mapping = listOf(8),
                extruderPresets = presets,
                requiredNozzleSides = listOf(NozzleSide.RIGHT),
                slotNozzleRoutes = routes,
            ),
        )
        assertEquals(
            null,
            validateBambuAmsMapping(
                mapping = listOf(99),
                extruderPresets = presets,
                requiredNozzleSides = listOf(NozzleSide.RIGHT),
                slotNozzleRoutes = routes,
            ),
        )
    }

    @Test
    fun `live Bambu slots project side and FTS routing into send UI`() {
        val routes = buildBambuSlotNozzleRoutes(
            listOf(
                FilamentSlot(0, "Left", "#FFFFFF", true, "PLA", nozzleSide = NozzleSide.LEFT),
                FilamentSlot(
                    1,
                    "FTS",
                    "#000000",
                    true,
                    "PETG",
                    nozzleSide = NozzleSide.RIGHT,
                    routing = FilamentRouting.SWITCHABLE,
                ),
            ),
        )

        assertEquals(NozzleSide.LEFT, routes.getValue(0).side)
        assertFalse(routes.getValue(0).switchable)
        assertTrue(routes.getValue(1).switchable)
        assertEquals(
            listOf(NozzleSide.RIGHT, NozzleSide.LEFT, NozzleSide.UNKNOWN),
            h2dRequiredNozzleSides(listOf(2, 1, 0), visibleFilamentCount = 3),
        )
        assertEquals(
            listOf(NozzleSide.RIGHT, NozzleSide.RIGHT),
            h2dRequiredNozzleSides(
                filamentNozzleMap = listOf(1, 2, 1, 2),
                visibleFilamentCount = 2,
                projectFilamentIndices = listOf(1, 3),
            ),
        )
    }

    @Test
    fun `embedded project mapping preserves sparse project filament positions`() {
        assertEquals(
            listOf(0, -1, -1, 3),
            expandBambuProjectAmsMapping(
                mapping = listOf(0, 3),
                plateFileIndices = listOf(0, 3),
                source = PreviewBambuProject.Source.EmbeddedGcodeProject,
            ),
        )
    }

    @Test
    fun `generated project mapping remains compact after filament compaction`() {
        assertEquals(
            listOf(0, 3),
            expandBambuProjectAmsMapping(
                mapping = listOf(0, 3),
                plateFileIndices = listOf(0, 3),
                source = PreviewBambuProject.Source.GeneratedSliceArtifact,
            ),
        )
    }

    @Test
    fun `existing printers keep their provider kind fixed`() {
        assertTrue(canChangePrinterKind(existing = null))
        assertFalse(
            canChangePrinterKind(
                existing = Printer(
                    id = "moonraker-1",
                    nickname = "Stable U1",
                    kind = PrinterKind.MOONRAKER,
                    moonrakerUrl = "http://printer.local:7125",
                )
            )
        )
        assertFalse(
            canChangePrinterKind(
                existing = Printer(
                    id = "bambu-1",
                    nickname = "A1 Mini",
                    kind = PrinterKind.BAMBU_LAN,
                    bambu = BambuConfig(
                        ip = "192.168.1.88",
                        accessCode = "12345678",
                        serial = "A1MINI1234",
                        model = BambuModel.A1_MINI,
                    ),
                    extruderPresets = emptyList(),
                )
            )
        )
    }
}
