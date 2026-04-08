package com.u1.slicer.gcode

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Tests for [buildSuspiciousModelLineContexts] — the extracted utility that
 * fetches ±2-line context windows around suspicious G-code line numbers.
 *
 * B52: The old implementation called File.readLines() which OOMs on 115 MB
 * G-code files (~3.7 M lines).  The new implementation must stream.
 */
class SuspiciousLineContextTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun writeNumberedGcode(lineCount: Int): File {
        val file = tempFolder.newFile("large.gcode")
        file.bufferedWriter().use { w ->
            for (i in 1..lineCount) {
                w.write("G1 X${i % 270} Y${i % 270} E${i * 0.01}  ; line $i")
                w.newLine()
            }
        }
        return file
    }

    @Test
    fun `returns correct context window for middle line`() {
        // 20-line file, suspicious sample at line 10 — should return lines 8–12
        val file = writeNumberedGcode(20)
        val samples = listOf(
            GcodeValidator.MoveSample(
                x0 = 0f, y0 = 0f, x1 = 1f, y1 = 1f,
                featureType = FeatureType.OUTER_WALL,
                lineNumber = 10,
                featureLabel = "Outer wall"
            )
        )

        val contexts = buildSuspiciousModelLineContexts(file, samples)

        assertEquals(1, contexts.size)
        val ctx = contexts[0]
        assertEquals(10, ctx["lineNumber"])
        @Suppress("UNCHECKED_CAST")
        val lines = ctx["lines"] as List<Map<String, Any?>>
        assertEquals(5, lines.size) // lines 8,9,10,11,12
        assertEquals(8, lines[0]["lineNumber"])
        assertEquals(12, lines[4]["lineNumber"])
        // Verify actual line content is present
        assertTrue((lines[2]["text"] as String).contains("line 10"))
    }

    @Test
    fun `returns empty for empty samples`() {
        val file = writeNumberedGcode(10)
        val contexts = buildSuspiciousModelLineContexts(file, emptyList())
        assertTrue(contexts.isEmpty())
    }

    @Test
    fun `clamps window at start of file`() {
        val file = writeNumberedGcode(10)
        val samples = listOf(
            GcodeValidator.MoveSample(
                x0 = 0f, y0 = 0f, x1 = 1f, y1 = 1f,
                featureType = FeatureType.OUTER_WALL,
                lineNumber = 1,
                featureLabel = "Outer wall"
            )
        )

        val contexts = buildSuspiciousModelLineContexts(file, samples)

        assertEquals(1, contexts.size)
        @Suppress("UNCHECKED_CAST")
        val lines = contexts[0]["lines"] as List<Map<String, Any?>>
        assertEquals(1, lines[0]["lineNumber"])
        // Window: 1,2,3 (can't go before line 1)
        assertEquals(3, lines.size)
    }

    @Test
    fun `clamps window at end of file`() {
        val file = writeNumberedGcode(10)
        val samples = listOf(
            GcodeValidator.MoveSample(
                x0 = 0f, y0 = 0f, x1 = 1f, y1 = 1f,
                featureType = FeatureType.OUTER_WALL,
                lineNumber = 10,
                featureLabel = "Outer wall"
            )
        )

        val contexts = buildSuspiciousModelLineContexts(file, samples)

        assertEquals(1, contexts.size)
        @Suppress("UNCHECKED_CAST")
        val lines = contexts[0]["lines"] as List<Map<String, Any?>>
        assertEquals(10, lines.last()["lineNumber"])
    }

    @Test
    fun `handles multiple distinct line numbers (max 3)`() {
        val file = writeNumberedGcode(100)
        val samples = (1..5).map { i ->
            GcodeValidator.MoveSample(
                x0 = 0f, y0 = 0f, x1 = 1f, y1 = 1f,
                featureType = FeatureType.OUTER_WALL,
                lineNumber = i * 20,
                featureLabel = "Outer wall"
            )
        }

        val contexts = buildSuspiciousModelLineContexts(file, samples)

        // Should cap at 3 distinct line numbers
        assertEquals(3, contexts.size)
    }

    @Test
    fun `does not load entire file into memory`() {
        // Generate a file with 200_000 lines (~6 MB).  The old readLines() approach
        // would allocate a List<String> for all of them.  The streaming version should
        // only hold a small rolling buffer.  We can't directly assert memory usage in a
        // unit test, but we CAN verify the function still returns correct results for a
        // file this size — if it tried readLines() it would succeed here but OOM on the
        // real 3.7 M line file.  This test is a functional smoke-test.
        val file = writeNumberedGcode(200_000)
        val samples = listOf(
            GcodeValidator.MoveSample(
                x0 = 0f, y0 = 0f, x1 = 1f, y1 = 1f,
                featureType = FeatureType.OUTER_WALL,
                lineNumber = 150_000,
                featureLabel = "Outer wall"
            )
        )

        val contexts = buildSuspiciousModelLineContexts(file, samples)

        assertEquals(1, contexts.size)
        @Suppress("UNCHECKED_CAST")
        val lines = contexts[0]["lines"] as List<Map<String, Any?>>
        assertTrue(lines.size in 3..5)
        assertTrue((lines.any { (it["text"] as String).contains("line 150000") }))
    }
}
