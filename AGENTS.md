# u1-slicer-orca — Agent Instructions

Android slicer for the **Snapmaker U1** 3D printer (270×270×270mm, 4 extruders), powered by OrcaSlicer 2.2.4. Kotlin + Jetpack Compose + native C++ via JNI. App ID: `com.u1.slicer.orca`.

> **CLAUDE.md** contains the full test-class listing and detailed architecture notes. This file is designed to be complete enough to operate the project without it.

---

## Build

```bash
./gradlew installDebug          # Build and install on connected device
./gradlew assembleDebug         # Build APK only
./gradlew assembleRelease       # Release APK (no signing required for testing)
```

The native `.so` is pre-built in `app/src/main/jniLibs/arm64-v8a/`. Normal builds do not require the NDK.

**Requirements:** Android SDK 34, JDK 17, Kotlin 1.9.22. Gradle daemon may OOM — use `--no-daemon` if builds fail.

**SDK / Gradle locations:** `ANDROID_HOME=D:\Android\Sdk`, `GRADLE_USER_HOME=D:\.gradle` set in Windows registry; `local.properties` also points at `D:\Android\Sdk`. No overrides needed in build commands.

---

## Testing

```bash
./gradlew testDebugUnitTest                        # 1699 JVM unit tests
./gradlew connectedDebugAndroidTest                # 433 instrumented tests — uses Orchestrator
```

**2132 total tests.** Instrumented tests run each test in its own process (Android Test Orchestrator) to prevent native memory accumulation across slicing tests. An ARM64 Android device is required.

**Target device:** See `AGENTS.local.md` (or `CLAUDE.local.md`) for local device IDs. Pass the serial via `ANDROID_SERIAL=<id>` env var. Never deploy automated tests to personal or non-phone devices (the NF22E1 listed in local notes is off-limits).

**Single-test targeting:**
```bash
ANDROID_SERIAL=<id> ./gradlew connectedDebugAndroidTest --no-daemon \
  "-Pandroid.testInstrumentationRunnerArguments.class=com.u1.slicer.SomeTest#someMethod"
```

**Windows progress wrapper** (prints `[current/total]` while the long instrumented suite runs):
```powershell
.\scripts\run-connected-with-progress.ps1 -Device <id>
.\scripts\run-connected-with-progress.ps1 -Device <id> -ClassFilter com.u1.slicer.SomeTest
```

**Confidence checks** (single entrypoint with live progress + status file):
```powershell
.\scripts\run-confidence-check.ps1
.\scripts\run-confidence-check.ps1 -Status
```
The default instrumented smoke set and E2E range live in [`scripts/confidence-check.psd1`](scripts/confidence-check.psd1). Use `-InstrumentedClasses` / `-SkipUnit` / `-SkipInstrumented` / `-SkipE2E` only when you intentionally want a partial sweep.

**Sharded across two devices** (roughly 2× speedup):
```bash
scripts/run-instrumented-sharded.sh
# or with class filter:
scripts/run-instrumented-sharded.sh --class com.u1.slicer.SomeTest
```

**All tests must pass — there are no known pre-existing failures.** If a test fails, investigate it; do not assume it is pre-existing or flaky. Never weaken a test assertion to make a failing test pass.

---

## Native Rebuild

The native `.so` **must** be built with **NDK 26** (Clang 17). NDK 25 produces different code for OrcaSlicer's paint segmentation (regression B62). **Always build Release** — Debug is ~83 MB vs ~20 MB and causes OOM crashes.

Before rebuilding from a fresh worktree, initialise the submodule:
```bash
git submodule update --init --recursive app/src/main/cpp/orcaslicer
```

**Quick rebuild** (existing `app/.cxx/Release/<dir>/arm64-v8a/` build directory):
```bash
# Verify NDK26: CMakeCache.txt must have ndk/26.1.10909125 in CMAKE_TOOLCHAIN_FILE
# Verify Release: CMAKE_BUILD_TYPE:STRING=Release
ninja -j1    # -j2+ OOMs
$NDK/toolchains/llvm/prebuilt/windows-x86_64/bin/llvm-strip.exe --strip-unneeded libprusaslicer-jni.so
cp libprusaslicer-jni.so app/src/main/jniLibs/arm64-v8a/
./gradlew clean installDebug
```

**Verification after strip:**
- Size ~19–21 MB (if 50 MB+, you built Debug — redo)
- `llvm-readelf -p .comment libprusaslicer-jni.so` → `clang version 17.0.2`
- JNI symbol count: `llvm-readelf --dyn-syms ... | grep Java_com_u1_slicer | wc -l` matches the count of `external fun` declarations in `NativeLibrary.kt`

**Fresh CMake configure** (no existing build dir):
```bash
CMAKE=D:/Android/Sdk/cmake/3.22.1/bin/cmake.exe
NDK=D:/Android/Sdk/ndk/26.1.10909125
BUILD_DIR=app/.cxx/Release/ndk26release/arm64-v8a
mkdir -p "$BUILD_DIR"
"$CMAKE" -Happ/src/main/cpp \
  -DCMAKE_SYSTEM_NAME=Android -DCMAKE_SYSTEM_VERSION=26 \
  -DANDROID_PLATFORM=android-26 -DANDROID_ABI=arm64-v8a \
  -DCMAKE_ANDROID_ARCH_ABI=arm64-v8a -DANDROID_NDK="$NDK" \
  -DCMAKE_ANDROID_NDK="$NDK" \
  -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
  -DCMAKE_MAKE_PROGRAM=D:/Android/Sdk/cmake/3.22.1/bin/ninja.exe \
  -DCMAKE_BUILD_TYPE=Release -B"$BUILD_DIR" -GNinja \
  -DSLICER_BACKEND=orca -DANDROID_STL=c++_shared
ninja -j1 -C "$BUILD_DIR"
```

Claude is pre-authorised to rebuild the native `.so` whenever a fix genuinely requires C++ changes — no need to pause and ask.

---

## Release

> **NEVER create a GitHub release or push a public tag without explicit user authorisation.**

1. Bump `versionCode` and `versionName` in `app/build.gradle`
2. Update test counts in `CLAUDE.md` and `README.md`
3. Commit, push, build the release APK, rename it to `u1-slicer-orca-v<version>.apk`, upload that APK to the GitHub release, create the release tag, then sync `BACKLOG.md` + close issues — see `CLAUDE.md §Release` for the full checklist.
4. A release is not complete until the APK asset is attached to the GitHub release page under the branded `u1-slicer-orca-v<version>.apk` filename.

Never reuse or update a published release — always use a new tag.

---

## Architecture

- **MVVM**: `SlicerViewModel` (StateFlow) + Compose UI
- **DI**: Manual via `AppContainer`
- **Persistence**: Room DB (filaments, jobs) + DataStore (settings)
- **Network**: OkHttp (Moonraker printer API)
- **Native**: OrcaSlicer C++ via JNI — pre-built `.so` in `jniLibs/arm64-v8a/`
- **3D**: OpenGL ES 3.0 (`GLSurfaceView`, `viewer/` package)
- Kotlin 1.9.22, compileSdk 34, minSdk 26, JVM 17

---

## Key conventions

- Do NOT add fields to `ModelInfo`/`SliceConfig` without rebuilding the native `.so` — JNI signatures must match
- OrcaSlicer config key names differ from PrusaSlicer: `wall_loops`, `sparse_infill_density`, `enable_prime_tower`, `initial_layer_print_height`, etc.
- Add unit tests for every new parsing/logic function
- `org.json` is Android API — add `testImplementation 'org.json:json:20231013'` for JVM tests that use it
- `MeshData` vertex format: 10 floats/vertex (3 pos + 3 normal + 4 RGBA); `recolor(extruderColors)` updates RGBA in-place

---

## ColorMix

Within-layer top-surface colour mixing:

- Any filament can be designated a **mix** of 2–4 physical extruder components with weighted distribution
- **Top-surface modes** (per-mix BETA setting): `STRIPES` (default — per-line round-robin), `PROPORTIONAL` (weighted within-line boundary), `DITHER` (1.5mm Bayer halftone dashes)
- **`fineTopLines`**: narrows top-surface line width to nozzle/2 so component stripes interleave tightly
- **`ironingGlaze`**: force-enables ironing on top surfaces and splits the ironing pass across components
- Fully wipe-tower-safe: only splits if all component tools are planned by the wipe tower for that layer
- Works for both object/part assignment (Prepare screen) and Smart Paint (painted mixes)
- Mix recipe serialized as `t<mode>,f<0|1>,i<0|1>` tokens in `mixed_filament_definitions`

---

## Critical safety rules

> **NEVER start a print on the user's physical printer without explicit permission.**
> Use **Map & Upload** / **Upload Only** for send-flow testing — these upload without starting the print.
> "Map & Print" / "Send & Print" heat filament, move the head, and use the build plate.

> **NEVER create a GitHub release or push a public tag without explicit user authorisation.**

> **NEVER deploy tests to the NF22E1 device** (`NE12442001324`).

---

## Backlog

Open bugs and features: see [`BACKLOG.md`](BACKLOG.md).
