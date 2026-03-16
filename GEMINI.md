# u1-slicer-orca — Gemini Instructions

**Read `CLAUDE.md` for full project context.** This file contains architecture, conventions, build commands, test suite breakdown, and the native rebuild procedure.

## Quick reference

**Build & install:**
```bash
./gradlew installDebug
```

**Test:**
```bash
./gradlew testDebugUnitTest                 # 375 JVM unit tests
./gradlew connectedDebugAndroidTest         # 109 instrumented tests
```

**Target device:** `43211JEKB16931` (Pixel 8a)
- Always pass `-s 43211JEKB16931` to adb commands
- `NE12442001324` (NF22E1) — not a phone, never deploy to it
- `5A101JEBF24790` (Pixel 9a) — user's personal device, no automated tests

**App ID:** `com.u1.slicer.orca`

## Backlog

Open bugs and features: see [`BACKLOG.md`](BACKLOG.md).

## Critical constraints

- Do NOT add fields to `ModelInfo` or `SliceConfig` without rebuilding the native `.so` — JNI signatures must match
- The native `.so` is pre-built in `app/src/main/jniLibs/arm64-v8a/` — CMake is disabled
- Always bump the version number before committing a release — never reuse or update an existing GitHub release
- Always update `CLAUDE.md` (test counts, version) after every push or release
