package com.u1.slicer

import android.content.Context
import com.u1.slicer.data.AppDatabase
import com.u1.slicer.data.PrintersRepository
import com.u1.slicer.data.SettingsRepository
import com.u1.slicer.network.MoonrakerClient
import com.u1.slicer.printer.PrinterRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class AppContainer(context: Context) {
    val settingsRepository = SettingsRepository(context)
    val moonrakerClient = MoonrakerClient()
    val printersRepository = PrintersRepository(context.applicationContext)
    val printerRepository = PrinterRepository(
        context.applicationContext,
        moonrakerClient,
        settingsRepository,
        printersRepository,
    )

    val database = AppDatabase.getInstance(context)
    val filamentDao = database.filamentDao()
    val sliceJobDao = database.sliceJobDao()

    val aiPaintViewModel by lazy {
        com.u1.slicer.aipaint.AiPaintViewModel(context.applicationContext as android.app.Application)
    }

    init {
        // F78: migration runs once per install — reads legacy DataStore keys
        // into a "Printer 1" entry on first launch of v2.4.0.
        CoroutineScope(Dispatchers.IO).launch {
            printersRepository.runMigrationIfNeeded(settingsRepository)
        }
    }
}
