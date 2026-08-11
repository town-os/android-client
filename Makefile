SHELL := /bin/bash

# A bare `make` prints help rather than silently kicking off a long SDK/Gradle
# download on a machine that may not have the toolchain yet.
.DEFAULT_GOAL := help

# The Android SDK and Gradle both live inside the repo by default, so `make deps`
# never touches a system path and two checkouts can't fight over one SDK.
# Override ANDROID_SDK_ROOT to reuse an SDK you already have.
ANDROID_SDK_ROOT ?= $(PWD)/.android-sdk
export ANDROID_SDK_ROOT
export ANDROID_HOME = $(ANDROID_SDK_ROOT)

# Pinned so every machine builds against the same toolchain. Bump deliberately.
COMPILE_SDK      ?= 35
BUILD_TOOLS      ?= 35.0.0
CMDLINE_TOOLS    ?= 13114758
# AGP 8.7 requires Gradle 8.9+; distro packages are older than that.
GRADLE_VERSION   ?= 8.11.1
export GRADLE_VERSION

# make/gradle.sh generates the wrapper, so gradlew exists after `make deps`.
#
# Absolute path, and an explicit -p project dir: muvm does NOT preserve the
# working directory into the guest, so a relative "./gradlew" is not found and
# Gradle would otherwise look for a project in the guest's cwd.
GRADLE           ?= $(PWD)/gradlew
GRADLE_ARGS      := -p $(PWD)

# Stamp the APK with an explicit version. The release workflow passes the pushed
# tag here so the tag is what ships; left empty, build.gradle.kts falls back to
# its own default. versionCode is derived from this, so don't pass anything that
# isn't a semver.
VERSION_NAME     ?=
ifneq ($(VERSION_NAME),)
GRADLE_ARGS      += -PversionName=$(VERSION_NAME)
endif

# Command prefix needed to run the build on this host. Empty on x86_64 and on a
# 4K-page aarch64 kernel (where FEX handles aapt2 transparently via binfmt);
# "muvm" on a 16K-page aarch64 kernel, where FEX can only run inside muvm's
# 4K-page microVM. See make/emulation.sh for the full explanation.
EMU              := $(shell $(PWD)/make/emulation.sh 2>/dev/null)

# muvm relays the guest's stdout/stderr through its own logger, and with RUST_LOG
# unset it prints NOTHING — a build would appear to hang and then silently
# "succeed". Exit codes do propagate, but the output does not. So force a log
# level whenever we are running under muvm. (info, not debug: debug buries the
# build in libkrun device tracing.)
ifneq ($(EMU),)
export RUST_LOG ?= info
endif

APP_ID           := com.townos.client

# Prefer a NATIVE adb from the system (`android-tools`, installed by make deps)
# and fall back to the SDK's only if there isn't one.
#
# The order matters on ARM: Google ships platform-tools for x86_64 Linux only, so
# the SDK's adb is an x86_64 binary that would be dragged through FEX/muvm on
# every invocation. adb runs a long-lived background server and talks to USB —
# emulating it is both slow and needlessly fragile, and the distro's native adb
# works perfectly. On x86_64 either one is fine.
ADB              := $(shell command -v adb 2>/dev/null || echo $(ANDROID_SDK_ROOT)/platform-tools/adb)

APK_DEBUG        := app/build/outputs/apk/debug/app-debug.apk
APK_RELEASE      := app/build/outputs/apk/release/app-release-unsigned.apk

# ---- emulator --------------------------------------------------------------
# Installed by `make deps`, via emulator-deps. Overridable here for a different
# API level or device profile without touching the scripts.
AVD_NAME         ?= townos-api$(COMPILE_SDK)
AVD_DEVICE       ?= pixel_6

# Pinned to the API level the app targets, and that is not arbitrary: from 35
# Android forces edge-to-edge, which is exactly what turns the keyboard into an
# inset the app has to handle. An older image would not exercise it.
#
# `default` rather than google_apis — nothing here touches Play services, and it
# is the smaller image.
SYSTEM_IMAGE     ?= system-images;android-$(COMPILE_SDK);default;x86_64

# AVDs live in ~/.android/avd by default, which is shared between checkouts.
# Keep ours in the repo for the same reason the SDK is here.
ANDROID_AVD_HOME ?= $(PWD)/.android-avd
export ANDROID_AVD_HOME

# Extra emulator flags — e.g. EMULATOR_ARGS=-no-window for a headless run.
EMULATOR_ARGS    ?=

# -e targets "the only running emulator", so none of these ever land on a phone
# that happens to be plugged in at the same time.
ADB_EMU          := $(ADB) -e

.PHONY: help deps deps-debian sdk gradle build debug release lint test clean \
        install uninstall logs devices check-java check-sdk check-gradle check-aapt2 \
        check-device x86-jdk emulator-deps emulator run stop emulator-logs browse

help:
	@echo 'Town OS Android client — connect a phone to a Town OS network.'
	@echo
	@echo 'Setup:'
	@echo '  deps          Install everything: host packages, Gradle, SDK, and emulator'
	@echo '  deps-debian   Same, but host packages for Debian/Ubuntu only'
	@echo '  sdk           Install/refresh the Android SDK into $$ANDROID_SDK_ROOT'
	@echo '  gradle        Install Gradle and generate ./gradlew'
	@echo '  x86-jdk       (ARM only) x86_64 JVM for Robolectric test workers'
	@echo
	@echo 'Build:'
	@echo '  debug         Build the debug APK (default build target)'
	@echo '  release       Build the unsigned release APK'
	@echo '  build         Alias for debug'
	@echo
	@echo 'Quality:'
	@echo '  lint          Run Android lint'
	@echo '  test          Run unit tests (includes the DNS-routing tests)'
	@echo
	@echo 'Emulator (reaches the box via host NAT; x86_64 only):'
	@echo '  run           Build, boot the emulator, install, and launch the app'
	@echo '  stop          Shut the emulator down'
	@echo '  emulator-logs Tail logcat for this app on the emulator'
	@echo '  browse        Open a Town OS name in the browser (URL=http://...)'
	@echo '  emulator      Just boot the emulator, without building or installing'
	@echo '  emulator-deps Install the emulator, a system image, and the AVD (in deps)'
	@echo
	@echo 'Device (needs a USB-connected phone with USB debugging on):'
	@echo '  install       Build and install the debug APK on the phone'
	@echo '  uninstall     Remove the app from the phone'
	@echo '  logs          Tail logcat for this app only'
	@echo '  devices       List attached devices'
	@echo
	@echo '  clean         Remove build output'
	@echo
	@echo "SDK:    $(ANDROID_SDK_ROOT)"
	@echo "Gradle: $(GRADLE_VERSION)"
	@echo "AVD:    $(AVD_NAME) ($(ANDROID_AVD_HOME))"

# deps takes a clean machine all the way to "can build the APK and run it". Every
# step is idempotent, so re-running is safe.
#
# emulator-deps comes last, and it is the expensive one: the emulator plus a
# system image is several GB. It also has to follow `sdk`, because it drives the
# sdkmanager that `sdk` installs. On a non-x86_64 host it skips itself rather
# than failing — see make/emulator-deps.sh.
deps:
	$(PWD)/make/deps.sh
	$(MAKE) gradle
	$(MAKE) sdk
	$(MAKE) x86-jdk
	$(MAKE) emulator-deps

deps-debian:
	$(PWD)/make/deps-debian.sh
	$(MAKE) gradle
	$(MAKE) sdk
	$(MAKE) x86-jdk
	$(MAKE) emulator-deps

gradle: check-java
	$(PWD)/make/gradle.sh

# Robolectric has no linux-aarch64 native runtime, so on ARM the unit-test
# workers are forked with an x86_64 JVM under FEX. No-op on x86_64.
x86-jdk:
	$(PWD)/make/x86-jdk.sh

sdk: check-java
	COMPILE_SDK=$(COMPILE_SDK) BUILD_TOOLS=$(BUILD_TOOLS) \
	CMDLINE_TOOLS=$(CMDLINE_TOOLS) $(PWD)/make/sdk.sh

build: debug

debug: preflight
	$(EMU) $(GRADLE) $(GRADLE_ARGS) assembleDebug
	@echo "APK: $(APK_DEBUG)"

release: preflight
	$(EMU) $(GRADLE) $(GRADLE_ARGS) assembleRelease
	@echo "APK: $(APK_RELEASE)"

# Static analysis. `check` is Gradle's umbrella task, but we spell out the
# pieces so a lint failure is distinguishable from a test failure in CI output.
lint: preflight
	$(EMU) $(GRADLE) $(GRADLE_ARGS) lint

# test runs every code check FIRST and stops on the first failure — there is no
# point running a test suite against code that does not lint. Kotlin compilation
# of both main and test sources is implied by these tasks, so a type error fails
# here rather than halfway through the suite.
#
# testDebugUnitTest, not `test`: the latter also runs the RELEASE unit tests,
# where every Compose UI test dies with "Unable to resolve activity for Intent
# { act=android.intent.action.MAIN }". createComposeRule() needs a host activity,
# which comes from compose ui-test-manifest — a debugImplementation dependency,
# because a test manifest has no business shipping in a release build. The debug
# variant runs the identical sources.
test: preflight lint
	$(EMU) $(GRADLE) $(GRADLE_ARGS) testDebugUnitTest

# Everything a build needs, checked before Gradle is invoked so failures name
# their own cause instead of surfacing as an opaque Gradle stack trace.
preflight: check-java check-gradle check-sdk check-aapt2

clean:
	@test -x $(GRADLE) && $(GRADLE) clean || rm -rf app/build build .gradle

# ---- emulator --------------------------------------------------------------
#
# The emulator does not bridge onto the LAN — the guest sits on 10.0.2.0/24
# behind QEMU's user-mode (slirp) stack, which opens real sockets on the HOST on
# the guest's behalf. So the rule is "whatever the host can reach, the guest can
# reach", and the box sees the host's IP.
#
# That is enough for the real thing: the API is plain TCP, the WireGuard
# handshake is outbound UDP NATed the same way, and DNS queries reach rolodex
# carrying the overlay source address — the property the whole split-horizon
# design hangs on. Two casualties of the NAT: inbound needs `adb forward`, and
# ICMP does not work, so a failed ping proves nothing.
#
# What still wants a real phone is the mobile conditions, not the tunnel —
# Wi-Fi/cellular handoff, doze, always-on VPN, battery. Plus window insets are
# far easier to check here, since they are API-level behaviour you would
# otherwise need a phone on that exact Android version to see.

emulator-deps:
	AVD_NAME=$(AVD_NAME) AVD_DEVICE=$(AVD_DEVICE) SYSTEM_IMAGE='$(SYSTEM_IMAGE)' \
	$(PWD)/make/emulator-deps.sh

emulator:
	@AVD_NAME=$(AVD_NAME) ADB='$(ADB)' EMULATOR_ARGS='$(EMULATOR_ARGS)' \
	$(PWD)/make/emulator.sh

# The headline target: from nothing to the app on screen.
run: debug emulator
	$(ADB_EMU) install -r $(APK_DEBUG)
	$(ADB_EMU) shell am start -n $(APP_ID)/.ui.MainActivity
	@echo
	@echo "Running in the emulator. Watch it with: make emulator-logs"

stop:
	@$(ADB_EMU) emu kill 2>/dev/null || echo "no emulator running"

emulator-logs:
	@pid=$$($(ADB_EMU) shell pidof -s $(APP_ID) 2>/dev/null | tr -d '\r'); \
	if [ -z "$$pid" ]; then \
	  echo "$(APP_ID) is not running in the emulator — start it with 'make run'" >&2; exit 1; \
	fi; \
	$(ADB_EMU) logcat --pid=$$pid

# Open a URL in the emulator's browser: the end-to-end proof that a Town OS name
# resolves through the tunnel, which is the one thing logcat cannot show you.
#
# Hence the default URL — a package FQDN, not a public site. A public site
# resolving proves only that the phone has internet.
#
# Deliberately does NOT boot an emulator: this is for poking at one you are
# already looking at, so a missing emulator is a mistake worth reporting rather
# than a two-minute cold boot you did not ask for.
URL ?= http://gitea.default.home

browse:
	@$(ADB_EMU) get-state >/dev/null 2>&1 || { \
	  echo "no emulator running — start one with 'make run' (or 'make emulator')" >&2; \
	  exit 1; }
	@out=$$($(ADB_EMU) shell am start -a android.intent.action.VIEW -d '$(URL)' 2>&1); \
	echo "$$out"; \
	if echo "$$out" | grep -qi 'error'; then \
	  echo >&2; \
	  echo "Nothing handled the URL. The AOSP ('default') system image ships no" >&2; \
	  echo "browser; a Google APIs image has Chrome. To switch:" >&2; \
	  echo "  rm -rf $(ANDROID_AVD_HOME)/$(AVD_NAME).avd $(ANDROID_AVD_HOME)/$(AVD_NAME).ini" >&2; \
	  echo "  make emulator-deps SYSTEM_IMAGE='system-images;android-$(COMPILE_SDK);google_apis;x86_64'" >&2; \
	  exit 1; \
	fi

# Install the debug APK on a USB-connected phone.
#
# This is the only way to exercise the tunnel and the DNS behaviour that is the
# app's whole point; see the emulator targets above for what those can and
# cannot stand in for.
#
# Enable Developer options on the phone (tap Build number 7 times), then USB
# debugging, then accept the "Allow USB debugging?" prompt when you plug in.
install: debug check-device
	$(ADB) install -r $(APK_DEBUG)
	@echo
	@echo "Installed. Open 'Town OS' on the phone."
	@echo "Watch it with: make logs"

uninstall: check-device
	$(ADB) uninstall $(APP_ID) || true

# Logcat scoped to this app only — an unfiltered logcat is unreadable.
logs: check-device
	@pid=$$($(ADB) shell pidof -s $(APP_ID) 2>/dev/null); \
	if [ -z "$$pid" ]; then \
	  echo "$(APP_ID) is not running — start it on the phone first." >&2; exit 1; \
	fi; \
	$(ADB) logcat --pid=$$pid

# List attached devices, so "why won't it install" has an obvious first step.
devices: check-device
	@$(ADB) devices -l

check-device:
	@$(PWD)/make/check-adb.sh

check-java:
	@command -v java >/dev/null 2>&1 || { \
	  echo 'java not found — run `make deps` first' >&2; exit 1; }

check-gradle:
	@test -x $(GRADLE) || { \
	  echo 'Gradle wrapper missing — run `make deps` (or `make gradle`)' >&2; exit 1; }

check-sdk:
	@test -d "$(ANDROID_SDK_ROOT)/platforms/android-$(COMPILE_SDK)" || { \
	  echo 'Android SDK not installed — run `make deps` (or `make sdk`)' >&2; exit 1; }

# aapt2 is the one native binary in an Android build, and Google ships it for
# x86_64 Linux only. On aarch64 it runs under emulation — which silently
# requires a 4K-page kernel. Without this check the failure surfaces as an
# inscrutable "AAPT2 Daemon startup failed" from deep inside Gradle.
check-aapt2:
	@$(PWD)/make/check-aapt2.sh
