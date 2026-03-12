package com.u1.slicer

import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class UpgradeDetectorTest {

    @get:Rule
    val tempDir = TemporaryFolder()

    private lateinit var detector: UpgradeDetector

    @Before
    fun setUp() {
        detector = UpgradeDetector()
    }

    // ── Detection logic ──

    @Test
    fun `first install returns FIRST_INSTALL`() {
        val saved = UpgradeDetector.State(lastVersionCode = -1, savedApkUpdateTime = 0L)
        val current = UpgradeDetector.Current(versionCode = 25, apkUpdateTime = 1000L)
        assertEquals(UpgradeDetector.Result.FIRST_INSTALL, detector.detect(saved, current))
    }

    @Test
    fun `same version and same APK time returns SAME_APK`() {
        val saved = UpgradeDetector.State(lastVersionCode = 25, savedApkUpdateTime = 1000L)
        val current = UpgradeDetector.Current(versionCode = 25, apkUpdateTime = 1000L)
        assertEquals(UpgradeDetector.Result.SAME_APK, detector.detect(saved, current))
    }

    @Test
    fun `version code change returns APK_CHANGED`() {
        val saved = UpgradeDetector.State(lastVersionCode = 24, savedApkUpdateTime = 1000L)
        val current = UpgradeDetector.Current(versionCode = 25, apkUpdateTime = 2000L)
        assertEquals(UpgradeDetector.Result.APK_CHANGED, detector.detect(saved, current))
    }

    @Test
    fun `same version but different APK time returns APK_CHANGED`() {
        // This is the debug reinstall case — same versionCode, new APK binary
        val saved = UpgradeDetector.State(lastVersionCode = 25, savedApkUpdateTime = 1000L)
        val current = UpgradeDetector.Current(versionCode = 25, apkUpdateTime = 2000L)
        assertEquals(UpgradeDetector.Result.APK_CHANGED, detector.detect(saved, current))
    }

    @Test
    fun `version downgrade returns APK_CHANGED`() {
        val saved = UpgradeDetector.State(lastVersionCode = 26, savedApkUpdateTime = 2000L)
        val current = UpgradeDetector.Current(versionCode = 25, apkUpdateTime = 3000L)
        assertEquals(UpgradeDetector.Result.APK_CHANGED, detector.detect(saved, current))
    }

    @Test
    fun `first install with saved version but no APK time returns SAME_APK`() {
        // Edge case: upgraded from a build before APK time tracking was added
        val saved = UpgradeDetector.State(lastVersionCode = 25, savedApkUpdateTime = 0L)
        val current = UpgradeDetector.Current(versionCode = 25, apkUpdateTime = 1000L)
        assertEquals(UpgradeDetector.Result.SAME_APK, detector.detect(saved, current))
    }

    @Test
    fun `version change from pre-APK-time build returns APK_CHANGED`() {
        val saved = UpgradeDetector.State(lastVersionCode = 24, savedApkUpdateTime = 0L)
        val current = UpgradeDetector.Current(versionCode = 25, apkUpdateTime = 1000L)
        assertEquals(UpgradeDetector.Result.APK_CHANGED, detector.detect(saved, current))
    }

    // ── File clearing logic ──

    @Test
    fun `filesToClearOnUpgrade includes 3mf and gcode files`() {
        val dir = tempDir.root
        File(dir, "embedded_model.3mf").createNewFile()
        File(dir, "sanitized_model.3mf").createNewFile()
        File(dir, "output.gcode").createNewFile()
        File(dir, "settings.json").createNewFile()  // should NOT be cleared

        val files = detector.filesToClearOnUpgrade(dir)
        assertEquals(3, files.size)
        assertTrue(files.all { it.name.endsWith(".3mf") || it.name.endsWith(".gcode") })
    }

    @Test
    fun `filesToClearOnUpgrade preserves non-cache files`() {
        val dir = tempDir.root
        File(dir, "settings.json").createNewFile()
        File(dir, "filaments.db").createNewFile()

        val files = detector.filesToClearOnUpgrade(dir)
        assertTrue(files.isEmpty())
    }

    @Test
    fun `filesToClearOnStartup only includes transient patterns`() {
        val dir = tempDir.root
        File(dir, "embedded_model.3mf").createNewFile()
        File(dir, "sanitized_model.3mf").createNewFile()
        File(dir, "plate1.3mf").createNewFile()
        File(dir, "plate2.3mf").createNewFile()
        File(dir, "user_model.3mf").createNewFile()  // should NOT be cleared
        File(dir, "output.gcode").createNewFile()      // should NOT be cleared

        val files = detector.filesToClearOnStartup(dir)
        assertEquals(4, files.size)
        val names = files.map { it.name }.toSet()
        assertTrue("embedded_model.3mf" in names)
        assertTrue("sanitized_model.3mf" in names)
        assertTrue("plate1.3mf" in names)
        assertTrue("plate2.3mf" in names)
    }

    @Test
    fun `filesToClearOnStartup handles empty directory`() {
        val dir = tempDir.root
        val files = detector.filesToClearOnStartup(dir)
        assertTrue(files.isEmpty())
    }

    @Test
    fun `filesToClearOnUpgrade handles empty directory`() {
        val dir = tempDir.root
        val files = detector.filesToClearOnUpgrade(dir)
        assertTrue(files.isEmpty())
    }
}
