package com.u1.slicer.ui

import org.junit.Assert.assertTrue
import org.junit.Test

class FilamentMappingMixTest {
    private val src: String = listOf(
        "app/src/main/java/com/u1/slicer/ui/FilamentMappingDialog.kt",
        "src/main/java/com/u1/slicer/ui/FilamentMappingDialog.kt",
        "../app/src/main/java/com/u1/slicer/ui/FilamentMappingDialog.kt",
    ).map { java.io.File(it) }.firstOrNull { it.exists() }?.readText() ?: error("not found")

    @Test fun offersMixSlots() {
        assertTrue("object/part assigner must offer mixes via FilamentMixChipRow",
            src.contains("FilamentMixChipRow("))
    }
}
