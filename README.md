# U1 Slicer for Android

[![License: AGPL v3](https://img.shields.io/badge/License-AGPL_v3-blue.svg)](https://www.gnu.org/licenses/agpl-3.0)

Native Android slicer for the **Snapmaker U1** 3D printer (270×270×270mm, 4 extruders), powered by [Snapmaker Orca 2.2.4](https://github.com/Snapmaker/OrcaSlicer) (OrcaSlicer fork).

Built with Kotlin, Jetpack Compose, and OrcaSlicer's C++ engine via JNI — no server required, everything runs on-device.

Current release: `v2.10.3` (`versionCode 313`)

**This has been fully "vibe" coded using AI. A lot of effort has gone into adding as many unit, instrumented and manaual e2e tests as as possible which are run before every release, but use at your own risk.**

## Security

Security reports should be handled privately. See [SECURITY.md](SECURITY.md) for the preferred reporting flow.

## Features

- **STL and 3MF slicing** — single-color, multi-color (up to 4 extruders), and paint-based (SEMM)
- **Bambu 3MF support** — multi-plate extraction, profile embedding, sanitization pipeline
- **Smart Paint** — one-tap multi-colour segmentation via a 6-stage cascade with optional AI-driven region naming (see [below](#smart-paint))
- **3D model viewer** — OpenGL ES 3.0, drag-to-place models on bed, scale, copies
- **3D G-code viewer** — per-layer toolpath rendering with Gouraud shading; feature-type color mode (outer wall, infill, support, etc.)
- **Wipe tower auto-positioning** — evaluates 8 candidates, picks spot with most clearance
- **Moonraker connectivity** — send G-code directly to your printer; remote screen support for paxx12 extended firmware
- **Slicer overrides** — per-job control over layer height, infill, support, shell layers, surface patterns, speeds, wipe tower, and more
- **MakerWorld integration** — share models from Bambu Handy to slice locally
- **Filament library** — manage profiles with temps, retraction, flow ratio, max volumetric speed, fan curves, pressure advance, and 10+ other OrcaSlicer per-filament settings. Imports Bambu Studio filament profile JSONs and resolves the `inherits` chain against bundled BBL / generic parents (v2.8.0)
- **Process profile import** — Settings → Process Profiles imports OrcaSlicer `.orca_process` / `.json` files; the active profile's keys layer between bundled Snapmaker defaults and Prepare-screen overrides at slice time (v2.8.0)
- **Settings backup/restore** — export and import all app settings as JSON
- **Background slicing** — foreground service keeps slicing alive when app is backgrounded
- **Auto-resume** (v2.6.0): If Android closes the app while you have a model loaded, sliced, or being edited, a "Resume <name>?" banner appears on next launch to restore your session. Sliced sessions resume on the Preview tab instantly; the model loads in the background for editing.

### Smart Paint

Tap **Smart Paint** on the Prepare screen and the model is split into up to 4 paintable regions automatically. Smart Paint runs a cascade and takes the first stage that produces useful regions:

1. **Painted (from the file)** — uses paint data already baked into MMU-style 3MFs (OrcaSlicer / Bambu Studio exports). No computation — the creator's intent is preserved.
2. **Per-volume** — multi-volume 3MFs (e.g. Bambu Studio designs with per-volume extruder assignments) split along their volume boundaries.
3. **Per-object** — multi-object plates get one region per object.
4. **Triangle indices** — legacy paint formats with per-triangle extruder hints.
5. **Topology** — dihedral-angle flood-fill on the raw mesh: sharp creases become region boundaries (hull ↔ deck, body ↔ limbs). Oversized smooth components get spatial K-means subdivision so a goat's body splits into face/neck/legs by proximity.
6. **Height bands** — fallback: 12 equal-height slices. Always succeeds; useful for Hueforge tiles and props with no sharp features.

After the auto-segmentation you can tap regions to assign slots, fix mistakes with **tap-to-paint** or the **Lasso** (draw a closed polygon, everything inside commits to the selected slot), or toggle **Painted ↔ Regions** to see your work against the segmentation tree.

Optional **AI region naming** sends 4 angle screenshots to a vision LLM so generic "Region 1/2/3" labels become semantic ("head", "wing", "base"). The segmentation itself is local and deterministic — only the naming is AI-driven, and it's optional. Supported providers: Pollinations (anonymous, free, optional key for higher rate limits), Google Gemini (free 1k/day), OpenRouter, Claude, OpenAI.

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
./gradlew testDebugUnitTest              # 1444 JVM unit tests
./gradlew connectedDebugAndroidTest      # 388 instrumented tests (ARM64 device required)
```

**1832 total tests** covering G-code parsing/validation, feature-type tagging, 3MF sanitization, STL parsing, slicing integration, profile embedding, Room DAOs, placement layout, native paint-state decoding, multi-plate canonical filament list, and more.

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

The native `.so` must be built with **NDK 26** (Clang 17). NDK 25 or older produces
different code generation in OrcaSlicer's paint segmentation, causing degraded
multi-colour output (B62). Verify with: `llvm-readelf -p .comment libprusaslicer-jni.so`
— must show `clang version 17`.

**Quick rebuild** (existing build directory):

```bash
ninja -j1    # in app/.cxx/Debug/<hash>/arm64-v8a/
llvm-strip --strip-unneeded libprusaslicer-jni.so
cp libprusaslicer-jni.so app/src/main/jniLibs/arm64-v8a/
./gradlew clean installDebug
```

**Fresh build** (no existing build directory):

```bash
NDK=<path-to-ndk-26.1.10909125>
cmake -Happ/src/main/cpp \
  -DCMAKE_SYSTEM_NAME=Android -DANDROID_PLATFORM=android-26 \
  -DANDROID_ABI=arm64-v8a -DANDROID_NDK="$NDK" \
  -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
  -DCMAKE_BUILD_TYPE=Release -DSLICER_BACKEND=orca \
  -DANDROID_STL=c++_shared -GNinja \
  -B<build-dir>
ninja -j1 -C <build-dir>
```

Use `-j1` — higher parallelism OOMs on most machines. See `CLAUDE.md` for full details.

If new functionality depends on native C++ changes, it is OK to rebuild the `.so` and ship the refreshed binary. Don't leave required native changes source-only.

## Credits

- [Snapmaker Orca / OrcaSlicer](https://github.com/SoftFever/OrcaSlicer) — Core slicing engine (AGPL-3.0)
- [PrusaSlicer](https://github.com/prusa3d/PrusaSlicer) — Upstream slicer (AGPL-3.0)

## License

This project is licensed under the [GNU Affero General Public License v3.0](LICENSE) (AGPL-3.0-or-later).
