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

object AiLabelClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()

    // Kept for backward compat with existing tests that reference it directly
    val PROMPT = """These 8 images show a 3D model. The first 4 are shaded renders (front, back, left isometric, right isometric). The next 4 show the same views with 4 regions pre-coloured by geometry analysis: red=region 0, green=region 1, cyan=region 2, yellow=region 3.

Based on the coloured regions you can see on the model, give each region a short descriptive label (e.g. "Legs", "Body", "Head", "Base") and suggest a realistic filament colour for that part.

Respond ONLY with valid JSON:
{"regions": [{"id": 0, "label": "...", "colour": "#RRGGBB"}, {"id": 1, "label": "...", "colour": "#RRGGBB"}, {"id": 2, "label": "...", "colour": "#RRGGBB"}, {"id": 3, "label": "...", "colour": "#RRGGBB"}]}"""

    fun buildBoxesPrompt(targetColours: Int): String =
        "You are segmenting a 3D model for multi-colour 3D printing. " +
        "These 4 images are renders of the model from front, back, left-iso and right-iso angles. " +
        "Identify exactly $targetColours visually distinct semantic regions (e.g. \"Head\", \"Body\", \"Legs\", \"Base\"; " +
        "or for an object: \"Lid\", \"Body\", \"Handle\", \"Base\"). For each region, give a label, " +
        "a realistic filament colour, and a bounding box in EACH view in normalised screen coordinates [0..1] " +
        "where (0,0) is the top-left of the image and (1,1) is the bottom-right.\n\n" +
        "IMPORTANT: be generous with your boxes — slightly oversize them to cover all visible region pixels. " +
        "For bilaterally symmetric features (eyes, ears, legs, arms) draw ONE box that encloses both sides together. " +
        "If a region isn't visible in a given view, use the empty box [0, 0, 0, 0].\n\n" +
        "Respond ONLY with valid JSON:\n" +
        "{\"regions\": [\n" +
        "  {\"label\": \"...\", \"colour\": \"#RRGGBB\", \"boxes\": {\n" +
        "    \"front\":     [xMin, yMin, xMax, yMax],\n" +
        "    \"back\":      [xMin, yMin, xMax, yMax],\n" +
        "    \"left_iso\":  [xMin, yMin, xMax, yMax],\n" +
        "    \"right_iso\": [xMin, yMin, xMax, yMax]\n" +
        "  }},\n" +
        "  ...\n" +
        "]}\n" +
        "Rules: exactly $targetColours regions; every coordinate in [0, 1]; xMax > xMin and yMax > yMin for visible regions."

    /** Last raw AI text response from labelByBoxes — for diagnostic logging. */
    @Volatile var lastBoxesRaw: String? = null

    /** True when the most recent labelByBoxes call had to fall back because the AI refused or
     *  returned malformed JSON. Consumers use this to surface the error and switch to a
     *  non-AI fallback segmentation. */
    @Volatile var lastBoxesFellBack: Boolean = false

    suspend fun labelByBoxes(
        provider: AiPaintProvider,
        apiKey: String,
        shadedBitmaps: List<Bitmap>,   // ordered: FRONT, BACK, LEFT_ISO, RIGHT_ISO
        targetColours: Int,
    ): List<AiRegionBoxes> = withContext(Dispatchers.IO) {
        try {
            val prompt = buildBoxesPrompt(targetColours)
            val jpegBytes = shadedBitmaps.map { bitmapToJpeg(it) }
            val request = buildRequest(provider, apiKey, prompt, jpegBytes)
            val response = client.newCall(request).execute()
            val body = response.body?.string()
            if (body == null || !response.isSuccessful) {
                lastBoxesRaw = "HTTP ${response.code}: ${body?.take(800)}"
                lastBoxesFellBack = true
                return@withContext fallbackBoxes(targetColours)
            }
            val text = extractTextFromResponse(provider, body)
            lastBoxesRaw = text.take(2000)
            val parsed = tryParseBoxes(text, targetColours)
            lastBoxesFellBack = parsed == null
            parsed ?: fallbackBoxes(targetColours)
        } catch (e: Exception) {
            lastBoxesRaw = "exception: ${e.message}"
            lastBoxesFellBack = true
            fallbackBoxes(targetColours)
        }
    }

    /** Returns null when the AI response does not parse as a valid regions+boxes JSON, so the
     *  caller can distinguish "AI succeeded" from "AI refused / returned plain text". */
    private fun tryParseBoxes(raw: String, targetColours: Int): List<AiRegionBoxes>? {
        if (!raw.contains("\"regions\"")) return null
        val parsed = parseBoxesJson(raw, targetColours)
        // parseBoxesJson silently falls back too; detect by checking whether all boxes are the
        // boring full-stripe fallback. If every region's areas across views are exactly equal,
        // it's the stripe fallback — treat as failure.
        val stripeFallback = parsed.all { r ->
            val areas = r.boxes.values.map {
                val w = (it[2] - it[0]).coerceAtLeast(0f)
                val h = (it[3] - it[1]).coerceAtLeast(0f)
                w * h
            }
            areas.distinct().size == 1
        }
        return if (stripeFallback) null else parsed
    }

    fun parseBoxesJson(raw: String, targetColours: Int): List<AiRegionBoxes> {
        val jsonStr = Regex("""\{[\s\S]*"regions"[\s\S]*\}""").find(raw)?.value ?: raw
        return try {
            val arr = JSONObject(jsonStr).getJSONArray("regions")
            if (arr.length() != targetColours) return fallbackBoxes(targetColours)
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                val boxesObj = obj.getJSONObject("boxes")
                val boxes = mutableMapOf<CameraAngle, FloatArray>()
                for (angle in CameraAngle.entries) {
                    val key = angle.name.lowercase()
                    val arrBox = boxesObj.optJSONArray(key)
                    if (arrBox != null && arrBox.length() == 4) {
                        boxes[angle] = floatArrayOf(
                            arrBox.getDouble(0).toFloat().coerceIn(0f, 1f),
                            arrBox.getDouble(1).toFloat().coerceIn(0f, 1f),
                            arrBox.getDouble(2).toFloat().coerceIn(0f, 1f),
                            arrBox.getDouble(3).toFloat().coerceIn(0f, 1f),
                        )
                    } else {
                        boxes[angle] = floatArrayOf(0f, 0f, 0f, 0f)
                    }
                }
                AiRegionBoxes(
                    label = obj.optString("label", "Region ${i + 1}"),
                    suggestedColour = obj.optString("colour", "#888888"),
                    boxes = boxes,
                )
            }
        } catch (e: Exception) {
            fallbackBoxes(targetColours)
        }
    }

    /** Fallback when the AI fails: equal-area horizontal stripes (top, upper, lower, bottom). */
    fun fallbackBoxes(targetColours: Int): List<AiRegionBoxes> {
        val palette = listOf("#E53935", "#1E88E5", "#43A047", "#FB8C00",
                             "#8E24AA", "#00ACC1", "#F4511E", "#6D4C41")
        val labels = listOf("Top", "Upper", "Lower", "Bottom")
        return (0 until targetColours).map { i ->
            // Image Y: 0 = top of image, 1 = bottom. Stripe i goes from i/N to (i+1)/N,
            // so region 0 ("Top") = top of image, region N-1 ("Bottom") = bottom.
            val yMin = i.toFloat() / targetColours
            val yMax = (i + 1).toFloat() / targetColours
            val full = floatArrayOf(0f, yMin, 1f, yMax)
            AiRegionBoxes(
                label = labels.getOrElse(i) { "Region ${i + 1}" },
                suggestedColour = palette.getOrElse(i) { "#888888" },
                boxes = CameraAngle.entries.associateWith { full },
            )
        }
    }

    fun buildGroupPrompt(numComponents: Int, targetColours: Int): String =
        "These 8 images show a 3D model (front, back, left-iso, right-iso views). " +
        "The first 4 are shaded renders. The next 4 show $numComponents surface regions coloured by topology — " +
        "each colour is a connected surface region numbered 0 to ${numComponents - 1}.\n\n" +
        "Group these $numComponents regions into exactly $targetColours semantic groups (e.g. \"Legs\", \"Body\", \"Head\", \"Base\"). " +
        "Choose contrasting, realistic filament colours so adjacent groups look visually distinct.\n\n" +
        "IMPORTANT — symmetry rule: bilaterally symmetric features MUST be in the same group. " +
        "If the model has a left eye and a right eye, both eye component-ids must go in one group. " +
        "Same for pairs/sets of legs, ears, horns, wings, arms, hooves — anything that comes in mirrored copies " +
        "or as a repeating set belongs together. Look across the front, back, left-iso and right-iso views to spot these pairs.\n\n" +
        "Respond ONLY with valid JSON:\n" +
        "{\"groups\": [{\"component_ids\": [0, 2], \"label\": \"...\", \"colour\": \"#RRGGBB\"}, ...]}\n" +
        "Rules: exactly $targetColours groups, every integer 0..${numComponents - 1} used exactly once."

    suspend fun label(
        provider: AiPaintProvider,
        apiKey: String,
        shadedBitmaps: List<Bitmap>,
        componentBitmaps: List<Bitmap>,
        numComponents: Int,
        targetColours: Int
    ): List<AiRegion> = withContext(Dispatchers.IO) {
        try {
            val prompt = buildGroupPrompt(numComponents, targetColours)
            val jpegBytes = (shadedBitmaps + componentBitmaps).map { bitmapToJpeg(it) }
            val request = buildRequest(provider, apiKey, prompt, jpegBytes)
            val response = client.newCall(request).execute()
            val body = response.body?.string()
                ?: return@withContext fallbackGrouping(numComponents, targetColours)
            if (!response.isSuccessful) return@withContext fallbackGrouping(numComponents, targetColours)
            val text = extractTextFromResponse(provider, body)
            parseGroupJson(text, numComponents, targetColours)
        } catch (e: Exception) {
            fallbackGrouping(numComponents, targetColours)
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
            // Pollinations works anonymously but accepts an OpenAI-style Authorization header
            // to lift the public-tier rate limits and prioritise routing.
            apiKey = apiKey.takeIf { it.isNotBlank() },
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
        AiPaintProvider.FIND3D -> error("FIND3D doesn't use the 2D vision request path; runPipeline branches on provider.isFind3D")
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

    fun parseGroupJson(raw: String, numComponents: Int, targetColours: Int): List<AiRegion> {
        val jsonStr = Regex("""\{[\s\S]*"groups"[\s\S]*\}""").find(raw)?.value ?: raw
        return try {
            val arr = JSONObject(jsonStr).getJSONArray("groups")
            if (arr.length() != targetColours) return fallbackGrouping(numComponents, targetColours)
            val seen = mutableSetOf<Int>()
            val regions = (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                val ja = obj.getJSONArray("component_ids")
                val compIds = (0 until ja.length()).map { ja.getInt(it) }
                if (compIds.isEmpty()) return fallbackGrouping(numComponents, targetColours)
                if (compIds.any { it in seen }) return fallbackGrouping(numComponents, targetColours)
                seen.addAll(compIds)
                AiRegion(
                    id = i,
                    label = obj.getString("label"),
                    suggestedColour = obj.getString("colour"),
                    componentIds = compIds
                )
            }
            if (seen.size != numComponents || seen.any { it < 0 || it >= numComponents }) {
                return fallbackGrouping(numComponents, targetColours)
            }
            regions
        } catch (e: Exception) {
            fallbackGrouping(numComponents, targetColours)
        }
    }

    fun fallbackGrouping(numComponents: Int, targetColours: Int): List<AiRegion> {
        val tc = targetColours.coerceAtLeast(1)
        val groups = Array(tc) { mutableListOf<Int>() }
        for (c in 0 until numComponents) groups[c % tc].add(c)
        val palette = listOf("#E53935", "#1E88E5", "#43A047", "#FB8C00",
                             "#8E24AA", "#00ACC1", "#F4511E", "#6D4C41")
        return (0 until tc).map { i ->
            AiRegion(
                id = i,
                label = "Region ${i + 1}",
                suggestedColour = palette.getOrElse(i) { "#888888" },
                componentIds = groups[i].toList()
            )
        }
    }

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
