# Motorola MT4310 (43", Newsan / Hisense hayward, Android TV 11)

What was done on this TV on 2026-10-21, and how to change or undo it. Commands run from the repo root
on Windows; replace `<tv ip>` with your TV's address.

## What changed

- **Disabled, not uninstalled:** every package in [debloat.txt](debloat.txt) (store demo, Hisense silent
  installs, telemetry, partner promo rows, calendar and printing, Play Movies, Play Games, TalkBack/TTS,
  Arkiv) plus the stock launcher `com.google.android.tvlauncher`.
- **Removed for your user only (your choice):** voice search `com.google.android.katniss` and
  `com.google.android.tvrecommendations`. The TV's factory manager re-enabled them at every boot, and
  together they took about 63 MB of RAM. The APKs stay on the system partition, so nothing is lost.
- **New Home:** the launcher in `launcher/` (`com.gathin.tvhome`), with this TV's dock in
  [theme/config.json](theme/config.json): Netflix, YouTube, Disney+, Magis TV.
- **Ad-blocking DNS:** Private DNS set to `dns.adguard-dns.com`. It blocks ad and tracking domains in
  apps, **not YouTube ads**.
- **Left alone:** Netflix, Prime Video, YouTube, YouTube Music, Chromecast built-in, BrowseHere, the
  ambient screensaver, your own apps, and everything the TV needs (Live TV, HDMI, remote keys, Bluetooth,
  keyboard, Play Store, Play services).

A full backup from before any change (package lists, getprop, all settings) is in
`backup/motorola-mt4310/` (kept out of git).

## Connecting again

Turn on USB debugging (Settings > Device Preferences > About > press Build 7 times, then Developer
options). This TV opens network ADB on port 5555 by itself. Then:

```powershell
$env:TV_HOST = '<tv ip>'
tools\venv\Scripts\python.exe -I tools\tv.py --probe
```

## Re-enable one app

```powershell
tools\venv\Scripts\python.exe -I tools\tv.py 'pm enable --user 0 com.google.android.videos'
```

Swap in the package name from `debloat.txt`. To keep an app on for good, put `#` in front of its line
in `debloat.txt`.

The two removed for your user come back with `install-existing` instead:

```powershell
tools\venv\Scripts\python.exe -I tools\tv.py 'cmd package install-existing com.google.android.katniss'
tools\venv\Scripts\python.exe -I tools\tv.py 'cmd package install-existing com.google.android.tvrecommendations'
```

`katniss` is voice search; the mic button also needs the remote paired over Bluetooth (the pairing screen
the mic button opens).

## Undo everything

```powershell
examples\motorola-mt4310\restore.ps1 -TvHost <tv ip>
```

It reinstalls `katniss` and `tvrecommendations` for your user, re-enables everything the list disabled,
re-enables the stock launcher and makes it Home again, and sets Private DNS back to automatic. To undo only the DNS:

```powershell
tools\venv\Scripts\python.exe -I tools\tv.py 'settings put global private_dns_mode opportunistic'
```

`backup/motorola-mt4310/restore-all.ps1` is a blunter safety net: it enables every package that is
disabled now but wasn't in the backup.

## Change the dock order

Edit `dock_apps` in [theme/config.json](theme/config.json) (package names, left to right; four fit),
then push it, no rebuild needed:

```powershell
tools\theme.ps1 -TvHost <tv ip> examples\motorola-mt4310\theme
```

Package names: `tools\venv\Scripts\python.exe -I tools\tv.py 'pm list packages -3'`. Netflix
`com.netflix.ninja`, YouTube `com.google.android.youtube.tv`, Disney+ `com.disney.disneyplus`, Magis
TV `com.android.mgstv`, Prime Video `com.amazon.amazonvideo.livingroom`, Spotify
`com.spotify.tv.android`. Reordering also works on the TV: long-press the Settings pill.

## Change the wallpaper

Put a `wallpaper.jpg` (1280x720 is enough for this TV) next to `theme/config.json`, set
`"wallpaper": true` there, and push the theme with the command above. `"wallpaper_dim": 40` darkens
it so the tiles stay readable. Back to plain black: `"wallpaper": false` and push again. All theme
keys: [themes/README.md](../../themes/README.md).

## Rebuild / reinstall the launcher

```powershell
examples\motorola-mt4310\install-launcher.ps1 -TvHost <tv ip>
```

It builds the APK (JDK 17 and the Android SDK, with `JAVA_HOME` and `ANDROID_HOME` set), installs it
as Home, disables the stock launcher and pushes the dock theme.

## Notes from this TV

- At every boot `com.at.factory.factorymanager` re-enables `katniss` and `tvrecommendations` (and TalkBack
  comes back too, but never runs). Disabling them, clearing the voice and notification-listener settings
  and background restrictions were all undone at boot; removing the two for user 0 is what held. Never
  open or disable the factory manager itself.
- Memory after a reboot: about 315 MB available with those two running, about 349 MB without them
  (of 934 MB). There is no reading from before the debloat started.
- The screen goes to sleep after 5 minutes without a key press (the factory setting) and Wi-Fi drops
  with it. If the TV is unreachable a few minutes after a reboot, it is asleep: press Power.

- The remote's Input, Netflix, YouTube, Prime Video and Disney+ buttons are handled by the TV's own
  MediaTek/CVTE key services, not by the stock launcher, so they keep working with the new Home.
- The Inputs menu lists HDMI 1, HDMI 2 and Live TV. The TV registers no composite (AV) input.
- The mic button opens Bluetooth pairing: the remote talks IR until it is paired, and voice search is off.
- Disabling `partnercustomizer` removes the Inputs and Wi-Fi icons from the *stock* launcher's top bar,
  which only matters if you go back to it.
- `com.google.android.tungsten.setupwraith` is left on: it doesn't claim Home on this firmware, and it
  owned a Wi-Fi network saved during setup, which Android would delete if it were disabled.
