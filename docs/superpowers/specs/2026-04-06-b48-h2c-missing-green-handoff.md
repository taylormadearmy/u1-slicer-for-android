# B48 Handoff: H2C Benchy Missing Green (E2/T1) — Colour Mapping Pipeline Bug

## Status: Not Fixed — Needs Investigation

## What the user sees
The `3DBenchy-H2C-Multi-Color.3mf` (H2C dual-AMS painted model) is missing one of its 7 model colours in both the Prepare preview AND the sliced G-code output. The missing colour maps to green (E2/T1) via auto-mapping.

## Confirmed: slice pipeline issue, not viewer
The sliced G-code (`G:/My Drive/Logs/output (2).gcode`, copy at `c:/tmp/e2e-results/h2c-benchy-missing-green.gcode`) contains:
- T0: 238 tool changes
- T2: 241 tool changes  
- T3: 241 tool changes
- **T1: 0 tool changes** — green is completely absent

This proves the bug is in the colour mapping pipeline that feeds the slicer, not in the preview renderer.

## Key data from logs

```
embedProfile: info.isBambu=true, info.detectedExtruders=7, info.hasToolChanges=false, 
  info.hasPaint=true, info.isMultiPlate=false, sourceConfig=true, targetCount=4, extruderRemap=null

NativePreviewMesh: toMeshData triangles=1977475 
  indices={0=460441, 1=337805, 2=355028, 3=210429, 4=244610, 5=144680, 6=224482}

InlineModelPreview: recolor mapping=[2, 0, 3, 2, 0, 1, 0] 
  extruderColors=[#FF0000, #00FF00, #0000FF, #FFFFFF] paletteSize=7 hasMeshColors=true
```

- 7 model colours detected (`detectedExtruders=7`)
- `colorMapping=[2, 0, 3, 2, 0, 1, 0]` — maps 7 model colours → 4 extruders
- Model colour 5 → extruder slot 1 → green (`#00FF00`). Index 5 has 144K triangles in the preview mesh
- The palette is built correctly (7 entries), the indices are present, yet green doesn't show in either Prepare or G-code

## Root cause hypothesis

Two different systems build the colour mapping:

### 1. Kotlin ThreeMfParser (builds `detectedColors` and `colorMapping`)
- `paintIndexForState()` in ThreeMfParser uses **H2C-aware folding**: states 5-8 → 1-4 when `isH2cProject` is detected
- `paintStateCount` is computed by walking TriangleSelector data in the 3MF XML
- This determines `detectedExtruders` and `detectedColors` which drive the auto-mapping

### 2. Native C++ sapil_model.cpp (builds preview mesh indices + feeds the slicer)  
- `state_idx - 1` mapping (line 464): raw, not H2C-aware
- For an H2C model with states 0-7, this produces indices 0-6 (with state 0 = fallback)

**If the Kotlin parser's H2C-aware folding produces a different colour ordering than the native C++'s raw mapping, the `colorMapping` built from Kotlin won't align with the native indices.** Model colour 5 in the Kotlin scheme may correspond to a different TS state than index 5 in the native scheme.

### 3. The slice pipeline
The slicer (native OrcaSlicer engine) processes the embedded 3MF with its own TriangleSelector handling. The `embedProfile()` step writes the colour configuration that the slicer reads. If `targetCount=4` and `extruderRemap=null`, the slicer may be mapping H2C states differently than either the Kotlin parser or the preview mesh.

The fact that T1 is absent from the gcode means the slicer itself is not producing any E2 output — the colour that should map to E2 is being mapped elsewhere or dropped entirely.

## Investigation path

1. **Check `isH2c` detection for this file**: Does `ThreeMfInfo.isH2c` return true? The detection looks for `@BBL H2C` in profiles. This file may or may not trigger it.

2. **Compare state→index mappings**: 
   - Print the actual `detectedColors` list and the TS state each colour came from
   - Compare with the native `state_idx - 1` indices
   - Identify which model colour corresponds to green and why it's being mapped to the same extruder as another colour (note: `colorMapping=[2, 0, 3, 2, 0, 1, 0]` — slot 1 (green) appears only once, at position 5)

3. **Check the embedded profile**: After `embedProfile()`, what extruder configuration does the 3MF contain? Does it have 4 extruders configured? Does the slicer's internal TS→extruder mapping match?

4. **Slice with verbose logging**: Add logging in `sapil_print.cpp` around the `WipeTowerIntegration` and tool-change generation to see which extruders the slicer thinks it's using

## Key files

| File | What |
|------|------|
| `app/src/main/java/com/u1/slicer/bambu/ThreeMfParser.kt` | `paintIndexForState()`, `paintStateCount`, H2C detection |
| `app/src/main/java/com/u1/slicer/SlicerViewModel.kt` | `buildProfileOverrides()`, `embedProfile()`, `colorMapping` |  
| `app/src/main/cpp/src/sapil_model.cpp:464` | Native `state_idx - 1` mapping |
| `app/src/main/cpp/src/sapil_print.cpp` | Slice pipeline, extruder configuration |
| `app/src/main/java/com/u1/slicer/viewer/NativePreviewMesh.kt` | `toMeshData()` — indices pass through unchanged now |

## Test asset
`app/src/androidTest/assets/3DBenchy-H2C-Multi-Color.3mf` — push to `/data/local/tmp/h2c_benchy.3mf` and load via broadcast.

## Sliced G-code reference
`G:/My Drive/Logs/output (2).gcode` (copy at `c:/tmp/e2e-results/h2c-benchy-missing-green.gcode`) — T0=238, T2=241, T3=241, **T1=0**.

## What was fixed in B46 (context)
The B46 session fixed the Prepare preview rendering for painted/SEMM models:
- Native `getPreparePreviewMesh()`: skip QEM/stride for MMU, model-local coords, skip index compaction
- Kotlin: route all 3MF through native path, remove Kotlin ThreeMfMeshParser from preview
- The colored_3DBenchy (non-H2C) and Korok mask now show correctly
- B48 is a **separate issue** in the colour mapping pipeline that affects both preview and slicing
