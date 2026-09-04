package com.u1.slicer.slicing

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.u1.slicer.NativeLibrary
import com.u1.slicer.data.SliceConfig
import com.u1.slicer.gcode.GcodeValidator
import com.u1.slicer.gcode.MarlinDialect
import com.u1.slicer.machine.CachedOrcaProfileLookup
import com.u1.slicer.machine.MachineFamily
import com.u1.slicer.machine.MachineProfile
import com.u1.slicer.machine.MachineProfileRepository
import com.u1.slicer.machine.NativeMachineProfileValidator
import com.u1.slicer.machine.OrcaProfileType
import com.u1.slicer.machine.PrinterWizardViewModel
import com.u1.slicer.machine.ProfileCache
import com.u1.slicer.machine.VendorCatalogClient
import com.u1.slicer.machine.resolveMachineSliceConfig
import java.io.File
import java.net.URLDecoder
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Device E2E: pinned catalogue wizard selection -> validation save -> Marlin Benchy slice. */
@RunWith(AndroidJUnit4::class)
class PrinterWizardToSliceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val targetContext = instrumentation.targetContext
    private lateinit var server: MockWebServer
    private lateinit var testRoot: File

    @Before
    fun setUp() {
        assertTrue("Native library required", NativeLibrary.isLoaded)
        testRoot = File(targetContext.cacheDir, "printer-wizard-e2e").also {
            it.deleteRecursively()
            it.mkdirs()
        }
        server = MockWebServer()
        server.dispatcher = fixtureDispatcher()
        server.start()
    }

    @After
    fun tearDown() {
        NativeLibrary().clearModel()
        runCatching { server.shutdown() }
        testRoot.deleteRecursively()
    }

    @Test
    fun ender3V3Se_wizardSaveAndBenchySlice_passesMarlinValidator() = runBlocking {
        val cache = ProfileCache(File(testRoot, "profiles"))
        val client = VendorCatalogClient(profilesBaseUrl = server.url("/"))
        val lookup = CachedOrcaProfileLookup(cache, client)
        val repository = MachineProfileRepository(
            assets = { path -> targetContext.assets.open(path).bufferedReader().use { it.readText() } },
            cacheDir = File(testRoot, "resolved"),
        )
        var savedProfile: MachineProfile? = null
        val wizard = PrinterWizardViewModel(
            vendorNames = listOf("Creality"),
            fetchIndex = { vendor ->
                client.fetchVendorIndex(vendor).also { cache.writeVendorIndex(vendor, it) }
            },
            resolveMachine = { vendor, name ->
                lookup.resolveByName(vendor, OrcaProfileType.MACHINE, name)
            },
            validate = NativeMachineProfileValidator(targetContext)::validate,
            save = { profile, _, _ ->
                repository.save(profile)
                savedProfile = profile
            },
        )

        wizard.selectVendor("Creality")
        val enderModel = wizard.state.value.models.single { it.name == "Creality Ender-3 V3 SE" }
        wizard.selectModel(enderModel)
        val variant = wizard.state.value.variants.single { it.profile.printerVariant == "0.4" }
        wizard.selectVariant(variant)
        wizard.confirm("Creality Ender-3 V3 SE", "")

        assertTrue("Wizard error: ${wizard.state.value.errorMessage}", wizard.state.value.saved)
        val profile = assertNotNull(savedProfile).let { savedProfile!! }
        assertEquals("creality-ender-3-v3-se-0.4", profile.id)
        assertEquals(profile, MachineProfileRepository(
            assets = { path -> targetContext.assets.open(path).bufferedReader().use { it.readText() } },
            cacheDir = File(testRoot, "resolved"),
        ).byId(profile.id))

        server.shutdown()
        sliceBenchyAndValidate(profile)
    }

    private fun sliceBenchyAndValidate(profile: MachineProfile) {
        val native = NativeLibrary()
        native.clearModel()
        val benchy = copyTestAsset("3DBenchy.stl")
        assertTrue("Benchy must load", native.loadModel(benchy.absolutePath))
        val info = assertNotNull(native.getModelInfo()).let { native.getModelInfo()!! }
        assertTrue(native.setModelInstances(floatArrayOf(
            (profile.bedSizeX - info.sizeX) / 2f,
            (profile.bedSizeY - info.sizeY) / 2f,
        )))
        val result = native.slice(resolveMachineSliceConfig(profile, SliceConfig(
            layerHeight = 0.2f,
            firstLayerHeight = 0.2f,
            nozzleTemp = 210,
            bedTemp = 60,
            nozzleDiameter = profile.nozzleDiameter,
            extruderCount = 1,
            extruderTemps = intArrayOf(210),
            wipeTowerEnabled = false,
        )))
        assertNotNull("Native slicer must return a result", result)
        assertTrue("Benchy slice failed: ${result!!.errorMessage}", result.success)
        val validation = GcodeValidator.validateDialect(File(result.gcodePath).readText(), MarlinDialect)
        assertTrue("Marlin validation failed: ${validation.describe()}", validation.isValid)
    }

    private fun fixtureDispatcher() = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            val path = URLDecoder.decode(request.requestUrl?.encodedPath.orEmpty(), "UTF-8")
            val asset = when (path) {
                "/Creality.json" -> "machine/catalogue/Creality/index.json"
                "/Creality/machine/Creality Ender-3 V3 SE 0.4 nozzle.json" ->
                    "machine/catalogue/Creality/machine/Creality Ender-3 V3 SE 0.4 nozzle.json"
                "/Creality/machine/fdm_creality_common.json" ->
                    "machine/catalogue/Creality/machine/fdm_creality_common.json"
                "/Creality/machine/fdm_machine_common.json" ->
                    "machine/catalogue/Creality/machine/fdm_machine_common.json"
                else -> return MockResponse().setResponseCode(404)
            }
            return MockResponse().setResponseCode(200).setBody(readTestAsset(asset))
        }
    }

    private fun readTestAsset(path: String): String =
        instrumentation.context.assets.open(path).bufferedReader().use { it.readText() }

    private fun copyTestAsset(path: String): File = File(testRoot, path).also { file ->
        instrumentation.context.assets.open(path).use { input ->
            file.outputStream().use(input::copyTo)
        }
    }
}
