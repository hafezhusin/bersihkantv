"""Release manifest creation and verification (#604).

Run from the repository root: python3 -m unittest tests.test_release_manifest -v
"""
import contextlib
import io
import json
import os
from pathlib import Path
import shutil
import sys
import tempfile
import unittest
import zipfile

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tools"))

import release_manifest  # noqa: E402

RELEASE_CERT = "ab" * 32
DEBUG_CERT = "cd" * 32
FAKE_AAPT2 = """#!/bin/sh
printf "package: name='%s' versionCode='%s' versionName='%s'\\n" "$FAKE_PACKAGE" "$FAKE_VERSION_CODE" "$FAKE_VERSION_NAME"
printf "sdkVersion:'%s'\\n" "$FAKE_MIN_SDK"
printf "targetSdkVersion:'%s'\\n" "$FAKE_TARGET_SDK"
if [ -n "$FAKE_ABIS" ]; then printf "native-code: %s\\n" "$FAKE_ABIS"; fi
"""
FAKE_APKSIGNER = """#!/bin/sh
echo "Signer #1 certificate DN: $FAKE_DN"
echo "Signer #1 certificate SHA-256 digest: $FAKE_CERT"
"""


class ReleaseManifestTest(unittest.TestCase):
    def setUp(self):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        self.base = Path(tmp.name)
        self.apk = self.base / "launcher.apk"
        self.manifest = self.base / "launcher-release.json"
        self.aapt2, self.apksigner = self._tools()
        self.env = {"FAKE_PACKAGE": "com.gathin.tvhome", "FAKE_VERSION_CODE": "6",
                    "FAKE_VERSION_NAME": "2.5", "FAKE_MIN_SDK": "26",
                    "FAKE_TARGET_SDK": "34", "FAKE_ABIS": "",
                    "FAKE_DN": "CN=cleanyourtv, O=cleanyourtv", "FAKE_CERT": RELEASE_CERT}
        self._apk()

    def _tools(self):
        tools = []
        for name, body in (("aapt2", FAKE_AAPT2), ("apksigner", FAKE_APKSIGNER)):
            path = self.base / name
            path.write_text(body)
            path.chmod(0o755)
            tools.append(str(path))
        return tools

    def _apk(self, entries=("lib/arm64-v8a/liblauncher.so",)):
        with zipfile.ZipFile(self.apk, "w") as archive:
            archive.writestr("AndroidManifest.xml", "binary manifest")
            for entry in entries:
                archive.writestr(entry, b"x")
            archive.writestr("classes.dex", b"dex")

    def _env(self):
        old = {key: os.environ.get(key) for key in self.env}
        os.environ.update(self.env)

        def restore():
            for key, value in old.items():
                if value is None:
                    os.environ.pop(key, None)
                else:
                    os.environ[key] = value

        self.addCleanup(restore)

    def run_cli(self, *args):
        self._env()
        out, err = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
            code = release_manifest.main(list(args))
        return code, out.getvalue(), err.getvalue()

    def create(self, *extra):
        return self.run_cli("create", "--apk", str(self.apk), "--out", str(self.manifest),
                            "--aapt2", self.aapt2, "--apksigner", self.apksigner, *extra)

    def verify(self, *extra, installed=None):
        args = ["verify", "--apk", str(self.apk), "--manifest", str(self.manifest),
                "--aapt2", self.aapt2, "--apksigner", self.apksigner]
        if installed is not None:
            path = self.base / "installed.json"
            path.write_text(json.dumps(installed))
            args += ["--installed", str(path)]
        return self.run_cli(*(args + list(extra)))

    def data(self):
        return json.loads(self.manifest.read_text())

    def test_create_records_identity_hash_and_signer(self):
        code, out, err = self.create()
        self.assertEqual(code, 0, err)
        data = self.data()
        self.assertEqual(data["format"], release_manifest.FORMAT)
        self.assertEqual(data["package"], "com.gathin.tvhome")
        self.assertEqual((data["versionCode"], data["versionName"]), (6, "2.5"))
        self.assertEqual((data["minSdk"], data["targetSdk"]), (26, 34))
        self.assertEqual(data["abis"], ["arm64-v8a"])
        self.assertEqual(data["signing"]["kind"], "release")
        self.assertEqual(data["signing"]["certificateSha256"], RELEASE_CERT)
        self.assertEqual(len(data["sha256"]), 64)
        self.assertEqual(data["sizeBytes"], self.apk.stat().st_size)
        self.assertEqual(data["protocol"], {"version": 1, "mandatory": [], "optional": []})

    def test_debug_apk_is_refused_without_allow_debug(self):
        self.env["FAKE_DN"] = "C=US, O=Android, CN=Android Debug"
        self.env["FAKE_CERT"] = DEBUG_CERT
        code, _, err = self.create()
        self.assertEqual(code, 1)
        self.assertIn("debug-signed", err)
        self.assertFalse(self.manifest.exists())

    def test_allow_debug_records_a_local_build(self):
        self.env["FAKE_DN"] = "C=US, O=Android, CN=Android Debug"
        self.env["FAKE_CERT"] = DEBUG_CERT
        code, _, err = self.create("--allow-debug")
        self.assertEqual(code, 0, err)
        self.assertEqual(self.data()["signing"]["kind"], "debug")
        code, _, err = self.verify()
        self.assertEqual(code, 0, err)
        self.assertIn("debug-signed local build", err)

    def test_verify_accepts_the_pinned_apk(self):
        self.create()
        code, out, err = self.verify()
        self.assertEqual(code, 0, err)
        self.assertIn("verified: com.gathin.tvhome 2.5 (6)", out)

    def test_corrupted_and_truncated_downloads_are_refused(self):
        self.create()
        original = self.apk.read_bytes()
        self.apk.write_bytes(original + b"corruption")
        code, _, err = self.verify()
        self.assertEqual(code, 1)
        self.assertIn("size:", err)
        self.assertIn("sha256:", err)
        self.assertIn("nothing was installed", err)
        self.apk.write_bytes(original[:len(original) // 2])
        code, _, err = self.verify()
        self.assertEqual(code, 1)
        self.assertTrue("size:" in err or "not a readable APK" in err, err)

    def test_wrong_package_version_sdk_or_abi_is_refused(self):
        self.create()
        data = self.data()
        for field, value in (("package", "com.example.other"), ("versionCode", 7),
                             ("minSdk", 25), ("targetSdk", 33), ("abis", ["x86_64"])):
            broken = dict(data, **{field: value})
            self.manifest.write_text(json.dumps(broken))
            code, _, err = self.verify()
            self.assertEqual(code, 1, field)
            self.assertIn(field, err)

    def test_changed_signer_is_refused(self):
        self.create()
        data = self.data()
        data["signing"]["certificateSha256"] = "ef" * 32
        self.manifest.write_text(json.dumps(data))
        code, _, err = self.verify()
        self.assertEqual(code, 1)
        self.assertIn("signer:", err)

    def test_release_manifest_refuses_a_debug_signed_apk(self):
        self.create()
        self.env["FAKE_DN"] = "C=US, O=Android, CN=Android Debug"
        self.env["FAKE_CERT"] = DEBUG_CERT
        code, _, err = self.verify()
        self.assertEqual(code, 1)
        self.assertIn("debug-signed", err)

    def test_installed_states(self):
        self.create()
        base = {"package": "com.gathin.tvhome", "signerSha256": RELEASE_CERT}
        code, out, _ = self.verify(installed=dict(base, versionCode=6))
        self.assertEqual(code, 0)
        self.assertIn("no-op", out)
        code, out, _ = self.verify(installed=dict(base, versionCode=5))
        self.assertEqual(code, 0)
        self.assertIn("install: upgrade", out)
        code, _, err = self.verify(installed=dict(base, versionCode=7))
        self.assertEqual(code, 3)
        self.assertIn("downgrade", err)
        code, _, err = self.verify(installed=dict(base, versionCode=5, signerSha256="ef" * 32))
        self.assertEqual(code, 1)
        self.assertIn("installed signer differs", err)
        code, out, _ = self.verify(installed={"package": "com.example.other", "versionCode": 1})
        self.assertEqual(code, 0)
        self.assertIn("install: fresh", out)

    def test_manifest_missing_fields_is_refused(self):
        self.create()
        for field in ("sha256", "signing", "protocol", "abis"):
            data = self.data()
            del data[field]
            self.manifest.write_text(json.dumps(data))
            code, _, err = self.verify()
            self.assertEqual(code, 1, field)
            self.assertIn("missing", err)

    def test_apk_without_native_code_advertises_no_abi(self):
        self._apk(entries=())
        code, _, err = self.create()
        self.assertEqual(code, 0, err)
        self.assertEqual(self.data()["abis"], [])

    def test_missing_apk_and_missing_tool_fail_closed(self):
        self.create()
        self.apk.unlink()
        code, _, err = self.verify()
        self.assertEqual(code, 1)
        self.assertIn("no such file", err)
        shutil.copy(self.manifest, self.base / "kept.json")
        code, _, err = self.run_cli("create", "--apk", str(self.base / "kept.json"),
                                    "--out", str(self.manifest), "--aapt2", "/nonexistent/aapt2",
                                    "--apksigner", self.apksigner)
        self.assertEqual(code, 1)
        self.assertIn("cannot run", err)


if __name__ == "__main__":
    unittest.main()
