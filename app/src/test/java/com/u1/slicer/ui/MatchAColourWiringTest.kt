package com.u1.slicer.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class MatchAColourWiringTest {
    private fun dialogSrc(): String = listOf(
        "app/src/main/java/com/u1/slicer/ui/CreateMixSlotDialog.kt",
        "../app/src/main/java/com/u1/slicer/ui/CreateMixSlotDialog.kt",
        "src/main/java/com/u1/slicer/ui/CreateMixSlotDialog.kt",
    ).map { File(it) }.firstOrNull { it.exists() }?.readText() ?: error("CreateMixSlotDialog.kt not found")

    @Test fun hasMatchAColourEntry() {
        val s = dialogSrc()
        assertTrue("dialog must offer a Match-a-colour action", s.contains("Match a colour", ignoreCase = true))
        assertTrue("dialog must call the reverse matcher", s.contains("MixColourMatcher.bestMix"))
    }

    @Test fun hasCountSelectorAndBadge() {
        val s = dialogSrc()
        assertTrue("count selector", s.contains("matchCount") || Regex("""\b2\s*,\s*3\s*,\s*4\b""").containsMatchIn(s))
        assertTrue("closeness badge from deltaE", s.contains("deltaE"))
        assertTrue("single-filament note", s.contains("closestSingleFilament"))
    }
}
