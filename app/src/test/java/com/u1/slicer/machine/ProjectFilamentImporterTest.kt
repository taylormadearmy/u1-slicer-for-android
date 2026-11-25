package com.u1.slicer.machine

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ProjectFilamentImporterTest {
    @Test
    fun `legacy project keeps per filament values and broadcasts scalars`() {
        val source = project().put("chamber_temperature", "35")
        val profiles = JSONArray(ProjectFilamentImporter.snapshots(source.toString()))
        assertEquals("230", profiles.getJSONObject(1).getJSONArray("nozzle_temperature").getString(0))
        assertEquals("35", profiles.getJSONObject(1).getJSONArray("chamber_temperature").getString(0))
        assertTrue(!profiles.getJSONObject(0).has("machine_start_gcode"))
        assertTrue(!profiles.getJSONObject(0).has("inherits"))
    }

    @Test
    fun `standard selection follows each filament even when variant counts differ`() {
        val source = project()
            .put("filament_extruder_variant", JSONArray(listOf("Direct Drive High Flow", "Direct Drive Standard", "Direct Drive Standard")))
            .put("nozzle_temperature", JSONArray(listOf("280", "215", "235")))
        val profiles = JSONArray(ProjectFilamentImporter.snapshots(source.toString()))
        assertEquals("215", profiles.getJSONObject(0).getJSONArray("nozzle_temperature").getString(0))
        assertEquals("235", profiles.getJSONObject(1).getJSONArray("nozzle_temperature").getString(0))
    }

    @Test
    fun `missing standard variant rejects ambiguous project`() {
        val source = project().put("filament_extruder_variant", JSONArray(listOf("Direct Drive Standard", "Bowden Standard")))
        assertFailure("exactly one", source)
    }

    @Test
    fun `inconsistent per filament arrays reject entire import`() {
        assertFailure("inconsistent array lengths", project().put("filament_flow_ratio", JSONArray(listOf("0.9", "1", "1.1"))))
    }

    @Test
    fun `unresolved project cannot create default temperature profiles`() {
        val source = project().apply { remove("nozzle_temperature") }
        assertFailure("lacks resolved", source)
    }

    @Test
    fun `duplicate preset names remain distinguishable in library`() {
        val profiles = JSONArray(ProjectFilamentImporter.snapshots(project()
            .put("filament_settings_id", JSONArray(listOf("PLA", "PLA"))).toString()))
        assertEquals("PLA", profiles.getJSONObject(0).getString("name"))
        assertEquals("PLA (project filament 2)", profiles.getJSONObject(1).getString("name"))
    }

    @Test
    fun `nonnumeric nozzle temperature cannot become a material default`() {
        assertFailure("invalid temperature", project().put("nozzle_temperature", "bad"))
    }

    @Test
    fun `nonnumeric bed and null temperatures are rejected`() {
        assertFailure("invalid temperature", project().put("hot_plate_temp", "bad"))
        assertFailure("invalid temperature", project().put("nozzle_temperature", JSONArray().put(JSONObject.NULL).put("230")))
        assertFailure("invalid temperature", project().put("nozzle_temperature_initial_layer", "NaN"))
    }

    @Test
    fun `project colour overrides preset default colour`() {
        val profiles = JSONArray(ProjectFilamentImporter.snapshots(project()
            .put("default_filament_colour", "#FFFFFF")
            .put("filament_colour", JSONArray(listOf("#123456", "#654321"))).toString()))
        assertEquals("#123456", profiles.getJSONObject(0).getJSONArray("default_filament_colour").getString(0))
        assertEquals("#654321", profiles.getJSONObject(1).getJSONArray("default_filament_colour").getString(0))
    }

    @Test
    fun `nil temperatures cannot create default temperature profiles`() {
        assertFailure("lacks resolved", project().put("nozzle_temperature", JSONArray(listOf("nil", "230"))))
    }

    @Test
    fun `oversized advertised directory is rejected before ZipFile opens it`() {
        val bytes = zip("Metadata/project_settings.config" to project().toString())
        java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            .putShort(bytes.size - 12, 10_001.toShort())
        val error = runCatching { ProjectFilamentImporter.read(ByteArrayInputStream(bytes), 10000) }.exceptionOrNull()
        assertTrue(error?.message.orEmpty().contains("too many entries"))
    }

    @Test
    fun `lying directory count is rejected before ZipFile opens it`() {
        val bytes = zip("Metadata/project_settings.config" to project().toString())
        java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            .putShort(bytes.size - 14, 0).putShort(bytes.size - 12, 0)
        val error = runCatching { ProjectFilamentImporter.read(ByteArrayInputStream(bytes), 10000) }.exceptionOrNull()
        assertTrue(error?.message.orEmpty().contains("entry count is invalid"))
        val valid = zip("Metadata/project_settings.config" to project().toString())
        val withGap = valid.copyOfRange(0, valid.size - 22) + byteArrayOf(0, 0, 0, 0) + valid.copyOfRange(valid.size - 22, valid.size)
        val gapError = runCatching { ProjectFilamentImporter.read(ByteArrayInputStream(withGap), 10000) }.exceptionOrNull()
        assertTrue(gapError?.message.orEmpty().contains("directory is invalid"))
    }

    @Test
    fun `temporary archive is deleted after success and metadata rejection`() {
        val directory = java.nio.file.Files.createTempDirectory("project-import-test").toFile()
        try {
            val bytes = zip("Metadata/project_settings.config" to project().toString())
            ProjectFilamentImporter.read(ByteArrayInputStream(bytes), 10000, directory)
            assertEquals(0, directory.listFiles()!!.size)
            assertTrue(runCatching { ProjectFilamentImporter.read(ByteArrayInputStream(bytes), 16, directory) }.isFailure)
            assertEquals(0, directory.listFiles()!!.size)
        } finally {
            directory.delete()
        }
    }

    @Test
    fun `geometry is ignored and archive settings size is bounded`() {
        val bytes = zip("3D/3dmodel.model" to "geometry", "Metadata/project_settings.config" to project().toString())
        assertEquals(2, JSONArray(ProjectFilamentImporter.read(ByteArrayInputStream(bytes), 10000)).length())
        val error = runCatching { ProjectFilamentImporter.read(ByteArrayInputStream(bytes), 16) }.exceptionOrNull()
        assertTrue(error?.message.orEmpty().contains("too large"))
    }

    @Test
    fun `mesh only project explains resolved settings requirement`() {
        val error = runCatching {
            ProjectFilamentImporter.read(ByteArrayInputStream(zip("3D/3dmodel.model" to "geometry")), 10000)
        }.exceptionOrNull()
        assertTrue(error?.message.orEmpty().contains("no resolved filament settings"))
    }

    private fun project() = JSONObject()
        .put("filament_settings_id", JSONArray(listOf("PLA", "CoPE")))
        .put("filament_type", JSONArray(listOf("PLA", "PLA")))
        .put("nozzle_temperature", JSONArray(listOf("210", "230")))
        .put("hot_plate_temp", JSONArray(listOf("55", "60")))
        .put("machine_start_gcode", "G28")
        .put("inherits", "Not a filament parent")

    private fun assertFailure(message: String, source: JSONObject) {
        val error = runCatching { ProjectFilamentImporter.snapshots(source.toString()) }.exceptionOrNull()
        assertTrue(error?.message.orEmpty(), error?.message.orEmpty().contains(message))
    }

    private fun zip(vararg entries: Pair<String, String>) = ByteArrayOutputStream().also { output ->
        ZipOutputStream(output).use { zip ->
            entries.forEach { (path, text) ->
                zip.putNextEntry(ZipEntry(path))
                zip.write(text.toByteArray())
                zip.closeEntry()
            }
        }
    }.toByteArray()
}
