package com.u1.slicer.machine

import com.u1.slicer.data.HostType
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
    fun `manual single extruder model remains selectable when Orca enables SEMM metadata`() = runTest {
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
                    manualChange = true,
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
    fun `automatic SEMM model is not offered as an independent single tool`() = runTest {
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
            resolveMachine = { _, _ -> machineJson(name, model, "i3", semm = true, manualChange = false) },
            validate = { error("Unsupported automatic SEMM must not reach native validation") },
            save = { _, _, _ -> error("Unsupported automatic SEMM must not be saved") },
        )
        viewModel.selectVendor("Comgrow")
        viewModel.selectModel(index.machineModelList.single())
        assertTrue(viewModel.state.value.variants.isEmpty())
        assertEquals("No supported variants are available for $model.", viewModel.state.value.errorMessage)
    }

    @Test
    fun `catalog model alias resolves variants using its profile filename`() = runTest {
        val catalogModel = VendorCatalogEntry("MK4IS", "machine/Prusa MK4.json")
        val variantNames = listOf(
            "Prusa MK4 0.25 nozzle",
            "Prusa MK4 0.4 nozzle",
        )
        val index = VendorCatalogIndex(
            name = "Prusa",
            version = "02.02.00.04",
            machineModelList = listOf(catalogModel),
            machineList = variantNames.map { VendorCatalogEntry(it, "machine/$it.json") },
        )
        val viewModel = PrinterWizardViewModel(
            vendorNames = listOf("Prusa"),
            fetchIndex = { index },
            resolveMachine = { _, name ->
                machineJson(
                    name = name,
                    model = "Prusa MK4",
                    structure = "i3",
                )
            },
            validate = { MachineProfileValidationResult.success() },
            save = { _, _, _ -> },
        )

        viewModel.selectVendor("Prusa")
        viewModel.selectModel(catalogModel)

        assertEquals(variantNames, viewModel.state.value.variants.map { it.profile.displayName })
        assertEquals(null, viewModel.state.value.errorMessage)
    }

    @Test
    fun `Prusa catalogue defaults to PrusaLink despite generic Orca host metadata`() = runTest {
        val name = "Prusa MK4 0.4 nozzle"
        val model = "Prusa MK4"
        val index = VendorCatalogIndex(
            name = "Prusa",
            version = "1",
            machineModelList = listOf(VendorCatalogEntry(model, "machine/$model.json")),
            machineList = listOf(VendorCatalogEntry(name, "machine/$name.json")),
        )
        var savedConnection: PrinterWizardConnection? = null
        val viewModel = PrinterWizardViewModel(
            vendorNames = listOf("Prusa"),
            fetchIndex = { index },
            resolveMachine = { _, _ ->
                machineJson(name, model, "i3", hostType = "octoprint")
            },
            validate = { MachineProfileValidationResult.success() },
            save = { _, _, connection -> savedConnection = connection },
        )

        viewModel.selectVendor("Prusa")
        viewModel.selectModel(index.machineModelList.single())
        viewModel.selectVariant(viewModel.state.value.variants.single())

        assertEquals(HostType.PRUSALINK, viewModel.state.value.connectionHostType)

        viewModel.confirm(
            nickname = "My MK4",
            address = " prusa.local ",
            apiKey = " api-key ",
            username = " maker ",
            password = "secret",
        )

        assertEquals(
            PrinterWizardConnection(
                hostType = HostType.PRUSALINK,
                address = "prusa.local",
                apiKey = "api-key",
                username = "maker",
                password = "secret",
            ),
            savedConnection,
        )
    }

    @Test
    fun `Snapmaker U1 catalogue default remains Moonraker despite Orca metadata`() = runTest {
        val name = "Snapmaker U1 0.4 nozzle"
        val model = "Snapmaker U1"
        val index = VendorCatalogIndex(
            name = "Snapmaker",
            version = "1",
            machineModelList = listOf(VendorCatalogEntry(model, "machine/$model.json")),
            machineList = listOf(VendorCatalogEntry(name, "machine/$name.json")),
        )
        val viewModel = PrinterWizardViewModel(
            vendorNames = listOf("Snapmaker"),
            fetchIndex = { index },
            resolveMachine = { _, _ -> machineJson(name, model, "i3", hostType = "octoprint") },
            validate = { MachineProfileValidationResult.success() },
            save = { _, _, _ -> },
        )

        viewModel.selectVendor("Snapmaker")
        viewModel.selectModel(index.machineModelList.single())
        viewModel.selectVariant(viewModel.state.value.variants.single())

        assertEquals(HostType.MOONRAKER, viewModel.state.value.connectionHostType)
    }

    @Test
    fun `catalog connection default is selectable and OctoPrint credentials are saved`() = runTest {
        val fixture = singleMachineFixture(hostType = "octoprint")
        var savedConnection: PrinterWizardConnection? = null
        val viewModel = fixture.viewModel(
            validate = { MachineProfileValidationResult.success() },
            save = { _, _, connection -> savedConnection = connection },
        )
        fixture.chooseVariant(viewModel)

        assertEquals(HostType.OCTOPRINT, viewModel.state.value.connectionHostType)
        viewModel.selectHostType(HostType.MOONRAKER)
        assertEquals(HostType.MOONRAKER, viewModel.state.value.connectionHostType)
        viewModel.selectHostType(HostType.OCTOPRINT)

        viewModel.confirm("Octo Ender", "octo.local", apiKey = "octo-key")

        assertEquals(HostType.OCTOPRINT, savedConnection?.hostType)
        assertEquals("octo-key", savedConnection?.apiKey)
    }

    @Test
    fun `unsupported Orca host does not masquerade as Moonraker`() = runTest {
        val fixture = singleMachineFixture(hostType = "esp3d")
        var saves = 0
        val viewModel = fixture.viewModel(
            validate = { MachineProfileValidationResult.success() },
            save = { _, _, _ -> saves++ },
        )
        fixture.chooseVariant(viewModel)

        assertEquals(null, viewModel.state.value.connectionHostType)

        viewModel.confirm("ESP printer", "printer.local")

        assertEquals(0, saves)
        assertEquals("Choose a supported connection type.", viewModel.state.value.errorMessage)
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
        var saved: Triple<MachineProfile, String, PrinterWizardConnection>? = null
        val viewModel = fixture.viewModel(
            validate = { MachineProfileValidationResult.success() },
            save = { profile, nickname, url -> saved = Triple(profile, nickname, url) },
        )
        fixture.chooseVariant(viewModel)

        viewModel.confirm("My Ender", "http://ender.local")

        assertTrue(viewModel.state.value.saved)
        assertEquals("creality-ender-3-v3-se-0.4", saved!!.first.id)
        assertEquals("My Ender", saved!!.second)
        assertEquals("http://ender.local", saved!!.third.address)
    }

    private data class Fixture(
        val index: VendorCatalogIndex,
        val json: String,
    ) {
        fun viewModel(
            validate: suspend (MachineProfile) -> MachineProfileValidationResult,
            save: suspend (MachineProfile, String, PrinterWizardConnection) -> Unit,
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

    private fun singleMachineFixture(hostType: String? = null): Fixture {
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
                hostType = hostType,
            ),
        )
    }

    private fun machineJson(
        name: String,
        model: String,
        structure: String,
        semm: Boolean = false,
        manualChange: Boolean = false,
        hostType: String? = null,
    ): String = JSONObject().apply {
        put("type", "machine")
        put("name", name)
        put("printer_model", model)
        put("printer_variant", "0.4")
        put("printer_structure", structure)
        put("gcode_flavor", "marlin2")
        hostType?.let { put("host_type", it) }
        put("printable_area", org.json.JSONArray(listOf("0x0", "220x0", "220x220", "0x220")))
        put("printable_height", "250")
        put("nozzle_diameter", org.json.JSONArray(listOf("0.4")))
        put("single_extruder_multi_material", if (semm) "1" else "0")
        put("manual_filament_change", if (manualChange) "1" else "0")
        put("machine_start_gcode", "G28\nM104 S[first_layer_temperature]")
        put("machine_end_gcode", "M104 S0")
    }.toString()
}
