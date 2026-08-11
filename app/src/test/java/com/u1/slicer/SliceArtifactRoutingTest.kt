package com.u1.slicer

import com.u1.slicer.slice.SliceArtifact
import com.u1.slicer.slice.SlicerTarget
import com.u1.slicer.slice.buildSliceArtifact
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipFile

class SliceArtifactRoutingTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `every single nozzle bambu target produces executable project artifact`() {
        val expectedIds = mapOf(
            SlicerTarget.BambuX1C to "BL-P001",
            SlicerTarget.BambuX1E to "C13",
            SlicerTarget.BambuP1S to "C12",
            SlicerTarget.BambuP1P to "C11",
            SlicerTarget.BambuA1 to "N2S",
            SlicerTarget.BambuA1Mini to "N1",
        )

        expectedIds.forEach { (target, modelId) ->
            val gcode = File(tmp.root, "${target.name}.gcode").apply {
                writeText("; filament used [mm] = 12\nG28\n")
            }
            val artifact = buildSliceArtifact(
                target = target,
                sourceModelName = "${target.name}.stl",
                gcodeFile = gcode,
                workingDir = tmp.root,
                plateId = 1,
                filamentColours = listOf("#FFFFFF"),
                filamentTypes = listOf("PLA"),
            ) as SliceArtifact.BambuProjectArtifact

            assertEquals(target, artifact.target)
            ZipFile(artifact.projectFile).use { zip ->
                val settings = org.json.JSONObject(
                    zip.getInputStream(zip.getEntry("Metadata/project_settings.config")).reader().readText(),
                )
                assertEquals(modelId, settings.getString("printer_model_id"))
                assertTrue(zip.getEntry("Metadata/plate_1.gcode") != null)
            }
        }
    }

    @Test
    fun `snapmaker target keeps the gcode artifact`() {
        val gcode = tmp.newFile("model.gcode").apply { writeText("G28\n") }

        val artifact = buildSliceArtifact(
            target = SlicerTarget.SnapmakerU1,
            sourceModelName = "model.3mf",
            gcodeFile = gcode,
            workingDir = tmp.root,
            plateId = 1,
            filamentColours = listOf("#FFFFFF"),
            filamentTypes = listOf("PLA"),
        )

        assertTrue(artifact is SliceArtifact.MoonrakerGcodeArtifact)
        assertEquals(gcode.absolutePath, (artifact as SliceArtifact.MoonrakerGcodeArtifact).gcodeFile.absolutePath)
    }

    @Test
    fun `a1 mini target builds a bambu project artifact`() {
        val gcode = tmp.newFile("model.gcode").apply { writeText("G28\n") }

        val artifact = buildSliceArtifact(
            target = SlicerTarget.BambuA1Mini,
            sourceModelName = "model.3mf",
            gcodeFile = gcode,
            workingDir = tmp.root,
            plateId = 1,
            filamentColours = listOf("#FFFFFF"),
            filamentTypes = listOf("PLA"),
        )

        assertTrue(artifact is SliceArtifact.BambuProjectArtifact)
        val projectFile = (artifact as SliceArtifact.BambuProjectArtifact).projectFile
        assertTrue(projectFile.name.endsWith(".gcode.3mf"))
        ZipFile(projectFile).use { zip ->
            assertTrue(zip.getEntry("Metadata/plate_1.gcode") != null)
        }
    }

    @Test
    fun `h2d target builds dual nozzle project metadata and hotend aware gcode`() {
        val gcode = tmp.newFile("h2d.gcode").apply {
            writeText(
                """
                ; CONFIG_BLOCK_START
                ; printer_model = Bambu Lab H2D
                ; CONFIG_BLOCK_END
                M620 S0A
                T0
                M621 S0A
                ;LAYER_CHANGE
                M83
                G1 X30 Y20 E1
                T1
                G1 X20 Y20 E1
                ; filament used [mm] = 100,100
                """.trimIndent(),
            )
        }

        val artifact = buildSliceArtifact(
            target = SlicerTarget.BambuH2D,
            sourceModelName = "h2d.3mf",
            gcodeFile = gcode,
            workingDir = tmp.root,
            plateId = 1,
            filamentColours = listOf("#111111", "#222222"),
            filamentTypes = listOf("PLA", "PETG"),
            filamentNozzleAssignments = listOf(2, 1),
        ) as SliceArtifact.BambuProjectArtifact

        assertEquals(SlicerTarget.BambuH2D, artifact.target)
        assertEquals(listOf(2, 1), artifact.filamentNozzleMap)
        ZipFile(artifact.projectFile).use { zip ->
            val settings = org.json.JSONObject(
                zip.getInputStream(zip.getEntry("Metadata/project_settings.config")).reader().readText(),
            )
            assertEquals("O1D", settings.getString("printer_model_id"))
            assertEquals("Bambu Lab H2D", settings.getString("printer_model"))
            assertEquals("350x320", settings.getJSONArray("printable_area").getString(2))
            assertEquals(listOf(2, 1), List(2) { settings.getJSONArray("filament_map").getInt(it) })
            assertEquals(listOf(1, 0), List(2) { settings.getJSONArray("filament_nozzle_map").getInt(it) })
            assertEquals(listOf(0, 0), List(2) { settings.getJSONArray("filament_volume_map").getInt(it) })
            assertEquals(2, settings.getJSONArray("nozzle_diameter").length())
            assertEquals("1", settings.getJSONArray("physical_extruder_map").getString(0))
            assertEquals("0", settings.getJSONArray("physical_extruder_map").getString(1))
            assertEquals("Standard#1", settings.getJSONArray("extruder_nozzle_stats").getString(0))
            assertEquals("Standard#1", settings.getJSONArray("extruder_nozzle_stats").getString(1))
            assertEquals("Direct Drive", settings.getJSONArray("extruder_type").getString(0))

            val sliceInfo = zip.getInputStream(zip.getEntry("Metadata/slice_info.config"))
                .reader().readText()
            assertTrue(sliceInfo.contains("printer_model_id\" value=\"O1D\""))
            assertTrue(sliceInfo.contains("filament_maps\" value=\"2 1\""))
            assertTrue(sliceInfo.contains("has_filament_switcher\" value=\"false\""))
            assertTrue(sliceInfo.contains("extruder_type\" value=\"0,0\""))
            assertTrue(sliceInfo.contains("enable_filament_dynamic_map\" value=\"false\""))
            assertTrue(sliceInfo.contains("group_id=\"1\""))
            assertTrue(sliceInfo.contains("group_id=\"0\""))
            assertTrue(sliceInfo.contains("<nozzle id=\"0\" extruder_id=\"1\""))
            assertTrue(sliceInfo.contains("<nozzle id=\"1\" extruder_id=\"2\""))

            val packaged = zip.getInputStream(zip.getEntry("Metadata/plate_1.gcode"))
                .reader().readText()
            assertTrue(packaged.contains("M620 S0A H1"))
            assertTrue(packaged.contains("\nT0 H1\n"))
            assertTrue(packaged.contains("\nT1 H0\n"))
        }
    }

    @Test
    fun `a1 mini artifact narrows project filament metadata to tools used in gcode header`() {
        val gcode = tmp.newFile("model.gcode").apply {
            writeText(
                """
                ; filament used [mm] = 120.0,0.0,45.0
                G28
                """.trimIndent()
            )
        }

        val artifact = buildSliceArtifact(
            target = SlicerTarget.BambuA1Mini,
            sourceModelName = "model.3mf",
            gcodeFile = gcode,
            workingDir = tmp.root,
            plateId = 1,
            filamentColours = listOf("#111111", "#222222", "#333333"),
            filamentTypes = listOf("PLA", "PETG", "ABS"),
        )

        assertTrue(artifact is SliceArtifact.BambuProjectArtifact)
        val projectFile = (artifact as SliceArtifact.BambuProjectArtifact).projectFile
        ZipFile(projectFile).use { zip ->
            val projectSettings = org.json.JSONObject(
                zip.getInputStream(zip.getEntry("Metadata/project_settings.config"))
                    .reader()
                    .readText()
            )
            assertEquals("2", projectSettings.getString("filament_count"))
            assertEquals("#111111", projectSettings.getJSONArray("filament_colour").getString(0))
            assertEquals("#333333", projectSettings.getJSONArray("filament_colour").getString(1))
            assertEquals("PLA", projectSettings.getJSONArray("filament_type").getString(0))
            assertEquals("ABS", projectSettings.getJSONArray("filament_type").getString(1))

            val modelSettings = zip.getInputStream(zip.getEntry("Metadata/model_settings.config"))
                .reader()
                .readText()
            assertTrue(modelSettings.contains("value=\"1 1\""))
        }
    }

    @Test
    fun `a1 mini artifact reads filament use from the gcode footer`() {
        val gcode = tmp.newFile("model.gcode").apply {
            writeText(buildString {
                repeat(250) { append("; generated command\n") }
                append("; filament used [mm] = 0.0,120.0,0.0,0.0\n")
            })
        }

        val artifact = buildSliceArtifact(
            target = SlicerTarget.BambuA1Mini,
            sourceModelName = "model.3mf",
            gcodeFile = gcode,
            workingDir = tmp.root,
            plateId = 1,
            filamentColours = listOf("#111111", "#222222", "#333333", "#444444"),
            filamentTypes = listOf("PLA", "PETG", "ABS", "TPU"),
        )

        val projectFile = (artifact as SliceArtifact.BambuProjectArtifact).projectFile
        ZipFile(projectFile).use { zip ->
            val projectSettings = org.json.JSONObject(
                zip.getInputStream(zip.getEntry("Metadata/project_settings.config"))
                    .reader()
                    .readText()
            )
            assertEquals("1", projectSettings.getString("filament_count"))
            assertEquals("#222222", projectSettings.getJSONArray("filament_colour").getString(0))
            assertEquals("PETG", projectSettings.getJSONArray("filament_type").getString(0))
        }
    }

    @Test
    fun `a1 mini artifact compacts sparse tool commands and footer with metadata`() {
        val gcode = tmp.newFile("sparse.gcode").apply {
            writeText(
                """
                ; CONFIG_BLOCK_START
                ; extruder_colour = #111111;#222222;#333333;#444444
                ; filament_colour = #111111;#222222;#333333;#444444
                ; filament_type = PLA;PETG;ABS;ASA
                ; filament_density = 1.1,1.2,1.3,1.4
                ; nozzle_temperature = 200,210,220,230
                ; CONFIG_BLOCK_END
                M620 S1A
                M620.11 S1 I1 E-18 F1200
                T1
                M621 S1A
                ; filament used [mm] = 0.0,120.0,0.0,0.0
                """.trimIndent()
            )
        }

        val artifact = buildSliceArtifact(
            target = SlicerTarget.BambuA1Mini,
            sourceModelName = "sparse.3mf",
            gcodeFile = gcode,
            workingDir = tmp.root,
            plateId = 1,
            filamentColours = listOf("#111111", "#222222", "#333333", "#444444"),
            filamentTypes = listOf("PLA", "PETG", "ABS", "TPU"),
        ) as SliceArtifact.BambuProjectArtifact

        assertEquals(listOf(1), artifact.sourceFilamentIndices)
        assertEquals("T1", gcode.readLines().first { it.startsWith("T") })
        ZipFile(artifact.projectFile).use { zip ->
            val packagedGcode = zip.getInputStream(zip.getEntry("Metadata/plate_1.gcode"))
                .reader()
                .readText()
            assertTrue(packagedGcode.contains("M620 S0A"))
            assertTrue(packagedGcode.contains("M620.11 S1 I0 E-18 F1200"))
            assertTrue(packagedGcode.contains("\nT0\n"))
            assertTrue(packagedGcode.contains("M621 S0A"))
            assertTrue(packagedGcode.contains("; filament used [mm] = 120.0"))
            assertFalse(packagedGcode.contains("; filament used [mm] = 0.0,120.0"))
            assertTrue(packagedGcode.contains("; filament_density = 1.2"))
            assertTrue(packagedGcode.contains("; nozzle_temperature = 210"))
            assertTrue(packagedGcode.contains("; extruder_colour = #222222"))
            assertTrue(packagedGcode.contains("; filament_colour = #222222"))
            assertTrue(packagedGcode.contains("; filament_type = PETG"))
            assertFalse(packagedGcode.contains("; filament_density = 1.1,1.2"))
            assertFalse(packagedGcode.contains("#111111;#222222"))

            val settings = org.json.JSONObject(
                zip.getInputStream(zip.getEntry("Metadata/project_settings.config"))
                    .reader()
                    .readText()
            )
            assertEquals("1", settings.getString("filament_count"))
            assertEquals("#222222", settings.getJSONArray("filament_colour").getString(0))
            assertEquals("PETG", settings.getJSONArray("filament_type").getString(0))
        }
    }

    @Test
    fun `slicer view model stores the latest typed slice artifact`() {
        val src = listOf(
            File("app/src/main/java/com/u1/slicer/SlicerViewModel.kt"),
            File("../app/src/main/java/com/u1/slicer/SlicerViewModel.kt"),
            File("src/main/java/com/u1/slicer/SlicerViewModel.kt"),
        ).first { it.exists() }.readText()

        assertTrue(src.contains("_latestSliceArtifact"))
        assertTrue(src.contains("buildSliceArtifact("))
        val packaging = src.indexOf("val preparedBambuArtifact")
        val sliceComplete = src.indexOf(
            "_state.value = SlicerState.SliceComplete(result)",
            startIndex = packaging,
        )
        assertTrue("Bambu packaging must start before SliceComplete exposes upload", packaging >= 0)
        assertTrue("SliceComplete must not precede Bambu project publication", sliceComplete > packaging)
    }
}
