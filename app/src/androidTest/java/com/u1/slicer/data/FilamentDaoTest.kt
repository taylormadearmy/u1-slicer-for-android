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
class FilamentDaoTest {

    private lateinit var database: AppDatabase
    private lateinit var dao: FilamentDao

    @Before
    fun setup() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
        dao = database.filamentDao()
    }

    @After
    fun teardown() {
        database.close()
    }

    private fun testProfile(
        name: String = "Test PLA",
        material: String = "PLA",
        nozzleTemp: Int = 210,
        bedTemp: Int = 60
    ) = FilamentProfile(
        name = name,
        material = material,
        nozzleTemp = nozzleTemp,
        bedTemp = bedTemp,
        printSpeed = 60f,
        retractLength = 0.8f,
        retractSpeed = 45f
    )

    @Test
    fun insertAndRetrieve() = runTest {
        val profile = testProfile()
        val id = dao.insert(profile)
        assertTrue(id > 0)

        val retrieved = dao.getById(id)
        assertNotNull(retrieved)
        assertEquals("Test PLA", retrieved!!.name)
        assertEquals("PLA", retrieved.material)
        assertEquals(210, retrieved.nozzleTemp)
    }

    @Test
    fun getAllReturnsInsertedProfiles() = runTest {
        dao.insert(testProfile(name = "PLA"))
        dao.insert(testProfile(name = "PETG", material = "PETG", nozzleTemp = 235, bedTemp = 80))

        val all = dao.getAll().first()
        assertEquals(2, all.size)
    }

    @Test
    fun getAllOrderedByName() = runTest {
        dao.insert(testProfile(name = "Zylon"))
        dao.insert(testProfile(name = "Alpha"))
        dao.insert(testProfile(name = "Middle"))

        val all = dao.getAll().first()
        assertEquals("Alpha", all[0].name)
        assertEquals("Middle", all[1].name)
        assertEquals("Zylon", all[2].name)
    }

    @Test
    fun updateProfile() = runTest {
        val id = dao.insert(testProfile())
        val profile = dao.getById(id)!!

        dao.update(profile.copy(nozzleTemp = 220))
        val updated = dao.getById(id)!!
        assertEquals(220, updated.nozzleTemp)
    }

    @Test
    fun deleteProfile() = runTest {
        val id = dao.insert(testProfile())
        val profile = dao.getById(id)!!

        dao.delete(profile)
        val deleted = dao.getById(id)
        assertNull(deleted)
    }

    @Test
    fun countReturnsCorrectNumber() = runTest {
        assertEquals(0, dao.count())
        dao.insert(testProfile(name = "A"))
        dao.insert(testProfile(name = "B"))
        assertEquals(2, dao.count())
    }

    @Test
    fun getByIdReturnsNullForNonexistent() = runTest {
        val result = dao.getById(999)
        assertNull(result)
    }

    @Test
    fun clearAllDefaults_clearsIsDefaultFlag() = runTest {
        val id1 = dao.insert(testProfile(name = "A").copy(isDefault = true))
        val id2 = dao.insert(testProfile(name = "B").copy(isDefault = true))

        dao.clearAllDefaults()

        assertFalse("Profile A should no longer be default", dao.getById(id1)!!.isDefault)
        assertFalse("Profile B should no longer be default", dao.getById(id2)!!.isDefault)
    }

    @Test
    fun setDefault_onlyOneProfileIsDefault() = runTest {
        val id1 = dao.insert(testProfile(name = "A").copy(isDefault = true))
        val id2 = dao.insert(testProfile(name = "B"))

        // Simulate setDefaultFilament: clear all, then mark one
        dao.clearAllDefaults()
        dao.update(dao.getById(id2)!!.copy(isDefault = true))

        assertFalse("Profile A should no longer be default", dao.getById(id1)!!.isDefault)
        assertTrue("Profile B should now be default", dao.getById(id2)!!.isDefault)
    }
}
