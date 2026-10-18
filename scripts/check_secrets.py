#!/usr/bin/env python3
"""Run a pinned local scanner and retain metadata, never matched source or secrets."""

from __future__ import annotations

import datetime as dt
import hashlib
import io
import json
import os
from pathlib import Path
import platform
import re
import shlex
import shutil
import subprocess
import sys
import tarfile
import tempfile
import time
import urllib.request


ROOT = Path(__file__).resolve().parents[1]
VERSION = "8.30.1"
RELEASE = f"https://github.com/gitleaks/gitleaks/releases/download/v{VERSION}/"
ARCHIVE = f"gitleaks_{VERSION}_linux_x64.tar.gz"
ARCHIVE_SHA256 = "551f6fc83ea457d62a0d98237cbad105af8d557003051f41f3e7ca7b3f2470eb"
CHECKSUMS_SHA256 = "061476c21adaf5441516f96f185c1a4706a83cd6329b9b38762271b3d4a52fae"
BINARY_SHA256 = "88f91962aa2f93ac6ab281d553b9e125f5197bbbce38f9f2437f7299c32e5509"
MAX_REPORT_BYTES = 4 * 1024 * 1024
MAX_TRACE_BYTES = 16 * 1024 * 1024
MAX_TRACE_LINE_BYTES = 128 * 1024
HISTORY_OPTIONS = ("--full-history", "--diff-merges=separate", "--text",
                   "--no-ext-diff", "--no-textconv", "HEAD")
SOURCE_IGNORE_ERROR = "Source .gitleaksignore is forbidden; no scanner exceptions are accepted"
SCANNER_DIAGNOSTIC_ERROR = "Scanner emitted execution diagnostics; a complete scan was not established"
HISTORY_COMPLETION_ERROR = "Git history completion was not verified; no complete scan was established"


class ScanFailure(RuntimeError):
    pass


class HistoryCompletionFailure(ScanFailure):
    """The exact Git history child did not verifiably complete successfully."""


def require_history_completion(trace: Path, repository: Path):
    """Bind a bounded private Trace2 session to the unchanged history command."""
    sessions = {}
    total = 0

    def malformed(_):
        raise HistoryCompletionFailure(HISTORY_COMPLETION_ERROR)

    def unique_fields(pairs):
        row = {}
        for key, value in pairs:
            if key in row:
                raise HistoryCompletionFailure(HISTORY_COMPLETION_ERROR)
            row[key] = value
        return row

    try:
        with trace.open("rb") as stream:
            while line := stream.readline(MAX_TRACE_LINE_BYTES + 1):
                total += len(line)
                if (total > MAX_TRACE_BYTES or len(line) > MAX_TRACE_LINE_BYTES
                        or not line.endswith(b"\n")):
                    raise HistoryCompletionFailure(HISTORY_COMPLETION_ERROR)
                row = json.loads(line, object_pairs_hook=unique_fields, parse_constant=malformed)
                if (not isinstance(row, dict) or not isinstance(row.get("event"), str)
                        or not isinstance(row.get("sid"), str) or not row["sid"]):
                    raise HistoryCompletionFailure(HISTORY_COMPLETION_ERROR)
                session = sessions.setdefault(row["sid"], {"control": [], "last": None})
                if len(sessions) > 64:
                    raise HistoryCompletionFailure(HISTORY_COMPLETION_ERROR)
                session["last"] = row["event"]
                if row["event"] in ("version", "start", "exit", "atexit", "signal"):
                    session["control"].append(row)
                    if len(session["control"]) > 8:
                        raise HistoryCompletionFailure(HISTORY_COMPLETION_ERROR)
    except (OSError, ValueError, RecursionError):
        raise HistoryCompletionFailure(HISTORY_COMPLETION_ERROR) from None

    expected = ["-C", os.path.normpath(str(repository)), "log", "-p", "-U0", *HISTORY_OPTIONS]
    matches = [session for session in sessions.values() if any(
        row["event"] == "start" and isinstance(row.get("argv"), list)
        and row["argv"][1:] == expected for row in session["control"])]
    if len(matches) != 1:
        raise HistoryCompletionFailure(HISTORY_COMPLETION_ERROR)
    session = matches[0]
    control = session["control"]
    if ([row["event"] for row in control] != ["version", "start", "exit", "atexit"]
            or control[0].get("evt") != "4" or session["last"] != "atexit"
            or any(row.get("thread") != "main" for row in control)
            or any(type(row.get("code")) is not int or row["code"] != 0 for row in control[2:])):
        raise HistoryCompletionFailure(HISTORY_COMPLETION_ERROR)


def require(condition: bool, message: str):
    if not condition:
        raise ScanFailure(message)


def verified_bytes(data: bytes, expected: str, limit: int) -> bytes:
    require(len(data) <= limit, "Scanner input exceeded its byte limit")
    require(hashlib.sha256(data).hexdigest() == expected, "Scanner input checksum differs")
    return data


def download(name: str, expected: str, limit: int) -> bytes:
    with urllib.request.urlopen(RELEASE + name, timeout=60) as response:
        return verified_bytes(response.read(limit + 1), expected, limit)


def install_scanner(directory: Path) -> Path:
    checksums = download(f"gitleaks_{VERSION}_checksums.txt", CHECKSUMS_SHA256, 4096).decode("ascii")
    require(checksums.splitlines().count(ARCHIVE_SHA256 + "  " + ARCHIVE) == 1,
            "Published scanner checksum entry differs")
    data = download(ARCHIVE, ARCHIVE_SHA256, 12 * 1024 * 1024)
    with tarfile.open(fileobj=io.BytesIO(data), mode="r:gz") as archive:
        for name in ("gitleaks", "LICENSE"):
            member = archive.getmember(name)
            require(member.isfile() and member.size <= 40 * 1024 * 1024,
                    "Unexpected scanner archive member")
            # Read selected regular entries only; never extract archive paths or links.
            with archive.extractfile(member) as source:
                payload = source.read(40 * 1024 * 1024 + 1)
            require(len(payload) == member.size, "Incomplete scanner archive member")
            if name == "gitleaks":
                verified_bytes(payload, BINARY_SHA256, 40 * 1024 * 1024)
            (directory / name).write_bytes(payload)
    binary = directory / "gitleaks"
    binary.chmod(0o700)
    version = subprocess.run([str(binary), "version"], capture_output=True, text=True, timeout=10)
    require(version.returncode == 0 and version.stdout.strip() == VERSION, "Scanner version differs")
    return binary


def finding_metadata(value, *, history: bool) -> list[dict]:
    require(isinstance(value, list), "Scanner report is not a findings list")
    safe = []
    for finding in value:
        require(isinstance(finding, dict), "Scanner finding is not an object")
        rule, path, line, commit = (finding.get(key) for key in ("RuleID", "File", "StartLine", "Commit"))
        require(isinstance(rule, str) and re.fullmatch(r"[a-z0-9-]{1,120}", rule) is not None,
                "Scanner finding has no valid rule ID")
        require(isinstance(path, str) and 0 < len(path) <= 4096, "Scanner finding has no valid file path")
        require(type(line) is int and line > 0, "Scanner finding has no valid line")
        require(not history or isinstance(commit, str) and re.fullmatch(r"[0-9a-f]{40}", commit) is not None,
                "Historical finding has no exact commit")
        row = {"rule_id": rule, "file": path, "line": line}
        if history:
            row["commit"] = commit
        # Never retain Secret, Match, Line, message, author, email or arbitrary extra fields.
        safe.append(row)
    return safe


def require_no_source_ignore(target: Path):
    # The pinned CLI also loads source/.gitleaksignore even with an explicit
    # --gitleaks-ignore-path. Reject every entry, including dangling links.
    try:
        (target / ".gitleaksignore").lstat()
    except FileNotFoundError:
        return
    except OSError:
        raise ScanFailure("Source ignore-file metadata could not be inspected") from None
    raise ScanFailure(SOURCE_IGNORE_ERROR)


def scan(binary: Path, target: Path, directory: Path, name: str, *, history: bool) -> dict:
    require_no_source_ignore(target)
    config, ignore = directory / "built-in-rules.toml", directory / "empty-ignore"
    config.write_text("[extend]\nuseDefault = true\n", encoding="utf-8")
    ignore.write_text("", encoding="utf-8")
    report = directory / (name + ".json")
    require(not report.exists(), "Refusing a pre-existing scanner report")
    trace = directory / (name + "-git-trace.json")
    if history:
        require(not trace.exists() and not trace.is_symlink(),
                "Refusing a pre-existing Git completion trace")
    args = [str(binary), "git" if history else "dir", "--no-banner", "--no-color", "--redact=100",
            "--log-level=error",
            "--exit-code=10", "--timeout=120", "--config", str(config),
            "--gitleaks-ignore-path", str(ignore), "--ignore-gitleaks-allow",
            "--report-format=json", "--report-path", str(report)]
    if history:
        args.append("--log-opts=" + " ".join(HISTORY_OPTIONS))
    args.append(str(target))
    env = {key: value for key, value in os.environ.items()
           if key not in ("GITLEAKS_CONFIG", "GITLEAKS_CONFIG_TOML")}
    if history:
        env.update(GIT_TRACE2_EVENT=str(trace.absolute()), GIT_TRACE2_EVENT_BRIEF="1",
                   GIT_TRACE2_EVENT_NESTING="1")
    result = subprocess.run(args, env=env, capture_output=True, text=True, timeout=140)
    # Raw streams stay in memory; raw finding files stay in private temporary storage.
    require(result.returncode in (0, 10), f"Scanner execution failed with exit {result.returncode}")
    # This CLI can log Git-source errors yet exit 0 with an empty report. At the
    # explicit error log level, successful/detected scans produce empty streams.
    require(not result.stdout and not result.stderr, SCANNER_DIAGNOSTIC_ERROR)
    require(report.is_file() and not report.is_symlink(), "Scanner report is absent or not a regular file")
    with report.open("rb") as source:
        data = source.read(MAX_REPORT_BYTES + 1)
    require(len(data) <= MAX_REPORT_BYTES, "Scanner report exceeded its byte limit")
    findings = finding_metadata(json.loads(data), history=history)
    require((result.returncode == 0) == (len(findings) == 0), "Scanner exit and finding evidence disagree")
    if history:
        require_history_completion(trace, target)
    return {"exit_code": result.returncode, "finding_count": len(findings), "findings": findings}


def verify_silent_history_failure(binary: Path, repository: Path, directory: Path):
    """Exercise the real CLI's silent-child failure between intact scan controls."""
    def controls(prefix):
        for history, label in ((True, "history"), (False, "directory")):
            result = scan(binary, repository, directory, prefix + "-" + label, history=history)
            require(result["exit_code"] == 0 and result["finding_count"] == 0,
                    "Git completion control fixture did not scan cleanly")

    controls("completion-before")
    real_git = shutil.which("git")
    require(real_git is not None, "Git completion fixture could not find Git")
    with tempfile.TemporaryDirectory(prefix="pyai-secret-child-", dir=directory) as child_folder:
        wrapper = Path(child_folder) / "git"
        marker = Path(child_folder) / "history-invocations"
        wrapper.write_text(
            '#!/bin/sh\nif [ "$3" = "log" ]; then\n  printf "73\\n" >> '
            + shlex.quote(str(marker)) + '\n  exit 73\nfi\nexec '
            + shlex.quote(real_git) + ' "$@"\n', encoding="utf-8")
        wrapper.chmod(0o700)
        fault = subprocess.run([str(wrapper), "-C", str(repository), "log"],
                               capture_output=True, text=True, timeout=10)
        require(fault.returncode == 73 and not fault.stdout and not fault.stderr
                and marker.read_text(encoding="utf-8") == "73\n",
                "Silent Git completion fault was not established")
        previous_path = os.environ.get("PATH")
        os.environ["PATH"] = child_folder + os.pathsep + (previous_path or os.defpath)
        try:
            try:
                scan(binary, repository, directory, "silent-child", history=True)
            except HistoryCompletionFailure:
                pass
            else:
                raise ScanFailure("Silent Git history failure was not rejected")
        finally:
            if previous_path is None:
                os.environ.pop("PATH", None)
            else:
                os.environ["PATH"] = previous_path
        require(marker.read_text(encoding="utf-8") == "73\n73\n",
                "Scanner did not exercise the expected silent Git history child")
    controls("completion-after")


def verify_detection_and_removal(binary: Path, directory: Path) -> dict:
    fixture = directory / "synthetic-fixture"
    fixture.mkdir()
    path = fixture / "fixture.txt"
    token = "ghp" + "_" + hashlib.sha256(b"public-unissued-pyai-scanner-test-only").hexdigest()[:36]
    # Generated locally only; no issued credential, account, authentication or outbound validation.
    path.write_text('synthetic_fixture = "' + token + '" # gitleaks:allow\n', encoding="utf-8")
    detected = scan(binary, fixture, directory, "seeded", history=False)
    require(detected["exit_code"] == 10 and detected["finding_count"] == 1 and
            detected["findings"][0]["rule_id"] == "github-pat", "Harmless seeded credential was not detected")
    # Reproduce the pinned upstream's implicit source ignore with only the private
    # unissued fixture, then prove the production scan boundary rejects that file.
    fingerprint = json.loads((directory / "seeded.json").read_text(encoding="utf-8"))[0].get("Fingerprint")
    require(fingerprint == str(path) + ":github-pat:1", "Synthetic fixture fingerprint differs")
    source_ignore = fixture / ".gitleaksignore"
    source_ignore.write_text(fingerprint + "\n", encoding="utf-8")
    ignored_report = directory / "implicit-ignore-upstream.json"
    probe = subprocess.run(
        [str(binary), "dir", "--no-banner", "--no-color", "--redact=100", "--log-level=error",
         "--exit-code=10", "--timeout=120", "--config", str(directory / "built-in-rules.toml"),
         "--gitleaks-ignore-path", str(directory / "empty-ignore"), "--ignore-gitleaks-allow",
         "--report-format=json", "--report-path", str(ignored_report), str(fixture)],
        env={key: value for key, value in os.environ.items()
             if key not in ("GITLEAKS_CONFIG", "GITLEAKS_CONFIG_TOML")},
        capture_output=True, text=True, timeout=140,
    )
    require(probe.returncode == 0 and not probe.stdout and not probe.stderr and
            json.loads(ignored_report.read_text(encoding="utf-8")) == [],
            "Pinned implicit-ignore fixture behavior differs")
    try:
        scan(binary, fixture, directory, "forbidden-ignore", history=False)
    except ScanFailure as error:
        require(str(error) == SOURCE_IGNORE_ERROR, "Source ignore fixture failed for an unexpected reason")
    else:
        raise ScanFailure("Source ignore fixture was not rejected")
    source_ignore.unlink()
    path.write_text('synthetic_fixture = "removed"\n', encoding="utf-8")
    removed = scan(binary, fixture, directory, "removed", history=False)
    require(removed["exit_code"] == 0 and removed["finding_count"] == 0, "Removed fixture did not scan cleanly")
    merge_repo = directory / "synthetic-merge.git"
    merge_repo.mkdir()
    fixture_env = dict(os.environ, GIT_AUTHOR_NAME="Synthetic Scanner Fixture",
                       GIT_AUTHOR_EMAIL="fixture@example.invalid", GIT_COMMITTER_NAME="Synthetic Scanner Fixture",
                       GIT_COMMITTER_EMAIL="fixture@example.invalid")
    def fixture_git(*args, input=None, repository=merge_repo):
        result = subprocess.run(["git", *args], cwd=repository, env=fixture_env, input=input,
                                capture_output=True, text=True, timeout=10)
        require(result.returncode == 0, "Temporary merge fixture could not be constructed")
        return result.stdout.strip()
    # These explicitly synthetic commits exist only inside the private temporary
    # fixture, have no remote and are never published as project contributions.
    fixture_git("init", "--bare", "--quiet")
    empty = fixture_git("mktree", input="")
    first = fixture_git("commit-tree", empty, "-m", "Synthetic first parent")
    second = fixture_git("commit-tree", empty, "-m", "Synthetic second parent")
    blob = fixture_git("hash-object", "-w", "--stdin", input='synthetic_fixture = "' + token + '"\n')
    tree = fixture_git("mktree", input="100644 blob " + blob + "\tfixture.txt\n")
    merge = fixture_git("commit-tree", tree, "-p", first, "-p", second, "-m", "Synthetic merge-only fixture")
    fixture_git("update-ref", "refs/heads/main", merge)
    fixture_git("symbolic-ref", "HEAD", "refs/heads/main")
    merged = scan(binary, merge_repo, directory, "merge-only", history=True)
    # Separate diffs expose the same new line against each of the two empty parents.
    require(merged["exit_code"] == 10 and merged["finding_count"] == 2 and all(
            row == {"rule_id": "github-pat", "file": "fixture.txt", "line": 1, "commit": merge}
            for row in merged["findings"]), "Merge-only seeded credential was not detected")

    # Force text patches for both contributor-controlled -diff attributes and
    # Git's automatic binary classification; neither may conceal source bytes.
    attributed = directory / "synthetic-binary-history"
    attributed.mkdir()
    fixture_git("init", "--quiet", repository=attributed)
    token_line = 'synthetic_fixture = "' + token + '"\n'
    (attributed / ".gitattributes").write_text("attribute.txt -diff\n", encoding="utf-8")
    (attributed / "attribute.txt").write_text(token_line, encoding="utf-8")
    (attributed / "binary.dat").write_bytes(b"\x00synthetic binary prefix\n" + token_line.encode("utf-8"))
    fixture_git("add", "--", ".gitattributes", "attribute.txt", "binary.dat", repository=attributed)
    binary_tree = fixture_git("write-tree", repository=attributed)
    binary_commit = fixture_git("commit-tree", binary_tree, "-m", "Synthetic binary-history fixture", repository=attributed)
    fixture_git("update-ref", "refs/heads/main", binary_commit, repository=attributed)
    fixture_git("symbolic-ref", "HEAD", "refs/heads/main", repository=attributed)
    binary_history = scan(binary, attributed, directory, "binary-history", history=True)
    require(binary_history["exit_code"] == 10 and binary_history["finding_count"] == 2 and
            sorted(binary_history["findings"], key=lambda row: row["file"]) == [
                {"rule_id": "github-pat", "file": "attribute.txt", "line": 1, "commit": binary_commit},
                {"rule_id": "github-pat", "file": "binary.dat", "line": 2, "commit": binary_commit}],
            "Binary-classified historical fixtures were not detected")

    # A clean tip and a successful commit count cannot prove readable history.
    # Remove only the blob just created in this new private, remote-free fixture.
    damaged = directory / "synthetic-missing-object.git"
    damaged.mkdir()
    fixture_git("init", "--bare", "--quiet", repository=damaged)
    missing_blob = fixture_git("hash-object", "-w", "--stdin", input="synthetic missing history\n", repository=damaged)
    parent_tree = fixture_git("mktree", input="100644 blob " + missing_blob + "\tfixture.txt\n", repository=damaged)
    parent = fixture_git("commit-tree", parent_tree, "-m", "Synthetic missing-object parent", repository=damaged)
    empty_tip = fixture_git("mktree", input="", repository=damaged)
    child = fixture_git("commit-tree", empty_tip, "-p", parent, "-m", "Synthetic clean tip", repository=damaged)
    fixture_git("update-ref", "refs/heads/main", child, repository=damaged)
    fixture_git("symbolic-ref", "HEAD", "refs/heads/main", repository=damaged)
    (damaged / "objects" / missing_blob[:2] / missing_blob[2:]).unlink()
    require(fixture_git("rev-list", "--count", "HEAD", repository=damaged) == "2",
            "Missing-object fixture lost its reachable commit metadata")
    for target, name in ((fixture, "nonrepository"), (damaged, "missing-object")):
        try:
            scan(binary, target, directory, name, history=True)
        except ScanFailure as error:
            require(str(error) == SCANNER_DIAGNOSTIC_ERROR, "Source-error fixture failed for an unexpected reason")
        else:
            raise ScanFailure("Incomplete Git-source fixture was not rejected")

    # Use a new clean, remote-free repository for the silent-child counterexample.
    complete = directory / "synthetic-completion"
    complete.mkdir()
    fixture_git("init", "--quiet", repository=complete)
    (complete / "example.txt").write_text("Synthetic Git completion control.\n", encoding="utf-8")
    clean_blob = fixture_git("hash-object", "-w", "example.txt", repository=complete)
    clean_tree = fixture_git("mktree", input="100644 blob " + clean_blob + "\texample.txt\n",
                             repository=complete)
    clean_commit = fixture_git("commit-tree", clean_tree, "-m", "Synthetic completion control",
                               repository=complete)
    fixture_git("update-ref", "refs/heads/main", clean_commit, repository=complete)
    fixture_git("symbolic-ref", "HEAD", "refs/heads/main", repository=complete)
    verify_silent_history_failure(binary, complete, directory)
    return {"seeded_exit_code": 10, "seeded_rule": "github-pat", "seeded_findings": 1,
            "inline_ignore_rejected": True, "removed_exit_code": 0, "removed_findings": 0,
            "source_ignore_upstream_exit_code": 0, "source_ignore_upstream_findings": 0,
            "source_ignore_rejected": True, "nonrepository_rejected": True,
            "missing_history_object_rejected": True,
            "silent_git_child_exit_code": 73, "silent_git_child_rejected": True,
            "history_completion_controls_passed": True,
            "binary_history_exit_code": 10, "binary_history_findings": 2,
            "binary_fixture_commit": binary_commit,
            "merge_only_exit_code": 10, "merge_only_findings": 2, "merge_fixture_commit": merge}


def git(*args: str) -> str:
    result = subprocess.run(["git", *args], cwd=ROOT, capture_output=True, text=True, timeout=30)
    require(result.returncode == 0, "Required Git source inspection failed")
    return result.stdout.strip()


def inspect_source() -> dict:
    require(Path.cwd().resolve() == ROOT, "Run from the Android repository root")
    require(git("rev-parse", "--show-toplevel") == str(ROOT), "Wrong repository root")
    require(git("rev-parse", "--is-shallow-repository") == "false", "A full reachable history is required")
    require(not git("status", "--porcelain", "--untracked-files=no"), "Tracked source differs from HEAD")
    require(git("hash-object", str(Path(__file__))) == git("rev-parse", "HEAD:scripts/check_secrets.py"),
            "Scanner driver differs from the recorded commit")
    commit = git("rev-parse", "HEAD")
    require(re.fullmatch(r"[0-9a-f]{40}", commit) is not None, "Invalid source commit")
    if os.environ.get("GITHUB_SHA"):
        require(os.environ["GITHUB_SHA"] == commit, "Workflow and source commit differ")
    return {"commit": commit, "tree": git("rev-parse", "HEAD^{tree}"),
            "reachable_commits": int(git("rev-list", "--count", "HEAD"))}


def main() -> int:
    output = ROOT / "build" / "security"
    output.mkdir(parents=True, exist_ok=True)
    receipt_path = output / "secret-scan.json"
    receipt = {"schema_version": 1, "status": "failed", "scope": "reachable-HEAD-history",
               "started_utc": dt.datetime.now(dt.timezone.utc).isoformat(),
               "scanner": {"version": VERSION, "archive_sha256": ARCHIVE_SHA256,
                           "binary_sha256": BINARY_SHA256, "license": "MIT"},
               "limitations": ["pattern detection is not proof that no secret exists",
                               "unreachable branches, local files and external systems are not scanned",
                               "no live credential validation or production credential rotation",
                               "not a dependency vulnerability scan, SBOM or release acceptance"]}
    started = time.monotonic()
    try:
        require(platform.system() == "Linux" and platform.machine() == "x86_64",
                "Pinned scanner requires Linux x86_64")
        receipt["source"] = inspect_source()
        with tempfile.TemporaryDirectory(prefix="pyai-secret-check-") as temporary:
            private = Path(temporary)
            binary = install_scanner(private)
            receipt["fixture"] = verify_detection_and_removal(binary, private)
            receipt["scan"] = scan(binary, ROOT, private, "history", history=True)
            require(receipt["scan"]["finding_count"] == 0, "Secret findings require triage before integration")
        receipt["status"] = "passed"
    except ScanFailure as error:
        receipt["error"] = str(error)
    except Exception as error:
        # An unexpected error can contain tool output or request details. Keep only its type.
        receipt["error"] = "Scanner operation failed: " + type(error).__name__
    finally:
        receipt["seconds"] = round(time.monotonic() - started, 3)
        receipt_path.write_text(json.dumps(receipt, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print("Secret scan " + receipt["status"] + "; sanitized receipt: build/security/secret-scan.json")
    return 0 if receipt["status"] == "passed" else 1


if __name__ == "__main__":
    sys.exit(main())
