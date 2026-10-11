package com.u1.slicer.machine

import com.u1.slicer.data.FilamentProfile
import com.u1.slicer.data.ProcessProfile
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.zip.ZipInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class ImportedOrcaBundle(
    val machines: List<MachineProfile>,
    val filaments: List<FilamentProfile>,
    val processes: List<ProcessProfile>,
)

/** Streams Orca preset bundles without extracting archive paths to disk. */
class OrcaProfileBundleImporter(
    private val loadMissing: (String, OrcaProfileType, String) -> String?,
    private val validateMachine: suspend (MachineProfile) -> MachineProfileValidationResult,
    private val parseFilaments: (String) -> List<FilamentProfile>,
    private val parseProcesses: (String, String) -> List<ProcessProfile>,
    private val maxEntryBytes: Int = DEFAULT_MAX_ENTRY_BYTES,
    private val maxTotalBytes: Int = DEFAULT_MAX_TOTAL_BYTES,
) {
    suspend fun import(input: InputStream, fileName: String): ImportedOrcaBundle = withContext(Dispatchers.IO) {
        val expected = when {
            fileName.endsWith(".orca_printer", ignoreCase = true) -> BundleKind.PRINTER
            fileName.endsWith(".orca_filament", ignoreCase = true) -> BundleKind.FILAMENT
            else -> throw IllegalArgumentException("Choose an .orca_printer or .orca_filament bundle.")
        }
        val files = readArchive(input)
        val manifestText = files[MANIFEST]
            ?: throw IllegalArgumentException("Orca bundle has no $MANIFEST.")
        val manifest = runCatching { JSONObject(manifestText) }.getOrElse {
            throw IllegalArgumentException("Orca bundle manifest is malformed.")
        }
        val kind = BundleKind.fromManifest(manifest.optString("bundle_type"))
            ?: throw IllegalArgumentException("Orca bundle has an unsupported bundle_type.")
        require(kind == expected) { "Bundle extension does not match bundle_type." }
        val declaration = declaredProfiles(kind, manifest)
        require(declaration.paths.isNotEmpty()) { "Orca bundle declares no profiles." }

        val undeclared = files.keys - MANIFEST - declaration.paths
        require(undeclared.isEmpty()) { "Orca bundle contains undeclared profiles: ${undeclared.first()}" }
        val presets = declaration.paths.map { path ->
            val text = files[path] ?: throw IllegalArgumentException("Orca bundle is missing $path.")
            val json = runCatching { JSONObject(text) }.getOrElse {
                throw IllegalArgumentException("Orca profile $path is malformed.")
            }
            val type = profileType(json)
                ?: throw IllegalArgumentException("Orca profile $path has no supported profile type.")
            val expectedType = declaration.types[path]
            require(expectedType == null || expectedType == type) {
                "Orca profile $path does not match its declared category."
            }
            RawPreset(path, text, json, type, declaration.vendors[path])
        }
        if (kind == BundleKind.PRINTER && presets.none { it.type == OrcaProfileType.MACHINE }) {
            throw IllegalArgumentException("Orca printer bundle contains no machine profile.")
        }
        if (kind == BundleKind.FILAMENT && presets.none { it.type == OrcaProfileType.FILAMENT }) {
            throw IllegalArgumentException("Orca filament bundle contains no filament profile.")
        }

        val local = presets.groupBy { preset ->
            LocalPresetKey(preset.type, inferVendor(preset), preset.json.optString("name"))
        }
        val duplicate = local.entries.firstOrNull { it.value.size > 1 }
        require(duplicate == null) {
            "Orca bundle contains duplicate ${duplicate?.key?.type?.name?.lowercase()} profile " +
                "${duplicate?.key?.name} for ${duplicate?.key?.vendor}."
        }
        val machines = mutableListOf<MachineProfile>()
        val filaments = mutableListOf<FilamentProfile>()
        val processes = mutableListOf<ProcessProfile>()
        for (preset in presets.filterNot { it.json.optString("instantiation") == "false" }) {
            val vendor = inferVendor(preset)
            val resolved = OrcaProfileResolver.resolve(preset.text) { parentName ->
                val parentVendor = vendorForParent(vendor, preset.type, parentName)
                localPreset(local, vendor, parentVendor, preset.type, parentName)?.text
                    ?: loadMissing(parentVendor, preset.type, parentName)
            } ?: throw IllegalArgumentException(
                "Could not resolve ${preset.json.optString("name", preset.path)}.",
            )
            when (preset.type) {
                OrcaProfileType.MACHINE -> {
                    val vendorQualified = JSONObject(resolved).apply {
                        if (optString("vendor").isBlank()) put("vendor", vendor)
                    }.toString()
                    val profile = MachineProfile.fromResolvedOrcaJson(vendorQualified, MachineFamily.GENERIC)
                        ?: throw IllegalArgumentException("Imported machine profile is not printable.")
                    require(PrinterWizardViewModel.isWizardSupported(profile)) {
                        "Imported machine uses an unsupported delta or multi-material configuration."
                    }
                    val validation = validateMachine(profile)
                    if (!validation.isValid) {
                        throw IllegalArgumentException(
                            validation.errorMessage ?: "Imported machine profile validation failed.",
                        )
                    }
                    machines += profile
                }
                OrcaProfileType.FILAMENT -> {
                    val parsed = parseFilaments(resolved)
                    require(parsed.isNotEmpty()) { "Imported filament profile is not usable." }
                    filaments += parsed
                }
                OrcaProfileType.PROCESS -> {
                    val name = preset.json.optString("name").ifBlank { "Imported process" }
                    val parsed = parseProcesses(resolved, name)
                    require(parsed.isNotEmpty()) { "Imported process profile is not usable." }
                    processes += parsed
                }
            }
        }
        ImportedOrcaBundle(machines, filaments, processes)
    }

    private fun readArchive(input: InputStream): Map<String, String> {
        val files = linkedMapOf<String, String>()
        var total = 0
        var entryCount = 0
        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entryCount++
                require(entryCount <= MAX_ENTRIES) { "Orca bundle contains too many files." }
                if (entry.isDirectory) continue
                val path = entry.name
                requireSafePath(path)
                require(path !in files) { "Orca bundle contains duplicate entry $path." }
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = zip.read(buffer)
                    if (count < 0) break
                    total += count
                    require(output.size() + count <= maxEntryBytes) { "Orca bundle entry $path is too large." }
                    require(total <= maxTotalBytes) { "Orca bundle is too large." }
                    output.write(buffer, 0, count)
                }
                files[path] = decodeUtf8(output.toByteArray(), path)
                zip.closeEntry()
            }
        }
        return files
    }

    private fun declaredProfiles(kind: BundleKind, manifest: JSONObject): Declaration {
        val paths = linkedSetOf<String>()
        val types = mutableMapOf<String, OrcaProfileType>()
        val vendors = mutableMapOf<String, String>()
        when (kind) {
            BundleKind.PRINTER -> listOf(
                "printer_config" to OrcaProfileType.MACHINE,
                "filament_config" to OrcaProfileType.FILAMENT,
                "process_config" to OrcaProfileType.PROCESS,
            ).forEach { (key, type) ->
                manifest.optJSONArray(key).strings().forEach { path ->
                    requireSafePath(path)
                    require(paths.add(path)) { "Orca bundle declares $path more than once." }
                    types[path] = type
                }
            }
            BundleKind.FILAMENT -> {
                val groups = manifest.optJSONArray("printer_vendor") ?: JSONArray()
                for (index in 0 until groups.length()) {
                    val group = groups.optJSONObject(index)
                        ?: throw IllegalArgumentException("Invalid printer_vendor entry.")
                    val vendor = group.optString("vendor").trim()
                    require(vendor.isNotEmpty()) { "Filament bundle vendor is missing." }
                    group.optJSONArray("filament_path").strings().forEach { path ->
                        requireSafePath(path)
                        require(paths.add(path)) { "Orca bundle declares $path more than once." }
                        types[path] = OrcaProfileType.FILAMENT
                        vendors[path] = vendor
                    }
                }
            }
        }
        return Declaration(paths, types, vendors)
    }

    private fun inferVendor(preset: RawPreset): String {
        preset.vendor?.takeIf { it.isNotBlank() }?.let { return it }
        preset.json.optString("vendor").takeIf { it.isNotBlank() }?.let { return it }
        val inherited = preset.json.optString("inherits")
        CachedOrcaProfileLookup.inferVendor(inherited, preset.type)?.let { return it }
        val candidates = listOf(
            preset.json.optString("printer_settings_id"),
            preset.json.optString("printer_model"),
            preset.json.optString("name"),
        )
        return VendorCatalogClient.VENDOR_NAMES.firstOrNull { vendor ->
            candidates.any { it == vendor || it.startsWith("$vendor ") }
        } ?: "Custom"
    }

    private fun vendorForParent(current: String, type: OrcaProfileType, parentName: String): String {
        val inferred = CachedOrcaProfileLookup.inferVendor(parentName, type)
        return inferred?.takeIf { it in VendorCatalogClient.VENDOR_NAMES } ?: current
    }

    private fun localPreset(
        local: Map<LocalPresetKey, List<RawPreset>>,
        currentVendor: String,
        parentVendor: String,
        type: OrcaProfileType,
        name: String,
    ): RawPreset? {
        local[LocalPresetKey(type, currentVendor, name)]?.singleOrNull()?.let { return it }
        if (parentVendor != currentVendor) {
            return local[LocalPresetKey(type, parentVendor, name)]?.singleOrNull()
        }
        return null
    }

    private fun profileType(json: JSONObject): OrcaProfileType? = when (json.optString("type")) {
        "machine" -> OrcaProfileType.MACHINE
        "filament" -> OrcaProfileType.FILAMENT
        "process" -> OrcaProfileType.PROCESS
        else -> when {
            json.has("printer_settings_id") -> OrcaProfileType.MACHINE
            json.has("filament_settings_id") -> OrcaProfileType.FILAMENT
            json.has("print_settings_id") -> OrcaProfileType.PROCESS
            else -> null
        }
    }

    private fun requireSafePath(path: String) {
        val segments = path.split('/')
        require(path.isNotBlank() && !path.startsWith('/') && '\\' !in path && '\u0000' !in path &&
            segments.none { it.isBlank() || it == "." || it == ".." }) {
            "Orca bundle contains an unsafe path."
        }
        require(path.endsWith(".json", ignoreCase = true)) { "Orca bundle entries must be JSON." }
    }

    private fun decodeUtf8(bytes: ByteArray, path: String): String = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (_: Exception) {
        throw IllegalArgumentException("Orca bundle entry $path is not valid UTF-8.")
    }

    private fun JSONArray?.strings(): List<String> {
        if (this == null) return emptyList()
        return (0 until length()).map { index ->
            optString(index).takeIf { it.isNotBlank() }
                ?: throw IllegalArgumentException("Orca bundle manifest contains an invalid path.")
        }
    }

    private data class RawPreset(
        val path: String,
        val text: String,
        val json: JSONObject,
        val type: OrcaProfileType,
        val vendor: String?,
    )

    private data class Declaration(
        val paths: Set<String>,
        val types: Map<String, OrcaProfileType>,
        val vendors: Map<String, String>,
    )

    private data class LocalPresetKey(
        val type: OrcaProfileType,
        val vendor: String,
        val name: String,
    )

    private enum class BundleKind {
        PRINTER,
        FILAMENT;

        companion object {
            fun fromManifest(value: String): BundleKind? = when (value) {
                "printer config bundle" -> PRINTER
                "filament config bundle" -> FILAMENT
                else -> null
            }
        }
    }

    companion object {
        private const val MANIFEST = "bundle_structure.json"
        private const val MAX_ENTRIES = 256
        private const val DEFAULT_MAX_ENTRY_BYTES = 1024 * 1024
        private const val DEFAULT_MAX_TOTAL_BYTES = 8 * 1024 * 1024
    }
}
