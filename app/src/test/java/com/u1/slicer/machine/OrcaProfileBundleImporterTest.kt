package com.u1.slicer.machine

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.test.runTest
import com.u1.slicer.data.ProcessProfileParser
import com.u1.slicer.ui.parseFilamentJson
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OrcaProfileBundleImporterTest {
    @Test
    fun `recorded printer bundle fetches missing pinned base and passes validation gate`() = runTest {
        val ender = fixture("machine/catalogue/Creality/machine/Creality Ender-3 V3 SE 0.4 nozzle.json")
        val creality = fixture("machine/catalogue/Creality/machine/fdm_creality_common.json")
        val common = fixture("machine/catalogue/Creality/machine/fdm_machine_common.json")
        val resolvedEnder = requireNotNull(OrcaProfileResolver.resolve(ender) { name ->
            mapOf("fdm_creality_common" to creality, "fdm_machine_common" to common)[name]
        })
        val root = JSONObject().apply {
            put("type", "machine")
            put("name", "My Ender-3 V3 SE")
            put("printer_settings_id", "Creality")
            put("inherits", "Creality Ender-3 V3 SE 0.4 nozzle")
        }.toString()
        val manifest = JSONObject().apply {
            put("bundle_type", "printer config bundle")
            put("printer_preset_name", "My Ender-3 V3 SE")
            put("printer_config", JSONArray(listOf("printer/My Ender.json")))
            put("filament_config", JSONArray())
            put("process_config", JSONArray())
        }.toString()
        val missingLoads = mutableListOf<Triple<String, OrcaProfileType, String>>()
        var validated: MachineProfile? = null
        val importer = importer(
            loadMissing = { vendor, type, name ->
                missingLoads += Triple(vendor, type, name)
                resolvedEnder.takeIf { name == "Creality Ender-3 V3 SE 0.4 nozzle" }
            },
            validateMachine = { profile ->
                validated = profile
                MachineProfileValidationResult.success()
            },
        )

        val result = importer.import(
            ByteArrayInputStream(zipOf(
                "bundle_structure.json" to manifest,
                "printer/My Ender.json" to root,
            )),
            "My Ender.orca_printer",
        )

        assertEquals(listOf(Triple("Creality", OrcaProfileType.MACHINE, "Creality Ender-3 V3 SE 0.4 nozzle")), missingLoads)
        assertEquals(1, result.machines.size)
        assertEquals("creality-ender-3-v3-se-0.4", result.machines.single().id)
        assertEquals(result.machines.single(), validated)
    }

    @Test
    fun `machine validation failure rejects bundle with the exact native error`() = runTest {
        val importer = importer(
            loadMissing = { _, _, _ -> null },
            validateMachine = { MachineProfileValidationResult.failure("unknown placeholder foo") },
        )
        val flat = flatMachine("Imported printer")

        val error = runCatching {
            importer.import(
                ByteArrayInputStream(zipOf(
                    "bundle_structure.json" to printerManifest("printer/imported.json"),
                    "printer/imported.json" to flat,
                )),
                "imported.orca_printer",
            )
        }.exceptionOrNull()

        assertEquals("unknown placeholder foo", error?.message)
    }

    @Test
    fun `filament bundle resolves its recorded local inheritance chain`() = runTest {
        val base = JSONObject().apply {
            put("type", "filament")
            put("name", "Recorded PLA base")
            put("instantiation", "false")
            put("filament_type", JSONArray(listOf("PLA")))
            put("nozzle_temperature", JSONArray(listOf("215")))
            put("hot_plate_temp", JSONArray(listOf("55")))
        }.toString()
        val child = JSONObject().apply {
            put("type", "filament")
            put("name", "Recorded PLA @Creality")
            put("inherits", "Recorded PLA base")
            put("nozzle_temperature", JSONArray(listOf("220")))
        }.toString()
        val manifest = JSONObject().apply {
            put("bundle_type", "filament config bundle")
            put("filament_name", "Recorded PLA")
            put("printer_vendor", JSONArray().put(JSONObject().apply {
                put("vendor", "Creality")
                put("filament_path", JSONArray(listOf(
                    "Creality/base.json",
                    "Creality/child.json",
                )))
            }))
        }.toString()
        val importer = importer(
            loadMissing = { _, _, _ -> error("local inheritance must not fetch") },
            validateMachine = { error("filament import must not run machine validation") },
        )

        val result = importer.import(
            ByteArrayInputStream(zipOf(
                "bundle_structure.json" to manifest,
                "Creality/base.json" to base,
                "Creality/child.json" to child,
            )),
            "Recorded PLA.orca_filament",
        )

        assertEquals(1, result.filaments.size)
        val resolved = result.filaments.single()
        assertEquals(220, resolved.nozzleTemp)
        assertEquals(55, resolved.bedTemp)
        assertTrue(result.machines.isEmpty())
    }

    @Test
    fun `filament inheritance stays within its declared vendor`() = runTest {
        fun base(vendor: String, temperature: String) = JSONObject().apply {
            put("type", "filament")
            put("name", "Shared PLA base")
            put("instantiation", "false")
            put("filament_type", JSONArray(listOf("PLA")))
            put("nozzle_temperature", JSONArray(listOf(temperature)))
            put("hot_plate_temp", JSONArray(listOf("55")))
            put("vendor", vendor)
        }.toString()
        fun child(vendor: String) = JSONObject().apply {
            put("type", "filament")
            put("name", "$vendor PLA")
            put("inherits", "Shared PLA base")
        }.toString()
        val manifest = JSONObject().apply {
            put("bundle_type", "filament config bundle")
            put("filament_name", "Shared PLA")
            put("printer_vendor", JSONArray().apply {
                put(JSONObject().apply {
                    put("vendor", "Creality")
                    put("filament_path", JSONArray(listOf("Creality/base.json", "Creality/child.json")))
                })
                put(JSONObject().apply {
                    put("vendor", "Bambu Lab")
                    put("filament_path", JSONArray(listOf("Bambu/base.json", "Bambu/child.json")))
                })
            })
        }.toString()
        val importer = importer(
            loadMissing = { _, _, _ -> error("local inheritance must not fetch") },
            validateMachine = { error("filament import must not run machine validation") },
        )

        val result = importer.import(
            ByteArrayInputStream(zipOf(
                "bundle_structure.json" to manifest,
                "Creality/base.json" to base("Creality", "210"),
                "Creality/child.json" to child("Creality"),
                "Bambu/base.json" to base("Bambu Lab", "230"),
                "Bambu/child.json" to child("Bambu Lab"),
            )),
            "Shared PLA.orca_filament",
        )

        val temperatures = result.filaments.associate { it.name to it.nozzleTemp }
        assertEquals(mapOf("Creality PLA" to 210, "Bambu Lab PLA" to 230), temperatures)
    }

    @Test
    fun `foreign local base does not replace missing current-vendor base`() = runTest {
        val foreignBase = JSONObject().apply {
            put("type", "filament")
            put("name", "Shared PLA base")
            put("instantiation", "false")
            put("filament_type", JSONArray(listOf("PLA")))
            put("nozzle_temperature", JSONArray(listOf("230")))
        }.toString()
        val child = JSONObject().apply {
            put("type", "filament")
            put("name", "Creality PLA")
            put("inherits", "Shared PLA base")
        }.toString()
        val currentVendorBase = JSONObject(foreignBase).apply {
            put("vendor", "Creality")
            put("nozzle_temperature", JSONArray(listOf("210")))
        }.toString()
        val manifest = JSONObject().apply {
            put("bundle_type", "filament config bundle")
            put("filament_name", "Shared PLA")
            put("printer_vendor", JSONArray().apply {
                put(JSONObject().apply {
                    put("vendor", "Bambu Lab")
                    put("filament_path", JSONArray(listOf("Bambu/base.json")))
                })
                put(JSONObject().apply {
                    put("vendor", "Creality")
                    put("filament_path", JSONArray(listOf("Creality/child.json")))
                })
            })
        }.toString()
        val loads = mutableListOf<Triple<String, OrcaProfileType, String>>()
        val importer = importer(
            loadMissing = { vendor, type, name ->
                loads += Triple(vendor, type, name)
                currentVendorBase.takeIf { vendor == "Creality" && name == "Shared PLA base" }
            },
            validateMachine = { error("filament import must not run machine validation") },
        )

        val result = importer.import(
            ByteArrayInputStream(zipOf(
                "bundle_structure.json" to manifest,
                "Bambu/base.json" to foreignBase,
                "Creality/child.json" to child,
            )),
            "Shared PLA.orca_filament",
        )

        assertEquals(210, result.filaments.single().nozzleTemp)
        assertEquals(
            listOf(Triple("Creality", OrcaProfileType.FILAMENT, "Shared PLA base")),
            loads,
        )
    }

    @Test
    fun `metadata-only process profile rejects whole bundle before returning imports`() = runTest {
        val importer = importer(
            loadMissing = { _, _, _ -> null },
            validateMachine = { MachineProfileValidationResult.success() },
        )
        val metadataOnlyProcess = JSONObject().apply {
            put("type", "process")
            put("name", "Metadata only")
            put("inherits", "")
        }.toString()
        val manifest = JSONObject().apply {
            put("bundle_type", "printer config bundle")
            put("printer_config", JSONArray(listOf("printer/imported.json")))
            put("filament_config", JSONArray())
            put("process_config", JSONArray(listOf("process/metadata.json")))
        }.toString()

        val error = runCatching {
            importer.import(
                ByteArrayInputStream(zipOf(
                    "bundle_structure.json" to manifest,
                    "printer/imported.json" to flatMachine("Imported printer"),
                    "process/metadata.json" to metadataOnlyProcess,
                )),
                "imported.orca_printer",
            )
        }.exceptionOrNull()

        assertTrue(error?.message.orEmpty().contains("process profile", ignoreCase = true))
    }

    @Test
    fun `wrong bundle type and oversized entries are rejected`() = runTest {
        val importer = importer(
            loadMissing = { _, _, _ -> null },
            validateMachine = { MachineProfileValidationResult.success() },
        )

        val wrongType = runCatching {
            importer.import(ByteArrayInputStream(zipOf(
                "bundle_structure.json" to JSONObject().apply {
                    put("bundle_type", "printer config bundle")
                    put("printer_config", JSONArray())
                    put("filament_config", JSONArray(listOf("filament/a.json")))
                    put("process_config", JSONArray())
                }.toString(),
                "filament/a.json" to JSONObject().put("type", "filament").put("name", "a").toString(),
            )), "a.orca_printer")
        }.exceptionOrNull()
        val oversized = runCatching {
            importer(
                loadMissing = { _, _, _ -> null },
                validateMachine = { MachineProfileValidationResult.success() },
                maxEntryBytes = 32,
            ).import(
                ByteArrayInputStream(zipOf("filament/a.json" to "x".repeat(33))),
                "a.orca_filament",
            )
        }.exceptionOrNull()

        assertTrue(wrongType?.message.orEmpty().contains("machine profile"))
        assertTrue(oversized?.message.orEmpty().contains("too large"))
    }

    private fun flatMachine(name: String) = JSONObject().apply {
        put("type", "machine")
        put("name", name)
        put("vendor", "Custom")
        put("printer_model", "Custom Cartesian")
        put("printer_variant", "0.4")
        put("printer_structure", "i3")
        put("gcode_flavor", "klipper")
        put("machine_start_gcode", "G28")
        put("machine_end_gcode", "M104 S0")
        put("printable_area", JSONArray(listOf("0x0", "200x0", "200x200", "0x200")))
        put("printable_height", "200")
        put("nozzle_diameter", JSONArray(listOf("0.4")))
    }.toString()

    private fun printerManifest(path: String) = JSONObject().apply {
        put("bundle_type", "printer config bundle")
        put("printer_config", JSONArray(listOf(path)))
        put("filament_config", JSONArray())
        put("process_config", JSONArray())
    }.toString()

    private fun importer(
        loadMissing: (String, OrcaProfileType, String) -> String?,
        validateMachine: suspend (MachineProfile) -> MachineProfileValidationResult,
        maxEntryBytes: Int = 1024 * 1024,
    ) = OrcaProfileBundleImporter(
        loadMissing = loadMissing,
        validateMachine = validateMachine,
        parseFilaments = { json -> parseFilamentJson(json) },
        parseProcesses = ProcessProfileParser::parse,
        maxEntryBytes = maxEntryBytes,
    )

    private fun fixture(path: String): String =
        requireNotNull(javaClass.classLoader?.getResource(path)).readText()

    private fun zipOf(vararg entries: Pair<String, String>): ByteArray =
        ByteArrayOutputStream().also { output ->
            ZipOutputStream(output).use { zip ->
                entries.forEach { (path, text) ->
                    zip.putNextEntry(ZipEntry(path))
                    zip.write(text.toByteArray())
                    zip.closeEntry()
                }
            }
        }.toByteArray()
}
