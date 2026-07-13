#!/usr/bin/env bash
# Verify that aapt2 can actually execute on this host, and explain it if not.
#
# aapt2 is the Android resource compiler — the ONLY native binary in an Android
# build (Gradle, Kotlin, D8/R8 and apksigner are all pure Java). Google ships it
# for x86_64 Linux only; there is no linux-arm64 build on Google Maven. So on an
# ARM host it runs under FEX emulation, and FEX needs a 4K page size.
#
# When this is not satisfied, Gradle fails with:
#     AAPT2 ... Daemon startup failed
#     ... cannot execute binary file
# which says nothing useful. Run this first so the real cause is named.
set -euo pipefail

repo_root="$(cd "$(dirname "$0")/.." && pwd)"
sdk="${ANDROID_SDK_ROOT:-$repo_root/.android-sdk}"
build_tools="${BUILD_TOOLS:-35.0.0}"
aapt2="$sdk/build-tools/$build_tools/aapt2"

if [ ! -x "$aapt2" ]; then
  echo "aapt2 not found at $aapt2 — run 'make sdk' first" >&2
  exit 1
fi

emu="$("$repo_root/make/emulation.sh")"

# shellcheck disable=SC2086 # $emu is a deliberate word-split command prefix.
if $emu "$aapt2" version >/dev/null 2>&1; then
  echo "aapt2 OK${emu:+ (via $emu)}"
  exit 0
fi

arch="$(uname -m)"
page_size="$(getconf PAGESIZE)"

cat >&2 <<EOF

================================================================================
aapt2 cannot run on this host — the Android build cannot proceed.
================================================================================
  arch:      $arch
  page size: $page_size
  aapt2:     $aapt2
  prefix:    ${emu:-<none>}

Google ships aapt2 for x86_64 Linux only, so on aarch64 it must be emulated with
FEX. FEX requires a 4K page size (x86 assumes 4K mmap granularity); on a 16K-page
kernel it aborts with "<jemalloc>: Unsupported system page size".
EOF

if [ "$arch" = "aarch64" ] && [ "$page_size" != "4096" ]; then
  cat >&2 <<'EOF'

This host is aarch64 with 16K pages, so the build must go through muvm — a
microVM that boots a 4K-page guest specifically to host FEX:

    muvm make debug

If muvm exits without running the command ("could not connect to muvm server"),
its guest server is not up. muvm generally expects a normal desktop session and
is unreliable headless / over SSH.

Alternatives, in order of how much they cost you:
  1. Run the build from a graphical session where muvm works.
  2. Boot the 4K-page kernel ('kernel', not 'kernel-16k'). FEX then runs aapt2
     directly via binfmt, no VM involved.
  3. Build on an x86_64 machine or in x86_64 CI.
EOF
fi

echo >&2 "================================================================================"
exit 1
