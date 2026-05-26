package com.u1.slicer.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F91: extended fields populated from OrcaSlicer filament JSON (and absent when not provided).
 */
class FilamentJsonImportExtendedTest {

    @Test
    fun bambuFormat_extractsExtendedKeys() {
        val json = """
            {
                "type": "filament",
                "name": "PLA Pro @U1",
                "filament_type": ["PLA"],
                "nozzle_temperature": ["220"],
                "nozzle_temperature_initial_layer": ["225"],
                "bed_temperature": ["60"],
                "hot_plate_temp_initial_layer": ["65"],
                "filament_flow_ratio": ["0.98"],
                "filament_max_volumetric_speed": ["18"],
                "filament_cost": ["25"],
                "fan_min_speed": ["100"],
                "fan_max_speed": ["100"],
                "overhang_fan_speed": ["100"],
                "additional_cooling_fan_speed": ["70"],
                "slow_down_layer_time": ["4"],
                "slow_down_min_speed": ["20"],
                "close_fan_the_first_x_layers": ["1"],
                "full_fan_speed_layer": ["3"],
                "enable_pressure_advance": ["1"],
                "pressure_advance": ["0.045"],
                "filament_minimal_purge_on_wipe_tower": ["15"]
            }
        """.trimIndent()
        val profiles = parseFilamentJson(json)
        assertEquals(1, profiles.size)
        val p = profiles[0]
        assertEquals(225, p.nozzleTempInitialLayer)
        assertEquals(65, p.bedTempInitialLayer)
        assertEquals(0.98f, p.flowRatio!!, 0.001f)
        assertEquals(18f, p.maxVolumetricSpeed!!, 0.001f)
        assertEquals(25f, p.filamentCost!!, 0.001f)
        assertEquals(100, p.fanMinSpeed)
        assertEquals(100, p.fanMaxSpeed)
        assertEquals(100, p.overhangFanSpeed)
        assertEquals(70, p.additionalCoolingFanSpeed)
        assertEquals(4f, p.slowDownLayerTime!!, 0.001f)
        assertEquals(20f, p.slowDownMinSpeed!!, 0.001f)
        assertEquals(1, p.closeFanFirstLayers)
        assertEquals(3, p.fullFanSpeedLayer)
        assertEquals(true, p.enablePressureAdvance)
        assertEquals(0.045f, p.pressureAdvance!!, 0.001f)
        assertEquals(15f, p.filamentMinimalPurgeOnWipeTower!!, 0.001f)
    }

    @Test
    fun bambuFormat_omittedExtendedKeys_areNull() {
        val json = """
            {
                "type": "filament",
                "name": "Plain PLA",
                "filament_type": ["PLA"],
                "nozzle_temperature": ["210"]
            }
        """.trimIndent()
        val profiles = parseFilamentJson(json)
        assertEquals(1, profiles.size)
        val p = profiles[0]
        assertNull(p.flowRatio)
        assertNull(p.maxVolumetricSpeed)
        assertNull(p.filamentCost)
        assertNull(p.fanMinSpeed)
        assertNull(p.enablePressureAdvance)
        assertNull(p.pressureAdvance)
        // Basic fields still populated.
        assertEquals(210, p.nozzleTemp)
    }

    @Test
    fun simpleFormat_extractsExtendedKeys() {
        val json = """
            [{
                "name": "Custom PLA",
                "material": "PLA",
                "nozzleTemp": 220,
                "flowRatio": 0.95,
                "maxVolumetricSpeed": 15.5,
                "fanMaxSpeed": 80,
                "enablePressureAdvance": true,
                "pressureAdvance": 0.05
            }]
        """.trimIndent()
        val profiles = parseFilamentJson(json)
        assertEquals(1, profiles.size)
        val p = profiles[0]
        assertEquals(0.95f, p.flowRatio!!, 0.001f)
        assertEquals(15.5f, p.maxVolumetricSpeed!!, 0.001f)
        assertEquals(80, p.fanMaxSpeed)
        assertEquals(true, p.enablePressureAdvance)
        assertEquals(0.05f, p.pressureAdvance!!, 0.001f)
    }

    @Test
    fun nilValue_isTreatedAsAbsent() {
        val json = """
            {
                "type": "filament",
                "name": "Nil",
                "filament_flow_ratio": ["nil"],
                "filament_max_volumetric_speed": ["12"]
            }
        """.trimIndent()
        val profiles = parseFilamentJson(json)
        assertEquals(1, profiles.size)
        assertNull(profiles[0].flowRatio)
        assertEquals(12f, profiles[0].maxVolumetricSpeed!!, 0.001f)
    }
}
