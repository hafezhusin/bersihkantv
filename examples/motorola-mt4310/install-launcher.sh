#!/usr/bin/env bash
# Build the launcher, install it as Home on the Motorola MT4310 and push this TV's dock theme.
# Usage (from the repo root):  TV_HOST=<tv ip> examples/motorola-mt4310/install-launcher.sh
# Needs JDK 17 and the Android SDK: set JAVA_HOME and ANDROID_HOME first (see CONTRIBUTING.md).
set -euo pipefail
: "${TV_HOST:?set TV_HOST to the TV IP}"
HERE="$(cd "$(dirname "$0")" && pwd)"
"$HERE/../../launcher/build-and-install.sh"
"$HERE/../../tools/theme.sh" "$HERE/theme"
