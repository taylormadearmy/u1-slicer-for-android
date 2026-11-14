package com.u1.slicer

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class Phase3SlotLiteralGuardTest {
    @Test
    fun `profile-aware slot files contain no bare four-slot ranges or list sizes`() {
        val root = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
            .first { File(it, "app/src/main/java/com/u1/slicer/SlicerViewModel.kt").isFile }
        val relativePaths = listOf(
            "app/src/main/java/com/u1/slicer/SlicerViewModel.kt",
            "app/src/main/java/com/u1/slicer/MainActivity.kt",
            "app/src/main/java/com/u1/slicer/navigation/NavGraph.kt",
            "app/src/main/java/com/u1/slicer/ui/FilamentScreen.kt",
            "app/src/main/java/com/u1/slicer/ui/MultiColorDialog.kt",
            "app/src/main/java/com/u1/slicer/printer/SyncPreview.kt",
            "app/src/main/java/com/u1/slicer/gcode/PrintTimeRemap.kt",
            "app/src/main/java/com/u1/slicer/data/CanonicalColourRemap.kt",
        )
        val forbidden = listOf("0..3", "MutableList(4)")
        val violations = relativePaths.flatMap { relativePath ->
            File(root, relativePath).readLines().mapIndexedNotNull { index, line ->
                forbidden.firstOrNull(line::contains)?.let { literal ->
                    "$relativePath:${index + 1}: $literal"
                }
            }
        }

        assertTrue(
            "Use the active profile slot count. Keep fixed mix widths named through " +
                "SegmentationCascade.TARGET_SLOTS instead. Found: $violations",
            violations.isEmpty(),
        )
    }
}
