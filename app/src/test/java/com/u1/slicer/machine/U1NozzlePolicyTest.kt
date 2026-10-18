package com.u1.slicer.machine

import org.junit.Assert.*
import org.junit.Test

class U1NozzlePolicyTest {
    @Test fun `all supported sizes produce compatible process defaults`() {
        for (diameter in U1NozzlePolicy.supportedDiameters) {
            val config = U1NozzlePolicy.defaults(diameter).toMutableMap()
            U1NozzlePolicy.finalize(config, diameter, 4)
            assertEquals(List(4) { diameter.toString() }, config["nozzle_diameter"])
            U1NozzlePolicy.validate(config, diameter)
        }
    }

    @Test fun `small nozzle replaces inherited 04 process and hardware`() {
        val config = mutableMapOf<String, Any>(
            "layer_height" to "0.2", "initial_layer_print_height" to "0.3",
            "outer_wall_line_width" to "0.42", "support_line_width" to "0.45",
            "nozzle_diameter" to listOf("0.4"),
        )
        U1NozzlePolicy.applyDefaults(config, 0.2f, 2)
        U1NozzlePolicy.finalize(config, 0.2f, 2)
        assertEquals("0.1", config["layer_height"])
        assertEquals("0.1", config["initial_layer_print_height"])
        assertEquals("0.21", config["outer_wall_line_width"])
        assertEquals("0.225", config["support_line_width"])
        assertEquals(listOf("0.04", "0.04"), config["min_layer_height"])
        assertEquals(listOf("0.16", "0.16"), config["max_layer_height"])
        assertEquals("Snapmaker U1 (0.2 nozzle) - multiplate", config["printer_settings_id"])
    }

    @Test fun `04 keeps existing process values`() {
        val config = mutableMapOf<String, Any>("layer_height" to "0.16", "outer_wall_line_width" to "0.42")
        U1NozzlePolicy.applyDefaults(config, 0.4f, 4)
        assertEquals("0.16", config["layer_height"])
        assertEquals("0.42", config["outer_wall_line_width"])
        assertEquals("0.20mm Standard @Snapmaker U1", config["print_settings_id"])
    }

    @Test fun `compatible explicit process and slicing overrides win`() {
        val config = mutableMapOf<String, Any>()
        U1NozzlePolicy.applyDefaults(config, 0.2f, 1)
        config.putAll(mapOf("layer_height" to "0.08", "initial_layer_print_height" to "0.12", "outer_wall_line_width" to "110%"))
        U1NozzlePolicy.finalize(config, 0.2f, 1)
        assertEquals("0.08", config["layer_height"])
        assertEquals("110%", config["outer_wall_line_width"])
    }

    @Test fun `imported machine diameter cannot override selected hardware`() {
        val config = U1NozzlePolicy.defaults(0.2f).toMutableMap()
        config["nozzle_diameter"] = listOf("0.4")
        U1NozzlePolicy.finalize(config, 0.2f, 1)
        assertEquals(listOf("0.2"), config["nozzle_diameter"])
    }

    @Test fun `incompatible explicit heights fail with setting named`() {
        for (key in listOf("layer_height", "initial_layer_print_height")) {
            val config = U1NozzlePolicy.defaults(0.2f).toMutableMap()
            config[key] = "0.2"
            expectFailure(key) { U1NozzlePolicy.validate(config, 0.2f) }
        }
    }

    @Test fun `incompatible imported width fails instead of silently using 04 geometry`() {
        val config = U1NozzlePolicy.defaults(0.2f).toMutableMap()
        config["outer_wall_line_width"] = "0.42"
        expectFailure("outer_wall_line_width") { U1NozzlePolicy.validate(config, 0.2f) }
    }

    @Test fun `invalid values are rejected at policy boundary`() {
        for (value in listOf("NaN", "Infinity", "bad", "-1")) {
            expectFailure("layer_height") { U1NozzlePolicy.validate(mapOf("layer_height" to value), 0.2f) }
        }
        expectFailure("diameter") { U1NozzlePolicy.defaults(0.3f) }
        expectFailure("extruder") { U1NozzlePolicy.applyDefaults(mutableMapOf(), 0.2f, 0) }
    }

    @Test fun `auto width remains valid but width below layer height does not`() {
        U1NozzlePolicy.validate(mapOf("line_width" to "0"), 0.2f)
        expectFailure("line_width") { U1NozzlePolicy.validate(mapOf("line_width" to "0.05"), 0.2f) }
    }

    @Test fun `virtual palette can contain more than four slicing extruders`() {
        val config = mutableMapOf<String, Any>()
        U1NozzlePolicy.applyDefaults(config, 0.2f, 8)
        assertEquals(List(8) { "0.2" }, config["nozzle_diameter"])
    }

    @Test fun `04 preserves existing machine layer limits`() {
        val config = mutableMapOf<String, Any>("min_layer_height" to listOf("0.08"), "max_layer_height" to listOf("0.28"))
        U1NozzlePolicy.applyDefaults(config, 0.4f, 1)
        assertEquals(listOf("0.08"), config["min_layer_height"])
        assertEquals(listOf("0.28"), config["max_layer_height"])
    }

    private fun expectFailure(message: String, action: () -> Unit) {
        try {
            action()
            fail("Expected incompatible nozzle configuration")
        } catch (error: IllegalArgumentException) {
            assertTrue(error.message.orEmpty(), error.message.orEmpty().contains(message))
        }
    }
}
