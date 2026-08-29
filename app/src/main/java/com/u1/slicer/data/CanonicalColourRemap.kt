package com.u1.slicer.data

/** A durable destination for a source 3MF file colour. Never persist virtual tool ids. */
sealed interface CanonicalColourDestination {
    data class PhysicalSlot(val slot: Int) : CanonicalColourDestination
    data class Mix(val mixId: Long) : CanonicalColourDestination
}

/** Source identity is the canonical file index, not a colour value (duplicate swatches are valid). */
data class CanonicalColourRemap(
    val fileIndex: Int,
    val destination: CanonicalColourDestination,
)

/**
 * Lowers canonical source colours into Orca's full-spectrum paint tool space.
 * Physical states are 1..[physicalCount]; active mixes follow them in stable active-order.
 */
fun resolveCanonicalColourRemap(
    canonicalSize: Int,
    remaps: Collection<CanonicalColourRemap>,
    projectMixes: List<MixedFilamentRow>,
    libraryMixes: List<MixedFilamentRow>,
    physicalCount: Int = 4,
): Map<Int, Int>? {
    if (canonicalSize <= 0 || physicalCount !in 1..4) return null
    val byFile = remaps.associateBy { it.fileIndex }
    if (byFile.size != remaps.size || byFile.keys.any { it !in 0 until canonicalSize }) return null
    val active = MixSlotOrdering.activeOrder(projectMixes, libraryMixes, physicalCount)
    val mixSlots = active.mapIndexed { index, row -> row.id to physicalCount + index + 1 }.toMap()
    return (0 until canonicalSize).associateWith { fileIndex ->
        when (val destination = byFile[fileIndex]?.destination ?: CanonicalColourDestination.PhysicalSlot(fileIndex % physicalCount)) {
            is CanonicalColourDestination.PhysicalSlot ->
                if (destination.slot !in 0 until physicalCount) return null else destination.slot + 1
            is CanonicalColourDestination.Mix -> mixSlots[destination.mixId] ?: return null
        }
    }
}
