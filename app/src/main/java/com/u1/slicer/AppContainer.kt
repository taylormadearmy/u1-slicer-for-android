package com.u1.slicer

import android.content.Context
import com.u1.slicer.data.AppDatabase
import com.u1.slicer.data.FilamentLibraryRepository
import com.u1.slicer.data.PrintersRepository
import com.u1.slicer.data.ProcessProfilesRepository
import com.u1.slicer.data.ProcessProfileParser
import com.u1.slicer.data.SettingsRepository
import com.u1.slicer.machine.MachineProfileRepository
import com.u1.slicer.machine.CachedOrcaProfileLookup
import com.u1.slicer.machine.NativeMachineProfileValidator
import com.u1.slicer.machine.ProfileCache
import com.u1.slicer.machine.VendorCatalogClient
import com.u1.slicer.machine.OrcaProfileBundleImporter
import com.u1.slicer.printer.DefaultPrinterTransportFactory
import com.u1.slicer.printer.BambuDiagnostics
import com.u1.slicer.printer.PrinterRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class AppContainer(context: Context) {
    private val diagnosticsStore = DiagnosticsStore(context.applicationContext)
    init {
        BambuDiagnostics.install { event, details -> diagnosticsStore.recordEvent(event, details) }
    }
    val settingsRepository = SettingsRepository(context)
    val filamentLibraryRepository = FilamentLibraryRepository(context, settingsRepository)
    val bambuLanClientFactory: () -> com.u1.slicer.printer.BambuLanClient = {
        com.u1.slicer.printer.DefaultBambuLanClient(
            com.u1.slicer.printer.PahoBambuMqttSessionFactory()
        )
    }
    private val machineProfilesDir = java.io.File(context.filesDir, "machine_profiles")
    val machineProfileRepository = MachineProfileRepository.fromContext(
        context.applicationContext,
        machineProfilesDir,
    )
    val printersRepository = PrintersRepository(context.applicationContext, machineProfileRepository)
    val vendorCatalogClient = VendorCatalogClient()
    val profileCache = ProfileCache(machineProfilesDir)
    val cachedOrcaProfileLookup = CachedOrcaProfileLookup(profileCache, vendorCatalogClient)
    val machineProfileValidator = NativeMachineProfileValidator(context.applicationContext)
    val orcaProfileBundleImporter = OrcaProfileBundleImporter(
        loadMissing = cachedOrcaProfileLookup::resolveByName,
        validateMachine = machineProfileValidator::validate,
        parseFilaments = { json -> com.u1.slicer.ui.parseFilamentJson(json) },
        parseProcesses = ProcessProfileParser::parse,
    )
    val processProfilesRepository = ProcessProfilesRepository(context.applicationContext)
    val printerTransportFactory = DefaultPrinterTransportFactory(
        bambuClientFactory = bambuLanClientFactory,
        machineProfileFactory = machineProfileRepository::activeFor,
    )
    val printerRepository = PrinterRepository(
        context.applicationContext,
        printersRepository,
        printerTransportFactory,
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
