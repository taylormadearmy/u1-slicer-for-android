package com.u1.slicer.data

import org.json.JSONArray
import org.json.JSONObject
import com.u1.slicer.slice.GenericMachineProfile
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

enum class PrinterKind { MOONRAKER, BAMBU_LAN, ELEGOO_CC1, OCTOPRINT }

enum class MachineKind { SNAPMAKER_U1, BAMBU, ELEGOO_CENTAURI_CARBON, GENERIC_KLIPPER }

data class PrintHostConfig(val url: String, val apiKey: String = "")

fun defaultMachineKind(kind: PrinterKind): MachineKind = when (kind) {
    PrinterKind.MOONRAKER -> MachineKind.SNAPMAKER_U1
    PrinterKind.BAMBU_LAN -> MachineKind.BAMBU
    PrinterKind.ELEGOO_CC1 -> MachineKind.ELEGOO_CENTAURI_CARBON
    PrinterKind.OCTOPRINT -> MachineKind.GENERIC_KLIPPER
}

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
    val machine: MachineKind = defaultMachineKind(kind),
    val machineProfile: GenericMachineProfile? = null,
    val host: PrintHostConfig? = null,
) {
    init {
        when (kind) {
            PrinterKind.MOONRAKER -> {
                require(bambu == null) { "Moonraker printer must not have bambu config" }
                require(host == null) { "Moonraker uses moonrakerUrl" }
                require(machine == MachineKind.SNAPMAKER_U1 || machine == MachineKind.GENERIC_KLIPPER)
            }
            PrinterKind.BAMBU_LAN -> {
                require(bambu != null) { "Bambu printer requires bambu config" }
                require(moonrakerUrl.isBlank()) { "Bambu printer must not have moonrakerUrl" }
                require(machine == MachineKind.BAMBU && host == null)
            }
            PrinterKind.ELEGOO_CC1 -> {
                require(host != null && host.url.isNotBlank()) { "Centauri Carbon requires an address" }
                require(host.apiKey.isBlank()) { "Centauri Carbon CC1 has no API key" }
                require(machine == MachineKind.ELEGOO_CENTAURI_CARBON)
                require(bambu == null && moonrakerUrl.isBlank())
                val address = host.url.trim().removePrefix("http://").removePrefix("https://").trimEnd('/')
                require(address.isNotBlank() && address.none { it.isWhitespace() || it in "/\\?#@:" }) {
                    "Enter the CC1 hostname or IPv4 address without a port or path"
                }
            }
            PrinterKind.OCTOPRINT -> {
                require(host != null && host.url.isNotBlank()) { "OctoPrint requires an address" }
                require(machine == MachineKind.GENERIC_KLIPPER)
                require(bambu == null && moonrakerUrl.isBlank())
                val address = if (host.url.contains("://")) host.url else "http://${host.url}"
                require(address.toHttpUrlOrNull()?.let { it.username.isBlank() && it.password.isBlank() && it.query == null && it.fragment == null } == true) {
                    "Enter an HTTP or HTTPS OctoPrint URL without embedded credentials or query parameters"
                }
            }
        }
        require(machine == MachineKind.GENERIC_KLIPPER || machineProfile == null) {
            "Only generic machines may have an imported machine profile"
        }
    }

    companion object {
        fun toJsonObject(p: Printer): JSONObject = JSONObject().apply {
            put("id", p.id)
            put("nickname", p.nickname)
            put("kind", p.kind.name)
            put("moonrakerUrl", p.moonrakerUrl)
            put("machine", p.machine.name)
            p.machineProfile?.let { put("machineProfile", JSONObject(it.resolvedJson)) }
            p.host?.let { put("host", JSONObject().put("url", it.url).put("apiKey", it.apiKey)) }
            if (p.bambu != null) put("bambu", BambuConfig.toJsonObject(p.bambu))
            put("extruderPresets", JSONArray(serializeExtruderPresets(p.extruderPresets)))
            p.selectedWebcamUid?.takeIf { it.isNotBlank() }?.let { put("selectedWebcamUid", it) }
        }

        fun fromJsonObject(obj: JSONObject): Printer {
            val kind = obj.optString("kind", PrinterKind.MOONRAKER.name)
                .takeIf { it.isNotBlank() }
                ?.let { PrinterKind.valueOf(it) }
                ?: PrinterKind.MOONRAKER
            val machine = if (obj.has("machine")) MachineKind.valueOf(obj.getString("machine")) else defaultMachineKind(kind)
            val extruderPresets = parsePersistedExtruderPresets(
                machine = machine,
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
                machine = machine,
                machineProfile = obj.optJSONObject("machineProfile")?.let { GenericMachineProfile.fromJson(it.toString()) },
                host = obj.optJSONObject("host")?.let { PrintHostConfig(it.getString("url"), it.optString("apiKey", "")) },
            )
        }

        /**
         * Deserialise extruder presets exactly as stored — no slot-filling.
         * The fill-to-4 behaviour in [parseExtruderPresets] is a UI concern; [Printer]
         * stores whatever the user configured.
         */
        private fun parsePersistedExtruderPresets(
            machine: MachineKind,
            arr: JSONArray?,
        ): List<ExtruderPreset> {
            if (arr == null) {
                return if (machine == MachineKind.SNAPMAKER_U1) defaultExtruderPresets() else emptyList()
            }
            if (arr.length() == 0 && machine == MachineKind.SNAPMAKER_U1) {
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
