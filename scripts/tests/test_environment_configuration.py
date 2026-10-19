"""Small synthetic ZIP/receipt controls, not actual APK or Android execution."""
import hashlib
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch
import warnings
import zipfile

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import check_environment_configuration as guard


class EnvironmentConfigurationTest(unittest.TestCase):
    def data(self):
        return json.dumps(guard.EXPECTED).encode()

    def test_exact_profile_accepts_and_secret_unknown_or_wrong_type_fields_reject(self):
        self.assertEqual(guard.validate(self.data()), guard.EXPECTED)
        for key, value in [("token", "PRIVATE_CANARY"), ("application_version_code", True),
                           ("application_version_code", 1.0), ("environment", "production"),
                           ("api_base_url", "https://unapproved.example.invalid/v1")]:
            candidate = dict(guard.EXPECTED)
            candidate[key] = value
            with self.assertRaises(guard.PolicyFailure) as failure:
                guard.validate(json.dumps(candidate).encode())
            self.assertNotIn("PRIVATE_CANARY", str(failure.exception))

    def test_duplicates_trailing_roots_encoding_and_limits_reject(self):
        data = self.data()
        for invalid in [data.replace(b'{', b'{"environment":"preview",', 1), data + b'{}',
                        b'\xc3(', b' ' * (guard.MAX_BYTES + 1), b'{"x":NaN}']:
            with self.assertRaises(guard.PolicyFailure):
                guard.validate(invalid)

    def test_packaged_member_requires_unique_exact_bounded_bytes(self):
        with tempfile.TemporaryDirectory() as directory:
            apk = Path(directory) / "synthetic.zip"
            for entries in [[], [(guard.MEMBER, b'{}')], [(guard.MEMBER, b' ' * 4097)],
                            [(guard.MEMBER, self.data()), (guard.MEMBER, self.data())]]:
                with warnings.catch_warnings():
                    warnings.simplefilter("ignore", UserWarning)
                    with zipfile.ZipFile(apk, "w") as archive:
                        for name, data in entries:
                            archive.writestr(name, data)
                with self.assertRaises(guard.PolicyFailure):
                    guard.inspect_member(apk, self.data())
            with zipfile.ZipFile(apk, "w") as archive:
                archive.writestr(guard.MEMBER, self.data())
            guard.inspect_member(apk, self.data())

    def test_exact_inventory_binding_and_actual_member_both_required(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / guard.SOURCE_PATH
            source.parent.mkdir(parents=True)
            source.write_bytes(self.data())
            apk = root / guard.APK_PATH
            apk.parent.mkdir(parents=True)
            with zipfile.ZipFile(apk, "w") as archive:
                archive.writestr(guard.MEMBER, self.data())
            identity = {"commit": "a" * 40, "tree": "b" * 40}
            inventory = {"schema_version": 1, "scope": "resolved-runtime-classpaths", "source": dict(identity, pins=[]),
                         "outputs": [{"kind": "unsigned-debug-preview", "file": guard.APK_PATH,
                                      "sha256": hashlib.sha256(apk.read_bytes()).hexdigest(), "bytes": apk.stat().st_size}]}
            path = root / "build/inventory/runtime-inventory.json"
            path.parent.mkdir(parents=True)
            path.write_text(json.dumps(inventory))
            with patch.object(guard, "source_identity", return_value=identity):
                self.assertEqual(guard.check(root)["status"], "passed")
                inventory["outputs"][0]["sha256"] = "0" * 64
                path.write_text(json.dumps(inventory))
                with self.assertRaises(guard.PolicyFailure):
                    guard.check(root)
                inventory["source"] = {"commit": "c" * 40, "tree": "b" * 40}
                path.write_text(json.dumps(inventory))
                with self.assertRaises(guard.PolicyFailure):
                    guard.check(root)

    def test_missing_symlink_and_oversized_source_never_supply_defaults(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            with self.assertRaises(guard.PolicyFailure):
                guard.source_bytes(root)
            path = root / guard.SOURCE_PATH
            path.parent.mkdir(parents=True)
            path.write_bytes(b' ' * 4097)
            with self.assertRaises(guard.PolicyFailure):
                guard.source_bytes(root)
            target = root / "target.json"
            target.write_bytes(self.data())
            path.unlink()
            path.symlink_to(target)
            with self.assertRaises(guard.PolicyFailure):
                guard.source_bytes(root)


if __name__ == "__main__":
    unittest.main()
