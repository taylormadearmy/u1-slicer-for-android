package com.u1.slicer.machine

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import org.json.JSONObject

/** Crash-safe on-disk snapshots of vendor indexes and their profile files. */
class ProfileCache(private val root: File) {
    init {
        recoverInterruptedTransactions()
    }

    fun needsUpdate(remoteIndex: VendorCatalogIndex, vendor: String = remoteIndex.name): Boolean {
        val installed = readVendorIndex(vendor) ?: return true
        return isNewerVersion(remoteIndex.version, installed.version)
    }

    fun replaceVendor(index: VendorCatalogIndex, profiles: Map<String, String>, vendor: String = index.name) {
        requireSafeSegment(vendor)
        profiles.keys.forEach(::requireSafePath)
        root.mkdirs()
        val current = File(root, vendor)
        val staging = File(root, ".$vendor$STAGING_MARKER${System.nanoTime()}")
        val backup = File(root, ".$vendor$BACKUP_SUFFIX")
        try {
            staging.mkdirs()
            val indexFile = File(staging, INDEX_FILE)
            indexFile.writeText(index.rawJson.ifBlank { serialize(index) })
            profiles.forEach { (path, text) ->
                val file = File(staging, path)
                file.parentFile?.mkdirs()
                file.writeText(text)
            }
            if (backup.exists()) backup.deleteRecursively()
            if (current.exists()) move(current, backup)
            move(staging, current)
        } catch (error: Exception) {
            staging.deleteRecursively()
            if (!current.exists() && backup.exists()) move(backup, current)
            throw error
        } finally {
            if (staging.exists()) staging.deleteRecursively()
        }
    }

    fun readVendorIndex(vendor: String): VendorCatalogIndex? =
        readIndexFile(File(File(root, safeFile(vendor)), INDEX_FILE))

    fun readBackupIndex(vendor: String): VendorCatalogIndex? =
        readIndexFile(File(File(root, ".${safeFile(vendor)}$BACKUP_SUFFIX"), INDEX_FILE))

    fun readProfile(vendor: String, subPath: String): String? {
        requireSafeSegment(vendor); requireSafePath(subPath)
        val file = File(File(root, vendor), subPath)
        return file.takeIf { it.isFile }?.readText()
    }

    fun rollback(vendor: String) {
        requireSafeSegment(vendor)
        val current = File(root, vendor)
        val backup = File(root, ".$vendor$BACKUP_SUFFIX")
        require(backup.isDirectory) { "No backup for $vendor" }
        val displaced = File(root, ".$vendor$ROLLBACK_MARKER${System.nanoTime()}")
        if (current.exists()) move(current, displaced)
        try {
            move(backup, current)
            if (displaced.exists()) move(displaced, backup)
        } catch (error: Exception) {
            // Put both snapshots back where they started if either half of the swap fails.
            if (!backup.exists() && current.exists()) move(current, backup)
            if (!current.exists() && displaced.exists()) move(displaced, current)
            throw error
        }
    }

    private fun recoverInterruptedTransactions() {
        if (!root.isDirectory) return
        val entries = root.listFiles().orEmpty()
        val vendors = buildSet {
            entries.forEach { file ->
                val name = file.name
                when {
                    name.startsWith(".") && name.endsWith(BACKUP_SUFFIX) ->
                        add(name.removePrefix(".").removeSuffix(BACKUP_SUFFIX))
                    name.startsWith(".") && STAGING_MARKER in name ->
                        add(name.removePrefix(".").substringBefore(STAGING_MARKER))
                    name.startsWith(".") && ROLLBACK_MARKER in name ->
                        add(name.removePrefix(".").substringBefore(ROLLBACK_MARKER))
                }
            }
        }
        vendors.forEach { vendor ->
            if (vendor.isBlank()) return@forEach
            val current = File(root, vendor)
            val backup = File(root, ".$vendor$BACKUP_SUFFIX")
            val displaced = root.listFiles { file ->
                file.name.startsWith(".$vendor$ROLLBACK_MARKER")
            }.orEmpty().maxByOrNull(File::lastModified)

            if (!current.exists()) {
                when {
                    backup.isDirectory -> move(backup, current)
                    displaced?.isDirectory == true -> move(displaced, current)
                }
            }
            if (current.isDirectory && !backup.exists() && displaced?.isDirectory == true) {
                move(displaced, backup)
            }
            root.listFiles { file -> file.name.startsWith(".$vendor$STAGING_MARKER") }
                .orEmpty()
                .forEach(File::deleteRecursively)
            root.listFiles { file -> file.name.startsWith(".$vendor$ROLLBACK_MARKER") }
                .orEmpty()
                .forEach(File::deleteRecursively)
        }
    }

    companion object {
        private const val INDEX_FILE = "vendor-index.json"
        private const val BACKUP_SUFFIX = ".backup"
        private const val STAGING_MARKER = ".staging-"
        private const val ROLLBACK_MARKER = ".rollback-"

        fun isNewerVersion(remote: String, current: String): Boolean {
            val r = remote.split('.').map { it.toIntOrNull() ?: 0 }
            val c = current.split('.').map { it.toIntOrNull() ?: 0 }
            return (0 until maxOf(r.size, c.size)).firstNotNullOfOrNull {
                val d = r.getOrElse(it) { 0 } - c.getOrElse(it) { 0 }
                d.takeIf { it != 0 }
            }?.let { it > 0 } ?: false
        }

        private fun serialize(index: VendorCatalogIndex): String = JSONObject().apply {
            put("name", index.name); put("version", index.version)
            put("machine_model_list", index.machineModelList.toJson())
            put("machine_list", index.machineList.toJson())
        }.toString()

        private fun List<VendorCatalogEntry>.toJson() = org.json.JSONArray().also { array ->
            forEach { array.put(JSONObject().put("name", it.name).put("sub_path", it.subPath)) }
        }

        private fun readIndexFile(file: File): VendorCatalogIndex? =
            file.takeIf { it.isFile }?.let { runCatching { VendorCatalogClient.parseVendorIndex(it.readText()) }.getOrNull() }

        private fun requireSafeSegment(value: String) {
            require(value.isNotBlank() && value != "." && value != ".." && !value.contains('/') && !value.contains('\\'))
        }

        private fun requireSafePath(value: String) {
            require(value.isNotBlank() && !File(value).isAbsolute && value.split('/', '\\').none { it.isEmpty() || it == "." || it == ".." })
        }

        private fun safeFile(value: String): String { requireSafeSegment(value); return value }

        private fun move(from: File, to: File) {
            Files.move(from.toPath(), to.toPath(), StandardCopyOption.ATOMIC_MOVE)
        }
    }
}
