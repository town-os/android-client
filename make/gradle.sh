#!/usr/bin/env bash
# Install Gradle into the repo and materialize the Gradle wrapper.
#
# We do not use the distro's gradle package: Debian/Ubuntu and Fedora both ship
# versions far older than the Android Gradle Plugin requires (AGP 8.7 needs
# Gradle 8.9+), and a too-old Gradle fails with an unhelpful plugin-resolution
# error. Fetching the official distribution keeps every machine on one version.
#
# The wrapper (gradlew) is generated rather than committed, because the wrapper
# jar is a binary and committing binaries to review is a bad habit. Once
# generated, Android Studio and CI both work with no extra setup.
#
# Idempotent.
set -euo pipefail

GRADLE_VERSION="${GRADLE_VERSION:-8.11.1}"
repo_root="$(cd "$(dirname "$0")/.." && pwd)"
dist_dir="$repo_root/.gradle-dist"
gradle_bin="$dist_dir/gradle-${GRADLE_VERSION}/bin/gradle"

if [ ! -x "$gradle_bin" ]; then
  echo "==> fetching Gradle ${GRADLE_VERSION}"
  mkdir -p "$dist_dir"
  tmp="$(mktemp -d)"
  trap 'rm -rf "$tmp"' EXIT
  curl -fsSL -o "$tmp/gradle.zip" \
    "https://services.gradle.org/distributions/gradle-${GRADLE_VERSION}-bin.zip"
  unzip -q "$tmp/gradle.zip" -d "$dist_dir"
fi

if [ ! -x "$repo_root/gradlew" ]; then
  echo "==> generating the Gradle wrapper"
  (cd "$repo_root" && "$gradle_bin" --quiet wrapper --gradle-version "$GRADLE_VERSION")
fi

echo "Gradle ${GRADLE_VERSION} ready: $gradle_bin"
