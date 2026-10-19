#!/usr/bin/env python3
"""Rehearse a removed-secret finding without issued credentials or remote writes."""

from __future__ import annotations

import argparse
from contextlib import contextmanager
import datetime as dt
import hashlib
import json
import os
from pathlib import Path
import stat
import subprocess
import tempfile

import check_secrets as scanner

ROOT = Path(__file__).resolve().parents[1]


@contextmanager
def fixture_environment():
    """Keep inherited Git routing, replacement objects and configuration out."""
    previous = {key: value for key, value in os.environ.items() if key.startswith("GIT_")}
    for key in previous:
        os.environ.pop(key)
    os.environ.update(GIT_CONFIG_NOSYSTEM="1", GIT_CONFIG_GLOBAL=os.devnull,
                      GIT_TERMINAL_PROMPT="0", GIT_NO_REPLACE_OBJECTS="1")
    try:
        yield
    finally:
        for key in list(os.environ):
            if key.startswith("GIT_"):
                os.environ.pop(key)
        os.environ.update(previous)


def git(repository: Path, *arguments: str, data: str | None = None) -> str:
    env = {key: value for key, value in os.environ.items() if not key.startswith("GIT_")}
    env.update(GIT_CONFIG_NOSYSTEM="1", GIT_CONFIG_GLOBAL=os.devnull,
               GIT_TERMINAL_PROMPT="0", GIT_NO_REPLACE_OBJECTS="1",
               GIT_AUTHOR_NAME="Synthetic Response Exercise",
               GIT_COMMITTER_NAME="Synthetic Response Exercise",
               GIT_AUTHOR_EMAIL="response-exercise@example.invalid",
               GIT_COMMITTER_EMAIL="response-exercise@example.invalid")
    result = subprocess.run(["git", *arguments], cwd=repository, env=env, input=data,
                            capture_output=True, text=True, timeout=30)
    scanner.require(result.returncode == 0, "Local exercise Git operation failed")
    return result.stdout.strip()


def checked_binary(source: Path, private: Path) -> Path:
    scanner.require(stat.S_ISREG(source.lstat().st_mode), "Scanner must be a regular file")
    with source.open("rb") as stream:
        data = scanner.verified_bytes(stream.read(40 * 1024 * 1024 + 1),
                                      scanner.BINARY_SHA256, 40 * 1024 * 1024)
    binary = private / "gitleaks"
    binary.write_bytes(data)
    binary.chmod(0o700)
    version = subprocess.run([str(binary), "version"], capture_output=True, text=True, timeout=10)
    scanner.require(version.returncode == 0 and version.stdout.strip() == scanner.VERSION,
                    "Scanner version differs")
    return binary


def exercise(binary: Path, private: Path) -> dict:
    fixture = private / "incident-fixture"
    fixture.mkdir(mode=0o700)
    git(fixture, "-c", "init.templateDir=", "init", "--quiet")
    scanner.require(git(fixture, "remote") == "", "Exercise must have no remotes")
    path = fixture / "fixture.txt"
    token = "ghp" + "_" + hashlib.sha256(b"public-unissued-pyai-response-exercise").hexdigest()[:36]
    path.write_text('synthetic_fixture = "' + token + '"\n', encoding="utf-8")

    def commit(message: str, parent: str | None = None) -> str:
        blob = git(fixture, "hash-object", "-w", "--no-filters", "fixture.txt")
        tree = git(fixture, "mktree", data=f"100644 blob {blob}\tfixture.txt\n")
        parents = ["-p", parent] if parent else []
        sha = git(fixture, "commit-tree", tree, "-m", message, *parents)
        git(fixture, "update-ref", "refs/heads/main", sha)
        git(fixture, "symbolic-ref", "HEAD", "refs/heads/main")
        return sha

    seeded = commit("Seed an unissued exercise pattern")
    before = scanner.scan(binary, fixture, private, "before-removal", history=False)
    scanner.require(before["exit_code"] == 10 and before["finding_count"] == 1
                    and before["findings"][0]["rule_id"] == "github-pat",
                    "Seeded directory finding differs")
    history = scanner.scan(binary, fixture, private, "before-history", history=True)
    expected_history = {"exit_code": 10, "finding_count": 1, "findings": [
        {"rule_id": "github-pat", "file": "fixture.txt", "line": 1, "commit": seeded}]}
    scanner.require(history == expected_history, "Seeded history finding differs")
    path.write_text('synthetic_fixture = "removed"\n', encoding="utf-8")
    removed = commit("Remove the pattern from current files", seeded)
    after = scanner.scan(binary, fixture, private, "after-removal", history=False)
    scanner.require(after == {"exit_code": 0, "finding_count": 0, "findings": []},
                    "Removed directory must be clean")
    remaining = scanner.scan(binary, fixture, private, "after-history", history=True)
    scanner.require(remaining == expected_history, "Original history finding must remain")
    scanner.require(git(fixture, "rev-list", "--count", "HEAD") == "2"
                    and git(fixture, "rev-parse", "HEAD^") == seeded
                    and git(fixture, "remote") == "", "Fixture history or isolation differs")
    return {
        "fixture": {"kind": "publicly-derived-unissued-pattern", "remotes": [],
                    "seeded_commit": seeded, "removed_commit": removed},
        "observations": {
            "before_removal_directory": {"exit_code": 10, "finding_count": 1},
            "before_removal_history": history,
            "after_removal_directory": after,
            "after_removal_history": remaining},
        "conclusion": "Current-file removal leaves the same exact finding in reachable history.",
        "disposition": "Unissued synthetic fixture; no exception or source-history rewrite applied."}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--scanner", type=Path, help="Optional existing binary; exact pin is still verified")
    parser.add_argument("--output", type=Path, default=ROOT / "build/security/response-exercise.json")
    args = parser.parse_args()
    if args.output.exists() or args.output.is_symlink():
        print("Refusing an existing output; choose a new receipt path.")
        return 2
    report = {
        "schema_version": 1, "scope": "local-synthetic-response-exercise", "status": "failed",
        "started_utc": dt.datetime.now(dt.timezone.utc).isoformat(),
        "scanner": {"version": scanner.VERSION, "binary_sha256": scanner.BINARY_SHA256},
        "limitations": [
            "Not a scan of project history or a dependency vulnerability assessment.",
            "No issued credential, live authentication, revocation or provider audit was exercised.",
            "No private report, contacted responder, advisory or customer notification was performed.",
            "No corrected application build, signing, installation or distribution was performed.",
            "This rehearsal does not complete Android #69 or operational response readiness."]}
    try:
        scanner.require(Path.cwd().resolve() == ROOT, "Run from the repository root")
        report["source"] = {
            "base_commit": git(ROOT, "rev-parse", "HEAD"),
            "base_tree": git(ROOT, "rev-parse", "HEAD^{tree}"),
            "executed_files": [{"path": str(path.relative_to(ROOT)),
                                "sha256": hashlib.sha256(path.read_bytes()).hexdigest()}
                               for path in (Path(__file__).resolve(), Path(scanner.__file__).resolve())],
            "identity_scope": "Base revision plus actual executed-file digests; driver may be uncommitted."}
        with tempfile.TemporaryDirectory(prefix="pyai-response-exercise-") as folder:
            private = Path(folder)
            binary = checked_binary(args.scanner, private) if args.scanner else scanner.install_scanner(private)
            with fixture_environment():
                report.update(exercise(binary, private))
        report["status"] = "passed"
    except Exception as error:
        report["error"] = "Response rehearsal failed: " + type(error).__name__
    try:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        with args.output.open("x", encoding="utf-8") as output:
            output.write(json.dumps(report, indent=2, sort_keys=True) + "\n")
    except OSError:
        print("Response rehearsal receipt could not be created; no saved result is claimed.")
        return 2
    print("Response rehearsal " + report["status"] + "; sanitized receipt written.")
    return 0 if report["status"] == "passed" else 1


if __name__ == "__main__":
    raise SystemExit(main())
