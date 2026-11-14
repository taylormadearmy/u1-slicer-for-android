package com.u1.slicer.migration

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.u1.slicer.RequiresExternalFixture
import com.u1.slicer.data.AppDatabase
import com.u1.slicer.data.PrintersConfig
import com.u1.slicer.data.PrintersRepository
import com.u1.slicer.data.SliceJob
import com.u1.slicer.slice.GenericMachineProfile
import com.u1.slicer.slice.isCompatibleWith
import com.u1.slicer.slice.sliceArtifactForJob
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Runs across the actual published 405 APK and the reconciled APK, without network access. */
@RunWith(AndroidJUnit4::class)
@RequiresExternalFixture("scripts/run-v405-upgrade-soak.ps1")
class V405UpgradeMigrationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext.applicationContext

    private fun expected() = PrintersConfig.fromJson(
        instrumentation.context.assets.open("migration/v405_printers_config.json").bufferedReader().use { it.readText() },
    )

    @Test fun seedPublished405DataAndGenericJob() = runBlocking {
        val config = expected()
        PrintersRepository(context).replace(config)
        assertEquals(config, PrintersRepository(context).config.first())
        val profile = requireNotNull(config.active.machineProfile)
        val jobDir = File(context.filesDir, "jobs/v405-upgrade").apply { mkdirs() }
        val gcode = File(jobDir, "published.gcode").apply { writeText("G28\nG1 X10 Y10 E1\nM84\n") }
        val dao = AppDatabase.getInstance(context).sliceJobDao()
        dao.deleteAll()
        dao.insert(SliceJob(modelName = "Published 405 job", gcodePath = gcode.absolutePath,
            totalLayers = 1, estimatedTimeSeconds = 1f, estimatedFilamentMm = 1f,
            layerHeight = 0.2f, fillDensity = 15f, nozzleTemp = 240, bedTemp = 80,
            supportEnabled = false, filamentType = "PETG", sliceTarget = "GenericKlipper",
            machineProfileFingerprint = profile.fingerprint))
        assertEquals(1, dao.getAll().first().size)
    }

    @Test fun verifyPublished405PrintersAndSchema9JobAfterUpgrade() = runBlocking {
        val expected = expected()
        val actual = requireNotNull(PrintersRepository(context).config.first())
        assertEquals(expected, actual)
        assertEquals(listOf("405-u1", "405-bambu", "405-cc1", "405-octo", "405-klipper"), actual.printers.map { it.id })
        assertEquals("405-klipper", actual.activeId)
        val profile = requireNotNull(actual.active.machineProfile)
        assertEquals(requireNotNull(expected.active.machineProfile).fingerprint, profile.fingerprint)
        assertEquals(300f, profile.bedSizeX)
        assertEquals(280f, profile.bedSizeY)
        assertEquals(320f, profile.maxPrintHeight)
        assertEquals("published-fixture-key", actual.printers.single { it.id == "405-octo" }.host?.apiKey)
        val database = AppDatabase.getInstance(context)
        assertEquals(9, database.openHelper.readableDatabase.version)
        val job = database.sliceJobDao().getAll().first().single()
        assertEquals("GenericKlipper", job.sliceTarget)
        assertEquals(profile.fingerprint, job.machineProfileFingerprint)
        assertTrue(File(job.gcodePath).isFile)
        val artifact = sliceArtifactForJob(job)
        assertTrue(artifact.isCompatibleWith(actual.active))
        assertTrue(artifact.isCompatibleWith(actual.printers.single { it.id == "405-octo" }))
        assertFalse(artifact.isCompatibleWith(actual.printers.single { it.id == "405-u1" }))
        assertFalse(artifact.isCompatibleWith(actual.printers.single { it.id == "405-cc1" }))
        val changed = GenericMachineProfile.fromJson(profile.resolvedJson.replace("320", "321"))
        assertFalse(artifact.isCompatibleWith(actual.active.copy(machineProfile = changed)))
    }
}
