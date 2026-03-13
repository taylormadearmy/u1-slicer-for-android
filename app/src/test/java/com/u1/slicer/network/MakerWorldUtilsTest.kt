package com.u1.slicer.network

import org.junit.Assert.*
import org.junit.Test

class MakerWorldUtilsTest {

    // --- extractDesignId ---

    @Test
    fun `extractDesignId - standard URL with locale`() {
        assertEquals("2455683", MakerWorldUtils.extractDesignId("https://makerworld.com/en/models/2455683"))
    }

    @Test
    fun `extractDesignId - URL with slug`() {
        assertEquals("2455683", MakerWorldUtils.extractDesignId("https://makerworld.com/en/models/2455683-foldy-fidget-coasters"))
    }

    @Test
    fun `extractDesignId - URL with query params`() {
        assertEquals("2354760", MakerWorldUtils.extractDesignId("https://makerworld.com/models/2354760?appSharePlatform=more"))
    }

    @Test
    fun `extractDesignId - URL without locale`() {
        assertEquals("123456", MakerWorldUtils.extractDesignId("https://makerworld.com/models/123456"))
    }

    @Test
    fun `extractDesignId - URL with www prefix`() {
        assertEquals("789012", MakerWorldUtils.extractDesignId("https://www.makerworld.com/en/models/789012"))
    }

    @Test
    fun `extractDesignId - URL without scheme`() {
        assertEquals("2455683", MakerWorldUtils.extractDesignId("makerworld.com/models/2455683"))
    }

    @Test
    fun `extractDesignId - URL with http scheme`() {
        assertEquals("111", MakerWorldUtils.extractDesignId("http://makerworld.com/en/models/111"))
    }

    @Test
    fun `extractDesignId - URL with whitespace`() {
        assertEquals("2455683", MakerWorldUtils.extractDesignId("  https://makerworld.com/en/models/2455683  "))
    }

    @Test
    fun `extractDesignId - non-MakerWorld URL returns null`() {
        assertNull(MakerWorldUtils.extractDesignId("https://thingiverse.com/thing:12345"))
    }

    @Test
    fun `extractDesignId - random text returns null`() {
        assertNull(MakerWorldUtils.extractDesignId("hello world"))
    }

    @Test
    fun `extractDesignId - empty string returns null`() {
        assertNull(MakerWorldUtils.extractDesignId(""))
    }

    // --- extractInstanceId ---

    @Test
    fun `extractInstanceId - valid response`() {
        val json = """{"id":2455683,"title":"Test","defaultInstanceId":2696321}"""
        assertEquals("2696321", MakerWorldUtils.extractInstanceId(json))
    }

    @Test
    fun `extractInstanceId - zero instance ID returns null`() {
        val json = """{"id":123,"defaultInstanceId":0}"""
        assertNull(MakerWorldUtils.extractInstanceId(json))
    }

    @Test
    fun `extractInstanceId - missing field returns null`() {
        val json = """{"id":123,"title":"No instance"}"""
        assertNull(MakerWorldUtils.extractInstanceId(json))
    }

    @Test
    fun `extractInstanceId - invalid JSON returns null`() {
        assertNull(MakerWorldUtils.extractInstanceId("not json"))
    }

    // --- parseDownloadResponse ---

    @Test
    fun `parseDownloadResponse - success with name and url`() {
        val json = """{"name":"model.3mf","url":"https://example.com/download/signed"}"""
        val result = MakerWorldUtils.parseDownloadResponse(json)
        assertTrue(result is MakerWorldUtils.DownloadResponse.Success)
        val success = result as MakerWorldUtils.DownloadResponse.Success
        assertEquals("model.3mf", success.fileName)
        assertEquals("https://example.com/download/signed", success.url)
    }

    @Test
    fun `parseDownloadResponse - success without name defaults to model 3mf`() {
        val json = """{"url":"https://example.com/download"}"""
        val result = MakerWorldUtils.parseDownloadResponse(json)
        assertTrue(result is MakerWorldUtils.DownloadResponse.Success)
        assertEquals("model.3mf", (result as MakerWorldUtils.DownloadResponse.Success).fileName)
    }

    @Test
    fun `parseDownloadResponse - API error with login message`() {
        val json = """{"code":1,"error":"You can only download up to five models in an unlogged-in state. Please log in to enjoy more benefits."}"""
        val result = MakerWorldUtils.parseDownloadResponse(json)
        assertTrue(result is MakerWorldUtils.DownloadResponse.ApiError)
        assertTrue((result as MakerWorldUtils.DownloadResponse.ApiError).message.contains("unlogged"))
    }

    @Test
    fun `parseDownloadResponse - API error with captcha`() {
        val json = """{"captchaId":"abc123","code":1,"error":"We need to confirm that you are not a robot."}"""
        val result = MakerWorldUtils.parseDownloadResponse(json)
        assertTrue(result is MakerWorldUtils.DownloadResponse.ApiError)
        assertTrue((result as MakerWorldUtils.DownloadResponse.ApiError).message.contains("robot"))
    }

    @Test
    fun `parseDownloadResponse - invalid JSON`() {
        assertEquals(MakerWorldUtils.DownloadResponse.ParseError, MakerWorldUtils.parseDownloadResponse("not json"))
    }

    @Test
    fun `parseDownloadResponse - empty JSON object`() {
        // No "url" field and no "error" field
        assertEquals(MakerWorldUtils.DownloadResponse.ParseError, MakerWorldUtils.parseDownloadResponse("{}"))
    }

    // --- classifyDownloadError ---

    @Test
    fun `classifyDownloadError - captcha in body`() {
        val msg = MakerWorldUtils.classifyDownloadError(418, """{"error":"captcha required"}""")
        assertTrue(msg.contains("CAPTCHA"))
    }

    @Test
    fun `classifyDownloadError - login required in body`() {
        val msg = MakerWorldUtils.classifyDownloadError(200, "Please log in to continue")
        assertTrue(msg.contains("download limit"))
    }

    @Test
    fun `classifyDownloadError - unlogged in body`() {
        val msg = MakerWorldUtils.classifyDownloadError(403, "unlogged-in state")
        assertTrue(msg.contains("download limit"))
    }

    @Test
    fun `classifyDownloadError - 403 without body`() {
        val msg = MakerWorldUtils.classifyDownloadError(403, null)
        assertTrue(msg.contains("requires login"))
    }

    @Test
    fun `classifyDownloadError - 429 rate limit`() {
        val msg = MakerWorldUtils.classifyDownloadError(429, null)
        assertTrue(msg.contains("rate limit"))
    }

    @Test
    fun `classifyDownloadError - unknown code`() {
        val msg = MakerWorldUtils.classifyDownloadError(500, null)
        assertEquals("Download failed: HTTP 500", msg)
    }

    // --- classifyNonZipResponse ---

    @Test
    fun `classifyNonZipResponse - login page`() {
        val msg = MakerWorldUtils.classifyNonZipResponse("<html>Please sign in</html>", 1024)
        assertTrue(msg.contains("login"))
    }

    @Test
    fun `classifyNonZipResponse - captcha page`() {
        val msg = MakerWorldUtils.classifyNonZipResponse("<html>captcha challenge</html>", 2048)
        assertTrue(msg.contains("CAPTCHA"))
    }

    @Test
    fun `classifyNonZipResponse - rate limit page`() {
        val msg = MakerWorldUtils.classifyNonZipResponse("rate limit exceeded", 512)
        assertTrue(msg.contains("Rate limited"))
    }

    @Test
    fun `classifyNonZipResponse - unknown response includes file size`() {
        val msg = MakerWorldUtils.classifyNonZipResponse("<html>something else</html>", 3456)
        assertTrue(msg.contains("3456 bytes"))
    }

    // --- sanitizeCookies ---

    @Test
    fun `sanitizeCookies - strips CR LF`() {
        assertEquals("abc=123; def=456", MakerWorldUtils.sanitizeCookies("abc=123; def=456\r\n"))
    }

    @Test
    fun `sanitizeCookies - strips embedded newlines`() {
        assertEquals("abc=123;def=456", MakerWorldUtils.sanitizeCookies("abc=123;\ndef=456"))
    }

    @Test
    fun `sanitizeCookies - trims whitespace`() {
        assertEquals("abc=123", MakerWorldUtils.sanitizeCookies("  abc=123  "))
    }

    @Test
    fun `sanitizeCookies - empty string`() {
        assertEquals("", MakerWorldUtils.sanitizeCookies(""))
    }

    @Test
    fun `sanitizeCookies - only whitespace and newlines`() {
        assertEquals("", MakerWorldUtils.sanitizeCookies("\r\n  \n  \r"))
    }
}
