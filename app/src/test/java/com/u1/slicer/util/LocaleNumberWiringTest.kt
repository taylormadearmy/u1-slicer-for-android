package com.u1.slicer.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * B139 wiring guard: every user-typed decimal field must parse via the locale-tolerant
 * [toFloatLenient] (accepts ',' or '.'), NOT the locale-independent `toFloatOrNull` that
 * silently drops European comma input. The parse behaviour itself is unit-tested in
 * [LocaleNumbersTest]; this guards that the helper is actually wired into the input sites
 * (no Compose UI harness in the project), and that the machine-format parser is left alone.
 */
class LocaleNumberWiringTest {

    private fun src(relative: String): String {
        val candidates = listOf(
            File("app/src/main/java/$relative"),
            File("../app/src/main/java/$relative"),
            File("src/main/java/$relative"),
        )
        return (candidates.firstOrNull { it.exists() }
            ?: error("$relative not found from ${File(".").absolutePath}")).readText()
    }

    @Test
    fun `all user-typed decimal screens use toFloatLenient`() {
        for (rel in listOf(
            "com/u1/slicer/ui/SlicingOverridesUI.kt",
            "com/u1/slicer/ui/SettingsScreen.kt",
            "com/u1/slicer/ui/FilamentScreen.kt",
            "com/u1/slicer/ui/EditPanel.kt",
            "com/u1/slicer/MainActivity.kt",
        )) {
            assertTrue(
                "$rel must parse user-entered decimals with toFloatLenient (B139)",
                src(rel).contains("toFloatLenient(")
            )
        }
    }

    @Test
    fun `OverrideFloatField parses with toFloatLenient not toFloatOrNull`() {
        val s = src("com/u1/slicer/ui/SlicingOverridesUI.kt")
        val start = s.indexOf("fun OverrideFloatField(")
        require(start >= 0) { "OverrideFloatField not found" }
        val bodyStart = s.indexOf('{', s.indexOf(')', start))
        var depth = 0; var i = bodyStart; var end = -1
        while (i < s.length) {
            when (s[i]) { '{' -> depth++; '}' -> { depth--; if (depth == 0) { end = i + 1; break } } }
            i++
        }
        val body = s.substring(bodyStart, end)
        assertTrue("OverrideFloatField must use toFloatLenient", body.contains("toFloatLenient("))
        assertFalse(
            "OverrideFloatField must NOT use locale-independent toFloatOrNull (B139 regression)",
            body.contains("toFloatOrNull(")
        )
    }

    @Test
    fun `machine-format Bambu profile parsing stays locale-independent`() {
        // extractBambuValue reads '.'-formatted profile/config values, not user input — it must
        // keep toFloatOrNull (lenient parsing there would be wrong/unnecessary). Guard the exclusion.
        val s = src("com/u1/slicer/ui/FilamentScreen.kt")
        assertTrue(
            "extractBambuValue(...) profile parsing must remain toFloatOrNull (locale-independent)",
            Regex("extractBambuValue\\([^)]*\\)[^\\n]*toFloatOrNull\\(").containsMatchIn(s)
        )
    }
}
