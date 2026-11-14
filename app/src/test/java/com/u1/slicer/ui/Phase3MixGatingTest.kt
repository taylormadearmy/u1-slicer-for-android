package com.u1.slicer.ui

import com.u1.slicer.aipaint.SegmentationCascade
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Phase3MixGatingTest {
    private fun source(vararg paths: String): String = paths
        .map(::File)
        .firstOrNull(File::exists)
        ?.readText()
        ?: error("Source file not found: ${paths.joinToString()}")

    @Test
    fun mixEncodingRemainsFourSlot() {
        assertEquals(4, SegmentationCascade.TARGET_SLOTS)

        val source = source(
            "app/src/main/java/com/u1/slicer/SlicerViewModel.kt",
            "../app/src/main/java/com/u1/slicer/SlicerViewModel.kt",
            "src/main/java/com/u1/slicer/SlicerViewModel.kt",
        )
        assertTrue("ColorMix serialization must retain its four-slot encoding", source.contains("serialize(4)"))
    }

    @Test
    fun prepareAndFilamentMixEntryPointsRequireFourSlots() {
        val prepare = source(
            "app/src/main/java/com/u1/slicer/MainActivity.kt",
            "../app/src/main/java/com/u1/slicer/MainActivity.kt",
            "src/main/java/com/u1/slicer/MainActivity.kt",
        )
        assertTrue(prepare.contains("val mixesAvailable = activeMachineProfile.extruderCount =="))
        assertTrue(prepare.contains("availableMixes = if (mixesAvailable)"))
        assertTrue(prepare.contains("mixSlotsContent = if (mixesAvailable)"))

        val filaments = source(
            "app/src/main/java/com/u1/slicer/ui/FilamentScreen.kt",
            "../app/src/main/java/com/u1/slicer/ui/FilamentScreen.kt",
            "src/main/java/com/u1/slicer/ui/FilamentScreen.kt",
        )
        assertTrue(filaments.contains("val mixesAvailable = slotCount =="))
        assertTrue(filaments.contains("if (mixesAvailable && showMixDialog"))
        assertTrue(filaments.contains("if (mixesAvailable && mixedFilamentManager != null)"))
    }

    @Test
    fun aiPaintMixPaletteRequiresFourSlots() {
        val navigation = source(
            "app/src/main/java/com/u1/slicer/navigation/NavGraph.kt",
            "../app/src/main/java/com/u1/slicer/navigation/NavGraph.kt",
            "src/main/java/com/u1/slicer/navigation/NavGraph.kt",
        )
        assertTrue(navigation.contains("val mixesAvailable = numPhysical =="))
        assertTrue(navigation.contains("if (mixesAvailable && (createMixOpen || editMixRow != null))"))
        assertTrue(navigation.contains("projectMixes = if (mixesAvailable) projectMixes else emptyList()"))
        assertTrue(navigation.contains("onCreateMix = if (mixesAvailable)"))

        val resultScreen = source(
            "app/src/main/java/com/u1/slicer/ui/AiPaintResultScreen.kt",
            "../app/src/main/java/com/u1/slicer/ui/AiPaintResultScreen.kt",
            "src/main/java/com/u1/slicer/ui/AiPaintResultScreen.kt",
        )
        assertTrue(resultScreen.contains("if (mixesAvailable)"))
        assertTrue(resultScreen.contains("else emptyList()"))
    }
}
