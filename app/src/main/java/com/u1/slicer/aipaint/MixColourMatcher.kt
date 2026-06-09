package com.u1.slicer.aipaint

/**
 * Reverse colour search: given a target hex and the loaded filament colours, find the
 * mix (subset of [count] filaments + integer weights summing to 100) whose
 * FilamentMixPredictor colour is closest to the target by ΔE76. Brute force over filament
 * subsets × a coarse weight grid, then a fine local refine. Pure + deterministic.
 *
 * componentIndices are 1-BASED (matching MixedFilamentRow.components / the engine slot ids).
 */
object MixColourMatcher {

    /** A suggested mix. [componentIndices] 1-based; [weights] same size, sum 100. */
    data class MixSuggestion(
        val componentIndices: List<Int>,
        val weights: List<Int>,
        val predictedHex: String,
        val deltaE: Double,
    )

    /** Closest single loaded filament: (1-based index, ΔE). */
    fun closestSingleFilament(target: String, loaded: List<String>): Pair<Int, Double> {
        var bi = 1; var bd = Double.MAX_VALUE
        loaded.forEachIndexed { i, hex ->
            val d = ColourMatch.deltaE76(target, hex)
            if (d < bd) { bd = d; bi = i + 1 }
        }
        return bi to bd
    }

    fun bestMix(target: String, loaded: List<String>, count: Int): MixSuggestion {
        val k = count.coerceIn(1, loaded.size)
        var best: MixSuggestion? = null
        for (subset in combinations(loaded.indices.toList(), k)) {
            for (weights in weightGrid(k, step = 5)) {
                val sug = evaluate(target, loaded, subset, weights)
                if (best == null || sug.deltaE < best!!.deltaE) best = sug
            }
        }
        // Fine local refine (±5 in steps of 1) around the winning weights, same subset.
        best?.let { coarse ->
            val subset0 = coarse.componentIndices.map { it - 1 }
            for (weights in refineGrid(coarse.weights, span = 5)) {
                val sug = evaluate(target, loaded, subset0, weights)
                if (sug.deltaE < best!!.deltaE) best = sug
            }
        }
        return best ?: error("no candidates (loaded empty?)")
    }

    private fun evaluate(target: String, loaded: List<String>, subset: List<Int>, weights: List<Int>): MixSuggestion {
        val hexes = subset.map { loaded[it] }
        val pred = FilamentMixPredictor.predict(hexes, weights)
        return MixSuggestion(subset.map { it + 1 }, weights, pred, ColourMatch.deltaE76(target, pred))
    }

    // ---- combinatorics ----
    private fun <T> combinations(items: List<T>, k: Int): List<List<T>> {
        if (k <= 0) return listOf(emptyList())
        if (k > items.size) return emptyList()
        val out = ArrayList<List<T>>()
        fun rec(start: Int, acc: MutableList<T>) {
            if (acc.size == k) { out.add(acc.toList()); return }
            for (i in start until items.size) { acc.add(items[i]); rec(i + 1, acc); acc.removeAt(acc.size - 1) }
        }
        rec(0, ArrayList())
        return out
    }

    /** All weight vectors of length [k], each part a multiple of [step] in 1..99, summing to 100. */
    private fun weightGrid(k: Int, step: Int): List<List<Int>> {
        val out = ArrayList<List<Int>>()
        fun rec(remaining: Int, parts: Int, acc: MutableList<Int>) {
            if (parts == 1) { if (remaining in 1..99 || (k == 1 && remaining == 100)) { acc.add(remaining); out.add(acc.toList()); acc.removeAt(acc.size - 1) }; return }
            var v = step
            while (v <= remaining - (parts - 1) * step) { acc.add(v); rec(remaining - v, parts - 1, acc); acc.removeAt(acc.size - 1); v += step }
        }
        if (k == 1) return listOf(listOf(100))
        rec(100, k, ArrayList())
        return out
    }

    /** Local refine: each part within ±[span] of [base] (step 1), still summing to 100. */
    private fun refineGrid(base: List<Int>, span: Int): List<List<Int>> {
        val k = base.size
        if (k == 1) return listOf(base)
        val out = ArrayList<List<Int>>()
        fun rec(idx: Int, remaining: Int, acc: MutableList<Int>) {
            if (idx == k - 1) { if (remaining >= 1) { acc.add(remaining); out.add(acc.toList()); acc.removeAt(acc.size - 1) }; return }
            val lo = (base[idx] - span).coerceAtLeast(1); val hi = (base[idx] + span)
            var v = lo
            while (v <= hi && v <= remaining - (k - 1 - idx)) { acc.add(v); rec(idx + 1, remaining - v, acc); acc.removeAt(acc.size - 1); v++ }
        }
        rec(0, 100, ArrayList())
        return out
    }
}
