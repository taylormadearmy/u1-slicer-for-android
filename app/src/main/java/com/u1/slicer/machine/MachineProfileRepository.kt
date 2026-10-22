package com.u1.slicer.machine

import android.content.Context
import com.u1.slicer.data.Printer
import com.u1.slicer.data.MachineKind
import com.u1.slicer.slice.GenericMachineProfile
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Minimal asset abstraction so profile loading can be tested without an Android Context. */
fun interface AssetProvider {
    fun readText(path: String): String
}

/** AssetProvider backed by Android's application assets. */
class ContextAssetProvider(private val context: Context) : AssetProvider {
    override fun readText(path: String): String =
        context.assets.open(path).bufferedReader().use { it.readText() }
}

/**
 * Resolves machine profiles shipped with the app and profiles cached by the catalogue.
 *
 * A missing machineProfileId deliberately means the built-in U1 profile. This keeps
 * existing PrintersConfig data valid while giving newly imported profiles a stable binding.
 */
class MachineProfileRepository(
    private val assets: AssetProvider,
    private val cacheDir: File,
) {
    /** The bundled Snapmaker U1 machine profile. Parsed at most once per repository. */
    fun builtInU1(): MachineProfile = builtInU1Internal()

    /** Finds a bundled or cached profile by its stable id. */
    fun byId(id: String): MachineProfile? {
        if (id == builtInU1().id) return builtInU1()

        // Phase 5 writes one resolved machine preset per `<id>.json`. Reading the directory
        // as a fallback also accepts cache snapshots created by development builds.
        val direct = cacheFileFor(id)?.takeIf { it.isFile }?.let(::readCached)
            ?.takeIf { it.id == id }
        if (direct != null) return direct
        return cacheDir.listFiles()
            ?.asSequence()
            ?.filter { it.isFile && it.extension.equals("json", ignoreCase = true) }
            ?.mapNotNull(::readCached)
            ?.firstOrNull { it.id == id }
    }

    /** Snapshots take precedence over catalogue IDs. An unresolved explicit binding never becomes U1. */
    fun activeFor(printer: Printer): MachineProfile {
        val profile = when {
            printer.machineProfile != null -> MachineProfile.fromSnapshot(printer.machineProfile)
            printer.machine == MachineKind.ELEGOO_CENTAURI_CARBON ->
                MachineProfile.fromSnapshot(GenericMachineProfile.centauriCarbon())
            printer.machineProfileId != null -> requireNotNull(byId(printer.machineProfileId)) {
                "Machine profile '${printer.machineProfileId}' is unavailable. Choose a profile before slicing."
            }
            printer.machine in setOf(MachineKind.GENERIC_KLIPPER, MachineKind.GENERIC_MARLIN) ->
                throw IllegalArgumentException("Choose a machine profile before slicing")
            else -> builtInU1()
        }
        when (printer.machine) {
            MachineKind.GENERIC_KLIPPER, MachineKind.GENERIC_MARLIN -> {
                val expectedFlavor = if (printer.machine == MachineKind.GENERIC_KLIPPER) GcodeFlavor.KLIPPER else GcodeFlavor.MARLIN
                require(profile.family == MachineFamily.GENERIC && profile.gcodeFlavor == expectedFlavor) {
                    "Machine firmware must match the selected profile"
                }
            }
            MachineKind.SNAPMAKER_U1 -> require(profile.family == MachineFamily.SNAPMAKER) {
                "Select a Snapmaker U1 machine profile"
            }
            else -> Unit
        }
        return profile
    }

    /** Older catalogue printers stored only an ID; restore the same immutable snapshot as new printers. */
    fun restoreCachedBinding(printer: Printer, inferMachine: Boolean = false): Printer {
        if (printer.machineProfile != null || printer.machineProfileId == null ||
            printer.machine !in setOf(MachineKind.GENERIC_KLIPPER, MachineKind.GENERIC_MARLIN)) return printer
        // Keep an unavailable printer visible so Settings can repair its binding.
        return runCatching {
            val cached = byId(printer.machineProfileId)?.takeIf { it.family == MachineFamily.GENERIC }
                ?: return printer
            val snapshot = GenericMachineProfile.fromPersistedJson(cached.machineJson)
            printer.copy(
                machineProfile = snapshot,
                machine = if (!inferMachine) printer.machine else if (snapshot.gcodeFlavor == "klipper") {
                    MachineKind.GENERIC_KLIPPER
                } else MachineKind.GENERIC_MARLIN,
            )
        }.getOrDefault(printer)
    }

    /** Returns the supported physical slot count for a configured printer. */
    fun extruderCountFor(printer: Printer): Int = activeFor(printer).extruderCount.coerceIn(1, 4)

    /** Atomically persists one fully resolved profile for binding from [Printer.machineProfileId]. */
    fun save(profile: MachineProfile) {
        val target = cacheFileFor(profile.id) ?: error("Invalid machine profile id: ${profile.id}")
        target.parentFile?.mkdirs()
        val temporary = File(target.parentFile, ".${target.name}.tmp-${System.nanoTime()}")
        try {
            temporary.writeText(profile.machineJson)
            Files.move(
                temporary.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    private fun builtInU1Internal(): MachineProfile {
        // The lazy cache must not recursively call builtInU1().
        return cachedU1 ?: synchronized(this) {
            cachedU1 ?: MachineProfile.fromResolvedOrcaJson(
                assets.readText(U1_MACHINE_ASSET),
                MachineFamily.SNAPMAKER,
            )?.copy(bedStlAsset = U1_BED_STL_ASSET)?.also { cachedU1 = it }
                ?: error("Bundled U1 machine profile is invalid")
        }
    }

    @Volatile
    private var cachedU1: MachineProfile? = null

    private fun cacheFileFor(id: String): File? {
        if (id.isBlank() || id != File(id).name || id.contains("..")) return null
        return File(cacheDir, "$id.json")
    }

    private fun readCached(file: File): MachineProfile? = runCatching {
        MachineProfile.fromResolvedOrcaJson(file.readText(), MachineFamily.GENERIC)
    }.getOrNull()

    companion object {
        const val U1_MACHINE_ASSET = "orca_profiles/printer/snapmaker_u1.json"
        const val U1_PROCESS_ASSET = "orca_profiles/process/standard_0.20mm.json"
        const val U1_FILAMENT_ASSET = "orca_profiles/filament/pla.json"
        const val U1_BED_STL_ASSET = "bed/u1_bed.stl"

        /** Production factory; tests should inject an [AssetProvider] directly. */
        fun fromContext(context: Context, cacheDir: File = File(context.filesDir, "machine_profiles")):
            MachineProfileRepository =
            MachineProfileRepository(ContextAssetProvider(context.applicationContext), cacheDir)
    }
}
