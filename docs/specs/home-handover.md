# Home handover, spec 1

`launcher/build-and-install.sh` switches Android's Home from the stock launcher to this one. This
document is the contract the companion can rely on: what the script reads without changing anything,
the order in which it changes Home, the two first-class outcomes other than a takeover, and how the
exact prior launcher is restored.

Handover is **opt-in, per device**. Nothing in this contract is a background watchdog: the script
runs when a person runs it, on a TV they have named with `TV_HOST`, and only after they confirm that
this TV should use the new Home. A TV that keeps its stock Home keeps every launcher feature that
does not require Home ownership.

## Reading the current owner without changing anything

```bash
TV_HOST=192.168.1.50 launcher/build-and-install.sh --status
```

`--status` prints one JSON object and exits `0`. It installs nothing, disables nothing, enables
nothing and never sets a Home activity.

```json
{
  "new_package": "com.gathin.tvhome",
  "new_component": "com.gathin.tvhome/.MainActivity",
  "home": {
    "component": "com.google.android.tvlauncher/.MainActivity",
    "package": "com.google.android.tvlauncher",
    "owner": "stock"
  },
  "stock": {
    "launcher": "com.google.android.tvlauncher",
    "prior_component": "com.google.android.tvlauncher/.MainActivity"
  },
  "modes": ["takeover", "leave-stock-alone", "restore-prior-owner"],
  "recovery": {
    "file": "/home/you/cleanyourtv-handover-192.168.1.50.txt",
    "exists": false
  }
}
```

`home.owner` is one of:

| owner | meaning | takeover |
|---|---|---|
| `stock` | a launcher this script recognises owns Home | yes, with confirmation |
| `new` | this launcher already owns Home | re-run is a no-op for Home |
| `chooser` | Android's chooser (`android`); two or more candidates tie | only when exactly one known stock launcher claims Home |
| `other` | another launcher, or the setup wizard, owns Home | no; the script explains and exits |
| `unknown` | the TV did not answer | no; the TV must answer before anything is changed |

`stock.prior_component` is non-empty only when `home.owner` is `stock`, and it is the component to
restore — not merely the package. `stock.launcher` is the package the script would disable, or an
empty string when there is nothing it is willing to disable.

The companion should read `--status`, show `home.owner` and `stock.launcher`, and let the person
decline. Declining is a no-op: the companion simply does not run the handover. The companion must
not pick a chooser candidate itself; when `owner` is `chooser` it asks the person to pick a default
on the TV first, or to confirm the single candidate the script reports.

## The two first-class outcomes besides takeover

- **Leave stock alone.** `STOCK_LAUNCHER=none` (or the TV already running this launcher) installs,
  starts and sets this launcher as Home but disables and enables nothing. No `pm disable-user` and no
  `pm enable` is sent. This is the outcome for a TV whose owner wants the new Home without giving up
  the stock one.
- **Restore prior owner.** `tools/restore.sh` (or `build-and-install.sh`'s own rollback) puts the
  prior launcher back. Rollback enables the prior package, sets the exact prior component and checks
  what Home resolves to.

`modes` in the status output is the list of outcomes this build supports. It is stable per script
version, so a companion can decide whether it understands the build before offering a takeover.

## Order of operations, and why

The script only ever moves forward; a failed step stops before the next mutation.

1. Build the release APK.
2. Read the current Home owner. If no owner can be read, exit with an error; nothing has changed.
3. Resolve the **exact** prior component: reuse the component that owned Home at the start, and only
   if that was not the stock package, resolve the package's HOME activity once, before anything is
   disabled. Fail if no component resolves.
4. Upload and `pm install -r` the APK on its own. A failed install leaves the stock launcher
   installed and enabled.
5. `am start -W` the new launcher on its own, and require `Status: ok` with no `Error` line. A
   launcher that installs but does not start is not made Home.
6. In one batch: grant the optional location permission, compile the package, disable the stock
   launcher (unless "leave stock alone"), and `cmd package set-home-activity` to the new component.
7. Read Home again. If it does not resolve to the new launcher, roll back.

**The new launcher is started before the stock Home is disabled** because disabling the current Home
first would leave a TV with nothing to start if the new activity cannot come up (#239). A start
failure is only an `Error:` line inside a batch, so the start is a separate command whose output is
checked. The Home preference is set after the disable because changing the set of HOME packages makes
Android drop the existing preference.

## Restore semantics

Rollback, and re-running against a TV already on the wrong Home, restore the **exact prior
component**:

1. `pm enable --user 0 <prior package>`
2. `cmd package set-home-activity --user 0 <prior package>/<prior activity>`
3. Read Home and compare the whole `package/activity` string.

A package that owns Home again but through a different activity is reported as **not** restored,
because the owner's default could differ and a guessed activity is not a restore. The script says
which component it expected.

Re-running the handover is idempotent: when Home already resolves to this launcher the script
reports "nothing will be disabled", sends no `pm disable-user`, and does not disable the previous
owner a second time.

## Chooser, multiple claimants, partial installs, interruptions

- **Chooser declined.** If more than one known stock launcher claims Home, the script refuses with
  an actionable message. It does not guess, and it changes nothing: no install, no disable, no Home
  change. Exactly one known claimant is accepted and named in the output.
- **Other owner.** Another launcher or the setup wizard owning Home is refused the same way, with
  the three common answers (`android`, the setup wizard, "another launcher") spelled out.
- **Partial install.** `pm install` is its own command; a failure exits before the start, the
  disable and the Home change. The TV keeps its current Home.
- **Interrupted handover.** If the TV stops answering while Home is being switched, the outcome is
  unknown. The script says so, attempts rollback, and writes a recovery file.
- **Failed rollback.** Recovery is only reported as complete when Home resolves to the exact prior
  component. Otherwise the script says the recovery is incomplete and points at the recovery file.

## Recovery file

`build-and-install.sh` writes `<HOME>/cleanyourtv-handover-<sanitised TV_HOST>.txt` only when
rollback could not be confirmed. It contains the two commands that finish the restore, the exact
component, the TV host and the check to run afterwards:

```text
# cleanyourtv: the launcher handover on 192.168.1.50 did not finish. From the repo root:
TV_HOST=192.168.1.50 tools/tv.py 'pm enable --user 0 com.google.android.tvlauncher'
TV_HOST=192.168.1.50 tools/tv.py 'cmd package set-home-activity --user 0 com.google.android.tvlauncher/.MainActivity'
# then check: TV_HOST=192.168.1.50 tools/tv.py 'cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME'
```

It carries no passwords, tokens, accounts, serial numbers or other device data beyond the LAN
address the person passed in, so it is safe to attach to a bug report. The `recovery.file` and
`recovery.exists` fields in `--status` let a companion point at a previous incomplete run before
offering another handover.

## Non-goals

- No forced takeover, no silent disabling, and no automatic retry after a failed handover.
- No background service that re-asserts Home after the person changes it.
- No APK or data downgrade, and no debloat changes.
- No automatic chooser selection when more than one known launcher claims Home.
