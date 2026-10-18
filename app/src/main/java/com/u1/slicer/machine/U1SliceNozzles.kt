package com.u1.slicer.machine

import com.u1.slicer.data.U1NozzleConfiguration
import com.u1.slicer.gcode.GcodeToolSpace
import java.io.File
import java.io.RandomAccessFile

/** Stored in the G-code itself so Save, Share and historical jobs keep their requirements. */
data class U1SliceNozzles(
    val diameterMm: Float,
    val tools: List<Int>,
    val toolSpace: GcodeToolSpace,
    val legacy: Boolean = false,
) {
    init {
        require(diameterMm in U1NozzleConfiguration.SUPPORTED_DIAMETERS_MM)
        require(tools.isNotEmpty() && tools.all { it >= 0 })
    }

    fun validate(hardware: U1NozzleConfiguration, mapping: List<Int>?) {
        if (legacy) {
            require(hardware.diametersMm.all { it == diameterMm }) {
                "This older U1 job has no saved nozzle mapping. Slice it again after changing installed nozzle sizes."
            }
            return
        }
        val slots = tools.map { tool ->
            mapping?.getOrNull(tool) ?: tool
        }
        require(slots.all { it in 0..3 && hardware.diameterMm(it) == diameterMm }) {
            "This G-code requires $diameterMm mm nozzles. Choose matching toolheads or slice again for the installed nozzle size."
        }
    }

    fun remap(mapping: Map<Int, Int>): U1SliceNozzles = copy(
        tools = if (legacy) (0..3).toList() else tools.map { mapping[it] ?: it }.distinct().sorted(),
        toolSpace = GcodeToolSpace.PHYSICAL,
        legacy = false,
    )

    fun toGcodeLine(): String = PREFIX + "$diameterMm|${toolSpace.name}|${tools.distinct().sorted().joinToString(",")}"

    companion object {
        const val PREFIX = "; u1_nozzle_requirement = "

        fun parse(line: String): U1SliceNozzles? {
            if (!line.startsWith(PREFIX)) return null
            val parts = line.removePrefix(PREFIX).trim().split('|')
            require(parts.size == 3) { "Invalid U1 nozzle requirement" }
            return U1SliceNozzles(
                diameterMm = parts[0].toFloat(),
                toolSpace = GcodeToolSpace.valueOf(parts[1]),
                tools = parts[2].split(',').map(String::toInt),
            )
        }

        fun read(file: File): U1SliceNozzles? {
            if (!file.isFile) return null
            // App metadata is appended after Orca's footer. Read a bounded tail even for huge jobs.
            return RandomAccessFile(file, "r").use { input ->
                val size = minOf(input.length(), 131072L).toInt()
                input.seek(input.length() - size)
                val bytes = ByteArray(size)
                input.readFully(bytes)
                val tail = bytes.toString(Charsets.UTF_8)
                tail.lineSequence().lastOrNull { it.startsWith(PREFIX) }?.let(::parse) ?: readLegacyU1(tail)
            }
        }

        private fun readLegacyU1(tail: String): U1SliceNozzles? {
            val settings = tail.lineSequence().filter { it.startsWith("; ") && " = " in it }
                .associate { it.removePrefix("; ").substringBefore(" = ") to it.substringAfter(" = ").trim() }
            if (settings["printer_settings_id"]?.startsWith("Snapmaker U1") != true &&
                settings["printer_model"] != "Snapmaker U1") return null
            val nozzles = settings["nozzle_diameter"]?.split(',', ';')?.map { it.trim().toFloat() } ?: return null
            val diameters = nozzles.distinct()
            require(diameters.size == 1) { "This legacy U1 job uses differing nozzle sizes. Slice it again before sending." }
            // Older remappers changed body tools without rewriting usage statistics.
            // Their exact physical binding cannot be recovered from the footer alone.
            return U1SliceNozzles(diameters.single(), (0..3).toList(), GcodeToolSpace.CANONICAL, legacy = true)
        }
    }
}

/** Physical heads may differ, but this slicer currently uses a uniform diameter per job. */
fun resolveU1SliceDiameter(hardware: U1NozzleConfiguration, usedPhysicalSlots: Collection<Int>): Float {
    require(usedPhysicalSlots.isNotEmpty()) { "Choose a toolhead before slicing." }
    val diameters = usedPhysicalSlots.map(hardware::diameterMm).distinct()
    require(diameters.size == 1) {
        "All toolheads used by this print, including supports and ColorMix components, must have the same nozzle size. Choose matching toolheads."
    }
    return diameters.single()
}
