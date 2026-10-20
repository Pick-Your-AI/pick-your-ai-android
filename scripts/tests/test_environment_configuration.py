"""Small synthetic ZIP/receipt controls, not actual APK or Android execution."""
from contextlib import redirect_stdout
import hashlib
import io
import json
import os
from pathlib import Path
import subprocess
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


class EnvironmentSourceBytesTest(unittest.TestCase):
    def check_hidden_edit(self, flag, during_inspection):
        # Source identity and the synthetic ZIP/profile checks remain real.
        # All commits and index hints belong only to this remote-free fixture.
        fixture_environment = {key: value for key, value in os.environ.items() if not key.startswith("GIT_")}
        fixture_environment.update({"GIT_CONFIG_NOSYSTEM": "1", "GIT_CONFIG_GLOBAL": os.devnull,
                                    "GIT_TERMINAL_PROMPT": "0"})
        with patch.dict(os.environ, fixture_environment, clear=True), tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            def git(*arguments):
                return subprocess.run(["git", "--no-replace-objects", *arguments], cwd=root, check=True,
                                      stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=30).stdout.decode().strip()
            git("init", "--quiet", "--template=")
            (root / ".gitignore").write_text("build/\n**/build/\n")
            tracked = root / "tracked.txt"
            original = b"original synthetic source\n"
            tracked.write_bytes(original)
            profile = json.dumps(guard.EXPECTED).encode()
            source = root / guard.SOURCE_PATH
            source.parent.mkdir(parents=True)
            source.write_bytes(profile)
            git("add", "--all")
            git("-c", "user.name=Inspection source fixture", "-c", "user.email=inspection@example.invalid",
                "-c", "commit.gpgsign=false", "-c", "core.hooksPath=" + os.devnull,
                "commit", "--quiet", "--no-verify", "-m", "Disposable source baseline")
            identity = guard.source_identity(root)
            apk = root / guard.APK_PATH
            apk.parent.mkdir(parents=True)
            with zipfile.ZipFile(apk, "w") as package:
                package.writestr(guard.MEMBER, profile)
            apk_hash = guard.sha256(apk)
            inventory = root / "build/inventory/runtime-inventory.json"
            inventory.parent.mkdir(parents=True)
            inventory.write_text(json.dumps({"schema_version": 1, "scope": "resolved-runtime-classpaths",
                "source": identity, "outputs": [{"kind": "unsigned-debug-preview", "file": guard.APK_PATH,
                "sha256": apk_hash, "bytes": apk.stat().st_size}]}))
            inventory_hash = guard.sha256(inventory)
            git("update-index", flag, "--", "tracked.txt")
            inject = False
            def change_source():
                changed = b"private! synthetic source\n"
                self.assertEqual(len(original), len(changed))
                tracked.write_bytes(changed)
                self.assertEqual("", git("status", "--porcelain", "--untracked-files=all"))
            inspect_member = guard.inspect_member
            def inspect(*arguments):
                inspect_member(*arguments)
                if inject:
                    change_source()
            with patch.object(guard, "inspect_member", side_effect=inspect) as inspected:
                self.assertEqual("passed", guard.check(root)["status"])
                inspected.reset_mock()
                if during_inspection:
                    inject = True
                else:
                    change_source()
                receipt = root / "build/inventory/environment-configuration.json"
                receipt.write_text('{"status":"passed","source":"old"}\n')
                with patch.object(guard, "ROOT", root), patch.object(sys, "argv", ["inspection"]), \
                     redirect_stdout(io.StringIO()) as messages:
                    result = guard.main()
                new_receipt = receipt.exists()
                failure = None
                try:
                    guard.source_identity(root)
                except guard.PolicyFailure as error:
                    failure = str(error)
                negative_inspections = inspected.call_count
                self.assertEqual(apk_hash, guard.sha256(apk))
                self.assertEqual(inventory_hash, guard.sha256(inventory))
                inject = False
                tracked.write_bytes(original)
                self.assertEqual("", git("status", "--porcelain", "--untracked-files=all"))
                self.assertEqual("passed", guard.check(root)["status"])
                self.assertEqual(identity, guard.source_identity(root))
                self.assertEqual(apk_hash, guard.sha256(apk))
                self.assertEqual(inventory_hash, guard.sha256(inventory))
                self.assertEqual(("SOURCE_CHANGED", 1, False), (failure, result, new_receipt))
                self.assertNotIn("private!", messages.getvalue())
                self.assertEqual(1 if during_inspection else 0, negative_inspections)

    def test_assume_unchanged_source_edit_before_environment_inspection_rejects(self):
        self.check_hidden_edit("--assume-unchanged", False)

    def test_skip_worktree_source_edit_before_environment_inspection_rejects(self):
        self.check_hidden_edit("--skip-worktree", False)

    def test_assume_unchanged_source_edit_during_environment_inspection_rejects(self):
        self.check_hidden_edit("--assume-unchanged", True)

    def test_skip_worktree_source_edit_during_environment_inspection_rejects(self):
        self.check_hidden_edit("--skip-worktree", True)


if __name__ == "__main__":
    unittest.main()
