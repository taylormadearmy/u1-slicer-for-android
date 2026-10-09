# Printer support implementation

## Problem and usage

Connection protocol must not select machine geometry or executable G-code. Existing saved Moonraker printers remain U1s. New users explicitly choose U1, Centauri Carbon CC1, or a single-nozzle imported Klipper machine. Bambu behavior stays unchanged.

## Selected architecture

Retain the existing printer record and transport factory. Add a separate machine kind, HTTP host configuration, and an immutable validated imported profile. Saved-job compatibility uses the exact profile digest. The selected machine owns machine settings, and uploading does not start a print.

## Shape and tradeoffs

`PrinterKind` describes transport. `MachineKind` describes hardware. Imported profiles contain resolved Orca JSON with a deterministic content identity. Generic profiles initially support one nozzle and Klipper firmware only. Invalid persistence/configuration fails at its boundary; it never falls back to U1.

`SlicerTarget` retains existing built-in enum cases and adds CC1/generic targets. Plain G-code artifacts carry target and imported profile identity. Native `machineTarget` receives an explicit generic policy without changing JNI fields. Native rebuilding is required because today's non-Bambu policy hardcodes U1 geometry and tuning.

## Implementation checklist

- [x] Model migration, imported-profile validation and identity.
- [x] Separate machine and connection selection in settings.
- [x] Generic Moonraker capabilities and compatibility/send safety.
- [x] CC1 SDCP client, bounded chunk upload, lifecycle and tests.
- [x] Generic/CC1 profile composition and native policy.
- [x] NDK26 Release rebuild and offline native slicing checks on an ARM64 device.
- [x] OctoPrint transport and safe upload-only behavior.
- [x] Full JVM verification: 2,044 tests, zero failures, errors, or skips.
- [x] Debug APK packaging; instrumentation sources compile.
- [x] Device coverage: all 468 tests executed; native, migration, and printer UI checks passed. Two layout checks passed after correcting system-warning handling in their test setup.
- [x] Full 34-case U1 and H2D E2E batch passed for v4.0.5.

## Alternatives

A sealed connection hierarchy eliminates nullable configuration combinations but causes a broader persisted-schema and caller rewrite. Transport-only support cannot solve the unsafe U1 slicing assumption.

## Implementation reconciliation

The independent machine field and immutable profile snapshot avoid a connection-schema rewrite. New targets use `loadProfile` for composed machine settings, without changing JNI fields. Native policy and ABI checks pass. The new printer adapters remain beta because physical hardware operation has not been verified.

Review found additional safety paths outside the first sketch: saved jobs lacked machine identity, imported per-layer macros survived profile filtering, and native placement still used U1 bounds. Schema 9 now persists job target/profile identity. Generic native slicing removes foreign per-layer macros and blocks volume, excluded-area, and generated-helper violations. Existing U1 and Bambu policy branches remain intact.

Native validation also compares the loaded profile's bed, height, nozzle, and templates against the captured slice target. A stale native profile cannot silently supply different machine geometry.

## Verification evidence

On 2026-10-09, `testDebugUnitTest --no-daemon` passed all 2,044 tests across 219 suites. The Pixel 9a device sweep executed all 468 tests across 74 classes. The 466 non-layout tests passed in that sweep. Both layout tests passed on a focused rerun after their setup dismissed Android's system compatibility warning, with their visibility assertions unchanged.

The native library was rebuilt in Release with NDK 26, stripped, and checked for clang 17.0.2 and 64 JNI exports matching 64 Kotlin declarations. The debug APK's embedded native library matches the checked-in binary by SHA-256. Passing device tests cover generic slicing, stale profile rejection, saved-job migration, printer settings, and selected-filament propagation through the ViewModel to executable heating commands.

The Pixel uses 4 KB pages. Full 16 KB native-library and APK alignment remains an open follow-up in [issue #190](https://github.com/taylormadearmy/u1-slicer-for-android/issues/190).

## Open questions and next step

CC1 hardware is needed to validate firmware-specific acknowledgements, camera, and daemon tolerance. Imported macros can only be checked syntactically offline; their definitions belong to the user's printer. No physical prints are authorized. Camera and unproven CC1 controls remain disabled.
