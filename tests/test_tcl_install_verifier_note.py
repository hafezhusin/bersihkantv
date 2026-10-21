"""The TCL / FFalcon install-verifier note (#376, #683), and the places that used to say "impossible".

Documentation only, so the tests pin what a reader has to be able to trust: the two gates and how to
tell them apart, the three commands of the window in the order they must run, the undo, and that
PROMPT.md, TROUBLESHOOTING, the tcl-q10g-pro header and the examples index all point at the note
instead of stopping at "the launcher cannot be installed".

Run from the repository root: python3 -m unittest discover -s tests -v
"""
import re
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
NOTE = ROOT / "docs" / "devices" / "tcl-ffalcon-install-verifier.md"
TROUBLE = ROOT / "docs" / "TROUBLESHOOTING.md"
PROMPT = ROOT / "PROMPT.md"
Q10G = ROOT / "examples" / "tcl-q10g-pro" / "debloat.txt"
EXAMPLES_README = ROOT / "examples" / "README.md"

LINK = re.compile(r"\]\(([^)\s]+)\)")
DISABLE = "pm disable-user --user 0 com.android.packageinstaller"
INSTALL = "pm install -r"
ENABLE = "pm enable --user 0 com.android.packageinstaller"


def text(path):
    return path.read_text(encoding="utf-8")


def broken_links(path):
    broken = []
    for target in LINK.findall(text(path)):
        if target.startswith(("http://", "https://", "mailto:", "#")):
            continue
        local = target.split("#", 1)[0]
        if local and not (path.parent / local).exists():
            broken.append(target)
    return broken


def bullet(doc, start):
    """The markdown bullet that begins with `start`, up to the next top-level bullet."""
    lines = doc.split("\n")
    first = next(i for i, line in enumerate(lines) if line.startswith(start))
    out = [lines[first]]
    for line in lines[first + 1:]:
        if line.startswith("- **") or line.startswith("## "):
            break
        out.append(line)
    return "\n".join(out)


class TclInstallVerifierNoteTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.note = text(NOTE)

    def test_note_exists_and_links_resolve(self):
        self.assertTrue(NOTE.exists())
        self.assertEqual([], broken_links(NOTE))

    def test_note_names_both_reports_and_both_models(self):
        for needle in (
            "issues/376", "issues/683",
            "tcl_mt5879_cn", "t982_ar31a8",
            "RQ3A.210705.001", "RQ3A.210705.001.2024111116",
            "com.tcl.cyberui",
        ):
            self.assertIn(needle, self.note)

    def test_note_quotes_the_verifier_that_refuses(self):
        for line in (
            "fetch signature info from :com.tcl.cyberui",
            "LauncherStrategy.isTclLauncher",
            "The installing app not support tcl ui tab",
            "INSTALL_FAILED_VERIFICATION_FAILURE",
        ):
            self.assertIn(line, self.note)

    def test_note_separates_the_two_gates(self):
        # The window only helps when the check is in com.android.packageinstaller. The system_server
        # refusal is the Android 14 MT9655 gate and must be called out as not bypassable.
        self.assertIn("PackageManager: Install not allowed", self.note)
        self.assertIn("system_server", self.note)
        self.assertIn("tcl-mt9655-cn-android14.md", self.note)
        self.assertEqual([], broken_links(NOTE))

    def test_window_commands_are_in_order(self):
        disable = self.note.index(DISABLE)
        install = self.note.index(INSTALL, disable)
        enable = self.note.index(ENABLE, install)
        self.assertLess(disable, install)
        self.assertLess(install, enable)
        # The re-enable is the recovery step too, so it appears a second time on its own.
        self.assertGreaterEqual(self.note.count(ENABLE), 2)

    def test_note_says_the_re_enable_is_mandatory(self):
        self.assertIn("**The re-enable is not optional.**", self.note)
        self.assertIn("cannot\ninstall anything at all", self.note)

    def test_note_keeps_the_gentler_default(self):
        self.assertIn("多屏互动", self.note)
        self.assertIn("电视卫士", self.note)


class CrossReferencesTest(unittest.TestCase):
    def test_troubleshooting_bullet_points_at_the_note(self):
        doc = text(TROUBLE)
        entry = bullet(
            doc,
            "- **`INSTALL_FAILED_VERIFICATION_FAILURE` for every APK on a China TCL / FFalcon set**",
        )
        self.assertIn("devices/tcl-ffalcon-install-verifier.md", entry)
        self.assertIn("issues/376", entry)
        self.assertIn("issues/683", entry)
        self.assertIn("system_server", entry)
        self.assertIn("电视卫士", entry)
        # The doc as a whole must not claim the window always works.
        self.assertIn("verifier_verify_adb_installs=0", entry)

    def test_prompt_reads_logcat_before_giving_up(self):
        doc = text(PROMPT)
        self.assertIn("docs/devices/tcl-ffalcon-install-verifier.md", doc)
        start = doc.index("Check that the TV accepts APKs before building anything")
        entry = doc[start:start + 1800]
        self.assertIn("logcat", entry)
        self.assertIn("not support tcl ui tab", entry)
        # The system_server case still ends the launcher half; that sentence has to survive.
        self.assertIn("no window exists", entry)
        self.assertIn("impossible here", entry)

    def test_q10g_header_no_longer_says_flatly_impossible(self):
        doc = text(Q10G)
        self.assertNotIn("cannot be installed here:", doc)
        self.assertIn("cannot be installed with a\n# bare `adb install` here", doc)
        self.assertIn("docs/devices/tcl-ffalcon-install-verifier.md", doc)

    def test_examples_index_row_points_at_the_note(self):
        doc = text(EXAMPLES_README)
        row = next(
            line for line in doc.split("\n") if line.startswith("| `tcl-q10g-pro/` |")
        )
        self.assertIn("tcl-ffalcon-install-verifier.md", row)
        # And the device-note list at the bottom of the same file.
        self.assertIn(
            "- [TCL / FFalcon China firmware, the install verifier]"
            "(../docs/devices/tcl-ffalcon-install-verifier.md)",
            doc,
        )


if __name__ == "__main__":
    unittest.main()
