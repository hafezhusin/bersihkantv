"""Where the #707 field report went: the FFalcon `ak30a5` 20250630 note and the pointers to it.

The report's own suggestion was a firmware-scoped device note, and the reason it is worth one is that
the repository currently tells an assistant to give up when a China-market TCL answers
`INSTALL_FAILED_VERIFICATION_FAILURE`. On this unit that failure is the install verifier with a single
global behind it, restored by deleting the setting rather than by writing a value - so the note, the
troubleshooting bullet and PROMPT.md have to say "read, open, restore on both paths" instead of
"impossible here".

The snippet in the note is executed here against a stub `tools/tv.py`, because the part that matters
is that the restore happens when the install fails, not only after it succeeds.

Run from the repository root: python3 -m unittest discover -s tests -v
"""
import os
import re
import subprocess
import tempfile
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
NOTE = ROOT / "docs" / "devices" / "ffalcon-50f295c-ak30a5.md"
TROUBLE = ROOT / "docs" / "TROUBLESHOOTING.md"
README = ROOT / "README.md"
EXAMPLES_README = ROOT / "examples" / "README.md"
PROMPT = ROOT / "PROMPT.md"
ISSUE = "https://github.com/Gathin23/cleanyourtv/issues/707"
AK30A5_2023 = ROOT / "examples" / "tcl-ak30a5" / "debloat.txt"

LINK = re.compile(r"\]\(([^)\s]+)\)")


def flat(text):
    """Prose wrapped across lines, so match on a single line of it."""
    return re.sub(r"[ \t]*\n[ \t]*", " ", text)


def section(text, heading):
    """The body of the top-level markdown section `## heading`, up to the next `## `."""
    lines = text.split("\n")
    start = next(i for i, line in enumerate(lines) if line.strip() == "## " + heading)
    out = []
    for line in lines[start + 1:]:
        if line.startswith("## "):
            break
        out.append(line)
    return "\n".join(out)


def bullet(text, start):
    """The markdown bullet that begins with `start`, up to the next top-level bullet."""
    lines = text.split("\n")
    first = next(i for i, line in enumerate(lines) if line.startswith(start))
    out = [lines[first]]
    for line in lines[first + 1:]:
        if line.startswith("- **") or line.startswith("## "):
            break
        out.append(line)
    return "\n".join(out)


def broken_links(path):
    return [
        target for target in LINK.findall(path.read_text(encoding="utf-8"))
        if not target.startswith(("http://", "https://", "mailto:", "#"))
        and target.split("#", 1)[0]
        and not (path.parent / target.split("#", 1)[0]).exists()
    ]


class NoteTest(unittest.TestCase):
    def setUp(self):
        self.text = NOTE.read_text(encoding="utf-8")

    def test_note_exists_and_identifies_the_device(self):
        self.assertIn("FFalcon", self.text)
        self.assertIn("50F295C", self.text)
        self.assertIn("`ak30a5`", self.text)
        self.assertIn("TCL Multimedia", self.text)
        self.assertIn("PPR1.180610.011/20250630:user/dev-keys", self.text)
        self.assertIn("armeabi-v7a", self.text)

    def test_note_is_not_an_examples_entry(self):
        self.assertIn("CONTRIBUTING", self.text)
        self.assertFalse(
            (ROOT / "examples" / "ffalcon-50f295c").exists(),
            "one evening and two packages is not a list someone lived with for days",
        )

    def test_note_separates_this_firmware_from_the_20230208_list(self):
        body = section(self.text, "This is not the `ak30a5` in `examples/tcl-ak30a5/`")
        self.assertIn("20230208", body)
        self.assertIn("20250630", body)
        self.assertIn("INSTALL_PARSE_FAILED_INCONSISTENT_CERTIFICATES", body)
        self.assertIn("does not establish", body)
        self.assertTrue(AK30A5_2023.is_file())

    def test_install_section_keeps_the_verifier_evidence(self):
        body = section(self.text, "Installing: the verifier refuses first, and it can be opened for one install")
        self.assertIn("INSTALL_FAILED_VERIFICATION_FAILURE", body)
        self.assertIn("com.android.packageinstaller/.verifier.VerifierReciver", body)
        self.assertIn("LauncherStrategy", body)
        self.assertIn("The installing app not support tcl ui tab !", body)

    def test_install_section_restores_the_absent_default_by_deleting(self):
        body = section(self.text, "Installing: the verifier refuses first, and it can be opened for one install")
        self.assertIn("verifier_verify_adb_installs", body)
        self.assertIn("returned `null`", body)
        self.assertIn("settings delete global verifier_verify_adb_installs", body)
        self.assertIn("Put it back on both paths", body)
        # The setting that would weaken every install is not part of the procedure.
        self.assertNotIn("package_verifier_enable 0", body)
        self.assertIn("`package_verifier_enable` stayed `1`", body)

    def test_home_section_says_the_boot_target_stays(self):
        body = section(self.text, "Default Home and the firmware's boot target are separate")
        self.assertIn("set-home-activity", body)
        self.assertIn("com.tcl.cyberui/.MainActivity", body)
        self.assertIn("`pm disable-user com.tcl.cyberui` did **not** stop its boot activity", body)
        self.assertIn("persist.sys.bootpackage", body)
        self.assertIn("proves\nnothing", body.replace("\r", ""))

    def test_autostart_appop_is_recorded_with_its_original_mode(self):
        body = section(self.text, "TCL's own autostart AppOp silences a boot receiver")
        self.assertIn("APP_AUTO_START", body)
        self.assertIn("cmd appops set com.gathin.tvhome APP_AUTO_START allow", body)
        self.assertIn("`ignore`", body)
        self.assertIn("restore that mode, not `deny`", body)
        self.assertIn("No other package's autostart policy was touched", body)

    def test_boot_timing_numbers_are_kept_and_not_oversold(self):
        body = section(self.text, "The ordinary boot broadcast arrives about a minute and a half late")
        for line in ("+0.000 s", "+16.878 s", "+17.697 s", "+81.269 s"):
            self.assertIn(line, body)
        self.assertIn("about 102", body)
        self.assertIn("not a\npower-button-to-Home figure", body)

    def test_boot_receiver_is_not_requested_here(self):
        body = section(self.text, "The ordinary boot broadcast arrives about a minute and a half late")
        self.assertIn("RECEIVE_BOOT_COMPLETED", body)
        self.assertIn("no new permission", body)
        self.assertIn("opt-in", body)
        self.assertIn("not a request to enable the behaviour everywhere", body)

    def test_links_resolve(self):
        self.assertEqual(broken_links(NOTE), [])

    def test_note_links_the_issue_it_transcribes(self):
        self.assertIn(ISSUE, self.text)


class RestoreSnippetTest(unittest.TestCase):
    """The documented procedure has to put the verifier back even when the install fails."""

    def run_snippet(self, was, install_exit):
        text = NOTE.read_text(encoding="utf-8")
        body = section(text, "Installing: the verifier refuses first, and it can be opened for one install")
        match = re.search(r"```sh\n(.*?)```", body, re.S)
        if match is None:
            self.fail("the note has to keep an executable restore snippet")
        code = match.group(1)
        tmp = tempfile.mkdtemp()
        os.makedirs(os.path.join(tmp, "tools"))
        log = os.path.join(tmp, "calls.log")
        stub = (
            "#!/bin/sh\n"
            f'printf "%s\\n" "$*" >> {log}\n'
            'case "$*" in\n'
            f'  *"get global verifier_verify_adb_installs"*) echo {was} ;;\n'
            f'  *"pm install"*) exit {int(install_exit)} ;;\n'
            "esac\n"
            "exit 0\n"
        )
        path = os.path.join(tmp, "tools", "tv.py")
        with open(path, "w", encoding="utf-8") as handle:
            handle.write(stub)
        os.chmod(path, 0o755)
        subprocess.run(["bash", "-c", code], cwd=tmp, check=True,
                       stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=60)
        with open(log, encoding="utf-8") as handle:
            return [line.strip() for line in handle if line.strip()]

    def test_absent_setting_is_deleted_again(self):
        calls = self.run_snippet("null", 0)
        self.assertEqual(calls[-1], "settings delete global verifier_verify_adb_installs")
        self.assertLess(calls.index("settings put global verifier_verify_adb_installs 0"),
                        len(calls) - 1)

    def test_deleted_even_when_the_install_fails(self):
        calls = self.run_snippet("null", 1)
        self.assertEqual(calls[-1], "settings delete global verifier_verify_adb_installs")
        self.assertTrue(any("pm install" in call for call in calls))

    def test_a_value_that_was_there_is_written_back(self):
        calls = self.run_snippet("1", 0)
        self.assertEqual(calls[-1], "settings put global verifier_verify_adb_installs 1")
        self.assertNotIn("settings delete global verifier_verify_adb_installs", calls)


class TroubleShootingTest(unittest.TestCase):
    def setUp(self):
        self.text = TROUBLE.read_text(encoding="utf-8")

    def test_bullet_lives_under_working_on_the_tv(self):
        self.assertIn("VerifierReciver", section(self.text, "Working on the TV"))

    def test_bullet_names_the_setting_the_restore_and_the_note(self):
        body = bullet(self.text, "- **`INSTALL_FAILED_VERIFICATION_FAILURE` naming")
        self.assertIn("verifier_verify_adb_installs", body)
        self.assertIn("settings delete global", body)
        self.assertIn("failing path", body)
        self.assertIn("devices/ffalcon-50f295c-ak30a5.md", body)

    def test_bullet_contrasts_the_parse_time_signature_refusal(self):
        body = bullet(self.text, "- **`INSTALL_FAILED_VERIFICATION_FAILURE` naming")
        self.assertIn("INSTALL_PARSE_FAILED_INCONSISTENT_CERTIFICATES", body)
        self.assertIn("package_verifier_enable", body)


class PointerTest(unittest.TestCase):
    def test_prompt_reads_the_verifier_before_giving_up(self):
        text = flat(PROMPT.read_text(encoding="utf-8"))
        self.assertIn("verifier_verify_adb_installs", text)
        self.assertIn("settings delete global verifier_verify_adb_installs", text)
        self.assertIn("on the failing path too", text)
        # The dead end stays a dead end: the give-up sentence must survive for the signature rule.
        self.assertIn("stop retrying installs", text)

    def test_prompt_names_the_autostart_appop_for_the_boot_handover(self):
        text = flat(PROMPT.read_text(encoding="utf-8"))
        self.assertIn("APP_AUTO_START", text)
        self.assertIn("com.tcl.cyberui", text)

    def test_readme_row_points_at_the_note(self):
        text = flat(README.read_text(encoding="utf-8"))
        self.assertIn("docs/devices/ffalcon-50f295c-ak30a5.md", text)
        self.assertIn("deleted again afterwards", text)

    def test_examples_readme_lists_the_note(self):
        text = EXAMPLES_README.read_text(encoding="utf-8")
        self.assertIn("../docs/devices/ffalcon-50f295c-ak30a5.md", text)
        self.assertIn("verifier_verify_adb_installs", text)


if __name__ == "__main__":
    unittest.main()
