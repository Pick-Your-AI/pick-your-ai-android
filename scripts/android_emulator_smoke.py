#!/usr/bin/env python3
"""Exercise the synthetic APK only in a fresh AVD owned by this hosted CI job."""

from __future__ import annotations

import contextlib
import hashlib
import json
import os
from pathlib import Path
import platform
import re
import signal
import stat
import subprocess
import sys
import tempfile
import time
import uuid
import xml.etree.ElementTree as ET


PACKAGE = "ai.pickyour.android.preview"
ACTIVITY = PACKAGE + ".PreviewActivity"
SERIAL = "emulator-5554"
SYSTEM_IMAGE = "system-images;android-35;default;x86_64"
IMAGE_REVISION = "2"
HVF_EMULATOR_VERSION = "37.1.11.0"
BOOT_TIMEOUT_SECONDS_BY_MODE = {"kvm": 300, "hvf": 600, "software": 900}
HVF_HOST_IDENTITY = {"runner_environment": "github-hosted", "runner_os": "macOS",
                     "runner_arch": "X64", "system": "Darwin", "machine": "x86_64"}
CASE_IDS = (
    "installed_synthetic_launch",
    "acknowledged_save_survives_force_stop",
    "pending_fields_survive_rotation",
    "confirmed_deletion_does_not_resurrect",
)
TITLE_LABEL = "Draft title (up to 120 characters)"
OBJECTIVE_LABEL = "Draft objective (optional)"
CONTEXT_LABEL = "Draft context (optional)"
BUTTON = "android.widget.Button"
EDITOR = "android.widget.EditText"
UI_DUMP_DIRECTORY = "/data/local/tmp"
UI_DUMP_PATH = UI_DUMP_DIRECTORY + "/pyai-smoke-window.xml"
HOME_PACKAGE = "com.android.launcher3"
HOME_WINDOW = HOME_PACKAGE + "/" + HOME_PACKAGE + ".uioverrides.QuickstepLauncher"
FRAMEWORK_BARRIER = ("shell", "am", "wait-for-broadcast-barrier", "--flush-application-threads")
HOME_FOCUS_QUERY = ("shell", "dumpsys", "-t", "3", "activity", "activities")
GUEST_READINESS_COMMANDS = (
    ("boot_completed", ("getprop", "sys.boot_completed"), ("1",)),
    ("boot_animation", ("getprop", "init.svc.bootanim"), ("", "stopped")),
    ("current_user", ("am", "get-current-user"), ("0",)),
    ("user_state", ("am", "get-started-user-state", "0"), ("RUNNING_UNLOCKED",)),
    ("package_manager", ("pm", "path", "android"), ("package:/system/framework/framework-res.apk",)),
    ("ui_dump_directory", ("test", "-d", UI_DUMP_DIRECTORY), ("",)),
    ("ui_dump_readable", ("test", "-r", UI_DUMP_DIRECTORY), ("",)),
    ("ui_dump_writable", ("test", "-w", UI_DUMP_DIRECTORY), ("",)),
    ("ui_dump_searchable", ("test", "-x", UI_DUMP_DIRECTORY), ("",)),
)
BOOT_FAILURE_COMMANDS = (
    ("adb_state", ("get-state",)),
    ("emulator_name", ("emu", "avd", "name")),
    ("emulator_property", ("shell", "getprop", "ro.kernel.qemu")),
    ("boot_animation", ("shell", "getprop", "init.svc.bootanim")),
)
CASE_FAILURE_COMMANDS = (
    ("framework_log", ("logcat", "-b", "main", "-b", "system", "-b", "crash", "-b", "events",
                       "-d", "-v", "threadtime", "-t", "2000", "ActivityManager:I",
                       "ActivityTaskManager:I", "AndroidRuntime:E", "System.err:W", "lmkd:I",
                       "lowmemorykiller:I", "am_proc_start:I", "am_proc_bound:I", "am_proc_died:I",
                       "am_kill:I", "am_crash:I", "am_anr:I", "am_low_memory:I",
                       "wm_activity_launch_time:I", "*:S"), 131072),
    ("package_pid", ("shell", "pidof", PACKAGE), 32768),
    ("package_exit_history", ("shell", "dumpsys", "-t", "3", "activity", "exit-info", PACKAGE), 32768),
    ("activity_stack", ("shell", "dumpsys", "-t", "3", "activity", "activities"), 32768),
    ("package_processes", ("shell", "dumpsys", "-t", "3", "activity", "processes", PACKAGE), 32768),
    ("low_memory_killer", ("shell", "dumpsys", "-t", "3", "activity", "lmk"), 32768),
)
GUEST_ANR_FAILURE_COMMANDS = (
    ("last_anr_state", ("shell", "dumpsys", "-t", "3", "activity", "lastanr"), 131072),
    ("last_anr_traces", ("shell", "dumpsys", "-t", "3", "activity", "lastanr-traces"), 131072),
)


class SmokeFailure(RuntimeError):
    pass


def require(condition: bool, message: str) -> None:
    if not condition:
        raise SmokeFailure(message)


def check_kvm_access(device: Path = Path("/dev/kvm")) -> None:
    """Require existing runner access; never repair ownership, modes or groups."""
    print(f"KVM preflight: effective_uid={os.geteuid()} groups={os.getgroups()} device={device}")
    try:
        metadata = device.lstat()
    except OSError:
        raise SmokeFailure("KVM device is absent or unreadable; no host permissions were changed") from None
    print(f"KVM device: mode={stat.filemode(metadata.st_mode)} owner={metadata.st_uid} group={metadata.st_gid}")
    require(stat.S_ISCHR(metadata.st_mode), "KVM path is not an existing character device")
    require(os.access(device, os.R_OK | os.W_OK, effective_ids=True),
            "KVM read/write access is unavailable; no host permissions were changed")


def hvf_host_passes(host: dict) -> bool:
    if not (isinstance(host, dict) and all(host.get(key) == value for key, value in HVF_HOST_IDENTITY.items())
            and host.get("kern_hv_support") == "1" and "error" not in host):
        return False
    observed = host.get("support_check")
    return (hvf_command_passes(observed) and len(observed["stdout"]) <= 1024
            and observed["stdout"].strip() == "1")


def hvf_command_passes(observed: dict) -> bool:
    return (isinstance(observed, dict) and type(observed.get("exit_code")) is int
            and observed["exit_code"] == 0 and observed.get("stderr") == ""
            and isinstance(observed.get("stdout"), str) and observed.get("output_truncated") is False
            and "command_timeout_seconds" not in observed and "error" not in observed)


def observe_hvf_command(args: list[str], observed: dict, *, timeout: int, output_limit: int) -> None:
    """Keep bounded complete-result or timeout evidence in the caller's receipt."""
    observed.clear()
    try:
        result = command(args, timeout=timeout, check=False)
        observed.update(exit_code=result.returncode,
                        stdout=bounded_text(result.stdout, output_limit),
                        stderr=bounded_text(result.stderr, output_limit),
                        output_truncated=len(result.stdout) > output_limit or len(result.stderr) > output_limit)
    except subprocess.TimeoutExpired as error:
        observed.update(command_timeout_seconds=error.timeout,
                        stdout=bounded_text(error.stdout, output_limit),
                        stderr=bounded_text(error.stderr, output_limit),
                        output_truncated=len(error.stdout or "") > output_limit or len(error.stderr or "") > output_limit)
        raise
    except Exception as error:
        observed["error"] = bounded_text(str(error), 1200)
        raise


def check_hvf_access(observed: dict | None = None) -> dict:
    """Observe the explicit hosted Intel Mac; never install or enable a hypervisor."""
    host = {} if observed is None else observed
    host.clear()
    host.update(runner_environment=os.environ.get("RUNNER_ENVIRONMENT"),
                runner_os=os.environ.get("RUNNER_OS"), runner_arch=os.environ.get("RUNNER_ARCH"),
                system=platform.system(), machine=platform.machine(), macos_version=platform.mac_ver()[0])
    try:
        require(os.environ.get("GITHUB_ACTIONS") == "true" and
                all(host[key] == value for key, value in HVF_HOST_IDENTITY.items()),
                "HVF mode requires the explicit GitHub-hosted Intel macOS runner")
        emulator = Path(os.environ["ANDROID_HOME"]) / "emulator/emulator"
        require(emulator.is_file() and not emulator.is_symlink(), "Expected the existing Intel macOS emulator")
        support = {}
        host["support_check"] = support
        observe_hvf_command(["/usr/sbin/sysctl", "-n", "kern.hv_support"], support, timeout=10, output_limit=1024)
        require(hvf_command_passes(support) and len(support["stdout"]) <= 1024
                and support["stdout"].strip() == "1",
                "Existing Hypervisor.Framework support is unavailable; no host setting was changed")
        host["kern_hv_support"] = support["stdout"].strip()
        return host
    except Exception as error:
        host["error"] = bounded_text(str(error), 1200)
        raise
    finally:
        print("HVF host preflight: " + json.dumps(host, sort_keys=True), flush=True)


def hvf_acceleration_passes(observed: dict) -> bool:
    return (hvf_command_passes(observed) and len(observed["stdout"]) <= 2048
            and re.fullmatch(r"accel:\r?\n0\r?\nHypervisor\.Framework OS X Version [0-9]+(?:\.[0-9]+)*\r?\naccel\r?\n?",
                             observed["stdout"]) is not None)


def check_hvf_acceleration(emulator: Path, observed: dict | None = None) -> dict:
    observed = {} if observed is None else observed
    observed.clear()
    try:
        require(emulator.is_file() and not emulator.is_symlink(), "Expected the existing Intel macOS emulator")
        observe_hvf_command([str(emulator), "-accel-check"], observed, timeout=30, output_limit=2048)
        require(hvf_acceleration_passes(observed),
                "The existing emulator did not confirm usable Hypervisor.Framework acceleration")
        return observed
    except Exception as error:
        observed["error"] = bounded_text(str(error), 1200)
        raise
    finally:
        print("HVF acceleration preflight: " + json.dumps(observed, sort_keys=True), flush=True)


def require_apk_handoff(actual_hash: str, expected_hash: str | None) -> None:
    require(isinstance(expected_hash, str) and re.fullmatch(r"[0-9a-f]{64}", expected_hash) is not None
            and expected_hash == actual_hash,
            "Unsigned APK does not match the successful Linux build's exact SHA-256 handoff")


def emulation_settings(receipt: dict | None = None) -> dict:
    """Select one explicit mode; an unavailable mode never becomes a skipped pass."""
    mode = os.environ.get("PYAI_EMULATOR_MODE", "kvm")
    require(mode in ("kvm", "software", "hvf"), "Unknown emulator mode; expected kvm, software or hvf")
    if mode == "kvm":
        check_kvm_access()
    settings = {"mode": mode, "accel_argument": "off" if mode == "software" else "on",
                "boot_timeout_seconds": BOOT_TIMEOUT_SECONDS_BY_MODE[mode],
                "unsupported_software_opt_in": mode == "software"}
    if receipt is not None:
        receipt["emulation"] = settings
    if mode == "hvf":
        host = {}
        settings["host"] = host
        check_hvf_access(host)
    print("Emulation settings: " + json.dumps(settings, sort_keys=True))
    return settings


def emulation_environment(environment: dict, mode: str) -> dict:
    require(mode in ("kvm", "software", "hvf"), "Unknown emulator environment mode")
    selected = dict(environment)
    selected.pop("ANDROID_I_WANT_MY_TCG", None)
    if mode == "software":
        selected["ANDROID_I_WANT_MY_TCG"] = "yes"
    return selected


def command(args: list[str], *, env=None, timeout=30, input=None, check=True):
    result = subprocess.run(
        [str(arg) for arg in args], env=env, timeout=timeout, input=input,
        capture_output=True, text=True,
    )
    if check and result.returncode != 0:
        raise SmokeFailure(
            f"{Path(args[0]).name} failed ({result.returncode}): "
            + (result.stdout + result.stderr)[-6000:]
        )
    return result


def digest(path: Path) -> str:
    with path.open("rb") as source:
        return hashlib.file_digest(source, "sha256").hexdigest()


def bounded_text(value: str | bytes | None, limit=6000) -> str:
    if isinstance(value, bytes):
        value = value.decode("utf-8", errors="replace")
    return (value or "")[-limit:]


def validate_target(serial: str, qemu: str, avd_name: str, expected_name: str) -> None:
    require(serial == SERIAL, "Only the dedicated emulator serial is permitted")
    require(qemu.strip() == "1", "Target is not an Android emulator")
    require(re.fullmatch(r"pyai_smoke_[0-9a-f]{32}", expected_name) is not None,
            "AVD name is not a generated smoke identity")
    lines = [line.strip() for line in avd_name.splitlines() if line.strip()]
    require(lines == [expected_name, "OK"], "Connected AVD is not the owned fresh AVD")


def bounds(node: ET.Element) -> tuple[int, int, int, int]:
    match = re.fullmatch(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", node.get("bounds", ""))
    require(match is not None, "Missing or invalid UI bounds")
    left, top, right, bottom = map(int, match.groups())
    require(right > left and bottom > top, "UI node has no visible area")
    return left, top, right, bottom


def visible(node: ET.Element) -> bool:
    try:
        bounds(node)
        return node.get("package") == PACKAGE
    except SmokeFailure:
        return False


def single_match(root: ET.Element, predicate) -> ET.Element | None:
    matches = [node for node in root.iter("node") if visible(node) and predicate(node)]
    require(len(matches) <= 1, "Ambiguous UI selector; refusing to guess a target")
    return matches[0] if matches else None


def labelled_editor(root: ET.Element, label: str) -> ET.Element | None:
    field_label = single_match(root, lambda node: node.get("text") == label)
    if field_label is None:
        return None
    for parent in root.iter():
        children = list(parent)
        if field_label not in children:
            continue
        index = children.index(field_label) + 1
        if index < len(children):
            field = children[index]
            if field.get("class") == EDITOR and visible(field):
                return field
    return None


def wait_for_boot_completed(device, receipt: dict, timeout_seconds):
    """Retain bounded observations of the original boot-property gate."""
    require(0 < timeout_seconds <= 900, "Invalid boot deadline")
    state = {"completed": False, "timeout_seconds": timeout_seconds,
             "attempts": 0, "recent_probes": []}
    receipt["boot_wait"] = state
    started = time.monotonic()
    deadline = started + timeout_seconds
    try:
        while time.monotonic() < deadline:
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                break
            state["attempts"] += 1
            probe_started = time.monotonic()
            probe = {"attempt": state["attempts"],
                     "started_seconds": round(probe_started - started, 3)}
            state["last_probe"] = probe
            try:
                result = device.adb("shell", "getprop", "sys.boot_completed",
                                    timeout=min(10, remaining), check=False)
                probe.update(exit_code=result.returncode,
                             stdout_tail=bounded_text(result.stdout, 1200),
                             stderr_tail=bounded_text(result.stderr, 1200),
                             output_truncated=len(result.stdout) > 1200 or len(result.stderr) > 1200,
                             boot_property_passed=(result.returncode == 0 and result.stdout.strip() == "1"
                                                   and not result.stderr.strip()))
            except subprocess.TimeoutExpired as error:
                probe.update(command_timeout_seconds=error.timeout,
                             stdout_tail=bounded_text(error.stdout, 1200),
                             stderr_tail=bounded_text(error.stderr, 1200))
                raise  # Preserve the existing command-timeout failure; never accept partial stdout.
            except Exception as error:
                probe["error"] = bounded_text(str(error), 1200)
                raise
            finally:
                probe["seconds"] = round(time.monotonic() - probe_started, 3)
                state.setdefault("first_probe", probe)
                state["recent_probes"].append(probe)
                del state["recent_probes"][:-16]
            if probe.get("boot_property_passed") is True and time.monotonic() < deadline:
                state["completed"] = True
                return
            remaining = deadline - time.monotonic()
            if remaining > 0:
                time.sleep(min(1, remaining))
        raise SmokeFailure(f"Owned emulator did not boot within {timeout_seconds} seconds")
    except Exception as error:
        state["error"] = bounded_text(str(error), 1200)
        raise
    finally:
        state["seconds"] = round(time.monotonic() - started, 3)


def retain_boot_failure_state(device, receipt: dict):
    """Read the same owned serial after failure; observations cannot rescue boot."""
    state = {"timeout_seconds": 20, "checks": {}}
    receipt["boot_failure_observations"] = state
    started = time.monotonic()
    deadline = started + state["timeout_seconds"]
    try:
        for name, args in BOOT_FAILURE_COMMANDS:
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                state["deadline_reached"] = True
                break
            check = {"arguments": list(args)}
            state["checks"][name] = check
            try:
                result = device.adb(*args, timeout=min(5, remaining), check=False)
                check.update(exit_code=result.returncode,
                             stdout_tail=bounded_text(result.stdout, 1200),
                             stderr_tail=bounded_text(result.stderr, 1200),
                             output_truncated=len(result.stdout) > 1200 or len(result.stderr) > 1200)
            except subprocess.TimeoutExpired as error:
                check.update(command_timeout_seconds=error.timeout,
                             stdout_tail=bounded_text(error.stdout, 1200),
                             stderr_tail=bounded_text(error.stderr, 1200))
            except Exception as error:
                check["error"] = bounded_text(str(error), 1200)
    finally:
        state["seconds"] = round(time.monotonic() - started, 3)


def guest_readiness_checks_pass(checks: dict) -> bool:
    if not isinstance(checks, dict) or set(checks) != {name for name, _, _ in GUEST_READINESS_COMMANDS}:
        return False
    return all(isinstance(checks[name], dict)
               and type(checks[name].get("exit_code")) is int and checks[name]["exit_code"] == 0
               and checks[name].get("output_truncated") is False
               and isinstance(checks[name].get("stdout_tail"), str)
               and checks[name]["stdout_tail"].strip() in expected
               and isinstance(checks[name].get("stderr_tail"), str)
               and not checks[name]["stderr_tail"].strip()
               and "command_timeout_seconds" not in checks[name]
               for name, _, expected in GUEST_READINESS_COMMANDS)


def wait_for_guest_ready(device, receipt: dict, timeout_seconds=180):
    """Bound user/storage checks and one framework/home readiness sequence together.

    The framework barrier synchronizes existing queued work; the home observation
    never starts the preview or dismisses a system dialog. App cases remain separate.
    """
    require(0 < timeout_seconds <= 180, "Invalid guest readiness deadline")
    state = {"ready": False, "timeout_seconds": timeout_seconds, "attempts": 0,
             "ui_dump_path": UI_DUMP_PATH, "last_checks": {}}
    receipt["guest_readiness"] = state
    started = time.monotonic()
    deadline = started + timeout_seconds
    try:
        device.verify_identity()
        while time.monotonic() < deadline:
            state["attempts"] += 1
            checks = {}
            state["last_checks"] = checks
            for name, args, _ in GUEST_READINESS_COMMANDS:
                remaining = deadline - time.monotonic()
                if remaining <= 0:
                    break
                try:
                    result = device.adb("shell", *args, timeout=min(10, remaining), check=False)
                    checks[name] = {"exit_code": result.returncode,
                                    "stdout_tail": bounded_text(result.stdout, 1200),
                                    "stderr_tail": bounded_text(result.stderr, 1200),
                                    "output_truncated": len(result.stdout) > 1200 or len(result.stderr) > 1200}
                except subprocess.TimeoutExpired as error:
                    checks[name] = {"command_timeout_seconds": error.timeout,
                                    "stdout_tail": bounded_text(error.stdout, 1200),
                                    "stderr_tail": bounded_text(error.stderr, 1200)}
            if guest_readiness_checks_pass(checks) and time.monotonic() < deadline:
                establish_interactive_readiness(device, state, deadline)
                state["ready"] = True
                return
            state.setdefault("first_incomplete_checks", checks)
            remaining = deadline - time.monotonic()
            if remaining > 0:
                time.sleep(min(1, remaining))
        raise SmokeFailure(f"Owned guest user/storage readiness was not established within {timeout_seconds} seconds")
    except Exception as error:
        state["error"] = bounded_text(str(error), 1200)
        raise
    finally:
        state["seconds"] = round(time.monotonic() - started, 3)


def complete_receipt(receipt: dict, source_sha: str, expected_mode: str | None = None) -> None:
    require(receipt.get("source_commit") == source_sha, "Receipt source commit differs")
    require(receipt.get("scope") == "owned-installed-aosp-emulator", "Wrong evidence scope")
    require(receipt.get("status") == "passed", "Installed smoke did not pass")
    cases = receipt.get("cases", [])
    require([case.get("id") for case in cases] == list(CASE_IDS), "Missing or duplicate smoke case")
    require(all(case.get("status") == "passed" for case in cases), "A smoke case did not execute successfully")
    require(receipt.get("unsigned_apk_unchanged") is True, "Unsigned build preservation was not verified")
    emulation = receipt.get("emulation")
    require(isinstance(emulation, dict) and emulation.get("mode") in ("kvm", "software", "hvf"),
            "Explicit emulation settings are missing")
    software = emulation["mode"] == "software"
    require(emulation.get("accel_argument") == ("off" if software else "on") and
            emulation.get("boot_timeout_seconds") == BOOT_TIMEOUT_SECONDS_BY_MODE[emulation["mode"]] and
            emulation.get("unsupported_software_opt_in") is software,
            "Emulation settings differ from the explicit selected mode")
    if expected_mode is not None:
        require(emulation.get("mode") == expected_mode,
                "Receipt does not use the selected emulator mode")
    if emulation["mode"] == "hvf":
        require(emulation.get("accel_argument") == "on"
                and emulation.get("unsupported_software_opt_in") is False
                and hvf_host_passes(emulation.get("host"))
                and hvf_acceleration_passes(receipt.get("host_acceleration")),
                "Complete hosted Intel Hypervisor.Framework evidence is missing")
        require_apk_handoff(receipt.get("unsigned_apk_sha256"), receipt.get("expected_unsigned_apk_sha256"))
    readiness = receipt.get("guest_readiness", {})
    require(isinstance(readiness, dict) and readiness.get("ready") is True and
            readiness.get("ui_dump_path") == UI_DUMP_PATH and
            guest_readiness_checks_pass(readiness.get("last_checks")),
            "Complete owned-guest readiness evidence is missing")
    require(interactive_readiness_passes(readiness.get("interactive")),
            "Complete pre-installation framework/home readiness evidence is missing")


class Device:
    def __init__(self, adb: Path, env: dict, process: subprocess.Popen, avd_name: str, receipt: dict):
        self.adb_path, self.env, self.process, self.avd_name = adb, env, process, avd_name
        self.receipt = receipt

    def adb(self, *args, timeout=30, check=True):
        require(self.process.poll() is None, "Owned emulator process exited")
        return command([str(self.adb_path), "-s", SERIAL, *args], env=self.env,
                       timeout=timeout, check=check)

    def verify_identity(self):
        validate_target(SERIAL, self.adb("shell", "getprop", "ro.kernel.qemu").stdout,
                        self.adb("emu", "avd", "name").stdout, self.avd_name)

    def shell(self, *args, **kwargs):
        return self.adb("shell", *args, **kwargs).stdout.strip()

    def launch(self):
        self.verify_identity()
        attempt = {"component": PACKAGE + "/" + ACTIVITY, "check_passed": False}
        self.receipt.setdefault("activity_launches", []).append(attempt)
        started = time.monotonic()
        try:
            result = self.adb("shell", "am", "start", "-W", "-n", attempt["component"], check=False)
        except subprocess.TimeoutExpired as error:
            attempt.update(command_timeout_seconds=error.timeout,
                           stdout_tail=bounded_text(error.stdout), stderr_tail=bounded_text(error.stderr))
            raise
        finally:
            attempt["seconds"] = round(time.monotonic() - started, 3)
        attempt.update(exit_code=result.returncode, stdout_tail=bounded_text(result.stdout),
                       stderr_tail=bounded_text(result.stderr))
        require(result.returncode == 0, f"Activity start command failed ({result.returncode})")
        require("Status: ok" in result.stdout and "Error:" not in result.stdout,
                "Activity did not launch; inspect activity_launches in the receipt")
        attempt["check_passed"] = True

    def cold_restart(self):
        self.verify_identity()
        old_pid = self.shell("pidof", PACKAGE)
        require(re.fullmatch(r"[0-9]+", old_pid) is not None, "Expected one running preview process")
        self.shell("input", "keyevent", "KEYCODE_HOME")
        self.shell("am", "force-stop", PACKAGE)
        require(not self.shell("pidof", PACKAGE, check=False), "Force-stop did not terminate the preview")
        self.launch()
        new_pid = self.shell("pidof", PACKAGE)
        require(re.fullmatch(r"[0-9]+", new_pid) is not None and new_pid != old_pid,
                "Relaunch did not establish a new preview process")
        return {"terminated_pid": old_pid, "relaunch_pid": new_pid, "method": "am force-stop"}


def ui_stream_observation(value: str | bytes | None) -> dict:
    """Bound retained decoded text; this does not bound subprocess capture memory."""
    if value is None:
        return {"available": False}
    if not isinstance(value, (str, bytes)):
        return {"available": False, "unexpected_type": type(value).__name__}
    text = value.decode("utf-8", errors="replace") if isinstance(value, bytes) else value
    return {"available": True, "decoded_from_bytes": isinstance(value, bytes),
            "text_characters": len(text), "text_sha256": hashlib.sha256(text.encode("utf-8")).hexdigest(),
            "prefix": text[:1200], "suffix": text[-1200:], "edges_truncated": len(text) > 2400}


def observe_ui_command(device: Device, arguments: tuple[str, ...], observed: dict, *, timeout: int):
    """Retain the exact owned command result before any capture assertion."""
    observed.update(arguments=list(arguments), timeout_seconds=timeout)
    started = time.monotonic()
    try:
        result = device.adb(*arguments, timeout=timeout, check=False)
        observed.update(exit_code=result.returncode, stdout=ui_stream_observation(result.stdout),
                        stderr=ui_stream_observation(result.stderr))
        return result
    except subprocess.TimeoutExpired as error:
        observed.update(command_timeout_seconds=error.timeout, stdout=ui_stream_observation(error.stdout),
                        stderr=ui_stream_observation(error.stderr))
        raise
    except Exception as error:
        observed["error"] = bounded_text(str(error), 1200)
        raise
    finally:
        observed["seconds"] = round(time.monotonic() - started, 3)


def command_budget(deadline: float | None, maximum: int) -> float:
    if deadline is None:
        return maximum
    remaining = deadline - time.monotonic()
    require(remaining > 0, "Owned guest readiness deadline expired")
    return min(maximum, remaining)


def observe_interactive_command(device: Device, arguments: tuple[str, ...], observed: dict,
                                *, timeout: float, retained_limit: int):
    observed["stdout_retained_character_limit"] = retained_limit
    try:
        result = observe_ui_command(device, arguments, observed, timeout=timeout)
        stdout = result.stdout
    except subprocess.TimeoutExpired as error:
        observed["stdout_tail"] = bounded_text(error.stdout, retained_limit)
        observed["stdout_tail_truncated"] = observed["stdout"].get("text_characters", 0) > retained_limit
        raise
    observed["stdout_tail"] = bounded_text(stdout, retained_limit)
    observed["stdout_tail_truncated"] = observed["stdout"].get("text_characters", 0) > retained_limit
    return result


def ui_stream_matches_text(observed: dict, text: str) -> bool:
    """Bind every retained field to a known complete command stream."""
    return (isinstance(observed, dict) and isinstance(text, str)
            and type(observed.get("text_characters")) is int
            and observed.get("available") is True and observed.get("decoded_from_bytes") is False
            and observed.get("edges_truncated") is (len(text) > 2400)
            and observed == ui_stream_observation(text))


def ui_read_observation_passes(observed: dict) -> bool:
    """Validate retained read metadata; omitted XML cannot be reconstructed from its digest."""
    if not (isinstance(observed, dict) and observed.get("available") is True
            and observed.get("decoded_from_bytes") is False
            and type(observed.get("text_characters")) is int and observed["text_characters"] > 0
            and isinstance(observed.get("text_sha256"), str)
            and re.fullmatch(r"[0-9a-f]{64}", observed["text_sha256"]) is not None
            and isinstance(observed.get("prefix"), str) and isinstance(observed.get("suffix"), str)):
        return False
    count = observed["text_characters"]
    edge_length = min(count, 1200)
    if len(observed["prefix"]) != edge_length or len(observed["suffix"]) != edge_length:
        return False
    if count > 2400:
        return (observed.get("edges_truncated") is True
                and set(observed) == set(ui_stream_observation("")))
    text = observed["prefix"] + observed["suffix"][2 * edge_length - count:]
    return ui_stream_matches_text(observed, text)


def interactive_command_passes(observed: dict, arguments: tuple[str, ...], retained_limit: int) -> bool:
    if not (isinstance(observed, dict) and observed.get("arguments") == list(arguments)
            and type(observed.get("exit_code")) is int and observed["exit_code"] == 0
            and observed.get("stdout_retained_character_limit") == retained_limit
            and observed.get("stdout_tail_truncated") is False
            and isinstance(observed.get("stdout_tail"), str)
            and len(observed["stdout_tail"]) <= retained_limit
            and isinstance(observed.get("stdout"), dict) and isinstance(observed.get("stderr"), dict)
            and "command_timeout_seconds" not in observed and "error" not in observed):
        return False
    return (ui_stream_matches_text(observed["stdout"], observed["stdout_tail"])
            and ui_stream_matches_text(observed["stderr"], ""))


def framework_barrier_passes(observed: dict) -> bool:
    # Android 15 returns zero even when its application barrier gives up. Its
    # broadcast queue's interim "failed due to" lines mean pending work, not a pass.
    return (interactive_command_passes(observed, FRAMEWORK_BARRIER, 16384)
            and re.fullmatch(
                r"(?:Test barrier failed due to [^\r\n]+\r?\n)*Test barrier passed\r?\n"
                r"(?:Waiting for application barriers, at [0-9]+ of [0-9]+\.\.\.\r?\n)*"
                r"Finished application barriers!(?:\r?\n)?", observed["stdout_tail"]) is not None)


def home_focus_passes(observed: dict) -> bool:
    if not (interactive_command_passes(observed, HOME_FOCUS_QUERY, 65536)
            and "DUMP TIMEOUT" not in observed["stdout_tail"]
            and "Error dumping service info:" not in observed["stdout_tail"]):
        return False
    expected = r"Window\{[0-9a-f]+ u0 " + re.escape(HOME_WINDOW) + r"\}"
    return all(len(matches := re.findall(r"^\s*" + name + r"=(.*)$", observed["stdout_tail"], re.M)) == 1
               and re.fullmatch(expected, matches[0].strip()) is not None
               for name in ("mCurrentFocus", "mFocusedWindow"))


def home_hierarchy_summary(root: ET.Element, read_observation: dict) -> dict:
    nodes = list(root.iter("node"))
    visible_home_nodes = 0
    for node in nodes:
        if node.get("package") == HOME_PACKAGE:
            try:
                bounds(node)
                visible_home_nodes += 1
            except SmokeFailure:
                pass
    return {"rotation": root.get("rotation"), "packages": sorted({node.get("package", "") for node in nodes}),
            "visible_home_nodes": visible_home_nodes,
            "read_text_sha256": read_observation["stdout"].get("text_sha256")}


def interactive_readiness_passes(state: dict) -> bool:
    if not (isinstance(state, dict) and state.get("ready") is True
            and state.get("phase") == "before_installation" and state.get("serial") == SERIAL
            and state.get("home_package") == HOME_PACKAGE and "error" not in state
            and framework_barrier_passes(state.get("barrier")) and home_focus_passes(state.get("focus"))):
        return False
    hierarchy = state.get("home_hierarchy", {})
    observations = state.get("ui_hierarchy_observations", {})
    attempt = observations.get("last_attempt", {}) if isinstance(observations, dict) else {}
    if not isinstance(attempt, dict) or not isinstance(attempt.get("commands"), dict):
        return False
    read = attempt["commands"].get("read", {})
    if not isinstance(read, dict):
        return False
    for name, arguments in (("remove", ("shell", "rm", "-f", UI_DUMP_PATH)),
                            ("dump", ("shell", "uiautomator", "dump", UI_DUMP_PATH)),
                            ("read", ("exec-out", "cat", UI_DUMP_PATH))):
        command = attempt["commands"].get(name)
        if not (isinstance(command, dict) and command.get("arguments") == list(arguments)
                and type(command.get("exit_code")) is int
                and command["exit_code"] in ((0, 137) if name == "dump" else (0,))
                and "command_timeout_seconds" not in command and "error" not in command
                and isinstance(command.get("stdout"), dict) and isinstance(command.get("stderr"), dict)):
            return False
        if name != "remove" and not ui_stream_matches_text(command["stderr"], ""):
            return False
        if name == "dump" and not (ui_stream_matches_text(command["stdout"], command["stdout"].get("prefix"))
                                    and command["stdout"]["prefix"].strip() == "UI hierchary dumped to: " + UI_DUMP_PATH):
            return False
    return (isinstance(hierarchy, dict) and hierarchy.get("rotation") == "0"
            and hierarchy.get("packages") in ([HOME_PACKAGE], [HOME_PACKAGE, "com.android.systemui"])
            and type(hierarchy.get("visible_home_nodes")) is int and hierarchy["visible_home_nodes"] > 0
            and isinstance(hierarchy.get("read_text_sha256"), str)
            and re.fullmatch(r"[0-9a-f]{64}", hierarchy["read_text_sha256"]) is not None
            and type(observations.get("attempted")) is int and observations["attempted"] == 1
            and "first_failed_attempt" not in observations
            and type(attempt.get("sequence")) is int and attempt["sequence"] == 1 and "error" not in attempt
            and attempt.get("completed") is True and attempt.get("dump_completion_passed") is True
            and attempt.get("ui_dump_path") == UI_DUMP_PATH and attempt.get("stage") == "parse"
            and ui_read_observation_passes(read.get("stdout"))
            and hierarchy["read_text_sha256"] == read["stdout"].get("text_sha256"))


def establish_interactive_readiness(device: Device, readiness: dict, deadline: float):
    """Synchronize once, then positively observe the owned home UI before installation."""
    state = {"ready": False, "phase": "before_installation", "serial": SERIAL,
             "home_package": HOME_PACKAGE, "barrier": {}, "focus": {}}
    readiness["interactive"] = state
    started = time.monotonic()
    try:
        observe_interactive_command(device, FRAMEWORK_BARRIER, state["barrier"],
                                    timeout=command_budget(deadline, 120), retained_limit=16384)
        require(framework_barrier_passes(state["barrier"]),
                "Owned framework broadcast/application barriers did not complete")
        root = Ui(device, Path("."), state).snapshot(deadline=deadline)
        state["home_hierarchy"] = home_hierarchy_summary(
            root, state["ui_hierarchy_observations"]["last_attempt"]["commands"]["read"])
        observe_interactive_command(device, HOME_FOCUS_QUERY, state["focus"],
                                    timeout=command_budget(deadline, 5), retained_limit=65536)
        state["ready"] = True
        require(interactive_readiness_passes(state) and time.monotonic() < deadline,
                "Owned launcher hierarchy/focus readiness was not established before the deadline")
    except Exception as error:
        state["ready"] = False
        state["error"] = bounded_text(str(error), 1200)
        raise
    finally:
        state["seconds"] = round(time.monotonic() - started, 3)


class Ui:
    def __init__(self, device: Device, output: Path, receipt: dict):
        self.device, self.output = device, output
        self.dumps = receipt.setdefault("ui_hierarchy_dumps", {"attempted": 0, "completed": 0, "exit_137": 0})
        self.observations = receipt.setdefault("ui_hierarchy_observations", {"attempted": 0, "recent_attempts": []})

    def snapshot(self, *, deadline: float | None = None) -> ET.Element:
        path = UI_DUMP_PATH
        self.observations["attempted"] += 1
        attempt = {"sequence": self.observations["attempted"], "ui_dump_path": path,
                   "completed": False, "commands": {}}
        self.observations["last_attempt"] = attempt
        self.observations["recent_attempts"].append(attempt)
        del self.observations["recent_attempts"][:-8]
        started = time.monotonic()
        try:
            attempt["stage"] = "remove"
            removed = observe_ui_command(self.device, ("shell", "rm", "-f", path),
                                         attempt["commands"].setdefault("remove", {}),
                                         timeout=command_budget(deadline, 30))
            require(removed.returncode == 0,
                    f"Prior UI hierarchy removal failed ({removed.returncode}): " +
                    (removed.stdout + removed.stderr)[-6000:])
            self.dumps["attempted"] += 1
            attempt["stage"] = "dump"
            result = observe_ui_command(self.device, ("shell", "uiautomator", "dump", path),
                                        attempt["commands"].setdefault("dump", {}),
                                        timeout=command_budget(deadline, 20))
            # The observed 137 path and ordinary zero path both require the full
            # completion message. Neither an exit nor that message replaces fresh XML.
            completion_passed = (result.returncode in (0, 137) and not result.stderr.strip() and
                                 result.stdout.strip() == "UI hierchary dumped to: " + path)
            attempt["dump_completion_passed"] = completion_passed
            require(completion_passed,
                    f"UI hierarchy dump failed ({result.returncode}): " +
                    (result.stdout + result.stderr)[-6000:])
            attempt["stage"] = "read"
            read = observe_ui_command(self.device, ("exec-out", "cat", path),
                                      attempt["commands"].setdefault("read", {}),
                                      timeout=command_budget(deadline, 30))
            require(read.returncode == 0 and not read.stderr.strip(),
                    f"Fresh UI hierarchy read failed ({read.returncode}): " +
                    (read.stdout + read.stderr)[-6000:])
            attempt["stage"] = "parse"
            root = ET.fromstring(read.stdout)
            require(root.tag == "hierarchy" and root.get("rotation") in ("0", "1", "2", "3") and
                    any(True for _ in root.iter("node")), "Invalid or empty UI hierarchy")
            require(deadline is None or time.monotonic() < deadline,
                    "Owned guest readiness hierarchy completed after its deadline")
            self.dumps["completed"] += 1
            if result.returncode == 137:
                self.dumps["exit_137"] += 1
            attempt["completed"] = True
            return root
        except Exception as error:
            failure = {"type": type(error).__name__, "message": bounded_text(str(error), 1200)}
            if isinstance(error, ET.ParseError):
                failure.update(code=getattr(error, "code", None), position=getattr(error, "position", None))
            attempt["error"] = failure
            self.observations.setdefault("first_failed_attempt", attempt)
            raise
        finally:
            attempt["seconds"] = round(time.monotonic() - started, 3)

    def scroll(self, root: ET.Element, down: bool) -> bool:
        node = single_match(root, lambda item: item.get("class") == "android.widget.ScrollView")
        if node is None:
            return False
        left, top, right, bottom = bounds(node)
        x = max(left + 1, right - 12)
        high, low = top + (bottom - top) // 5, bottom - (bottom - top) // 5
        start, end = (low, high) if down else (high, low)
        self.device.shell("input", "swipe", str(x), str(start), str(x), str(end), "250")
        return True

    def seek(self, selector, description: str, scroll=True) -> ET.Element:
        previous = None
        for direction, limit in ((False, 8), (True, 14)) if scroll else ((False, 8),):
            for _ in range(limit):
                root = self.snapshot()
                match = selector(root)
                if match is not None:
                    return match
                if not scroll:
                    time.sleep(0.25)
                    continue
                current = ET.tostring(root)
                if current == previous or not self.scroll(root, direction):
                    break
                previous = current
            previous = None
        raise SmokeFailure("UI control not found: " + description)

    def text(self, text: str, *, kind=None, contains=False, scroll=True):
        def selector(root):
            return single_match(root, lambda node:
                (text.casefold() in node.get("text", "").casefold() if contains
                 else node.get("text", "").casefold() == text.casefold())
                and (kind is None or node.get("class") == kind))
        return self.seek(selector, text, scroll)

    def tap(self, node: ET.Element):
        require(node.get("enabled") == "true", "Refusing to tap a disabled control")
        left, top, right, bottom = bounds(node)
        self.device.shell("input", "tap", str((left + right) // 2), str((top + bottom) // 2))

    def click(self, text: str, *, scroll=True):
        self.tap(self.text(text, kind=BUTTON, scroll=scroll))

    def editor(self, label: str):
        return self.seek(lambda root: labelled_editor(root, label), label)

    def enter(self, label: str, value: str):
        require(re.fullmatch(r"[A-Za-z0-9_]+", value) is not None, "Only fixed synthetic ASCII input is permitted")
        self.tap(self.editor(label))
        self.device.shell("input", "keyevent", "KEYCODE_MOVE_END")
        self.device.shell("input", "text", value)
        # BACK without an open IME would finish the Activity on hardware-keyboard
        # configurations. Query the real input service before dismissing it.
        if re.search(r"\bmInputShown=true\b", self.device.shell("dumpsys", "input_method")):
            self.device.shell("input", "keyevent", "KEYCODE_BACK")

    def expect_fields(self, title: str, objective: str, context: str):
        for label, value in ((TITLE_LABEL, title), (OBJECTIVE_LABEL, objective), (CONTEXT_LABEL, context)):
            require(self.editor(label).get("text") == value, "Unexpected visible value for " + label)

    def expect_button(self, text: str, enabled: bool):
        deadline = time.monotonic() + 20
        while time.monotonic() < deadline:
            node = self.text(text, kind=BUTTON)
            if node.get("enabled") == str(enabled).lower():
                return
            time.sleep(0.25)
        raise SmokeFailure("Unexpected enabled state for " + text)

    def checkpoint(self, name: str):
        root = self.snapshot()
        ET.ElementTree(root).write(self.output / (name + ".xml"), encoding="utf-8", xml_declaration=True)
        result = subprocess.run(
            [str(self.device.adb_path), "-s", SERIAL, "exec-out", "screencap", "-p"],
            env=self.device.env, capture_output=True, timeout=15, check=True,
        )
        require(result.stdout.startswith(b"\x89PNG\r\n\x1a\n"), "Screenshot is not PNG")
        (self.output / (name + ".png")).write_bytes(result.stdout)

    def rotate(self, rotation: int):
        self.device.shell("settings", "put", "system", "user_rotation", str(rotation))
        deadline = time.monotonic() + 30
        while time.monotonic() < deadline:
            root = self.snapshot()
            if root.get("rotation") == str(rotation) and any(visible(node) for node in root.iter("node")):
                return
        raise SmokeFailure("Emulator did not reach the requested rotation")


@contextlib.contextmanager
def case(receipt: dict, case_id: str):
    row = next(item for item in receipt["cases"] if item["id"] == case_id)
    started = time.monotonic()
    row["status"] = "running"
    try:
        yield row
    except Exception as error:
        row.update(status="failed", error=str(error))
        raise
    else:
        row["status"] = "passed"
    finally:
        row["seconds"] = round(time.monotonic() - started, 3)


def retain_failure_log(device: Device, output: Path, receipt: dict):
    """Best-effort bounded diagnostics from this owned synthetic installation."""
    try:
        result = device.adb("logcat", "-b", "main", "-b", "system", "-b", "crash",
                            "-d", "-v", "threadtime", "-t", "400", "ActivityManager:I",
                            "ActivityTaskManager:I", "AndroidRuntime:E", "System.err:W", "*:S",
                            timeout=20, check=False)
        receipt["failure_logcat"] = {"exit_code": result.returncode,
                                     "stderr_tail": bounded_text(result.stderr),
                                     "retained_character_limit": 131072}
        (output / "failure-logcat.txt").write_text(bounded_text(result.stdout, 131072), encoding="utf-8")
    except Exception as error:
        receipt["failure_logcat"] = {"error": bounded_text(str(error))}


def retain_case_failure_state(device: Device, receipt: dict, *, phase="after_failure_checkpoint"):
    """Read owned state after the named failure; observations cannot rescue it."""
    require(phase in ("after_failure_checkpoint", "after_guest_readiness_failure"), "Unknown failure phase")
    state = {"phase": phase, "serial": SERIAL, "package": PACKAGE,
             "timeout_seconds": 20, "checks": {}}
    receipt["case_failure_observations" if phase == "after_failure_checkpoint"
            else "guest_readiness_failure_observations"] = state
    commands = CASE_FAILURE_COMMANDS
    if phase == "after_guest_readiness_failure":
        # Existing global records can describe different ANRs; correlate their actual identities.
        state["anr_record_scope"] = "latest_global_records_not_automatically_package_bound"
        commands = commands[:1] + GUEST_ANR_FAILURE_COMMANDS + commands[1:]
    started = time.monotonic()
    deadline = started + state["timeout_seconds"]
    try:
        for index, (name, arguments, retained_limit) in enumerate(commands):
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                state["deadline_reached"] = True
                state["unattempted_checks"] = [item[0] for item in commands[index:]]
                break
            observed = {"started_seconds": round(time.monotonic() - started, 3),
                        "stdout_retained_character_limit": retained_limit}
            state["checks"][name] = observed
            try:
                result = observe_ui_command(device, arguments, observed, timeout=min(5, remaining))
                stdout = result.stdout
            except subprocess.TimeoutExpired as error:
                stdout = error.stdout
            except Exception:
                continue  # The command's original error is already retained by observe_ui_command.
            observed["stdout_tail"] = bounded_text(stdout, retained_limit)
            observed["stdout_tail_truncated"] = observed["stdout"].get("text_characters", 0) > retained_limit
            if name in ("last_anr_state", "last_anr_traces"):
                # Keep the record header/first stacks as well as the end, including timeout partials.
                text = stdout.decode("utf-8", errors="replace") if isinstance(stdout, bytes) else (stdout or "")
                observed["stdout_prefix_retained_character_limit"] = retained_limit
                observed["stdout_prefix"] = text[:retained_limit]
                observed["stdout_prefix_truncated"] = len(text) > retained_limit
    finally:
        state["seconds"] = round(time.monotonic() - started, 3)


def exercise(device: Device, output: Path, receipt: dict):
    ui = Ui(device, output, receipt)
    try:
        with case(receipt, CASE_IDS[0]):
            device.launch()
            ui.text("SYNTHETIC DATA", contains=True)
            ui.text("Active local draft ID: guest-draft", scroll=False)
            ui.expect_button("Manage local drafts", True)
            ui.editor(TITLE_LABEL)
            ui.checkpoint("01-installed-launch")
        with case(receipt, CASE_IDS[1]) as row:
            ui.enter(TITLE_LABEL, "SyntheticEmulatorDraft")
            ui.enter(OBJECTIVE_LABEL, "SyntheticObjective")
            ui.enter(CONTEXT_LABEL, "SyntheticContext")
            ui.expect_fields("SyntheticEmulatorDraft", "SyntheticObjective", "SyntheticContext")
            ui.click("Save locally")
            ui.text("Saved locally.")
            ui.expect_button("Save locally", False)
            ui.checkpoint("02-acknowledged-save")
            row["process_restart"] = device.cold_restart()
            ui.text("Saved locally.")
            ui.expect_fields("SyntheticEmulatorDraft", "SyntheticObjective", "SyntheticContext")
            ui.text("Active local draft ID: guest-draft")
            ui.text("Saved locally.")
            ui.expect_button("Save locally", False)
            ui.checkpoint("03-cold-relaunch")
        with case(receipt, CASE_IDS[2]):
            ui.enter(TITLE_LABEL, "_pending")
            ui.enter(OBJECTIVE_LABEL, "_pending")
            ui.expect_fields("SyntheticEmulatorDraft_pending", "SyntheticObjective_pending", "SyntheticContext")
            ui.rotate(1)
            ui.text("Unsaved changes. Tap Save locally to commit this form.")
            ui.expect_fields("SyntheticEmulatorDraft_pending", "SyntheticObjective_pending", "SyntheticContext")
            ui.expect_button("Save locally", True)
            ui.text("Unsaved changes. Tap Save locally to commit this form.")
            ui.checkpoint("04-pending-landscape")
            ui.rotate(0)
            ui.text("Unsaved changes. Tap Save locally to commit this form.")
            ui.expect_fields("SyntheticEmulatorDraft_pending", "SyntheticObjective_pending", "SyntheticContext")
            ui.expect_button("Save locally", True)
            ui.checkpoint("05-pending-portrait")
        with case(receipt, CASE_IDS[3]) as row:
            ui.click("Manage local drafts")
            entry = ui.text("Local ID: guest-draft", contains=True, scroll=False)
            require("SyntheticEmulatorDraft\n" in entry.get("text", ""), "Library row is not the saved snapshot")
            ui.tap(entry)
            ui.click("Delete local draft", scroll=False)
            ui.text("Permanently delete local ID guest-draft", contains=True, scroll=False)
            ui.checkpoint("06-explicit-delete-confirmation")
            ui.click("Delete local draft", scroll=False)
            ui.text("The selected local draft and its retained revision files have been removed.", contains=True, scroll=False)
            ui.click("Close", scroll=False)
            ui.expect_fields("SyntheticEmulatorDraft_pending", "SyntheticObjective_pending", "SyntheticContext")
            ui.expect_button("Save locally", False)
            ui.expect_button("Saved local revision history", False)
            ui.checkpoint("07-deleted-editor-keeps-pending-fields")
            ui.click("Manage local drafts")
            ui.text("No local draft directories found.", scroll=False)
            ui.click("Close", scroll=False)
            row["process_restart"] = device.cold_restart()
            ui.text("Local draft could not be opened.", contains=True)
            ui.expect_button("Manage local drafts", True)
            ui.expect_button("Save locally", False)
            ui.click("Manage local drafts")
            ui.text("No local draft directories found.", scroll=False)
            ui.checkpoint("08-deleted-draft-stays-absent-after-relaunch")
            ui.click("Close", scroll=False)
    except Exception:
        retain_failure_log(device, output, receipt)
        try:
            ui.checkpoint("failure")
        except Exception as error:
            receipt["failure_checkpoint_error"] = bounded_text(str(error))
        try:
            retain_case_failure_state(device, receipt)
        except Exception as error:
            receipt.setdefault("case_failure_observations", {})["error"] = bounded_text(str(error), 1200)
        raise


def write_reports(output: Path, receipt: dict):
    (output / "receipt.json").write_text(json.dumps(receipt, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    cases = receipt["cases"]
    failed = sum(row["status"] == "failed" for row in cases)
    skipped = sum(row["status"] not in ("passed", "failed") for row in cases)
    setup_error = receipt["status"] != "passed" and failed == 0
    suite = ET.Element("testsuite", name="installed-synthetic-emulator",
                       tests=str(len(cases) + int(setup_error)), failures=str(failed + int(setup_error)),
                       skipped=str(skipped))
    if setup_error:
        ET.SubElement(ET.SubElement(suite, "testcase", name="environment_setup_or_cleanup"), "failure").text = receipt.get("error", "Incomplete run")
    for row in cases:
        test = ET.SubElement(suite, "testcase", name=row["id"], time=str(row.get("seconds", 0)))
        if row["status"] == "failed":
            ET.SubElement(test, "failure").text = row.get("error", "Smoke failed")
        elif row["status"] != "passed":
            ET.SubElement(test, "skipped").text = "Not executed; no passing evidence"
    ET.ElementTree(suite).write(output / "junit.xml", encoding="utf-8", xml_declaration=True)


def main() -> int:
    require(os.environ.get("GITHUB_ACTIONS") == "true" and
            os.environ.get("RUNNER_ENVIRONMENT") == "github-hosted",
            "Provisioning is restricted to a disposable GitHub-hosted runner")
    repo = Path(__file__).resolve().parents[1]
    require(Path.cwd().resolve() == repo, "Run from the repository root")
    output = repo / "preview/build/reports/emulator-smoke"
    output.mkdir(parents=True, exist_ok=False)  # Never reuse a preceding receipt.
    source = command(["git", "rev-parse", "HEAD"]).stdout.strip()
    receipt = {
        "schema_version": 1, "scope": "owned-installed-aosp-emulator", "status": "failed",
        "source_commit": source, "source_tree": command(["git", "rev-parse", "HEAD^{tree}"]).stdout.strip(),
        "workflow_commit": os.environ.get("GITHUB_SHA"), "run_id": os.environ.get("GITHUB_RUN_ID"),
        "run_attempt": os.environ.get("GITHUB_RUN_ATTEMPT"), "event": os.environ.get("GITHUB_EVENT_NAME"),
        "cases": [{"id": name, "status": "not_run"} for name in CASE_IDS],
        "limitations": ["one API 35 AOSP x86_64 emulator", "synthetic experimental package",
                        "force-stop is not low-memory process death or storage power loss",
                        "no physical-device, TalkBack, performance, production signing or Play acceptance"],
    }
    apk = repo / "preview/build/outputs/apk/debug/preview-debug-unsigned.apk"
    original_hash = None
    process = None
    try:
        require(source == receipt["workflow_commit"], "Checkout differs from the workflow commit")
        command(["git", "diff", "--quiet"])
        command(["git", "diff", "--cached", "--quiet"])
        require(apk.is_file() and not apk.is_symlink(), "Expected the original unsigned diagnostic APK")
        original_hash = digest(apk)
        receipt["unsigned_apk_sha256"] = original_hash
        settings = emulation_settings(receipt)
        if settings["mode"] == "hvf":
            expected_hash = os.environ.get("PYAI_EXPECTED_APK_SHA256")
            require_apk_handoff(original_hash, expected_hash)
            receipt["expected_unsigned_apk_sha256"] = expected_hash
        if settings["mode"] == "software":
            receipt["limitations"].append("software CPU emulation is a vendor-unsupported diagnostic mode")
        sdk = Path(os.environ["ANDROID_HOME"])
        adb = sdk / "platform-tools/adb"
        emulator = sdk / "emulator/emulator"
        avdmanager = sdk / "cmdline-tools/latest/bin/avdmanager"
        build_tools = sdk / "build-tools/35.0.0"
        image_dir = sdk / "system-images/android-35/default/x86_64"
        image_metadata = ET.parse(image_dir / "package.xml").getroot()
        image_package = next(node for node in image_metadata.iter() if node.tag.rsplit("}", 1)[-1] == "localPackage")
        require(image_package.get("path") == SYSTEM_IMAGE, "Unexpected system image package")
        revision_node = next(node for node in image_package if node.tag.rsplit("}", 1)[-1] == "revision")
        revision_parts = {node.tag.rsplit("}", 1)[-1]: int(node.text or "0") for node in revision_node}
        require(revision_parts.get("major") == int(IMAGE_REVISION) and
                all(value == 0 for name, value in revision_parts.items() if name != "major"),
                "System image revision changed; review before updating its pin")
        revision = IMAGE_REVISION
        receipt["system_image"] = {"package": SYSTEM_IMAGE, "revision": revision,
                                  "package_xml_sha256": digest(image_dir / "package.xml"),
                                  "system_img_sha256": digest(image_dir / "system.img")}
        if settings["mode"] != "hvf":
            receipt["libpulse0_version"] = command(["dpkg-query", "-W", "-f=${Version}", "libpulse0"]).stdout.strip()
        receipt["emulator_version"] = command([str(emulator), "-version"]).stdout.strip()
        receipt["emulator_binary_sha256"] = digest(emulator)
        receipt["adb_version"] = command([str(adb), "version"]).stdout.strip()
        if settings["mode"] == "kvm":
            command([str(emulator), "-accel-check"])
        elif settings["mode"] == "hvf":
            require(receipt["emulator_version"].splitlines()[0].startswith(
                    "Android emulator version " + HVF_EMULATOR_VERSION + " "),
                    "The preinstalled HVF emulator version changed; review its capability before updating the pin")
            acceleration = {}
            receipt["host_acceleration"] = acceleration
            check_hvf_acceleration(emulator, acceleration)
        connected = command([str(adb), "devices"]).stdout.splitlines()[1:]
        require(not any(line.strip() for line in connected), "Existing adb devices found; refusing shared-device execution")
        badging = command([str(build_tools / "aapt"), "dump", "badging", str(apk)]).stdout
        require(re.search(r"^package: name='" + re.escape(PACKAGE) + r"'", badging, re.M) is not None,
                "APK package is not the synthetic preview")
        require("application-debuggable" in badging and "targetSdkVersion:'35'" in badging and
                "sdkVersion:'26'" in badging and ("launchable-activity: name='" + ACTIVITY + "'") in badging,
                "Unexpected APK variant, SDK bounds or launcher")
        require(not re.search(r"^uses-permission", badging, re.M), "Preview unexpectedly requests permissions")
        require(command([str(build_tools / "apksigner"), "verify", str(apk)], check=False).returncode != 0,
                "Input artifact must remain unsigned")
        with tempfile.TemporaryDirectory(prefix="pyai-emulator-", dir=os.environ["RUNNER_TEMP"]) as temporary:
            scratch = Path(temporary)
            env = dict(os.environ, ANDROID_AVD_HOME=str(scratch / "avds"),
                       ANDROID_USER_HOME=str(scratch / "android-user"),
                       ANDROID_EMULATOR_HOME=str(scratch / "emulator-home"))
            for key in ("ANDROID_AVD_HOME", "ANDROID_USER_HOME", "ANDROID_EMULATOR_HOME"):
                Path(env[key]).mkdir()
            avd_name = "pyai_smoke_" + uuid.uuid4().hex
            receipt["avd_name"] = avd_name
            command([str(avdmanager), "create", "avd", "--name", avd_name,
                     "--package", SYSTEM_IMAGE, "--device", "pixel_2"], env=env, input="no\n", timeout=60)
            keystore = scratch / "disposable-preview.p12"
            signed_apk = scratch / "installed-preview.apk"
            password = "disposable-synthetic-preview"
            command(["keytool", "-genkeypair", "-noprompt", "-alias", "synthetic-emulator",
                     "-keyalg", "RSA", "-keysize", "2048", "-validity", "2", "-storetype", "PKCS12",
                     "-dname", "CN=Disposable Synthetic Emulator Test", "-keystore", str(keystore),
                     "-storepass", password, "-keypass", password])
            command([str(build_tools / "apksigner"), "sign", "--ks", str(keystore),
                     "--ks-key-alias", "synthetic-emulator", "--ks-pass", "pass:" + password,
                     "--out", str(signed_apk), str(apk)])
            certificate = command([str(build_tools / "apksigner"), "verify", "--verbose", "--print-certs", str(signed_apk)]).stdout
            cert_hash = re.search(r"Signer #1 certificate SHA-256 digest: ([0-9a-f]+)", certificate)
            require(cert_hash is not None, "Signed diagnostic certificate verification is missing")
            receipt["installed_apk_sha256"] = digest(signed_apk)
            receipt["disposable_certificate_sha256"] = cert_hash.group(1)
            emulator_log = scratch / "emulator.log"
            emulator_env = emulation_environment(env, settings["mode"])
            try:
                with emulator_log.open("wb") as log:
                    process = subprocess.Popen(
                        [str(emulator), "-avd", avd_name, "-port", "5554", "-no-window", "-no-audio",
                         "-no-boot-anim", "-no-snapshot", "-no-metrics", "-show-kernel", "-gpu", "swiftshader_indirect",
                         "-accel", settings["accel_argument"], "-cores", "2", "-memory", "2048",
                         "-camera-front", "none", "-camera-back", "none"],
                        env=emulator_env, stdout=log, stderr=subprocess.STDOUT, start_new_session=True,
                    )
                    device = Device(adb, env, process, avd_name, receipt)
                    boot_timeout = settings["boot_timeout_seconds"]
                    try:
                        wait_for_boot_completed(device, receipt, boot_timeout)
                    except Exception:
                        try:
                            retain_boot_failure_state(device, receipt)
                        except Exception as diagnostic_error:
                            receipt["boot_failure_diagnostics_error"] = bounded_text(str(diagnostic_error), 1200)
                        raise
                    device.verify_identity()
                    receipt["device"] = {key: device.shell("getprop", key) for key in
                                         ("ro.build.fingerprint", "ro.build.version.sdk", "ro.product.cpu.abi",
                                          "persist.sys.locale", "ro.product.locale")}
                    require(receipt["device"]["ro.build.version.sdk"] == "35" and
                            receipt["device"]["ro.product.cpu.abi"] == "x86_64", "Unexpected installed platform")
                    receipt["display"] = device.shell("wm", "size") + "\n" + device.shell("wm", "density")
                    for setting in ("window_animation_scale", "transition_animation_scale", "animator_duration_scale"):
                        device.shell("settings", "put", "global", setting, "0")
                    device.shell("settings", "put", "system", "screen_off_timeout", "1800000")
                    device.shell("settings", "put", "system", "accelerometer_rotation", "0")
                    device.shell("settings", "put", "system", "user_rotation", "0")
                    device.shell("input", "keyevent", "KEYCODE_WAKEUP")
                    device.shell("wm", "dismiss-keyguard")
                    try:
                        wait_for_guest_ready(device, receipt)
                    except Exception:
                        try:
                            retain_case_failure_state(device, receipt, phase="after_guest_readiness_failure")
                        except Exception as diagnostic_error:
                            receipt["guest_readiness_diagnostics_error"] = bounded_text(str(diagnostic_error), 1200)
                        raise
                    require(not device.shell("pm", "list", "packages", PACKAGE),
                            "Fresh AVD unexpectedly already contains the preview")
                    require("Success" in device.adb("install", "--no-streaming", str(signed_apk), timeout=60).stdout,
                            "Synthetic APK installation failed")
                    exercise(device, output, receipt)
            finally:
                if process is not None and process.poll() is None:
                    # Only the new process group created above, never a shared adb server or VM.
                    os.killpg(process.pid, signal.SIGTERM)
                    try:
                        process.wait(timeout=15)
                    except subprocess.TimeoutExpired:
                        os.killpg(process.pid, signal.SIGKILL)
                        process.wait(timeout=10)
                if emulator_log.exists():
                    with emulator_log.open("rb") as log:
                        log.seek(max(0, emulator_log.stat().st_size - 1_048_576))
                        (output / "emulator.log").write_bytes(log.read())
        receipt["unsigned_apk_unchanged"] = digest(apk) == original_hash
        receipt["status"] = "passed"
        complete_receipt(receipt, source, expected_mode=settings["mode"])
    except Exception as error:
        receipt.update(status="failed", error=str(error))
        print("Installed emulator smoke failed: " + str(error), file=sys.stderr)
    finally:
        if original_hash is not None and apk.is_file():
            receipt["unsigned_apk_unchanged"] = digest(apk) == original_hash
        write_reports(output, receipt)
    if receipt["status"] == "passed":
        print(f"Verified {len(CASE_IDS)} installed synthetic emulator cases at {source}; physical-device acceptance remains separate")
        return 0
    return 1


if __name__ == "__main__":
    sys.exit(main())
