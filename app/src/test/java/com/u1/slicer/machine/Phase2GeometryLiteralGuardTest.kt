package com.u1.slicer.machine

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Phase2GeometryLiteralGuardTest {

    @Test
    fun geometryCode_hasNoPrivateU1BedLiteral() {
        val relativePaths = listOf(
            "model/CopyArrangeCalculator.kt",
            "model/MultiStlCombiner.kt",
            "model/ModelScaleConverter.kt",
            "gcode/GcodeValidator.kt",
            "gcode/GcodeLayer.kt",
        )
        val offenders = relativePaths.filter { relativePath ->
            sourceFile(relativePath).readText().contains(Regex("""\\b270f\\b"""))
        }

        assertTrue(
            "Bed geometry must come from MachineProfile, not private 270f literals: $offenders",
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
