package com.u1.slicer

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.u1.slicer.data.ExtruderPreset
import com.u1.slicer.data.FilamentProfile
import com.u1.slicer.data.Printer
import com.u1.slicer.data.PrintersConfig
import com.u1.slicer.data.defaultExtruderPresets
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * F78 routing regression guard: [SlicerViewModel.setSlotColor] and
 * [SlicerViewModel.applyFilament] must write extruder preset changes through
 * [com.u1.slicer.data.PrintersRepository.update] (active printer record) rather
 * than the legacy [com.u1.slicer.data.SettingsRepository.saveExtruderPresets] key.
 *
 * Guards commit fd65fe6: before the fix both methods wrote to settingsRepository,
 * making changes invisible to multi-printer builds. After the fix they write to
 * the active printer in PrintersRepository.
 *
 * Each test seeds a known printer state via [com.u1.slicer.data.PrintersRepository.replace]
 * so there are no order-dependencies on pre-existing device DataStore state.
 */
@RunWith(AndroidJUnit4::class)
class SlicerViewModelExtruderRoutingTest {

    private val targetContext get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val application get() = targetContext.applicationContext as U1SlicerApplication

    private val seedPresets = listOf(
        ExtruderPreset(index = 0, color = "#111111", materialType = "PLA"),
        ExtruderPreset(index = 1, color = "#222222", materialType = "PLA"),
        ExtruderPreset(index = 2, color = "#333333", materialType = "PLA"),
        ExtruderPreset(index = 3, color = "#444444", materialType = "PLA"),
    )
    private val seedPrinter = Printer(
        id = "test-routing-printer",
        nickname = "Routing Test Printer",
        moonrakerUrl = "http://routing-test",
        extruderPresets = seedPresets,
    )
    private val seedConfig = PrintersConfig(
        printers = listOf(seedPrinter),
        activeId = "test-routing-printer",
    )

    @Before
    fun setUp() {
        runBlocking {
            application.container.printersRepository.replace(seedConfig)
            // Wait for the write to propagate so the ViewModel sees the seed state.
            application.container.printersRepository.config.first { it?.activeId == "test-routing-printer" }
        }
    }

    @After
    fun tearDown() {
        runBlocking {
            // Restore a sane state so other tests aren't affected.
            val repo = application.container.printersRepository
            val cfg = repo.config.first()
            if (cfg != null && cfg.activeId == "test-routing-printer") {
                repo.update(seedPrinter.copy(extruderPresets = defaultExtruderPresets()))
            }
        }
    }

    // ---- Gap 1a: setSlotColor writes through PrintersRepository ----

    @Test
    fun setSlotColor_writesToActivePrinterInPrintersRepository() = runBlocking {
        val viewModel = SlicerViewModel(application)
        val printersRepo = application.container.printersRepository
        val settingsRepo = application.container.settingsRepository

        // Capture the legacy settings value BEFORE the call (should not change).
        val legacyColorBefore = settingsRepo.extruderPresets.first()
            .firstOrNull { it.index == 1 }?.color ?: "#000000"

        // Call setSlotColor on slot 1 with a distinctive colour.
        viewModel.setSlotColor(slotIndex = 1, hex = "#ABCDEF")

        // Wait for the coroutine inside setSlotColor to write to DataStore.
        waitUntil("printersRepo reflects new slot-1 color") {
            runBlocking {
                printersRepo.config.first()?.active?.extruderPresets
                    ?.firstOrNull { it.index == 1 }?.color == "#ABCDEF"
            }
        }

        // Positive assertion: PrintersRepository has the new value.
        val updatedColor = printersRepo.config.first()?.active?.extruderPresets
            ?.firstOrNull { it.index == 1 }?.color
        assertEquals(
            "F78: setSlotColor must persist to the active printer in PrintersRepository",
            "#ABCDEF",
            updatedColor,
        )

        // Negative assertion: the legacy settingsRepository key must NOT have changed.
        // The legacy path was the pre-F78 bug — if this assertion fails it means
        // the routing regression has been reintroduced.
        val legacyColorAfter = settingsRepo.extruderPresets.first()
            .firstOrNull { it.index == 1 }?.color ?: "#000000"
        assertEquals(
            "F78 regression guard: setSlotColor must NOT write to the legacy " +
                "settingsRepository.extruderPresets key. Before=$legacyColorBefore After=$legacyColorAfter",
            legacyColorBefore,
            legacyColorAfter,
        )

        viewModel.clearModel()
    }

    // ---- Gap 1b: setSlotColor does not affect other slots ----

    @Test
    fun setSlotColor_onlyMutatesTargetSlot() = runBlocking {
        val viewModel = SlicerViewModel(application)
        val printersRepo = application.container.printersRepository

        viewModel.setSlotColor(slotIndex = 2, hex = "#FEDCBA")

        waitUntil("slot 2 updated") {
            runBlocking {
                printersRepo.config.first()?.active?.extruderPresets
                    ?.firstOrNull { it.index == 2 }?.color == "#FEDCBA"
            }
        }

        val presets = printersRepo.config.first()?.active?.extruderPresets!!
        assertEquals("#111111", presets.firstOrNull { it.index == 0 }?.color)
        assertEquals("#222222", presets.firstOrNull { it.index == 1 }?.color)
        assertEquals("#FEDCBA", presets.firstOrNull { it.index == 2 }?.color)
        assertEquals("#444444", presets.firstOrNull { it.index == 3 }?.color)

        viewModel.clearModel()
    }

    // ---- Gap 1c: applyFilament writes filamentProfileId through PrintersRepository ----

    @Test
    fun applyFilament_writesFilamentProfileIdToActivePrinterInPrintersRepository() = runBlocking {
        val viewModel = SlicerViewModel(application)
        val printersRepo = application.container.printersRepository
        val settingsRepo = application.container.settingsRepository

        // applyFilament only writes extruder presets in single-colour mode
        // (_config.extruderCount == 1, which is the default for a fresh ViewModel).
        // The default selected extruder is slot 0.
        val sentinelProfileId = 9999L
        val profile = FilamentProfile(
            id = sentinelProfileId,
            name = "Test PLA",
            material = "PLA",
            color = "#FF0000",
            nozzleTemp = 215,
            bedTemp = 55,
            retractLength = 1.0f,
            retractSpeed = 45f,
        )

        // Capture legacy state before the call.
        val legacyPresetsBefore = settingsRepo.extruderPresets.first()

        viewModel.applyFilament(profile)

        // Wait for the coroutine to persist.
        waitUntil("printersRepo reflects filamentProfileId=$sentinelProfileId on slot 0") {
            runBlocking {
                printersRepo.config.first()?.active?.extruderPresets
                    ?.firstOrNull { it.index == 0 }?.filamentProfileId == sentinelProfileId
            }
        }

        val updatedPreset = printersRepo.config.first()?.active?.extruderPresets
            ?.firstOrNull { it.index == 0 }
        assertEquals(
            "F78: applyFilament must persist filamentProfileId to the active printer in PrintersRepository",
            sentinelProfileId,
            updatedPreset?.filamentProfileId,
        )
        assertEquals(
            "F78: applyFilament must also persist materialType to the active printer in PrintersRepository",
            "PLA",
            updatedPreset?.materialType,
        )

        // Negative: legacy settingsRepository must not have been touched.
        val legacyPresetsAfter = settingsRepo.extruderPresets.first()
        assertEquals(
            "F78 regression guard: applyFilament must NOT write to legacy settingsRepository.extruderPresets",
            legacyPresetsBefore.map { it.filamentProfileId },
            legacyPresetsAfter.map { it.filamentProfileId },
        )

        viewModel.clearModel()
    }

    // ---- helpers ----

    private fun waitUntil(label: String, timeoutMs: Long = 5_000L, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(50)
        }
        throw AssertionError("Timed out waiting for: $label")
    }
}
