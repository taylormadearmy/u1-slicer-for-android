# U1 Slicer - E2E Testing

Current release line: `v1.4.13` (`versionCode 102`)
Primary test device: See `E2E_TESTING.local.md` for the private device IDs used on this machine.

## Automated baseline

- JVM unit tests: `408`
- Instrumented tests: `118`
- Full automated total: `526`

Run before release:

```bash
./gradlew testDebugUnitTest
./gradlew connectedDebugAndroidTest
./gradlew assembleRelease
```

If Gradle's connected-test wrapper is flaky on this Windows machine, run the device suite directly:

```bash
adb -s <pixel-8a-device-id> shell am instrument -w com.u1.slicer.orca.test/androidx.test.runner.AndroidJUnitRunner
```

## Manual release checklist

Use this checklist for final on-device sanity passes before publishing:

1. Load the file
2. If multi-plate, select the target plate
3. Confirm Prepare preview shows the expected colour count and geometry
4. Tap-select and drag an object
5. Tap-select and drag the prime tower
6. Slice successfully
7. Confirm Preview colours match the intended tool usage

Useful log capture:

```bash
adb -s <pixel-8a-device-id> logcat -s "SlicerVM,BambuSanitizer,ThreeMfParser,InlineModelPreview"
```

## Priority manual files

These are the files most likely to catch regressions quickly:

| File | Why it matters | Minimum manual check |
|------|----------------|----------------------|
| `calib-cube-10-dual-colour-merged.3mf` | Basic 2-colour baseline; post-update Clipper history | Prepare colours, move, slice |
| `Dragon Scale infinity.3mf` plate 3 | Old-format multi-plate; tri-colour regression history | Plate 3 shows 3 colours, move, slice |
| `Shashibo-h2s-textured.3mf` plate 5 | Old-format multi-plate textured case | Plate 5 preview colours, slice |
| `3DBenchy-H2C-Multi-Color-Test-Print.3mf` | H2C sparse paint / 7-colour mapping | Prepare vs Preview colour parity |
| `colored_3DBenchy (1).3mf` | Non-H2C painted benchy baseline | Prepare colours, slice |
| `2026+F1+CALENDAR+-+DATES+&+TRACK+NAMES+(P_X+SERIES).3mf` | Large Bambu file / former B18 OOM repro | Loads without OOM, slice preserves multi-colour output |

## What automation already covers well

- Multi-plate parsing and extraction
- Bambu sanitization / profile embedding
- Native Prepare preview export
- Dragon plate 3 Prepare state and slice-output colour coverage
- G-code generation and tool-remap behaviour
- Large-model preview budget guardrails

## What still benefits from manual verification

- Final visual parity between Prepare and Preview
- Plate selector UX
- Tap-to-select / drag placement feel
- Very large file load latency and messaging
- Real-device colour perception on unusual palettes
