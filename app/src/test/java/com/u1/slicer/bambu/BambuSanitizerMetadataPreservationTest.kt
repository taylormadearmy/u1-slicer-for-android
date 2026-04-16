package com.u1.slicer.bambu

import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * B77: BambuSanitizer must preserve per-object non-extruder metadata
 * (enable_support, support_type, layer_height, seam_position, etc.) from the
 * source model_settings.config, so OrcaSlicer's per-object config layer sees
 * them and Bambu Studio Objects-tab overrides are honoured.
 *
 * Root cause these tests guard against: the sanitizer's "no extruder rewrite
 * needed" branch previously was a no-op, stripping the entire source
 * model_settings.config from the output zip.
 */
class BambuSanitizerMetadataPreservationTest {

    private lateinit var tmpDir: File

    @Before
    fun setUp() {
        tmpDir = File.createTempFile("sanitizer-test", "").also {
            it.delete()
            it.mkdirs()
        }
    }

    @After
    fun tearDown() {
        tmpDir.deleteRecursively()
    }

    /** Build a minimal Bambu 3MF: project_settings.config + model_settings.config +
     *  a stub 3dmodel.model. The sanitizer identifies it as Bambu via
     *  project_settings.config presence. */
    private fun buildMinimalBambu3mf(
        modelSettingsXml: String,
        projectSettingsJson: String = """{"filament_colour":["#FFFFFF"]}"""
    ): File {
        val out = File(tmpDir, "input.3mf")
        ZipOutputStream(out.outputStream()).use { zip ->
            fun storedEntry(name: String, bytes: ByteArray) {
                val e = ZipEntry(name).apply {
                    method = ZipEntry.STORED
                    size = bytes.size.toLong()
                    compressedSize = bytes.size.toLong()
                    crc = CRC32().apply { update(bytes) }.value
                }
                zip.putNextEntry(e)
                zip.write(bytes)
                zip.closeEntry()
            }
            storedEntry(
                "[Content_Types].xml",
                """<?xml version="1.0" encoding="UTF-8"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"/>""".toByteArray()
            )
            storedEntry(
                "3D/3dmodel.model",
                """<?xml version="1.0"?><model xmlns:BambuStudio="http://schemas.bambulab.com/package/2021"><resources/></model>""".toByteArray()
            )
            storedEntry(
                "Metadata/project_settings.config",
                projectSettingsJson.toByteArray()
            )
            storedEntry(
                "Metadata/model_settings.config",
                modelSettingsXml.toByteArray()
            )
        }
        return out
    }

    private fun readZipEntry(file: File, name: String): String? =
        ZipFile(file).use { zip ->
            val entry = zip.getEntry(name) ?: return null
            zip.getInputStream(entry).bufferedReader().readText()
        }

    @Test
    fun preservesPerObjectEnableSupportMetadata() {
        // Model settings with a single object carrying per-object support overrides
        // (e.g. Sensory Twist Ball with Bambu Studio Objects-tab overrides).
        val modelSettings = """<?xml version="1.0" encoding="UTF-8"?>
<config>
  <object id="2">
    <metadata key="name" value="LOW POLY SENSORY TWIST BALL FIDGET.stl"/>
    <metadata key="enable_support" value="1"/>
    <metadata key="support_type" value="tree(manual)"/>
    <metadata key="support_on_build_plate_only" value="1"/>
    <metadata key="extruder" value="1"/>
  </object>
</config>"""

        val input = buildMinimalBambu3mf(modelSettings)
        val output = BambuSanitizer.process(input, tmpDir, isBambu = true)

        val preserved = readZipEntry(output, "Metadata/model_settings.config")
        assertNotNull("Output must contain Metadata/model_settings.config", preserved)
        assertTrue(
            "Output model_settings.config must preserve per-object enable_support=1. Got:\n$preserved",
            preserved!!.contains("""key="enable_support" value="1"""")
        )
        assertTrue(
            "Output model_settings.config must preserve per-object support_type=tree(manual). Got:\n$preserved",
            preserved.contains("""key="support_type" value="tree(manual)"""")
        )
        assertTrue(
            "Output model_settings.config must preserve per-object support_on_build_plate_only=1. Got:\n$preserved",
            preserved.contains("""key="support_on_build_plate_only" value="1"""")
        )
    }

    @Test
    fun preservesMetadataForSingleObjectSingleExtruder() {
        // This is the "no model config rewrite needed" branch — single object, single
        // extruder, but the source has non-extruder per-object overrides to preserve.
        val modelSettings = """<?xml version="1.0" encoding="UTF-8"?>
<config>
  <object id="2">
    <metadata key="name" value="test"/>
    <metadata key="seam_position" value="back"/>
    <metadata key="layer_height" value="0.12"/>
    <metadata key="extruder" value="1"/>
  </object>
</config>"""

        val input = buildMinimalBambu3mf(modelSettings)
        val output = BambuSanitizer.process(input, tmpDir, isBambu = true)

        val preserved = readZipEntry(output, "Metadata/model_settings.config")
        assertNotNull("Output must retain model_settings.config", preserved)
        assertTrue(
            "seam_position override must survive. Got:\n$preserved",
            preserved!!.contains("""key="seam_position" value="back"""")
        )
        assertTrue(
            "layer_height override must survive. Got:\n$preserved",
            preserved.contains("""key="layer_height" value="0.12"""")
        )
    }
}
