"""Rejection and disclosure boundaries; real scanner fixtures run separately in CI."""

import hashlib
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("secret_check", ROOT / "scripts/check_secrets.py")
check = importlib.util.module_from_spec(spec)
spec.loader.exec_module(check)


def finding():
    return {"RuleID": "github-pat", "File": "example.txt", "StartLine": 3, "Commit": "a" * 40,
            "Secret": "PRIVATE_MATCH", "Match": "PRIVATE_LINE", "Line": "PRIVATE_CONTEXT",
            "Author": "PRIVATE_AUTHOR", "Email": "PRIVATE_EMAIL", "Message": "PRIVATE_MESSAGE"}


class SecretCheckTest(unittest.TestCase):
    def test_unverified_or_oversized_tool_bytes_are_rejected(self):
        value = b"verified tool fixture"
        digest = hashlib.sha256(value).hexdigest()
        self.assertEqual(value, check.verified_bytes(value, digest, len(value)))
        for body, expected, limit in ((value + b"changed", digest, 100),
                                      (value, "0" * 64, 100), (value, digest, len(value) - 1)):
            with self.subTest(limit=limit), self.assertRaises(check.ScanFailure):
                check.verified_bytes(body, expected, limit)

    def test_only_finding_location_and_rule_leave_private_report(self):
        result = check.finding_metadata([finding()], history=True)
        self.assertEqual([{"rule_id": "github-pat", "file": "example.txt", "line": 3,
                           "commit": "a" * 40}], result)
        self.assertNotIn("PRIVATE_", json.dumps(result))

    def test_malformed_finding_identity_is_rejected(self):
        invalid = [None, {}, [None]]
        for key, value in (("RuleID", ""), ("RuleID", "arbitrary rule text"), ("File", ""),
                           ("StartLine", True), ("StartLine", 0), ("Commit", "unknown")):
            changed = finding()
            changed[key] = value
            invalid.append([changed])
        for value in invalid:
            with self.subTest(value=type(value).__name__), self.assertRaises(check.ScanFailure):
                check.finding_metadata(value, history=True)

    def test_missing_error_or_inconsistent_reports_cannot_pass(self):
        for code, payload in ((0, None), (2, []), (0, [finding()]), (10, [])):
            with self.subTest(code=code, report=payload is not None), tempfile.TemporaryDirectory() as temporary:
                private = Path(temporary)
                def run(*args, **kwargs):
                    if payload is not None:
                        (private / "history.json").write_text(json.dumps(payload))
                    return subprocess.CompletedProcess([], code, "", "")
                with patch.object(check.subprocess, "run", side_effect=run):
                    with self.assertRaises(check.ScanFailure) as raised:
                        check.scan(Path("scanner"), Path("repository"), private, "history", history=True)
                self.assertNotIn("PRIVATE_", str(raised.exception))

    def test_successful_exit_and_report_cannot_hide_source_errors_or_disclose_streams(self):
        for code, stdout, stderr in ((0, "PRIVATE_STDOUT", ""), (0, "", "PRIVATE_STDERR"),
                                     (10, "", "PRIVATE_STDERR"), (0, "", "\n")):
            with self.subTest(code=code), tempfile.TemporaryDirectory() as temporary:
                private = Path(temporary)
                def run(*args, **kwargs):
                    (private / "history.json").write_text(json.dumps([finding()] if code == 10 else []))
                    return subprocess.CompletedProcess([], code, stdout, stderr)
                with patch.object(check.subprocess, "run", side_effect=run):
                    with self.assertRaises(check.ScanFailure) as raised:
                        check.scan(Path("scanner"), Path("repository"), private, "history", history=True)
                self.assertEqual(check.SCANNER_DIAGNOSTIC_ERROR, str(raised.exception))
                self.assertNotIn("PRIVATE_", str(raised.exception))

    def test_every_source_ignore_entry_or_unreadable_metadata_fails_before_scanner(self):
        for kind in ("file", "directory", "symlink", "dangling"):
            with self.subTest(kind=kind), tempfile.TemporaryDirectory() as temporary:
                private = Path(temporary)
                target = private / "source"
                target.mkdir()
                ignore = target / ".gitleaksignore"
                if kind == "file":
                    ignore.write_text("example.txt:github-pat:3\n")
                elif kind == "directory":
                    ignore.mkdir()
                else:
                    destination = private / "ignore-target"
                    if kind == "symlink":
                        destination.write_text("")
                    ignore.symlink_to(destination)
                with patch.object(check.subprocess, "run") as run:
                    with self.assertRaises(check.ScanFailure) as raised:
                        check.scan(Path("scanner"), target, private, "history", history=True)
                    self.assertEqual(check.SOURCE_IGNORE_ERROR, str(raised.exception))
                    run.assert_not_called()
        with patch.object(Path, "lstat", side_effect=PermissionError("PRIVATE_METADATA")), \
                patch.object(check.subprocess, "run") as run:
            with self.assertRaisesRegex(check.ScanFailure, "metadata could not be inspected") as raised:
                check.scan(Path("scanner"), Path("repository"), Path("private"), "history", history=True)
            self.assertNotIn("PRIVATE_", str(raised.exception))
            run.assert_not_called()

    def test_stale_and_oversized_reports_are_rejected(self):
        with tempfile.TemporaryDirectory() as temporary:
            private = Path(temporary)
            (private / "history.json").write_text("[]")
            with patch.object(check.subprocess, "run") as run:
                with self.assertRaisesRegex(check.ScanFailure, "pre-existing"):
                    check.scan(Path("scanner"), Path("repository"), private, "history", history=True)
                run.assert_not_called()
        with tempfile.TemporaryDirectory() as temporary:
            private = Path(temporary)
            def run(*args, **kwargs):
                (private / "history.json").write_bytes(b" " * (check.MAX_REPORT_BYTES + 1))
                return subprocess.CompletedProcess([], 0, "", "")
            with patch.object(check.subprocess, "run", side_effect=run):
                with self.assertRaisesRegex(check.ScanFailure, "byte limit"):
                    check.scan(Path("scanner"), Path("repository"), private, "history", history=True)

    def test_scan_uses_builtin_rules_and_cannot_enable_repo_or_environment_ignores(self):
        with tempfile.TemporaryDirectory() as temporary:
            private = Path(temporary)
            def run(args, **kwargs):
                self.assertIn("--log-opts=--full-history --diff-merges=separate --text --no-ext-diff --no-textconv HEAD", args)
                self.assertIn("--redact=100", args)
                self.assertIn("--log-level=error", args)
                self.assertIn("--ignore-gitleaks-allow", args)
                self.assertEqual(str(private / "built-in-rules.toml"), args[args.index("--config") + 1])
                self.assertEqual(str(private / "empty-ignore"), args[args.index("--gitleaks-ignore-path") + 1])
                self.assertNotIn("GITLEAKS_CONFIG", kwargs["env"])
                self.assertNotIn("GITLEAKS_CONFIG_TOML", kwargs["env"])
                self.assertEqual("[extend]\nuseDefault = true\n", (private / "built-in-rules.toml").read_text())
                self.assertEqual("", (private / "empty-ignore").read_text())
                (private / "history.json").write_text("[]")
                return subprocess.CompletedProcess(args, 0, "", "")
            with patch.dict(os.environ, {"GITLEAKS_CONFIG": "unreviewed", "GITLEAKS_CONFIG_TOML": "unreviewed"}), \
                    patch.object(check.subprocess, "run", side_effect=run):
                self.assertEqual({"exit_code": 0, "finding_count": 0, "findings": []},
                                 check.scan(Path("scanner"), Path("repository"), private, "history", history=True))

    def test_shallow_dirty_or_mismatched_workflow_source_is_rejected(self):
        baseline = {("rev-parse", "--show-toplevel"): str(check.ROOT),
                    ("rev-parse", "--is-shallow-repository"): "false",
                    ("status", "--porcelain", "--untracked-files=no"): "",
                    ("hash-object", str(Path(check.__file__))): "b" * 40,
                    ("rev-parse", "HEAD:scripts/check_secrets.py"): "b" * 40,
                    ("rev-parse", "HEAD"): "a" * 40}
        for key, value in ((("rev-parse", "--is-shallow-repository"), "true"),
                           (("status", "--porcelain", "--untracked-files=no"), " M file"),
                           (("rev-parse", "HEAD:scripts/check_secrets.py"), "c" * 40),
                           (("rev-parse", "HEAD"), "d" * 40)):
            inputs = dict(baseline)
            inputs[key] = value
            with self.subTest(key=key), patch.object(check.Path, "cwd", return_value=check.ROOT), \
                    patch.dict(os.environ, {"GITHUB_SHA": "a" * 40}), \
                    patch.object(check, "git", side_effect=lambda *args: inputs[args]):
                with self.assertRaises(check.ScanFailure):
                    check.inspect_source()


if __name__ == "__main__":
    unittest.main()
