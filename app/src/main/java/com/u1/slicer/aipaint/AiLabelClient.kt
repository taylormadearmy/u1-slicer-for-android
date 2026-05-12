package com.u1.slicer.aipaint

import android.graphics.Bitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.concurrent.TimeUnit

object AiLabelClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()

    val PROMPT = """These 8 images show a 3D model. The first 4 are shaded renders (front, back, left isometric, right isometric). The next 4 show the same views with 4 regions pre-coloured by geometry analysis: red=region 0, green=region 1, cyan=region 2, yellow=region 3.

Based on the coloured regions you can see on the model, give each region a short descriptive label (e.g. "Legs", "Body", "Head", "Base") and suggest a realistic filament colour for that part.

Respond ONLY with valid JSON:
{"regions": [{"id": 0, "label": "...", "colour": "#RRGGBB"}, {"id": 1, "label": "...", "colour": "#RRGGBB"}, {"id": 2, "label": "...", "colour": "#RRGGBB"}, {"id": 3, "label": "...", "colour": "#RRGGBB"}]}"""

    suspend fun label(
        provider: AiPaintProvider,
        apiKey: String,
        bitmaps: List<Bitmap>
    ): List<AiRegion> = withContext(Dispatchers.IO) {
        try {
            val jpegBytes = bitmaps.map { bitmapToJpeg(it) }
            val request = buildRequest(provider, apiKey, PROMPT, jpegBytes)
            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: return@withContext fallbackRegions()
            if (!response.isSuccessful) return@withContext fallbackRegions()
            val text = extractTextFromResponse(provider, body)
            parseRegionJson(text)
        } catch (e: Exception) {
            fallbackRegions()
        }
    }

    fun buildRequest(
        provider: AiPaintProvider,
        apiKey: String,
        prompt: String,
        jpegBytes: List<ByteArray>
    ): Request = when (provider) {
        AiPaintProvider.POLLINATIONS -> buildOpenAiStyleRequest(
            url = "https://text.pollinations.ai/openai/chat/completions",
            model = "openai",
            apiKey = null,
            prompt = prompt,
            jpegBytes = jpegBytes
        )
        AiPaintProvider.OPENROUTER -> buildOpenAiStyleRequest(
            url = "https://openrouter.ai/api/v1/chat/completions",
            model = "meta-llama/llama-3.2-11b-vision-instruct:free",
            apiKey = apiKey,
            prompt = prompt,
            jpegBytes = jpegBytes
        )
        AiPaintProvider.OPENAI -> buildOpenAiStyleRequest(
            url = "https://api.openai.com/v1/chat/completions",
            model = "gpt-4o-mini",
            apiKey = apiKey,
            prompt = prompt,
            jpegBytes = jpegBytes
        )
        AiPaintProvider.GEMINI -> buildGeminiRequest(apiKey, prompt, jpegBytes)
        AiPaintProvider.CLAUDE -> buildClaudeRequest(apiKey, prompt, jpegBytes)
    }

    private fun buildOpenAiStyleRequest(
        url: String,
        model: String,
        apiKey: String?,
        prompt: String,
        jpegBytes: List<ByteArray>
    ): Request {
        val content = JSONArray().apply {
            put(JSONObject().put("type", "text").put("text", prompt))
            jpegBytes.forEach { bytes ->
                val b64 = Base64.getEncoder().encodeToString(bytes)
                put(
                    JSONObject().put("type", "image_url").put(
                        "image_url",
                        JSONObject().put("url", "data:image/jpeg;base64,$b64")
                    )
                )
            }
        }
        val body = JSONObject()
            .put("model", model)
            .put(
                "messages", JSONArray().put(
                    JSONObject().put("role", "user").put("content", content)
                )
            )
            .put("max_tokens", 300)
            .toString().toRequestBody(JSON_TYPE)
        val builder = Request.Builder().url(url).post(body)
        if (apiKey != null) builder.header("Authorization", "Bearer $apiKey")
        return builder.build()
    }

    private fun buildGeminiRequest(
        apiKey: String,
        prompt: String,
        jpegBytes: List<ByteArray>
    ): Request {
        val parts = JSONArray().apply {
            put(JSONObject().put("text", prompt))
            jpegBytes.forEach { bytes ->
                val b64 = Base64.getEncoder().encodeToString(bytes)
                put(
                    JSONObject().put(
                        "inline_data",
                        JSONObject().put("mime_type", "image/jpeg").put("data", b64)
                    )
                )
            }
        }
        val body = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("parts", parts)))
            .toString().toRequestBody(JSON_TYPE)
        val url =
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash-lite:generateContent?key=$apiKey"
        return Request.Builder().url(url).post(body).build()
    }

    private fun buildClaudeRequest(
        apiKey: String,
        prompt: String,
        jpegBytes: List<ByteArray>
    ): Request {
        val content = JSONArray().apply {
            jpegBytes.forEach { bytes ->
                val b64 = Base64.getEncoder().encodeToString(bytes)
                put(
                    JSONObject().put("type", "image").put(
                        "source",
                        JSONObject()
                            .put("type", "base64")
                            .put("media_type", "image/jpeg")
                            .put("data", b64)
                    )
                )
            }
            put(JSONObject().put("type", "text").put("text", prompt))
        }
        val body = JSONObject()
            .put("model", "claude-haiku-4-5-20251001")
            .put("max_tokens", 300)
            .put(
                "messages", JSONArray().put(
                    JSONObject().put("role", "user").put("content", content)
                )
            )
            .toString().toRequestBody(JSON_TYPE)
        return Request.Builder()
            .url("https://api.anthropic.com/v1/messages")
            .post(body)
            .header("x-api-key", apiKey)
            .header("anthropic-version", "2023-06-01")
            .build()
    }

    private fun extractTextFromResponse(provider: AiPaintProvider, body: String): String {
        return try {
            val json = JSONObject(body)
            when (provider) {
                AiPaintProvider.GEMINI ->
                    json.getJSONArray("candidates")
                        .getJSONObject(0).getJSONObject("content")
                        .getJSONArray("parts").getJSONObject(0).getString("text")
                AiPaintProvider.CLAUDE ->
                    json.getJSONArray("content").getJSONObject(0).getString("text")
                else ->
                    json.getJSONArray("choices")
                        .getJSONObject(0).getJSONObject("message").getString("content")
            }
        } catch (e: Exception) {
            body
        }
    }

    fun parseRegionJson(raw: String): List<AiRegion> {
        val jsonStr = Regex("""\{[\s\S]*"regions"[\s\S]*\}""").find(raw)?.value ?: raw
        return try {
            val arr = JSONObject(jsonStr).getJSONArray("regions")
            val regions = (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                AiRegion(
                    id = obj.getInt("id"),
                    label = obj.getString("label"),
                    suggestedColour = obj.getString("colour")
                )
            }.sortedBy { it.id }.mapIndexed { idx, r -> r.copy(id = idx) }
            if (regions.size != 4) fallbackRegions() else regions
        } catch (e: Exception) {
            fallbackRegions()
        }
    }

    fun fallbackRegions(): List<AiRegion> = listOf(
        AiRegion(0, "Region 1", "#E53935"),
        AiRegion(1, "Region 2", "#1E88E5"),
        AiRegion(2, "Region 3", "#43A047"),
        AiRegion(3, "Region 4", "#FB8C00")
    )

    private fun bitmapToJpeg(bmp: Bitmap): ByteArray {
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 85, out)
        return out.toByteArray()
    }
}
