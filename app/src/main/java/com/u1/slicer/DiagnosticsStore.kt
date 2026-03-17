package com.u1.slicer

import android.content.Context
import android.content.pm.PackageManager
import android.os.Process
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class DiagnosticsStore(private val context: Context) {
    data class RestartObservation(
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

    private var sessionStartedWithPendingRestart = prefs.contains(KEY_PENDING_RESTART)
    private var firstModelLoadRecorded = false
    private var firstSliceRecorded = false
    private var firstSliceAfterUpgradeRecorded = false

    private val diagnosticsDir = File(context.filesDir, "diagnostics").apply { mkdirs() }
    private val historyFile = File(diagnosticsDir, "clipper_investigation.jsonl")
    private val bundleFile = File(diagnosticsDir, "clipper_investigation_bundle.txt")

    fun diagnosticsPath(): String = historyFile.absolutePath

    fun sessionStartedAfterPendingRestart(): Boolean = sessionStartedWithPendingRestart

    fun pendingRestartTrigger(): String? {
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
        val firstSliceAfterUpgrade = sessionStartedWithPendingRestart && !firstSliceAfterUpgradeRecorded
        if (firstSliceAfterUpgrade) firstSliceAfterUpgradeRecorded = true
        return firstSliceThisLaunch to firstSliceAfterUpgrade
    }

    fun markUpgradePendingForCurrentSession(trigger: String, nativeStateJson: String?): Boolean {
        val persisted = markUpgradeRestartRequested(trigger, nativeStateJson)
        sessionStartedWithPendingRestart = sessionStartedWithPendingRestart || persisted
        return persisted
    }

    fun markSliceSucceeded() {
        if (!prefs.contains(KEY_PENDING_RESTART)) return
        prefs.edit().remove(KEY_PENDING_RESTART).apply()
        sessionStartedWithPendingRestart = false
        recordEvent("post_upgrade_slice_settled")
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
        obj.put("versionCode", packageInfo.longVersionCode)
        obj.put("apkLastUpdateTime", packageInfo.lastUpdateTime)
        obj.put("buildType", if (BuildConfig.DEBUG) "debug" else "release")
        obj.put("sessionStartedWithPendingRestart", sessionStartedWithPendingRestart)
        details.forEach { (key, value) -> obj.put(key, toJsonValue(value)) }
        historyFile.appendText(obj.toString() + "\n")
        trimHistory()
    }

    fun markUpgradeRestartRequested(trigger: String, nativeStateJson: String?): Boolean {
        val nativeState = nativeStateJson?.let(::parseNativeState)
        val marker = JSONObject()
        marker.put("trigger", trigger)
        marker.put("requestedAtMs", System.currentTimeMillis())
        marker.put("sessionId", sessionId)
        marker.put("pid", Process.myPid())
        marker.put("appVersion", BuildConfig.VERSION_NAME)
        marker.put("versionCode", packageInfo.longVersionCode)
        marker.put("apkLastUpdateTime", packageInfo.lastUpdateTime)
        marker.put("nativeGeneration", nativeState?.optString("nativeGeneration"))
        val persisted = prefs.edit().putString(KEY_PENDING_RESTART, marker.toString()).commit()
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

    fun recordNativeConfigured(nativeStateJson: String?): RestartObservation? {
        recordEvent("native_configured", mapOf("nativeState" to nativeStateJson))
        val pendingJson = prefs.getString(KEY_PENDING_RESTART, null) ?: return null
        val pending = try {
            JSONObject(pendingJson)
        } catch (_: Exception) {
            prefs.edit().remove(KEY_PENDING_RESTART).apply()
            return null
        }
        val nativeState = nativeStateJson?.let(::parseNativeState)
        val observation = RestartObservation(
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
            "restart_observed",
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
        val out = buildString {
            appendLine("U1 Slicer Clipper Investigation Bundle")
            appendLine("sessionId=$sessionId")
            appendLine("pid=${Process.myPid()}")
            appendLine("appVersion=${BuildConfig.VERSION_NAME}")
            appendLine("versionCode=${packageInfo.longVersionCode}")
            appendLine("apkLastUpdateTime=${packageInfo.lastUpdateTime}")
            appendLine("buildType=${if (BuildConfig.DEBUG) "debug" else "release"}")
            appendLine("sessionStartedWithPendingRestart=$sessionStartedWithPendingRestart")
            if (!latestError.isNullOrBlank()) appendLine("latestError=$latestError")
            appendLine("pendingRestartMarker=${pendingRestart ?: "<none>"}")
            appendLine()
            appendLine("Recent events:")
            trimToMax(historyLines, MAX_HISTORY_LINES).forEach { appendLine(it) }
        }
        bundleFile.writeText(out)
        return bundleFile
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
