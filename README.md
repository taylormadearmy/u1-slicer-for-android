# U1 Slicer for Android

[![License: AGPL v3](https://img.shields.io/badge/License-AGPL_v3-blue.svg)](https://www.gnu.org/licenses/agpl-3.0)

Native Android slicer for the **Snapmaker U1** 3D printer (270×270×270mm, 4 extruders), powered by [Snapmaker Orca 2.2.4](https://github.com/Snapmaker/OrcaSlicer) (OrcaSlicer fork).

Built with Kotlin, Jetpack Compose, and OrcaSlicer's C++ engine via JNI — no server required, everything runs on-device.

## Features

- **STL and 3MF slicing** — single-color, multi-color (up to 4 extruders), and paint-based (SEMM)
- **Bambu 3MF support** — multi-plate extraction, profile embedding, sanitization pipeline
- **3D model viewer** — OpenGL ES 3.0, drag-to-place models on bed, scale, copies
- **3D G-code viewer** — per-layer toolpath rendering with Gouraud shading
- **Wipe tower auto-positioning** — evaluates 8 candidates, picks spot with most clearance
- **Moonraker connectivity** — send G-code directly to your printer
- **MakerWorld integration** — share models from Bambu Handy to slice locally
- **Filament library** — manage profiles with temps, speeds, retraction settings
- **Settings backup/restore** — export and import all app settings as JSON
- **Background slicing** — foreground service keeps slicing alive when app is backgrounded

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

- **MVVM**: SlicerViewModel (StateFlow) + Compose UI
- **DI**: Manual via AppContainer
- **Persistence**: Room DB (filaments, jobs) + DataStore (settings)
- **Network**: OkHttp (Moonraker printer API)
- **Native**: Snapmaker Orca C++ via JNI — pre-built `.so` in `jniLibs/`
- **3D**: OpenGL ES 3.0 via GLSurfaceView

## Building

The native `.so` is pre-built and committed to `app/src/main/jniLibs/arm64-v8a/`. Normal builds do not require the NDK.

```bash
./gradlew installDebug    # Build and install on connected device
./gradlew assembleDebug   # Build APK only
```

**Requirements**: Android SDK 34, JDK 17, Kotlin 1.9.22. Gradle daemon may OOM — use `--no-daemon` if builds fail.

## Testing

```bash
./gradlew testDebugUnitTest              # 376 JVM unit tests
./gradlew connectedDebugAndroidTest      # 109 instrumented tests (ARM64 device required)
```

**485 total tests** covering G-code parsing/validation, 3MF sanitization, STL parsing, slicing integration, profile embedding, Room DAOs, placement layout, and more.

Instrumented tests use [Android Test Orchestrator](https://developer.android.com/training/testing/instrumented-tests/androidx-test-libraries/runner#use-android) to run each test in its own process — prevents native memory accumulation across slicing tests.

## Project Structure

| Directory | Description |
|-----------|-------------|
| `app/src/main/java/` | Kotlin source — UI, ViewModel, data, network, viewers |
| `app/src/main/cpp/` | Native C++ — SAPIL JNI layer + OrcaSlicer submodule |
| `app/src/main/cpp/extern/tbb_serial/` | TBB serial shims for ARM64 (fixes SEMM data races) |
| `app/src/main/assets/orca_profiles/` | Snapmaker U1 printer/filament/process profiles |
| `app/src/main/jniLibs/` | Pre-built native `.so` (ARM64) |
| `app/src/test/` | JVM unit tests |
| `app/src/androidTest/` | On-device instrumented tests + test 3MF/STL assets |

## Native Rebuild

To rebuild the native library from source (requires NDK 26.1):

1. Uncomment `externalNativeBuild` blocks in `app/build.gradle`
2. Run `./gradlew assembleDebug` to configure CMake
3. Re-comment CMake blocks, then `ninja -j1` in `app/.cxx/Debug/<hash>/arm64-v8a/`
4. Strip: `llvm-strip --strip-unneeded libprusaslicer-jni.so`
5. Copy to `app/src/main/jniLibs/arm64-v8a/`
6. `./gradlew clean installDebug`

Use `-j1` — higher parallelism OOMs on most machines.

## Credits

- [Snapmaker Orca / OrcaSlicer](https://github.com/SoftFever/OrcaSlicer) — Core slicing engine (AGPL-3.0)
- [PrusaSlicer](https://github.com/prusa3d/PrusaSlicer) — Upstream slicer (AGPL-3.0)

## License

This project is licensed under the [GNU Affero General Public License v3.0](LICENSE) (AGPL-3.0-or-later).

See [THIRD_PARTY_LICENSES.md](THIRD_PARTY_LICENSES.md) for third-party dependency licenses.
