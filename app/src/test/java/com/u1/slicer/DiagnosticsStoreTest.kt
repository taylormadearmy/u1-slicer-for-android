package com.u1.slicer

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsStoreTest {

    @Test
    fun `trimToMax keeps tail when history exceeds limit`() {
        val trimmed = DiagnosticsStore.trimToMax(
            listOf("a", "b", "c", "d"),
            maxEntries = 2
        )
        assertEquals(listOf("c", "d"), trimmed)
    }

    @Test
    fun `trimToMax keeps all lines when within limit`() {
        val trimmed = DiagnosticsStore.trimToMax(
            listOf("a", "b"),
            maxEntries = 5
        )
        assertEquals(listOf("a", "b"), trimmed)
    }

    @Test
    fun `classifyRestartObservation detects fresh process from pid change`() {
        val status = DiagnosticsStore.classifyRestartObservation(
            previousSessionId = "old",
            previousPid = 100,
            previousNativeGeneration = "native-old",
            currentSessionId = "new",
            currentPid = 101,
            currentNativeGeneration = "native-new"
        )
        assertEquals("fresh_process", status)
    }

    @Test
    fun `classifyRestartObservation detects same process when markers match`() {
        val status = DiagnosticsStore.classifyRestartObservation(
            previousSessionId = "same",
            previousPid = 100,
            previousNativeGeneration = "native-same",
            currentSessionId = "same",
            currentPid = 100,
            currentNativeGeneration = "native-same"
        )
        assertEquals("same_process_or_unknown", status)
    }

    @Test
    fun `classifyRestartObservation handles missing request marker`() {
        val status = DiagnosticsStore.classifyRestartObservation(
            previousSessionId = null,
            previousPid = null,
            previousNativeGeneration = null,
            currentSessionId = "current",
            currentPid = 200,
            currentNativeGeneration = "native-current"
        )
        assertTrue(status == "not_requested")
    }

    @Test
    fun `bambu timeline includes only redacted bambu events`() {
        val bambu = JSONObject()
            .put("type", "bambu_upload_failed")
            .put("timestampMs", 1_700_000_000_000L)
            .put("model", "P1S")
            .put("printerId", "abc123")
            .put("errorCategory", "tls")
            .toString()
        val unrelated = JSONObject()
            .put("type", "slice_output_validation")
            .put("timestampMs", 1_700_000_000_001L)
            .toString()

        val lines = DiagnosticsStore.bambuTimelineLines(listOf("bad json", unrelated, bambu))

        assertEquals(1, lines.size)
        assertTrue(lines.single().contains("bambu_upload_failed"))
        assertTrue(lines.single().contains("model=P1S"))
        assertTrue(lines.single().contains("errorCategory=tls"))
    }

    @Test
    fun `bambu provenance timeline exposes precedence counts for kotlin and native events`() {
        val kotlinEvent = JSONObject()
            .put("type", "bambu_config_provenance")
            .put("timestampMs", 1_700_000_000_000L)
            .put("target", "BambuH2D")
            .put("sourceValueCount", 42)
            .put("targetReplacementCount", 9)
            .put("explicitOverrideCount", 2)
            .toString()
        val nativeEvent = JSONObject()
            .put("type", "bambu_config_provenance")
            .put("timestampMs", 1_700_000_000_001L)
            .put("payload", JSONObject()
                .put("target", "BAMBU_H2D")
                .put("safeSourceKeysApplied", 37))
            .toString()

        val lines = DiagnosticsStore.bambuTimelineLines(listOf(kotlinEvent, nativeEvent))

        assertTrue(lines[0].contains("target=BambuH2D"))
        assertTrue(lines[0].contains("sourceValueCount=42"))
        assertTrue(lines[0].contains("explicitOverrideCount=2"))
        assertTrue(lines[1].contains("target=BAMBU_H2D"))
        assertTrue(lines[1].contains("safeSourceKeysApplied=37"))
    }
}
