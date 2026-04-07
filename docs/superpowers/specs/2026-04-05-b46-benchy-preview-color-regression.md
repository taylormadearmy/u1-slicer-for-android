# B46 Investigation: colored_3DBenchy Prepare Preview Missing White + Triangle Artifacts

## Status: Needs Fix (regression from v1.5.0 → v1.5.1)

**See [`2026-04-05-b46-handoff.md`](2026-04-05-b46-handoff.md) for the full handoff to the next agent.**

Broke in commit `1d9d19b` (v1.5.1) which replaced the native `getPreparePreviewMesh()` path with Kotlin `ThreeMfMeshParser` for painted models. Last working version: v1.5.0.

## Symptoms

Comparing colored_3DBenchy Prepare preview between v1.5.0 and v1.5.38:

1. **Missing color regions** — v1.5.0 shows 4 distinct colors with correct boundaries (hull color change halfway up, door detail). v1.5.38 loses these boundaries — regions show as single color.
2. **Triangle artifacts** — jagged seams/gaps visible on the hull surface in v1.5.38. v1.5.0 is clean and smooth.

## Background

colored_3DBenchy is a Bambu H2C (dual-AMS) painted model with paint states 1-8:
- AMS1 (printable): states 1-4
- AMS2 (non-printable): states 5-8 → should fold to 1-4 (same physical filaments)

Paint state distribution:
```
State 0: 52K triangles (unpainted)
States 1-4: 174, 36K, 115K, 35K triangles
States 5-8: 67K, 83K, 40K, 21K triangles
```

## Root Cause Analysis

### Issue 1: White Color Missing — H2C State Folding Bug in Preview

The Kotlin preview renderer (ThreeMfMeshParser) has a state-folding formula that maps states 5-8 to the wrong extruder indices.

**Key function: `paintIndexForState()`** (`ThreeMfMeshParser.kt:755-772`):
- Line 771 fallback: `(state - 1) % 4` 
- This maps: 5→0, 6→1, 7→2, 8→3 (0-indexed)
- But the **native slicer fix** (B44, TriangleSelector.cpp) folds state N+4 → state N, i.e. 5→1, 6→2, 7→3, 8→4

**The mismatch**: The Kotlin preview maps state 5 to extruder index 0 (red), but the native slicer maps state 5 to state 1 (which is a different color). So the preview colors don't match the slice output, and specifically, the white hull bottom (which may be state 4 or state 8) gets mapped to the wrong color.

**Compare with ThreeMfParser.kt:180** (B44 UI paint state counting):
```kotlin
val folded = if (state > 4) ((state - 1) % 4) + 1 else state
```
This correctly folds 5→1, 6→2, 7→3, 8→4 (1-indexed). The preview renderer should match.

**Fix**: Align `paintIndexForState()` with the native slicer's folding: state 5→1, 6→2, 7→3, 8→4 (then subtract 1 for 0-indexed extruder array).

### Issue 2: Triangle Artifacts — Possibly Residual from B45 or mergeH2cPairs

Two potential causes:

**A. H2C merge artifacts** (`ThreeMfMeshParser.kt:483-517`):
- `mergeH2cPairs()` merges non-printable component paint arrays into printable component paint arrays
- The merge copies paint specs from AMS2 components into AMS1 components, but the merged paint spec strings still contain the original state numbers (5-8), not folded values
- If the merge doesn't correctly align triangle indices between the printable and non-printable mesh components, triangles could get miscolored or show seams at component boundaries

**B. Stride decimation edge case** (`ThreeMfMeshParser.kt:532-550`):
- B45 fixed stride to use `baseTris` not `totalTris`, so stride should be 1 for colored_3DBenchy (225K base < 500K cap)
- If the TriangleSelector expansion creates non-watertight mesh seams at subdivision boundaries, these would show as visual artifacts
- This could explain why v1.4.8 (which may have used a different preview path) didn't show artifacts

**C. v1.4.8 may have used the native preview path**:
- The Kotlin ThreeMfMeshParser path for painted models was added/modified after v1.4.8
- v1.4.8 might have used `getPreparePreviewMesh()` (native C++) which renders differently
- The native path doesn't do TriangleSelector expansion — it gets pre-colored vertices from the C++ mesh parser
- If the preview routing changed (hasPaintData gate, colorMapping gate), the current version may be using the Kotlin path where v1.4.8 used native

## Key Files

| File | What to check |
|------|---------------|
| `ThreeMfMeshParser.kt:755-772` | `paintIndexForState()` — state folding formula |
| `ThreeMfMeshParser.kt:483-517` | `mergeH2cPairs()` — H2C merge correctness |
| `ThreeMfMeshParser.kt:296-316` | `parsePaintIndex()` — raw state extraction |
| `ThreeMfMeshParser.kt:803-810` | `triangleSelectorLeafStateToPaintState()` — H2C-aware |
| `ThreeMfMeshParser.kt:532-550` | Stride decimation (B45 fix) |
| `ThreeMfMeshParser.kt:602-603` | Stride application during expansion |
| `ThreeMfParser.kt:164-185` | B44 paint state counting (correct folding formula) |
| `MainActivity.kt` | Preview routing: hasPaintData / colorMapping gate |
| `NativePreviewMesh.kt:109-121` | Native preview path (used when Kotlin path not active) |

## Investigation Steps

1. **Check preview routing**: What path does v1.4.8 use vs v1.5.38? Did B45's `colorMapping` gate change which path colored_3DBenchy takes?
2. **Fix the state folding**: Align `paintIndexForState()` with the native slicer's `h2c_state_matches()` — state N+4 should fold to state N (1-indexed), not state N-1 (0-indexed)
3. **Verify H2C merge**: After `mergeH2cPairs()`, dump the per-triangle extruder indices and compare with v1.4.8's native preview output
4. **Test**: Load colored_3DBenchy, screenshot Prepare preview, verify all 4 colors present and no artifacts
5. **Regression range**: If the routing changed, bisect between v1.4.8 and current to find when the preview path switched

## What NOT to Change

- The native slicer fix (B44, TriangleSelector.cpp + PrintApply.cpp) is correct and tested — don't touch it
- The slice output is correct (all 4 extruders active) — this is purely a Prepare preview rendering issue
- The `paintStateCount` detection in ThreeMfParser.kt (B44) is correct
