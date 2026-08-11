package com.u1.slicer.bambu

import com.u1.slicer.slice.SlicerTarget
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BambuImportedConfigComposerTest {

    @Test
    fun `dragon scale safe process and filament values survive x1c source retargeted to h2d`() {
        val result = BambuImportedConfigComposer.compose(
            target = SlicerTarget.BambuH2D,
            sourceConfig = dragonScaleSource(),
        )

        assertEquals("0.15", result.config["elefant_foot_compensation"])
        assertEquals("0.2", result.config["initial_layer_print_height"])
        assertEquals("Textured PEI Plate", result.config["curr_bed_type"])
        assertEquals(listOf("55", "55"), result.config["textured_plate_temp"])
        assertEquals(listOf("0.98", "0.985"), result.config["filament_flow_ratio"])
        assertEquals(listOf("1", "1"), result.config["reduce_fan_stop_start_freq"])
        assertEquals(listOf("100", "100"), result.config["fan_cooling_layer_time"])
        assertEquals(listOf("GFA00", "GFA05"), result.config["filament_ids"])
        assertEquals(listOf("1", "1"), result.config["filament_map"])
        assertEquals("Bambu Lab H2D", result.config["printer_model"])
        assertEquals("O1D", result.config["printer_model_id"])
        assertEquals("0x0,350x0,350x320,0x320", result.config["printable_area"])
        assertFalse(result.config.values.any { it.toString().contains("fixture sentinel") })
        assertEquals(BambuImportedConfigComposer.Disposition.SOURCE_SAFE,
            result.provenance.getValue("elefant_foot_compensation").disposition)
        assertEquals(BambuImportedConfigComposer.Disposition.SOURCE_REJECTED_TARGET_REPLACED,
            result.provenance.getValue("printer_model").disposition)
    }

    @Test
    fun `foreign source macros and source geometry never survive retargeting`() {
        val result = BambuImportedConfigComposer.compose(
            SlicerTarget.BambuP1S,
            mapOf(
                "printer_model" to "Bambu Lab X1 Carbon",
                "printable_area" to "0x0,256x0,256x256,0x256",
                "machine_start_gcode" to "M1005 I put foreign macros here",
                "time_lapse_gcode" to "M971 S11 C11 O0",
                "filament_start_gcode" to listOf("M142 P1 R35"),
            ),
        )

        assertEquals("Bambu Lab P1S", result.config["printer_model"])
        assertEquals("0x0,256x0,256x256,0x256", result.config["printable_area"])
        assertFalse(result.config.values.any { it.toString().contains("foreign macros") })
        assertFalse(result.config.values.any { it.toString().contains("M971") })
        assertFalse(result.config.values.any { it.toString().contains("M142") })
        assertEquals(BambuImportedConfigComposer.Disposition.SOURCE_REJECTED,
            result.provenance.getValue("time_lapse_gcode").disposition)
    }

    @Test
    fun `explicit safe override wins but cannot override firmware safety`() {
        val result = BambuImportedConfigComposer.compose(
            SlicerTarget.BambuX1C,
            sourceConfig = mapOf("initial_layer_print_height" to "0.20", "machine_target" to "SNAPMAKER_U1"),
            explicitOverrides = mapOf(
                "initial_layer_print_height" to "0.28",
                "machine_start_gcode" to "PRINT_START",
                "gcode_flavor" to "klipper",
            ),
        )

        assertEquals("0.28", result.config["initial_layer_print_height"])
        assertEquals(BambuImportedConfigComposer.Disposition.EXPLICIT_OVERRIDE,
            result.provenance.getValue("initial_layer_print_height").disposition)
        assertEquals("marlin", result.config["gcode_flavor"])
        assertEquals("BAMBU_X1C", result.config["machine_target"])
        assertEquals(BambuImportedConfigComposer.Disposition.FIRMWARE_SAFETY,
            result.provenance.getValue("gcode_flavor").disposition)
        assertFalse(result.config["machine_start_gcode"].toString().contains("PRINT_START"))
    }

    @Test
    fun `plain stl composition uses official bambu defaults never u1 defaults`() {
        val result = BambuImportedConfigComposer.compose(SlicerTarget.BambuA1Mini)

        assertEquals("Bambu Lab A1 mini", result.config["printer_model"])
        assertEquals("0x0,180x0,180x180,0x180", result.config["printable_area"])
        assertEquals("180", result.config["printable_height"])
        assertEquals("BAMBU_A1_MINI", result.config["machine_target"])
        assertEquals("0.2", result.config["initial_layer_print_height"])
        assertEquals("0.15", result.config["elefant_foot_compensation"])
        assertEquals(listOf("55"), result.config["textured_plate_temp"])
        assertEquals(listOf("0.98"), result.config["filament_flow_ratio"])
        assertEquals(listOf("1"), result.config["reduce_fan_stop_start_freq"])
        assertEquals(listOf("100"), result.config["fan_cooling_layer_time"])
        assertFalse(result.config.values.any { it.toString().contains("Snapmaker") })
    }

    @Test
    fun `every target retains its official identity envelope and h2d nozzle metadata`() {
        BAMBU_MACHINE_PROFILES.forEach { (target, machine) ->
            val result = BambuImportedConfigComposer.compose(target)
            assertEquals(machine.printerModel, result.config["printer_model"])
            assertEquals(machine.printerModelId, result.config["printer_model_id"])
            assertEquals(machine.maxPrintHeight.toInt().toString(), result.config["printable_height"])
        }
        val h2d = BambuImportedConfigComposer.compose(SlicerTarget.BambuH2D)
        assertEquals(listOf(130, 133, 133, 145, 148, 148, 148), h2d.config["nozzle_volume"])
        assertEquals("2", h2d.config["master_extruder_id"])
        assertEquals(2, (h2d.config["nozzle_printable_area"] as List<*>).size)
    }

    private fun dragonScaleSource(): Map<String, Any> {
        val text = requireNotNull(
            javaClass.getResourceAsStream("/bambu/dragon-scale-imported-profile.json")
        ).bufferedReader().use { it.readText() }
        return jsonObjectToMap(JSONObject(text))
    }

    private fun jsonObjectToMap(json: JSONObject): Map<String, Any> = json.keys().asSequence()
        .associateWith { key -> jsonValue(json.get(key)) }

    private fun jsonValue(value: Any): Any = when (value) {
        is JSONObject -> jsonObjectToMap(value)
        is JSONArray -> (0 until value.length()).map { jsonValue(value.get(it)) }
        else -> value
    }
}
