# U1 Slicer for Android

Native Android 3D model slicer powered by **Snapmaker Orca 2.2.4** (OrcaSlicer fork) for the Snapmaker U1 printer (270×270×270mm, 4 extruders).

## Architecture

```
┌──────────────────────────────────────────┐
│           Android UI (Compose)           │
│     MainActivity / SlicerViewModel       │
├──────────────────┬───────────────────────┤
│  NativeLibrary   │   Data Classes        │
│   (JNI Bridge)   │  SliceConfig/Result   │
├──────────────────┴───────────────────────┤
│          SAPIL (C++ JNI Layer)           │
│  sapil_model / sapil_print / sapil_config│
├──────────────────────────────────────────┤
│       Snapmaker Orca 2.2.4 Core         │
│     libslic3r (Print/GCode/Model)       │
├──────────────────────────────────────────┤
│  Native Deps (Boost/TBB/CGAL/...)       │
│        Cross-compiled for ARM64          │
└──────────────────────────────────────────┘
```

## Building

The native `.so` is pre-built and committed to `app/src/main/jniLibs/arm64-v8a/`. Normal builds do not require the NDK.

```bash
./gradlew installDebug    # Build and install on connected device
./gradlew assembleDebug   # Build APK only
```

Gradle daemon may OOM — use `--no-daemon` if builds fail.

## Testing

```bash
./gradlew testDebugUnitTest           # 152 JVM unit tests (no device needed)
ANDROID_SERIAL=43211JEKB00954 ./gradlew connectedDebugAndroidTest  # 59 instrumented tests
```

See `CLAUDE.md` for full testing policy.

## Project Structure

| Directory | Description |
|-----------|-------------|
| `app/src/main/cpp/` | Native C++ (SAPIL + OrcaSlicer submodule) |
| `app/src/main/java/` | Kotlin source (UI + JNI bridge) |
| `app/src/main/assets/orca_profiles/` | Snapmaker U1 printer/filament/process profiles |
| `app/src/androidTest/assets/` | Test 3MF/STL files from u1-slicer-bridge |
| `.maestro/` | Maestro E2E UI automation flows |
| `scripts/` | Native build scripts |
| `docs/` | Technical documentation |

## Features

- STL and 3MF file slicing (single and multi-colour)
- Bambu 3MF support: multi-plate extraction, profile embedding, sanitization
- 4-extruder / wipe tower support
- 3D model viewer (OpenGL ES 3.0)
- 3D G-code viewer with per-layer rendering
- Pre-slice placement viewer (drag objects on bed)
- Moonraker printer connectivity
- Filament library (Room DB, 7 default profiles)
- MakerWorld 3MF download
- Job history

## Credits

- [OrcaSlicer](https://github.com/SoftFever/OrcaSlicer) — Core slicing engine
- [u1-slicer-bridge](https://github.com/taylormadearmy/u1-slicer-bridge) — Original server-side slicer and test data
