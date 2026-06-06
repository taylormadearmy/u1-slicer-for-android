package com.u1.slicer.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicLong

/**
 * Holds the user's full-spectrum mix recipe — both project-scoped rows
 * (cleared when a different model loads) and library rows (persistent
 * across projects).
 *
 * The serialize() method (added in Task 3) produces the engine's recipe
 * string consumed by SliceConfig.mixedFilamentDefinitions.
 *
 * Persistence is wired in Tasks 5 (project, via SessionStateRepository)
 * and 6 (library, via SettingsRepository).
 */
class MixedFilamentManager(
    private val loadProject: () -> List<MixedFilamentRow>,
    private val loadLibrary: () -> List<MixedFilamentRow>,
    private val saveProject: (List<MixedFilamentRow>) -> Unit,
    private val saveLibrary: (List<MixedFilamentRow>) -> Unit,
) {
    private val _projectMixes = MutableStateFlow(loadProject())
    private val _libraryMixes = MutableStateFlow(loadLibrary())
    val projectMixes: StateFlow<List<MixedFilamentRow>> = _projectMixes.asStateFlow()
    val libraryMixes: StateFlow<List<MixedFilamentRow>> = _libraryMixes.asStateFlow()

    // Monotonic counter to seed unique ids within a single process.
    // Combined with the millis-since-epoch base, this is collision-free
    // for any reasonable rate of mix creation.
    private val counter = AtomicLong(0)

    private fun nextId(): Long =
        System.currentTimeMillis() * 1_000L + counter.incrementAndGet()

    fun add(
        componentA: Int,
        componentB: Int,
        mixBPercent: Int,
        distributionMode: MixedFilamentRow.MixDistributionMode,
    ): MixedFilamentRow {
        require(componentA != componentB) { "componentA must differ from componentB" }
        require(mixBPercent in 0..100) { "mixBPercent must be 0..100" }
        val row = MixedFilamentRow(
            id = nextId(),
            componentA = componentA,
            componentB = componentB,
            mixBPercent = mixBPercent,
            distributionMode = distributionMode,
            label = MixedFilamentRow.autoLabel(componentA, componentB, mixBPercent),
            inLibrary = false,
        )
        _projectMixes.value = _projectMixes.value + row
        saveProject(_projectMixes.value)
        return row
    }

    fun edit(
        id: Long,
        componentA: Int,
        componentB: Int,
        mixBPercent: Int,
        distributionMode: MixedFilamentRow.MixDistributionMode,
        label: String? = null,
    ) {
        require(componentA != componentB) { "componentA must differ from componentB" }
        require(mixBPercent in 0..100) { "mixBPercent must be 0..100" }
        _projectMixes.value = _projectMixes.value.map { existing ->
            if (existing.id != id) existing
            else existing.copy(
                componentA = componentA,
                componentB = componentB,
                mixBPercent = mixBPercent,
                distributionMode = distributionMode,
                label = label
                    ?: MixedFilamentRow.autoLabel(componentA, componentB, mixBPercent),
            )
        }
        // Library copy (if any) also updates so the user's saved version stays in sync.
        _libraryMixes.value = _libraryMixes.value.map { existing ->
            if (existing.id != id) existing
            else existing.copy(
                componentA = componentA,
                componentB = componentB,
                mixBPercent = mixBPercent,
                distributionMode = distributionMode,
                label = label
                    ?: MixedFilamentRow.autoLabel(componentA, componentB, mixBPercent),
            )
        }
        saveProject(_projectMixes.value)
        saveLibrary(_libraryMixes.value)
    }

    fun delete(id: Long) {
        _projectMixes.value = _projectMixes.value.filterNot { it.id == id }
        _libraryMixes.value = _libraryMixes.value.filterNot { it.id == id }
        saveProject(_projectMixes.value)
        saveLibrary(_libraryMixes.value)
    }

    fun promoteToLibrary(id: Long) {
        val row = _projectMixes.value.firstOrNull { it.id == id } ?: return
        val promoted = row.copy(inLibrary = true)
        _projectMixes.value = _projectMixes.value.map { if (it.id == id) promoted else it }
        if (_libraryMixes.value.none { it.id == id }) {
            _libraryMixes.value = _libraryMixes.value + promoted
        }
        saveProject(_projectMixes.value)
        saveLibrary(_libraryMixes.value)
    }

    fun demoteFromLibrary(id: Long) {
        _libraryMixes.value = _libraryMixes.value.filterNot { it.id == id }
        _projectMixes.value = _projectMixes.value.map {
            if (it.id == id) it.copy(inLibrary = false) else it
        }
        saveProject(_projectMixes.value)
        saveLibrary(_libraryMixes.value)
    }

    /**
     * Engine recipe string for `mixed_filament_definitions`. Project rows
     * first, then library rows. Library rows referencing slots beyond
     * `numPhysicalFilaments` are silently skipped (incompatible with the
     * current project).
     *
     * Format mirrors `libslic3r/MixedFilament.cpp::serialize_custom_entries`:
     *   <a>,<b>,<enabled>,<custom>,<mix_b_pct>,<pointillism>,g,w,m<dist>,z0,xa0,xb0,d0,o0,u<id>
     * separated by `;`. Empty string when no rows are present (engine treats
     * this as "no mixing").
     */
    fun serialize(numPhysicalFilaments: Int): String {
        val rows = mutableListOf<String>()
        for (r in _projectMixes.value) rows.add(serializeRow(r))
        for (r in _libraryMixes.value) {
            if (r.componentA > numPhysicalFilaments || r.componentB > numPhysicalFilaments) continue
            // Skip library rows already promoted from project (they're in both lists).
            if (_projectMixes.value.any { it.id == r.id }) continue
            rows.add(serializeRow(r))
        }
        return rows.joinToString(";")
    }

    private fun serializeRow(r: MixedFilamentRow): String {
        val distMode = when (r.distributionMode) {
            MixedFilamentRow.MixDistributionMode.LAYER_CYCLE -> 0
            MixedFilamentRow.MixDistributionMode.SAME_LAYER_DOTS -> 1
        }
        // enabled=1, custom=1 for any user-created row.
        // pointillism_all_filaments=0 (not used in v1).
        // gradient_component_ids = empty, gradient_component_weights = empty (no gradient in v1).
        // local_z_max_sublayers=0, surface_offsets=0, deleted=0, origin_auto=0.
        return "${r.componentA},${r.componentB},1,1,${r.mixBPercent},0,g,w,m$distMode,z0,xa0,xb0,d0,o0,u${r.id}"
    }
}
