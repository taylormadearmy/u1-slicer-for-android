package com.u1.slicer.nativesrc

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * D5: embedded app-authored profiles must be able to override every native
 * setting written by applyConfigToPrusa().  A missing whitelist entry silently
 * discards an Orca preset value, which is particularly dangerous now that
 * profiles are no longer U1-only.
 */
class ProfileKeyCoverageTest {

    private val nonOverridableKeys = setOf("mixed_filament_definitions")

    @Test
    fun profileKeys_coverEveryApplyConfigWrite() {
        val source = nativeSource()
        val profileKeys = quotedStrings(blockFor(source, "static const char* profile_keys[]"))
        val applyConfig = blockFor(source, "static void applyConfigToPrusa(")
        val writes = Regex("""dpc\.(?:set_key_value|set_deserialize)\(\s*"([^"]+)["]""")
            .findAll(applyConfig)
            .map { it.groupValues[1] }
            .toSet()

        val missing = (writes - nonOverridableKeys) - profileKeys
        assertTrue(
            "profile_keys[] must cover every quoted applyConfigToPrusa write except " +
                "NON_OVERRIDABLE_KEYS=$nonOverridableKeys. Missing: $missing",
            missing.isEmpty()
        )
    }

    @Test
    fun profileKeys_includePrintableHeight() {
        val keys = quotedStrings(blockFor(nativeSource(), "static const char* profile_keys[]"))
        assertTrue("printable_height must be admitted from authored profiles", "printable_height" in keys)
    }

    private fun nativeSource(): String {
        val candidates = listOf(
            File("app/src/main/cpp/src/sapil_print.cpp"),
            File("../app/src/main/cpp/src/sapil_print.cpp"),
            File("src/main/cpp/src/sapil_print.cpp"),
        )
        return candidates.firstOrNull { it.exists() }?.readText()
            ?: error("sapil_print.cpp not found from ${File(".").absolutePath}")
    }

    private fun blockFor(source: String, marker: String): String {
        val start = source.indexOf(marker)
        require(start >= 0) { "$marker not found" }
        val brace = source.indexOf('{', start)
        require(brace >= 0) { "$marker has no opening brace" }
        var depth = 0
        for (index in brace until source.length) {
            when (source[index]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return source.substring(brace, index + 1)
            }
        }
        error("$marker has no closing brace")
    }

    private fun quotedStrings(source: String): Set<String> =
        Regex("""\"([^\"]+)\"""").findAll(source).map { it.groupValues[1] }.toSet()
}
