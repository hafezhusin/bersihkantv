#!/usr/bin/env bash
# Restore script for Siragon 2K Smart TV (jagalchi / KTC mt5867 Android 14)
set -euo pipefail

TV_HOST="${TV_HOST:-192.168.31.169}"
echo "Restoring packages on TV at $TV_HOST..."

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DISABLED_FILE="$SCRIPT_DIR/backup/packages_disabled.txt"

# Enable all packages that we may have disabled
if [ -f "$SCRIPT_DIR/debloat.txt" ]; then
    while IFS= read -r pkg || [ -n "$pkg" ]; do
        pkg=$(echo "$pkg" | sed -e 's/#.*//' -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//')
        [ -z "$pkg" ] && continue
        echo "Re-enabling $pkg..."
        adb -s "$TV_HOST:5555" shell "pm enable --user 0 $pkg" 2>/dev/null || true
    done < "$SCRIPT_DIR/debloat.txt"
fi

# Ensure stock launcher is active if needed
echo "Setting stock launcher if disabled..."
adb -s "$TV_HOST:5555" shell "pm enable --user 0 com.google.android.apps.tv.launcherx" 2>/dev/null || true
adb -s "$TV_HOST:5555" shell "cmd package set-home-activity --user 0 com.google.android.apps.tv.launcherx/com.google.android.apps.tv.launcherx.home.HomeActivity" 2>/dev/null || true

echo "Restore complete."
