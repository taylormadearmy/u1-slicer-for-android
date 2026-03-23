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

    private var sessionHasPostUpgradeGuard = prefs.contains(KEY_PENDING_RESTART)
    private var firstModelLoadRecorded = false
    private var firstSliceRecorded = false
    private var firstSliceAfterUpgradeRecorded = false

    private val diagnosticsDir = File(context.filesDir, "diagnostics").apply { mkdirs() }
    private val historyFile = File(diagnosticsDir, "clipper_investigation.jsonl")
    private val bundleFile = File(diagnosticsDir, "clipper_investigation_bundle.txt")

    fun diagnosticsPath(): String = historyFile.absolutePath

    fun sessionHasPostUpgradeGuard(): Boolean = sessionHasPostUpgradeGuard

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

    fun markSliceStart(): Pair<Boolean, Boolean> {
        val firstSliceThisLaunch = !firstSliceRecorded
        firstSliceRecorded = true
        val firstSliceAfterUpgrade = sessionHasPostUpgradeGuard && !firstSliceAfterUpgradeRecorded
        if (firstSliceAfterUpgrade) firstSliceAfterUpgradeRecorded = true
        return firstSliceThisLaunch to firstSliceAfterUpgrade
    }

    fun markUpgradePendingForCurrentSession(trigger: String, nativeStateJson: String?): Boolean {
        val persisted = persistPendingUpgradeMarker(trigger, nativeStateJson)
        sessionHasPostUpgradeGuard = sessionHasPostUpgradeGuard || persisted
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

    fun markSliceSucceeded() {
        if (!prefs.contains(KEY_PENDING_RESTART)) return
        prefs.edit().remove(KEY_PENDING_RESTART).apply()
        sessionHasPostUpgradeGuard = false
        recordEvent("post_upgrade_slice_settled")
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
        obj.put("sessionHasPostUpgradeGuard", sessionHasPostUpgradeGuard)
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
            sessionHasPostUpgradeGuard = false
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
            appendLine("sessionHasPostUpgradeGuard=$sessionHasPostUpgradeGuard")
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
            "native_configured",
            "post_upgrade_guard_armed",
            "post_upgrade_guard_observed",
            "post_upgrade_slice_settled",
            "pending_upgrade_marker_consumed",
            "slice_in_progress_marker_consumed",
            "clipper_recovery_pending_consumed",
            "clipper_recovery_suppressed",
            "clipper_failure",
            "hard_crash_during_slice",
            "manual_restart_requested",
            "manual_reset_app_state",
            "upgrade_slice_skip_clear_model",
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
                "app_launch" -> "app_launch guard=${obj.optString("sessionHasPostUpgradeGuard")}"
                "upgrade_check" -> "upgrade_check result=${obj.optString("result")} reason=${obj.optString("reason")}"
                "native_configured" -> "native_configured state=${obj.optString("nativeState")}"
                "post_upgrade_guard_armed" -> "post_upgrade_guard_armed trigger=${obj.optString("trigger")} persisted=${obj.optString("markerPersisted")}"
                "post_upgrade_guard_observed" -> "post_upgrade_guard_observed status=${obj.optString("status")}"
                "post_upgrade_slice_settled" -> "post_upgrade_slice_settled"
                "pending_upgrade_marker_consumed" -> "pending_upgrade_marker_consumed"
                "slice_in_progress_marker_consumed" -> "slice_in_progress_marker_consumed"
                "clipper_recovery_pending_consumed" -> "clipper_recovery_pending_consumed"
                "clipper_recovery_suppressed" -> "clipper_recovery_suppressed"
                "clipper_failure" -> "clipper_failure source=${obj.optString("source")} autoRecovery=${obj.optString("autoRecoveryAttempted")}"
                "hard_crash_during_slice" -> "hard_crash_during_slice"
                "manual_restart_requested" -> "manual_restart_requested"
                "manual_reset_app_state" -> "manual_reset_app_state files=${obj.optInt("filesCleared")} cache=${obj.optInt("cacheCleared")} total=${obj.optInt("totalCleared")}"
                "upgrade_slice_skip_clear_model" -> "upgrade_slice_skip_clear_model reason=${obj.optString("reason")}"
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
