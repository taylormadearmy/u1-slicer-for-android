package com.u1.slicer.printer

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.u1.slicer.data.Printer
import com.u1.slicer.data.PrintersConfig
import com.u1.slicer.data.PrintersRepository
import com.u1.slicer.network.MoonrakerClient
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * F78 regression guards for [PrinterRepository] rebind behaviour.
 *
 * When [PrintersRepository] emits a new active printer, [PrinterRepository]:
 *   1. Updates [MoonrakerClient.baseUrl] to the new URL.
 *   2. Resets status state to "disconnected".
 *   3. Updates [PrinterRepository.printerUrl] to the new URL.
 *
 * Also tests [PrinterRepository.updateActiveUrl] and the end-to-end switch-active
 * chain through [PrintersRepository.setActive].
 *
 * No HTTP requests are made — the URLs are unreachable addresses used only as
 * sentinels to verify binding.
 *
 * Each test seeds via [PrintersRepository.replace] for deterministic, order-independent runs.
 */
@RunWith(AndroidJUnit4::class)
class PrinterRepositoryRebindTest {

    private val targetContext get() = InstrumentationRegistry.getInstrumentation().targetContext

    private lateinit var client: MoonrakerClient
    private lateinit var printersRepo: PrintersRepository
    private lateinit var printerRepo: PrinterRepository

    private val printerA = Printer(
        id = "rebind-a",
        nickname = "Rebind A",
        moonrakerUrl = "http://rebind-a.local",
    )
    private val printerB = Printer(
        id = "rebind-b",
        nickname = "Rebind B",
        moonrakerUrl = "http://rebind-b.local",
    )

    @Before
    fun setUp() {
        runBlocking {
            // Use fresh instances for each test so we're testing the real wiring without
            // sharing state with the app's AppContainer. A fresh PrintersRepository
            // pointing at the same DataStore context is sufficient — the collect loop
            // in PrinterRepository.init picks up emissions from any writer to that DataStore.
            val ctx = targetContext.applicationContext
            client = MoonrakerClient()
            printersRepo = PrintersRepository(ctx)
            printerRepo = PrinterRepository(ctx, client, printersRepo)

            // Seed a deterministic initial state with printer A active.
            printersRepo.replace(
                PrintersConfig(printers = listOf(printerA, printerB), activeId = "rebind-a")
            )
            printersRepo.config.first { it?.activeId == "rebind-a" }

            // Wait for the init collector to fire and set the initial baseUrl.
            waitForBaseUrl(MoonrakerClient.normalizeUrl(printerA.moonrakerUrl))
        }
    }

    @After
    fun tearDown() {
        runBlocking {
            // Leave the DataStore in a known single-entry state so other tests are not affected.
            printersRepo.replace(
                PrintersConfig(
                    printers = listOf(Printer(id = "cleanup", nickname = "Cleanup", moonrakerUrl = "")),
                    activeId = "cleanup",
                )
            )
        }
    }

    // ---- Gap 2: switching the active printer rebinds the client ----

    @Test
    fun setActive_rebindsClientBaseUrl() = runBlocking {
        val urlA = MoonrakerClient.normalizeUrl(printerA.moonrakerUrl)
        val urlB = MoonrakerClient.normalizeUrl(printerB.moonrakerUrl)

        // Initial state: A is active.
        assertEquals(
            "Before switch client.baseUrl should be printer A's URL",
            urlA,
            client.baseUrl,
        )
        assertEquals(
            "Before switch printerUrl StateFlow should be printer A's URL",
            urlA,
            printerRepo.printerUrl.value,
        )

        // Switch to B.
        printersRepo.setActive("rebind-b")

        // Wait for the collect loop in PrinterRepository.init to process the emission.
        waitForBaseUrl(urlB)

        assertEquals(
            "F78: after setActive(B) client.baseUrl must be printer B's URL",
            urlB,
            client.baseUrl,
        )
        assertEquals(
            "F78: after setActive(B) printerUrl StateFlow must reflect printer B's URL",
            urlB,
            printerRepo.printerUrl.value,
        )
        assertEquals(
            "F78: after rebind status must be reset to disconnected",
            "disconnected",
            printerRepo.status.value.state,
        )
    }

    // ---- Gap 3: updateActiveUrl persists to PrintersRepository and triggers rebind ----

    @Test
    fun updateActiveUrl_persistsToRepository_andTriggersRebind() = runBlocking {
        val newUrl = "http://updated.local"
        val normalizedNew = MoonrakerClient.normalizeUrl(newUrl)

        printerRepo.updateActiveUrl(newUrl)

        // Wait for rebind (the config collector fires because PrintersRepository emits a new value).
        waitForBaseUrl(normalizedNew)

        // Verify the DataStore was updated.
        val persistedUrl = printersRepo.config.first()?.active?.moonrakerUrl
        assertEquals(
            "F78: updateActiveUrl must persist the new URL to PrintersRepository",
            normalizedNew,
            persistedUrl,
        )

        // Verify the client is bound to the new URL.
        assertEquals(
            "F78: updateActiveUrl must trigger a rebind so client.baseUrl reflects the new URL",
            normalizedNew,
            client.baseUrl,
        )
    }

    // ---- Gap 5: full switch-active end-to-end chain ----

    @Test
    fun switchActivePrinter_chainPropagatesToMoonrakerBaseUrl() = runBlocking {
        val urlA = MoonrakerClient.normalizeUrl(printerA.moonrakerUrl)
        val urlB = MoonrakerClient.normalizeUrl(printerB.moonrakerUrl)

        // Ensure initial rebind has happened.
        waitForBaseUrl(urlA)

        // Trigger the switch via PrintersRepository (the same call path as PrinterViewModel.switchActivePrinter).
        printersRepo.setActive("rebind-b")

        // Verify the full chain propagates: PrintersRepository → PrinterRepository.init collect → rebind → client.baseUrl.
        waitForBaseUrl(urlB)

        assertEquals(
            "F78 end-to-end: after setActive(B) MoonrakerClient.baseUrl must be B's URL",
            urlB,
            client.baseUrl,
        )
        assertEquals(
            "F78 end-to-end: printerUrl StateFlow must reflect B's URL",
            urlB,
            printerRepo.printerUrl.value,
        )

        // Switch back to A to confirm the chain works in both directions.
        printersRepo.setActive("rebind-a")
        waitForBaseUrl(urlA)

        assertEquals(
            "F78 end-to-end: after switching back to A, MoonrakerClient.baseUrl must be A's URL",
            urlA,
            client.baseUrl,
        )
    }

    // ---- helpers ----

    private fun waitForBaseUrl(expected: String, timeoutMs: Long = 30_000L) {
        // 2026-05-31: bumped from 4s → 30s after a sustained-load sweep regression
        // (the DataStore-write → PrintersRepository.config emission → init
        // collect loop → MoonrakerClient.setBaseUrl chain can take >4s under
        // device-thermal/coroutine pressure even though each hop is microseconds
        // in isolation). Passes in isolation, failed twice in 2h+ sharded sweeps.
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (client.baseUrl == expected) return
            Thread.sleep(50)
        }
        throw AssertionError(
            "Timed out after ${timeoutMs}ms waiting for client.baseUrl == \"$expected\". " +
                "Actual: \"${client.baseUrl}\""
        )
    }
}
