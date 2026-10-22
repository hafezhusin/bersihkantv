#!/usr/bin/env python3
"""Signed release metadata for the launcher: create `launcher-release.json`, then verify an APK against it.

    release_manifest.py create --apk <apk> --out launcher-release.json [--allow-debug]
    release_manifest.py verify --apk <apk> --manifest launcher-release.json [--installed <json>]
    release_manifest.py show --manifest launcher-release.json

`create` reads the identity from the APK with `aapt2` and the signer with `apksigner`, records the
APK's SHA-256 and size, and refuses a debug-signed APK unless `--allow-debug` is passed (a published
artifact must use the release key). `verify` fails on a changed hash, size, package, version, SDK,
ABI or signer, and reports whether installing would be a no-op, an upgrade or a downgrade. Nothing
here installs an APK.

Both Android tools are found under $ANDROID_HOME/build-tools/<newest> when not passed explicitly.
This script never contains or writes a key or a password; it only records public identity.
"""
import argparse
import datetime
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import zipfile

FORMAT = "cleanyourtv-launcher-release"
VERSION = 1
DEBUG_DN = "CN=ANDROID DEBUG"  # apksigner prints the DN as C=US, O=Android, CN=Android Debug
# Exit codes: 0 verified, 1 refused, 3 verified but a downgrade (explicit warning for the caller).
VERIFIED, REFUSED, DOWNGRADE = 0, 1, 3


class ReleaseError(Exception):
    pass


def run_tool(argv):
    try:
        result = subprocess.run(argv, capture_output=True, text=True, timeout=120)
    except OSError as e:
        raise ReleaseError(f"cannot run {argv[0]}: {e}")
    if result.returncode != 0:
        raise ReleaseError(f"{Path(argv[0]).name} failed: {result.stderr.strip() or result.stdout.strip()}")
    return result.stdout


def find_sdk_tool(name):
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if not sdk:
        raise ReleaseError(f"{name} was not found and ANDROID_HOME is not set")
    builds = sorted((Path(sdk) / "build-tools").glob("*"), reverse=True)
    for build in builds:
        candidate = build / name
        if candidate.is_file() and os.access(candidate, os.X_OK):
            return str(candidate)
    raise ReleaseError(f"{name} was not found under {Path(sdk) / 'build-tools'}")


def tools(args):
    return (args.aapt2 or find_sdk_tool("aapt2"),
            args.apksigner or find_sdk_tool("apksigner"))


def is_debug(dn):
    return DEBUG_DN in dn.upper()


def sha256_file(path):
    digest = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def apk_identity(apk, aapt2):
    """(package, versionCode, versionName, minSdk, targetSdk, abis) from `aapt2 dump badging`."""
    output = run_tool([aapt2, "dump", "badging", str(apk)])
    package = re.search(r"^package: name='([^']+)'", output, re.MULTILINE)
    if not package:
        raise ReleaseError("the APK has no package line; it is truncated or not an APK")
    version_code = re.search(r"versionCode='([0-9]+)'", output)
    version_name = re.search(r"versionName='([^']*)'", output)
    min_sdk = re.search(r"^sdkVersion:'([0-9]+)'", output, re.MULTILINE)
    target_sdk = re.search(r"^targetSdkVersion:'([0-9]+)'", output, re.MULTILINE)
    native = re.search(r"^native-code:(.*)$", output, re.MULTILINE)
    abis = re.findall(r"'([^']+)'", native.group(1)) if native else []
    missing = [name for name, match in (("versionCode", version_code), ("versionName", version_name),
                                        ("sdkVersion", min_sdk), ("targetSdkVersion", target_sdk))
               if not match]
    if missing:
        raise ReleaseError(f"the APK does not declare {', '.join(missing)}")
    return (package.group(1), int(version_code.group(1)), version_name.group(1),
            int(min_sdk.group(1)), int(target_sdk.group(1)), abis)


def apk_signer(apk, apksigner):
    """(certificate SHA-256, DN) of the first signer."""
    output = run_tool([apksigner, "verify", "--print-certs", str(apk)])
    digest = re.search(r"Signer #1 certificate SHA-256 digest: ([0-9a-fA-F:]+)", output)
    dn = re.search(r"Signer #1 certificate DN: (.+)", output)
    if not digest:
        raise ReleaseError("the APK has no signer certificate")
    return re.sub(r"[^0-9a-fA-F]", "", digest.group(1)).lower(), (dn.group(1).strip() if dn else "")


def zip_abis(apk):
    try:
        with zipfile.ZipFile(apk) as archive:
            names = archive.namelist()
    except (OSError, zipfile.BadZipFile) as e:
        raise ReleaseError(f"not a readable APK: {e}")
    return sorted({name.split("/")[1] for name in names
                   if name.startswith("lib/") and name.count("/") >= 2})


def metadata(apk, aapt2, apksigner, allow_debug=False, protocol_version=1,
             mandatory=(), optional=()):
    apk = Path(apk)
    if not apk.is_file():
        raise ReleaseError(f"{apk}: no such file")
    package, version_code, version_name, min_sdk, target_sdk, abis = apk_identity(apk, aapt2)
    certificate, dn = apk_signer(apk, apksigner)
    debug = is_debug(dn)
    if debug and not allow_debug:
        raise ReleaseError("the APK is debug-signed. Publish a release-signed APK, or pass "
                           "--allow-debug for a local build that will not be published.")
    if not abis:
        abis = zip_abis(apk)
    return {
        "format": FORMAT,
        "version": VERSION,
        "created": datetime.date.today().isoformat(),
        "package": package,
        "versionCode": version_code,
        "versionName": version_name,
        "minSdk": min_sdk,
        "targetSdk": target_sdk,
        # An empty list means the APK carries no native libraries, so every ABI runs it.
        "abis": abis,
        "sha256": sha256_file(apk),
        "sizeBytes": apk.stat().st_size,
        "signing": {
            "kind": "debug" if debug else "release",
            "certificateSha256": certificate,
            "distinguishedName": dn,
        },
        "protocol": {
            "version": protocol_version,
            "mandatory": sorted(set(mandatory)),
            "optional": sorted(set(optional)),
        },
    }


REQUIRED = ("format", "version", "package", "versionCode", "versionName", "minSdk",
            "targetSdk", "abis", "sha256", "sizeBytes", "signing", "protocol")


def load_manifest(path):
    try:
        with open(path, encoding="utf-8") as f:
            data = json.load(f)
    except (OSError, ValueError) as e:
        raise ReleaseError(f"{path}: cannot read the manifest ({e})")
    missing = [field for field in REQUIRED if field not in data]
    if missing:
        raise ReleaseError(f"{path}: manifest is missing {', '.join(missing)}")
    if data["format"] != FORMAT or data["version"] != VERSION:
        raise ReleaseError(f"{path}: unsupported manifest format or version")
    signing = data.get("signing")
    if not isinstance(signing, dict) or not re.fullmatch(r"[0-9a-f]{64}",
                                                         str(signing.get("certificateSha256", ""))):
        raise ReleaseError(f"{path}: signing.certificateSha256 is not a SHA-256 digest")
    if signing.get("kind") not in ("release", "debug"):
        raise ReleaseError(f"{path}: signing.kind must be release or debug")
    if not re.fullmatch(r"[0-9a-f]{64}", str(data.get("sha256", ""))):
        raise ReleaseError(f"{path}: sha256 is not a digest")
    return data


def compare(apk, manifest, aapt2, apksigner, installed=None):
    """Problems (a list) and the install action. An empty list means the APK is the pinned artifact."""
    apk = Path(apk)
    problems = []
    if not apk.is_file():
        return [f"{apk}: no such file"], None
    size = apk.stat().st_size
    if size != manifest["sizeBytes"]:
        problems.append(f"size: manifest {manifest['sizeBytes']} bytes, APK {size} bytes "
                        "(truncated or replaced)")
    try:
        digest = sha256_file(apk)
    except OSError as e:
        return [f"{apk}: cannot read the APK ({e})"], None
    if digest != manifest["sha256"]:
        problems.append(f"sha256: manifest {manifest['sha256']}, APK {digest}")
    try:
        package, version_code, version_name, min_sdk, target_sdk, abis = apk_identity(apk, aapt2)
        certificate, dn = apk_signer(apk, apksigner)
    except ReleaseError as e:
        return problems + [str(e)], None
    for field, actual in (("package", package), ("versionCode", version_code),
                          ("versionName", version_name), ("minSdk", min_sdk),
                          ("targetSdk", target_sdk)):
        if manifest[field] != actual:
            problems.append(f"{field}: manifest {manifest[field]!r}, APK {actual!r}")
    if not abis:
        abis = zip_abis(apk)
    if sorted(manifest["abis"]) != sorted(abis):
        problems.append(f"abis: manifest {manifest['abis']}, APK {abis}")
    expected_signing = manifest["signing"]
    if certificate != expected_signing["certificateSha256"]:
        problems.append("signer: manifest "
                        f"{expected_signing['certificateSha256']}, APK {certificate}")
    if expected_signing["kind"] == "release" and is_debug(dn):
        problems.append("signer: the manifest says release, but the APK is debug-signed")
    if expected_signing.get("kind") == "debug":
        print("warning: this manifest describes a debug-signed local build, not a published "
              "artifact", file=sys.stderr)

    action = None
    if installed:
        if installed.get("package") == manifest["package"]:
            if installed.get("signerSha256") not in (None, certificate):
                problems.append("installed signer differs: Android will refuse this install")
            elif int(installed.get("versionCode", -1)) == version_code:
                action = "no-op"
            elif int(installed.get("versionCode", -1)) > version_code:
                action = "downgrade"
            else:
                action = "upgrade"
        else:
            action = "fresh"
    return problems, action


def load_installed(path):
    try:
        with open(path, encoding="utf-8") as f:
            data = json.load(f)
    except (OSError, ValueError) as e:
        raise ReleaseError(f"{path}: cannot read the installed state ({e})")
    if not isinstance(data, dict) or not isinstance(data.get("package"), str):
        raise ReleaseError(f"{path}: installed state needs at least a package name")
    return data


def create(args):
    aapt2, apksigner = tools(args)
    data = metadata(args.apk, aapt2, apksigner, args.allow_debug, args.protocol_version,
                    args.mandatory or (), args.optional or ())
    out = Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    tmp = out.with_name(out.name + ".tmp")
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(data, f, indent=2, sort_keys=True)
        f.write("\n")
    os.replace(tmp, out)
    print(f"{out}: {data['package']} {data['versionName']} ({data['versionCode']}), "
          f"{data['signing']['kind']}-signed, {data['sizeBytes']} bytes")
    return VERIFIED


def verify(args):
    manifest = load_manifest(args.manifest)
    installed = load_installed(args.installed) if args.installed else None
    aapt2, apksigner = tools(args)
    problems, action = compare(args.apk, manifest, aapt2, apksigner, installed)
    if problems:
        for problem in problems:
            print(f"refused: {problem}", file=sys.stderr)
        print("nothing was installed and no package was changed", file=sys.stderr)
        return REFUSED
    print(f"verified: {manifest['package']} {manifest['versionName']} "
          f"({manifest['versionCode']}), sha256 {manifest['sha256']}")
    if action == "no-op":
        print("install: no-op (this exact version and signer are already installed)")
    elif action == "downgrade":
        print("install: downgrade; Android will refuse it unless the owner explicitly allows a "
              "downgrade, and app data may not be compatible", file=sys.stderr)
        return DOWNGRADE
    elif action:
        print(f"install: {action}")
    return VERIFIED


def show(args):
    manifest = load_manifest(args.manifest)
    print(json.dumps(manifest, indent=2, sort_keys=True))
    return VERIFIED


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    sub = parser.add_subparsers(dest="command", required=True)
    tool_args = argparse.ArgumentParser(add_help=False)
    tool_args.add_argument("--aapt2", help="path to aapt2 (default: under $ANDROID_HOME)")
    tool_args.add_argument("--apksigner", help="path to apksigner (default: under $ANDROID_HOME)")

    make = sub.add_parser("create", parents=[tool_args])
    make.add_argument("--apk", required=True)
    make.add_argument("--out", required=True)
    make.add_argument("--allow-debug", action="store_true",
                      help="record a debug-signed local build instead of refusing it")
    make.add_argument("--protocol-version", type=int, default=1)
    make.add_argument("--mandatory", action="append", help="mandatory protocol feature (repeatable)")
    make.add_argument("--optional", action="append", help="optional protocol feature (repeatable)")

    check = sub.add_parser("verify", parents=[tool_args])
    check.add_argument("--apk", required=True)
    check.add_argument("--manifest", required=True)
    check.add_argument("--installed", help="JSON: {package, versionCode, signerSha256} already on the TV")

    look = sub.add_parser("show", parents=[tool_args])
    look.add_argument("--manifest", required=True)
    args = parser.parse_args(argv)
    try:
        return {"create": create, "verify": verify, "show": show}[args.command](args)
    except ReleaseError as e:
        print(f"refused: {e}", file=sys.stderr)
        return REFUSED


if __name__ == "__main__":
    sys.exit(main())
