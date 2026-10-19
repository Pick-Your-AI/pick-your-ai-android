#!/usr/bin/env python3
"""Exercise the actual inventory producer against a private Git replacement."""

from __future__ import annotations

import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile
import time
import zipfile


ROOT = Path(__file__).resolve().parents[1]
DRIVER = "scripts/runtime_inventory.init.gradle.kts"
INVENTORY = "build/inventory/runtime-inventory.json"
JAVA_SOURCE = "core/src/main/java/inventory/fixture/Identity.java"
JAVA_CLASS = "inventory/fixture/Identity.class"
REJECTION = "Inventory requires a clean source checkout at its recorded commit"
CASES = ("clean-original", "replacement-commit", "clean-adopted")
EVIDENCE = ROOT / "build/inventory/source-identity-regression"
PIN_FILES = (
    "build.gradle.kts", "settings.gradle.kts", "core/build.gradle.kts",
    "core/gradle.lockfile", "gradle/verification-metadata.xml",
    "gradle/wrapper/gradle-wrapper.properties", "gradle/wrapper/gradle-wrapper.jar",
    DRIVER,
)


def digest(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def child_environment() -> dict[str, str]:
    # A private fixture must not inherit another worktree or disabled replacement
    # lookup. The producer itself must reject the fixture; an inherited setting
    # must not make a broken producer look correct.
    env = {key: value for key, value in os.environ.items() if not key.startswith("GIT_")}
    env.update({"GIT_CONFIG_NOSYSTEM": "1", "GIT_CONFIG_GLOBAL": os.devnull,
                "GIT_TERMINAL_PROMPT": "0"})
    return env


def git(root: Path, *arguments: str, replacements: bool = False) -> bytes:
    command = ["git"]
    if not replacements:
        command.append("--no-replace-objects")
    result = subprocess.run(
        [*command, *arguments], cwd=root, env=child_environment(), check=True,
        stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=30,
    )
    return result.stdout


def identity(root: Path) -> dict[str, str]:
    return {"commit": git(root, "rev-parse", "HEAD").decode().strip(),
            "tree": git(root, "rev-parse", "HEAD^{tree}").decode().strip()}


def commit(root: Path, message: str) -> None:
    # These commits exist only in a disposable remote-free test repository.
    git(root, "-c", "user.name=Runtime inventory fixture", "-c",
        "user.email=runtime-inventory@example.invalid", "-c", "commit.gpgsign=false",
        "-c", "core.hooksPath=" + os.devnull, "commit", "--quiet", "--no-verify",
        "-m", message)


def java_source(marker: str) -> bytes:
    return ("package inventory.fixture;\n"
            "public final class Identity {\n"
            f'  public static String marker() {{ return "{marker}"; }}\n'
            "}\n").encode()


def prepare_fixture(root: Path, driver: bytes) -> None:
    files = {
        ".gitignore": b".gradle/\n.kotlin/\n**/build/\n",
        "settings.gradle.kts":
            b'rootProject.name = "runtime-inventory-identity-fixture"\ninclude(":core")\n',
        "build.gradle.kts": b"// Private Java-only producer fixture.\n",
        "core/build.gradle.kts": b"plugins { java }\n",
        "core/gradle.lockfile": b"# No external runtime dependencies.\n",
        "gradle/verification-metadata.xml":
            b'<?xml version="1.0" encoding="UTF-8"?>\n'
            b'<verification-metadata xmlns="https://schema.gradle.org/dependency-verification">'
            b'<configuration><verify-metadata>true</verify-metadata>'
            b'<verify-signatures>false</verify-signatures></configuration>'
            b'<components/></verification-metadata>\n',
        DRIVER: driver,
        JAVA_SOURCE: java_source("original"),
    }
    for name in ("gradle/wrapper/gradle-wrapper.properties", "gradle/wrapper/gradle-wrapper.jar"):
        files[name] = (ROOT / name).read_bytes()
    for name, content in files.items():
        target = root / name
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(content)
    git(root, "init", "--quiet", "--template=")
    git(root, "add", "--all")
    commit(root, "Private original source for runtime inventory regression")


def run_producer(root: Path, case: str) -> tuple[subprocess.CompletedProcess[bytes], dict[str, object]]:
    # Remove only this fixture's prior receipt, so a rejected invocation cannot
    # accidentally borrow the clean control's output. No project receipt changes.
    output = root / INVENTORY
    output.unlink(missing_ok=True)
    started = time.monotonic()
    result = subprocess.run(
        [str(ROOT / "gradlew"), "--offline", "--no-daemon", "--console=plain",
         "--max-workers=1", "--dependency-verification=strict", "--project-dir",
         str(root), "--init-script", str(root / DRIVER), "runtimeInventory"],
        cwd=root, env=child_environment(), stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT, timeout=180,
    )
    log = EVIDENCE / (case + ".log")
    log.write_bytes(result.stdout)
    observation: dict[str, object] = {
        "gradle_exit_code": result.returncode,
        "seconds": round(time.monotonic() - started, 3),
        "gradle_log_sha256": digest(result.stdout),
        "gradle_log": log.relative_to(ROOT).as_posix(),
        "fresh_inventory_written": output.is_file(),
        "expected_rejection_observed": REJECTION.encode() in result.stdout,
    }
    jar = root / "core/build/libs/core.jar"
    if not jar.is_file():
        raise RuntimeError("The private Java build did not produce its real core JAR:\n" +
                           result.stdout[-6000:].decode(errors="replace"))
    with zipfile.ZipFile(jar) as archive:
        if archive.testzip() is not None or archive.namelist().count(JAVA_CLASS) != 1:
            raise RuntimeError("The private Java build has no unique intact compiled fixture")
        class_bytes = archive.read(JAVA_CLASS)
    jar_bytes = jar.read_bytes()
    jar_evidence = EVIDENCE / (case + ".jar")
    jar_evidence.write_bytes(jar_bytes)
    observation["compiled_class_sha256"] = digest(class_bytes)
    observation["jar_sha256"] = digest(jar_bytes)
    observation["jar_evidence"] = jar_evidence.relative_to(ROOT).as_posix()
    if output.is_file():
        raw = output.read_bytes()
        if len(raw) > 8 * 1024 * 1024:
            raise RuntimeError("Private inventory exceeded its byte bound")
        inventory = json.loads(raw)
        inventory_evidence = EVIDENCE / (case + ".json")
        inventory_evidence.write_bytes(raw)
        observation["inventory_source"] = inventory["source"]
        observation["inventory_sha256"] = digest(raw)
        observation["inventory_evidence"] = inventory_evidence.relative_to(ROOT).as_posix()
        if inventory["schema_version"] != 1 or inventory["scope"] != "resolved-runtime-classpaths":
            raise RuntimeError("The actual producer returned an unexpected inventory format")
        if len(inventory["classpaths"]) != 1:
            raise RuntimeError("The private Java-only fixture has an unexpected runtime scope")
        scope = inventory["classpaths"][0]
        if scope["project"] != ":core" or scope["configuration"] != "runtimeClasspath":
            raise RuntimeError("The private fixture did not exercise the real core classpath")
        pins = inventory["source"]["pins"]
        if len(pins) != len(PIN_FILES) or {pin["file"] for pin in pins} != set(PIN_FILES):
            raise RuntimeError("The actual producer omitted a fixture source pin")
        if any(pin["sha256"] != digest((root / pin["file"]).read_bytes()) for pin in pins):
            raise RuntimeError("The actual producer reported a different fixture source pin")
        expected_output = {"kind": "core-jar", "file": "core/build/libs/core.jar",
                           "sha256": observation["jar_sha256"], "bytes": jar.stat().st_size}
        if inventory["outputs"] != [expected_output]:
            raise RuntimeError("The actual producer did not bind the real fixture JAR")
    return result, observation


def require_clean_control(root: Path, result: subprocess.CompletedProcess[bytes],
                          observation: dict[str, object]) -> None:
    expected = identity(root)
    actual = observation.get("inventory_source", {})
    if (result.returncode != 0 or not observation["fresh_inventory_written"] or
            any(actual.get(key) != value for key, value in expected.items()) or
            git(root, "status", "--porcelain", "--untracked-files=all")):
        raise RuntimeError("Clean actual producer control failed:\n" +
                           result.stdout[-6000:].decode(errors="replace"))


def check() -> dict[str, object]:
    driver = (ROOT / DRIVER).read_bytes()
    report: dict[str, object] = {
        "schema_version": 1,
        "scope": "private-java-runtime-inventory-source-identity",
        "source": identity(ROOT),
        "producer_sha256": digest(driver),
        "wrapper_sha256": digest((ROOT / "gradle/wrapper/gradle-wrapper.jar").read_bytes()),
        "cases": [],
        "limitations": "Actual offline Java-only producer fixture; no Android or release build.",
    }
    with tempfile.TemporaryDirectory(prefix="android-inventory-identity-") as temporary:
        root = Path(temporary)
        prepare_fixture(root, driver)
        original = identity(root)
        result, clean = run_producer(root, "clean-original")
        require_clean_control(root, result, clean)
        report["cases"].append({"case": "clean-original", "status": "passed", **clean})

        (root / JAVA_SOURCE).write_bytes(java_source("replacement"))
        git(root, "add", JAVA_SOURCE)
        replacement_tree = git(root, "write-tree").decode().strip()
        replacement = git(root, "-c", "user.name=Runtime inventory fixture", "-c",
                          "user.email=runtime-inventory@example.invalid", "commit-tree",
                          replacement_tree, "-m", "Private replacement source").decode().strip()
        git(root, "replace", original["commit"], replacement)
        apparent = {
            "commit": git(root, "rev-parse", "HEAD", replacements=True).decode().strip(),
            "tree": git(root, "rev-parse", "HEAD^{tree}", replacements=True).decode().strip(),
        }
        if (apparent != {"commit": original["commit"], "tree": replacement_tree} or
                apparent["tree"] == original["tree"] or
                git(root, "status", "--porcelain", "--untracked-files=all", replacements=True) or
                not git(root, "status", "--porcelain", "--untracked-files=all")):
            raise RuntimeError("Private replacement did not reproduce the clean-status deception")

        result, replaced = run_producer(root, "replacement-commit")
        if replaced["compiled_class_sha256"] == clean["compiled_class_sha256"]:
            raise RuntimeError("The replacement fixture did not change the actual compiled class")
        rejected = (result.returncode != 0 and replaced["expected_rejection_observed"] and
                    not replaced["fresh_inventory_written"])
        if not rejected:
            # A real legacy success must carry the deceptive pair; an unrelated
            # build failure is never classified as this specific source defect.
            observed = replaced.get("inventory_source", {})
            if result.returncode != 0 or any(observed.get(k) != v for k, v in apparent.items()):
                raise RuntimeError("Replacement fixture failed outside the source-identity guard:\n" +
                                   result.stdout[-6000:].decode(errors="replace"))
        report["cases"].append({
            "case": "replacement-commit", "status": "passed" if rejected else "failed",
            "actual_source": original, "apparent_source": apparent,
            "git_default_status_was_clean": True, "git_literal_status_was_dirty": True,
            "expected_rejection": REJECTION, **replaced,
        })

        git(root, "replace", "--delete", original["commit"])
        commit(root, "Actually adopt the changed Java source in private history")
        adopted = identity(root)
        if adopted["commit"] == original["commit"] or adopted["tree"] != replacement_tree:
            raise RuntimeError("Adopted control does not have the actual changed source identity")
        result, final = run_producer(root, "clean-adopted")
        require_clean_control(root, result, final)
        if final["compiled_class_sha256"] != replaced["compiled_class_sha256"]:
            raise RuntimeError("Adopted control changed the compiled replacement class")
        if git(root, "replace", "--list"):
            raise RuntimeError("A replacement ref survived the private adopted control")
        report["cases"].append({"case": "clean-adopted", "status": "passed", **final})
        report["status"] = "passed" if rejected else "failed"
    report["private_fixture_removed"] = not Path(temporary).exists()
    if not report["private_fixture_removed"]:
        raise RuntimeError("Private producer fixture was not removed")
    return report


def main() -> None:
    output = ROOT / "build/inventory/source-identity-regression.json"
    output.unlink(missing_ok=True)
    EVIDENCE.mkdir(parents=True, exist_ok=True)
    for case in CASES:
        for suffix in (".log", ".jar", ".json"):
            (EVIDENCE / (case + suffix)).unlink(missing_ok=True)
    report = check()
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(report, indent=2, sort_keys=True) + "\n")
    if report["status"] != "passed":
        raise SystemExit("Runtime inventory accepted replaced source under an unchanged commit; "
                         "original and adopted controls passed; source-identity regression failed")
    print("Verified actual runtime inventory source identity: clean original, replacement rejection, "
          "and clean adopted Java source; no installed or release claim")


if __name__ == "__main__":
    main()
