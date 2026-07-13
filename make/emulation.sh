#!/usr/bin/env bash
# Print the command prefix needed to run the Android build on this host.
#
# Prints nothing when the build runs natively; prints "muvm" when it must be
# wrapped in a microVM. The Makefile captures this into $(EMU) and prefixes
# every Gradle invocation with it.
#
# Why any of this is needed
# -------------------------
# Google ships `aapt2` — the Android resource compiler, and the ONLY native
# binary in an Android build — for **x86_64 Linux only**. There is no linux-arm64
# build on Google Maven (the `linux-arm64` and `linux_aarch64` classifiers both
# 404). Gradle, Kotlin, D8/R8 and apksigner are pure Java and run natively on
# aarch64. So on ARM, aapt2 — and only aapt2 — must be emulated. FEX does that.
#
# FEX has one hard requirement: a **4K page size**. x86 software assumes 4K
# mmap/mprotect granularity, so on a 16K-page kernel FEX aborts immediately with
# "<jemalloc>: Unsupported system page size". Apple Silicon supports both, and
# Fedora Asahi's default `kernel-16k` uses 16K pages.
#
# muvm bridges exactly that gap: it boots a lightweight 4K-page guest VM
# (libkrun) that shares the host filesystem, and runs the command inside it, so
# FEX works without changing the host kernel. Verified on an M1 under Asahi:
# x86_64 aapt2 reports "Android Asset Packaging Tool (aapt) 2.19" from inside
# muvm, and exit codes propagate correctly (so `make` still fails on failure).
#
#   x86_64              -> native, no prefix
#   aarch64, 4K pages   -> FEX via binfmt_misc, no prefix
#   aarch64, 16K pages  -> "muvm" (boots a 4K guest to host FEX)
set -euo pipefail

[ "$(uname -m)" = "aarch64" ] || exit 0

# FEX can drive aapt2 directly through binfmt_misc on a 4K kernel.
[ "$(getconf PAGESIZE)" = "4096" ] && exit 0

command -v muvm >/dev/null 2>&1 || exit 0

# The trailing `--` is REQUIRED, not cosmetic. muvm defines its own `-p`
# (port-forward), `-c`, `-e`, `-m`, `-t`... and its arg parser will happily
# consume flags that were meant for the command. Passing Gradle's `-p <dir>`
# without a separator makes muvm read "<dir>" as a port number and die with
#   Error: Failed to start `passt` / invalid digit found in string
# which looks like a networking bug and is not one. `--` ends muvm's own options.
#
# MUVM_MEM (MiB) caps the guest. muvm otherwise claims 80% of host RAM, which is
# hostile on a loaded machine. Left empty by default = muvm's own default.
if [ -n "${MUVM_MEM:-}" ]; then
  printf 'muvm --mem %s --' "$MUVM_MEM"
else
  printf 'muvm --'
fi
