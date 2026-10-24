# Copyright (c) 2026 Ringophilia and xiaomao
# SPDX-License-Identifier: MPL-2.0
import tempfile
import unittest
from pathlib import Path
from zipfile import ZipFile, ZIP_DEFLATED
from verify_apk_payloads import verify


class VerifyApkPayloadsTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.root = Path(self.directory.name)
        def cleanup():
            # TemporaryDirectory removes recursively; verify the created absolute
            # target is still a direct child of the intended system temp folder.
            resolved = self.root.resolve()
            if resolved.parent != Path(tempfile.gettempdir()).resolve() or self.root.is_symlink():
                raise ValueError("Temporary test directory escaped its expected parent")
            self.directory.cleanup()
        self.addCleanup(cleanup)

    def archive(self, name, entries):
        path = self.root / name
        with ZipFile(path, "w", compression=ZIP_DEFLATED) as archive:
            for entry, value in entries:
                archive.writestr(entry, value)
        return path

    def test_reordered_payloads_and_resigned_metadata_are_allowed(self):
        before = self.archive("before.apk", [("classes.dex", b"dex"), ("assets/omni.ja", b"omni"), ("META-INF/CERT.SF", b"old")])
        after = self.archive("after.apk", [("META-INF/CERT.SF", b"new"), ("assets/omni.ja", b"omni"), ("classes.dex", b"dex")])
        self.assertEqual(2, verify(before, after)["verifiedPayloadEntries"])

    def test_same_length_payload_corruption_is_rejected(self):
        before = self.archive("before.apk", [("classes.dex", b"abc")])
        after = self.archive("after.apk", [("classes.dex", b"abd")])
        with self.assertRaisesRegex(ValueError, "payload changed"):
            verify(before, after)

    def test_non_signature_meta_inf_licenses_are_not_exempt(self):
        before = self.archive("before.apk", [("META-INF/LICENSE.txt", b"license")])
        after = self.archive("after.apk", [("META-INF/LICENSE.txt", b"changed")])
        with self.assertRaises(ValueError):
            verify(before, after)

    def test_missing_or_added_payload_is_rejected(self):
        before = self.archive("before.apk", [("classes.dex", b"dex")])
        for entries in [[], [("classes.dex", b"dex"), ("assets/new", b"new")]]:
            with self.assertRaisesRegex(ValueError, "paths changed"):
                verify(before, self.archive("after.apk", entries))

    def test_duplicate_paths_are_rejected(self):
        with self.assertWarns(UserWarning):
            duplicate = self.archive("duplicate.apk", [("classes.dex", b"dex"), ("classes.dex", b"dex")])
        with self.assertRaisesRegex(ValueError, "Duplicate"):
            verify(duplicate, duplicate)

    def test_size_comparison_is_strict(self):
        apk = self.archive("original.apk", [("classes.dex", b"dex")])
        with self.assertRaises(ValueError):
            verify(apk, apk, apk.stat().st_size)
        self.assertEqual("PASSED", verify(apk, apk, apk.stat().st_size + 1)["status"])


if __name__ == "__main__":
    unittest.main()
