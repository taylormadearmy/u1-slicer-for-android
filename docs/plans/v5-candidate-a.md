# Candidate A: one machine profile model

## Problem

Reconcile published v4.0.5 at `8ac2b292` with `feature/a2-multi-printer` at `efa58e79`. The feature branch has the catalogue, Orca bundle importer, machine-profile repository, variable slot count, and PrusaLink adapter. The published branch has CC1, strict generic-profile isolation, schema 9 job identity, and printer-switch send guards. A wholesale feature-branch merge would lose those published behaviors. This candidate replaces the two machine models with one immutable `MachineProfile` snapshot, while preserving existing serialized data and transport safeguards.

Working phases: grounding complete; candidate sketch complete; synthesis pending; implementation not started.

## Usage

The UI asks the repository to bind a validated machine. It does not coordinate cache writes with DataStore writes or infer a machine from the connection protocol.

```kotlin
// Catalogue and bundle setup share this entry point.
val machine = machineProfiles.importResolved(resolvedOrcaJson)
printers.addConfigured(nickname, connectionDraft, machine)

// Slicing captures an immutable machine, connection identity, and action generation.
val binding = printers.captureActiveBinding()
val config = machineProfiles.configForSlice(binding.machine, overrides, selectedFilaments)
val artifact = slice(config, binding.machine)

// One send operation owns all compatibility and switch checks.
printerRepository.send(artifact, SendIntent.UploadOnly, binding.identity)
```

`importResolved` rejects unsupported profiles. `captureActiveBinding` refuses an unresolved explicit machine reference. `send` returns a refusal without upload when incompatible. Upload-only never starts a print. Upload-and-start validates before upload and after upload for hosts with separate start requests. A host that starts as part of upload validates immediately before that request and requires explicit user print confirmation.

## Shape

```kotlin
enum class HostType { MOONRAKER, OCTOPRINT, PRUSALINK, BAMBU_LAN, ELEGOO_CC1 }
enum class MachineFamily { SNAPMAKER, BAMBU, GENERIC, ELEGOO_CC1 }
enum class GcodeFlavor { KLIPPER, MARLIN }

@JvmInline value class MachineFingerprint internal constructor(val value: String)

// Private constructor. Only a validated, resolved profile can enter the domain.
class MachineProfile private constructor(
    val id: String,                     // Catalogue label, not artifact identity.
    val displayName: String,
    val family: MachineFamily,
    val geometry: MachineGeometry,      // Finite rectangle, origin, height, exclusions.
    val tools: List<PhysicalTool>,      // 1..4 with finite supported nozzle diameters.
    val flavor: GcodeFlavor,
    val fingerprint: MachineFingerprint,
    internal val resolvedJson: String,
)

// Physical machine and protocol are independent. No parallel MachineKind field.
data class Printer(
    val id: String,
    val nickname: String,
    val connection: PrinterConnection,
    val machine: MachineSelection,
    val extruderPresets: List<ExtruderPreset>,
    val selectedWebcamUid: String?,
)

sealed interface MachineSelection {
    data class Available(val profile: MachineProfile) : MachineSelection
    data class Unavailable(val requestedId: String?, val reason: String) : MachineSelection
}

sealed interface PrinterConnection {
    data class Moonraker(val host: HostAddress, val credentials: HostCredentials) : PrinterConnection
    data class OctoPrint(val host: HostAddress, val apiKey: String) : PrinterConnection
    data class PrusaLink(val host: HostAddress, val credentials: HostCredentials) : PrinterConnection
    data class Bambu(val config: BambuConfig) : PrinterConnection
    data class CentauriCarbon1(val address: Cc1Address) : PrinterConnection
}

class MachineProfileRepository {
    fun importResolved(json: String): MachineProfile = error("not implemented")
    fun builtInU1(): MachineProfile = error("not implemented")
    fun builtInCc1(): MachineProfile = error("not implemented")
    fun resolveCatalogue(id: String): MachineResolution = error("not implemented")
    fun configForSlice(
        machine: MachineProfile, overrides: SlicingOverrides, filaments: List<FilamentProfile>,
    ): ResolvedMachineSliceConfig = error("not implemented")
}

sealed interface MachineResolution {
    data class Available(val profile: MachineProfile) : MachineResolution
    data class Unavailable(val requestedId: String, val reason: String) : MachineResolution
}

data class PrinterBinding(val machine: MachineProfile, val identity: PrinterActionIdentity)
data class ArtifactMachineIdentity(val target: SlicerTarget, val fingerprint: MachineFingerprint?)
enum class SendIntent { UploadOnly, UploadAndStart }

class PrinterRepository {
    suspend fun send(
        artifact: SliceArtifact, intent: SendIntent, expected: PrinterActionIdentity,
    ): TransportCommandResult = error("not implemented")
}
```

The snippets define public contracts, not compiling replacement files. Existing Bambu artifact packaging remains part of `SliceArtifact`. Native `SliceConfig` keeps its current JNI field layout. `ResolvedMachineSliceConfig` owns profile JSON and the existing JNI config as one validated pair.

The repository hides canonicalization, inheritance validation, supported geometry, temperature bounds, and machine-key ownership. Callers see a machine snapshot and a resolved slice config. This concentrates policy rather than making the UI coordinate load, validate, cache, and apply stages, per boundary-discipline and minimize-reader-load.

### File ownership

| Owner | Files and responsibility |
| --- | --- |
| Machine domain | `machine/MachineProfile.kt`, `MachineProfileRepository.kt`, `MachineConfigResolver.kt`. Merge strict current validation and canonical fingerprinting here. Repository resolution never invents U1 for an explicit unresolved reference. |
| Catalogue/import | `VendorCatalogClient.kt`, `ProfileCache.kt`, `CachedOrcaProfileLookup.kt`, `OrcaProfileResolver.kt`, `OrcaProfileBundleImporter.kt`, `PrinterWizardViewModel.kt`. Produce resolved JSON and then call the machine domain. Cache entries are catalogue resources, not active mutable machine identities. |
| Printer persistence | `data/Printer.kt`, `PrintersRepository.kt`, `SettingsBackup.kt`. Own connection validation, legacy decode, embedded machine snapshots, and atomic printer-record publication. Cache writes may happen first; an interrupted add cannot leave a configured printer referring to missing profile content. |
| Slicing | `SlicerViewModel.kt`, `slice/SlicerTarget.kt`, `SliceTargetResolver.kt`, `SliceCapabilities.kt`, `bambu/BambuTargetedConfigResolver.kt`. Capture selected snapshot once, derive dimensions/slots/capabilities from it, and retain explicit U1/Bambu/CC1/generic targets. |
| Native machine policy | `sapil_print.cpp`. Generic defaults plus selected validated machine JSON; ignore embedded foreign machine config and layer tool injections. Validate selected JSON against JNI geometry/templates/tools, placement/exclusions, and generated helper geometry. |
| Artifact/history | `slice/SliceArtifact.kt`, `data/SliceJob.kt`, `AppDatabase.kt`. Preserve schema 9 and job identities. Extend generic artifact identity to Marlin without mapping it to U1. |
| Send/transport | `PrinterRepository.kt`, `PrinterTransport.kt`, `PrinterTransportFactory.kt`, `PrinterViewModel.kt`, adapters. Preserve expected-printer and action-generation checks, distinguish upload from irreversible start, and derive capabilities from machine plus host. |
| UI | `ui/machine/PrinterWizardScreen.kt`, `ui/printer/PrinterEditDialog.kt`, `PrintersSettingsCard.kt`, `SettingsScreen.kt`, `MainActivity.kt`. Show unresolved-profile errors; explicit confirmation for print; upload-only remains distinct. |

### Migration contract

The persisted v5 record has a schema discriminator and embedded canonical machine snapshot. Decode old records through a single internal adapter; write only the v5 form. Keep old enum names in that adapter, not as a second live architecture.

- A 405 U1 record maps to built-in U1 and its existing four slot presets.
- A 405 Bambu record preserves model, credentials, slots, and model-specific target.
- A 405 CC1 record maps to the bundled CC1 snapshot and its original host address. Keep CC1 transport discovery and CC2 rejection.
- A 405 generic Moonraker or OctoPrint record converts embedded `machineProfile` JSON with the same canonical algorithm. Its fingerprint must remain byte-for-byte equal to the old identity so saved jobs still match.
- A 405 generic record with no profile remains a configured network host with unavailable slicing. Never promote it to U1. This requires the persisted printer decoder to represent unavailable machine selection separately until the UI binds a valid snapshot.
- An unreleased v5 record with `machineProfileId` resolves its cache entry once and embeds the result on next save. A missing explicit ID remains unavailable.
- Old null machine identity means U1 only when the legacy record actually follows the U1 path. Unknown identities remain unsendable.
- Do not change Room version 9 or remove either identity column. Add another forward migration only if the selected design genuinely needs new columns.

Runtime `captureActiveBinding` returns only an available profile. The persisted unavailable-machine selection is an explicit domain state, not a null profile that callers interpret differently.

### Native generic policy

Generalize current generic isolation from one physical extruder to 1..4. Machine JSON owns bed, excluded areas, nozzle list, flavor, start/end/toolchange/pause templates, and machine limits. Process and filament imports use allowlists and cannot overwrite those keys. Tool-dependent arrays must match the selected physical tool count. Reject any referenced tool outside it. Keep U1-only firmware scripts and ColorMix policies out of generic execution.

Support Marlin through the same policy and its own pause/toolchange dialect. Keep single-extruder multi-material unsupported until a verified purge/tool mapping path exists. Validate multi-extruder behavior with real native output, not only parser tests. Read source paint and layer-tool metadata as requested tools, then validate it against selected tools; do not copy foreign machine command templates.

## Synthesis decision

Pending the orchestrator's comparison with the independent candidate. This document proposes the unified v5 model as base and the 405 migration, selected-machine authority, immutable fingerprints, and send checks as required behavior.

## Tradeoffs accepted

- Accept a larger one-time migration in exchange for one live machine type and one config policy.
- Accept embedded resolved JSON duplication across printer records in exchange for self-contained backups and stable slicing/history identity.
- Accept an unavailable machine state in exchange for refusing silent geometry or macro substitution.
- Accept native rebuild and new device regression tests in exchange for genuine generic 1..4 and Marlin support.

## Alternatives considered

Keeping 405's embedded `GenericMachineProfile` and adding the catalogue as a JSON producer minimizes integration work but leaves a second machine representation and generic capability limits that catalogue callers must understand. It is a viable smaller candidate, with lower migration risk and less interface depth for arbitrary machines.

Using mutable cached profiles by ID for every configured printer avoids snapshot duplication but exposes cache availability and overwrite semantics to slicing, history, and backups. It fails the identity and ownership constraints.

## Implementation reconciliation

No production implementation has started. Any change to failure behavior, generic tool scope, or profile ownership needs acceptance in the synthesized design before worker handoff.

## Open questions and risks

Can the full native/device suite prove generic multi-extruder paint and toolchange semantics without actual generic hardware? Device output tests can prove command generation, but hardware operation remains unverified.

Does any old generic record rely on a fingerprint calculation outside the current canonical JSON algorithm? Test actual 405 serialized fixtures before deleting the legacy parser.

## Implementation path and tests

1. Implement old-record decode and immutable profile validation with exact 405 fixtures. Test all five current configurations, unavailable generic profiles, invalid references, credentials, slots, active IDs, and backup round trips.
2. Introduce one machine selection/binding API; migrate every profile/config caller and remove `GenericMachineProfile` after its tests move. Verify same-name different-content profiles have different identities and cache replacement cannot mutate a selected snapshot.
3. Retain schema 9 history and explicit targets. Test old U1 history, current CC1/generic history, unknown target refusal, changed-profile refusal, and restored-session identity.
4. Port native generic policy to unified JSON and 1..4 tools/Marlin. Rebuild Release with NDK26. Add device slices for Klipper one-tool, Klipper two-tool paint, Marlin one-tool, tool-array mismatch, embedded foreign macros, exclusions, brim/skirt bounds, and stale native profile mismatch.
5. Connect catalogue/bundle UI through the validated domain and add PrusaLink without replacing CC1. Test missing/cyclic parents, unsafe archives, failed native validation, cancellation, unsupported variants, and no cache-to-U1 fallback.
6. Migrate all send callers to `send`. Mock each adapter's upload-only and explicit start; test printer ID, profile, address, credentials, and generation changes during upload. Verify PrusaLink start-on-upload has no automatic retry and never starts from upload-only.
7. Build debug and instrumentation APKs, then run the complete JVM, orchestrated device, and E2E suites on authorized devices. Preserve 405 painted-model remapping and compact mapping-row regressions. Record actual counts and failures rather than relying on branch reports. Do not contact a physical printer or publish a release.

## Next implementation step

Build exact published-405 serialization fixtures and profile-fingerprint migration tests before replacing the current printer domain.
