package com.u1.slicer

import com.u1.slicer.bambu.ThreeMfInfo
import com.u1.slicer.bambu.ThreeMfObject
import com.u1.slicer.bambu.ThreeMfPlate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ModelExportArtifactsTest {
    @Test
    fun current_returnsNull_forNonBambuModels() {
        val dir = Files.createTempDirectory("export-artifacts").toFile()
        val sanitized = File(dir, "model.sanitized.3mf").apply { writeText("zip") }
        val embedded = File(dir, "model.embedded.3mf").apply { writeText("zip") }

        val artifacts = ModelExportArtifacts.current(
            sourceDisplayName = "model.3mf",
            selectedPlateId = null,
            sanitizedFile = sanitized,
            embeddedFile = embedded,
            info = sampleInfo(isBambu = false)
        )

        assertNull(artifacts)
    }

    @Test
    fun current_filtersUnavailableArtifacts_andKeepsExportableBambuState() {
        val dir = Files.createTempDirectory("export-artifacts").toFile()
        val sanitized = File(dir, "dragon.sanitized.3mf").apply { writeText("zip") }
        val missingEmbedded = File(dir, "dragon.sanitized-embedded.3mf")

        val artifacts = ModelExportArtifacts.current(
            sourceDisplayName = "Dragon Scale infinity.3mf",
            selectedPlateId = 3,
            sanitizedFile = sanitized,
            embeddedFile = missingEmbedded,
            info = sampleInfo(isBambu = true, isMultiPlate = true)
        )

        assertNotNull(artifacts)
        assertEquals(sanitized, artifacts!!.fileFor(ExportArtifactKind.Sanitized3mf))
        assertNull(artifacts.fileFor(ExportArtifactKind.SanitizedEmbedded3mf))
        assertEquals(3, artifacts.selectedPlateId)
    }

    @Test
    fun current_returnsNull_whenNoExportableArtifactsRemain() {
        val dir = Files.createTempDirectory("export-artifacts").toFile()
        val sanitized = File(dir, "dragon.sanitized.tmp").apply { writeText("not-3mf") }
        val embedded = File(dir, "dragon.sanitized-embedded.3mf")

        val artifacts = ModelExportArtifacts.current(
            sourceDisplayName = "",
            selectedPlateId = null,
            sanitizedFile = sanitized,
            embeddedFile = embedded,
            info = sampleInfo(isBambu = true)
        )

        assertNull(artifacts)
    }

    @Test
    fun suggestedFilename_usesKnownSuffixes() {
        assertEquals(
            "Dragon Scale infinity.sanitized.3mf",
            ModelExportArtifacts.suggestedFilename(
                "Dragon Scale infinity.3mf",
                ExportArtifactKind.Sanitized3mf
            )
        )
        assertEquals(
            "Dragon Scale infinity.sanitized-embedded.3mf",
            ModelExportArtifacts.suggestedFilename(
                "Dragon Scale infinity.3mf",
                ExportArtifactKind.SanitizedEmbedded3mf
            )
        )
    }

    @Test
    fun suggestedFilename_fallsBackToModel_whenSourceNameBlank() {
        assertEquals(
            "model.sanitized.3mf",
            ModelExportArtifacts.suggestedFilename(
                "",
                ExportArtifactKind.Sanitized3mf
            )
        )
    }

    @Test
    fun buildDebugSummary_includesStageContractFields() {
        val dir = Files.createTempDirectory("export-artifacts").toFile()
        val sanitized = File(dir, "dragon.sanitized.3mf").apply { writeText("zip") }
        val embedded = File(dir, "dragon.sanitized-embedded.3mf").apply { writeText("zip") }
        val artifacts = ModelExportArtifacts.current(
            sourceDisplayName = "Dragon Scale infinity.3mf",
            selectedPlateId = 3,
            sanitizedFile = sanitized,
            embeddedFile = embedded,
            info = sampleInfo(
                isBambu = true,
                isMultiPlate = true,
                hasPaintData = true,
                hasPaintSupports = true,
                hasLayerToolChanges = true,
                detectedExtruderCount = 4,
                usedExtruderIndices = setOf(1, 3, 4),
                objectExtruderMap = mapOf("11" to 1, "12" to 3)
            )
        )!!

        val summary = ModelExportArtifacts.buildDebugSummary(artifacts, embedded)

        assertTrue(summary.contains("Source file: Dragon Scale infinity.3mf"))
        assertTrue(summary.contains("Current working file: dragon.sanitized-embedded.3mf"))
        assertTrue(summary.contains("Bambu file: yes"))
        assertTrue(summary.contains("Multi-plate: yes"))
        assertTrue(summary.contains("Selected plate: 3"))
        assertTrue(summary.contains("Detected extruders: 4"))
        assertTrue(summary.contains("Paint data: yes"))
        assertTrue(summary.contains("Paint supports: yes"))
        assertTrue(summary.contains("Layer-tool changes: yes"))
        assertTrue(summary.contains("Used extruder indices: 1, 3, 4"))
        assertTrue(summary.contains("Object-extruder map size: 2"))
        assertTrue(summary.contains("Sanitized artifact: dragon.sanitized.3mf"))
        assertTrue(summary.contains("Sanitized + embedded artifact: dragon.sanitized-embedded.3mf"))
    }

    private fun sampleInfo(
        isBambu: Boolean,
        isMultiPlate: Boolean = false,
        hasPaintData: Boolean = false,
        hasPaintSupports: Boolean = false,
        hasLayerToolChanges: Boolean = false,
        detectedExtruderCount: Int = 1,
        usedExtruderIndices: Set<Int> = emptySet(),
        objectExtruderMap: Map<String, Int> = emptyMap()
    ): ThreeMfInfo = ThreeMfInfo(
        objects = listOf(ThreeMfObject("11", "Test Object")),
        plates = if (isMultiPlate) listOf(ThreeMfPlate(3, "Plate 3", listOf("11"))) else emptyList(),
        isBambu = isBambu,
        isMultiPlate = isMultiPlate,
        hasPaintData = hasPaintData,
        hasPaintSupports = hasPaintSupports,
        hasLayerToolChanges = hasLayerToolChanges,
        detectedExtruderCount = detectedExtruderCount,
        usedExtruderIndices = usedExtruderIndices,
        objectExtruderMap = objectExtruderMap
    )
}
