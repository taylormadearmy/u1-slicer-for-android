# u1-slicer-orca — Agent Instructions

**Read `CLAUDE.md` for full project context.** This file contains architecture, conventions, build commands, test suite breakdown, and the native rebuild procedure.

## Quick reference

**Build & install:**
```bash
./gradlew installDebug
```

**Test:**
```bash
./gradlew testDebugUnitTest                 # 519 JVM unit tests
./gradlew connectedDebugAndroidTest         # 125 instrumented tests
```

**Target device:** See `AGENTS.local.md` for local device IDs and adb targets.
**Public-safe rule:** never deploy automated tests to personal or non-phone devices.

**App ID:** `com.u1.slicer.orca`
**Current release:** `v1.5.10` (`versionCode 176`)

## Backlog

Open bugs and features: see [`BACKLOG.md`](BACKLOG.md).

## Critical constraints

- Do NOT add fields to `ModelInfo` or `SliceConfig` without rebuilding the native `.so` — JNI signatures must match
- The native `.so` is pre-built in `app/src/main/jniLibs/arm64-v8a/` — CMake is disabled
- If native source changes are required for new functionality or correct fallback behavior, it is always OK to rebuild the native `.so`; do not leave required C++ changes source-only
- Always bump the version number before committing a release — never reuse or update an existing GitHub release
- Always update `CLAUDE.md` (test counts, version) after every push or release

## Orca submodule recovery note

Current `main` pins `app/src/main/cpp/orcaslicer` at Orca commit `bd66b99b2d2b69b7d6bb7d14d30cc74c37c6424b`. That commit was not fetchable from `Snapmaker/OrcaSlicer` after the project move, but it was recovered from the old local Git object store and pushed to `https://github.com/taylormadearmy/OrcaSlicer.git` on branch `codex/recover-u1-main-bd66b99`.

Do not switch `.gitmodules` back to `Snapmaker/OrcaSlicer` unless that remote is confirmed to serve `bd66b99...`; fresh clones/worktrees will otherwise fail to initialise the native source submodule.
