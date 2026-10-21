# TCL China: the `InstallConfig` policy that decides what installs, and the one firmware that takes a write

Observed on a TCL set reporting `ro.product.model=tcl_mt5879_cn`, `ro.product.brand=TCL`, Android 11
(API 30), `ro.product.cpu.abilist=armeabi-v7a,armeabi` — 32-bit userspace only, so an APK needs
`armeabi-v7a` libraries or none at all. `tcl_mt5879_cn` is a platform string, not a retail model, and
one unit is not a compatibility claim. Owner report:
[#743](https://github.com/Gathin23/cleanyourtv/issues/743). The same row was already read, read-only,
on the [MT9655 Android 14 sets](tcl-mt9655-cn-android14.md); the two units disagree about whether it
can be written, which is the useful part of this note.

## One more gate than the other notes list

TCL firmware decides installs in more than one place, and they are independent. [#707](https://github.com/Gathin23/cleanyourtv/issues/707)
is the Android installer verifier (`verifier_verify_adb_installs`, `com.android.packageinstaller` →
`com.tcl.cyberui`); [#376](https://github.com/Gathin23/cleanyourtv/issues/376) and
[#683](https://github.com/Gathin23/cleanyourtv/issues/683) are the installer-disable window, where the
installer package is stopped for one install. This policy is a third one: it stays in force with the
installer running and the verifier setting untouched.

## What the row is

```sh
tools/tv.py 'content query --uri content://com.tcl.providers.config/InstallConfig'
# Row: 0 config_content=[{"enable":"true","strategies":[...]}]
```

One row, column `config_content`, a JSON array holding a single object: a global `enable`, then a
`strategies` list of six entries — `safeStrategy`, `blackListStrategy`, `thridStrategy`,
`launcherStrategy`, `overDueStrategy`, `pmStrategy`. `enable` is the string `"true"` or `"false"`, not
a JSON boolean, and the entries carry `packages` and `priority` where they apply. On the sets seen so
far `thridStrategy` ships disabled and the others enabled.

This is maintenance on a television you own, for an APK you chose, with the owner's approval for that
specific package: the policy is a setting TCL exposes over `content`, not a lock to pick on somebody
else's set. Nothing below gets an APK past a check the firmware makes on the APK itself.

Read it before you plan anything: it is what your firmware thinks, and its exact shape is what a write
has to give back byte for byte. The Android 14 unit's JSON is quoted in full in
[`examples/tcl-mt9655-cn-android14/debloat.txt`](../../examples/tcl-mt9655-cn-android14/debloat.txt);
here the reporter had to put the APK's package name into `launcherStrategy.packages` and set
`pmStrategy.enable` to `"false"` before the same APK's `adb install` succeeded. What `pmStrategy` gates
is not documented by TCL: the report only shows that the install failed without that change and
succeeded with it.

Stop if the row does not look like that — zero rows, two rows, a different strategy count, a global
`enable` that is already `"false"`, or `thridStrategy` enabled. A policy you do not recognise is not one
to edit.

## The quoting trap, and why it is not a dead end

The `content` CLI splits `--bind` on `:`, and this value is full of colons (content URIs, priorities,
timestamps inside quoted strings), so the naive write

```sh
content update --uri content://com.tcl.providers.config/InstallConfig \
  --bind config_content:s:<json>
```

comes back mangled or changes nothing. The MT9655 note used to read that as "a JSON value cannot be
passed at all"; on the MT5879 unit it can, once the value is escaped **backslashes first, then
colons**, and the whole argument is quoted as one argv:

```python
value = original.replace("\\", "\\\\").replace(":", "\\:")
# content update --uri <uri> --bind config_content:s:<value>, passed as one quoted argument
```

The order is load-bearing. Escaping the colons first and the backslashes second re-escapes the
backslashes you just added, and the TV reads back a different string. Build the argument list in Python
and quote it (`shlex.join` on the completed list); do not interpolate JSON into a shell string.
`tests/test_installconfig_policy_note.py` runs this recipe against synthetic values with URLs, quotes
and backslashes.

On the Android 14 MT9655 unit the write was refused instead: a `content update` against the `Version`
row returned 0 and read back unchanged, and the policy database is not readable as `shell`. That is a
different firmware generation, and it is not evidence that the write fails everywhere — nor that a
successful write there would lift the signature refusal those sets show, which nobody has tested.

## The transaction, in the order that matters

1. Read the row. Keep the original string, not a re-serialised copy of it: whitespace and key order are
   part of what gets written back.
2. Save it privately — file mode `0600` — with the device serial and model next to it. That file is the
   only way back if the session dies.
3. Re-read the row and compare. If it changed since step 1, someone or something else is editing the
   policy; abort without writing.
4. Apply the two temporary changes, then read the row back and compare it to what you intended to
   write. A silent partial write is worse than a refusal.
5. Run the install.
6. Restore the saved original string, re-read, compare **exactly**. This runs even when step 5 failed,
   and a failed restore is reported as a failure even when the APK did install.

**Scope:** adding the package to `launcherStrategy.packages` is package-specific; turning `pmStrategy`
off covers that strategy for everything during the window. So do not run two installs, or an install
and a policy edit, at the same time, and keep the window as short as an `adb install` allows.

Nothing here changes the Android verifier setting, stops the installer package, needs root, touches the
default HOME or reboots the TV, and every other strategy entry — `safeStrategy`, the blacklists,
`overDueStrategy` — is carried through unchanged.

## When the restore does not happen

Power loss, a killed process or a dropped ADB connection can stop the restore, and there is no durable
lock or crash-recovery daemon to finish it. If that happens: keep the backup, reconnect to the same
device, check the serial and model before writing, put the original string back and read it back. Never
regenerate "an equivalent" JSON by hand and never use a backup from another TV — the value has to be the
string that was there.

A pre-write re-read narrows the race window; it is not compare-and-swap, and the report says so
explicitly. Do not describe it as atomic.

## Limits

One unit, one Android generation. Install, app launch and the foreground activity were checked there;
long-term playback, cold-boot behaviour and any other model string were not. The copy of the helper
published in #743 has not been rerun on hardware since it was sanitised (the default address removed,
`--tv` made mandatory), and it parses no APK manifest — package name, version and minimum API have to be
confirmed with `aapt` or `apkanalyzer` first. On a set whose `InstallConfig` refuses writes, the honest
answer is still "debloat only", as in the [MT9655 note](tcl-mt9655-cn-android14.md).
