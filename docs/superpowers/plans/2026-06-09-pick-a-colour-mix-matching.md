# Pick-a-colour mix matching — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let the user pick a target colour and have the app suggest the closest mix of their loaded filaments, built on an accurate forward colour predictor (a Kotlin port of prusa-fdm-mixer).

**Architecture:** Two pure, isolated Kotlin units — `FilamentMixPredictor` (forward: filaments+weights → predicted hex, a faithful port of prusa3d/prusa-fdm-mixer @ `75fcd72`, native N-way) and `MixColourMatcher` (reverse: brute-force search of filament subsets × weight grid, scored by `ColourMatch.deltaE76`). Light UI wiring adds a "Match a colour" button + 2/3/4 count selector to the existing M4 `CreateMixSlotDialog`, reusing the existing HSV picker. No native change; no `.so` rebuild.

**Tech Stack:** Kotlin 1.9.22, Jetpack Compose/Material3, JUnit4 (JVM unit tests), AndroidJUnit4 (instrumented). Forward model ported from prusa3d/prusa-fdm-mixer (MIT).

**Worktree:** `D:\projects\u1-slicer-for-android\.claude\worktrees\pick-a-colour` (branch `feature/pick-a-colour`, off `main` @ e1790a4). Run all commands there. `--no-daemon` on gradle.

**Spec:** `docs/superpowers/specs/2026-06-09-pick-a-colour-mix-matching-design.md`

---

## File Structure

**Create:**
- `app/src/main/java/com/u1/slicer/aipaint/FilamentMixPredictor.kt` — forward predictor (port). One responsibility: predicted blended hex from components+weights.
- `app/src/main/java/com/u1/slicer/aipaint/MixColourMatcher.kt` — reverse search + `MixSuggestion`. One responsibility: target hex → best mix.
- `app/src/test/java/com/u1/slicer/aipaint/FilamentMixPredictorTest.kt`
- `app/src/test/java/com/u1/slicer/aipaint/MixColourMatcherTest.kt`
- `app/src/test/java/com/u1/slicer/ui/MatchAColourWiringTest.kt` — structural guard for the dialog wiring.
- `app/src/androidTest/java/com/u1/slicer/MatchAColourE2ETest.kt` — device flow (Task 6).

**Modify:**
- `app/src/main/java/com/u1/slicer/SlicerViewModel.kt` — mix-colour display site (~1830) → `FilamentMixPredictor`.
- `app/src/main/java/com/u1/slicer/navigation/NavGraph.kt` — mixDisplayColoursProvider (~201) → `FilamentMixPredictor`.
- `app/src/main/java/com/u1/slicer/ui/AiPaintResultScreen.kt` — slotPalette mix colour (~141) → `FilamentMixPredictor`.
- `app/src/main/java/com/u1/slicer/ui/CreateMixSlotDialog.kt` — add "Match a colour" button + count selector + matcher wiring.

**Reuse (do not modify):** `ColourMatch` (`deltaE76`, `lab`, `closestSlot`, `closestDistance`), `FilamentColorEditDialog(initialHex, onSave, onDismiss, onReset?)` + `HsvColorPicker` + `hexToHsv`/`hsvToHex`/`parseHexColor` (in `PrinterScreen.kt`), the M4 `MixedFilamentRow` + the weight-bar editor in `CreateMixSlotDialog`, `BetaPill`.

---

## Task 1: `FilamentMixPredictor` — forward model (port prusa-fdm-mixer)

**Files:**
- Create: `app/src/main/java/com/u1/slicer/aipaint/FilamentMixPredictor.kt`
- Test: `app/src/test/java/com/u1/slicer/aipaint/FilamentMixPredictorTest.kt`

> **Port note:** the code below is transcribed from prusa3d/prusa-fdm-mixer `cpp/prusa_fdm_mixer.cpp` @ commit `75fcd72f2df153fd3d0e930f38ee2ae771a1f1bc` (MIT). Before finalising, fetch that file and confirm the constants + steps match; the reference-vector test (Step 1) is the gate — it must pass to ΔE < 1.0.

- [ ] **Step 1: Write the failing test** — `FilamentMixPredictorTest.kt`:

```kotlin
package com.u1.slicer.aipaint

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FilamentMixPredictorTest {
    // ΔE between two hexes via ColourMatch (CIE76).
    private fun dE(a: String, b: String) = ColourMatch.deltaE76(a, b)

    @Test fun referenceVectors_matchWithinDeltaE1() {
        // (hexA, wA, hexB, wB, expected) from prusa-fdm-mixer test_prusa_fdm_mixer.cpp
        val cases = listOf(
            Triple("#009bc3" to "#f6b921", 50 to 50, "#519e5f"),
            Triple("#c9378c" to "#f6b921", 50 to 50, "#cc6545"),
            Triple("#009bc3" to "#c9378c", 50 to 50, "#4a5e94"),
            Triple("#252e2e" to "#e4e4e5", 50 to 50, "#636363"),
            Triple("#007a9d" to "#b32326", 50 to 50, "#434446"),
            Triple("#007a9d" to "#f6b921", 75 to 25, "#1e7c6f"),
            Triple("#347644" to "#e2deda", 75 to 25, "#4f7e5a"),
        )
        for ((colours, weights, expected) in cases) {
            val out = FilamentMixPredictor.predict(listOf(colours.first, colours.second), listOf(weights.first, weights.second))
            assertTrue("predict(${colours.first}+${colours.second} ${weights}) = $out, expected ~$expected (ΔE ${dE(out, expected)})",
                dE(out, expected) < 1.0)
        }
    }

    @Test fun endpoint_returnsThatColour() {
        assertEquals("#007a9d", FilamentMixPredictor.predict(listOf("#007a9d"), listOf(100)).lowercase())
        // a ~pure part (ratio >= 0.9999) short-circuits to that colour
        assertEquals("#007a9d", FilamentMixPredictor.predict(listOf("#007a9d", "#ffffff"), listOf(10000, 0)).lowercase())
    }

    @Test fun blueYellow_isGreenish_notGrey() {
        // subtractive: cyan-blue + yellow → green, NOT a desaturated grey
        val out = FilamentMixPredictor.predict(listOf("#009bc3", "#f6b921"), listOf(50, 50))
        assertTrue("blue+yellow should be greenish ($out)", dE(out, "#519e5f") < 3.0)
    }

    @Test fun threeWay_returnsMidtone() {
        val out = FilamentMixPredictor.predict(listOf("#009bc3", "#c9378c", "#f6b921"), listOf(34, 33, 33))
        val v = out.removePrefix("#").toLong(16)
        val sum = ((v shr 16) and 0xFF) + ((v shr 8) and 0xFF) + (v and 0xFF)
        assertTrue("3-way midtone sum=$sum", sum in 200..550)
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd "D:/projects/u1-slicer-for-android/.claude/worktrees/pick-a-colour" && ./gradlew :app:testDebugUnitTest --tests "com.u1.slicer.aipaint.FilamentMixPredictorTest" --no-daemon`
Expected: FAIL — `FilamentMixPredictor` unresolved.

- [ ] **Step 3: Implement** — create `FilamentMixPredictor.kt`:

```kotlin
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

    // ---- conversions (verbatim from prusa-fdm-mixer) ----
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
```

- [ ] **Step 4: Run tests** → `./gradlew :app:testDebugUnitTest --tests "com.u1.slicer.aipaint.FilamentMixPredictorTest" --no-daemon` → PASS. If a reference vector fails ΔE<1, diff your conversions/constants against the real `cpp/prusa_fdm_mixer.cpp` @ 75fcd72 (likely a white-point or matrix transcription slip) — do NOT loosen the ΔE tolerance.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/u1/slicer/aipaint/FilamentMixPredictor.kt app/src/test/java/com/u1/slicer/aipaint/FilamentMixPredictorTest.kt
git commit -m "feat(pick-a-colour): FilamentMixPredictor — Kotlin port of prusa-fdm-mixer (N-way forward model)"
```

---

## Task 2: Use the predictor for displayed mix colours

Swap the three display sites from `ColourMatch.naiveBlendHexMulti` to `FilamentMixPredictor.predict`. This is the standalone accuracy win.

**Files:**
- Modify: `SlicerViewModel.kt` (~1830), `navigation/NavGraph.kt` (~201), `ui/AiPaintResultScreen.kt` (~141)
- Test: `app/src/test/java/com/u1/slicer/aipaint/FilamentMixPredictorTest.kt` (add a contrast assertion)

- [ ] **Step 1: Add failing contrast test** (to FilamentMixPredictorTest.kt):

```kotlin
@Test fun predictor_differsFromNaiveAverage_forSubtractivePair() {
    val naive = ColourMatch.naiveBlendHexMulti(listOf("#009bc3", "#f6b921"), listOf(50, 50))
    val pred = FilamentMixPredictor.predict(listOf("#009bc3", "#f6b921"), listOf(50, 50))
    org.junit.Assert.assertNotEquals("predictor must differ from the naive sRGB average", naive, pred)
}
```

- [ ] **Step 2: Run** → confirms it passes (documents the difference) and that both APIs exist.

- [ ] **Step 3: Swap the call sites.** At each site, find the `ColourMatch.naiveBlendHexMulti(<hexes>, <weights>)` call used to compute a mix's display colour and replace with `FilamentMixPredictor.predict(<hexes>, <weights>)` (identical argument shapes — both take `List<String>` hexes + `List<Int>` weights). Grep to confirm exact lines: `cd "D:/projects/u1-slicer-for-android/.claude/worktrees/pick-a-colour" && grep -rn "naiveBlendHexMulti" app/src/main`. There are three production call sites (SlicerViewModel, NavGraph, AiPaintResultScreen). Leave `ColourMatch.naiveBlendHex`/`naiveBlendHexMulti` defined (used by tests / any non-mix caller) but no longer the mix predictor in production.

- [ ] **Step 4: Build + test** → `./gradlew :app:compileDebugKotlin --no-daemon && ./gradlew :app:testDebugUnitTest --tests "com.u1.slicer.aipaint.*" --no-daemon` → compile + PASS. Confirm no remaining production `naiveBlendHexMulti` mix-colour call: `grep -rn "naiveBlendHexMulti" app/src/main` should show only definitions/comments.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat(pick-a-colour): show true predicted mix colours (FilamentMixPredictor) in swatches/preview"
```

---

## Task 3: `MixColourMatcher` — reverse search

**Files:**
- Create: `app/src/main/java/com/u1/slicer/aipaint/MixColourMatcher.kt`
- Test: `app/src/test/java/com/u1/slicer/aipaint/MixColourMatcherTest.kt`

- [ ] **Step 1: Write the failing test**:

```kotlin
package com.u1.slicer.aipaint

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MixColourMatcherTest {
    // 4 loaded filaments (1-based indices map to these): red, green, blue, white
    private val loaded = listOf("#e23b3b", "#46c46a", "#3b6fe2", "#ffffff")

    @Test fun recoversKnownMix() {
        // Predict a known 2-colour mix, then ask the matcher to hit that predicted colour.
        val target = FilamentMixPredictor.predict(listOf(loaded[0], loaded[2]), listOf(60, 40))
        val s = MixColourMatcher.bestMix(target, loaded, count = 2)
        assertEquals(2, s.componentIndices.size)
        assertTrue("should recover red+blue (got ${s.componentIndices})", s.componentIndices.toSet() == setOf(1, 3))
        assertTrue("close match expected (ΔE=${s.deltaE})", s.deltaE < 3.0)
        assertEquals(100, s.weights.sum())
    }

    @Test fun respectsCount() {
        val s3 = MixColourMatcher.bestMix("#8a7f6a", loaded, count = 3)
        assertEquals(3, s3.componentIndices.size)
        assertEquals(3, s3.weights.size)
        assertEquals(100, s3.weights.sum())
    }

    @Test fun capsCountToLoaded() {
        val s = MixColourMatcher.bestMix("#888888", loaded.take(2), count = 4) // only 2 loaded
        assertTrue("count capped to loaded", s.componentIndices.size <= 2)
    }

    @Test fun closestSingleFilament_picksNearest() {
        val (idx, dE) = MixColourMatcher.closestSingleFilament("#e63c3c", loaded)
        assertEquals(1, idx) // nearest to red
        assertTrue(dE < 5.0)
    }

    @Test fun isFastEnough() {
        val start = System.nanoTime()
        repeat(10) { MixColourMatcher.bestMix("#a85b9c", loaded, count = 3) }
        val msPer = (System.nanoTime() - start) / 1e6 / 10.0
        assertTrue("avg ${msPer}ms/query should be < 250ms", msPer < 250.0)
    }
}
```

- [ ] **Step 2: Run to verify failure** → `./gradlew :app:testDebugUnitTest --tests "com.u1.slicer.aipaint.MixColourMatcherTest" --no-daemon` → FAIL (`MixColourMatcher` unresolved).

- [ ] **Step 3: Implement** — create `MixColourMatcher.kt`:

```kotlin
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
```

- [ ] **Step 4: Run tests** → PASS. If `recoversKnownMix` returns the right subset but ΔE slightly above 3.0, the predictor round-trip is fine — keep the assertion (it documents real accuracy); investigate only if the SUBSET is wrong.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/u1/slicer/aipaint/MixColourMatcher.kt app/src/test/java/com/u1/slicer/aipaint/MixColourMatcherTest.kt
git commit -m "feat(pick-a-colour): MixColourMatcher — reverse target-colour search over predictor"
```

---

## Task 4: "Match a colour" UI in CreateMixSlotDialog

**Files:**
- Modify: `app/src/main/java/com/u1/slicer/ui/CreateMixSlotDialog.kt`
- Test: `app/src/test/java/com/u1/slicer/ui/MatchAColourWiringTest.kt`

**Behaviour:** A "🎯 Match a colour" button near the top of the dialog. Tapping it shows the target picker (reuse `FilamentColorEditDialog`) + a 2/3/4 segmented count selector (default 2, options capped to `physicalFilamentColours.size`). On a target pick or count change, call `MixColourMatcher.bestMix(target, physicalFilamentColours-as-hex, count)`, then set the dialog's existing `components` and `weights` state from the suggestion, and show a closeness badge + (when `closestSingleFilament` beats the mix) a "filament E_n alone is closer" note.

- [ ] **Step 1: Add the structural guard test** — `MatchAColourWiringTest.kt`:

```kotlin
package com.u1.slicer.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class MatchAColourWiringTest {
    private fun dialogSrc(): String = listOf(
        "app/src/main/java/com/u1/slicer/ui/CreateMixSlotDialog.kt",
        "../app/src/main/java/com/u1/slicer/ui/CreateMixSlotDialog.kt",
        "src/main/java/com/u1/slicer/ui/CreateMixSlotDialog.kt",
    ).map { File(it) }.firstOrNull { it.exists() }?.readText() ?: error("CreateMixSlotDialog.kt not found")

    @Test fun hasMatchAColourEntry() {
        val s = dialogSrc()
        assertTrue("dialog must offer a Match-a-colour action", s.contains("Match a colour", ignoreCase = true))
        assertTrue("dialog must call the reverse matcher", s.contains("MixColourMatcher.bestMix"))
    }

    @Test fun hasCountSelectorAndBadge() {
        val s = dialogSrc()
        // 2/3/4 colour-count selector present
        assertTrue("count selector", s.contains("matchCount") || Regex("""\b2\s*,\s*3\s*,\s*4\b""").containsMatchIn(s))
        // closeness uses the suggestion's deltaE
        assertTrue("closeness badge from deltaE", s.contains("deltaE"))
        // single-filament fallback note
        assertTrue("single-filament note", s.contains("closestSingleFilament"))
    }
}
```

- [ ] **Step 2: Run to verify failure** → `./gradlew :app:testDebugUnitTest --tests "com.u1.slicer.ui.MatchAColourWiringTest" --no-daemon` → FAIL.

- [ ] **Step 3: Implement the UI** in `CreateMixSlotDialog`. Add state + the button + the match panel. Insert near the top of the `text = { Column(...) { ... } }`, above the `MixWeightBar`:

```kotlin
// --- Match-a-colour (pick-a-colour) state ---
var matching by remember { mutableStateOf(false) }           // target picker open
var matchCount by remember { mutableStateOf(2) }              // 2/3/4, default 2
var matchTarget by remember { mutableStateOf<String?>(null) } // last picked target hex
var matchBadge by remember { mutableStateOf<String?>(null) }  // "ΔE 6 · OK" etc.
var matchNote by remember { mutableStateOf<String?>(null) }   // single-filament note
val maxMatch = minOf(4, physicalFilamentColours.size)

fun runMatch(targetHex: String, count: Int) {
    val loadedHex = physicalFilamentColours.map { c ->
        "#%02x%02x%02x".format((c.red * 255).toInt(), (c.green * 255).toInt(), (c.blue * 255).toInt())
    }
    val s = com.u1.slicer.aipaint.MixColourMatcher.bestMix(targetHex, loadedHex, count.coerceAtMost(maxMatch))
    components = s.componentIndices
    weights = s.weights
    val q = when { s.deltaE <= 3.0 -> "good"; s.deltaE <= 8.0 -> "OK"; else -> "weak" }
    matchBadge = "ΔE ${Math.round(s.deltaE)} · $q"
    val (singleIdx, singleDe) = com.u1.slicer.aipaint.MixColourMatcher.closestSingleFilament(targetHex, loadedHex)
    matchNote = if (singleDe + 0.5 < s.deltaE) "Filament E$singleIdx alone is closer" else null
}

// Match-a-colour button
TextButton(onClick = { matching = true }) { Text("🎯 Match a colour") }
matchBadge?.let { Text("Closest mix: $it", style = MaterialTheme.typography.labelSmall) }
matchNote?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
```

Add the target picker + count selector. The count selector is a small row of 2/3/4 chips; picking a count re-runs the match if a target exists. The picker reuses `FilamentColorEditDialog`:

```kotlin
if (matching) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Colours:", style = MaterialTheme.typography.labelMedium, modifier = Modifier.align(Alignment.CenterVertically))
        for (c in 2..maxMatch) {
            FilterChip(
                selected = matchCount == c,
                onClick = { matchCount = c; matchTarget?.let { runMatch(it, c) } },
                label = { Text("$c") },
            )
        }
    }
    FilamentColorEditDialog(
        initialHex = matchTarget ?: "#888888",
        onSave = { hex -> matchTarget = hex; runMatch(hex, matchCount); matching = false },
        onDismiss = { matching = false },
    )
}
```

> **Implementation notes for the engineer:**
> - `components`/`weights` are the existing `var ... by remember { mutableStateOf(...) }` in this dialog (from M4); `runMatch` assigns to them so the existing weight-bar editor re-renders with the suggestion.
> - `FilterChip` needs `androidx.compose.material3.FilterChip` (already wildcard-imported via `androidx.compose.material3.*`). `Color.red/green/blue` extension is on `androidx.compose.ui.graphics.Color` (already imported).
> - The count selector row is shown while `matching`; you may prefer to keep it visible after a match so the user can change count and see it update — if so, gate the selector row on `matchTarget != null` instead of `matching`, and only the `FilamentColorEditDialog` on `matching`. Either is fine; keep the 2/3/4 chips + re-run-on-change behaviour.
> - Hex formatting: `physicalFilamentColours` are Compose `Color`; convert to `#rrggbb` as shown. If the project has a `Color → hex` helper, prefer it.

- [ ] **Step 4: Build + tests** → `./gradlew :app:testDebugUnitTest --tests "com.u1.slicer.ui.MatchAColourWiringTest" --no-daemon && ./gradlew :app:assembleDebug --no-daemon` → PASS + build SUCCESS.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat(pick-a-colour): Match-a-colour button + 2/3/4 count selector in Create-Mix dialog"
```

---

## Task 5: Full unit sweep + BETA scope check

- [ ] **Step 1:** Run the whole JVM unit suite: `cd "D:/projects/u1-slicer-for-android/.claude/worktrees/pick-a-colour" && ./gradlew :app:testDebugUnitTest --no-daemon`. All green. Any failure → investigate (never weaken).
- [ ] **Step 2:** Confirm the new mix-match UI sits under the existing BETA framing — the Create-Mix dialog already shows the `BetaPill` (from M4); no extra pill needed, but verify the dialog still renders it (don't remove). Grep: `grep -n "BetaPill" app/src/main/java/com/u1/slicer/ui/CreateMixSlotDialog.kt`.
- [ ] **Step 3: Commit** any test-count/doc touch-ups (see Task 7).

---

## Task 6: On-device E2E

**Files:**
- Create: `app/src/androidTest/java/com/u1/slicer/MatchAColourE2ETest.kt` OR run as a manual device check (the UI flow is screenshot-driven; a structural+slice instrumented test is the durable part).

- [ ] **Step 1:** Add an instrumented test that exercises the *engine-facing* result of a matched mix (the UI tap flow is verified manually): build a 2-colour suggestion via `MixColourMatcher.bestMix` for a target, feed its `components`/`weights` into a `MixedFilamentManager.addN(...)`, slice a painted box assigned to that mix (reuse the pattern in `MixSlotNWayBlendGateTest`), and assert the suggested component tools appear in the G-code. This proves a matched mix slices correctly end-to-end.

```kotlin
// sketch — mirror MixSlotNWayBlendGateTest setup (box, PaintedMeshWriter, makeConfig)
@Test fun matchedMix_slicesWithSuggestedTools() {
    val loaded = listOf("#FF0000", "#00FF00", "#0000FF", "#FFFF00")
    val s = MixColourMatcher.bestMix("#7a8a3a", loaded, count = 2) // olive-ish → likely red+green/yellow
    val mgr = MixedFilamentManager({ emptyList() }, { emptyList() }, {}, {})
    mgr.addN(s.componentIndices, s.weights, MixedFilamentRow.MixDistributionMode.LAYER_CYCLE)
    // ... write painted box to mix slot 4, slice, assert each suggested component's tool count > 0
}
```

- [ ] **Step 2:** Run on the Pixel 8a: `ANDROID_SERIAL=43211JEKB16931 ./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.u1.slicer.MatchAColourE2ETest --no-daemon` → PASS.
- [ ] **Step 3:** Manual device smoke (subagent or by hand): open Create-Mix → Match a colour → pick a target → suggestion fills the editor + badge shows → change count 2→3 → updates → Create → assign → slice succeeds. No physical print (upload-only if any send path is touched). Capture a screenshot of the populated suggestion.
- [ ] **Step 4: Commit** the instrumented test.

---

## Task 7: Docs + BACKLOG + version

- [ ] **Step 1:** Add a BACKLOG.md entry: "Pick-a-colour mix matching — DONE on `feature/pick-a-colour` (forward predictor port + reverse matcher + Match-a-colour UI); v1 = suggested/preview; calibration deferred." Note it resolves the M5-F "colour prediction" direction.
- [ ] **Step 2:** Update unit/instrumented test counts in `CLAUDE.md` + `README.md` (new test classes: FilamentMixPredictorTest, MixColourMatcherTest, MatchAColourWiringTest, + the instrumented MatchAColourE2ETest).
- [ ] **Step 3:** Bump `app/build.gradle` versionName/versionCode if releasing (coordinate with the user — no release/tag without explicit authorization).
- [ ] **Step 4: Commit** the doc churn.

---

## Self-Review (completed against spec)
- **Forward prediction (spec §4.1):** Task 1 (port + reference-vector test), Task 2 (swap 3 display sites). N-way confirmed native — no fold needed. ✓
- **Reverse matcher (spec §4.2):** Task 3 (`bestMix` + `closestSingleFilament`, brute force + refine, ΔE via ColourMatch). ✓
- **UI integration (spec §4.3, §3):** Task 4 — Match button, HSV picker reuse, 2/3/4 count selector (default 2, capped to loaded), auto-fill components/weights, closeness badge, single-filament note. ✓
- **Edge cases (spec §6):** count capped to loaded (Task 3 `coerceIn` + Task 3 test `capsCountToLoaded`); out-of-gamut → "weak" badge never blocks (Task 4 quality bands); single-filament closer → note (Task 4); endpoint guard (Task 1). ✓
- **Testing (spec §7):** unit predictor/matcher, structural dialog guard, instrumented slice E2E. ✓
- **Sequencing (spec §8):** Task 1+2 = forward (releasable alone); Task 3+4 = matcher+UI. ✓
- **Deferred (spec §2/§9):** calibration + photo/model sampling — not in any task (correctly out of scope). ✓
- **Type consistency:** `FilamentMixPredictor.predict(List<String>, List<Int>): String`, `MixColourMatcher.bestMix(String, List<String>, Int): MixSuggestion`, `MixSuggestion(componentIndices: List<Int> 1-based, weights: List<Int>, predictedHex, deltaE)`, `closestSingleFilament(String, List<String>): Pair<Int,Double>` — used consistently across Tasks 3, 4, 6. ✓
