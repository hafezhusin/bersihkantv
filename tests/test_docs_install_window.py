"""The China TCL / FFalcon verifier workaround stays in the order that keeps the TV usable.

The three commands in docs/TROUBLESHOOTING.md are a window, not a setting: packageinstaller is
disabled, one install runs, packageinstaller comes back. Dropping or reordering the re-enable leaves
a set that installs and uninstalls nothing, so the order is checked rather than trusted. The places
that used to say no launcher can ever be installed have to keep pointing at that write-up, which is
the whole point of the correction (issue #376).

Run from the repository root: python3 -m unittest discover -s tests -v
"""
import re
from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[1]
TROUBLE = ROOT / "docs" / "TROUBLESHOOTING.md"
Q10G = ROOT / "examples" / "tcl-q10g-pro" / "debloat.txt"
DISABLE = "pm disable-user --user 0 com.android.packageinstaller"
ENABLE = "pm enable com.android.packageinstaller"


def window_block():
    """The first fenced block in TROUBLESHOOTING that carries the packageinstaller window."""
    for block in re.findall(r"```sh\n(.*?)```", TROUBLE.read_text(encoding="utf-8"), re.S):
        if DISABLE in block:
            return [line.strip() for line in block.split("\n") if line.strip()]
    return []


class WindowTest(unittest.TestCase):
    def test_window_is_three_commands_in_order(self):
        lines = window_block()
        self.assertTrue(lines, f"no ```sh block holding {DISABLE!r} in docs/TROUBLESHOOTING.md")
        self.assertIn(DISABLE, lines[0], "the window has to open with the disable")
        self.assertIn(ENABLE, lines[-1], "the window has to close with the re-enable")
        installs = [n for n, line in enumerate(lines) if "pm install" in line]
        self.assertTrue(installs, "no install inside the window")
        self.assertTrue(all(0 < n < len(lines) - 1 for n in installs),
                        f"the install must sit between the disable and the re-enable: {lines}")

    def test_the_re_enable_is_called_out_as_mandatory(self):
        text = TROUBLE.read_text(encoding="utf-8")
        self.assertIn("re-enable is not optional", text,
                      "the bullet must say the re-enable is not optional, not just show it")
        self.assertIn("install and uninstall nothing", text,
                      "and say what a TV is left with while packageinstaller is disabled")

    def test_scope_is_named(self):
        text = TROUBLE.read_text(encoding="utf-8")
        self.assertIn("tcl_mt5879_cn", text, "name the firmware family the report came from")
        self.assertIn("75R685C", text, "name the one unit it was seen on")
        self.assertIn("issues/376", text, "link the report")


class PointersTest(unittest.TestCase):
    def test_prompt_tries_the_window_before_calling_it_impossible(self):
        text = (ROOT / "PROMPT.md").read_text(encoding="utf-8")
        self.assertIn("com.android.packageinstaller", text,
                      "PROMPT.md still sends every refusal straight to 'impossible'")
        self.assertIn("docs/TROUBLESHOOTING.md", text, "PROMPT.md must point at the caveats")
        self.assertIn("launcher half is impossible", text,
                      "a real refusal still ends the launcher half; keep that guidance")

    def test_readme_no_longer_says_no_launcher_ever(self):
        text = (ROOT / "README.md").read_text(encoding="utf-8")
        start = text.index("## Supported TVs")
        section = text[start:text.index("\n## ", start + 1)]
        self.assertIn("docs/TROUBLESHOOTING.md", section, "Supported TVs must point at the window")
        self.assertNotIn("install any launcher at all", section,
                         "that absolute claim is what #376 corrected")

    def test_q10g_header_scopes_the_workaround_to_the_sibling_unit(self):
        header = Q10G.read_text(encoding="utf-8")
        head = header[:header.index("\ncom.")]
        self.assertIn("75R685C", head, "the workaround was seen on the sibling FFalcon, name it")
        self.assertIn("Not tried on this 55Q10G Pro", head,
                      "do not imply it was tried on this list's own TV")
        self.assertIn("docs/TROUBLESHOOTING.md", head, "point at the commands and their caveats")


if __name__ == "__main__":
    unittest.main()
