#!/usr/bin/env bash
# Install everything needed to run the app in an Android emulator.
#
# Run by `make deps` as its last step. It is by far the most expensive one — the
# emulator plus one system image is several GB — so it goes last, after the parts
# that get you to "can build"; a failure here does not cost you those.
#
# Three separate things, in order:
#
#   1. Host libraries. The emulator bundles its own QEMU and Qt but links against
#      the system's audio, GL and X11 libraries.
#   2. Access to /dev/kvm. Without it the emulator falls back to pure software
#      emulation, which is slow enough to be useless.
#   3. The SDK's `emulator` package, a system image, and an AVD to boot.
#
# Safe to re-run: every step is idempotent.
set -euo pipefail

: "${ANDROID_SDK_ROOT:?ANDROID_SDK_ROOT must be set (the Makefile sets it)}"
: "${ANDROID_AVD_HOME:?ANDROID_AVD_HOME must be set (the Makefile sets it)}"
AVD_NAME="${AVD_NAME:-townos}"
AVD_DEVICE="${AVD_DEVICE:-pixel_6}"
SYSTEM_IMAGE="${SYSTEM_IMAGE:-system-images;android-35;default;x86_64}"
AVD_HW_KEYBOARD="${AVD_HW_KEYBOARD:-no}"

# ---------------------------------------------------------------------------
# x86_64 only — skipped, not failed, on anything else
# ---------------------------------------------------------------------------
# Google ships the Android emulator for linux-x86_64, darwin-x86_64 and
# darwin-aarch64 — there is no linux-aarch64 build.
#
# Unlike aapt2, this cannot be papered over with FEX. The emulator's whole point
# is running the guest on the host CPU via KVM, and an x86_64 process under
# emulation has no route to an aarch64 host's KVM; you would be emulating x86 to
# run a virtualiser that then has nothing to virtualise. Building on ARM works
# fine (see make/emulation.sh) — running the emulator there does not.
#
# `make deps` depends on this target, and ARM is a first-class build host here,
# so exit 0: an unavailable emulator must not fail the whole dependency install.
# Same shape as make/x86-jdk.sh, which no-ops on the arch that does not need it.
if [ "$(uname -m)" != "x86_64" ]; then
  cat <<EOF
$(uname -m) host — skipping the emulator (x86_64 Linux only).

Google publishes no linux-aarch64 emulator, and FEX cannot substitute: the
emulator needs the host's KVM, which an emulated x86_64 process cannot reach.
Building here is unaffected. Test on a real phone:  make install
EOF
  exit 0
fi

if [ -f /etc/os-release ]; then
  . /etc/os-release
fi

echo "==> host libraries"
case "${ID:-}" in
  arch|manjaro|endeavouros|garuda)
    sudo pacman -S --needed --noconfirm \
      libpulse mesa nss alsa-lib \
      libx11 libxcb libxcursor libxdamage libxrandr libxi libxtst libxcomposite
    ;;
  ubuntu|debian|pop|linuxmint)
    sudo apt-get update
    sudo apt-get install -y \
      libpulse0 libgl1 libnss3 \
      libx11-6 libx11-xcb1 libxcursor1 libxdamage1 libxrandr2 libxi6 libxtst6 \
      libxcomposite1
    # libasound2 was renamed libasound2t64 by the 64-bit time_t transition
    # (Ubuntu 24.04+). Ask for whichever this release actually has rather than
    # failing the whole install on a package name.
    sudo apt-get install -y libasound2t64 || sudo apt-get install -y libasound2
    ;;
  *)
    echo "Unsupported distro: ${ID:-unknown}" >&2
    echo "Install manually: libpulse, mesa/libGL, nss, alsa-lib, and the X11" >&2
    echo "client libraries (libX11, libXcursor, libXdamage, libXrandr, libXi," >&2
    echo "libXtst, libXcomposite)." >&2
    exit 1
    ;;
esac

echo
echo "==> KVM"
if [ ! -e /dev/kvm ]; then
  cat >&2 <<'EOF'
warning: /dev/kvm does not exist.

Hardware virtualisation is off in firmware, or this is itself a VM without
nested virtualisation. The emulator will still boot, but under pure software
emulation it is slow enough that you will give up on it.
EOF
elif [ ! -w /dev/kvm ]; then
  echo "adding $USER to the kvm group"
  sudo usermod -aG kvm "$USER"
  echo "log out and back in for it to take effect (or run: newgrp kvm)"
else
  echo "ok: /dev/kvm is writable"
fi

SDKMANAGER="$ANDROID_SDK_ROOT/cmdline-tools/latest/bin/sdkmanager"
AVDMANAGER="$ANDROID_SDK_ROOT/cmdline-tools/latest/bin/avdmanager"

if [ ! -x "$SDKMANAGER" ]; then
  echo "Android SDK command-line tools missing — run 'make deps' first" >&2
  exit 1
fi

echo
echo "==> emulator and system image ($SYSTEM_IMAGE)"
# Quoted: the package coordinate contains semicolons.
"$SDKMANAGER" --sdk_root="$ANDROID_SDK_ROOT" "emulator" "$SYSTEM_IMAGE"

echo
echo "==> AVD '$AVD_NAME'"
mkdir -p "$ANDROID_AVD_HOME"
if [ -d "$ANDROID_AVD_HOME/$AVD_NAME.avd" ]; then
  echo "already exists (delete $ANDROID_AVD_HOME/$AVD_NAME.avd to recreate)"
else
  # avdmanager prompts "Do you wish to create a custom hardware profile?" and
  # blocks forever on a closed stdin; "no" takes the device profile as-is.
  echo no | "$AVDMANAGER" create avd \
    --name "$AVD_NAME" \
    --package "$SYSTEM_IMAGE" \
    --device "$AVD_DEVICE"
fi

# ---------------------------------------------------------------------------
# hw.keyboard=no is deliberate
# ---------------------------------------------------------------------------
# With a hardware keyboard present, Android suppresses the on-screen IME — and
# the emulator counts the host keyboard as one. That makes the default AVD
# actively useless for the thing an emulator is most useful for here: checking
# that the soft keyboard does not cover the login fields. Turning it off is what
# makes the IME appear at all.
#
# The cost is that you cannot type with the host keyboard. Use the on-screen one,
# or push text in with:  adb -e shell input text 'hello'
# Set AVD_HW_KEYBOARD=yes to get host typing back.
config="$ANDROID_AVD_HOME/$AVD_NAME.avd/config.ini"
if [ -f "$config" ]; then
  tmp="$(mktemp)"
  grep -v '^hw\.keyboard=' "$config" > "$tmp" || true
  printf 'hw.keyboard=%s\n' "$AVD_HW_KEYBOARD" >> "$tmp"
  mv "$tmp" "$config"
  echo "hw.keyboard=$AVD_HW_KEYBOARD"
fi

echo
echo "Emulator ready. Start the app with: make run"
