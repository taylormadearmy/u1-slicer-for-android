#!/usr/bin/env bash
# =============================================================================
# setup-worktree-native.sh — make a git worktree able to build the native
# libprusaslicer-jni.so IN ISOLATION (no borrowing from u1-slicer-orca).
# =============================================================================
#
# Background
# ----------
# A fresh checkout / worktree CANNOT build the native engine because three
# build inputs are absent or unresolvable:
#
#   1. orcaslicer/ submodule  — empty (gitlink only). The pinned commit
#      (9d6c160a) lives ONLY on the taylormadearmy/OrcaSlicer fork, NOT on
#      Snapmaker/OrcaSlicer, so a naive `submodule update` fails "not our ref".
#   2. extern/*/{include,lib}  — ~1.8 GB of prebuilt arm64 deps (boost, occt,
#      cgal, eigen, tbb, gmp, mpfr, clipper2, zlib, expat, cereal, nlohmann).
#      These are GITIGNORED prebuilt binaries — never committed, so checkouts
#      don't get them.
#   3. app/.cxx/.../arm64-v8a  — the NDK26/Release CMake build cache, which
#      binds to its own source tree (CMAKE_HOME_DIRECTORY) and so must be
#      configured per worktree.
#
# This script provisions all three so any worktree builds on its own:
#   * checks out the orcaslicer submodule at its pinned SHA, fetched from the
#     fork (URL-overridden defensively, independent of .gitmodules);
#   * junctions extern/<dep>/{include,lib} into a shared neutral deps cache
#     (no per-worktree duplication; junctions land exactly on the gitignored
#     paths so they never show as dirty);
#   * fresh-configures a worktree-local NDK26/Release build dir.
#
# After it finishes, build + deploy with:
#     scripts/rebuild-native-so.sh <worktree>/app/.cxx/Release/<name>/arm64-v8a
#
# Usage:
#     scripts/setup-worktree-native.sh [worktree-path]
# Default worktree-path: the git toplevel of the current directory.
#
# Env overrides:
#     U1_DEPS_CACHE   prebuilt-deps cache extern dir
#                     (default: D:/projects/u1-native-deps-cache/extern)
#     ORCA_FORK_URL   submodule fetch URL
#                     (default: https://github.com/taylormadearmy/OrcaSlicer.git)
#     ANDROID_NDK_HOME, CMAKE_BIN, NINJA_BIN  toolchain locations
# =============================================================================

set -euo pipefail

WT="${1:-$(git rev-parse --show-toplevel)}"
WT="$(cd "$WT" && pwd)"                       # normalise

CACHE="${U1_DEPS_CACHE:-D:/projects/u1-native-deps-cache/extern}"
FORK="${ORCA_FORK_URL:-https://github.com/taylormadearmy/OrcaSlicer.git}"

NDK="${ANDROID_NDK_HOME:-D:/Android/Sdk/ndk/26.1.10909125}"
CMAKE_BIN="${CMAKE_BIN:-D:/Android/Sdk/cmake/3.22.1/bin/cmake.exe}"
NINJA_BIN="${NINJA_BIN:-D:/Android/Sdk/cmake/3.22.1/bin/ninja.exe}"

CPP="$WT/app/src/main/cpp"
SUBMODULE_REL="app/src/main/cpp/orcaslicer"
SUB="$WT/$SUBMODULE_REL"
EXTERN="$CPP/extern"

WT_NAME="$(basename "$WT")"
BUILD_DIR="$WT/app/.cxx/Release/$WT_NAME/arm64-v8a"

# Big prebuilt deps (gitignored binaries) — junctioned from the cache.
DEPS=(boost occt cgal eigen tbb clipper2 zlib expat cereal nlohmann gmp mpfr)

# Tiny source-shim dirs the engine needs on its include path. These SHOULD be
# committed to git (they are un-ignored), but worktrees branched off a main that
# predates that commit won't have them — copy them from the cache as a fallback.
STUBS=(freetype_stub jpeg_stub libbgcode_stub libpng_stub nlopt_stub opencv_stub openssl_stub openvdb_stub qhull_stub)

say() { echo "==> $*"; }
die() { echo "ERROR: $*" >&2; exit 1; }

[ -d "$CPP" ]   || die "no app/src/main/cpp under worktree: $WT"
[ -d "$CACHE" ] || die "deps cache not found: $CACHE (populate it once from a known-good extern/)"
[ -d "$NDK" ]   || die "NDK not found: $NDK"
[ -x "$CMAKE_BIN" ] || die "cmake not found: $CMAKE_BIN"

say "Worktree:   $WT"
say "Deps cache: $CACHE"
say "Build dir:  $BUILD_DIR"

# ---------------------------------------------------------------------------
# 1. orcaslicer submodule — checkout pinned SHA from the fork
# ---------------------------------------------------------------------------
PIN="$(git -C "$WT" ls-tree HEAD "$SUBMODULE_REL" | awk '{print $3}')"
[ -n "$PIN" ] || die "could not read submodule pin from superproject tree"
say "Submodule pin: $PIN"

# Force the fetch URL to the fork, independent of .gitmodules. ORDER MATTERS:
# `submodule sync` copies the .gitmodules URL into .git/config, so the override
# MUST come AFTER sync or it gets reverted. Future engine commits (e.g. the
# ColorMix port) live only on the fork, so the fork must always win the fetch.
git -C "$WT" submodule init "$SUBMODULE_REL" >/dev/null 2>&1 || true
git -C "$WT" submodule sync "$SUBMODULE_REL" >/dev/null 2>&1 || true
git -C "$WT" config "submodule.$SUBMODULE_REL.url" "$FORK"

CUR=""
if git -C "$SUB" rev-parse HEAD >/dev/null 2>&1; then
  CUR="$(git -C "$SUB" rev-parse HEAD)"
fi

if [ "$CUR" = "$PIN" ]; then
  say "Submodule already at pin — skipping fetch."
else
  say "Initialising + fetching orcaslicer from fork ..."
  git -C "$WT" submodule update --init --recursive "$SUBMODULE_REL" || {
    # Fallback: the pinned SHA lives on a branch, not at a default ref tip.
    say "submodule update fell back to manual fetch of $PIN ..."
    git -C "$SUB" remote set-url origin "$FORK" 2>/dev/null || true
    git -C "$SUB" fetch --tags origin
    git -C "$SUB" checkout "$PIN"
    git -C "$WT" submodule update --init --recursive "$SUBMODULE_REL" || true
  }
  CUR="$(git -C "$SUB" rev-parse HEAD 2>/dev/null || echo none)"
  [ "$CUR" = "$PIN" ] || die "submodule checkout is $CUR, expected pin $PIN"
fi
say "orcaslicer at $CUR ✓"

# ---------------------------------------------------------------------------
# 2. extern prebuilt deps — junction from the shared cache
# ---------------------------------------------------------------------------
# Windows directory junction (no admin needed on NTFS). Idempotent: skip if
# the link/dir already resolves.
link_dir() {
  local link="$1" target="$2"
  [ -d "$target" ] || return 0          # nothing in cache for this sub
  if [ -e "$link" ]; then
    return 0                            # already provisioned (junction or real dir)
  fi
  mkdir -p "$(dirname "$link")"
  local lwin twin
  lwin="$(cygpath -w "$link")"
  twin="$(cygpath -w "$target")"
  cmd //c mklink //J "$lwin" "$twin" >/dev/null
  echo "    junction $link -> $target"
}

say "Linking extern deps from cache ..."
for d in "${DEPS[@]}"; do
  for sub in include lib; do
    link_dir "$EXTERN/$d/$sub" "$CACHE/$d/$sub"
  done
done
say "extern deps linked ✓"

# Stub source-shim dirs — copy from cache only if the checkout didn't carry them.
for s in "${STUBS[@]}"; do
  if [ ! -e "$EXTERN/$s" ] && [ -d "$CACHE/$s" ]; then
    cp -r "$CACHE/$s" "$EXTERN/$s"
    echo "    stub copied $s"
  fi
done

# ---------------------------------------------------------------------------
# 3. Configure worktree-local NDK26 / Release build dir
# ---------------------------------------------------------------------------
if [ -f "$BUILD_DIR/build.ninja" ]; then
  say "Build dir already configured — skipping cmake configure."
else
  say "Configuring CMake (NDK26 / Release) ..."
  mkdir -p "$BUILD_DIR"
  "$CMAKE_BIN" \
    -H"$CPP" \
    -DCMAKE_SYSTEM_NAME=Android \
    -DCMAKE_SYSTEM_VERSION=26 \
    -DANDROID_PLATFORM=android-26 \
    -DANDROID_ABI=arm64-v8a \
    -DCMAKE_ANDROID_ARCH_ABI=arm64-v8a \
    -DANDROID_NDK="$NDK" \
    -DCMAKE_ANDROID_NDK="$NDK" \
    -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
    -DCMAKE_MAKE_PROGRAM="$NINJA_BIN" \
    -DCMAKE_BUILD_TYPE=Release \
    -B"$BUILD_DIR" \
    -GNinja \
    -DSLICER_BACKEND=orca \
    -DANDROID_STL=c++_shared
  say "Configured ✓"
fi

echo ""
say "Worktree is native-build ready."
echo "Build + deploy with:"
echo "    scripts/rebuild-native-so.sh \"$BUILD_DIR\""
