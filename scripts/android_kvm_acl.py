#!/usr/bin/env python3
"""Provision one temporary, named-user KVM ACL on a fresh hosted Ubuntu job.

This is a separate explicit provisioning boundary. It never retries or falls back
from the retired same-UID/RunAs-group experiment in android_kvm_process.py.
"""

from __future__ import annotations

import importlib.util
import json
import os
from pathlib import Path
import signal
import stat
import subprocess
import sys


ROOT = Path(__file__).resolve().parents[1]
_spec = importlib.util.spec_from_file_location("_kvm_shared", ROOT / "scripts/android_kvm_process.py")
shared = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(shared)
_lifecycle_spec = importlib.util.spec_from_file_location("_acl_lifecycle", ROOT / "scripts/android_acl_lifecycle.py")
lifecycle = importlib.util.module_from_spec(_lifecycle_spec)
_lifecycle_spec.loader.exec_module(lifecycle)
require = shared.require
BoundaryFailure = shared.BoundaryFailure

REPORT = ROOT / "preview/build/reports/kvm-acl/receipt.json"
DEVICE = Path("/dev/kvm")
GETFACL = "/usr/bin/getfacl"
SETFACL = "/usr/bin/setfacl"
SUDO = "/usr/bin/sudo"
MARKER = "same-uid-temporary-device-acl-v1"
DISPLAY_PROFILE = "raster-720x1280-280"
BASE_ACL = ("user::rw-", "group::rw-", "other::---")
COMMAND_ENVIRONMENT = {"PATH": "/usr/bin:/bin", "LANG": "C.UTF-8", "LC_ALL": "C.UTF-8"}
ACL_ARGUMENTS = [GETFACL, "--access", "--omit-header", "--no-effective", "--numeric",
                 "--absolute-names", "--", str(DEVICE)]
OUTPUT_LIMIT = 8192


def current_context() -> dict:
    # Reuse only the reviewed source/APK/host/environment checks and tool pinning.
    # No legacy command-policy, credential-selection or execution function runs.
    context = shared.current_context()
    profile = os.environ.get("PYAI_EMULATOR_DISPLAY_PROFILE")
    require(profile == DISPLAY_PROFILE,
            "The ACL experiment requires its explicit reviewed startup display profile")
    context["environment"]["PYAI_KVM_PROCESS"] = MARKER
    context["environment"]["PYAI_EMULATOR_DISPLAY_PROFILE"] = profile
    context["child_command"] = [context["child_command"][0], "-I",
                                str(ROOT / "scripts/android_kvm_acl.py"), "--child"]
    return context


def write_receipt(receipt: dict) -> None:
    require(not REPORT.is_symlink(), "Refusing a linked ACL receipt")
    temporary = REPORT.with_name(REPORT.name + "." + str(os.getpid()) + ".tmp")
    created = False
    try:
        with temporary.open("x") as output:
            created = True
            output.write(json.dumps(receipt, indent=2, sort_keys=True) + "\n")
            output.flush()
            os.fsync(output.fileno())
        os.replace(temporary, REPORT)
        created = False
        directory = os.open(REPORT.parent, os.O_RDONLY | os.O_DIRECTORY)
        try:
            os.fsync(directory)
        finally:
            os.close(directory)
    finally:
        if created:
            temporary.unlink(missing_ok=True)


def read_receipt() -> dict:
    require(REPORT.is_file() and not REPORT.is_symlink(), "Expected this job's ACL receipt")
    return json.loads(REPORT.read_text())


def persist_preserving_error(receipt: dict, failure=None):
    try:
        write_receipt(receipt)
    except BaseException as error:
        if failure is None:
            return error
        print("ACL receipt persistence also failed: " + str(error)[-2000:], file=sys.stderr)
    return failure


def tool_identity() -> dict:
    observed = {}
    for name in (SUDO, GETFACL, SETFACL):
        path = Path(name)
        metadata = path.lstat()
        require(stat.S_ISREG(metadata.st_mode) and metadata.st_uid == 0 and
                metadata.st_mode & 0o022 == 0 and os.access(path, os.X_OK),
                "Expected a root-owned, unlinked, non-writable system ACL/sudo tool")
        for parent in path.parents:
            parent_metadata = parent.lstat()
            require(stat.S_ISDIR(parent_metadata.st_mode) and parent_metadata.st_uid == 0 and
                    parent_metadata.st_mode & 0o022 == 0,
                    "A system tool parent is linked or writable by another identity")
        observed[name] = {"mode": metadata.st_mode, "uid": metadata.st_uid,
                          "gid": metadata.st_gid, "sha256": shared.file_digest(path)}
    return observed


def observe_command(arguments: list[str], observed: dict, *, timeout: int = 10) -> None:
    observed.update(arguments=arguments)
    try:
        result = subprocess.run(arguments, env=COMMAND_ENVIRONMENT, timeout=timeout,
                                capture_output=True, text=True, check=False)
        observed.update(exit_code=result.returncode, stdout=result.stdout[-OUTPUT_LIMIT:],
                        stderr=result.stderr[-OUTPUT_LIMIT:],
                        output_truncated=len(result.stdout) > OUTPUT_LIMIT or len(result.stderr) > OUTPUT_LIMIT)
    except subprocess.TimeoutExpired as error:
        def text(value):
            return value.decode("utf-8", errors="replace") if isinstance(value, bytes) else (value or "")
        observed.update(timeout_seconds=error.timeout, stdout=text(error.stdout)[-OUTPUT_LIMIT:],
                        stderr=text(error.stderr)[-OUTPUT_LIMIT:])
        raise
    except Exception as error:
        observed["error"] = str(error)[-2000:]
        raise


def command_passes(observed: dict) -> bool:
    return (isinstance(observed, dict) and type(observed.get("exit_code")) is int and
            observed["exit_code"] == 0 and isinstance(observed.get("stdout"), str) and
            observed.get("stderr") == "" and observed.get("output_truncated") is False and
            not any(key in observed for key in ("timeout_seconds", "error", "interrupted_signals",
                                                "supervisor_error", "supervisor_interruption_error")))


def acl_entries(observed: dict) -> tuple[str, ...]:
    require(command_passes(observed) and observed.get("arguments") == ACL_ARGUMENTS,
            "A complete successful exact-device ACL read is required")
    return tuple(line for line in observed["stdout"].splitlines() if line)


def granted_acl(uid: int) -> tuple[str, ...]:
    require(type(uid) is int and uid > 0, "Only the original non-root numeric UID may receive access")
    return ("user::rw-", f"user:{uid}:rw-", "group::rw-", "mask::rw-", "other::---")


def observe_state(destination: dict) -> None:
    shared.observe_identity(destination)
    require(destination["device"]["mode"] == stat.S_IFCHR | 0o660,
            "The existing root:kvm device must retain mode 0660")
    destination["acl"] = {}
    observe_command(ACL_ARGUMENTS, destination["acl"])


def identity_without_access(state: dict) -> dict:
    return {key: value for key, value in state.items() if key not in ("acl", "read_write_access")}


def process_credentials(state: dict) -> dict:
    return {key: state[key] for key in ("uid", "effective_uid", "gid", "effective_gid", "supplementary_groups")}


def require_original(state: dict) -> None:
    require(type(state.get("uid")) is int and state["uid"] > 0 and
            type(state.get("effective_uid")) is int and state["uid"] == state["effective_uid"] and
            type(state.get("gid")) is int and state["gid"] > 0 and
            type(state.get("effective_gid")) is int and state["gid"] == state["effective_gid"] and
            isinstance(state.get("supplementary_groups"), list) and
            all(type(group) is int and group >= 0 for group in state["supplementary_groups"]) and
            state["supplementary_groups"] == sorted(set(state["supplementary_groups"])),
            "The original real/effective user and groups must remain non-root and explicit")
    device = state.get("device", {})
    require(isinstance(device, dict) and device.get("path") == str(DEVICE) and
            type(device.get("mode")) is int and device["mode"] == stat.S_IFCHR | 0o660 and
            type(device.get("uid")) is int and device["uid"] == 0 and
            type(device.get("gid")) is int and device["gid"] > 0 and
            all(type(device.get(key)) is int and device[key] > 0
                for key in ("filesystem_device", "inode", "rdev")) and
            state.get("kvm_group") == {"name": "kvm", "gid": device["gid"]},
            "The existing character device must be root-owned with the named kvm group")
    require(state.get("read_write_access") is False and acl_entries(state.get("acl")) == BASE_ACL,
            "Provisioning requires the exact inaccessible base ACL; no existing ACL is replaced")


def require_granted(state: dict, before: dict) -> None:
    require_original(before)
    require(identity_without_access(state) == identity_without_access(before) and
            state.get("read_write_access") is True and
            acl_entries(state.get("acl")) == granted_acl(before["uid"]),
            "The temporary ACL changed another identity/device/right or did not grant original-user access")


def acl_command(uid: int, *, restore: bool, listing: bool = False) -> list[str]:
    granted_acl(uid)  # Reject booleans, root and arbitrary command-line text.
    operation = "--set=" + ",".join(BASE_ACL) if restore else f"--modify=user:{uid}:rw-,mask::rw-"
    arguments = [SUDO, "--non-interactive", "--user=#0"]
    if listing:
        arguments.append("--list")
    return arguments + ["--", SETFACL, "--no-mask", operation, "--", str(DEVICE)]


def require_policy(receipt: dict) -> None:
    require_original(receipt["before"])
    for name, restore in (("grant", False), ("restore", True)):
        observation = receipt.get("policy", {}).get(name, {})
        require(command_passes(observation) and observation.get("arguments") ==
                acl_command(receipt["before"]["uid"], restore=restore, listing=True),
                "Both exact existing privileged-tooling policies must authorize grant and restoration")


def require_tools(receipt: dict) -> None:
    tools = receipt.get("tools", {})
    require(isinstance(tools, dict) and set(tools) == {SUDO, GETFACL, SETFACL}, "Exact trusted tool identities are missing")
    for observed in tools.values():
        require(isinstance(observed, dict) and type(observed.get("mode")) is int and stat.S_ISREG(observed["mode"]) and
                observed["mode"] & 0o022 == 0 and type(observed.get("uid")) is int and
                observed["uid"] == 0 and type(observed.get("gid")) is int and observed["gid"] >= 0 and
                isinstance(observed.get("sha256"), str) and len(observed["sha256"]) == 64 and
                all(value in "0123456789abcdef" for value in observed["sha256"]),
                "A trusted system tool receipt is incomplete")
    for path, name in ((GETFACL, "getfacl"), (SETFACL, "setfacl")):
        observation = receipt.get("tool_versions", {}).get(name, {})
        require(command_passes(observation) and observation.get("arguments") == [path, "--version"] and
                observation["stdout"] == name + " 2.3.2\n", "Complete pinned ACL tool-version evidence is missing")


def observe_acl_command(receipt: dict, observed: dict, *, restore: bool) -> None:
    arguments = acl_command(receipt["before"]["uid"], restore=restore)
    observed.update(arguments=arguments, quiescent=False)
    write_receipt(receipt)  # No command can start without its preceding checkpoint.
    failure = None
    try:
        lifecycle.observe(arguments, COMMAND_ENVIRONMENT, process_credentials(receipt["before"]), observed)
    except BaseException as error:
        failure = error
    failure = persist_preserving_error(receipt, failure)
    if failure is not None:
        raise failure


def check_policy() -> None:
    REPORT.parent.mkdir(parents=True, exist_ok=False)
    receipt = {"schema_version": 1, "scope": MARKER, "status": "policy_failed", "before": {}}
    try:
        receipt["context"] = current_context()
        receipt["tools"] = tool_identity()
        receipt["tool_versions"] = {}
        for path, name in ((GETFACL, "getfacl"), (SETFACL, "setfacl")):
            observation = receipt["tool_versions"][name] = {}
            observe_command([path, "--version"], observation)
            require(command_passes(observation) and observation["stdout"] == name + " 2.3.2\n",
                    "The existing Ubuntu ACL tool version differs from the reviewed 2.3.2 tools")
        observe_state(receipt["before"])
        require_original(receipt["before"])
        receipt["policy"] = {}
        for name, restore in (("grant", False), ("restore", True)):
            observation = receipt["policy"][name] = {}
            observe_command(acl_command(receipt["before"]["uid"], restore=restore, listing=True),
                            observation, timeout=15)
            require(command_passes(observation), "Exact " + name + " policy denied or ambiguous; no fallback")
        require_policy(receipt)
        require_tools(receipt)
        receipt["status"] = "policy_checked"
    except Exception as error:
        receipt["error"] = str(error)[-2000:]
        raise
    finally:
        write_receipt(receipt)


def require_pending(receipt: dict) -> None:
    require(receipt.get("scope") == MARKER and receipt.get("status") == "policy_checked" and
            current_context() == receipt.get("context") and tool_identity() == receipt.get("tools"),
            "The prepared ACL context or trusted tools changed")
    require_policy(receipt)
    require_tools(receipt)
    state = {}
    observe_state(state)
    require(state == receipt["before"], "The original device, ACL or process identity changed")


def prepare_emulator() -> None:
    receipt = read_receipt()
    try:
        require_pending(receipt)
        destination = Path(receipt["context"]["environment"]["RUNNER_TEMP"]) / shared.EMULATOR_DIRECTORY
        destination.mkdir(exist_ok=False)
        archive = destination / shared.EMULATOR_ARCHIVE_NAME
        subprocess.run(["/usr/bin/curl", "--fail", "--silent", "--show-error", "--location",
                        "--proto", "=https", "--proto-redir", "=https", "--connect-timeout", "30",
                        "--max-time", "180", "--output", str(archive), shared.EMULATOR_ARCHIVE_URL],
                       env=receipt["context"]["environment"], timeout=190, check=True)
        receipt["emulator_package"] = shared.verify_archive(archive, destination)
        subprocess.run(["/usr/bin/unzip", "-q", str(archive), "-d", str(destination)],
                       env=receipt["context"]["environment"], timeout=120, check=True)
        binary = receipt["context"]["environment"]["PYAI_EMULATOR_BINARY"]
        receipt["emulator_package"].update(binary=binary, binary_sha256=shared.file_digest(Path(binary)))
        shared.require_emulator_package(receipt, inspect_binary=True)
        archive.unlink()
    except Exception as error:
        receipt["status"] = "prepare_failed"
        receipt["error"] = str(error)[-2000:]
        raise
    finally:
        write_receipt(receipt)


def restore_acl(receipt: dict) -> None:
    previous = receipt.get("cleanup")
    if previous is not None:
        require(len(receipt.get("cleanup_attempts", [])) < 1, "Only one bounded workflow cleanup recovery is allowed")
        receipt.setdefault("cleanup_attempts", []).append(previous)
    cleanup = receipt["cleanup"] = {"status": "failed", "before": {}, "after": {}}
    try:
        require(receipt.get("scope") == MARKER and receipt.get("grant_attempted") is True,
                "Restoration requires this job's recorded ACL grant attempt")
        require_policy(receipt)
        require_tools(receipt)
        require(lifecycle.quiescence_passes(receipt.get("grant", {}), process_credentials(receipt["before"])),
                "The grant subtree may still run; refusing a racing restoration")
        if previous and "command" in previous:
            require(lifecycle.quiescence_passes(previous["command"], process_credentials(receipt["before"])),
                    "The previous restoration subtree may still run; no further device mutation")
        require(tool_identity() == receipt["tools"], "The trusted restoration tools changed")
        observe_state(cleanup["before"])
        require(identity_without_access(cleanup["before"]) == identity_without_access(receipt["before"]),
                "The device or process identity changed; refusing an unrelated restoration target")
        require(acl_entries(cleanup["before"]["acl"]) in (BASE_ACL, granted_acl(receipt["before"]["uid"])),
                "The device has an unrelated ACL; refusing to replace another change")
        cleanup["command"] = {}
        observe_acl_command(receipt, cleanup["command"], restore=True)
        require(command_passes(cleanup["command"]) and
                lifecycle.quiescence_passes(cleanup["command"], process_credentials(receipt["before"])),
                "Exact ACL restoration or its command-subtree completion failed")
        observe_state(cleanup["after"])
        require(cleanup["after"] == receipt["before"], "The exact original ACL/device/access was not restored")
        cleanup["status"] = "restored"
    except BaseException as error:
        cleanup["error"] = str(error)[-2000:]
        raise


def enter_child() -> None:
    receipt = read_receipt()
    try:
        require(receipt.get("scope") == MARKER and receipt.get("status") == "invoking" and
                os.environ.get("PYAI_KVM_PROCESS") == MARKER and
                os.environ.get("PYAI_EMULATOR_BINARY") == receipt["context"]["environment"]["PYAI_EMULATOR_BINARY"],
                "The unprivileged child requires this exact pending ACL boundary")
        require(current_context() == receipt["context"] and tool_identity() == receipt["tools"],
                "Child source/environment/system tools changed")
        shared.require_emulator_package(receipt, inspect_binary=True)
        receipt["inside"] = {}
        observe_state(receipt["inside"])
        require_granted(receipt["inside"], receipt["before"])
        receipt["status"] = "inside_verified"
        write_receipt(receipt)
        arguments = [receipt["context"]["child_command"][0], "-I",
                     str(ROOT / "scripts/android_emulator_smoke.py")]
        os.execve(arguments[0], arguments, receipt["context"]["environment"])
    except BaseException as error:
        receipt["status"] = "inside_failed"
        receipt["inside_error"] = str(error)[-2000:]
        write_receipt(receipt)
        raise


def wait_for_child(receipt: dict) -> int:
    process = subprocess.Popen(receipt["context"]["child_command"],
                               env=receipt["context"]["environment"], start_new_session=True)
    try:
        return process.wait()
    except BaseException:
        # Only the new session created by this Popen belongs to this boundary.
        # Let the existing harness finally clean up first; bound forced interruption.
        try:
            if process.poll() is None and os.getpgid(process.pid) == process.pid:
                os.killpg(process.pid, signal.SIGINT)
                try:
                    process.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    if process.poll() is None and os.getpgid(process.pid) == process.pid:
                        os.killpg(process.pid, signal.SIGKILL)
                    process.wait(timeout=2)
        except BaseException as error:
            receipt["child_interruption_error"] = str(error)[-2000:]
        raise


def complete_boundary(receipt: dict, context: dict) -> None:
    require(isinstance(context, dict) and isinstance(context.get("environment"), dict) and
            context["environment"].get("PYAI_EMULATOR_DISPLAY_PROFILE") == DISPLAY_PROFILE,
            "The ACL receipt context is missing its explicit startup display profile")
    require(receipt.get("schema_version") == 1 and receipt.get("scope") == MARKER and
            receipt.get("status") == "passed" and receipt.get("context") == context and
            receipt.get("grant_attempted") is True and type(receipt.get("child_exit_code")) is int and
            receipt["child_exit_code"] == 0 and not any(key in receipt for key in
                                                     ("error", "inside_error", "cleanup_error", "child_interruption_error",
                                                      "child_receipt_error")),
            "Complete successful ACL-boundary evidence is missing")
    require_policy(receipt)
    require_tools(receipt)
    shared.require_emulator_package(receipt, inspect_binary=False)
    grant = receipt.get("grant", {})
    require(command_passes(grant) and grant.get("arguments") ==
            acl_command(receipt["before"]["uid"], restore=False) and
            lifecycle.quiescence_passes(grant, process_credentials(receipt["before"])),
            "The exact named-user ACL grant and its subtree completion are missing")
    require_granted(receipt.get("granted", {}), receipt["before"])
    require_granted(receipt.get("inside", {}), receipt["before"])
    cleanup = receipt.get("cleanup", {})
    restored = cleanup.get("command", {})
    cleanup_before = cleanup.get("before", {})
    require(cleanup.get("status") == "restored" and "error" not in cleanup and
            identity_without_access(cleanup_before) == identity_without_access(receipt["before"]) and
            acl_entries(cleanup_before.get("acl")) in (BASE_ACL, granted_acl(receipt["before"]["uid"])) and
            command_passes(restored) and restored.get("arguments") ==
            acl_command(receipt["before"]["uid"], restore=True) and
            lifecycle.quiescence_passes(restored, process_credentials(receipt["before"])) and
            not receipt.get("cleanup_attempts") and
            cleanup.get("after") == receipt["before"], "The exact original ACL restoration is missing")


def run_harness() -> None:
    receipt = read_receipt()
    failure = None
    attempted = False
    child_started = False
    previous_handler = signal.getsignal(signal.SIGTERM)
    def interrupted(signum, frame):
        raise BoundaryFailure("The owned ACL execution was interrupted by signal " + str(signum))
    signal.signal(signal.SIGTERM, interrupted)
    try:
        require_pending(receipt)
        shared.require_emulator_package(receipt, inspect_binary=True)
        receipt.update(status="grant_attempted", grant_attempted=True, grant={})
        write_receipt(receipt)  # Durable before a possibly partial/timeout privilege command.
        attempted = True
        observe_acl_command(receipt, receipt["grant"], restore=False)
        require(command_passes(receipt["grant"]) and
                lifecycle.quiescence_passes(receipt["grant"], process_credentials(receipt["before"])),
                "Exact named-user ACL grant or subtree completion failed; no fallback")
        receipt["granted"] = {}
        observe_state(receipt["granted"])
        require_granted(receipt["granted"], receipt["before"])
        receipt["status"] = "invoking"
        write_receipt(receipt)
        child_started = True
        exit_code = wait_for_child(receipt)
        child = read_receipt()
        require(child.get("context") == receipt["context"] and child.get("before") == receipt["before"],
                "The child replaced its original ACL/source authority")
        for key in ("inside", "inside_error"):
            if key in child:
                receipt[key] = child[key]
        receipt["child_exit_code"] = exit_code
        require(type(exit_code) is int and exit_code == 0 and child.get("status") == "inside_verified",
                "The original-user child or installed harness failed")
        require_granted(receipt.get("inside", {}), receipt["before"])
        receipt["status"] = "child_completed"
    except BaseException as error:
        failure = error
        receipt["status"] = "failed"
        receipt["error"] = str(error)[-2000:]
        if child_started:
            try:
                child = read_receipt()
                require(child.get("context") == receipt["context"] and child.get("before") == receipt["before"],
                        "The failed child's receipt changed source or ACL authority")
                for key in ("inside", "inside_error"):
                    if key in child:
                        receipt[key] = child[key]
            except BaseException as child_error:
                receipt["child_receipt_error"] = str(child_error)[-2000:]
    finally:
        if attempted:
            try:
                restore_acl(receipt)
            except BaseException as error:
                receipt["status"] = "failed"
                receipt["cleanup_error"] = str(error)[-2000:]
                if failure is None:
                    failure = error
        if receipt.get("status") == "child_completed" and failure is None:
            receipt["status"] = "passed"
            try:
                complete_boundary(receipt, current_context())
            except BaseException as error:
                receipt["status"] = "failed"
                receipt["error"] = str(error)[-2000:]
                if failure is None:
                    failure = error
        try:
            failure = persist_preserving_error(receipt, failure)
        finally:
            signal.signal(signal.SIGTERM, previous_handler)
    if failure is not None:
        raise failure


def cleanup_after_step() -> None:
    """An always-run workflow step can finish a recorded interrupted restoration."""
    receipt = read_receipt()
    if receipt.get("grant_attempted") is not True:
        require(receipt.get("scope") == MARKER, "Unrecognized no-grant receipt")
        print("No ACL grant was attempted; no privileged cleanup is needed")
        return
    try:
        if receipt.get("cleanup", {}).get("status") == "restored":
            require_tools(receipt)
            require(tool_identity() == receipt["tools"], "The trusted final ACL observation tools changed")
            state = {}
            observe_state(state)
            require(state == receipt["before"], "The restored ACL/device/access changed after the owned step")
            return
        # Recovery may restore access, but it cannot rescue the interrupted run.
        receipt["status"] = "failed"
        receipt.setdefault("error", "The original ACL execution did not finish its cleanup")
        restore_acl(receipt)
    except BaseException as error:
        receipt["status"] = "failed"
        receipt["cleanup_error"] = str(error)[-2000:]
        raise
    finally:
        write_receipt(receipt)


def main() -> int:
    try:
        require(len(sys.argv) == 2, "Select exactly one ACL-boundary action")
        action = {"--check-policy": check_policy, "--prepare-emulator": prepare_emulator,
                  "--run": run_harness, "--child": enter_child, "--restore": cleanup_after_step}.get(sys.argv[1])
        require(action is not None, "Unknown ACL-boundary action")
        action()
        return 0
    except Exception as error:
        print("Hosted KVM ACL boundary failed: " + str(error), file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
