# Put the Motorola MT4310 back: re-enable everything debloat.txt disabled (including the stock launcher),
# give Home back to it, switch Private DNS back to automatic and let voice search and
# tvrecommendations bind again.
# Usage (from the repo root):  examples\motorola-mt4310\restore.ps1 -TvHost <tv ip>
param([string]$TvHost = $env:TV_HOST)
$ErrorActionPreference = 'Stop'
if (-not $TvHost) { throw 'pass -TvHost <tv ip> or set $env:TV_HOST' }
$env:TV_HOST = $TvHost
$root = Resolve-Path (Join-Path $PSScriptRoot '..\..')
$py = Join-Path $root 'tools\venv\Scripts\python.exe'
$tv = Join-Path $root 'tools\tv.py'
# katniss and tvrecommendations were removed for user 0 (debloat.txt header); bring them back first
& $py -I $tv 'cmd package install-existing com.google.android.katniss'
& $py -I $tv 'cmd package install-existing com.google.android.tvrecommendations'
& (Join-Path $root 'tools\restore.ps1') -TvHost $TvHost (Join-Path $PSScriptRoot 'debloat.txt')
& $py -I $tv 'pm enable --user 0 com.google.android.tvlauncher'
& $py -I $tv 'cmd package set-home-activity --user 0 com.google.android.tvlauncher/.MainActivity'
& $py -I $tv 'settings put global private_dns_mode opportunistic'
# Undo the earlier unbinding attempts, so voice search and the listener work as before
& $py -I $tv 'cmd appops set com.google.android.katniss RUN_ANY_IN_BACKGROUND allow'
& $py -I $tv 'cmd appops set com.google.android.tvrecommendations RUN_ANY_IN_BACKGROUND allow'
& $py -I $tv 'am set-standby-bucket com.google.android.katniss active'
& $py -I $tv 'am set-standby-bucket com.google.android.tvrecommendations active'
& $py -I $tv 'cmd notification allow_listener com.google.android.tvrecommendations/com.google.android.tvrecommendations.NotificationsService'
& $py -I $tv 'settings put secure voice_interaction_service com.google.android.katniss/.search.serviceapi.KatnissVoiceInteractionService'
& $py -I $tv 'settings put secure assistant com.google.android.katniss/.search.serviceapi.KatnissVoiceInteractionService'
& $py -I $tv 'settings put secure voice_recognition_service com.google.android.katniss/com.google.android.apps.tvsearch.voice.recognition.KatnissRecognitionService'
& $py -I $tv 'pm list packages -d'
