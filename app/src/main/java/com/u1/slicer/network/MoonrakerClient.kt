package com.u1.slicer.network

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * HTTP client for Moonraker API (Klipper web interface).
 * Used to communicate with Snapmaker U1 printer.
 *
 * Ported from u1-slicer-bridge: moonraker.py
 */
class MoonrakerClient {
    private val TAG = "MoonrakerClient"

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    var baseUrl: String = ""
        set(value) {
            field = normalizeUrl(value)
        }

    /**
     * Query Moonraker's webcam list and return snapshot URL candidates for the first webcam.
     * Returns up to 2 candidates: [primary, alt] where alt keeps the original port.
     * Falls back to legacy mjpeg-streamer path if the list is unavailable.
     * Mirrors the bridge's moonraker.py get_webcams() + _resolve_moonraker_url() logic.
     */
    suspend fun queryWebcamSnapshotCandidates(): List<String> = withContext(Dispatchers.IO) {
        if (baseUrl.isBlank()) return@withContext emptyList()
        try {
            val request = Request.Builder().url(url("/server/webcams/list")).get().build()
            val response = client.newCall(request).execute()
            val body = response.body?.string()
            response.close()
            if (response.isSuccessful && body != null) {
                val webcams = org.json.JSONObject(body)
                    .getJSONObject("result")
                    .getJSONArray("webcams")
                if (webcams.length() > 0) {
                    val cam = webcams.getJSONObject(0)
                    val rawUrl = cam.optString("snapshot_url", "")
                        .ifBlank { cam.optString("snapshotUrl", "") }
                    if (rawUrl.isNotBlank()) {
                        val primary = resolveWebcamUrl(rawUrl, keepPort = false)
                        val alt = resolveWebcamUrl(rawUrl, keepPort = true)
                        val candidates = listOfNotNull(
                            primary.ifBlank { null },
                            alt.takeIf { it.isNotBlank() && it != primary }
                        )
                        if (candidates.isNotEmpty()) {
                            Log.d(TAG, "Webcam candidates: $candidates")
                            return@withContext candidates
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "Webcam list unavailable, using legacy fallback: ${e.message}")
        }
        // Legacy fallback: mjpeg-streamer style
        listOf("$baseUrl/webcam/?action=snapshot")
    }

    /** Resolve a possibly-relative webcam URL against baseUrl. Mirrors bridge's _resolve_moonraker_url(). */
    private fun resolveWebcamUrl(value: String, keepPort: Boolean): String {
        if (value.isBlank()) return ""
        // Already absolute
        if (value.startsWith("http://") || value.startsWith("https://")) return value
        // Relative — resolve against base, optionally stripping the port
        return try {
            val base = java.net.URI(baseUrl)
            val host = if (keepPort) base.host + (if (base.port != -1) ":${base.port}" else "")
                       else base.host
            "${base.scheme}://$host/${value.trimStart('/')}"
        } catch (_: Exception) {
            "$baseUrl/${value.trimStart('/')}"
        }
    }

    companion object {
        /** Normalizes a printer URL: adds http:// scheme and :7125 port if missing. */
        fun normalizeUrl(raw: String): String {
            var url = raw.trim()
            if (url.isBlank()) return ""
            // Add scheme if missing
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                url = "http://$url"
            }
            // Add default Moonraker port if no port present
            try {
                val uri = java.net.URI(url)
                if (uri.port == -1) {
                    // Insert :7125 before any path
                    val path = if (uri.rawPath.isNullOrEmpty()) "" else uri.rawPath
                    url = "${uri.scheme}://${uri.host}:7125$path"
                }
            } catch (_: Exception) { /* keep as-is */ }
            return url.trimEnd('/')
        }
    }

    private fun url(path: String): String = "$baseUrl$path"

    /**
     * Test connection to the printer.
     * @return null on success, or a human-readable error string on failure.
     */
    suspend fun testConnection(): String? = withContext(Dispatchers.IO) {
        if (baseUrl.isBlank()) return@withContext "No URL configured"
        if (!baseUrl.startsWith("http://") && !baseUrl.startsWith("https://")) {
            return@withContext "URL must start with http:// or https://"
        }
        try {
            val request = Request.Builder().url(url("/server/info")).get().build()
            val response = client.newCall(request).execute()
            val ok = response.isSuccessful
            val code = response.code
            response.close()
            if (ok) null else "HTTP $code — is Moonraker running on this address?"
        } catch (e: java.net.ConnectException) {
            "Connection refused — check IP and port (Moonraker default: 7125)"
        } catch (e: java.net.SocketTimeoutException) {
            "Timed out — check printer is on and reachable"
        } catch (e: java.net.UnknownHostException) {
            "Unknown host — check URL"
        } catch (e: Exception) {
            Log.w(TAG, "Connection test failed: ${e.message}")
            e.message?.take(100) ?: "Connection failed"
        }
    }

    /**
     * Get printer status: temps, state, progress.
     */
    suspend fun getStatus(): PrinterStatus = withContext(Dispatchers.IO) {
        if (baseUrl.isBlank()) return@withContext PrinterStatus(state = "disconnected", progress = 0f)
        try {
            val queryParams = "print_stats&heater_bed&extruder&extruder1&extruder2&extruder3"
            val request = Request.Builder()
                .url(url("/printer/objects/query?$queryParams"))
                .get().build()
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                response.close()
                return@withContext PrinterStatus(state = "error", progress = 0f)
            }

            val json = JSONObject(response.body?.string() ?: "{}")
            response.close()
            val result = json.optJSONObject("result")?.optJSONObject("status")
                ?: return@withContext PrinterStatus(state = "standby", progress = 0f)

            val printStats = result.optJSONObject("print_stats")
            val heaterBed = result.optJSONObject("heater_bed")
            val extruder = result.optJSONObject("extruder")

            // Parse extruders 1-3
            val extruders = mutableListOf<ExtruderStatus>()
            extruder?.let {
                extruders.add(ExtruderStatus(0,
                    it.optDouble("temperature", 0.0).toFloat(),
                    it.optDouble("target", 0.0).toFloat(),
                    it.optDouble("target", 0.0) > 0
                ))
            }
            for (i in 1..3) {
                result.optJSONObject("extruder$i")?.let {
                    extruders.add(ExtruderStatus(i,
                        it.optDouble("temperature", 0.0).toFloat(),
                        it.optDouble("target", 0.0).toFloat(),
                        it.optDouble("target", 0.0) > 0
                    ))
                }
            }

            PrinterStatus(
                state = printStats?.optString("state", "standby") ?: "standby",
                progress = printStats?.optDouble("progress", 0.0)?.toFloat() ?: 0f,
                filename = printStats?.optString("filename", "") ?: "",
                printDuration = printStats?.optDouble("print_duration", 0.0)?.toFloat() ?: 0f,
                filamentUsed = printStats?.optDouble("filament_used", 0.0)?.toFloat() ?: 0f,
                nozzleTemp = extruder?.optDouble("temperature", 0.0)?.toFloat() ?: 0f,
                nozzleTarget = extruder?.optDouble("target", 0.0)?.toFloat() ?: 0f,
                bedTemp = heaterBed?.optDouble("temperature", 0.0)?.toFloat() ?: 0f,
                bedTarget = heaterBed?.optDouble("target", 0.0)?.toFloat() ?: 0f,
                extruders = extruders
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get status: ${e.message}")
            PrinterStatus(state = "disconnected", progress = 0f)
        }
    }

    /**
     * Query per-extruder filament information from the printer.
     * Primary source: print_task_config (Snapmaker U1 firmware).
     * Fallback: AFC (Automatic Filament Changer) Klipper objects.
     * Returns null if neither source is available.
     */
    suspend fun queryFilamentSlots(): List<FilamentSlot>? = withContext(Dispatchers.IO) {
        if (baseUrl.isBlank()) return@withContext null
        // Try print_task_config first (native Snapmaker U1 source)
        val fromTask = queryPrintTaskConfig()
        if (!fromTask.isNullOrEmpty()) return@withContext fromTask
        // Fallback to AFC
        queryAfcSlots()
    }

    private fun queryPrintTaskConfig(): List<FilamentSlot>? {
        return try {
            val request = Request.Builder()
                .url(url("/printer/objects/query?print_task_config=&filament_detect="))
                .get().build()
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) { response.close(); return null }
            val json = JSONObject(response.body?.string() ?: "{}")
            response.close()

            val status = json.optJSONObject("result")?.optJSONObject("status") ?: return null
            val ptc = status.optJSONObject("print_task_config") ?: return null

            val colors = ptc.optJSONArray("filament_color_rgba")
            val types = ptc.optJSONArray("filament_type")
            val subTypes = ptc.optJSONArray("filament_sub_type")
            val vendors = ptc.optJSONArray("filament_vendor")
            val exist = ptc.optJSONArray("filament_exist")

            if (colors == null || colors.length() == 0) return null

            val slots = mutableListOf<FilamentSlot>()
            for (i in 0 until colors.length()) {
                val rgba = colors.optString(i, "")
                val loaded = exist?.optInt(i, 0) != 0
                slots.add(FilamentSlot(
                    index = i,
                    label = "E${i + 1}",
                    color = normalizeRgbaHex(rgba),
                    loaded = loaded,
                    materialType = normalizeMaterialType(types?.optString(i, "PLA") ?: "PLA"),
                    subType = subTypes?.optString(i, "") ?: "",
                    manufacturer = vendors?.optString(i, "") ?: ""
                ))
            }
            slots.takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            Log.w(TAG, "print_task_config query failed: ${e.message}")
            null
        }
    }

    private fun queryAfcSlots(): List<FilamentSlot>? {
        return try {
            // Discover AFC objects via /printer/objects/list
            val listReq = Request.Builder().url(url("/printer/objects/list")).get().build()
            val listResp = client.newCall(listReq).execute()
            if (!listResp.isSuccessful) { listResp.close(); return null }
            val listJson = JSONObject(listResp.body?.string() ?: "{}")
            listResp.close()

            val objects = listJson.optJSONObject("result")?.optJSONArray("objects") ?: return null
            val afcObjects = mutableListOf<String>()
            for (i in 0 until objects.length()) {
                val name = objects.optString(i)
                if (name.startsWith("AFC_stepper ") || name.startsWith("AFC ")) {
                    afcObjects.add(name)
                }
            }
            if (afcObjects.isEmpty()) return null

            // Query all AFC objects at once
            val queryStr = afcObjects.joinToString("&") { java.net.URLEncoder.encode(it, "UTF-8") + "=" }
            val afcReq = Request.Builder().url(url("/printer/objects/query?$queryStr")).get().build()
            val afcResp = client.newCall(afcReq).execute()
            if (!afcResp.isSuccessful) { afcResp.close(); return null }
            val afcJson = JSONObject(afcResp.body?.string() ?: "{}")
            afcResp.close()

            val afcStatus = afcJson.optJSONObject("result")?.optJSONObject("status") ?: return null
            val slots = mutableListOf<FilamentSlot>()
            var idx = 0
            for (objName in afcObjects) {
                val obj = afcStatus.optJSONObject(objName) ?: continue
                val color = obj.optString("color", "").let {
                    if (it.isNotBlank()) "#${it.uppercase().trimStart('#')}" else "#808080"
                }
                val material = normalizeMaterialType(obj.optString("material", "PLA"))
                val loaded = obj.optBoolean("load_state", false) ||
                             obj.optString("status", "") == "Loaded"
                val laneNum = objName.substringAfterLast(" ").filter { it.isDigit() }
                slots.add(FilamentSlot(
                    index = idx++,
                    label = if (laneNum.isNotEmpty()) "E$laneNum" else "E${idx}",
                    color = color,
                    loaded = loaded,
                    materialType = material
                ))
            }
            slots.takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            Log.w(TAG, "AFC query failed: ${e.message}")
            null
        }
    }

    /** Convert Bambu RGBA hex (e.g. "E72F1DFF") to #RRGGBB */
    private fun normalizeRgbaHex(rgba: String): String {
        val cleaned = rgba.trimStart('#').uppercase()
        return when {
            cleaned.length == 8 -> "#${cleaned.take(6)}"  // RRGGBBAA → #RRGGBB
            cleaned.length == 6 -> "#$cleaned"
            else -> "#808080"
        }
    }

    /** Normalize material type strings to canonical forms */
    private fun normalizeMaterialType(raw: String): String {
        return when (raw.uppercase().trim()) {
            "PLA+", "PLA PLUS", "PLAP" -> "PLA"
            "PET-G", "PETG-", "PETG+" -> "PETG"
            "ABS+", "ABS PLUS" -> "ABS"
            "FLEX", "TPU95A", "TPU87A" -> "TPU"
            "HIPS-", "HIPS+" -> "HIPS"
            else -> raw.trim().ifEmpty { "PLA" }
        }
    }

    /**
     * Upload a G-code file to the printer.
     */
    suspend fun uploadGcode(file: File, filename: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val fileSizeMb = file.length() / (1024 * 1024)
            val timeout = maxOf(30L, minOf(300L, fileSizeMb))

            val uploadClient = client.newBuilder()
                .writeTimeout(timeout, TimeUnit.SECONDS)
                .readTimeout(timeout, TimeUnit.SECONDS)
                .build()

            val body = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", filename,
                    file.asRequestBody("application/octet-stream".toMediaType()))
                .build()

            val request = Request.Builder()
                .url(url("/server/files/upload"))
                .post(body)
                .build()

            val response = uploadClient.newCall(request).execute()
            val ok = response.isSuccessful
            response.close()
            Log.i(TAG, "Upload ${if (ok) "success" else "failed"}: $filename")
            ok
        } catch (e: Exception) {
            Log.e(TAG, "Upload failed: ${e.message}")
            false
        }
    }

    /**
     * Start printing a previously uploaded file.
     */
    suspend fun startPrint(filename: String): Boolean = withContext(Dispatchers.IO) {
        postCommand("/printer/print/start?filename=$filename")
    }

    suspend fun pausePrint(): Boolean = withContext(Dispatchers.IO) {
        postCommand("/printer/print/pause")
    }

    suspend fun resumePrint(): Boolean = withContext(Dispatchers.IO) {
        postCommand("/printer/print/resume")
    }

    suspend fun cancelPrint(): Boolean = withContext(Dispatchers.IO) {
        postCommand("/printer/print/cancel")
    }

    suspend fun getLedState(): Boolean? = withContext(Dispatchers.IO) {
        if (baseUrl.isBlank()) return@withContext null
        try {
            val request = Request.Builder()
                .url(url("/printer/objects/query?led%20cavity_led"))
                .get().build()
            val response = client.newCall(request).execute()
            val body = response.body?.string()
            response.close()
            if (!response.isSuccessful || body == null) return@withContext null
            val result = JSONObject(body).optJSONObject("result")?.optJSONObject("status")
            val ledObj = result?.optJSONObject("led cavity_led") ?: return@withContext null
            val colorData = ledObj.optJSONArray("color_data")
            if (colorData != null && colorData.length() > 0) {
                val rgba = colorData.getJSONArray(0)
                // W channel is index 3 for RGBW
                val w = if (rgba.length() > 3) rgba.getDouble(3) else 0.0
                w > 0
            } else null
        } catch (e: Exception) {
            Log.d(TAG, "LED state query failed: ${e.message}")
            null
        }
    }

    suspend fun setLed(on: Boolean): Boolean = withContext(Dispatchers.IO) {
        if (baseUrl.isBlank()) return@withContext false
        val white = if (on) "1.0" else "0"
        val script = "SET_LED LED=cavity_led WHITE=$white RED=0 GREEN=0 BLUE=0"
        try {
            val body = JSONObject().put("script", script).toString()
                .toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url(url("/printer/gcode/script"))
                .post(body)
                .build()
            val response = client.newCall(request).execute()
            val ok = response.isSuccessful
            response.close()
            ok
        } catch (e: Exception) {
            Log.w(TAG, "Set LED failed: ${e.message}")
            false
        }
    }

    private fun postCommand(path: String): Boolean {
        return try {
            val request = Request.Builder()
                .url(url(path))
                .post(ByteArray(0).toRequestBody(null))
                .build()
            val response = client.newCall(request).execute()
            val ok = response.isSuccessful
            response.close()
            ok
        } catch (e: Exception) {
            Log.w(TAG, "Command failed ($path): ${e.message}")
            false
        }
    }
}
