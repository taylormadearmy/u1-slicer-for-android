package com.u1.slicer.printer

import com.u1.slicer.bambu.ThreeMfInfo
import com.u1.slicer.bambu.ThreeMfObject
import com.u1.slicer.bambu.ThreeMfPlate
import com.u1.slicer.data.BambuModel
import com.u1.slicer.network.FilamentRouting
import com.u1.slicer.network.FilamentSlot
import com.u1.slicer.network.NozzleSide
import com.u1.slicer.network.NozzleHardwareStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class BambuProjectFileInspectorTest {

    @Test
    fun `describe returns sendable project when selected plate gcode exists`() {
        val file = createProjectZip(
            "selected.3mf",
            listOf(
                "3D/3dmodel.model",
                "Metadata/project_settings.config",
                "Metadata/plate_2.gcode",
            ),
        )

        try {
            val descriptor = BambuProjectFileInspector.describe(
                rawInputFile = file,
                sourceDisplayName = "Selected plate.3mf",
                selectedPlateId = 2,
                info = sampleInfo(),
            )

            assertNotNull(descriptor)
            assertEquals(file.absolutePath, descriptor?.sourceFile?.absolutePath)
            assertEquals("Selected plate.3mf", descriptor?.displayName)
            assertEquals(2, descriptor?.selectedPlateId)
            assertEquals("Metadata/plate_2.gcode", descriptor?.plateGcodeEntry)
        } finally {
            file.delete()
        }
    }

    @Test
    fun `describe falls back to first known plate when none is selected`() {
        val file = createProjectZip(
            "plate1.3mf",
            listOf(
                "3D/3dmodel.model",
                "Metadata/project_settings.config",
                "Metadata/plate_1.gcode",
            ),
        )

        try {
            val descriptor = BambuProjectFileInspector.describe(
                rawInputFile = file,
                sourceDisplayName = "",
                selectedPlateId = null,
                info = sampleInfo(),
            )

            assertNotNull(descriptor)
            assertEquals(file.name, descriptor?.displayName)
            assertEquals(1, descriptor?.selectedPlateId)
        } finally {
            file.delete()
        }
    }

    @Test
    fun `describe exposes embedded H2D nozzle map for side-aware send UX`() {
        val file = createExecutableProject(
            machineName = "Bambu Lab H2D",
            filamentIds = listOf(1, 3),
            projectFilamentCount = 4,
            filamentMap = listOf(1, 2, 1, 2),
        )

        try {
            val descriptor = BambuProjectFileInspector.describe(
                rawInputFile = file,
                sourceDisplayName = "H2D embedded.3mf",
                selectedPlateId = 1,
                info = sampleInfo(),
            )

            assertEquals(true, descriptor?.isH2D)
            assertEquals(listOf(1, 2, 1, 2), descriptor?.filamentNozzleMap)
        } finally {
            file.delete()
        }
    }

    @Test
    fun `describe returns null when selected plate gcode is missing`() {
        val file = createProjectZip(
            "missing.3mf",
            listOf(
                "3D/3dmodel.model",
                "Metadata/project_settings.config",
            ),
        )

        try {
            val descriptor = BambuProjectFileInspector.describe(
                rawInputFile = file,
                sourceDisplayName = "Missing.3mf",
                selectedPlateId = 2,
                info = sampleInfo(),
            )

            assertNull(descriptor)
        } finally {
            file.delete()
        }
    }

    @Test
    fun `has embedded plate gcode distinguishes model-only Bambu projects`() {
        val modelOnly = createProjectZip(
            "model-only.3mf",
            listOf("3D/3dmodel.model", "Metadata/project_settings.config"),
        )
        val slicedProject = createProjectZip(
            "sliced.3mf",
            listOf("3D/3dmodel.model", "Metadata/plate_3.gcode"),
        )

        try {
            assertTrue(!BambuProjectFileInspector.hasEmbeddedPlateGcode(modelOnly, sampleInfo()))
            assertTrue(BambuProjectFileInspector.hasEmbeddedPlateGcode(slicedProject, sampleInfo()))
        } finally {
            modelOnly.delete()
            slicedProject.delete()
        }
    }

    @Test
    fun `executable preflight validates checksum machine and project filament positions`() {
        val file = createExecutableProject(
            machineName = "Bambu Lab A1 mini",
            filamentIds = listOf(0, 3),
        )

        try {
            val result = BambuProjectFileInspector.validateExecutableProject(
                projectFile = file,
                plateId = 1,
                model = BambuModel.A1_MINI,
                amsMapping = listOf(0, -1, -1, 3),
            )

            assertTrue(result.isSuccess)
            assertEquals(4, result.getOrThrow().projectFilamentCount)
            assertEquals(listOf(0, -1, -1, 3), result.getOrThrow().amsMapping)
            assertEquals(gcodeMd5("Bambu Lab A1 mini"), result.getOrThrow().plateGcodeMd5)
        } finally {
            file.delete()
        }
    }

    @Test
    fun `h2d preflight pads selected plate mapping to project filament count`() {
        val file = createExecutableProject(
            machineName = "Bambu Lab H2D",
            filamentIds = listOf(0),
            projectFilamentCount = 4,
        )

        try {
            val result = BambuProjectFileInspector.validateExecutableProject(
                projectFile = file,
                plateId = 1,
                model = BambuModel.H2D,
                amsMapping = listOf(2),
            )

            assertTrue(result.isSuccess)
            assertEquals(4, result.getOrThrow().projectFilamentCount)
            assertEquals(listOf(2, -1, -1, -1), result.getOrThrow().amsMapping)
        } finally {
            file.delete()
        }
    }

    @Test
    fun `h2d preflight trusts slice routing over stale wider gcode colour header`() {
        val file = createExecutableProject(
            machineName = "Bambu Lab H2D",
            filamentIds = listOf(0, 1),
            projectFilamentCount = 4,
            filamentMap = listOf(1, 2),
        )

        try {
            val result = BambuProjectFileInspector.validateExecutableProject(
                projectFile = file,
                plateId = 1,
                model = BambuModel.H2D,
                amsMapping = listOf(0, 4),
            )

            assertTrue(result.isSuccess)
            assertEquals(2, result.getOrThrow().projectFilamentCount)
            assertEquals(listOf(0, 4), result.getOrThrow().amsMapping)
        } finally {
            file.delete()
        }
    }

    @Test
    fun `h2d preflight rejects mapping beyond declared project filaments`() {
        val file = createExecutableProject(
            machineName = "Bambu Lab H2D",
            filamentIds = listOf(0),
            projectFilamentCount = 2,
        )

        try {
            val error = BambuProjectFileInspector.validateExecutableProject(
                projectFile = file,
                plateId = 1,
                model = BambuModel.H2D,
                amsMapping = listOf(0, -1, 3),
            ).exceptionOrNull()

            assertTrue(error?.message?.contains("positions not declared by this H2D project") == true)
        } finally {
            file.delete()
        }
    }

    @Test
    fun `h2d preflight enforces left and right external spool nozzle paths`() {
        val file = createExecutableProject(
            machineName = "Bambu Lab H2D",
            filamentIds = listOf(0, 1),
            projectFilamentCount = 2,
            filamentMap = listOf(1, 2),
        )

        try {
            val valid = BambuProjectFileInspector.validateExecutableProject(
                projectFile = file,
                plateId = 1,
                model = BambuModel.H2D,
                amsMapping = listOf(254, 255),
            )
            assertTrue(valid.isSuccess)

            val error = BambuProjectFileInspector.validateExecutableProject(
                projectFile = file,
                plateId = 1,
                model = BambuModel.H2D,
                amsMapping = listOf(255, 254),
            ).exceptionOrNull()
            assertTrue(error?.message?.contains("external spool") == true)
        } finally {
            file.delete()
        }
    }

    @Test
    fun `h2d preflight rejects known wrong-side AMS and AMS-HT routes`() {
        val file = createExecutableProject(
            machineName = "Bambu Lab H2D",
            filamentIds = listOf(0, 1),
            projectFilamentCount = 2,
            filamentMap = listOf(1, 2),
        )
        val slots = listOf(
            FilamentSlot(
                index = 0,
                label = "AMS 1",
                color = "#FFFFFF",
                loaded = true,
                materialType = "PLA",
                nozzleSide = NozzleSide.RIGHT,
                routing = FilamentRouting.FIXED,
            ),
            FilamentSlot(
                index = 128,
                label = "AMS-HT 1",
                color = "#FFFFFF",
                loaded = true,
                materialType = "PLA",
                nozzleSide = NozzleSide.LEFT,
                routing = FilamentRouting.FIXED,
            ),
        )

        try {
            val wrong = BambuProjectFileInspector.validateExecutableProject(
                projectFile = file,
                plateId = 1,
                model = BambuModel.H2D,
                amsMapping = listOf(0, 128),
                filamentSlots = slots,
            ).exceptionOrNull()
            assertTrue(wrong?.message?.contains("AMS 1 feeds the right nozzle") == true)

            val valid = BambuProjectFileInspector.validateExecutableProject(
                projectFile = file,
                plateId = 1,
                model = BambuModel.H2D,
                amsMapping = listOf(128, 0),
                filamentSlots = slots,
            )
            assertTrue(valid.isSuccess)
        } finally {
            file.delete()
        }
    }

    @Test
    fun `h2d preflight leaves unknown topology compatible with older firmware`() {
        val file = createExecutableProject(
            machineName = "Bambu Lab H2D",
            filamentIds = listOf(0),
            projectFilamentCount = 1,
            filamentMap = listOf(1),
        )

        try {
            val result = BambuProjectFileInspector.validateExecutableProject(
                projectFile = file,
                plateId = 1,
                model = BambuModel.H2D,
                amsMapping = listOf(0),
                filamentSlots = listOf(FilamentSlot(0, "AMS 1", "#FFFFFF", true, "PLA")),
            )
            assertTrue(result.isSuccess)
        } finally {
            file.delete()
        }
    }

    @Test
    fun `dynamic H2D group list requires installed FTS and switchable tray`() {
        val file = createExecutableProject(
            machineName = "Bambu Lab H2D",
            filamentIds = listOf(0),
            projectFilamentCount = 1,
            sliceInfo = """<config><plate><metadata key="has_filament_switcher" value="true"/><filament id="1" group_id="0,1"/></plate></config>""",
        )

        try {
            val missing = BambuProjectFileInspector.validateExecutableProject(
                projectFile = file,
                plateId = 1,
                model = BambuModel.H2D,
                amsMapping = listOf(0),
            ).exceptionOrNull()
            assertTrue(missing?.message?.contains("did not report an installed FTS") == true)

            val fixed = BambuProjectFileInspector.validateExecutableProject(
                projectFile = file,
                plateId = 1,
                model = BambuModel.H2D,
                amsMapping = listOf(0),
                filamentSlots = listOf(FilamentSlot(0, "AMS 1", "#FFFFFF", true, "PLA")),
                filamentTrackSwitchInstalled = true,
            ).exceptionOrNull()
            assertTrue(fixed?.message?.contains("not reported as FTS-routed") == true)

            val valid = BambuProjectFileInspector.validateExecutableProject(
                projectFile = file,
                plateId = 1,
                model = BambuModel.H2D,
                amsMapping = listOf(0),
                filamentSlots = listOf(
                    FilamentSlot(
                        0,
                        "AMS 1",
                        "#FFFFFF",
                        true,
                        "PLA",
                        routing = FilamentRouting.SWITCHABLE,
                    ),
                ),
                filamentTrackSwitchInstalled = true,
            )
            assertTrue(valid.isSuccess)
        } finally {
            file.delete()
        }
    }

    @Test
    fun `executable preflight rejects compact mapping for sparse project filament ids`() {
        val file = createExecutableProject(
            machineName = "Bambu Lab A1 mini",
            filamentIds = listOf(0, 3),
        )

        try {
            val error = BambuProjectFileInspector.validateExecutableProject(
                projectFile = file,
                plateId = 1,
                model = BambuModel.A1_MINI,
                amsMapping = listOf(0, 3),
            ).exceptionOrNull()

            assertTrue(error?.message?.contains("project filament position 4") == true)
        } finally {
            file.delete()
        }
    }

    @Test
    fun `executable preflight rejects unresolved route for a filament used by the plate`() {
        val file = createExecutableProject(
            machineName = "Bambu Lab P1S",
            filamentIds = listOf(0, 3),
        )

        try {
            val error = BambuProjectFileInspector.validateExecutableProject(
                projectFile = file,
                plateId = 1,
                model = BambuModel.P1S,
                amsMapping = listOf(0, -1, -1, -1),
            ).exceptionOrNull()

            assertTrue(error?.message?.contains("Project filament 4 has no selected") == true)
        } finally {
            file.delete()
        }
    }

    @Test
    fun `executable preflight rejects invalid checksum`() {
        val file = createExecutableProject(
            machineName = "Bambu Lab A1 mini",
            filamentIds = listOf(0),
            checksum = "00000000000000000000000000000000",
        )

        try {
            val error = BambuProjectFileInspector.validateExecutableProject(
                projectFile = file,
                plateId = 1,
                model = BambuModel.A1_MINI,
                amsMapping = listOf(0),
            ).exceptionOrNull()

            assertTrue(error?.message?.contains("invalid plate 1 G-code checksum") == true)
        } finally {
            file.delete()
        }
    }

    @Test
    fun `a series does not apply the h2d undefined nozzle material guard`() {
        val file = createExecutableProject(
            machineName = "Bambu Lab A1 mini",
            filamentIds = listOf(0),
            nozzleTypes = listOf("undefine"),
        )

        try {
            val result = BambuProjectFileInspector.validateExecutableProject(
                projectFile = file,
                plateId = 1,
                model = BambuModel.A1_MINI,
                amsMapping = listOf(0),
                installedNozzles = listOf(NozzleHardwareStatus(0, 0.4f, "stainless_steel")),
            )
            assertTrue(result.isSuccess)
        } finally {
            file.delete()
        }
    }

    @Test
    fun `executable preflight blocks only a positive single nozzle diameter mismatch`() {
        val file = createExecutableProject(
            machineName = "Bambu Lab P1S",
            filamentIds = listOf(0),
            nozzleDiameters = listOf(0.6f),
        )

        try {
            val unknown = BambuProjectFileInspector.validateExecutableProject(
                projectFile = file,
                plateId = 1,
                model = BambuModel.P1S,
                amsMapping = listOf(0),
            )
            assertTrue(unknown.isSuccess)

            val mismatch = BambuProjectFileInspector.validateExecutableProject(
                projectFile = file,
                plateId = 1,
                model = BambuModel.P1S,
                amsMapping = listOf(0),
                installedNozzles = listOf(NozzleHardwareStatus(0, 0.4f, "hardened_steel")),
            ).exceptionOrNull()
            assertTrue(mismatch?.message?.contains("sliced for a 0.6mm nozzle") == true)
            assertTrue(mismatch?.message?.contains("reports a 0.4mm nozzle") == true)
        } finally {
            file.delete()
        }
    }

    @Test
    fun `h2d nozzle diameter mismatch is checked against the required side`() {
        val file = createExecutableProject(
            machineName = "Bambu Lab H2D",
            filamentIds = listOf(0, 1),
            projectFilamentCount = 2,
            filamentMap = listOf(1, 2),
            nozzleDiameters = listOf(0.4f, 0.6f),
        )

        try {
            val mismatch = BambuProjectFileInspector.validateExecutableProject(
                projectFile = file,
                plateId = 1,
                model = BambuModel.H2D,
                amsMapping = listOf(0, 4),
                installedNozzles = listOf(
                    NozzleHardwareStatus(0, 0.6f, "HH01"),
                    NozzleHardwareStatus(1, 0.6f, "HS00"),
                ),
            ).exceptionOrNull()

            assertTrue(mismatch?.message?.contains("0.4mm left nozzle") == true)
        } finally {
            file.delete()
        }
    }

    @Test
    fun `h2d preflight rejects known nozzle material mismatch but permits unknown old firmware`() {
        val file = createExecutableProject(
            machineName = "Bambu Lab H2D",
            filamentIds = listOf(0),
            projectFilamentCount = 1,
            filamentMap = listOf(2),
            nozzleDiameters = listOf(0.4f, 0.4f),
            nozzleTypes = listOf("hardened_steel", "hardened_steel"),
        )

        try {
            val mismatch = BambuProjectFileInspector.validateExecutableProject(
                projectFile = file,
                plateId = 1,
                model = BambuModel.H2D,
                amsMapping = listOf(4),
                installedNozzles = listOf(NozzleHardwareStatus(1, 0.4f, "HS00")),
            ).exceptionOrNull()
            assertTrue(mismatch?.message?.contains("hardened steel right nozzle") == true)
            assertTrue(mismatch?.message?.contains("stainless steel right nozzle") == true)

            val oldFirmware = BambuProjectFileInspector.validateExecutableProject(
                projectFile = file,
                plateId = 1,
                model = BambuModel.H2D,
                amsMapping = listOf(4),
                installedNozzles = listOf(NozzleHardwareStatus(1, 0.4f, "")),
            )
            assertTrue(oldFirmware.isSuccess)
        } finally {
            file.delete()
        }
    }

    @Test
    fun `h2d preflight rejects an explicitly undefined project nozzle material`() {
        val file = createExecutableProject(
            machineName = "Bambu Lab H2D",
            filamentIds = listOf(0),
            projectFilamentCount = 1,
            filamentMap = listOf(2),
            nozzleDiameters = listOf(0.4f, 0.4f),
            nozzleTypes = listOf("undefine"),
        )

        try {
            val error = BambuProjectFileInspector.validateExecutableProject(
                projectFile = file,
                plateId = 1,
                model = BambuModel.H2D,
                amsMapping = listOf(4),
                installedNozzles = listOf(NozzleHardwareStatus(1, 0.4f, "HS01")),
            ).exceptionOrNull()
            assertTrue(error?.message?.contains("does not declare the right nozzle material") == true)
            assertTrue(error?.message?.contains("Re-slice") == true)
        } finally {
            file.delete()
        }
    }

    @Test
    fun `h2d preflight rejects legacy single hotend tower load extrusion`() {
        val file = createExecutableProject(
            machineName = "Bambu Lab H2D",
            filamentIds = listOf(0),
            projectFilamentCount = 1,
            filamentMap = listOf(1),
            extraGcode = """
                ; CP TOOLCHANGE LOAD
                G1 X150 E63 F1300
                ; CP TOOLCHANGE WIPE
            """.trimIndent(),
        )

        try {
            val error = BambuProjectFileInspector.validateExecutableProject(
                projectFile = file,
                plateId = 1,
                model = BambuModel.H2D,
                amsMapping = listOf(0),
            ).exceptionOrNull()
            assertTrue(error?.message?.contains("unsafe single-hotend purge-tower load") == true)
            assertTrue(error?.message?.contains("Re-slice") == true)
        } finally {
            file.delete()
        }
    }

    private fun sampleInfo(): ThreeMfInfo = ThreeMfInfo(
        objects = listOf(ThreeMfObject("1", "Cube")),
        plates = listOf(
            ThreeMfPlate(1, "Plate 1", listOf("1")),
            ThreeMfPlate(2, "Plate 2", listOf("1")),
        ),
        isBambu = true,
        isMultiPlate = true,
    )

    private fun createProjectZip(name: String, entries: List<String>): File {
        val file = File.createTempFile(name.substringBeforeLast('.'), ".3mf")
        ZipOutputStream(file.outputStream().buffered()).use { zip ->
            entries.forEach { path ->
                zip.putNextEntry(ZipEntry(path))
                zip.write("x".toByteArray())
                zip.closeEntry()
            }
        }
        return file
    }

    private fun createExecutableProject(
        machineName: String,
        filamentIds: List<Int>,
        projectFilamentCount: Int? = null,
        filamentMap: List<Int>? = null,
        sliceInfo: String? = null,
        nozzleDiameters: List<Float>? = null,
        nozzleTypes: List<String>? = null,
        extraGcode: String = "",
        checksum: String = gcodeMd5(machineName, projectFilamentCount, extraGcode),
    ): File {
        val file = File.createTempFile("executable", ".3mf")
        ZipOutputStream(file.outputStream().buffered()).use { zip ->
            fun add(path: String, content: String) {
                zip.putNextEntry(ZipEntry(path))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
            add("Metadata/plate_1.gcode", gcode(machineName, projectFilamentCount, extraGcode))
            add("Metadata/plate_1.gcode.md5", checksum)
            add(
                "Metadata/plate_1.json",
                "{\"filament_ids\":${filamentIds.joinToString(prefix = "[", postfix = "]")}}",
            )
            if (nozzleDiameters != null || nozzleTypes != null) {
                val fields = buildList {
                    nozzleDiameters?.let { diameters ->
                        add("\"nozzle_diameter\":${diameters.joinToString(prefix = "[", postfix = "]")}")
                    }
                    nozzleTypes?.let { types ->
                        add("\"nozzle_type\":${types.joinToString(prefix = "[\"", postfix = "\"]", separator = "\",\"")}")
                    }
                }
                add(
                    "Metadata/project_settings.config",
                    fields.joinToString(prefix = "{", postfix = "}"),
                )
            }
            sliceInfo?.let { xml ->
                add("Metadata/slice_info.config", xml)
            } ?: filamentMap?.let { map ->
                add(
                    "Metadata/slice_info.config",
                    """<config><plate><metadata key="filament_maps" value="${map.joinToString(" ")}"/><metadata key="has_filament_switcher" value="false"/></plate></config>""",
                )
            }
        }
        return file
    }

    private fun gcode(machineName: String, projectFilamentCount: Int? = null, extraGcode: String = ""): String {
        val filamentHeader = projectFilamentCount?.let { count ->
            "; filament_colour = ${(0 until count).joinToString(";") { "#111111" }}\n"
        }.orEmpty()
        return "; printer_model = $machineName\n${filamentHeader}G28\n${extraGcode.takeIf { it.isNotBlank() }?.plus("\n").orEmpty()}"
    }

    private fun gcodeMd5(machineName: String, projectFilamentCount: Int? = null, extraGcode: String = ""): String =
        MessageDigest.getInstance("MD5")
        .digest(gcode(machineName, projectFilamentCount, extraGcode).toByteArray())
        .joinToString("") { byte -> "%02X".format(byte) }
}
