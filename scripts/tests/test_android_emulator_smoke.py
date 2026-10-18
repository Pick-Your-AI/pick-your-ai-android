"""Host-side guard tests; these do not install or run an Android app."""

import copy
import importlib.util
import io
import os
from pathlib import Path
import re
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
    def test_boot_poll_retains_transport_failure_then_completed_property(self):
        receipt, clock = {}, ReadinessClock()
        device = Mock(spec=smoke.Device)
        device.adb.side_effect = [subprocess.CompletedProcess([], 1, "", "adb: device offline"),
                                  subprocess.CompletedProcess([], 0, "0\n", ""),
                                  subprocess.CompletedProcess([], 0, "1\n", "")]
        with patch.object(smoke.time, "monotonic", clock.monotonic), patch.object(smoke.time, "sleep", clock.sleep):
            smoke.wait_for_boot_completed(device, receipt, 20)
        state = receipt["boot_wait"]
        self.assertTrue(state["completed"])
        self.assertEqual(3, state["attempts"])
        self.assertEqual(2, state["seconds"])
        self.assertEqual("adb: device offline", state["first_probe"]["stderr_tail"])
        self.assertEqual("1\n", state["last_probe"]["stdout_tail"])
        self.assertTrue(state["last_probe"]["boot_property_passed"])
        self.assertEqual([1, 2, 3], [p["attempt"] for p in state["recent_probes"]])
        self.assertEqual([call("shell", "getprop", "sys.boot_completed", timeout=10, check=False)] * 3,
                         device.adb.call_args_list)
        device.launch.assert_not_called()
        device.shell.assert_not_called()

    def test_boot_poll_deadline_streams_and_recent_history_are_bounded(self):
        receipt, clock = {}, ReadinessClock()
        device = Mock(spec=smoke.Device)
        device.adb.return_value = subprocess.CompletedProcess([], 1, "x" * 3000, "y" * 3000 + "offline")
        with patch.object(smoke.time, "monotonic", clock.monotonic), patch.object(smoke.time, "sleep", clock.sleep):
            with self.assertRaisesRegex(smoke.SmokeFailure, "did not boot within 21.5 seconds"):
                smoke.wait_for_boot_completed(device, receipt, 21.5)
        state = receipt["boot_wait"]
        self.assertFalse(state["completed"])
        self.assertEqual(22, state["attempts"])
        self.assertEqual(21.5, state["seconds"])
        self.assertEqual(1, state["first_probe"]["attempt"])
        self.assertEqual(list(range(7, 23)), [p["attempt"] for p in state["recent_probes"]])
        self.assertEqual(22, state["last_probe"]["attempt"])
        for probe in state["recent_probes"] + [state["first_probe"]]:
            self.assertTrue(probe["output_truncated"])
            self.assertEqual(1200, len(probe["stdout_tail"]))
            self.assertEqual(1200, len(probe["stderr_tail"]))
            self.assertTrue(probe["stderr_tail"].endswith("offline"))
        self.assertEqual(0.5, device.adb.call_args_list[-1].kwargs["timeout"])
        self.assertTrue(all(x.kwargs["timeout"] <= 10 for x in device.adb.call_args_list))

    def test_boot_property_must_succeed_without_error_before_its_deadline(self):
        for code, stdout, stderr in ((1, "1\n", ""), (0, "0\n", ""),
                                     (0, "11\n", ""), (0, "1\n", "device error")):
            receipt, clock = {}, ReadinessClock()
            device = Mock(spec=smoke.Device)
            device.adb.return_value = subprocess.CompletedProcess([], code, stdout, stderr)
            with self.subTest(code=code, stdout=stdout, stderr=stderr), \
                    patch.object(smoke.time, "monotonic", clock.monotonic), patch.object(smoke.time, "sleep", clock.sleep):
                with self.assertRaises(smoke.SmokeFailure):
                    smoke.wait_for_boot_completed(device, receipt, 1)
            self.assertFalse(receipt["boot_wait"]["completed"])
            self.assertFalse(receipt["boot_wait"]["last_probe"]["boot_property_passed"])
        receipt, clock = {}, ReadinessClock()

        def late_result(*args, **kwargs):
            clock.now = 2
            return subprocess.CompletedProcess([], 0, "1\n", "")

        device.adb.side_effect = late_result
        with patch.object(smoke.time, "monotonic", clock.monotonic), patch.object(smoke.time, "sleep", clock.sleep):
            with self.assertRaises(smoke.SmokeFailure):
                smoke.wait_for_boot_completed(device, receipt, 1)
        self.assertTrue(receipt["boot_wait"]["last_probe"]["boot_property_passed"])
        self.assertFalse(receipt["boot_wait"]["completed"])

    def test_boot_timeout_and_owned_process_errors_keep_the_original_failure(self):
        errors = [subprocess.TimeoutExpired(["adb"], 2, output=b"x" * 3000 + b"1", stderr=b"y" * 3000),
                  smoke.SmokeFailure("Owned emulator process exited")]
        for error in errors:
            receipt, clock = {}, ReadinessClock()
            device = Mock(spec=smoke.Device)
            device.adb.side_effect = error
            with self.subTest(error=type(error).__name__), \
                    patch.object(smoke.time, "monotonic", clock.monotonic), patch.object(smoke.time, "sleep", clock.sleep):
                with self.assertRaises(type(error)) as raised:
                    smoke.wait_for_boot_completed(device, receipt, 2)
            self.assertIs(error, raised.exception)
            device.adb.assert_called_once_with("shell", "getprop", "sys.boot_completed", timeout=2, check=False)
            state = receipt["boot_wait"]
            self.assertFalse(state["completed"])
            self.assertEqual(1, state["attempts"])
            self.assertNotIn("boot_property_passed", state["last_probe"])
            self.assertIn("error", state)
            if isinstance(error, subprocess.TimeoutExpired):
                self.assertEqual(2, state["last_probe"]["command_timeout_seconds"])
                self.assertEqual(1200, len(state["last_probe"]["stdout_tail"]))
                self.assertEqual(1200, len(state["last_probe"]["stderr_tail"]))
            else:
                self.assertEqual(str(error), state["last_probe"]["error"])

    def test_failed_boot_observations_only_read_the_owned_transport_and_keep_failure(self):
        receipt = {"status": "failed", "error": "original boot failure", "boot_wait": {"completed": False}}
        device = Mock(spec=smoke.Device)
        device.adb.return_value = subprocess.CompletedProcess([], 0, "device\n", "")
        smoke.retain_boot_failure_state(device, receipt)
        self.assertEqual([
            call("get-state", timeout=5, check=False),
            call("emu", "avd", "name", timeout=5, check=False),
            call("shell", "getprop", "ro.kernel.qemu", timeout=5, check=False),
            call("shell", "getprop", "init.svc.bootanim", timeout=5, check=False),
        ], device.adb.call_args_list)
        self.assertEqual("failed", receipt["status"])
        self.assertEqual("original boot failure", receipt["error"])
        self.assertFalse(receipt["boot_wait"]["completed"])
        self.assertEqual(4, len(receipt["boot_failure_observations"]["checks"]))
        device.launch.assert_not_called()
        device.shell.assert_not_called()

    def test_failed_boot_diagnostic_timeouts_are_bounded_and_cannot_mask_failure(self):
        receipt, clock = {"status": "failed", "error": "original"}, ReadinessClock()
        device = Mock(spec=smoke.Device)

        def timeout(*args, **kwargs):
            clock.now += kwargs["timeout"]
            raise subprocess.TimeoutExpired(["adb"], kwargs["timeout"], output=b"x" * 3000, stderr=b"offline")

        device.adb.side_effect = timeout
        with patch.object(smoke.time, "monotonic", clock.monotonic):
            smoke.retain_boot_failure_state(device, receipt)
        self.assertEqual("original", receipt["error"])
        self.assertEqual("failed", receipt["status"])
        self.assertEqual(20, receipt["boot_failure_observations"]["seconds"])
        self.assertEqual(4, len(receipt["boot_failure_observations"]["checks"]))
        for observed in receipt["boot_failure_observations"]["checks"].values():
            self.assertEqual(5, observed["command_timeout_seconds"])
            self.assertEqual(1200, len(observed["stdout_tail"]))
            self.assertEqual("offline", observed["stderr_tail"])
        device.adb.side_effect = smoke.SmokeFailure("Owned emulator process exited")
        smoke.retain_boot_failure_state(device, receipt)
        self.assertTrue(all(x["error"] == "Owned emulator process exited"
                            for x in receipt["boot_failure_observations"]["checks"].values()))
        self.assertEqual("original", receipt["error"])

    def test_invalid_boot_deadlines_fail_before_any_device_command(self):
        device = Mock(spec=smoke.Device)
        for timeout in (0, -1, 901, float("inf"), float("nan")):
            with self.subTest(timeout=timeout), self.assertRaisesRegex(smoke.SmokeFailure, "Invalid boot deadline"):
                smoke.wait_for_boot_completed(device, {}, timeout)
        device.adb.assert_not_called()

    def test_hvf_workflow_artifact_id_download_reaches_the_harness_apk_path(self):
        # v4.3.0 src/download-artifact.ts treats only a name lookup as a single
        # artifact download. An ID list needs merge-multiple even with one ID.
        # Exercise that pinned dependency's extraction rule with the workflow
        # inputs, including the previously broken default as a negative case.
        workflow = (ROOT / ".github/workflows/core.yml").read_text()
        action = "actions/download-artifact@d3f86a106a0bac45b974a628896c90dbdf5c8093"
        matches = re.findall(r"(?m)^      - uses: " + re.escape(action) + r"[^\n]*\n((?:        [^\n]*\n)+)", workflow)
        self.assertEqual(1, len(matches))
        inputs = dict(line.strip().split(":", 1) for line in matches[0].splitlines() if line.startswith("          "))
        inputs = {key: value.strip() for key, value in inputs.items()}
        self.assertEqual("${{ needs.android-preview.outputs.unsigned_artifact_id }}", inputs["artifact-ids"])
        self.assertNotIn("name", inputs)
        self.assertNotIn("pattern", inputs)
        self.assertEqual("true", inputs.get("merge-multiple"))
        artifact_name = "android-preview-" + "a" * 40
        archive_entry = Path("outputs/apk/debug/preview-debug-unsigned.apk")
        expected = Path("preview/build/outputs/apk/debug/preview-debug-unsigned.apk")

        def extracted_path(options):
            root = Path(options["path"])
            return (root if options.get("name") or options.get("merge-multiple") == "true"
                    else root / artifact_name) / archive_entry

        self.assertEqual(expected, extracted_path(inputs))
        without_merge = dict(inputs)
        without_merge.pop("merge-multiple")
        self.assertNotEqual(expected, extracted_path(without_merge))

    def test_hvf_accepts_only_observed_host_support_without_kvm_or_permission_changes(self):
        with patch.dict(os.environ, {"PYAI_EMULATOR_MODE": "hvf", "RUNNER_ENVIRONMENT": "github-hosted",
                                     "RUNNER_OS": "macOS", "RUNNER_ARCH": "X64"}, clear=True), \
                patch.multiple(smoke.platform, system=lambda: "Darwin", machine=lambda: "x86_64",
                               mac_ver=lambda: ("15.7.9", (), "")), \
                patch.object(smoke, "command", return_value=subprocess.CompletedProcess([], 0, "1\n", "")) as command, \
                patch.object(smoke, "check_kvm_access") as kvm, \
                patch.object(smoke.os, "chmod") as chmod, patch.object(smoke.os, "chown") as chown, \
                patch("sys.stdout", new_callable=io.StringIO):
            settings = smoke.emulation_settings()
        command.assert_called_once_with(["/usr/sbin/sysctl", "-n", "kern.hv_support"], timeout=10, check=False)
        self.assertEqual("hvf", settings["mode"])
        self.assertEqual("on", settings["accel_argument"])
        self.assertEqual(300, settings["boot_timeout_seconds"])
        self.assertFalse(settings["unsupported_software_opt_in"])
        self.assertTrue(smoke.hvf_host_passes(settings["host"]))
        self.assertEqual("15.7.9", settings["host"]["macos_version"])
        kvm.assert_not_called()
        chmod.assert_not_called()
        chown.assert_not_called()

    def test_hvf_rejects_foreign_or_unobserved_host_before_any_command(self):
        env = {"PYAI_EMULATOR_MODE": "hvf", "RUNNER_ENVIRONMENT": "github-hosted",
               "RUNNER_OS": "macOS", "RUNNER_ARCH": "X64"}
        for changed, system, machine in (
            ({"RUNNER_ENVIRONMENT": "self-hosted"}, "Darwin", "x86_64"),
            ({"RUNNER_OS": "Linux"}, "Darwin", "x86_64"),
            ({"RUNNER_ARCH": "ARM64"}, "Darwin", "x86_64"),
            ({"RUNNER_ARCH": ""}, "Darwin", "x86_64"),
            ({}, "Linux", "x86_64"),
            ({}, "Darwin", "arm64"),
        ):
            with self.subTest(changed=changed, system=system, machine=machine), \
                    patch.dict(os.environ, dict(env, **changed), clear=True), \
                    patch.object(smoke.platform, "system", return_value=system), \
                    patch.object(smoke.platform, "machine", return_value=machine), \
                    patch.object(smoke, "command") as command, patch.object(smoke, "check_kvm_access") as kvm:
                with self.assertRaisesRegex(smoke.SmokeFailure, "explicit GitHub-hosted Intel"):
                    smoke.emulation_settings()
                command.assert_not_called()
                kvm.assert_not_called()

    def test_hvf_failed_or_ambiguous_support_never_falls_back(self):
        env = {"PYAI_EMULATOR_MODE": "hvf", "RUNNER_ENVIRONMENT": "github-hosted",
               "RUNNER_OS": "macOS", "RUNNER_ARCH": "X64"}
        invalid = [(0, "0\n", ""), (1, "1\n", ""), (0, "1\n", "failure"),
                   (0, "1\n", " "), (0, "1\nextra", ""), (False, "1\n", ""),
                   (0, " " * 2048 + "1\n", "")]
        for code, stdout, stderr in invalid:
            with self.subTest(code=code, stdout=stdout[-12:], stderr=stderr), \
                    patch.dict(os.environ, env, clear=True), \
                    patch.multiple(smoke.platform, system=lambda: "Darwin", machine=lambda: "x86_64"), \
                    patch.object(smoke, "command", return_value=subprocess.CompletedProcess([], code, stdout, stderr)), \
                    patch.object(smoke, "check_kvm_access") as kvm:
                with self.assertRaisesRegex(smoke.SmokeFailure, "support is unavailable"):
                    smoke.emulation_settings()
                kvm.assert_not_called()
        with patch.dict(os.environ, env, clear=True), \
                patch.multiple(smoke.platform, system=lambda: "Darwin", machine=lambda: "x86_64"), \
                patch.object(smoke, "command", side_effect=subprocess.TimeoutExpired(["sysctl"], 10)), \
                patch.object(smoke, "check_kvm_access") as kvm:
            with self.assertRaises(subprocess.TimeoutExpired):
                smoke.emulation_settings()
            kvm.assert_not_called()

    def test_hvf_acceleration_requires_complete_successful_hypervisor_output(self):
        valid = {"exit_code": 0, "stdout": "accel:\n0\nHypervisor.Framework OS X Version 15.7\naccel\n", "stderr": ""}
        with patch.object(smoke, "command", return_value=subprocess.CompletedProcess([], 0, valid["stdout"], "")) as command:
            self.assertEqual(valid, smoke.check_hvf_acceleration(Path("/sdk/emulator/emulator")))
        command.assert_called_once_with(["/sdk/emulator/emulator", "-accel-check"], timeout=30, check=False)
        for change in ({"exit_code": 1}, {"exit_code": False}, {"stderr": "warning"},
                       {"stdout": "Hypervisor.Framework is unavailable"},
                       {"stdout": "accel:\n0\nKVM is installed and usable.\naccel\n"},
                       {"stdout": valid["stdout"] + "unexpected"},
                       {"stdout": " " * 3000 + valid["stdout"]}):
            observed = dict(valid, **change)
            with self.subTest(change=change), patch.object(smoke, "command", return_value=subprocess.CompletedProcess(
                    [], observed["exit_code"], observed["stdout"], observed["stderr"])):
                with self.assertRaisesRegex(smoke.SmokeFailure, "did not confirm usable"):
                    smoke.check_hvf_acceleration(Path("/sdk/emulator/emulator"))
        with patch.object(smoke, "command", side_effect=subprocess.TimeoutExpired(["emulator"], 30)):
            with self.assertRaises(subprocess.TimeoutExpired):
                smoke.check_hvf_acceleration(Path("/sdk/emulator/emulator"))

    def test_apk_handoff_rejects_missing_malformed_or_different_build_digest(self):
        actual = "a" * 64
        smoke.require_apk_handoff(actual, actual)
        for expected in (None, "", "a" * 63, "b" * 64, actual + "\n", actual.upper(), False):
            with self.subTest(expected=expected), self.assertRaisesRegex(smoke.SmokeFailure, "exact SHA-256 handoff"):
                smoke.require_apk_handoff(actual, expected)

    def test_hvf_receipt_cannot_replace_host_handoff_or_installed_evidence(self):
        receipt = {"source_commit": "a" * 40, "scope": "owned-installed-aosp-emulator", "status": "passed",
                   "unsigned_apk_unchanged": True, "unsigned_apk_sha256": "b" * 64,
                   "expected_unsigned_apk_sha256": "b" * 64,
                   "emulation": {"mode": "hvf", "accel_argument": "on", "unsupported_software_opt_in": False,
                                 "host": dict(smoke.HVF_HOST_IDENTITY, kern_hv_support="1")},
                   "host_acceleration": {"exit_code": 0, "stderr": "",
                                         "stdout": "accel:\n0\nHypervisor.Framework OS X Version 15.7\naccel\n"},
                   "guest_readiness": {"ready": True, "ui_dump_path": smoke.UI_DUMP_PATH, "last_checks": ready_checks()},
                   "cases": [{"id": name, "status": "passed"} for name in smoke.CASE_IDS]}
        smoke.complete_receipt(receipt, "a" * 40, expected_mode="hvf")
        invalid = []
        for key in ("expected_unsigned_apk_sha256", "unsigned_apk_sha256", "host_acceleration", "emulation"):
            changed = copy.deepcopy(receipt)
            changed.pop(key)
            invalid.append(changed)
        for key, value in (("mode", "software"), ("accel_argument", "off"),
                           ("unsupported_software_opt_in", True), ("host", {"kern_hv_support": "1"})):
            changed = copy.deepcopy(receipt)
            changed["emulation"][key] = value
            invalid.append(changed)
        changed = copy.deepcopy(receipt)
        changed["guest_readiness"]["last_checks"]["ui_dump_writable"]["exit_code"] = 1
        invalid.append(changed)
        changed = copy.deepcopy(receipt)
        changed["cases"][0]["status"] = "not_run"
        invalid.append(changed)
        for changed in invalid:
            with self.subTest(receipt=changed), self.assertRaises(smoke.SmokeFailure):
                smoke.complete_receipt(changed, "a" * 40, expected_mode="hvf")

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
