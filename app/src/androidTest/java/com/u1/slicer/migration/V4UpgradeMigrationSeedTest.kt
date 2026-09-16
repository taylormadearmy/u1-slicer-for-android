package com.u1.slicer.migration

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.u1.slicer.data.PrintersConfig
import com.u1.slicer.data.PrintersRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** Seeds the released v4 persistence shape before an in-place candidate install. */
@RunWith(AndroidJUnit4::class)
class V4UpgradeMigrationSeedTest {
    @Test
    fun seedReleasedV4PrintersConfig() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val fixture = instrumentation.context.assets
            .open("migration/v4_printers_config.json")
            .bufferedReader()
            .use { it.readText() }
        val expected = PrintersConfig.fromJson(fixture)
        val repository = PrintersRepository(instrumentation.targetContext.applicationContext)
        repository.replace(expected)
        assertEquals(expected, repository.config.first())
    }
}
