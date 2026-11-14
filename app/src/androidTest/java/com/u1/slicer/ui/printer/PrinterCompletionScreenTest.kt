package com.u1.slicer.ui.printer

import android.text.format.DateFormat
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.u1.slicer.U1SlicerApplication
import com.u1.slicer.data.Printer
import com.u1.slicer.data.PrintersConfig
import com.u1.slicer.network.PrinterStatus
import com.u1.slicer.printer.PrinterViewModel
import com.u1.slicer.ui.PrinterScreen
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PrinterCompletionScreenTest {
    @get:Rule val compose = createComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as U1SlicerApplication
    private val store = ViewModelStore()
    private val reported = AtomicReference(PrinterStatus("printing", 0.5f, "mock-print.gcode", 3600f))
    private lateinit var server: MockWebServer
    private lateinit var originalPrinters: PrintersConfig
    private lateinit var vm: PrinterViewModel

    @Before fun setUp() = runBlocking {
        originalPrinters = withTimeout(15_000) { app.container.printersRepository.config.first { it != null }!! }
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val status = reported.get()
                    if (request.path?.startsWith("/printer/objects/query?") == true) {
                        if (status.state == "disconnected") return MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)
                        return MockResponse().setBody("""{"result":{"status":{
                            "print_stats":{"state":"${status.state}","filename":"${status.filename}","print_duration":${status.printDuration}},
                            "virtual_sdcard":{"progress":${status.progress}}
                        }}}""")
                    }
                    return MockResponse().setBody("""{"result":{"webcams":[]}}""")
                }
            }
            start()
        }
        val printer = Printer("completion-mock", "Completion mock", moonrakerUrl = server.url("/").toString())
        app.container.printersRepository.replace(PrintersConfig(listOf(printer), printer.id))
        withTimeout(15_000) { app.container.printerRepository.printerUrl.first { it.trimEnd('/') == printer.moonrakerUrl.trimEnd('/') } }
        instrumentation.runOnMainSync {
            vm = PrinterViewModel(app)
            store.put("printer", vm)
        }
    }

    @After fun tearDown() = runBlocking {
        instrumentation.runOnMainSync { store.clear() }
        if (::originalPrinters.isInitialized) app.container.printersRepository.replace(originalPrinters)
        if (::server.isInitialized) server.shutdown()
    }

    private fun showScreen(largeText: Boolean = false) {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, if (largeText) 2f else density.fontScale)) {
                MaterialTheme {
                    Box(Modifier.width(320.dp)) {
                        PrinterScreen(vm, bedSizeXmm = 270f, bedSizeYmm = 270f)
                    }
                }
            }
        }
    }

    private fun report(status: PrinterStatus) {
        reported.set(status)
        compose.waitUntil(15_000) {
            val actual = vm.status.value
            if (status.state == "disconnected") actual.state == "disconnected"
            else actual.state == status.state && actual.printDuration == status.printDuration && actual.progress == status.progress
        }
        compose.waitForIdle()
    }

    private fun assertDisplayedEstimate(status: PrinterStatus) {
        compose.onNodeWithText("Estimated finish").performScrollTo().assertIsDisplayed()
        val now = Instant.now()
        val locale = instrumentation.targetContext.resources.configuration.locales[0]
        val expected = (-1L..1L).map { minuteOffset ->
            formatPrintCompletion(estimatePrintCompletion(status, now.plusSeconds(minuteOffset * 60))!!,
                now, ZoneId.systemDefault(), DateFormat.is24HourFormat(instrumentation.targetContext), locale)
        }
        val value = compose.onNode(expected.map { hasText(it) }.reduce { first, next -> first or next })
        value.performScrollTo().assertIsDisplayed()
        val labelBounds = compose.onNodeWithText("Estimated finish").fetchSemanticsNode().boundsInRoot
        val valueBounds = value.fetchSemanticsNode().boundsInRoot
        val rootBounds = compose.onRoot().fetchSemanticsNode().boundsInRoot
        assertTrue("Label and time overlap", labelBounds.right <= valueBounds.left)
        assertTrue("Time exceeds screen width", valueBounds.right <= rootBounds.right)
    }

    @Test fun polledStatusUpdatesFinishTimeAndRemovesItWhenInactive() {
        showScreen()
        report(reported.get())
        assertDisplayedEstimate(reported.get())
        val faster = reported.get().copy(progress = 0.75f, printDuration = 1800f)
        report(faster)
        assertDisplayedEstimate(faster)
        report(faster.copy(state = "paused"))
        compose.onNodeWithText("Estimated finish").assertDoesNotExist()
        report(faster)
        assertDisplayedEstimate(faster)
        report(faster.copy(state = "complete", progress = 1f))
        compose.onNodeWithText("Estimated finish").assertDoesNotExist()
        report(faster)
        assertDisplayedEstimate(faster)
        report(faster.copy(state = "disconnected"))
        compose.onNodeWithText("Estimated finish").assertDoesNotExist()
    }

    @Test fun overnightAndMultidayLabelsFitNarrowScreenWithLargeText() {
        showScreen(largeText = true)
        val overnight = reported.get().copy(printDuration = 86400f)
        report(overnight)
        assertDisplayedEstimate(overnight)
        val multiday = overnight.copy(printDuration = 259200f)
        report(multiday)
        assertDisplayedEstimate(multiday)
    }
}
