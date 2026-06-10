# OpenPrintTag filament library — design

**Date:** 2026-06-10
**Branch:** `feature/filament-library` (worktree off `main` @ fe3a7d3)
**Status:** Design — approved by user in brainstorm (this doc is the written record)
**Follows:** pick-a-colour mix matching (v3.1.0-beta). Uses its `ColourMatch.deltaE76` machinery.

---

## 1. Summary

A built-in library of real filaments — from the MIT-licensed **OpenPrintTag material database** ([github.com/OpenPrintTag/openprinttag-database](https://github.com/OpenPrintTag/openprinttag-database); ~13,000 FFF materials across 121 brands) — so the user picks the actual spool they loaded instead of hand-typing colours:

1. **Library tab** in the existing filament colour dialog: search real filaments (brand / name / material), exact colour swatches, favourites + recents. Picking one sets the slot's **colour + material type**, with an **opt-in** "import profile data" step that previews extra fields (temps, density, transmission distance, refractive index) before saving them as a normal `FilamentProfile`.
2. **Printer-sync matching**: the existing Sync button (PrinterScreen) already receives per-slot `filament_vendor` / `filament_type` / `filament_sub_type` / `filament_color_rgba` from the printer — populated from RFID tags (Snapmaker tags on stock firmware; OpenSpool NTAG215/216 tags on the paxx12 extended firmware, [SnapmakerU1-Extended-Firmware](https://github.com/paxx12-snapmaker-u1/SnapmakerU1-Extended-Firmware)). A matching step identifies the exact catalogue filament from that data, so tagged spools self-identify on Sync.

All Kotlin/UI + a build-time data script — **no native change, no `.so` rebuild**.

## 2. Goals / Non-goals

**Goals**
- Bundled, offline, searchable filament library (FFF-only snapshot of OpenPrintTag).
- Pick → slot colour + material type set (default); opt-in profile-data import with an explicit preview of exactly what would be imported (only fields the database actually has for that filament).
- Favourites (⭐) + recents surfaced at the top of the Library tab.
- Sync matching: printer-reported vendor/material/subtype/colour → confident catalogue match shown by name in the existing sync preview dialog; apply uses the catalogue filament. No confident match → sync behaves exactly as today (raw RFID colour/type). Matching never blocks and never guesses.
- Carry `transmission_distance` + `refractive_index` in the bundled data, clearly labelled, for future translucency work. They do NOT change slicing in v1. (Per Prusa's ColorMix findings, TD does not improve layer-mix colour prediction — it is stored for *future translucent-feature* use, not fed to `FilamentMixPredictor`.)
- MIT attribution for OpenPrintTag in the app.

**Non-goals (deferred)**
- Translucency rendering/features using TD/refractive index.
- A browsable Library section on the Filaments tab (the reusable picker makes this cheap later).
- In-app library updates between app releases (snapshot refresh is a release-flow step).
- Writing RFID tags from the app.
- Feeding TD into the mix predictor (proven unhelpful by the model's authors).

## 3. Decisions (from brainstorm)

- **Pick semantics:** colour + material type by default; **opt-in** "Also import profile data…" with a preview dialog listing exactly the fields available for that filament (nozzle/bed temps, density, TD, refractive index — varies per entry; many entries are colour-only). Confirm → creates/links a `FilamentProfile` (existing table) and links it to the slot via the existing `ExtruderPreset.filamentProfileId`.
- **Entry point:** a **"Library" tab inside the existing colour-edit dialog** (`FilamentColorEditDialog` contexts where a physical slot filament is being set). The HSV "Custom colour" picker remains as the other tab, unchanged. Built as one reusable `FilamentLibraryPicker` composable so other surfaces can host it later.
- **Data:** **bundled snapshot** — a conversion script in the repo distils the OpenPrintTag YAMLs into one compact FFF-only JSON asset (~1–2 MB) at release time. Offline always; updated each app release; snapshot date stamped and committed.
- **Storage/search:** JSON asset loaded once (background) into an in-memory list; plain-Kotlin search. No Room table for the library itself (13k entries ≈ ~2 MB heap; instant at this scale). Swappable internals behind a `search()` interface if the dataset ever outgrows memory.
- **Shortlist:** favourites (⭐ per row) + recents, stored as slugs in DataStore; shown first when the tab opens.
- **Search behaviour:** search-as-you-type across brand/name/material + a material filter chip row (PLA / PETG / ABS / TPU / …). No brand-browsing hierarchy in v1.
- **Sync matching in v1:** yes — it is the killer use ("load filament → Sync → slots identify themselves").

## 4. Architecture

Four units + light wiring. Reuses: `ColourMatch.deltaE76`, `FilamentColorEditDialog`/`HsvColorPicker`, `ExtruderPreset` (colour/materialType/filamentProfileId), `FilamentProfile` + DAO, `MoonrakerClient` sync parsing (`filament_vendor`/`filament_type`/`filament_sub_type`/`filament_color_rgba`), `PrinterViewModel.syncFilaments()` + `FilamentSyncDialog`, `normalizeMaterialType()`.

### 4.1 Conversion script (build/release tooling, committed)
- `tools/openprinttag-convert/` — script (Python) that reads a checkout/tarball of `OpenPrintTag/openprinttag-database`, filters `class: FFF`, and emits `app/src/main/assets/filament_library.json` (minified) plus a snapshot-stamp (database commit SHA + date) embedded in the JSON header.
- Per-entry fields: `slug`, `brand` (display name), `name`, `material` (type, normalised to the app's canonical set where possible; raw kept), `hex` (from `primary_color.color_rgba`, alpha stripped), and nullable `td` (`transmission_distance`), `ri` (`refractive_index`), `density`, `minNozzle`/`maxNozzle`/`bed` temps (from `fff_material_properties`). Entries without a primary colour are kept (material-only pick; no swatch).
- MIT licence honoured: attribution string + link shipped in the app (Settings/About area) and a `NOTICE` note alongside the asset.

### 4.2 `FilamentLibrary` (pure Kotlin, new)
- Lazy-loads the asset off the main thread once; exposes:
  - `search(query: String, material: String? = null): List<FilamentLibraryEntry>` — case-insensitive substring across brand/name/material; material chip filter; favourites/recents ranked first on empty query.
  - `entry(slug: String): FilamentLibraryEntry?`
  - `snapshotInfo(): SnapshotInfo` (database SHA + date, entry count) for the attribution/about line.
- Favourites + recents: slug lists in DataStore via `SettingsRepository` (new keys); `FilamentLibrary` takes them as inputs (stays pure); the ViewModel composes the two.
- Load failure → a sealed `LibraryState` (Loading / Ready / Failed-with-retry); the Library tab renders accordingly; HSV tab unaffected.

### 4.3 `FilamentLibraryMatcher` (pure Kotlin, new) — sync matching
- `match(vendor: String?, material: String?, subType: String?, hex: String?): LibraryMatch?`
- Strategy: normalise vendor → brand candidates (case/punctuation-insensitive containment both ways); filter by normalised material; rank by `ColourMatch.deltaE76(reported hex, entry hex)`; subtype as a name-token bonus.
- **Confidence gate:** return a match only when brand+material matched AND colour ΔE is below a strict threshold (tuned in tests; e.g. ≤ ~10). Otherwise `null` → sync falls back to today's behaviour. Never guesses across brands; vendor missing/unknown → `null` (except Snapmaker-brand tags, which carry vendor on stock firmware).
- Pure + deterministic → fully unit-testable with synthetic and real-world-shaped inputs.

### 4.4 `FilamentLibraryPicker` (Compose, new) + dialog wiring
- One composable: search field, material chips, favourites/recents section, result rows (swatch + brand + name + material + ⭐), bounded height, paging-free (in-memory list + lazy column).
- `FilamentColorEditDialog` gains a two-tab mode (**Custom colour** | **Library**) only for physical-slot contexts (callers opt in via a parameter; the Match-a-colour target picker and mix display contexts keep the plain HSV dialog).
- Pick → `onPickFilament(entry)`: caller applies colour + material to the slot (same plumbing as today) and records the recent. Then an optional **"Also import profile data…"** affordance opens the preview dialog: a field-by-field list of what this entry has (only present fields shown; TD/RI labelled "for future translucency features — not used in slicing"). Confirm → insert `FilamentProfile` (name = "Brand Name", material, colour, temps/density where present) and set `ExtruderPreset.filamentProfileId`.

### 4.5 Sync integration (modify, small)
- `PrinterViewModel.syncFilaments()`: after building the per-slot preview entries, run `FilamentLibraryMatcher.match(...)` per slot; attach an optional `LibraryMatch` to each preview row.
- `FilamentSyncDialog`: matched rows show the identified filament — *"E2: Prusament PLA Galaxy Black (matched)"* — with the catalogue swatch; applying uses the catalogue colour/material (instead of the raw RFID values) and records a recent. Unmatched rows render exactly as today. The existing apply-colours/apply-types toggles keep working for both.

## 5. Data flow

```
Release time:  openprinttag-database (YAML, MIT)
                 └─ tools/openprinttag-convert → assets/filament_library.json (FFF-only + snapshot stamp)

Manual pick:   colour dialog ▸ Library tab → FilamentLibrary.search
                 → pick entry → slot colour+material set, recent recorded
                 → (optional) import-preview dialog → FilamentProfile created + linked

Printer sync:  Sync → MoonrakerClient (vendor/type/subtype/colour per slot)
                 → FilamentLibraryMatcher.match per slot
                 → FilamentSyncDialog (matched name or raw values) → apply → ExtruderPreset
```

## 6. Error handling / edge cases

- **Asset missing/corrupt:** Library tab shows failure + retry; HSV tab and everything else unaffected.
- **Entry without colour:** listed without swatch; picking applies material only (colour untouched).
- **Import preview with nothing beyond colour/material:** affordance hidden (nothing to import).
- **Sync: unknown vendor / hand-written tag / colour far from catalogue:** matcher returns null → today's raw behaviour. Matching is an upgrade, never a gate.
- **Material strings:** printer values go through the existing `normalizeMaterialType()`; library `material_types` mapped to the app's canonical set at conversion time (unknown types pass through as-is, displayable but matched conservatively).
- **Duplicate profile import:** importing the same filament twice updates/links the existing profile of the same library slug rather than duplicating (slug stored on the profile name convention or a nullable column — implementation detail for the plan; no silent duplicates).

## 7. Testing

- **Unit — converter contract:** a checked-in sample of real YAML inputs → expected JSON entries (fields, colour normalisation, FFF filter, no-colour entries kept).
- **Unit — `FilamentLibrary`:** search ranking (favourites/recents first, substring across brand/name/material, material chip), empty/failed-load states, snapshot info.
- **Unit — `FilamentLibraryMatcher`:** exact-brand+colour match, ΔE threshold rejection, vendor-unknown → null, subtype bonus, Snapmaker-tag shape, case/punctuation vendor normalisation. Threshold pinned by tests.
- **Unit — import preview mapping:** entry fields → preview rows → created `FilamentProfile` values; colour-only entry hides the affordance.
- **Structural guards:** colour dialog hosts the Library tab in slot contexts only; sync dialog renders matched names.
- **Instrumented/E2E (device):** load app → Library tab pick sets slot colour+material; sync path with a mocked/matched payload shows the match and applies catalogue values; a slice afterwards still passes the standard checks. No physical print.

## 8. Sequencing

1. **Data + logic:** conversion script + bundled snapshot + `FilamentLibrary` (search/favourites/recents) — pure, fully testable.
2. **Picker UI:** Library tab in the colour dialog + apply colour/material + import-preview dialog.
3. **Sync matching:** `FilamentLibraryMatcher` + sync-dialog wiring (independently revertible).
4. **Sweep:** full unit suite + confidence check + device E2E.

Each step ships working software.

## 9. Future work (captured, not in scope)

- Translucency preview/printing features consuming TD + refractive index.
- Filaments-tab Library browser (host the same picker).
- In-app snapshot refresh between releases.
- OpenTag3D support arriving in paxx12 firmware → revisit matcher inputs if new fields appear.

## 10. Branch & release

- Work on `feature/filament-library` (worktree off `main` @ fe3a7d3). No native change.
- No GitHub release/tag without explicit user authorization; `gh auth switch -u taylormadearmy` before any push.
