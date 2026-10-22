package com.u1.slicer.machine

import com.u1.slicer.data.Printer
import com.u1.slicer.data.MachineKind
import com.u1.slicer.data.PrinterKind
import com.u1.slicer.data.PrintHostConfig
import com.u1.slicer.data.PrintersConfig
import com.u1.slicer.data.PrintersRepository
import com.u1.slicer.printer.DefaultPrinterTransportFactory
import com.u1.slicer.printer.TransportCommandResult
import com.u1.slicer.slice.GenericMachineProfile
import com.u1.slicer.slice.SliceArtifact
import com.u1.slicer.slice.SlicerTarget
import com.u1.slicer.slice.isCompatibleWith
import com.u1.slicer.slice.isLocalSliceAvailable
import com.u1.slicer.slice.resolveDefaultSliceTarget
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlinx.coroutines.test.runTest

class LegacyMachineProfileBindingTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun repository(cache: File = temporary.newFolder()) = MachineProfileRepository(AssetProvider { path ->
        require(path == MachineProfileRepository.U1_MACHINE_ASSET)
        requireNotNull(javaClass.classLoader?.getResource("machine/snapmaker_u1_resolved.json")).readText()
    }, cache)

    private fun profile(flavor: String = "klipper"): MachineProfile {
        val json = JSONObject(requireNotNull(javaClass.classLoader?.getResource("machine/generic_klipper_300.json")).readText())
            .put("gcode_flavor", flavor).toString()
        return requireNotNull(MachineProfile.fromResolvedOrcaJson(json, MachineFamily.GENERIC))
    }

    private fun restore(printer: Printer, repository: MachineProfileRepository): Printer =
        PrintersRepository.decodeConfig(PrintersConfig.toJson(PrintersConfig(listOf(printer), printer.id)), repository).active

    @Test
    fun `cached catalogue binding enables Prepare after restarting`() {
        val repository = repository()
        val profile = profile()
        repository.save(profile)
        val legacy = Printer("catalogue", "Workshop", machineProfileId = profile.id)
        val raw = PrintersConfig.toJson(PrintersConfig(listOf(legacy), legacy.id))

        val restored = PrintersRepository.decodeConfig(raw, repository).active

        assertEquals(300f, repository.activeFor(restored).bedSizeX)
        assertTrue("A valid cached machine must enable the Prepare bed and local slicing", isLocalSliceAvailable(restored))
        assertEquals(profile.machineJson, restored.machineProfile?.resolvedJson)
    }

    @Test fun `restored cached bindings persist and remain usable without the cache`() {
        val repository = repository()
        val profile = profile()
        repository.save(profile)
        val restored = restore(Printer("legacy", "Workshop", machineProfileId = profile.id), repository)
        val afterCacheLoss = restore(restored, repository())
        assertTrue(isLocalSliceAvailable(afterCacheLoss))
        assertEquals(restored, afterCacheLoss)
        assertSame(restored, repository.restoreCachedBinding(restored))
    }

    @Test fun `restored binding can send only the matching generated artifact`() {
        val repository = repository()
        val profile = profile()
        repository.save(profile)
        val restored = restore(Printer("legacy", "Workshop", machineProfileId = profile.id), repository)
        val artifact = SliceArtifact.MoonrakerGcodeArtifact("model", File("output.gcode"), SlicerTarget.GenericKlipper,
            machineProfileFingerprint = GenericMachineProfile.fromJson(profile.machineJson).fingerprint)
        assertTrue(artifact.isCompatibleWith(restored))
        assertFalse(artifact.isCompatibleWith(Printer("u1", "U1")))
        assertFalse(artifact.copy(machineProfileFingerprint = "another-profile").isCompatibleWith(restored))
    }

    @Test fun `explicit Marlin cache bindings restore the Marlin target`() {
        val repository = repository()
        val profile = profile("marlin2")
        repository.save(profile)
        val restored = restore(Printer("legacy", "Marlin", machineProfileId = profile.id, machine = MachineKind.GENERIC_MARLIN), repository)
        assertTrue(isLocalSliceAvailable(restored))
        assertEquals(SlicerTarget.GenericMarlin, resolveDefaultSliceTarget(restored))
        assertEquals("marlin2", restored.machineProfile?.gcodeFlavor)
    }

    @Test fun `legacy record without machine kind infers firmware from its cached profile`() {
        val repository = repository()
        val profile = profile("marlin2")
        repository.save(profile)
        val raw = JSONObject().put("activeId", "legacy").put("printers", org.json.JSONArray().put(
            JSONObject().put("id", "legacy").put("nickname", "Old catalogue printer")
                .put("machineProfileId", profile.id)
        )).toString()
        val restored = PrintersRepository.decodeConfig(raw, repository).active
        assertEquals(MachineKind.GENERIC_MARLIN, restored.machine)
        assertTrue(isLocalSliceAvailable(restored))
        assertEquals(SlicerTarget.GenericMarlin, resolveDefaultSliceTarget(restored))
    }

    @Test fun `startup backfill survives cache loss and preserves unknown and unavailable records`() {
        val repository = repository()
        val profile = profile()
        repository.save(profile)
        val source = JSONObject(PrintersConfig.toJson(PrintersConfig(listOf(
            Printer("legacy", "Workshop", machineProfileId = profile.id),
            Printer("missing", "Unavailable", machineProfileId = "missing"),
        ), "legacy")))
        source.put("futureSetting", "retained")
        val originalPrinters = source.getJSONArray("printers")
        originalPrinters.getJSONObject(0).put("futurePrinterSetting", "retained too")
        val unavailable = originalPrinters.getJSONObject(1).toString()
        val invalid = JSONObject().put("id", "future").put("kind", "UNKNOWN_FUTURE_PROTOCOL")
        originalPrinters.put(invalid)

        val backfilled = PrintersRepository.restoreCachedBindings(source.toString(), repository)
        val raw = JSONObject(backfilled)
        assertEquals("retained", raw.getString("futureSetting"))
        assertEquals("retained too", raw.getJSONArray("printers").getJSONObject(0).getString("futurePrinterSetting"))
        assertEquals(JSONObject(unavailable).toMap(), raw.getJSONArray("printers").getJSONObject(1).toMap())
        assertEquals(invalid.toMap(), raw.getJSONArray("printers").getJSONObject(2).toMap())
        assertTrue(isLocalSliceAvailable(PrintersRepository.decodeConfig(backfilled, repository()).active))
        assertEquals(backfilled, PrintersRepository.restoreCachedBindings(backfilled, repository()))
    }

    @Test fun `backfill without an available profile leaves the original bytes intact`() {
        val raw = """{ "activeId": "missing", "printers": [{"id":"missing","nickname":"Missing","machineProfileId":"not-cached"}] }"""
        assertEquals(raw, PrintersRepository.restoreCachedBindings(raw, repository()))
    }

    @Test fun `missing and corrupt bindings stay visible and blocked`() {
        val cache = temporary.newFolder()
        val repository = repository(cache)
        val legacy = Printer("legacy", "Workshop", machineProfileId = "missing")
        for (corruptJson in listOf<String?>(null, "not JSON")) {
            if (corruptJson != null) File(cache, "missing.json").writeText(corruptJson)
            val restored = restore(legacy, repository)
            assertEquals(legacy, restored)
            assertFalse(isLocalSliceAvailable(restored))
            assertThrows(IllegalArgumentException::class.java) { repository.activeFor(restored) }
        }
    }

    @Test fun `cached firmware mismatch never changes an explicit machine kind`() {
        val repository = repository()
        val profile = profile()
        repository.save(profile)
        val legacy = Printer("legacy", "Marlin", machineProfileId = profile.id, machine = MachineKind.GENERIC_MARLIN)
        val restored = restore(legacy, repository)
        assertEquals(legacy, restored)
        assertFalse(isLocalSliceAvailable(restored))
        assertThrows(IllegalArgumentException::class.java) { repository.activeFor(restored) }
    }

    @Test fun `unavailable machine bindings disable transport instead of crashing printer selection`() = runTest {
        val repository = repository()
        val profile = profile()
        repository.save(profile)
        val factory = DefaultPrinterTransportFactory(
            machineProfileFactory = repository::activeFor,
            moonrakerClientFactory = { error("Unavailable machine must not create a network client") },
        )
        val unavailable = listOf(
            Printer("mismatch", "Marlin", machineProfileId = profile.id, machine = MachineKind.GENERIC_MARLIN),
            Printer("missing", "Missing", machineProfileId = "missing"),
            Printer("wrong-family", "U1", machineProfileId = profile.id, machine = MachineKind.SNAPMAKER_U1),
        )
        for (printer in unavailable) {
            val transport = factory.create(printer)
            assertFalse(transport.capabilities.supportsUpload)
            assertFalse(transport.capabilities.supportsStartOnUpload)
            assertTrue(transport.testConnection()?.isNotBlank() == true)
            assertTrue(transport.setHeaterTemperature("extruder", 200) is TransportCommandResult.Unsupported)
            assertTrue(transport.uploadJob(File("output.gcode"), "output.gcode") is TransportCommandResult.Unsupported)
        }
    }

    @Test fun `generic binding cannot silently use built in U1 commands`() {
        val repository = repository()
        val legacy = Printer("legacy", "Generic", machineProfileId = "snapmaker-u1-0.4", machine = MachineKind.GENERIC_KLIPPER)
        assertFalse(isLocalSliceAvailable(restore(legacy, repository)))
        assertThrows(IllegalArgumentException::class.java) { repository.activeFor(legacy) }
    }

    @Test fun `U1 binding cannot silently use generic machine commands`() {
        val repository = repository()
        val profile = profile()
        repository.save(profile)
        val legacy = Printer("legacy", "U1", machineProfileId = profile.id, machine = MachineKind.SNAPMAKER_U1)
        assertThrows(IllegalArgumentException::class.java) { repository.activeFor(legacy) }
    }

    @Test fun `embedded profile wins over conflicting cached profile`() {
        val repository = repository()
        val cached = profile()
        repository.save(cached)
        val embedded = GenericMachineProfile.fromJson(JSONObject(cached.machineJson).put("printable_height", "200").toString())
        val printer = Printer("embedded", "Workshop", machineProfileId = cached.id, machineProfile = embedded)
        val restored = restore(printer, repository)
        assertEquals(embedded.fingerprint, restored.machineProfile?.fingerprint)
        assertEquals(200f, repository.activeFor(restored).maxPrintHeight)
    }

    @Test fun `built in CC1 and U1 remain sliceable without imported profiles`() {
        val repository = repository()
        val cc1 = restore(Printer("cc1", "Carbon", PrinterKind.ELEGOO_CC1, host = PrintHostConfig("cc.local")), repository)
        assertNull(cc1.machineProfile)
        assertTrue(isLocalSliceAvailable(cc1))
        assertEquals(257f, repository.activeFor(cc1).bedSizeX)
        assertEquals(1, repository.extruderCountFor(cc1))
        val u1 = restore(Printer("u1", "U1"), repository)
        assertTrue(isLocalSliceAvailable(u1))
        assertEquals(270f, repository.activeFor(u1).bedSizeX)
        assertEquals(4, repository.extruderCountFor(u1))
    }
}
