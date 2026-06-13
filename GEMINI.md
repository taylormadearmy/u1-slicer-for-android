# u1-slicer-orca — Gemini Instructions

Android slicer for the **Snapmaker U1** 3D printer (270×270×270mm, 4 extruders), powered by OrcaSlicer 2.2.4. Kotlin + Jetpack Compose + native C++ via JNI. App ID: `com.u1.slicer.orca`.

**`AGENTS.md`** contains the complete quick reference for builds, testing, native rebuilds, and architecture. **`CLAUDE.md`** has the detailed test-class listing. Read both as needed — this file covers the same content with Gemini-specific notes.

---

## Build

```bash
./gradlew installDebug          # Build and install on connected device
./gradlew assembleDebug         # Build APK only
./gradlew assembleRelease       # Release APK
```

Use `--no-daemon` if Gradle daemon OOMs. The native `.so` is pre-built — normal builds do not need the NDK.

**SDK locations:** `ANDROID_HOME=D:\Android\Sdk`, `GRADLE_USER_HOME=D:\.gradle` (Windows registry env vars, already set).

---

## Testing

```bash
./gradlew testDebugUnitTest                        # 1670 JVM unit tests
./gradlew connectedDebugAndroidTest                # 433 instrumented tests — uses Orchestrator
```

**2103 total tests.** Requires ARM64 Android device. Set target with `ANDROID_SERIAL=<device-id>`. See `AGENTS.local.md` or `CLAUDE.local.md` for local device IDs — never deploy to the NF22E1 (`NE12442001324`) or the user's personal Pixel 9a.

Single-test run:
```bash
ANDROID_SERIAL=<id> ./gradlew connectedDebugAndroidTest --no-daemon \
  "-Pandroid.testInstrumentationRunnerArguments.class=com.u1.slicer.SomeTest#method"
```

For live progress on Windows, use:
```powershell
.\scripts\run-connected-with-progress.ps1 -Device <id>
.\scripts\run-connected-with-progress.ps1 -Device <id> -ClassFilter com.u1.slicer.SomeTest
```

All tests must pass. Do not weaken assertions to fix failures — investigate the root cause.

---

## Native Rebuild

Must use **NDK 26** (Clang 17), **Release** build type. Verify: `llvm-readelf -p .comment libprusaslicer-jni.so` → `clang version 17.0.2`. Stripped size ~19–21 MB.

Before rebuilding from a fresh worktree:
```bash
git submodule update --init --recursive app/src/main/cpp/orcaslicer
```

Run ninja in the existing build dir:
```bash
ninja -j1    # in app/.cxx/Release/<dir>/arm64-v8a/ — higher -j OOMs
$NDK/toolchains/llvm/prebuilt/windows-x86_64/bin/llvm-strip.exe --strip-unneeded libprusaslicer-jni.so
cp libprusaslicer-jni.so app/src/main/jniLibs/arm64-v8a/
./gradlew clean installDebug
```

Full CMake configure and fresh-build details: see `AGENTS.md §Native Rebuild` or `CLAUDE.md §Native Rebuild`.

---

## Architecture

- **MVVM**: `SlicerViewModel` (StateFlow) + Compose UI; manual DI via `AppContainer`
- **Persistence**: Room DB (filaments, jobs) + DataStore (settings)
- **Network**: OkHttp (Moonraker printer API)
- **Native**: OrcaSlicer C++ via JNI — pre-built `.so` in `app/src/main/jniLibs/arm64-v8a/`
- **3D**: OpenGL ES 3.0 (`GLSurfaceView`, `viewer/` package)
- Kotlin 1.9.22, compileSdk 34, minSdk 26, JVM 17

---

## Key conventions

- Do NOT add fields to `ModelInfo`/`SliceConfig` without rebuilding the native `.so`
- OrcaSlicer config keys differ from PrusaSlicer: `wall_loops`, `sparse_infill_density`, `enable_prime_tower`, `initial_layer_print_height`, etc.
- Add unit tests for every new parsing/logic function
- `org.json` is Android API — add `testImplementation 'org.json:json:20231013'` for JVM tests that use it

---

## Critical safety rules

> **NEVER start a print without explicit user permission.** Use Map & Upload / Upload Only for send-flow testing.

> **NEVER create a GitHub release or push a tag without explicit user authorisation.**

> **NEVER deploy tests to NF22E1** (`NE12442001324`).

---

## Backlog

Open bugs and features: see [`BACKLOG.md`](BACKLOG.md).
