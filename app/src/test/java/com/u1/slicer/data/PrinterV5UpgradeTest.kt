package com.u1.slicer.data

import com.u1.slicer.slice.GenericMachineProfile
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PrinterV5UpgradeTest {
    private val snapshot = GenericMachineProfile.fromJson(requireNotNull(javaClass.classLoader?.getResource("machine/generic_klipper_300.json")).readText())

    @Test fun published405GenericSnapshotRetainsMachineIdentityAndAddress() {
        val old = JSONObject().put("id", "published-generic").put("nickname", "Workshop")
            .put("kind", "MOONRAKER").put("machine", "GENERIC_KLIPPER")
            .put("moonrakerUrl", "http://workshop:7125").put("machineProfile", JSONObject(snapshot.resolvedJson))
        val migrated = Printer.fromJsonObject(old)
        assertEquals(MachineKind.GENERIC_KLIPPER, migrated.machine)
        assertEquals(snapshot.fingerprint, migrated.machineProfile?.fingerprint)
        assertEquals("http://workshop:7125", migrated.moonrakerUrl)
        assertEquals(emptyList<ExtruderPreset>(), migrated.extruderPresets)
        assertEquals(migrated, Printer.fromJsonObject(Printer.toJsonObject(migrated)))
    }

    @Test fun published405Cc1AndOctoPrintRecordsRemainActiveAndKeepCredentials() {
        val cc1 = JSONObject().put("id", "cc1").put("nickname", "Carbon").put("kind", "ELEGOO_CC1")
            .put("machine", "ELEGOO_CENTAURI_CARBON").put("host", JSONObject().put("url", "192.168.1.70").put("apiKey", ""))
        val octo = JSONObject().put("id", "octo").put("nickname", "Octo").put("kind", "OCTOPRINT")
            .put("machine", "GENERIC_KLIPPER").put("host", JSONObject().put("url", "http://octo.local/prefix").put("apiKey", "secret"))
            .put("machineProfile", JSONObject(snapshot.resolvedJson))
        val config = PrintersConfig.fromJson(JSONObject().put("activeId", "octo").put("printers", org.json.JSONArray(listOf(cc1, octo))).toString())
        assertEquals(listOf("cc1", "octo"), config.printers.map { it.id })
        assertEquals("octo", config.activeId)
        assertEquals(HostType.ELEGOO_CC1, config.printers.first().hostType)
        assertEquals(PrintHostConfig("http://octo.local/prefix", "secret"), config.active.host)
        assertEquals(config, PrintersConfig.fromJson(PrintersConfig.toJson(config)))
    }

    @Test fun catalogueIdWithoutEmbeddedSnapshotNeverDefaultsToU1() {
        val record = JSONObject().put("id", "catalogue").put("nickname", "Imported")
            .put("kind", "MOONRAKER").put("host_type", "prusalink").put("print_host", "http://prusa.local")
            .put("machineProfileId", "prusa-core-one-0.4").put("printhost_user", "user").put("printhost_password", "password")
        val migrated = Printer.fromJsonObject(record)
        assertEquals(MachineKind.GENERIC_KLIPPER, migrated.machine)
        assertNull(migrated.machineProfile)
        assertEquals("prusa-core-one-0.4", migrated.machineProfileId)
        assertEquals(HostType.PRUSALINK, migrated.hostType)
        assertEquals("user", migrated.username)
        assertEquals("password", migrated.password)
        assertFalse(com.u1.slicer.slice.isLocalSliceAvailable(migrated))
        assertEquals(migrated, Printer.fromJsonObject(Printer.toJsonObject(migrated)))
    }

    @Test fun exactBuiltInU1IdInUnreleasedV5RecordPreservesU1Identity() {
        val record = JSONObject().put("id", "bound-u1").put("nickname", "U1")
            .put("kind", "MOONRAKER").put("machineProfileId", "snapmaker-u1-0.4")
        val restored = Printer.fromJsonObject(record)
        assertEquals(MachineKind.SNAPMAKER_U1, restored.machine)
        assertEquals(4, restored.extruderPresets.size)
        assertTrue(com.u1.slicer.slice.isLocalSliceAvailable(restored))
        assertEquals(MachineKind.SNAPMAKER_U1, Printer("constructor-u1", "U1", machineProfileId = "snapmaker-u1-0.4").machine)
        assertEquals(restored, Printer.fromJsonObject(Printer.toJsonObject(restored)))
        record.put("machineProfileId", "snapmaker-u1-0.4-custom")
        assertEquals(MachineKind.GENERIC_KLIPPER, Printer.fromJsonObject(record).machine)
        record.put("machineProfileId", "snapmaker-u1-0.4").put("machine", "GENERIC_KLIPPER")
        assertEquals(MachineKind.GENERIC_KLIPPER, Printer.fromJsonObject(record).machine)
    }

    @Test fun firmwareAndProfileMismatchIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            Printer("mismatch", "Mismatch", machineProfile = snapshot, machine = MachineKind.GENERIC_MARLIN)
        }
    }

    @Test fun publishedSingleNozzleSemmMetadataRemainsSafeAndDoesNotLosePrinter() {
        val oldSnapshot = JSONObject(snapshot.resolvedJson).put("single_extruder_multi_material", "1")
        val record = JSONObject().put("id", "legacy-flag").put("nickname", "Legacy")
            .put("kind", "MOONRAKER").put("machine", "GENERIC_KLIPPER").put("machineProfile", oldSnapshot)
        val migrated = Printer.fromJsonObject(record)
        val profile = requireNotNull(migrated.machineProfile)
        assertEquals("1", JSONObject(profile.resolvedJson).getString("single_extruder_multi_material"))
        assertEquals("0", profile.compose()["single_extruder_multi_material"])
        assertEquals(1, profile.extruderCount)
        assertEquals(profile.fingerprint, Printer.fromJsonObject(Printer.toJsonObject(migrated)).machineProfile?.fingerprint)
        assertThrows(IllegalArgumentException::class.java) { GenericMachineProfile.fromJson(oldSnapshot.toString()) }
    }
}
