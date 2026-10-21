"""Profile fingerprint gate, dry-run and restore manifest (#603).

Run from the repository root: python3 -m unittest tests.test_tcl_profile -v
"""
import copy
import contextlib
import io
import json
from pathlib import Path
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tools"))

import profile  # noqa: E402

PROFILE_PATH = ROOT / "profiles" / "tcl-mt9655-cn-android14"


class FakeTv:
    """The package manager commands profile.py uses, with the state the TV would report."""

    def __init__(self, identity, enabled=(), disabled=(), uninstalled=(), fail_on=None):
        self.props = {prop: identity.get(field, "")
                      for field, prop in profile.IDENTITY.items()}
        self.enabled = set(enabled)
        self.disabled = set(disabled)
        self.uninstalled = set(uninstalled)
        self.fail_on = fail_on
        self.commands = []

    def identity(self):
        return profile.read_identity(self.shell("getprop"))

    def shell(self, command):
        self.commands.append(command)
        if command == "getprop":
            return "\n".join(f"[{key}]: [{value}]" for key, value in sorted(self.props.items()))
        if command == "pm list packages":
            return _packages(self.enabled | self.disabled)
        if command == "pm list packages -d":
            return _packages(self.disabled)
        if command == "pm list packages -u":
            return _packages(self.enabled | self.disabled | self.uninstalled)
        for prefix, into, out_of in (
                ("pm disable-user --user 0 ", self.disabled, self.enabled),
                ("pm enable --user 0 ", self.enabled, self.disabled)):
            if command.startswith(prefix):
                name = command[len(prefix):]
                if name == self.fail_on:
                    return "Error: java.lang.SecurityException"
                out_of.discard(name)
                into.add(name)
                return ""
        return ""


def _packages(names):
    return "\n".join(f"package:{name}" for name in sorted(names)) + "\n"


def checked_in():
    return profile.load_profile(PROFILE_PATH)


class ProfileValidationTest(unittest.TestCase):
    def test_checked_in_profile_is_valid(self):
        errors, warnings = profile.validate(checked_in())
        self.assertEqual(errors, [])
        # Both are recorded as gaps on purpose: no full fingerprint and no restore run yet.
        self.assertEqual(len(warnings), 2)
        self.assertTrue(any("fingerprint" in warning for warning in warnings))
        self.assertTrue(any("restore" in warning for warning in warnings))

    def test_duplicate_entry_is_rejected(self):
        data = checked_in()
        data["packages"].append(copy.deepcopy(data["packages"][0]))
        errors, _ = profile.validate(data)
        self.assertTrue(any("duplicate entry" in error for error in errors))

    def test_missing_rationale_and_risk_are_rejected(self):
        data = checked_in()
        del data["packages"][0]["rationale"]
        data["packages"][1]["risk"] = "maybe"
        errors, _ = profile.validate(data)
        self.assertTrue(any("rationale is required" in error for error in errors))
        self.assertTrue(any("risk must be one of" in error for error in errors))

    def test_package_cannot_be_both_candidate_and_protected(self):
        data = checked_in()
        data["protected"].append({"package": data["packages"][0]["package"],
                                  "category": "home", "reason": "test"})
        errors, _ = profile.validate(data)
        self.assertTrue(any("both a candidate and protected" in error for error in errors))

    def test_packages_match_the_contributor_list(self):
        """The profile may not invent packages: they come from the list beside it."""
        data = checked_in()
        listed = set()
        for line in (ROOT / "examples" / "tcl-mt9655-cn-android14" / "debloat.txt").read_text(
                encoding="utf-8").splitlines():
            body = line.split("#", 1)[0].strip()
            if body:
                listed.add(body.split()[0])
        self.assertEqual({entry["package"] for entry in data["packages"]}, listed)

    def test_protected_packages_are_not_offered(self):
        data = checked_in()
        for entry in data["protected"]:
            if entry["package"].endswith("*"):
                continue
            self.assertNotIn(entry["package"], {c["package"] for c in data["packages"]})

    def test_profile_without_fingerprint_is_identified_by_product_and_build(self):
        data = checked_in()
        identity = dict(data["identity"])
        ok, differences = profile.match_identity(data, identity)
        self.assertTrue(ok, differences)
        identity["build_id"] = "AU02"
        ok, differences = profile.match_identity(data, identity)
        self.assertFalse(ok)
        self.assertEqual(len(differences), 1)
        self.assertIn("build_id: profile AU01, TV AU02", differences[0])

    def test_region_and_abi_are_part_of_the_match(self):
        data = checked_in()
        identity = dict(data["identity"], abi="arm64-v8a,armeabi-v7a")
        self.assertTrue(profile.match_identity(data, identity)[0])
        ok, differences = profile.match_identity(data, dict(identity, region="US"))
        self.assertFalse(ok)
        self.assertIn("region", differences[0])
        ok, differences = profile.match_identity(data, dict(identity, abi="x86_64"))
        self.assertFalse(ok)
        self.assertIn("abi", differences[0])


class SelectionAndPlanTest(unittest.TestCase):
    def test_protected_and_unknown_packages_are_refused(self):
        data = checked_in()
        with self.assertRaises(SystemExit) as caught:
            profile.selection(data, ["com.tcl.cyberui"])
        self.assertIn("is protected", str(caught.exception))
        with self.assertRaises(SystemExit) as caught:
            profile.selection(data, ["com.example.nope"])
        self.assertIn("not in this profile", str(caught.exception))

    def test_all_selects_only_default_entries(self):
        data = checked_in()
        selected = profile.selection(data, everything=True)
        defaults = {entry["package"] for entry in data["packages"] if entry["default_selected"]}
        self.assertEqual(selected, defaults)
        self.assertNotIn("com.tcl.versionUpdateApp", selected)
        self.assertNotIn("com.tcl.hearaid", selected)
        with self.assertRaises(SystemExit):
            profile.selection(data)

    def test_plan_skips_already_disabled_and_uninstalled(self):
        data = checked_in()
        states = {name: "enabled" for name in profile.selection(data, everything=True)}
        states["com.tcl.bootadservice"] = "disabled"
        states["com.tcl.logkit"] = "uninstalled"
        actions = profile.plan(data, states, set(states))
        self.assertEqual(actions["com.tcl.bootadservice"], "already-disabled")
        self.assertEqual(actions["com.tcl.logkit"], "uninstalled")
        self.assertNotIn("com.tcl.bootadservice", profile._commands(actions))
        self.assertNotIn("com.tcl.logkit", profile._commands(actions))
        self.assertIn("pm disable-user --user 0 com.tcl.c2dm.client",
                      profile._commands(actions))

    def test_unreadable_state_stops_the_plan(self):
        data = checked_in()
        with self.assertRaises(SystemExit) as caught:
            profile.plan(data, {"com.tcl.bootadservice": "unknown"}, {"com.tcl.bootadservice"})
        self.assertIn("cannot read the state", str(caught.exception))


class ApplyRestoreTest(unittest.TestCase):
    def setUp(self):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        # apply/restore narrate what they do for the person at the terminal; tests only check state.
        for stream in (contextlib.redirect_stdout(io.StringIO()),
                       contextlib.redirect_stderr(io.StringIO())):
            stream.__enter__()
            self.addCleanup(stream.__exit__, None, None, None)
        self.manifest = Path(tmp.name) / "restore.json"
        self.data = checked_in()
        self.selected = ["com.tcl.bootadservice", "com.tcl.c2dm.client", "com.tcl.logkit"]
        self.identity = dict(self.data["identity"])

    def tv(self, **kwargs):
        return FakeTv(self.identity,
                      enabled={"com.tcl.bootadservice", "com.tcl.c2dm.client",
                               "com.tcl.logkit", "com.tcl.tshop", "com.tcl.hearaid"},
                      disabled={"com.tcl.wholenetsearch"},
                      **kwargs)

    def test_fingerprint_mismatch_changes_nothing(self):
        tv = self.tv()
        tv.props["ro.build.version.incremental"] = "AU02"
        with self.assertRaises(SystemExit) as caught:
            profile.apply(self.data, tv, "192.168.1.50", self.selected, self.manifest)
        self.assertIn("does not match the profile", str(caught.exception))
        self.assertEqual([c for c in tv.commands if not c.startswith("getprop")], [])
        self.assertFalse(self.manifest.exists())

    def test_apply_records_prior_state_and_restores_exactly(self):
        tv = self.tv()
        self.assertEqual(profile.apply(self.data, tv, "192.168.1.50", self.selected,
                                       self.manifest), 0)
        manifest = json.loads(self.manifest.read_text())
        self.assertTrue(manifest["complete"])
        self.assertEqual(manifest["packages"]["com.tcl.bootadservice"],
                         {"prior": "enabled", "action": "disable", "verified": True})
        self.assertTrue(self.manifest.exists())
        self.assertFalse(any(command.startswith("pm uninstall") for command in tv.commands))
        self.assertEqual(tv.disabled, {"com.tcl.wholenetsearch"} | set(self.selected))

        self.assertEqual(profile.restore(self.manifest, tv), 0)
        self.assertEqual(tv.disabled, {"com.tcl.wholenetsearch"},
                         "a package that was disabled before the apply stays disabled")
        self.assertEqual(tv.enabled, {"com.tcl.bootadservice", "com.tcl.c2dm.client",
                                      "com.tcl.logkit", "com.tcl.tshop", "com.tcl.hearaid"})
        self.assertTrue(json.loads(self.manifest.read_text())["restored"])

    def test_manifest_covers_every_touched_package(self):
        tv = self.tv()
        profile.apply(self.data, tv, "192.168.1.50", self.selected, self.manifest)
        manifest = json.loads(self.manifest.read_text())
        self.assertEqual(set(manifest["packages"]), set(self.selected))
        for entry in manifest["packages"].values():
            self.assertIn(entry["prior"], profile.STATES)
            self.assertTrue(entry["verified"])

    def test_already_disabled_and_uninstalled_packages_are_recorded_not_touched(self):
        tv = self.tv(uninstalled={"com.tcl.logkit"})
        tv.enabled.discard("com.tcl.logkit")
        selected = self.selected + ["com.tcl.wholenetsearch", "com.tcl.logkit"]
        self.assertEqual(profile.apply(self.data, tv, "192.168.1.50", selected, self.manifest), 0)
        manifest = json.loads(self.manifest.read_text())
        self.assertEqual(manifest["packages"]["com.tcl.wholenetsearch"]["action"], "already-disabled")
        self.assertEqual(manifest["packages"]["com.tcl.logkit"]["prior"], "uninstalled")
        self.assertNotIn("pm enable --user 0 com.tcl.wholenetsearch", tv.commands)
        profile.restore(self.manifest, tv)
        self.assertIn("com.tcl.wholenetsearch", tv.disabled)
        self.assertNotIn("com.tcl.logkit", tv.enabled)

    def test_interrupted_apply_is_detected_and_restorable(self):
        tv = self.tv(fail_on="com.tcl.c2dm.client")
        self.assertEqual(profile.apply(self.data, tv, "192.168.1.50", self.selected,
                                       self.manifest), 1)
        manifest = json.loads(self.manifest.read_text())
        self.assertFalse(manifest["complete"])
        self.assertEqual(manifest["packages"]["com.tcl.c2dm.client"]["verified"], False)
        self.assertIn("com.tcl.bootadservice", tv.disabled)

        with self.assertRaises(SystemExit) as caught:
            profile.apply(self.data, tv, "192.168.1.50", self.selected, self.manifest)
        self.assertIn("interrupted apply", str(caught.exception))

        tv.fail_on = None
        self.assertEqual(profile.restore(self.manifest, tv), 0)
        self.assertEqual(tv.disabled, {"com.tcl.wholenetsearch"})
        self.assertTrue(json.loads(self.manifest.read_text())["restored"])

    def test_completed_manifest_needs_a_restore_before_another_apply(self):
        tv = self.tv()
        profile.apply(self.data, tv, "192.168.1.50", self.selected, self.manifest)
        with self.assertRaises(SystemExit) as caught:
            profile.apply(self.data, tv, "192.168.1.50", self.selected, self.manifest)
        self.assertIn("already records a completed apply", str(caught.exception))

    def test_unknown_package_state_writes_no_manifest(self):
        tv = self.tv()
        tv.enabled.discard("com.tcl.bootadservice")
        tv.disabled.discard("com.tcl.bootadservice")
        with self.assertRaises(SystemExit) as caught:
            profile.apply(self.data, tv, "192.168.1.50", ["com.tcl.bootadservice"], self.manifest)
        self.assertIn("cannot read the state", str(caught.exception))
        self.assertFalse(self.manifest.exists())
        self.assertFalse(any(command.startswith("pm disable-user") for command in tv.commands))

    def test_restore_reports_a_failed_state(self):
        tv = self.tv()
        profile.apply(self.data, tv, "192.168.1.50", self.selected, self.manifest)
        # A package that refuses to come back, e.g. a vendor service that re-disables itself.
        tv.fail_on = None
        tv.disabled.add("com.tcl.bootadservice")
        original = tv.shell

        def shell(command):
            if command == "pm enable --user 0 com.tcl.bootadservice":
                tv.commands.append(command)
                return ""
            return original(command)

        tv.shell = shell
        self.assertEqual(profile.restore(self.manifest, tv), 1)
        self.assertFalse(json.loads(self.manifest.read_text())["restored"])


if __name__ == "__main__":
    unittest.main()
