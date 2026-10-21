# Companion protocol v1 — read and discovery

Status: opt-in protocol v1, including transactional staging and activation. Both the provider and
every write path are compiled only with `-PcompanionBridge=true`. The companion must require
capabilities rather than inferring support from `versionName`.

Companion contract: <https://github.com/gethari/clean-your-tv-companion/blob/main/docs/LAUNCHER_PROTOCOL.md>

## Build opt-in

The provider is compiled and merged into the manifest only when the build property is on:

```bash
cd launcher
./gradlew assembleRelease -PcompanionBridge=true
# optional: exclude theme schedules from an otherwise writable bridge build
./gradlew assembleRelease -PcompanionBridge=true -PcompanionSchedules=false
```

The default build (`./gradlew assembleRelease`, `build-and-install.sh`, and every release artifact built
without the flag) contains neither `CompanionProvider` nor the `com.gathin.tvhome.companion`
authority. Enabling the bridge does not enable personalization: the companion still has to discover the
capability and the TV owner still has to approve a later apply. No existing theme file, setting, UI or
runtime permission changes when the flag is off. Disabling the flag and reinstalling leaves
`ThemeProvider` and every theme untouched.

## Transport and caller boundary

Authority `com.gathin.tvhome.companion`; base URI `content://com.gathin.tvhome.companion/v1`.

`call(method, arg, extras)` takes one base64url UTF-8 JSON request in `arg` (no padding, ASCII letters,
digits, `-` and `_` only) and returns a `Bundle` with one base64url JSON `reply`. The decoded envelope
is capped at 256 KiB. Unknown methods, a protocol version other than `1`, malformed JSON, a missing
`request_id`, or unsafe identifiers fail before any state is read or written.

Every reply has `protocol_version: 1`, a 32-lowercase-hex `request_id` when one could be parsed, `ok`,
and exactly one of `data` or `error: {code, message}`. Error messages are fixed text and never echo
credentials, secret values or absolute source paths.

Authorization uses `Binder.getCallingUid()` at every entry point and accepts only the Android shell UID
(2000) or the launcher's own UID. An ordinary app receives `access_denied` and no state. There is no
network listener, no cloud service, no exported file provider and no generic path access.

## `capabilities`

```json
{
  "protocol_version": 1,
  "launcher_version": "2.5",
  "features": ["collections_v1", "atomic_activation_v1", "revision_checks_v1", "readback_v1"],
  "limits": {"asset_bytes": 20971520, "collection_bytes": 268435456, "asset_count": 200}
}
```

The four listed features are mandatory for any personalization. They are advertised only when actually
implemented. `theme_schedules_v1` and `private_credentials_v1` are optional. A bridge build advertises
`theme_schedules_v1` unless it was built with `-PcompanionSchedules=false`; that build also rejects a
`theme_schedule` patch with `unsupported_feature`. A companion that sees no provider, a protocol other
than `1`, malformed limits or a missing mandatory feature must stay read-only; it must not fall back to
writing `config.json`.

## `state`

`state` returns `{revision, settings, overrides, collections, active_collection, operation}`.

- `revision` is a 64-character lowercase SHA-256 digest of canonical JSON covering the companion
  overrides, the collections and the legacy theme/preference source, including unknown settings.
- `settings` is the effective view: the theme's settings with companion overrides applied. Only the
  documented safe owned keys are exposed; credentials and unknown settings are excluded.
- `overrides` contains only values the companion owns. It is a separate record from the theme, so
  removing an override restores the theme value (or its absence), not a guessed default.
- `collections` exposes opaque 32-hex IDs, 64-hex asset hashes, byte sizes, MIME types and playback
  preferences (`order`, `interval_minutes`). It never exposes absolute paths or filenames that reveal
  the desktop folder. A malformed record is skipped rather than echoed.
- `active_collection` is the effective selected ID, or `null` when none is set.
- `operation` carries the last committed operation identity (`request_id`, `session_id`, `revision`)
  so a disconnected client can reconcile readback. It is empty before the first activation.
- `schedule` is the TV-side schedule readback (see below). It is `{"enabled": false}` when no schedule
  is saved, it was turned off on the TV, or it failed validation (`invalid: true`). A schedule the
  companion wrote is not evaluated until the TV owner enables it.

No secret appears in capabilities, state, errors, logcat or exports.

## Staging, asset streaming, activation, result and discard

`stage` takes `{protocol_version, request_id, expected_revision, patch, collections}`. The session ID is
the `request_id`. An identical retry returns the same session; the same ID with a different payload
returns `request_conflict`. Stage checks the revision, patch shape, unique opaque IDs, safe hashes, MIME
types (JPEG/PNG/WebP), individual and aggregate limits, free space and rollback margin **before** it
writes a session. Staging never changes the active collection or the displayed wallpaper.

Assets are written to the fixed path
`content://com.gathin.tvhome.companion/v1/sessions/<session-id>/assets/<sha256>` with
`content write --uri`. The provider returns a reliable pipe, not a private-file descriptor. It rejects
traversal, symlinks, duplicate concurrent writers and unlisted hashes; it validates declared length,
SHA-256, decoded format, dimensions and MIME type, fsyncs the temporary file and only then moves it into
the immutable asset store. Truncated, oversized, wrong-length, wrong-hash and undecodable streams are
discarded; EOF alone is never treated as success. Already verified hashes can be skipped by the
companion after `validate` reports them present.

`validate` returns `{ready, missing_assets, revision}`. A stale revision returns `revision_conflict`.
`activate` rechecks the revision, fsyncs the session and asset metadata, merges the patch and
collections into a copy of the active state, and replaces a single `active.json` under one mutation
lock with an atomic move plus a directory fsync. Only after that replacement does the launcher tell
Home to rebuild from the new state. Repeating activation of the same session returns the original
`{revision, operation_id}`.

Patch semantics are `{set: {...}, remove: [...]}` with no overlapping key. Unknown keys, credentials
and unsupported features are rejected. Set with an allowlisted value; remove restores absence (the
theme value reappears) rather than a guessed default. Rotation is 1–1440 minutes, dimming 0–100, order
`sequential` or `shuffle`, colors `#RRGGBB`, collection IDs 32 lowercase hex. The merge preserves every
unrelated and unknown theme setting.

`result` returns `activated` with the session and revision, or `unknown` when no operation was
committed. It is the recovery path after a dropped connection: query it instead of replaying a
mutation. A crash before the atomic replacement leaves the previous state; a crash after it recovers
the new state and operation ID.

`discard` marks only unactivated staging as discarded and is idempotent. It refuses to touch a session
that was activated. Verified immutable assets are retained because they may be referenced by the
active collection or a companion rollback manifest; automatic expiry and garbage collection are not
implemented, so long-lived use can hit the storage preflight and should stage a smaller collection.
Nothing ever deletes active or rollback media to make room.

Stable error codes: `unsupported_protocol`, `unsupported_feature`, `invalid_request`, `access_denied`,
`revision_conflict`, `request_conflict`, `missing_assets`, `invalid_asset`, `storage_full`, `busy`,
`unknown_session`, `internal_error`.

Restoration is another reviewed stage/activate transaction with a fresh `expected_revision`. It never
resets all settings and never overwrites a later manual change.

## Theme schedules (`theme_schedules_v1`)

A schedule is stored in the companion override record, separate from the theme's `config.json`, and
evaluated on the TV with the TV clock and timezone. It needs no network, companion process or pairing.
Turning the schedule off leaves the saved theme and the wallpaper rotation interval untouched.
The schema is version 1:

```json
{
  "version": 1,
  "timezone": "Asia/Kolkata",
  "location": {"latitude": 12.9, "longitude": 77.6},
  "default_theme": {"background_color": "#000000"},
  "rules": [
    {"id": "evening", "priority": 10,
     "trigger": {"type": "fixed", "time": "18:30"},
     "theme": {"accent_color": "#ffffff"}},
    {"id": "sunset", "priority": 20,
     "trigger": {"type": "solar", "event": "sunset", "fallback_time": "19:00"},
     "theme": {"wallpaper": false}}
  ]
}
```

Rules are applied by priority (higher first) at coincident boundaries, then by rule ID; only the
winning rule's fully merged theme is applied, never a half-merged state. Rule themes may set
`background_color`, `accent_color`, `wallpaper`, `wallpaper_dim` and `active_collection`; rotation
settings are excluded. Unknown keys, duplicate rule IDs, duplicate fixed minutes, duplicate priorities,
an invalid timezone or an out-of-range location reject the whole patch before anything is saved.

Fixed times use the saved timezone. A time in a DST gap moves to the first valid local time; a
repeated time fires once, at the earlier offset. Solar sunrise/sunset use a local NOAA approximation
and a missing polar event uses the rule's required `fallback_time`, marked `fallback: true`. A manual
clock or timezone change reconciles to the theme for *now*; missed transitions are never replayed.
Restarting Home or the TV does the same: it applies at most the current state, not a backlog.

`state.schedule` reports:

```json
{"enabled": true, "timezone": "Asia/Kolkata",
 "last_evaluated": {"rule_id": "evening", "manual": false, "fallback": false,
                    "transition_at": 1790000000000, "theme": {"accent_color": "#ffffff"}},
 "upcoming": [{"at": 1790000000000, "rule_id": "evening", "theme": {...}, "fallback": false}]}
```

The TV settings screen shows whether the schedule is on and the next transition, and offers one
**Turn off** action. Turning it off stops evaluation and restores the previous static theme values;
the saved schedule remains so it can be turned on again. Deleting the `theme_schedule` override is the
reset path and leaves no schedule behind.

A manual override is an internal `theme_manual_override` record (`theme` plus `started_at` epoch
milliseconds) and is never writable through the companion patch. It layers on the current rule and
expires at the first later transition; with no later rule it lasts until cleared. A new manual
override replaces the previous one explicitly; nothing silently overwrites a later manual edit, and a
schedule change alone never creates one.

## Verification

- `launcher/app/src/test/java/com/gathin/tvhome/CompanionAccessTest.java` covers shell UID accepted,
  an ordinary app rejected and the launcher's own UID accepted.
- `CompanionStoreTest` covers safe settings, unknown/credential exclusion, revision changes for
  unknown settings, opaque collection exposure, malformed record skipping, capability limits, schedule
  readback gating and the durable on-TV disable switch.
- `ThemeScheduleTest` covers polar fallback, DST gap/repeat, midnight, coincident rules, explicit
  timezone changes, manual override expiry, schema rejection and restart-safe evaluation.
- `CompanionProtocolTest` covers base64url parsing, the 256 KiB cap, version/method rejection, the reply
  shape, canonical revisions and a malformed request touching no file.
- `tests/test_launcher_protocol.py` checks the supported/legacy/newer/incomplete capability fixtures and
  that the opt-in manifest is the only one carrying the provider.
- `CompanionTransactionTest` covers idempotent/conflicting staging, stale revisions, patch and
  identifier rejection, truncated/wrong-hash/oversized assets, unsolicited hashes, missing-asset
  activation, idempotent activation, result recovery, discard safety, remove-restores-absence and
  secret/path redaction.

Physical apply/restore on a compatible TV with the opt-in build is still required before closing the
issue; it cannot be replaced by JVM tests.
