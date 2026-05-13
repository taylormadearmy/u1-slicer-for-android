package com.u1.slicer.aipaint

import org.junit.Assert.*
import org.junit.Test

class AiLabelClientBoxesTest {

    private val sampleJson = """{
        "regions": [
            {"label": "Head", "colour": "#FFCC00", "boxes": {
                "front":     [0.3, 0.05, 0.7, 0.30],
                "back":      [0.3, 0.05, 0.7, 0.30],
                "left_iso":  [0.4, 0.05, 0.6, 0.32],
                "right_iso": [0.4, 0.05, 0.6, 0.32]
            }},
            {"label": "Body", "colour": "#1E88E5", "boxes": {
                "front":     [0.2, 0.30, 0.8, 0.75],
                "back":      [0.2, 0.30, 0.8, 0.75],
                "left_iso":  [0.3, 0.30, 0.7, 0.75],
                "right_iso": [0.3, 0.30, 0.7, 0.75]
            }},
            {"label": "Legs", "colour": "#C62828", "boxes": {
                "front":     [0.25, 0.75, 0.75, 0.95],
                "back":      [0.25, 0.75, 0.75, 0.95],
                "left_iso":  [0.30, 0.75, 0.70, 0.95],
                "right_iso": [0.30, 0.75, 0.70, 0.95]
            }},
            {"label": "Base", "colour": "#43A047", "boxes": {
                "front":     [0.10, 0.95, 0.90, 1.00],
                "back":      [0.10, 0.95, 0.90, 1.00],
                "left_iso":  [0.10, 0.95, 0.90, 1.00],
                "right_iso": [0.10, 0.95, 0.90, 1.00]
            }}
        ]
    }""".trimIndent()

    @Test
    fun `parses regions with all 4 view boxes`() {
        val regions = AiLabelClient.parseBoxesJson(sampleJson, targetColours = 4)
        assertEquals(4, regions.size)
        assertEquals("Head", regions[0].label)
        assertEquals("#FFCC00", regions[0].suggestedColour)
        val frontBox = regions[0].boxes[CameraAngle.FRONT]!!
        assertEquals(0.3f, frontBox[0], 0.001f)
        assertEquals(0.7f, frontBox[2], 0.001f)
    }

    @Test
    fun `parseBoxesJson clamps out-of-range coordinates`() {
        val sloppy = """{"regions":[
            {"label":"A","colour":"#fff","boxes":{
                "front":[-0.5, 0.0, 1.5, 1.2],
                "back":[0,0,0,0], "left_iso":[0,0,0,0], "right_iso":[0,0,0,0]
            }},
            {"label":"B","colour":"#fff","boxes":{"front":[0,0,0,0],"back":[0,0,0,0],"left_iso":[0,0,0,0],"right_iso":[0,0,0,0]}},
            {"label":"C","colour":"#fff","boxes":{"front":[0,0,0,0],"back":[0,0,0,0],"left_iso":[0,0,0,0],"right_iso":[0,0,0,0]}},
            {"label":"D","colour":"#fff","boxes":{"front":[0,0,0,0],"back":[0,0,0,0],"left_iso":[0,0,0,0],"right_iso":[0,0,0,0]}}
        ]}"""
        val regions = AiLabelClient.parseBoxesJson(sloppy, targetColours = 4)
        val front = regions[0].boxes[CameraAngle.FRONT]!!
        assertEquals(0f, front[0], 0.001f)   // -0.5 → 0
        assertEquals(0f, front[1], 0.001f)
        assertEquals(1f, front[2], 0.001f)   // 1.5 → 1
        assertEquals(1f, front[3], 0.001f)   // 1.2 → 1
    }

    @Test
    fun `parseBoxesJson returns fallback on malformed JSON`() {
        val regions = AiLabelClient.parseBoxesJson("not json at all", targetColours = 4)
        assertEquals(4, regions.size)
        regions.forEach { r ->
            assertTrue(r.label.isNotBlank())
            // Fallback uses horizontal stripes spanning the full screen, so every view's box is non-empty.
            assertTrue(r.boxes[CameraAngle.FRONT]!![2] > r.boxes[CameraAngle.FRONT]!![0])
        }
    }

    @Test
    fun `parseBoxesJson returns fallback on wrong region count`() {
        val tooFew = """{"regions":[{"label":"X","colour":"#fff","boxes":{"front":[0,0,1,1],"back":[0,0,1,1],"left_iso":[0,0,1,1],"right_iso":[0,0,1,1]}}]}"""
        val regions = AiLabelClient.parseBoxesJson(tooFew, targetColours = 4)
        assertEquals(4, regions.size)
        assertTrue("fallback labels start with Top/Upper/Lower/Bottom or 'Region'", regions[0].label.isNotBlank())
    }

    @Test
    fun `buildBoxesPrompt embeds target colours and references all 4 view keys`() {
        val prompt = AiLabelClient.buildBoxesPrompt(targetColours = 4)
        assertTrue(prompt.contains("exactly 4"))
        listOf("front", "back", "left_iso", "right_iso").forEach { k ->
            assertTrue("prompt should reference key '$k'", prompt.contains("\"$k\""))
        }
        assertTrue("prompt should ask for symmetric pairing", prompt.contains("symmetric", ignoreCase = true))
    }

    @Test
    fun `fallbackBoxes produces non-overlapping horizontal stripes`() {
        val regions = AiLabelClient.fallbackBoxes(targetColours = 4)
        assertEquals(4, regions.size)
        val fronts = regions.map { it.boxes[CameraAngle.FRONT]!! }
        // Stripes should be ordered top → bottom (low yMin to high yMin) and not overlap.
        for (i in 0 until fronts.size - 1) {
            assertTrue("stripe $i should end at stripe ${i+1}'s start (${fronts[i][3]} vs ${fronts[i+1][1]})",
                kotlin.math.abs(fronts[i][3] - fronts[i+1][1]) < 0.001f)
        }
    }
}
