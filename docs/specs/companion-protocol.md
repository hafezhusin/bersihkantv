# Companion protocol v1 — read and discovery

Status: opt-in, read-only half. Write paths (staging, activation, discard) are tracked separately and
are not implemented here. The companion must require capabilities rather than inferring support from
`versionName`.

Companion contract: <https://github.com/gethari/clean-your-tv-companion/blob/main/docs/LAUNCHER_PROTOCOL.md>

## Build opt-in

The provider is compiled and merged into the manifest only when the build property is on:

```bash
cd launcher
./gradlew assembleRelease -PcompanionBridge=true
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
implemented. `theme_schedules_v1` and `private_credentials_v1` are optional and are absent until their
own opt-in issues land and are enabled. A companion that sees no provider, a protocol other than `1`,
malformed limits or a missing mandatory feature must stay read-only; it must not fall back to writing
`config.json`.

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

No secret appears in capabilities, state, errors, logcat or exports.

## Verification

- `launcher/app/src/test/java/com/gathin/tvhome/CompanionAccessTest.java` covers shell UID accepted,
  an ordinary app rejected and the launcher's own UID accepted.
- `CompanionStoreTest` covers safe settings, unknown/credential exclusion, revision changes for
  unknown settings, opaque collection exposure, malformed record skipping and capability limits.
- `CompanionProtocolTest` covers base64url parsing, the 256 KiB cap, version/method rejection, the reply
  shape, canonical revisions and a malformed request touching no file.
- `tests/test_launcher_protocol.py` checks the supported/legacy/newer/incomplete capability fixtures and
  that the opt-in manifest is the only one carrying the provider.

Device verification on a compatible TV with the opt-in build is still required before closing the
issue; it cannot be replaced by JVM tests.
