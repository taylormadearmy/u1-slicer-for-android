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
}
