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
import org.junit.Assert.assertNotSame
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList

/** Regression coverage for active-printer rebinding and Moonraker client isolation. */
@RunWith(AndroidJUnit4::class)
class PrinterRepositoryRebindTest {

    private val targetContext get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val clients = CopyOnWriteArrayList<MoonrakerClient>()
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
            val context = targetContext.applicationContext
            printersRepo = PrintersRepository(context)
            printerRepo = PrinterRepository(
                context,
                printersRepo,
                DefaultPrinterTransportFactory(
                    moonrakerClientFactory = { MoonrakerClient().also(clients::add) },
                ),
            )
            printersRepo.replace(
                PrintersConfig(printers = listOf(printerA, printerB), activeId = printerA.id),
            )
            printersRepo.config.first { it?.activeId == printerA.id }
            waitForClient(urlFor(printerA))
        }
    }

    @After
    fun tearDown() {
        runBlocking {
            printersRepo.replace(
                PrintersConfig(
                    printers = listOf(Printer(id = "cleanup", nickname = "Cleanup", moonrakerUrl = "")),
                    activeId = "cleanup",
                ),
            )
        }
    }

    @Test
    fun setActive_rebindsToAnIndependentClient() = runBlocking {
        val clientA = waitForClient(urlFor(printerA))

        printersRepo.setActive(printerB.id)

        val clientB = waitForClient(urlFor(printerB))
        assertNotSame(clientA, clientB)
        assertEquals(urlFor(printerA), clientA.baseUrl)
        assertEquals(urlFor(printerB), clientB.baseUrl)
        assertEquals(urlFor(printerB), printerRepo.printerUrl.value)
        assertEquals("disconnected", printerRepo.status.value.state)
    }

    @Test
    fun updateActiveUrl_persistsAndCreatesAnIndependentClient() = runBlocking {
        val initialClient = waitForClient(urlFor(printerA))
        val updatedUrl = MoonrakerClient.normalizeUrl("http://updated.local")

        printerRepo.updateActiveUrl(updatedUrl)

        val updatedClient = waitForClient(updatedUrl, excluding = setOf(initialClient))
        assertNotSame(initialClient, updatedClient)
        assertEquals(urlFor(printerA), initialClient.baseUrl)
        assertEquals(updatedUrl, updatedClient.baseUrl)
        assertEquals(updatedUrl, printersRepo.config.first()?.active?.moonrakerUrl)
    }

    @Test
    fun switchingBack_createsAnotherIndependentClient() = runBlocking {
        val firstClientA = waitForClient(urlFor(printerA))
        printersRepo.setActive(printerB.id)
        val clientB = waitForClient(urlFor(printerB))
        printersRepo.setActive(printerA.id)
        val secondClientA = waitForClient(urlFor(printerA), excluding = setOf(firstClientA, clientB))

        assertNotSame(firstClientA, clientB)
        assertNotSame(firstClientA, secondClientA)
        assertEquals(urlFor(printerA), firstClientA.baseUrl)
        assertEquals(urlFor(printerB), clientB.baseUrl)
        assertEquals(urlFor(printerA), secondClientA.baseUrl)
    }

    private fun urlFor(printer: Printer): String = MoonrakerClient.normalizeUrl(printer.moonrakerUrl)

    private fun waitForClient(
        expectedUrl: String,
        excluding: Set<MoonrakerClient> = emptySet(),
        timeoutMs: Long = 30_000L,
    ): MoonrakerClient {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            clients.firstOrNull { it !in excluding && it.baseUrl == expectedUrl }?.let { return it }
            Thread.sleep(50)
        }
        throw AssertionError(
            "Timed out after ${timeoutMs}ms waiting for a client bound to $expectedUrl. " +
                "Actual: ${clients.map { it.baseUrl }}",
        )
    }
}
