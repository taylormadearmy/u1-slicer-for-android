# B61 Support Settings Passthrough Fix — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix support settings (enable_support, support_type=tree, etc.) being silently dropped from Bambu 3MF files when the user selects "File" mode, even though the file's embedded profile has supports enabled.

**Architecture:** The fix adds single-color Bambu files with `enable_support=true` to the `needsPreserve` path in `ProfileEmbedder.buildConfig()`, so the file's embedded support settings survive instead of being overwritten by the standard Snapmaker process profile (which defaults to `enable_support=0`).

**Tech Stack:** Kotlin, JVM unit tests, instrumented integration test

---

## Root Cause

When a Bambu 3MF file with embedded tree supports (e.g., 火焰马里奥1.3.3mf) is loaded:

1. **`buildProfileOverridesImpl()`** (SlicerViewModel.kt:3297) correctly omits support keys when `supports.mode == USE_FILE && hasSourceConfig == true` — this is the B10 fix to let the file's embedded values survive.

2. **`ProfileEmbedder.buildConfig()`** decides the merge strategy:
   - **Preserve path** (line 208): starts with `sourceConfig`, overlays hardware → file's support settings survive
   - **Standard path** (line 216): starts empty, stacks `printerProfile + processProfile + filamentProfile` → `processProfile` has `enable_support=0` which stomps the file's value

3. The **`needsPreserve`** condition (line 200-206) requires multi-extruder, layer-tool changes, paint data, multi-plate, or paint supports. A **single-color, single-plate Bambu file with tree supports** meets NONE of these conditions.

4. So the standard path runs, `processProfile` writes `enable_support=0`, and no user override is emitted (because the guard at step 1 intentionally omitted them). **Tree supports vanish silently.**

**Fix:** The simplest, most targeted fix is to detect when the sourceConfig has `enable_support` set and route through the preserve path. This is consistent with the existing pattern (B57 added `hasPaintSupports` for the same reason).

---

## File Structure

| File | Action | Responsibility |
|------|--------|----------------|
| `app/src/test/java/com/u1/slicer/bambu/ProfileEmbedderTest.kt` | Modify | Add failing test for single-color Bambu with supports |
| `app/src/main/java/com/u1/slicer/bambu/ProfileEmbedder.kt` | Modify | Add `sourceHasSupports` to `needsPreserve` condition |
| `app/src/androidTest/java/com/u1/slicer/slicing/SlicingIntegrationTest.kt` | Modify | Add E2E test: load Mario file, slice with "file" supports, verify G-code has support structures |

---

### Task 1: Write RED failing unit test

**Files:**
- Modify: `app/src/test/java/com/u1/slicer/bambu/ProfileEmbedderTest.kt`

- [ ] **Step 1: Read the existing ProfileEmbedderTest to understand test patterns**

Read `app/src/test/java/com/u1/slicer/bambu/ProfileEmbedderTest.kt` — note how it creates `ThreeMfInfo` and sourceConfig for existing tests.

- [ ] **Step 2: Write failing test**

Add this test to `ProfileEmbedderTest.kt`. The test verifies that when a single-color Bambu file has `enable_support=1` in its sourceConfig, the preserve path is used and the support setting survives into the final config:

```kotlin
@Test
fun `B61 single-color Bambu file with supports uses preserve path`() {
    // Single-color, single-plate, no paint data, no layer-tool changes —
    // but sourceConfig has enable_support=1 and support_type=tree(1).
    // Without the fix, the standard path overwrites with processProfile's enable_support=0.
    val info = ThreeMfInfo(
        isBambu = true,
        detectedExtruderCount = 1,
        hasLayerToolChanges = false,
        hasPaintData = false,
        isMultiPlate = false,
        hasPaintSupports = false,
        plates = emptyList(),
        objectExtruderMap = emptyMap()
    )
    val sourceConfig = mapOf<String, Any>(
        "enable_support" to "1",
        "support_type" to "tree(1)",
        "support_threshold_angle" to "40",
        "tree_support_branch_angle" to "40",
        "tree_support_branch_distance" to "5",
        "tree_support_branch_diameter" to "5",
        "layer_height" to "0.2",
        "initial_layer_print_height" to "0.2",
        "sparse_infill_density" to "15%"
    )

    // buildConfig with sourceConfig that has supports enabled
    // The preserve path should keep enable_support=1 from sourceConfig
    // The standard path would overlay processProfile's enable_support=0
    val result = embedder.buildConfig(
        info = info,
        sourceConfig = sourceConfig,
        filamentSettings = emptyMap(),
        overrides = emptyMap(),  // no overrides — "File" mode
        targetExtruderCount = 1
    )

    assertEquals("Supports must be preserved from source config",
        "1", result["enable_support"]?.toString())
    assertEquals("Tree support type must be preserved from source config",
        "tree(1)", result["support_type"]?.toString())
}
```

- [ ] **Step 3: Run test to verify it fails (RED)**

```bash
./gradlew testDebugUnitTest --tests "com.u1.slicer.bambu.ProfileEmbedderTest.B61*" --no-daemon
```

Expected: FAIL — `enable_support` will be `"0"` (from processProfile) instead of `"1"`.

- [ ] **Step 4: Commit the failing test**

```bash
git add app/src/test/java/com/u1/slicer/bambu/ProfileEmbedderTest.kt
git commit -m "test: B61 RED — single-color Bambu file with supports should use preserve path"
```

---

### Task 2: Fix needsPreserve condition (GREEN)

**Files:**
- Modify: `app/src/main/java/com/u1/slicer/bambu/ProfileEmbedder.kt`

- [ ] **Step 1: Read the current needsPreserve condition**

Read `app/src/main/java/com/u1/slicer/bambu/ProfileEmbedder.kt` lines 198-206.

- [ ] **Step 2: Add sourceConfig support detection to needsPreserve**

The `buildConfig` function signature already receives `sourceConfig: Map<String, Any>?`. Add a check:

Change lines 200-206 from:

```kotlin
val needsPreserve = info.isBambu && (
    info.detectedExtruderCount > 1 ||
    info.hasLayerToolChanges ||
    (info.hasPaintData && targetExtruderCount > 1) ||
    info.isMultiPlate ||
    info.hasPaintSupports  // B57: single-color with support painting needs embedded config preserved
)
```

to:

```kotlin
val sourceHasSupports = sourceConfig?.get("enable_support")?.toString() == "1"
val needsPreserve = info.isBambu && (
    info.detectedExtruderCount > 1 ||
    info.hasLayerToolChanges ||
    (info.hasPaintData && targetExtruderCount > 1) ||
    info.isMultiPlate ||
    info.hasPaintSupports ||  // B57: single-color with support painting needs embedded config preserved
    sourceHasSupports  // B61: single-color file with supports enabled — preserve so processProfile doesn't stomp
)
```

- [ ] **Step 3: Run unit test to verify it passes (GREEN)**

```bash
./gradlew testDebugUnitTest --tests "com.u1.slicer.bambu.ProfileEmbedderTest" --no-daemon
```

Expected: all ProfileEmbedder tests PASS including the new B61 test.

- [ ] **Step 4: Run full unit test suite to check for regressions**

```bash
./gradlew testDebugUnitTest --no-daemon
```

Expected: all tests PASS.

- [ ] **Step 5: Commit the fix**

```bash
git add app/src/main/java/com/u1/slicer/bambu/ProfileEmbedder.kt
git commit -m "fix: B61 preserve support settings from Bambu files in standard path"
```

---

### Task 3: Add instrumented integration test

This test loads the actual Mario 3MF file, slices it with "file" support settings, and verifies the G-code contains support structures.

**Files:**
- Modify: `app/src/androidTest/java/com/u1/slicer/slicing/SlicingIntegrationTest.kt`

**Prerequisite:** The test asset `火焰马里奥1.3.3mf` needs to be available. If not in `app/src/androidTest/assets/`, copy it there (renamed to a safe ASCII name like `fire-mario-1.3.3mf`).

- [ ] **Step 1: Read SlicingIntegrationTest.kt for the test pattern**

Read the file to understand how existing tests load files, embed profiles, slice, and check G-code. Note the helper functions (`loadNativeLib`, `sliceAndGetGcode`, etc.).

- [ ] **Step 2: Write instrumented test**

```kotlin
@Test
fun b61_bambu_file_support_settings_preserved_from_source_config() {
    // 火焰马里奥1.3.3mf: single-color Bambu file with tree supports enabled.
    // When sliced with "file" settings (no support overrides), the G-code must
    // contain support structures (;TYPE:Support lines).
    val file = copyAsset("fire-mario-1.3.3mf")
    val config = SliceConfig()
    
    // Parse the 3MF to get info and sourceConfig
    val info = ThreeMfParser.parse(file)
    val sourceConfig = java.util.zip.ZipFile(file).use { embedder.parseSourceConfig(it) }
    
    // Verify the sourceConfig actually has supports enabled
    assertEquals("sourceConfig must have enable_support=1",
        "1", sourceConfig?.get("enable_support")?.toString())
    
    // Build config with NO support overrides (simulating "File" mode)
    val overrides = emptyMap<String, String>()
    
    val result = lib.slice(config, overrides)
    assertNotNull("Slice must succeed", result)
    
    val gcode = File(result!!.gcodePath).readText()
    val supportLines = gcode.lines().count { it.contains(";TYPE:Support") }
    assertTrue("G-code must contain support structures (;TYPE:Support), got $supportLines",
        supportLines > 0)
}
```

Note: Adapt this to match the actual test helpers used in SlicingIntegrationTest. The exact helper function names and slice invocation pattern should be read from the existing tests first.

- [ ] **Step 3: Run the instrumented test on device**

```bash
ANDROID_SERIAL=43211JEKB16931 ./gradlew connectedDebugAndroidTest --tests "*.SlicingIntegrationTest.b61*" --no-daemon
```

Expected: PASS — G-code contains `;TYPE:Support` lines.

- [ ] **Step 4: Commit**

```bash
git add app/src/androidTest/java/com/u1/slicer/slicing/SlicingIntegrationTest.kt
git commit -m "test: B61 instrumented test for Bambu file support preservation"
```

---

### Task 4: Copy test asset, run full test suite, update docs

**Files:**
- Copy: `火焰马里奥1.3.3mf` → `app/src/androidTest/assets/fire-mario-1.3.3mf` (if not already there)
- Modify: `CLAUDE.md` (update test counts)
- Modify: `BACKLOG.md` (mark B61 as done)

- [ ] **Step 1: Copy test asset if needed**

```bash
cp "G:/My Drive/tes-data/火焰马里奥1.3.3mf" app/src/androidTest/assets/fire-mario-1.3.3mf
```

Check the file size — if it's very large (>10MB), consider whether a smaller test asset with tree supports would be better for CI speed. If so, create a minimal 3MF with supports enabled instead.

- [ ] **Step 2: Run full unit tests**

```bash
./gradlew testDebugUnitTest --no-daemon
```

Expected: all pass (count should increase by 1 from new ProfileEmbedder test).

- [ ] **Step 3: Run full instrumented tests**

```bash
ANDROID_SERIAL=43211JEKB16931 ./gradlew connectedDebugAndroidTest --no-daemon
```

Expected: all pass (count should increase by 1 from new SlicingIntegrationTest).

- [ ] **Step 4: Update CLAUDE.md test counts and inventory**

Update unit test total and ProfileEmbedderTest count. Update instrumented test total and SlicingIntegrationTest count + description.

- [ ] **Step 5: Add B61 to BACKLOG.md as fixed**

- [ ] **Step 6: Commit**

```bash
git add CLAUDE.md BACKLOG.md app/src/androidTest/assets/fire-mario-1.3.3mf
git commit -m "docs: B61 update test counts and backlog"
```
