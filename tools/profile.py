#!/usr/bin/env python3
"""Evidence-backed cleanup profiles, with an exact fingerprint gate and a restore manifest.

    profile.py validate <profile>                      check the profile, change nothing
    profile.py show <profile>                          identity, evidence and packages
    profile.py dry-run <profile> [selection]           print the commands and the state, change nothing
    profile.py apply <profile> [selection]             disable the selected packages, recording prior state
    profile.py restore --manifest <file>               return every touched package to its prior state

A profile lives in `profiles/<name>/profile.json`. It names the exact firmware it was written for,
gives every package a rationale and a risk label, and lists the vendor packages that must stay
enabled. `apply` refuses when any identity field differs, when a package state cannot be read, or
when an earlier apply on the same TV was interrupted; `restore` is driven by the manifest that
`apply` wrote before its first command. Nothing is ever uninstalled.

`--select` names packages explicitly; `--all` selects the profile's `default_selected` entries.
`apply` needs one of them: a profile is a suggestion, never an automatic match.
"""
import fnmatch
import hashlib
import json
import os
from pathlib import Path
import re
import sys
import time

VERSION = 1
PACKAGE_RE = re.compile(r"[A-Za-z_][A-Za-z0-9_]*(\.[A-Za-z0-9_]+)+")
ID_RE = re.compile(r"[a-z0-9][a-z0-9-]{1,63}")
RISKS = ("low", "medium", "high", "critical")
LEVELS = ("community-reported", "device-verified")
STATES = ("enabled", "disabled", "uninstalled", "unknown")
# getprop prints one `[key]: [value]` line per property.
PROP_RE = re.compile(r"^\[([^\]]+)\]: \[(.*)\]$")
IDENTITY = {
    "brand": "ro.product.brand",
    "manufacturer": "ro.product.manufacturer",
    "model": "ro.product.model",
    "product": "ro.product.name",
    "device": "ro.product.device",
    "board": "ro.board.platform",
    "hardware": "ro.hardware",
    "android_release": "ro.build.version.release",
    "sdk": "ro.build.version.sdk",
    "build_id": "ro.build.version.incremental",
    "build_display": "ro.build.display.id",
    "fingerprint": "ro.build.fingerprint",
    "region": "ro.product.locale.region",
    "abi": "ro.product.cpu.abilist",
}
CATEGORIES = ("home", "inputs", "casting", "updates", "accessibility", "setup", "remote")


def load_profile(path):
    """A profile directory or its profile.json, with a readable error before anything is touched."""
    path = Path(path)
    if path.is_dir():
        path = path / "profile.json"
    try:
        with open(path, encoding="utf-8") as f:
            profile = json.load(f)
    except (OSError, ValueError) as e:
        raise SystemExit(f"{path}: cannot read the profile ({e})")
    profile["_path"] = str(path)
    return profile


def _entries(profile):
    return profile.get("packages") if isinstance(profile.get("packages"), list) else []


def _protected(profile):
    return profile.get("protected") if isinstance(profile.get("protected"), list) else []


def validate(profile):
    """(errors, warnings). Errors stop every action; warnings are gaps the reviewer should see."""
    errors, warnings = [], []
    if profile.get("format") != "cleanyourtv-profile" or profile.get("version") != VERSION:
        errors.append(f"format must be cleanyourtv-profile version {VERSION}")
    if not isinstance(profile.get("id"), str) or not ID_RE.fullmatch(profile.get("id", "")):
        errors.append("id must be a lowercase profile slug")
    identity = profile.get("identity")
    if not isinstance(identity, dict):
        errors.append("identity is missing")
        identity = {}
    for field in ("brand", "model", "product", "device", "sdk", "build_id", "build_display"):
        if not str(identity.get(field, "")).strip():
            errors.append(f"identity.{field} is required")
    unknown = set(identity) - set(IDENTITY)
    if unknown:
        errors.append(f"identity has unknown fields: {', '.join(sorted(unknown))}")
    if not str(identity.get("fingerprint", "")).strip():
        warnings.append("identity.fingerprint is not recorded: the profile matches on product, "
                        "build_id and build_display instead; capture ro.build.fingerprint on the TV")
    evidence = profile.get("evidence")
    if not isinstance(evidence, dict):
        errors.append("evidence is missing")
        evidence = {}
    if evidence.get("level") not in LEVELS:
        errors.append(f"evidence.level must be one of {', '.join(LEVELS)}")
    for field in ("date", "source"):
        if not str(evidence.get(field, "")).strip():
            errors.append(f"evidence.{field} is required")
    for field in ("reboot_exercised", "restore_exercised"):
        if not isinstance(evidence.get(field), bool):
            errors.append(f"evidence.{field} must be true or false")
    if evidence.get("level") == "device-verified" and evidence.get("restore_exercised") is False:
        warnings.append("this device-verified profile has not exercised restore yet")

    seen = {}
    for index, entry in enumerate(_entries(profile)):
        where = f"packages[{index}]"
        name = entry.get("package")
        if not isinstance(name, str) or not PACKAGE_RE.fullmatch(name):
            errors.append(f"{where}: not a package name: {name!r}")
            continue
        if name in seen:
            errors.append(f"{name}: duplicate entry (already at packages[{seen[name]}])")
        seen[name] = index
        for field in ("rationale", "impact"):
            if not str(entry.get(field, "")).strip():
                errors.append(f"{name}: {field} is required")
        if entry.get("risk") not in RISKS:
            errors.append(f"{name}: risk must be one of {', '.join(RISKS)}")
        if not isinstance(entry.get("default_selected"), bool):
            errors.append(f"{name}: default_selected must be true or false")
        if entry.get("vendor_critical"):
            errors.append(f"{name}: device-critical packages belong in protected, not packages")
    if not seen:
        errors.append("the profile lists no packages")

    protected = {}
    for index, entry in enumerate(_protected(profile)):
        where = f"protected[{index}]"
        name = entry.get("package")
        if not isinstance(name, str) or not name.endswith("*") and not PACKAGE_RE.fullmatch(name) \
                or "*" in name[:-1]:
            errors.append(f"{where}: not a package name or a prefix ending in *: {name!r}")
            continue
        if not str(entry.get("reason", "")).strip():
            errors.append(f"{name}: protected packages need a reason")
        if entry.get("category") not in CATEGORIES:
            errors.append(f"{name}: protected category must be one of {', '.join(CATEGORIES)}")
        if name in seen:
            errors.append(f"{name}: listed as both a candidate and protected")
        protected[name] = entry
    if not protected:
        errors.append("the profile protects no vendor packages")
    return errors, warnings


def protected_entry(profile, package):
    for entry in _protected(profile):
        pattern = entry.get("package", "")
        if pattern.endswith("*") and fnmatch.fnmatchcase(package, pattern):
            return entry
        if package == pattern:
            return entry
    return None


def read_identity(props_text):
    props = {}
    for line in props_text.splitlines():
        match = PROP_RE.match(line.strip())
        if match:
            props[match.group(1)] = match.group(2).strip()
    return {field: props.get(prop, "") for field, prop in IDENTITY.items()}


def match_identity(profile, device):
    """(ok, differences). Every recorded field must match exactly; an unreadable field is a mismatch."""
    identity = profile.get("identity", {})
    differences = []
    for field in IDENTITY:
        expected = str(identity.get(field, "")).strip()
        if not expected:
            continue
        actual = str(device.get(field, "")).strip()
        if field == "abi":
            if expected not in [a.strip() for a in actual.split(",")]:
                differences.append(f"abi: profile {expected}, TV {actual or 'unreadable'}")
        elif actual != expected:
            differences.append(f"{field}: profile {expected}, TV {actual or 'unreadable'}")
    return not differences, differences


def parse_package_list(output):
    names = set()
    for line in output.splitlines():
        line = line.strip()
        if line.startswith("package:"):
            name = line[len("package:"):].strip()
            if PACKAGE_RE.fullmatch(name):
                names.add(name)
    return names


def read_states(shell, packages):
    """Map every package to enabled/disabled/uninstalled/unknown from the TV's package manager."""
    installed = parse_package_list(shell("pm list packages"))
    if not installed:
        raise SystemExit("the TV returned no packages; nothing will be changed")
    disabled = parse_package_list(shell("pm list packages -d"))
    uninstalled = parse_package_list(shell("pm list packages -u")) - installed
    states = {}
    for name in packages:
        if name in installed:
            states[name] = "disabled" if name in disabled else "enabled"
        elif name in uninstalled:
            states[name] = "uninstalled"
        else:
            states[name] = "unknown"
    return states


def selection(profile, select=None, everything=False):
    """The requested package names, checked against the profile. No selection is not a selection."""
    names = {entry["package"] for entry in _entries(profile) if isinstance(entry.get("package"), str)}
    if everything:
        requested = {entry["package"] for entry in _entries(profile) if entry.get("default_selected")}
    elif select:
        requested = set(select)
    else:
        raise SystemExit("name the packages to change with --select <package> (repeatable), or --all "
                         "for the profile's default selection. Nothing was changed.")
    refused = sorted(requested - names)
    for name in sorted(requested):
        entry = protected_entry(profile, name)
        if entry:
            raise SystemExit(f"{name} is protected ({entry.get('category')}: {entry.get('reason')}). "
                             "Nothing was changed.")
    if refused:
        raise SystemExit(f"not in this profile: {', '.join(refused)}. Nothing was changed.")
    return requested


def plan(profile, states, requested):
    """The exact actions per package. Unknown state is an error, not a guess."""
    unknown = sorted(name for name in requested if states.get(name) == "unknown")
    if unknown:
        raise SystemExit(f"cannot read the state of {', '.join(unknown)} on the TV; nothing was changed.")
    actions = {}
    for name in sorted(requested):
        actions[name] = {
            "enabled": "disable",
            "disabled": "already-disabled",
            "uninstalled": "uninstalled",
        }[states[name]]
    return actions


def _commands(actions):
    return [f"pm disable-user --user 0 {name}"
            for name, action in sorted(actions.items()) if action == "disable"]


def manifest_path(profile, host):
    tag = re.sub(r"[^A-Za-z0-9.-]", "_", host or "no-host")
    return Path.home() / ".cleanyourtv" / "profiles" / f"{tag}-{profile['id']}.json"


def _write_json(path, data):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_name(path.name + ".tmp")
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(data, f, indent=2, sort_keys=True)
        f.write("\n")
        f.flush()
        os.fsync(f.fileno())
    os.replace(tmp, path)


def load_manifest(path):
    try:
        with open(path, encoding="utf-8") as f:
            manifest = json.load(f)
    except (OSError, ValueError) as e:
        raise SystemExit(f"{path}: cannot read the restore manifest ({e})")
    if manifest.get("version") != VERSION or not isinstance(manifest.get("packages"), dict):
        raise SystemExit(f"{path}: unknown restore manifest format; it is left untouched")
    for name, entry in manifest["packages"].items():
        if not PACKAGE_RE.fullmatch(name) or entry.get("prior") not in STATES:
            raise SystemExit(f"{path}: not a usable restore manifest ({name!r}); it is left untouched")
    return manifest


def connect():
    """The same TV client the other scripts use, imported late so validate needs no adb_shell."""
    sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
    try:
        import tv
    except ImportError as e:
        raise SystemExit(f"cannot load tools/tv.py ({e}).\nrun: python3 -m venv tools/venv && "
                         "tools/venv/bin/pip install -r tools/requirements.txt")
    return tv.connect()


class Device:
    def __init__(self, device):
        self._device = device

    def shell(self, command):
        return (self._device.shell(command, read_timeout_s=120.0) or "")

    def close(self):
        self._device.close()

    def identity(self):
        return read_identity(self.shell("getprop"))


def apply(profile, device, host, requested, path):
    """Disable the selected packages, writing the prior state before the first command."""
    errors, _ = validate(profile)
    if errors:
        raise SystemExit("the profile is not valid:\n  " + "\n  ".join(errors))
    ok, differences = match_identity(profile, device.identity())
    if not ok:
        raise SystemExit("this TV does not match the profile, so nothing was changed:\n  "
                         + "\n  ".join(differences) +
                         "\nWrite a profile for this firmware instead of guessing.")
    path = Path(path)
    if path.exists():
        previous = load_manifest(path)
        if not previous.get("complete"):
            raise SystemExit(f"an interrupted apply is recorded in {path}; restore it first:\n"
                             f"  tools/venv/bin/python tools/profile.py restore --manifest {path}")
        raise SystemExit(f"{path} already records a completed apply; restore it before applying again")
    states = read_states(device.shell, sorted(requested))
    actions = plan(profile, states, requested)
    manifest = {
        "version": VERSION,
        "profile": {"id": profile["id"], "path": profile["_path"],
                    "identity": profile.get("identity", {})},
        "host": host,
        "created": time.strftime("%Y-%m-%dT%H:%M:%S%z"),
        "identity": device.identity(),
        "complete": False,
        "restored": False,
        "packages": {name: {"prior": states[name], "action": action, "verified": False}
                     for name, action in actions.items()},
    }
    _write_json(path, manifest)
    print(f"restore manifest: {path}")
    failed = None
    for name, action in sorted(actions.items()):
        if action != "disable":
            print(f"{name}: {action}, left alone")
            continue
        output = device.shell(f"pm disable-user --user 0 {name}").strip()
        if "Error" in output or "Exception" in output:
            failed = f"{name}: {output or 'no output'}"
            break
        print(f"{name}: disabled")
    after = read_states(device.shell, sorted(requested))
    for name, entry in manifest["packages"].items():
        entry["verified"] = after[name] == ("disabled" if entry["action"] == "disable"
                                            else entry["prior"])
    manifest["complete"] = failed is None and all(e["verified"] for e in manifest["packages"].values())
    _write_json(path, manifest)
    if failed:
        print(f"stopped at {failed}", file=sys.stderr)
    broken = [name for name, entry in manifest["packages"].items() if not entry["verified"]]
    if broken:
        print(f"not in the expected state afterwards: {', '.join(sorted(broken))}", file=sys.stderr)
    if not manifest["complete"]:
        print(f"the apply is incomplete; put every package back with:\n"
              f"  tools/venv/bin/python tools/profile.py restore --manifest {path}", file=sys.stderr)
        return 1
    print(f"applied {sum(1 for a in actions.values() if a == 'disable')} packages; "
          f"restore with: tools/venv/bin/python tools/profile.py restore --manifest {path}")
    return 0


def restore(manifest_path, device):
    """Re-enable only what this manifest disabled, then verify every package's prior state."""
    manifest = load_manifest(manifest_path)
    names = sorted(manifest["packages"])
    before = read_states(device.shell, names)
    failures = []
    for name, entry in sorted(manifest["packages"].items()):
        prior, action = entry["prior"], entry.get("action")
        if action != "disable" or prior != "enabled":
            # Already disabled before the apply, uninstalled, or never touched: enabling it would
            # change a state this profile did not change.
            continue
        if before[name] != "disabled":
            continue
        output = device.shell(f"pm enable --user 0 {name}").strip()
        if "Error" in output or "Exception" in output:
            failures.append(f"{name}: {output or 'no output'}")
    after = read_states(device.shell, names)
    for name, entry in sorted(manifest["packages"].items()):
        expected = entry["prior"]
        if after[name] != expected:
            failures.append(f"{name}: expected {expected}, TV says {after[name]}")
    manifest["restored"] = not failures
    manifest["restored_at"] = time.strftime("%Y-%m-%dT%H:%M:%S%z")
    _write_json(manifest_path, manifest)
    if failures:
        print("restore is incomplete:\n  " + "\n  ".join(failures), file=sys.stderr)
        return 1
    print(f"restored {len(manifest['packages'])} packages to their recorded state")
    return 0


def describe(profile):
    identity = profile.get("identity", {})
    evidence = profile.get("evidence", {})
    print(f"{profile['id']}: {identity.get('brand', '?')} {identity.get('model', '?')} "
          f"({identity.get('product', '?')}, {identity.get('device', '?')}), "
          f"Android {identity.get('android_release', '?')} / SDK {identity.get('sdk', '?')}")
    print(f"build: {identity.get('build_display', '?')}")
    fingerprint = identity.get("fingerprint") or "not recorded (matches product/build_id/build_display)"
    print(f"fingerprint: {fingerprint}")
    print(f"evidence: {evidence.get('level', '?')} {evidence.get('date', '?')}, "
          f"reboot {evidence.get('reboot_exercised')}, restore {evidence.get('restore_exercised')}")
    print(f"source: {evidence.get('source', '?')}")
    for gap in evidence.get("gaps", []):
        print(f"gap: {gap}")
    print("protected:")
    for entry in _protected(profile):
        print(f"  {entry.get('package')}  [{entry.get('category')}] {entry.get('reason')}")
    print("packages:")
    for entry in _entries(profile):
        mark = "default" if entry.get("default_selected") else "opt-in"
        print(f"  {entry['package']:<45} {entry.get('risk', '?'):<7} {mark:<8} {entry.get('rationale')}")


def main(argv):
    if len(argv) < 2:
        raise SystemExit(__doc__.strip())
    command, rest = argv[1], argv[2:]
    if command == "validate":
        profile = load_profile(rest[0])
        errors, warnings = validate(profile)
        for warning in warnings:
            print(f"warning: {warning}")
        for error in errors:
            print(f"error: {error}", file=sys.stderr)
        if errors:
            return 1
        print(f"{profile['id']}: profile is valid"
              + (f" ({len(warnings)} warnings)" if warnings else ""))
        return 0
    if command == "show":
        describe(load_profile(rest[0]))
        return 0
    if command == "dry-run":
        profile = load_profile(rest[0])
        errors, warnings = validate(profile)
        if errors:
            raise SystemExit("the profile is not valid:\n  " + "\n  ".join(errors))
        selected, everything = _selection_args(rest[1:])
        requested = selection(profile, selected, everything)
        device = Device(connect())
        try:
            ok, differences = match_identity(profile, device.identity())
            states = read_states(device.shell, sorted(requested))
        finally:
            device.close()
        print(f"fingerprint: {'match' if ok else 'MISMATCH'}")
        for difference in differences:
            print(f"  {difference}")
        actions = plan(profile, states, requested)
        for name, action in sorted(actions.items()):
            print(f"{name}: {states[name]} -> {action}")
        for response in _commands(actions):
            print(response)
        return 0 if ok else 1
    if command == "apply":
        profile = load_profile(rest[0])
        selected, everything = _selection_args(rest[1:])
        requested = selection(profile, selected, everything)
        host = os.environ.get("TV_HOST", "")
        path = _manifest_arg(rest[1:]) or manifest_path(profile, host)
        device = Device(connect())
        try:
            return apply(profile, device, host, requested, path)
        finally:
            device.close()
    if command == "restore":
        path = _manifest_arg(rest)
        if not path:
            raise SystemExit("usage: profile.py restore --manifest <file>")
        device = Device(connect())
        try:
            return restore(Path(path), device)
        finally:
            device.close()
    raise SystemExit(f"unknown command {command!r}\n\n{__doc__.strip()}")


def _selection_args(args):
    selected, everything = [], False
    for index, value in enumerate(args):
        if value == "--all":
            everything = True
        elif value == "--select":
            if index + 1 >= len(args):
                raise SystemExit("--select needs a package name")
            selected.append(args[index + 1])
        elif value == "--manifest":
            continue
        elif args[index - 1] in ("--select", "--manifest"):
            continue
        else:
            raise SystemExit(f"unknown argument {value!r}")
    return selected, everything


def _manifest_arg(args):
    for index, value in enumerate(args):
        if value == "--manifest":
            if index + 1 >= len(args):
                raise SystemExit("--manifest needs a file name")
            return args[index + 1]
    return None


if __name__ == "__main__":
    sys.exit(main(sys.argv))
