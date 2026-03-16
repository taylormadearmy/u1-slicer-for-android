package com.u1.slicer

import com.u1.slicer.bambu.ThreeMfInfo
import org.junit.Assert.*
import org.junit.Test

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
    fun `mergeThreeMfInfo preserves objectExtruderMap from origInfo`() {
        val origInfo = ThreeMfInfo(
            objects = emptyList(), plates = emptyList(),
            isBambu = true, isMultiPlate = false,
            objectExtruderMap = mapOf("1" to 1, "2" to 2, "3" to 3),
            detectedColors = listOf("#FF0000", "#00FF00", "#0000FF"),
            detectedExtruderCount = 3,
            hasPaintData = true
        )
        val processedInfo = ThreeMfInfo(
            objects = emptyList(), plates = emptyList(),
            isBambu = false, isMultiPlate = false,
            objectExtruderMap = emptyMap()  // process() strips model_settings.config
        )
        val merged = SlicerViewModel.mergeThreeMfInfo(processedInfo, origInfo)
        assertEquals(mapOf("1" to 1, "2" to 2, "3" to 3), merged.objectExtruderMap)
    }
}
