package com.u1.slicer.data

import org.json.JSONArray
import org.json.JSONObject

enum class PrinterKind { MOONRAKER, BAMBU_LAN }

enum class BambuModel { X1C, X1E, P1S, P1P, P2S, A1, A1_MINI, H2D }

data class BambuConfig(
    val ip: String,
    val accessCode: String,
    val serial: String,
    val model: BambuModel,
) {
    companion object {
        fun toJsonObject(cfg: BambuConfig): JSONObject = JSONObject().apply {
            put("ip", cfg.ip)
            put("accessCode", cfg.accessCode)
            put("serial", cfg.serial)
            put("model", cfg.model.name)
        }

        fun fromJsonObject(obj: JSONObject): BambuConfig = BambuConfig(
            ip = obj.getString("ip"),
            accessCode = obj.getString("accessCode"),
            serial = obj.getString("serial"),
            model = BambuModel.valueOf(obj.getString("model")),
        )
    }
}

/**
 * One configured U1 printer. Persisted as part of [PrintersConfig] in DataStore.
 * The id is a stable UUID generated at create time so renames don't break references.
 */
data class Printer(
    val id: String,
    val nickname: String,
    val kind: PrinterKind = PrinterKind.MOONRAKER,
    val moonrakerUrl: String = "",
    val bambu: BambuConfig? = null,
    val extruderPresets: List<ExtruderPreset> = defaultExtruderPresets(),
    /** Stable Moonraker webcam UID chosen for this printer, or null for automatic choice. */
    val selectedWebcamUid: String? = null,
) {
    init {
        when (kind) {
            PrinterKind.MOONRAKER -> {
                require(bambu == null) { "Moonraker printer must not have bambu config" }
            }
            PrinterKind.BAMBU_LAN -> {
                require(bambu != null) { "Bambu printer requires bambu config" }
                require(moonrakerUrl.isBlank()) { "Bambu printer must not have moonrakerUrl" }
            }
        }
    }

    companion object {
        fun toJsonObject(p: Printer): JSONObject = JSONObject().apply {
            put("id", p.id)
            put("nickname", p.nickname)
            put("kind", p.kind.name)
            put("moonrakerUrl", p.moonrakerUrl)
            if (p.bambu != null) put("bambu", BambuConfig.toJsonObject(p.bambu))
            put("extruderPresets", JSONArray(serializeExtruderPresets(p.extruderPresets)))
            p.selectedWebcamUid?.takeIf { it.isNotBlank() }?.let { put("selectedWebcamUid", it) }
        }

        fun fromJsonObject(obj: JSONObject): Printer {
            val kind = obj.optString("kind", PrinterKind.MOONRAKER.name)
                .takeIf { it.isNotBlank() }
                ?.let { PrinterKind.valueOf(it) }
                ?: PrinterKind.MOONRAKER
            val extruderPresets = parsePersistedExtruderPresets(
                kind = kind,
                arr = obj.optJSONArray("extruderPresets"),
            )
            return Printer(
                id = obj.getString("id"),
                nickname = obj.getString("nickname"),
                kind = kind,
                moonrakerUrl = obj.optString("moonrakerUrl", ""),
                bambu = obj.optJSONObject("bambu")?.let(BambuConfig::fromJsonObject),
                extruderPresets = extruderPresets,
                selectedWebcamUid = obj.optString("selectedWebcamUid", "").takeIf { it.isNotBlank() },
            )
        }

        /**
         * Deserialise extruder presets exactly as stored — no slot-filling.
         * The fill-to-4 behaviour in [parseExtruderPresets] is a UI concern; [Printer]
         * stores whatever the user configured.
         */
        private fun parsePersistedExtruderPresets(
            kind: PrinterKind,
            arr: JSONArray?,
        ): List<ExtruderPreset> {
            if (arr == null) {
                return if (kind == PrinterKind.MOONRAKER) defaultExtruderPresets() else emptyList()
            }
            if (arr.length() == 0 && kind == PrinterKind.MOONRAKER) {
                return defaultExtruderPresets()
            }
            return parseExtruderPresetsExact(arr)
        }

        private fun parseExtruderPresetsExact(arr: JSONArray): List<ExtruderPreset> {
            if (arr.length() == 0) return emptyList()
            return (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                ExtruderPreset(
                    index = o.getInt("index"),
                    color = o.optString("color", "#FFFFFF"),
                    materialType = o.optString("materialType", "PLA"),
                    filamentProfileId = if (o.has("filamentProfileId")) o.getLong("filamentProfileId") else null,
                )
            }
        }
    }
}

/**
 * The list of all configured printers plus which one is currently active.
 * Invariants (enforced by the constructor):
 *  - printers must be non-empty
 *  - activeId must reference an id in printers
 */
data class PrintersConfig(
    val printers: List<Printer>,
    val activeId: String,
) {
    init {
        require(printers.isNotEmpty()) { "PrintersConfig requires at least one printer" }
        require(printers.any { it.id == activeId }) {
            "PrintersConfig activeId='$activeId' is not present in printers list"
        }
    }

    val active: Printer get() = printers.first { it.id == activeId }

    companion object {
        fun toJson(cfg: PrintersConfig): String = JSONObject().apply {
            val arr = JSONArray()
            cfg.printers.forEach { arr.put(Printer.toJsonObject(it)) }
            put("printers", arr)
            put("activeId", cfg.activeId)
        }.toString()

        fun fromJson(json: String): PrintersConfig {
            val obj = JSONObject(json)
            val arr = obj.getJSONArray("printers")
            val list = (0 until arr.length()).mapNotNull { index ->
                runCatching { Printer.fromJsonObject(arr.getJSONObject(index)) }.getOrNull()
            }
            require(list.isNotEmpty()) { "PrintersConfig requires at least one valid printer" }
            val requestedActiveId = obj.getString("activeId")
            val resolvedActiveId = requestedActiveId.takeIf { id -> list.any { it.id == id } } ?: list.first().id
            return PrintersConfig(printers = list, activeId = resolvedActiveId)
        }
    }
}
