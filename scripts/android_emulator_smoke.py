#!/usr/bin/env python3
"""Exercise the synthetic APK only in a fresh AVD owned by this hosted CI job."""

from __future__ import annotations

import contextlib
from collections import Counter
from decimal import Decimal, InvalidOperation
import hashlib
import json
import math
import os
from pathlib import Path
import platform
import re
import signal
import stat
import struct
import subprocess
import sys
import tempfile
import time
import uuid
import xml.etree.ElementTree as ET
import zlib


PACKAGE = "ai.pickyour.android.preview"
ACTIVITY = PACKAGE + ".PreviewActivity"
SERIAL = "emulator-5554"
SYSTEM_IMAGE = "system-images;android-35;default;x86_64"
IMAGE_REVISION = "2"
HVF_EMULATOR_VERSION = "37.1.11.0"
BOOT_TIMEOUT_SECONDS_BY_MODE = {"kvm": 300, "hvf": 600, "software": 900}
HVF_HOST_IDENTITY = {"runner_environment": "github-hosted", "runner_os": "macOS",
                     "runner_arch": "X64", "system": "Darwin", "machine": "x86_64"}
DISPLAY_CONFIGS = {
    "pixel-2": {"hw.lcd.width": "1080", "hw.lcd.height": "1920", "hw.lcd.density": "420"},
    "raster-720x1280-280": {"hw.lcd.width": "720", "hw.lcd.height": "1280", "hw.lcd.density": "280"},
}
DISPLAY_COMMANDS = {"size": ("shell", "wm", "size"), "density": ("shell", "wm", "density")}
CASE_IDS = (
    "installed_synthetic_launch",
    "acknowledged_save_survives_force_stop",
    "pending_fields_survive_rotation",
    "confirmed_deletion_does_not_resurrect",
)
TITLE_LABEL = "Draft title (up to 120 characters)"
OBJECTIVE_LABEL = "Draft objective (optional)"
CONTEXT_LABEL = "Draft context (optional)"
REQUESTS_LABEL = "Requests per day"
PENDING_REQUESTS = "invalid"
INVALID_WORKLOAD = "Some entered workload values are invalid. Your text is retained; correct it before saving."
UNSAVED_FORM = "Unsaved changes. Valid edits save after a short pause; Save locally saves them now."
BUTTON = "android.widget.Button"
EDITOR = "android.widget.EditText"
UI_DUMP_DIRECTORY = "/data/local/tmp"
UI_DUMP_PATH = UI_DUMP_DIRECTORY + "/pyai-smoke-window.xml"
HOME_PACKAGE = "com.android.launcher3"
HOME_WINDOW = HOME_PACKAGE + "/" + HOME_PACKAGE + ".uioverrides.QuickstepLauncher"
FRAMEWORK_BARRIER = ("shell", "am", "wait-for-broadcast-barrier", "--flush-application-threads")
HOME_FOCUS_QUERY = ("shell", "dumpsys", "-t", "3", "activity", "activities")
HOME_ROOT_ATTEMPT_LIMIT = 3
NAVIGATION_READINESS_ATTEMPT_LIMIT = 36
HOME_ROOT_UNAVAILABLE = "ERROR: null root node returned by UiTestAutomationBridge.\n"
UI_SCROLL_WHEEL_UNITS = 2
UI_SCROLL_MAX_STEPS = 64
UI_ROTATION_TIMEOUT_SECONDS = 30
UI_ROTATION_STATE_TEXT_LIMIT = 65536
ROTATION_STATE_QUERY = ("shell", "dumpsys", "-t", "3", "activity", "activities")
SYSTEM_BAR_CHECKPOINTS = (
    "05-pending-portrait", "06-explicit-delete-confirmation",
    "07-deleted-editor-keeps-pending-fields", "08-deleted-draft-stays-absent-after-relaunch",
)
SYSTEM_BAR_WINDOW_QUERY = ("shell", "dumpsys", "-t", "3", "window")
SYSTEM_BAR_LIGHTBAR_QUERY = ("shell", "dumpsys", "-t", "3", "activity", "service",
                            "com.android.systemui/.SystemUIService", "LightBarController")
SYSTEM_BAR_TASKBAR_QUERY = ("shell", "dumpsys", "-t", "3", "activity", "service",
                           "com.android.launcher3/com.android.quickstep.TouchInteractionService")
SYSTEM_BAR_WINDOW_LIMIT = 262144
SYSTEM_BAR_PNG_LIMIT = 8388608
SYSTEM_BAR_MINIMUM_CONTRAST = 3.0
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
BOOT_FRAMEWORK_COMMANDS = (
    ("framework_log", ("logcat", "-b", "main", "-b", "system", "-b", "crash", "-b", "events",
                       "-d", "-v", "threadtime", "-t", "2000", "SystemServer:V", "SystemServerTiming:V", "*:I"), 131072),
    ("framework_services", ("shell", "dumpsys", "-l"), 32768),
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
        raise SmokeFailure("KVM device is absent or unreadable; this access check changed no permissions") from None
    print(f"KVM device: mode={stat.filemode(metadata.st_mode)} owner={metadata.st_uid} group={metadata.st_gid}")
    require(stat.S_ISCHR(metadata.st_mode), "KVM path is not an existing character device")
    require(os.access(device, os.R_OK | os.W_OK, effective_ids=True),
            "KVM read/write access is unavailable; this access check changed no permissions")


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


def kvm_acceleration_passes(observed: dict) -> bool:
    return (hvf_command_passes(observed) and len(observed["stdout"]) <= 2048
            and re.fullmatch(r"accel:\r?\n0\r?\nKVM \(version [1-9][0-9]*\) is installed and usable\."
                             r"(?:\r?\naccel)?\r?\n?", observed["stdout"]) is not None)


def kvm_emulator_version_passes(version: str) -> bool:
    return (isinstance(version, str) and bool(version.splitlines()) and
            version.splitlines()[0].startswith("Android emulator version " + HVF_EMULATOR_VERSION +
                                               " (build_id 15917651) "))


def check_kvm_acceleration(emulator: Path, observed: dict) -> None:
    try:
        require(emulator.is_file() and not emulator.is_symlink(), "Expected the dedicated pinned Linux emulator")
        observe_hvf_command([str(emulator), "-accel-check"], observed, timeout=30, output_limit=2048)
        require(kvm_acceleration_passes(observed), "The selected non-root process did not confirm usable KVM")
    except Exception as error:
        observed["error"] = bounded_text(str(error), 1200)
        raise
    finally:
        print("KVM acceleration preflight: " + json.dumps(observed, sort_keys=True), flush=True)


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


def system_bar_bounds(root: ET.Element) -> list[tuple[int, int, int, int]]:
    """Read only the two known app-decor bar identities from this fresh hierarchy."""
    found, seen = [], set()
    for node in root.iter("node"):
        identity = node.get("resource-id")
        if identity in ("android:id/navigationBarBackground", "android:id/statusBarBackground"):
            require(identity not in seen and node.get("package") == PACKAGE,
                    "Ambiguous or foreign system bar identity")
            seen.add(identity)
            found.append(bounds(node))
    return found


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
        retain_boot_framework_state(device, state, deadline)
    finally:
        state["seconds"] = round(time.monotonic() - started, 3)


def retain_boot_framework_state(device, boot_state: dict, deadline: float):
    """Read existing framework state within the original boot-diagnostic budget."""
    state = {"phase": "after_boot_failure", "serial": SERIAL, "target_verified": False,
             "record_scope": "existing_global_framework_records_not_package_bound",
             "checks": {}, "unattempted_checks": [item[0] for item in BOOT_FRAMEWORK_COMMANDS],
             "deadline_reached": time.monotonic() >= deadline}
    boot_state["framework"] = state
    if state["deadline_reached"]:
        boot_state["deadline_reached"] = True
    try:
        identity = boot_state["checks"]
        for name in ("adb_state", "emulator_name", "emulator_property"):
            observed = identity.get(name)
            require(isinstance(observed, dict) and type(observed.get("exit_code")) is int
                    and observed["exit_code"] == 0 and observed.get("output_truncated") is False
                    and isinstance(observed.get("stdout_tail"), str) and observed.get("stderr_tail") == ""
                    and "command_timeout_seconds" not in observed and "error" not in observed,
                    "Complete owned boot identity observation is unavailable: " + name)
        require(identity["adb_state"]["stdout_tail"].strip() == "device", "Owned ADB transport is unavailable")
        validate_target(SERIAL, identity["emulator_property"]["stdout_tail"],
                        identity["emulator_name"]["stdout_tail"], device.avd_name)
        state["target_verified"] = True
    except Exception as error:
        state["identity_error"] = bounded_text(str(error), 1200)
        return  # Do not read broader guest state using an incomplete or foreign identity.
    for index, (name, arguments, retained_limit) in enumerate(BOOT_FRAMEWORK_COMMANDS):
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            state["deadline_reached"] = boot_state["deadline_reached"] = True
            break
        observed = {"stdout": ui_stream_observation(None), "stderr": ui_stream_observation(None)}
        state["checks"][name] = observed
        state["unattempted_checks"] = [item[0] for item in BOOT_FRAMEWORK_COMMANDS[index + 1:]]
        try:
            observe_interactive_command(device, arguments, observed,
                                        timeout=min(5, remaining), retained_limit=retained_limit)
        except subprocess.TimeoutExpired:
            observed["status"] = "timed_out"
        except Exception:
            observed["status"] = "error"  # The original command error and unavailable streams are retained.
        else:
            observed["status"] = "completed"  # This records an exit, including nonzero; it cannot rescue boot.
    if time.monotonic() >= deadline:
        state["deadline_reached"] = boot_state["deadline_reached"] = True


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
    state.update(started_monotonic=float(started), deadline_monotonic=float(deadline))
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
                if receipt.get("startup_display", {}).get("profile") == "raster-720x1280-280":
                    wait_for_navigation_provider(device, state, deadline,
                                                 source_sha=receipt.get("source_commit"),
                                                 apk_sha=receipt.get("unsigned_apk_sha256"))
                    require(time.monotonic() < deadline,
                            "Owned pre-install navigation provider/TIS readiness deadline expired")
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


def display_profile_setting(mode: str) -> str:
    profile = os.environ.get("PYAI_EMULATOR_DISPLAY_PROFILE", "pixel-2")
    require(profile in DISPLAY_CONFIGS, "Unknown startup display profile")
    require(profile == "pixel-2" or mode in ("hvf", "kvm"),
            "The raster experiment requires an explicit hardware-accelerated mode")
    return profile


def display_ini_content(raw: bytes) -> tuple[dict, list[str], dict]:
    """Read only the three startup display keys; preserve every other line."""
    require(0 < len(raw) <= 65536, "Unexpected owned AVD configuration size")
    lines = raw.decode("utf-8").splitlines(keepends=True)
    values, positions = {}, {}
    for index, line in enumerate(lines):
        key, separator, value = line.partition("=")
        key = key.strip()
        if separator and key in DISPLAY_CONFIGS["pixel-2"]:
            require(key not in values, "Duplicate startup display configuration key")
            require(re.fullmatch(r"[1-9][0-9]*", value.strip()) is not None,
                    "Invalid startup display configuration value")
            values[key], positions[key] = value.strip(), index
    require(set(values) == set(DISPLAY_CONFIGS["pixel-2"]), "Incomplete startup display configuration")
    return values, lines, positions


def owned_display_file(avd_root: Path, avd_name: str, filename: str) -> Path:
    require(re.fullmatch(r"pyai_smoke_[0-9a-f]{32}", avd_name) is not None,
            "Unexpected owned AVD identity for startup display")
    require(filename in ("config.ini", "hardware-qemu.ini"), "Unexpected startup display file")
    directory = avd_root / (avd_name + ".avd")
    require(avd_root.is_absolute() and avd_root.is_dir() and not avd_root.is_symlink()
            and directory.is_dir() and not directory.is_symlink()
            and directory.resolve().parent == avd_root.resolve(), "Startup display path is not the owned AVD")
    path = directory / filename
    metadata = path.lstat()
    require(stat.S_ISREG(metadata.st_mode) and metadata.st_nlink == 1 and 0 < metadata.st_size <= 65536,
            "Startup display file is not a bounded private regular file")
    return path


def display_config_observation(raw: bytes) -> dict:
    values, lines, positions = display_ini_content(raw)
    other = "".join(line for index, line in enumerate(lines) if index not in positions.values())
    return {"sha256": hashlib.sha256(raw).hexdigest(), "bytes": len(raw), "properties": values,
            "other_lines_sha256": hashlib.sha256(other.encode("utf-8")).hexdigest()}


def configure_startup_display(avd_root: Path, avd_name: str, profile: str, receipt: dict) -> None:
    """Configure only this newly created AVD, before the emulator process exists."""
    require(profile in DISPLAY_CONFIGS, "Unknown startup display profile")
    state = {"profile": profile, "avd_name": avd_name, "scope": "owned-avd-before-emulator-start",
             "verified": False}
    receipt["startup_display"] = state
    try:
        path = owned_display_file(avd_root, avd_name, "config.ini")
        raw = path.read_bytes()
        before = display_config_observation(raw)
        state["configuration_before"] = before
        require(before["properties"] == DISPLAY_CONFIGS["pixel-2"],
                "Fresh Pixel 2 display defaults changed; review before applying the experiment")
        values, lines, positions = display_ini_content(raw)
        if profile != "pixel-2":
            for key, value in DISPLAY_CONFIGS[profile].items():
                index = positions[key]
                ending = "\r\n" if lines[index].endswith("\r\n") else "\n" if lines[index].endswith("\n") else ""
                lines[index] = lines[index].partition("=")[0] + "=" + value + ending
            updated = "".join(lines).encode("utf-8")
            require(display_config_observation(updated)["other_lines_sha256"] == before["other_lines_sha256"],
                    "Startup display edit changed unrelated AVD configuration")
            path.write_bytes(updated)
        after = display_config_observation(path.read_bytes())
        state["configuration_after"] = after
        require(after["properties"] == DISPLAY_CONFIGS[profile]
                and after["other_lines_sha256"] == before["other_lines_sha256"],
                "Startup display configuration readback differs")
        state["changed_keys"] = sorted(DISPLAY_CONFIGS[profile]) if profile != "pixel-2" else []
    except Exception as error:
        state["error"] = bounded_text(str(error), 1200)
        raise


def startup_display_passes(state: dict, expected_profile: str | None = None) -> bool:
    if not (isinstance(state, dict) and isinstance(state.get("profile"), str)
            and state["profile"] in DISPLAY_CONFIGS and state.get("verified") is True
            and state.get("scope") == "owned-avd-before-emulator-start" and "error" not in state
            and isinstance(state.get("avd_name"), str)
            and re.fullmatch(r"pyai_smoke_[0-9a-f]{32}", state["avd_name"]) is not None):
        return False
    profile = state["profile"]
    if expected_profile is not None and profile != expected_profile:
        return False
    expected = DISPLAY_CONFIGS[profile]
    before, after, hardware = (state.get(name) for name in
                               ("configuration_before", "configuration_after", "emulator_hardware"))
    for observed, values in ((before, DISPLAY_CONFIGS["pixel-2"]), (after, expected), (hardware, expected)):
        if not (isinstance(observed, dict) and observed.get("properties") == values
                and type(observed.get("bytes")) is int and 0 < observed["bytes"] <= 65536
                and all(isinstance(observed.get(key), str) and re.fullmatch(r"[0-9a-f]{64}", observed[key])
                        for key in ("sha256", "other_lines_sha256"))):
            return False
    if (before["other_lines_sha256"] != after["other_lines_sha256"]
            or (before["sha256"] == after["sha256"]) is not (profile == "pixel-2")
            or state.get("changed_keys") != (sorted(expected) if profile != "pixel-2" else [])):
        return False
    observations = state.get("observations")
    if not (isinstance(observations, dict) and set(observations) == set(DISPLAY_COMMANDS)
            and all(interactive_command_passes(observations[name], args, 1024)
                    for name, args in DISPLAY_COMMANDS.items())):
        return False
    width, height, density = (int(expected[key]) for key in ("hw.lcd.width", "hw.lcd.height", "hw.lcd.density"))
    return (re.fullmatch(rf"Physical size: {width}x{height}\r?\n?", observations["size"]["stdout_tail"]) is not None
            and re.fullmatch(rf"Physical density: {density}\r?\n?", observations["density"]["stdout_tail"]) is not None
            and width * 420 == 1080 * density and height * 420 == 1920 * density)


def observe_startup_display(device, avd_root: Path, receipt: dict) -> None:
    state = receipt["startup_display"]
    try:
        require(device.avd_name == state["avd_name"], "Startup display belongs to a different AVD")
        device.verify_identity()
        path = owned_display_file(avd_root, state["avd_name"], "hardware-qemu.ini")
        state["emulator_hardware"] = display_config_observation(path.read_bytes())
        observations = {}
        state["observations"] = observations
        for name, arguments in DISPLAY_COMMANDS.items():
            observed = {}
            observations[name] = observed
            observe_interactive_command(device, arguments, observed, timeout=30, retained_limit=1024)
        receipt["display"] = "\n".join(observations[name]["stdout_tail"].strip() for name in DISPLAY_COMMANDS)
        state["verified"] = True
        require(startup_display_passes(state), "Observed startup display does not match the selected phone geometry")
    except Exception as error:
        state["verified"] = False
        state["error"] = bounded_text(str(error), 1200)
        raise


def complete_receipt(receipt: dict, source_sha: str, expected_mode: str | None = None,
                     expected_display_profile: str | None = None,
                     expected_system_bar_evidence: bool = False) -> None:
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
    if emulation["mode"] == "kvm":
        require(kvm_acceleration_passes(receipt.get("host_acceleration")) and
                kvm_emulator_version_passes(receipt.get("emulator_version")),
                "Complete pinned Linux KVM acceleration evidence is missing")
    if emulation["mode"] in ("hvf", "kvm"):
        require_apk_handoff(receipt.get("unsigned_apk_sha256"), receipt.get("expected_unsigned_apk_sha256"))
    if expected_display_profile is not None or "startup_display" in receipt:
        display = receipt.get("startup_display")
        require(startup_display_passes(display, expected_display_profile),
                "Complete startup display configuration evidence is missing or inconsistent")
        require(display["avd_name"] == receipt.get("avd_name")
                and (display["profile"] == "pixel-2" or emulation["mode"] in ("hvf", "kvm")),
                "Startup display differs from the owned AVD or selected mode")
    readiness = receipt.get("guest_readiness", {})
    require(isinstance(readiness, dict) and readiness.get("ready") is True and
            readiness.get("ui_dump_path") == UI_DUMP_PATH and
            guest_readiness_checks_pass(readiness.get("last_checks")),
            "Complete owned-guest readiness evidence is missing")
    require(interactive_readiness_passes(readiness.get("interactive")),
            "Complete pre-installation framework/home readiness evidence is missing")
    if expected_system_bar_evidence or "system_bar_evidence" in receipt:
        require(system_bar_evidence_passes(receipt.get("system_bar_evidence")),
                "Complete installed navigation contrast/window evidence is missing or failed")
        require(startup_display_passes(receipt.get("startup_display"),
                                       receipt["system_bar_evidence"]["profile"]),
                "Installed navigation evidence has no matching qualified startup display")
        require(receipt["system_bar_evidence"]["source_commit"] == source_sha
                and receipt["system_bar_evidence"]["unsigned_apk_sha256"] == receipt.get("unsigned_apk_sha256"),
                "Installed navigation evidence belongs to another source or APK")
        require(navigation_readiness_passes(readiness.get("navigation_provider"), source_sha,
                                            receipt.get("unsigned_apk_sha256")),
                "Complete pre-install navigation provider/TIS readiness evidence is missing or failed")
        navigation = readiness["navigation_provider"]
        require(type(readiness.get("timeout_seconds")) in (int, float)
                and math.isfinite(readiness["timeout_seconds"]) and 0 < readiness["timeout_seconds"] <= 180
                and all(type(readiness.get(key)) is float and math.isfinite(readiness[key])
                        for key in ("started_monotonic", "deadline_monotonic"))
                and 0 <= readiness["started_monotonic"] <= navigation["started"]
                and readiness["deadline_monotonic"] == navigation["readiness_deadline"]
                and readiness["deadline_monotonic"] == readiness["started_monotonic"] + readiness["timeout_seconds"],
                "Pre-install navigation readiness differs from the original guest readiness budget")


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


def home_root_unavailable_passes(attempt: dict) -> bool:
    """Recognize only the observed pre-installation null-root response for retry."""
    if not (isinstance(attempt, dict) and attempt.get("stage") == "dump"
            and attempt.get("completed") is False and attempt.get("dump_completion_passed") is False
            and attempt.get("ui_dump_path") == UI_DUMP_PATH
            and attempt.get("error") == {"type": "SmokeFailure",
                                        "message": "UI hierarchy dump failed (0): " + HOME_ROOT_UNAVAILABLE}
            and isinstance(attempt.get("commands"), dict)
            and set(attempt["commands"]) == {"remove", "dump"}):
        return False
    for name, arguments, stderr in (
            ("remove", ("shell", "rm", "-f", UI_DUMP_PATH), ""),
            ("dump", ("shell", "uiautomator", "dump", UI_DUMP_PATH), HOME_ROOT_UNAVAILABLE)):
        observed = attempt["commands"][name]
        if not (isinstance(observed, dict) and observed.get("arguments") == list(arguments)
                and type(observed.get("exit_code")) is int and observed["exit_code"] == 0
                and "command_timeout_seconds" not in observed and "error" not in observed
                and ui_stream_matches_text(observed.get("stdout"), "")
                and ui_stream_matches_text(observed.get("stderr"), stderr)):
            return False
    return True


def home_attempt_sequence_passes(state: dict) -> bool:
    if not isinstance(state, dict):
        return False
    observations = state.get("ui_hierarchy_observations", {})
    dumps = state.get("ui_hierarchy_dumps", {})
    if not (isinstance(observations, dict) and isinstance(dumps, dict)
            and type(observations.get("attempted")) is int
            and 1 <= observations["attempted"] <= HOME_ROOT_ATTEMPT_LIMIT
            and isinstance(observations.get("recent_attempts"), list)
            and len(observations["recent_attempts"]) == observations["attempted"]):
        return False
    attempts = observations["recent_attempts"]
    if not all(isinstance(attempt, dict) and type(attempt.get("sequence")) is int
               and attempt["sequence"] == index + 1 for index, attempt in enumerate(attempts)):
        return False
    if observations.get("last_attempt") != attempts[-1]:
        return False
    if len(attempts) == 1:
        if "first_failed_attempt" in observations:
            return False
    elif (observations.get("first_failed_attempt") != attempts[0]
          or not all(home_root_unavailable_passes(attempt) for attempt in attempts[:-1])):
        return False
    commands = attempts[-1].get("commands")
    if not isinstance(commands, dict) or not isinstance(commands.get("dump"), dict):
        return False
    final_dump = commands["dump"]
    return (type(dumps.get("attempted")) is int and dumps["attempted"] == len(attempts)
            and type(dumps.get("completed")) is int and dumps["completed"] == 1
            and type(dumps.get("exit_137")) is int
            and dumps["exit_137"] == (1 if final_dump.get("exit_code") == 137 else 0))


def interactive_readiness_passes(state: dict) -> bool:
    if not (isinstance(state, dict) and state.get("ready") is True
            and state.get("phase") == "before_installation" and state.get("serial") == SERIAL
            and state.get("home_package") == HOME_PACKAGE and "error" not in state
            and framework_barrier_passes(state.get("barrier")) and home_focus_passes(state.get("focus"))
            and home_attempt_sequence_passes(state)):
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
            and hierarchy.get("packages") in (
                [HOME_PACKAGE], [HOME_PACKAGE, "com.android.systemui"],
                [HOME_PACKAGE, "com.android.quicksearchbox"],
                [HOME_PACKAGE, "com.android.quicksearchbox", "com.android.systemui"])
            and type(hierarchy.get("visible_home_nodes")) is int and hierarchy["visible_home_nodes"] > 0
            and isinstance(hierarchy.get("read_text_sha256"), str)
            and re.fullmatch(r"[0-9a-f]{64}", hierarchy["read_text_sha256"]) is not None
            and type(attempt.get("sequence")) is int and attempt["sequence"] == observations["attempted"]
            and "error" not in attempt
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
        ui = Ui(device, Path("."), state)
        for index in range(HOME_ROOT_ATTEMPT_LIMIT):
            try:
                root = ui.snapshot(deadline=deadline)
                break
            except SmokeFailure:
                # This is a HOME precondition only. Each failed response remains
                # in the receipt; installed snapshots and app cases never retry it.
                if (index + 1 == HOME_ROOT_ATTEMPT_LIMIT or time.monotonic() >= deadline
                        or not home_root_unavailable_passes(ui.observations.get("last_attempt"))):
                    raise
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


def navigation_provider_state(text: str) -> dict | None:
    """Read the fixed-rig navigation provider in the current default HOME display.

    The caller separately binds the complete HOME_FOCUS_QUERY command and output.
    InsetsState and its provider deliberately repeat the same source: each has
    its own scope and the two records must agree.
    """
    if not (isinstance(text, str) and len(text) <= 65536
            and text.startswith("ACTIVITY MANAGER ACTIVITIES (dumpsys activity activities)\n")
            and not any(marker in text for marker in
                        ("\x00", "DUMP TIMEOUT", "Error dumping service info:"))):
        return None
    headers = list(re.finditer(r"(?m)^Display #[^\n]*$", text))
    ids = []
    for header in headers:
        match = re.fullmatch(r"Display #(0|[1-9][0-9]*) \(activities from top to bottom\):", header.group())
        if match is None:
            return None
        ids.append(match.group(1))
    if ids.count("0") != 1 or len(ids) != len(set(ids)):
        return None
    index = ids.index("0")
    section = text[headers[index].end():headers[index + 1].start() if index + 1 < len(headers) else len(text)]

    def block(parent, name, spaces):
        indent = " " * spaces
        matches = list(re.finditer(r"(?m)^" + re.escape(indent + name) + r"$", parent))
        if len(matches) != 1:
            return None
        tail = parent[matches[0].end():]
        end = re.search(r"(?m)^" + indent + r"\S", tail)
        return tail[:end.start()] if end is not None else tail

    frames = re.findall(r"(?m)^  DisplayFrames[^\n]*$", section)
    if frames != ["  DisplayFrames w=720 h=1280 r=0"]:
        return None
    policy = block(section, "DisplayPolicy", 2)
    controller = block(section, "WindowInsetsStateController", 2)
    if policy is None or controller is None:
        return None
    navigation_windows = re.findall(r"(?m)^    mNavigationBar=([^\n]*)$", policy)
    bottom_hosts = re.findall(r"(?m)^    mBottomGestureHost=([^\n]*)$", policy)
    if not (len(navigation_windows) == 1
            and re.fullmatch(r"Window\{[0-9a-f]{1,8} u0 Taskbar\}", navigation_windows[0])
            and bottom_hosts == navigation_windows):
        return None
    window = navigation_windows[0]
    state = block(controller, "InsetsState", 4)
    providers = block(controller, "InsetsSourceProviders:", 4)
    if state is None or providers is None:
        return None
    if re.findall(r"(?m)^      mDisplayFrame=([^\n]*)$", state) != ["Rect(0, 0 - 720, 1280)"]:
        return None
    state_sources = re.findall(r"(?m)^        InsetsSource ([^\n]*)$", state)
    nav_sources = [line for line in state_sources if re.search(r"(?:^| )type=navigationBars(?: |$)", line)]
    if len(nav_sources) != 1:
        return None
    source = nav_sources[0]
    match = re.fullmatch(
        r"id=([0-9a-f]{1,8}) type=navigationBars frame=\[0,1196\]\[720,1280\] "
        r"visible=true flags=[A-Z_|]* sideHint=BOTTOM boundingRects=null", source)
    if match is None:
        return None
    source_id = match.group(1)
    if sum(line.startswith("id=" + source_id + " ") for line in state_sources) != 1:
        return None
    provider_headers = list(re.finditer(r"(?m)^      (\S[^\n]*)$", providers))
    selected = []
    for index, header in enumerate(provider_headers):
        body = providers[header.end():provider_headers[index + 1].start()
                         if index + 1 < len(provider_headers) else len(providers)]
        rows = re.findall(r"(?m)^        mSource=InsetsSource ([^\n]*)$", body)
        if any(re.search(r"(?:^| )type=navigationBars(?: |$)", row) for row in rows):
            selected.append((header.group(1), body, rows))
    if len(selected) != 1:
        return None
    label, provider, rows = selected[0]
    if not (label == "InsetsSourceProvider" and rows == [source]
            and re.findall(r"(?m)^        mSourceFrame=([^\n]*)$", provider)
            == ["Rect(0, 1196 - 720, 1280)"]
            and re.findall(r"(?m)^        mWindowContainer=([^\n]*)$", provider) == [window]
            and re.findall(r"(?m)^        mIsLeashReadyForDispatching=[^\n]*$", provider)
            == ["        mIsLeashReadyForDispatching=true mHasPendingPosition=false"]):
        return None
    return {"source_id": source_id, "window": window, "frame": [0, 1196, 720, 1280]}


def initialized_taskbar_service_passes(text: str) -> bool:
    """Observe initialized TIS fields and the existing complete Taskbar scope."""
    if not taskbar_state_fields_pass(text):
        return False
    lines = text.splitlines()
    selected = []
    for name in ("Client", "OverviewComponentObserver", "OverviewCommandHelper",
                 "TaskAnimationManager", "TaskbarManager"):
        matches = [(index, line) for index, line in enumerate(lines)
                   if re.match(r"^[ \t]*" + re.escape(name) + r":", line)]
        if len(matches) != 1 or matches[0][1].strip() != name + ":":
            return False
        index, line = matches[0]
        selected.append((index, len(line) - len(line.lstrip(" \t"))))
    return (all(left[0] < right[0] for left, right in zip(selected, selected[1:]))
            and selected[1][1] == selected[2][1] == selected[3][1] == selected[4][1]
            and selected[1][1] > selected[0][1])


def navigation_readiness_command_passes(row: dict, arguments: tuple[str, ...], limit: int) -> bool:
    if not (isinstance(row, dict) and system_bar_interval_passes(row.get("interval"), 5)
            and interactive_command_passes(row.get("command"), arguments, limit)):
        return False
    command = row["command"]
    timeout = command.get("timeout_seconds")
    return (type(timeout) in (int, float) and math.isfinite(timeout) and 0 < timeout <= 5
            and not any(marker in command["stdout_tail"] for marker in (
                "DUMP TIMEOUT", "Error dumping service info:",
                "Failure while dumping the service:", "Got a RemoteException while dumping the service")))


def navigation_readiness_passes(state: dict, source_sha: str, apk_sha: str) -> bool:
    """Require every retained attempt and a complete final pre-install provider."""
    if not (isinstance(state, dict) and state.get("ready") is True and "error" not in state
            and state.get("phase") == "before_installation" and state.get("serial") == SERIAL
            and state.get("profile") == "raster-720x1280-280"
            and isinstance(source_sha, str) and re.fullmatch(r"[0-9a-f]{40}", source_sha)
            and isinstance(apk_sha, str) and re.fullmatch(r"[0-9a-f]{64}", apk_sha)
            and state.get("source_commit") == source_sha and state.get("unsigned_apk_sha256") == apk_sha
            and state.get("attempt_limit") == NAVIGATION_READINESS_ATTEMPT_LIMIT
            and all(type(state.get(key)) is float and math.isfinite(state[key])
                    for key in ("started", "ended", "readiness_deadline"))
            and 0 <= state["started"] <= state["ended"] < state["readiness_deadline"]
            and 0 < state["readiness_deadline"] - state["started"] <= 180
            and isinstance(state.get("attempts"), list)
            and 1 <= len(state["attempts"]) <= NAVIGATION_READINESS_ATTEMPT_LIMIT):
        return False
    previous_end = state["started"]
    for index, attempt in enumerate(state["attempts"]):
        if not (isinstance(attempt, dict) and type(attempt.get("sequence")) is int
                and attempt["sequence"] == index + 1 and "error" not in attempt
                and navigation_readiness_command_passes(attempt.get("window"), HOME_FOCUS_QUERY, 65536)
                and navigation_readiness_command_passes(attempt.get("taskbar"),
                                                        SYSTEM_BAR_TASKBAR_QUERY, SYSTEM_BAR_WINDOW_LIMIT)):
            return False
        window, taskbar = attempt["window"], attempt["taskbar"]
        if not (previous_end <= window["interval"]["start"] <= window["interval"]["end"]
                <= taskbar["interval"]["start"] <= taskbar["interval"]["end"] <= state["ended"]
                and home_focus_passes(window["command"])):
            return False
        for row in (window, taskbar):
            if row["command"]["timeout_seconds"] > state["readiness_deadline"] - row["interval"]["start"]:
                return False
        provider = navigation_provider_state(window["command"]["stdout_tail"])
        initialized = initialized_taskbar_service_passes(taskbar["command"]["stdout_tail"])
        if (attempt.get("provider") != provider or type(attempt.get("taskbar_initialized")) is not bool
                or attempt["taskbar_initialized"] is not initialized):
            return False
        ready = provider is not None and initialized
        if (attempt.get("status") != ("ready" if ready else "not_ready")
                or ready is not (index == len(state["attempts"]) - 1)):
            return False
        previous_end = taskbar["interval"]["end"]
    return True


def wait_for_navigation_provider(device: Device, readiness: dict, deadline: float, *,
                                 source_sha: str, apk_sha: str):
    """Poll owned startup observations within the original readiness deadline."""
    state = {"ready": False, "phase": "before_installation", "serial": SERIAL,
             "profile": "raster-720x1280-280", "source_commit": source_sha,
             "unsigned_apk_sha256": apk_sha, "attempt_limit": NAVIGATION_READINESS_ATTEMPT_LIMIT,
             "started": float(time.monotonic()), "readiness_deadline": float(deadline), "attempts": []}
    readiness["navigation_provider"] = state
    try:
        require(isinstance(source_sha, str) and re.fullmatch(r"[0-9a-f]{40}", source_sha)
                and isinstance(apk_sha, str) and re.fullmatch(r"[0-9a-f]{64}", apk_sha),
                "Pre-install navigation readiness source/APK identity is missing")
        for index in range(NAVIGATION_READINESS_ATTEMPT_LIMIT):
            require(time.monotonic() < deadline,
                    "Owned pre-install navigation provider/TIS readiness deadline expired")
            attempt = {"sequence": index + 1, "status": "observing"}
            state["attempts"].append(attempt)
            try:
                for name, arguments, limit in (
                        ("window", HOME_FOCUS_QUERY, 65536),
                        ("taskbar", SYSTEM_BAR_TASKBAR_QUERY, SYSTEM_BAR_WINDOW_LIMIT)):
                    row = {"command": {}, "interval": {"clock": "host_monotonic",
                                                       "start": float(time.monotonic())}}
                    attempt[name] = row
                    try:
                        observe_interactive_command(device, arguments, row["command"],
                                                    timeout=command_budget(deadline, 5), retained_limit=limit)
                    finally:
                        row["interval"]["end"] = float(time.monotonic())
                    require(navigation_readiness_command_passes(row, arguments, limit),
                            "Incomplete pre-install navigation readiness command: " + name)
                    if name == "window":
                        require(home_focus_passes(row["command"]),
                                "Owned HOME lost focus before navigation-provider readiness")
                attempt["provider"] = navigation_provider_state(attempt["window"]["command"]["stdout_tail"])
                attempt["taskbar_initialized"] = initialized_taskbar_service_passes(
                    attempt["taskbar"]["command"]["stdout_tail"])
                ready = attempt["provider"] is not None and attempt["taskbar_initialized"]
                attempt["status"] = "ready" if ready else "not_ready"
                if ready:
                    state.update(ready=True, ended=float(time.monotonic()))
                    require(navigation_readiness_passes(state, source_sha, apk_sha),
                            "Owned pre-install navigation provider/TIS evidence is inconsistent or late")
                    return
            except Exception as error:
                attempt.update(status="failed", error={"type": type(error).__name__,
                                                       "message": bounded_text(str(error), 1200)})
                raise
            remaining = deadline - time.monotonic()
            if index + 1 < NAVIGATION_READINESS_ATTEMPT_LIMIT and remaining > 0:
                time.sleep(min(5, remaining))
        raise SmokeFailure("Owned pre-install navigation provider/TIS did not initialize within the bounded observations")
    except Exception as error:
        state.update(ready=False, error={"type": type(error).__name__, "message": bounded_text(str(error), 1200)})
        raise
    finally:
        state["ended"] = float(time.monotonic())
        state["seconds"] = round(state["ended"] - state["started"], 3)


def default_display_rotation_state(text: str) -> dict | None:
    """Read only the current DisplayRotation block for the owned default display."""
    if not (isinstance(text, str) and len(text) <= UI_ROTATION_STATE_TEXT_LIMIT
            and text.startswith("ACTIVITY MANAGER ACTIVITIES (dumpsys activity activities)\n")
            and "DUMP TIMEOUT" not in text and "Error dumping service info:" not in text
            and "\x00" not in text):
        return None
    headers = list(re.finditer(
        r"(?m)^Display #(0|[1-9][0-9]*) \(activities from top to bottom\):$", text))
    ids = [header.group(1) for header in headers]
    if ids.count("0") != 1 or len(ids) != len(set(ids)):
        return None
    index = ids.index("0")
    section = text[headers[index].end():headers[index + 1].start() if index + 1 < len(headers) else len(text)]
    blocks = list(re.finditer(r"(?m)^  DisplayRotation$", section))
    if len(blocks) != 1:
        return None
    section = section[blocks[0].end():]
    end = re.search(r"(?m)^  [^ \n]", section)
    if end is not None:
        section = section[:end.start()]
    current_lines = re.findall(r"(?m)^    mRotation=.*$", section)
    requested_lines = re.findall(r"(?m)^    mUserRotationMode=.*$", section)
    if len(current_lines) != 1 or len(requested_lines) != 1:
        return None
    current = re.fullmatch(
        r"    mRotation=([0-3]) mDeferredRotationPauseCount=(0|[1-9][0-9]{0,9})", current_lines[0])
    requested = re.fullmatch(
        r"    mUserRotationMode=(USER_ROTATION_FREE|USER_ROTATION_LOCKED) "
        r"mUserRotation=(ROTATION_0|ROTATION_90|ROTATION_180|ROTATION_270) "
        r"mCameraRotationMode=[0-9]+ mAllowAllRotations=(?:true|false|unknown)", requested_lines[0])
    if current is None or requested is None:
        return None
    rotation, paused = current.groups()
    mode, requested_rotation = requested.groups()
    return {"display_id": 0, "current_rotation": int(rotation), "deferred_pause_count": int(paused),
            "user_rotation_mode": mode,
            "user_rotation": ("ROTATION_0", "ROTATION_90", "ROTATION_180", "ROTATION_270").index(requested_rotation)}


def decode_navigation_png(data: bytes) -> tuple[int, int, bytearray]:
    """Decode only the observed fixed-rig 8-bit, non-interlaced sRGB RGBA format.

    Original bytes are never changed. Both compressed input and inflation are
    bounded; unsupported profiles/chunks fail instead of guessing their colors.
    """
    require(isinstance(data, bytes) and 8 < len(data) <= SYSTEM_BAR_PNG_LIMIT
            and data[:8] == b"\x89PNG\r\n\x1a\n", "Unsupported or oversized navigation PNG")
    offset, width, height = 8, 0, 0
    kinds, compressed = [], bytearray()
    while offset < len(data):
        require(offset + 12 <= len(data), "Truncated navigation PNG chunk")
        length = struct.unpack_from(">I", data, offset)[0]
        kind = data[offset + 4:offset + 8]
        end = offset + 12 + length
        require(end <= len(data), "Truncated navigation PNG payload")
        payload = data[offset + 8:end - 4]
        require(zlib.crc32(kind + payload) & 0xffffffff == struct.unpack_from(">I", data, end - 4)[0],
                "Navigation PNG chunk CRC differs")
        require(kind in (b"IHDR", b"sRGB", b"sBIT", b"IDAT", b"IEND"),
                "Unqualified navigation PNG chunk or color profile")
        if kind == b"IHDR":
            require(not kinds and length == 13, "Duplicate or misplaced navigation PNG header")
            width, height, depth, color, compression, filtering, interlace = struct.unpack(">IIBBBBB", payload)
            require((width, height) == (720, 1280) and (depth, color, compression, filtering, interlace)
                    == (8, 6, 0, 0, 0), "Navigation PNG does not match the qualified portrait raster")
        elif kind == b"sRGB":
            require(kinds == [b"IHDR"] and payload == b"\x00", "Unqualified navigation PNG sRGB intent")
        elif kind == b"sBIT":
            require(kinds == [b"IHDR", b"sRGB"] and payload == b"\x08\x08\x08\x08",
                    "Unqualified navigation PNG component precision")
        elif kind == b"IDAT":
            require(kinds[:3] == [b"IHDR", b"sRGB", b"sBIT"]
                    and all(value == b"IDAT" for value in kinds[3:]), "Misordered navigation PNG data")
            compressed.extend(payload)
        else:
            require(length == 0 and end == len(data) and b"IDAT" in kinds,
                    "Missing image data or trailing navigation PNG content")
        # One marker represents all consecutive IDAT chunks, including empty
        # chunks. Header/order validation therefore has constant work per chunk.
        if kind != b"IDAT" or kinds[-1] != b"IDAT":
            kinds.append(kind)
        offset = end
    require(kinds and kinds[-1] == b"IEND", "Missing navigation PNG end")
    stride = width * 4
    expected = height * (stride + 1)
    inflater = zlib.decompressobj()
    raw = inflater.decompress(compressed, expected + 1)
    require(len(raw) == expected and inflater.eof and not inflater.unused_data
            and not inflater.unconsumed_tail, "Navigation PNG inflation differs from bounded image size")
    pixels, previous = bytearray(), bytearray(stride)
    for y in range(height):
        start = y * (stride + 1)
        method = raw[start]
        require(method in range(5), "Unknown navigation PNG row filter")
        row = bytearray(raw[start + 1:start + 1 + stride])
        if method:
            for x in range(stride):
                left = row[x - 4] if x >= 4 else 0
                above = previous[x]
                upper_left = previous[x - 4] if x >= 4 else 0
                if method == 1:
                    predictor = left
                elif method == 2:
                    predictor = above
                elif method == 3:
                    predictor = (left + above) // 2
                else:
                    estimate = left + above - upper_left
                    distances = (abs(estimate - left), abs(estimate - above), abs(estimate - upper_left))
                    predictor = (left, above, upper_left)[distances.index(min(distances))]
                row[x] = (row[x] + predictor) & 255
        pixels.extend(row)
        previous = row
    return width, height, pixels


def navigation_contrast(first: tuple[int, ...], second: tuple[int, ...]) -> float:
    def luminance(color):
        channels = [value / 255 for value in color[:3]]
        linear = [value / 12.92 if value <= 0.04045 else ((value + 0.055) / 1.055) ** 2.4
                  for value in channels]
        return sum(value * weight for value, weight in zip(linear, (0.2126, 0.7152, 0.0722)))
    values = sorted((luminance(first), luminance(second)))
    return (values[1] + 0.05) / (values[0] + 0.05)


def navigation_image(png: bytes, xml: bytes, *, modal_text: str | None = None,
                     modal_button: str | None = None) -> tuple[list[tuple[int, ...]], dict]:
    width, height, pixels = decode_navigation_png(png)
    require(isinstance(xml, bytes) and 0 < len(xml) <= 262144, "Unqualified navigation hierarchy size")
    root = ET.fromstring(xml)
    nodes = list(root.iter("node"))
    require(root.tag == "hierarchy" and root.get("rotation") == "0" and nodes
            and all(node.get("package") == PACKAGE for node in nodes),
            "Navigation hierarchy is not the fresh owned portrait window")
    bars = [node for node in nodes if node.get("resource-id") == "android:id/navigationBarBackground"]
    if modal_text is None:
        require(len(bars) == 1 and bars[0].get("class") == "android.view.View"
                and bars[0].get("enabled") == "true" and bounds(bars[0]) == (0, 1196, 720, 1280),
                "Missing or ambiguous fixed-rig navigation background")
    else:
        messages = [node for node in nodes if modal_text.casefold() in node.get("text", "").casefold()]
        buttons = [node for node in nodes if node.get("text", "").casefold() == modal_button.casefold()
                   and node.get("class") == BUTTON and node.get("enabled") == "true"]
        require(not bars and len(messages) == 1 and len(buttons) == 1
                and all(visible(node) and bounds(node)[2] <= width and bounds(node)[3] <= height
                        for node in messages + buttons),
                "Navigation modal hierarchy identity is missing or ambiguous")
    # The ordinary hierarchy identifies this fixed-rig rectangle. Modal hierarchies
    # omit the underlying bar; the paired same-size image must preserve its glyph geometry.
    region = [tuple(pixels[index:index + 4]) for index in range(1196 * width * 4, len(pixels), 4)]
    require(len(region) == 60480 and all(color[3] == 255 for color in region),
            "Navigation region is incomplete or transparent")
    return region, {"png_bytes": len(png), "png_sha256": hashlib.sha256(png).hexdigest(),
                    "xml_bytes": len(xml), "xml_sha256": hashlib.sha256(xml).hexdigest(),
                    "dimensions": [width, height], "bounds": [0, 1196, 720, 1280]}


def navigation_components(region: list[tuple[int, ...]], color: tuple[int, ...]) -> list[set[int]]:
    remaining = {index for index, value in enumerate(region) if value == color}
    components = []
    while remaining:
        seed = min(remaining)
        remaining.remove(seed)
        component, pending = {seed}, [seed]
        while pending:
            index = pending.pop()
            x, y = index % 720, index // 720
            neighbors = ([index - 1] if x else []) + ([index + 1] if x < 719 else [])
            neighbors += ([index - 720] if y else []) + ([index + 720] if y < 83 else [])
            for neighbor in neighbors:
                if neighbor in remaining:
                    remaining.remove(neighbor)
                    component.add(neighbor)
                    pending.append(neighbor)
        components.append(component)
    components.sort(key=lambda group: min(index % 720 for index in group))
    require(len(components) == 3, "Ordinary navigation glyph geometry is ambiguous")
    for position, component in enumerate(components):
        xs, ys = [index % 720 for index in component], [index // 720 for index in component]
        require(250 <= len(component) <= 900 and position * 240 + 16 <= min(xs)
                and max(xs) < (position + 1) * 240 - 16 and 12 <= max(xs) - min(xs) + 1 <= 48
                and 12 <= max(ys) - min(ys) + 1 <= 48 and min(ys) >= 12 and max(ys) < 72,
                "Ordinary navigation glyph placement is outside the fixed rig")
    return components


def measure_navigation_pair(reference_png: bytes, reference_xml: bytes, modal_png: bytes,
                            modal_xml: bytes, modal_text: str, modal_button: str) -> dict:
    reference, reference_identity = navigation_image(reference_png, reference_xml)
    modal, modal_identity = navigation_image(modal_png, modal_xml, modal_text=modal_text,
                                             modal_button=modal_button)
    common = Counter(reference).most_common()
    require(len(common) >= 2 and common[0][1] * 100 >= len(reference) * 95
            and 1000 <= common[1][1] <= 2700
            and (len(common) == 2 or common[2][1] * 10 < common[1][1]),
            "Ordinary navigation color populations are ambiguous")
    background, foreground = common[0][0], common[1][0]
    groups = navigation_components(reference, foreground)
    reference_ratio = navigation_contrast(background, foreground)
    require(reference_ratio >= SYSTEM_BAR_MINIMUM_CONTRAST, "Ordinary navigation contrast is below 3:1")
    background_indices = [index for index, color in enumerate(reference) if color == background]
    modal_background, background_count = Counter(modal[index] for index in background_indices).most_common(1)[0]
    require(background_count * 100 >= len(background_indices) * 95,
            "Modal navigation background is not a uniform paired region")
    measured = []
    for group in groups:
        color, count = Counter(modal[index] for index in group).most_common(1)[0]
        require(count * 100 >= len(group) * 95 and color != modal_background,
                "Modal navigation glyph is absent or differs from paired geometry")
        ratio = navigation_contrast(color, modal_background)
        measured.append({"reference_solid_pixels": len(group), "matching_modal_pixels": count,
                         "modal_glyph_rgb": list(color[:3]), "contrast": ratio,
                         "reference_bounds": [min(index % 720 for index in group),
                                              min(index // 720 for index in group) + 1196,
                                              max(index % 720 for index in group) + 1,
                                              max(index // 720 for index in group) + 1197]})
    require(len({tuple(row["modal_glyph_rgb"]) for row in measured}) == 1,
            "Modal navigation glyph colors are inconsistent")
    return {"reference": reference_identity, "modal": modal_identity,
            "reference_background_rgb": list(background[:3]), "reference_glyph_rgb": list(foreground[:3]),
            "reference_contrast": reference_ratio, "modal_background_rgb": list(modal_background[:3]),
            "minimum_contrast": SYSTEM_BAR_MINIMUM_CONTRAST, "glyphs": measured,
            "status": "passed" if all(row["contrast"] >= SYSTEM_BAR_MINIMUM_CONTRAST for row in measured)
            else "failed_contrast"}


def retain_system_bar_window(device: Device, output: Path, receipt: dict, name: str) -> None:
    state = receipt.setdefault("system_bar_evidence", {"scope": "fixed-rig-navigation-contrast",
        "profile": receipt.get("startup_display", {}).get("profile"), "serial": SERIAL,
        "source_commit": receipt.get("source_commit"), "unsigned_apk_sha256": receipt.get("unsigned_apk_sha256"),
        "window_observations": {}})
    observations = state["window_observations"]
    require(name in SYSTEM_BAR_CHECKPOINTS and name not in observations,
            "Duplicate or unexpected system-bar checkpoint")
    observed = {"phase": "after_original_png_capture", "command": {},
                "png_sha256": digest(output / (name + ".png")), "xml_sha256": digest(output / (name + ".xml"))}
    observations[name] = observed
    observed["png_capture"] = receipt.get("system_bar_png_captures", {}).get(name)
    observed["timing"] = {"clock": "host_monotonic", "start": time.monotonic()}
    try:
        observe_interactive_command(device, SYSTEM_BAR_WINDOW_QUERY, observed["command"],
                                    timeout=5, retained_limit=SYSTEM_BAR_WINDOW_LIMIT)
    except Exception as error:
        observed["error"] = {"type": type(error).__name__, "message": bounded_text(str(error), 1200)}
    finally:
        observed["timing"]["end"] = time.monotonic()
    # Keep every partial result and all later original checkpoints. Final acceptance
    # rejects an incomplete query; this observation cannot rescue a product failure.
    retained = observed["command"].get("stdout_tail", "")
    path = output / (name + ".window.txt")
    path.write_text(retained, encoding="utf-8")
    observed["retained_text_file"] = path.name
    observed["retained_text_sha256"] = hashlib.sha256(retained.encode("utf-8")).hexdigest()

    # This separate read observes SystemUI's tint decision after the original PNG
    # and WindowManager query. It is not an atomic reconstruction of either read.
    light_observations = state.setdefault("light_bar_observations", {})
    require(name not in light_observations, "Duplicate SystemUI light-bar checkpoint")
    light = {"phase": "after_original_png_and_window_observation", "command": {},
             "png_sha256": observed["png_sha256"], "xml_sha256": observed["xml_sha256"]}
    light_observations[name] = light
    light["timing"] = {"clock": "host_monotonic", "start": time.monotonic()}
    try:
        observe_interactive_command(device, SYSTEM_BAR_LIGHTBAR_QUERY, light["command"],
                                    timeout=5, retained_limit=SYSTEM_BAR_WINDOW_LIMIT)
    except Exception as error:
        light["error"] = {"type": type(error).__name__, "message": bounded_text(str(error), 1200)}
    finally:
        light["timing"]["end"] = time.monotonic()
    retained = light["command"].get("stdout_tail", "")
    path = output / (name + ".lightbar.txt")
    path.write_text(retained, encoding="utf-8")
    light["retained_text_file"] = path.name
    light["retained_text_sha256"] = hashlib.sha256(retained.encode("utf-8")).hexdigest()

    # The original WMS records identify Launcher3's Taskbar as the navigation
    # window owner. Its state is a third sequential read, not the earlier PNG.
    taskbars = state.setdefault("taskbar_observations", {})
    require(name not in taskbars, "Duplicate Launcher Taskbar checkpoint")
    taskbar = {"phase": "after_original_png_window_and_lightbar_observations", "command": {},
               "png_sha256": observed["png_sha256"], "xml_sha256": observed["xml_sha256"],
               "timing": {"clock": "host_monotonic", "start": time.monotonic()}}
    taskbars[name] = taskbar
    try:
        observe_interactive_command(device, SYSTEM_BAR_TASKBAR_QUERY, taskbar["command"],
                                    timeout=5, retained_limit=SYSTEM_BAR_WINDOW_LIMIT)
    except Exception as error:
        taskbar["error"] = {"type": type(error).__name__, "message": bounded_text(str(error), 1200)}
    finally:
        taskbar["timing"]["end"] = time.monotonic()
    retained = taskbar["command"].get("stdout_tail", "")
    path = output / (name + ".taskbar.txt")
    path.write_text(retained, encoding="utf-8")
    taskbar["retained_text_file"] = path.name
    taskbar["retained_text_sha256"] = hashlib.sha256(retained.encode("utf-8")).hexdigest()


def qualify_system_bar_evidence(output: Path, receipt: dict) -> None:
    state = receipt.setdefault("system_bar_evidence", {})
    state["status"] = "unqualified"
    pairs = state["pairs"] = {}
    try:
        require(receipt.get("startup_display", {}).get("profile") == "raster-720x1280-280",
                "System-bar image qualification requires the observed fixed raster profile")
        for reference, modal, text, button in (
                (SYSTEM_BAR_CHECKPOINTS[0], SYSTEM_BAR_CHECKPOINTS[1],
                 "Permanently delete local ID guest-draft", "Delete local draft"),
                (SYSTEM_BAR_CHECKPOINTS[2], SYSTEM_BAR_CHECKPOINTS[3],
                 "No local draft directories found.", "Close")):
            row = pairs[modal] = {"status": "unqualified", "reference_checkpoint": reference}
            try:
                row.update(measure_navigation_pair(
                    (output / (reference + ".png")).read_bytes(), (output / (reference + ".xml")).read_bytes(),
                    (output / (modal + ".png")).read_bytes(), (output / (modal + ".xml")).read_bytes(),
                    text, button))
            except Exception as error:
                row["error"] = {"type": type(error).__name__, "message": bounded_text(str(error), 1200)}
        state["status"] = "passed"
        require(system_bar_evidence_passes(state), "Installed navigation images or window observations did not qualify")
    except Exception as error:
        state["status"] = "failed"
        state["error"] = {"type": type(error).__name__, "message": bounded_text(str(error), 1200)}
        raise


def lightbar_intensity_fields_pass(text: str) -> bool:
    """Admit finite Java float spellings without rounding or interpreting tint."""
    for field in ("mPendingDarkIntensity", "mDarkIntensity", "mNextDarkIntensity"):
        values = re.findall(r"\b" + field + r"=(\S*)", text)
        if len(values) != 1 or len(values[0]) > 32 or re.fullmatch(
                r"[+-]?(?:[0-9]+(?:\.[0-9]*)?|\.[0-9]+)(?:[eE][+-]?[0-9]+)?", values[0]) is None:
            return False
        try:
            value = Decimal(values[0])
            if not value.is_finite() or not 0 <= value <= 1:
                return False
        except InvalidOperation:
            return False
    return True


def system_bar_interval_passes(value: dict, maximum: int) -> bool:
    """These intervals share this host process's monotonic clock, not guest time."""
    return (isinstance(value, dict) and set(value) == {"clock", "start", "end"}
            and value.get("clock") == "host_monotonic"
            and all(type(value.get(key)) is float and math.isfinite(value[key]) for key in ("start", "end"))
            and 0 <= value["start"] <= value["end"] and value["end"] - value["start"] <= maximum)


def taskbar_state_fields_pass(text: str) -> bool:
    """Require the selected Taskbar subsection and raw fields, not a tint verdict."""
    if not isinstance(text, str) or any(marker in text for marker in (
            "DUMP TIMEOUT", "Error dumping service info:",
            "Failure while dumping the service:", "Got a RemoteException while dumping the service")):
        return False
    lines = text.splitlines()

    def indentation(line):
        return len(line) - len(line.lstrip(" \t"))

    def heading(name, start=0, stop=None):
        stop = len(lines) if stop is None else stop
        matches = [index for index in range(start, stop)
                   if re.match(r"^[ \t]*" + re.escape(name) + r":", lines[index])]
        if (len(matches) != 1 or re.fullmatch(
                r"[ \t]*" + re.escape(name) + r":[ \t]*", lines[matches[0]]) is None):
            return None
        index = matches[0]
        return index, indentation(lines[index])

    def nested(child, parent):
        return (child[0] > parent[0] and child[1] > parent[1]
                and not any(line.strip() and indentation(line) <= parent[1]
                            for line in lines[parent[0] + 1:child[0]]))

    services = [(index, line) for index, line in enumerate(lines)
                if re.match(r"^[ \t]*SERVICE\b", line)]
    if (len(services) != 1 or re.fullmatch(
            r"SERVICE com\.android\.launcher3/com\.android\.quickstep\.TouchInteractionService "
            r"\S+ pid=[1-9][0-9]* user=0", services[0][1].strip()) is None):
        return False
    service = services[0][0], indentation(services[0][1])
    names = ("Client", "TaskbarManager", "TaskbarActivityContext",
             "TaskbarControllers", "NavbarButtonsViewController", "AssistStateManager")
    selected = [heading(name) for name in names]
    if any(item is None for item in selected):
        return False
    client, manager, context, controllers, navbar, completion = selected
    if not all(nested(child, parent) for child, parent in (
            (client, service), (manager, client), (context, manager),
            (controllers, context), (navbar, controllers))):
        return False
    number = r"[+-]?(?:[0-9]+(?:\.[0-9]*)?|\.[0-9]+)(?:[eE][+-]?[0-9]+)?"

    def positive(values):
        try:
            return all(len(value) <= 32 and re.fullmatch(number, value) is not None
                       and Decimal(value).is_finite() and Decimal(value) > 0 for value in values)
        except InvalidOperation:
            return False

    # TouchInteractionService prints this peer only after TaskbarManager returns.
    # Later service output still follows: this is not a whole-service footer.
    if completion[0] <= navbar[0] or completion[1] != manager[1]:
        return False
    # An overview activity may have an earlier DeviceProfile. Only the one after
    # this context's controllers, at this context's own prefix, is relevant.
    profile = heading("DeviceProfile", context[0] + 1, completion[0])
    if profile is None or profile[0] <= navbar[0] or profile[1] != context[1]:
        return False

    # TransferPipe can omit its wrapper prefix after a read starts with LF.
    # Only complete optional measurement data in this own profile may use the
    # observed two-tab client prefix. Raw text and authoritative depths stay exact.
    authoritative_names = {
        "widthPx", "heightPx", "availableWidthPx", "availableHeightPx", "1 dp",
        "isTablet", "isPhone", "transposeLayoutWithOrientation", "isGestureMode",
        "isLandscape", "isMultiWindowMode", "isTwoPanels", "isLeftRightSplit",
        "isTaskbarPresent", "isTaskbarPresentInApps", "mNavMode", "mImeDrawsImeNavBar",
        "mIsUserSetupComplete", "mBindInProgress", "mWindowLayoutParams.height", "mState",
        "mTaskbarNavButtonDarkIntensity", "mSlideInViewVisibleNavButtonColorOverride",
        "mOnTaskbarBackgroundNavButtonColorOverride", "mOnBackgroundNavButtonColorOverrideMultiplier",
    }

    def finite_measurements(values):
        try:
            return all(len(value) <= 32 and re.fullmatch(number, value) is not None
                       and Decimal(value).is_finite() for value in values)
        except InvalidOperation:
            return False

    measurement_names = {}
    for index in range(profile[0] + 1, completion[0]):
        name = re.match(r"^[ \t]*([a-z][A-Za-z0-9.]{0,63}):", lines[index])
        if name is not None:
            measurement_names[name[1]] = measurement_names.get(name[1], 0) + 1
    scalar_indices = set()
    for index in range(profile[0] + 1, completion[0]):
        match = re.fullmatch(r"\t\t([a-z][A-Za-z0-9.]{0,63}): (\S+)px \((\S+)dp\)", lines[index])
        if (match is not None and match[1] not in authoritative_names
                and re.fullmatch(r"[a-z][A-Za-z0-9]*(?:\.[a-z][A-Za-z0-9]*)*", match[1]) is not None
                and measurement_names[match[1]] == 1 and finite_measurements(match.groups()[1:])):
            scalar_indices.add(index)

    # Only complete optional Insets records in this context's own controller
    # may use the retained four-tab form without the four-space wrapper.
    # This observes the raw prefix; it does not identify the writer or transport.
    inset_indices = set()
    inset_headings = [index for index in range(controllers[0] + 1, profile[0])
                      if re.match(r"^[ \t]*TaskbarInsetsController:", lines[index])]
    if inset_headings:
        insets = heading("TaskbarInsetsController", controllers[0] + 1, profile[0])
        if (insets is None or insets[0] <= navbar[0] or insets[1] != navbar[1]
                or not nested(insets, controllers)):
            return False
        end = next((index for index in range(insets[0] + 1, profile[0])
                    if lines[index].strip() and indentation(lines[index]) <= insets[1]
                    and re.match(r"^[ \t]*providedInsets:", lines[index]) is None), profile[0])
        integer = r"(0|-?[1-9][0-9]{0,9})"
        size = (r"Insets\{left=" + integer + r", top=" + integer
                + r", right=" + integer + r", bottom=" + integer + r"\}")
        override = (r" insetsSizeOverrides=\{TypedInsetsSize: \{windowType=INPUT_METHOD, insetsSize="
                    + size + r"\}, TypedInsetsSize: \{windowType=VOICE_INTERACTION, insetsSize="
                    + size + r"\}\}\)")
        record = (r"[ \t]*providedInsets: \(type="
                  r"(navigationBars|tappableElement|mandatorySystemGestures|systemGestures)"
                  r" insetsSize=" + size + r"(?:" + override + r")?")
        seen = Counter()
        limits = {"navigationBars": 1, "tappableElement": 1,
                  "mandatorySystemGestures": 1, "systemGestures": 2}
        for index in range(insets[0] + 1, end):
            line = lines[index]
            if re.match(r"^[ \t]*providedInsets:", line) is None:
                continue
            match = re.fullmatch(record, line)
            if (match is None
                    or (match[1] == "systemGestures") == (" insetsSizeOverrides=" in line)
                    or any(not -(2 ** 31) <= int(value) < 2 ** 31
                           for value in match.groups()[1:] if value is not None)):
                return False
            seen[match[1]] += 1
            if seen[match[1]] > limits[match[1]]:
                return False
            if line.startswith("\t\t\t\tprovidedInsets:"):
                inset_indices.add(index)
            elif indentation(line) <= insets[1]:
                return False

    # The current d5 originals also retain an optional Voice controller Boolean
    # with four TABs and no wrapper spaces. Admit only this own controller's
    # complete three-Boolean group; none supplies an authoritative state field.
    voice_indices = set()
    voice_names = ("isSeparateBackgroundEnabled", "isVoiceInteractionWindowVisible",
                   "isSeparateTaskbarBackgroundAttachedToWindow")
    voice_prefix = r"^[ \t]*(?:" + "|".join(voice_names) + r")="
    voice_headings = [index for index in range(controllers[0] + 1, profile[0])
                      if re.match(r"^[ \t]*VoiceInteractionWindowController:", lines[index])]
    if voice_headings:
        voice = heading("VoiceInteractionWindowController", controllers[0] + 1, profile[0])
        if (voice is None or voice[0] <= navbar[0] or voice[1] != navbar[1]
                or any(lines[index].strip() and indentation(lines[index]) <= controllers[1]
                       for index in range(controllers[0] + 1, voice[0])
                       if index not in inset_indices)):
            return False
        # A following controller may itself contain an extra space in its
        # printed prefix. Any next heading ends this optional group.
        end = next((index for index in range(voice[0] + 1, profile[0])
                    if re.fullmatch(r"[ \t]*[A-Z][A-Za-z0-9]*:[ \t]*", lines[index])
                    or (lines[index].strip() and indentation(lines[index]) <= voice[1]
                        and re.match(voice_prefix, lines[index]) is None)), profile[0])
        seen = Counter()
        for index in range(voice[0] + 1, end):
            line = lines[index]
            if re.match(voice_prefix, line) is None:
                continue
            match = re.fullmatch(r"[ \t]*(" + "|".join(voice_names) + r")=(true|false)", line)
            if match is None:
                return False
            seen[match[1]] += 1
            if seen[match[1]] != 1:
                return False
            if line.startswith("\t\t\t\t" + match[1] + "="):
                voice_indices.add(index)
            elif indentation(line) != voice[1] + 1:
                return False
        if voice_indices and set(seen) != set(voice_names):
            return False

    if (any(lines[index].strip() and indentation(lines[index]) <= manager[1]
            for index in range(manager[0] + 1, completion[0])
            if index not in scalar_indices and index not in inset_indices and index not in voice_indices)
            or any(lines[index].strip() and indentation(lines[index]) <= context[1]
                   for index in range(context[0] + 1, profile[0])
                   if index not in inset_indices and index not in voice_indices)
            or any(lines[index].strip() and indentation(lines[index]) <= profile[1]
                   for index in range(profile[0] + 1, completion[0]) if index not in scalar_indices)):
        return False

    def field(section, name, separator, pattern, depth):
        prefix = re.escape(name + separator)
        matches = [line for line in section if re.match(r"^[ \t]*" + prefix, line)]
        if len(matches) != 1 or indentation(matches[0]) != depth:
            return None
        return re.fullmatch(r"[ \t]*" + prefix + pattern + r"[ \t]*", matches[0])

    context_fields = lines[context[0] + 1:controllers[0]]
    depth = controllers[1]
    if field(context_fields, "mNavMode", "=", r"([A-Z][A-Z0-9_]{0,63})", depth) is None:
        return False
    for name in ("mImeDrawsImeNavBar", "mIsUserSetupComplete", "mBindInProgress"):
        if field(context_fields, name, "=", r"(true|false)", depth) is None:
            return False
    height = field(context_fields, "mWindowLayoutParams.height", "=",
                   r"([+-]?[0-9]{1,10})px", depth)
    if height is None or not -(2 ** 31) <= int(height[1]) < 2 ** 31:
        return False

    profile_fields = lines[profile[0] + 1:completion[0]]
    density = field(profile_fields, "1 dp", " = ", r"(\S+) px", depth)
    if density is None or not positive((density[1],)):
        return False
    for name in ("widthPx", "heightPx", "availableWidthPx", "availableHeightPx"):
        dimensions = field(profile_fields, name, ": ", r"(\S+)px \((\S+)dp\)", depth)
        if dimensions is None or not positive(dimensions.groups()):
            return False
    for name in ("isTablet", "isPhone", "transposeLayoutWithOrientation", "isGestureMode",
                 "isLandscape", "isMultiWindowMode", "isTwoPanels", "isLeftRightSplit",
                 "isTaskbarPresent", "isTaskbarPresentInApps"):
        if field(profile_fields, name, ":", r"(true|false)", depth) is None:
            return False

    body = []
    for line in lines[navbar[0] + 1:]:
        if line.strip() and indentation(line) <= navbar[1]:
            break
        body.append(line)
    controller = "\n".join(body)
    if len(re.findall(r"^[ \t]*mState=[^\r\n]*$", controller, re.M)) != 1:
        return False
    for name in ("mTaskbarNavButtonDarkIntensity", "mSlideInViewVisibleNavButtonColorOverride",
                 "mOnTaskbarBackgroundNavButtonColorOverride", "mOnBackgroundNavButtonColorOverrideMultiplier"):
        values = re.findall(r"\b" + name + r"=(\S*)", controller)
        if (len(values) != 1 or len(re.findall(r"^[ \t]*" + name + r"=\S*[ \t]*$", controller, re.M)) != 1):
            return False
        if len(values[0]) > 32 or re.fullmatch(number, values[0]) is None:
            return False
        try:
            value = Decimal(values[0])
            if not value.is_finite() or not 0 <= value <= 1:
                return False
        except InvalidOperation:
            return False
    return True


def system_bar_evidence_passes(state: dict) -> bool:
    if not (isinstance(state, dict) and state.get("scope") == "fixed-rig-navigation-contrast"
            and state.get("profile") == "raster-720x1280-280" and state.get("serial") == SERIAL
            and state.get("status") == "passed" and "error" not in state
            and isinstance(state.get("window_observations"), dict)
            and set(state["window_observations"]) == set(SYSTEM_BAR_CHECKPOINTS)
            and isinstance(state.get("light_bar_observations"), dict)
            and set(state["light_bar_observations"]) == set(SYSTEM_BAR_CHECKPOINTS)
            and isinstance(state.get("taskbar_observations"), dict)
            and set(state["taskbar_observations"]) == set(SYSTEM_BAR_CHECKPOINTS)
            and isinstance(state.get("pairs"), dict)
            and set(state["pairs"]) == {SYSTEM_BAR_CHECKPOINTS[1], SYSTEM_BAR_CHECKPOINTS[3]}
            and isinstance(state.get("source_commit"), str)
            and re.fullmatch(r"[0-9a-f]{40}", state["source_commit"]) is not None
            and isinstance(state.get("unsigned_apk_sha256"), str)
            and re.fullmatch(r"[0-9a-f]{64}", state["unsigned_apk_sha256"]) is not None):
        return False
    for name, observation in state["window_observations"].items():
        if not (isinstance(observation, dict) and observation.get("phase") == "after_original_png_capture"
                and "error" not in observation and isinstance(observation.get("command"), dict)):
            return False
        capture, window_time = observation.get("png_capture"), observation.get("timing")
        if not (system_bar_interval_passes(capture, 15) and system_bar_interval_passes(window_time, 5)
                and capture["end"] <= window_time["start"]):
            return False
        command = observation["command"]
        if not (interactive_command_passes(command, SYSTEM_BAR_WINDOW_QUERY, SYSTEM_BAR_WINDOW_LIMIT)
                and command.get("timeout_seconds") == 5 and "DUMP TIMEOUT" not in command["stdout_tail"]
                and type(command.get("seconds")) in (int, float) and 0 <= command["seconds"] <= 5
                and "Error dumping service info:" not in command["stdout_tail"]
                and command["stdout_tail"].startswith(("WINDOW MANAGER ", "\nWINDOW MANAGER "))
                and observation.get("retained_text_file") == name + ".window.txt"
                and observation.get("retained_text_sha256")
                == hashlib.sha256(command["stdout_tail"].encode("utf-8")).hexdigest()):
            return False
        light = state["light_bar_observations"][name]
        if not (isinstance(light, dict) and "error" not in light
                and light.get("phase") == "after_original_png_and_window_observation"
                and isinstance(light.get("command"), dict)
                and all(light.get(key) == observation.get(key) for key in ("png_sha256", "xml_sha256"))
                and system_bar_interval_passes(light.get("timing"), 5)
                and window_time["end"] <= light["timing"]["start"]):
            return False
        command = light["command"]
        if not (interactive_command_passes(command, SYSTEM_BAR_LIGHTBAR_QUERY, SYSTEM_BAR_WINDOW_LIMIT)
                and command.get("timeout_seconds") == 5
                and type(command.get("seconds")) in (int, float) and 0 <= command["seconds"] <= 5
                and "DUMP TIMEOUT" not in command["stdout_tail"]
                and "Error dumping service info:" not in command["stdout_tail"]
                and light.get("retained_text_file") == name + ".lightbar.txt"
                and light.get("retained_text_sha256")
                == hashlib.sha256(command["stdout_tail"].encode("utf-8")).hexdigest()):
            return False
        # Require the named target and its raw state to be present. No missing
        # field is defaulted to zero/false or interpreted as a pixel/tint result.
        text = command["stdout_tail"]
        if not (re.search(r"^\s*SERVICE com\.android\.systemui/\.SystemUIService\b", text, re.M)
                and re.search(r"^\s*LightBarController:\s*$", text, re.M)
                and re.search(r"^\s*mAppearance=.*$", text, re.M)
                and all(re.search(r"^\s*" + field + r"=(true|false)\s*$", text, re.M)
                        for field in ("mNavigationLight", "mHasLightNavigationBar", "mForceDarkForScrim",
                                      "mForceLightForScrim", "mNavbarColorManagedByIme"))
                and len(re.findall(r"^\s*NavigationBarTransitionsController:\s*$", text, re.M)) == 1
                and re.search(r"^\s*Dump took [0-9]+ms\s*$", text, re.M)):
            return False
        navigation = re.split(r"^\s*NavigationBarTransitionsController:\s*$", text, flags=re.M)[1]
        if not (all(re.search(r"\b" + field + r"=(true|false)\b", navigation)
                    for field in ("mTransitionDeferring", "mTransitionPending", "mTintChangePending"))
                and lightbar_intensity_fields_pass(navigation)):
            return False
        taskbar = state["taskbar_observations"][name]
        if not (isinstance(taskbar, dict) and "error" not in taskbar
                and taskbar.get("phase") == "after_original_png_window_and_lightbar_observations"
                and isinstance(taskbar.get("command"), dict)
                and all(taskbar.get(key) == observation.get(key) for key in ("png_sha256", "xml_sha256"))
                and system_bar_interval_passes(taskbar.get("timing"), 5)
                and light["timing"]["end"] <= taskbar["timing"]["start"]):
            return False
        command = taskbar["command"]
        if not (interactive_command_passes(command, SYSTEM_BAR_TASKBAR_QUERY, SYSTEM_BAR_WINDOW_LIMIT)
                and command.get("timeout_seconds") == 5
                and type(command.get("seconds")) in (int, float) and 0 <= command["seconds"] <= 5
                and "DUMP TIMEOUT" not in command["stdout_tail"]
                and "Error dumping service info:" not in command["stdout_tail"]
                and taskbar.get("retained_text_file") == name + ".taskbar.txt"
                and taskbar.get("retained_text_sha256")
                == hashlib.sha256(command["stdout_tail"].encode("utf-8")).hexdigest()
                and taskbar_state_fields_pass(command["stdout_tail"])):
            return False
    for modal, row in state["pairs"].items():
        reference = SYSTEM_BAR_CHECKPOINTS[SYSTEM_BAR_CHECKPOINTS.index(modal) - 1]
        if not (isinstance(row, dict) and row.get("status") == "passed" and "error" not in row
                and row.get("reference_checkpoint") == reference
                and row.get("minimum_contrast") == SYSTEM_BAR_MINIMUM_CONTRAST
                and type(row.get("reference_contrast")) in (int, float)
                and row["reference_contrast"] >= SYSTEM_BAR_MINIMUM_CONTRAST
                and isinstance(row.get("glyphs"), list) and len(row["glyphs"]) == 3):
            return False
        colors = [row.get(key) for key in ("reference_background_rgb", "reference_glyph_rgb", "modal_background_rgb")]
        colors += [glyph.get("modal_glyph_rgb") if isinstance(glyph, dict) else None for glyph in row["glyphs"]]
        if not all(isinstance(color, list) and len(color) == 3
                   and all(type(value) is int and 0 <= value <= 255 for value in color) for color in colors):
            return False
        if (row["reference_contrast"] != navigation_contrast(colors[0], colors[1])
                or len({tuple(color) for color in colors[3:]}) != 1):
            return False
        for name, identity in ((reference, row.get("reference")), (modal, row.get("modal"))):
            if not (isinstance(identity, dict) and identity.get("dimensions") == [720, 1280]
                    and identity.get("bounds") == [0, 1196, 720, 1280]
                    and all(type(value) is int for value in identity["dimensions"] + identity["bounds"])
                    and all(isinstance(identity.get(key), str)
                            and re.fullmatch(r"[0-9a-f]{64}", identity[key]) is not None
                            and identity[key] == state["window_observations"][name].get(key)
                            for key in ("png_sha256", "xml_sha256"))
                    and type(identity.get("png_bytes")) is int and 8 < identity["png_bytes"] <= SYSTEM_BAR_PNG_LIMIT
                    and type(identity.get("xml_bytes")) is int and 0 < identity["xml_bytes"] <= 262144):
                return False
        for position, glyph in enumerate(row["glyphs"]):
            if not (isinstance(glyph, dict) and type(glyph.get("contrast")) in (int, float)
                    and glyph["contrast"] == navigation_contrast(glyph["modal_glyph_rgb"], colors[2])
                    and glyph["contrast"] >= SYSTEM_BAR_MINIMUM_CONTRAST
                    and type(glyph.get("reference_solid_pixels")) is int
                    and 250 <= glyph["reference_solid_pixels"] <= 900
                    and type(glyph.get("matching_modal_pixels")) is int
                    and 0 <= glyph["matching_modal_pixels"] <= glyph["reference_solid_pixels"]
                    and glyph["matching_modal_pixels"] * 100 >= glyph["reference_solid_pixels"] * 95
                    and isinstance(glyph.get("reference_bounds"), list) and len(glyph["reference_bounds"]) == 4
                    and all(type(value) is int for value in glyph["reference_bounds"])):
                return False
            left, top, right, bottom = glyph["reference_bounds"]
            if not (position * 240 + 16 <= left < right <= (position + 1) * 240 - 16
                    and 1208 <= top < bottom <= 1268 and 12 <= right - left <= 48
                    and 12 <= bottom - top <= 48 and glyph["reference_solid_pixels"] <= (right - left) * (bottom - top)):
                return False
    return True


class Ui:
    def __init__(self, device: Device, output: Path, receipt: dict):
        self.device, self.output = device, output
        self.receipt = receipt
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

    def scroll(self, root: ET.Element, down: bool, *, observation: dict | None = None) -> bool:
        node = single_match(root, lambda item: item.get("class") == "android.widget.ScrollView")
        if node is None:
            return False
        left, top, right, bottom = bounds(node)
        original_bounds = [left, top, right, bottom]
        for bar_left, bar_top, bar_right, bar_bottom in system_bar_bounds(root):
            if bar_right <= left or bar_left >= right or bar_bottom <= top or bar_top >= bottom:
                continue
            if bar_left <= left and bar_right >= right and bar_top <= top:
                top = min(bottom, bar_bottom)
            elif bar_left <= left and bar_right >= right and bar_bottom >= bottom:
                bottom = max(top, bar_top)
            elif bar_top <= top and bar_bottom >= bottom and bar_left <= left:
                left = min(right, bar_right)
            elif bar_top <= top and bar_bottom >= bottom and bar_right >= right:
                right = max(left, bar_left)
            else:
                raise SmokeFailure("System bar does not define an unambiguous scroll edge")
        require(left < right and top < bottom, "System bars cover the entire scroll viewport")
        x = max(left, right - 12)
        y = (top + bottom) // 2
        units = -UI_SCROLL_WHEEL_UNITS if down else UI_SCROLL_WHEEL_UNITS
        observation = observation if observation is not None else {}
        observation.update(scroll_view_bounds=original_bounds,
                           unobscured_scroll_bounds=[left, top, right, bottom])
        # Android 15 dispatches mouse VSCROLL as one synchronous ACTION_SCROLL.
        # Touch swipe's moving ACTION_UP can instead fling past an unseen control.
        result = observe_ui_command(
            self.device, ("shell", "input", "mouse", "scroll", str(x), str(y),
                          "--axis", "VSCROLL," + str(units)),
            observation, timeout=30)
        require(type(result.returncode) is int and result.returncode == 0
                and isinstance(result.stdout, str) and not result.stdout.strip()
                and isinstance(result.stderr, str) and not result.stderr.strip(),
                "Owned UI wheel command did not complete cleanly")
        return True

    def seek(self, selector, description: str, scroll=True) -> ET.Element:
        navigation = self.observations.setdefault("navigation", {"attempted": 0, "recent_searches": []})
        navigation["attempted"] += 1
        attempt = {"sequence": navigation["attempted"], "description": bounded_text(description, 120),
                   "scroll": scroll, "status": "searching", "snapshots": 0, "wheel_events": 0,
                   "wheel_units": UI_SCROLL_WHEEL_UNITS,
                   "wheel_step_limit_per_direction": UI_SCROLL_MAX_STEPS, "recent_frames": []}
        navigation["last_search"] = attempt
        navigation["recent_searches"].append(attempt)
        del navigation["recent_searches"][:-8]
        started = time.monotonic()
        try:
            directions = ((False, UI_SCROLL_MAX_STEPS), (True, UI_SCROLL_MAX_STEPS)) if scroll else ((False, 7),)
            for direction, limit in directions:
                previous = None
                # Inspect the initial viewport and every resulting viewport,
                # including the one after the final permitted wheel event.
                for step in range(limit + 1):
                    root = self.snapshot()
                    current = ET.tostring(root)
                    texts = [item.get("text") for item in root.iter("node") if visible(item) and item.get("text")]
                    frame = {"direction": ("down" if direction else "up") if scroll else "wait",
                             "step": step, "snapshot_sequence": self.observations["attempted"],
                             "parsed_xml_sha256": hashlib.sha256(current).hexdigest(),
                             "visible_label_count": len(texts), "visible_labels": [text[:120] for text in texts[:24]],
                             "visible_labels_truncated": len(texts) > 24 or any(len(text) > 120 for text in texts[:24])}
                    attempt["snapshots"] += 1
                    attempt["recent_frames"].append(frame)
                    del attempt["recent_frames"][:-16]
                    bars = system_bar_bounds(root)
                    frame["system_bars"] = [list(box) for box in bars]
                    match = selector(root)
                    if match is not None:
                        left, top, right, bottom = bounds(match)
                        x, y = (left + right) // 2, (top + bottom) // 2
                        occluded = any(a <= x < c and b <= y < d for a, b, c, d in bars)
                        frame.update(matched_center=[x, y], matched_center_in_system_bar=occluded)
                        if not occluded:
                            frame["result"] = "found"
                            attempt["status"] = "found"
                            return match
                    if not scroll:
                        frame["result"] = "waiting"
                        time.sleep(0.25)
                        continue
                    if current == previous:
                        frame["result"] = "unchanged"
                        break
                    if step == limit:
                        frame["result"] = "step_limit"
                        break
                    if not self.scroll(root, direction, observation=frame.setdefault("wheel_command", {})):
                        frame["result"] = "no_scroll_container"
                        break
                    attempt["wheel_events"] += 1
                    frame["result"] = "scrolled"
                    previous = current
            raise SmokeFailure("UI control not found: " + description)
        except Exception as error:
            attempt.update(status="failed", error={"type": type(error).__name__, "message": bounded_text(str(error), 1200)})
            navigation.setdefault("first_failed_search", attempt)
            raise
        finally:
            attempt["seconds"] = round(time.monotonic() - started, 3)

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
        capture = None
        if name in SYSTEM_BAR_CHECKPOINTS:
            captures = self.receipt.setdefault("system_bar_png_captures", {})
            require(name not in captures, "Duplicate original system-bar PNG")
            capture = captures[name] = {"clock": "host_monotonic", "start": time.monotonic()}
        try:
            result = subprocess.run(
                [str(self.device.adb_path), "-s", SERIAL, "exec-out", "screencap", "-p"],
                env=self.device.env, capture_output=True, timeout=15, check=True,
            )
        finally:
            if capture is not None:
                capture["end"] = time.monotonic()
        require(result.stdout.startswith(b"\x89PNG\r\n\x1a\n"), "Screenshot is not PNG")
        (self.output / (name + ".png")).write_bytes(result.stdout)
        if name in SYSTEM_BAR_CHECKPOINTS:
            retain_system_bar_window(self.device, self.output, self.receipt, name)

    def rotate(self, rotation: int):
        require(type(rotation) is int and rotation in (0, 1, 2, 3), "Invalid requested UI rotation")
        rotations = self.observations.setdefault("rotations", {"attempted": 0, "recent_requests": []})
        rotations["attempted"] += 1
        attempt = {"sequence": rotations["attempted"], "requested_rotation": rotation,
                   "timeout_seconds": UI_ROTATION_TIMEOUT_SECONDS, "status": "waiting",
                   "state_probes": 0, "snapshots": 0, "recent_states": [], "lock_command": {}}
        rotations["last_request"] = attempt
        rotations["recent_requests"].append(attempt)
        del rotations["recent_requests"][:-8]
        started = time.monotonic()
        deadline = started + UI_ROTATION_TIMEOUT_SECONDS

        def observe_state(phase: str) -> dict:
            attempt["state_probes"] += 1
            probe = {"sequence": attempt["state_probes"], "phase": phase, "command": {}}
            attempt.setdefault("first_state", probe)
            attempt["last_state"] = probe
            if phase == "after_capture":
                attempt["last_post_capture_state"] = probe
            try:
                observe_interactive_command(self.device, ROTATION_STATE_QUERY, probe["command"],
                                            timeout=command_budget(deadline, 5),
                                            retained_limit=UI_ROTATION_STATE_TEXT_LIMIT)
                require(interactive_command_passes(probe["command"], ROTATION_STATE_QUERY,
                                                   UI_ROTATION_STATE_TEXT_LIMIT),
                        "Rotation state query did not complete cleanly")
                state = default_display_rotation_state(probe["command"]["stdout_tail"])
                require(state is not None, "Missing or ambiguous default-display rotation state")
                probe["state"] = state
                require(time.monotonic() < deadline, "Rotation state query completed after its deadline")
                return state
            except Exception as error:
                probe["error"] = {"type": type(error).__name__, "message": bounded_text(str(error), 1200)}
                raise
            finally:
                observed = probe["command"]
                attempt["recent_states"].append({
                    "sequence": probe["sequence"], "phase": phase, "state": probe.get("state"),
                    "error": probe.get("error"), "exit_code": observed.get("exit_code"),
                    "seconds": observed.get("seconds"),
                    "stdout_text_sha256": observed.get("stdout", {}).get("text_sha256"),
                    "stdout_text_characters": observed.get("stdout", {}).get("text_characters")})
                del attempt["recent_states"][:-16]

        expected = {"display_id": 0, "current_rotation": rotation, "deferred_pause_count": 0,
                    "user_rotation_mode": "USER_ROTATION_LOCKED", "user_rotation": rotation}
        try:
            # A stored angle does not select locked mode. Also wait for the
            # current rotation before UiAutomation can save/restore that state.
            locked = observe_ui_command(
                self.device, ("shell", "wm", "user-rotation", "-d", "0", "lock", str(rotation)),
                attempt["lock_command"], timeout=command_budget(deadline, 5))
            require(type(locked.returncode) is int and locked.returncode == 0
                    and isinstance(locked.stdout, str) and not locked.stdout.strip()
                    and isinstance(locked.stderr, str) and not locked.stderr.strip(),
                    "Owned default-display rotation lock did not complete cleanly")
            while time.monotonic() < deadline:
                if observe_state("before_capture") == expected:
                    root = self.snapshot(deadline=deadline)
                    attempt["snapshots"] += 1
                    attempt["last_snapshot"] = {
                        "sequence": self.observations["attempted"], "rotation": root.get("rotation"),
                        "parsed_xml_sha256": hashlib.sha256(ET.tostring(root)).hexdigest(),
                        "visible_owned_nodes": sum(visible(node) for node in root.iter("node"))}
                    if (root.get("rotation") == str(rotation)
                            and attempt["last_snapshot"]["visible_owned_nodes"] > 0
                            and observe_state("after_capture") == expected
                            and time.monotonic() < deadline):
                        attempt["status"] = "passed"
                        return
                remaining = deadline - time.monotonic()
                if remaining > 0:
                    time.sleep(min(0.25, remaining))
            raise SmokeFailure("Emulator did not reach the requested rotation")
        except Exception as error:
            attempt.update(status="failed", error={"type": type(error).__name__,
                                                   "message": bounded_text(str(error), 1200)})
            rotations.setdefault("first_failed_request", attempt)
            raise
        finally:
            attempt["seconds"] = round(time.monotonic() - started, 3)


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
            row["save_trigger"] = "automatic-valid-form"
            ui.enter(TITLE_LABEL, "SyntheticEmulatorDraft")
            ui.enter(OBJECTIVE_LABEL, "SyntheticObjective")
            ui.enter(CONTEXT_LABEL, "SyntheticContext")
            ui.expect_fields("SyntheticEmulatorDraft", "SyntheticObjective", "SyntheticContext")
            # Accepted main saves the complete valid form after its ordinary pause.
            # Require the actual acknowledgment and cold persistence, without racing
            # a manual action against that product-owned deadline.
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
        with case(receipt, CASE_IDS[2]) as row:
            row["pending_input_constraint"] = "invalid-workload-requests"
            ui.enter(REQUESTS_LABEL, PENDING_REQUESTS)
            require(ui.editor(REQUESTS_LABEL).get("text") == PENDING_REQUESTS,
                    "The complete invalid workload input was not retained")
            ui.text(INVALID_WORKLOAD)
            ui.expect_button("Save locally", False)
            ui.enter(TITLE_LABEL, "_pending")
            ui.enter(OBJECTIVE_LABEL, "_pending")
            ui.expect_fields("SyntheticEmulatorDraft_pending", "SyntheticObjective_pending", "SyntheticContext")
            ui.rotate(1)
            ui.text(UNSAVED_FORM)
            ui.expect_fields("SyntheticEmulatorDraft_pending", "SyntheticObjective_pending", "SyntheticContext")
            require(ui.editor(REQUESTS_LABEL).get("text") == PENDING_REQUESTS,
                    "Landscape rotation lost the complete invalid workload input")
            ui.text(INVALID_WORKLOAD)
            ui.expect_button("Save locally", False)
            ui.text(UNSAVED_FORM)
            ui.checkpoint("04-pending-landscape")
            ui.rotate(0)
            ui.text(UNSAVED_FORM)
            ui.expect_fields("SyntheticEmulatorDraft_pending", "SyntheticObjective_pending", "SyntheticContext")
            require(ui.editor(REQUESTS_LABEL).get("text") == PENDING_REQUESTS,
                    "Portrait rotation lost the complete invalid workload input")
            ui.text(INVALID_WORKLOAD)
            ui.expect_button("Save locally", False)
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
            require(ui.editor(REQUESTS_LABEL).get("text") == PENDING_REQUESTS,
                    "Deletion replaced the retained invalid workload input")
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
        display_profile = display_profile_setting(settings["mode"])
        if settings["mode"] in ("hvf", "kvm"):
            expected_hash = os.environ.get("PYAI_EXPECTED_APK_SHA256")
            require_apk_handoff(original_hash, expected_hash)
            receipt["expected_unsigned_apk_sha256"] = expected_hash
        if settings["mode"] == "software":
            receipt["limitations"].append("software CPU emulation is a vendor-unsupported diagnostic mode")
        sdk = Path(os.environ["ANDROID_HOME"])
        adb = sdk / "platform-tools/adb"
        emulator = (Path(os.environ["PYAI_EMULATOR_BINARY"]) if settings["mode"] == "kvm"
                    else sdk / "emulator/emulator")
        if settings["mode"] == "kvm":
            require(emulator.is_absolute() and emulator.is_file() and not emulator.is_symlink(),
                    "Expected the ordinary dedicated Linux emulator before running any tool query")
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
            require(kvm_emulator_version_passes(receipt["emulator_version"]),
                    "The dedicated Linux emulator differs from the pinned 37.1.11 build15917651")
            acceleration = {}
            receipt["host_acceleration"] = acceleration
            check_kvm_acceleration(emulator, acceleration)
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
            configure_startup_display(Path(env["ANDROID_AVD_HOME"]), avd_name, display_profile, receipt)
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
                    receipt["device"]["online_cpus"] = device.shell("cat", "/sys/devices/system/cpu/online")
                    observe_startup_display(device, Path(env["ANDROID_AVD_HOME"]), receipt)
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
        qualify_system_bar_evidence(output, receipt)
        receipt["status"] = "passed"
        complete_receipt(receipt, source, expected_mode=settings["mode"], expected_display_profile=display_profile,
                         expected_system_bar_evidence=True)
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
