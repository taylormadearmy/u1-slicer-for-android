package com.u1.slicer.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SliceJobDaoTest {

    private lateinit var database: AppDatabase
    private lateinit var dao: SliceJobDao

    @Before
    fun setup() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
        dao = database.sliceJobDao()
    }

    @After
    fun teardown() {
        database.close()
    }

    private fun testJob(
        modelName: String = "test.stl",
        timestamp: Long = System.currentTimeMillis()
    ) = SliceJob(
        modelName = modelName,
        gcodePath = "/data/test.gcode",
        totalLayers = 100,
        estimatedTimeSeconds = 1800f,
        estimatedFilamentMm = 3000f,
        layerHeight = 0.2f,
        fillDensity = 0.15f,
        nozzleTemp = 210,
        bedTemp = 60,
        supportEnabled = false,
        filamentType = "PLA",
        timestamp = timestamp
    )

    @Test
    fun insertAndRetrieve() = runTest {
        val id = dao.insert(testJob())
        assertTrue(id > 0)

        val all = dao.getAll().first()
        assertEquals(1, all.size)
        assertEquals("test.stl", all[0].modelName)
    }

    @Test
    fun getAllOrderedByTimestampDesc() = runTest {
        dao.insert(testJob(modelName = "old.stl", timestamp = 1000))
        dao.insert(testJob(modelName = "new.stl", timestamp = 3000))
        dao.insert(testJob(modelName = "mid.stl", timestamp = 2000))

        val all = dao.getAll().first()
        assertEquals("new.stl", all[0].modelName)
        assertEquals("mid.stl", all[1].modelName)
        assertEquals("old.stl", all[2].modelName)
    }

    @Test
    fun deleteJob() = runTest {
        val id = dao.insert(testJob())
        val all = dao.getAll().first()
        assertEquals(1, all.size)

        dao.delete(all[0])
        val afterDelete = dao.getAll().first()
        assertEquals(0, afterDelete.size)
    }

    @Test
    fun deleteAllJobs() = runTest {
        dao.insert(testJob(modelName = "a.stl"))
        dao.insert(testJob(modelName = "b.stl"))
        dao.insert(testJob(modelName = "c.stl"))

        assertEquals(3, dao.getAll().first().size)

        dao.deleteAll()
        assertEquals(0, dao.getAll().first().size)
    }

    @Test
    fun jobPreservesAllFields() = runTest {
        val id = dao.insert(testJob())
        val job = dao.getAll().first()[0]

        assertEquals("test.stl", job.modelName)
        assertEquals("/data/test.gcode", job.gcodePath)
        assertNull(job.sourcePath)
        assertEquals(100, job.totalLayers)
        assertEquals(1800f, job.estimatedTimeSeconds, 0.001f)
        assertEquals(3000f, job.estimatedFilamentMm, 0.001f)
        assertEquals(0.2f, job.layerHeight, 0.001f)
        assertEquals(0.15f, job.fillDensity, 0.001f)
        assertEquals(210, job.nozzleTemp)
        assertEquals(60, job.bedTemp)
        assertFalse(job.supportEnabled)
        assertEquals("PLA", job.filamentType)
    }

    @Test
    fun sourcePathNullByDefault() = runTest {
        val id = dao.insert(testJob())
        val job = dao.getAll().first()[0]
        assertNull(job.sourcePath)
    }

    @Test
    fun sourcePathRoundTrip() = runTest {
        val jobWithSource = testJob().copy(sourcePath = "/data/user/0/com.u1.slicer.orca/files/jobs/1/model.3mf")
        dao.insert(jobWithSource)
        val job = dao.getAll().first()[0]
        assertEquals("/data/user/0/com.u1.slicer.orca/files/jobs/1/model.3mf", job.sourcePath)
    }

    @Test
    fun updateSourcePath() = runTest {
        val id = dao.insert(testJob())
        assertNull(dao.getAll().first()[0].sourcePath)

        dao.updateSourcePath(id, "/data/jobs/1/model.3mf")
        assertEquals("/data/jobs/1/model.3mf", dao.getAll().first()[0].sourcePath)
    }

    @Test
    fun updateGcodePath_persistsNewPath() = runTest {
        val id = dao.insert(testJob())

        dao.updateGcodePath(id, "/new/path.gcode")

        val job = dao.getAll().first()[0]
        assertEquals("/new/path.gcode", job.gcodePath)
    }
}
