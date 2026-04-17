package com.u1.slicer

import com.u1.slicer.bambu.LayerToolSegment
import com.u1.slicer.bambu.ThreeMfInfo
import com.u1.slicer.bambu.ThreeMfPlate
import com.u1.slicer.computeEmbedTargetCount
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
    fun `mergeThreeMfInfoForPlate uses selected source plate filament indices when extracted plate under-detects colours`() {
        val sourceInfo = ThreeMfInfo(
            objects = emptyList(),
            plates = listOf(
                com.u1.slicer.bambu.ThreeMfPlate(
                    plateId = 3,
                    name = "4 Colour",
                    objectIds = listOf("58"),
                    filamentIndices = setOf(1, 2)
                )
            ),
            isBambu = true,
            isMultiPlate = true,
            detectedColors = listOf("#101010", "#202020", "#303030", "#404040"),
            detectedExtruderCount = 4
        )
        val plateInfo = ThreeMfInfo(
            objects = emptyList(),
            plates = emptyList(),
            isBambu = true,
            isMultiPlate = false,
            usedExtruderIndices = setOf(1)
        )

        val merged = SlicerViewModel.mergeThreeMfInfoForPlate(plateInfo, sourceInfo, selectedPlateId = 3)

        assertEquals(listOf("#101010", "#202020"), merged.detectedColors)
        assertEquals(2, merged.detectedExtruderCount)
    }

    @Test
    fun `mergeThreeMfInfoForPlate keeps richer extracted plate colours when they exceed source filament maps`() {
        val sourceInfo = ThreeMfInfo(
            objects = emptyList(),
            plates = listOf(
                com.u1.slicer.bambu.ThreeMfPlate(
                    plateId = 3,
                    name = "4 Colour",
                    objectIds = listOf("58"),
                    filamentIndices = setOf(1, 2)
                )
            ),
            isBambu = true,
            isMultiPlate = true,
            detectedColors = listOf("#101010", "#202020", "#303030", "#404040"),
            detectedExtruderCount = 4
        )
        val plateInfo = ThreeMfInfo(
            objects = emptyList(),
            plates = emptyList(),
            isBambu = true,
            isMultiPlate = false,
            usedExtruderIndices = setOf(1, 2, 3, 4)
        )

        val merged = SlicerViewModel.mergeThreeMfInfoForPlate(plateInfo, sourceInfo, selectedPlateId = 3)

        assertEquals(listOf("#101010", "#202020", "#303030", "#404040"), merged.detectedColors)
        assertEquals(4, merged.detectedExtruderCount)
    }

    @Test
    fun `mergeThreeMfInfoForPlate layer-tool models include base object colour from selected plate`() {
        val sourceInfo = ThreeMfInfo(
            objects = emptyList(),
            plates = listOf(
                com.u1.slicer.bambu.ThreeMfPlate(
                    plateId = 4,
                    name = "Small - Dual Colour",
                    objectIds = listOf("5"),
                    filamentIndices = emptySet()
                )
            ),
            isBambu = true,
            isMultiPlate = true,
            detectedColors = listOf("#161616", "#F4D976", "#368CF9"),
            detectedExtruderCount = 3,
            hasLayerToolChanges = true,
            objectExtruderMap = mapOf("5" to 1)
        )
        val plateInfo = ThreeMfInfo(
            objects = emptyList(),
            plates = emptyList(),
            isBambu = true,
            isMultiPlate = false,
            detectedColors = listOf("#F4D976"),
            detectedExtruderCount = 1,
            hasLayerToolChanges = true,
            usedExtruderIndices = setOf(2)
        )

        val merged = SlicerViewModel.mergeThreeMfInfoForPlate(plateInfo, sourceInfo, selectedPlateId = 4)

        assertEquals(listOf("#161616", "#F4D976"), merged.detectedColors)
        assertEquals(2, merged.detectedExtruderCount)
        assertEquals(setOf(1, 2), merged.usedExtruderIndices)
    }

    @Test
    fun `mergeThreeMfInfoForPlate with per-object assignments avoids full layer-tool palette`() {
        val sourceInfo = ThreeMfInfo(
            objects = emptyList(),
            plates = listOf(
                com.u1.slicer.bambu.ThreeMfPlate(
                    plateId = 5,
                    name = "Textured",
                    objectIds = listOf("58"),
                    filamentIndices = setOf(1, 2)
                )
            ),
            isBambu = true,
            isMultiPlate = true,
            detectedColors = listOf("#C1", "#C2", "#C3", "#C4", "#C5"),
            detectedExtruderCount = 5,
            hasLayerToolChanges = true,
            hasMultiExtruderAssignments = true
        )
        val plateInfo = ThreeMfInfo(
            objects = emptyList(),
            plates = emptyList(),
            isBambu = true,
            isMultiPlate = false,
            detectedColors = listOf("#C1", "#C2", "#C3", "#C4", "#C5"),
            detectedExtruderCount = 5,
            usedExtruderIndices = setOf(1, 2)
        )

        val merged = SlicerViewModel.mergeThreeMfInfoForPlate(plateInfo, sourceInfo, selectedPlateId = 5)

        assertEquals(listOf("#C1", "#C2"), merged.detectedColors)
        assertEquals(2, merged.detectedExtruderCount)
    }

    @Test
    fun `mergeThreeMfInfoForPlate prefers selected source plate indices over over-reported used extruders`() {
        val sourceInfo = ThreeMfInfo(
            objects = emptyList(),
            plates = listOf(
                com.u1.slicer.bambu.ThreeMfPlate(
                    plateId = 5,
                    name = "Selected Plate",
                    objectIds = listOf("58"),
                    filamentIndices = setOf(1, 2)
                )
            ),
            isBambu = true,
            isMultiPlate = true,
            detectedColors = listOf("#A", "#B", "#C", "#D"),
            detectedExtruderCount = 4,
            hasLayerToolChanges = true,
            hasMultiExtruderAssignments = true
        )
        val plateInfo = ThreeMfInfo(
            objects = emptyList(),
            plates = emptyList(),
            isBambu = true,
            isMultiPlate = false,
            // Extracted plate metadata can over-report here; should not drive color count.
            usedExtruderIndices = setOf(1, 2, 3),
            detectedColors = listOf("#A", "#B", "#C", "#D"),
            detectedExtruderCount = 4
        )

        val merged = SlicerViewModel.mergeThreeMfInfoForPlate(plateInfo, sourceInfo, selectedPlateId = 5)

        assertEquals(listOf("#A", "#B"), merged.detectedColors)
        assertEquals(2, merged.detectedExtruderCount)
    }

    @Test
    fun `mergeThreeMfInfoForPlate layer-tool assignment fallback expands one reported source slot to stable dual slot`() {
        val sourceInfo = ThreeMfInfo(
            objects = emptyList(),
            plates = listOf(
                com.u1.slicer.bambu.ThreeMfPlate(
                    plateId = 5,
                    name = "Selected Plate",
                    objectIds = listOf("58"),
                    filamentIndices = setOf(1)
                )
            ),
            isBambu = true,
            isMultiPlate = true,
            detectedColors = listOf("#F4D976", "#EC008C", "#F7D959", "#F4EE2A"),
            detectedExtruderCount = 4,
            hasLayerToolChanges = true,
            hasMultiExtruderAssignments = true
        )
        val plateInfo = ThreeMfInfo(
            objects = emptyList(),
            plates = emptyList(),
            isBambu = true,
            isMultiPlate = false,
            usedExtruderIndices = setOf(1, 2, 3)
        )

        val merged = SlicerViewModel.mergeThreeMfInfoForPlate(plateInfo, sourceInfo, selectedPlateId = 5)

        assertEquals(listOf("#F4D976", "#EC008C"), merged.detectedColors)
        assertEquals(2, merged.detectedExtruderCount)
    }

    @Test
    fun `mergeThreeMfInfoForPlate non-layer-tool assignment keeps richer extracted used extruders`() {
        val sourceInfo = ThreeMfInfo(
            objects = emptyList(),
            plates = listOf(
                com.u1.slicer.bambu.ThreeMfPlate(
                    plateId = 3,
                    name = "Dragon Plate 3",
                    objectIds = listOf("58"),
                    filamentIndices = emptySet()
                )
            ),
            isBambu = true,
            isMultiPlate = true,
            detectedColors = listOf("#F4EE2A", "#F4D976", "#EC008C"),
            detectedExtruderCount = 3,
            hasLayerToolChanges = false,
            hasMultiExtruderAssignments = true,
            objectExtruderMap = mapOf("58" to 1)
        )
        val plateInfo = ThreeMfInfo(
            objects = emptyList(),
            plates = emptyList(),
            isBambu = true,
            isMultiPlate = false,
            usedExtruderIndices = setOf(1, 2, 3)
        )

        val merged = SlicerViewModel.mergeThreeMfInfoForPlate(plateInfo, sourceInfo, selectedPlateId = 3)

        assertEquals(listOf("#F4EE2A", "#F4D976", "#EC008C"), merged.detectedColors)
        assertEquals(3, merged.detectedExtruderCount)
    }

    @Test
    fun `mergeThreeMfInfo prefers processed objectExtruderMap when available`() {
        val origInfo = ThreeMfInfo(
            objects = emptyList(), plates = emptyList(),
            isBambu = true, isMultiPlate = false,
            objectExtruderMap = mapOf("3" to 2),
            detectedColors = listOf("#FF0000", "#00FF00", "#0000FF"),
            detectedExtruderCount = 3,
            usedExtruderIndices = setOf(1, 2, 3),
            hasPaintData = true
        )
        val processedInfo = ThreeMfInfo(
            objects = emptyList(), plates = emptyList(),
            isBambu = false, isMultiPlate = false,
            objectExtruderMap = mapOf("1" to 1, "2" to 2)
        )
        val merged = SlicerViewModel.mergeThreeMfInfo(processedInfo, origInfo)
        assertEquals(mapOf("1" to 1, "2" to 2), merged.objectExtruderMap)
        assertEquals(setOf(1, 2, 3), merged.usedExtruderIndices)
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
    fun `mergeThreeMfInfo carries layerToolSegments from origInfo`() {
        val segments = listOf(LayerToolSegment(1.0f, 1), LayerToolSegment(5.0f, 2))
        val origInfo = ThreeMfInfo(
            objects = emptyList(), plates = emptyList(), isBambu = false, isMultiPlate = false,
            hasLayerToolChanges = true,
            layerToolSegments = segments
        )
        val processedInfo = ThreeMfInfo(objects = emptyList(), plates = emptyList(), isBambu = false, isMultiPlate = false)
        val merged = SlicerViewModel.mergeThreeMfInfo(processedInfo, origInfo)
        assertEquals(segments, merged.layerToolSegments)
    }

    @Test
    fun `mergeThreeMfInfoForPlate carries layerToolSegments from sourceInfo`() {
        val segments = listOf(LayerToolSegment(2.0f, 1), LayerToolSegment(8.0f, 2))
        val sourceInfo = ThreeMfInfo(
            objects = emptyList(), plates = emptyList(), isBambu = false, isMultiPlate = false,
            hasLayerToolChanges = true,
            layerToolSegments = segments
        )
        val plateInfo = ThreeMfInfo(objects = emptyList(), plates = emptyList(), isBambu = false, isMultiPlate = false)
        val merged = SlicerViewModel.mergeThreeMfInfoForPlate(plateInfo, sourceInfo)
        assertEquals(segments, merged.layerToolSegments)
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
    fun `resolvePlateSelectionSourceFile prefers pre-embed source for multi-plate extraction`() {
        val source = File("processed.3mf")
        val current = File("embedded_sanitized.3mf")

        val selected = SlicerViewModel.resolvePlateSelectionSourceFile(
            sourceModelFile = source,
            currentModelFile = current
        )

        assertEquals(source, selected)
    }

    @Test
    fun `resolvePlateSelectionSourceFile falls back to current model when source missing`() {
        val current = File("embedded_sanitized.3mf")

        val selected = SlicerViewModel.resolvePlateSelectionSourceFile(
            sourceModelFile = null,
            currentModelFile = current
        )

        assertEquals(current, selected)
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

    @Test
    fun `buildCompactExtruderRemap compacts original extruders to chosen slot order`() {
        val info = ThreeMfInfo(
            objects = emptyList(),
            plates = emptyList(),
            isBambu = true,
            isMultiPlate = false,
            usedExtruderIndices = setOf(1, 2, 3, 4, 5)
        )

        val remap = buildCompactExtruderRemap(
            info = info,
            colorMapping = listOf(0, 0, 3, 3, 0)
        )

        assertEquals(
            mapOf(1 to 1, 2 to 1, 3 to 2, 4 to 2, 5 to 1),
            remap
        )
    }

    @Test
    fun `buildCompactExtruderRemap returns null without color mapping`() {
        val info = ThreeMfInfo(
            objects = emptyList(),
            plates = emptyList(),
            isBambu = true,
            isMultiPlate = false,
            usedExtruderIndices = setOf(1, 2, 3)
        )

        assertNull(buildCompactExtruderRemap(info, null))
        assertNull(buildCompactExtruderRemap(info, emptyList()))
    }

    @Test
    fun `buildCompactExtruderRemap falls back to color row indices when source extruders are missing`() {
        val info = ThreeMfInfo(
            objects = emptyList(),
            plates = emptyList(),
            isBambu = true,
            isMultiPlate = false
        )

        val remap = buildCompactExtruderRemap(
            info = info,
            colorMapping = listOf(0, 1, 3, 3)
        )

        assertEquals(mapOf(1 to 1, 2 to 2, 3 to 3, 4 to 3), remap)
    }

    @Test
    fun `buildPreviewSlotColors uses slot colors instead of detected model colors`() {
        val presets = listOf(
            com.u1.slicer.data.ExtruderPreset(0, "#112233"),
            com.u1.slicer.data.ExtruderPreset(1, "#445566"),
            com.u1.slicer.data.ExtruderPreset(2, "#778899"),
            com.u1.slicer.data.ExtruderPreset(3, "#AABBCC")
        )

        val colors = SlicerViewModel.buildPreviewSlotColors(
            extruderPresets = presets,
            usedSlots = listOf(3, 2, 1, 0)
        )

        assertEquals(listOf("#112233", "#445566", "#778899", "#AABBCC"), colors)
    }

    @Test
    fun `buildPreviewSlotColors falls back to default slot colors when preset color is blank`() {
        val presets = listOf(
            com.u1.slicer.data.ExtruderPreset(0, ""),
            com.u1.slicer.data.ExtruderPreset(1, "#445566"),
            com.u1.slicer.data.ExtruderPreset(2, ""),
            com.u1.slicer.data.ExtruderPreset(3, "#AABBCC")
        )

        val colors = SlicerViewModel.buildPreviewSlotColors(
            extruderPresets = presets,
            usedSlots = listOf(0, 1, 2, 3)
        )

        assertEquals(
            listOf(
                com.u1.slicer.data.ExtruderPreset.DEFAULT_COLORS[0],
                "#445566",
                com.u1.slicer.data.ExtruderPreset.DEFAULT_COLORS[2],
                "#AABBCC"
            ),
            colors
        )
    }

    // ── Color bug fix (SEMM models) ────────────────────────────────────────────

    @Test
    fun `buildCompactExtruderRemap returns null for SEMM model (hasPaintData=true)`() {
        // SEMM models: per-triangle paint states bypass extruder attribute remapping.
        // GcodeToolRemapper handles physical slot assignment; extruderRemap must be null.
        val info = ThreeMfInfo(
            objects = emptyList(),
            plates = emptyList(),
            isBambu = true,
            isMultiPlate = false,
            usedExtruderIndices = setOf(1, 2, 3, 4),
            hasPaintData = true
        )
        // Non-identity colorMapping that would normally produce a remap for per-object models
        val colorMapping = listOf(2, 0, 3, 1)
        assertNull(
            "SEMM models must not produce an extruder remap (GcodeToolRemapper handles it)",
            buildCompactExtruderRemap(info, colorMapping)
        )
    }

    @Test
    fun `buildCompactExtruderRemap returns null for SEMM model even with identity colorMapping`() {
        val info = ThreeMfInfo(
            objects = emptyList(),
            plates = emptyList(),
            isBambu = true,
            isMultiPlate = false,
            usedExtruderIndices = setOf(1, 2, 3, 4),
            hasPaintData = true
        )
        assertNull(buildCompactExtruderRemap(info, listOf(0, 1, 2, 3)))
    }

    @Test
    fun `buildCompactExtruderRemap still works for per-object models (hasPaintData=false)`() {
        // Existing behaviour unchanged for models without paint data
        val info = ThreeMfInfo(
            objects = emptyList(),
            plates = emptyList(),
            isBambu = true,
            isMultiPlate = false,
            usedExtruderIndices = setOf(1, 2, 3, 4),
            hasPaintData = false
        )
        val colorMapping = listOf(2, 0, 3, 1)
        val remap = buildCompactExtruderRemap(info, colorMapping)
        assertNotNull(remap)
        // colorMapping=[2,0,3,1]: file extruder 1→slot2→compact3, 2→slot0→compact1, 3→slot3→compact4, 4→slot1→compact2
        assertEquals(mapOf(1 to 3, 2 to 1, 3 to 4, 4 to 2), remap)
    }

    @Test
    fun `buildCompactExtruderRemap returns null for layer tool change models`() {
        val info = ThreeMfInfo(
            objects = emptyList(),
            plates = emptyList(),
            isBambu = true,
            isMultiPlate = false,
            hasLayerToolChanges = true,
            usedExtruderIndices = setOf(1, 2, 3)
        )

        assertNull(
            "Layer tool change models should stay single-filament at slice time",
            buildCompactExtruderRemap(info, listOf(0, 1, 2))
        )
    }

    // ── layerToolOnly single-colour plate colour chip tests ───────────────────

    @Test
    fun `mergeThreeMfInfoForPlate single-colour layerToolOnly plate shows 1 chip when sourcePlateObjectExtruders has 1 entry`() {
        // flippy+flappy+mini plate 1 scenario: layerToolOnly=true, all objects on extruder 1,
        // but plateInfo.usedExtruderIndices=[1,2] is over-reported. Should produce 1 colour chip.
        val sourceInfo = ThreeMfInfo(
            objects = emptyList(),
            plates = listOf(ThreeMfPlate(1, "Single Colour", listOf("obj1"))),
            isBambu = true, isMultiPlate = true,
            hasLayerToolChanges = true,
            hasMultiExtruderAssignments = false,
            hasPaintData = false,
            objectExtruderMap = mapOf("obj1" to 1),
            detectedColors = listOf("#161616", "#F4D976"),
            detectedExtruderCount = 2,
            usedExtruderIndices = setOf(1, 2)
        )
        val plateInfo = ThreeMfInfo(
            objects = emptyList(), plates = emptyList(),
            isBambu = false, isMultiPlate = false,
            usedExtruderIndices = setOf(1, 2)
        )
        val merged = SlicerViewModel.mergeThreeMfInfoForPlate(plateInfo, sourceInfo, 1)
        assertEquals(
            "Single-colour layerToolOnly plate must show 1 colour chip, not leak plateInfo extruder 2",
            1, merged.detectedColors.size
        )
        assertEquals("#161616", merged.detectedColors[0])
    }

    @Test
    fun `mergeThreeMfInfoForPlate dual-colour layerToolOnly plate shows 2 chips when sourcePlateObjectExtruders is empty`() {
        // flippy+flappy+mini plate 4 scenario: layerToolOnly=true, no object extruder map
        // (Hueforge-style), plateInfo.usedExtruderIndices=[1,2] is correct. Should produce 2 chips.
        val sourceInfo = ThreeMfInfo(
            objects = emptyList(),
            plates = listOf(ThreeMfPlate(4, "Dual Colour", listOf())),
            isBambu = true, isMultiPlate = true,
            hasLayerToolChanges = true,
            hasMultiExtruderAssignments = false,
            hasPaintData = false,
            objectExtruderMap = emptyMap(),
            detectedColors = listOf("#161616", "#F4D976"),
            detectedExtruderCount = 2,
            usedExtruderIndices = setOf(1, 2)
        )
        val plateInfo = ThreeMfInfo(
            objects = emptyList(), plates = emptyList(),
            isBambu = false, isMultiPlate = false,
            usedExtruderIndices = setOf(1, 2)
        )
        val merged = SlicerViewModel.mergeThreeMfInfoForPlate(plateInfo, sourceInfo, 4)
        assertEquals(
            "Dual-colour layerToolOnly plate (no object extruder map) must show 2 colour chips",
            2, merged.detectedColors.size
        )
    }

    // ── isHueforgePlate classification tests ──────────────────────────────────

    @Test
    fun `mergeThreeMfInfoForPlate is NOT Hueforge when objectExtruderMap has diverse extruders`() {
        // Shashibo scenario: file has layer-tool changes AND per-object extruder diversity.
        // objectExtruderMap maps objects to extruders 1 and 2 — NOT a pure Hueforge plate.
        // Must produce hasMultiExtruderAssignments=true so the multi-extruder slice path runs.
        val sourceInfo = ThreeMfInfo(
            objects = emptyList(),
            plates = listOf(
                ThreeMfPlate(5, "Textured", listOf("5"), filamentIndices = setOf(1))
            ),
            isBambu = true, isMultiPlate = true,
            hasLayerToolChanges = true,
            hasMultiExtruderAssignments = true,
            objectExtruderMap = mapOf("5" to 1, "1" to 2, "2" to 1, "3" to 2, "4" to 1),
            detectedColors = listOf("#F4D976", "#EC008C", "#F7D959", "#F4EE2A"),
            detectedExtruderCount = 4,
            layerToolSegments = listOf(
                LayerToolSegment(12.4f, 2),
                LayerToolSegment(24.8f, 3)
            ),
            usedExtruderIndices = setOf(1, 2, 3)
        )
        val plateInfo = ThreeMfInfo(
            objects = emptyList(), plates = emptyList(),
            isBambu = true, isMultiPlate = false,
            usedExtruderIndices = setOf(1, 2, 3)
        )
        val merged = SlicerViewModel.mergeThreeMfInfoForPlate(plateInfo, sourceInfo, 5)
        assertTrue(
            "Shashibo-like file with per-object extruder diversity must keep hasMultiExtruderAssignments=true",
            merged.hasMultiExtruderAssignments
        )
        assertTrue("Layer tool changes should be preserved", merged.hasLayerToolChanges)
        assertFalse("Not a paint data file", merged.hasPaintData)
    }

    @Test
    fun `mergeThreeMfInfoForPlate IS Hueforge when objectExtruderMap has uniform extruder`() {
        // Flippy plate 4 scenario: file has layer-tool changes, single object on the plate,
        // and all objectExtruderMap values are extruder 1. This IS a pure Hueforge plate.
        // Must produce hasMultiExtruderAssignments=false so the layerToolOnly path runs.
        val sourceInfo = ThreeMfInfo(
            objects = emptyList(),
            plates = listOf(
                ThreeMfPlate(4, "Dual Colour", listOf("5"))
            ),
            isBambu = true, isMultiPlate = true,
            hasLayerToolChanges = true,
            hasMultiExtruderAssignments = true,  // source-level flag may be true from other plates
            objectExtruderMap = mapOf("3" to 1, "4" to 1, "5" to 1),  // all extruder 1
            detectedColors = listOf("#161616", "#F4D976"),
            detectedExtruderCount = 2,
            layerToolSegments = listOf(
                LayerToolSegment(1.2f, 2),
                LayerToolSegment(9.2f, 2)
            ),
            usedExtruderIndices = setOf(1, 2)
        )
        val plateInfo = ThreeMfInfo(
            objects = emptyList(), plates = emptyList(),
            isBambu = false, isMultiPlate = false,
            usedExtruderIndices = setOf(1, 2)
        )
        val merged = SlicerViewModel.mergeThreeMfInfoForPlate(plateInfo, sourceInfo, 4)
        assertFalse(
            "Pure Hueforge plate (uniform objectExtruderMap) must have hasMultiExtruderAssignments=false",
            merged.hasMultiExtruderAssignments
        )
        assertTrue("Layer tool changes should be preserved", merged.hasLayerToolChanges)
        // detectedExtruderCount should be >= 2 for the Z-band preview path
        assertTrue(
            "Hueforge plate needs extruderCount >= 2 for Z-band preview",
            merged.detectedExtruderCount >= 2
        )
    }

    @Test
    fun `mergeThreeMfInfoForPlate is NOT Hueforge when plate has paint data`() {
        // SEMM plate with layer-tool changes: plate-level hasPaintData=true overrides isHueforgePlate.
        val sourceInfo = ThreeMfInfo(
            objects = emptyList(),
            plates = listOf(
                ThreeMfPlate(1, "painted", listOf("1"))
            ),
            isBambu = true, isMultiPlate = false,
            hasLayerToolChanges = true,
            hasPaintData = true,
            objectExtruderMap = mapOf("1" to 1),  // single extruder but has paint
            detectedColors = listOf("#FF0000", "#00FF00"),
            detectedExtruderCount = 2,
            usedExtruderIndices = setOf(1, 2)
        )
        val plateInfo = ThreeMfInfo(
            objects = emptyList(), plates = emptyList(),
            isBambu = false, isMultiPlate = false,
            hasPaintData = true,  // this plate's extracted file has paint data
            usedExtruderIndices = setOf(1, 2)
        )
        val merged = SlicerViewModel.mergeThreeMfInfoForPlate(plateInfo, sourceInfo, 1)
        assertTrue("Paint data must be preserved from plate", merged.hasPaintData)
        // hasPaintData=true means isHueforgePlate=false, so hasMultiExtruderAssignments
        // should come from sourceInfo, not be forced false
    }

    @Test
    fun `mergeThreeMfInfoForPlate IS Hueforge when plate has no paint but source does`() {
        // Mixed file: source has paint data (from another plate), but THIS plate is layer-tool only.
        // Like flippy-with-painted where plate 5 has paint but plate 4 is layer-tool.
        val sourceInfo = ThreeMfInfo(
            objects = emptyList(),
            plates = listOf(
                ThreeMfPlate(4, "Dual Colour", listOf("5"))
            ),
            isBambu = true, isMultiPlate = true,
            hasLayerToolChanges = true,
            hasPaintData = true,  // source-level: another plate has paint
            objectExtruderMap = mapOf("5" to 1),  // all same extruder
            detectedColors = listOf("#161616", "#F4D976"),
            detectedExtruderCount = 2,
            layerToolSegments = listOf(
                LayerToolSegment(1.6f, 2)
            ),
            usedExtruderIndices = setOf(1, 2)
        )
        val plateInfo = ThreeMfInfo(
            objects = emptyList(), plates = emptyList(),
            isBambu = false, isMultiPlate = false,
            hasPaintData = false,  // THIS plate's extracted file has no paint
            usedExtruderIndices = setOf(1, 2)
        )
        val merged = SlicerViewModel.mergeThreeMfInfoForPlate(plateInfo, sourceInfo, 4)
        assertFalse(
            "Plate without paint data should have hasPaintData=false even if source has paint",
            merged.hasPaintData
        )
        assertFalse(
            "Layer-tool plate without paint should have hasMultiExtruderAssignments=false (Hueforge path)",
            merged.hasMultiExtruderAssignments
        )
        assertTrue(merged.hasLayerToolChanges)
        assertTrue("Hueforge plate needs extruderCount >= 2", merged.detectedExtruderCount >= 2)
    }

    @Test
    fun `mergeThreeMfInfoForPlate clears hasMultiExtruderAssignments for single-object plate`() {
        // Flippy: whole file has hasMultiExtruderAssignments=true (plates 1-3 have multi-extruder
        // objects), but plate 4 has only one object assigned to one extruder.
        val sourceInfo = ThreeMfInfo(
            objects = emptyList(),
            plates = listOf(
                ThreeMfPlate(1, "plate1", listOf("3")),
                ThreeMfPlate(2, "plate2", listOf("4")),
                ThreeMfPlate(3, "plate3", listOf("2")),
                ThreeMfPlate(4, "plate4", listOf("5"))
            ),
            isBambu = true, isMultiPlate = true,
            hasLayerToolChanges = true,
            hasMultiExtruderAssignments = true,
            objectExtruderMap = mapOf("3" to 1, "4" to 1, "2" to 1, "5" to 1),
            detectedColors = listOf("#161616", "#F4D976"),
            detectedExtruderCount = 2,
            layerToolSegments = listOf(
                LayerToolSegment(1.2f, 2),
                LayerToolSegment(9.2f, 2)
            ),
            usedExtruderIndices = setOf(1, 2)
        )
        val plateInfo = ThreeMfInfo(
            objects = emptyList(), plates = emptyList(),
            isBambu = false, isMultiPlate = false,
            usedExtruderIndices = setOf(1, 2)
        )
        val merged = SlicerViewModel.mergeThreeMfInfoForPlate(plateInfo, sourceInfo, 4)
        assertFalse(
            "Plate 4 has single-object extruder assignment — hasMultiExtruderAssignments should be false",
            merged.hasMultiExtruderAssignments
        )
        assertTrue("Layer tool changes should be preserved", merged.hasLayerToolChanges)
    }

    // ── computeEmbedTargetCount tests (B48) ─────────────────────────────────────

    @Test
    fun `computeEmbedTargetCount uses full colorMapping size for H2C SEMM models`() {
        // H2C benchy: 7 model colours → 4 distinct physical slots.
        // All 4 physical extruders used AND more model colours → virtual count (7).
        val colorMapping = listOf(2, 0, 3, 2, 0, 1, 0)
        assertEquals(7, computeEmbedTargetCount(colorMapping, hasPaintData = true, toolRemapSlots = null, fallbackExtCount = 4))
    }

    @Test
    fun `computeEmbedTargetCount uses distinct count for per-object models`() {
        // Per-object models: targetCount = number of distinct physical slots.
        val toolRemap = listOf(2, 3)  // E3+E4
        assertEquals(2, computeEmbedTargetCount(colorMapping = null, hasPaintData = false, toolRemapSlots = toolRemap, fallbackExtCount = 4))
    }

    @Test
    fun `computeEmbedTargetCount falls back to extCount when no remap slots`() {
        assertEquals(4, computeEmbedTargetCount(colorMapping = null, hasPaintData = false, toolRemapSlots = null, fallbackExtCount = 4))
        assertEquals(1, computeEmbedTargetCount(colorMapping = null, hasPaintData = true, toolRemapSlots = null, fallbackExtCount = 1))
    }

    @Test
    fun `computeEmbedTargetCount handles 4-colour SEMM identity mapping`() {
        // Non-H2C SEMM model: 4 paint states mapped 1:1 to 4 extruders.
        val colorMapping = listOf(0, 1, 2, 3)
        assertEquals(4, computeEmbedTargetCount(colorMapping, hasPaintData = true, toolRemapSlots = null, fallbackExtCount = 4))
    }

    @Test
    fun `computeEmbedTargetCount pure-SEMM duplicate mapping uses distinct count`() {
        // Pure SEMM (no per-object extruders).  Embedding with distinct count
        // matches the physical slots exactly so GcodeToolRemapper distributes
        // tools correctly (regression guard for old.3mf baseline).
        val colorMapping = listOf(0, 0, 1, 1)
        assertEquals(2, computeEmbedTargetCount(colorMapping, hasPaintData = true, toolRemapSlots = null, fallbackExtCount = 2))
    }

    @Test
    fun `computeEmbedTargetCount old_3mf — 6 colours to 2 slots uses distinct`() {
        // Pure SEMM: keep the distinct slot count so the post-slice remap places
        // paint states onto the exact physical slots the user selected (no
        // interleaving into unused slots).  Regression guard for v1.5.69.
        val colorMapping = listOf(0, 2, 0, 2, 0, 2)
        assertEquals(2, computeEmbedTargetCount(colorMapping, hasPaintData = true, toolRemapSlots = null, fallbackExtCount = 2))
    }

    @Test
    fun `computeEmbedTargetCount Korok — 5 colours to 3 slots uses distinct`() {
        // Pure SEMM, no per-object extruders — keep distinct count.
        val colorMapping = listOf(0, 0, 1, 1, 3)
        assertEquals(3, computeEmbedTargetCount(colorMapping, hasPaintData = true, toolRemapSlots = null, fallbackExtCount = 3))
    }

    @Test
    fun `computeEmbedTargetCount B76 Goat hybrid paint+per-object uses full size`() {
        // Jon's Goat (Gray).3mf: paint data AND per-object extruder assignments.
        // hasMultiExtruderAssignments=true → preserve all 4 paint states so parts
        // with extruder="4" retain a valid slot; post-slice remap T3→T2 (E3).
        val colorMapping = listOf(0, 1, 2, 2)
        assertEquals(
            4,
            computeEmbedTargetCount(
                colorMapping, hasPaintData = true, toolRemapSlots = null,
                fallbackExtCount = 3, hasMultiExtruderAssignments = true
            )
        )
    }

    @Test
    fun `computeEmbedTargetCount B76 Goat pure-paint variant stays distinct`() {
        // Same colour mapping without per-object assignments — NOT a hybrid.
        // Pure SEMM with duplicate mapping → distinct count (preserves remap fidelity).
        val colorMapping = listOf(0, 1, 2, 2)
        assertEquals(
            3,
            computeEmbedTargetCount(
                colorMapping, hasPaintData = true, toolRemapSlots = null,
                fallbackExtCount = 3, hasMultiExtruderAssignments = false
            )
        )
    }

    @Test
    fun `mergeThreeMfInfo preserves hasPaintSupports from origInfo`() {
        val origInfo = ThreeMfInfo(
            objects = emptyList(), plates = emptyList(),
            isBambu = true, isMultiPlate = false,
            hasPaintSupports = true
        )
        val processedInfo = ThreeMfInfo(
            objects = emptyList(), plates = emptyList(),
            isBambu = false, isMultiPlate = false,
            hasPaintSupports = false  // parsed with skipPaintDetection=true
        )
        val merged = SlicerViewModel.mergeThreeMfInfo(processedInfo, origInfo)
        assertTrue("mergeThreeMfInfo must carry hasPaintSupports from origInfo", merged.hasPaintSupports)
    }

    @Test
    fun `mergeThreeMfInfoForPlate preserves hasPaintSupports from sourceInfo`() {
        val sourceInfo = ThreeMfInfo(
            objects = emptyList(), plates = emptyList(),
            isBambu = true, isMultiPlate = true,
            hasPaintSupports = true
        )
        val plateInfo = ThreeMfInfo(
            objects = emptyList(), plates = emptyList(),
            isBambu = false, isMultiPlate = false,
            hasPaintSupports = false  // lightweight parse always false
        )
        val merged = SlicerViewModel.mergeThreeMfInfoForPlate(plateInfo, sourceInfo)
        assertTrue("mergeThreeMfInfoForPlate must carry hasPaintSupports from sourceInfo", merged.hasPaintSupports)
    }
}
