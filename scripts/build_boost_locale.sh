#!/bin/bash
set -ex
cd /tmp/u1-deps-boost-build
NDK=/mnt/c/Users/kevin/AppData/Local/Android/Sdk/ndk/26.1.10909125
export PATH="/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"

echo "NDK: $NDK"
ls "$NDK/build/cmake/android.toolchain.cmake"

echo "Building boost_locale for arm64-v8a..."
./build-android.sh \
    --boost=1.84.0 \
    --arch=arm64-v8a \
    --with-libraries=locale \
    "$NDK" 2>&1 | tail -50

echo "---RESULT---"
ls -lh build/out/arm64-v8a/lib/*locale* 2>/dev/null || echo "NO LOCALE LIB FOUND"

# Copy to extern if found
if ls build/out/arm64-v8a/lib/*locale* > /dev/null 2>&1; then
    DST="/mnt/c/Users/kevin/projects/u1-slicer-orca/app/src/main/cpp/extern/boost/lib/arm64-v8a"
    cp -v build/out/arm64-v8a/lib/*locale* "$DST/"
    echo "Copied to extern"
fi
