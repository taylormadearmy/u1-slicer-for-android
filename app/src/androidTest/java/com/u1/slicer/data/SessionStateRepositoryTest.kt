package com.u1.slicer.data

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SessionStateRepositoryTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val repo = SessionStateRepository(context)

    @Before
    fun setUp() = runBlocking { repo.clear() }

    @After
    fun tearDown() = runBlocking { repo.clear() }

    private fun sample() = SessionState(
        modelName = "test.3mf",
        rawInputPath = "/cache/test.3mf",
        sourceModelPath = null,
        currentModelPath = null,
        multiPlateSourcePath = null,
        selectedPlateId = 3,
        modelScale = Triple(1f, 1f, 1f),
        modelRotation = Triple(0f, 0f, 0f),
        copyCount = 1,
        customObjectPositions = floatArrayOf(135f, 135f),
        customWipeTowerPos = 170f to 140f,
        additionalFiles = emptyList(),
        sliceJobId = null,
        wasSliceComplete = false,
        savedAtEpochMs = 1716480000000L,
        appVersionCode = 295,
    )

    @Test
    fun write_thenRead_returnsSameSessionState() = runBlocking {
        val src = sample()
        repo.write(src)
        val parsed = repo.state.first()
        assertNotNull(parsed)
        assertEquals(src, parsed)
    }

    @Test
    fun read_emptyStore_returnsNull() = runBlocking {
        val parsed = repo.state.first()
        assertNull(parsed)
    }

    @Test
    fun clear_afterWrite_readReturnsNull() = runBlocking {
        repo.write(sample())
        repo.clear()
        assertNull(repo.state.first())
    }

    @Test
    fun write_overwrites_prior() = runBlocking {
        repo.write(sample())
        val second = sample().copy(modelName = "second.3mf", copyCount = 4)
        repo.write(second)
        val parsed = repo.state.first()
        assertEquals("second.3mf", parsed?.modelName)
        assertEquals(4, parsed?.copyCount)
    }
}
