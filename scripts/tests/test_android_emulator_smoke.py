"""Host-side guard tests; these do not install or run an Android app."""

import copy
import importlib.util
import io
import os
from pathlib import Path
import stat
import subprocess
import tempfile
import unittest
from unittest.mock import Mock, call, patch
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("emulator_smoke", ROOT / "scripts/android_emulator_smoke.py")
smoke = importlib.util.module_from_spec(spec)
spec.loader.exec_module(smoke)


def node(text="", kind="android.widget.TextView", package=smoke.PACKAGE, box="[0,0][100,100]"):
    return ET.Element("node", text=text, **{"class": kind, "package": package, "bounds": box, "enabled": "true"})


def ready_checks():
    return {name: {"exit_code": 0, "stdout_tail": expected[-1], "stderr_tail": "", "output_truncated": False}
            for name, _, expected in smoke.GUEST_READINESS_COMMANDS}


class ReadinessClock:
    def __init__(self):
        self.now = 0

    def monotonic(self):
        return self.now

    def sleep(self, seconds):
        self.now += seconds


class EmulatorSmokeGuardTest(unittest.TestCase):
    def test_guest_readiness_requires_all_read_only_observations_on_the_verified_target(self):
        receipt, clock = {}, ReadinessClock()
        device = Mock(spec=smoke.Device)
        device.adb.side_effect = [subprocess.CompletedProcess([], 0, expected[-1], "")
                                  for _, _, expected in smoke.GUEST_READINESS_COMMANDS]
        with patch.object(smoke.time, "monotonic", clock.monotonic), patch.object(smoke.time, "sleep", clock.sleep):
            smoke.wait_for_guest_ready(device, receipt)
        device.verify_identity.assert_called_once_with()
        self.assertEqual([call("shell", *args, timeout=10, check=False)
                          for _, args, _ in smoke.GUEST_READINESS_COMMANDS], device.adb.call_args_list)
        self.assertTrue(receipt["guest_readiness"]["ready"])
        self.assertEqual(1, receipt["guest_readiness"]["attempts"])
        self.assertEqual(smoke.UI_DUMP_PATH, receipt["guest_readiness"]["ui_dump_path"])
        self.assertEqual(ready_checks(), receipt["guest_readiness"]["last_checks"])
        self.assertNotIn("first_incomplete_checks", receipt["guest_readiness"])
        device.launch.assert_not_called()
        device.shell.assert_not_called()

    def test_ui_dump_storage_queries_observe_existing_shell_temp_directory(self):
        self.assertEqual("/data/local/tmp/pyai-smoke-window.xml", smoke.UI_DUMP_PATH)
        self.assertEqual([
            ("ui_dump_directory", ("test", "-d", "/data/local/tmp"), ("",)),
            ("ui_dump_readable", ("test", "-r", "/data/local/tmp"), ("",)),
            ("ui_dump_writable", ("test", "-w", "/data/local/tmp"), ("",)),
            ("ui_dump_searchable", ("test", "-x", "/data/local/tmp"), ("",)),
        ], [entry for entry in smoke.GUEST_READINESS_COMMANDS if entry[0].startswith("ui_dump_")])

    def test_boot_property_alone_cannot_accept_locked_user_storage_or_failed_services(self):
        for key, change in (
            ("boot_completed", {"stdout_tail": ""}),
            ("boot_animation", {"stdout_tail": "running"}),
            ("current_user", {"stdout_tail": "10"}),
            ("user_state", {"stdout_tail": "RUNNING_UNLOCKING"}),
            ("user_state", {"stdout_tail": "RUNNING_LOCKED"}),
            ("user_state", {"stdout_tail": "User is not started: 0"}),
            ("package_manager", {"stdout_tail": ""}),
            ("ui_dump_directory", {"exit_code": 1}),
            ("ui_dump_readable", {"exit_code": 1}),
            ("ui_dump_writable", {"exit_code": 1, "stderr_tail": "Permission denied"}),
            ("ui_dump_searchable", {"exit_code": 1}),
            ("user_state", {"stderr_tail": "service failure"}),
            ("user_state", {"exit_code": False}),
            ("user_state", {"output_truncated": True}),
        ):
            with self.subTest(key=key, change=change):
                checks = ready_checks()
                checks[key].update(change)
                self.assertFalse(smoke.guest_readiness_checks_pass(checks))
        self.assertFalse(smoke.guest_readiness_checks_pass(None))
        checks = ready_checks()
        checks.pop("ui_dump_writable")
        self.assertFalse(smoke.guest_readiness_checks_pass(checks))

    def test_guest_readiness_retries_incomplete_state_without_mutating_or_launching(self):
        receipt, clock = {}, ReadinessClock()
        device = Mock(spec=smoke.Device)
        first = [subprocess.CompletedProcess([], 0, expected[-1], "")
                 for _, _, expected in smoke.GUEST_READINESS_COMMANDS]
        state_index = next(index for index, (name, _, _) in enumerate(smoke.GUEST_READINESS_COMMANDS)
                           if name == "user_state")
        first[state_index] = subprocess.CompletedProcess([], 0, "RUNNING_LOCKED", "")
        second = [subprocess.CompletedProcess([], 0, expected[-1], "")
                  for _, _, expected in smoke.GUEST_READINESS_COMMANDS]
        device.adb.side_effect = first + second
        with patch.object(smoke.time, "monotonic", clock.monotonic), patch.object(smoke.time, "sleep", clock.sleep):
            smoke.wait_for_guest_ready(device, receipt)
        state = receipt["guest_readiness"]
        self.assertTrue(state["ready"])
        self.assertEqual(2, state["attempts"])
        self.assertEqual("RUNNING_LOCKED", state["first_incomplete_checks"]["user_state"]["stdout_tail"])
        self.assertEqual("RUNNING_UNLOCKED", state["last_checks"]["user_state"]["stdout_tail"])
        self.assertEqual(1, state["seconds"])
        device.launch.assert_not_called()
        device.shell.assert_not_called()

    def test_guest_readiness_timeout_is_bounded_and_retains_actual_failed_probe(self):
        receipt, clock = {}, ReadinessClock()
        device = Mock(spec=smoke.Device)
        device.adb.return_value = subprocess.CompletedProcess([], 1, "", "Permission denied")
        with patch.object(smoke.time, "monotonic", clock.monotonic), patch.object(smoke.time, "sleep", clock.sleep):
            with self.assertRaisesRegex(smoke.SmokeFailure, "not established within 2 seconds"):
                smoke.wait_for_guest_ready(device, receipt, 2)
        state = receipt["guest_readiness"]
        self.assertFalse(state["ready"])
        self.assertEqual(2, state["attempts"])
        self.assertEqual(2, state["seconds"])
        self.assertEqual("Permission denied", state["last_checks"]["ui_dump_writable"]["stderr_tail"])
        self.assertTrue(all(call.kwargs["timeout"] <= 2 for call in device.adb.call_args_list))
        device.launch.assert_not_called()
        device.shell.assert_not_called()

    def test_timed_out_readiness_probe_cannot_pass_from_partial_stdout(self):
        receipt, clock = {}, ReadinessClock()
        device = Mock(spec=smoke.Device)
        device.adb.side_effect = subprocess.TimeoutExpired(["adb"], 1, output=b"RUNNING_UNLOCKED", stderr=b"partial")
        with patch.object(smoke.time, "monotonic", clock.monotonic), patch.object(smoke.time, "sleep", clock.sleep):
            with self.assertRaises(smoke.SmokeFailure):
                smoke.wait_for_guest_ready(device, receipt, 1)
        state = receipt["guest_readiness"]
        self.assertFalse(state["ready"])
        self.assertEqual(1, state["last_checks"]["user_state"]["command_timeout_seconds"])
        self.assertEqual("RUNNING_UNLOCKED", state["last_checks"]["user_state"]["stdout_tail"])

    def test_readiness_rejects_unverified_target_and_never_uses_truncated_output(self):
        device = Mock(spec=smoke.Device)
        device.verify_identity.side_effect = smoke.SmokeFailure("Different owned target")
        receipt = {}
        with self.assertRaisesRegex(smoke.SmokeFailure, "Different owned target"):
            smoke.wait_for_guest_ready(device, receipt)
        self.assertFalse(receipt["guest_readiness"]["ready"])
        device.adb.assert_not_called()
        device.verify_identity.side_effect = None
        device.adb.return_value = subprocess.CompletedProcess([], 0, "x" * 3000 + "RUNNING_UNLOCKED", "")
        clock = ReadinessClock()
        with patch.object(smoke.time, "monotonic", clock.monotonic), patch.object(smoke.time, "sleep", clock.sleep):
            with self.assertRaises(smoke.SmokeFailure):
                smoke.wait_for_guest_ready(device, receipt, 1)
        observed = receipt["guest_readiness"]["last_checks"]["user_state"]
        self.assertTrue(observed["output_truncated"])
        self.assertEqual(1200, len(observed["stdout_tail"]))

    def test_guest_readiness_cannot_acknowledge_a_complete_snapshot_after_its_deadline(self):
        receipt, clock = {}, ReadinessClock()
        device = Mock(spec=smoke.Device)
        answers = iter(smoke.GUEST_READINESS_COMMANDS)

        def answer(*args, **kwargs):
            name, _, expected = next(answers)
            if name == smoke.GUEST_READINESS_COMMANDS[-1][0]:
                clock.now = 2
            return subprocess.CompletedProcess([], 0, expected[-1], "")

        device.adb.side_effect = answer
        with patch.object(smoke.time, "monotonic", clock.monotonic), patch.object(smoke.time, "sleep", clock.sleep):
            with self.assertRaises(smoke.SmokeFailure):
                smoke.wait_for_guest_ready(device, receipt, 1)
        self.assertTrue(smoke.guest_readiness_checks_pass(receipt["guest_readiness"]["last_checks"]))
        self.assertFalse(receipt["guest_readiness"]["ready"])

    def test_software_mode_is_explicit_and_never_uses_kvm_or_changes_host_access(self):
        with patch.dict(os.environ, {"PYAI_EMULATOR_MODE": "software"}), \
                patch.object(smoke, "check_kvm_access") as kvm, \
                patch.object(smoke, "command") as command, patch("sys.stdout", new_callable=io.StringIO):
            self.assertEqual({"mode": "software", "accel_argument": "off", "boot_timeout_seconds": 900,
                              "unsupported_software_opt_in": True}, smoke.emulation_settings())
            kvm.assert_not_called()
            command.assert_not_called()

    def test_default_hardware_mode_cannot_fall_back_after_denied_access(self):
        with patch.dict(os.environ, {}, clear=True), \
                patch.object(smoke, "check_kvm_access", side_effect=smoke.SmokeFailure("Access denied")) as kvm:
            with self.assertRaisesRegex(smoke.SmokeFailure, "Access denied"):
                smoke.emulation_settings()
            kvm.assert_called_once_with()

    def test_unknown_emulation_mode_fails_before_any_host_probe(self):
        with patch.dict(os.environ, {"PYAI_EMULATOR_MODE": "automatic"}), \
                patch.object(smoke, "check_kvm_access") as kvm:
            with self.assertRaisesRegex(smoke.SmokeFailure, "Unknown emulator mode"):
                smoke.emulation_settings()
            kvm.assert_not_called()

    def test_kvm_preflight_rejects_missing_regular_and_symlink_devices(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            regular = root / "regular"
            regular.touch()
            link = root / "link"
            link.symlink_to(regular)
            with patch.object(smoke.os, "access") as access, patch.object(smoke, "command") as command, \
                    patch("sys.stdout", new_callable=io.StringIO):
                for device in (root / "missing", regular, link):
                    with self.subTest(device=device.name), self.assertRaises(smoke.SmokeFailure):
                        smoke.check_kvm_access(device)
                access.assert_not_called()
                command.assert_not_called()

    def test_kvm_metadata_permission_error_fails_without_access_changes(self):
        with patch.object(Path, "lstat", side_effect=PermissionError), \
                patch.object(smoke.os, "access") as access, patch.object(smoke.os, "chown") as chown, \
                patch.object(smoke.os, "chmod") as chmod, patch.object(smoke, "command") as command, \
                patch("sys.stdout", new_callable=io.StringIO):
            with self.assertRaisesRegex(smoke.SmokeFailure, "absent or unreadable"):
                smoke.check_kvm_access()
            access.assert_not_called()
            chown.assert_not_called()
            chmod.assert_not_called()
            command.assert_not_called()

    def test_kvm_denied_access_fails_without_attempting_permission_repair(self):
        metadata = os.stat_result((stat.S_IFCHR | 0o660, 1, 1, 1, 1001, 108, 0, 0, 0, 0))
        with patch.object(Path, "lstat", return_value=metadata), \
                patch.object(smoke.os, "access", return_value=False) as access, \
                patch.object(smoke.os, "chown") as chown, patch.object(smoke.os, "chmod") as chmod, \
                patch.object(smoke, "command") as command, patch("sys.stdout", new_callable=io.StringIO):
            with self.assertRaisesRegex(smoke.SmokeFailure, "read/write access is unavailable"):
                smoke.check_kvm_access()
            access.assert_called_once_with(Path("/dev/kvm"), os.R_OK | os.W_OK, effective_ids=True)
            chown.assert_not_called()
            chmod.assert_not_called()
            command.assert_not_called()

    def test_kvm_existing_character_device_access_is_accepted_without_commands(self):
        metadata = os.stat_result((stat.S_IFCHR | 0o660, 1, 1, 1, 1001, 108, 0, 0, 0, 0))
        with patch.object(Path, "lstat", return_value=metadata), \
                patch.object(smoke.os, "access", return_value=True) as access, \
                patch.object(smoke.os, "chown") as chown, patch.object(smoke.os, "chmod") as chmod, \
                patch.object(smoke, "command") as command, patch("sys.stdout", new_callable=io.StringIO) as output:
            smoke.check_kvm_access()
            access.assert_called_once_with(Path("/dev/kvm"), os.R_OK | os.W_OK, effective_ids=True)
            self.assertIn("mode=crw-rw---- owner=1001 group=108", output.getvalue())
            chown.assert_not_called()
            chmod.assert_not_called()
            command.assert_not_called()

    def test_only_exact_owned_avd_identity_is_accepted(self):
        name = "pyai_smoke_" + "a" * 32
        smoke.validate_target(smoke.SERIAL, "1\n", name + "\nOK\n", name)
        for serial, qemu, actual, expected in (
            ("physical-device", "1", name + "\nOK", name),
            (smoke.SERIAL, "0", name + "\nOK", name),
            (smoke.SERIAL, "1", "someone_else\nOK", name),
            (smoke.SERIAL, "1", name + "\nOK\nextra", name),
            (smoke.SERIAL, "1", "shared\nOK", "shared"),
        ):
            with self.subTest(serial=serial, actual=actual), self.assertRaises(smoke.SmokeFailure):
                smoke.validate_target(serial, qemu, actual, expected)

    def test_local_provisioning_is_rejected_before_any_command(self):
        with patch.dict(os.environ, {"GITHUB_ACTIONS": "false", "RUNNER_ENVIRONMENT": "self-hosted"}), \
                patch.object(smoke, "command") as command:
            with self.assertRaisesRegex(smoke.SmokeFailure, "disposable GitHub-hosted"):
                smoke.main()
            command.assert_not_called()

    def test_launch_records_separate_output_without_relaxing_success_requirement(self):
        for status, stdout, stderr, accepted in (
            (0, "Status: ok\n", "", True),
            (0, "Status: timeout\n", "diagnostic stderr", False),
            (0, "Status: ok\nError: activity failed\n", "", False),
            (1, "Status: ok\n", "command failed", False),
        ):
            with self.subTest(status=status, stdout=stdout):
                receipt = {}
                device = smoke.Device(Path("adb"), {}, Mock(), "unused", receipt)
                with patch.object(device, "verify_identity") as identity, patch.object(
                        device, "adb", return_value=subprocess.CompletedProcess([], status, stdout, stderr)) as adb:
                    if accepted:
                        device.launch()
                    else:
                        with self.assertRaises(smoke.SmokeFailure):
                            device.launch()
                    identity.assert_called_once_with()
                    adb.assert_called_once_with("shell", "am", "start", "-W", "-n",
                                                smoke.PACKAGE + "/" + smoke.ACTIVITY, check=False)
                attempt = receipt["activity_launches"][0]
                self.assertEqual(accepted, attempt["check_passed"])
                self.assertEqual(status, attempt["exit_code"])
                self.assertEqual(stdout, attempt["stdout_tail"])
                self.assertEqual(stderr, attempt["stderr_tail"])

    def test_launch_command_timeout_retains_bounded_partial_streams_and_fails(self):
        receipt = {}
        device = smoke.Device(Path("adb"), {}, Mock(), "unused", receipt)
        error = subprocess.TimeoutExpired("am", 30, output=b"x" * 7000, stderr=b"partial error")
        with patch.object(device, "verify_identity"), patch.object(device, "adb", side_effect=error):
            with self.assertRaises(subprocess.TimeoutExpired) as raised:
                device.launch()
        self.assertIs(error, raised.exception)
        attempt = receipt["activity_launches"][0]
        self.assertFalse(attempt["check_passed"])
        self.assertEqual(30, attempt["command_timeout_seconds"])
        self.assertEqual("x" * 6000, attempt["stdout_tail"])
        self.assertEqual("partial error", attempt["stderr_tail"])
        self.assertNotIn("exit_code", attempt)

    def test_failure_diagnostics_are_bounded_and_cannot_mask_original_case_failure(self):
        for log_result in (subprocess.CompletedProcess([], 1, "x" * 140000, "log failed"),
                           subprocess.TimeoutExpired("logcat", 20)):
            with self.subTest(log_result=type(log_result).__name__), tempfile.TemporaryDirectory() as temporary:
                receipt = {"cases": [{"id": name, "status": "not_run"} for name in smoke.CASE_IDS]}
                device = Mock(spec=smoke.Device)
                original = smoke.SmokeFailure("Original launch failure")
                device.launch.side_effect = original
                device.adb.side_effect = ([log_result] if isinstance(log_result, Exception) else None)
                device.adb.return_value = log_result
                with patch.object(smoke.Ui, "checkpoint", side_effect=smoke.SmokeFailure("Capture failed")):
                    with self.assertRaises(smoke.SmokeFailure) as raised:
                        smoke.exercise(device, Path(temporary), receipt)
                self.assertIs(original, raised.exception)
                self.assertEqual("failed", receipt["cases"][0]["status"])
                self.assertTrue(all(row["status"] == "not_run" for row in receipt["cases"][1:]))
                self.assertEqual("Capture failed", receipt["failure_checkpoint_error"])
                args, kwargs = device.adb.call_args
                self.assertEqual("logcat", args[0])
                self.assertIn("400", args)
                self.assertEqual({"timeout": 20, "check": False}, kwargs)
                if isinstance(log_result, Exception):
                    self.assertIn("timed out", receipt["failure_logcat"]["error"])
                    self.assertFalse((Path(temporary) / "failure-logcat.txt").exists())
                else:
                    self.assertEqual("x" * 131072, (Path(temporary) / "failure-logcat.txt").read_text())
                    self.assertEqual(1, receipt["failure_logcat"]["exit_code"])
                    self.assertEqual("log failed", receipt["failure_logcat"]["stderr_tail"])

    def test_ambiguous_controls_are_not_guessed(self):
        root = ET.Element("hierarchy")
        root.extend([node("Delete local draft", smoke.BUTTON), node("Delete local draft", smoke.BUTTON)])
        with self.assertRaisesRegex(smoke.SmokeFailure, "Ambiguous"):
            smoke.single_match(root, lambda item: item.get("text") == "Delete local draft")

    def test_other_packages_and_invisible_nodes_cannot_be_selected(self):
        root = ET.Element("hierarchy")
        root.extend([node("Save", package="another.application"), node("Save", box="[0,0][0,0]")])
        self.assertIsNone(smoke.single_match(root, lambda item: item.get("text") == "Save"))

    def test_editor_requires_the_observed_label_adjacency(self):
        root = ET.Element("hierarchy")
        container = ET.SubElement(root, "node")
        label, field = node(smoke.TITLE_LABEL), node("Synthetic", smoke.EDITOR)
        container.extend([label, field])
        self.assertIs(smoke.labelled_editor(root, smoke.TITLE_LABEL), field)
        container.insert(1, node("Unrelated control"))
        self.assertIsNone(smoke.labelled_editor(root, smoke.TITLE_LABEL))

    def test_dump_requires_removal_then_fresh_xml_and_records_completed_137(self):
        path = smoke.UI_DUMP_PATH
        raw = '<hierarchy rotation="0"><node package="' + smoke.PACKAGE + '" /></hierarchy>'
        for status in (0, 137):
            with self.subTest(status=status):
                device, receipt = Mock(spec=smoke.Device), {}
                device.adb.side_effect = [
                    subprocess.CompletedProcess([], status, "UI hierchary dumped to: " + path + "\n", ""),
                    subprocess.CompletedProcess([], 0, raw, ""),
                ]
                root = smoke.Ui(device, Path("unused"), receipt).snapshot()
                self.assertEqual("hierarchy", root.tag)
                self.assertEqual([
                    call.shell("rm", "-f", path),
                    call.adb("shell", "uiautomator", "dump", path, timeout=20, check=False),
                    call.adb("exec-out", "cat", path),
                ], device.mock_calls)
                self.assertEqual({"attempted": 1, "completed": 1, "exit_137": int(status == 137)},
                                 receipt["ui_hierarchy_dumps"])

    def test_failed_prior_dump_removal_cannot_reuse_stale_xml(self):
        device, receipt = Mock(spec=smoke.Device), {}
        device.shell.side_effect = smoke.SmokeFailure("Dump removal failed")
        with self.assertRaisesRegex(smoke.SmokeFailure, "removal failed"):
            smoke.Ui(device, Path("unused"), receipt).snapshot()
        device.adb.assert_not_called()
        self.assertEqual(0, receipt["ui_hierarchy_dumps"]["completed"])

    def test_unrelated_dump_failure_or_inexact_completion_is_rejected(self):
        marker = "UI hierchary dumped to: " + smoke.UI_DUMP_PATH
        for status, stdout, stderr in (
            (1, marker, ""), (137, "", ""), (137, marker, "Killed"),
            (137, marker + "\nERROR: dump failed", ""),
            (137, "UI hierchary dumped to: /sdcard/pyai-smoke-window.xml", ""),
            (137, "UI hierchary dumped to: /sdcard/another.xml", ""),
        ):
            with self.subTest(status=status, stdout=stdout, stderr=stderr):
                device, receipt = Mock(spec=smoke.Device), {}
                device.adb.return_value = subprocess.CompletedProcess([], status, stdout, stderr)
                with self.assertRaisesRegex(smoke.SmokeFailure, "UI hierarchy dump failed"):
                    smoke.Ui(device, Path("unused"), receipt).snapshot()
                self.assertEqual(1, device.adb.call_count)
                self.assertEqual(0, receipt["ui_hierarchy_dumps"]["completed"])

    def test_completion_message_cannot_replace_missing_truncated_or_invalid_xml(self):
        marker = "UI hierchary dumped to: " + smoke.UI_DUMP_PATH
        for raw in (None, "", '<hierarchy rotation="0"><node>',
                    '<other><node /></other>', '<hierarchy rotation="0" />',
                    '<hierarchy rotation="9"><node /></hierarchy>'):
            with self.subTest(raw=raw):
                device, receipt = Mock(spec=smoke.Device), {}
                read = (smoke.SmokeFailure("Fresh dump missing") if raw is None else
                        subprocess.CompletedProcess([], 0, raw, ""))
                device.adb.side_effect = [subprocess.CompletedProcess([], 137, marker, ""), read]
                with self.assertRaises((smoke.SmokeFailure, ET.ParseError)):
                    smoke.Ui(device, Path("unused"), receipt).snapshot()
                self.assertEqual(0, receipt["ui_hierarchy_dumps"]["completed"])
                self.assertEqual(0, receipt["ui_hierarchy_dumps"]["exit_137"])

    def test_incomplete_or_differently_scoped_receipts_fail(self):
        receipt = {"source_commit": "a" * 40, "scope": "owned-installed-aosp-emulator", "status": "passed",
                   "unsigned_apk_unchanged": True,
                   "guest_readiness": {"ready": True, "ui_dump_path": smoke.UI_DUMP_PATH, "last_checks": ready_checks()},
                   "cases": [{"id": name, "status": "passed"} for name in smoke.CASE_IDS]}
        smoke.complete_receipt(receipt, "a" * 40)
        invalid = []
        for key, value in (("source_commit", "b" * 40), ("scope", "simulated"),
                           ("status", "failed"), ("unsigned_apk_unchanged", False),
                           ("guest_readiness", None), ("guest_readiness", {"ready": True}),
                           ("guest_readiness", {"ready": False, "ui_dump_path": smoke.UI_DUMP_PATH, "last_checks": ready_checks()}),
                           ("guest_readiness", {"ready": True, "last_checks": ready_checks()}),
                           ("guest_readiness", {"ready": True, "ui_dump_path": "/sdcard/pyai-smoke-window.xml", "last_checks": ready_checks()})):
            changed = copy.deepcopy(receipt)
            changed[key] = value
            invalid.append(changed)
        missing, duplicate, skipped = [copy.deepcopy(receipt) for _ in range(3)]
        missing["cases"].pop()
        duplicate["cases"][1] = duplicate["cases"][0]
        skipped["cases"][2]["status"] = "not_run"
        changed = copy.deepcopy(receipt)
        changed["guest_readiness"]["last_checks"]["user_state"]["stdout_tail"] = "RUNNING_LOCKED"
        invalid.append(changed)
        for changed in invalid + [missing, duplicate, skipped]:
            with self.assertRaises(smoke.SmokeFailure):
                smoke.complete_receipt(changed, "a" * 40)

    def test_setup_failure_report_never_counts_unexecuted_cases_as_passed(self):
        receipt = {"status": "failed", "error": "No KVM", "cases":
                   [{"id": name, "status": "not_run"} for name in smoke.CASE_IDS]}
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary)
            smoke.write_reports(output, receipt)
            suite = ET.parse(output / "junit.xml").getroot()
            self.assertEqual("1", suite.get("failures"))
            self.assertEqual("4", suite.get("skipped"))
            self.assertEqual(4, len(suite.findall("testcase/skipped")))
            self.assertEqual("No KVM", suite.find("testcase/failure").text)


if __name__ == "__main__":
    unittest.main()
