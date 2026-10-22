package com.u1.slicer

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SlicingProgressConfigurationTest {

    @Test
    fun `slicing elapsed time survives activity recreation`() {
        val source = File("src/main/java/com/u1/slicer/MainActivity.kt").readText()
        val card = source.substringAfter("fun SlicingProgressCard").substringBefore("@Composable")

        assertTrue(
            "Slicing progress must save elapsed time across orientation changes",
            card.contains("rememberSaveable") && card.contains("elapsedSeconds"),
        )
    }
}
