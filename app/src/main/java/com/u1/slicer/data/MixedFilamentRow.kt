package com.u1.slicer.data

/**
 * One row of the full-spectrum mix recipe. Maps to one virtual filament slot the engine
 * creates by blending [components] (2..4 physical filaments) at [weights] (sum 100).
 *
 * [components] / [weights] are the single source of truth. The 2-way accessors
 * (componentA/componentB/mixBPercent) are DERIVED read-only views for code not yet
 * generalized to N — they are never stored.
 *
 * TRANSITION: a legacy 2-way secondary constructor and a 3-arg `autoLabel` overload are
 * retained so existing call sites (MixedFilamentManager, SessionState, SettingsRepository)
 * keep compiling while they are migrated in later tasks. Both are REMOVED later once no
 * caller uses them — they must not survive into the merged feature.
 *
 * Indices are 1-based to match the engine's filament numbering.
 */
data class MixedFilamentRow(
    val id: Long,
    val components: List<Int>,                    // 2..4 entries, 1-based, distinct
    val weights: List<Int>,                       // same size as components (>=1 / sum==100 enforced by MixWeights at create/edit time, not here)
    val distributionMode: MixDistributionMode,
    val label: String,
    val inLibrary: Boolean,
) {
    init {
        require(components.size in 2..4) { "a mix has 2..4 components, got ${components.size}" }
        require(weights.size == components.size) { "weights must match components" }
    }

    /**
     * TRANSITIONAL legacy 2-way constructor — delegates to the list form so existing
     * `MixedFilamentRow(id=, componentA=, componentB=, mixBPercent=, …)` call sites still
     * compile during migration. Removed in a later task. Int args disambiguate from the
     * List<Int> primary constructor.
     */
    constructor(
        id: Long, componentA: Int, componentB: Int, mixBPercent: Int,
        distributionMode: MixDistributionMode, label: String, inLibrary: Boolean,
    ) : this(
        id = id,
        components = listOf(componentA, componentB),
        weights = mixBPercent.coerceIn(0, 100).let { listOf(100 - it, it) },
        distributionMode = distributionMode, label = label, inLibrary = inLibrary,
    )

    val componentA: Int get() = components.getOrElse(0) { 1 }
    val componentB: Int get() = components.getOrElse(1) { componentA }
    /** Share (0..100) of the second component — preserves the legacy 2-way meaning. */
    val mixBPercent: Int get() = weights.getOrElse(1) { 0 }

    enum class MixDistributionMode {
        /** Whole-layer alternation: each layer uses one component per the weighted cadence. */
        LAYER_CYCLE,
        /** Same-layer dot pattern: each layer interleaves components in XY. */
        SAME_LAYER_DOTS,
    }

    companion object {
        /** Default label, list form. Example: autoLabel([1,2,3]) -> "E1+E2+E3". */
        fun autoLabel(components: List<Int>): String =
            components.joinToString("+") { "E$it" }

        /**
         * TRANSITIONAL 3-arg label overload — kept so MixedFilamentManager's existing
         * `autoLabel(componentA, componentB, mixBPercent)` calls compile during migration.
         * Removed in a later task. Returns the same list-form label.
         */
        fun autoLabel(componentA: Int, componentB: Int, @Suppress("UNUSED_PARAMETER") mixBPercent: Int): String =
            autoLabel(listOf(componentA, componentB))

        /** Build a row from legacy 2-way fields (used by JSON readers for old saves). */
        fun fromLegacy(
            id: Long,
            componentA: Int,
            componentB: Int,
            mixBPercent: Int,
            distributionMode: MixDistributionMode,
            label: String,
            inLibrary: Boolean,
        ): MixedFilamentRow {
            val p = mixBPercent.coerceIn(0, 100)
            return MixedFilamentRow(
                id = id,
                components = listOf(componentA, componentB),
                weights = listOf(100 - p, p),
                distributionMode = distributionMode,
                label = label,
                inLibrary = inLibrary,
            )
        }
    }
}

/**
 * TRANSITIONAL copy overload — provides `copy(componentA=, componentB=, mixBPercent=,
 * distributionMode=, label=)` so `MixedFilamentManager.edit()` call sites keep compiling
 * while that file is migrated to the list model in a later task.
 * REMOVED once no caller uses it.
 */
fun MixedFilamentRow.copy(
    componentA: Int,
    componentB: Int,
    mixBPercent: Int,
    distributionMode: MixedFilamentRow.MixDistributionMode = this.distributionMode,
    label: String = this.label,
): MixedFilamentRow {
    val p = mixBPercent.coerceIn(0, 100)
    return copy(
        components = listOf(componentA, componentB),
        weights = listOf(100 - p, p),
        distributionMode = distributionMode,
        label = label,
    )
}
