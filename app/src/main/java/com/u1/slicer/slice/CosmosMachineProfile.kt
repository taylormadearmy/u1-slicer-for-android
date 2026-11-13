package com.u1.slicer.slice

import org.json.JSONObject

/** CC1 without AFC, 0.4 mm. Geometry inherits the same ECC preset as the upstream profile.
 * Source: https://cloud.orcaslicer.com/b/3fad3c38f25f, profile version 2.4.0.2, retrieved 2026-10-23.
 * COSMOS 26.07+ intentionally emergency-stops on the stock M729/M8213 commands.
 */
object CosmosMachineProfile {
    const val NAME = "Elegoo Centauri Carbon 0.4 nozzle - Cosmos"

    val profile: GenericMachineProfile by lazy {
        GenericMachineProfile.fromJson(JSONObject(GenericMachineProfile.centauriCarbon().resolvedJson).apply {
            put("name", NAME)
            put("printer_settings_id", NAME)
            put("single_extruder_multi_material", "0")
            put("machine_start_gcode", "M104 S0 ; Stops OrcaSlicer from sending temp waits separately\n\n" +
                "M140 S0\n\nSET_PRINT_STATS_INFO TOTAL_LAYER=[total_layer_count]\n\n" +
                "PRINT_START EXTRUDER=[first_layer_temperature] BED=[first_layer_bed_temperature] CHAMBER=[chamber_temperature]")
            put("machine_end_gcode", "PRINT_END")
            put("machine_pause_gcode", "PAUSE")
            put("change_filament_gcode", "M600")
        }.toString())
    }
}
