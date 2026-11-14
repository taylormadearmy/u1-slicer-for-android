package com.u1.slicer

import androidx.test.platform.app.InstrumentationRegistry
import com.u1.slicer.data.Printer
import com.u1.slicer.data.PrintersConfig
import com.u1.slicer.data.PrintersRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.rules.ExternalResource

/** Legacy U1 scenarios must not inherit the phone's selected generic printer. */
class OfflineU1PrinterRule : ExternalResource() {
    private lateinit var repository: PrintersRepository
    private var previous: PrintersConfig? = null

    override fun before() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as U1SlicerApplication
        repository = app.container.printersRepository
        repository.runMigrationIfNeeded(app.container.settingsRepository)
        previous = repository.config.first()
        val printer = Printer(id = "test-u1", nickname = "Offline test U1", moonrakerUrl = "http://192.0.2.2")
        repository.replace(PrintersConfig(printers = listOf(printer), activeId = printer.id))
    }

    override fun after() = runBlocking {
        previous?.let { repository.replace(it) } ?: repository.clearForTest()
    }
}
