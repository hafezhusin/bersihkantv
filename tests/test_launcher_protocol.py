"""Contract checks for the opt-in companion read protocol (#597).

The launcher only advertises capabilities it implements, and the companion must fail closed for a
legacy build, a newer protocol, a missing mandatory feature or malformed limits. These fixtures keep
that boundary explicit and prove the provider is opt-in at the manifest level.

Run from the repository root: python3 -m unittest discover -s tests -v
"""
import json
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
FIXTURES = ROOT / "tests" / "fixtures" / "launcher-protocol"
MANDATORY = ("collections_v1", "atomic_activation_v1", "revision_checks_v1", "readback_v1")


def editable(capabilities):
    """The companion's gate: only a supported v1 shape with every mandatory feature is writable."""
    if not isinstance(capabilities, dict):
        return False
    if capabilities.get("protocol_version") != 1:
        return False
    features = capabilities.get("features")
    if not isinstance(features, list) or not all(feature in features for feature in MANDATORY):
        return False
    limits = capabilities.get("limits")
    if not isinstance(limits, dict):
        return False
    for key in ("asset_bytes", "collection_bytes", "asset_count"):
        value = limits.get(key)
        if not isinstance(value, int) or isinstance(value, bool) or value <= 0:
            return False
    return True


def load(name):
    return json.loads((FIXTURES / name).read_text())


class CapabilityFixturesTest(unittest.TestCase):
    def test_supported_shape_is_editable(self):
        self.assertTrue(editable(load("supported.json")))

    def test_legacy_2_4_is_read_only(self):
        self.assertFalse(editable(load("legacy-2.4.json")))

    def test_newer_protocol_is_read_only(self):
        self.assertFalse(editable(load("newer-protocol.json")))

    def test_missing_mandatory_feature_is_read_only(self):
        self.assertFalse(editable(load("missing-mandatory-feature.json")))

    def test_invalid_limits_are_read_only(self):
        self.assertFalse(editable(load("invalid-limits.json")))

    def test_every_optional_feature_is_absent_from_the_supported_fixture(self):
        features = load("supported.json")["features"]
        self.assertNotIn("theme_schedules_v1", features)
        self.assertNotIn("private_credentials_v1", features)


class OptInManifestTest(unittest.TestCase):
    def test_default_manifest_has_no_companion_provider(self):
        manifest = (ROOT / "launcher/app/src/main/AndroidManifest.xml").read_text()
        self.assertNotIn("com.gathin.tvhome.companion", manifest)
        self.assertNotIn("CompanionProvider", manifest)

    def test_provider_lives_in_the_companion_source_set(self):
        provider = ROOT / "launcher/app/src/companion/java/com/gathin/tvhome/CompanionProvider.java"
        self.assertTrue(provider.is_file())
        self.assertIn("com.gathin.tvhome.companion", (ROOT / "docs/specs/companion-protocol.md").read_text())

    def test_build_gates_the_manifest_and_the_source_set(self):
        build = (ROOT / "launcher/app/build.gradle").read_text()
        self.assertIn("companionBridge", build)
        self.assertIn("src/companion/java", build)
        self.assertIn("generateCompanionManifest", build)


if __name__ == "__main__":
    unittest.main()
