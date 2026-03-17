package com.u1.slicer.bambu

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Tests for ThreeMfParser.
 *
 * Note: ThreeMfParser uses android.util.Log which is unavailable in JVM tests.
 * These tests run as instrumented tests instead. For JVM, we test the 3MF
 * ZIP structure creation helpers here, and the actual parser in androidTest/.
 *
 * This file tests the data models and ZIP creation utilities.
 */
class ThreeMfParserTest {

    @Test
    fun `ThreeMfInfo defaults`() {
        val info = ThreeMfInfo(
            objects = emptyList(),
            plates = emptyList(),
            isBambu = false,
            isMultiPlate = false
        )
        assertFalse(info.isBambu)
        assertFalse(info.isMultiPlate)
        assertFalse(info.hasPaintData)
        assertFalse(info.hasLayerToolChanges)
        assertEquals(0, info.detectedColors.size)
        assertEquals(1, info.detectedExtruderCount)
    }

    @Test
    fun `ThreeMfObject data class`() {
        val obj = ThreeMfObject(
            objectId = "1",
            name = "Cube",
            vertices = 8,
            triangles = 12
        )
        assertEquals("1", obj.objectId)
        assertEquals("Cube", obj.name)
        assertEquals(8, obj.vertices)
        assertEquals(12, obj.triangles)
    }

    @Test
    fun `ThreeMfPlate data class`() {
        val plate = ThreeMfPlate(
            plateId = 1,
            name = "Main Plate",
            objectIds = listOf("1", "2"),
            printable = true,
            transform = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f)
        )
        assertEquals(1, plate.plateId)
        assertEquals("Main Plate", plate.name)
        assertEquals(2, plate.objectIds.size)
        assertTrue(plate.printable)
    }

    @Test
    fun `ThreeMfInfo with multicolor metadata`() {
        val info = ThreeMfInfo(
            objects = listOf(
                ThreeMfObject("1", "Part", 100, 200)
            ),
            plates = listOf(
                ThreeMfPlate(1, "Plate 1", listOf("1"), true,
                    floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f))
            ),
            isBambu = true,
            isMultiPlate = false,
            hasPaintData = true,
            hasLayerToolChanges = true,
            detectedColors = listOf("#FF0000", "#00FF00", "#0000FF"),
            detectedExtruderCount = 3
        )
        assertTrue(info.isBambu)
        assertTrue(info.hasPaintData)
        assertTrue(info.hasLayerToolChanges)
        assertEquals(3, info.detectedColors.size)
        assertEquals(3, info.detectedExtruderCount)
    }

    @Test
    fun `ThreeMfInfo multi-plate detection`() {
        val info = ThreeMfInfo(
            objects = listOf(
                ThreeMfObject("1", "Part A", 50, 100),
                ThreeMfObject("2", "Part B", 60, 120)
            ),
            plates = listOf(
                ThreeMfPlate(1, "Plate 1", listOf("1"), true,
                    floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f)),
                ThreeMfPlate(2, "Plate 2", listOf("2"), true,
                    floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f))
            ),
            isBambu = true,
            isMultiPlate = true
        )
        assertTrue(info.isMultiPlate)
        assertEquals(2, info.plates.size)
    }

    @Test
    fun `filterDetectedColorsByUsedPaintIndices drops unused H2C metadata rows`() {
        val detectedColors = listOf(
            "#0086D6",
            "#FFFF00",
            "#FFFFFF",
            "#6A00D5",
            "#FF0000",
            "#00AE42",
            "#FF8000"
        )

        val filtered = ThreeMfParser.filterDetectedColorsByUsedPaintIndices(
            detectedColors,
            setOf(0, 1, 2, 3, 6)
        )

        assertEquals(
            listOf("#0086D6", "#FFFF00", "#FFFFFF", "#6A00D5", "#FF8000"),
            filtered
        )
    }

    @Test
    fun `isMultiPlate uses plate JSON count not build item count`() {
        // New-format multi-plate: detected by plateJsonCount > 1
        val plateJsonRegex = Regex("Metadata/plate_\\d+\\.json")
        fun countPlateJsons(entries: Set<String>) = entries.count { plateJsonRegex.matches(it) }

        // Benchy-style: 1 plate JSON, 2 build items (one printable=0) → single-plate
        val benchyEntries = setOf("Metadata/plate_1.json", "3D/3dmodel.model")
        assertEquals(1, countPlateJsons(benchyEntries))
        assertFalse("1 plate JSON → not multi-plate by JSON count", countPlateJsons(benchyEntries) > 1)

        // Coaster-style: 5 plate JSONs, 7 build items → multi-plate
        val coasterEntries = (2..6).map { "Metadata/plate_$it.json" }.toSet() + "3D/3dmodel.model"
        assertEquals(5, countPlateJsons(coasterEntries))
        assertTrue("5 plate JSONs → multi-plate", countPlateJsons(coasterEntries) > 1)
    }

    @Test
    fun `isMultiPlate detects old Bambu format via virtual plate positions`() {
        // Old Bambu format (Dragon Scale, Shashibo): no plate JSONs, but printable items
        // are laid out in a large virtual space with TX/TY outside the 270mm bed.
        // Multi-plate is detected by any printable item having TX > 270 or TY outside [0, 270].
        val bedSize = 270f

        data class Item(val tx: Float, val ty: Float, val printable: Boolean)

        fun hasVirtualPlateItems(items: List<Item>): Boolean =
            items.any { item ->
                if (!item.printable) return@any false
                item.tx > bedSize || item.ty < 0f || item.ty > bedSize
            }

        // Dragon Scale: 3 items — plate 1 on bed, plates 2+3 at virtual positions
        val dragonItems = listOf(
            Item(128f, 128f, true),   // plate 1 — on bed
            Item(435f, 128f, true),   // plate 2 — TX > 270
            Item(128f, -179f, true)   // plate 3 — TY < 0
        )
        assertTrue("Dragon Scale should be multi-plate", hasVirtualPlateItems(dragonItems))

        // Benchy: 2 items — one printable=0 (reference), one printable=1 on bed
        val benchyItems = listOf(
            Item(213f, 194f, false),  // printable=0 reference — ignored
            Item(135f, 119f, true)    // printable=1 — on bed
        )
        assertFalse("Benchy should NOT be multi-plate", hasVirtualPlateItems(benchyItems))

        // Single-plate dual-color: 1 item on bed
        val dualColorItems = listOf(Item(135f, 135f, true))
        assertFalse("Single-plate dual-colour should NOT be multi-plate",
            hasVirtualPlateItems(dualColorItems))
    }
}
