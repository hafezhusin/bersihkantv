"""Pins the TCL `InstallConfig` policy note and the correction it needed (#743).

The Android 14 MT9655 note ended its install section with "the `content` CLI splits `--bind` on every
`:`, so a JSON value cannot be passed at all", and concluded debloat-only. A TCL MT5879 unit on
Android 11 (#743) passed the same value once the backslashes are escaped before the colons, so the
sentence was a quoting bug read as a wall - the kind of claim that stops the next owner from trying the
one thing that works. These tests keep the recipe (and its escaping order, which is what makes it
work), keep the transaction's restore rule, keep the corrected wording in the MT9655 note, and keep the
three entry points pointing at the note. Whitespace is flattened before matching because every file
here wraps its prose.
"""
import re
from pathlib import Path
import shlex
import unittest


ROOT = Path(__file__).resolve().parents[1]
NOTE = ROOT / "docs" / "devices" / "tcl-installconfig-policy.md"
MT9655 = ROOT / "docs" / "devices" / "tcl-mt9655-cn-android14.md"
TROUBLE = ROOT / "docs" / "TROUBLESHOOTING.md"
PROMPT = ROOT / "PROMPT.md"
EXAMPLES = ROOT / "examples" / "README.md"

URI = "content://com.tcl.providers.config/InstallConfig"


def flat(path):
    return re.sub(r"\s+", " ", path.read_text(encoding="utf-8"))


def bullet(text, opening):
    """One flattened markdown bullet, from its opening words to the next top-level bullet."""
    start = text.index(opening)
    end = text.find(" - **", start)
    return text[start:end if end != -1 else len(text)]


def escaped(value):
    """The recipe the note gives: backslashes first, then colons."""
    return value.replace("\\", "\\\\").replace(":", "\\:")


def unescaped(value):
    """What the vendor `content` parser is documented to do with the escaped value."""
    out = []
    index = 0
    while index < len(value):
        if value[index] == "\\" and index + 1 < len(value):
            out.append(value[index + 1])
            index += 2
        else:
            out.append(value[index])
            index += 1
    return "".join(out)


def binding(value):
    return "config_content:s:" + escaped(value)


def fields(argument):
    """The CLI's field split: a colon ends a field unless it is backslash-escaped."""
    return re.split(r"(?<!\\):", argument)


def relative_links(path):
    """Every relative markdown link target in a file, as a resolved path."""
    text = path.read_text(encoding="utf-8")
    for target in re.findall(r"\]\(([^)\s]+)\)", text):
        if target.startswith(("http://", "https://", "#")):
            continue
        yield path.parent / target.split("#", 1)[0]


class EscapingRecipeTest(unittest.TestCase):
    """The note's claim, run: backslashes before colons, then quote the whole argv."""

    def test_urls_quotes_and_backslashes_survive_the_recipe(self):
        values = [
            '[{"url":"https://host:123/a","text":"a\\\\b\'c"}]',
            '{"packages":["com.example.tv"],"priority":"3"}',
            '{"note":"C:\\\\Users\\\\tv"}',
        ]
        for value in values:
            with self.subTest(value=value):
                argv = ["content", "update", "--uri", URI, "--bind", binding(value)]
                remote = shlex.split(shlex.join(argv))      # the shell layer on the TV
                self.assertEqual(remote[-1], binding(value))  # quoting loses nothing
                payload = remote[-1].split(":", 2)[2]        # after "config_content" "s"
                self.assertEqual(unescaped(payload), value)

    def test_the_opposite_order_does_not_round_trip(self):
        value = '{"url":"https://host:123/a"}'
        wrong = value.replace(":", "\\:").replace("\\", "\\\\")
        self.assertNotEqual(unescaped(wrong), value)

    def test_an_unescaped_value_splits_into_the_wrong_number_of_fields(self):
        value = '{"url":"https://host:123/a"}'
        self.assertGreater(len(fields("config_content:s:" + value)), 3)   # the CLI sees fragments of JSON
        self.assertEqual(len(fields(binding(value))), 3)


class NoteTest(unittest.TestCase):
    def setUp(self):
        self.text = flat(NOTE)

    def test_the_note_names_the_provider_column_and_unit(self):
        for phrase in (
            URI,
            "config_content",
            "tcl_mt5879_cn",
            "Android 11",
            "API 30",
            "armeabi-v7a",
            "issues/743",
        ):
            with self.subTest(phrase=phrase):
                self.assertIn(phrase, self.text)

    def test_the_six_strategy_names_are_listed_so_a_reader_can_compare(self):
        for name in (
            "safeStrategy",
            "blackListStrategy",
            "thridStrategy",
            "launcherStrategy",
            "overDueStrategy",
            "pmStrategy",
        ):
            with self.subTest(name=name):
                self.assertIn(name, self.text)

    def test_the_note_states_the_escaping_order_and_why(self):
        self.assertIn("backslashes first, then colons", self.text)
        self.assertIn("Escaping the colons first and the backslashes second", self.text)
        # The recipe is worthless without the tool that keeps the shell from re-splitting it.
        self.assertIn("shlex.join", self.text)

    def test_the_note_keeps_the_backup_and_exact_restore_rule(self):
        self.assertIn("0600", self.text)
        self.assertIn("Restore the saved original string", self.text)
        self.assertIn("a failed restore is reported as a failure even when the APK did install", self.text)

    def test_the_note_keeps_the_scope_of_the_two_temporary_changes(self):
        self.assertIn("package-specific", self.text)
        self.assertIn("do not run two installs", self.text)

    def test_the_note_says_it_is_not_atomic(self):
        self.assertIn("not compare-and-swap", self.text)
        self.assertIn("no durable lock", self.text)

    def test_the_note_does_not_promise_anything_about_other_firmware(self):
        self.assertIn("not evidence that the write fails everywhere", self.text)
        self.assertIn("one unit is not a compatibility claim", self.text)

    def test_the_note_keeps_the_refusal_on_the_android_14_sets(self):
        self.assertIn("debloat only", self.text)
        self.assertIn("tcl-mt9655-cn-android14.md", self.text)

    def test_the_note_keeps_the_owner_and_approval_boundary(self):
        self.assertIn("television you own", self.text)
        self.assertIn("owner's approval for that", self.text)
        self.assertIn("not a lock to pick", self.text)

    def test_every_relative_link_in_the_note_resolves(self):
        for target in relative_links(NOTE):
            with self.subTest(target=str(target)):
                self.assertTrue(target.is_file(), f"broken link in the note: {target}")

    def test_the_last_policy_sentence_is_not_a_general_unlock(self):
        self.assertIn("refuses writes", self.text)
        self.assertIn("aapt", self.text)   # the package metadata is not read by the helper


class Mt9655CorrectionTest(unittest.TestCase):
    def setUp(self):
        self.text = flat(MT9655)
        self.section = self.text[self.text.index("`launcherStrategy` is enabled"):]
        self.section = self.section[:self.section.index("The stock launcher")]

    def test_the_blanket_cannot_be_passed_claim_is_gone(self):
        self.assertNotIn("a JSON value cannot be passed at all", self.text)
        self.assertIn("a quoting problem rather than a closed door", self.section)

    def test_the_section_links_the_unit_where_the_write_lands(self):
        self.assertIn("tcl-installconfig-policy.md", self.section)
        self.assertIn("issues/743", self.section)

    def test_the_evidence_from_that_unit_survives(self):
        # The Version-row probe and the unreadable database are what "read-only here" rests on.
        self.assertIn("rewriting the `Version` row", self.section)
        self.assertIn("not readable as `shell`", self.section)

    def test_it_does_not_claim_the_allowlist_would_not_have_helped(self):
        self.assertIn("is untested: the write never landed, so nobody knows", self.section)
        self.assertIn("debloat only", self.section)


class EntryPointsTest(unittest.TestCase):
    def test_the_troubleshooting_bullet_points_at_the_note(self):
        entry = bullet(flat(TROUBLE), "- **A China TCL/FFalcon set refuses an install it should accept**")
        self.assertIn(URI, entry)
        self.assertIn("issues/743", entry)
        self.assertIn("[the policy note](devices/tcl-installconfig-policy.md)", entry)
        self.assertIn("content query --uri", entry)
        self.assertIn("restored to the exact original string", entry)
        # The two generations must stay apart: the note exists because they differ.
        self.assertIn("Android 14 MT9655 sets the same write is ignored", entry)

    def test_the_prompt_reads_the_policy_before_giving_up_on_the_launcher(self):
        step = bullet(flat(PROMPT), "- Check that the TV accepts APKs before building anything")
        self.assertIn(URI, step)
        self.assertIn("launcherStrategy.packages", step)
        self.assertIn("Restore the original string exactly", step)
        self.assertIn("docs/devices/tcl-installconfig-policy.md", step)

    def test_the_examples_index_lists_the_note(self):
        row = next(
            line for line in EXAMPLES.read_text(encoding="utf-8").splitlines()
            if "../docs/devices/tcl-installconfig-policy.md" in line
        )
        self.assertIn("tcl_mt5879_cn", row)
        self.assertIn("InstallConfig", row)
        self.assertTrue((EXAMPLES.parent / ".." / "docs" / "devices" / "tcl-installconfig-policy.md").is_file())

    def test_every_relative_link_in_the_three_entry_points_resolves(self):
        for path in (TROUBLE, PROMPT, EXAMPLES):
            for target in relative_links(path):
                with self.subTest(file=path.name, target=str(target)):
                    self.assertTrue(target.exists(), f"broken link in {path.name}: {target}")


if __name__ == "__main__":
    unittest.main()
