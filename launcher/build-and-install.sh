#!/usr/bin/env bash
# Build the launcher APK and install it on the TV as the default home screen.
set -euo pipefail
cd "$(dirname "$0")"
: "${TV_HOST:?set TV_HOST to the TV IP address, e.g. TV_HOST=192.168.1.50 $0}"
TOOLS="$(cd ../tools && pwd)"   # absolute, so the venv's Python doesn't warn about "../" in its prefix
PY=$TOOLS/venv/bin/python
[ -x "$PY" ] || PY=$TOOLS/venv/Scripts/python.exe
export MSYS_NO_PATHCONV=1

HOME_QUERY='cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME'
# Which app owns Home differs by build (com.google.android.tvlauncher on Android TV,
# com.google.android.apps.tv.launcherx on newer Google TV), so ask the TV instead of guessing.
# Only a launcher we recognise is accepted: the answer can also be Android's chooser ("android"),
# the setup wizard, or another third-party launcher, none of which should be disabled by accident.
# Pass STOCK_LAUNCHER=<package> to name it yourself, or STOCK_LAUNCHER=none to disable nothing.
KNOWN_STOCK_LAUNCHERS="com.google.android.tvlauncher com.google.android.apps.tv.launcherx com.google.android.leanbacklauncher com.mitv.tvhome"

# Home's owner, or an empty string when the TV cannot be reached (pipefail would otherwise make a
# lost connection abort the script with no explanation).
query_home() {
  "$PY" -I "$TOOLS/tv.py" "$HOME_QUERY" 2>/dev/null | tail -n 1 | tr -d '\r' || true
}

query_claimants() {
  "$PY" -I "$TOOLS/tv.py" 'cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.HOME' \
    | tr -d '\r' | grep -o '[A-Za-z0-9_.]*/' | tr -d / | sort -u || true
}

recovery_file() {
  printf '%s/cleanyourtv-handover-%s.txt' "${HOME:-$PWD}" \
    "$(printf '%s' "$TV_HOST" | tr -c 'A-Za-z0-9._-' '_')"
}

# JSON string literal, including the empty one: sed alone emits nothing for empty input, which would
# produce invalid JSON such as "prior_component": instead of "prior_component":"".
json_string() {
  printf '"%s"' "$(printf '%s' "$1" | sed 's/\\/\\\\/g; s/"/\\"/g')"
}

# Read-only contract output for the companion: current owner, exact component and supported modes.
# It never installs, disables, enables or sets anything.
if [ "${1:-}" = --status ]; then
  CURRENT=$(query_home)
  case "$CURRENT" in
    com.gathin.tvhome/*) OWNER=new ;;
    android) OWNER=chooser ;;
    "") OWNER=unknown ;;
    *)
      PKG=${CURRENT%%/*}
      case " $KNOWN_STOCK_LAUNCHERS " in
        *" $PKG "*) OWNER=stock ;;
        *) OWNER=other ;;
      esac ;;
  esac
  PRIOR=""
  [ "$OWNER" = stock ] && PRIOR=$CURRENT
  REC=$(recovery_file)
  if [ -f "$REC" ]; then REC_EXISTS=true; else REC_EXISTS=false; fi
  printf '{"new_package":"com.gathin.tvhome","new_component":"com.gathin.tvhome/.MainActivity",'
  printf '"home":{"component":%s,"package":%s,"owner":"%s"},' \
    "$(json_string "$CURRENT")" "$(json_string "${CURRENT%%/*}")" "$OWNER"
  printf '"stock":{"launcher":"%s","prior_component":%s},' \
    "$([ "$OWNER" = stock ] && printf '%s' "${CURRENT%%/*}")" \
    "$(json_string "$PRIOR")"
  printf '"modes":["takeover","leave-stock-alone","restore-prior-owner"],'
  printf '"recovery":{"file":"%s","exists":%s}}' "$REC" "$REC_EXISTS"
  printf '\n'
  exit 0
fi

# macOS: java_home or Homebrew; Linux: the java on PATH.
export JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 17 2>/dev/null || { [ -d /opt/homebrew/opt/openjdk@17 ] && echo /opt/homebrew/opt/openjdk@17; } || java -XshowSettings:properties -version 2>&1 | awk '/java.home/ {print $3}')}"
# Android Studio's default SDK location on macOS, then on Linux, then Homebrew's command-line tools.
export ANDROID_HOME="${ANDROID_HOME:-$(for d in ~/Library/Android/sdk ~/Android/Sdk; do [ -d "$d" ] && echo "$d" && break; done)}"
export ANDROID_HOME="${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}"
export PATH="$JAVA_HOME/bin:$PATH"
[ -f local.properties ] || echo "sdk.dir=$ANDROID_HOME" > local.properties
./gradlew assembleRelease -q --console=plain

ORIGINAL_HOME=$(query_home)
if [ -z "${STOCK_LAUNCHER:-}" ]; then
  HOME_PKG=${ORIGINAL_HOME%%/*}
  if [ -z "$ORIGINAL_HOME" ]; then
    echo "ERROR: could not ask the TV which launcher owns Home. Check TV_HOST and the debugging prompt on the TV," >&2
    echo "or pass STOCK_LAUNCHER=<package> yourself." >&2; exit 1
  fi
  case " $KNOWN_STOCK_LAUNCHERS " in
    *" $HOME_PKG "*) STOCK_LAUNCHER=$HOME_PKG ;;
    *)
      if [ "$HOME_PKG" = com.gathin.tvhome ]; then
        STOCK_LAUNCHER=none; echo "Home already belongs to this launcher; nothing will be disabled."
      elif [ "$HOME_PKG" = android ]; then
        # Android's chooser: two or more launchers tie and no default is set, which is where a TV lands
        # after tools/restore.sh followed by a re-applied list. Take the one known stock launcher among
        # the HOME claimants, if there is exactly one.
        CLAIMANTS=$(query_claimants)
        FOUND=""; N=0
        for k in $KNOWN_STOCK_LAUNCHERS; do
          if printf '%s\n' "$CLAIMANTS" | grep -qx "$k"; then FOUND=$k; N=$((N + 1)); fi
        done
        if [ "$N" -eq 1 ]; then
          STOCK_LAUNCHER=$FOUND; echo "Home is Android's chooser; the stock launcher among the candidates is $STOCK_LAUNCHER."
        else
          echo "ERROR: Home is Android's launcher chooser and $N known stock launchers claim Home: pick a default on the TV first," >&2
          echo "or run again with STOCK_LAUNCHER=<package to disable> or STOCK_LAUNCHER=none." >&2; exit 1
        fi
      else
        echo "ERROR: Home currently resolves to '${HOME_PKG:-nothing}', which is not a stock launcher this script knows." >&2
        echo "  android                                  = Android's launcher chooser (two launchers tie); pick a default on the TV first" >&2
        echo "  com.google.android.tungsten.setupwraith  = Google TV's setup wizard; see README, Troubleshooting" >&2
        echo "  anything else                            = another launcher is the default" >&2
        echo "Run again with STOCK_LAUNCHER=<package to disable> or STOCK_LAUNCHER=none." >&2; exit 1
      fi ;;
  esac
fi
echo "Stock launcher: $STOCK_LAUNCHER"
# Restore must name the exact component, not just the package: Android 9's set-home-activity needs
# pkg/activity, and a package can expose more than one HOME activity. Prefer the component that owned
# Home at the start; otherwise resolve the package once, before anything is disabled.
STOCK_COMPONENT=""
if [ "$STOCK_LAUNCHER" != none ]; then
  case "$ORIGINAL_HOME" in
    "$STOCK_LAUNCHER"/*) STOCK_COMPONENT=$ORIGINAL_HOME ;;
    *) STOCK_COMPONENT=$("$PY" -I "$TOOLS/tv.py" \
         "cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME $STOCK_LAUNCHER" \
         2>/dev/null | tail -n 1 | tr -d '\r' || true) ;;
  esac
  case "$STOCK_COMPONENT" in
    "$STOCK_LAUNCHER"/*) echo "Restore component: $STOCK_COMPONENT" ;;
    *) echo "ERROR: could not resolve the exact Home component for $STOCK_LAUNCHER; nothing was changed." >&2
       echo "Pass STOCK_LAUNCHER=none to leave Home alone." >&2; exit 1 ;;
  esac
fi
if [ "$STOCK_LAUNCHER" = none ]; then DISABLE_STOCK="echo stock launcher left alone"
else DISABLE_STOCK="pm disable-user --user 0 $STOCK_LAUNCHER"; fi
APK=app/build/outputs/apk/release/app-release.apk
"$PY" -I ../tools/b64push.py "$APK" /data/local/tmp/tvhome.apk
# Install on its own: in the batch below a failed install (e.g. "not enough space" on a full /data)
# would not stop the stock launcher from being disabled.
OUT=$("$PY" -I ../tools/tv.py 'pm install -r /data/local/tmp/tvhome.apk; rm -f /data/local/tmp/tvhome.apk')
echo "$OUT"
case "$OUT" in
  *Success*) ;;
  *) echo "ERROR: pm install failed; the stock launcher was left untouched." >&2; exit 1 ;;
esac
# Start the launcher on its own, before the stock Home is touched. In the batch below a start that
# fails only prints an "Error:" line, and the stock launcher would be disabled anyway, leaving the TV
# with no usable Home (#239). "Status: ok" from `am start -W` means the activity really came up;
# the HOME resolution further down only names a component, it does not prove the activity starts.
START_OUT=$("$PY" -I ../tools/tv.py 'am start -W -n com.gathin.tvhome/.MainActivity')
echo "$START_OUT"
# Both halves matter: some builds answer "Status: ok" for a task that was merely brought to the
# front, and an activity that cannot start still prints "Error: Activity not started".
if ! printf '%s' "$START_OUT" | grep -qF 'Status: ok' || printf '%s' "$START_OUT" | grep -qi '^Error'; then
  echo "ERROR: the new launcher did not start (see above); $STOCK_LAUNCHER was left enabled." >&2
  exit 1
fi
printf '%s\n' \
  "pm grant com.gathin.tvhome android.permission.ACCESS_FINE_LOCATION 2>/dev/null; echo location-granted" \
  "cmd package compile -m speed -f com.gathin.tvhome" \
  "$DISABLE_STOCK" \
  "cmd package set-home-activity --user 0 com.gathin.tvhome/.MainActivity" \
  | "$PY" -I ../tools/tv.py --batch

# Put the stock launcher back and check that it took. A TV whose new Home does not start can only be
# rescued over ADB, so the commands go into a local file as well: when it is the connection that
# broke, the terminal that ran this script may never come back.
rollback_home() {
  if [ "$STOCK_LAUNCHER" = none ]; then
    echo "ERROR: Home is not the new launcher, and no stock launcher was disabled; nothing to roll back." >&2
    return 1
  fi
  "$PY" -I "$TOOLS/tv.py" "pm enable --user 0 $STOCK_LAUNCHER" >/dev/null 2>&1 || true
  "$PY" -I "$TOOLS/tv.py" "cmd package set-home-activity --user 0 $STOCK_COMPONENT" >/dev/null 2>&1 || true
  BACK=$(query_home)
  case "$BACK" in
    "$STOCK_COMPONENT") echo "$STOCK_COMPONENT owns Home again." >&2; return 0 ;;
    "$STOCK_LAUNCHER"/*) echo "$STOCK_LAUNCHER owns Home again, but not the exact prior component $STOCK_COMPONENT." >&2
      return 1 ;;
  esac
  REC=$(recovery_file)
  {
    echo "# cleanyourtv: the launcher handover on $TV_HOST did not finish. From the repo root:"
    echo "TV_HOST=$TV_HOST tools/tv.py 'pm enable --user 0 $STOCK_LAUNCHER'"
    echo "TV_HOST=$TV_HOST tools/tv.py 'cmd package set-home-activity --user 0 $STOCK_COMPONENT'"
    echo "# then check: TV_HOST=$TV_HOST tools/tv.py '$HOME_QUERY'"
  } > "$REC" 2>/dev/null || REC=""
  echo "ERROR: recovery is incomplete; Home still resolves to '${BACK:-nothing}'." >&2
  if [ -n "$REC" ]; then echo "The commands that finish it are in $REC; the TV still needs ADB for them." >&2
  else echo "Re-run once the TV answers: pm enable --user 0 $STOCK_LAUNCHER, then set its Home activity." >&2; fi
  return 1
}

# Order matters: the new launcher is started before the stock one is disabled, and the home
# preference is set after. Android drops the preference whenever the set of HOME apps changes, and
# on Google TV 14 the next candidate is the setup wizard's RecoveryActivity, which crash-loops
# system_server (see examples/xiaomi-tv-a-pro-2026). So check where Home ended up, and put the
# stock launcher back if it is not ours.
HOME_NOW=$(query_home)
case "$HOME_NOW" in
  com.gathin.tvhome/*) echo "Home now resolves to $HOME_NOW" ;;
  *)
    if [ -z "$HOME_NOW" ]; then
      echo "ERROR: the TV stopped answering while Home was being switched, so the outcome is unknown." >&2
    else
      echo "ERROR: Home resolves to $HOME_NOW, not to the new launcher." >&2
    fi
    rollback_home || true
    if [ -n "$HOME_NOW" ]; then
      echo "On Google TV this usually means com.google.android.tungsten.setupwraith also claims Home: add it to" >&2
      echo "your debloat list (see README, Troubleshooting), apply the list, then run this script again." >&2
    fi
    exit 1 ;;
esac

# Optional accessibility services. Off unless asked for, and Android switches them off again on
# every reinstall, so pass the flag each time you run this script.
#   INPUT_KEY_SERVICE=1   the remote's Input button (KEYCODE_TV or TV_INPUT) opens the input switcher from any
#                         app; Google TV needs this once the stock launcher is gone (InputKeyService).
#                         It also handles input_key_keycodes and app_keys (config.xml or a theme; a YouTube button)
#   HOME_KEY_SERVICE=1    remotes whose Home button sends KEYCODE_ALL_APPS instead of HOME, e.g. Xiaomi
#                         sticks: the key is routed to the launcher (HomeKeyService)
enable_service() {   # appends to enabled_accessibility_services without dropping other services
  svc=com.gathin.tvhome/.$1
  "$PY" -I ../tools/tv.py "s=\$(settings get secure enabled_accessibility_services); case \"\$s\" in *$1*) ;; null|'') settings put secure enabled_accessibility_services $svc ;; *) settings put secure enabled_accessibility_services \"\$s:$svc\" ;; esac; settings put secure accessibility_enabled 1; echo $1 enabled"
}
if [ "${INPUT_KEY_SERVICE:-0}" = 1 ]; then enable_service InputKeyService; fi
if [ "${HOME_KEY_SERVICE:-0}" = 1 ]; then enable_service HomeKeyService; fi
echo "Installed. Press Home on the remote to see it."
