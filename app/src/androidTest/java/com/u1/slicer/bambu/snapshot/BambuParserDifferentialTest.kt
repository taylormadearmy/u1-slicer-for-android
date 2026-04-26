package com.u1.slicer.bambu.snapshot

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.u1.slicer.NativeLibrary
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Task 9: Corpus differential runner.
 *
 * One @Test per fixture in `app/src/androidTest/assets/`. Each loads the fixture,
 * runs both [KotlinBambuSnapshot.snapshot] and [NativeBambuSnapshot.snapshot], diffs
 * them with [BambuSnapshotDiff.diff], and asserts the diff is empty — unless the path
 * appears in `diagnostics/known-disagreements.json` under that fixture's entry.
 *
 * Initial baseline is empty: the first run is expected to surface many disagreements,
 * which Task 10 will categorise and record.
 */
@RunWith(AndroidJUnit4::class)
class BambuParserDifferentialTest {

    private val targetContext get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val assetContext get() = InstrumentationRegistry.getInstrumentation().context

    private val baseline: JSONObject by lazy {
        assetContext.assets.open("diagnostics/known-disagreements.json").use {
            it.bufferedReader().readText()
        }.let { JSONObject(it).optJSONObject("fixtures") ?: JSONObject() }
    }

    private fun runFixture(assetName: String) {
        val safeName = assetName.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val tmp = File(targetContext.cacheDir, "diff_$safeName")
        assetContext.assets.open(assetName).use { input ->
            tmp.outputStream().use { input.copyTo(it) }
        }

        val native = NativeLibrary()
        val nativeSnapshot = runBlocking { NativeBambuSnapshot.snapshot(tmp, native) }
        val kotlinSnapshot = runBlocking { KotlinBambuSnapshot.snapshot(tmp, native) }

        val diffs = BambuSnapshotDiff.diff(kotlinSnapshot, nativeSnapshot)
        val allowedPaths: Set<String> = baseline.optJSONArray(assetName)?.let { arr ->
            (0 until arr.length()).map { arr.getJSONObject(it).getString("path") }.toSet()
        } ?: emptySet()

        val diffPaths = diffs.map { it.path }.toSet()
        val unexpected = diffs.filterNot { it.path in allowedPaths }
        val stale = allowedPaths - diffPaths

        if (unexpected.isNotEmpty()) {
            val report = unexpected.joinToString("\n") {
                "  ${it.path}\n    kotlin = ${it.kotlinValue}\n    native = ${it.nativeValue}"
            }
            val staleNote = if (stale.isNotEmpty()) {
                "\n\nAlso: ${stale.size} stale baseline entries (no longer produce a diff) — " +
                    "remove from known-disagreements.json while you're in there:\n" +
                    stale.sorted().joinToString("\n") { "  - $it" }
            } else ""
            fail(
                "Unexpected diffs for $assetName (${unexpected.size}):\n$report\n\n" +
                    "If intentional, add to known-disagreements.json under fixtures.\"$assetName\"." +
                    staleNote
            )
        } else if (stale.isNotEmpty()) {
            // Test passes but allowlist has stale entries — fail anyway so Phase 1
            // work that closes baseline entries forces a baseline cleanup. Without
            // this, the allowlist quietly grows stale and inflates the accepted
            // disagreement surface.
            val report = stale.sorted().joinToString("\n") { "  - $it" }
            fail(
                "Baseline has ${stale.size} stale entries for $assetName " +
                    "(listed in known-disagreements.json but no longer produce a diff):\n$report\n\n" +
                    "Remove them from known-disagreements.json under fixtures.\"$assetName\"."
            )
        }
    }

    @Test fun coloredBenchy() = runFixture("colored_3DBenchy (1).3mf")
    @Test fun h2cBenchy() = runFixture("3DBenchy-H2C-Multi-Color.3mf")
    @Test fun buzzMultipart() = runFixture("Buzz_Multipart_3MF_Bambu.3mf")
    @Test fun buttonForS() = runFixture("Button-for-S-trousers.3mf")
    @Test fun dragonScale() = runFixture("Dragon Scale infinity.3mf")
    @Test fun dragonScale2c() = runFixture("Dragon Scale infinity-1-plate-2-colours.3mf")
    @Test fun dragonScale2cNew() = runFixture("Dragon Scale infinity-1-plate-2-colours-new-plate.3mf")
    @Test fun flarewingDragon() = runFixture("Flarewing-Dragon_100%_4FilamentMulticolor_v1.1.3mf")
    @Test fun goatGray() = runFixture("Goat ( Gray ).3mf")
    @Test fun korokMask() = runFixture("PrusaSlicer-printables-Korok_mask_4colour.3mf")
    @Test fun sensoryTwistBall() = runFixture("SENSORY+TWIST+BALL+FIDGETS+optimised.3mf")
    @Test fun shashibo() = runFixture("Shashibo-h2s-textured.3mf")
    @Test fun calibCubeDual() = runFixture("calib-cube-10-dual-colour-merged.3mf")
    @Test fun flippyFlappyMini() = runFixture("flippy+flappy+mini.3mf")
    @Test fun flippyFlappyPainted() = runFixture("flippy+flappy+mini-with-plate-painted.3mf")
    @Test fun foldyCoaster() = runFixture("foldy+coaster (1).3mf")
    @Test fun oldThreeMf() = runFixture("old.3mf")
    @Test fun skywingSeawing() = runFixture("skywing-seawing-silkwing.3mf")
    @Test fun slipSlideSpin() = runFixture("slip slide spin fidget.3mf")
    @Test fun spidermanHanging() = runFixture("spiderman-hanging-pre-cut.3mf")
    @Test fun u1AuxFanCover() = runFixture("u1-auxiliary-fan-cover-hex_mw.3mf")
}
