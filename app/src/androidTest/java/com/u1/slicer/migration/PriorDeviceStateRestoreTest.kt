package com.u1.slicer.migration

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.u1.slicer.data.PrintersConfig
import com.u1.slicer.data.PrintersRepository
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Restores state captured before the v4 downgrade once the candidate checks have passed. */
@RunWith(AndroidJUnit4::class)
class PriorDeviceStateRestoreTest {
    @Test
    fun restorePrinterConfigAndMachineProfiles() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        val snapshotDir = File(context.filesDir, PriorDeviceStateSnapshotTest.SNAPSHOT_PATH)
        val configFile = File(snapshotDir, PriorDeviceStateSnapshotTest.CONFIG_FILE)
        assertTrue("prior-state snapshot is missing", configFile.isFile)

        val repository = PrintersRepository(context)
        val saved = configFile.readText()
        if (saved == PriorDeviceStateSnapshotTest.NULL_CONFIG) {
            repository.clearForTest()
            assertEquals(null, repository.config.first())
        } else {
            val expected = PrintersConfig.fromJson(saved)
            repository.replace(expected)
            assertEquals(expected, repository.config.first())
        }

        val profiles = File(context.filesDir, "machine_profiles")
        profiles.deleteRecursively()
        val savedProfiles = File(snapshotDir, "machine_profiles")
        if (savedProfiles.exists()) {
            assertTrue(savedProfiles.copyRecursively(profiles, overwrite = true))
        }
        snapshotDir.deleteRecursively()
        assertFalse(snapshotDir.exists())
    }
}
