# Cleanup profile, spec 1

A cleanup profile is an evidence-backed package list for **one exact firmware**, with a rationale
and a risk label per package. It is deliberately smaller and stricter than the community lists in
[`examples/`](../examples/): a list is a starting point a person edits, a profile is something a
companion can show, refuse and restore.

Profiles live in `profiles/<id>/profile.json` and are applied with
`tools/profile.py`. Nothing here runs automatically, matches a model by name, or changes Home.

## Opt-in, per package

- `apply` needs `--select <package>` (repeatable) or `--all` for the profile's `default_selected`
  entries. There is no implicit selection.
- The profile lists its `protected` packages (Home, inputs, casting, updates, accessibility, setup,
  remote). They are not candidates; selecting one is refused even with `--all`.
- `dry-run` (see below) prints the exact commands and the current state without changing anything.
- No package is ever uninstalled. Only `pm disable-user` and `pm enable` are used.

## Identity

`identity` records the values the TV reports, and every non-empty field must match exactly before a
single command is sent:

| field | `getprop` |
|---|---|
| `brand`, `manufacturer`, `model` | `ro.product.brand`, `ro.product.manufacturer`, `ro.product.model` |
| `product`, `device` | `ro.product.name`, `ro.product.device` |
| `board`, `hardware` | `ro.board.platform`, `ro.hardware` |
| `android_release`, `sdk` | `ro.build.version.release`, `ro.build.version.sdk` |
| `build_id`, `build_display` | `ro.build.version.incremental`, `ro.build.display.id` |
| `fingerprint` | `ro.build.fingerprint` |
| `region` | `ro.product.locale.region` |
| `abi` | one entry of `ro.product.cpu.abilist` |

`fingerprint` is the strongest identifier and should be filled in whenever it was captured. When it
is empty `validate` warns and the profile matches on `product` + `build_id` + `build_display`
instead — never on the brand or model alone. A missing value on the TV is a mismatch, not a wildcard.

`product`, `device`, `sdk`, `build_id` and `build_display` are mandatory. Brand, model, region and
ABI are recorded and matched when present: `Smart TV Pro` alone is shared by many TCL sets, so it is
not an identifier.

## Evidence

```json
"evidence": {
  "level": "device-verified",
  "date": "2026-10-21",
  "source": "examples/tcl-mt9655-cn-android14/debloat.txt",
  "reboot_exercised": true,
  "restore_exercised": false,
  "post_change_checks": {"remote": "...", "casting": "..."},
  "gaps": ["..."]
}
```

- **`community-reported`** — assembled from owner reports, not exercised on a device by this
  project. Safe to read; `apply` still validates the fingerprint, but the UI should show the level.
- **`device-verified`** — applied to a physical unit whose reported identity matches the profile.
  `date`, `source`, `reboot_exercised` and `restore_exercised` say what was actually exercised.

`restore_exercised: false` is a warning in `validate` and is shown by `show`; it is not hidden. The
profile must never claim a reboot or restore that did not happen.

## Per-package fields

| field | meaning |
|---|---|
| `package` | bare package name; duplicates are rejected |
| `group` | display grouping only |
| `rationale` | why it is a candidate |
| `impact` | what stops working without it, in user-visible terms |
| `risk` | `low`, `medium`, `high` or `critical` |
| `default_selected` | the profile author's recommendation; the person still chooses |

`vendor_critical` is not a package field: a package that is critical belongs in `protected` with a
`category` and a `reason`, and cannot be selected at all.

## Commands

Run them with the venv Python, like the other tools:

```bash
python3 -m venv tools/venv && tools/venv/bin/pip install -r tools/requirements.txt

# No TV needed:
tools/venv/bin/python tools/profile.py validate profiles/tcl-mt9655-cn-android14
tools/venv/bin/python tools/profile.py show profiles/tcl-mt9655-cn-android14

# Reads the TV, changes nothing:
TV_HOST=192.168.1.50 tools/venv/bin/python tools/profile.py dry-run \
  profiles/tcl-mt9655-cn-android14 --select com.tcl.bootadservice

# Writes the restore manifest before the first command:
TV_HOST=192.168.1.50 tools/venv/bin/python tools/profile.py apply \
  profiles/tcl-mt9655-cn-android14 --select com.tcl.bootadservice --select com.tcl.logkit

# Put every touched package back and verify it:
tools/venv/bin/python tools/profile.py restore --manifest ~/.cleanyourtv/profiles/192.168.1.50-tcl-mt9655-cn-android14.json
```

`dry-run` checks the fingerprint first. A mismatch prints the differing fields and exits non-zero
without listing commands, because those commands are for a different firmware.

## Restore manifest

The manifest is written **before** the first `pm disable-user` command, atomically, to
`~/.cleanyourtv/profiles/<host>-<profile id>.json` (override with `--manifest`). For every selected
package it records:

```json
"com.tcl.bootadservice": {"prior": "enabled", "action": "disable", "verified": true}
```

`prior` is `enabled`, `disabled`, `uninstalled` or `unknown`; `action` is `disable`,
`already-disabled` or `uninstalled`. The whole manifest carries `complete: false` until every
command has run and the TV reports the expected state, then `complete: true`.

- `unknown` state stops the plan before anything is written.
- A package that was already disabled is recorded, not enabled on restore.
- A package that was uninstalled is recorded and left alone.
- An interrupted apply leaves `complete: false`. `apply` refuses to start again while that manifest
  exists and points at `restore`. Restoring re-enables only the packages this manifest disabled and
  verifies every recorded prior state; a package that stays in the wrong state is reported and the
  manifest keeps `restored: false`.
- The manifest contains package names, the TV host and build identity only: no accounts, tokens or
  personal data, so it is safe to attach to a report.

## Adding a profile

1. Apply nothing yet. Read `getprop`, the firmware build display and the package list on the TV.
2. Copy an existing profile and fill `identity` from those values. Capture `ro.build.fingerprint`
   whenever possible; leave it empty only when the device cannot report it, and record that gap.
3. Give every package a rationale, an impact and a risk label. Put Home, inputs, casting, updates,
   accessibility, setup and remote packages in `protected`.
4. Run `validate` and `show`. Fix every error; keep the warnings visible.
5. Run `dry-run` on the TV, then apply a small selection, reboot, and exercise the post-change
   checks.
6. Run `restore` and confirm every package returns to its prior state. Set
   `restore_exercised: true` only after that succeeded.
7. Record the evidence source and date, and link the profile from `examples/<folder>/` or
   `docs/devices/` so the two stay together.

A profile that cannot name an exact build, or that has no per-package impact, is a community list
and stays in `examples/`.

## Non-goals

- No automatic model match, no profile applied by default, no "recommended" selection.
- No uninstall, no `pm clear`, no data wipe, no Home takeover.
- No claim that a `community-reported` list is firmware evidence.
