# Candidate B: selected-machine snapshots on the v4.0.5 base

## Problem

Keep the released v4.0.5 selected-machine authority and send safeguards, then add the v5 catalogue, wizard, Marlin, PrusaLink, machine-sized Prepare view, variable slots, and Smart Paint fixes. The v5 branch routes generic machines through `SlicerTarget.SnapmakerU1` and the shared native U1 configuration policy. That loses the v4.0.5 native geometry, exclusion, stale-profile, and foreign per-layer macro guards. A wholesale merge needs to fix that policy before regression testing.

## Usage

The wizard lists vendor machines and imports an Orca bundle. Saving produces one immutable resolved machine snapshot. Existing CC1 and generic Klipper entries remain printable without importing their profiles again. Selecting a printer changes the machine used by Prepare and slicing, independently of its connection protocol.

```kotlin
val machine = machineProfiles.resolveSelection(wizardSelection)
printers.save(draft.withMachine(machine))

val selected = printers.activeSnapshot()
val artifact = slicer.slice(model, selected.machine, process, filaments)

printerRepository.uploadOnly(artifact, expectedPrinter = selected.printer)
// A confirmed print additionally checks artifact.machineIdentity == selected.machine.identity.
```

These calls are a design sketch, not existing APIs. They hide profile inheritance, canonical identity, firmware flavor, bed validation, and persisted migration. Callers do not select a U1 route for a generic printer.

## Shape

Retain `GenericMachineProfile` as the validated generic-machine representation, but extend it to Marlin and authored multi-extruder topology. Rename it to `ResolvedMachineProfile` only if all callers migrate in the same change. Keep `MachineKind`, existing transport `PrinterKind`, and explicit generic target families. Do not maintain the v5 `MachineProfile` and v4 `GenericMachineProfile` as parallel slice authorities.

```kotlin
class ResolvedMachineProfile private constructor(
    val identity: MachineProfileIdentity,
    val geometry: MachineGeometry,
    val topology: ExtruderTopology,
    val flavor: SupportedGcodeFlavor,
    val resolvedJson: String,
) {
    fun compose(process: ProcessSettings, filaments: FilamentSettings): ResolvedSliceProfile = TODO()
    fun applyTo(config: SliceConfig): SliceConfig = TODO()
}

sealed interface ExtruderTopology {
    data object Single : ExtruderTopology
    data class Independent(val nozzleDiameters: List<Float>, val offsets: List<Offset>) : ExtruderTopology
    data class Semm(val physicalNozzleDiameter: Float, val filamentSlots: Int) : ExtruderTopology
}

enum class SupportedGcodeFlavor { KLIPPER, MARLIN }
data class ResolvedSliceProfile(val machineIdentity: MachineProfileIdentity, val json: String)
```

The parser resolves Orca inheritance once, rejects unsupported flavor/topology and malformed geometry, canonicalizes the result, and derives its digest. The catalogue's human-readable profile ID is not artifact identity. This follows boundary-discipline and foundational-thinking. One immutable snapshot owns both JNI geometry and composed native JSON, so callers cannot pair a new machine identity with stale profile settings.

The native selected-profile route remains separate from U1 and Bambu. It applies selected resolved JSON after Orca defaults, ignores source-project machine settings and foreign per-layer macros, compares selected JSON geometry/nozzles/templates with JNI fields, then applies explicit process and filament settings. It preserves authored offsets and SEMM policy for validated topology. It fails on print validation and on model/generated-helper bounds or exclusions. Raw STL and imported 3MF use the same selected-machine route.

## Module changes

- `machine/OrcaProfileResolver`, `CachedOrcaProfileLookup`, `OrcaProfileBundleImporter`, `VendorCatalogClient`, `ProfileCache`, and `PrinterWizardViewModel`: bring in the v5 catalogue behavior, but convert resolved output to the selected-machine representation at its boundary. Keep vendor/download cache data separate from selected printable snapshots.
- `slice/GenericMachineProfile.kt`: extend validated flavor and topology; retain process/filament whitelist, immutable canonical JSON, and fingerprint. Preserve `GenericFilamentSettings.kt` selected-library temperature/header normalization. Catalogue selection must not introduce a second composition path.
- `data/Printer.kt` and `PrintersRepository.kt`: retain all v4.0.5 JSON fields and default-machine migration. Add v5 profile reference metadata and host types without replacing inline selected snapshots. Upgrade tests cover CC1, generic Moonraker, OctoPrint, U1, and Bambu entries already written by v4.0.5.
- `slice/SlicerTarget.kt`, `SliceTargetResolver.kt`, and `SliceArtifact.kt`: explicit selected generic target and artifact digest remain. Generalize generic identity for Marlin. Keep saved-job provenance and fail-closed unknown legacy target behavior.
- `SlicerViewModel.kt`, `PrepareScreen`, viewer geometry, filament-slot helpers, and Smart Paint: transplant v5 geometry/slot behavior, but use one captured selected snapshot for the complete slice. Retain v4.0.5 PETG executable heating and post-slice header fixes.
- `ProfileEmbedder.kt`: retain v5 U1 authored marker and generic embed support only where imports need it. Selected-machine native JSON remains authoritative; a source 3MF cannot choose the printer by carrying an authored marker.
- `printer/PrusaLinkTransport.kt`: add v5 REST support. Retain v4.0.5 CC1, guarded repository upload/start flow, OctoPrint upload-only `select=false` and `print=false`, disabled redirects/retries, and stale connection cancellation. Import v5 controls/capability fixes individually.
- `NativeLibrary.kt`, `sapil_arrange.cpp`, `sapil.h`, and `slicer_wrapper.cpp`: adopt configured-extruder `nativeSplitVolume` signature together. Rebuild the native library; symbol-count equality alone cannot detect a same-name JNI signature mismatch.
- `sapil_print.cpp`: retain the v4.0.5 selected-machine branch; add flavor/topology handling rather than sharing U1 defaults. Adopt v5 machine-sized centering, U1 explicit authored marker, authored offset/SEMM support, and flavor-specific exclude-object policy where applicable.
- Orca submodule: use v5 `f3c6081993c9b7b7b704b22d0f15ec8019f3c5b3`. Its difference from v4.0.5 `2535da4c` is five lines in `PrintConfig.cpp` for the authored marker. Existing v5 cache is NDK26 Release and can build the reconciled native sources.

## Synthesis decision

Pending parent comparison. This candidate intentionally uses the v4.0.5 model as the implementation base instead of accepting v5's generic-U1 route and fixing it afterward.

## Tradeoffs accepted

- We accept more integration work in exchange for retaining the released machine-identity and fail-closed native behavior.
- We accept conversion at the catalogue boundary in exchange for having only one printable machine representation.
- We accept rejecting unsupported imported multi-extruder layouts until their topology validates, rather than inferring safe support from nozzle-array length.

## Alternatives considered

Use v5 as the base and translate v4.0.5 data into `MachineProfile`. This reuses more v5 code, but generic ownership currently spans the Snapmaker target enum, profile embedding, JNI configuration, and post-export checks. It requires a broad safety correction and migration proof. It may still be the better delivery choice if those corrections remain small and all released behavior is covered.

Keep both profile classes and choose one per printer generation. This hides less behind the API because the ViewModel must coordinate two parsers, two identities, two native loading paths, and two artifact rules. Reject this shape.

## Implementation reconciliation

No production changes made. Candidate only. Grounding used HEAD `8ac2b292` and v5 `efa58e79`. Preserve the remapper's reused `java.util.regex.Matcher` implementation in either base. Both branches already contain this fix, with equivalent structure.

## Regression contracts

- Adapt all v4.0.5 `GenericKlipperNativeConfigTest` tests instead of dropping them. They cover official CC1 templates/exclusion, native stale profile rejection, no U1 macros, foreign per-layer hook suppression, selected PETG heating, and switching to Bambu.
- Preserve `GenericPrinterSliceViewModelTest` executable M109/M190 and first-layer header checks, `JobMachineMigrationTest`, mapping layout checks, and printer-edit UI tests.
- Add 4.0.5-to-5.0.0 migration cases for every released printer representation and every saved artifact target. A v4.0.4-only upgrade fixture is insufficient.
- Add imported generic Marlin/Klipper source files with malicious foreign start/per-layer hooks; selected machine wins.
- Add generic oversized model, Z overflow, exclusion intersection, and brim/skirt overflow rejection. Check that failure creates no sendable artifact.
- Add same catalogue ID with changed machine JSON, stale slice, and mid-upload selection changes; no print start.
- Add generic multi-slot heating and extrusion topology tests before supporting Independent or Semm variants. Keep U1 byte-compatible defaults.
- Run all device tests, including external fixture classes separately. The v5 Gradle default excludes `RequiresExternalFixture`, so a single connected suite is not complete coverage.

## Safe regression execution

Before installing or downgrading, identify the approved phone from local notes and back up its complete app sandbox. The v5 upgrade script restores only printer JSON and `machine_profiles`; it does not restore the full database/settings/session. Never use NF22E1 `NE12442001324`.

Build the matching NDK26 Release library, strip it, compare JNI declarations/signatures, and prove the APK contains that exact library. Then run:

```powershell
.\gradlew.bat testDebugUnitTest assembleDebug assembleDebugAndroidTest --no-daemon --console=plain "-Pkotlin.compiler.execution.strategy=in-process"
.\scripts\run-connected-with-progress.ps1 -Device <approved-phone>
.\scripts\run-phase6-printer-host-e2e.ps1 -Device NF12209000455 -Backend Python
.\scripts\run-v4-upgrade-soak.ps1 -CandidateApk app/build/outputs/apk/debug/app-debug.apk
.\scripts\run-batch-manual-e2e.ps1 -Device <approved-phone> -Suite Both -BambuTarget H2D -NoInstall -IncludePrinterWizardUpload -ResultsDir <new-results-dir>
```

The host fixture sends confirmed start requests only to loopback mocks, not printers. The upgrade fixture pins the v4.0.4 SHA256 and device NF12209000455. Expand it to v4.0.5 before claiming reconciliation coverage. Manual E2E defaults to another serial and has no off-limits-device guard, so pass the verified serial explicitly. Full raw Orchestrator sharding must include the cleanup listener and fixture exclusion, then run excluded fixtures independently. The raw shard script refuses failures, skips, missing completion status, and empty results.

## Open questions and risks

Can catalogue entries with non-origin bed coordinates or authored nonrectangular exclusion regions be represented without silently rebasing coordinates? Which multi-extruder imported topologies can pass native proof on existing fixtures? Can every user-visible v5 behavior remain covered while its machine identity model changes? These are implementation questions to resolve with source/tests, not reasons to request user input yet.

Effort is higher than a v5-base merge because several wizard/storage/ViewModel contracts must migrate. The native change is localized and retains stronger existing guards. The largest risk is losing one of the 67 v5 commits during selective transplants; maintain a behavior checklist from their commit subjects and tests before choosing this candidate.

## Next implementation step

Build the released v4.0.5 printer/profile migration fixtures and the resolved-machine flavor/topology contract first, then adapt the catalogue/wizard to that contract.
