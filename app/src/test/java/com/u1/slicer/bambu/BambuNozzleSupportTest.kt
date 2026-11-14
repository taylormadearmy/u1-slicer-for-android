package com.u1.slicer.bambu

import com.u1.slicer.data.BambuConfig
import com.u1.slicer.data.BambuModel
import com.u1.slicer.data.SliceConfig
import com.u1.slicer.slice.SlicerTarget
import com.u1.slicer.slice.buildSliceArtifact
import com.u1.slicer.slice.isCompatibleWith
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipFile

class BambuNozzleSupportTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun `saved diameters round trip and legacy printers retain 04`() {
        val legacy = JSONObject().put("ip", "192.168.1.2").put("accessCode", "test")
            .put("serial", "TEST").put("model", "P1S")
        assertEquals(0.4f, BambuConfig.fromJsonObject(legacy).nozzleDiameter)
        BambuConfig.SUPPORTED_NOZZLE_DIAMETERS.forEach { diameter ->
            val config = BambuConfig("ip", "code", "serial", BambuModel.P1S, diameter)
            assertEquals(config, BambuConfig.fromJsonObject(BambuConfig.toJsonObject(config)))
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `unsupported hardware diameter is rejected`() {
        BambuConfig("ip", "code", "serial", BambuModel.A1, 0.5f)
    }

    @Test fun `all Bambu targets derive nozzle geometry and identity from selected hardware`() {
        BAMBU_MACHINE_PROFILES.keys.forEach { target ->
            BambuConfig.SUPPORTED_NOZZLE_DIAMETERS.forEach { diameter ->
                val machine = requireNotNull(bambuMachineProfileFor(target, diameter))
                assertTrue(machine.nozzleDiameters.all { it == diameter })
                assertTrue(machine.printerSettingsId.endsWith("$diameter nozzle"))
                val base = BambuNozzlePolicy.baseConfig(SliceConfig(), diameter)
                assertEquals(diameter, resolveTargetedSliceConfig(target, base).nozzleDiameter)
                val result = BambuImportedConfigComposer.compose(target, nozzleDiameter = diameter)
                assertEquals(diameter.toString(), result.config["printer_variant"])
                assertEquals(machine.nozzleDiameters.joinToString(","), result.config["nozzle_diameter"])
                assertEquals((diameter / 2).toString(), result.config["layer_height"])
            }
        }
    }

    @Test fun `source hardware cannot override installed nozzle but compatible tuning survives`() {
        val result = BambuImportedConfigComposer.compose(
            SlicerTarget.BambuP1S,
            sourceConfig = mapOf("nozzle_diameter" to "0.4", "printer_variant" to "0.4", "layer_height" to "0.12"),
            nozzleDiameter = 0.2f,
        )
        assertEquals("0.2", result.config["nozzle_diameter"])
        assertEquals("0.12", result.config["layer_height"])
        assertEquals("0.21", result.config["outer_wall_line_width"])
    }

    @Test(expected = IllegalArgumentException::class)
    fun `incompatible imported layer height is rejected for fine nozzle`() {
        BambuImportedConfigComposer.compose(SlicerTarget.BambuA1, sourceConfig = mapOf("layer_height" to "0.2"), nozzleDiameter = 0.2f)
    }

    @Test fun `explicit compatible process wins over imported incompatible height`() {
        val result = BambuImportedConfigComposer.compose(SlicerTarget.BambuA1,
            sourceConfig = mapOf("layer_height" to "0.2"), explicitOverrides = mapOf("layer_height" to "0.1"), nozzleDiameter = 0.2f)
        assertEquals("0.1", result.config["layer_height"])
    }

    @Test(expected = IllegalArgumentException::class)
    fun `incompatible imported line width is rejected for fine nozzle`() {
        BambuImportedConfigComposer.compose(SlicerTarget.BambuA1,
            sourceConfig = mapOf("outer_wall_line_width" to "0.45"), nozzleDiameter = 0.2f)
    }

    @Test fun `completed project requires matching saved nozzle size`() {
        val printer = com.u1.slicer.data.Printer("test", "P1S", kind = com.u1.slicer.data.PrinterKind.BAMBU_LAN,
            bambu = BambuConfig("ip", "code", "serial", BambuModel.P1S, 0.6f))
        val artifact = com.u1.slicer.slice.SliceArtifact.BambuProjectArtifact("test", File(tmp.root, "test.3mf"), 1,
            target = SlicerTarget.BambuP1S, nozzleDiameter = 0.6f)
        assertTrue(artifact.isCompatibleWith(printer))
        assertFalse(artifact.isCompatibleWith(printer.copy(bambu = printer.bambu!!.copy(nozzleDiameter = 0.4f))))
    }

    @Test fun `packaged projects preserve selected size in settings and slice metadata`() {
        listOf(SlicerTarget.BambuP1S, SlicerTarget.BambuH2D).forEach { target ->
            BambuConfig.SUPPORTED_NOZZLE_DIAMETERS.forEach { diameter ->
                val dir = tmp.newFolder("${target.name}-$diameter")
                val gcode = File(dir, "test.gcode").apply {
                    writeText("; CONFIG_BLOCK_START\n; nozzle_diameter = 0.4\n; CONFIG_BLOCK_END\nG90\nG1 X100 Y100 Z1\n")
                }
                val artifact = buildSliceArtifact(target, "test", gcode, dir, 1, listOf("#FFFFFF"), listOf("PLA"), bambuNozzleDiameter = diameter)
                    as com.u1.slicer.slice.SliceArtifact.BambuProjectArtifact
                ZipFile(artifact.projectFile).use { zip ->
                    val settings = JSONObject(zip.getInputStream(zip.getEntry("Metadata/project_settings.config")).reader().readText())
                    assertEquals(diameter.toString(), settings.getString("printer_variant"))
                    val nozzles = settings.getJSONArray("nozzle_diameter")
                    assertEquals(if (target == SlicerTarget.BambuH2D) 2 else 1, nozzles.length())
                    repeat(nozzles.length()) { assertEquals(diameter.toDouble(), nozzles.getDouble(it), 0.00001) }
                    val metadata = zip.getInputStream(zip.getEntry("Metadata/slice_info.config")).reader().readText()
                    assertTrue(metadata.contains("nozzle_diameter=\"$diameter\""))
                    if (target == SlicerTarget.BambuH2D) {
                        val packaged = zip.getInputStream(zip.getEntry("Metadata/plate_1.gcode")).reader().readText()
                        assertTrue(packaged.contains("; nozzle_diameter = $diameter,$diameter"))
                    }
                }
            }
        }
    }
}
