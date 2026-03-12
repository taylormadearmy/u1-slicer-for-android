package com.u1.slicer.network

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Client for downloading 3MF files from MakerWorld.
 *
 * Supports URLs like:
 * - https://makerworld.com/en/models/12345
 * - https://makerworld.com/models/12345
 * - https://www.makerworld.com/en/models/12345
 * - Direct design ID numbers
 */
class MakerWorldClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    data class DownloadResult(
        val file: File,
        val designId: String,
        val filename: String
    )

    /**
     * Parse a MakerWorld URL or design ID and download the 3MF file.
     */
    suspend fun download(
        urlOrId: String,
        outputDir: File,
        cookies: String = "",
        onProgress: (Int) -> Unit = {}
    ): DownloadResult = withContext(Dispatchers.IO) {
        val designId = parseDesignId(urlOrId)
            ?: throw IllegalArgumentException("Could not parse MakerWorld design ID from: $urlOrId")

        Log.i("MakerWorld", "Downloading design $designId")
        onProgress(10)

        // MakerWorld's implicit API endpoint for 3MF download
        val downloadUrl = "https://makerworld.com/api/v1/design-service/instance/$designId/f3mf?type=download"

        val requestBuilder = Request.Builder()
            .url(downloadUrl)
            .header("User-Agent", "U1Slicer/1.0 Android")
        if (cookies.isNotBlank()) {
            requestBuilder.header("Cookie", cookies)
        }
        val request = requestBuilder.get().build()

        onProgress(20)

        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            response.close()
            throw RuntimeException("Download failed: HTTP ${response.code}")
        }

        onProgress(40)

        val body = response.body ?: throw RuntimeException("Empty response body")
        val contentLength = body.contentLength()
        val filename = "makerworld_${designId}.3mf"
        val outputFile = File(outputDir, filename)

        outputFile.outputStream().use { out ->
            body.byteStream().use { input ->
                val buffer = ByteArray(8192)
                var totalRead = 0L
                var read: Int
                while (input.read(buffer).also { read = it } != -1) {
                    out.write(buffer, 0, read)
                    totalRead += read
                    if (contentLength > 0) {
                        val downloadProgress = ((totalRead.toFloat() / contentLength) * 60).toInt()
                        onProgress(40 + downloadProgress)
                    }
                }
            }
        }
        response.close()

        onProgress(100)
        Log.i("MakerWorld", "Downloaded ${outputFile.length()} bytes to ${outputFile.name}")

        // Validate the downloaded file is actually a ZIP (3MF is a ZIP format).
        // MakerWorld may return HTML (login page, CAPTCHA, rate limit) instead of the file.
        validateZipFile(outputFile)

        DownloadResult(
            file = outputFile,
            designId = designId,
            filename = filename
        )
    }

    private fun validateZipFile(file: File) {
        if (file.length() < 4) {
            file.delete()
            throw RuntimeException("Downloaded file is empty or too small (${file.length()} bytes)")
        }
        // ZIP files start with PK\x03\x04 (local file header)
        val magic = ByteArray(4)
        file.inputStream().use { it.read(magic) }
        if (magic[0] != 0x50.toByte() || magic[1] != 0x4B.toByte()) {
            // Not a ZIP — likely an HTML error page. Read first 200 chars for diagnostics.
            val preview = file.readText(Charsets.UTF_8).take(200)
            file.delete()
            val hint = when {
                preview.contains("login", ignoreCase = true) || preview.contains("sign in", ignoreCase = true) ->
                    "MakerWorld requires login for this file. Add cookies in Settings > MakerWorld."
                preview.contains("captcha", ignoreCase = true) ->
                    "MakerWorld returned a CAPTCHA challenge. Try again later or add cookies."
                preview.contains("rate limit", ignoreCase = true) || preview.contains("429", ignoreCase = true) ->
                    "Rate limited by MakerWorld. Try again in a few minutes."
                else ->
                    "MakerWorld returned an unexpected response instead of a 3MF file."
            }
            throw RuntimeException(hint)
        }
    }

    companion object {
        // Regex patterns for MakerWorld URLs
        private val URL_PATTERN = Regex(
            """(?:https?://)?(?:www\.)?makerworld\.com/(?:\w+/)?models/(\d+)"""
        )
        private val ID_PATTERN = Regex("""^\d+$""")

        fun parseDesignId(input: String): String? {
            val trimmed = input.trim()

            // Direct numeric ID
            if (ID_PATTERN.matches(trimmed)) {
                return trimmed
            }

            // URL pattern
            val match = URL_PATTERN.find(trimmed)
            if (match != null) {
                return match.groupValues[1]
            }

            return null
        }

        fun isValidInput(input: String): Boolean {
            return parseDesignId(input) != null
        }
    }
}
