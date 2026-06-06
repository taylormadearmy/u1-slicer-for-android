package com.u1.slicer

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.u1.slicer.data.MixedFilamentRow
import com.u1.slicer.data.SettingsRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * E2E instrumented test: library mixes persist across SettingsRepository instantiations.
 *
 * Writes rows via one repository instance and reads them back via a fresh instance,
 * proving the DataStore persistence path round-trips correctly (M3 Phase A — Task 11).
 */
@RunWith(AndroidJUnit4::class)
class MixedFilamentLibraryPersistenceE2ETest {

    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Before
    fun setUp() = runBlocking {
        // Clear any rows left by previous test runs so the assertion is deterministic.
        SettingsRepository(ctx).setLibraryMixes(emptyList())
    }

    @After
    fun tearDown() = runBlocking {
        SettingsRepository(ctx).setLibraryMixes(emptyList())
    }

    /**
     * Writing library mixes to DataStore then reading them back via a fresh
     * SettingsRepository instance returns the same list — proves the
     * SettingsRepository persistence path round-trips through actual storage.
     */
    @Test
    fun libraryMixes_persistAcrossRepositoryInstantiations() = runBlocking {
        val rows = listOf(
            MixedFilamentRow(
                id = 1L,
                componentA = 1,
                componentB = 3,
                mixBPercent = 50,
                distributionMode = MixedFilamentRow.MixDistributionMode.LAYER_CYCLE,
                label = "Test",
                inLibrary = true,
            ),
        )

        // Write via one repository instance.
        val writer = SettingsRepository(ctx)
        writer.setLibraryMixes(rows)

        // Read back via a fresh repository instance.
        // Both instances share the same underlying DataStore file (process-singleton),
        // so the second instance observes the write immediately.
        val reader = SettingsRepository(ctx)
        val readBack = reader.libraryMixesFlow.first()

        assertEquals(
            "Library mixes written by one SettingsRepository instance must be readable " +
                "by a fresh instance via libraryMixesFlow.",
            rows,
            readBack,
        )
    }
}
