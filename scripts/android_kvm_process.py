#!/usr/bin/env python3
"""Select one policy-authorized, non-root KVM process on a fresh hosted runner."""

from __future__ import annotations

import grp
import hashlib
import json
import os
from pathlib import Path
import platform
import re
import stat
import subprocess
import sys
import zipfile


ROOT = Path(__file__).resolve().parents[1]
REPORT = ROOT / "preview/build/reports/kvm-boundary/receipt.json"
DEVICE = Path("/dev/kvm")
SUDO = "/usr/bin/sudo"
MARKER = "same-uid-existing-device-gid-v1"
EMULATOR_DIRECTORY = "pyai-kvm-emulator-37.1.11"
EMULATOR_ARCHIVE_NAME = "emulator-linux_x64-15917651.zip"
EMULATOR_ARCHIVE_URL = "https://dl.google.com/android/repository/" + EMULATOR_ARCHIVE_NAME
EMULATOR_ARCHIVE_SHA256 = "95771e0ae431897b2a4bd2d97fa095f29a8b0624a7b216baf529f9306161c266"
ENVIRONMENT_KEYS = (
    "ANDROID_HOME", "JAVA_HOME", "RUNNER_TEMP", "GITHUB_WORKSPACE",
    "GITHUB_ACTIONS", "RUNNER_ENVIRONMENT", "RUNNER_OS", "RUNNER_ARCH",
    "GITHUB_SHA", "GITHUB_RUN_ID", "GITHUB_RUN_ATTEMPT", "GITHUB_EVENT_NAME",
    "PYAI_EMULATOR_MODE", "PYAI_EXPECTED_APK_SHA256",
)


class BoundaryFailure(RuntimeError):
    pass


def require(condition: bool, message: str) -> None:
    if not condition:
        raise BoundaryFailure(message)


def selected_environment() -> dict:
    selected = {key: os.environ.get(key) for key in ENVIRONMENT_KEYS}
    require(all(isinstance(value, str) and value for value in selected.values()),
            "A required, explicitly allowlisted runner environment value is missing")
    require(selected["GITHUB_ACTIONS"] == "true" and
            selected["RUNNER_ENVIRONMENT"] == "github-hosted" and
            selected["RUNNER_OS"] == "Linux" and selected["RUNNER_ARCH"] == "X64" and
            selected["PYAI_EMULATOR_MODE"] == "kvm",
            "The boundary requires the selected GitHub-hosted Linux/X64 KVM job")
    require(re.fullmatch(r"[0-9a-f]{40}", selected["GITHUB_SHA"]) is not None and
            re.fullmatch(r"[0-9a-f]{64}", selected["PYAI_EXPECTED_APK_SHA256"]) is not None and
            all(re.fullmatch(r"[1-9][0-9]*", selected[key]) is not None
                for key in ("GITHUB_RUN_ID", "GITHUB_RUN_ATTEMPT")),
            "The exact workflow, run and unsigned APK identities are required")
    for key in ("ANDROID_HOME", "JAVA_HOME", "RUNNER_TEMP", "GITHUB_WORKSPACE"):
        path = Path(selected[key])
        require(path.is_absolute() and path.is_dir(), "Expected an existing absolute " + key)
        selected[key] = str(path.resolve())
    require(selected["GITHUB_WORKSPACE"] == str(ROOT), "The workspace differs from this checkout")
    selected.update(PATH=selected["JAVA_HOME"] + "/bin:/usr/bin:/bin",
                    LANG="C.UTF-8", LC_ALL="C.UTF-8", PYAI_KVM_PROCESS=MARKER,
                    PYAI_EMULATOR_BINARY=str(Path(selected["RUNNER_TEMP"]) / EMULATOR_DIRECTORY / "emulator/emulator"))
    return selected


def fixed_child_command() -> list[str]:
    python = Path("/usr/bin/python3").resolve(strict=True)
    require(Path(sys.executable).resolve() == python and sys.version_info[:2] == (3, 12),
            "Use the existing absolute Ubuntu Python 3.12 interpreter")
    return [str(python), "-I", str(ROOT / "scripts/android_kvm_process.py"), "--child"]


def current_context() -> dict:
    environment = selected_environment()
    require(Path.cwd().resolve() == ROOT, "Run from the exact repository root")
    release = platform.freedesktop_os_release()
    require(platform.system() == "Linux" and platform.machine() == "x86_64" and
            release.get("ID") == "ubuntu" and release.get("VERSION_ID") == "24.04",
            "The process boundary is limited to the selected Ubuntu 24.04 x86_64 runner")
    context = {"environment": environment, "child_command": fixed_child_command(),
               "system": platform.system(), "machine": platform.machine(),
               "os_release": {key: release.get(key) for key in ("ID", "VERSION_ID")},
               "python_version": platform.python_version(), "host_cpu_count": os.cpu_count()}
    for key, arguments in (("source_commit", ["rev-parse", "HEAD"]),
                           ("source_tree", ["rev-parse", "HEAD^{tree}"]),
                           ("working_tree", ["diff", "--quiet"]),
                           ("index", ["diff", "--cached", "--quiet"])):
        result = subprocess.run(["/usr/bin/git", *arguments], env=environment, timeout=10,
                                capture_output=True, text=True, check=False)
        require(result.returncode == 0 and not result.stderr.strip(), "Checkout verification failed: " + key)
        context[key] = result.stdout.strip()
    require(context["source_commit"] == environment["GITHUB_SHA"] and
            re.fullmatch(r"[0-9a-f]{40}", context["source_tree"]) is not None,
            "Checkout source differs from the exact workflow identity")
    apk = ROOT / "preview/build/outputs/apk/debug/preview-debug-unsigned.apk"
    require(apk.is_file() and not apk.is_symlink(), "Expected the original unsigned APK handoff")
    with apk.open("rb") as stream:
        context["unsigned_apk_sha256"] = hashlib.file_digest(stream, "sha256").hexdigest()
    require(context["unsigned_apk_sha256"] == environment["PYAI_EXPECTED_APK_SHA256"],
            "Unsigned APK differs from the successful producer's exact handoff")
    return context


def observe_identity(observed: dict) -> None:
    observed.update(uid=os.getuid(), effective_uid=os.geteuid(), gid=os.getgid(),
                    effective_gid=os.getegid(), supplementary_groups=sorted(os.getgroups()))
    require(observed["uid"] == observed["effective_uid"] and observed["uid"] > 0 and
            observed["gid"] == observed["effective_gid"] and observed["gid"] > 0,
            "The boundary must keep a non-root real/effective user and group")
    metadata = DEVICE.lstat()
    observed["device"] = {"path": str(DEVICE), "mode": metadata.st_mode, "uid": metadata.st_uid,
                          "gid": metadata.st_gid, "filesystem_device": metadata.st_dev,
                          "inode": metadata.st_ino, "rdev": metadata.st_rdev}
    require(stat.S_ISCHR(metadata.st_mode) and metadata.st_uid == 0 and metadata.st_gid > 0,
            "Expected the existing root-owned, non-root-group KVM character device")
    group = grp.getgrnam("kvm")
    observed["kvm_group"] = {"name": group.gr_name, "gid": group.gr_gid}
    require(group.gr_name == "kvm" and group.gr_gid == metadata.st_gid and group.gr_gid > 0,
            "The device group differs from the existing named kvm group; no group correction")
    observed["read_write_access"] = os.access(DEVICE, os.R_OK | os.W_OK, effective_ids=True)


def require_inside(inside: dict, before: dict) -> None:
    require(type(before.get("uid")) is int and before["uid"] > 0 and
            before.get("effective_uid") == before["uid"] and type(before.get("gid")) is int and
            before["gid"] > 0 and before.get("effective_gid") == before["gid"] and
            isinstance(before.get("device"), dict) and before["device"].get("path") == str(DEVICE) and
            type(before["device"].get("mode")) is int and stat.S_ISCHR(before["device"]["mode"]) and
            before["device"].get("uid") == 0 and type(before["device"].get("gid")) is int and
            before["device"]["gid"] > 0 and
            before.get("kvm_group") == {"name": "kvm", "gid": before["device"]["gid"]},
            "The original non-root identity and named kvm device group are missing")
    require(inside.get("device") == before.get("device"), "The KVM device changed across the boundary")
    require(inside.get("uid") == inside.get("effective_uid") == before["uid"] > 0 and
            inside.get("gid") == inside.get("effective_gid") == before["device"]["gid"] > 0 and
            inside.get("read_write_access") is True and
            inside.get("kvm_group") == before["kvm_group"] and
            inside.get("sudo_uid") == str(before["uid"]) and
            inside.get("sudo_gid") == str(before["gid"]),
            "The selected process did not retain the same non-root UID and existing device GID")


def sudo_arguments(before: dict, context: dict, *, listing: bool) -> list[str]:
    arguments = [SUDO, "--non-interactive", "--user=#" + str(before["uid"]),
                 "--group=#" + str(before["device"]["gid"])]
    if listing:
        arguments.append("--list")
    else:
        arguments.append("--preserve-env=" + ",".join(sorted(context["environment"])))
    return arguments + ["--", *context["child_command"]]


def write_receipt(receipt: dict) -> None:
    require(not REPORT.is_symlink(), "Refusing a linked boundary receipt")
    REPORT.write_text(json.dumps(receipt, indent=2, sort_keys=True) + "\n")


def read_receipt() -> dict:
    require(REPORT.is_file() and not REPORT.is_symlink(), "Expected this job's existing boundary receipt")
    return json.loads(REPORT.read_text())


def file_digest(path: Path) -> str:
    require(path.is_file() and not path.is_symlink(), "Expected an ordinary, unlinked tool file")
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def verify_archive(archive: Path, destination: Path) -> dict:
    checksum = file_digest(archive)
    require(checksum == EMULATOR_ARCHIVE_SHA256, "The official pinned Linux emulator archive hash differs")
    with zipfile.ZipFile(archive) as zipped:
        entries = zipped.infolist()
        require(entries and any(item.filename == "emulator/emulator" for item in entries),
                "Pinned archive has no emulator executable")
        for item in entries:
            path = Path(item.filename)
            require(not path.is_absolute() and path.parts and path.parts[0] == "emulator" and
                    ".." not in path.parts, "Archive member escapes the dedicated emulator directory")
            if stat.S_ISLNK(item.external_attr >> 16):
                target = Path(zipped.read(item).decode("utf-8"))
                require(not target.is_absolute() and
                        (destination / path.parent / target).resolve().is_relative_to(destination.resolve()),
                        "Archive link escapes the dedicated tool directory")
    return {"name": EMULATOR_ARCHIVE_NAME, "url": EMULATOR_ARCHIVE_URL,
            "sha256": checksum, "bytes": archive.stat().st_size}


def require_emulator_package(receipt: dict, *, inspect_binary: bool) -> None:
    package = receipt.get("emulator_package", {})
    expected = receipt["context"]["environment"]["PYAI_EMULATOR_BINARY"]
    require(package.get("name") == EMULATOR_ARCHIVE_NAME and package.get("url") == EMULATOR_ARCHIVE_URL and
            package.get("sha256") == EMULATOR_ARCHIVE_SHA256 and type(package.get("bytes")) is int and
            package["bytes"] > 0 and package.get("binary") == expected and
            re.fullmatch(r"[0-9a-f]{64}", package.get("binary_sha256", "")) is not None,
            "Complete checksum-pinned Linux emulator preparation evidence is missing")
    if inspect_binary:
        require(file_digest(Path(expected)) == package["binary_sha256"] and os.access(expected, os.X_OK),
                "The dedicated pinned emulator binary changed or is not executable")


def prepare_emulator() -> None:
    receipt = read_receipt()
    try:
        require(receipt.get("status") == "policy_checked" and receipt.get("scope") == MARKER and
                current_context() == receipt["context"], "The exact policy must pass before tool preparation")
        observed = {}
        observe_identity(observed)
        require(observed == receipt["before"], "The original runner identity or device changed")
        destination = Path(receipt["context"]["environment"]["RUNNER_TEMP"]) / EMULATOR_DIRECTORY
        destination.mkdir(exist_ok=False)  # Never replace a preceding or preinstalled emulator.
        archive = destination / EMULATOR_ARCHIVE_NAME
        subprocess.run(["/usr/bin/curl", "--fail", "--silent", "--show-error", "--location",
                        "--proto", "=https", "--proto-redir", "=https", "--connect-timeout", "30",
                        "--max-time", "180", "--output", str(archive), EMULATOR_ARCHIVE_URL],
                       env=receipt["context"]["environment"], timeout=190, check=True)
        receipt["emulator_package"] = verify_archive(archive, destination)
        subprocess.run(["/usr/bin/unzip", "-q", str(archive), "-d", str(destination)],
                       env=receipt["context"]["environment"], timeout=120, check=True)
        binary = receipt["context"]["environment"]["PYAI_EMULATOR_BINARY"]
        receipt["emulator_package"].update(binary=binary, binary_sha256=file_digest(Path(binary)))
        require_emulator_package(receipt, inspect_binary=True)
        archive.unlink()  # Only this command's verified temporary download; keep its exact digest.
    except Exception as error:
        receipt["status"] = "prepare_failed"
        receipt["error"] = str(error)[-2000:]
        raise
    finally:
        write_receipt(receipt)


def check_policy() -> None:
    REPORT.parent.mkdir(parents=True, exist_ok=False)
    receipt = {"schema_version": 1, "scope": MARKER, "status": "policy_failed", "before": {}}
    try:
        receipt["context"] = current_context()
        observe_identity(receipt["before"])
        observed = {}
        receipt["policy_listing"] = observed
        arguments = sudo_arguments(receipt["before"], receipt["context"], listing=True)
        observed["arguments"] = arguments
        try:
            result = subprocess.run(arguments, env=receipt["context"]["environment"], timeout=15,
                                    capture_output=True, text=True, check=False)
            observed.update(exit_code=result.returncode, stdout=result.stdout[-8192:],
                            stderr=result.stderr[-8192:],
                            output_truncated=len(result.stdout) > 8192 or len(result.stderr) > 8192)
        except subprocess.TimeoutExpired as error:
            observed.update(timeout_seconds=error.timeout,
                            stdout=str(error.stdout or "")[-8192:], stderr=str(error.stderr or "")[-8192:])
            raise
        require(observed["exit_code"] == 0 and not observed["stderr"].strip() and
                observed["output_truncated"] is False,
                "Existing sudo policy did not authorize the exact non-root user/group/command; no fallback")
        receipt["status"] = "policy_checked"
    except Exception as error:
        receipt["error"] = str(error)[-2000:]
        raise
    finally:
        write_receipt(receipt)


def enter_child() -> None:
    receipt = read_receipt()
    try:
        require(receipt.get("status") == "invoking" and receipt.get("scope") == MARKER and
                os.environ.get("PYAI_KVM_PROCESS") == MARKER and
                os.environ.get("PYAI_EMULATOR_BINARY") == receipt["context"]["environment"]["PYAI_EMULATOR_BINARY"],
                "The child requires this job's explicit pending boundary")
        require(current_context() == receipt["context"], "The child environment or checkout identity changed")
        require_emulator_package(receipt, inspect_binary=True)
        inside = {"sudo_uid": os.environ.get("SUDO_UID"), "sudo_gid": os.environ.get("SUDO_GID")}
        receipt["inside"] = inside
        observe_identity(inside)
        require_inside(inside, receipt["before"])
        receipt["status"] = "inside_verified"
        write_receipt(receipt)
        arguments = [receipt["context"]["child_command"][0], "-I",
                     str(ROOT / "scripts/android_emulator_smoke.py")]
        # Drop sudo's added variables and any policy-added environment before the fixed harness.
        os.execve(arguments[0], arguments, receipt["context"]["environment"])
    except Exception as error:
        receipt["status"] = "inside_failed"
        receipt["inside_error"] = str(error)[-2000:]
        write_receipt(receipt)
        raise


def complete_boundary(receipt: dict, context: dict) -> None:
    require(receipt.get("schema_version") == 1 and receipt.get("scope") == MARKER and
            receipt.get("status") == "passed" and receipt.get("context") == context and
            receipt.get("child_exit_code") == 0 and type(receipt.get("child_exit_code")) is int and
            "error" not in receipt and "after_error" not in receipt,
            "Complete successful process-boundary evidence is missing")
    policy = receipt.get("policy_listing", {})
    require(type(policy.get("exit_code")) is int and policy["exit_code"] == 0 and
            isinstance(policy.get("stderr"), str) and not policy["stderr"].strip() and
            policy.get("output_truncated") is False and "timeout_seconds" not in policy and
            policy.get("arguments") == sudo_arguments(receipt["before"], context, listing=True),
            "The exact command's complete policy listing is missing")
    require_inside(receipt.get("inside", {}), receipt["before"])
    require_emulator_package(receipt, inspect_binary=False)
    require(receipt.get("invocation_before") == receipt["before"] == receipt.get("after"),
            "The invoking user, groups, device or access changed during the boundary")


def run_harness() -> None:
    receipt = read_receipt()
    final_error = None
    try:
        require(receipt.get("status") == "policy_checked" and receipt.get("scope") == MARKER,
                "The exact command policy must pass before provisioning and invocation")
        require(current_context() == receipt["context"], "The preflight environment or checkout changed")
        require_emulator_package(receipt, inspect_binary=True)
        receipt["invocation_before"] = {}
        observe_identity(receipt["invocation_before"])
        require(receipt["invocation_before"] == receipt["before"], "The original runner identity or device changed")
        receipt["status"] = "invoking"
        write_receipt(receipt)
        result = subprocess.run(sudo_arguments(receipt["before"], receipt["context"], listing=False),
                                env=receipt["context"]["environment"], check=False)
        # The child writes its identity before exec; preserve that independently observed phase.
        receipt = read_receipt()
        receipt["child_exit_code"] = result.returncode
        require(result.returncode == 0, "The exact sudo/child/harness invocation failed; no fallback")
        require(receipt.get("status") == "inside_verified", "The child never verified its credential boundary")
        require_inside(receipt.get("inside", {}), receipt["before"])
        receipt["status"] = "passed"
    except Exception as error:
        receipt["status"] = "failed"
        receipt["error"] = str(error)[-2000:]
        raise
    finally:
        receipt["after"] = {}
        try:
            observe_identity(receipt["after"])
            require(receipt["after"] == receipt["before"], "The invoking identity, groups or device changed")
        except Exception as error:
            receipt["after_error"] = str(error)[-2000:]
            receipt["status"] = "failed"
            final_error = error
        if receipt["status"] == "passed":
            try:
                complete_boundary(receipt, current_context())
            except Exception as error:
                receipt["error"] = str(error)[-2000:]
                receipt["status"] = "failed"
                final_error = error
        write_receipt(receipt)
    if final_error is not None:
        raise final_error


def main() -> int:
    try:
        require(len(sys.argv) == 2, "Select exactly --check-policy, --prepare-emulator, --run or --child")
        action = {"--check-policy": check_policy, "--prepare-emulator": prepare_emulator,
                  "--run": run_harness, "--child": enter_child}.get(sys.argv[1])
        require(action is not None, "Unknown process-boundary action")
        action()
        return 0
    except Exception as error:
        print("Hosted KVM process boundary failed: " + str(error), file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
