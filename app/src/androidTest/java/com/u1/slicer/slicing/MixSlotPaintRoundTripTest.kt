package com.u1.slicer.slicing

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.u1.slicer.NativeLibrary
import com.u1.slicer.aipaint.AiRegion
import com.u1.slicer.aipaint.PaintedMeshWriter
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * M3-B/F2 GATE — proves the engine actually decodes mix-slot paint_color codes
 * (slots >= 4 → states >= 5) back to the correct paint states after a real
 * [PaintedMeshWriter.write] + [NativeLibrary.loadModel] round-trip.
 *
 * The encoder (PaintedMeshWriterSlotEncodingTest) only asserts the bitstream
 * string. This test closes the loop: it writes a painted 3MF whose triangles
 * are painted to physical slot 0 (state 1) and mix slots 4 + 5 (states 5 + 6),
 * loads it through the native BBS importer, and reads back the decoded paint
 * states via nativeGetPaintStateCounts(kind=0, mmu_segmentation_facets).
 *
 * count_paint_states uses has_facets() with DIRECT state equality (no H2C fold —
 * see sapil_bambu_snapshot.cpp:299-324), so a triangle written as paint state 5
 * MUST read back as state 5, not folded to state 1. If the engine rejected or
 * remapped the mix codes, the expected states would be absent → GATE FAILS.
 */
@RunWith(AndroidJUnit4::class)
class MixSlotPaintRoundTripTest {

    private lateinit var lib: NativeLibrary
    private lateinit var out3mf: File

    @Before
    fun setup() {
        assertTrue("Native library must be loaded on device (arm64 required)", NativeLibrary.isLoaded)
        lib = NativeLibrary()
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        out3mf = File(ctx.cacheDir, "mix_slot_roundtrip_${System.currentTimeMillis()}.3mf")
    }

    @After
    fun teardown() {
        lib.clearModel()
        out3mf.delete()
    }

    /**
     * One non-degenerate triangle in the z=0 plane, offset along +X by [ox] so
     * each triangle is a distinct facet (the BBS importer drops exact duplicates).
     */
    private fun triAt(ox: Float): FloatArray = floatArrayOf(
        ox + 0f, 0f, 0f,
        ox + 2f, 0f, 0f,
        ox + 1f, 2f, 0f,
    )

    @Test
    fun mixSlotPaint_survivesNativeLoad_asStates5and6() {
        // 6 triangles: 2 painted to physical slot 0 (state 1), 2 to mix slot 4
        // (state 5), 2 to mix slot 5 (state 6). numPhysical = 4.
        val regionIds = intArrayOf(0, 0, 4, 4, 5, 5)
        val positions = (regionIds.indices)
            .flatMap { i -> triAt(i * 4f).toList() }
            .toFloatArray()

        // 4 physical slot regions + 2 mix display colours -> filament_colour length 6.
        val regions = (0..3).map { s ->
            AiRegion(id = s, label = "Slot ${s + 1}", suggestedColour = "#888888", slot = s)
        }
        val printerColours = listOf("#000000", "#FF0000", "#00FF00", "#0000FF")
        val mixDisplayColours = listOf("#808000", "#800080") // E1+E2 blend, E1+E3 blend

        PaintedMeshWriter.write(
            positions = positions,
            regionIds = regionIds,
            regions = regions,
            outputFile = out3mf,
            printerColours = printerColours,
            mixDisplayColours = mixDisplayColours,
        )
        assertTrue("painted 3MF must be written", out3mf.length() > 0)

        assertTrue("loadModel must succeed for the painted 3MF", lib.loadModel(out3mf.absolutePath))

        val objectCount = lib.nativeGetObjectCount()
        assertTrue("expected at least one object, got $objectCount", objectCount >= 1)

        // Aggregate decoded mmu paint states across every volume of every object.
        val seenStates = mutableSetOf<Int>()
        for (oi in 0 until objectCount) {
            val vc = lib.nativeGetVolumeCount(oi)
            for (vi in 0 until vc) {
                val packed = lib.nativeGetPaintStateCounts(oi, vi, 0)
                assertNotNull("mmu paint counts must be non-null (oi=$oi,vi=$vi)", packed)
                packed!!
                var i = 0
                while (i < packed.size) {
                    val state = packed[i]
                    val count = packed[i + 1]
                    assertTrue("count must be > 0 for state $state", count > 0)
                    seenStates += state
                    i += 2
                }
            }
        }

        // GATE: mix slots 4 and 5 must decode to engine paint states 5 and 6.
        // If the engine had folded/clamped them to 1..4 these would be absent.
        assertTrue(
            "GATE: mix slot 4 must decode to paint state 5. Observed states: $seenStates",
            seenStates.contains(5),
        )
        assertTrue(
            "GATE: mix slot 5 must decode to paint state 6. Observed states: $seenStates",
            seenStates.contains(6),
        )
        // Physical slot 0 must still decode to state 1 (no regression to the base encoding).
        assertTrue(
            "physical slot 0 must decode to paint state 1. Observed states: $seenStates",
            seenStates.contains(1),
        )
    }
}
