# TCL / FFalcon MT9655 China, Android 14

Profile: [`profile.json`](profile.json). Format and commands:
[`docs/specs/tcl-profile.md`](../../docs/specs/tcl-profile.md).

| | |
|---|---|
| Retail name | TCL Smart TV Pro / IFFALCON (雷鸟) |
| Product / device | `MT9655_4K_CN` / `MT9655`, MediaTek MT9655 (`merak`) |
| Android | 14, SDK 34 |
| Build display | `tcl9655_cn_an64-user 14 UTT2.250416.001 AU01 release-keys` |
| Region | CN, no Google services |
| ABI | `arm64-v8a` |
| Evidence | device-verified 2026-10-21, 28 packages, one full reboot |
| Restore | **not exercised on the device yet** |

The observed package list, the ADB access procedure and the post-change checks are in
[`examples/tcl-mt9655-cn-android14/debloat.txt`](../../examples/tcl-mt9655-cn-android14/debloat.txt)
and [`docs/devices/tcl-mt9655-cn-android14.md`](../../docs/devices/tcl-mt9655-cn-android14.md).

## Caveats

- `ro.build.fingerprint` was not captured. The profile matches on `product`, `device`, `build_id`,
  `build_display`, `region` and `abi`; a different build is refused rather than guessed.
- Restore has not been run on this unit. Treat `restore` as unverified until it is.
- The three updater packages are **not** in the default selection. Choosing them means no system,
  app or peripheral updates while they stay disabled.
- `com.tcl.hearaid` is an accessibility app and is not in the default selection.
- The stock launcher `com.tcl.cyberui` is protected. On this firmware a third-party launcher cannot
  be installed at all (`launcherStrategy` with an empty allowlist), so this profile is debloat only.
- HDMI and Cast were checked at the package level, not with an external source or receiver.
