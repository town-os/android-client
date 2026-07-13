#!/usr/bin/env bash
# Verify we have a usable adb and an authorized device, and explain it if not.
#
# The arch check is the non-obvious part. Google ships platform-tools for x86_64
# Linux only, so the SDK's adb is an x86_64 binary. On an ARM host it still
# *appears* runnable — binfmt_misc hands it to FEX — so a plain `test -x` passes
# and adb then runs inside an emulator. That is a bad idea for adb specifically:
# it starts a long-lived background server and talks to USB devices, and dragging
# that through FEX (and, on a 16K-page kernel, a muvm microVM) is slow and
# needlessly fragile. The distro's native adb works perfectly, so require it
# rather than quietly emulating.
set -euo pipefail

repo_root="$(cd "$(dirname "$0")/.." && pwd)"
sdk="${ANDROID_SDK_ROOT:-$repo_root/.android-sdk}"

adb="$(command -v adb 2>/dev/null || true)"

if [ -z "$adb" ]; then
  # Nothing on PATH. The SDK has one, but on ARM it is the wrong architecture.
  sdk_adb="$sdk/platform-tools/adb"
  if [ "$(uname -m)" = "aarch64" ] && [ -f "$sdk_adb" ]; then
    cat >&2 <<EOF
adb not found, and the SDK's copy is x86_64-only.

Google ships platform-tools for x86_64 Linux only. On ARM that binary would run
under FEX emulation — adb runs a background server and talks to USB, so that is
slow and fragile. Install your distro's native adb instead:

    sudo dnf install android-tools     # Fedora
    sudo pacman -S android-tools       # Arch
    sudo apt-get install adb           # Debian/Ubuntu

'make deps' installs it for you.
EOF
    exit 1
  fi
  echo "adb not found — run 'make deps' (Fedora/Arch: android-tools, Debian: adb)" >&2
  exit 1
fi

if ! "$adb" devices >/dev/null 2>&1; then
  echo "adb failed to start (found at $adb)" >&2
  exit 1
fi

if "$adb" devices | grep -qw unauthorized; then
  echo 'Device is unauthorized — accept the "Allow USB debugging?" prompt on the phone.' >&2
  exit 1
fi

if [ "$("$adb" devices | grep -cw device || true)" -eq 0 ]; then
  cat >&2 <<'EOF'
No device connected.

On the phone: Settings -> About -> tap "Build number" 7 times, then
Developer options -> USB debugging. Plug in over USB and accept the prompt.

Check with: make devices
EOF
  exit 1
fi
