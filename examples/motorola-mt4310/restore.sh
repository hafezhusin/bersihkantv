#!/usr/bin/env bash
# Put the Motorola MT4310 back: re-enable everything debloat.txt disabled (including the stock launcher),
# give Home back to it, switch Private DNS back to automatic and let voice search and
# tvrecommendations bind again.
# Usage (from the repo root):  TV_HOST=<tv ip> examples/motorola-mt4310/restore.sh
set -euo pipefail
: "${TV_HOST:?set TV_HOST to the TV IP}"
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$HERE/../.."
PY="$ROOT/tools/venv/bin/python"; [ -x "$PY" ] || PY="$ROOT/tools/venv/Scripts/python.exe"
# katniss and tvrecommendations were removed for user 0 (debloat.txt header); bring them back first
"$PY" -I "$ROOT/tools/tv.py" 'cmd package install-existing com.google.android.katniss'
"$PY" -I "$ROOT/tools/tv.py" 'cmd package install-existing com.google.android.tvrecommendations'
"$ROOT/tools/restore.sh" "$HERE/debloat.txt"
"$PY" -I "$ROOT/tools/tv.py" 'pm enable --user 0 com.google.android.tvlauncher'
"$PY" -I "$ROOT/tools/tv.py" 'cmd package set-home-activity --user 0 com.google.android.tvlauncher/.MainActivity'
"$PY" -I "$ROOT/tools/tv.py" 'settings put global private_dns_mode opportunistic'
# Undo the earlier unbinding attempts, so voice search and the listener work as before
"$PY" -I "$ROOT/tools/tv.py" 'cmd appops set com.google.android.katniss RUN_ANY_IN_BACKGROUND allow'
"$PY" -I "$ROOT/tools/tv.py" 'cmd appops set com.google.android.tvrecommendations RUN_ANY_IN_BACKGROUND allow'
"$PY" -I "$ROOT/tools/tv.py" 'am set-standby-bucket com.google.android.katniss active'
"$PY" -I "$ROOT/tools/tv.py" 'am set-standby-bucket com.google.android.tvrecommendations active'
"$PY" -I "$ROOT/tools/tv.py" 'cmd notification allow_listener com.google.android.tvrecommendations/com.google.android.tvrecommendations.NotificationsService'
"$PY" -I "$ROOT/tools/tv.py" 'settings put secure voice_interaction_service com.google.android.katniss/.search.serviceapi.KatnissVoiceInteractionService'
"$PY" -I "$ROOT/tools/tv.py" 'settings put secure assistant com.google.android.katniss/.search.serviceapi.KatnissVoiceInteractionService'
"$PY" -I "$ROOT/tools/tv.py" 'settings put secure voice_recognition_service com.google.android.katniss/com.google.android.apps.tvsearch.voice.recognition.KatnissRecognitionService'
"$PY" -I "$ROOT/tools/tv.py" 'pm list packages -d'
