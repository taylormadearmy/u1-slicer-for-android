# Canonical Export Mapping Helper — Design

Date: 2026-04-28
Branch: `feature/phase2-canonical-filaments`
Driver: Adversarial review (`docs/superpowers/reviews/2026-04-28-adversarial-review-v1.6.13-to-phase2.md`) found 3 P1 leaks where canonical-fileIndex G-code reaches the printer-facing path: (a) Send-dialog race, (b) Job-history share bypass, (c) Save/Share helper using plate-narrowed `_colorMapping` and missing single-colour selected-slot.

## Goal

A single owned helper on `SlicerViewModel` that produces a print-ready G-code (T0..T3 physical-slot space) from a canonical-fileIndex slice. Used by **all four** export surfaces — Send, Save Gcode, Share Gcode, and Jobs-tab Share — so the boundary is impossible to bypass at the call site.

## Four input cases the helper must handle

1. **Full canonical mapping** — user confirmed via Filament Mapping dialog. `_colorMapping` is set, length matches canonical filament count.
2. **Plate-narrowed mapping** — multi-plate selected; `_colorMapping` is auto-derived to plate's filament indices (e.g. `[0,1]` for a 2-colour plate of a 10-filament file). Need to expand to canonical size so out-of-plate filaments still get a defined slot.
3. **Single-colour selected slot** — STL or single-colour 3MF; `_colorMapping` is null, user's choice is in `_selectedExtruder`. Need `T0 → selectedSlot`.
4. **No canonical at all** — no canonical filament list available (legacy / unrecognised file). Identity copy (no remap).

## Design

### Two-layer split

```kotlin
// Layer 1 — pure resolver (testable, no IO)
internal fun resolveExportMapping(): List<Int>?

// Layer 2 — file IO with the resolved mapping
internal fun prepareExportableGcode(sourceFile: File, destFile: File): Boolean
internal fun prepareExportableGcodeWithMapping(
    sourceFile: File, destFile: File, mapping: List<Int>?
): Boolean
```

`prepareExportableGcode()` calls `resolveExportMapping()` then `prepareExportableGcodeWithMapping()`. The split lets:
- Send dialog supply its own user-confirmed mapping directly to layer 2 (current Send flow)
- Save/Share/Jobs use layer 1 to derive the mapping from current ViewModel state
- Unit tests exercise layer 1 without touching files

### Resolver logic

```kotlin
internal fun resolveExportMapping(): List<Int>? {
    val canonical = _canonicalFilamentList.value
    val canonicalSize = canonical?.entries?.size ?: 0

    if (canonicalSize == 0) {
        // No canonical context — identity copy
        return null
    }

    val confirmed = _colorMapping.value
    if (!confirmed.isNullOrEmpty()) {
        // Cases 1 + 2: user has a mapping (full or plate-narrowed)
        return expandMappingToCanonicalSize(confirmed, canonicalSize)
    }

    // Case 3: single-colour with selected slot
    if (canonicalSize == 1) {
        return listOf(_selectedExtruder.value.coerceIn(0, 3))
    }

    // Edge case: multi-colour file but no mapping yet (loaded but not Send-confirmed)
    // Use identity-mod-4 as a safe default. Send dialog will refresh this when the
    // user confirms.
    return List(canonicalSize) { it % 4 }
}

private fun expandMappingToCanonicalSize(
    src: List<Int>,
    canonicalSize: Int,
    fallbackSlot: Int = 0
): List<Int> {
    if (src.size >= canonicalSize) return src.take(canonicalSize)
    // Plate-narrowed case — pad missing entries with mod-4 so high canonical indices
    // still resolve to a valid physical slot if the slicer happens to emit them.
    return List(canonicalSize) { i ->
        if (i < src.size) src[i] else (i % 4)
    }
}
```

### Send dialog three-state lookup

The current `produceState` returns `null` while loading and the `null` branch sends unchanged. Fix: distinguish loading from "no canonical".

```kotlin
sealed class CanonicalLookupState {
    object Loading : CanonicalLookupState()
    object Absent : CanonicalLookupState()
    data class Present(val list: CanonicalFilamentList) : CanonicalLookupState()
}

val state by produceState<CanonicalLookupState>(
    initialValue = CanonicalLookupState.Loading, key1 = pending.gcodePath
) {
    val list = withContext(Dispatchers.IO) { viewModel.getCanonicalFilamentList() }
    value = if (list != null) CanonicalLookupState.Present(list) else CanonicalLookupState.Absent
}

when (state) {
    Loading -> { /* keep pendingMappingSend alive; show small spinner in dialog area */ }
    Absent -> { /* go through helper with null/identity mapping */ }
    Present -> { /* show FilamentMappingDialog */ }
}
```

The Absent branch still routes through `prepareExportableGcodeWithMapping(... mapping = null)` so even legacy/no-canonical files get the helper boundary.

### Job-history share

Add metadata to `SliceJob`:

```kotlin
data class SliceJob(
    ...
    val canonicalListSize: Int? = null,  // size at slice time
    val colorMapping: List<Int>? = null, // user mapping at slice time (or null if not confirmed)
)
```

`shareJobGcode(job)` then:

```kotlin
fun shareJobGcode(job: SliceJob) {
    viewModelScope.launch(Dispatchers.IO) {
        val sourceFile = File(job.gcodePath)
        val mapping = job.colorMapping ?: job.canonicalListSize?.let {
            // No confirmed mapping at slice time — best-effort identity-mod-4
            List(it) { i -> i % 4 }
        }
        val tempFile = File(sourceFile.parentFile, "${sourceFile.nameWithoutExtension}.share.${sourceFile.extension}")
        prepareExportableGcodeWithMapping(sourceFile, tempFile, mapping)
        // ... share tempFile via FileProvider
    }
}
```

For pre-Phase-2 jobs (where `canonicalListSize` is null), the gcode is already in physical-slot space (v1.6.13 behaviour), so identity copy is correct.

### Migration story

Group A.1 commits:
1. Add resolver + helper signature to `SlicerViewModel`. Leave existing `prepareExportableGcode` (yesterday's fix) in place and have it delegate to the new pair. Add unit tests for the resolver.
2. Update `MainActivity.kt` Send dialog to use the three-state `produceState`. The `Loading` branch shows a non-blocking message; only `Absent` falls through to identity.
3. Update Save/Share to call `prepareExportableGcode()` directly (it now uses the resolver).
4. Add `canonicalListSize` + `colorMapping` to `SliceJob` Room entity (with migration). Wire `startSlicing` to record both at slice completion. Update `shareJobGcode` to use the helper.

## Acceptance criteria

| Test | Spec |
|---|---|
| **resolver — full canonical mapping** | Given canonical size 4 and `_colorMapping=[2,0,1,3]`, returns `[2,0,1,3]`. |
| **resolver — plate-narrowed mapping** | Given canonical size 10 and `_colorMapping=[0,1]` (2-colour plate of 10-fil file), returns `[0,1,2,3,0,1,2,3,0,1]`. |
| **resolver — single-colour selected** | Given canonical size 1, `_colorMapping=null`, `_selectedExtruder=2`, returns `[2]`. |
| **resolver — no canonical** | Given canonical null, returns `null` (identity). |
| **resolver — single-colour with E1 default** | Given canonical size 1, `_selectedExtruder=0`, returns `[0]`. |
| **resolver — multi-colour no mapping yet** | Given canonical size 5, `_colorMapping=null`, returns `[0,1,2,3,0]` (identity-mod-4 fallback). |
| **resolver — selectedSlot clamp** | `_selectedExtruder=99` clamped to 3 (max physical slot). |
| **Send race instrumented** | Tap Send before async canonical lookup completes; dialog stays alive in Loading; gcode path is NOT sent unchanged. |
| **Save/Share single-colour-non-E1 instrumented** | Load STL, select E3, slice, Save → exported gcode has all `T0` rewritten to `T2`. |
| **Save/Share sparse plate instrumented** | Load 10-filament file, select 2-colour plate, slice, Save → exported gcode has no canonical T-indices the printer can't resolve via the expanded mapping. |
| **Jobs share instrumented** | Slice multi-colour with confirmed mapping → revisit Jobs tab → share that job → exported gcode applies the originally-confirmed mapping (not identity, not raw canonical). |
| **Pre-Phase-2 job compatibility** | A SliceJob row with `canonicalListSize=null, colorMapping=null` (v1.6.13-era) shares as identity copy (raw stored G-code, which was already physical-slot in that era). |

## Out of scope (handled by other Group A fixes)

- Cache identity / cross-load leak (fix 4)
- Native paint-state under-detection (fix 5)
- GcodeParser T10+ (fix 6)
- Preview palette length (fix 7)

## Out of scope (handled by Group B pivots)

- `CanonicalGcode` / `PhysicalGcode` value-class type safety (B.1) — the type system would make these bugs compile errors. The helper is a stepping stone toward that pivot, not a replacement for it.
- Replace `PRINT_START` heuristic with explicit profile metadata (B.3) — the `is_snapmaker_profile` gate is in C++ and orthogonal to this helper.
