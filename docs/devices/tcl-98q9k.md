# TCL 98Q9K (China): debloat, third-party launcher install, and the ConfigProvider debug gate

Tested 2026-10-22 on a TCL 98Q9K (98-inch QD-Mini LED 2024), device `tcl_mt5879_cn`
(MT9952 family, same platform as `examples/tcl-q10g-pro`), Android 11 (SDK 30),
build RQ3A.210705.001 release-keys, 3.8 GB RAM, **32-bit only**
(`ro.product.cpu.abilist = armeabi-v7a,armeabi`), `ro.adb.secure=1`.

Outcome: the debloat list in `examples/tcl-98q9k/debloat.txt` (19 packages) applied and
survived reboots, **and** the repo launcher `com.gathin.tvhome` was installed over Wi-Fi,
set as the default Home and verified over a full reboot. The Q10G Pro note "a third-party
launcher cannot be installed here" holds for `adb install` as shipped, but the gate can be
turned off on this firmware — see below. `com.tcl.cyberui` was left enabled as the fallback
Home the whole time (`build-and-install.ps1 -StockLauncher none`).

## ADB

Same as the Q10G Pro: Settings (设置) → 关于本机 → 本机信息, press UP DOWN LEFT RIGHT on
the remote (repeat 2-3x), the ADB toggle appears at the bottom. Persists across reboots,
port 5555.

## The install gate, and how it was opened

`pm install` of anything (even a same-package reinstall of an already-installed
third-party app) fails with `INSTALL_FAILED_VERIFICATION_FAILURE`. The framework's
`TclPackageManagerService.verifyOverseasApp` → `OverseasAppConfig.verify` (in
system_server) consults two rows of the `com.tcl.providers.config` provider, then
`com.android.packageinstaller/.verifier.StrategyService` applies the InstallConfig
strategy chain. Both live in the provider's SQLite database (`features_table`,
`project_id` TEXT PRIMARY KEY, `config_content` JSON):

- `OverseasAppConfig`: `{"enable":"true","packages":[...]}` — allowlist of
  non-Chinese-signature packages; as shipped only `com.netflix.ninja`.
- `InstallConfig`: a strategy chain. On this unit the decisive one was
  `pmStrategy` (priority 5, enabled, **no packages field**): it rejects every
  install initiated from `pm`/shell, TCL signatures included — which is why the
  Q10G Pro record saw even a TCL-signed APK fail.

The provider's own `update()`/`insert()`/`delete()` carry no permission check, but the
`content` CLI on this firmware splits `--bind` on every `:`, so JSON values with colons
cannot be written that way. The writable path is the provider's debug shell command:

1. `setprop persist.odm.tcl.autotest.mode true` — persistent; the provider's
   `onCreate` (directBootAware, initOrder 100, process `system`) then sets
   `debug.configprovider.enable=1` early at every boot, before `FeatureConfigService`
   loads and caches its `mDebug` static from `"1".equals(...)`.
2. Reboot once. Then `cmd feature <verb> ...` works (verbs: query / insert / update /
   delete, content passed as a raw argument, colons fine; `dumpsys feature` only dumps).
3. Writes that opened installs on this unit:
   - `OverseasAppConfig` packages += `com.gathin.tvhome` (plus the package
     names of any other apps you sideload and want to keep installing and
     updating over ADB),
   - `InstallConfig`: `launcherStrategy.packages` += `com.gathin.tvhome`, and
     `pmStrategy.enable` → `false`.
   Both persist in the database; after that, `pm install` works without the debug prop
   (step 1 can be reverted with `setprop persist.odm.tcl.autotest.mode false` and a
   reboot; re-enable it the same way if the configs ever need changing again).
4. `launcher\build-and-install.ps1 -TvHost <ip> -StockLauncher none` then builds,
   pushes, installs (`b64push.py` + `pm install`), starts the launcher, sets
   `cmd package set-home-activity` and verifies Home resolves to `com.gathin.tvhome`.
   A full reboot kept Home on the new launcher with no crashes.

## Notes

- The launcher is pure Java, so the 32-bit-only CPU is no obstacle.
- `dumpsys feature` / `cmd feature` also accept `track <name>` to address
  `trackings_table` instead of `features_table`.
- The provider runs in `system_server` (`android:process="system"`,
  `coreApp="true"`), so no app process to restart; only a reboot re-reads
  `mDebug`. The database is named `config_test.db` in production.
- Kept apps worth knowing about: the content preloads on this unit (CIBN, Migu Video,
  Mango TV, Bilibili TV 云视听小电视, KTCP, blbl, Migu game hall, TCL browser, weather,
  album, video call, WeChat TV) are ordinary `/data/app` applications — disable any of
  them the same way if unused; `live_tv_package` for a launcher build is `com.tcl.tv`
  (hidden from the grid, offered in the Inputs menu).
