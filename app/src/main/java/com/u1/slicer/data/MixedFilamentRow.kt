package com.u1.slicer.data

/**
 * One row of the full-spectrum mix recipe. Maps to one virtual filament slot
 * that the engine creates from the layer-alternation of two physical filaments.
 *
 * Indices are 1-based to match the engine's filament numbering.
 * IDs are millis-since-epoch + monotonic counter (assigned by MixedFilamentManager).
 */
data class MixedFilamentRow(
    val id: Long,
    val componentA: Int,                          // 1-based physical filament index
    val componentB: Int,                          // 1-based; must differ from componentA
    val mixBPercent: Int,                         // 0..100; share of component B
    val distributionMode: MixDistributionMode,
    val label: String,                            // auto-derived "E1+E3 @ 50%" or user-renamed
    val inLibrary: Boolean,                       // false = project-scoped, true = persistent across projects
) {
    enum class MixDistributionMode {
        /** Whole-layer alternation: layer N uses component A, N+1 uses B, etc. */
        LAYER_CYCLE,
        /** Same-layer dot pattern: each layer interleaves A and B in XY. */
        SAME_LAYER_DOTS,
    }

    companion object {
        /**
         * Default label when the user hasn't renamed the row.
         * Example: autoLabel(1, 3, 50) -> "E1+E3 @ 50%"
         */
        fun autoLabel(componentA: Int, componentB: Int, mixBPercent: Int): String =
            "E$componentA+E$componentB @ $mixBPercent%"
    }
}
