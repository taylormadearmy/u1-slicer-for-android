#!/bin/bash
# Run instrumented tests across all connected devices in parallel.
#
# Strategy: build APKs once (gradle), install on every device, then fan
# out test execution via raw `adb shell am instrument` calls with
# AndroidJUnitRunner's `numShards` / `shardIndex` test arguments. Bypasses
# the gradle daemon for the parallel section so concurrent gradle
# processes don't fight over `.gradle/` and `app/build/` file locks (the
# original reason CLAUDE.local.md required ANDROID_SERIAL pinning).
#
# Each shard runs through Android Test Orchestrator (matching the gradle
# `connectedDebugAndroidTest` setup) so per-test process isolation
# survives — important because the slicer's JNI memory accumulates across
# tests and a non-orchestrated run OOMs on heavy multi-colour fixtures.
#
# Usage:
#   scripts/run-instrumented-sharded.sh
#   scripts/run-instrumented-sharded.sh --class com.u1.slicer.SomeTest
#
# Single-device mode: if only one device is connected, runs unsharded
# (orchestrator still active) — equivalent to the gradle invocation but
# faster since it skips gradle's task graph evaluation for the test
# phase.

set -e

# Resolve repo root regardless of where the script is invoked from
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$REPO_ROOT"

# --- Discover devices ---------------------------------------------------

DEVICES=($(adb devices | grep -v "^List" | grep -E "device$" | awk '{print $1}'))
COUNT=${#DEVICES[@]}

if [ $COUNT -eq 0 ]; then
  echo "No devices connected via adb." >&2
  exit 1
fi

echo "=== Detected $COUNT device(s): ${DEVICES[*]} ==="

# --- Optional class filter ---------------------------------------------

CLASS_FILTER=""
if [ "$1" = "--class" ] && [ -n "$2" ]; then
  CLASS_FILTER="-e class $2"
  echo "    Class filter: $2"
  shift 2
fi

# --- Build APKs once ---------------------------------------------------

echo ""
echo "=== Building debug + androidTest APKs (gradle, once) ==="
./gradlew --no-daemon assembleDebug assembleDebugAndroidTest

DEBUG_APK="app/build/outputs/apk/debug/app-debug.apk"
TEST_APK="app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"

if [ ! -f "$DEBUG_APK" ] || [ ! -f "$TEST_APK" ]; then
  echo "Expected APKs not found:" >&2
  echo "  $DEBUG_APK" >&2
  echo "  $TEST_APK" >&2
  exit 1
fi

echo "    debug:     $DEBUG_APK"
echo "    androidTest: $TEST_APK"

# --- Install on every device --------------------------------------------

echo ""
echo "=== Installing APKs on all devices ==="
for DEV in "${DEVICES[@]}"; do
  echo "    $DEV"
  adb -s "$DEV" install -r "$DEBUG_APK"
  adb -s "$DEV" install -r "$TEST_APK"
done

# Orchestrator + services packages — these are normally installed by
# gradle's `connectedDebugAndroidTest` task on first run. If a device has
# never run that task, the orchestrator app may be missing. We push them
# from gradle's downloaded copies if found.
ORCH_DIR=""
for d in "$HOME/.gradle/caches/modules-2/files-2.1/androidx.test/orchestrator" \
         "$HOME/.gradle/caches/modules-2/files-2.1/androidx.test.services/test-services"; do
  if [ -d "$d" ]; then
    ORCH_DIR="$d"
    break
  fi
done

# Verify orchestrator + services are on each device. If missing, gradle
# will need to install them once (run `./gradlew connectedDebugAndroidTest`
# on that device first to seed them).
for DEV in "${DEVICES[@]}"; do
  for PKG in androidx.test.orchestrator androidx.test.services; do
    if ! adb -s "$DEV" shell "pm list packages $PKG" | grep -q "$PKG"; then
      echo "WARNING: $PKG not installed on $DEV — run \`ANDROID_SERIAL=$DEV ./gradlew connectedDebugAndroidTest\` once to install." >&2
    fi
  done
done

# --- Run tests ----------------------------------------------------------

LOGDIR="${TMPDIR:-/c/tmp}/sharded-tests-$(date +%Y%m%d-%H%M%S)"
mkdir -p "$LOGDIR"

# Orchestrator command template — matches what AGP's connectedAndroidTest
# emits. ShellExecutor lets the test runner spawn a fresh process per
# test under orchestration.
ORCH_CMD='CLASSPATH=$(pm path androidx.test.services) app_process / androidx.test.services.shellexecutor.ShellMain'

run_shard () {
  local DEV=$1
  local NUMSHARDS=$2
  local SHARDINDEX=$3
  local LOG=$4

  local SHARD_ARGS=""
  if [ "$NUMSHARDS" -gt 1 ]; then
    SHARD_ARGS="-e numShards $NUMSHARDS -e shardIndex $SHARDINDEX"
  fi

  adb -s "$DEV" shell "$ORCH_CMD am instrument -w \
    -e targetInstrumentation com.u1.slicer.orca.test/androidx.test.runner.AndroidJUnitRunner \
    -e clearPackageData true \
    $SHARD_ARGS \
    $CLASS_FILTER \
    androidx.test.orchestrator/.AndroidTestOrchestrator" > "$LOG" 2>&1
}

if [ $COUNT -eq 1 ]; then
  echo ""
  echo "=== Running tests on ${DEVICES[0]} (single device, orchestrator) ==="
  LOG="$LOGDIR/single-${DEVICES[0]}.log"
  run_shard "${DEVICES[0]}" 1 0 "$LOG"
  EXIT=$?
  cat "$LOG"
  echo ""
  echo "Log: $LOG"
  exit $EXIT
fi

echo ""
echo "=== Running sharded tests across $COUNT devices in parallel ==="
PIDS=()
for i in "${!DEVICES[@]}"; do
  DEV=${DEVICES[$i]}
  LOG="$LOGDIR/shard-$i-$DEV.log"
  echo "    shard $i → $DEV (log: $LOG)"
  run_shard "$DEV" $COUNT $i "$LOG" &
  PIDS+=($!)
done

# Wait for all shards
EXIT=0
for pid in "${PIDS[@]}"; do
  wait $pid || EXIT=1
done

# --- Summary ------------------------------------------------------------

echo ""
echo "=== Shard results ==="
TOTAL_TESTS=0
TOTAL_FAIL=0
for i in "${!DEVICES[@]}"; do
  DEV=${DEVICES[$i]}
  LOG="$LOGDIR/shard-$i-$DEV.log"

  # AndroidJUnitRunner's text output ends with either:
  #   OK (N tests)
  # or
  #   FAILURES!!!
  #   Tests run: X,  Failures: Y
  RESULT_LINE=$(grep -E "^OK \(|^FAILURES|Tests run:" "$LOG" | tail -2)
  echo "  shard $i ($DEV):"
  echo "$RESULT_LINE" | sed 's/^/    /'

  # Try to extract counts
  COUNT_RUN=$(grep -oE "Tests run: [0-9]+" "$LOG" | tail -1 | grep -oE "[0-9]+" || echo "")
  COUNT_FAIL=$(grep -oE "Failures: [0-9]+" "$LOG" | tail -1 | grep -oE "[0-9]+" || echo "")
  [ -n "$COUNT_RUN" ] && TOTAL_TESTS=$((TOTAL_TESTS + COUNT_RUN))
  [ -n "$COUNT_FAIL" ] && TOTAL_FAIL=$((TOTAL_FAIL + COUNT_FAIL))
done

echo ""
echo "Total across all shards: $TOTAL_TESTS tests, $TOTAL_FAIL failures"
echo "Logs: $LOGDIR"
exit $EXIT
