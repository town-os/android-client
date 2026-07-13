#!/usr/bin/env bash
# Install the host dependencies needed to build the Town OS Android client.
#
# The Android SDK itself is NOT installed here — no distro packages it in a form
# Gradle can use. `make sdk` fetches the official command-line tools and drives
# sdkmanager; this script installs what that bootstrap needs (a JDK 17+, curl,
# unzip, adb) plus, on aarch64, the x86_64 emulation the SDK requires.
#
# ---------------------------------------------------------------------------
# Why aarch64 needs anything at all
# ---------------------------------------------------------------------------
# Google publishes `aapt2` — the Android resource compiler — for **x86_64 Linux
# only**. There is no linux-arm64 build on Google Maven (verified: the
# `linux-arm64` and `linux_aarch64` classifiers both 404). Everything else in an
# Android build is pure Java and runs natively on aarch64; aapt2 is the single
# native blocker. So on an ARM host, aapt2 must be emulated.
#
# On Apple Silicon (Asahi) that runs into a second problem: **page size**. FEX
# (and qemu-user) can only emulate x86_64 on a 4K-page kernel, because x86
# software assumes 4K mmap/mprotect granularity. Fedora Asahi's *default*
# `kernel-16k` uses 16384-byte pages, where FEX dies immediately with
# "<jemalloc>: Unsupported system page size".
#
# There are exactly two ways out, and this script sets up whichever applies:
#
#   4K-page kernel  -> FEX runs natively via binfmt. Install the `kernel`
#                      package (not `kernel-16k`) and boot it.
#   16K-page kernel -> FEX must run inside `muvm`, a microVM that boots a
#                      4K-page guest specifically to host FEX.
#
# Safe to re-run.
set -euo pipefail

if [ -f /etc/os-release ]; then
  . /etc/os-release
fi

ARCH="$(uname -m)"

case "${ID:-}" in
  arch|manjaro|endeavouros|garuda)
    sudo pacman -S --needed --noconfirm jdk17-openjdk curl unzip android-tools
    if [ "$ARCH" = "aarch64" ]; then
      # Arch ARM: qemu-user-static + binfmt is the available route; FEX is AUR.
      sudo pacman -S --needed --noconfirm qemu-user-static qemu-user-static-binfmt
    fi
    ;;
  ubuntu|debian|pop|linuxmint)
    exec "$(dirname "$0")/deps-debian.sh"
    ;;
  fedora*|rhel|centos|rocky|almalinux)
    sudo dnf install -y java-17-openjdk-devel curl unzip android-tools
    if [ "$ARCH" = "aarch64" ]; then
      # Asahi ships all of these; they are no-ops if already present.
      sudo dnf install -y fex-emu fex-emu-rootfs-fedora muvm qemu-user-static-x86 || true
    fi
    ;;
  *)
    echo "Unsupported distro: ${ID:-unknown}" >&2
    echo "Install manually: a JDK 17+, curl, unzip, adb (android-tools)." >&2
    [ "$ARCH" = "aarch64" ] && echo "On aarch64 you also need x86_64 emulation for aapt2." >&2
    exit 1
    ;;
esac

# The Android Gradle Plugin refuses any JDK older than 17, and it fails deep
# inside a Gradle run with a confusing error. Catch it here, where the fix is
# obvious.
if ! java -version 2>&1 | grep -qE '"(1[7-9]|2[0-9])'; then
  echo "warning: default java is not 17+; set JAVA_HOME to a JDK 17 or newer" >&2
fi

if [ "$ARCH" = "aarch64" ]; then
  # binfmt_misc is what makes the kernel transparently hand an x86_64 ELF to an
  # emulator. The handlers are registered by systemd-binfmt, which frequently has
  # not run (or ran before the mount existed) — restart it so the registration is
  # actually live.
  if command -v systemctl >/dev/null 2>&1; then
    sudo systemctl restart systemd-binfmt || true
  fi

  page_size="$(getconf PAGESIZE)"
  if [ "$page_size" != "4096" ]; then
    cat >&2 <<EOF

================================================================================
aarch64 with a ${page_size}-byte page size — aapt2 cannot run yet.
================================================================================
Google ships aapt2 for x86_64 Linux only, so it must be emulated. FEX and
qemu-user both REQUIRE a 4K page size; this kernel uses ${page_size}.

Pick one:

  1. Boot a 4K-page kernel (recommended on Fedora Asahi). FEX then runs aapt2
     transparently via binfmt, with no VM in the loop:

         sudo dnf install kernel          # NOT kernel-16k
         sudo reboot                      # select the 4K kernel

     Verify afterwards with:  getconf PAGESIZE   ->  4096

  2. Keep this 16K kernel and run the build through muvm, which boots a 4K-page
     microVM to host FEX:

         muvm -- make debug

     muvm needs a working session bus; headless/SSH use often fails with
     "could not connect to muvm server".

  3. Build on an x86_64 machine or in x86_64 CI.

Run 'make check-aapt2' once you have done one of the above.
================================================================================
EOF
  fi
fi

echo "host deps installed — now run: make gradle && make sdk"
