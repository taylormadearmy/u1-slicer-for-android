package com.u1.slicer.viewer

import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Phase2BedRenderingTest {

    @Test
    fun genericBedFill_isAProfileSizedRectangle() {
        assertArrayEquals(
            floatArrayOf(
                0f, 0f, 0f,
                300f, 0f, 0f,
                300f, 180f, 0f,
                0f, 0f, 0f,
                300f, 180f, 0f,
                0f, 180f, 0f,
            ),
            BedDrawable.rectangleVertices(300f, 180f),
            0f,
        )
    }

    @Test
    fun modelRenderer_gatesSnapmakerLogoByMachineFamily() {
        val source = source("viewer/ModelRenderer.kt")

        assertTrue(
            "Logo setup must be guarded by the active machine family",
            Regex("if \\(machineFamily == MachineFamily\\.SNAPMAKER\\) \\{\\s*setupLogoTexture\\(\\)")
                .containsMatchIn(source),
        )
        assertTrue(
            "Logo drawing must be guarded by the active machine family",
            Regex("if \\(machineFamily == MachineFamily\\.SNAPMAKER\\) \\{\\s*drawLogo\\(")
                .containsMatchIn(source),
        )
    }

    @Test
    fun gcodeRenderer_centresEmptyBedFromProfileDimensions() {
        val source = source("viewer/GcodeRenderer.kt")

        assertFalse("G-code camera must not target the U1 midpoint literal", source.contains("135.0"))
        assertTrue(source.contains("bedSizeXmm / 2.0"))
        assertTrue(source.contains("bedSizeYmm / 2.0"))
        assertTrue(
            "Reapplying the same profile must not reset a user-restored camera",
            source.contains("if (profile != bedProfile)"),
        )
    }

    private fun source(relativePath: String): String {
        val candidates = listOf(
            File("app/src/main/java/com/u1/slicer/$relativePath"),
            File("../app/src/main/java/com/u1/slicer/$relativePath"),
            File("src/main/java/com/u1/slicer/$relativePath"),
        )
        return (candidates.firstOrNull { it.exists() }
            ?: error("$relativePath not found from ${File(".").absolutePath}")).readText()
    }
}
