# China TCL / FFalcon (tcl_mt5879_cn, t982_ar31a8): the install verifier, and a window that lets a launcher through

Two owners, two sets, the same gate:

| | [#376](https://github.com/Gathin23/cleanyourtv/issues/376) | [#683](https://github.com/Gathin23/cleanyourtv/issues/683) |
|---|---|---|
| Retail model | FFalcon (雷鸟) 75R685C | FFalcon / TCL, no retail name reported |
| Android model | `tcl_mt5879_cn` | `t982_ar31a8` |
| SoC | MediaTek MT9952 | Amlogic T982 |
| Android / SDK | 11 / 30 | 11 / 30 |
| Build id | `RQ3A.210705.001` | `RQ3A.210705.001.2024111116` |
| Panel / RAM | 1920×1080 @ 240 dpi | 1080p, 2 GB |
| Stock HOME | `com.tcl.cyberui` | `com.tcl.cyberui` |

Same Android 11 base, same stock launcher, same refusal — and the same way through it. This note exists
because the repository currently says the opposite: `PROMPT.md` and the header of
`examples/tcl-q10g-pro/debloat.txt` tell a reader that no third-party launcher can go on these sets and
to stop trying. That is true for a bare `adb install`. It is not true in general.

## What refuses the APK

Every channel fails, and not only for HOME packages:

```text
adb install / pm install / cmd package install
  -> INSTALL_FAILED_VERIFICATION_FAILURE
```

- `-i com.tcl.guard` (spoofing the manufacturer's own installer) changes nothing.
- `verifier_verify_adb_installs=0` and `package_verifier_enable=0` change nothing: this is not Google's
  Play Protect verifier.
- `com.android.packageinstaller/.PackageInstallerActivity` is not exported on the T982 unit
  (`Permission Denial ... not exported from uid 10009`), so the installer UI cannot be started by hand
  either.
- 电视卫士 (`com.tcl.guard`) only scans USB storage on the T982; the 欢视助手 (`tv.huan.tvhelper`) web
  channel caps uploads at 10 MB and hangs at "安装中" for a HOME-capable APK.

On the T982 unit the reason is in logcat, and it names the comparison the verifier makes:

```text
W verifier: fetch signature info from :com.tcl.cyberui
W System.err: NameNotFoundException: local.quiettv.launcher
    at ...InstallUtil.getPackageInfo(InstallUtil.java:32)
    at ...strategy.LauncherStrategy.isTclLauncher(LauncherStrategy.java:139)
W verifier: The installing app not support tcl ui tab !
```

So the gate is `com.android.packageinstaller` → `InstallStrategyManager` → `LauncherStrategy`: a package
that declares `android.intent.category.HOME` is accepted only if it is signed with `com.tcl.cyberui`'s
key (and, on the MT5879 set, carries `<meta-data com.tcl.app.type="com.tcl.ui">`). A fresh launcher is
neither, and the verifier drops it — silently, with no dialog on the TV.

## Which gate you have

Read logcat while an install fails, because there are two different gates on TCL firmware and only one
of them is in the installer process:

| Logcat | Where the refusal is | Does the window below help? |
|---|---|---|
| `verifier: signature does not match for tcl <pkg>` / `fetch signature info from :com.tcl.cyberui` / `not support tcl ui tab` | `com.android.packageinstaller` | Yes |
| `PackageManager: Install not allowed for file:///data/app/vmdl*.tmp` | `system_server` | No |

The second case is the Android 14 MT9655 set in
[tcl-mt9655-cn-android14.md](tcl-mt9655-cn-android14.md): there the check runs inside the package
manager, so taking the installer out of the loop removes nothing. On those sets the only channel is a
USB stick through 电视卫士, and the launcher half really is closed.

## The window

Three commands, back to back, on Android 11 firmware whose refusal is the installer's verifier:

```sh
tools/tv.py 'pm disable-user --user 0 com.android.packageinstaller'
tools/tv.py 'pm install -r /data/local/tmp/launcher.apk'    # or: adb install -r launcher.apk
tools/tv.py 'pm enable --user 0 com.android.packageinstaller'
```

`pm install` answers `Success`, including for an APK that declares `MAIN` + `HOME`. Both owners ran
this on their own set and both re-enabled the installer in the same script, so the window lasts a
couple of seconds.

**The re-enable is not optional.** While `com.android.packageinstaller` is disabled the TV cannot
install anything at all, and nothing else will turn it back on. If a script dies between the first and
the third command, the recovery is the third command on its own:

```sh
tools/tv.py 'pm enable --user 0 com.android.packageinstaller'
tools/tv.py 'pm list packages -e | grep packageinstaller'
```

Nothing is patched, no property is written and no signature is forged: the verifier process is simply
not running for the length of the install. That also means **the window accepts any APK**, with no
verification of any kind — push the launcher build you compiled, not something from a forum.

## Making Home stick after a reboot

Installing is half of it. On both sets the firmware still boots into `com.tcl.cyberui`:

1. `tools/tv.py 'cmd package set-home-activity --user 0 <pkg>/.MainActivity'` — afterwards
   `resolve-activity` for `MAIN`+`HOME` answers the new package. `cmd role add-role-holder` printed
   nothing and changed nothing on the MT5879 set.
2. The two TCL-only app ops are what change the boot target:
   `appops set <pkg> APP_AUTO_START allow` and `APP_ASSOC_START allow`
   ([#376](https://github.com/Gathin23/cleanyourtv/issues/376); `docs/TROUBLESHOOTING.md` has the
   `appops write-settings` step that keeps the op after a reboot).
3. An accessibility guard plus the notification listener revive the launcher after a force-stop; without
   them `set-home-activity` alone loses Home to the stock launcher again.

Verified on the MT5879 set (`#376`):

| Test | Result |
|---|---|
| Home key from the stock launcher and from another app | new launcher |
| `am force-stop <pkg>`, then Home | back in ~1 s |
| full `adb reboot` | new launcher; logcat `start home ... reason is startDockOrHome`, `am_proc_start ... HomeGuardService` |
| standby → wake | new launcher still resumed |

On the T982 set (`#683`) the same handover survived a full reboot with `set-home-activity`, all 24
disabled packages still disabled and ADB back up; `com.tcl.cyberui` was kept as the restore path.

Expect a brief flash of `com.tcl.cyberui` at boot on the `set-home-activity` path; the accessibility
guard path avoids it.

## When not to do this

- 多屏互动 (`launcher/build-and-install.sh`'s existing TCL path: push the APK, press 同意 on the TV)
  is gentler and stays the default when it is available.
- A USB stick through 电视卫士 installs ordinary APKs and needs no window at all — it is the only route
  for HOME packages on the Android 14 sets, and the fallback if the installer misbehaves.
- If you are not going to keep the TV, or cannot reach it with a cable when a step fails, leave the
  installer alone.

## Unit notes

`t982_ar31a8` ([#683](https://github.com/Gathin23/cleanyourtv/issues/683)): 1080p, 2 GB RAM,
`armeabi-v7a`, `ro.adb.secure=1`. 24 packages disabled (ads, telemetry, promo, voice, account) — the
list and restore steps are in the reporter's
[ffalcon-t982-debloat](https://github.com/bozhang0419/ffalcon-t982-debloat) repository. FLauncher
0.18.0 installs but hangs on its splash screen for over a minute on the 2 GB unit, with nothing in
logcat; the reporter's own launcher runs. `scrcpy` needs
`--video-encoder c2.android.avc.encoder --no-audio` here: the Amlogic hardware encoder emits corrupt
frames (`QP 52 out of range`).
