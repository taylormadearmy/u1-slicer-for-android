package com.u1.slicer

import java.io.File

/**
 * Pure-logic class for detecting APK upgrades and determining which cached files to clear.
 * Extracted from MainActivity to enable unit testing.
 */
class UpgradeDetector {

    data class State(
        val lastVersionCode: Int,      // -1 = first install
        val savedApkUpdateTime: Long,  // 0 = first install
    )

    data class Current(
        val versionCode: Int,
        val apkUpdateTime: Long,
    )

    enum class Result {
        /** APK changed (version or reinstall) — must clear all caches and kill process */
        APK_CHANGED,
        /** Same APK — clear transient cache patterns only */
        SAME_APK,
        /** First install — nothing to clear */
        FIRST_INSTALL,
    }

    fun detect(saved: State, current: Current): Result {
        // First install: no saved state to compare against
        if (saved.lastVersionCode == -1 && saved.savedApkUpdateTime == 0L) {
            return Result.FIRST_INSTALL
        }

        val versionChanged = saved.lastVersionCode != -1 && saved.lastVersionCode != current.versionCode
        val apkChanged = saved.savedApkUpdateTime != 0L && saved.savedApkUpdateTime != current.apkUpdateTime

        return if (versionChanged || apkChanged) Result.APK_CHANGED else Result.SAME_APK
    }

    /**
     * Returns the list of files that should be deleted for a full cache clear (APK_CHANGED).
     */
    fun filesToClearOnUpgrade(filesDir: File): List<File> {
        return filesDir.listFiles()?.filter { f ->
            f.name.endsWith(".3mf") || f.name.endsWith(".gcode")
        } ?: emptyList()
    }

    /**
     * Returns the list of transient cache files to clear on normal startup (SAME_APK).
     */
    fun filesToClearOnStartup(filesDir: File): List<File> {
        return filesDir.listFiles()?.filter { f ->
            f.name.startsWith("embedded_") || f.name.startsWith("sanitized_") ||
                (f.name.startsWith("plate") && f.name.endsWith(".3mf"))
        } ?: emptyList()
    }
}
