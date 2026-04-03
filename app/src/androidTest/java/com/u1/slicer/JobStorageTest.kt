package com.u1.slicer

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.u1.slicer.data.SliceJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Regression tests for B40 — durable file cleanup on job deletion.
 */
@RunWith(AndroidJUnit4::class)
class JobStorageTest {

    private val targetContext get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun makeJob(modelName: String = "test.stl"): SliceJob = SliceJob(
        modelName = modelName,
        gcodePath = "/data/test.gcode",
        sourcePath = null,
        totalLayers = 10,
        estimatedTimeSeconds = 600f,
        estimatedFilamentMm = 1000f,
        estimatedFilamentGrams = 0f,
        layerHeight = 0.2f,
        fillDensity = 15f,
        nozzleTemp = 200,
        bedTemp = 60,
        supportEnabled = false,
        filamentType = "PLA"
    )

    // Regression: B40 — deleting a job must remove its durable files/jobs/<id>/ directory
    @Test
    fun deleteJob_removesJobDirectory() {
        val application = targetContext.applicationContext as U1SlicerApplication
        val viewModel = SlicerViewModel(application)
        val dao = application.container.sliceJobDao

        val insertedId: Long = runBlocking { dao.insert(makeJob()) }
        val job = runBlocking { dao.getAll().first() }.first { it.id == insertedId }

        // Create the durable directory that copyGcodeToDurableJobDir() would have created
        val jobDir = File(targetContext.filesDir, "jobs/${job.id}")
        val gcodeFile = File(jobDir, "output.gcode")
        jobDir.mkdirs()
        gcodeFile.createNewFile()

        viewModel.deleteJob(job)
        Thread.sleep(500)

        assertFalse("jobs/${job.id}/ directory should be removed after deleteJob", jobDir.exists())
    }

    // Regression: B40 — deleteAllJobs must remove the entire files/jobs/ directory
    @Test
    fun deleteAllJobs_removesJobsDirectory() {
        val application = targetContext.applicationContext as U1SlicerApplication
        val viewModel = SlicerViewModel(application)
        val dao = application.container.sliceJobDao

        val id1: Long = runBlocking { dao.insert(makeJob(modelName = "a.stl")) }
        val id2: Long = runBlocking { dao.insert(makeJob(modelName = "b.stl")) }

        // Create durable directories for both
        val jobsRoot = File(targetContext.filesDir, "jobs")
        File(jobsRoot, "$id1/output.gcode").also { it.parentFile?.mkdirs(); it.createNewFile() }
        File(jobsRoot, "$id2/output.gcode").also { it.parentFile?.mkdirs(); it.createNewFile() }

        viewModel.deleteAllJobs()
        Thread.sleep(500)

        assertFalse("jobs/ directory should be removed after deleteAllJobs", jobsRoot.exists())
    }
}
