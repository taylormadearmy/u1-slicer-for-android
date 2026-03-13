package com.u1.slicer.network

import org.json.JSONObject

/**
 * Utility functions for MakerWorld URL parsing and API response handling.
 * Extracted from SlicerViewModel for testability.
 */
object MakerWorldUtils {

    private val DESIGN_ID_REGEX = Regex("""(?:https?://)?(?:www\.)?makerworld\.com/(?:\w+/)?models/(\d+)""")

    /**
     * Extracts a MakerWorld design ID from a URL.
     * Supports formats:
     *   - https://makerworld.com/models/2455683
     *   - https://makerworld.com/en/models/2455683-slug-text
     *   - https://www.makerworld.com/en/models/2455683?query=params
     *   - makerworld.com/models/2455683
     */
    fun extractDesignId(url: String): String? {
        return DESIGN_ID_REGEX.find(url.trim())?.groupValues?.get(1)
    }

    /**
     * Extracts the defaultInstanceId from a MakerWorld design API JSON response.
     * Returns null if the response doesn't contain a valid instance ID.
     */
    fun extractInstanceId(designJson: String): String? {
        return try {
            val obj = JSONObject(designJson)
            val id = obj.optLong("defaultInstanceId", 0L)
            if (id > 0) id.toString() else null
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Parses a MakerWorld download API JSON response.
     * Returns a Pair of (fileName, downloadUrl) or null if the response is an error.
     */
    fun parseDownloadResponse(json: String): DownloadResponse {
        val obj = try { JSONObject(json) } catch (_: Exception) {
            return DownloadResponse.ParseError
        }
        // Check for error response (e.g. rate limit, login required)
        if (obj.has("error") && !obj.has("url")) {
            val errorMsg = obj.optString("error", "Unknown error")
            return DownloadResponse.ApiError(errorMsg)
        }
        val url = try { obj.getString("url") } catch (_: Exception) {
            return DownloadResponse.ParseError
        }
        val name = obj.optString("name", "model.3mf")
        return DownloadResponse.Success(name, url)
    }

    /**
     * Classifies an HTTP error response into a user-friendly message.
     */
    fun classifyDownloadError(httpCode: Int, responseBody: String?): String {
        return when {
            responseBody?.contains("captcha", ignoreCase = true) == true ->
                "MakerWorld requires CAPTCHA verification. Open makerworld.com in a browser first, or download the 3MF in Bambu Handy and share the file directly."
            responseBody?.contains("log in", ignoreCase = true) == true || responseBody?.contains("unlogged", ignoreCase = true) == true ->
                "MakerWorld download limit reached. Enable cookies in Settings > MakerWorld, or wait and try again."
            httpCode == 403 -> "MakerWorld requires login. Enable cookies in Settings > MakerWorld, or share the file directly from Bambu Handy."
            httpCode == 429 -> "MakerWorld rate limit. Wait a minute and try again."
            else -> "Download failed: HTTP $httpCode"
        }
    }

    /**
     * Classifies a non-ZIP response body into a user-friendly error message.
     */
    fun classifyNonZipResponse(preview: String, fileSize: Long): String {
        return when {
            preview.contains("login", ignoreCase = true) || preview.contains("sign in", ignoreCase = true) ->
                "MakerWorld requires login. Check your cookies in Settings > MakerWorld."
            preview.contains("captcha", ignoreCase = true) ->
                "MakerWorld returned a CAPTCHA challenge. Try again later."
            preview.contains("rate limit", ignoreCase = true) || preview.contains("429") ->
                "Rate limited by MakerWorld. Try again in a few minutes."
            else -> "MakerWorld returned an unexpected response ($fileSize bytes). Your cookies may be expired — update them in Settings > MakerWorld."
        }
    }

    /**
     * Sanitizes cookie string by removing CR/LF characters that OkHttp rejects.
     */
    fun sanitizeCookies(raw: String): String {
        return raw.replace("\r", "").replace("\n", "").trim()
    }

    sealed class DownloadResponse {
        data class Success(val fileName: String, val url: String) : DownloadResponse()
        data class ApiError(val message: String) : DownloadResponse()
        object ParseError : DownloadResponse()
    }
}
