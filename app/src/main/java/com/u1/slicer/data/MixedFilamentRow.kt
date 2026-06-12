package com.u1.slicer.data

/**
 * One row of the full-spectrum mix recipe. Maps to one virtual filament slot the engine
 * creates by blending [components] (2..4 physical filaments) at [weights] (sum 100).
 *
 * [components] / [weights] are the single source of truth. The 2-way accessors
 * (componentA/componentB/mixBPercent) are DERIVED read-only views for code not yet
 * generalized to N — they are never stored.
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
    // BETA per-row top-surface mixing settings — defaults preserve v1 behaviour.
    val topMixMode: TopMixMode = TopMixMode.STRIPES,
    val fineTopLines: Boolean = false,
    val ironingGlaze: Boolean = false,
) {
    init {
        require(components.size in 2..4) { "a mix has 2..4 components, got ${components.size}" }
        require(weights.size == components.size) { "weights must match components" }
    }

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

    /** How a mix divides its TOP-surface lines between components (BETA). */
    enum class TopMixMode {
        /** v1 behaviour: whole lines round-robin across components. */
        STRIPES,
        /** Each line splits at the cumulative-weight boundary, brick-staggered. */
        PROPORTIONAL,
        /** Lines chopped to dashes assigned by position-based halftone. */
        DITHER,
    }

    companion object {
        /** Default label, list form. Example: autoLabel([1,2,3]) -> "E1+E2+E3". */
        fun autoLabel(components: List<Int>): String =
            components.joinToString("+") { "E$it" }

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
