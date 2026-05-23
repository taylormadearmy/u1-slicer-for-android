package com.u1.slicer.printer

import androidx.test.platform.app.InstrumentationRegistry
import com.u1.slicer.data.Printer
import com.u1.slicer.data.PrintersConfig
import com.u1.slicer.data.PrintersRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test

/**
 * Instrumented integration tests for [PrintersRepository].
 *
 * MockWebServer is used only to generate stable, valid URLs — no HTTP requests are
 * actually exchanged. Each test seeds the DataStore via [PrintersRepository.replace]
 * so there are no order-dependencies on pre-existing device state.
 *
 * The @Before seeds a known 1-printer state so DataStore is always initialised
 * before any assertion, preventing stale-migration-state reads.
 */
class MultiPrinterIntegrationTest {

    private lateinit var serverA: MockWebServer
    private lateinit var serverB: MockWebServer
    private lateinit var repo: PrintersRepository

    private val seed = Printer(id = "seed", nickname = "Seed", moonrakerUrl = "http://seed")
    private val seedCfg = PrintersConfig(printers = listOf(seed), activeId = "seed")

    @Before fun setUp() {
        serverA = MockWebServer().apply { start() }
        serverB = MockWebServer().apply { start() }
        repo = PrintersRepository(InstrumentationRegistry.getInstrumentation().targetContext)
        // Write a known state so every test starts from a clean, deterministic base.
        runBlocking {
            repo.replace(seedCfg)
            // Wait for the write to propagate before any test body runs.
            repo.config.first { it?.activeId == "seed" }
        }
    }

    @After fun tearDown() {
        serverA.shutdown()
        serverB.shutdown()
    }

    @Test fun addPrinter_updatesPrinterListStateFlow() = runBlocking {
        val p1 = Printer(id = "a", nickname = "P1", moonrakerUrl = serverA.url("/").toString())
        val p2 = Printer(id = "b", nickname = "P2", moonrakerUrl = serverB.url("/").toString())
        repo.replace(PrintersConfig(printers = listOf(p1), activeId = "a"))
        repo.config.first { it?.activeId == "a" }  // wait for replace to propagate
        repo.add(p2)
        val cfg = repo.config.first { it?.printers?.size == 2 }!!
        assertEquals(2, cfg.printers.size)
        assertEquals("a", cfg.activeId)
    }

    @Test fun setActive_switchesActiveId() = runBlocking {
        val p1 = Printer(id = "a", nickname = "P1", moonrakerUrl = serverA.url("/").toString())
        val p2 = Printer(id = "b", nickname = "P2", moonrakerUrl = serverB.url("/").toString())
        repo.replace(PrintersConfig(printers = listOf(p1, p2), activeId = "a"))
        repo.config.first { it?.activeId == "a" }  // wait for replace to propagate
        repo.setActive("b")
        val activeId = repo.config.first { it?.activeId == "b" }!!.activeId
        assertEquals("b", activeId)
        assertNotEquals("a", activeId)
    }

    @Test fun delete_nonActive_removesAndKeepsActive() = runBlocking {
        val p1 = Printer(id = "a", nickname = "P1", moonrakerUrl = "http://1")
        val p2 = Printer(id = "b", nickname = "P2", moonrakerUrl = "http://2")
        repo.replace(PrintersConfig(printers = listOf(p1, p2), activeId = "a"))
        repo.config.first { it?.activeId == "a" }  // wait for replace to propagate
        repo.delete("b")
        val cfg = repo.config.first { it?.printers?.size == 1 }!!
        assertEquals(1, cfg.printers.size)
        assertEquals("a", cfg.activeId)
    }
}
