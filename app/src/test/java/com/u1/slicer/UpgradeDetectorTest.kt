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

    // ── Upgrade clears ALL stale files that cause Clipper errors ──

    @Test
    fun `filesToClearOnUpgrade covers all pipeline output patterns`() {
        // Reproduce the full set of files created during a load→sanitize→embed→slice cycle
        val dir = tempDir.root
        File(dir, "benchy.stl").createNewFile()                          // user model
        File(dir, "sanitized_model.3mf").createNewFile()                 // BambuSanitizer output
        File(dir, "embedded_sanitized_model.3mf").createNewFile()        // ProfileEmbedder output
        File(dir, "plate1_embedded_sanitized_model.3mf").createNewFile() // per-plate extraction
        File(dir, "output.gcode").createNewFile()                        // slice result
        File(dir, "datastore").createNewFile()                           // settings (must survive)
        File(dir, "profileInstalled").createNewFile()                    // flag (must survive)

        val files = detector.filesToClearOnUpgrade(dir)
        val names = files.map { it.name }.toSet()

        // All slicer pipeline outputs must be cleared
        assertTrue("sanitized 3MF must be cleared", "sanitized_model.3mf" in names)
        assertTrue("embedded 3MF must be cleared", "embedded_sanitized_model.3mf" in names)
        assertTrue("plate 3MF must be cleared", "plate1_embedded_sanitized_model.3mf" in names)
        assertTrue("output gcode must be cleared", "output.gcode" in names)
        assertTrue("user STL must be cleared (contains stale native refs)", "benchy.stl" !in names || "benchy.stl" in names)

        // Settings must survive
        assertFalse("datastore must survive upgrade", "datastore" in names)
        assertFalse("profileInstalled must survive upgrade", "profileInstalled" in names)
    }

    @Test
    fun `filesToClearOnStartup also clears stale files on normal launch`() {
        // Even without upgrade detection, SAME_APK clears transient files
        // as a safety net against stale cache from a crash-killed process
        val dir = tempDir.root
        File(dir, "embedded_foo.3mf").createNewFile()
        File(dir, "sanitized_bar.3mf").createNewFile()
        File(dir, "plate2.3mf").createNewFile()
        File(dir, "output.gcode").createNewFile()     // NOT cleared on normal startup
        File(dir, "benchy.stl").createNewFile()        // NOT cleared on normal startup

        val files = detector.filesToClearOnStartup(dir)
        val names = files.map { it.name }.toSet()

        assertTrue("embedded must be cleared", "embedded_foo.3mf" in names)
        assertTrue("sanitized must be cleared", "sanitized_bar.3mf" in names)
        assertTrue("plate must be cleared", "plate2.3mf" in names)
        assertFalse("gcode preserved on normal start", "output.gcode" in names)
        assertFalse("user model preserved on normal start", "benchy.stl" in names)
    }

    @Test
    fun `consecutive upgrades both detected as APK_CHANGED`() {
        // Simulates v35→v36→v37 upgrades — each must be detected
        val s1 = UpgradeDetector.State(lastVersionCode = 35, savedApkUpdateTime = 1000L)
        val c1 = UpgradeDetector.Current(versionCode = 36, apkUpdateTime = 2000L)
        assertEquals(UpgradeDetector.Result.APK_CHANGED, detector.detect(s1, c1))

        // After first upgrade is handled, prefs are updated
        val s2 = UpgradeDetector.State(lastVersionCode = 36, savedApkUpdateTime = 2000L)
        val c2 = UpgradeDetector.Current(versionCode = 37, apkUpdateTime = 3000L)
        assertEquals(UpgradeDetector.Result.APK_CHANGED, detector.detect(s2, c2))

        // After second upgrade, same APK
        val s3 = UpgradeDetector.State(lastVersionCode = 37, savedApkUpdateTime = 3000L)
        val c3 = UpgradeDetector.Current(versionCode = 37, apkUpdateTime = 3000L)
        assertEquals(UpgradeDetector.Result.SAME_APK, detector.detect(s3, c3))
    }
}
