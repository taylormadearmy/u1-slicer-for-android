# Native Provenance: ColorMix Top-Surface Build

This note records the native inputs for the ColorMix top-surface `.so` currently
checked into `app/src/main/jniLibs/arm64-v8a/libprusaslicer-jni.so`.

- Android repo commit carrying the binary: `0e1ab1f5` plus follow-up review fixes
- Orca submodule URL: `https://github.com/taylormadearmy/OrcaSlicer.git`
- Orca gitlink: `e29830e644f164add25ffe419db01abcccb0aef4`
- Orca containing branch observed locally: `origin/colormix-topsurface`
- Native compiler observed in `.comment`: `clang version 17.0.2`
- NDK expected by rebuild script: `D:/Android/Sdk/ndk/26.1.10909125`
- Checked-in `.so` Build ID observed during review: `b193d7c8e61eb565bc6fb59314f0847953523579`
- Checked-in `.so` size observed during review: `21,764,872` bytes
- LOAD alignment observed during review: `0x4000`
- JNI symbol count observed during review: `51`, matching `NativeLibrary.kt`

Before a public release with this binary:

- Protect the Orca gitlink with a tag or protected release branch in the fork.
- Generate and commit a SHA-256 manifest for the external native dependency cache
  used by `scripts/setup-worktree-native.sh` (`extern/*/{include,lib}`).
- Rebuild with `scripts/rebuild-native-so.sh`; it now fails before deploy if the
  size, compiler, LOAD alignment, or JNI symbol count is wrong.
