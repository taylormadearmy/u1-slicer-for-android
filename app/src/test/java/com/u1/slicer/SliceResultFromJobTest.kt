package com.u1.slicer

import com.u1.slicer.data.SliceJob
import com.u1.slicer.data.SliceResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies sliceResultFromJob() — the B40 fix that synthesizes a SliceComplete state
 * when a saved G-code is loaded for viewing after kill+reopen.
 */
class SliceResultFromJobTest {

    private val sampleJob = SliceJob(
        id = 7L,
        modelName = "benchy.stl",
        gcodePath = "/data/user/0/com.u1.slicer.orca/files/jobs/7/benchy.gcode",
        sourcePath = null,
        totalLayers = 120,
        estimatedTimeSeconds = 3600f,
        estimatedFilamentMm = 4200f,
        estimatedFilamentGrams = 12.5f,
        layerHeight = 0.2f,
        fillDensity = 15f,
        nozzleTemp = 200,
        bedTemp = 60,
        supportEnabled = false,
        filamentType = "PLA"
    )

    @Test fun `sliceResultFromJob maps gcodePath totalLayers and time`() {
        val result: SliceResult = sliceResultFromJob(sampleJob)
        assertTrue(result.success)
        assertEquals("", result.errorMessage)
        assertEquals(sampleJob.gcodePath, result.gcodePath)
        assertEquals(sampleJob.totalLayers, result.totalLayers)
        assertEquals(sampleJob.estimatedTimeSeconds, result.estimatedTimeSeconds, 0.01f)
        assertEquals(sampleJob.estimatedFilamentMm, result.estimatedFilamentMm, 0.01f)
        assertEquals(sampleJob.estimatedFilamentGrams, result.estimatedFilamentGrams, 0.01f)
    }

    @Test fun `sliceResultFromJob is marked success`() {
        val result = sliceResultFromJob(sampleJob)
        assertTrue(result.success)
    }
}
