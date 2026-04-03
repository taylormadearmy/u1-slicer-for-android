package com.u1.slicer.viewer

import com.u1.slicer.gcode.*
import org.junit.Assert.*
import org.junit.Test

class VGCodeDataMappingTest {

    @Test
    fun `mapMoveType returns correct EMoveType ordinals`() {
        assertEquals(10.toByte(), VGCodeNative.mapMoveType(MoveType.EXTRUDE))  // EMoveType::Extrude
        assertEquals(8.toByte(), VGCodeNative.mapMoveType(MoveType.TRAVEL))    // EMoveType::Travel
    }

    @Test
    fun `mapRole maps all FeatureType constants`() {
        assertEquals(2.toByte(), VGCodeNative.mapRole(FeatureType.OUTER_WALL))       // ExternalPerimeter
        assertEquals(1.toByte(), VGCodeNative.mapRole(FeatureType.INNER_WALL))       // Perimeter
        assertEquals(4.toByte(), VGCodeNative.mapRole(FeatureType.SPARSE_INFILL))    // InternalInfill
        assertEquals(5.toByte(), VGCodeNative.mapRole(FeatureType.SOLID_INFILL))     // SolidInfill
        assertEquals(6.toByte(), VGCodeNative.mapRole(FeatureType.TOP_SURFACE))      // TopSolidInfill
        assertEquals(5.toByte(), VGCodeNative.mapRole(FeatureType.BOTTOM_SURFACE))   // SolidInfill
        assertEquals(11.toByte(), VGCodeNative.mapRole(FeatureType.SUPPORT))         // SupportMaterial
        assertEquals(12.toByte(), VGCodeNative.mapRole(FeatureType.SUPPORT_INTERFACE)) // SupportMaterialInterface
        assertEquals(13.toByte(), VGCodeNative.mapRole(FeatureType.PRIME_TOWER))     // WipeTower
        assertEquals(8.toByte(), VGCodeNative.mapRole(FeatureType.BRIDGE))           // BridgeInfill
        assertEquals(10.toByte(), VGCodeNative.mapRole(FeatureType.SKIRT))           // Skirt
        assertEquals(14.toByte(), VGCodeNative.mapRole(FeatureType.OTHER))           // Custom
    }

    @Test
    fun `packToolColors uses defaults for empty input`() {
        val colors = VGCodeNative.packToolColors(emptyList())
        assertEquals(4, colors.size)
        assertEquals(0xFF9900, colors[0])  // orange
        assertEquals(0x33B3FF, colors[1])  // blue
    }

    @Test
    fun `packToolColors overrides with hex strings`() {
        val colors = VGCodeNative.packToolColors(listOf("#FF0000", "#00FF00"))
        assertEquals(0xFF0000, colors[0])
        assertEquals(0x00FF00, colors[1])
        assertEquals(0x00E666, colors[2])  // default green
        assertEquals(0xE63380, colors[3])  // default pink
    }

    @Test
    fun `packToolColors handles missing hash prefix`() {
        val colors = VGCodeNative.packToolColors(listOf("AABBCC"))
        assertEquals(0xAABBCC, colors[0])
    }

    @Test
    fun `packToolColors ignores blank entries`() {
        val colors = VGCodeNative.packToolColors(listOf("", "#00FF00"))
        assertEquals(0xFF9900, colors[0])  // unchanged default
        assertEquals(0x00FF00, colors[1])  // overridden
    }
}
