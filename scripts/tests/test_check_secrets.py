"""Rejection and disclosure boundaries; real scanner fixtures run separately in CI."""

import copy
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


def history_events(repository):
    common = {"sid": "synthetic-history-process", "thread": "main"}
    return [
        {**common, "event": "version", "evt": "4"},
        {**common, "event": "start", "argv": [
            "git", "-C", str(repository), "log", "-p", "-U0", "--full-history",
            "--diff-merges=separate", "--text", "--no-ext-diff", "--no-textconv", "HEAD",
        ]},
        {**common, "event": "exit", "code": 0},
        {**common, "event": "atexit", "code": 0},
    ]


def write_events(trace, rows):
    trace.write_text("".join(json.dumps(row) + "\n" for row in rows), encoding="utf-8")


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
                self.assertEqual(str(private / "history-git-trace.json"), kwargs["env"]["GIT_TRACE2_EVENT"])
                self.assertEqual("1", kwargs["env"]["GIT_TRACE2_EVENT_BRIEF"])
                self.assertEqual("1", kwargs["env"]["GIT_TRACE2_EVENT_NESTING"])
                self.assertEqual("[extend]\nuseDefault = true\n", (private / "built-in-rules.toml").read_text())
                self.assertEqual("", (private / "empty-ignore").read_text())
                (private / "history.json").write_text("[]")
                write_events(Path(kwargs["env"]["GIT_TRACE2_EVENT"]), history_events(Path("repository")))
                return subprocess.CompletedProcess(args, 0, "", "")
            with patch.dict(os.environ, {"GITLEAKS_CONFIG": "unreviewed", "GITLEAKS_CONFIG_TOML": "unreviewed",
                                         "GIT_TRACE2_EVENT": "unreviewed", "GIT_TRACE2_EVENT_BRIEF": "0",
                                         "GIT_TRACE2_EVENT_NESTING": "0"}), \
                    patch.object(check.subprocess, "run", side_effect=run):
                self.assertEqual({"exit_code": 0, "finding_count": 0, "findings": []},
                                 check.scan(Path("scanner"), Path("repository"), private, "history", history=True))

    def test_consistent_clean_or_detected_history_requires_completed_git_child(self):
        for code, payload in ((0, []), (10, [finding()])):
            for completed in (False, True):
                with self.subTest(code=code, completed=completed), tempfile.TemporaryDirectory() as temporary:
                    private = Path(temporary)
                    def run(args, **kwargs):
                        (private / "history.json").write_text(json.dumps(payload))
                        if completed:
                            write_events(Path(kwargs["env"]["GIT_TRACE2_EVENT"]), history_events(Path("repository")))
                        return subprocess.CompletedProcess(args, code, "", "")
                    with patch.object(check.subprocess, "run", side_effect=run):
                        if completed:
                            result = check.scan(Path("scanner"), Path("repository"), private, "history", history=True)
                            self.assertEqual(code, result["exit_code"])
                            self.assertEqual(len(payload), result["finding_count"])
                            self.assertNotIn("PRIVATE_", json.dumps(result))
                        else:
                            with self.assertRaises(check.HistoryCompletionFailure) as raised:
                                check.scan(Path("scanner"), Path("repository"), private, "history", history=True)
                            self.assertEqual(check.HISTORY_COMPLETION_ERROR, str(raised.exception))

    def test_preexisting_file_directory_or_dangling_trace_is_rejected_before_scanner(self):
        for kind in ("file", "directory", "dangling"):
            with self.subTest(kind=kind), tempfile.TemporaryDirectory() as temporary:
                private = Path(temporary)
                trace = private / "history-git-trace.json"
                if kind == "file":
                    write_events(trace, history_events(Path("repository")))
                elif kind == "directory":
                    trace.mkdir()
                else:
                    trace.symlink_to(private / "absent-trace")
                with patch.object(check.subprocess, "run") as run:
                    with self.assertRaisesRegex(check.ScanFailure, "pre-existing Git completion trace"):
                        check.scan(Path("scanner"), Path("repository"), private, "history", history=True)
                    run.assert_not_called()

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


class HistoryCompletionTest(unittest.TestCase):
    def setUp(self):
        self.folder = tempfile.TemporaryDirectory(prefix="history-completion-test-")
        self.addCleanup(self.folder.cleanup)
        self.repository = Path(self.folder.name) / "source"
        self.trace = Path(self.folder.name) / "trace.json"
        self.rows = history_events(self.repository)

    def rejected(self, rows):
        write_events(self.trace, rows)
        with self.assertRaises(check.HistoryCompletionFailure) as raised:
            check.require_history_completion(self.trace, self.repository)
        self.assertEqual(check.HISTORY_COMPLETION_ERROR, str(raised.exception))

    def test_completed_history_with_separate_remote_metadata_failure(self):
        other = copy.deepcopy(self.rows)
        for row in other:
            row["sid"] = "synthetic-remote-metadata-process"
        other[1]["argv"] = ["git", "remote", "get-url", "origin"]
        other[2]["code"] = other[3]["code"] = 2
        write_events(self.trace, other + self.rows)
        check.require_history_completion(self.trace, self.repository)

    def test_missing_failed_signaled_or_out_of_order_completion(self):
        cases = [[], self.rows[1:], self.rows[:-1], self.rows[:2] + self.rows[3:],
                 self.rows[:2] + list(reversed(self.rows[2:]))]
        for field in (2, 3):
            for code in (73, -9, False, 0.0):
                rows = copy.deepcopy(self.rows)
                rows[field]["code"] = code
                cases.append(rows)
        cases.append(self.rows[:2] + [{"sid": self.rows[0]["sid"], "event": "signal", "signo": 9}])
        for rows in cases:
            with self.subTest(events=[row["event"] for row in rows]):
                self.rejected(rows)

    def test_wrong_command_repository_session_format_or_thread_cannot_substitute(self):
        for change in ("arguments", "repository", "scope", "merge-policy", "session", "format", "thread"):
            rows = copy.deepcopy(self.rows)
            if change == "arguments":
                rows[1]["argv"].remove("--text")
            elif change == "repository":
                rows[1]["argv"][2] += "-other"
            elif change == "scope":
                rows[1]["argv"][-1] = "--all"
            elif change == "merge-policy":
                rows[1]["argv"][7] = "--diff-merges=first-parent"
            elif change == "session":
                rows[3]["sid"] = "different-process"
            elif change == "format":
                rows[0]["evt"] = "5"
            else:
                rows[2]["thread"] = "different-thread"
            with self.subTest(change=change):
                self.rejected(rows)

    def test_duplicate_or_post_shutdown_events_are_ambiguous(self):
        other = copy.deepcopy(self.rows)
        for row in other:
            row["sid"] = "duplicate-history-process"
        cases = [self.rows + other, self.rows + self.rows,
                 self.rows[:3] + [self.rows[2]] + self.rows[3:],
                 self.rows + [{"sid": self.rows[0]["sid"], "event": "data"}]]
        for rows in cases:
            with self.subTest(events=[row["event"] for row in rows]):
                self.rejected(rows)

    def test_malformed_truncated_duplicate_key_and_unreadable_trace(self):
        with self.assertRaises(check.HistoryCompletionFailure):
            check.require_history_completion(self.trace, self.repository)
        write_events(self.trace, self.rows)
        valid = self.trace.read_bytes()
        cases = [b"not JSON\n", b"[]\n", b"{}\n", b"\xff\n", valid[:-1],
                 b"[" * 2000 + b"]" * 2000 + b"\n",
                 valid.replace(b'"code": 0', b'"code": 73, "code": 0', 1),
                 valid.replace(b'"code": 0', b'"code": NaN', 1)]
        for number, raw in enumerate(cases):
            with self.subTest(case=number):
                self.trace.write_bytes(raw)
                with self.assertRaises(check.HistoryCompletionFailure) as raised:
                    check.require_history_completion(self.trace, self.repository)
                self.assertEqual(check.HISTORY_COMPLETION_ERROR, str(raised.exception))
        with patch.object(Path, "open", side_effect=PermissionError("PRIVATE_TRACE")):
            with self.assertRaises(check.HistoryCompletionFailure) as raised:
                check.require_history_completion(self.trace, self.repository)
            self.assertEqual(check.HISTORY_COMPLETION_ERROR, str(raised.exception))

    def test_private_receipt_resource_bounds(self):
        write_events(self.trace, self.rows)
        for limit in ("MAX_TRACE_BYTES", "MAX_TRACE_LINE_BYTES"):
            with self.subTest(limit=limit), patch.object(check, limit, 8):
                with self.assertRaises(check.HistoryCompletionFailure):
                    check.require_history_completion(self.trace, self.repository)
        rows = [{"sid": f"synthetic-process-{number}", "event": "version"} for number in range(65)]
        self.rejected(rows + self.rows)
        self.rejected(self.rows[:2] + [self.rows[2]] * 7 + self.rows[3:])


if __name__ == "__main__":
    unittest.main()
