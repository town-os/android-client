#!/usr/bin/env bash
# Install the Android SDK into $ANDROID_SDK_ROOT (repo-local by default).
#
# No distro packages the Android SDK in a form Gradle accepts, so we fetch
# Google's command-line tools and let sdkmanager pull the rest. Everything lands
# under $ANDROID_SDK_ROOT so a checkout is self-contained and `make clean` never
# has to reason about a system-wide SDK.
#
# Idempotent: re-running only installs what is missing.
set -euo pipefail

: "${ANDROID_SDK_ROOT:?ANDROID_SDK_ROOT must be set (the Makefile sets it)}"
COMPILE_SDK="${COMPILE_SDK:-35}"
BUILD_TOOLS="${BUILD_TOOLS:-35.0.0}"
CMDLINE_TOOLS="${CMDLINE_TOOLS:-13114758}"

# sdkmanager insists on living at cmdline-tools/latest/ — anywhere else and it
# refuses to run ("Could not determine SDK root").
TOOLS_DIR="$ANDROID_SDK_ROOT/cmdline-tools/latest"
SDKMANAGER="$TOOLS_DIR/bin/sdkmanager"

if [ ! -x "$SDKMANAGER" ]; then
  echo "==> fetching Android command-line tools ($CMDLINE_TOOLS)"
  mkdir -p "$ANDROID_SDK_ROOT/cmdline-tools"
  tmp="$(mktemp -d)"
  trap 'rm -rf "$tmp"' EXIT
  curl -fsSL -o "$tmp/tools.zip" \
    "https://dl.google.com/android/repository/commandlinetools-linux-${CMDLINE_TOOLS}_latest.zip"
  unzip -q "$tmp/tools.zip" -d "$tmp"
  # The zip unpacks to cmdline-tools/; move it into place as .../latest.
  rm -rf "$TOOLS_DIR"
  mkdir -p "$(dirname "$TOOLS_DIR")"
  mv "$tmp/cmdline-tools" "$TOOLS_DIR"
fi

echo "==> accepting licenses"
# sdkmanager reads each license prompt from stdin; feeding it a stream of "y"
# is the documented non-interactive path. It exits non-zero once stdin runs
# dry, which is not a failure — hence the guard.
yes | "$SDKMANAGER" --sdk_root="$ANDROID_SDK_ROOT" --licenses >/dev/null || true

echo "==> installing platform-tools, platform android-$COMPILE_SDK, build-tools $BUILD_TOOLS"
"$SDKMANAGER" --sdk_root="$ANDROID_SDK_ROOT" \
  "platform-tools" \
  "platforms;android-${COMPILE_SDK}" \
  "build-tools;${BUILD_TOOLS}"

# The Makefile exports ANDROID_HOME so Gradle finds the SDK on the command line,
# but Android Studio reads local.properties instead. Write it so both paths work
# from a single `make deps`. It is gitignored (it holds a machine-local path).
repo_root="$(cd "$(dirname "$0")/.." && pwd)"
printf 'sdk.dir=%s\n' "$ANDROID_SDK_ROOT" > "$repo_root/local.properties"

echo
echo "Android SDK ready: $ANDROID_SDK_ROOT"
echo "Build the app with: make debug"
