package com.u1.slicer

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
}
