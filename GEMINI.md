# u1-slicer-orca — Gemini Instructions

**Read `CLAUDE.md` for full project context.** This file contains architecture, conventions, build commands, test suite breakdown, and the native rebuild procedure.

## Quick reference

**Build & install:**
```bash
./gradlew installDebug
```

**Test:**
```bash
./gradlew testDebugUnitTest                 # 1190 JVM unit tests
./gradlew connectedDebugAndroidTest         # 304 instrumented tests
```

**Target device:** `<pixel-8a-device-id>` (Pixel 8a)
- Always pass `-s <pixel-8a-device-id>` to adb commands
- `<nf22e1-device-id>` (NF22E1) — not a phone, never deploy to it
- `<pixel-9a-device-id>` (Pixel 9a) — user's personal device, no automated tests

**App ID:** `com.u1.slicer.orca`
**Current release:** `v2.2.6` (`versionCode 279`)

**SDK / Gradle / AVD locations:** the user has moved these to `D:` to keep `C:` free.
The persistent user env vars `ANDROID_HOME=D:\Android\Sdk`,
`ANDROID_AVD_HOME=D:\Android\avd`, `GRADLE_USER_HOME=D:\.gradle` are set in the
Windows registry — every fresh shell + Studio launch picks them up automatically.
`local.properties` also points at `D:\Android\Sdk`. No need to override anything
in build commands.

## Backlog

Open bugs and features: see [`BACKLOG.md`](BACKLOG.md).

## Critical constraints

- Do NOT add fields to `ModelInfo` or `SliceConfig` without rebuilding the native `.so` — JNI signatures must match
- The native `.so` is pre-built in `app/src/main/jniLibs/arm64-v8a/` — CMake is disabled
- Always bump the version number before committing a release — never reuse or update an existing GitHub release
- Always update `CLAUDE.md` (test counts, version) after every push or release
