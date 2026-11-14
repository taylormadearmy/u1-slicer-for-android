package com.u1.slicer.machine

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class OrcaProfileResolverTest {
    @Test
    fun `resolves inherits chain with child wins and inherited keys`() {
        val profiles = mapOf(
            "u1_child" to fixture("u1_child.json"),
            "fdm_u1_base" to fixture("fdm_u1_base.json"),
            "fdm_common" to fixture("fdm_common.json")
        )

        val result = OrcaProfileResolver.resolve(profiles.getValue("u1_child")) { profiles[it] }

        assertNotNull(result)
        val resolved = JSONObject(requireNotNull(result))
        assertEquals("CHILD_START", resolved.getString("machine_start_gcode"))
        assertEquals("survives", resolved.getString("base_only"))
        assertEquals("present", resolved.getString("child_only"))
        assertEquals(0.8, resolved.getDouble("retraction_length"), 0.0)
        assertFalse(resolved.has("inherits"))
        assertFalse(resolved.has("instantiation"))
        assertFalse(resolved.has("from"))
    }

    @Test
    fun `missing parent returns null`() {
        val root = """{"type":"machine","inherits":"does_not_exist"}"""

        assertNull(OrcaProfileResolver.resolve(root) { null })
    }

    @Test
    fun `cycle returns null`() {
        val profiles = mapOf(
            "a" to """{"type":"machine","inherits":"b"}""",
            "b" to """{"type":"machine","inherits":"a"}"""
        )

        assertNull(OrcaProfileResolver.resolve(profiles.getValue("a")) { profiles[it] })
    }

    @Test
    fun `depth greater than ten returns null`() {
        val profiles = (0..11).associate { index ->
            "profile_$index" to """{"type":"machine","inherits":"profile_${index + 1}"}"""
        } + ("profile_12" to """{"type":"machine"}""")

        assertNull(OrcaProfileResolver.resolve(profiles.getValue("profile_0")) { profiles[it] })
    }

    @Test
    fun `flat JSON returns itself without bookkeeping keys`() {
        val result = OrcaProfileResolver.resolve(
            """{"type":"machine","name":"flat","retraction_length":0.4,"inherits":"","instantiation":"true","from":"system"}"""
        ) { error("flat profile must not load a parent") }

        assertNotNull(result)
        val resolved = JSONObject(requireNotNull(result))
        assertEquals("flat", resolved.getString("name"))
        assertEquals(0.4, resolved.getDouble("retraction_length"), 0.0)
        assertFalse(resolved.has("inherits"))
        assertFalse(resolved.has("instantiation"))
        assertFalse(resolved.has("from"))
    }

    private fun fixture(name: String): String =
        requireNotNull(javaClass.classLoader?.getResource("machine/chain/$name")).readText()
}
