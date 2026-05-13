package com.u1.slicer.aipaint

import org.junit.Assert.*
import org.junit.Test

class AiLabelClientTest {

    @Test
    fun `parseRegionJson parses valid response`() {
        val json = """{"regions":[
            {"id":0,"label":"Hooves","colour":"#8B4513"},
            {"id":1,"label":"Legs","colour":"#C62828"},
            {"id":2,"label":"Body","colour":"#1565C0"},
            {"id":3,"label":"Head","colour":"#FFCC00"}
        ]}"""
        val regions = AiLabelClient.parseRegionJson(json)
        assertEquals(4, regions.size)
        assertEquals("Hooves", regions[0].label)
        assertEquals("#8B4513", regions[0].suggestedColour)
        assertEquals("Head", regions[3].label)
        assertEquals(3, regions[3].id)
    }

    @Test
    fun `parseRegionJson returns fallback on malformed JSON`() {
        val regions = AiLabelClient.parseRegionJson("not json at all")
        assertEquals(4, regions.size)
        regions.forEachIndexed { i, r ->
            assertEquals(i, r.id)
            assertTrue(r.label.startsWith("Region"))
        }
    }

    @Test
    fun `parseRegionJson extracts JSON embedded in prose`() {
        val response = """Sure! Here is the result:
{"regions":[{"id":0,"label":"Head","colour":"#FFCC00"},{"id":1,"label":"Body","colour":"#FF0000"},{"id":2,"label":"Wings","colour":"#0000FF"},{"id":3,"label":"Base","colour":"#888888"}]}
Hope that helps."""
        val regions = AiLabelClient.parseRegionJson(response)
        assertEquals(4, regions.size)
        assertEquals("Head", regions[0].label)
    }

    @Test
    fun `fallbackRegions returns 4 distinct hue colours`() {
        val regions = AiLabelClient.fallbackRegions()
        assertEquals(4, regions.size)
        val colours = regions.map { it.suggestedColour }.toSet()
        assertEquals(4, colours.size)
    }

    @Test
    fun `buildRequest for Pollinations with blank key does not include Authorization header`() {
        val req = AiLabelClient.buildRequest(AiPaintProvider.POLLINATIONS, "", "prompt", emptyList())
        assertNull(req.header("Authorization"))
    }

    @Test
    fun `buildRequest for Pollinations with non-blank key includes Bearer Authorization`() {
        // Optional Pollinations key lifts the public-tier rate limits via standard OpenAI-style auth.
        val req = AiLabelClient.buildRequest(AiPaintProvider.POLLINATIONS, "pol-test-key", "prompt", emptyList())
        assertEquals("Bearer pol-test-key", req.header("Authorization"))
    }

    @Test
    fun `Pollinations provider accepts key but does not require it`() {
        assertFalse(AiPaintProvider.POLLINATIONS.requiresKey)
        assertTrue(AiPaintProvider.POLLINATIONS.acceptsKey)
    }

    @Test
    fun `all other providers require and accept a key`() {
        listOf(AiPaintProvider.GEMINI, AiPaintProvider.OPENROUTER, AiPaintProvider.CLAUDE, AiPaintProvider.OPENAI)
            .forEach { p ->
                assertTrue("${p.name} should require a key", p.requiresKey)
                assertTrue("${p.name} should accept a key", p.acceptsKey)
            }
    }

    @Test
    fun `buildRequest for Gemini includes key in URL`() {
        val req = AiLabelClient.buildRequest(AiPaintProvider.GEMINI, "MYKEY", "prompt", emptyList())
        assertTrue(req.url.toString().contains("MYKEY"))
        assertNull(req.header("Authorization"))
    }

    @Test
    fun `buildRequest for Claude includes x-api-key header`() {
        val req = AiLabelClient.buildRequest(AiPaintProvider.CLAUDE, "sk-ant-test", "prompt", emptyList())
        assertEquals("sk-ant-test", req.header("x-api-key"))
    }

    // ---- buildGroupPrompt tests ----

    @Test
    fun `buildGroupPrompt embeds component count and target colours`() {
        val prompt = AiLabelClient.buildGroupPrompt(numComponents = 7, targetColours = 4)
        assertTrue("prompt should mention 7 components", prompt.contains("7 surface regions"))
        assertTrue("prompt should mention 4 groups", prompt.contains("exactly 4"))
    }

    @Test
    fun `buildGroupPrompt instructs AI to keep symmetric features together`() {
        // Without this rule, bilateral pairs (eyes, legs) end up split across groups.
        val prompt = AiLabelClient.buildGroupPrompt(numComponents = 12, targetColours = 4)
        assertTrue("prompt should mention symmetry", prompt.contains("symmetr", ignoreCase = true))
        assertTrue("prompt should give concrete pair examples",
            prompt.contains("eye", ignoreCase = true) && prompt.contains("leg", ignoreCase = true))
    }

    // ---- parseGroupJson tests ----

    @Test
    fun `parseGroupJson parses valid response`() {
        val json = """{"groups": [
            {"component_ids": [0, 1], "label": "Legs", "colour": "#8B4513"},
            {"component_ids": [2], "label": "Body", "colour": "#C62828"},
            {"component_ids": [3, 4], "label": "Head", "colour": "#1565C0"},
            {"component_ids": [5], "label": "Base", "colour": "#FFCC00"}
        ]}"""
        val regions = AiLabelClient.parseGroupJson(json, numComponents = 6, targetColours = 4)
        assertEquals(4, regions.size)
        assertEquals("Legs", regions[0].label)
        assertEquals("#8B4513", regions[0].suggestedColour)
        assertEquals(listOf(0, 1), regions[0].componentIds)
        assertEquals(listOf(5), regions[3].componentIds)
    }

    @Test
    fun `parseGroupJson returns fallback on malformed JSON`() {
        val regions = AiLabelClient.parseGroupJson("not json at all", numComponents = 4, targetColours = 4)
        assertEquals(4, regions.size)
        regions.forEachIndexed { i, r ->
            assertEquals(i, r.id)
            assertTrue(r.label.startsWith("Region"))
        }
    }

    @Test
    fun `parseGroupJson returns fallback on wrong group count`() {
        val json = """{"groups": [{"component_ids": [0], "label": "A", "colour": "#FF0000"}]}"""
        val regions = AiLabelClient.parseGroupJson(json, numComponents = 4, targetColours = 4)
        assertEquals(4, regions.size)
    }

    @Test
    fun `parseGroupJson extracts JSON embedded in prose`() {
        val response = """Sure! Here is my grouping:
{"groups": [{"component_ids": [0, 1], "label": "Body", "colour": "#FF0000"},{"component_ids": [2], "label": "Legs", "colour": "#0000FF"},{"component_ids": [3], "label": "Head", "colour": "#00FF00"},{"component_ids": [4], "label": "Base", "colour": "#888888"}]}
Hope that helps."""
        val regions = AiLabelClient.parseGroupJson(response, numComponents = 5, targetColours = 4)
        assertEquals(4, regions.size)
        assertEquals("Body", regions[0].label)
        assertEquals(listOf(0, 1), regions[0].componentIds)
    }

    @Test
    fun `parseGroupJson returns fallback on duplicate component ids`() {
        val json = """{"groups": [
            {"component_ids": [0, 1], "label": "A", "colour": "#FF0000"},
            {"component_ids": [1, 2], "label": "B", "colour": "#00FF00"},
            {"component_ids": [3], "label": "C", "colour": "#0000FF"},
            {"component_ids": [4], "label": "D", "colour": "#FFFF00"}
        ]}"""
        val regions = AiLabelClient.parseGroupJson(json, numComponents = 5, targetColours = 4)
        assertEquals(4, regions.size)
        // Should fall back because component 1 is used twice
        assertTrue(regions.all { it.label.startsWith("Region") })
    }

    // ---- componentDisplayColors tests ----

    @Test
    fun `componentDisplayColors returns n distinct ARGB values`() {
        val colors = AiLabelClient.componentDisplayColors(8)
        assertEquals(8, colors.size)
        assertEquals(8, colors.toSet().size)
        colors.forEach { c ->
            assertEquals("alpha should be 255", 255, (c ushr 24) and 0xFF)
        }
    }

    @Test
    fun `componentDisplayColors returns empty array for 0`() {
        assertEquals(0, AiLabelClient.componentDisplayColors(0).size)
    }

    @Test
    fun `componentDisplayColors 4 colors are evenly hue-spaced`() {
        // With n=4, hues are 0°, 90°, 180°, 270°: red, lime, cyan, blue-ish
        val colors = AiLabelClient.componentDisplayColors(4)
        assertEquals(4, colors.size)
        assertEquals(4, colors.toSet().size)
    }

    // ---- fallbackGrouping tests ----

    @Test
    fun `fallbackGrouping distributes all components`() {
        val regions = AiLabelClient.fallbackGrouping(numComponents = 7, targetColours = 4)
        assertEquals(4, regions.size)
        val allIds = regions.flatMap { it.componentIds }
        assertEquals(7, allIds.size)
        assertEquals((0..6).toSet(), allIds.toSet())
    }

    @Test
    fun `fallbackGrouping with fewer components than colours`() {
        val regions = AiLabelClient.fallbackGrouping(numComponents = 2, targetColours = 4)
        assertEquals(4, regions.size)
        val allIds = regions.flatMap { it.componentIds }
        assertEquals(2, allIds.size)
        assertEquals(setOf(0, 1), allIds.toSet())
    }
}
