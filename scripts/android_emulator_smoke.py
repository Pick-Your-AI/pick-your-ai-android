#!/usr/bin/env python3
"""Exercise the synthetic APK only in a fresh AVD owned by this hosted CI job."""

from __future__ import annotations

import contextlib
import hashlib
import json
import os
from pathlib import Path
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


def emulation_settings() -> dict:
    """Select one explicit mode; an unavailable mode never becomes a skipped pass."""
    mode = os.environ.get("PYAI_EMULATOR_MODE", "kvm")
    require(mode in ("kvm", "software"), "Unknown emulator mode; expected kvm or software")
    if mode == "kvm":
        check_kvm_access()
    settings = {"mode": mode, "accel_argument": "off" if mode == "software" else "on",
                "boot_timeout_seconds": 900 if mode == "software" else 300,
                "unsupported_software_opt_in": mode == "software"}
    print("Emulation settings: " + json.dumps(settings, sort_keys=True))
    return settings


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
    """Read-only readiness observations of the already verified, owned API-35 guest.

    Boot completion alone does not demonstrate unlocked-user or UI-dump directory
    access. These observations do not certify performance or successful app launch.
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


def complete_receipt(receipt: dict, source_sha: str) -> None:
    require(receipt.get("source_commit") == source_sha, "Receipt source commit differs")
    require(receipt.get("scope") == "owned-installed-aosp-emulator", "Wrong evidence scope")
    require(receipt.get("status") == "passed", "Installed smoke did not pass")
    cases = receipt.get("cases", [])
    require([case.get("id") for case in cases] == list(CASE_IDS), "Missing or duplicate smoke case")
    require(all(case.get("status") == "passed" for case in cases), "A smoke case did not execute successfully")
    require(receipt.get("unsigned_apk_unchanged") is True, "Unsigned build preservation was not verified")
    readiness = receipt.get("guest_readiness", {})
    require(isinstance(readiness, dict) and readiness.get("ready") is True and
            readiness.get("ui_dump_path") == UI_DUMP_PATH and
            guest_readiness_checks_pass(readiness.get("last_checks")),
            "Complete owned-guest readiness evidence is missing")


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


class Ui:
    def __init__(self, device: Device, output: Path, receipt: dict):
        self.device, self.output = device, output
        self.dumps = receipt.setdefault("ui_hierarchy_dumps", {"attempted": 0, "completed": 0, "exit_137": 0})

    def snapshot(self) -> ET.Element:
        path = UI_DUMP_PATH
        self.device.shell("rm", "-f", path)
        self.dumps["attempted"] += 1
        result = self.device.adb("shell", "uiautomator", "dump", path, timeout=20, check=False)
        # This pinned image has returned 137 after reporting the completed dump.
        # Do not infer its cause or accept an exit alone: a prior dump was removed,
        # the exact completion message is required, and fresh XML is read below.
        completed_with_137 = (result.returncode == 137 and not result.stderr.strip() and
                              result.stdout.strip() == "UI hierchary dumped to: " + path)
        require(result.returncode == 0 or completed_with_137,
                f"UI hierarchy dump failed ({result.returncode}): " +
                (result.stdout + result.stderr)[-6000:])
        raw = self.device.adb("exec-out", "cat", path).stdout
        root = ET.fromstring(raw)
        require(root.tag == "hierarchy" and root.get("rotation") in ("0", "1", "2", "3") and
                any(True for _ in root.iter("node")), "Invalid or empty UI hierarchy")
        self.dumps["completed"] += 1
        if completed_with_137:
            self.dumps["exit_137"] += 1
        return root

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
        settings = emulation_settings()
        receipt["emulation"] = settings
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
        receipt["libpulse0_version"] = command(["dpkg-query", "-W", "-f=${Version}", "libpulse0"]).stdout.strip()
        receipt["emulator_version"] = command([str(emulator), "-version"]).stdout.strip()
        receipt["emulator_binary_sha256"] = digest(emulator)
        receipt["adb_version"] = command([str(adb), "version"]).stdout.strip()
        if settings["mode"] == "kvm":
            command([str(emulator), "-accel-check"])
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
            emulator_env = (dict(env, ANDROID_I_WANT_MY_TCG="yes")
                            if settings["mode"] == "software" else env)
            try:
                with emulator_log.open("wb") as log:
                    process = subprocess.Popen(
                        [str(emulator), "-avd", avd_name, "-port", "5554", "-no-window", "-no-audio",
                         "-no-boot-anim", "-no-snapshot", "-no-metrics", "-gpu", "swiftshader_indirect",
                         "-accel", settings["accel_argument"], "-cores", "2", "-memory", "2048",
                         "-camera-front", "none", "-camera-back", "none"],
                        env=emulator_env, stdout=log, stderr=subprocess.STDOUT, start_new_session=True,
                    )
                    device = Device(adb, env, process, avd_name, receipt)
                    boot_timeout = settings["boot_timeout_seconds"]
                    deadline = time.monotonic() + boot_timeout
                    while time.monotonic() < deadline:
                        boot = device.adb("shell", "getprop", "sys.boot_completed", timeout=10, check=False)
                        if boot.returncode == 0 and boot.stdout.strip() == "1" and not boot.stderr.strip():
                            break
                        time.sleep(1)
                    else:
                        raise SmokeFailure(f"Owned emulator did not boot within {boot_timeout} seconds")
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
                    wait_for_guest_ready(device, receipt)
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
        complete_receipt(receipt, source)
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
