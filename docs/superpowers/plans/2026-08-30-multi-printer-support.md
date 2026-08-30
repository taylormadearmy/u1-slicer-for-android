# A2 — Heterogeneous Multi-Printer Support Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.
>
> **Scope note:** This is the A2 master plan. Phases 1–7 are each independently shippable milestones. Phase 1 is specified to execution-grade detail below. Phases 2–7 are specified to task-and-interface grade; per repo convention (see `2026-04-23-phase1-roadmap.md` + its sub-plans), each later phase gets its own execution-grade sub-plan written from this document **when that phase is scheduled**, so it can incorporate what earlier phases actually shipped.

**Goal:** A user can add any rectangular-bed FDM printer from OrcaSlicer's vendor catalogue (or an `.orca_printer` import) and get correct local slicing and network upload, with the Snapmaker U1 unchanged as the first-class default.

**Architecture:** Replace the two hardcoded machine paths (U1 literals in `applyConfigToPrusa()`, `BambuMachineProfile` enum map) with one data-driven `MachineProfile` pipeline: Orca vendor JSON → inherits resolution → `MachineProfile` → `SliceConfig` + `ProfileEmbedder` + `GcodeDialect`. Native honours JNI geometry and gates the profile whitelist on an explicit authored-profile marker instead of the `PRINT_START` heuristic.

**Tech Stack:** Kotlin + Compose (app), org.json (profile parsing), OkHttp (catalogue fetch + host clients), C++/JNI Snapmaker Orca 2.2.4 (`sapil_print.cpp`), NDK 26 Release `.so` rebuilds via `scripts/rebuild-native-so.sh`.

**Spec:** [`docs/superpowers/specs/2026-08-30-multi-printer-support-design.md`](../specs/2026-08-30-multi-printer-support-design.md) — decisions D1–D11, reuse map, risks. Read it first; tasks below cite decisions by number.

## Global Constraints

- All 1976 JVM unit tests and 451 instrumented tests must stay green after every task; never weaken an assertion (CLAUDE.md rule).
- Native rebuilds: NDK 26 (clang 17.0.2), Release, stripped ~19–21 MB, via `scripts/rebuild-native-so.sh`; commit orca submodule changes BEFORE deploying a new `.so`; JNI symbol count must match `NativeLibrary.kt` `external fun` count.
- U1 behaviour must be byte-equivalent through phases 1–3: every demoted literal becomes a fallback whose value equals today's literal, and the U1 `MachineProfile` supplies today's values.
- Profile source pin: `https://raw.githubusercontent.com/SoftFever/OrcaSlicer/v2.2.0/resources/profiles/` (D2). The pin is a single constant `ORCA_PROFILE_TAG` in `VendorCatalogClient.kt`; bumping it requires re-running the phase-5 validation fixtures.
- New user-facing strings: neutral wording ("printer", "machine profile"), never "Snapmaker" outside the Snapmaker family.
- Do not touch the Bambu path (`SlicerTarget`, `BambuMachineProfile`, `resolveTargetedSliceConfig` Bambu branches, `applyBambuConfigToPrusa`) except where a task explicitly says so (D3).
- Mixes (ColorMix / AI-paint mix bytes) remain gated to 4-slot printers; the `4 + k` encoding is untouched (D6).
- Each phase ends with `.\scripts\run-confidence-check.ps1` green plus that phase's new tests, and a version-bump-free commit train (release/version bumps only at phase 7).

---

## Phase 1 — MachineProfile foundation + native geometry/provenance

**Milestone deliverable:** the engine slices correctly for a 300×300×350 generic Klipper machine profile (dev-only fixture, no UI), and the U1 path is driven by the same data model with zero behaviour change.

### Task 1.1: `MachineProfile` data class + Orca machine-JSON parser

**Files:**
- Create: `app/src/main/java/com/u1/slicer/machine/MachineProfile.kt`
- Test: `app/src/test/java/com/u1/slicer/machine/MachineProfileTest.kt`
- Test fixtures: `app/src/test/resources/machine/snapmaker_u1_resolved.json` (copy of `app/src/main/assets/orca_profiles/printer/snapmaker_u1.json`), `app/src/test/resources/machine/generic_klipper_300.json` (hand-written: 300×300×350, `gcode_flavor: "klipper"`, 1 nozzle 0.4, minimal start/end G-code)

**Interfaces (produces):**
```kotlin
enum class MachineFamily { SNAPMAKER, BAMBU, GENERIC }
enum class GcodeFlavor { KLIPPER, MARLIN, OTHER;
    companion object { fun fromOrca(value: String?): GcodeFlavor }  // "klipper"→KLIPPER, "marlin"/"marlin2"→MARLIN, else OTHER
}

data class MachineProfile(
    val id: String,                 // stable key, e.g. "snapmaker-u1-0.4"
    val displayName: String,        // Orca "name"
    val vendor: String,
    val printerModel: String,       // Orca printer_model
    val printerVariant: String,     // Orca printer_variant, e.g. "0.4"
    val family: MachineFamily,
    val bedSizeX: Float, val bedSizeY: Float, val maxPrintHeight: Float,
    val extruderCount: Int,         // from nozzle_diameter array length
    val nozzleDiameter: Float,
    val gcodeFlavor: GcodeFlavor,
    val singleExtruderMultiMaterial: Boolean,
    val machineStartGcode: String, val machineEndGcode: String,
    val machineChangeFilamentGcode: String, val machinePauseGcode: String,
    val machineJson: String,        // full RESOLVED Orca machine preset (for ProfileEmbedder)
) {
    companion object {
        /** Parse a fully-resolved (no `inherits`) Orca machine preset. Returns null when
         *  printable_area is missing/malformed or type != "machine". Bed size = bounding box
         *  of the printable_area polygon (non-rectangular beds reduce to bbox, spec D9/out-of-scope). */
        fun fromResolvedOrcaJson(json: String, family: MachineFamily): MachineProfile?
    }
}
```

- [ ] **Step 1: Write the failing tests** — bed-size rule: `bedSizeX = round(maxX - minX)` of the `printable_area` bbox, **rounded to the nearest whole mm** (the U1's area is `-0.5..270.5` → bbox 271; profiles commonly pad ±0.5, so also subtract symmetric sub-mm padding: when `minX < 0` and `maxX > round(maxX)-0.6`, use `round(maxX + minX)` → 270). Assert: U1 fixture → `bedSizeX==270f`, `bedSizeY==270f`, `maxPrintHeight==270f`, `extruderCount==4`, `gcodeFlavor==KLIPPER`, `machineStartGcode` contains `PRINT_START`; generic fixture (plain `0..300` area) → `300f/300f/350f`, `extruderCount==1`; malformed JSON and missing `printable_area` return null; `type:"process"` returns null.
- [ ] **Step 2: Run, verify FAIL** (`./gradlew testDebugUnitTest --tests "com.u1.slicer.machine.MachineProfileTest"`).
- [ ] **Step 3: Implement** `MachineProfile.kt` (org.json; `printable_area` entries are `"x×y"` strings using `x` separator — reuse the parsing pattern from `ProfileEmbedder.sanitizeWipeTowerPosition`).
- [ ] **Step 4: Run, verify PASS.**
- [ ] **Step 5: Commit** `feat(machine): MachineProfile data model parsed from Orca machine JSON`.

### Task 1.2: `OrcaProfileResolver` — offline `inherits` chain resolution

**Files:**
- Create: `app/src/main/java/com/u1/slicer/machine/OrcaProfileResolver.kt`
- Test: `app/src/test/java/com/u1/slicer/machine/OrcaProfileResolverTest.kt`
- Test fixtures: `app/src/test/resources/machine/chain/` — 3-file chain `u1_child.json` (`inherits: "fdm_u1_base"`) → `fdm_u1_base.json` (`inherits: "fdm_common"`) → `fdm_common.json`

**Interfaces (produces):**
```kotlin
object OrcaProfileResolver {
    /** Merge an inherits chain child-wins. [loadByName] returns raw JSON for a preset name or null.
     *  Returns null on: missing parent, cycle (seen-set), depth > 10. Strips `inherits`,
     *  `instantiation`, `from` from the result. */
    fun resolve(rootJson: String, loadByName: (String) -> String?): String?
}
```
(The network loader that feeds `loadByName` from GitHub raw arrives in phase 5 — this task is pure merge logic, mirroring OrcaSlicer-Mobile's `downloadProfilesRecursively` merge semantics minus the whitelist.)

- [ ] **Step 1: Failing tests** — chain resolves child-wins (child's `machine_start_gcode` beats base's; base-only key `retraction_length` survives); missing parent → null; cycle (`a inherits b`, `b inherits a`) → null; already-flat JSON returns itself minus bookkeeping keys.
- [ ] **Step 2: Verify FAIL. Step 3: Implement. Step 4: Verify PASS.**
- [ ] **Step 5: Commit** `feat(machine): OrcaProfileResolver inherits-chain merge`.

### Task 1.3: `MachineConfigResolver` — MachineProfile → SliceConfig

**Files:**
- Create: `app/src/main/java/com/u1/slicer/machine/MachineConfigResolver.kt`
- Modify: `app/src/main/java/com/u1/slicer/data/SliceConfig.kt` (no new JNI fields in this task — `machineTarget` gains the value `"GENERIC"` which native treats as the non-Bambu path)
- Test: `app/src/test/java/com/u1/slicer/machine/MachineConfigResolverTest.kt`

**Interfaces (produces):**
```kotlin
/** Generic-family counterpart of resolveTargetedSliceConfig. Bambu targets keep the old path. */
fun resolveMachineSliceConfig(profile: MachineProfile, base: SliceConfig): SliceConfig
// sets: bedSizeX/Y, maxPrintHeight, machineStartGcode/EndGcode/ChangeFilamentGcode,
// machineTarget = "SNAPMAKER_U1" when family==SNAPMAKER else "GENERIC",
// wipeTowerX/Y clamped via wipeTowerClampBounds(profile.bedSizeX, profile.bedSizeY, ...)
```
**Consumes:** `MachineProfile` (task 1.1), existing `wipeTowerClampBounds` (`SlicerViewModel.kt:248`).

- [ ] **Step 1: Failing tests** — U1 profile → config equal to today's defaults (270/270/270, `machineTarget=="SNAPMAKER_U1"`, wipe tower untouched at 170/140); generic 300 profile → 300/300/350, `machineTarget=="GENERIC"`, wipe tower position preserved when in-bounds and clamped when out (assert one out-of-bounds case: base `wipeTowerX=290f` → clamped ≤ `300 - width - margin`).
- [ ] **Steps 2–4: FAIL → implement → PASS. Step 5: Commit** `feat(machine): resolveMachineSliceConfig for SNAPMAKER/GENERIC families`.

### Task 1.4: Native — honour JNI bed geometry; add `printable_height`

**Files:**
- Modify: `app/src/main/cpp/src/sapil_print.cpp:447-456` (printable_area from `config.bed_size_x/y`), `:1536-1563` (auto-centre net uses config dims), `profile_keys[]` at `:1064` (add `printable_height`), plus set `printable_height` from `config.max_print_height` next to printable_area
- Test (structural, JVM): `app/src/test/java/com/u1/slicer/nativesrc/ProfileKeyCoverageTest.kt`
- Test (instrumented): `app/src/androidTest/java/com/u1/slicer/slicing/MachineBedSizeTest.kt`

**Native change (exact):**
```cpp
// applyConfigToPrusa(), replacing the 270 literals:
const double bed_x = config.bed_size_x > 0 ? (double)config.bed_size_x : 270.0;
const double bed_y = config.bed_size_y > 0 ? (double)config.bed_size_y : 270.0;
const double bed_z = config.max_print_height > 0 ? (double)config.max_print_height : 270.0;
dpc.set_key_value("printable_area", new ConfigOptionPoints{
    Vec2d(0,0), Vec2d(bed_x,0), Vec2d(bed_x,bed_y), Vec2d(0,bed_y)});
dpc.set_key_value("printable_height", new ConfigOptionFloat(bed_z));
// slice() auto-centre net: BED_X/BED_Y become the same bed_x/bed_y locals.
```

- [ ] **Step 1: Write the failing structural test** — `ProfileKeyCoverageTest` reads `app/src/main/cpp/src/sapil_print.cpp` from the repo root (same source-grep pattern as `ModelInfoDialogScrollTest`), extracts (a) the `profile_keys[]` string-literal set, (b) every `dpc.set_key_value("<key>"` / `set_deserialize` key inside `applyConfigToPrusa`, and asserts (a) ⊇ (b) minus an explicit allowlist constant `NON_OVERRIDABLE_KEYS` (initially: `mixed_filament_definitions`; spec D5). Also asserts `"printable_height"` ∈ (a). Fails today because `printable_height` is absent and several written keys are missing from the whitelist — grow `profile_keys[]` (preferred) or the documented allowlist until the invariant holds honestly.
- [ ] **Step 2: Write the failing instrumented test** — `MachineBedSizeTest`: load `3DBenchy.stl` (existing asset fixture), `config.bedSizeX=300f; bedSizeY=300f; maxPrintHeight=350f; machineTarget="GENERIC"`, translate model centre to (280, 150), slice; assert success, assert G-code max X coordinate > 270 (grep `X28`/parse moves), and assert `GcodeValidator.checkBedBounds(bedMaxX=300.0, bedMaxY=300.0)` passes. Second test: same placement with 270 config must keep today's auto-centre/reject behaviour (regression pin).
- [ ] **Step 3: Implement native change; rebuild `.so`** via `scripts/rebuild-native-so.sh` (NDK 26 / Release / verify checklist), commit submodule-clean.
- [ ] **Step 4: Run both tests on-device → PASS; run confidence check.**
- [ ] **Step 5: Commit** `feat(native): printable_area/height from JNI config; profile-key coverage guard`.

### Task 1.5: Native — authored-profile marker replaces `PRINT_START` heuristic

**Files:**
- Modify: `app/src/main/cpp/src/sapil_print.cpp:982-999`; `app/src/main/java/com/u1/slicer/bambu/ProfileEmbedder.kt:337` (write marker for every app-authored embed, not just U1)
- Test (instrumented): extend `app/src/androidTest/java/com/u1/slicer/slicing/SlicingIntegrationTest.kt`

**Native logic (exact):**
```cpp
bool authored = model_config_has(model_config, "snapmaker_authored_profile");   // read raw map
bool klipper_heuristic = start_gcode_contains(model_config, "PRINT_START");
bool is_snapmaker_family = config.machine_target == "SNAPMAKER_U1";
// Whitelist applies to any app-authored embed; heuristic only rescues
// desktop-Orca U1 exports when the ACTIVE target is the Snapmaker family (spec D4).
bool apply_profile_keys = authored || (klipper_heuristic && is_snapmaker_family);
```
The marker key is read from the raw JSON config map before Orca schema filtering (it is not a registered `PrintConfig` option — same access pattern the fork uses for other custom keys; if the raw map is filtered earlier, register the key in the fork's `PrintConfig.cpp` as `coString` and commit the submodule).

- [ ] **Step 1: Failing tests** — (a) slice a fixture 3MF whose embedded config contains `PRINT_START` in `machine_start_gcode` but no marker, with `machineTarget="GENERIC"`: assert diagnostics `isSnapmakerProfile==false` (new diagnostics key `appliedProfileKeys=false`) and slice still succeeds; (b) existing U1 desktop-export fixture with `machineTarget="SNAPMAKER_U1"`: `appliedProfileKeys==true` (regression pin); (c) app-authored embed (via `ProfileEmbedder`) with `machineTarget="GENERIC"`: `appliedProfileKeys==true`.
- [ ] **Steps 2–4: FAIL → implement (Kotlin + native, rebuild `.so`) → PASS + confidence check.**
- [ ] **Step 5: Commit** `feat(native): authored-profile marker gates profile_keys; PRINT_START heuristic scoped to Snapmaker targets`.

### Task 1.6: Wire U1 through `MachineProfile` + `MachineProfileRepository`

**Files:**
- Create: `app/src/main/java/com/u1/slicer/machine/MachineProfileRepository.kt`
- Modify: `app/src/main/java/com/u1/slicer/bambu/ProfileEmbedder.kt:163-170` (`loadProfiles(machineProfile)` — asset paths resolved from the active profile; U1 profile points at today's three assets), `SlicerViewModel.kt:7296-7310` (`readPrinterMachineGcode` reads from the active `MachineProfile.machineJson`), `SlicerViewModel.kt:4800` (`activeBedDimensions()` reads the active profile)
- Test: `app/src/test/java/com/u1/slicer/machine/MachineProfileRepositoryTest.kt` + existing embedder/slicing suites as regression net

**Interfaces (produces):**
```kotlin
class MachineProfileRepository(private val assets: AssetProvider, private val cacheDir: File) {
    fun builtInU1(): MachineProfile                    // parsed once from snapmaker_u1.json
    fun byId(id: String): MachineProfile?              // built-ins + cached (phase-5 populates cache)
    fun activeFor(printer: Printer): MachineProfile    // printer.machineProfileId ?: builtInU1()
}
```
`Printer` (`data/Printer.kt`) gains `val machineProfileId: String? = null` — null means U1 (D10 migration is thereby automatic; add a JSON round-trip test in the existing `PrintersConfig` test file).

- [ ] **Step 1: Failing tests** — repository returns U1 profile with 270/4/KLIPPER; unknown id → null; `activeFor(printer-with-null-id)` == builtInU1; `PrintersConfig` JSON round-trips `machineProfileId`.
- [ ] **Steps 2–4: FAIL → implement → PASS**, then run the full JVM suite + confidence check (this task touches the embedder — the highest-blast-radius file in the app).
- [ ] **Step 5: Commit** `feat(machine): MachineProfileRepository; U1 becomes the built-in machine profile`.

**Phase 1 exit criteria:** all suites green; `MachineBedSizeTest` proves non-270 slicing; no UI change visible to users.

---

## Phase 2 — Kotlin geometry unification

Thread the active profile's bed dimensions through every Kotlin 270/135 site found in the 2026-08-30 inventory; delete defaults where a real value can be passed.

- **Task 2.1** Arrange/validate plumbing: `CopyArrangeCalculator` callers pass profile dims everywhere (fix `MultiStlCombiner.kt:27,79` and `ModelScaleConverter.kt:24` which use private 270 consts); `GcodeValidator.checkBedBounds` and `GcodeLayer` bed defaults become required parameters; `SlicerViewModel` "270 mm bed" user strings interpolate the profile size. Tests: parameterised unit tests at 180/270/300 in `CopyArrangeCalculatorTest` style; grep-guard test asserting no `270f` literal remains in the touched files outside `MachineProfile` defaults.
- **Task 2.2** Viewer: `BedDrawable` gains a generic rectangle+grid path (profile without `bedStlAsset`); U1 keeps `u1_bed.stl`; `ModelRenderer` camera/logo — `setupLogoTexture` renders only for `MachineFamily.SNAPMAKER`; `GcodeRenderer.setTarget(bedX/2, bedY/2, 0)`. Tests: structural guards (source-grep) for the family gate + existing viewer unit tests; manual screenshot pass on-device.
- **Task 2.3** UI literals: `PlacementViewerScreen.kt:154` legend, `MainActivity.kt:3233` Build Volume row, `:5879` scale warnings, `PlateSelectDialog`, `ExcludeObjectBedCanvas`, `ModelViewerScreen`/`GcodeViewer3DScreen` defaults — all take dims from the active profile via existing composable params. Tests: string-formatting unit tests for the warning/legend builders (extract pure functions where inline today).
- **Task 2.4** `BambuSanitizer`/`ThreeMfParser` off-bed heuristics: rename the `bedSize` params to `sourceBedSize`, document that they describe the *source file's* (Bambu ≤256) conventions, keep current values, and pin with regression fixtures (spec risk 5). No behaviour change; tests are pins.
- **Task 2.5** `SettingsBackup` round-trips `machineProfileId` per printer; restore across printers keeps geometry consistent. Test in `SettingsBackupTest`.

**Exit criteria:** on-device with a dev-toggled 300 mm profile, Prepare/preview/placement/G-code viewer all render and clamp at 300; U1 pixel-identical.

---

## Phase 3 — Extruder-count parameterisation (1–4)

- **Task 3.1** Data: `MachineProfile.extruderCount` (already parsed) → `Printer.extruderCount` derived from its profile; `defaultExtruderPresets(count: Int)` and `parseExtruderPresets(raw, count)` (`ExtruderPreset.kt:26,55`) stop force-filling to 4. Tests: counts 1/2/4 in `ExtruderPresetTest` (new), `Printer` mapping test.
- **Task 3.2** Clamp sweep: replace `coerceIn(1, 4)` / `0..3` guards / `MutableList(4)` sites listed in the inventory (§2: `SlicerViewModel` ~15 sites, `MainActivity` ~8, `NavGraph`, `FilamentScreen`, `MultiColorDialog`, `SyncPreview`, `PrintTimeRemap`, `CanonicalColourRemap`) with `slotCount` from the active profile. Tests: extend `MultiColorMappingTest`, `PrintTimeRemapTest`-class suites with `slotCount=1` and `=2` cases; grep-guard that the touched files contain no bare `0..3` slot literals.
- **Task 3.3** Mix gating: `CreateMixSlotDialog` entry points and AI-paint mix palette hidden when `slotCount != 4` (D6; the `TARGET_SLOTS=4` encoding at `SlicerViewModel.kt:6387` is untouched). Structural test in the `MatchAColourWiringTest` style.
- **Task 3.4** Native: `single_extruder_multi_material` from the embedded profile instead of forced `false` (`sapil_print.cpp:569`) for authored non-Snapmaker profiles; `extruder_offset` passthrough (drop the forced zeroing at `:635` when the profile provides offsets); `sapil_arrange.cpp:674` `MAX_EXTRUDERS` reads `config.extruder_count` ceiling 4. Rebuild `.so`. Tests: instrumented single-extruder slice (next task) + U1 sweep.
- **Task 3.5** Instrumented gate: `slicing/SingleExtruderMachineTest.kt` — generic 1-extruder profile, multi-colour source model: slices to single-tool G-code (no `T1`+, no toolchange, `enable_prime_tower` off), temps present once.

**Exit criteria:** 1-extruder generic profile produces clean single-tool G-code; U1 4-slot flows unchanged; mixes invisible on non-4-slot printers.

---

## Phase 4 — G-code dialect layer (Marlin + generic Klipper)

- **Task 4.1** Extract current behaviour: `gcode/GcodeDialect.kt` interface — `remapToolLine`, `pauseInjection`, `thumbnailFormat`, `supportsExcludeObject`, `validatorRules`; `KlipperSnapmakerDialect` object reproduces today's `SM_*`/`PAUSE_PRINT`/Klipper-thumbnail behaviour exactly. `GcodeToolRemapper`, `LayerToolPauseInjector`, `GcodeThumbnailInjector`, `GcodeValidator` take a dialect parameter defaulted to `KlipperSnapmakerDialect`. Tests: existing suites unchanged (they now exercise the extraction); dialect-selection unit test from `MachineProfile` (`SNAPMAKER→KlipperSnapmakerDialect`, `GENERIC+KLIPPER→KlipperGenericDialect`, `GENERIC+MARLIN→MarlinDialect`).
- **Task 4.2** `KlipperGenericDialect`: no `SM_*` handling, pause = `PAUSE` macro, Klipper thumbnails, exclude_object on. `MarlinDialect`: pause = `M600`, PRUSA-style thumbnails, exclude_object **off**, validator requires body `M104/M109` present and forbids `EXCLUDE_OBJECT_*`/`SET_HEATER_TEMPERATURE`. Unit tests per rule.
- **Task 4.3** Native/flavour E2E: instrumented `slicing/MarlinFlavorTest.kt` — authored Marlin machine profile (fixture with `gcode_flavor: "marlin"`) slices benchy: assert `M104`/`M109` appear in the body, no Klipper macros, no `EXCLUDE_OBJECT_DEFINE` (native `exclude_object` forced-true at `sapil_print.cpp:907-910` becomes flavour-conditional — rebuild `.so`).
- **Task 4.4** `GcodeValidator` per-dialect wiring + `hasBowdenUnloadSequence` scoped to the Snapmaker dialect. Unit tests.

**Exit criteria:** a Marlin profile slices to G-code a Marlin firmware would accept; Klipper/U1 output byte-identical to phase 3.

---

## Phase 5 — Catalogue, wizard, import, OTA

- **Task 5.1** `machine/VendorCatalogClient.kt`: fetch `ORCA_PROFILE_TAG` vendor index list (static vendor-name list constant, since GitHub raw can't enumerate directories; generated from the pinned tag and checked in), fetch `<Vendor>.json` index, expose `machine_model_list`/`machine_list` (parse per the survey's schema: `{name, sub_path}`). Unit tests on recorded JSON fixtures (Snapmaker + one small vendor); OkHttp `MockWebServer` for error paths (404, malformed).
- **Task 5.2** `machine/ProfileCache.kt`: disk cache under `filesDir/machine_profiles/<vendor>/`, OTA check comparing the vendor index `version` field (Orca `PresetUpdater` design per spec reuse map), atomic replace + keep-one-backup rollback. Unit tests with temp dirs.
- **Task 5.3** Network-backed resolution: `loadByName` implementation feeding `OrcaProfileResolver` (task 1.2) from cache-then-network, including OrcaSlicer-Mobile's vendor-inference rules for cross-vendor `inherits` (`@BBL` → BBL etc.). Unit tests with fixture chains incl. a real `fdm_*` common-base chain recorded from the pinned tag.
- **Task 5.4** Wizard UI `ui/machine/PrinterWizardScreen.kt` (vendor list → model list with variants → confirm), text-only v1; entry from Settings → Printers → "Add printer" alongside the existing U1/Bambu options; delta-printer and SEMM machines filtered out (spec out-of-scope). Validation gate: before saving, slice the bundled 1-layer calibration cube with the resolved profile in a background job; failures surface the native error and block save. Structural + ViewModel unit tests; one instrumented wizard-to-slice E2E.
- **Task 5.5** `.orca_printer`/`.orca_filament` import (SAF picker, ZIP of Orca JSON): reuse resolver + validation gate; missing bases fetched via task-5.3 path. Unit tests on a recorded `.orca_printer` fixture.
- **Task 5.6** CI/profile validation: check in `scripts/validate-machine-profiles.py` wrapping upstream's `orca_extra_profile_check.py` checks that apply to fetched-profile snapshots; wire into the confidence-check docs (D11).

**Exit criteria:** a user (or E2E subagent) adds "Creality Ender-3 V3 SE" from the wizard and slices a benchy that passes the dialect validator; profiles survive offline restarts; OTA bump replaces a vendor cleanly with rollback.

---

## Phase 6 — Connectivity: OctoPrint + PrusaLink hosts

- **Task 6.1** `Printer` gains `hostType: HostType` (`MOONRAKER`, `OCTOPRINT`, `PRUSALINK`, existing `BAMBU_LAN`), `apiKey: String?`, `username/password: String?` — persisted with PrusaSlicer's key names in backups (`host_type`, `print_host`, `printhost_apikey`, spec D8). `PrinterEditDialog` grows the host-type selector + credential fields; neutral copy replaces "Connects to your Snapmaker U1…". JSON round-trip + migration tests.
- **Task 6.2** `printer/OctoPrintTransport.kt`: `POST /api/files/local` multipart + `X-Api-Key`, optional `print=true` (only via the existing explicitly-user-confirmed start-print flow — the CLAUDE.md never-start-without-permission rule applies to every host), job/status polling via `GET /api/job`. Port request shapes from Orca `src/slic3r/Utils/OctoPrint.cpp`. Unit tests with MockWebServer (upload success/401/conflict, status mapping to `PrinterStatus`).
- **Task 6.3** `printer/PrusaLinkTransport.kt`: `PUT /api/v1/files/usb/<name>` with digest or API-key auth, status via `/api/v1/status`. Same test pattern.
- **Task 6.4** `PrinterTransportFactory` dispatches on `hostType`; console/heater/LED/webcam cards gate on capability flags (`supportsConsole` only for Moonraker; `MoonrakerClient.buildSetHeaterGcode` temp clamps move to per-profile limits). Wiring tests in the existing `PrinterRepositoryTest`/`PrinterViewModelTest` style.

**Exit criteria:** upload-only flow verified against real OctoPrint and PrusaLink instances (or their docker mocks) via the E2E batch skill; U1 Moonraker untouched.

---

## Phase 7 — Hardening + v5.0.0 release

- **Task 7.1** E2E matrix: extend `scripts/confidence-check.psd1` smoke set with `MachineBedSizeTest`, `SingleExtruderMachineTest`, `MarlinFlavorTest`; add an e2e-batch scenario "add printer via wizard → slice → upload-only".
- **Task 7.2** Docs: CLAUDE.md (architecture, new test counts, machine-profile conventions section), README (supported printers statement + wizard screenshot), BACKLOG A2 flip, close #152 with follow-up issues for deferred items (delta beds, SEMM, SDCP/Duet, Bambu-path convergence, >4 extruders, mix-encoding generalisation).
- **Task 7.3** Migration soak: upgrade-in-place test from v4.x data (existing `UpgradeDetectorTest` + a new `PrintersConfig` migration fixture); `SettingsBackup` cross-version import test.
- **Task 7.4** Release train per CLAUDE.md release checklist (version 5.0.0, versionCode 500), **with explicit user authorization before `gh release create`**.

---

## Self-review notes

- Spec coverage: D1–D11 all land in tasks (D1/D2→5.x, D3→1.1-1.6, D4→1.4/1.5, D5→1.4 structural test, D6→3.3, D7→4.x, D8→6.x, D9→2.2, D10→1.6/7.3, D11→5.6/7.1). Out-of-scope list mirrored in 7.2's follow-up issues.
- Type consistency: `MachineProfile`/`MachineFamily`/`GcodeFlavor` (1.1) are the only new shared types; later tasks reference them by those exact names. `resolveMachineSliceConfig` (1.3) is the only new config entry point; `machineTarget="GENERIC"` string is used consistently in 1.3/1.4/1.5.
- Known open question deliberately deferred to phase-5 planning: whether the static vendor-name list (5.1) ships generated-in-repo or fetched from a checked-in snapshot of the pinned tag's index — decide when writing the phase-5 sub-plan.
