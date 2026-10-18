#!/usr/bin/env python3
"""Prove ignored packaged schemas/assets cannot receive a clean inventory."""

from __future__ import annotations

import argparse
import hashlib
import io
import json
from pathlib import Path
import subprocess
import tarfile
import tempfile
import zipfile


ROOT = Path(__file__).resolve().parents[1]
PAYLOAD = b'{"title":"harmless inventory source fixture"}\n'
REJECTION = "Untracked project build input cannot enter an inventoried build"


def git(root: Path, *arguments: str) -> bytes:
    return subprocess.run(
        ["git", *arguments], cwd=root, check=True, stdout=subprocess.PIPE,
        stderr=subprocess.PIPE, timeout=30,
    ).stdout


def check_guard(preview: bool) -> dict[str, object]:
    source_commit = git(ROOT, "rev-parse", "HEAD").decode().strip()
    source_tree = git(ROOT, "rev-parse", "HEAD^{tree}").decode().strip()
    archive = git(ROOT, "archive", "HEAD")
    case = "ignored-preview-asset" if preview else "ignored-core-schema"
    with tempfile.TemporaryDirectory(prefix="android-inventory-guard-") as temporary:
        fixture = Path(temporary)
        with tarfile.open(fileobj=io.BytesIO(archive)) as source:
            source.extractall(fixture, filter="data")
        git(fixture, "init", "--quiet")
        git(fixture, "add", "--all")
        # This is a private, disposable test history, not a contribution commit.
        git(fixture, "-c", "user.name=Runtime inventory fixture",
            "-c", "user.email=runtime-inventory@example.invalid",
            "-c", "commit.gpgsign=false", "commit", "--quiet", "--no-verify",
            "-m", "Synthetic checkout for a packaged-source rejection test")

        if preview:
            relative = "contracts/v1/examples/inventory-source-guard.json"
            packaged = "assets/inventory-source-guard.json"
            output = "preview/build/outputs/apk/debug/preview-debug-unsigned.apk"
            # Use a normal Android asset name; dot-prefixed assets can be ignored
            # by Android packaging independently of the Git source guard.
            with (fixture / ".git/info/exclude").open("a") as exclude:
                exclude.write(f"\n/{relative}\n")
        else:
            relative = "contracts/v1/schemas/.env.inventory-source-guard.schema.json"
            packaged = relative
            output = "core/build/libs/core.jar"
        seed = fixture / relative
        if seed.exists():
            raise RuntimeError("Inventory guard fixture would replace a source file")
        seed.write_bytes(PAYLOAD)
        git(fixture, "check-ignore", "--quiet", relative)
        if git(fixture, "status", "--porcelain", "--untracked-files=all"):
            raise RuntimeError("Ignored-input fixture did not preserve a clean Git status")

        command = [
            str(fixture / "gradlew"), "--offline", "--no-daemon",
            "--dependency-verification=strict", "--init-script",
            "scripts/runtime_inventory.init.gradle.kts",
        ]
        if preview:
            command.append("-PandroidPreview=true")
        command.extend([":core:clean", "runtimeInventory"])
        result = subprocess.run(
            command, cwd=fixture, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
            text=True, timeout=300,
        )
        if result.returncode == 0 or REJECTION not in result.stdout:
            raise RuntimeError(
                f"{case} did not reach the expected source rejection "
                f"(exit {result.returncode}):\n{result.stdout[-6000:]}"
            )
        if (fixture / "build/inventory/runtime-inventory.json").exists():
            raise RuntimeError("Rejected source produced a fresh inventory receipt")
        built = fixture / output
        with zipfile.ZipFile(built) as package:
            if package.read(packaged) != PAYLOAD:
                raise RuntimeError("The ignored source was not present in the real build output")
        return {
            "schema_version": 1,
            "source_commit": source_commit,
            "source_tree": source_tree,
            "case": case,
            "status": "passed",
            "expected_rejection": REJECTION,
            "gradle_exit_code": result.returncode,
            "git_status_was_clean": True,
            "ignored_input_was_packaged": True,
            "fresh_inventory_written": False,
            "fixture_payload_sha256": hashlib.sha256(PAYLOAD).hexdigest(),
            "fixture_output_sha256": hashlib.sha256(built.read_bytes()).hexdigest(),
            "limitations": "Disposable source-guard fixture, not an installed or release result.",
        }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--preview", action="store_true", help="Check an ignored APK asset")
    arguments = parser.parse_args()
    report = check_guard(arguments.preview)
    output = ROOT / "build/inventory" / f"guard-{report['case']}.json"
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(report, indent=2, sort_keys=True) + "\n")
    print(f"Verified {report['case']}: real packaged input rejected; no fresh inventory")


if __name__ == "__main__":
    main()
