package com.u1.slicer.data

import com.u1.slicer.aipaint.ColourMatch

/**
 * Identifies the exact catalogue filament from printer-reported RFID sync data
 * (vendor/material/subtype/colour). Conservative by design: returns a match only
 * when brand AND material agree and the colour is within a strict ΔE gate —
 * otherwise null and sync behaves exactly as before. Never guesses across brands.
 */
object FilamentLibraryMatcher {

    /** CIE76 gate — pinned by FilamentLibraryMatcherTest; tune only with new test evidence. */
    const val MAX_DELTA_E = 10.0

    /** Ranking bonus (in ΔE units) when subtype tokens appear in the entry name. */
    private const val SUBTYPE_BONUS = 3.0

    data class LibraryMatch(val entry: FilamentLibraryEntry, val deltaE: Double)

    fun match(
        library: FilamentLibrary,
        vendor: String?,
        material: String?,
        subType: String?,
        hex: String?,
    ): LibraryMatch? {
        if (vendor.isNullOrBlank() || hex.isNullOrBlank() || material.isNullOrBlank()) return null
        val vNorm = norm(vendor)
        if (vNorm.isEmpty()) return null

        val candidates = library.entries.filter { e ->
            if (e.hex == null) return@filter false
            if (!e.material.equals(material, ignoreCase = true)) return@filter false
            val bNorm = norm(e.brand)
            bNorm.isNotEmpty() && (bNorm.contains(vNorm) || vNorm.contains(bNorm))
        }
        if (candidates.isEmpty()) return null

        // Gate on raw ΔE FIRST, then rank survivors with the subtype bonus. Ranking
        // before gating would let the bonus promote an out-of-gate candidate to the
        // top and reject the whole match even when an in-gate candidate existed.
        val inGate = candidates.mapNotNull { e ->
            val dE = ColourMatch.deltaE76(hex, e.hex!!)
            if (dE > MAX_DELTA_E) return@mapNotNull null
            val rank = dE - if (subtypeMatches(subType, e.name)) SUBTYPE_BONUS else 0.0
            Triple(e, dE, rank)
        }
        val best = inGate.minByOrNull { it.third } ?: return null
        return LibraryMatch(best.first, best.second)
    }

    private fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

    private fun subtypeMatches(subType: String?, name: String): Boolean {
        if (subType.isNullOrBlank()) return false
        val nameLc = name.lowercase()
        return subType.lowercase().split(Regex("[^a-z0-9]+"))
            .filter { it.length >= 3 }
            .any { nameLc.contains(it) }
    }
}
