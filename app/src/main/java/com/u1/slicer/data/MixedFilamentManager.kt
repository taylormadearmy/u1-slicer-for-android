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

    fun addN(
        components: List<Int>,
        weights: List<Int>,
        distributionMode: MixedFilamentRow.MixDistributionMode,
    ): MixedFilamentRow {
        require(components.size in 2..4) { "2..4 components" }
        require(components.distinct().size == components.size) { "components must be distinct" }
        val w = MixWeights.normalize(weights)
        val row = MixedFilamentRow(
            id = nextId(), components = components, weights = w,
            distributionMode = distributionMode,
            label = MixedFilamentRow.autoLabel(components), inLibrary = false,
        )
        _projectMixes.value = _projectMixes.value + row
        saveProject(_projectMixes.value)
        return row
    }

    fun add(
        componentA: Int,
        componentB: Int,
        mixBPercent: Int,
        distributionMode: MixedFilamentRow.MixDistributionMode,
    ): MixedFilamentRow {
        require(componentA != componentB) { "componentA must differ from componentB" }
        require(mixBPercent in 0..100) { "mixBPercent must be 0..100" }
        return addN(listOf(componentA, componentB), listOf(100 - mixBPercent, mixBPercent), distributionMode)
    }

    fun editN(
        id: Long,
        components: List<Int>,
        weights: List<Int>,
        distributionMode: MixedFilamentRow.MixDistributionMode,
        label: String? = null,
    ) {
        require(components.size in 2..4) { "2..4 components" }
        require(components.distinct().size == components.size) { "components must be distinct" }
        val w = MixWeights.normalize(weights)
        fun patch(existing: MixedFilamentRow) =
            if (existing.id != id) existing
            else existing.copy(
                components = components, weights = w, distributionMode = distributionMode,
                label = label ?: MixedFilamentRow.autoLabel(components),
            )
        _projectMixes.value = _projectMixes.value.map(::patch)
        _libraryMixes.value = _libraryMixes.value.map(::patch)
        saveProject(_projectMixes.value)
        saveLibrary(_libraryMixes.value)
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
        editN(id, listOf(componentA, componentB), listOf(100 - mixBPercent, mixBPercent), distributionMode, label)
    }

    /** BETA top-surface mixing settings — copy-on-write the row in both flows and persist. */
    fun updateTopSurfaceSettings(
        id: Long,
        topMixMode: MixedFilamentRow.TopMixMode,
        fineTopLines: Boolean,
        ironingGlaze: Boolean,
    ) {
        fun patch(existing: MixedFilamentRow) =
            if (existing.id != id) existing
            else existing.copy(
                topMixMode = topMixMode,
                fineTopLines = fineTopLines,
                ironingGlaze = ironingGlaze,
            )
        _projectMixes.value = _projectMixes.value.map(::patch)
        _libraryMixes.value = _libraryMixes.value.map(::patch)
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
     *   <a>,<b>,<enabled>,<custom>,<mix_b_pct>,<pointillism>,g<ids>,w<weights>,m<dist>,z0,xa0,xb0,d0,o0,t<mode>,f<fine>,i<glaze>,u<id>
     * separated by `;`. Empty string when no rows are present (engine treats
     * this as "no mixing").
     */
    fun serialize(numPhysicalFilaments: Int): String =
        MixSlotOrdering.activeOrder(_projectMixes.value, _libraryMixes.value, numPhysicalFilaments)
            .joinToString(";") { serializeRow(it) }

    /** Number of active mix slots for the current project given [numPhysicalFilaments]. */
    fun activeMixCount(numPhysicalFilaments: Int): Int = activeOrder(numPhysicalFilaments).size

    /** The active ordering — for the picker and import auto-assign. */
    fun activeOrder(numPhysicalFilaments: Int): List<MixedFilamentRow> =
        MixSlotOrdering.activeOrder(_projectMixes.value, _libraryMixes.value, numPhysicalFilaments)

    private fun serializeRow(r: MixedFilamentRow): String {
        val distMode = when (r.distributionMode) {
            MixedFilamentRow.MixDistributionMode.LAYER_CYCLE -> 0
            MixedFilamentRow.MixDistributionMode.SAME_LAYER_DOTS -> 1
        }
        val ids = MixWeights.encodeIds(r.components)          // e.g. "123" or "1/12/3"
        val weights = MixWeights.encodeWeights(r.weights)     // e.g. "50/30/20"
        val topMode = when (r.topMixMode) {
            MixedFilamentRow.TopMixMode.STRIPES -> 0
            MixedFilamentRow.TopMixMode.PROPORTIONAL -> 1
            MixedFilamentRow.TopMixMode.DITHER -> 2
        }
        // a,b = first two components; mix_b_pct = weight of component B (legacy 2-way fallback fields).
        // gradient tokens g<ids>,w<weights> drive the N-way engine path when ids.size >= 3.
        // t/f/i = BETA top-surface settings (mode / fine top lines / ironing glaze).
        return "${r.componentA},${r.componentB},1,1,${r.mixBPercent},0," +
            "g$ids,w$weights,m$distMode,z0,xa0,xb0,d0,o0," +
            "t$topMode,f${if (r.fineTopLines) 1 else 0},i${if (r.ironingGlaze) 1 else 0},u${r.id}"
    }
}
