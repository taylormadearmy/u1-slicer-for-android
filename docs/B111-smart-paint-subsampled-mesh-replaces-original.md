# B111 — Smart Paint replaces the original mesh with the subsampled 30k-tri scaffolding instead of propagating paint state back to the full mesh

**Status:** Open. Reproduced on Pixel 8a, v2.2.0 debug (versionCode 273), 2026-05-15.

**Severity:** High. Smart Paint produces an unusable model on any 3MF with more than ~30,000 triangles. The exported 3MF replaces the original geometry — slicing it prints the sparse scaffolding, not the intended part.

## Symptom

On any 3MF where `trianglePositions / 9 > 30_000` (so `fix45 subsampleMeshForAiPaint` fires), opening Smart Paint and accepting the result with "Use this painting →" causes:

1. The Smart Paint result viewer shows the model as a sparse cloud of disconnected coloured dots.
2. After accepting, the Prepare screen replaces the source 3MF with a derived `ai_paint_<timestamp>.3mf` containing **only the 29-30k subsampled triangles**. The 850k+ unpainted triangles are dropped.
3. Slicing this derived 3MF produces the sparse scaffolding instead of the full part.

## Reproduction

Fixture: `axolotl_multi_color_gray.3mf` (12 MB, 880,184 triangles, multi-volume gray + red).
A local copy currently sits at `/g/My Drive/tes-data/axolotl_multi_color_gray.3mf` and at `c:/tmp/axolotl.3mf` on the workstation that ran the repro. A repro fixture *should* be added to `app/src/androidTest/assets/` for a regression test — see Tests section.

```bash
# Copy into app-private storage (cp /sdcard → run-as is denied on Android 14;
# the pipe-through-run-as pattern works):
adb -s 43211JEKB16931 push c:/tmp/axolotl.3mf /sdcard/Download/axolotl.3mf
adb -s 43211JEKB16931 shell "cat /sdcard/Download/axolotl.3mf | run-as com.u1.slicer.orca sh -c 'mkdir -p files; cat > files/axolotl.3mf'"
adb -s 43211JEKB16931 shell am start -n com.u1.slicer.orca/com.u1.slicer.MainActivity
adb -s 43211JEKB16931 shell am broadcast -a com.u1.slicer.orca.LOAD_FILE \
    --es path '/data/data/com.u1.slicer.orca/files/axolotl.3mf' -p com.u1.slicer.orca
```

Then in the UI: tap the Smart Paint wand icon, wait ~50 s for the cascade, tap **Use this painting →**. Observe the Prepare screen now shows scattered dots instead of an axolotl. Slice — the G-code matches.

## Evidence captured on the failing run

Logcat (filtered):

```
05-15 16:39:52  AiPaint:        fix45 subsample: 880184 → 29340 tris (stride=30)
05-15 16:39:52  AiPaint:        Cascade fired: PAINT_STATE → 2 leaves
05-15 16:40:44  AiPaint:        Alternate available: TOPOLOGY → 28747 leaves
05-15 16:40:44  AiPaint:        fix44 gate: aiEnabled=false provider=POLLINATIONS canCallAi=false swapToTopology=true primary=TOPOLOGY alternate=PAINT_STATE
05-15 16:43:37  BambuSanitizer: Sanitization complete: …/sanitized_ai_paint_1778859814176.3mf
05-15 16:43:40  ProfileEmbedder: Embedded 163 config keys into embedded_sanitized_ai_paint_1778859814176.3mf
05-15 16:43:40  SAPIL:          Model loaded: embedded_sanitized_ai_paint_1778859814176.3mf (3mf) — 174.7 x 58.2 x 24.5 mm, 29340 triangles
```

The bounding box (174.7 × 58.2 × 24.5 mm) matches the axolotl, but the triangle count collapsed from 880,184 to 29,340 — the subsample count. The painted export contains *only* the subsampled mesh.

Screenshots: `c:/tmp/axolotl-{05,08,12,14}.png` — load, Smart Paint result viewer, after accept (sparse dots on bed), Prepare after returning (scattered coloured speckles + filaments=4 with red/green/blue/white slots).

## Root cause

[app/src/main/java/com/u1/slicer/aipaint/AiPaintViewModel.kt:129-133](app/src/main/java/com/u1/slicer/aipaint/AiPaintViewModel.kt#L129-L133) substitutes the subsampled mesh for the full mesh when the model exceeds `aiPaintTriCap = 30_000`:

```kotlin
val aiPaintTriCap = 30_000
val mesh = if (rawMesh.trianglePositions.size / 9 > aiPaintTriCap) {
    subsampleMeshForAiPaint(rawMesh, aiPaintTriCap)
} else rawMesh
val positions = mesh.trianglePositions
```

That `positions` array is then carried straight through to the painted export at [AiPaintViewModel.kt:232-237](app/src/main/java/com/u1/slicer/aipaint/AiPaintViewModel.kt#L232-L237):

```kotlin
val outFile = File(app.cacheDir, "ai_paint_${System.currentTimeMillis()}.3mf")
val slotIdsForFile = IntArray(triCount) { triangleRegions[it].toInt() and 0xFF }
PaintedMeshWriter.write(
    positions, slotIdsForFile, slotsView, outFile,
    printerColours = printerColours,
)
```

`PaintedMeshWriter.write` receives `positions` (the 29k-tri subsample) and writes that as the new mesh. There is no path that maps painted slot ids back to the original mesh's full triangle count.

`fix45`'s docstring (lines 618-635) explicitly acknowledges this trade-off but does not implement the propagation: *"Painted state stays attached to its source triangle — paint state propagation reads `originalTriangleId` if present, falls back to position-in-mesh. Tap-to-highlight lookups still resolve via `triangleIds in leaf`."* — that reasoning only describes how the cascade tree resolves leaves on the subsampled space. It says nothing about reconstructing the full mesh at export time.

## What the fix needs to do

The export path must produce a 3MF whose geometry equals the **original** mesh (all 880k triangles), with each original triangle's paint state derived from the subsampled cascade output.

A correct mapping is straightforward given how subsampling works: `subsampleMeshForAiPaint` keeps every `stride`-th triangle. So source triangle `t` belongs to subsampled triangle `t / stride`. The export should:

1. Retain a handle to the **original** `NativePreviewMesh` (`rawMesh`, before subsample).
2. Build a full-length `slotIdsForFile = IntArray(originalTriCount)` where `slotIdsForFile[t] = triangleRegions[t / stride]` for each original triangle index `t`.
3. Call `PaintedMeshWriter.write(rawMesh.trianglePositions, slotIdsForFile, slotsView, outFile, …)`.

This restores the full geometry and broadcasts each subsampled triangle's paint to the 30 originals it represents — a slight blockiness in colour boundaries (acceptable for the bulk of Smart Paint, which produces height-banded or Z-band leaves that are themselves coarse), but the model itself is intact.

If finer-grained boundaries matter, the alternative is to run a "snap to nearest subsampled triangle by centroid" pass per original triangle — more expensive (~O(N · k) for N=880k originals and k=29k subsamples, or O(N log k) with a KD-tree), but visually crisper. Try the simple `t / stride` approach first; only escalate to KD-tree mapping if user feedback shows the blocky boundaries are objectionable.

A second concern: the **viewer in the Smart Paint result screen** currently renders the subsampled mesh too. That's where the user sees the "sparse cloud" before accepting. Fixing this means feeding the viewer the original mesh + a per-triangle slot colour array derived the same way. Both paths share `ModelRenderer.pendingRecolor` / `MeshData.recolor`, so the recolor mechanic already handles per-triangle palette updates — the change is just to feed the renderer the full mesh.

## Suggested code-level approach

- `AiPaintViewModel.kt`: split into `meshForCascade` (subsampled when needed) and `meshForExportAndPreview` (always the original). The cascade keeps consuming the small one; the export and the result viewer consume the original.
- Add `originalToSubsampled: (Int) -> Int` returned from `subsampleMeshForAiPaint` (or expose `stride`) so the export can do the t / stride lookup without re-deriving.
- Update `PaintedMeshWriter.write` call sites to pass the original `trianglePositions` and the broadcast `slotIdsForFile`.
- Update the Smart Paint result-screen renderer call (the path that ends up populating `paintedModelPath` and `trianglePositions` in `AiPaintResultState` — line 248-260) so it renders the original mesh recoloured by the broadcast slot ids.

## What NOT to do

- Don't just raise `aiPaintTriCap` from 30k to something huge. fix45.1's comment explains the Korok 98k file hung the topology alternate >150 s — the cascade itself has super-linear merge cost. Subsampling for the cascade is correct; subsampling for the export is the bug.
- Don't drop `fix45` and the subsample altogether — that would re-introduce the hang.
- Don't write the painted 3MF using `rawMesh` positions but the subsampled `triangleRegions` array sized to 29k — array size mismatch will throw. The broadcast (t / stride) step is mandatory.
- Don't change `PaintedMeshWriter`'s contract to silently tile slot ids when the slot array is shorter than the triangle count — keep the writer strict, fix the caller.

## Acceptance criteria

1. Loading `axolotl_multi_color_gray.3mf`, running Smart Paint, and accepting "Use this painting" produces a `ai_paint_<ts>.3mf` whose triangle count equals the original 880,184 (verifiable via `SAPIL: Model loaded` logcat line).
2. The post-accept Prepare screen renders the axolotl as a full mesh, not scattered specks.
3. Slicing the painted result produces G-code with the expected layer count and `tool_counts` for at least 2 active tools (sparse-cloud current behaviour produces empty or near-empty layers).
4. The Smart Paint result viewer itself shows the full mesh — no sparse cloud in the preview either.
5. fix45 still subsamples for the cascade — `AiPaint: fix45 subsample: …` logcat line still appears for >30k models. Cascade performance is unchanged.
6. Smaller painted fixtures that don't trigger fix45 (e.g. `colored_3DBenchy (1).3mf`, ~600k tris — actually this also triggers fix45, pick a <30k fixture; `tetrahedron.stl` if no painted small fixture exists) still work end-to-end.
7. New instrumented test: `app/src/androidTest/java/com/u1/slicer/aipaint/SmartPaintExportFidelityTest.kt`. Load axolotl, run cascade, write painted export, load the export, assert triangle count equals original. Asset added to `app/src/androidTest/assets/`.
8. New unit test for the broadcast helper — given stride=30 and `triangleRegions = [r0, r1, r2, …]`, the full-length output has `full[0..29] = r0`, `full[30..59] = r1`, etc.

## BACKLOG / issue housekeeping

When the fix is ready:
1. Add `B111: Smart Paint replaces full mesh with 30k subsampled scaffolding on export` under `## Open Bugs` in `BACKLOG.md`.
2. Create the matching GitHub issue per CLAUDE.md BACKLOG↔issue sync rule.
3. Update `memory/e2e-results-history.md` once a regression test is added and passing.

## Release implications

This blocks v2.2.0 release. Smart Paint is the headline F54 feature; shipping it in a state where any model >30k triangles becomes a sparse scaffold on accept would be embarrassing and dangerous (someone would print the scaffold). Either land the fix before v2.2.0, or hide Smart Paint behind an Experimental toggle that's off by default and explicitly bump the entry-point UI out of the user-discoverable flow until B111 is fixed.

## Native rebuild

Not required — the bug is entirely in Kotlin (export path uses the wrong mesh). No native changes needed for the fix.
