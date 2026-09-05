package com.u1.slicer.machine

import org.json.JSONObject

enum class OrcaProfileType {
    MACHINE,
    PROCESS,
    FILAMENT,
}

/** Adapts the disk cache and pinned catalogue to [OrcaProfileResolver]'s name loader. */
class CachedOrcaProfileLookup(
    private val cache: ProfileCache,
    private val client: VendorCatalogClient,
) {
    /**
     * Returns the installed catalogue after checking the pinned source for a newer vendor snapshot.
     * A complete newer snapshot is downloaded before the cache swap starts, so any fetch failure
     * leaves the installed version available for offline use.
     */
    fun refreshVendor(vendor: String): VendorCatalogIndex {
        val installed = cache.readVendorIndex(vendor)
        val remote = try {
            client.fetchVendorIndex(vendor)
        } catch (error: Exception) {
            return installed ?: throw error
        }
        if (installed == null) {
            cache.replaceVendor(remote, emptyMap(), vendor)
            return remote
        }
        if (!cache.needsUpdate(remote, vendor)) return installed

        return try {
            val profiles = remote.allEntries()
                .distinctBy(VendorCatalogEntry::subPath)
                .associate { entry ->
                    val json = client.fetchProfile(vendor, entry.subPath)
                    require(isJsonObject(json)) { "Malformed Orca profile: ${entry.subPath}" }
                    entry.subPath to json
                }
            cache.replaceVendor(remote, profiles, vendor)
            remote
        } catch (_: Exception) {
            installed
        }
    }

    fun resolveByName(vendor: String, type: OrcaProfileType, name: String): String? {
        var chainVendor = vendor
        val root = loadFromVendor(chainVendor, type, name, refreshIndexOnMiss = true) ?: return null
        chainVendor = root.vendor
        return OrcaProfileResolver.resolve(root.json) { parentName ->
            loadParent(chainVendor, type, parentName)?.also {
                chainVendor = it.vendor
            }?.json
        }
    }

    fun loadByName(vendor: String, type: OrcaProfileType, name: String): String? =
        loadFromVendor(vendor, type, name, refreshIndexOnMiss = true)?.json

    private fun loadParent(
        preferredVendor: String,
        type: OrcaProfileType,
        name: String,
    ): LoadedProfile? {
        loadFromVendor(preferredVendor, type, name, refreshIndexOnMiss = false)?.let { return it }
        val inferred = inferVendor(name, type)?.takeUnless { it == preferredVendor }
        return if (inferred != null) {
            loadFromVendor(inferred, type, name, refreshIndexOnMiss = true)
        } else {
            loadFromVendor(preferredVendor, type, name, refreshIndexOnMiss = true)
        }
    }

    private fun loadFromVendor(
        vendor: String,
        type: OrcaProfileType,
        name: String,
        refreshIndexOnMiss: Boolean,
    ): LoadedProfile? {
        val cachedIndex = cache.readVendorIndex(vendor)
        var index = cachedIndex ?: runCatching { refreshVendor(vendor) }.getOrNull() ?: return null
        var entry = index.entries(type).firstOrNull { it.name == name }
        if (entry == null && refreshIndexOnMiss && cachedIndex != null) {
            index = runCatching { refreshVendor(vendor) }.getOrNull() ?: return null
            entry = index.entries(type).firstOrNull { it.name == name }
        }
        entry ?: return null
        cache.readProfile(vendor, entry.subPath)
            ?.takeIf(::isJsonObject)
            ?.let { return LoadedProfile(vendor, it) }
        val json = runCatching { client.fetchProfile(vendor, entry.subPath) }.getOrNull() ?: return null
        if (!isJsonObject(json)) return null
        cache.writeProfile(vendor, entry.subPath, json)
        return LoadedProfile(vendor, json)
    }

    private fun isJsonObject(json: String): Boolean = runCatching { JSONObject(json) }.isSuccess

    private fun VendorCatalogIndex.allEntries(): List<VendorCatalogEntry> =
        machineModelList + machineList + processList + filamentList

    private fun VendorCatalogIndex.entries(type: OrcaProfileType): List<VendorCatalogEntry> = when (type) {
        OrcaProfileType.MACHINE -> machineList
        OrcaProfileType.PROCESS -> processList
        OrcaProfileType.FILAMENT -> filamentList
    }

    private data class LoadedProfile(val vendor: String, val json: String)

    companion object {
        /** Compatibility rules used by SliceBeam when an inherited preset is outside its bundle. */
        fun inferVendor(name: String, type: OrcaProfileType): String? {
            if ("@BBL" in name) return "BBL"
            val inferred = when (type) {
                OrcaProfileType.PROCESS -> name.substringAfter('@', "")
                    .substringBefore(' ')
                    .takeIf { it.isNotBlank() }
                OrcaProfileType.MACHINE,
                OrcaProfileType.FILAMENT -> name.substringBefore(' ')
                    .takeIf { ' ' in name && it.isNotBlank() }
            } ?: return null
            return if (inferred == "Generic" || name.startsWith("Bambu Lab")) "BBL" else inferred
        }
    }
}
