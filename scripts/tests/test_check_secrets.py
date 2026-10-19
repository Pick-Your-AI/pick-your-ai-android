"""Rejection and disclosure boundaries; real scanner fixtures run separately in CI."""

import copy
import contextlib
import hashlib
import importlib.util
import io
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
                self.assertEqual("1", kwargs["env"]["GIT_NO_REPLACE_OBJECTS"])
                self.assertEqual("[extend]\nuseDefault = true\n", (private / "built-in-rules.toml").read_text())
                self.assertEqual("", (private / "empty-ignore").read_text())
                (private / "history.json").write_text("[]")
                write_events(Path(kwargs["env"]["GIT_TRACE2_EVENT"]), history_events(Path("repository")))
                return subprocess.CompletedProcess(args, 0, "", "")
            with patch.dict(os.environ, {"GITLEAKS_CONFIG": "unreviewed", "GITLEAKS_CONFIG_TOML": "unreviewed",
                                         "GIT_TRACE2_EVENT": "unreviewed", "GIT_TRACE2_EVENT_BRIEF": "0",
                                         "GIT_TRACE2_EVENT_NESTING": "0", "GIT_NO_REPLACE_OBJECTS": "0"}), \
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

    def test_source_metadata_uses_literal_objects_with_replacement_refs(self):
        with tempfile.TemporaryDirectory() as temporary:
            repository = Path(temporary)
            env = dict(os.environ, GIT_AUTHOR_NAME="Synthetic Source Fixture",
                       GIT_AUTHOR_EMAIL="fixture@example.invalid", GIT_COMMITTER_NAME="Synthetic Source Fixture",
                       GIT_COMMITTER_EMAIL="fixture@example.invalid")
            def fixture_git(*args, input=None):
                result = subprocess.run(["git", *args], cwd=repository, env=env, input=input,
                                        capture_output=True, text=True, timeout=10)
                self.assertEqual(0, result.returncode, "Temporary source fixture construction failed")
                return result.stdout.strip()
            fixture_git("init", "--bare", "--quiet")
            empty = fixture_git("mktree", input="")
            first = fixture_git("commit-tree", empty, "-m", "Synthetic initial source")
            blob = fixture_git("hash-object", "-w", "--stdin", input="Synthetic source metadata control.\n")
            tree = fixture_git("mktree", input="100644 blob " + blob + "\tfixture.txt\n")
            head = fixture_git("commit-tree", tree, "-p", first, "-m", "Synthetic current source")
            fixture_git("update-ref", "refs/heads/main", head)
            fixture_git("symbolic-ref", "HEAD", "refs/heads/main")
            fixture_git("replace", head, first)
            with patch.object(check, "ROOT", repository):
                self.assertEqual(head, check.git("rev-parse", "HEAD"))
                self.assertEqual(tree, check.git("rev-parse", "HEAD^{tree}"))
                self.assertEqual("2", check.git("rev-list", "--count", "HEAD"))


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


class PublicChecksumDispositionTest(unittest.TestCase):
    def row(self, commit="a" * 40):
        return {"rule_id": "generic-api-key", "file": check.CHECKSUM_PROOF[0][0],
                "line": 5, "commit": commit}

    def result(self, rows):
        return {"exit_code": 10 if rows else 0, "finding_count": len(rows), "findings": rows}

    def test_exact_review_keeps_raw_exit_count_findings_and_actual_commit(self):
        raw = self.result([self.row()])
        before = copy.deepcopy(raw)
        with patch.object(check, "verify_checksum_finding") as verify:
            disposition = check.classify_reviewed_findings(Path("source"), "b" * 40, raw)
        verify.assert_called_once_with(Path("source"), "a" * 40, "b" * 40)
        self.assertEqual(before, raw)
        self.assertEqual(1, disposition["reviewed_count"])
        self.assertEqual(0, disposition["unresolved_count"])
        self.assertEqual([{**self.row(), "disposition": check.CHECKSUM_DISPOSITION,
                           "file_blob": check.CHECKSUM_PROOF[0][1],
                           "file_sha256": check.CHECKSUM_PROOF[0][3]}], disposition["reviewed_findings"])

    def test_other_rule_path_or_line_remains_unresolved_without_evidence_lookup(self):
        for key, value in (("rule_id", "github-pat"), ("file", "other.json"), ("line", 6)):
            row = {**self.row(), key: value}
            with self.subTest(key=key), patch.object(check, "verify_checksum_finding") as verify:
                disposition = check.classify_reviewed_findings(Path("source"), "a" * 40, self.result([row]))
            verify.assert_not_called()
            self.assertEqual(0, disposition["reviewed_count"])
            self.assertEqual([row], disposition["unresolved_findings"])

    def test_repeated_public_findings_preserve_every_parent_diff_observation(self):
        # --diff-merges=separate can report one literal public line once per
        # parent diff. Keep all occurrences, including their original order.
        rows = [self.row()] * 3 + [self.row("b" * 40), self.row("c" * 40)]
        raw = self.result(copy.deepcopy(rows))
        before = copy.deepcopy(raw)
        with patch.object(check, "verify_checksum_finding") as verify:
            disposition = check.classify_reviewed_findings(Path("source"), "d" * 40, raw)
        self.assertEqual(before, raw)
        self.assertEqual(5, disposition["reviewed_count"])
        self.assertEqual(0, disposition["unresolved_count"])
        self.assertEqual(rows, [
            {key: finding[key] for key in ("rule_id", "file", "line", "commit")}
            for finding in disposition["reviewed_findings"]
        ])
        self.assertEqual(["a" * 40, "b" * 40, "c" * 40],
                         [call.args[1] for call in verify.call_args_list])
        self.assertTrue(all(call.args[0] == Path("source") and call.args[2] == "d" * 40
                            for call in verify.call_args_list))

    def test_repeated_public_findings_cannot_bypass_any_commit_evidence(self):
        rows = [self.row()] * 3 + [self.row("b" * 40), self.row("c" * 40)]
        for failed_commit in ("a" * 40, "b" * 40, "c" * 40):
            def verify(target, commit, source_commit):
                if commit == failed_commit:
                    raise check.ScanFailure(check.CHECKSUM_EVIDENCE_ERROR)
            with self.subTest(commit=failed_commit), \
                    patch.object(check, "verify_checksum_finding", side_effect=verify), \
                    self.assertRaisesRegex(check.ScanFailure, check.CHECKSUM_EVIDENCE_ERROR):
                check.classify_reviewed_findings(Path("source"), "d" * 40, self.result(rows))

    def test_mixed_findings_preserve_the_unresolved_finding(self):
        unknown = {**self.row(), "file": "unreviewed.txt", "rule_id": "github-pat"}
        raw = self.result([self.row(), unknown])
        with patch.object(check, "verify_checksum_finding"):
            disposition = check.classify_reviewed_findings(Path("source"), "a" * 40, raw)
        self.assertEqual(2, raw["finding_count"])
        self.assertEqual(1, disposition["reviewed_count"])
        self.assertEqual(1, disposition["unresolved_count"])
        self.assertEqual([unknown], disposition["unresolved_findings"])

    def test_malformed_duplicate_or_inconsistent_disposition_input_fails(self):
        valid = self.result([self.row()])
        unreviewed = {**self.row(), "file": "unreviewed.txt"}
        invalid = [None, {}, {**valid, "extra": "PRIVATE_METADATA"},
                   {**valid, "exit_code": 0}, {**valid, "exit_code": True},
                   {**valid, "finding_count": 0}, {**valid, "finding_count": True},
                   self.result([unreviewed, unreviewed])]
        for key, value in (("line", True), ("line", 0), ("commit", "unknown"),
                           ("rule_id", "unstructured private text"), ("file", "")):
            invalid.append(self.result([{**self.row(), key: value}]))
        invalid.append(self.result([{**self.row(), "extra": "PRIVATE_METADATA"}]))
        for value in invalid:
            with self.subTest(value=type(value).__name__), patch.object(check, "verify_checksum_finding"), \
                    self.assertRaises(check.ScanFailure) as raised:
                check.classify_reviewed_findings(Path("source"), "a" * 40, value)
            self.assertNotIn("PRIVATE_", str(raised.exception))
        with self.assertRaises(check.ScanFailure):
            check.classify_reviewed_findings(Path("source"), "HEAD", self.result([]))

    def test_blob_mode_identity_size_and_complete_bytes_are_bound(self):
        raw = b"public source bytes\n"
        proof = ("public.txt", "b" * 40, len(raw), hashlib.sha256(raw).hexdigest())
        entry = f"100644 blob {proof[1]}\t{proof[0]}\0".encode()
        with patch.object(check, "checksum_git", side_effect=[entry, f"{len(raw)}\n".encode(), raw]) as read:
            self.assertEqual(raw, check.reviewed_checksum_blob(Path("source"), "a" * 40, proof))
        self.assertEqual([
            ((Path("source"), "ls-tree", "-z", "a" * 40, "--", "public.txt"),),
            ((Path("source"), "cat-file", "-s", "b" * 40),),
            ((Path("source"), "cat-file", "blob", "b" * 40),),
        ], [(call.args,) for call in read.call_args_list])
        variants = [
            [entry.replace(b"100644", b"100755")], [entry.replace(b"100644", b"120000")],
            [entry.replace(b"blob", b"tree")], [entry.replace(b"b" * 40, b"c" * 40)],
            [entry.replace(b"public.txt", b"different.txt")], [entry + entry],
            [entry, b"999\n"], [entry, f"{len(raw)}\n".encode(), raw + b"x"],
            [entry, f"{len(raw)}\n".encode(), b"P" + raw[1:]],
        ]
        for values in variants:
            with self.subTest(values=len(values)), patch.object(check, "checksum_git", side_effect=values), \
                    self.assertRaises(check.ScanFailure) as raised:
                check.reviewed_checksum_blob(Path("source"), "a" * 40, proof)
            self.assertEqual(check.CHECKSUM_EVIDENCE_ERROR, str(raised.exception))

    def test_git_evidence_disables_replacements_and_rejects_errors_or_oversize(self):
        target = Path("source")
        with patch.object(check.subprocess, "run", return_value=subprocess.CompletedProcess([], 0, b"data", b"")) as run:
            self.assertEqual(b"data", check.checksum_git(target, "cat-file", "blob", "a" * 40))
        self.assertEqual(["git", "--no-replace-objects", "-C", "source", "cat-file", "blob", "a" * 40], run.call_args.args[0])
        self.assertEqual({"capture_output": True, "timeout": 5}, run.call_args.kwargs)
        for result in (subprocess.CompletedProcess([], 1, b"", b""),
                       subprocess.CompletedProcess([], 0, b"", b"PRIVATE_ERROR"),
                       subprocess.CompletedProcess([], 0, b"x" * 65537, b"")):
            with patch.object(check.subprocess, "run", return_value=result), self.assertRaises(check.ScanFailure) as raised:
                check.checksum_git(target, "cat-file", "blob", "a" * 40)
            self.assertEqual(check.CHECKSUM_EVIDENCE_ERROR, str(raised.exception))
        for error in (OSError("PRIVATE_PATH"), subprocess.TimeoutExpired("PRIVATE_COMMAND", 5)):
            with patch.object(check.subprocess, "run", side_effect=error), self.assertRaises(check.ScanFailure) as raised:
                check.checksum_git(target, "cat-file", "blob", "a" * 40)
            self.assertEqual(check.CHECKSUM_EVIDENCE_ERROR, str(raised.exception))

    def test_each_actual_commit_binds_all_frozen_proof_inputs(self):
        schema = (ROOT / check.CHECKSUM_PROOF[1][0]).read_bytes()
        manifest = (ROOT / check.CHECKSUM_PROOF[2][0]).read_bytes()
        upstream = (ROOT / check.CHECKSUM_PROOF[3][0]).read_bytes()
        data = {"case_format": "shape", "contract_version": "shape", "upstream_revision": "shape",
                "openapi_sha256": hashlib.sha256(schema).hexdigest(), "cases": []}
        fixture = (json.dumps(data, indent=2) + "\n").encode()
        with patch.object(check, "checksum_git", side_effect=[b"b" * 40 + b"\n", b"commit\n", b""]) as git, \
                patch.object(check, "reviewed_checksum_blob", side_effect=[fixture, schema, manifest, upstream]) as read:
            check.verify_checksum_finding(Path("source"), "a" * 40, "b" * 40)
        self.assertEqual([proof for proof in check.CHECKSUM_PROOF], [call.args[2] for call in read.call_args_list])
        self.assertTrue(all(call.args[:2] == (Path("source"), "a" * 40) for call in read.call_args_list))
        self.assertEqual((Path("source"), "merge-base", "--is-ancestor", "a" * 40, "b" * 40), git.call_args.args)
        for wrong in ("head", "type", "ancestry"):
            replies = [b"b" * 40 + b"\n", b"commit\n", b""]
            replies[{"head": 0, "type": 1, "ancestry": 2}[wrong]] = b"different\n"
            with self.subTest(wrong=wrong), patch.object(check, "checksum_git", side_effect=replies), \
                    patch.object(check, "reviewed_checksum_blob") as read, self.assertRaises(check.ScanFailure):
                check.verify_checksum_finding(Path("source"), "a" * 40, "b" * 40)
            read.assert_not_called()
        for index in range(4):
            with self.subTest(proof=index), patch.object(check, "checksum_git", side_effect=[b"b" * 40 + b"\n", b"commit\n", b""]), \
                    patch.object(check, "reviewed_checksum_blob", side_effect=[fixture, schema, manifest, upstream][:index] + [check.ScanFailure(check.CHECKSUM_EVIDENCE_ERROR)]), \
                    self.assertRaises(check.ScanFailure):
                check.verify_checksum_finding(Path("source"), "a" * 40, "b" * 40)

    def test_public_value_or_frozen_digest_relationship_cannot_change(self):
        schema = (ROOT / check.CHECKSUM_PROOF[1][0]).read_bytes()
        upstream = (ROOT / check.CHECKSUM_PROOF[3][0]).read_bytes()
        data = {"case_format": "shape", "contract_version": "shape", "upstream_revision": "shape",
                "openapi_sha256": hashlib.sha256(schema).hexdigest(), "cases": []}
        fixture = (json.dumps(data, indent=2) + "\n").encode()
        for inputs in ([fixture.replace(b"6a08d723", b"7a08d723"), schema, b"pinned", upstream],
                       [fixture, schema + b"\n", b"pinned", upstream],
                       [fixture, schema, b"pinned", upstream + upstream]):
            with patch.object(check, "checksum_git", side_effect=[b"a" * 40 + b"\n", b"commit\n", b""]), \
                    patch.object(check, "reviewed_checksum_blob", side_effect=inputs), self.assertRaises(check.ScanFailure):
                check.verify_checksum_finding(Path("source"), "a" * 40, "a" * 40)

    def test_reviewed_commit_work_is_bounded(self):
        raw = self.result([self.row(f"{index:040x}") for index in range(check.MAX_REVIEWED_COMMITS + 1)])
        with patch.object(check, "verify_checksum_finding") as verify, self.assertRaisesRegex(check.ScanFailure, "commit limit"):
            check.classify_reviewed_findings(Path("source"), "a" * 40, raw)
        self.assertEqual(check.MAX_REVIEWED_COMMITS, verify.call_count)

    def test_entrypoint_never_calls_reviewed_or_mixed_findings_zero_raw(self):
        other = {**self.row(), "file": "other.txt", "rule_id": "github-pat"}
        cases = [([], 0, "passed", 0), ([self.row()], 0, "passed_with_reviewed_findings", 0),
                 ([self.row()] * 3, 0, "passed_with_reviewed_findings", 0),
                 ([self.row(), other], 1, "failed", 1),
                 ([self.row()] * 3 + [other], 1, "failed", 1), ([other], 1, "failed", 1)]
        for rows, exit_code, status, unresolved in cases:
            with self.subTest(status=status, rows=len(rows)), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                raw = self.result(rows)
                with patch.object(check, "ROOT", root), patch.object(check.platform, "system", return_value="Linux"), \
                        patch.object(check.platform, "machine", return_value="x86_64"), \
                        patch.object(check, "inspect_source", return_value={"commit": "a" * 40}), \
                        patch.object(check, "install_scanner", return_value=Path("scanner")), \
                        patch.object(check, "verify_detection_and_removal", return_value={}), \
                        patch.object(check, "scan", return_value=raw), patch.object(check, "verify_checksum_finding"), \
                        contextlib.redirect_stdout(io.StringIO()):
                    self.assertEqual(exit_code, check.main())
                receipt = json.loads((root / "build/security/secret-scan.json").read_text())
                self.assertEqual(status, receipt["status"])
                self.assertEqual(raw, receipt["scan"])
                self.assertEqual(unresolved, receipt["triage"]["unresolved_count"])
                self.assertEqual(len(rows), receipt["triage"]["reviewed_count"] + receipt["triage"]["unresolved_count"])


class SecretReceiptBoundaryTest(unittest.TestCase):
    """Real output entries with all scanner, process and network work controlled."""

    SENTINEL = b"SYNTHETIC retained receipt\n"
    PEER = b"SYNTHETIC unrelated peer\n"

    def invoke(self, root, *, inspect=None, raw=None, triage=None):
        captured = io.StringIO()
        with contextlib.ExitStack() as stack:
            stack.enter_context(patch.object(check, "ROOT", root))
            stack.enter_context(patch.object(check.platform, "system", return_value="Linux"))
            stack.enter_context(patch.object(check.platform, "machine", return_value="x86_64"))
            source = stack.enter_context(patch.object(check, "inspect_source", side_effect=inspect,
                                                     return_value={"commit": "a" * 40, "tree": "b" * 40}))
            install = stack.enter_context(patch.object(check, "install_scanner", return_value=Path("synthetic-scanner")))
            stack.enter_context(patch.object(check, "verify_detection_and_removal", return_value={"synthetic": True}))
            stack.enter_context(patch.object(check, "scan", return_value=raw or {
                "exit_code": 0, "finding_count": 0, "findings": []}))
            stack.enter_context(patch.object(check, "classify_reviewed_findings", return_value=triage or {
                "reviewed_count": 0, "unresolved_count": 0, "reviewed": [], "unresolved": []}))
            process = stack.enter_context(patch.object(check.subprocess, "Popen", side_effect=AssertionError("process forbidden")))
            network = stack.enter_context(patch.object(check.urllib.request, "urlopen", side_effect=AssertionError("network forbidden")))
            stack.enter_context(contextlib.redirect_stdout(captured))
            result, failure = None, None
            try:
                result = check.main()
            except Exception as error:
                failure = type(error).__name__
        self.assertEqual(0, process.call_count)
        self.assertEqual(0, network.call_count)
        return result, failure, source.call_count, install.call_count, captured.getvalue()

    def rejected_without_inspection(self, root):
        result, failure, source_calls, installs, output = self.invoke(root)
        self.assertEqual((1, None, 0, 0), (result, failure, source_calls, installs))
        self.assertNotIn("PRIVATE_", output)

    def test_ancestor_aliases_preserve_external_receipt_before_inspection(self):
        for component in ("root", "build", "security"):
            with self.subTest(component=component), tempfile.TemporaryDirectory() as temporary:
                base = Path(temporary)
                root, foreign = base / "root", base / "foreign"
                foreign.mkdir()
                if component == "root":
                    root.symlink_to(foreign, target_is_directory=True)
                    target = foreign / "build/security/secret-scan.json"
                else:
                    root.mkdir()
                    if component == "build":
                        (root / "build").symlink_to(foreign, target_is_directory=True)
                        target = foreign / "security/secret-scan.json"
                    else:
                        (root / "build").mkdir()
                        (root / "build/security").symlink_to(foreign, target_is_directory=True)
                        target = foreign / "secret-scan.json"
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(self.SENTINEL)
                peer = target.parent / "peer.txt"
                peer.write_bytes(self.PEER)
                before = target.stat()
                self.rejected_without_inspection(root)
                self.assertEqual(self.SENTINEL, target.read_bytes())
                self.assertEqual(self.PEER, peer.read_bytes())
                self.assertEqual((before.st_ino, before.st_nlink), (target.stat().st_ino, target.stat().st_nlink))

    def test_invalid_directory_entries_and_unreadable_metadata_fail_closed(self):
        for component in ("missing-root", "root-file", "build-file", "security-file", "unreadable"):
            with self.subTest(component=component), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary) / "root"
                if component == "missing-root":
                    self.rejected_without_inspection(root)
                    self.assertFalse(root.exists())
                    continue
                if component == "root-file":
                    root.write_bytes(self.SENTINEL)
                    self.rejected_without_inspection(root)
                    self.assertEqual(self.SENTINEL, root.read_bytes())
                    continue
                root.mkdir()
                target = root / ("build" if component == "build-file" else "build/security")
                target.parent.mkdir(parents=True, exist_ok=True)
                if component != "unreadable":
                    target.write_bytes(self.SENTINEL)
                    self.rejected_without_inspection(root)
                    self.assertEqual(self.SENTINEL, target.read_bytes())
                else:
                    target.mkdir()
                    receipt = target / "secret-scan.json"
                    receipt.write_bytes(self.SENTINEL)
                    with patch.object(Path, "lstat", side_effect=PermissionError("PRIVATE_METADATA")):
                        self.rejected_without_inspection(root)
                    self.assertEqual(self.SENTINEL, receipt.read_bytes())

    def test_unsafe_final_entries_preserve_targets_and_peers_without_opening(self):
        for kind in ("symlink", "dangling", "hardlink", "directory", "fifo"):
            with self.subTest(kind=kind), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary) / "root"
                folder = root / "build/security"
                folder.mkdir(parents=True)
                receipt = folder / "secret-scan.json"
                foreign = Path(temporary) / "foreign.json"
                if kind in ("symlink", "hardlink"):
                    foreign.write_bytes(self.SENTINEL)
                if kind in ("symlink", "dangling"):
                    receipt.symlink_to(foreign)
                elif kind == "hardlink":
                    os.link(foreign, receipt)
                elif kind == "directory":
                    receipt.mkdir()
                    (receipt / "retained.txt").write_bytes(self.SENTINEL)
                else:
                    os.mkfifo(receipt)
                peer = folder / "peer.txt"
                peer.write_bytes(self.PEER)
                before = receipt.lstat()
                # A future accidental FIFO open must fail immediately, not block this test.
                original_open = Path.open
                def bounded_open(path, *args, **kwargs):
                    if kind == "fifo" and path == receipt:
                        raise AssertionError("unsafe FIFO would have been opened")
                    return original_open(path, *args, **kwargs)
                with patch.object(Path, "open", bounded_open):
                    self.rejected_without_inspection(root)
                self.assertEqual((before.st_mode, before.st_ino, before.st_nlink),
                                 (receipt.lstat().st_mode, receipt.lstat().st_ino, receipt.lstat().st_nlink))
                self.assertEqual(self.PEER, peer.read_bytes())
                if kind in ("symlink", "hardlink"):
                    self.assertEqual(self.SENTINEL, foreign.read_bytes())
                elif kind == "dangling":
                    self.assertFalse(foreign.exists())
                elif kind == "directory":
                    self.assertEqual(self.SENTINEL, (receipt / "retained.txt").read_bytes())

    def test_ordinary_stale_success_retires_before_inspection_and_failed_results_remain_failed(self):
        for outcome in ("clean", "controlled-failure", "unexpected-failure"):
            with self.subTest(outcome=outcome), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                receipt = root / "build/security/secret-scan.json"
                receipt.parent.mkdir(parents=True)
                receipt.write_text('{"status":"passed","source":"SYNTHETIC previous"}\n')
                peer = receipt.parent / "peer.txt"
                peer.write_bytes(self.PEER)
                observed = []
                def inspect():
                    observed.append(receipt.exists())
                    if outcome == "controlled-failure":
                        raise check.ScanFailure("controlled source failure")
                    if outcome == "unexpected-failure":
                        raise ValueError("PRIVATE_SOURCE_DETAILS")
                    return {"commit": "a" * 40, "tree": "b" * 40}
                result, failure, source_calls, installs, output = self.invoke(root, inspect=inspect)
                self.assertEqual([False], observed)
                self.assertEqual((0 if outcome == "clean" else 1, None, 1), (result, failure, source_calls))
                self.assertEqual(self.PEER, peer.read_bytes())
                value = json.loads(receipt.read_bytes())
                self.assertEqual("passed" if outcome == "clean" else "failed", value["status"])
                self.assertNotIn("PRIVATE_", output + receipt.read_text())
                if outcome == "unexpected-failure":
                    self.assertEqual("Scanner operation failed: ValueError", value["error"])

    def test_complete_published_receipt_is_private_and_keeps_raw_finding_status(self):
        for raw_count, unresolved, status in ((0, 0, "passed"), (1, 0, "passed_with_reviewed_findings"), (1, 1, "failed")):
            with self.subTest(status=status), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                raw = {"exit_code": 10 if raw_count else 0, "finding_count": raw_count,
                       "findings": [{"rule_id": "synthetic-rule", "file": "synthetic.txt", "line": 1,
                                     "commit": "c" * 40}] if raw_count else []}
                triage = {"reviewed_count": raw_count - unresolved, "unresolved_count": unresolved}
                result, failure, _, _, output = self.invoke(root, raw=raw, triage=triage)
                self.assertEqual((1 if unresolved else 0, None), (result, failure))
                receipt = root / "build/security/secret-scan.json"
                value = json.loads(receipt.read_bytes())
                self.assertEqual(status, value["status"])
                self.assertEqual(raw, value["scan"])
                self.assertEqual(triage, value["triage"])
                self.assertEqual(0o600, receipt.stat().st_mode & 0o777)
                self.assertEqual([receipt], list(receipt.parent.iterdir()))
                self.assertNotIn("PRIVATE_", output + receipt.read_text())

    def test_new_unsafe_receipt_after_inspection_is_rechecked_before_publication(self):
        for kind in ("symlink", "hardlink"):
            with self.subTest(kind=kind), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary) / "root"
                folder = root / "build/security"
                folder.mkdir(parents=True)
                receipt, foreign = folder / "secret-scan.json", Path(temporary) / "foreign.json"
                foreign.write_bytes(self.SENTINEL)
                def inspect():
                    if kind == "symlink":
                        receipt.symlink_to(foreign)
                    else:
                        os.link(foreign, receipt)
                    return {"commit": "a" * 40, "tree": "b" * 40}
                result, failure, source_calls, _, output = self.invoke(root, inspect=inspect)
                self.assertEqual((1, None, 1), (result, failure, source_calls))
                self.assertEqual(self.SENTINEL, foreign.read_bytes())
                self.assertEqual([receipt], list(folder.iterdir()))
                self.assertNotIn("PRIVATE_", output)

    def test_serialization_and_replace_failures_leave_no_partial_or_stale_success(self):
        for phase in ("serialization", "replace"):
            with self.subTest(phase=phase), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                receipt = root / "build/security/secret-scan.json"
                receipt.parent.mkdir(parents=True)
                receipt.write_text('{"status":"passed","source":"SYNTHETIC previous"}\n')
                peer = receipt.parent / "peer.txt"
                peer.write_bytes(self.PEER)
                def partial(_value, stream, **_kwargs):
                    stream.write('{"incomplete":')
                    raise ValueError("PRIVATE_SERIALIZATION_DETAIL")
                with contextlib.ExitStack() as stack:
                    if phase == "serialization":
                        stack.enter_context(patch.object(check.json, "dump", side_effect=partial))
                        stack.enter_context(patch.object(check.json, "dumps", side_effect=ValueError("PRIVATE_SERIALIZATION_DETAIL")))
                    else:
                        stack.enter_context(patch.object(Path, "replace", side_effect=PermissionError("PRIVATE_REPLACE_DETAIL")))
                    result, failure, source_calls, _, output = self.invoke(root)
                self.assertEqual((1, None, 1), (result, failure, source_calls))
                self.assertFalse(receipt.exists())
                self.assertEqual([peer], list(receipt.parent.iterdir()))
                self.assertEqual(self.PEER, peer.read_bytes())
                self.assertNotIn("PRIVATE_", output)


if __name__ == "__main__":
    unittest.main()
