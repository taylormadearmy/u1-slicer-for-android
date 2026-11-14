package com.u1.slicer.migration

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.u1.slicer.RequiresExternalFixture
import com.u1.slicer.data.PrintersConfig
import com.u1.slicer.data.PrintersRepository
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Uses only v4-compatible APIs so it can snapshot state before the downgrade/install soak. */
@RunWith(AndroidJUnit4::class)
@RequiresExternalFixture("scripts/run-v4-upgrade-soak.ps1")
class PriorDeviceStateSnapshotTest {
    @Test
    fun snapshotPrinterConfigAndMachineProfiles() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        val snapshotDir = File(context.filesDir, SNAPSHOT_PATH).apply {
            deleteRecursively()
            assertTrue(mkdirs())
        }
        val config = PrintersRepository(context).config.first()
        File(snapshotDir, CONFIG_FILE).writeText(
            config?.let(PrintersConfig::toJson) ?: NULL_CONFIG
        )

        val profiles = File(context.filesDir, "machine_profiles")
        if (profiles.exists()) {
            assertTrue(profiles.copyRecursively(File(snapshotDir, "machine_profiles"), overwrite = true))
        }
    }

    companion object {
        const val SNAPSHOT_PATH = "diagnostics/phase7-prior-state"
        const val CONFIG_FILE = "printers.json"
        const val NULL_CONFIG = "__NULL__"
    }
}
