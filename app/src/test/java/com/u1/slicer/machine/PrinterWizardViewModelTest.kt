package com.u1.slicer.machine

import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrinterWizardViewModelTest {
    @Test
    fun `vendor model variant flow filters delta and keeps manual Ender`() = runTest {
        val ender = machineJson(
            name = "Creality Ender-3 V3 SE 0.4 nozzle",
            model = "Creality Ender-3 V3 SE",
            structure = "i3",
            semm = true,
            manualChange = true,
        )
        val delta = machineJson(
            name = "Creality Delta 0.4 nozzle",
            model = "Creality Delta",
            structure = "delta",
        )
        val automatedSemm = machineJson(
            name = "Creality MMU 0.4 nozzle",
            model = "Creality MMU",
            structure = "i3",
            semm = true,
            manualChange = false,
        )
        val profiles = mapOf(
            "Creality Ender-3 V3 SE 0.4 nozzle" to ender,
            "Creality Delta 0.4 nozzle" to delta,
            "Creality MMU 0.4 nozzle" to automatedSemm,
        )
        val index = VendorCatalogIndex(
            name = "Creality",
            version = "1.0.0",
            machineModelList = profiles.keys.map { name ->
                VendorCatalogEntry(name.substringBefore(" 0.4 nozzle"), "machine/model.json")
            },
            machineList = profiles.keys.map { VendorCatalogEntry(it, "machine/$it.json") },
        )
        val viewModel = PrinterWizardViewModel(
            vendorNames = listOf("BBL", "Creality"),
            fetchIndex = { index },
            resolveMachine = { _, name -> profiles[name] },
            validate = { MachineProfileValidationResult.success() },
            save = { _, _, _ -> },
        )

        assertEquals(listOf("Creality"), viewModel.state.value.vendors)
        viewModel.selectVendor("Creality")
        viewModel.selectModel(index.machineModelList.first())

        assertEquals(PrinterWizardStep.VARIANT, viewModel.state.value.step)
        assertEquals(
            listOf("Creality Ender-3 V3 SE 0.4 nozzle"),
            viewModel.state.value.variants.map { it.profile.displayName },
        )
    }

    @Test
    fun `standard single extruder model remains selectable when Orca enables SEMM mode`() = runTest {
        val name = "Comgrow T500 0.4 nozzle"
        val model = "Comgrow T500"
        val index = VendorCatalogIndex(
            name = "Comgrow",
            version = "1.0.0",
            machineModelList = listOf(VendorCatalogEntry(model, "machine_model/$model.json")),
            machineList = listOf(VendorCatalogEntry(name, "machine/$name.json")),
        )
        val viewModel = PrinterWizardViewModel(
            vendorNames = listOf("Comgrow"),
            fetchIndex = { index },
            resolveMachine = { _, _ ->
                machineJson(
                    name = name,
                    model = model,
                    structure = "i3",
                    semm = true,
                    manualChange = false,
                )
            },
            validate = { MachineProfileValidationResult.success() },
            save = { _, _, _ -> },
        )

        viewModel.selectVendor("Comgrow")
        viewModel.selectModel(index.machineModelList.single())

        assertEquals(listOf(name), viewModel.state.value.variants.map { it.profile.displayName })
        assertEquals(null, viewModel.state.value.errorMessage)
    }

    @Test
    fun `validation failure surfaces exact error and blocks persistence`() = runTest {
        val fixture = singleMachineFixture()
        var saves = 0
        val viewModel = fixture.viewModel(
            validate = { MachineProfileValidationResult.failure("native placeholder error") },
            save = { _, _, _ -> saves++ },
        )
        fixture.chooseVariant(viewModel)

        viewModel.confirm("My Ender", "http://ender.local")

        assertEquals(0, saves)
        assertEquals("native placeholder error", viewModel.state.value.errorMessage)
        assertFalse(viewModel.state.value.saved)
    }

    @Test
    fun `successful validation persists selected profile and connection`() = runTest {
        val fixture = singleMachineFixture()
        var saved: Triple<MachineProfile, String, String>? = null
        val viewModel = fixture.viewModel(
            validate = { MachineProfileValidationResult.success() },
            save = { profile, nickname, url -> saved = Triple(profile, nickname, url) },
        )
        fixture.chooseVariant(viewModel)

        viewModel.confirm("My Ender", "http://ender.local")

        assertTrue(viewModel.state.value.saved)
        assertEquals("creality-ender-3-v3-se-0.4", saved!!.first.id)
        assertEquals("My Ender", saved!!.second)
        assertEquals("http://ender.local", saved!!.third)
    }

    private data class Fixture(
        val index: VendorCatalogIndex,
        val json: String,
    ) {
        fun viewModel(
            validate: suspend (MachineProfile) -> MachineProfileValidationResult,
            save: suspend (MachineProfile, String, String) -> Unit,
        ) = PrinterWizardViewModel(
            vendorNames = listOf("Creality"),
            fetchIndex = { index },
            resolveMachine = { _, _ -> json },
            validate = validate,
            save = save,
        )

        suspend fun chooseVariant(viewModel: PrinterWizardViewModel) {
            viewModel.selectVendor("Creality")
            viewModel.selectModel(index.machineModelList.single())
            viewModel.selectVariant(viewModel.state.value.variants.single())
        }
    }

    private fun singleMachineFixture(): Fixture {
        val name = "Creality Ender-3 V3 SE 0.4 nozzle"
        return Fixture(
            index = VendorCatalogIndex(
                name = "Creality",
                version = "1.0.0",
                machineModelList = listOf(
                    VendorCatalogEntry("Creality Ender-3 V3 SE", "machine/model.json"),
                ),
                machineList = listOf(VendorCatalogEntry(name, "machine/$name.json")),
            ),
            json = machineJson(
                name = name,
                model = "Creality Ender-3 V3 SE",
                structure = "i3",
                semm = true,
                manualChange = true,
            ),
        )
    }

    private fun machineJson(
        name: String,
        model: String,
        structure: String,
        semm: Boolean = false,
        manualChange: Boolean = false,
    ): String = JSONObject().apply {
        put("type", "machine")
        put("name", name)
        put("printer_model", model)
        put("printer_variant", "0.4")
        put("printer_structure", structure)
        put("gcode_flavor", "marlin2")
        put("printable_area", org.json.JSONArray(listOf("0x0", "220x0", "220x220", "0x220")))
        put("printable_height", "250")
        put("nozzle_diameter", org.json.JSONArray(listOf("0.4")))
        put("single_extruder_multi_material", if (semm) "1" else "0")
        put("manual_filament_change", if (manualChange) "1" else "0")
        put("machine_start_gcode", "G28\nM104 S[first_layer_temperature]")
        put("machine_end_gcode", "M104 S0")
    }.toString()
}
