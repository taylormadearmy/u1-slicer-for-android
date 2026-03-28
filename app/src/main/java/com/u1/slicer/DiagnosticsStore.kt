package com.u1.slicer

import android.content.Context
import android.content.pm.PackageManager
import android.os.Process
import androidx.core.content.pm.PackageInfoCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class DiagnosticsStore(private val context: Context) {
    data class PostUpgradeObservation(
        val status: String,
        val previousSessionId: String?,
        val previousPid: Int?,
        val previousNativeGeneration: String?,
        val currentSessionId: String,
        val currentPid: Int,
        val currentNativeGeneration: String?
    )

    companion object {
        private const val PREFS_NAME = "clipper_diagnostics"
        private const val KEY_PENDING_RESTART = "pending_restart"
        private const val KEY_SLICE_IN_PROGRESS = "slice_in_progress"
        private const val KEY_CLIPPER_RECOVERY_PENDING = "clipper_recovery_pending"
        private const val MAX_HISTORY_LINES = 200

        @Volatile
        private var sessionIdCache: String? = null

        internal fun trimToMax(lines: List<String>, maxEntries: Int): List<String> {
            if (lines.size <= maxEntries) return lines
            return lines.takeLast(maxEntries)
        }

        internal fun classifyRestartObservation(
            previousSessionId: String?,
            previousPid: Int?,
            previousNativeGeneration: String?,
            currentSessionId: String,
            currentPid: Int,
            currentNativeGeneration: String?
        ): String {
            if (previousSessionId == null && previousPid == null && previousNativeGeneration == null) {
                return "not_requested"
            }
            return if (previousSessionId != currentSessionId ||
                previousPid != currentPid ||
                (previousNativeGeneration != null &&
                    currentNativeGeneration != null &&
                    previousNativeGeneration != currentNativeGeneration)
            ) {
                "fresh_process"
            } else {
                "same_process_or_unknown"
            }
        }

    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val packageInfo by lazy {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(context.packageName, 0)
    }

    val sessionId: String = synchronized(this) {
        sessionIdCache ?: "${System.currentTimeMillis()}-${Process.myPid()}".also { sessionIdCache = it }
    }

    private var firstModelLoadRecorded = false
    private var firstSliceRecorded = false

    private val diagnosticsDir = File(context.filesDir, "diagnostics").apply { mkdirs() }
    private val historyFile = File(diagnosticsDir, "clipper_investigation.jsonl")
    private val bundleFile = File(diagnosticsDir, "clipper_investigation_bundle.txt")

    fun diagnosticsPath(): String = historyFile.absolutePath

    fun pendingUpgradeTrigger(): String? {
        val pendingJson = prefs.getString(KEY_PENDING_RESTART, null) ?: return null
        return try {
            JSONObject(pendingJson).optString("trigger").ifBlank { null }
        } catch (_: Exception) {
            null
        }
    }

    fun markFirstModelLoad(): Boolean {
        val first = !firstModelLoadRecorded
        firstModelLoadRecorded = true
        return first
    }

    fun markSliceStart(): Boolean {
        val firstSliceThisLaunch = !firstSliceRecorded
        firstSliceRecorded = true
        return firstSliceThisLaunch
    }

    fun markUpgradePendingForCurrentSession(trigger: String, nativeStateJson: String?): Boolean {
        val persisted = persistPendingUpgradeMarker(trigger, nativeStateJson)
        recordEvent(
            "post_upgrade_guard_armed",
            mapOf(
                "trigger" to trigger,
                "nativeState" to nativeStateJson,
                "markerPersisted" to persisted,
                "continuingInCurrentProcess" to true
            )
        )
        return persisted
    }

    /**
     * Persist a "slice in progress" marker before calling native.slice().
     * If the process is killed (OOM, SIGSEGV) the marker survives and is detected
     * on the next launch via [consumeSliceInProgressMarker].
     */
    fun markSliceInProgress(modelName: String) {
        val marker = JSONObject()
        marker.put("modelName", modelName)
        marker.put("startedAtMs", System.currentTimeMillis())
        marker.put("sessionId", sessionId)
        marker.put("pid", Process.myPid())
        marker.put("appVersion", BuildConfig.VERSION_NAME)
        prefs.edit().putString(KEY_SLICE_IN_PROGRESS, marker.toString()).commit()
    }

    /** Clear the in-progress marker after slice completes or throws a caught exception. */
    fun clearSliceInProgress() {
        prefs.edit().remove(KEY_SLICE_IN_PROGRESS).apply()
    }

    /**
     * Called once on app launch. Returns the stale marker JSON string if the previous
     * slice never completed (hard crash), and removes it. Returns null otherwise.
     */
    fun consumeSliceInProgressMarker(): String? {
        val marker = prefs.getString(KEY_SLICE_IN_PROGRESS, null) ?: return null
        prefs.edit().remove(KEY_SLICE_IN_PROGRESS).apply()
        recordEvent(
            "slice_in_progress_marker_consumed",
            mapOf("marker" to marker)
        )
        return marker
    }

    /**
     * Persist a flag indicating that a clipper recovery restart is in progress.
     * Survives process kill so the relaunched session knows not to auto-restart again
     * for the same error (prevents crash-loop: error → restart → same error → restart).
     */
    fun markClipperRecoveryPending() {
        prefs.edit().putBoolean(KEY_CLIPPER_RECOVERY_PENDING, true).commit()
    }

    /**
     * Check and consume the clipper recovery flag. Returns true if the previous session
     * was killed for clipper recovery, meaning we should NOT auto-restart again.
     */
    fun consumeClipperRecoveryPending(): Boolean {
        val pending = prefs.getBoolean(KEY_CLIPPER_RECOVERY_PENDING, false)
        if (pending) {
            prefs.edit().remove(KEY_CLIPPER_RECOVERY_PENDING).apply()
            recordEvent("clipper_recovery_pending_consumed", mapOf("pending" to true))
        }
        return pending
    }

    @Synchronized
    fun recordEvent(type: String, details: Map<String, Any?> = emptyMap()) {
        diagnosticsDir.mkdirs()
        val obj = JSONObject()
        obj.put("type", type)
        obj.put("timestampMs", System.currentTimeMillis())
        obj.put("sessionId", sessionId)
        obj.put("pid", Process.myPid())
        obj.put("appVersion", BuildConfig.VERSION_NAME)
        obj.put("versionCode", PackageInfoCompat.getLongVersionCode(packageInfo))
        obj.put("apkLastUpdateTime", packageInfo.lastUpdateTime)
        obj.put("buildType", if (BuildConfig.DEBUG) "debug" else "release")
        details.forEach { (key, value) -> obj.put(key, toJsonValue(value)) }
        historyFile.appendText(obj.toString() + "\n")
        trimHistory()
    }

    fun markUpgradeRestartRequested(trigger: String, nativeStateJson: String?): Boolean {
        val persisted = persistPendingUpgradeMarker(trigger, nativeStateJson)
        recordEvent(
            "restart_requested",
            mapOf(
                "trigger" to trigger,
                "nativeState" to nativeStateJson,
                "markerPersisted" to persisted
            )
        )
        return persisted
    }

    fun consumePendingUpgradeMarker(): Boolean {
        val pending = prefs.contains(KEY_PENDING_RESTART)
        if (pending) {
            val marker = prefs.getString(KEY_PENDING_RESTART, null)
            prefs.edit().remove(KEY_PENDING_RESTART).apply()
            recordEvent(
                "pending_upgrade_marker_consumed",
                mapOf("marker" to marker)
            )
        }
        return pending
    }

    private fun persistPendingUpgradeMarker(trigger: String, nativeStateJson: String?): Boolean {
        val nativeState = nativeStateJson?.let(::parseNativeState)
        val marker = JSONObject()
        marker.put("trigger", trigger)
        marker.put("requestedAtMs", System.currentTimeMillis())
        marker.put("sessionId", sessionId)
        marker.put("pid", Process.myPid())
        marker.put("appVersion", BuildConfig.VERSION_NAME)
        marker.put("versionCode", PackageInfoCompat.getLongVersionCode(packageInfo))
        marker.put("apkLastUpdateTime", packageInfo.lastUpdateTime)
        marker.put("nativeGeneration", nativeState?.optString("nativeGeneration"))
        return prefs.edit().putString(KEY_PENDING_RESTART, marker.toString()).commit()
    }

    fun recordNativeConfigured(nativeStateJson: String?): PostUpgradeObservation? {
        recordEvent("native_configured", mapOf("nativeState" to nativeStateJson))
        val pendingJson = prefs.getString(KEY_PENDING_RESTART, null) ?: return null
        val pending = try {
            JSONObject(pendingJson)
        } catch (_: Exception) {
            prefs.edit().remove(KEY_PENDING_RESTART).apply()
            return null
        }
        val nativeState = nativeStateJson?.let(::parseNativeState)
        val observation = PostUpgradeObservation(
            status = classifyRestartObservation(
                previousSessionId = pending.optString("sessionId").ifBlank { null },
                previousPid = pending.optInt("pid").takeIf { it != 0 },
                previousNativeGeneration = pending.optString("nativeGeneration").ifBlank { null },
                currentSessionId = sessionId,
                currentPid = Process.myPid(),
                currentNativeGeneration = nativeState?.optString("nativeGeneration")
            ),
            previousSessionId = pending.optString("sessionId").ifBlank { null },
            previousPid = pending.optInt("pid").takeIf { it != 0 },
            previousNativeGeneration = pending.optString("nativeGeneration").ifBlank { null },
            currentSessionId = sessionId,
            currentPid = Process.myPid(),
            currentNativeGeneration = nativeState?.optString("nativeGeneration")
        )
        recordEvent(
            "post_upgrade_guard_observed",
            mapOf(
                "status" to observation.status,
                "previousSessionId" to observation.previousSessionId,
                "previousPid" to observation.previousPid,
                "previousNativeGeneration" to observation.previousNativeGeneration,
                "currentNativeGeneration" to observation.currentNativeGeneration
            )
        )
        return observation
    }

    fun buildBundle(latestError: String? = null): File {
        diagnosticsDir.mkdirs()
        val pendingRestart = prefs.getString(KEY_PENDING_RESTART, null)
        val historyLines = if (historyFile.exists()) historyFile.readLines() else emptyList()
        val timelineLines = buildTimelineLines(historyLines)
        val out = buildString {
            appendLine("U1 Slicer Clipper Investigation Bundle")
            appendLine("sessionId=$sessionId")
            appendLine("pid=${Process.myPid()}")
            appendLine("appVersion=${BuildConfig.VERSION_NAME}")
            appendLine("versionCode=${PackageInfoCompat.getLongVersionCode(packageInfo)}")
            appendLine("apkLastUpdateTime=${packageInfo.lastUpdateTime}")
            appendLine("buildType=${if (BuildConfig.DEBUG) "debug" else "release"}")
            if (!latestError.isNullOrBlank()) appendLine("latestError=$latestError")
            appendLine("pendingUpgradeMarker=${pendingRestart ?: "<none>"}")
            if (timelineLines.isNotEmpty()) {
                appendLine()
                appendLine("Timeline:")
                timelineLines.forEach { appendLine(it) }
            }
            appendLine()
            appendLine("Recent events:")
            trimToMax(historyLines, MAX_HISTORY_LINES).forEach { appendLine(it) }
        }
        bundleFile.writeText(out)
        return bundleFile
    }

    private fun buildTimelineLines(historyLines: List<String>): List<String> {
        val interesting = setOf(
            "app_launch",
            "upgrade_check",
            "apk_changed_while_running",
            "native_configured",
            "post_upgrade_guard_armed",
            "post_upgrade_guard_observed",
            "pending_upgrade_marker_consumed",
            "slice_in_progress_marker_consumed",
            "clipper_recovery_pending_consumed",
            "clipper_recovery_suppressed",
            "clipper_failure",
            "hard_crash_during_slice",
            "manual_restart_requested",
            "manual_reset_app_state",
            "native_model_cleared",
            "slice_geometry_snapshot",
            "slice_process_snapshot",
            "slice_native_model_snapshot",
            "slice_file_snapshot",
            "first_layer_dispatch_snapshot",
            "first_layer_region_snapshot",
            "first_layer_extrude_entry_snapshot",
            "first_layer_extrude_snapshot",
            "gcode_writer_invalid_line",
            "slice_output_validation",
            "slice_output_invalid",
            "clipper_coordinate_out_of_range",
            "clipper_invalid_path_detected",
            "native_trace_flush",
            "clipper_bounds_fallback",
            "wipe_tower_rib_snapshot",
            "wipe_tower_support_wall_snapshot",
            "wipe_tower_invalid_rib_polygon",
            "partplate_wipe_tower_polygon",
            "partplate_invalid_wipe_tower_polygon",
            "snapmaker_wipe_tower_polygon",
            "snapmaker_invalid_wipe_tower_polygon",
            "first_layer_brim_snapshot",
            "first_layer_islands_snapshot",
            "first_layer_convex_hull_snapshot",
            "head_wrap_detect_zone_inputs",
            "head_wrap_detect_zone_skipped",
            "head_wrap_detect_zone_fallback",
            "restart_requested",
            "clipper_recovery_deferred"
        )
        return historyLines.mapNotNull { line ->
            val obj = try {
                JSONObject(line)
            } catch (_: Exception) {
                return@mapNotNull null
            }
            val type = obj.optString("type")
            if (type !in interesting) return@mapNotNull null
            val timestamp = obj.optLong("timestampMs", 0L)
            val summary = when (type) {
                "app_launch" -> "app_launch"
                "upgrade_check" -> "upgrade_check result=${obj.optString("result")} reason=${obj.optString("reason")}"
                "apk_changed_while_running" -> "apk_changed_while_running launch=${obj.optLong("launchApkUpdateTime")} current=${obj.optLong("currentApkUpdateTime")}"
                "native_configured" -> "native_configured state=${obj.optString("nativeState")}"
                "post_upgrade_guard_armed" -> "post_upgrade_guard_armed trigger=${obj.optString("trigger")} persisted=${obj.optString("markerPersisted")}"
                "post_upgrade_guard_observed" -> "post_upgrade_guard_observed status=${obj.optString("status")}"
                "pending_upgrade_marker_consumed" -> "pending_upgrade_marker_consumed"
                "slice_in_progress_marker_consumed" -> "slice_in_progress_marker_consumed"
                "clipper_recovery_pending_consumed" -> "clipper_recovery_pending_consumed"
                "clipper_recovery_suppressed" -> "clipper_recovery_suppressed"
                "clipper_failure" -> "clipper_failure source=${obj.optString("source")} autoRecovery=${obj.optString("autoRecoveryAttempted")}"
                "hard_crash_during_slice" -> "hard_crash_during_slice"
                "manual_restart_requested" -> "manual_restart_requested"
                "manual_reset_app_state" -> "manual_reset_app_state files=${obj.optInt("filesCleared")} cache=${obj.optInt("cacheCleared")} total=${obj.optInt("totalCleared")}"
                "native_model_cleared" -> "native_model_cleared payload=${obj.optJSONObject("payload")}"
                "slice_geometry_snapshot" -> "slice_geometry_snapshot model=${obj.optString("modelName")} copies=${obj.optInt("copyCount")} wipeTower=${obj.optJSONObject("wipeTower")?.optBoolean("enabled")} "
                "slice_process_snapshot" -> "slice_process_snapshot pid=${obj.optInt("pid")} javaHeapUsed=${obj.optLong("javaHeapUsedBytes")} nativeHeapAllocated=${obj.optLong("nativeHeapAllocatedBytes")} lowMemory=${obj.opt("systemLowMemory")}"
                "slice_native_model_snapshot" -> "slice_native_model_snapshot payload=${obj.optJSONObject("payload")}"
                "slice_file_snapshot" -> "slice_file_snapshot raw=${obj.optJSONObject("rawInputFile")?.optString("sha256")} current=${obj.optJSONObject("currentModelFile")?.optString("sha256")}"
                "first_layer_dispatch_snapshot" -> "first_layer_dispatch_snapshot phase=${obj.optString("phase")} object=${obj.optInt("objectIndex")} layer=${obj.optInt("layerId")} regionCount=${obj.optInt("regionCount")} perimeters=${obj.optInt("totalPerimeterCount")} infills=${obj.optInt("totalInfillCount")}"
                "first_layer_region_snapshot" -> "first_layer_region_snapshot phase=${obj.optString("phase")} region=${obj.optInt("regionIndex")} perimeters=${obj.optInt("perimeterCount")} infills=${obj.optInt("infillCount")}"
                "first_layer_extrude_entry_snapshot" -> "first_layer_extrude_entry_snapshot entry=${obj.optString("entryPoint")} role=${obj.optString("role")} layer=${obj.optInt("layerIndex")} pathCount=${obj.optInt("pathCount")}"
                "first_layer_extrude_snapshot" -> "first_layer_extrude_snapshot role=${obj.optString("role")} layer=${obj.optInt("layerIndex")} points=${obj.optInt("pointCount")} speed=${obj.optDouble("speed")}"
                "gcode_writer_window_line" -> "gcode_writer_window_line lineIndex=${obj.optInt("lineIndex")} reason=${obj.optString("reason")} op=${obj.optString("operationLabel")}"
                "gcode_writer_invalid_line" -> "gcode_writer_invalid_line lineIndex=${obj.optInt("lineIndex")} op=${obj.optString("operationLabel")}"
                "slice_output_validation" -> "slice_output_validation layers=${obj.optInt("parsedLayerCount")} extrude=${obj.optInt("parsedExtrudeMoves")} nonPrime=${obj.optInt("parsedNonPrimeExtrudeMoves")} est=${obj.opt("nativeEstimatedTimeSeconds")}"
                "slice_output_invalid" -> "slice_output_invalid reason=${obj.optString("reason")} nonPrime=${obj.optInt("parsedNonPrimeExtrudeMoves")} est=${obj.opt("nativeEstimatedTimeSeconds")}"
                "clipper_coordinate_out_of_range" -> "clipper_coordinate_out_of_range payload=${obj.optJSONObject("payload")}"
                  "clipper_invalid_path_detected" -> "clipper_invalid_path_detected payload=${obj.optJSONObject("payload")}"
                  "native_trace_flush" -> "native_trace_flush reason=${obj.optJSONObject("payload")?.optString("reason")} entries=${obj.optJSONObject("payload")?.optInt("entryCount")}"
                  "clipper_bounds_fallback" -> "clipper_bounds_fallback payload=${obj.optJSONObject("payload")}"
                "wipe_tower_rib_snapshot" -> "wipe_tower_rib_snapshot payload=${obj.optJSONObject("payload")}"
                "wipe_tower_support_wall_snapshot" -> "wipe_tower_support_wall_snapshot payload=${obj.optJSONObject("payload")}"
                "wipe_tower_invalid_rib_polygon" -> "wipe_tower_invalid_rib_polygon payload=${obj.optJSONObject("payload")}"
                "partplate_wipe_tower_polygon" -> "partplate_wipe_tower_polygon payload=${obj.optJSONObject("payload")}"
                "partplate_invalid_wipe_tower_polygon" -> "partplate_invalid_wipe_tower_polygon payload=${obj.optJSONObject("payload")}"
                "snapmaker_wipe_tower_polygon" -> "snapmaker_wipe_tower_polygon payload=${obj.optJSONObject("payload")}"
                "snapmaker_invalid_wipe_tower_polygon" -> "snapmaker_invalid_wipe_tower_polygon payload=${obj.optJSONObject("payload")}"
                "first_layer_brim_snapshot" -> "first_layer_brim_snapshot payload=${obj.optJSONObject("payload")}"
                "first_layer_islands_snapshot" -> "first_layer_islands_snapshot payload=${obj.optJSONObject("payload")}"
                "first_layer_convex_hull_snapshot" -> "first_layer_convex_hull_snapshot payload=${obj.optJSONObject("payload")}"
                "head_wrap_detect_zone_inputs" -> "head_wrap_detect_zone_inputs payload=${obj.optJSONObject("payload")}"
                "head_wrap_detect_zone_skipped" -> "head_wrap_detect_zone_skipped payload=${obj.optJSONObject("payload")}"
                "head_wrap_detect_zone_fallback" -> "head_wrap_detect_zone_fallback payload=${obj.optJSONObject("payload")}"
                "restart_requested" -> "restart_requested trigger=${obj.optString("trigger")}"
                "clipper_recovery_deferred" -> "clipper_recovery_deferred"
                else -> type
            }
            val timeText = if (timestamp > 0L) {
                java.time.Instant.ofEpochMilli(timestamp).toString()
            } else {
                "unknown-time"
            }
            "$timeText $summary"
        }.takeLast(25)
    }

    private fun trimHistory() {
        if (!historyFile.exists()) return
        val lines = historyFile.readLines()
        val trimmed = trimToMax(lines, MAX_HISTORY_LINES)
        if (trimmed.size != lines.size) {
            historyFile.writeText(trimmed.joinToString(separator = "\n", postfix = "\n"))
        }
    }

    private fun parseNativeState(json: String): JSONObject? {
        return try {
            JSONObject(json)
        } catch (_: Exception) {
            null
        }
    }

    private fun toJsonValue(value: Any?): Any {
        return when (value) {
            null -> JSONObject.NULL
            is JSONObject -> value
            is JSONArray -> value
            is Map<*, *> -> JSONObject().apply {
                value.forEach { (k, v) -> put(k.toString(), toJsonValue(v)) }
            }
            is Iterable<*> -> JSONArray().apply { value.forEach { put(toJsonValue(it)) } }
            is Array<*> -> JSONArray().apply { value.forEach { put(toJsonValue(it)) } }
            is Boolean, is Number, is String -> value
            else -> value.toString()
        }
    }
}
