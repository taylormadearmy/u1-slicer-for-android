package com.u1.slicer.data

/**
 * Pure integer-weight math for N-way mixes. Every public function returns a list that
 * sums to exactly 100 with every element >= 1 (a component is removed, never zeroed).
 * No Android dependencies — fully JVM-unit-testable.
 *
 * Mirrors the native encoding in `libslic3r/MixedFilament.cpp`:
 *   ids     -> compact "123" when all <= 9, else slash form "1/12/3" (single >9 id -> "/12")
 *   weights -> slash-joined "50/30/20"
 */
object MixWeights {

    /** Even split of [n] components summing to 100; any remainder lands on the first entries. */
    fun even(n: Int): List<Int> {
        require(n >= 1)
        val base = 100 / n
        val rem = 100 - base * n
        return (0 until n).map { base + if (it < rem) 1 else 0 }
    }

    /** Scale arbitrary positive weights to sum 100 with a floor of 1 per element. */
    fun normalize(weights: List<Int>): List<Int> {
        require(weights.isNotEmpty())
        val clamped = weights.map { it.coerceAtLeast(1) }
        val total = clamped.sum()
        if (total == 100) return clamped
        val scaled = clamped.map { it * 100.0 / total }
        val floors = scaled.map { kotlin.math.floor(it).toInt().coerceAtLeast(1) }
        var deficit = 100 - floors.sum()
        val order = scaled.indices.sortedByDescending { scaled[it] - kotlin.math.floor(scaled[it]) }
        val out = floors.toMutableList()
        var i = 0
        while (deficit > 0 && order.isNotEmpty()) { out[order[i % order.size]] += 1; deficit--; i++ }
        while (deficit < 0) {
            val victim = out.indices.filter { out[it] > 1 }.maxByOrNull { out[it] } ?: break
            out[victim] -= 1; deficit++
        }
        return out
    }

    /** Lock [index] to [value] (clamped so others keep >=1); other elements scale to fill the rest. */
    fun rebalanceAfterType(weights: List<Int>, index: Int, value: Int): List<Int> {
        val n = weights.size
        if (n == 1) return listOf(100)
        val maxForIndex = 100 - (n - 1)
        val locked = value.coerceIn(1, maxForIndex)
        val remaining = 100 - locked
        val others = weights.indices.filter { it != index }
        val otherSum = others.sumOf { weights[it] }.coerceAtLeast(1)
        val scaled = others.map { (weights[it] * remaining.toDouble() / otherSum) }
        val floors = scaled.map { kotlin.math.floor(it).toInt().coerceAtLeast(1) }
        var deficit = remaining - floors.sum()
        val ord = scaled.indices.sortedByDescending { scaled[it] - kotlin.math.floor(scaled[it]) }
        val otherOut = floors.toMutableList()
        var i = 0
        while (deficit > 0 && ord.isNotEmpty()) { otherOut[ord[i % ord.size]] += 1; deficit--; i++ }
        while (deficit < 0) {
            val victim = otherOut.indices.filter { otherOut[it] > 1 }.maxByOrNull { otherOut[it] } ?: break
            otherOut[victim] -= 1; deficit++
        }
        val out = weights.toMutableList()
        out[index] = locked
        others.forEachIndexed { k, oi -> out[oi] = otherOut[k] }
        return out
    }

    /** Drag the divider after [leftIndex]: set that element to [leftValue]; the budget moves to/from
     *  its immediate right neighbour only (others untouched). */
    fun rebalanceAfterDrag(weights: List<Int>, leftIndex: Int, leftValue: Int): List<Int> {
        require(leftIndex in 0 until weights.size - 1)
        val pairSum = weights[leftIndex] + weights[leftIndex + 1]
        val left = leftValue.coerceIn(1, pairSum - 1)
        val out = weights.toMutableList()
        out[leftIndex] = left
        out[leftIndex + 1] = pairSum - left
        return out
    }

    /** Append a new component at an even share; existing weights renormalize to make room. */
    fun addEven(weights: List<Int>): List<Int> {
        val n = weights.size + 1
        val target = (100.0 / n)
        val existing = weights.map { (it * (100 - target) / 100.0) }
        val combined = existing + target
        return normalize(combined.map { kotlin.math.round(it).toInt() })
    }

    /** Remove [index] and renormalize the rest. Caller guarantees result keeps >= 2 components. */
    fun remove(weights: List<Int>, index: Int): List<Int> =
        normalize(weights.filterIndexed { i, _ -> i != index })

    fun encodeIds(ids: List<Int>): String {
        val extended = ids.any { it > 9 }
        if (extended && ids.size == 1) return "/" + ids[0]
        return if (extended) ids.joinToString("/") else ids.joinToString("")
    }

    fun encodeWeights(weights: List<Int>): String = weights.joinToString("/")
}
