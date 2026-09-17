#!/usr/bin/env bash
set -euo pipefail

OUT_DIR="${1:-device-test-logs/$(date -u +%Y%m%dT%H%M%SZ)}"

# applicationId differs per flavor: googletv has no suffix, firetv appends ".firetv".
# Override with OPENTVCAST_PACKAGE=<id> to target a specific build.
GOOGLETV_PACKAGE="tv.opentvcast"
FIRETV_PACKAGE="tv.opentvcast.firetv"
PACKAGE="${OPENTVCAST_PACKAGE:-}"

mkdir -p "$OUT_DIR"

if ! command -v adb >/dev/null 2>&1; then
  echo "adb is not installed or not on PATH" >&2
  exit 1
fi

if [[ -z "$PACKAGE" ]]; then
  if adb shell pm path "$GOOGLETV_PACKAGE" >/dev/null 2>&1; then
    PACKAGE="$GOOGLETV_PACKAGE"
  elif adb shell pm path "$FIRETV_PACKAGE" >/dev/null 2>&1; then
    PACKAGE="$FIRETV_PACKAGE"
  else
    PACKAGE="$GOOGLETV_PACKAGE"
    echo "Neither flavor is installed; assuming $PACKAGE for the remaining commands" >&2
  fi
fi

PID="$(adb shell pidof "$PACKAGE" 2>/dev/null | tr -d '\r' || true)"

adb devices -l > "$OUT_DIR/adb-devices.txt"
adb shell getprop > "$OUT_DIR/getprop.txt" || true
adb shell dumpsys package "$PACKAGE" > "$OUT_DIR/package.txt" || true
adb shell dumpsys meminfo "$PACKAGE" > "$OUT_DIR/meminfo.txt" || true

if [[ -n "$PID" ]]; then
  adb shell top -b -n 1 -p "$PID" > "$OUT_DIR/top.txt" || true
else
  echo "Package $PACKAGE is not running" > "$OUT_DIR/top.txt"
fi

# Filter by PID, not by log tag. Logging goes through Timber's DebugTree, which
# derives the tag from the calling class name, so there is no single fixed tag to
# match — an earlier version of this script filtered on a tag that never existed
# and silently captured nothing.
if [[ -n "$PID" ]]; then
  adb logcat -d -v time --pid "$PID" > "$OUT_DIR/logcat.txt" || true
else
  echo "Package $PACKAGE is not running; falling back to warnings and errors from all processes" > "$OUT_DIR/logcat.txt"
  adb logcat -d -v time '*:W' >> "$OUT_DIR/logcat.txt" || true
fi

{
  echo "package=$PACKAGE"
  echo "pid=${PID:-not-running}"
  echo "captured_at_utc=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  echo "out_dir=$OUT_DIR"
} > "$OUT_DIR/summary.txt"

echo "Wrote device test logs to $OUT_DIR"
