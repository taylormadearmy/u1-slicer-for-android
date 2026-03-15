# Agent Instructions — U1 Slicer for Android

## General Rules

- Read `CLAUDE.md` first — it has build commands, test commands, architecture, and key conventions.
- This is a **Windows** project. Run Gradle from PowerShell, not WSL. See CLAUDE.md for exact commands.
- Test device serial: `43211JEKB16931` (Pixel 8a). **Never** use `NE12442001324`.
- Native `.so` is pre-built — do NOT attempt to rebuild it unless explicitly asked.

## Subagent Roles

### Research / Explore agents
- Use for codebase searches, understanding control flow, finding usages.
- Start from `app/src/main/java/com/u1/slicer/` for Kotlin sources.
- Native C++ is read-only reference at `app/src/main/cpp/orcaslicer/` — useful for understanding JNI contracts but never modify.

### Build agents
- Run: `powershell.exe -NoProfile -Command "cd 'C:\Users\kevin\projects\u1-slicer-orca'; .\gradlew installDebug --no-daemon"`
- After updating `jniLibs/`, always `clean installDebug`.
- If Gradle OOMs, add `--no-daemon`.

### Test agents
- **Unit tests** (255, fast): `.\gradlew testDebugUnitTest --no-daemon`
- **Instrumented tests** (97, needs device): `.\gradlew connectedDebugAndroidTest --no-daemon`
- Before instrumented tests: `Remove-Item -Recurse -Force app\build\outputs\androidTest-results -ErrorAction SilentlyContinue`
- Check results at `app/build/reports/tests/testDebugUnitTest/index.html` and `app/build/reports/androidTests/connected/debug/index.html`.

### Code review agents
- Verify unit tests pass before approving any change.
- Check that JNI-boundary types (`ModelInfo`, `SliceConfig`) haven't been modified without a native rebuild.
- Ensure new parsing/logic functions have corresponding unit tests.

## File Layout

```
app/src/main/java/com/u1/slicer/
├── MainActivity.kt          # All Compose UI, theme, navigation
├── SlicerViewModel.kt       # Main ViewModel, slicing pipeline, state machine
├── bambu/                   # Bambu 3MF handling
│   ├── BambuSanitizer.kt    # 3MF sanitization pipeline
│   ├── ThreeMfParser.kt     # 3MF ZIP/XML parsing
│   └── ProfileEmbedder.kt   # Snapmaker U1 config embedding
├── data/                    # Data classes, Room DB, DataStore
│   ├── SliceConfig.kt       # Slicing parameters (JNI boundary!)
│   ├── SlicingOverrides.kt  # Per-setting override system
│   └── SettingsBackup.kt    # Settings export/import
├── gcode/                   # G-code parsing and post-processing
│   ├── GcodeParser.kt
│   ├── GcodeValidator.kt
│   ├── GcodeToolRemapper.kt
│   └── GcodeThumbnailInjector.kt
├── model/                   # Model placement
│   └── CopyArrangeCalculator.kt
├── native/                  # JNI bridge
├── network/                 # OkHttp clients (Moonraker, MakerWorld)
├── ui/                      # UI helpers, dialogs
└── viewer/                  # OpenGL ES 3.0 renderers
```

## Critical Pitfalls

1. **JNI boundary**: `ModelInfo` and `SliceConfig` field order/types must match native signatures. Adding a field without rebuilding the `.so` causes crashes.
2. **OrcaSlicer key names**: Use `wall_loops` not `perimeters`, `sparse_infill_density` not `fill_density`, etc. PrusaSlicer names silently fail.
3. **`_generate_volumes_new`**: OrcaSlicer's active volume generation matches by `subobject_id`, NOT `firstid/lastid`. Multi-color models must be restructured into separate objects.
4. **Stale 3MF cache**: After changing sanitizer/embedder logic, cached files on device produce wrong results. The app auto-clears on version upgrade, but during dev use: `adb -s 43211JEKB16931 shell "run-as com.u1.slicer.orca sh -c 'rm files/embedded_* files/sanitized_* files/plate*.3mf'"`
5. **`resolveInto()` before slicing**: Always call `SlicingOverrides.resolveInto(SliceConfig)` — never pass raw config to native.
6. **Don't reload model before `setModelInstances()`**: It clears instances internally; extra reload causes Clipper errors.
7. **Release diagnostics for field-only Clipper failures**: Use "Share Diagnostics" from Settings or the Clipper error card to export `files/diagnostics/clipper_investigation_bundle.txt`. This is now the primary evidence path when the bug only appears on release builds or production devices.
8. **Pre-built native caveat**: Kotlin-side diagnostics are active immediately, but native JNI/TreeSupport diagnostics only land in the APK after rebuilding and copying `libprusaslicer-jni.so` into `app/src/main/jniLibs/arm64-v8a/`.
