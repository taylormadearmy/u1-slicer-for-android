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

/**
 * AI label client (post-fix32 pivot). The pipeline no longer asks the AI to spatially ground
 * regions — it does Z-band segmentation locally, then sends a single rendered image of the
 * banded model to the AI for naming + colour suggestion. This is purely a text task: the AI
 * looks at the coloured model and returns `{"segments":[{"id":0,"label":"...","colour":"#..."}]}`.
 *
 * Reliability is higher because the AI doesn't need to draw bounding boxes — it just describes
 * what it sees. Failure falls back silently to default labels (handled by the caller).
 */
data class NamedColour(val label: String, val colour: String)

object AiLabelClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()

    /** Most recent raw AI text response (for diagnostic logging). */
    @Volatile var lastRaw: String? = null

    /** Which model in the chain actually returned a parseable answer, or null when none did. */
    @Volatile var lastModel: String? = null

    // Gemini free-tier model chain. 2.5 Pro is highest quality but has the tightest RPM/RPD
    // quota; 2.5 Flash is the daily-quota workhorse; 1.5 Flash is the legacy safety net. We try
    // them in order and return the first successful parse.
    private val GEMINI_MODELS = listOf(
        "gemini-2.5-pro",
        "gemini-2.5-flash",
        "gemini-1.5-flash",
    )

    fun buildLabelPrompt(bandCount: Int): String =
        "You are looking at two views of the SAME 3D model from the same angle:\n" +
        "  • Image 1: plain shaded render — use this to identify what the object IS " +
        "(e.g. boat, dragon, vase, figurine).\n" +
        "  • Image 2: the same model split into $bandCount horizontal bands from bottom to top, " +
        "each band rendered in a different colour.\n\n" +
        "First identify the object from image 1, then for image 2 name each colour band based " +
        "on which part of THAT specific object it covers. For a boat the bands might be " +
        "\"Hull\", \"Deck\", \"Cabin\", \"Roof\"; for a dragon \"Tail\", \"Body\", \"Neck\", " +
        "\"Head\". Use real names from the object you identified — NOT generic terms like " +
        "\"Band 1\". Also suggest a realistic filament colour for each band so the printed " +
        "result looks natural.\n\n" +
        "Bands are numbered 0..${bandCount - 1} from bottom to top. Return exactly $bandCount " +
        "entries in band-index order.\n\n" +
        "Respond ONLY with valid JSON:\n" +
        "{\"segments\": [\n" +
        "  {\"id\": 0, \"label\": \"...\", \"colour\": \"#RRGGBB\"},\n" +
        "  ...\n" +
        "]}"

    /**
     * Ask the AI to name each band in [images] and suggest a realistic filament colour.
     * [images] is expected to be `[shadedReference, bandedColours]` from the same camera angle —
     * the shaded reference helps the AI identify the object before naming its parts.
     * Returns the parsed list, or null if every attempt failed (caller falls back to defaults).
     */
    suspend fun labelSegments(
        provider: AiPaintProvider,
        apiKey: String,
        images: List<Bitmap>,
        bandCount: Int,
    ): List<NamedColour>? = withContext(Dispatchers.IO) {
        val prompt = buildLabelPrompt(bandCount)
        val jpegBytes = images.map { bitmapToJpeg(it) }
        // For Gemini we walk the model chain; everything else is a single attempt.
        val attempts: List<Pair<String, () -> Request>> =
            if (provider == AiPaintProvider.GEMINI) {
                GEMINI_MODELS.map { model ->
                    model to { buildGeminiRequest(apiKey, prompt, jpegBytes, model) }
                }
            } else {
                listOf(provider.name to { buildRequest(provider, apiKey, prompt, jpegBytes) })
            }
        var lastErr = ""
        for ((modelLabel, build) in attempts) {
            try {
                val response = client.newCall(build()).execute()
                val body = response.body?.string()
                if (body == null || !response.isSuccessful) {
                    lastErr = "$modelLabel: HTTP ${response.code} — ${body?.take(300)}"
                    continue
                }
                val text = extractTextFromResponse(provider, body)
                val parsed = parseLabelJson(text, bandCount)
                if (parsed != null) {
                    lastRaw = "[$modelLabel] ${text.take(2000)}"
                    lastModel = modelLabel
                    return@withContext parsed
                }
                lastErr = "$modelLabel: parse-fail — ${text.take(300)}"
            } catch (e: Exception) {
                lastErr = "$modelLabel: ${e.message}"
            }
        }
        lastRaw = "All attempts failed; last: $lastErr"
        lastModel = null
        null
    }

    /** Strict parse: returns null when the AI response doesn't carry a valid segments payload. */
    internal fun parseLabelJson(raw: String, bandCount: Int): List<NamedColour>? {
        val jsonStr = Regex("""\{[\s\S]*"segments"[\s\S]*\}""").find(raw)?.value ?: return null
        return try {
            val arr = JSONObject(jsonStr).getJSONArray("segments")
            if (arr.length() != bandCount) return null
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                NamedColour(
                    label = obj.optString("label", "Band ${i + 1}"),
                    colour = obj.optString("colour", "#888888"),
                )
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun buildRequest(
        provider: AiPaintProvider,
        apiKey: String,
        prompt: String,
        jpegBytes: List<ByteArray>
    ): Request = when (provider) {
        AiPaintProvider.POLLINATIONS -> buildOpenAiStyleRequest(
            url = "https://text.pollinations.ai/openai/chat/completions",
            model = "openai",
            // Pollinations works anonymously but accepts an OpenAI-style Authorization header
            // to lift the public-tier rate limits and prioritise routing.
            apiKey = apiKey.takeIf { it.isNotBlank() },
            prompt = prompt,
            jpegBytes = jpegBytes
        )
        AiPaintProvider.OPENROUTER -> buildOpenAiStyleRequest(
            url = "https://openrouter.ai/api/v1/chat/completions",
            // OpenRouter delisted the previous llama-3.2-11b-vision free endpoint. Qwen 2.5 VL
            // 72B is currently their best free vision model.
            model = "qwen/qwen-2.5-vl-72b-instruct:free",
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
            .put("max_tokens", 512)
            .toString().toRequestBody(JSON_TYPE)
        val builder = Request.Builder().url(url).post(body)
        if (apiKey != null) builder.header("Authorization", "Bearer $apiKey")
        return builder.build()
    }

    private fun buildGeminiRequest(
        apiKey: String,
        prompt: String,
        jpegBytes: List<ByteArray>,
        model: String = GEMINI_MODELS.first(),
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
        val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey"
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
            .put("max_tokens", 512)
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

    private fun bitmapToJpeg(bmp: Bitmap): ByteArray {
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 85, out)
        return out.toByteArray()
    }

}
