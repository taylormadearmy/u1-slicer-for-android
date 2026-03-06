# Maestro E2E Flows

UI automation flows for U1 Slicer (Orca). Run without code — just YAML.

## Install Maestro

```bash
curl -Ls "https://get.maestro.mobile.dev" | bash
```

Requires Java 11+. Talks to device over ADB.

## Run all flows

```bash
maestro test .maestro/ --device 43211JEKB00954
```

## Run a single flow

```bash
maestro test .maestro/smoke.yaml --device 43211JEKB00954
```

## Flow descriptions

| Flow | Mirrors bridge spec | What it tests |
|------|--------------------|-|
| `smoke.yaml` | `smoke.spec.ts` | App launches, version string, all 5 tabs visible |
| `navigation.yaml` | `smoke.spec.ts` | All 5 tabs navigate without crash |
| `slice_stl.yaml` | `stl-upload.spec.ts` + `slicing.spec.ts` | STL load → slice → result shown |
| `slice_3mf_singlecolour.yaml` | `slicing.spec.ts` | Single-colour Bambu 3MF full pipeline |
| `slice_multicolour.yaml` | `multicolour-slice.spec.ts` | Dual-colour 3MF, extruder assignment, slice |

## Preparing test files for slice flows

Push test files from `u1-slicer-bridge/test-data/` to device:

```bash
# tetrahedron (for slice_stl.yaml)
adb -s 43211JEKB00954 push app/src/androidTest/assets/tetrahedron.stl //sdcard/Download/tetrahedron.stl

# fan cover (for slice_3mf_singlecolour.yaml)
adb -s 43211JEKB00954 push "app/src/androidTest/assets/u1-auxiliary-fan-cover-hex_mw.3mf" //sdcard/Download/

# calib cube (for slice_multicolour.yaml)
adb -s 43211JEKB00954 push "app/src/androidTest/assets/calib-cube-10-dual-colour-merged.3mf" //sdcard/Download/
```

Then launch the app with the file's content URI before running the flow:

```bash
ID=$(adb -s 43211JEKB00954 shell content query \
  --uri content://media/external/file \
  --projection _id:_display_name \
  --where "_display_name='tetrahedron.stl'" | grep -oE "_id=[0-9]+" | grep -oE "[0-9]+")

adb -s 43211JEKB00954 shell am start \
  -n com.u1.slicer.orca/com.u1.slicer.MainActivity \
  -a android.intent.action.VIEW \
  -t "application/octet-stream" \
  -d "content://media/external/file/$ID" \
  --grant-read-uri-permission
```

## Large files (not bundled in assets — 24MB+)

`PrusaSlicer_majorasmask_2colour.3mf` and `PrusaSlicer_majorasmask_2colour-orca.3mf`
are excluded from assets (too large for test APK). Push directly from bridge test-data:

```bash
adb -s 43211JEKB00954 push \
  "/c/Users/kevin/projects/u1-slicer-bridge/test-data/PrusaSlicer_majorasmask_2colour.3mf" \
  //sdcard/Download/
```

These are covered by `@LargeTest` annotation in `BambuPipelineIntegrationTest`.
