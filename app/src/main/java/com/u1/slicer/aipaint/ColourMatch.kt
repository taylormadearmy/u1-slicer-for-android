package com.u1.slicer.aipaint

import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Pure colour helpers for Phase B. Naive RGB blend predicts a mix's display colour (good
 * enough for matching + swatches; M4 replaces it with prusa-fdm-mixer). CIELAB ΔE76 ranks
 * how close a target colour is to each palette entry.
 */
object ColourMatch {
    private fun parse(hex: String): Triple<Int, Int, Int> {
        val h = hex.removePrefix("#")
        val v = h.toLong(16).toInt()
        return Triple((v shr 16) and 0xFF, (v shr 8) and 0xFF, v and 0xFF)
    }
    private fun fmt(r: Int, g: Int, b: Int) =
        "#%02X%02X%02X".format(r.coerceIn(0, 255), g.coerceIn(0, 255), b.coerceIn(0, 255))

    /** Weighted sRGB average of N hex colours. [weights] need not sum to 100 (normalized here). */
    fun naiveBlendHexMulti(colours: List<String>, weights: List<Int>): String {
        require(colours.size == weights.size && colours.isNotEmpty())
        val total = weights.sumOf { it.coerceAtLeast(0) }.coerceAtLeast(1).toDouble()
        var r = 0.0; var g = 0.0; var b = 0.0
        colours.forEachIndexed { i, hex ->
            val (cr, cg, cb) = parse(hex)
            val t = weights[i].coerceAtLeast(0) / total
            r += cr * t; g += cg * t; b += cb * t
        }
        return fmt(Math.round(r).toInt(), Math.round(g).toInt(), Math.round(b).toInt())
    }

    /** Linear interpolation in sRGB space. [pB] is 0..100, the share of [b]. */
    fun naiveBlendHex(a: String, b: String, pB: Int): String {
        val (ar, ag, ab) = parse(a); val (br, bg, bb) = parse(b)
        val t = pB.coerceIn(0, 100) / 100.0
        return fmt(
            Math.round(ar * (1 - t) + br * t).toInt(),
            Math.round(ag * (1 - t) + bg * t).toInt(),
            Math.round(ab * (1 - t) + bb * t).toInt(),
        )
    }

    private fun srgbToLin(c: Int): Double {
        val s = c / 255.0
        return if (s <= 0.04045) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4)
    }
    private fun lab(hex: String): DoubleArray {
        val (R, G, B) = parse(hex)
        val r = srgbToLin(R); val g = srgbToLin(G); val b = srgbToLin(B)
        var x = (r * 0.4124 + g * 0.3576 + b * 0.1805) / 0.95047
        var y = (r * 0.2126 + g * 0.7152 + b * 0.0722) / 1.0
        var z = (r * 0.0193 + g * 0.1192 + b * 0.9505) / 1.08883
        fun f(t: Double) = if (t > 0.008856) t.pow(1.0 / 3.0) else 7.787 * t + 16.0 / 116.0
        x = f(x); y = f(y); z = f(z)
        return doubleArrayOf(116 * y - 16, 500 * (x - y), 200 * (y - z))
    }

    /** CIE76 ΔE between two hex colours. 0 = identical. */
    fun deltaE76(a: String, b: String): Double {
        val la = lab(a); val lb = lab(b)
        val dl = la[0] - lb[0]; val da = la[1] - lb[1]; val db = la[2] - lb[2]
        return sqrt(dl * dl + da * da + db * db)
    }

    /** Index of the palette entry nearest [target] by ΔE76. Returns 0 for an empty palette. */
    fun closestSlot(target: String, palette: List<String>): Int {
        if (palette.isEmpty()) return 0
        var best = 0; var bestD = Double.MAX_VALUE
        palette.forEachIndexed { i, hex ->
            val d = deltaE76(target, hex)
            if (d < bestD) { bestD = d; best = i }
        }
        return best
    }

    /** ΔE from [target] to its closest palette entry — used for the "no close match" threshold. */
    fun closestDistance(target: String, palette: List<String>): Double {
        if (palette.isEmpty()) return Double.MAX_VALUE
        return palette.minOf { deltaE76(target, it) }
    }
}

/**
 * For each target colour, pick the closest existing palette slot and count how many had no
 * close match (ΔE > [deltaThreshold]). Phase B does NOT create mixes — unmatched colours just
 * fall back to their closest slot and increment the count for the "create a mix" nudge.
 * Returns (slotPerTarget, unmatchedCount).
 */
fun autoAssignRegions(
    targets: List<String>,
    palette: List<String>,
    deltaThreshold: Double = 25.0,
): Pair<List<Int>, Int> {
    var unmatched = 0
    val slots = targets.map { t ->
        if (ColourMatch.closestDistance(t, palette) > deltaThreshold) unmatched++
        ColourMatch.closestSlot(t, palette)
    }
    return slots to unmatched
}
