package com.u1.slicer.aipaint

import android.graphics.Bitmap
import kotlin.math.abs
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
        "You are looking at two views of the SAME 3D model from the same camera angle:\n" +
        "  • Image 1: plain shaded render — use this to identify what the object IS.\n" +
        "  • Image 2: the same model with $bandCount painted regions, each shown in a " +
        "different colour. Region ids are 0..${bandCount - 1}.\n\n" +
        "First identify the object from image 1, then for each of the $bandCount coloured " +
        "regions name the part of THAT object that the region covers (e.g. \"Head\", \"Body\", " +
        "\"Wings\", \"Base\"). Use real anatomical or structural part names of whatever the " +
        "object actually is — NOT generic placeholders like \"Region 1\" or \"Band 2\", and " +
        "NOT names from unrelated objects. Also suggest a realistic filament colour for each " +
        "region so the printed result looks natural.\n\n" +
        "Return exactly $bandCount entries in region-id order (0 first, then 1, …).\n\n" +
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
            // 2026-05-14: Pollinations' free legacy endpoint dropped all vision-capable
            // models — `/models` now reports only GPT-OSS 20B (text-only) for both anonymous
            // and key-authenticated tiers. Pollinations therefore CANNOT do Smart Paint AI
            // grouping today. We keep the alias "openai" for the rare text-only call (e.g.
            // diagnostic naming on already-painted models) but the topology grouping pass
            // will silently fall back to raw cascade when Pollinations is the selected
            // provider. Configure GEMINI or OPENROUTER for working vision-based grouping.
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

    // ===================================================================================
    // F54 fix39 — restored topology semantic grouping (from fix30/fix33). For organic models
    // (goat, dragon, figurine) raw topology produces hundreds of tiny components. Asking the
    // AI to group them into ~12 semantic regions with explicit symmetry rules gives a
    // user-friendly tree where tap-on-face highlights the whole face, and bilateral features
    // (left/right legs, eyes, ears) share a colour. Optional — only fires when AI naming is
    // enabled and a provider key is set.
    // ===================================================================================

    fun buildGroupPrompt(numComponents: Int, targetColours: Int): String =
        "You are looking at two views of the SAME 3D model from the same camera angle:\n" +
        "  • Image 1: plain shaded render — use this to identify what the object IS " +
        "(e.g. goat, dragon, figurine, boat).\n" +
        "  • Image 2: the same model with $numComponents surface regions each shown in a " +
        "different colour. Component IDs are 0..${numComponents - 1}.\n\n" +
        "First identify the object from image 1, then group the $numComponents components " +
        "into exactly $targetColours semantic groups (\"Legs\", \"Horns\", \"Body\", \"Tail\", " +
        "\"Head\", \"Hooves\", \"Mane\", \"Eyes\"). Use real names from the identified object — " +
        "NOT generic terms like \"Region 1\". Choose contrasting realistic filament colours so " +
        "adjacent groups look visually distinct.\n\n" +
        "IMPORTANT — symmetry rule: bilaterally symmetric features MUST share a group. Left + " +
        "right eyes go together, left + right legs go together, pairs of horns / ears / wings " +
        "/ arms / hooves — anything that comes in mirrored copies belongs in the same group.\n\n" +
        "Respond ONLY with valid JSON:\n" +
        "{\"groups\": [\n" +
        "  {\"component_ids\": [0, 2], \"label\": \"...\", \"colour\": \"#RRGGBB\"},\n" +
        "  ...\n" +
        "]}\n" +
        "Rules: exactly $targetColours groups; every integer 0..${numComponents - 1} appears " +
        "exactly once across all groups."

    /** Topology-grouping AI call. Returns one (label, suggestedColour, componentIds) entry per
     *  semantic group, or null if every model in the chain failed to parse. */
    data class GroupingResult(
        val label: String,
        val suggestedColour: String,
        val componentIds: List<Int>,
    )

    suspend fun labelGroups(
        provider: AiPaintProvider,
        apiKey: String,
        shadedImage: Bitmap,
        componentImage: Bitmap,
        numComponents: Int,
        targetColours: Int,
    ): List<GroupingResult>? = withContext(Dispatchers.IO) {
        val prompt = buildGroupPrompt(numComponents, targetColours)
        val jpegBytes = listOf(bitmapToJpeg(shadedImage), bitmapToJpeg(componentImage))
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
                val parsed = parseGroupJson(text, numComponents, targetColours)
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

    /** Strict parse: returns null on malformed JSON, mismatched group count, duplicate ids,
     *  or component ids out of range. */
    internal fun parseGroupJson(raw: String, numComponents: Int, targetColours: Int): List<GroupingResult>? {
        val jsonStr = Regex("""\{[\s\S]*"groups"[\s\S]*\}""").find(raw)?.value ?: return null
        return try {
            val arr = org.json.JSONObject(jsonStr).getJSONArray("groups")
            if (arr.length() != targetColours) return null
            val seen = mutableSetOf<Int>()
            val groups = (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                val ja = obj.getJSONArray("component_ids")
                val compIds = (0 until ja.length()).map { ja.getInt(it) }
                if (compIds.isEmpty()) return null
                if (compIds.any { it in seen }) return null
                seen.addAll(compIds)
                GroupingResult(
                    label = obj.optString("label", "Region ${i + 1}"),
                    suggestedColour = obj.optString("colour", "#888888"),
                    componentIds = compIds,
                )
            }
            if (seen.size != numComponents || seen.any { it < 0 || it >= numComponents }) return null
            groups
        } catch (e: Exception) {
            null
        }
    }

    /** N evenly-spaced ARGB hues — used by the topology-coloured render so each component gets
     *  a visually distinct colour for the AI to refer to by index. */
    fun componentDisplayColors(n: Int): IntArray {
        if (n <= 0) return IntArray(0)
        return IntArray(n) { i -> hsvToArgb(i * 360f / n, 0.9f, 0.95f) }
    }

    private fun hsvToArgb(h: Float, s: Float, v: Float): Int {
        val c = v * s
        val x = c * (1f - abs(h / 60f % 2f - 1f))
        val m = v - c
        val (r, g, b) = when (((h / 60f).toInt()).coerceIn(0, 5)) {
            0 -> Triple(c, x, 0f)
            1 -> Triple(x, c, 0f)
            2 -> Triple(0f, c, x)
            3 -> Triple(0f, x, c)
            4 -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        val ri = ((r + m) * 255).toInt().coerceIn(0, 255)
        val gi = ((g + m) * 255).toInt().coerceIn(0, 255)
        val bi = ((b + m) * 255).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (ri shl 16) or (gi shl 8) or bi
    }
}
