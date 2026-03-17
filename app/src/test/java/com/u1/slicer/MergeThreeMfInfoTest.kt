package com.u1.slicer

import com.u1.slicer.bambu.ThreeMfInfo
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class MergeThreeMfInfoTest {

    @Test
    fun `mergeThreeMfInfoForPlate prefers plate objectExtruderMap when non-empty`() {
        val sourceInfo = ThreeMfInfo(
            objects = emptyList(), plates = emptyList(),
            isBambu = true, isMultiPlate = true,
            objectExtruderMap = mapOf("10" to 1, "11" to 2, "12" to 3),  // old IDs
            detectedColors = listOf("#FF0000", "#00FF00", "#0000FF"),
            detectedExtruderCount = 3
        )
        val plateInfo = ThreeMfInfo(
            objects = emptyList(), plates = emptyList(),
            isBambu = false, isMultiPlate = false,
            objectExtruderMap = mapOf("4" to 1, "5" to 2, "6" to 3),  // new restructured IDs
            usedExtruderIndices = setOf(1, 2, 3)
        )
        val merged = SlicerViewModel.mergeThreeMfInfoForPlate(plateInfo, sourceInfo)
        // Should use plate's map (new IDs), not source's (old IDs)
        assertEquals(mapOf("4" to 1, "5" to 2, "6" to 3), merged.objectExtruderMap)
    }

    @Test
    fun `mergeThreeMfInfoForPlate falls back to source objectExtruderMap when plate is empty`() {
        val sourceInfo = ThreeMfInfo(
            objects = emptyList(), plates = emptyList(),
            isBambu = true, isMultiPlate = true,
            objectExtruderMap = mapOf("10" to 1, "11" to 2),
            detectedColors = listOf("#FF0000", "#00FF00"),
            detectedExtruderCount = 2
        )
        val plateInfo = ThreeMfInfo(
            objects = emptyList(), plates = emptyList(),
            isBambu = false, isMultiPlate = false,
            objectExtruderMap = emptyMap(),  // no config in plate file
            usedExtruderIndices = setOf(1, 2)
        )
        val merged = SlicerViewModel.mergeThreeMfInfoForPlate(plateInfo, sourceInfo)
        // Should fall back to source's map
        assertEquals(mapOf("10" to 1, "11" to 2), merged.objectExtruderMap)
    }

    @Test
    fun `mergeThreeMfInfo prefers processed objectExtruderMap when available`() {
        val origInfo = ThreeMfInfo(
            objects = emptyList(), plates = emptyList(),
            isBambu = true, isMultiPlate = false,
            objectExtruderMap = mapOf("3" to 2),
            detectedColors = listOf("#FF0000", "#00FF00", "#0000FF"),
            detectedExtruderCount = 3,
            hasPaintData = true
        )
        val processedInfo = ThreeMfInfo(
            objects = emptyList(), plates = emptyList(),
            isBambu = false, isMultiPlate = false,
            objectExtruderMap = mapOf("1" to 1, "2" to 2)
        )
        val merged = SlicerViewModel.mergeThreeMfInfo(processedInfo, origInfo)
        assertEquals(mapOf("1" to 1, "2" to 2), merged.objectExtruderMap)
    }

    @Test
    fun `mergeThreeMfInfo falls back to orig objectExtruderMap when processed is empty`() {
        val origInfo = ThreeMfInfo(
            objects = emptyList(), plates = emptyList(),
            isBambu = true, isMultiPlate = false,
            objectExtruderMap = mapOf("1" to 1, "2" to 2),
            detectedColors = listOf("#FF0000", "#00FF00"),
            detectedExtruderCount = 2,
            hasPaintData = true
        )
        val processedInfo = ThreeMfInfo(
            objects = emptyList(), plates = emptyList(),
            isBambu = false, isMultiPlate = false,
            objectExtruderMap = emptyMap()
        )
        val merged = SlicerViewModel.mergeThreeMfInfo(processedInfo, origInfo)
        assertEquals(mapOf("1" to 1, "2" to 2), merged.objectExtruderMap)
    }

    @Test
    fun `resolvePreviewModelFile prefers raw file for H2C source projects`() {
        val raw = File("raw.3mf")
        val source = File("sanitized.3mf")
        val info = ThreeMfInfo(
            objects = emptyList(), plates = emptyList(),
            isBambu = true, isMultiPlate = false
        )

        val selected = SlicerViewModel.resolvePreviewModelFile(
            rawInputFile = raw,
            sourceModelFile = source,
            currentModelFile = null,
            info = info,
            originalSourceConfig = mapOf(
                "filament_settings_id" to listOf("Bambu PLA Basic @BBL H2C"),
                "extruder_ams_count" to listOf("1#1|4#0", "1#0|4#2")
            )
        )

        assertEquals(raw, selected)
    }

    @Test
    fun `resolvePreviewModelFile keeps sanitized file for non-H2C Bambu previews`() {
        val raw = File("raw.3mf")
        val source = File("sanitized.3mf")
        val info = ThreeMfInfo(
            objects = emptyList(), plates = emptyList(),
            isBambu = true, isMultiPlate = false
        )

        val selected = SlicerViewModel.resolvePreviewModelFile(
            rawInputFile = raw,
            sourceModelFile = source,
            currentModelFile = null,
            info = info,
            originalSourceConfig = mapOf(
                "filament_colour" to listOf("#FF0000", "#00FF00")
            )
        )

        assertEquals(source, selected)
    }

    @Test
    fun `isH2cSourceConfig ignores generic machine compatibility markers`() {
        val config = mapOf(
            "upward_compatible_machine" to "Bambu Lab H2C 0.4 nozzle",
            "extruder_ams_count" to listOf("1#0|4#0", ""),
            "physical_extruder_map" to listOf("0"),
            "filament_settings_id" to listOf("Bambu PLA Basic @BBL A1M")
        )

        assertFalse(SlicerViewModel.isH2cSourceConfig(config))
    }
}
