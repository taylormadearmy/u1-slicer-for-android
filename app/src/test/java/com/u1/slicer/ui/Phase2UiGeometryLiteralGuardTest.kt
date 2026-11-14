package com.u1.slicer.ui

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class Phase2UiGeometryLiteralGuardTest {

    @Test
    fun uiGeometry_hasNoPrivateU1BedOrMidpointLiterals() {
        val relativePaths = listOf(
            "MainActivity.kt",
            "ui/PlacementViewerScreen.kt",
            "ui/PlateSelectDialog.kt",
            "ui/PrinterScreen.kt",
            "ui/ModelViewerScreen.kt",
            "ui/GcodeViewer3DScreen.kt",
            "ui/AiPaintHelpers.kt",
        )
        val literal = Regex("""\b(?:270|135)f\b|270(?: x 270){1,2}|270×270""")
        val offenders = relativePaths.filter { relativePath ->
            sourceFile(relativePath).readText().contains(literal)
        }

        assertTrue(
            "UI bed geometry must come from the active MachineProfile: $offenders",
            offenders.isEmpty(),
        )
    }

    private fun sourceFile(relativePath: String): File {
        val candidates = listOf(
            File("app/src/main/java/com/u1/slicer/$relativePath"),
            File("../app/src/main/java/com/u1/slicer/$relativePath"),
            File("src/main/java/com/u1/slicer/$relativePath"),
        )
        return candidates.firstOrNull { it.exists() }
            ?: error("$relativePath not found from ${File(".").absolutePath}")
    }
}
