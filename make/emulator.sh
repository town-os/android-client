#!/usr/bin/env bash
# Boot the AVD and block until Android is actually up.
#
# Idempotent: if an emulator is already running this exits immediately, so
# `make run` twice in a row does not boot a second one.
set -euo pipefail

: "${ANDROID_SDK_ROOT:?ANDROID_SDK_ROOT must be set (the Makefile sets it)}"
: "${ANDROID_AVD_HOME:?ANDROID_AVD_HOME must be set (the Makefile sets it)}"
AVD_NAME="${AVD_NAME:-townos}"
ADB="${ADB:-adb}"
EMULATOR_ARGS="${EMULATOR_ARGS:-}"
BOOT_TIMEOUT="${BOOT_TIMEOUT:-300}"

EMULATOR_BIN="$ANDROID_SDK_ROOT/emulator/emulator"

if [ ! -x "$EMULATOR_BIN" ]; then
  echo "emulator not installed — run 'make emulator-deps'" >&2
  exit 1
fi

if [ ! -d "$ANDROID_AVD_HOME/$AVD_NAME.avd" ]; then
  echo "AVD '$AVD_NAME' does not exist — run 'make emulator-deps'" >&2
  exit 1
fi

# `adb -e` means "the only running emulator", which is also how every other
# emulator command here is targeted, so a plugged-in phone is never hit by
# accident. Match on the serial rather than trusting -e to fail cleanly.
running() {
  "$ADB" devices | awk '$1 ~ /^emulator-/ && $2 == "device" { found = 1 } END { exit !found }'
}

if running; then
  echo "emulator already running"
  exit 0
fi

log="$ANDROID_AVD_HOME/$AVD_NAME.log"
echo "==> booting $AVD_NAME (log: $log)"

# nohup + redirect so the emulator outlives this script and make does not sit
# holding its stdout open waiting for a process that never exits.
# shellcheck disable=SC2086 # EMULATOR_ARGS is intentionally word-split
nohup "$EMULATOR_BIN" -avd "$AVD_NAME" -no-boot-anim $EMULATOR_ARGS \
  >"$log" 2>&1 &
pid=$!

# Two waits, because they mean different things: the device appears in `adb
# devices` long before the framework has finished starting, and installing an
# APK into a half-booted system fails in confusing ways.
"$ADB" -e wait-for-device 2>/dev/null || true

for _ in $(seq 1 "$BOOT_TIMEOUT"); do
  if ! kill -0 "$pid" 2>/dev/null; then
    echo "emulator exited during boot — last lines of $log:" >&2
    tail -20 "$log" >&2
    exit 1
  fi
  if [ "$("$ADB" -e shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; then
    # A cold boot lands on the lock screen; dismiss it so `make run` ends with
    # the app on screen rather than behind a swipe.
    "$ADB" -e shell wm dismiss-keyguard >/dev/null 2>&1 || true
    echo "booted"
    exit 0
  fi
  sleep 1
done

echo "emulator did not finish booting in ${BOOT_TIMEOUT}s — see $log" >&2
echo "(a first cold boot is slow; retry, or raise BOOT_TIMEOUT)" >&2
exit 1
