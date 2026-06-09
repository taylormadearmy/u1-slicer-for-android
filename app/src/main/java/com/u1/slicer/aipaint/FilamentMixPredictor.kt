package com.u1.slicer.aipaint

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sin

/**
 * Kotlin port of prusa3d/prusa-fdm-mixer (MIT) @ 75fcd72 — predicts the blended colour
 * of an FDM filament mix: a Yule-Nielsen base prediction (n=3) plus CIELAB lightness,
 * chroma and cyan-band-hue corrections weighted by a bell curve, calibrated on Prusament
 * PLA. Forward + native N-way: pass all components with their weights. Hex in / hex out.
 *
 * Accuracy is "suggested/preview" — absolute colour on a U1 with arbitrary filaments
 * is not guaranteed without calibration (deferred). Pinned to the repo's reference
 * vectors in FilamentMixPredictorTest.
 */
object FilamentMixPredictor {
    private const val YN = 3.0
    private const val L_SLOPE = -0.0477
    private const val L_INTERCEPT = -2.112
    private const val L_KINK_THRESHOLD = 15.0
    private const val L_KINK_SLOPE = -0.060
    private const val C_SLOPE = 0.2780
    private const val C_INTERCEPT = -15.580
    private const val PEAK_STRENGTH = 1.375
    private const val HUE_PEAK_DEG = 10.38
    private const val HUE_CENTER_DEG = 210.0
    private const val HUE_HALF_WIDTH_DEG = 30.0

    /** Predicted blended hex. [weights] are integers (e.g. 50/30/20); normalised internally. */
    fun predict(hexes: List<String>, weights: List<Int>): String {
        require(hexes.isNotEmpty() && hexes.size == weights.size) { "hexes/weights size mismatch" }
        val total = weights.sum().coerceAtLeast(1).toDouble()
        val ratios = weights.map { it.coerceAtLeast(0) / total }

        // Step 0 — endpoint guard
        ratios.forEachIndexed { i, r -> if (r >= 0.9999) return fmtHex(parse(hexes[i])) }

        // Step 1 — Yule-Nielsen base
        var ra = 0.0; var ga = 0.0; var ba = 0.0
        for (i in hexes.indices) {
            val (r, g, b) = parse(hexes[i])
            ra += srgbToLinear(r).pow(1.0 / YN) * ratios[i]
            ga += srgbToLinear(g).pow(1.0 / YN) * ratios[i]
            ba += srgbToLinear(b).pow(1.0 / YN) * ratios[i]
        }
        val ynR = Math.round(linearToSrgb(ra.coerceAtLeast(0.0).pow(YN))).toInt()
        val ynG = Math.round(linearToSrgb(ga.coerceAtLeast(0.0).pow(YN))).toInt()
        val ynB = Math.round(linearToSrgb(ba.coerceAtLeast(0.0).pow(YN))).toInt()
        val base = rgbToLab(ynR, ynG, ynB)

        // Step 2 — bell-curve mixing weight
        val n = hexes.size
        val w = (n.toDouble().pow(n) * ratios.fold(1.0) { acc, r -> acc * r }).coerceIn(0.0, 1.0)
        val cs = w * PEAK_STRENGTH

        // Step 3 — lightness correction
        val labs = hexes.map { val (r, g, b) = parse(it); rgbToLab(r, g, b) }
        val lGap = labs.maxOf { it[0] } - labs.minOf { it[0] }
        var lCorr = L_SLOPE * lGap + L_INTERCEPT
        if (lGap > L_KINK_THRESHOLD) lCorr += L_KINK_SLOPE * (lGap - L_KINK_THRESHOLD)
        lCorr *= cs
        val lNew = base[0] + lCorr

        // Step 4 — chroma correction
        var aOut = base[1]; var bOut = base[2]
        val predC = hypot(base[1], base[2])
        if (predC >= 0.01) {
            val targetDC = (C_SLOPE * lNew + C_INTERCEPT) * cs
            val newC = (predC + targetDC).coerceAtLeast(0.0)
            val scale = newC / predC
            aOut = base[1] * scale; bOut = base[2] * scale
        }

        // Step 5 — cyan-band hue rotation
        val newCFinal = hypot(aOut, bOut)
        if (newCFinal >= 1.0) {
            var predH = Math.toDegrees(atan2(bOut, aOut)); if (predH < 0) predH += 360.0
            if (predH >= HUE_CENTER_DEG - HUE_HALF_WIDTH_DEG && predH < HUE_CENTER_DEG + HUE_HALF_WIDTH_DEG) {
                val dist = abs(predH - HUE_CENTER_DEG)
                val falloff = (1.0 - dist / HUE_HALF_WIDTH_DEG).coerceAtLeast(0.0)
                val newH = (((predH + HUE_PEAK_DEG * falloff * w) % 360.0) + 360.0) % 360.0
                aOut = newCFinal * cos(Math.toRadians(newH))
                bOut = newCFinal * sin(Math.toRadians(newH))
            }
        }
        return labToHex(lNew, aOut, bOut)
    }

    // ---- conversions ----
    private fun parse(hex: String): Triple<Int, Int, Int> {
        val v = hex.removePrefix("#").toLong(16).toInt()
        return Triple((v shr 16) and 0xFF, (v shr 8) and 0xFF, v and 0xFF)
    }
    private fun fmtHex(rgb: Triple<Int, Int, Int>) =
        "#%02x%02x%02x".format(rgb.first.coerceIn(0, 255), rgb.second.coerceIn(0, 255), rgb.third.coerceIn(0, 255))

    private fun srgbToLinear(c8: Int): Double {
        val c = c8 / 255.0
        return if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    }
    private fun linearToSrgb(cl: Double): Double {
        val c = cl.coerceIn(0.0, 1.0)
        val v = if (c <= 0.0031308) 12.92 * c else 1.055 * c.pow(1.0 / 2.4) - 0.055
        return v * 255.0
    }
    private fun fLab(t: Double) = if (t > 0.008856) Math.cbrt(t) else 7.787 * t + 16.0 / 116.0
    private fun fLabInv(t: Double) = if (t * t * t > 0.008856) t * t * t else (t - 16.0 / 116.0) / 7.787

    private fun rgbToLab(r8: Int, g8: Int, b8: Int): DoubleArray {
        val r = srgbToLinear(r8); val g = srgbToLinear(g8); val b = srgbToLinear(b8)
        val x = (r * 0.4124564 + g * 0.3575761 + b * 0.1804375) * 100.0
        val y = (r * 0.2126729 + g * 0.7151522 + b * 0.0721750) * 100.0
        val z = (r * 0.0193339 + g * 0.1191920 + b * 0.9503041) * 100.0
        val fx = fLab(x / 95.047); val fy = fLab(y / 100.0); val fz = fLab(z / 108.883)
        return doubleArrayOf(116.0 * fy - 16.0, 500.0 * (fx - fy), 200.0 * (fy - fz))
    }
    private fun labToHex(L: Double, a: Double, b: Double): String {
        val fy = (L + 16.0) / 116.0; val fx = fy + a / 500.0; val fz = fy - b / 200.0
        val x = fLabInv(fx) * 95.047 / 100.0; val y = fLabInv(fy) * 100.0 / 100.0; val z = fLabInv(fz) * 108.883 / 100.0
        val rl = x * 3.2404542 + y * -1.5371385 + z * -0.4985314
        val gl = x * -0.9692660 + y * 1.8760108 + z * 0.0415560
        val bl = x * 0.0556434 + y * -0.2040259 + z * 1.0572252
        return fmtHex(Triple(Math.round(linearToSrgb(rl)).toInt(), Math.round(linearToSrgb(gl)).toInt(), Math.round(linearToSrgb(bl)).toInt()))
    }
}
