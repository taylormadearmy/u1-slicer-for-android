#!/usr/bin/env bash
# Rebuild and deploy the native libprusaslicer-jni.so.
#
# Wraps the manual CMake + ninja + llvm-strip sequence so the deploy
# step (copy into app/src/main/jniLibs/arm64-v8a/) cannot complete
# while the orca submodule has uncommitted source modifications. The
# pre-commit hook is the primary defence; this script is the
# rebuild-flow's complementary safety net.
#
# Usage:  scripts/rebuild-native-so.sh [build-dir]
# Default build-dir: app/.cxx/Release/m1-stage1/arm64-v8a
#
# Prereqs:
#  - Build dir already configured with the desired CMake settings (NDK
#    26, Release, -Wl,-z,max-page-size=16384). If not, configure first
#    per CLAUDE.md §"Native Rebuild" §"Fresh build".
#  - orca submodule initialised + checked out at the intended pin.

set -euo pipefail

REPO_ROOT="$(git rev-parse --show-toplevel)"
SUBMODULE_PATH="$REPO_ROOT/app/src/main/cpp/orcaslicer"
JNILIBS_DIR="$REPO_ROOT/app/src/main/jniLibs/arm64-v8a"
SO_TARGET="$JNILIBS_DIR/libprusaslicer-jni.so"

BUILD_DIR="${1:-$REPO_ROOT/app/.cxx/Release/m1-stage1/arm64-v8a}"

NDK_ROOT="${ANDROID_NDK_HOME:-D:/Android/Sdk/ndk/26.1.10909125}"
NINJA="${NINJA_BIN:-D:/Android/Sdk/cmake/3.22.1/bin/ninja.exe}"
LLVM_STRIP="$NDK_ROOT/toolchains/llvm/prebuilt/windows-x86_64/bin/llvm-strip.exe"
LLVM_READELF="$NDK_ROOT/toolchains/llvm/prebuilt/windows-x86_64/bin/llvm-readelf.exe"

if [ ! -d "$BUILD_DIR" ]; then
  echo "ERROR: build dir $BUILD_DIR does not exist." >&2
  echo "Configure it first per CLAUDE.md §\"Native Rebuild\" §\"Fresh build\"." >&2
  exit 1
fi

if [ ! -f "$BUILD_DIR/build.ninja" ]; then
  echo "ERROR: $BUILD_DIR is not a configured CMake build dir (no build.ninja)." >&2
  exit 1
fi

echo "==> Build dir: $BUILD_DIR"
echo "==> Running ninja -j1 (orca builds are memory-heavy; -j2+ OOMs)..."
( cd "$BUILD_DIR" && "$NINJA" -j1 )

UNSTRIPPED="$BUILD_DIR/libprusaslicer-jni.so"
if [ ! -f "$UNSTRIPPED" ]; then
  # Some CMake configurations place the .so in a subdir
  UNSTRIPPED="$(find "$BUILD_DIR" -name 'libprusaslicer-jni.so' -type f | head -1)"
fi
if [ -z "$UNSTRIPPED" ] || [ ! -f "$UNSTRIPPED" ]; then
  echo "ERROR: ninja completed but couldn't find libprusaslicer-jni.so under $BUILD_DIR." >&2
  exit 1
fi
echo "==> Built: $UNSTRIPPED ($(du -h "$UNSTRIPPED" | cut -f1))"

# Safety gate: refuse to deploy if the orca submodule is dirty (F71 lesson).
echo "==> Verifying orca submodule has no uncommitted source modifications..."
DIRTY=$(git -C "$SUBMODULE_PATH" status --porcelain || true)
if [ -n "$DIRTY" ]; then
  echo "" >&2
  echo "DEPLOY REFUSED — orca submodule has uncommitted modifications:" >&2
  echo "" >&2
  echo "$DIRTY" | sed 's/^/    /' >&2
  echo "" >&2
  echo "Commit those source changes to the orca fork BEFORE deploying the .so." >&2
  echo "Otherwise the next engine bump will lose them silently (F71 happened" >&2
  echo "in 2026-05 exactly this way). See ENGINE_UPGRADE_GUIDE.md." >&2
  echo "" >&2
  echo "The built .so is left at $UNSTRIPPED but NOT copied to jniLibs/." >&2
  exit 1
fi

# Strip + deploy
echo "==> Stripping with llvm-strip --strip-unneeded..."
"$LLVM_STRIP" --strip-unneeded "$UNSTRIPPED" -o "$SO_TARGET"

# Verify
SIZE_BYTES=$(stat -c '%s' "$SO_TARGET" 2>/dev/null || stat -f '%z' "$SO_TARGET")
SIZE_MB=$((SIZE_BYTES / 1024 / 1024))
echo "==> Deployed: $SO_TARGET (${SIZE_MB} MB)"

if [ "$SIZE_MB" -lt 18 ] || [ "$SIZE_MB" -gt 25 ]; then
  echo "WARNING: .so size ${SIZE_MB} MB outside expected 19-22 MB range." >&2
  echo "         Debug builds run ~80 MB. Verify CMAKE_BUILD_TYPE in CMakeCache.txt." >&2
fi

COMPILER=$("$LLVM_READELF" -p .comment "$SO_TARGET" 2>/dev/null | grep -o 'clang version [0-9.]*' | head -1)
echo "==> Compiler: $COMPILER (expect 'clang version 17.0.2' for NDK 26)"

ALIGNS=$("$LLVM_READELF" -l "$SO_TARGET" 2>/dev/null | awk '/^[[:space:]]*LOAD/ {print $NF}' | sort -u)
echo "==> LOAD segment alignments: $ALIGNS (expect 0x4000 for 16KB-page-aligned Android 15+ compat)"

KOTLIN_COUNT=$(grep -cE 'external fun' "$REPO_ROOT/app/src/main/java/com/u1/slicer/NativeLibrary.kt" || echo "?")
JNI_COUNT=$("$LLVM_READELF" --dyn-syms "$SO_TARGET" 2>/dev/null | grep -c 'Java_com_u1_slicer_NativeLibrary' || echo "?")
echo "==> JNI symbols: $JNI_COUNT in .so vs $KOTLIN_COUNT external fun in NativeLibrary.kt"
if [ "$JNI_COUNT" != "$KOTLIN_COUNT" ]; then
  echo "WARNING: JNI symbol count mismatch — CMake may have dropped a source file." >&2
fi

echo ""
echo "Done. Test before committing: ./gradlew testDebugUnitTest --no-daemon"
