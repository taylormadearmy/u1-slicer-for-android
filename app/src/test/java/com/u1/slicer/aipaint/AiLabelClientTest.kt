package com.u1.slicer.aipaint

import org.junit.Assert.*
import org.junit.Test

class AiLabelClientTest {

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

    // ---- buildLabelPrompt ----

    @Test
    fun `buildLabelPrompt embeds the band count`() {
        val prompt = AiLabelClient.buildLabelPrompt(bandCount = 8)
        assertTrue("prompt should mention 8 bands", prompt.contains("8 horizontal bands"))
        assertTrue("prompt should mention band index range 0..7",
            prompt.contains("0..7"))
        assertTrue("prompt should ask for exactly 8 entries",
            prompt.contains("exactly 8"))
    }

    // ---- parseLabelJson ----

    @Test
    fun `parseLabelJson parses valid response`() {
        val json = """{"segments":[
            {"id":0,"label":"Hooves","colour":"#8B4513"},
            {"id":1,"label":"Legs","colour":"#C62828"},
            {"id":2,"label":"Body","colour":"#1565C0"},
            {"id":3,"label":"Head","colour":"#FFCC00"}
        ]}"""
        val result = AiLabelClient.parseLabelJson(json, bandCount = 4)
        assertNotNull(result)
        assertEquals(4, result!!.size)
        assertEquals("Hooves", result[0].label)
        assertEquals("#8B4513", result[0].colour)
        assertEquals("Head", result[3].label)
    }

    @Test
    fun `parseLabelJson returns null on malformed JSON`() {
        assertNull(AiLabelClient.parseLabelJson("not json at all", bandCount = 4))
    }

    @Test
    fun `parseLabelJson returns null on wrong segment count`() {
        val json = """{"segments":[{"id":0,"label":"A","colour":"#FF0000"}]}"""
        assertNull(AiLabelClient.parseLabelJson(json, bandCount = 4))
    }

    @Test
    fun `parseLabelJson extracts JSON embedded in prose`() {
        val response = """Sure! Here is the result:
{"segments":[{"id":0,"label":"Head","colour":"#FFCC00"},{"id":1,"label":"Body","colour":"#FF0000"},{"id":2,"label":"Legs","colour":"#0000FF"},{"id":3,"label":"Base","colour":"#888888"}]}
Hope that helps."""
        val result = AiLabelClient.parseLabelJson(response, bandCount = 4)
        assertNotNull(result)
        assertEquals(4, result!!.size)
        assertEquals("Head", result[0].label)
    }
}
