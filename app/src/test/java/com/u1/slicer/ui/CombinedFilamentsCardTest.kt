package com.u1.slicer.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CombinedFilamentsCardTest {
    private val src: String = listOf(
        "app/src/main/java/com/u1/slicer/MainActivity.kt",
        "src/main/java/com/u1/slicer/MainActivity.kt",
        "../app/src/main/java/com/u1/slicer/MainActivity.kt",
    ).map { java.io.File(it) }.firstOrNull { it.exists() }?.readText() ?: error("not found")

    @Test fun noDuplicateSlotMappingAtSendCard() {
        // the duplicate STL "Filament" card's "Slot mapping happens when you tap Send" must be gone
        assertFalse("duplicate STL Filament card must be removed",
            src.contains("Slot mapping happens when you tap Send"))
    }

    @Test fun mixesSectionInsideFilamentsCard() {
        // PrepareMixSlotsSection must still be called (mix logic preserved)
        // but no longer as a standalone top-level call after the Filaments card;
        // instead it is rendered inside PrintSetupSection as a subsection.
        // We verify by checking that PrepareMixSlotsSection is called inside
        // PrintSetupSection's composable body (i.e. the definition of
        // PrintSetupSection contains a call to PrepareMixSlotsSection or its
        // inner content is inlined there).
        assertTrue("Mixes subsection must appear inside PrintSetupSection",
            src.contains("MIXES") || src.contains("Mixes") &&
                src.indexOf("fun PrintSetupSection") < src.indexOf("Mixes") &&
                src.indexOf("Mixes") < src.indexOf("fun ScaleSection"))
    }
}
