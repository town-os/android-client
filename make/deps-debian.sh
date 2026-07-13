#!/usr/bin/env bash
# Debian/Ubuntu host dependencies for the Town OS Android client.
# See make/deps.sh for why the SDK itself is installed separately (make sdk).
set -euo pipefail

sudo apt-get update
sudo apt-get install -y openjdk-17-jdk-headless curl unzip adb

echo "host deps installed — now run: make sdk"
