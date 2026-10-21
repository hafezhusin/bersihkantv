# TCL 55Q750G (G05, Google TV 11), US

TCL 55Q750G, device `G05`, model "Smart TV Pro", product `G05_4K_US`, board `C05`, MediaTek MT5889, firmware
`AR2101` (software AR71), Google TV on Android 11 (SDK 30), 2.7 GB RAM, on Wi-Fi. This is the same firmware build as
the `tcl-g05-google-tv` list. That list's launcher half was unverified: setupwraith came back there after a reboot. On
this set both halves work. Home stayed with the Clean Your TV launcher after a reboot, and setupwraith stayed
disabled.

| File | What it is |
|---|---|
| `debloat.txt` | The 21 packages that were disabled, grouped and commented |
| `config.json` | Dock, Live TV app, hidden TCL tiles; push it with `tools/theme.sh` |

**How much testing is behind it:** one set, from 21 October 2026, with one day of use. The packages were disabled
in four batches with a screenshot after each, and reboot-checked. Checked on the TV:

- Home after a reboot (`mFocusedApp` is `com.gathin.tvhome`).
- D-pad navigation.
- The Inputs menu, plus switching to Live TV and HDMI 1 from it.
- The Wi-Fi name in the top bar.
- YouTube playback with AdGuard Private DNS on.
- The owner's remote: Input, Settings, Netflix, YouTube, Prime Video and the mic button.

## Install

USB debugging alone opens port 5555 on this set. The order matters:

1. Setupwraith goes while Google TV's home still owns Home.
2. `build-and-install.sh` starts the new launcher.
3. Only after that does it disable launcherx.

```bash
TV_HOST=<tv-ip> tools/tv.py "dumpsys wifi | grep -o 'cname=[^ ]*'"
TV_HOST=<tv-ip> tools/disable.sh examples/tcl-55q750g-google-tv/debloat.txt
TV_HOST=<tv-ip> launcher/build-and-install.sh
TV_HOST=<tv-ip> tools/theme.sh examples/tcl-55q750g-google-tv
```

If the first command prints `cname=com.google.android.tungsten.setupwraith`, the setup wizard owns your Wi-Fi.
Disabling it would delete the network and take the TV off Wi-Fi, so `disable.sh` skips it. On the TV, forget the
network and join it again in Settings, typing the password. It then belongs to `com.android.tv.settings`, and a
second `disable.sh` run takes setupwraith. Check that the forget worked: the network ID in
`cmd wifi list-networks` changes. On the first try here it hadn't, and the old entry was still the wizard's.

`config.json` puts Netflix, Max, Prime Video and Hulu in the dock. Change `dock_apps` to your own.

## Undo everything

```bash
TV_HOST=<tv-ip> tools/restore.sh examples/tcl-55q750g-google-tv/debloat.txt com.google.android.apps.tv.launcherx
```

## Remote buttons

Nothing needed mapping. TCL's own key services (`com.tcl.keyhelp`, `com.tcl.suspension`) handle these buttons with
Google TV's home disabled, so `INPUT_KEY_SERVICE` and `HOME_KEY_SERVICE` were not used:

- Input: TCL's source panel.
- Settings (gear).
- The Netflix, YouTube and Prime Video buttons.
- The mic (Google Assistant).

Keep `com.tcl.suspension`. It is the Input key's panel, and the G05 list's note says disabling it loses the key.
