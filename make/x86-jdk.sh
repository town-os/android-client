#!/usr/bin/env bash
# Install an x86_64 JDK, used ONLY to fork unit-test workers on an ARM host.
#
# Why: Robolectric — which is how the Android-framework code (SharedPreferences,
# Intent resolution, NotificationManager, Compose) gets tested without a phone —
# ships native libraries for linux-x86_64 and **has no linux-aarch64 build**. On
# an ARM host it dies with:
#
#     The Robolectric native runtime is not supported on Linux (aarch64)
#     UnsatisfiedLinkError: no conscrypt_openjdk_jni-linux-aarch_64
#
# We already have an x86_64 execution environment for aapt2 (FEX, inside muvm on
# a 16K-page kernel — see make/emulation.sh). An x86_64 JVM runs there too, and
# then Robolectric's x86_64 natives load normally.
#
# Only the *test workers* use this JDK. Gradle, Kotlin and the rest of the build
# keep running natively on the host's aarch64 JVM, so the cost is confined to the
# tests that actually need it. Verified: Temurin 21 x86_64 reports its version
# from inside muvm/FEX on an M1.
#
# x86_64 hosts do not need this at all.
#
# Idempotent.
set -euo pipefail

[ "$(uname -m)" = "aarch64" ] || { echo "x86_64 host — no cross JDK needed"; exit 0; }

JDK_VERSION="${JDK_VERSION:-21.0.5+11}"
repo_root="$(cd "$(dirname "$0")/.." && pwd)"
dest="$repo_root/.x86-jdk"

if [ -x "$dest/bin/java" ]; then
  echo "x86_64 JDK already present: $dest"
  exit 0
fi

# The '+' in the version is %2B in the release URL, but a plain '+' in the file.
url_version="${JDK_VERSION/+/%2B}"
file_version="${JDK_VERSION/+/_}"

echo "==> fetching Temurin ${JDK_VERSION} (x86_64) for Robolectric test workers"
mkdir -p "$dest"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
curl -fsSL -o "$tmp/jdk.tar.gz" \
  "https://github.com/adoptium/temurin21-binaries/releases/download/jdk-${url_version}/OpenJDK21U-jdk_x64_linux_hotspot_${file_version}.tar.gz"
tar xzf "$tmp/jdk.tar.gz" -C "$dest" --strip-components=1

echo "x86_64 JDK ready: $dest"
