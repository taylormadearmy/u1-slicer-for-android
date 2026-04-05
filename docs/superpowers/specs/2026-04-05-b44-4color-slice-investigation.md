# B44 Investigation: colored_3DBenchy Slices With 3 Colors Instead of 4

## Status: Needs Fix (test written, root cause narrowed)

## Regression Window
- **v1.4.9 works** (4 colors in slice output)
- **v1.4.10 broken** (only 3 colors)
- Only code change between v1.4.9 and v1.4.10: `GcodeThumbnailInjector.kt` (thumbnail selection logic)
- v1.4.9 commit: `beab09c` ("preserve 4-colour plate previews")
- v1.4.10 commit: `a191f14` ("fix printer thumbnail injection")

## What's Failing
The native slicer produces G-code with only 3 active extruders (T0, T1, T2) instead of 4 (T0-T3). The start gcode's `{if (is_extruder_used[3])}` resolves to false, confirming OrcaSlicer doesn't assign any toolpaths to extruder 3.

## Test That Catches It
`SemmSlicingTest.coloredBenchy_semm_maxExtruders_notCappedAtTwo` — asserts `SM_PRINT_AUTO_FEED` for all 4 extruders (0,1,2,3). Currently fails with `got [0, 1, 2]`.

## Root Cause Analysis

### The Paint Data
colored_3DBenchy uses Bambu H2C (dual-AMS) paint states 1-8:
```
State 0: 52K triangles (unpainted)
States 1-4 (AMS1): 174, 36K, 115K, 35K triangles
States 5-8 (AMS2): 67K, 83K, 40K, 21K triangles
```
States 5-8 should fold to extruders 0-3 (same physical filaments as states 1-4).

### Why the Slicer Only Uses 3 Colors
1. `multi_material_segmentation_by_painting()` in `MultiMaterialSegmentation.cpp:2198` computes:
   ```cpp
   num_facets_states = filament_colour.size() + 1
   ```
   With 4 filament_colour entries → `num_facets_states = 5` (states 0-4 captured, states 5-8 LOST)

2. The segmentation iterates `for (extruder_idx = 1; extruder_idx < 5)` and calls `TriangleSelector::get_facets(EnforcerBlockerType(N))` for N=1..4

3. `get_facets()` in `TriangleSelector.cpp:1430` does **exact match**: `tr.get_state() == state`
   - States 5-8 triangles are **never collected** because they don't match states 1-4

4. In `PrintObjectSlice.cpp:881`, the loop reads `segmentation[layer][0..3]` for 4 extruders:
   - Index 0 = state 0 (unpainted → extruder 0)  
   - Index 1 = state 1 → extruder 1
   - Index 2 = state 2 → extruder 2
   - Index 3 = state 3 → extruder 3
   - State 4 at index 4 is **not read** (loop stops at num_extruders=4)

5. Result: ~211K triangles (states 5-8) become unpainted, falling to default extruder. State 4 (35K triangles) is also lost. This collapses 4 intended colors into effectively 3 active extruders.

### Why v1.4.9 Worked — Likely Uninitialized Variable
The user confirmed the regression is real: v1.4.9 works, v1.4.10 doesn't. The only code change was `GcodeThumbnailInjector.kt` (thumbnail selection logic) — no paint/slicing code touched.

**Most likely theory: uninitialized C++ variable heisenbug.** The thumbnail code change altered Kotlin method layout, which shifted the native `.so`'s code/data addresses (through JNI trampoline layout changes or APK packaging differences). An uninitialized variable in the native slicer that happened to be zero in v1.4.9's memory layout now contains garbage in v1.4.10+. This is consistent with the `Print::m_origin` / `Print::m_isBBLPrinter` pattern from B38.

**Key investigation for the new agent:**
1. Check `TriangleSelector` deserialization for uninitialized state variables — does it initialize triangle states to 0 before deserializing paint_color data?
2. Check if `num_facets_states` or the extruder count used during paint segmentation comes from an uninitialized field
3. Run v1.4.9 and v1.4.10 APKs against colored_3DBenchy and diff the `SM_PRINT_AUTO_FEED` lines in G-code output
4. Consider: the fix may be an initializer in C++, not the H2C fold approach (though both may be needed)

## Approaches Tried and Failed

### 1. Increase filament_colour to 8 entries (sapil_print.cpp)
Set `filament_colour` to `max(n_ext, 8)` so `num_facets_states = 9`. This captures all H2C states in segmentation, BUT crashes in `ToolOrdering::reorder_extruders_for_minimum_flush_volume()` because the wipe tower code sees tool changes for extruders 4-7 that don't exist in the 4-extruder flush matrix. **Fundamentally unsafe** without changing many more OrcaSlicer internals.

### 2. Fold segmentation results in PrintObjectSlice.cpp
After segmentation, merge indices 4-7 into 0-3. Crashes with SIGSEGV because the segmentation arrays reference extruder IDs > 3 in other parts of the pipeline (ToolOrdering, WipeTower).

### 3. Modify TriangleSelector::get_facets() to fold H2C states
Added H2C folding directly in `get_facets()`: when querying for state N (1-4), also collect state N+4 (5-8). This approach is **promising** — it doesn't change `num_facets_states` or extruder count, keeps all pipeline assumptions intact. The SIGSEGV from approach 2 was from a different issue (approach 1's `filament_colour=8` was still active).

**Recommended next step**: Test approach 3 in isolation (only the TriangleSelector change, without the `filament_colour=8` change). This requires:
1. Revert `sapil_print.cpp` to original (already done)
2. Keep the `TriangleSelector.cpp` change (H2C fold in `get_facets`)
3. Rebuild the native `.so`
4. Run `SemmSlicingTest.coloredBenchy_semm_maxExtruders_notCappedAtTwo`

## Current Code State

### Files Modified (uncommitted)
- `app/src/main/assets/shaders/toolpath_instanced.vert` — B42+B43: hexagonal tube geometry + brighter lighting (WORKING)
- `app/src/main/java/com/u1/slicer/viewer/GcodeRenderer.kt` — B42: 36-vertex tube draw call (WORKING)
- `app/src/main/java/com/u1/slicer/viewer/ThreeMfMeshParser.kt` — B45: stride decimation uses baseTris not totalTris (WORKING)
- `app/src/main/java/com/u1/slicer/MainActivity.kt` — B45: paint routing with colorMapping gate (WORKING)
- `app/src/main/java/com/u1/slicer/bambu/ThreeMfParser.kt` — B44: paintStateCount using distinct first-char states (WORKING)
- `app/src/androidTest/.../SemmSlicingTest.kt` — regression test for 4-extruder colored_3DBenchy (FAILING as expected)
- `app/src/androidTest/.../BambuPipelineIntegrationTest.kt` — regression test for detectedColors == 4 (PASSING)
- `app/src/main/cpp/orcaslicer/src/libslic3r/TriangleSelector.cpp` — H2C fold in get_facets (UNTESTED in isolation)
- `app/src/main/cpp/orcaslicer/src/libslic3r/PrintObjectSlice.cpp` — REVERTED to original
- `app/src/main/cpp/src/sapil_print.cpp` — REVERTED to original
- `app/src/main/jniLibs/arm64-v8a/libprusaslicer-jni.so` — REVERTED to original

### Native Rebuild Notes
- Build dir without HWSan: `app/.cxx/Debug/6o2y6k1z/arm64-v8a/`
- Ninja path: `/c/Users/kevin/AppData/Local/Android/Sdk/cmake/3.22.1/bin/ninja.exe`
- Strip path: `/c/Users/kevin/AppData/Local/Android/Sdk/ndk/26.1.10909125/toolchains/llvm/prebuilt/windows-x86_64/bin/llvm-strip.exe`
- Debug .so is ~83MB (vs 19MB release) — installs on device but Gradle test runner may fail on APK install step; use manual `adb install -r` first
- Build dir `186o3e6t` has HWSan (`-fsanitize=hwaddress`) — DO NOT USE, causes dlopen failure on device
