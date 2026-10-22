# FFalcon 50F295C (`ak30a5`, Android 9, 20250630): an install verifier that opens for one install, and a Home that is not the boot target

Report from [#707](https://github.com/Gathin23/cleanyourtv/issues/707): one unit, one evening, several
full reboots, same-day results only. That is why the two packages the owner disabled are recorded
here and not in `examples/` - CONTRIBUTING asks for a list someone has lived with for a few days.

## Identify the device

| Field | Reported value |
|---|---|
| Retail name | FFalcon (雷鸟) 50F295C, China firmware |
| Manufacturer / product | `TCL Multimedia` / `ak30a5` |
| Android | 9, API 28 |
| Build fingerprint | `TCL/ak30a5/ak30a5:9/PPR1.180610.011/20250630:user/dev-keys` |
| ABI | `armeabi-v7a` |
| Tested at | commit `3e99673`, 2026-10-21 |

## This is not the `ak30a5` in `examples/tcl-ak30a5/`

Same product name, different firmware, and the difference matters. That list describes the
**20230208** build, where `pm install` is patched to refuse shell installs and an APK declaring
`android.intent.category.HOME` is rejected at parse time with
`INSTALL_PARSE_FAILED_INCONSISTENT_CERTIFICATES: ... signature by tcl`.

On this **20250630** unit the reference launcher installed, became the preferred Home and ran. That
does not establish that the older parse-time restriction can be bypassed the same way, and this note
does not claim it: treat the two firmwares as separate cases and match the fingerprint before
applying anything from either.

## Installing: the verifier refuses first, and it can be opened for one install

`adb install -r app-release.apk` failed with `INSTALL_FAILED_VERIFICATION_FAILURE`. The install log
named the verifier rather than the signature rule:

```text
com.android.packageinstaller/.verifier.VerifierReciver
LauncherStrategy
fetch signature info from :com.tcl.cyberui
The installing app not support tcl ui tab !
```

On this unit `settings get global verifier_verify_adb_installs` returned `null` - the setting was
absent, not `1`. Writing `0` to that one global let the same APK install through plain `adb install`,
and deleting it again restored the absent value. `package_verifier_enable` stayed `1`, the package
installer stayed enabled, and no root, firmware modification or TCL signature impersonation was
involved. The owner repeated the round trip for the unmodified launcher and for a later local build.

Put it back on both paths, including the one where the install fails. A helper that restores only
after a successful install leaves the TV accepting any APK - including one pushed over plaintext
network ADB - exactly when the install failed and nobody is watching:

```sh
was=$(tools/tv.py 'settings get global verifier_verify_adb_installs')   # null, 0 or 1
restore_verifier() {
  if [ "$was" = "null" ]; then
    tools/tv.py 'settings delete global verifier_verify_adb_installs'
  else
    tools/tv.py "settings put global verifier_verify_adb_installs $was"
  fi
}
trap restore_verifier EXIT
tools/tv.py 'settings put global verifier_verify_adb_installs 0'
tools/tv.py 'pm install -r /sdcard/app-release.apk' || true
```

Read the value back afterwards: `delete` on a default that was never written and `put 1` on a set
that arrived as `null` both look like success from the host side.

## Default Home and the firmware's boot target are separate

```sh
tools/tv.py 'cmd package set-home-activity --user 0 com.gathin.tvhome/.MainActivity'
```

The preferred Home persisted across full reboots and the physical Home key opened the new launcher.
The firmware still started `com.tcl.cyberui/.MainActivity` during boot:

- `pm disable-user com.tcl.cyberui` did **not** stop its boot activity on this unit.
- Writing `persist.sys.bootpackage` did not change where the boot landed either.
- Both experiments were reverted and the stock launcher was left enabled.

`persist.sys.bootpackage` also changes when a Home activity is launched, so reading it back proves
nothing about the boot handover. The stock launcher appearing first for a few seconds is the firmware,
not a broken handover.

## TCL's own autostart AppOp silences a boot receiver

A plain `BOOT_COMPLETED` receiver in the launcher got no callback at all, and the system log said why:

```text
calleePkgName = 'com.gathin.tvhome'
isAllow = 'false'
reason = 'callee_does't_have_OP_AUTO_START_permission.'
```

`cmd appops get com.gathin.tvhome` showed `APP_AUTO_START: ignore`. Allowing that single operation
made the callback arrive, and it survived a reboot:

```sh
tools/tv.py 'cmd appops get com.gathin.tvhome APP_AUTO_START'   # note the mode first: ignore here
tools/tv.py 'cmd appops set com.gathin.tvhome APP_AUTO_START allow'
```

Record the original mode before changing it and restore that mode, not `deny`: a set that shipped with
`ignore` was not opted out by the owner. No other package's autostart policy was touched.

## The ordinary boot broadcast arrives about a minute and a half late

With the receiver in place, the stock launcher appeared first and the new Home only opened about 102
seconds after boot. The delay was mostly before the ordinary callback arrived, not a two-minute timer
in the launcher.

The owner's local prototype listened for `LOCKED_BOOT_COMPLETED` with a direct-boot-aware receiver,
waited for `UserManager.isUserUnlocked()` before opening the existing activity, and recorded
`Settings.Global.BOOT_COUNT` in device-protected preferences so the later broadcast cannot launch a
second time. Measurements from system events, full reboot, no Home key injected:

```text
+0.000 s    stock TCL home activity created
+16.878 s   system starts com.gathin.tvhome for BootHomeReceiver
+17.697 s   reference home activity created
+81.269 s   Home already started in this boot; skipping late broadcast
```

So: 17.697 s from the stock home being created to the replacement being created, not a
power-button-to-Home figure, and still a stock-home interval that this does not remove.

That prototype is not a request to enable the behaviour everywhere. It adds `RECEIVE_BOOT_COMPLETED`,
which the launcher PR checklist rules out ("no new permission"), so it belongs in a discussion about an
opt-in feature - with a real device and a screenshot - before it becomes a launcher PR.

## What the owner actually changed

- Disabled `com.tcl.bootadservice` and `com.tcl.bi` only. No boot ad appeared, normal operation was
  confirmed, and nothing was uninstalled; `com.gathin.tvhome` was the only package added.
- The reference launcher's Inputs button opens TCL's own source panel through
  `com.tcl.settings/com.tcl.insrc.ShowWindowService` with action `com.tcl.insrc.SHOW_WINDOW`, the same
  route the [`examples/tcl-ak30a5/`](../../examples/tcl-ak30a5/debloat.txt) header gives - this
  firmware has no `tv_input` service either.
- Recovery restores the original Home, the stock launcher's enabled state, the autostart mode and the
  disabled-package states.

## Limits

One unit, one evening, several full reboots, no days of use. ADB over TCP closes on every full reboot
on this set, so every readback starts by enabling debugging again. The plain-text logs were evicted
from the TV's ring buffer during the session; the timings above come from the system event buffer plus
one line saved before eviction, matched by PID and process start time. The local prototype's numbers
(134 existing unit tests, lint and release build green, APK 439,943 bytes, same signing certificate as
the previous local build) describe that prototype, not the reference launcher, and the APK is not
published here.
