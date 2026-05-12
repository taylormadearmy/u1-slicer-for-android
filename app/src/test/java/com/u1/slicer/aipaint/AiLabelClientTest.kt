package com.u1.slicer.aipaint

import org.junit.Assert.*
import org.junit.Test

class AiLabelClientTest {

    @Test
    fun `parseRegionJson parses valid response`() {
        val json = """{"regions":[
            {"id":0,"label":"Head","colour":"#FFCC00"},
            {"id":1,"label":"Body","colour":"#C62828"},
            {"id":2,"label":"Wings","colour":"#1565C0"},
            {"id":3,"label":"Base","colour":"#37474F"}
        ]}"""
        val regions = AiLabelClient.parseRegionJson(json)
        assertEquals(4, regions.size)
        assertEquals("Head", regions[0].label)
        assertEquals("#FFCC00", regions[0].suggestedColour)
        assertEquals(2, regions[2].id)
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
    fun `buildRequest for Pollinations does not include Authorization header`() {
        val req = AiLabelClient.buildRequest(AiPaintProvider.POLLINATIONS, "", "prompt", emptyList())
        assertNull(req.header("Authorization"))
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
}
