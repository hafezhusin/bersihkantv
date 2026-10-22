# Launcher release artifact, spec 1

A published launcher release is a GitHub release with three assets: the release-signed APK, the
machine-readable `launcher-release.json`, and a `SHA256SUMS` file. A companion pins the manifest,
verifies a download against it, shows the package/version/signing identity, and only then asks the
owner to install. This document describes that contract; installing from source is unchanged.

## Opt-in

- Nothing here auto-updates, phones home, or forces an existing install onto the release artifact.
- A person who builds locally keeps the debug key and stays supported. A local manifest records
  `"signing": {"kind": "debug"}`; the companion records that identity separately instead of treating
  the build as invalid.
- The companion must show the package, version, signer certificate digest and the install action
  (`fresh`, `upgrade`, `no-op`, `downgrade`) and require owner approval.
- A downgrade is never silent: the manifest check exits `3` and says so.

## Manifest

`launcher-release.json` is a public, versioned JSON document:

```json
{
  "format": "cleanyourtv-launcher-release",
  "version": 1,
  "created": "2026-10-21",
  "package": "com.gathin.tvhome",
  "versionCode": 6,
  "versionName": "2.5",
  "minSdk": 26,
  "targetSdk": 34,
  "abis": [],
  "sha256": "64 lowercase hex characters",
  "sizeBytes": 405000,
  "signing": {
    "kind": "release",
    "certificateSha256": "64 lowercase hex characters",
    "distinguishedName": "CN=..."
  },
  "protocol": {
    "version": 1,
    "mandatory": [],
    "optional": []
  }
}
```

- `abis: []` means the APK has no native libraries, so it runs on every ABI. A non-empty list names
  the APKs's `lib/<abi>/` entries and must match the published metadata.
- `protocol.mandatory` is what the companion must understand before installing; `optional` is what
  it can ignore. This release advertises no companion features, so both lists are empty until the
  companion protocol lands. A companion must refuse a `protocol.version` it does not know.
- `sha256` and `sizeBytes` describe the exact APK bytes. The signer digest is the first signer's
  certificate SHA-256, lowercase hex, no colons.
- The manifest contains no keys, passwords, accounts or device data. The keystore and
  `launcher/keystore.properties` are never committed (see `.gitignore`).

Stable URL, once a release is published:

```text
https://github.com/Gathin23/cleanyourtv/releases/latest/download/launcher-release.json
https://github.com/Gathin23/cleanyourtv/releases/latest/download/cleanyourtv-launcher-<versionName>.apk
```

The `version` field versions the manifest format; the release tag versions the artifact. A companion
pins `versionCode` + `sha256` + `certificateSha256` for the artifact it will install, so a new release
does not silently replace the pinned one.

## Signing and publishing

`launcher/app/build.gradle` uses `launcher/keystore.properties` when it exists and falls back to the
debug key otherwise, so `assembleRelease` for a local build behaves exactly as before. Copy
[`launcher/keystore.properties.example`](../../launcher/keystore.properties.example), point
`storeFile` at a keystore outside the repository, and keep both out of git.

```bash
# 1. Release build with the release key. Without it this fails instead of publishing a debug APK.
cd launcher
./gradlew assembleRelease -PrequireReleaseSigning=true

# 2. Manifest: identity from aapt2, signer from apksigner, hash from the APK bytes.
cd ..
tools/venv/bin/python tools/release_manifest.py create \
  --apk launcher/app/build/outputs/apk/release/app-release.apk \
  --out launcher-release.json

# 3. Checksums and the release.
sha256sum launcher/app/build/outputs/apk/release/app-release.apk > SHA256SUMS
gh release create v2.5 \
  launcher/app/build/outputs/apk/release/app-release.apk \
  launcher-release.json SHA256SUMS \
  --title 'Launcher 2.5' --notes '...'
```

A local build can produce metadata for its own identity (never published):

```bash
tools/venv/bin/python tools/release_manifest.py create \
  --apk app-release.apk --out launcher-release.local.json --allow-debug
```

`create` refuses a debug-signed APK unless `--allow-debug` is passed, and records `signing.kind`
accordingly.

## Verifying a download

```bash
tools/venv/bin/python tools/release_manifest.py verify \
  --apk cleanyourtv-launcher-2.5.apk \
  --manifest launcher-release.json \
  --installed installed.json
```

`installed.json` is optional: `{"package": "com.gathin.tvhome", "versionCode": 5,
"signerSha256": "..."}` read from the TV before installing. The command exits:

| code | meaning |
|---|---|
| `0` | verified; prints `install: fresh`, `upgrade` or `no-op` |
| `1` | refused; nothing is installed and no package or Home change is made |
| `3` | verified, but the installed version is newer: an explicit downgrade warning |

It checks, in this order:

1. the file exists, its size and SHA-256 match the manifest byte for byte (a truncated or corrupted
   download fails here);
2. `aapt2` reports the same package, `versionCode`, `versionName`, `minSdk`, `targetSdk` and ABIs;
3. `apksigner` reports the same signer certificate digest, and a manifest that says `release` is not
   backed by a debug-signed APK;
4. when `--installed` is given, the installed signer is the same and the version decides
   `no-op`/`upgrade`/`downgrade`.

`aapt2` and `apksigner` come from `$ANDROID_HOME/build-tools/<newest>` unless `--aapt2` and
`--apksigner` name them. If they are missing, the check fails closed rather than guessing from the
file name.

### Install behaviour

- A failed verification installs nothing and leaves the current launcher and Home unchanged.
- `no-op` means the driver must not reinstall: the installed version and signer already match.
- After `pm install`, the companion must read the installed package (`dumpsys package
  com.gathin.tvhome` or `pm list packages`) and compare `versionCode` and signer again. An exit code
  of `0` from `pm install` is not proof of success, and an interrupted install can leave the old
  version in place; report that state instead of claiming success.
- If the signer differs from the installed one, Android refuses the install. The check above catches
  it before the attempt, so the user sees "signing identity changed" rather than a raw package-manager
  error.

## Rollback and data compatibility

- Android refuses a lower `versionCode` unless the owner explicitly allows a downgrade. Do not
  assume app data survives a downgrade: the launcher's shared preferences (layout, theme, module-card
  answers) are written by newer code and are not migrated backwards.
- Rolling back to the stock launcher is a Home change (`docs/specs/home-handover.md`), not an APK
  downgrade. An uninstall of `com.gathin.tvhome` removes its data.
- Existing users are never moved between the release and debug signing identities automatically; a
  signer change requires an uninstall, which the companion must state before doing anything.

## Release notes

Release notes state the `versionCode`, `versionName`, the supported protocol version and feature
lists, and the signer certificate SHA-256. If the release adds no companion features, say so
explicitly instead of leaving the protocol fields to be guessed.

## Non-goals

- No auto-update service, no telemetry, no background downloader in the launcher.
- No signing key in the repository or in a release asset.
- No promise that a downgrade is safe, and no silent downgrade.
