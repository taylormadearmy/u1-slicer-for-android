package com.u1.slicer.slice

import com.u1.slicer.data.MachineKind
import com.u1.slicer.data.Printer
import com.u1.slicer.data.PrinterKind
import com.u1.slicer.data.PrintHostConfig
import com.u1.slicer.data.SliceJob
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class JobMachineIdentityTest {
    private fun job(target: String? = null, fingerprint: String? = null) = SliceJob(
        modelName = "Saved model", gcodePath = "/saved.gcode", totalLayers = 5,
        estimatedTimeSeconds = 30f, estimatedFilamentMm = 20f, layerHeight = 0.2f,
        fillDensity = 0.15f, nozzleTemp = 210, bedTemp = 60, supportEnabled = false,
        filamentType = "PLA", sliceTarget = target, machineProfileFingerprint = fingerprint,
    )

    @Test fun legacyJobsRetainU1Identity() {
        val artifact = sliceArtifactForJob(job())
        assertEquals(SlicerTarget.SnapmakerU1, artifact.target)
        assertTrue(artifact.isCompatibleWith(Printer("u1", "U1")))
    }

    @Test fun savedCc1JobCannotBecomeU1OnRestore() {
        val artifact = sliceArtifactForJob(job(SlicerTarget.ElegooCentauriCarbon.name))
        assertFalse(artifact.isCompatibleWith(Printer("u1", "U1")))
        assertTrue(artifact.isCompatibleWith(Printer("cc", "CC1", PrinterKind.ELEGOO_CC1, host = PrintHostConfig("cc.local"))))
    }

    @Test fun malformedOrIncompleteIdentitiesNeverBecomeU1() {
        val u1 = Printer("u1", "U1")
        assertFalse(sliceArtifactForJob(job("future-target")).isCompatibleWith(u1))
        assertFalse(sliceArtifactForJob(job(SlicerTarget.GenericKlipper.name)).isCompatibleWith(u1))
        assertNull(sliceArtifactForJob(job("future-target", "random-digest")).machineProfileFingerprint)
    }

    @Test fun genericHistoryRetainsTheOriginalProfile() {
        val profile = GenericMachineProfile.centauriCarbon()
        val printer = Printer("generic", "Klipper", machine = MachineKind.GENERIC_KLIPPER, machineProfile = profile)
        val artifact = sliceArtifactForJob(job(SlicerTarget.GenericKlipper.name, profile.fingerprint))
        assertEquals(File("/saved.gcode"), artifact.gcodeFile)
        assertTrue(artifact.isCompatibleWith(printer))
        assertFalse(artifact.isCompatibleWith(printer.copy(machineProfile = null)))
    }
}
