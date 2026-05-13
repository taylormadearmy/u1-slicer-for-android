package com.u1.slicer.aipaint

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Talks to the Find3D HuggingFace Space (`ziqima/Find3D`) — a 3D-native, text-prompted part
 * segmentation model. Replaces the bounding-box prompt path for organic / single-shell models
 * (cat pots, figurines, vases) where the 2D-image AI struggles.
 *
 * Two-round-trip Gradio 5 protocol:
 *   1. POST  /gradio_api/upload                       (multipart) → server file path
 *   2. POST  /gradio_api/call/run_predict             (JSON)      → event_id
 *   3. GET   /gradio_api/call/run_predict/<event_id>  (SSE)       → Plotly figure JSON
 *
 * The Plotly figure carries per-point hex colours drawn from a hardcoded palette (see
 * [PALETTE_RGB]). Each colour maps 1:1 to a label index 0..N-1 where N = number of queries.
 * We send N = 4 queries ("head, body, legs, base" or user-provided) and map labels to
 * AI Paint regions in the same order.
 */
object Find3DClient {

    private const val TAG = "Find3D"
    private const val BASE_URL = "https://ziqima-find3d.hf.space"

    // The deterministic palette baked into Find3D's `get_seg_color` in inference/utils.py.
    // RGB triples in [0..1]. We only need the first ~4 entries for our 4-query case but
    // include up to 8 so we can match outputs robustly if Find3D returns lifted-mode colours.
    private val PALETTE_RGB: Array<FloatArray> = arrayOf(
        floatArrayOf(1f, 1f, 1f),      // 0 white
        floatArrayOf(1f, 0f, 0f),      // 1 red
        floatArrayOf(0f, 1f, 0f),      // 2 green
        floatArrayOf(0f, 0f, 1f),      // 3 blue
        floatArrayOf(1f, 1f, 0f),      // 4 yellow
        floatArrayOf(1f, 0f, 1f),      // 5 magenta
        floatArrayOf(0f, 1f, 1f),      // 6 cyan
        floatArrayOf(0.5f, 0.5f, 0.5f) // 7 grey
    )

    // HF Spaces wake on first call — cold start is 30-90 s, then inference adds another
    // ~30 s. Give every call up to 180 s read time.
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    /** Last raw SSE payload (truncated) — exposed for diagnostic logging. */
    @Volatile var lastRaw: String? = null

    /** Last failure reason (HTTP error / parse error / timeout) — null on success. */
    @Volatile var lastError: String? = null

    /**
     * Sample [trianglePositions] to one point per triangle (its centroid), send to Find3D,
     * and return a per-triangle region label 0..[queries.size - 1].
     *
     * @param trianglePositions flat triangle vertex positions, 9 floats per triangle.
     * @param queries 2..8 part name queries. They map to region indices in order: queries[0]
     *   → region 0, queries[1] → region 1, etc.
     * @param hfToken optional HuggingFace token for queue priority.
     * @return per-triangle labels, or null if Find3D failed (caller falls back).
     */
    suspend fun segmentByCentroid(
        trianglePositions: FloatArray,
        queries: List<String>,
        hfToken: String?,
    ): IntArray? = withContext(Dispatchers.IO) {
        require(queries.size in 2..PALETTE_RGB.size) { "Need 2..${PALETTE_RGB.size} queries, got ${queries.size}" }
        try {
            val triCount = trianglePositions.size / 9
            if (triCount == 0) {
                lastError = "empty mesh"; return@withContext null
            }
            val pcd = buildAsciiPcdFromCentroids(trianglePositions)
            Log.i(TAG, "Uploading PCD: ${pcd.size} bytes, $triCount points")

            val serverPath = upload(pcd, hfToken)
                ?: run { lastError = "upload failed"; return@withContext null }
            Log.i(TAG, "Upload OK, server path = $serverPath")

            val eventId = enqueuePredict(serverPath, queries.joinToString(", "), hfToken)
                ?: run { lastError = "predict enqueue failed"; return@withContext null }
            Log.i(TAG, "Enqueued predict event_id = $eventId")

            val plotlyFigure = streamResult(eventId, hfToken)
                ?: run { lastError = "stream/parse failed"; return@withContext null }

            val labels = parseLabels(plotlyFigure, triCount, queries.size)
            Log.i(TAG, "Find3D returned ${labels?.size} labels (expected $triCount)")
            labels?.also { lastError = null }
        } catch (e: Exception) {
            lastError = "exception: ${e.message}"
            Log.w(TAG, "Find3D failed: ${e.message}", e)
            null
        }
    }

    // ---- PCD writer ----

    internal fun buildAsciiPcdFromCentroids(trianglePositions: FloatArray): ByteArray {
        val triCount = trianglePositions.size / 9
        val header = """|VERSION 0.7
            |FIELDS x y z
            |SIZE 4 4 4
            |TYPE F F F
            |COUNT 1 1 1
            |WIDTH $triCount
            |HEIGHT 1
            |VIEWPOINT 0 0 0 1 0 0 0
            |POINTS $triCount
            |DATA ascii
            |""".trimMargin()
        val sb = StringBuilder(header.length + triCount * 32)
        sb.append(header)
        for (i in 0 until triCount) {
            val b = i * 9
            val cx = (trianglePositions[b]     + trianglePositions[b + 3] + trianglePositions[b + 6]) / 3f
            val cy = (trianglePositions[b + 1] + trianglePositions[b + 4] + trianglePositions[b + 7]) / 3f
            val cz = (trianglePositions[b + 2] + trianglePositions[b + 5] + trianglePositions[b + 8]) / 3f
            sb.append("%.4f %.4f %.4f\n".format(cx, cy, cz))
        }
        return sb.toString().toByteArray()
    }

    // ---- HTTP ----

    private fun upload(pcd: ByteArray, hfToken: String?): String? {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "files", "cloud.pcd",
                pcd.toRequestBody("application/octet-stream".toMediaType()),
            )
            .build()
        val builder = Request.Builder().url("$BASE_URL/gradio_api/upload").post(body)
        if (!hfToken.isNullOrBlank()) builder.header("Authorization", "Bearer $hfToken")
        val req = builder.build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) { Log.w(TAG, "upload HTTP ${resp.code}"); return null }
            val text = resp.body?.string() ?: return null
            val arr = JSONArray(text)
            return if (arr.length() > 0) arr.getString(0) else null
        }
    }

    private fun enqueuePredict(serverPath: String, partQueries: String, hfToken: String?): String? {
        val fileData = JSONObject()
            .put("path", serverPath)
            .put("meta", JSONObject().put("_type", "gradio.FileData"))
        val data = JSONArray()
            .put(fileData)
            .put("Segmentation")
            .put(partQueries)
        val body = JSONObject().put("data", data)
            .toString().toRequestBody("application/json".toMediaType())
        val builder = Request.Builder()
            .url("$BASE_URL/gradio_api/call/run_predict")
            .post(body)
        if (!hfToken.isNullOrBlank()) builder.header("Authorization", "Bearer $hfToken")
        client.newCall(builder.build()).execute().use { resp ->
            if (!resp.isSuccessful) {
                Log.w(TAG, "predict-enqueue HTTP ${resp.code}: ${resp.body?.string()?.take(400)}")
                return null
            }
            val text = resp.body?.string() ?: return null
            return JSONObject(text).optString("event_id").takeIf { it.isNotBlank() }
        }
    }

    private fun streamResult(eventId: String, hfToken: String?): String? {
        val url = "$BASE_URL/gradio_api/call/run_predict/$eventId"
        val builder = Request.Builder()
            .url(url)
            .header("Accept", "text/event-stream")
        if (!hfToken.isNullOrBlank()) builder.header("Authorization", "Bearer $hfToken")
        Log.i(TAG, "streamResult GET $url")
        client.newCall(builder.build()).execute().use { resp ->
            Log.i(TAG, "streamResult resp code=${resp.code} headers=${resp.headers.toMultimap()}")
            if (!resp.isSuccessful) {
                Log.w(TAG, "stream HTTP ${resp.code}: body=${resp.body?.string()?.take(800)}")
                return null
            }
            val reader = resp.body?.byteStream()?.bufferedReader() ?: run {
                Log.w(TAG, "stream body is null")
                return null
            }
            var lastDataLine: String? = null
            var eventName: String? = null
            var lineCount = 0
            var sawError = false
            while (true) {
                val line = reader.readLine() ?: break
                lineCount++
                if (lineCount <= 20) Log.i(TAG, "  sse[$lineCount]: ${line.take(200)}")
                when {
                    line.startsWith("event:") -> {
                        eventName = line.removePrefix("event:").trim()
                        if (eventName == "error") sawError = true
                    }
                    line.startsWith("data:")  -> {
                        val payload = line.removePrefix("data:").trim()
                        if (payload.isNotEmpty() && payload != "null") lastDataLine = payload
                    }
                    line.isEmpty() && eventName == "complete" -> break
                }
            }
            Log.i(TAG, "streamResult done: $lineCount lines, lastEvent=$eventName, sawError=$sawError, dataLen=${lastDataLine?.length}")
            if (sawError) {
                lastError = "Find3D Space returned event:error (the HuggingFace Space is currently failing — likely an upstream bug; their own example PCDs also error)"
                lastRaw = "event:error"
                return null
            }
            lastRaw = lastDataLine?.take(500)
            return lastDataLine
        }
    }

    // ---- Plotly figure parse ----

    /**
     * The SSE payload is `[<plotly figure JSON>]` — a JSON array with one element. The figure
     * has `data: [Scatter3d]`, and that scatter's `marker.color` is the per-point colour list
     * in input order. We map each colour to its nearest palette index in [PALETTE_RGB].
     *
     * Output is clamped to expectedTriCount in case Find3D dropped / added points (it shouldn't,
     * but defensive). Returns null if the payload doesn't match the expected shape.
     */
    internal fun parseLabels(payload: String, expectedTriCount: Int, queryCount: Int): IntArray? {
        return try {
            val outer = JSONArray(payload)
            val figure = if (outer.length() > 0) outer.getJSONObject(0) else return null
            val data = figure.optJSONArray("data") ?: return null
            if (data.length() == 0) return null
            val trace = data.getJSONObject(0)
            val marker = trace.optJSONObject("marker") ?: return null
            val colours = marker.opt("color") ?: return null
            val labelLimit = queryCount.coerceAtMost(PALETTE_RGB.size)
            val out = IntArray(expectedTriCount)
            when (colours) {
                is JSONArray -> {
                    val n = minOf(colours.length(), expectedTriCount)
                    for (i in 0 until n) {
                        val rgb = parseRgb(colours.opt(i)) ?: continue
                        out[i] = nearestPaletteIndex(rgb, labelLimit)
                    }
                }
                is String -> {
                    val rgb = parseRgb(colours) ?: return null
                    val label = nearestPaletteIndex(rgb, labelLimit)
                    for (i in 0 until expectedTriCount) out[i] = label
                }
                else -> return null
            }
            out
        } catch (e: Exception) {
            Log.w(TAG, "parseLabels failed: ${e.message}")
            null
        }
    }

    /** Accepts hex `#RRGGBB`, `rgb(r,g,b)`, or `[r,g,b]` JSONArray and returns floats in [0..1]. */
    internal fun parseRgb(any: Any?): FloatArray? {
        if (any == null) return null
        return when (any) {
            is String -> when {
                any.startsWith("#") && any.length >= 7 -> {
                    val r = any.substring(1, 3).toIntOrNull(16) ?: return null
                    val g = any.substring(3, 5).toIntOrNull(16) ?: return null
                    val b = any.substring(5, 7).toIntOrNull(16) ?: return null
                    floatArrayOf(r / 255f, g / 255f, b / 255f)
                }
                any.startsWith("rgb") -> {
                    val nums = Regex("""\d+""").findAll(any).map { it.value.toIntOrNull() }.toList()
                    if (nums.size >= 3 && nums.all { it != null }) {
                        floatArrayOf(nums[0]!! / 255f, nums[1]!! / 255f, nums[2]!! / 255f)
                    } else null
                }
                else -> null
            }
            is JSONArray -> if (any.length() >= 3) {
                floatArrayOf(any.getDouble(0).toFloat(), any.getDouble(1).toFloat(), any.getDouble(2).toFloat())
            } else null
            else -> null
        }
    }

    /** Nearest palette index by squared RGB distance, restricted to the first [labelLimit] entries. */
    internal fun nearestPaletteIndex(rgb: FloatArray, labelLimit: Int): Int {
        var best = 0; var bestDist = Float.MAX_VALUE
        val limit = labelLimit.coerceAtMost(PALETTE_RGB.size)
        for (i in 0 until limit) {
            val p = PALETTE_RGB[i]
            val dr = rgb[0] - p[0]; val dg = rgb[1] - p[1]; val db = rgb[2] - p[2]
            val d = dr * dr + dg * dg + db * db
            if (d < bestDist) { bestDist = d; best = i }
        }
        return best
    }
}
