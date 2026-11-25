package com.u1.slicer.machine

import com.u1.slicer.data.FilamentProfile
import com.u1.slicer.data.ProcessProfile
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.File
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

/** Resolves JSON and streamed Orca bundles without extracting archive paths to disk. */
class OrcaProfileBundleImporter(
    private val loadMissing: (String, OrcaProfileType, String) -> String?,
    private val validateMachine: suspend (MachineProfile) -> MachineProfileValidationResult,
    private val parseFilaments: (String) -> List<FilamentProfile>,
    private val parseProcesses: (String, String) -> List<ProcessProfile>,
    private val maxEntryBytes: Int = DEFAULT_MAX_ENTRY_BYTES,
    private val maxTotalBytes: Int = DEFAULT_MAX_TOTAL_BYTES,
    private val projectCacheDirectory: File? = null,
) {
    suspend fun import(
        input: InputStream,
        fileName: String,
        expectedType: OrcaProfileType? = null,
    ): ImportedOrcaBundle = withContext(Dispatchers.IO) {
        val buffered = input.buffered()
        buffered.mark(4)
        val signature = List(4) { buffered.read() }
        buffered.reset()
        val archive = signature == listOf(0x50, 0x4b, 3, 4)
        if (archive && fileName.endsWith(".3mf", ignoreCase = true)) {
            require(expectedType == null || expectedType == OrcaProfileType.FILAMENT) {
                "Select a ${expectedType?.name?.lowercase()} preset. Project import is available in Filament Library."
            }
            val filaments = parseFilaments(ProjectFilamentImporter.read(buffered, maxEntryBytes, projectCacheDirectory))
            require(filaments.isNotEmpty()) { "Project contains no usable filament profiles." }
            return@withContext ImportedOrcaBundle(emptyList(), filaments, emptyList())
        }
        val presets = if (archive) readBundle(buffered, fileName) else readJson(buffered, fileName, expectedType)
        require(expectedType == null || presets.any { it.type == expectedType }) {
            "Select a ${expectedType?.name?.lowercase()} preset."
        }
        resolvePresets(presets, expectedType)
    }

    private fun readBundle(input: InputStream, fileName: String): List<RawPreset> {
        val expected = when {
            fileName.endsWith(".orca_printer", ignoreCase = true) -> BundleKind.PRINTER
            fileName.endsWith(".orca_filament", ignoreCase = true) -> BundleKind.FILAMENT
            else -> null // Providers may rename files; the manifest owns the archive type.
        }
        val files = readArchive(input)
        val manifestText = files[MANIFEST]
            ?: throw IllegalArgumentException("Orca bundle has no $MANIFEST.")
        val manifest = runCatching { JSONObject(manifestText) }.getOrElse {
            throw IllegalArgumentException("Orca bundle manifest is malformed.")
        }
        val kind = BundleKind.fromManifest(manifest.optString("bundle_type"))
            ?: throw IllegalArgumentException("Orca bundle has an unsupported bundle_type.")
        require(expected == null || kind == expected) { "Bundle extension does not match bundle_type." }
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
            val declaredType = declaration.types[path]
            require(declaredType == null || declaredType == type) {
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
        return presets
    }

    private fun readJson(input: InputStream, fileName: String, expectedType: OrcaProfileType?): List<RawPreset> {
        val bytes = input.readBytesLimited(maxTotalBytes)
        val text = decodeUtf8(bytes, fileName).removePrefix("\uFEFF").trim()
        val objects = if (text.startsWith("[")) JSONArray(text) else {
            val root = JSONObject(text)
            root.optJSONArray("process_profiles") ?: root.optJSONArray("filaments")
                ?: JSONArray().put(root)
        }
        require(objects.length() in 1..MAX_ENTRIES) { "Import between 1 and $MAX_ENTRIES presets." }
        return (0 until objects.length()).map { index ->
            val json = objects.getJSONObject(index)
            require(!json.has("type") || json.optString("type") in setOf("machine", "filament", "process")) {
                "Unsupported preset type: ${json.optString("type")}."
            }
            val type = profileType(json) ?: expectedType
                ?: throw IllegalArgumentException("Preset has no supported profile type.")
            if (json.optString("name").isBlank() && type != OrcaProfileType.FILAMENT) {
                val fallback = fileName.substringBeforeLast('.')
                json.put("name", if (objects.length() == 1) fallback else "$fallback #${index + 1}")
            }
            RawPreset("$index.json", json.toString(), json, type, null)
        }
    }

    private fun InputStream.readBytesLimited(limit: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            require(output.size() + count <= limit) { "Preset JSON is too large." }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private suspend fun resolvePresets(presets: List<RawPreset>, expectedType: OrcaProfileType?): ImportedOrcaBundle {
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
        for (preset in presets.filterNot { it.json.optString("instantiation") == "false" }
            .filter { expectedType == null || it.type == expectedType }) {
            val vendor = inferVendor(preset)
            val resolved = OrcaProfileResolver.resolve(preset.text) { parentName ->
                val parentVendor = vendorForParent(vendor, preset.type, parentName)
                localPreset(local, vendor, parentVendor, preset.type, parentName)?.text
                    ?: loadMissing(parentVendor, preset.type, parentName)
                    ?: throw IllegalArgumentException(
                        "Missing parent preset \"$parentName\" for \"${preset.json.optString("name")}\". " +
                            if (preset.type == OrcaProfileType.FILAMENT) {
                                "Export a bundle containing all parent presets, or use Import profile in Filament Library " +
                                    "to select a project 3MF and save its resolved filaments instead."
                            } else {
                                "Export a bundle containing all parent presets."
                            },
                    )
            } ?: throw IllegalArgumentException(
                "Could not resolve ${preset.json.optString("name", preset.path)}.",
            )
            when (preset.type) {
                OrcaProfileType.MACHINE -> {
                    val vendorQualified = JSONObject(resolved).apply {
                        if (optString("vendor").isBlank()) put("vendor", vendor)
                    }.toString()
                    val profile = MachineProfile.fromSnapshot(
                        com.u1.slicer.slice.GenericMachineProfile.fromJson(vendorQualified),
                    )
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
        return ImportedOrcaBundle(machines, filaments, processes)
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
