package com.u1.slicer.aipaint

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * M3-B/F2: unit coverage for [PaintedMeshWriter.encodePaintColor] — the OrcaSlicer
 * TriangleSelector leaf-triangle bitstream encoding (right-to-left nibble read).
 *
 * Slots 0..3 reproduce the historical 4-state table (verbatim parity with the old
 * PAINT_COLOR array); slots >=4 extend into mix states 5..18 via the 0xC escape.
 * The native round-trip (MixSlotPaintRoundTripTest) proves the engine decodes these.
 */
class PaintedMeshWriterSlotEncodingTest {
    @Test fun matchesDocumentedStates1to4() {
        assertEquals("4",  PaintedMeshWriter.encodePaintColor(0))
        assertEquals("8",  PaintedMeshWriter.encodePaintColor(1))
        assertEquals("0C", PaintedMeshWriter.encodePaintColor(2))
        assertEquals("1C", PaintedMeshWriter.encodePaintColor(3))
    }

    @Test fun extendsToMixStates5to8() {
        assertEquals("2C", PaintedMeshWriter.encodePaintColor(4))
        assertEquals("3C", PaintedMeshWriter.encodePaintColor(5))
        assertEquals("4C", PaintedMeshWriter.encodePaintColor(6))
        assertEquals("5C", PaintedMeshWriter.encodePaintColor(7))
    }

    @Test fun distinctCodesNoTruncation() {
        val codes = (0..11).map { PaintedMeshWriter.encodePaintColor(it) }
        assertEquals(codes.size, codes.toSet().size)
    }
}
