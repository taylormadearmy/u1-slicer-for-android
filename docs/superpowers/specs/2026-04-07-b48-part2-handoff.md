# B48 Part 2 Handoff: Review, Test, Commit

## Status: Code complete, needs E2E verification + commit

All changes are uncommitted on `main`. The native `.so` in jniLibs matches the C++ source.

## What was fixed

### B48: H2C benchy missing green in Prepare + G-code preview
1. **GL shader** (`model.vert`): `uniform int u_UseVertexColor` → `uniform float` + `> 0.5`. Mali-G715 on Pixel 8a returned -1 for int uniform location.
2. **Native config order** (`sapil_print.cpp`): Restored original order (embedded profile → applyConfigToPrusa). Previous session's B48 fix reordered them, causing Shashibo's `enable_prime_tower=0` to override the JNI `wipeTowerEnabled=true`.
3. **filament_colour preservation** (`sapil_print.cpp`): `applyConfigToPrusa` now preserves `filament_colour` when the embedded profile set a larger array (SEMM virtual extruder count).
4. **B48 array padding** (`sapil_print.cpp`): Pads per-extruder arrays when `filament_colour.size > n_ext`.
5. **MMU triangle interleaving** (`sapil_model.cpp`): Round-robin across paint states so all colours are proportionally represented even if VBO truncates.
6. **getPreparePreviewMesh maxTriangles** (`MainActivity.kt`): Pass `MAX_DECIMATED_TRIANGLES` in the app path.
7. **SEMM toolRemapSlots** (`SlicerViewModel.kt`): H2C models (`distinctSlots >= 4 && colorMapping.size > distinctSlots`) → `null` (slicer produces physical tool indices). Normal SEMM → compacted slot list.
8. **G-code preview colorMapping** (`MainActivity.kt`, `NavGraph.kt`): H2C models → `null` (don't scramble tool colours). Normal SEMM → pass colorMapping through.
9. **computeEmbedTargetCount** (`SlicerViewModel.kt`): H2C → `colorMapping.size` (7). Normal SEMM → `distinct().size` (matches pre-B48).

### B49: Prepare preview slow reload after G-code view
1. **ViewModel mesh cache** (`SlicerViewModel.kt`): `cachedPrepareMesh` survives navigation.
2. **Parse LaunchedEffect guard** (`MainActivity.kt`): Don't null mesh when cache exists for 3MF.
3. **Rotation cache hit** (`MainActivity.kt`): Skip native call, reset `lastSetMesh=null` to force GL upload.
4. **Cache invalidation** (`SlicerViewModel.kt`): Called from `clearModel()`, `loadModel()`, `loadModelFromFile()`.

## Changed files

| File | Changes |
|------|---------|
| `model.vert` | `uniform int` → `uniform float`, `== 1` → `> 0.5` |
| `ModelRenderer.kt` | `glUniform1i` → `glUniform1f` for useVertexColor; `updateColorData` for existing-mesh recolor |
| `sapil_print.cpp` | Config order restored; filament_colour preservation; B48 array padding |
| `sapil_model.cpp` | MMU interleaved emission (round-robin across paint states) |
| `SlicerViewModel.kt` | `cachedPrepareMesh` + invalidation; `toolRemapSlots` H2C/normal split; `computeEmbedTargetCount` with `distinctSlots >= 4` threshold |
| `MainActivity.kt` | Cache params on InlineModelPreview; parse effect guard; rotation cache hit; G-code colorMapping H2C check; `NativePreviewMesh` import |
| `NavGraph.kt` | G-code colorMapping H2C check; `threeMfInfo` state collection |
| `libprusaslicer-jni.so` | Rebuilt from sapil_print.cpp + sapil_model.cpp changes |
| `BACKLOG.md` | B48 status updated, B49 added (fixed), B50 added (new) |
| `CLAUDE.md` | Test counts updated, TDD patterns added |

### New test files
| File | Tests | What |
|------|-------|------|
| `PreparePreviewCacheTest.kt` | 7 | B49 cache state machine: fresh load, tab switch, GL upload, parse effect guard |
| `PreviewColorNormalizationTest.kt` | +3 | G-code preview colour normalization for SEMM |
| `MergeThreeMfInfoTest.kt` | +5 | computeEmbedTargetCount: H2C, Korok, old.3mf, identity, duplicate |
| `NativePreparePreviewTest.kt` | +2 | H2C benchy full mesh + decimated mesh index preservation |
| `SemmSlicingTest.kt` | +2 | H2C benchy G-code tool counts + tool remap guard |
| `PreparePreviewViewModelTest.kt` | +1 | H2C benchy full pipeline green verification |

## Known-good E2E results (verified on device)

| File | targetCount | toolRemapSlots | G-code tools | Status |
|------|-------------|----------------|--------------|--------|
| H2C benchy | 7 | null | T0=120 T1=239 T2=242 T3=121 | PASS |
| old.3mf | 2 | [0,2] | T0=245 T2=176 | PASS |
| Korok mask | 3 | [0,1,3] | T0=10 T3=8 | PASS |
| calib-cube | 2 | identity | T1=27 CP=199 | PASS |
| Dragon plate 3 | 3 | [0,2,3] | T0=50 T2=53 T3=90 | PASS |
| Shashibo plate 5 | 2 | [0,3] | T0=71 T3=69 CP=551 | PASS |
| colored benchy | 4 | identity | T0>0 T1>0 | PASS |
| All others | — | — | — | PASS |

## Open questions

1. **Korok orientation**: User reports mask is "standing up" instead of "lying down" in Prepare preview. No baseline exists to confirm this is a regression vs pre-existing. Needs manual comparison with the committed code.

2. **B50 G-code preview colour swap**: G-code preview tool colours don't match Prepare preview for SEMM models. The slicer's internal paint-state→tool mapping is opaque. Filed in BACKLOG.md + GitHub issue #50.

## What the next agent should do

1. **Review** the current diff against HEAD — verify no leftover debug code, dead code, or inconsistencies
2. **Build + install** on Pixel 8a
3. **Run instrumented tests** (should be 157 pass)
4. **Run E2E batch** on the 3 key files (H2C, old.3mf, Korok) to verify known-good results
5. **Ask user** about Korok orientation — is it actually different from before B48?
6. **Commit** if all green
