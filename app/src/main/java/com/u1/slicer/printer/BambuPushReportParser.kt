package com.u1.slicer.printer

import com.u1.slicer.network.ExtruderStatus
import com.u1.slicer.network.FilamentRouting
import com.u1.slicer.network.FilamentSlot
import com.u1.slicer.network.FilamentTrackSwitchStatus
import com.u1.slicer.network.NozzleSide
import com.u1.slicer.network.NozzleHardwareStatus
import com.u1.slicer.network.PrinterStatus
import org.json.JSONArray
import org.json.JSONObject

data class BambuPushReport(
    val status: PrinterStatus,
    val filamentSlots: List<FilamentSlot>,
    val hasStatus: Boolean = true,
    val hasFilamentSlots: Boolean = true,
)

object BambuPushReportParser {

    fun parse(json: String?): BambuPushReport {
        if (json.isNullOrBlank()) return disconnected()
        return try {
            val root = JSONObject(json)
            val print = root.optJSONObject("print")
            val ams = root.optJSONObject("ams")
                ?: print?.optJSONObject("ams")
            val virtualTrays = virtualTrayObjects(root, print, ams)
            val filamentTrackSwitch = parseFilamentTrackSwitch(root, print)
            val reportedNozzleSideByAmsId = parseAmsNozzleSides(root, print, ams)
            val hasStatus = hasStatusPayload(print)
            val hasFilamentSlots = ams?.optJSONArray("ams") != null || virtualTrays.isNotEmpty()
            val status = parseStatus(print, filamentTrackSwitch)
            val isDualNozzle = status.extruders.size >= 2 ||
                reportedExtruderCount(print) >= 2 ||
                reportedNozzleSideByAmsId.containsValue(NozzleSide.LEFT) ||
                virtualTrays.size >= 2 ||
                filamentTrackSwitch.installed
            // Bits 8..11 are also zero on many single-nozzle AMS reports. Do not
            // turn that ordinary default into a misleading "right nozzle" badge.
            val nozzleSideByAmsId = if (isDualNozzle) reportedNozzleSideByAmsId else emptyMap()
            BambuPushReport(
                status = status,
                filamentSlots = parseFilamentSlots(
                    ams = ams,
                    virtualTrays = virtualTrays,
                    nozzleSideByAmsId = nozzleSideByAmsId,
                    filamentTrackSwitch = filamentTrackSwitch,
                    isDualNozzle = isDualNozzle,
                ),
                hasStatus = hasStatus,
                hasFilamentSlots = hasFilamentSlots,
            )
        } catch (_: Exception) {
            disconnected()
        }
    }

    private fun parseStatus(
        print: JSONObject?,
        filamentTrackSwitch: FilamentTrackSwitchStatus,
    ): PrinterStatus {
        if (print == null) {
            return PrinterStatus(
                state = "disconnected",
                progress = 0f,
                filamentTrackSwitch = filamentTrackSwitch,
            )
        }
        val extruders = parseExtruders(print)
        val firstExtruder = extruders.firstOrNull()
        return PrinterStatus(
            state = mapState(print.optString("gcode_state", "")),
            progress = (print.optDouble("mc_percent", 0.0) / 100.0).toFloat(),
            filename = print.optString("subtask_name", ""),
            printDuration = 0f,
            filamentUsed = 0f,
            nozzleTemp = firstExtruder?.temp ?: scalarOrArrayFirst(print, "nozzle_temper"),
            nozzleTarget = firstExtruder?.target ?: scalarOrArrayFirst(print, "nozzle_target_temper"),
            bedTemp = print.optDouble("bed_temper", 0.0).toFloat(),
            bedTarget = print.optDouble("bed_target_temper", 0.0).toFloat(),
            extruders = extruders,
            nozzles = parseNozzleHardware(print),
            filamentTrackSwitch = filamentTrackSwitch,
        )
    }

    /** Mirrors the field variants accepted by current Bambuddy. */
    private fun parseNozzleHardware(print: JSONObject): List<NozzleHardwareStatus> {
        val nozzles = linkedMapOf<Int, NozzleHardwareStatus>()

        fun merge(index: Int, type: String? = null, diameter: Float? = null) {
            val previous = nozzles[index] ?: NozzleHardwareStatus(index)
            nozzles[index] = previous.copy(
                type = type?.takeIf { it.isNotBlank() } ?: previous.type,
                diameter = diameter?.takeIf { it > 0f } ?: previous.diameter,
            )
        }

        merge(
            index = 0,
            type = print.optString("nozzle_type").ifBlank { null },
            diameter = print.optPositiveFloat("nozzle_diameter"),
        )
        merge(
            index = 0,
            type = print.optString("left_nozzle_type").ifBlank { null },
            diameter = print.optPositiveFloat("left_nozzle_diameter"),
        )
        merge(
            index = 1,
            type = print.optString("right_nozzle_type").ifBlank { null },
            diameter = print.optPositiveFloat("right_nozzle_diameter"),
        )
        merge(
            index = 1,
            type = print.optString("nozzle_type_2").ifBlank { null },
            diameter = print.optPositiveFloat("nozzle_diameter_2"),
        )

        val info = print.optJSONObject("device")
            ?.optJSONObject("nozzle")
            ?.optJSONArray("info")
        if (info != null) {
            for (position in 0 until info.length()) {
                val nozzle = info.optJSONObject(position) ?: continue
                val index = nozzle.optIntValue("id") ?: position
                // IDs 16+ are H2C rack positions, not installed hotends.
                if (index !in 0..1) continue
                merge(
                    index = index,
                    type = nozzle.optString("type").ifBlank { null },
                    diameter = nozzle.optPositiveFloat("diameter"),
                )
            }
        }
        return nozzles.values.filter { it.type.isNotBlank() || it.diameter != null }.sortedBy { it.index }
    }

    private fun hasStatusPayload(print: JSONObject?): Boolean {
        if (print == null) return false
        return print.optString("gcode_state", "").isNotBlank()
    }

    private fun parseExtruders(print: JSONObject): List<ExtruderStatus> {
        val temps = readFloatArray(print, "nozzle_temper")
        val targets = readFloatArray(print, "nozzle_target_temper")
        if (temps.isEmpty() && targets.isEmpty()) return emptyList()
        val count = maxOf(temps.size, targets.size)
        return (0 until count).map { index ->
            val temp = temps.getOrElse(index) { 0f }
            val target = targets.getOrElse(index) { 0f }
            ExtruderStatus(
                index = index,
                temp = temp,
                target = target,
                active = target > 0f,
            )
        }
    }

    private fun reportedExtruderCount(print: JSONObject?): Int =
        print
            ?.optJSONObject("device")
            ?.optJSONObject("extruder")
            ?.optJSONArray("info")
            ?.length()
            ?: 0

    private fun parseFilamentSlots(
        ams: JSONObject?,
        virtualTrays: List<JSONObject>,
        nozzleSideByAmsId: Map<Int, NozzleSide>,
        filamentTrackSwitch: FilamentTrackSwitchStatus,
        isDualNozzle: Boolean,
    ): List<FilamentSlot> {
        val units = ams?.optJSONArray("ams")
        val slots = mutableListOf<FilamentSlot>()
        if (units != null) {
            val regularUnitCount = (0 until units.length()).count { unitIndex ->
                val unitId = units.optJSONObject(unitIndex)?.optProtocolId("id", unitIndex) ?: unitIndex
                unitId < AMS_HT_ID_START
            }
            for (unitIndex in 0 until units.length()) {
                val unit = units.optJSONObject(unitIndex) ?: continue
                val unitId = unit.optProtocolId("id", unitIndex)
                val trays = unit.optJSONArray("tray") ?: continue
                for (trayIndex in 0 until trays.length()) {
                    val tray = trays.optJSONObject(trayIndex) ?: continue
                    val trayId = tray.optProtocolId("id", trayIndex)
                    slots += filamentSlot(
                        tray = tray,
                        index = routeIndex(unitId, trayId),
                        label = unitLabel(unitId, trayId, regularUnitCount),
                        nozzleSide = nozzleSideByAmsId[unitId] ?: NozzleSide.UNKNOWN,
                        routing = when {
                            filamentTrackSwitch.installed -> FilamentRouting.SWITCHABLE
                            nozzleSideByAmsId[unitId] != null -> FilamentRouting.FIXED
                            else -> FilamentRouting.UNKNOWN
                        },
                    )
                }
            }
        }

        virtualTrays.forEachIndexed { virtualIndex, tray ->
            val routeId = tray.optProtocolId("id", VIRTUAL_TRAY_ID_START + virtualIndex)
            val nozzleSide = externalNozzleSide(routeId, isDualNozzle)
            slots += filamentSlot(
                tray = tray,
                index = routeId,
                label = virtualTrayLabel(routeId, virtualTrays.size),
                nozzleSide = nozzleSide,
                routing = if (nozzleSide != NozzleSide.UNKNOWN) {
                    FilamentRouting.FIXED
                } else {
                    FilamentRouting.UNKNOWN
                },
            )
        }
        return slots.distinctBy { it.index }.sortedBy { it.index }
    }

    private fun filamentSlot(
        tray: JSONObject,
        index: Int,
        label: String,
        nozzleSide: NozzleSide,
        routing: FilamentRouting,
    ): FilamentSlot {
        val materialType = tray.optString("tray_type", "").trim()
        return FilamentSlot(
            index = index,
            label = label,
            color = normalizeColor(tray.optString("tray_color", "")),
            loaded = materialType.isNotBlank(),
            // An empty Bambu tray deliberately has no tray_type. Preserve that
            // distinction so users cannot mistake it for an unknown filament.
            materialType = materialType.ifBlank { "Empty" },
            subType = tray.optString("tray_sub_brands", "").trim(),
            nozzleSide = nozzleSide,
            routing = routing,
        )
    }

    /**
     * Resolve fixed AMS-to-nozzle topology.
     *
     * Newer integrations may surface `ams_extruder_map` directly. The printer
     * itself reports the same information in each AMS unit's hex `info` field:
     * bits 8..11 are 0 for the right/main nozzle, 1 for the left/deputy nozzle,
     * and 0xE when the assignment is uninitialised (notably with FTS).
     */
    private fun parseAmsNozzleSides(
        root: JSONObject,
        print: JSONObject?,
        ams: JSONObject?,
    ): Map<Int, NozzleSide> {
        val result = linkedMapOf<Int, NozzleSide>()
        listOfNotNull(root, print, ams).forEach { container ->
            parseAmsExtruderMap(container.opt("ams_extruder_map")).forEach { (amsId, side) ->
                result[amsId] = side
            }
        }

        val units = ams?.optJSONArray("ams") ?: return result
        for (unitIndex in 0 until units.length()) {
            val unit = units.optJSONObject(unitIndex) ?: continue
            val amsId = unit.optProtocolId("id", unitIndex)
            if (amsId in result) continue
            val rawInfo = unit.opt("info") ?: continue
            val info = rawInfo.toString().toLongOrNull(radix = 16) ?: continue
            nozzleSideFromExtruderId(((info shr AMS_EXTRUDER_SHIFT) and AMS_EXTRUDER_MASK).toInt())
                .takeUnless { it == NozzleSide.UNKNOWN }
                ?.let { result[amsId] = it }
        }
        return result
    }

    private fun parseAmsExtruderMap(value: Any?): Map<Int, NozzleSide> {
        val objectValue = when (value) {
            is JSONObject -> value
            is String -> runCatching { JSONObject(value) }.getOrNull()
            else -> null
        } ?: return emptyMap()
        val result = linkedMapOf<Int, NozzleSide>()
        val keys = objectValue.keys()
        while (keys.hasNext()) {
            val rawAmsId = keys.next()
            val amsId = rawAmsId.toIntOrNull() ?: continue
            val extruderId = objectValue.optIntValue(rawAmsId) ?: continue
            val side = nozzleSideFromExtruderId(extruderId)
            if (side != NozzleSide.UNKNOWN) result[amsId] = side
        }
        return result
    }

    private fun parseFilamentTrackSwitch(
        root: JSONObject,
        print: JSONObject?,
    ): FilamentTrackSwitchStatus {
        val switch = listOfNotNull(print?.optJSONObject("device"), root.optJSONObject("device"))
            .firstNotNullOfOrNull { it.optJSONObject("fila_switch") }
            ?: return FilamentTrackSwitchStatus()
        val inputs = readIntArray(switch.opt("in"))
        val outputs = readIntArray(switch.opt("out"))
        return FilamentTrackSwitchStatus(
            installed = true,
            inputSlots = inputs,
            outputExtruderIds = outputs,
            outputNozzleSides = outputs.map(::nozzleSideFromExtruderId),
            statusFlags = switch.optIntValue("stat") ?: 0,
            infoFlags = switch.optIntValue("info") ?: 0,
        )
    }

    private fun readIntArray(value: Any?): List<Int> = when (value) {
        is JSONArray -> (0 until value.length()).mapNotNull { value.optIntValue(it) }
        else -> emptyList()
    }

    private fun nozzleSideFromExtruderId(extruderId: Int): NozzleSide = when (extruderId) {
        BAMBU_RIGHT_EXTRUDER_ID -> NozzleSide.RIGHT
        BAMBU_LEFT_EXTRUDER_ID -> NozzleSide.LEFT
        else -> NozzleSide.UNKNOWN
    }

    private fun externalNozzleSide(routeId: Int, isDualNozzle: Boolean): NozzleSide {
        if (!isDualNozzle) return NozzleSide.UNKNOWN
        return when (routeId) {
            VIRTUAL_TRAY_ID_START -> NozzleSide.LEFT
            VIRTUAL_TRAY_ID_START + 1 -> NozzleSide.RIGHT
            else -> NozzleSide.UNKNOWN
        }
    }

    private fun virtualTrayObjects(
        root: JSONObject,
        print: JSONObject?,
        ams: JSONObject?,
    ): List<JSONObject> {
        val containers = listOfNotNull(root, print, ams)
        val virtualSlots = containers.firstNotNullOfOrNull { container ->
            jsonObjects(container.opt("vir_slot")).takeIf { it.isNotEmpty() }
        }
        if (virtualSlots != null) return virtualSlots
        return containers.firstNotNullOfOrNull { container ->
            jsonObjects(container.opt("vt_tray")).takeIf { it.isNotEmpty() }
        }.orEmpty()
    }

    private fun jsonObjects(value: Any?): List<JSONObject> = when (value) {
        is JSONObject -> listOf(value)
        is JSONArray -> (0 until value.length()).mapNotNull(value::optJSONObject)
        else -> emptyList()
    }

    private fun routeIndex(unitId: Int, trayId: Int): Int =
        if (unitId >= AMS_HT_ID_START) unitId + trayId else unitId * SLOTS_PER_AMS + trayId

    private fun unitLabel(unitId: Int, trayId: Int, regularUnitCount: Int): String = when {
        unitId >= AMS_HT_ID_START -> "AMS-HT ${unitId - AMS_HT_ID_START + 1}"
        regularUnitCount == 1 && unitId == 0 -> "AMS ${trayId + 1}"
        else -> "AMS ${unitId + 1} / ${trayId + 1}"
    }

    private fun virtualTrayLabel(routeId: Int, count: Int): String = when {
        count == 1 -> "External spool"
        routeId == VIRTUAL_TRAY_ID_START -> "External left"
        routeId == VIRTUAL_TRAY_ID_START + 1 -> "External right"
        else -> "External spool"
    }

    private fun JSONObject.optProtocolId(key: String, fallback: Int): Int {
        val raw = opt(key)
        return when (raw) {
            is Number -> raw.toInt()
            is String -> raw.toIntOrNull()
            else -> null
        } ?: fallback
    }

    private fun JSONObject.optIntValue(key: String): Int? = when (val raw = opt(key)) {
        is Number -> raw.toInt()
        is String -> raw.toIntOrNull()
        else -> null
    }

    private fun JSONObject.optPositiveFloat(key: String): Float? = when (val raw = opt(key)) {
        is Number -> raw.toFloat()
        is String -> raw.toFloatOrNull()
        else -> null
    }?.takeIf { it > 0f }

    private fun JSONArray.optIntValue(index: Int): Int? = when (val raw = opt(index)) {
        is Number -> raw.toInt()
        is String -> raw.toIntOrNull()
        else -> null
    }

    private fun normalizeColor(raw: String): String {
        val cleaned = raw.trim().removePrefix("#").uppercase()
        return when {
            cleaned.length >= 6 -> "#${cleaned.take(6)}"
            else -> "#808080"
        }
    }

    private fun mapState(raw: String): String = when (raw.uppercase()) {
        "IDLE", "READY" -> "standby"
        "PREPARE", "RUNNING", "SLICING" -> "printing"
        "PAUSE", "PAUSED" -> "paused"
        "FINISH", "FINISHED", "COMPLETE" -> "complete"
        "FAILED", "ERROR" -> "error"
        else -> if (raw.isBlank()) "disconnected" else raw.lowercase()
    }

    private fun scalarOrArrayFirst(obj: JSONObject, key: String): Float {
        val value = obj.opt(key)
        return when (value) {
            is Number -> value.toFloat()
            is JSONArray -> value.optDouble(0, 0.0).toFloat()
            else -> 0f
        }
    }

    private fun readFloatArray(obj: JSONObject, key: String): List<Float> {
        val value = obj.opt(key)
        return when (value) {
            is Number -> listOf(value.toFloat())
            is JSONArray -> (0 until value.length()).map { index -> value.optDouble(index, 0.0).toFloat() }
            else -> emptyList()
        }
    }

    private fun disconnected(): BambuPushReport =
        BambuPushReport(
            status = PrinterStatus(state = "disconnected", progress = 0f),
            filamentSlots = emptyList(),
        )

    private const val SLOTS_PER_AMS = 4
    private const val AMS_HT_ID_START = 128
    private const val VIRTUAL_TRAY_ID_START = 254
    private const val AMS_EXTRUDER_SHIFT = 8
    private const val AMS_EXTRUDER_MASK = 0xFL
    private const val BAMBU_RIGHT_EXTRUDER_ID = 0
    private const val BAMBU_LEFT_EXTRUDER_ID = 1
}
