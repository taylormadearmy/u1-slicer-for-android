# Per-Object Metadata + SEMM Dedupe Fix Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix two Bambu 3MF handling bugs — (A) per-object metadata like `enable_support=1` being dropped during sanitization, causing Bambu files with paint-on-supports to print with no supports; (B) SEMM models with duplicate colour-to-slot mapping losing paint states, causing parts assigned to a dedupe-target extruder to render with the wrong filament.

**Architecture:** (A) Pass through source `Metadata/model_settings.config` verbatim in the no-rewrite branch of `BambuSanitizer`. (B) Change `computeEmbedTargetCount` to always use `colorMapping.size` for SEMM models (unifying the H2C and normal-SEMM paths). Existing `GcodeToolRemapper` / `composeSemmRemap` / native B48 padding already handle the downstream.

**Tech Stack:** Kotlin, JUnit 4, Android Instrumented Tests, existing `BambuSanitizer` / `ProfileEmbedder` / `GcodeToolRemapper` infrastructure.

**Spec:** [`docs/superpowers/specs/2026-04-16-per-object-metadata-and-semm-dedupe-design.md`](../specs/2026-04-16-per-object-metadata-and-semm-dedupe-design.md)

---

## File Map

| File | Action | Responsibility |
|------|--------|---------------|
| `app/src/androidTest/assets/SENSORY+TWIST+BALL+FIDGETS+optimised.3mf` | Create (copy) | Test asset for DC15 per-object-supports fix |
| `app/src/androidTest/assets/Goat ( Gray ).3mf` | Create (copy) | Test asset for Jon SEMM-dedupe fix |
| `app/src/test/java/com/u1/slicer/bambu/BambuSanitizerMetadataPreservationTest.kt` | Create | Unit test: BambuSanitizer preserves per-object non-extruder metadata |
| `app/src/main/java/com/u1/slicer/bambu/BambuSanitizer.kt` | Modify | Pass through source `model_settings.config` in no-rewrite branch |
| `app/src/androidTest/java/com/u1/slicer/slicing/SensoryTwistSupportsTest.kt` | Create | Instrumented test: Sensory Twist slices with supports |
| `app/src/test/java/com/u1/slicer/MergeThreeMfInfoTest.kt` | Modify | Update 3 `computeEmbedTargetCount` tests to assert unified `colorMapping.size` behaviour |
| `app/src/main/java/com/u1/slicer/SlicerViewModel.kt` | Modify | Change `computeEmbedTargetCount` to always use `colorMapping.size` for SEMM |
| `app/src/androidTest/java/com/u1/slicer/slicing/GoatDedupeSemmTest.kt` | Create | Instrumented test: Goat with `[0,1,2,2]` mapping emits correct tool distribution |
| `app/build.gradle` | Modify | Version bump 1.5.68 → 1.5.69 (versionCode 234 → 235) |
| `CLAUDE.md` | Modify | Update unit/instrumented test counts |
| `BACKLOG.md` | Modify | Add closed entries for the two bug fixes |

---

## Task 1: Copy test assets

**Files:**
- Create: `app/src/androidTest/assets/SENSORY+TWIST+BALL+FIDGETS+optimised.3mf`
- Create: `app/src/androidTest/assets/Goat ( Gray ).3mf`

- [ ] **Step 1: Copy the Sensory Twist 3MF**

```bash
cp "G:/My Drive/tes-data/SENSORY+TWIST+BALL+FIDGETS+optimised.3mf" \
   "app/src/androidTest/assets/SENSORY+TWIST+BALL+FIDGETS+optimised.3mf"
```

- [ ] **Step 2: Copy the Goat 3MF**

```bash
cp "G:/My Drive/tes-data/Goat ( Gray ).3mf" \
   "app/src/androidTest/assets/Goat ( Gray ).3mf"
```

- [ ] **Step 3: Verify both are present and non-empty**

```bash
ls -la "app/src/androidTest/assets/SENSORY+TWIST+BALL+FIDGETS+optimised.3mf" \
       "app/src/androidTest/assets/Goat ( Gray ).3mf"
```

Expected: both ~13 MB (Sensory Twist) and ~2.4 MB (Goat), non-zero.

---

## Task 2 (DC15): Red — unit test for BambuSanitizer per-object metadata preservation

**Files:**
- Create: `app/src/test/java/com/u1/slicer/bambu/BambuSanitizerMetadataPreservationTest.kt`

- [ ] **Step 1: Write the failing unit test**

This test builds a minimal Bambu 3MF in-memory with a single-object `model_settings.config` containing `enable_support=1`, runs `BambuSanitizer.process()`, and asserts the per-object metadata survives in the output zip.

```kotlin
package com.u1.slicer.bambu

import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class BambuSanitizerMetadataPreservationTest {

    private lateinit var tmpDir: File

    @Before
    fun setUp() {
        tmpDir = File.createTempFile("sanitizer-test", "").also {
            it.delete()
            it.mkdirs()
        }
    }

    @After
    fun tearDown() {
        tmpDir.deleteRecursively()
    }

    /** Build a minimal Bambu 3MF: project_settings.config + model_settings.config +
     *  a stub 3dmodel.model. The sanitizer identifies it as Bambu via
     *  project_settings.config presence. */
    private fun buildMinimalBambu3mf(
        modelSettingsXml: String,
        projectSettingsJson: String = """{"filament_colour":["#FFFFFF"]}"""
    ): File {
        val out = File(tmpDir, "input.3mf")
        ZipOutputStream(out.outputStream()).use { zip ->
            fun entry(name: String, bytes: ByteArray) {
                val e = ZipEntry(name).also { it.method = ZipEntry.STORED; it.size = bytes.size.toLong(); it.compressedSize = bytes.size.toLong() }
                val crc = java.util.zip.CRC32().also { it.update(bytes) }
                e.crc = crc.value
                zip.putNextEntry(e)
                zip.write(bytes)
                zip.closeEntry()
            }
            entry("[Content_Types].xml", """<?xml version="1.0" encoding="UTF-8"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"/>""".toByteArray())
            entry("3D/3dmodel.model", """<?xml version="1.0"?><model xmlns:BambuStudio="http://schemas.bambulab.com/package/2021"><resources/></model>""".toByteArray())
            entry("Metadata/project_settings.config", projectSettingsJson.toByteArray())
            entry("Metadata/model_settings.config", modelSettingsXml.toByteArray())
        }
        return out
    }

    private fun readZipEntry(file: File, name: String): String? =
        ZipFile(file).use { zip ->
            val entry = zip.getEntry(name) ?: return null
            zip.getInputStream(entry).bufferedReader().readText()
        }

    @Test
    fun preservesPerObjectEnableSupportMetadata() {
        // Model settings with a single object carrying per-object support overrides.
        val modelSettings = """<?xml version="1.0" encoding="UTF-8"?>
<config>
  <object id="2">
    <metadata key="name" value="LOW POLY SENSORY TWIST BALL FIDGET.stl"/>
    <metadata key="enable_support" value="1"/>
    <metadata key="support_type" value="tree(manual)"/>
    <metadata key="support_on_build_plate_only" value="1"/>
    <metadata key="extruder" value="1"/>
  </object>
</config>"""

        val input = buildMinimalBambu3mf(modelSettings)
        val output = BambuSanitizer.process(input, tmpDir)

        val preserved = readZipEntry(output, "Metadata/model_settings.config")
        assertNotNull("Output must contain Metadata/model_settings.config", preserved)
        assertTrue(
            "Output model_settings.config must preserve per-object enable_support=1. Got:\n$preserved",
            preserved!!.contains("""key="enable_support" value="1"""")
        )
        assertTrue(
            "Output model_settings.config must preserve per-object support_type=tree(manual). Got:\n$preserved",
            preserved.contains("""key="support_type" value="tree(manual)"""")
        )
        assertTrue(
            "Output model_settings.config must preserve per-object support_on_build_plate_only=1. Got:\n$preserved",
            preserved.contains("""key="support_on_build_plate_only" value="1"""")
        )
    }

    @Test
    fun preservesMetadataForSingleObjectSingleExtruder() {
        // This is the "no model config needed" branch — single object, single extruder,
        // but the source has per-object overrides we must preserve.
        val modelSettings = """<?xml version="1.0" encoding="UTF-8"?>
<config>
  <object id="2">
    <metadata key="name" value="test"/>
    <metadata key="seam_position" value="back"/>
    <metadata key="layer_height" value="0.12"/>
    <metadata key="extruder" value="1"/>
  </object>
</config>"""

        val input = buildMinimalBambu3mf(modelSettings)
        val output = BambuSanitizer.process(input, tmpDir)

        val preserved = readZipEntry(output, "Metadata/model_settings.config")
        assertNotNull("Output must retain model_settings.config", preserved)
        assertTrue("seam_position override must survive. Got:\n$preserved",
            preserved!!.contains("""key="seam_position" value="back""""))
        assertTrue("layer_height override must survive. Got:\n$preserved",
            preserved.contains("""key="layer_height" value="0.12""""))
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail (RED)**

```bash
./gradlew testDebugUnitTest --tests "com.u1.slicer.bambu.BambuSanitizerMetadataPreservationTest" --no-daemon
```

Expected: **FAIL** — either the file has no `Metadata/model_settings.config` (no-op branch drops it) or the preserved metadata is missing because the sanitizer regenerated a minimal version.

---

## Task 3 (DC15): Green — implement BambuSanitizer pass-through

**Files:**
- Modify: `app/src/main/java/com/u1/slicer/bambu/BambuSanitizer.kt` around lines 346-376

- [ ] **Step 1: Locate the existing no-op branch**

Find the block that currently reads:

```kotlin
                    } else {
                        // No model config needed — no-op
                    }
```

- [ ] **Step 2: Replace with pass-through**

Replace the no-op with:

```kotlin
                    } else {
                        // No extruder-based rewrite is needed, but the source may carry
                        // per-object overrides (enable_support, support_type, layer_height,
                        // seam_position, etc.) set via Bambu Studio's Objects tab.
                        // Pass the source file through verbatim so OrcaSlicer's per-object
                        // config layer sees them — otherwise they would be silently dropped
                        // (Sensory Twist Ball: paint-on-supports with no supports generated).
                        if (modelSettingsContent != null) {
                            writeStored(destZip, "Metadata/model_settings.config", modelSettingsContent!!)
                            Log.i(TAG, "Preserved source model_settings.config for per-object overrides")
                        }
                    }
```

- [ ] **Step 3: Run the unit tests to verify they pass (GREEN)**

```bash
./gradlew testDebugUnitTest --tests "com.u1.slicer.bambu.BambuSanitizerMetadataPreservationTest" --no-daemon
```

Expected: **PASS** — both tests green.

- [ ] **Step 4: Commit**

```bash
git add app/src/test/java/com/u1/slicer/bambu/BambuSanitizerMetadataPreservationTest.kt \
        app/src/main/java/com/u1/slicer/bambu/BambuSanitizer.kt
git commit -m "fix(DC15): preserve per-object metadata through BambuSanitizer

Sensory Twist Ball and similar Bambu single-object files set
enable_support / support_type as per-object overrides in
model_settings.config. Previously the sanitizer's 'no rewrite needed'
branch dropped the file entirely, so OrcaSlicer fell back to the
project-level enable_support=0 and produced no supports despite
2870 paint_supports triangles on the mesh.

Pass the source model_settings.config through verbatim when no
extruder-based restructure is required."
```

---

## Task 4 (DC15): Instrumented test — Sensory Twist slices with supports

**Files:**
- Create: `app/src/androidTest/java/com/u1/slicer/slicing/SensoryTwistSupportsTest.kt`

- [ ] **Step 1: Write the instrumented test**

Note: match the pattern used in `SlicingIntegrationTest.kt` / `BambuPipelineIntegrationTest.kt` for the native slice pipeline. The asset name contains `+` characters — use `File` APIs, not shell quoting, throughout.

```kotlin
package com.u1.slicer.slicing

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.u1.slicer.NativeLibrary
import com.u1.slicer.bambu.BambuSanitizer
import com.u1.slicer.bambu.ProfileEmbedder
import com.u1.slicer.bambu.ThreeMfParser
import com.u1.slicer.data.SliceConfig
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * DC15 fix: Sensory Twist Ball has per-object enable_support=1 and
 * 2870 paint_supports="4" triangles. Before the BambuSanitizer pass-through fix,
 * all per-object metadata was dropped and U1 produced 0 support features.
 * After the fix, the G-code must contain Support features (Bambu Studio emits 173+).
 */
@RunWith(AndroidJUnit4::class)
class SensoryTwistSupportsTest {

    private lateinit var lib: NativeLibrary
    private lateinit var cacheDir: File
    private lateinit var outDir: File
    private lateinit var embedder: ProfileEmbedder

    private fun asset(name: String): File {
        val file = File(cacheDir, name.replace("/", "_"))
        InstrumentationRegistry.getInstrumentation().context
            .assets.open(name).use { it.copyTo(file.outputStream()) }
        return file
    }

    private fun makeConfig(extCount: Int = 1) = SliceConfig(
        layerHeight = 0.2f,
        firstLayerHeight = 0.2f,
        fillDensity = 0.05f,   // match the 3MF's 5% infill
        perimeters = 2,
        supportEnabled = false, // intentionally false — fix must honour per-object override
        extruderCount = extCount,
        extruderTemps = IntArray(extCount) { 220 },
        nozzleTemp = 220,
        bedTemp = 55,
        wipeTowerEnabled = false
    )

    @Before
    fun setUp() {
        assertTrue("Native library required", NativeLibrary.isLoaded)
        lib = NativeLibrary()
        lib.clearModel()
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        cacheDir = ctx.cacheDir
        outDir = File(cacheDir, "sensory_twist_test").also { it.mkdirs() }
        embedder = ProfileEmbedder(ctx)
    }

    @After
    fun tearDown() {
        lib.clearModel()
        outDir.deleteRecursively()
    }

    @Test
    fun sensoryTwist_paintOnSupports_producesSupportGcode() {
        val input = asset("SENSORY+TWIST+BALL+FIDGETS+optimised.3mf")
        val info = ThreeMfParser.parse(input)
        assertTrue("Sensory Twist must be detected as hasPaintSupports",
            info.hasPaintSupports)

        val sanitized = BambuSanitizer.process(input, outDir)
        val embedded = embedder.embed(
            sanitized,
            embedder.buildConfig(info = info, targetExtruderCount = 1),
            outDir,
            info
        )

        assertTrue("loadModel must succeed", lib.loadModel(embedded.absolutePath))
        val result = lib.slice(makeConfig(1))
        assertNotNull("slice() must not return null", result)
        result!!
        assertTrue("Sensory Twist must slice successfully: ${result.errorMessage}",
            result.success)

        val gcode = File(result.gcodePath).readText()
        val supportCount = gcode.lines().count {
            it.trim() == "; FEATURE: Support" || it.trim() == "; FEATURE: Support interface"
        }
        Log.i("SensoryTwistTest", "Support feature count: $supportCount")
        assertTrue(
            "Sensory Twist must emit >0 Support features (paint_supports + per-object enable_support=1). Got $supportCount",
            supportCount > 0
        )
    }
}
```

- [ ] **Step 2: Run the instrumented test on Pixel 8a**

```bash
ANDROID_SERIAL=43211JEKB16931 ./gradlew connectedDebugAndroidTest \
  --tests "com.u1.slicer.slicing.SensoryTwistSupportsTest" --no-daemon
```

Expected: **PASS** — support feature count > 0.

- [ ] **Step 3: Commit**

```bash
git add app/src/androidTest/java/com/u1/slicer/slicing/SensoryTwistSupportsTest.kt \
        "app/src/androidTest/assets/SENSORY+TWIST+BALL+FIDGETS+optimised.3mf"
git commit -m "test(DC15): instrumented test — Sensory Twist emits support features"
```

---

## Task 5 (Jon): Red — update unit tests for unified SEMM targetCount

**Files:**
- Modify: `app/src/test/java/com/u1/slicer/MergeThreeMfInfoTest.kt` lines 814-836

- [ ] **Step 1: Replace the three legacy tests**

Find and replace this block:

```kotlin
    @Test
    fun `computeEmbedTargetCount SEMM with duplicate mapping uses distinct count`() {
        // 4 model colours mapped to 2 physical extruders: [0, 0, 1, 1].
        // distinct = 2 = physical count needed.
        val colorMapping = listOf(0, 0, 1, 1)
        assertEquals(2, computeEmbedTargetCount(colorMapping, hasPaintData = true, toolRemapSlots = null, fallbackExtCount = 2))
    }

    @Test
    fun `computeEmbedTargetCount old_3mf — 6 colours to 2 slots uses distinct`() {
        // old.3mf: 6 detected paint colours mapped to 2 physical slots [0,2].
        // distinct = 2. Matches pre-B48 behaviour.
        val colorMapping = listOf(0, 2, 0, 2, 0, 2)
        assertEquals(2, computeEmbedTargetCount(colorMapping, hasPaintData = true, toolRemapSlots = null, fallbackExtCount = 2))
    }

    @Test
    fun `computeEmbedTargetCount Korok — 5 colours to 3 slots uses distinct`() {
        // Korok: 5 paint colours mapped to 3 physical slots [0,1,3].
        // distinct = 3. Matches pre-B48 behaviour.
        val colorMapping = listOf(0, 0, 1, 1, 3)
        assertEquals(3, computeEmbedTargetCount(colorMapping, hasPaintData = true, toolRemapSlots = null, fallbackExtCount = 3))
    }
```

…with the updated assertions that encode the unified behaviour:

```kotlin
    @Test
    fun `computeEmbedTargetCount SEMM with duplicate mapping uses full colorMapping size`() {
        // B76: SEMM models with duplicate-slot mapping must preserve every paint state.
        // GcodeToolRemapper / semmColorPermutation compresses tools to physical slots
        // post-slice; embedding with distinct count would drop high-index paint states
        // entirely (Jon's Goat horns-on-E1 bug).
        val colorMapping = listOf(0, 0, 1, 1)
        assertEquals(4, computeEmbedTargetCount(colorMapping, hasPaintData = true, toolRemapSlots = null, fallbackExtCount = 2))
    }

    @Test
    fun `computeEmbedTargetCount old_3mf — 6 colours to 2 slots uses full colorMapping size`() {
        // B76: preserve all 6 paint states; post-slice remap compresses to 2 slots.
        val colorMapping = listOf(0, 2, 0, 2, 0, 2)
        assertEquals(6, computeEmbedTargetCount(colorMapping, hasPaintData = true, toolRemapSlots = null, fallbackExtCount = 2))
    }

    @Test
    fun `computeEmbedTargetCount Korok — 5 colours to 3 slots uses full colorMapping size`() {
        // B76: preserve all 5 paint states; post-slice remap compresses to 3 slots.
        val colorMapping = listOf(0, 0, 1, 1, 3)
        assertEquals(5, computeEmbedTargetCount(colorMapping, hasPaintData = true, toolRemapSlots = null, fallbackExtCount = 3))
    }

    @Test
    fun `computeEmbedTargetCount B76 Goat — 4 colours 3 distinct slots uses full size`() {
        // Jon's Goat (Gray).3mf scenario: per-object 4-extruder model with paint data,
        // user sets E4 to match E3 colour → mapping [0,1,2,2].  Must embed with 4
        // filaments so per-object extruder="4" parts retain a valid slot; post-slice
        // remap maps T3 → T2 (physical E3).
        val colorMapping = listOf(0, 1, 2, 2)
        assertEquals(4, computeEmbedTargetCount(colorMapping, hasPaintData = true, toolRemapSlots = null, fallbackExtCount = 3))
    }
```

- [ ] **Step 2: Run the tests to verify they fail (RED)**

```bash
./gradlew testDebugUnitTest --tests "com.u1.slicer.MergeThreeMfInfoTest.*computeEmbedTargetCount*" --no-daemon
```

Expected: **FAIL** — the four tests assert the new behaviour which isn't yet implemented.

---

## Task 6 (Jon): Green — unify SEMM computeEmbedTargetCount

**Files:**
- Modify: `app/src/main/java/com/u1/slicer/SlicerViewModel.kt` lines 3616-3639

- [ ] **Step 1: Replace the function body**

Find:

```kotlin
internal fun computeEmbedTargetCount(
    colorMapping: List<Int>?,
    hasPaintData: Boolean,
    toolRemapSlots: List<Int>?,
    fallbackExtCount: Int
): Int {
    if (hasPaintData && colorMapping != null) {
        val distinctSlots = colorMapping.distinct().size.coerceAtLeast(1)
        // B48 H2C: when all 4 physical extruders are used AND there are more model
        // colours, the slicer needs virtual extruders (one per model colour) so
        // multi_material_segmentation_by_painting() captures all paint states.
        // Without this, paint states beyond 4 are silently dropped (T1=0 for H2C).
        // The native C++ padding block handles arrays sized > physical extruders.
        // For normal SEMM models (old.3mf: 2 slots, Korok: 3 slots), use the
        // compact distinct count — matches pre-B48 behaviour.
        return if (distinctSlots >= 4 && colorMapping.size > distinctSlots) {
            colorMapping.size
        } else {
            distinctSlots
        }
    }
    if (toolRemapSlots != null) return toolRemapSlots.distinct().size
    return fallbackExtCount
}
```

Replace with:

```kotlin
internal fun computeEmbedTargetCount(
    colorMapping: List<Int>?,
    hasPaintData: Boolean,
    toolRemapSlots: List<Int>?,
    fallbackExtCount: Int
): Int {
    if (hasPaintData && colorMapping != null && colorMapping.isNotEmpty()) {
        // B76: SEMM models must always embed with the full paint-state count, so
        // multi_material_segmentation_by_painting() sees every state and produces
        // one T-command per state.  GcodeToolRemapper + semmColorPermutation then
        // compress tools down to the user's distinct physical slots post-slice.
        //
        // Unifies the prior H2C special case with normal SEMM: any duplicate-slot
        // mapping (e.g. [0,1,2,2]) previously shrunk the embed and silently dropped
        // the high-index paint state, causing per-object parts assigned to that
        // slot to land on a wrong filament (Jon's Goat horns-on-E1 bug).
        //
        // Native B48 padding handles virtual_ext > n_ext for per-filament arrays.
        return colorMapping.size
    }
    if (toolRemapSlots != null) return toolRemapSlots.distinct().size
    return fallbackExtCount
}
```

- [ ] **Step 2: Run the targeted tests to verify they pass (GREEN)**

```bash
./gradlew testDebugUnitTest --tests "com.u1.slicer.MergeThreeMfInfoTest.*computeEmbedTargetCount*" --no-daemon
```

Expected: **PASS** for all six `computeEmbedTargetCount` tests (the 2 pre-existing that were already correct + the 4 updated/new).

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/u1/slicer/SlicerViewModel.kt \
        app/src/test/java/com/u1/slicer/MergeThreeMfInfoTest.kt
git commit -m "fix(Jon): unify SEMM computeEmbedTargetCount to always use colorMapping.size

Non-H2C SEMM models with duplicate-slot mapping (e.g. Goat with
colour 4 mapped to same slot as colour 3: [0,1,2,2]) previously
shrunk the embed to distinct count (3), silently dropping the
4th paint state.  Per-object parts with extruder=\"4\" then
landed on an out-of-range filament, printing horns in E1's
filament instead of E3's.

Always embed with colorMapping.size and let GcodeToolRemapper
(already running via semmColorPermutation) compress tools to
physical slots post-slice.  Unifies the H2C and normal-SEMM paths."
```

---

## Task 7 (Jon): Instrumented test — Goat with dedupe mapping

**Files:**
- Create: `app/src/androidTest/java/com/u1/slicer/slicing/GoatDedupeSemmTest.kt`

- [ ] **Step 1: Write the instrumented test**

This test loads `Goat ( Gray ).3mf`, embeds with the user's dedupe mapping `[0,1,2,2]`, slices, and verifies:
(a) the G-code contains all 4 tool indices T0–T3 pre-remap (from the 4-filament embed), and
(b) after applying the permutation, T2 gets the combined volume of both colour 3 and colour 4.

```kotlin
package com.u1.slicer.slicing

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.u1.slicer.NativeLibrary
import com.u1.slicer.bambu.BambuSanitizer
import com.u1.slicer.bambu.ProfileEmbedder
import com.u1.slicer.bambu.ThreeMfParser
import com.u1.slicer.data.SliceConfig
import com.u1.slicer.gcode.GcodeToolRemapper
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Jon fix: Goat ( Gray ).3mf is a 4-extruder per-object Bambu model with paint_color
 * triangle attributes.  When the user sets E4 to match E3 (mapping [0,1,2,2]) the
 * horn parts (originally extruder="3" or "4" in the 3MF) previously printed on E1
 * because the 3-filament embed dropped the 4th paint state.
 *
 * This test verifies the 4-filament embed is produced (all of T0-T3 appear pre-remap)
 * and that the post-slice remap collapses T3 → T2 so the combined colour-3+colour-4
 * volume goes to physical E3.
 */
@RunWith(AndroidJUnit4::class)
class GoatDedupeSemmTest {

    private lateinit var lib: NativeLibrary
    private lateinit var cacheDir: File
    private lateinit var outDir: File
    private lateinit var embedder: ProfileEmbedder

    private fun asset(name: String): File {
        val file = File(cacheDir, name.replace("/", "_"))
        InstrumentationRegistry.getInstrumentation().context
            .assets.open(name).use { it.copyTo(file.outputStream()) }
        return file
    }

    private fun makeConfig(extCount: Int) = SliceConfig(
        layerHeight = 0.2f,
        firstLayerHeight = 0.2f,
        fillDensity = 0.15f,
        perimeters = 2,
        supportEnabled = false,
        extruderCount = extCount,
        extruderTemps = IntArray(extCount) { 220 },
        nozzleTemp = 220,
        bedTemp = 55,
        wipeTowerEnabled = extCount > 1,
        wipeTowerX = 170f,
        wipeTowerY = 140f,
        wipeTowerWidth = 60f
    )

    @Before
    fun setUp() {
        assertTrue("Native library required", NativeLibrary.isLoaded)
        lib = NativeLibrary()
        lib.clearModel()
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        cacheDir = ctx.cacheDir
        outDir = File(cacheDir, "goat_dedupe_test").also { it.mkdirs() }
        embedder = ProfileEmbedder(ctx)
    }

    @After
    fun tearDown() {
        lib.clearModel()
        outDir.deleteRecursively()
    }

    @Test
    fun goat_dedupeMapping_preservesAllFourPaintStatesAndRemapsTo3Slots() {
        val input = asset("Goat ( Gray ).3mf")
        val info = ThreeMfParser.parse(input)
        assertTrue("Goat must be detected as hasPaintData", info.hasPaintData)
        assertEquals("Goat must have 4 detected colors", 4, info.detectedColors.size)

        // User dedupes: color 4 onto same slot as color 3
        val colorMapping = listOf(0, 1, 2, 2)
        // extCount = distinct (3), but targetCount must be 4 after the fix
        val sanitized = BambuSanitizer.process(input, outDir)
        val config = embedder.buildConfig(info = info, targetExtruderCount = 4)
        val embedded = embedder.embed(sanitized, config, outDir, info)

        assertTrue("loadModel must succeed", lib.loadModel(embedded.absolutePath))
        val result = lib.slice(makeConfig(extCount = 3))
        assertNotNull("slice() must not return null", result)
        result!!
        assertTrue("Goat must slice successfully: ${result.errorMessage}", result.success)

        val gcodeBefore = File(result.gcodePath).readText()
        val usageBefore = (0..3).map { t ->
            gcodeBefore.lines().count { it.trim() == "T$t" }
        }
        Log.i("GoatDedupeTest", "Pre-remap tool counts: $usageBefore")
        assertTrue("T0 must be emitted (pre-remap) — body on E1", usageBefore[0] > 0)
        assertTrue("T1 must be emitted (pre-remap) — color 2", usageBefore[1] > 0)
        assertTrue("T2 must be emitted (pre-remap) — color 3", usageBefore[2] > 0)
        assertTrue(
            "T3 must be emitted (pre-remap) — color 4 (horns or similar); " +
            "if this fails computeEmbedTargetCount shrunk to 3 and dropped paint state 4",
            usageBefore[3] > 0
        )

        // Apply the dedupe remap
        GcodeToolRemapper.remap(result.gcodePath, colorMapping)
        val gcodeAfter = File(result.gcodePath).readText()
        val usageAfter = (0..3).map { t ->
            gcodeAfter.lines().count { it.trim() == "T$t" }
        }
        Log.i("GoatDedupeTest", "Post-remap tool counts: $usageAfter")

        // T3 → T2 after remap [0,1,2,2]
        assertEquals("T3 must disappear after dedupe remap", 0, usageAfter[3])
        assertEquals(
            "T2 must absorb T3's count (color 3 + color 4 combined onto E3)",
            usageBefore[2] + usageBefore[3], usageAfter[2]
        )
        assertEquals("T0 must be unchanged", usageBefore[0], usageAfter[0])
        assertEquals("T1 must be unchanged", usageBefore[1], usageAfter[1])
    }
}
```

- [ ] **Step 2: Run the test on Pixel 8a**

```bash
ANDROID_SERIAL=43211JEKB16931 ./gradlew connectedDebugAndroidTest \
  --tests "com.u1.slicer.slicing.GoatDedupeSemmTest" --no-daemon
```

Expected: **PASS** — T0-T3 all present before remap; T3 absorbs into T2 after.

- [ ] **Step 3: Commit**

```bash
git add app/src/androidTest/java/com/u1/slicer/slicing/GoatDedupeSemmTest.kt \
        "app/src/androidTest/assets/Goat ( Gray ).3mf"
git commit -m "test(Jon): Goat dedupe mapping preserves 4 paint states + remaps T3→T2"
```

---

## Task 8: Full unit test regression run

- [ ] **Step 1: Run all unit tests**

```bash
./gradlew testDebugUnitTest --no-daemon
```

Expected: **ALL PASS** (previously 802; now 802 + 4 new = **806**, since we added 2 for `BambuSanitizerMetadataPreservationTest` and 1 new `computeEmbedTargetCount` test + the 3 existing renamed + 0 net for the 3 existing renamed).

**Rule:** If ANY test fails, treat it as a new regression caused by the two fixes, NOT as pre-existing. The CLAUDE.md contract is "no known pre-existing failures" — honour it. Investigate, fix the code (not the test), and rerun.

- [ ] **Step 2: If failures surface, diagnose before modifying tests**

For each failure:
1. Run the single test with `--info` to see the assertion.
2. Read the test source — understand what invariant it's checking.
3. Decide: is the invariant still correct? If yes, fix the CODE. If no (test encoded buggy behaviour), fix the TEST with a WHY-comment.
4. Recommit separately with a clear message.

---

## Task 9: Full instrumented test regression run

- [ ] **Step 1: Run all instrumented tests on Pixel 8a**

```bash
ANDROID_SERIAL=43211JEKB16931 ./gradlew connectedDebugAndroidTest --no-daemon
```

Expected: **ALL PASS** (previously 172; now 174 with the two new tests).

Key tests to watch — if any of these regress, our `computeEmbedTargetCount` change broke SEMM handling:
- `SemmSlicingTest.coloredBenchy_semm_gcodeHasToolChanges`
- `SemmSlicingTest.coloredBenchy_semm_maxExtruders_notCappedAtTwo`
- `SemmSlicingTest.h2cBenchy_semm_*`
- `SemmSlicingTest.flarewingDragon_semmPermutation_remapsGcodeToolIndices`
- `BambuPipelineIntegrationTest.korokMask_*`

- [ ] **Step 2: Same no-excuses rule as Task 8**

If a test fails, fix the code.

---

## Task 10: Version bump + docs

**Files:**
- Modify: `app/build.gradle`
- Modify: `CLAUDE.md`
- Modify: `BACKLOG.md`

- [ ] **Step 1: Bump version**

In `app/build.gradle`:
```groovy
        versionCode 235
        versionName "1.5.69"
```

- [ ] **Step 2: Update CLAUDE.md test counts**

Look for `802 JVM unit tests` and `172 instrumented tests` — increment by the number of new tests added (unit: +2 for `BambuSanitizerMetadataPreservationTest`, +1 for the new `B76 Goat` test = **805**; instrumented: +1 `SensoryTwistSupportsTest` + 1 `GoatDedupeSemmTest` = **174**).

Also add line items for the new test classes in the test class summary block.

- [ ] **Step 3: Add BACKLOG entries (both closed)**

Add two entries to the Closed/Fixed section:

```markdown
### B76: SEMM duplicate-slot mapping dropped high-index paint state — FIXED v1.5.69

Jon's Goat ( Gray ).3mf (4-extruder per-object Bambu model with paint_color triangles).
Setting E4 to match E3 colour (mapping [0,1,2,2]) previously caused horn parts to
print in E1's filament.  Root cause: computeEmbedTargetCount shrunk the embed from
4 to 3 slots, losing paint state 4.  Fixed by unifying SEMM target count to always
use colorMapping.size; GcodeToolRemapper compresses tools post-slice.

### B77: Per-object metadata dropped by BambuSanitizer — FIXED v1.5.69

DC15's Sensory Twist Ball (Bambu 3MF with paint-on-supports and per-object
enable_support=1) previously sliced with no supports.  Root cause: BambuSanitizer's
"no model config rewrite needed" branch was a no-op, stripping the entire source
model_settings.config and losing per-object support/layer-height/seam overrides.
Fixed by passing the source model_settings.config through verbatim in that branch.
```

- [ ] **Step 4: Commit**

```bash
git add app/build.gradle CLAUDE.md BACKLOG.md
git commit -m "bump: v1.5.69 — B76 SEMM dedupe + B77 per-object metadata preservation"
```

---

## Task 11: Full manual E2E batch

- [ ] **Step 1: Invoke the u1-slicer-e2e-batch skill**

Use the skill via `/u1-slicer-e2e-batch` (or Skill tool with name `u1-slicer-e2e-batch`). Run the full batch. Wait for all subagents to return.

If any file regresses compared to the most recent `batch-manual-e2e-*.txt` history, treat it as a regression caused by the two fixes. Investigate, fix, re-run just that file until green. **Do not dismiss any failure as pre-existing.**

- [ ] **Step 2: If any regression, iterate**

Fix the code, re-slice on device, confirm preview + slice succeed for the regressed file. Only proceed to release once the full batch is green.

---

## Task 12: Release

- [ ] **Step 1: Switch to taylormadearmy git identity**

```bash
gh auth switch -u taylormadearmy
```

- [ ] **Step 2: Push to main**

```bash
git push
```

- [ ] **Step 3: Build release APK**

```bash
./gradlew assembleRelease --no-daemon
cp app/build/outputs/apk/release/app-release.apk u1-slicer-v1.5.69.apk
```

- [ ] **Step 4: Create GitHub release**

```bash
gh release create v1.5.69 u1-slicer-v1.5.69.apk \
  --title "v1.5.69" \
  --notes "B76: Goat ( Gray ).3mf — fix horn colour when E4 set to match E3 (SEMM duplicate-slot mapping now preserves every paint state).

B77: Sensory Twist Ball (and other Bambu 3MFs with paint-on-supports or per-object overrides) — fix per-object metadata being dropped during sanitization, so enable_support / support_type / layer_height overrides from Bambu Studio's Objects tab are honoured."
```

- [ ] **Step 5: Copy APK to G: drive for test workstation**

```bash
cp u1-slicer-v1.5.69.apk "G:/My Drive/claude/u1-slicer-v1.5.69.apk"
```

- [ ] **Step 6: Verify release is live**

```bash
gh release view v1.5.69 | head -10
```

---

## Summary

Two bugs. Two fixes. ~15 lines of production code. Four new tests (2 unit + 2 instrumented). Three existing unit tests updated. Full regression pass before shipping.
