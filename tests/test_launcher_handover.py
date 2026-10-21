"""Run launcher/build-and-install.sh against a simulated TV, without gradle or a real device.

The handover is the step that can leave a TV with no working Home, so the failures worth testing are
the ones no TV reproduces on demand: a launcher that does not start, and a connection that drops
between disabling the stock launcher and confirming the result (#239).

Run from the repository root: python3 -m unittest discover -s tests -v
"""
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[1]
STOCK_HOME = "com.google.android.tvlauncher/.MainActivity"
NEW_HOME = "com.gathin.tvhome/.MainActivity"

# Stands in for tools/tv.py: a TV that answers shell commands and keeps the state the script changes.
FAKE_TV = r'''
import json
import os
import sys


class LinkLost(Exception):
    pass


STATE_PATH = os.environ["FAKE_TV_STATE"]


def load():
    with open(STATE_PATH) as f:
        return json.load(f)


def save():
    with open(STATE_PATH, "w") as f:
        json.dump(state, f)


state = load()


def run(command):
    if "pm install" in command:
        return "Failure [INSTALL_FAILED_INSUFFICIENT_STORAGE]\n" if state["install_fails"] else "Success\n"
    if "am start" in command:
        if state["start_fails"]:
            return ("Starting: Intent { cmp=com.gathin.tvhome/.MainActivity }\n"
                    "Error: Activity not started, unable to resolve Intent\n")
        return ("Starting: Intent { cmp=com.gathin.tvhome/.MainActivity }\n"
                "Status: ok\nActivity: com.gathin.tvhome/.MainActivity\n")
    if "pm disable-user" in command:
        if state["disable_fails"]:
            return "Error: java.lang.SecurityException\n"
        state["stock_disabled"] = True
        if state["drop_after_handover"]:
            state["link_lost"] = True
        return ""
    if "pm enable" in command:
        if state["enable_fails"]:
            return "Error: java.lang.SecurityException\n"
        state["stock_disabled"] = False
        return ""
    if "set-home-activity" in command:
        if not state["set_home_fails"]:
            target = command.split("--user 0", 1)[1].strip()
            state["home"] = STOCK if "$(" in target and "resolve-activity" in command else target
        return ""
    if "query-activities" in command:
        if state["link_lost"]:
            raise LinkLost()
        return "\n".join(state["claimants"]) + "\n"
    if "resolve-activity" in command:
        if state["link_lost"]:
            raise LinkLost()
        # An explicit package after the HOME filter asks for that package's component.
        for package in state["claimants"]:
            if package.split("/")[0] in command:
                return package + "\n"
        # A disabled package is not a HOME candidate, so the answer falls back to the chooser.
        if state["stock_disabled"] and state["home"] == state["stock_home"]:
            return "android\n"
        return state["home"] + "\n"
    return ""


def main():
    args = [a for a in sys.argv[1:] if a != "-I"]
    if any(a.endswith("b64push.py") for a in args):
        print("pushed")
        return 0
    if "--batch" in args:
        commands = [line.strip() for line in sys.stdin
                    if line.strip() and not line.strip().startswith("#")]
    elif args:
        commands = [args[-1]]
    else:
        commands = []
    for command in commands:
        state["commands"].append(command)
        try:
            sys.stdout.write(run(command))
        except LinkLost:
            save()
            sys.stderr.write("adb: connection reset by peer\n")
            return 2
        sys.stdout.flush()
    save()
    return 0


sys.exit(main())
'''.replace("STOCK", repr(STOCK_HOME)).replace("NEW", repr(NEW_HOME))


class HandoverTest(unittest.TestCase):
    def setUp(self):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        self.base = Path(tmp.name)
        self.launcher = self.base / "launcher"
        self.launcher.mkdir()
        shutil.copy2(ROOT / "launcher" / "build-and-install.sh", self.launcher)
        # No JDK and no Android SDK here: the build step only has to produce a file to push.
        gradlew = self.launcher / "gradlew"
        gradlew.write_text('#!/bin/sh\n'
                           'mkdir -p app/build/outputs/apk/release\n'
                           'printf apk > app/build/outputs/apk/release/app-release.apk\n')
        gradlew.chmod(0o755)
        tools = self.base / "tools"
        (tools / "venv" / "bin").mkdir(parents=True)
        (tools / "b64push.py").write_text("")
        fake = self.base / "fake_tv.py"
        fake.write_text(FAKE_TV)
        client = tools / "venv" / "bin" / "python"
        client.write_text(f'#!/bin/sh\nexec "{sys.executable}" "{fake}" "$@"\n')
        client.chmod(0o755)
        self.state_path = self.base / "tv-state.json"
        self.home = self.base / "home"
        self.home.mkdir()

    def handover(self, extra_env=None, **overrides):
        state = {
            "stock_home": STOCK_HOME,
            "home": STOCK_HOME,
            "claimants": [STOCK_HOME, NEW_HOME],
            "stock_disabled": False,
            "install_fails": False,
            "start_fails": False,
            "disable_fails": False,
            "set_home_fails": False,
            "enable_fails": False,
            "drop_after_handover": False,
            "link_lost": False,
            "commands": [],
        }
        state.update(overrides)
        self.state_path.write_text(json.dumps(state))
        env = {k: v for k, v in os.environ.items()
               if k not in ("STOCK_LAUNCHER", "INPUT_KEY_SERVICE", "HOME_KEY_SERVICE")}
        env.update(TV_HOST="192.168.1.50", FAKE_TV_STATE=str(self.state_path),
                   HOME=str(self.home), JAVA_HOME=str(self.base / "java"),
                   ANDROID_HOME=str(self.base / "android-sdk"))
        env.update(extra_env or {})
        result = subprocess.run(
            ["bash", str(self.launcher / "build-and-install.sh")],
            cwd=self.launcher, env=env, capture_output=True, text=True, timeout=180,
        )
        with open(self.state_path) as f:
            state = json.load(f)
        return result, state

    def status(self, **overrides):
        state = {
            "stock_home": STOCK_HOME,
            "home": STOCK_HOME,
            "claimants": [STOCK_HOME, NEW_HOME],
            "stock_disabled": False,
            "install_fails": False,
            "start_fails": False,
            "disable_fails": False,
            "set_home_fails": False,
            "enable_fails": False,
            "drop_after_handover": False,
            "link_lost": False,
            "commands": [],
        }
        state.update(overrides)
        self.state_path.write_text(json.dumps(state))
        env = {k: v for k, v in os.environ.items()
               if k not in ("STOCK_LAUNCHER", "INPUT_KEY_SERVICE", "HOME_KEY_SERVICE")}
        env.update(TV_HOST="192.168.1.50", FAKE_TV_STATE=str(self.state_path),
                   HOME=str(self.home), JAVA_HOME=str(self.base / "java"),
                   ANDROID_HOME=str(self.base / "android-sdk"))
        result = subprocess.run(
            ["bash", str(self.launcher / "build-and-install.sh"), "--status"],
            cwd=self.launcher, env=env, capture_output=True, text=True, timeout=60,
        )
        with open(self.state_path) as f:
            after = json.load(f)
        return result, after

    def record(self):
        files = list(self.home.glob("cleanyourtv-handover-*.txt"))
        self.assertLessEqual(len(files), 1)
        return files[0].read_text() if files else None

    def test_success_hands_home_over(self):
        result, state = self.handover()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("Installed.", result.stdout)
        commands = state["commands"]
        start = next(i for i, c in enumerate(commands) if "am start" in c)
        disable = next(i for i, c in enumerate(commands) if "pm disable-user" in c)
        self.assertLess(start, disable, "the launcher is started before the stock Home is disabled")
        self.assertTrue(state["stock_disabled"])
        self.assertEqual(state["home"], NEW_HOME)
        self.assertIsNone(self.record())

    def test_launcher_that_does_not_start_leaves_stock_home_enabled(self):
        result, state = self.handover(start_fails=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("did not start", result.stderr)
        self.assertIn("was left enabled", result.stderr)
        self.assertNotIn("Installed.", result.stdout)
        self.assertFalse(any("pm disable-user" in c for c in state["commands"]))
        self.assertFalse(state["stock_disabled"])

    def test_lost_connection_reports_incomplete_recovery(self):
        result, state = self.handover(drop_after_handover=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("stopped answering", result.stderr)
        self.assertIn("recovery is incomplete", result.stderr)
        self.assertTrue(any("pm enable --user 0" in c for c in state["commands"]))
        text = self.record()
        self.assertIsNotNone(text, "an unreachable TV needs the recovery commands on disk")
        self.assertIn("pm enable --user 0 com.google.android.tvlauncher", text or "")

    def test_home_that_stays_on_another_launcher_is_rolled_back(self):
        result, state = self.handover(set_home_fails=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("owns Home again", result.stderr)
        self.assertIn("setupwraith", result.stderr)
        self.assertFalse(state["stock_disabled"])
        self.assertIsNone(self.record())

    def test_failed_rollback_is_not_reported_as_recovered(self):
        result, state = self.handover(set_home_fails=True, enable_fails=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertNotIn("owns Home again", result.stderr)
        self.assertIn("recovery is incomplete", result.stderr)
        self.assertTrue(any("pm enable --user 0" in c for c in state["commands"]))
        self.assertIsNotNone(self.record())

    def test_status_is_read_only_and_names_the_exact_prior_component(self):
        result, state = self.status()
        self.assertEqual(result.returncode, 0, result.stderr)
        data = json.loads(result.stdout)
        self.assertEqual(data["home"]["component"], STOCK_HOME)
        self.assertEqual(data["home"]["owner"], "stock")
        self.assertEqual(data["stock"]["prior_component"], STOCK_HOME)
        self.assertEqual(data["stock"]["launcher"], "com.google.android.tvlauncher")
        self.assertEqual(data["modes"], ["takeover", "leave-stock-alone", "restore-prior-owner"])
        self.assertFalse(data["recovery"]["exists"])
        mutating = [c for c in state["commands"] if any(
            word in c for word in ("pm install", "pm disable-user", "pm enable", "set-home-activity"))]
        self.assertEqual(mutating, [])

    def test_status_reports_a_chooser_and_an_unknown_owner(self):
        result, _ = self.status(home="android")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(json.loads(result.stdout)["home"]["owner"], "chooser")

        result, _ = self.status(home="com.example.other/.Home")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(json.loads(result.stdout)["home"]["owner"], "other")

    def test_declined_chooser_with_two_known_claimants_changes_nothing(self):
        result, state = self.handover(home="android", claimants=[
            STOCK_HOME, "com.google.android.apps.tv.launcherx/.MainActivity", NEW_HOME])
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("known stock launchers claim Home", result.stderr)
        self.assertFalse(any("pm disable-user" in c for c in state["commands"]))
        self.assertFalse(any("set-home-activity" in c for c in state["commands"]))
        self.assertEqual(state["home"], "android")

    def test_partial_install_leaves_the_stock_launcher_enabled(self):
        result, state = self.handover(install_fails=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("pm install failed", result.stderr)
        self.assertFalse(any("pm disable-user" in c for c in state["commands"]))
        self.assertFalse(state["stock_disabled"])

    def test_restore_uses_the_exact_prior_component(self):
        component = "com.google.android.tvlauncher/.TvLauncherActivity"
        result, state = self.handover(stock_home=component, home=component, set_home_fails=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("owns Home again", result.stderr)
        restore = [c for c in state["commands"] if "set-home-activity" in c]
        self.assertTrue(any(component in c for c in restore), restore)
        self.assertEqual(state["home"], component)
        self.assertIsNone(self.record())

    def test_leave_stock_alone_touches_no_owner(self):
        result, state = self.handover(extra_env={"STOCK_LAUNCHER": "none"})
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertFalse(any("pm disable-user" in c for c in state["commands"]))
        self.assertFalse(any("pm enable" in c for c in state["commands"]))
        self.assertEqual(state["home"], NEW_HOME)

    def test_repeat_handover_is_idempotent(self):
        result, state = self.handover(home=NEW_HOME, stock_disabled=True)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("nothing will be disabled", result.stdout)
        self.assertFalse(any("pm disable-user" in c for c in state["commands"]))
        self.assertEqual(state["home"], NEW_HOME)


if __name__ == "__main__":
    unittest.main()
