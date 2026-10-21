# Build the launcher, install it as Home on the Motorola MT4310 and push this TV's dock theme.
# Usage (from the repo root):  examples\motorola-mt4310\install-launcher.ps1 -TvHost <tv ip>
# Needs JDK 17 and the Android SDK: set JAVA_HOME and ANDROID_HOME first (see CONTRIBUTING.md).
param([string]$TvHost = $env:TV_HOST)
$ErrorActionPreference = 'Stop'
if (-not $TvHost) { throw 'pass -TvHost <tv ip> or set $env:TV_HOST' }
$root = Resolve-Path (Join-Path $PSScriptRoot '..\..')
& (Join-Path $root 'launcher\build-and-install.ps1') -TvHost $TvHost
& (Join-Path $root 'tools\theme.ps1') -TvHost $TvHost (Join-Path $PSScriptRoot 'theme')
