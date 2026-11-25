package com.u1.slicer.machine

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CodingErrorAction
import java.util.zip.ZipFile
import org.json.JSONArray
import org.json.JSONObject

/** Imports resolved project values, not the possibly stale per-preset files in a 3MF. */
internal object ProjectFilamentImporter {
    fun read(input: InputStream, maxMetadataBytes: Int, cacheDirectory: File? = null): String {
        val archive = File.createTempFile("project-filaments-", ".3mf", cacheDirectory)
        try {
            archive.outputStream().use { output ->
                val buffer = ByteArray(8192)
                var totalBytes = 0L
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    totalBytes += count
                    require(totalBytes <= 256L * 1024 * 1024) { "Project archive is too large." }
                    output.write(buffer, 0, count)
                }
            }
            // Orca writes stored preview entries with data descriptors, which ZipInputStream
            // cannot traverse reliably. The central directory can locate metadata directly.
            checkDirectory(archive)
            return ZipFile(archive).use { zip ->
                var selected: java.util.zip.ZipEntry? = null
                var entries = 0
                val all = zip.entries()
                while (all.hasMoreElements()) {
                    val entry = all.nextElement()
                    require(++entries <= 10_000) { "Project contains too many entries." }
                    if (entry.name == "Metadata/project_settings.config") {
                        require(selected == null) { "Project contains duplicate resolved settings." }
                        selected = entry
                    }
                }
                val entry = requireNotNull(selected) {
                    "Project has no resolved filament settings. Save an Orca project 3MF with filament settings."
                }
                val bytes = zip.getInputStream(entry).use { metadata -> readMetadata(metadata, maxMetadataBytes) }
                val metadata = Charsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString()
                snapshots(metadata)
            }
        } finally {
            archive.delete()
        }
    }

    /** Bound the directory before ZipFile allocates entries from it. */
    private fun checkDirectory(archive: File) = RandomAccessFile(archive, "r").use { file ->
        val tail = ByteArray(minOf(file.length(), 65_557L).toInt())
        file.seek(file.length() - tail.size)
        file.readFully(tail)
        val footer = ByteBuffer.wrap(tail).order(ByteOrder.LITTLE_ENDIAN)
        val end = (tail.size - 22 downTo 0).firstOrNull { offset ->
            footer.getInt(offset) == 0x06054b50 &&
                offset + 22 + (footer.getShort(offset + 20).toInt() and 0xffff) == tail.size
        } ?: throw IllegalArgumentException("Project archive has no valid ZIP directory.")
        val disk = footer.getShort(end + 4).toInt() and 0xffff
        val directoryDisk = footer.getShort(end + 6).toInt() and 0xffff
        val diskCount = footer.getShort(end + 8).toInt() and 0xffff
        val count = footer.getShort(end + 10).toInt() and 0xffff
        require(count <= 10_000) { "Project contains too many entries." }
        require(disk == 0 && directoryDisk == 0 && diskCount == count) {
            "Project archive uses an unsupported ZIP layout. Re-save it as a standard 3MF."
        }
        val size = footer.getInt(end + 12).toLong() and 0xffffffffL
        val start = footer.getInt(end + 16).toLong() and 0xffffffffL
        val endPosition = file.length() - tail.size + end
        require(size <= 16L * 1024 * 1024 && start + size == endPosition) {
            "Project ZIP directory is invalid or too large."
        }
        var position = start
        var observed = 0
        val header = ByteArray(46)
        while (position < start + size) {
            require(++observed <= count && position + header.size <= start + size) {
                "Project ZIP directory entry count is invalid."
            }
            file.seek(position)
            file.readFully(header)
            val entry = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
            require(entry.getInt(0) == 0x02014b50) { "Project ZIP directory is malformed." }
            position += 46L + (entry.getShort(28).toInt() and 0xffff) +
                (entry.getShort(30).toInt() and 0xffff) + (entry.getShort(32).toInt() and 0xffff)
        }
        require(position == start + size && observed == count) { "Project ZIP directory length is invalid." }
    }

    private fun readMetadata(input: InputStream, maxBytes: Int): ByteArray {
        return ByteArrayOutputStream().use { output ->
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(output.size() + count <= maxBytes) { "Project settings are too large." }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
    }

    internal fun snapshots(metadata: String): String {
        val project = JSONObject(metadata)
        val names = project.optJSONArray("filament_settings_id")
            ?: throw IllegalArgumentException("Project has no filament preset names.")
        val count = names.length()
        require(count in 1..256) { "Project must contain between 1 and 256 filaments." }
        val variants = project.optJSONArray("filament_extruder_variant")
        val standard = variants?.let { values ->
            (0 until values.length()).filter { values.optString(it) == "Direct Drive Standard" }.also {
                require(it.size == count) {
                    "Project requires exactly one Direct Drive Standard variant per filament."
                }
            }
        }
        val profiles = JSONArray()
        val usedNames = mutableSetOf<String>()
        repeat(count) { index ->
            val name = names.optString(index).trim()
            require(name.isNotEmpty() && name !in setOf("nil", "null")) { "Project has an unnamed filament." }
            var importedName = name
            while (!usedNames.add(importedName)) importedName += " (project filament ${index + 1})"
            val profile = JSONObject().put("type", "filament").put("name", importedName)
            for (key in FILAMENT_KEYS) {
                val value = when (val raw = project.opt(key)) {
                    null, JSONObject.NULL -> null
                    is JSONArray -> when (raw.length()) {
                        1 -> raw.optString(0, "null")
                        count -> raw.optString(index, "null")
                        variants?.length() -> raw.optString(requireNotNull(standard)[index], "null")
                        else -> throw IllegalArgumentException("Project filament setting '$key' has inconsistent array lengths.")
                    }
                    else -> raw.toString()
                }?.trim()?.takeIf { it.isNotEmpty() && it != "nil" }
                if (value != null) profile.put(key, JSONArray().put(value))
            }
            require(profile.has("filament_type") && profile.has("nozzle_temperature") &&
                listOf("hot_plate_temp", "textured_plate_temp", "bed_temperature").any(profile::has)) {
                "Project filament '$name' lacks resolved material or temperature settings."
            }
            require(profile.getJSONArray("filament_type").getString(0) != "null") {
                "Project filament '$name' lacks a resolved material."
            }
            for ((key, range) in TEMPERATURE_RANGES) {
                val value = profile.optJSONArray(key)?.optString(0) ?: continue
                require(value.toIntOrNull() in range) {
                    "Project filament '$name' has an invalid temperature for '$key'."
                }
            }
            profile.optJSONArray("filament_colour")?.let { profile.put("default_filament_colour", it) }
            profiles.put(profile)
        }
        return profiles.toString()
    }

    private val FILAMENT_KEYS = setOf(
        "filament_type", "filament_settings_id", "filament_colour", "default_filament_colour",
        "nozzle_temperature", "nozzle_temperature_initial_layer", "hot_plate_temp",
        "hot_plate_temp_initial_layer", "textured_plate_temp", "textured_plate_temp_initial_layer",
        "bed_temperature", "bed_temperature_initial_layer", "filament_retraction_length",
        "filament_retraction_speed", "filament_flow_ratio", "filament_max_volumetric_speed",
        "filament_cost", "filament_density", "fan_min_speed", "fan_max_speed", "overhang_fan_speed",
        "additional_cooling_fan_speed", "slow_down_layer_time", "slow_down_min_speed",
        "close_fan_the_first_x_layers", "full_fan_speed_layer", "enable_pressure_advance",
        "pressure_advance", "filament_minimal_purge_on_wipe_tower", "chamber_temperature",
    )

    private val TEMPERATURE_RANGES = mapOf(
        "nozzle_temperature" to 100..400, "nozzle_temperature_initial_layer" to 100..400,
        "hot_plate_temp" to 0..150, "hot_plate_temp_initial_layer" to 0..150,
        "textured_plate_temp" to 0..150, "textured_plate_temp_initial_layer" to 0..150,
        "bed_temperature" to 0..150, "bed_temperature_initial_layer" to 0..150,
        "chamber_temperature" to 0..120,
    )
}
