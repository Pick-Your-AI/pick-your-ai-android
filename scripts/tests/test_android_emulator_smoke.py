"""Host-side guard tests; these do not install or run an Android app."""

import copy
import hashlib
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


def interactive_answers():
    home = '<hierarchy rotation="0"><node package="com.android.launcher3" bounds="[0,0][100,100]" /></hierarchy>'
    focus = ("  mCurrentFocus=Window{abc u0 com.android.launcher3/com.android.launcher3.uioverrides.QuickstepLauncher}\n"
             "  mFocusedWindow=Window{abc u0 com.android.launcher3/com.android.launcher3.uioverrides.QuickstepLauncher}\n")
    return [subprocess.CompletedProcess([], 0, "Test barrier passed\nFinished application barriers!\n", ""),
            subprocess.CompletedProcess([], 0, "", ""),
            subprocess.CompletedProcess([], 0, "UI hierchary dumped to: " + smoke.UI_DUMP_PATH + "\n", ""),
            subprocess.CompletedProcess([], 0, home, ""),
            subprocess.CompletedProcess([], 0, focus, "")]


def ready_interactive():
    device, readiness = Mock(spec=smoke.Device), {}
    device.adb.side_effect = interactive_answers()
    with patch.object(smoke.time, "monotonic", return_value=0):
        smoke.establish_interactive_readiness(device, readiness, 180)
    return readiness["interactive"]


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
                                     "RUNNER_OS": "macOS", "RUNNER_ARCH": "X64", "GITHUB_ACTIONS": "true",
                                     "ANDROID_HOME": "/sdk"}, clear=True), \
                patch.multiple(smoke.platform, system=lambda: "Darwin", machine=lambda: "x86_64",
                               mac_ver=lambda: ("15.7.9", (), "")), \
                patch.object(Path, "is_file", return_value=True), patch.object(Path, "is_symlink", return_value=False), \
                patch.object(smoke, "command", return_value=subprocess.CompletedProcess([], 0, "1\n", "")) as command, \
                patch.object(smoke, "check_kvm_access") as kvm, \
                patch.object(smoke.os, "chmod") as chmod, patch.object(smoke.os, "chown") as chown, \
                patch("sys.stdout", new_callable=io.StringIO):
            settings = smoke.emulation_settings()
        command.assert_called_once_with(["/usr/sbin/sysctl", "-n", "kern.hv_support"], timeout=10, check=False)
        self.assertEqual("hvf", settings["mode"])
        self.assertEqual("on", settings["accel_argument"])
        self.assertEqual(600, settings["boot_timeout_seconds"])
        self.assertFalse(settings["unsupported_software_opt_in"])
        self.assertTrue(smoke.hvf_host_passes(settings["host"]))
        self.assertEqual("15.7.9", settings["host"]["macos_version"])
        kvm.assert_not_called()
        chmod.assert_not_called()
        chown.assert_not_called()

    def test_hvf_rejects_foreign_or_unobserved_host_before_any_command(self):
        env = {"PYAI_EMULATOR_MODE": "hvf", "RUNNER_ENVIRONMENT": "github-hosted",
               "RUNNER_OS": "macOS", "RUNNER_ARCH": "X64", "GITHUB_ACTIONS": "true", "ANDROID_HOME": "/sdk"}
        for changed, system, machine in (
            ({"GITHUB_ACTIONS": "false"}, "Darwin", "x86_64"),
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
               "RUNNER_OS": "macOS", "RUNNER_ARCH": "X64", "GITHUB_ACTIONS": "true", "ANDROID_HOME": "/sdk"}
        invalid = [(0, "0\n", ""), (1, "1\n", ""), (0, "1\n", "failure"),
                   (0, "1\n", " "), (0, "1\nextra", ""), (False, "1\n", ""),
                   (0, " " * 2048 + "1\n", "")]
        for code, stdout, stderr in invalid:
            with self.subTest(code=code, stdout=stdout[-12:], stderr=stderr), \
                    patch.dict(os.environ, env, clear=True), \
                    patch.multiple(smoke.platform, system=lambda: "Darwin", machine=lambda: "x86_64"), \
                    patch.object(Path, "is_file", return_value=True), patch.object(Path, "is_symlink", return_value=False), \
                    patch.object(smoke, "command", return_value=subprocess.CompletedProcess([], code, stdout, stderr)), \
                    patch.object(smoke, "check_kvm_access") as kvm:
                with self.assertRaisesRegex(smoke.SmokeFailure, "support is unavailable"):
                    smoke.emulation_settings()
                kvm.assert_not_called()
        with patch.dict(os.environ, env, clear=True), \
                patch.multiple(smoke.platform, system=lambda: "Darwin", machine=lambda: "x86_64"), \
                patch.object(Path, "is_file", return_value=True), patch.object(Path, "is_symlink", return_value=False), \
                patch.object(smoke, "command", side_effect=subprocess.TimeoutExpired(["sysctl"], 10)), \
                patch.object(smoke, "check_kvm_access") as kvm:
            with self.assertRaises(subprocess.TimeoutExpired):
                smoke.emulation_settings()
            kvm.assert_not_called()

    def test_hvf_acceleration_requires_complete_successful_hypervisor_output(self):
        valid = {"exit_code": 0, "stdout": "accel:\n0\nHypervisor.Framework OS X Version 15.7\naccel\n",
                 "stderr": "", "output_truncated": False}
        with patch.object(smoke, "command", return_value=subprocess.CompletedProcess([], 0, valid["stdout"], "")) as command, \
                patch.object(Path, "is_file", return_value=True), patch.object(Path, "is_symlink", return_value=False):
            self.assertEqual(valid, smoke.check_hvf_acceleration(Path("/sdk/emulator/emulator")))
        command.assert_called_once_with(["/sdk/emulator/emulator", "-accel-check"], timeout=30, check=False)
        for change in ({"exit_code": 1}, {"exit_code": False}, {"stderr": "warning"},
                       {"stdout": "Hypervisor.Framework is unavailable"},
                       {"stdout": "accel:\n0\nKVM is installed and usable.\naccel\n"},
                       {"stdout": valid["stdout"] + "unexpected"},
                       {"stdout": " " * 3000 + valid["stdout"]},
                       *({"stdout": valid["stdout"].replace("15.7", malformed)}
                         for malformed in (".", "15..7", ".15", "15."))):
            observed = dict(valid, **change)
            with self.subTest(change=change), patch.object(smoke, "command", return_value=subprocess.CompletedProcess(
                    [], observed["exit_code"], observed["stdout"], observed["stderr"])), \
                    patch.object(Path, "is_file", return_value=True), patch.object(Path, "is_symlink", return_value=False):
                with self.assertRaisesRegex(smoke.SmokeFailure, "did not confirm usable"):
                    smoke.check_hvf_acceleration(Path("/sdk/emulator/emulator"))
        with patch.object(smoke, "command", side_effect=subprocess.TimeoutExpired(["emulator"], 30)), \
                patch.object(Path, "is_file", return_value=True), patch.object(Path, "is_symlink", return_value=False):
            with self.assertRaises(subprocess.TimeoutExpired):
                smoke.check_hvf_acceleration(Path("/sdk/emulator/emulator"))

    def test_apk_handoff_rejects_missing_malformed_or_different_build_digest(self):
        actual = "a" * 64
        smoke.require_apk_handoff(actual, actual)
        for expected in (None, "", "a" * 63, "b" * 64, actual + "\n", actual.upper(), False):
            with self.subTest(expected=expected), self.assertRaisesRegex(smoke.SmokeFailure, "exact SHA-256 handoff"):
                smoke.require_apk_handoff(actual, expected)

    def test_hvf_refuses_missing_directory_or_linked_emulator_before_any_command(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            regular = root / "regular-emulator"
            regular.write_text("unused test file")
            for kind in ("missing", "directory", "symlink"):
                sdk = root / kind
                emulator = sdk / "emulator/emulator"
                emulator.parent.mkdir(parents=True)
                if kind == "directory":
                    emulator.mkdir()
                elif kind == "symlink":
                    emulator.symlink_to(regular)
                environment = {"GITHUB_ACTIONS": "true", "RUNNER_ENVIRONMENT": "github-hosted",
                               "RUNNER_OS": "macOS", "RUNNER_ARCH": "X64", "ANDROID_HOME": str(sdk)}
                with self.subTest(kind=kind), patch.dict(os.environ, environment, clear=True), \
                        patch.multiple(smoke.platform, system=lambda: "Darwin", machine=lambda: "x86_64"), \
                        patch.object(smoke, "command") as command, patch("sys.stdout", new_callable=io.StringIO):
                    with self.assertRaisesRegex(smoke.SmokeFailure, "existing Intel macOS emulator"):
                        smoke.check_hvf_access()
                    with self.assertRaisesRegex(smoke.SmokeFailure, "existing Intel macOS emulator"):
                        smoke.check_hvf_acceleration(emulator)
                    command.assert_not_called()

    def test_hvf_host_failure_retains_bounded_partial_receipt_and_original_error(self):
        environment = {"GITHUB_ACTIONS": "true", "RUNNER_ENVIRONMENT": "github-hosted",
                       "RUNNER_OS": "macOS", "RUNNER_ARCH": "X64", "ANDROID_HOME": "/sdk",
                       "PYAI_EMULATOR_MODE": "hvf"}
        answers = (subprocess.CompletedProcess([], 1, "x" * 3000, "failure"),
                   subprocess.TimeoutExpired("sysctl", 10, output=b"x" * 3000, stderr=b"partial"),
                   OSError("observed sysctl failure"))
        for answer in answers:
            receipt = {"status": "failed", "cases": [{"status": "not_run"} for _ in smoke.CASE_IDS]}

            def observe(*args, **kwargs):
                self.assertEqual({}, receipt["emulation"]["host"]["support_check"])
                if isinstance(answer, BaseException):
                    raise answer
                return answer

            with self.subTest(answer=type(answer).__name__), patch.dict(os.environ, environment, clear=True), \
                    patch.multiple(smoke.platform, system=lambda: "Darwin", machine=lambda: "x86_64"), \
                    patch.object(Path, "is_file", return_value=True), patch.object(Path, "is_symlink", return_value=False), \
                    patch.object(smoke, "command", side_effect=observe) as command, \
                    patch.object(smoke, "check_kvm_access") as kvm, patch("sys.stdout", new_callable=io.StringIO) as log:
                with self.assertRaises(type(answer) if isinstance(answer, BaseException) else smoke.SmokeFailure) as raised:
                    smoke.emulation_settings(receipt)
            if isinstance(answer, BaseException):
                self.assertIs(answer, raised.exception)
            host = receipt["emulation"]["host"]
            check = host["support_check"]
            self.assertFalse(smoke.hvf_host_passes(host))
            self.assertNotIn("kern_hv_support", host)
            self.assertLessEqual(len(check.get("stdout", "")), 1024)
            self.assertLessEqual(len(check.get("stderr", "")), 1024)
            self.assertIn("HVF host preflight:", log.getvalue())
            self.assertEqual("failed", receipt["status"])
            self.assertTrue(all(case["status"] == "not_run" for case in receipt["cases"]))
            command.assert_called_once_with(["/usr/sbin/sysctl", "-n", "kern.hv_support"], timeout=10, check=False)
            kvm.assert_not_called()
            if isinstance(answer, subprocess.TimeoutExpired):
                self.assertEqual(10, check["command_timeout_seconds"])
                self.assertEqual("x" * 1024, check["stdout"])
                self.assertEqual("partial", check["stderr"])
                self.assertTrue(check["output_truncated"])

    def test_hvf_acceleration_failure_retains_bounded_partial_receipt_and_original_error(self):
        answers = (subprocess.CompletedProcess([], 1, "x" * 3000, "failure"),
                   subprocess.TimeoutExpired("emulator", 30, output=b"x" * 3000, stderr=b"partial"),
                   OSError("observed emulator query failure"))
        for answer in answers:
            observed = {}
            receipt = {"status": "failed", "host_acceleration": observed}

            def observe(*args, **kwargs):
                self.assertIs(observed, receipt["host_acceleration"])
                self.assertEqual({}, observed)
                if isinstance(answer, BaseException):
                    raise answer
                return answer

            with self.subTest(answer=type(answer).__name__), \
                    patch.object(Path, "is_file", return_value=True), patch.object(Path, "is_symlink", return_value=False), \
                    patch.object(smoke, "command", side_effect=observe) as command, \
                    patch("sys.stdout", new_callable=io.StringIO) as log:
                with self.assertRaises(type(answer) if isinstance(answer, BaseException) else smoke.SmokeFailure) as raised:
                    smoke.check_hvf_acceleration(Path("/sdk/emulator/emulator"), observed)
            if isinstance(answer, BaseException):
                self.assertIs(answer, raised.exception)
            self.assertFalse(smoke.hvf_acceleration_passes(observed))
            self.assertLessEqual(len(observed.get("stdout", "")), 2048)
            self.assertLessEqual(len(observed.get("stderr", "")), 2048)
            self.assertEqual("failed", receipt["status"])
            self.assertIn("HVF acceleration preflight:", log.getvalue())
            command.assert_called_once_with(["/sdk/emulator/emulator", "-accel-check"], timeout=30, check=False)
            if isinstance(answer, subprocess.TimeoutExpired):
                self.assertEqual(30, observed["command_timeout_seconds"])
                self.assertEqual("x" * 2048, observed["stdout"])
                self.assertEqual("partial", observed["stderr"])
                self.assertTrue(observed["output_truncated"])

    def test_emulation_environment_removes_inherited_software_opt_in_for_hardware(self):
        inherited = {"ANDROID_I_WANT_MY_TCG": "inherited", "ANDROID_AVD_HOME": "/owned/avd", "OTHER": "retained"}
        before = dict(inherited)
        for mode in ("kvm", "hvf", "software"):
            with self.subTest(mode=mode):
                selected = smoke.emulation_environment(inherited, mode)
                self.assertIsNot(selected, inherited)
                self.assertEqual("/owned/avd", selected["ANDROID_AVD_HOME"])
                self.assertEqual("retained", selected["OTHER"])
                if mode == "software":
                    self.assertEqual("yes", selected["ANDROID_I_WANT_MY_TCG"])
                else:
                    self.assertNotIn("ANDROID_I_WANT_MY_TCG", selected)
                self.assertEqual(before, inherited)
        with self.assertRaises(smoke.SmokeFailure):
            smoke.emulation_environment(inherited, "automatic")
        self.assertEqual(before, inherited)

    def test_receipts_require_consistent_mode_settings_without_an_expected_mode_argument(self):
        receipt = {"source_commit": "a" * 40, "scope": "owned-installed-aosp-emulator", "status": "passed",
                   "unsigned_apk_unchanged": True,
                   "guest_readiness": {"ready": True, "ui_dump_path": smoke.UI_DUMP_PATH,
                                       "last_checks": ready_checks(), "interactive": ready_interactive()},
                   "cases": [{"id": name, "status": "passed"} for name in smoke.CASE_IDS]}
        for value in (None, {}, {"mode": "automatic"}):
            receipt["emulation"] = value
            with self.subTest(value=value), self.assertRaises(smoke.SmokeFailure):
                smoke.complete_receipt(receipt, "a" * 40)
        receipt.pop("emulation")
        with self.assertRaises(smoke.SmokeFailure):
            smoke.complete_receipt(receipt, "a" * 40)
        for mode, accel, budget, opt_in in (("kvm", "on", 300, False), ("software", "off", 900, True)):
            settings = {"mode": mode, "accel_argument": accel, "boot_timeout_seconds": budget,
                        "unsupported_software_opt_in": opt_in}
            receipt["emulation"] = settings
            smoke.complete_receipt(receipt, "a" * 40)
            for field in settings:
                changed = copy.deepcopy(receipt)
                changed["emulation"].pop(field)
                with self.subTest(mode=mode, missing=field), self.assertRaises(smoke.SmokeFailure):
                    smoke.complete_receipt(changed, "a" * 40)
            for field, value in (("accel_argument", "off" if accel == "on" else "on"),
                                 ("boot_timeout_seconds", 900 if budget == 300 else 300),
                                 ("boot_timeout_seconds", 600),
                                 ("unsupported_software_opt_in", not opt_in)):
                changed = copy.deepcopy(receipt)
                changed["emulation"][field] = value
                with self.subTest(mode=mode, field=field), self.assertRaises(smoke.SmokeFailure):
                    smoke.complete_receipt(changed, "a" * 40)

    def test_hvf_receipt_cannot_replace_host_handoff_or_installed_evidence(self):
        receipt = {"source_commit": "a" * 40, "scope": "owned-installed-aosp-emulator", "status": "passed",
                   "unsigned_apk_unchanged": True, "unsigned_apk_sha256": "b" * 64,
                   "expected_unsigned_apk_sha256": "b" * 64,
                   "emulation": {"mode": "hvf", "accel_argument": "on", "boot_timeout_seconds": 600,
                                 "unsupported_software_opt_in": False,
                                 "host": dict(smoke.HVF_HOST_IDENTITY, kern_hv_support="1", support_check={
                                     "exit_code": 0, "stdout": "1\n", "stderr": "", "output_truncated": False})},
                   "host_acceleration": {"exit_code": 0, "stderr": "", "output_truncated": False,
                                         "stdout": "accel:\n0\nHypervisor.Framework OS X Version 15.7\naccel\n"},
                   "guest_readiness": {"ready": True, "ui_dump_path": smoke.UI_DUMP_PATH,
                                       "last_checks": ready_checks(), "interactive": ready_interactive()},
                   "cases": [{"id": name, "status": "passed"} for name in smoke.CASE_IDS]}
        smoke.complete_receipt(receipt, "a" * 40, expected_mode="hvf")
        smoke.complete_receipt(receipt, "a" * 40)
        invalid = []
        for key in ("expected_unsigned_apk_sha256", "unsigned_apk_sha256", "host_acceleration", "emulation"):
            changed = copy.deepcopy(receipt)
            changed.pop(key)
            invalid.append(changed)
        for key, value in (("mode", "software"), ("accel_argument", "off"),
                           ("boot_timeout_seconds", 300), ("boot_timeout_seconds", 900),
                           ("unsupported_software_opt_in", True), ("host", {"kern_hv_support": "1"})):
            changed = copy.deepcopy(receipt)
            changed["emulation"][key] = value
            invalid.append(changed)
        for name in ("support", "acceleration"):
            for missing in ("exit_code", "stdout", "stderr", "output_truncated"):
                changed = copy.deepcopy(receipt)
                target = (changed["emulation"]["host"]["support_check"] if name == "support"
                          else changed["host_acceleration"])
                target.pop(missing)
                invalid.append(changed)
            for key, value in (("exit_code", False), ("output_truncated", True),
                               ("command_timeout_seconds", 30), ("error", "failed")):
                changed = copy.deepcopy(receipt)
                target = (changed["emulation"]["host"]["support_check"] if name == "support"
                          else changed["host_acceleration"])
                target[key] = value
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
            with self.subTest(default_mode_receipt=changed), self.assertRaises(smoke.SmokeFailure):
                smoke.complete_receipt(changed, "a" * 40)

    def test_guest_readiness_requires_all_nine_checks_then_framework_and_home_on_the_verified_target(self):
        receipt, clock = {}, ReadinessClock()
        device = Mock(spec=smoke.Device)
        device.adb.side_effect = [subprocess.CompletedProcess([], 0, expected[-1], "")
                                  for _, _, expected in smoke.GUEST_READINESS_COMMANDS] + interactive_answers()
        with patch.object(smoke.time, "monotonic", clock.monotonic), patch.object(smoke.time, "sleep", clock.sleep):
            smoke.wait_for_guest_ready(device, receipt)
        device.verify_identity.assert_called_once_with()
        self.assertEqual([call("shell", *args, timeout=10, check=False)
                          for _, args, _ in smoke.GUEST_READINESS_COMMANDS] + [
                              call(*smoke.FRAMEWORK_BARRIER, timeout=120, check=False),
                              call("shell", "rm", "-f", smoke.UI_DUMP_PATH, timeout=30, check=False),
                              call("shell", "uiautomator", "dump", smoke.UI_DUMP_PATH, timeout=20, check=False),
                              call("exec-out", "cat", smoke.UI_DUMP_PATH, timeout=30, check=False),
                              call(*smoke.HOME_FOCUS_QUERY, timeout=5, check=False),
                          ], device.adb.call_args_list)
        self.assertTrue(receipt["guest_readiness"]["ready"])
        self.assertEqual(1, receipt["guest_readiness"]["attempts"])
        self.assertEqual(smoke.UI_DUMP_PATH, receipt["guest_readiness"]["ui_dump_path"])
        self.assertEqual(ready_checks(), receipt["guest_readiness"]["last_checks"])
        self.assertNotIn("first_incomplete_checks", receipt["guest_readiness"])
        self.assertTrue(smoke.interactive_readiness_passes(receipt["guest_readiness"]["interactive"]))
        self.assertNotIn("ui_hierarchy_dumps", receipt)
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

    def test_guest_readiness_rechecks_user_state_before_its_single_framework_sequence_without_launching(self):
        receipt, clock = {}, ReadinessClock()
        device = Mock(spec=smoke.Device)
        first = [subprocess.CompletedProcess([], 0, expected[-1], "")
                 for _, _, expected in smoke.GUEST_READINESS_COMMANDS]
        state_index = next(index for index, (name, _, _) in enumerate(smoke.GUEST_READINESS_COMMANDS)
                           if name == "user_state")
        first[state_index] = subprocess.CompletedProcess([], 0, "RUNNING_LOCKED", "")
        second = [subprocess.CompletedProcess([], 0, expected[-1], "")
                  for _, _, expected in smoke.GUEST_READINESS_COMMANDS]
        device.adb.side_effect = first + second + interactive_answers()
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

    def test_interactive_barrier_rejects_zero_exit_without_exact_complete_success(self):
        marker = "Test barrier passed\nFinished application barriers!\n"
        for code, stdout, stderr in (
            (0, "", ""), (0, "Finished application barriers!\n", ""),
            (0, "Test barrier passed\n", ""),
            (0, "Test barrier passed\nGave up waiting for application barriers!\n", ""),
            (0, "Test idle passed\nFinished application barriers!\n", ""),
            (0, "Test barrier passed\n" + marker, ""),
            (0, marker + "Error: diagnostic\n", ""), (0, marker, "diagnostic"),
            (0, marker, "\n"), (1, marker, ""), (False, marker, ""),
            (0, "x" * 20000 + marker, ""),
        ):
            device, readiness = Mock(spec=smoke.Device), {}
            device.adb.return_value = subprocess.CompletedProcess([], code, stdout, stderr)
            with self.subTest(code=code, stdout=stdout[-100:], stderr=stderr), \
                    patch.object(smoke.time, "monotonic", return_value=0), \
                    self.assertRaisesRegex(smoke.SmokeFailure, "barriers did not complete"):
                smoke.establish_interactive_readiness(device, readiness, 180)
            device.adb.assert_called_once_with(*smoke.FRAMEWORK_BARRIER, timeout=120, check=False)
            state = readiness["interactive"]
            self.assertFalse(state["ready"])
            self.assertEqual(stdout[-16384:], state["barrier"]["stdout_tail"])
            self.assertEqual(len(stdout) > 16384, state["barrier"]["stdout_tail_truncated"])
            self.assertNotIn("ui_hierarchy_observations", state)
            device.launch.assert_not_called()

    def test_interactive_barrier_accepts_ordered_pending_work_then_completed_home_evidence(self):
        for newline in ("\n", "\r\n"):
            device, readiness = Mock(spec=smoke.Device), {}
            answers = interactive_answers()
            answers[0].stdout = newline.join([
                "Test barrier failed due to 1101:com.android.systemui/u0a122 1 active, 2 pending",
                "Test barrier passed", "Waiting for application barriers, at 1 of 3...",
                "Finished application barriers!", ""])
            answers[2].returncode = 137  # The existing exact-marker/fresh-XML path remains required.
            device.adb.side_effect = answers
            with self.subTest(newline=newline), patch.object(smoke.time, "monotonic", return_value=0):
                smoke.establish_interactive_readiness(device, readiness, 180)
            state = readiness["interactive"]
            self.assertTrue(smoke.interactive_readiness_passes(state))
            self.assertEqual(5, device.adb.call_count)
            self.assertEqual({"attempted": 1, "completed": 1, "exit_137": 1}, state["ui_hierarchy_dumps"])
            self.assertEqual(["com.android.launcher3"], state["home_hierarchy"]["packages"])
            self.assertEqual(1, state["home_hierarchy"]["visible_home_nodes"])
            self.assertEqual(hashlib.sha256(answers[3].stdout.encode()).hexdigest(),
                             state["home_hierarchy"]["read_text_sha256"])
            device.launch.assert_not_called()

    def test_interactive_sequence_and_recorded_command_budgets_share_one_readiness_deadline(self):
        device, readiness, clock = Mock(spec=smoke.Device), {}, ReadinessClock()
        answers = iter(zip(interactive_answers(), (120, 150, 170, 178, 179)))

        def answer(*args, **kwargs):
            result, clock.now = next(answers)
            return result

        device.adb.side_effect = answer
        with patch.object(smoke.time, "monotonic", clock.monotonic):
            smoke.establish_interactive_readiness(device, readiness, 180)
        self.assertEqual([120, 30, 20, 10, 2], [c.kwargs["timeout"] for c in device.adb.call_args_list])
        state = readiness["interactive"]
        self.assertTrue(state["ready"])
        self.assertEqual(179, state["seconds"])
        commands = state["ui_hierarchy_observations"]["last_attempt"]["commands"]
        self.assertEqual([30, 20, 10], [commands[name]["timeout_seconds"] for name in ("remove", "dump", "read")])
        self.assertEqual(2, state["focus"]["timeout_seconds"])

    def test_interactive_timeout_retains_partial_barrier_and_never_proceeds_or_retries(self):
        device, readiness, clock = Mock(spec=smoke.Device), {}, ReadinessClock()
        original = subprocess.TimeoutExpired(["adb"], 1.5,
                                             output=b"x" * 20000 + b"Test barrier passed\n", stderr=b"partial")

        def timeout(*args, **kwargs):
            clock.now += kwargs["timeout"]
            raise original

        device.adb.side_effect = timeout
        with patch.object(smoke.time, "monotonic", clock.monotonic), self.assertRaises(subprocess.TimeoutExpired) as raised:
            smoke.establish_interactive_readiness(device, readiness, 1.5)
        self.assertIs(original, raised.exception)
        device.adb.assert_called_once_with(*smoke.FRAMEWORK_BARRIER, timeout=1.5, check=False)
        state = readiness["interactive"]
        self.assertFalse(state["ready"])
        self.assertEqual(1.5, state["seconds"])
        self.assertEqual(1.5, state["barrier"]["command_timeout_seconds"])
        self.assertEqual(16384, len(state["barrier"]["stdout_tail"]))
        self.assertTrue(state["barrier"]["stdout_tail_truncated"])
        self.assertEqual("partial", state["barrier"]["stderr"]["prefix"])
        self.assertNotIn("ui_hierarchy_observations", state)

    def test_interactive_success_arriving_at_deadline_cannot_accept_or_start_another_command(self):
        for stop_at in (0, 3, 4):
            device, readiness, clock = Mock(spec=smoke.Device), {}, ReadinessClock()
            answers = iter(enumerate(interactive_answers()))

            def answer(*args, **kwargs):
                index, result = next(answers)
                if index == stop_at:
                    clock.now = 1
                return result

            device.adb.side_effect = answer
            with self.subTest(stop_at=stop_at), patch.object(smoke.time, "monotonic", clock.monotonic), \
                    self.assertRaises(smoke.SmokeFailure):
                smoke.establish_interactive_readiness(device, readiness, 1)
            self.assertFalse(readiness["interactive"]["ready"])
            self.assertEqual(stop_at + 1, device.adb.call_count)

    def test_interactive_empty_dump_137_fails_before_read_focus_or_first_app_case(self):
        device, receipt, clock = Mock(spec=smoke.Device), {"cases": [{"id": c, "status": "not_run"}
                                                                  for c in smoke.CASE_IDS]}, ReadinessClock()
        answers = interactive_answers()
        answers[2] = subprocess.CompletedProcess([], 137, "", "")
        device.adb.side_effect = [subprocess.CompletedProcess([], 0, expected[-1], "")
                                  for _, _, expected in smoke.GUEST_READINESS_COMMANDS] + answers
        with patch.object(smoke.time, "monotonic", clock.monotonic), self.assertRaisesRegex(smoke.SmokeFailure, "dump failed"):
            smoke.wait_for_guest_ready(device, receipt)
        self.assertEqual(12, device.adb.call_count)
        self.assertFalse(receipt["guest_readiness"]["ready"])
        state = receipt["guest_readiness"]["interactive"]
        attempt = state["ui_hierarchy_observations"]["first_failed_attempt"]
        self.assertEqual("dump", attempt["stage"])
        self.assertFalse(attempt["dump_completion_passed"])
        self.assertEqual({"remove", "dump"}, set(attempt["commands"]))
        self.assertTrue(all(c["status"] == "not_run" for c in receipt["cases"]))
        self.assertNotIn("ui_hierarchy_observations", receipt)
        device.launch.assert_not_called()
        device.shell.assert_not_called()

    def test_interactive_home_xml_cannot_override_anr_wrong_user_or_ambiguous_focus(self):
        clean = interactive_answers()[4].stdout
        for result in (
            subprocess.CompletedProcess([], 0, clean.replace(smoke.HOME_WINDOW, "Application Not Responding: com.android.systemui"), ""),
            subprocess.CompletedProcess([], 0, clean.replace("u0 ", "u10 "), ""),
            subprocess.CompletedProcess([], 0, clean + clean, ""),
            subprocess.CompletedProcess([], 0, clean.splitlines()[0] + "\n", ""),
            subprocess.CompletedProcess([], 0, clean + "*** SERVICE 'activity' DUMP TIMEOUT (3000ms) EXPIRED ***\n", ""),
            subprocess.CompletedProcess([], 0, clean + "Error dumping service info: failed\n", ""),
            subprocess.CompletedProcess([], 0, clean, "DUMP TIMEOUT"),
            subprocess.CompletedProcess([], 1, clean, ""),
            subprocess.CompletedProcess([], False, clean, ""),
            subprocess.CompletedProcess([], 0, "x" * 70000 + clean, ""),
        ):
            device, readiness = Mock(spec=smoke.Device), {}
            answers = interactive_answers()
            answers[4] = result
            device.adb.side_effect = answers
            with self.subTest(stdout=result.stdout[-250:], stderr=result.stderr), \
                    patch.object(smoke.time, "monotonic", return_value=0), self.assertRaises(smoke.SmokeFailure):
                smoke.establish_interactive_readiness(device, readiness, 180)
            self.assertFalse(readiness["interactive"]["ready"])
            self.assertEqual(5, device.adb.call_count)
            self.assertEqual(1, readiness["interactive"]["home_hierarchy"]["visible_home_nodes"])

    def test_interactive_home_focus_cannot_override_missing_visible_home_or_unexpected_hierarchy(self):
        clean = interactive_answers()[3].stdout
        for raw in (clean.replace("com.android.launcher3", smoke.PACKAGE),
                    clean.replace("com.android.launcher3", "android"),
                    clean.replace('rotation="0"', 'rotation="1"'),
                    clean.replace("[0,0][100,100]", "[0,0][0,0]"),
                    clean.replace("</hierarchy>", '<node package="android" bounds="[0,0][100,100]" /></hierarchy>')):
            device, readiness = Mock(spec=smoke.Device), {}
            answers = interactive_answers()
            answers[3].stdout = raw
            device.adb.side_effect = answers
            with self.subTest(raw=raw), patch.object(smoke.time, "monotonic", return_value=0), \
                    self.assertRaises(smoke.SmokeFailure):
                smoke.establish_interactive_readiness(device, readiness, 180)
            self.assertFalse(readiness["interactive"]["ready"])

    def test_interactive_receipt_requires_complete_bound_commands_and_home_evidence(self):
        valid = ready_interactive()
        invalid = [None, {}, {"ready": True}]
        for key in ("barrier", "focus", "home_hierarchy", "ui_hierarchy_observations", "serial", "phase", "home_package"):
            changed = copy.deepcopy(valid)
            changed.pop(key)
            invalid.append(changed)
        for key, value in (("ready", False), ("serial", "emulator-5556"), ("phase", "after_installation"), ("error", "failed")):
            changed = copy.deepcopy(valid)
            changed[key] = value
            invalid.append(changed)
        for path, value in (
            (("barrier", "command_timeout_seconds"), 120),
            (("focus", "stdout_tail"), ""),
            (("home_hierarchy", "read_text_sha256"), "0" * 64),
            (("home_hierarchy", "visible_home_nodes"), True),
            (("ui_hierarchy_observations", "attempted"), 2),
            (("ui_hierarchy_observations", "first_failed_attempt"), {}),
            (("ui_hierarchy_observations", "last_attempt", "commands"), None),
            (("ui_hierarchy_observations", "last_attempt", "commands", "read"), None),
            (("ui_hierarchy_observations", "last_attempt", "commands", "read", "exit_code"), 1),
            (("ui_hierarchy_observations", "last_attempt", "commands", "dump", "exit_code"), False),
            (("ui_hierarchy_observations", "last_attempt", "commands", "dump", "stdout", "prefix"), ""),
        ):
            changed = copy.deepcopy(valid)
            target = changed
            for key in path[:-1]:
                target = target[key]
            target[path[-1]] = value
            invalid.append(changed)
        for state in invalid:
            with self.subTest(state=state):
                self.assertFalse(smoke.interactive_readiness_passes(state))

    def test_interactive_receipt_rejects_conflicting_stream_metadata(self):
        valid = ready_interactive()
        self.assertTrue(smoke.interactive_readiness_passes(valid))
        for size in (1199, 1200, 1201, 2399, 2400, 2401, 4096):
            answers = interactive_answers()
            home = answers[3].stdout
            answers[3].stdout = home.replace("</hierarchy>", " " * (size - len(home)) + "</hierarchy>")
            device, readiness = Mock(spec=smoke.Device), {}
            device.adb.side_effect = answers
            with self.subTest(valid_xml_characters=size), patch.object(smoke.time, "monotonic", return_value=0):
                smoke.establish_interactive_readiness(device, readiness, 180)
                self.assertTrue(smoke.interactive_readiness_passes(readiness["interactive"]))
        for path, value in (
            (("barrier", "stderr", "prefix"), "service failure"),
            (("focus", "stderr", "text_sha256"), "f" * 64),
            (("ui_hierarchy_observations", "last_attempt", "commands", "dump", "stdout", "text_sha256"), "0" * 64),
            (("ui_hierarchy_observations", "last_attempt", "commands", "dump", "stdout", "text_characters"), 0),
            (("ui_hierarchy_observations", "last_attempt", "commands", "read", "stdout", "available"), False),
            (("ui_hierarchy_observations", "last_attempt", "commands", "read", "stdout", "text_characters"), 0),
            (("ui_hierarchy_observations", "last_attempt", "commands", "read", "stderr", "prefix"), "read failed"),
        ):
            changed = copy.deepcopy(valid)
            target = changed
            for key in path[:-1]:
                target = target[key]
            target[path[-1]] = value
            with self.subTest(path=path):
                self.assertFalse(smoke.interactive_readiness_passes(changed))

    def test_interactive_receipt_rejects_misidentified_or_failed_only_probe(self):
        valid = ready_interactive()
        self.assertTrue(smoke.interactive_readiness_passes(valid))
        for key, value in (
            ("sequence", 2),
            ("error", {"type": "ParseError", "message": "syntax error: line 1, column 0"}),
        ):
            changed = copy.deepcopy(valid)
            changed["ui_hierarchy_observations"]["last_attempt"][key] = value
            with self.subTest(key=key):
                self.assertFalse(smoke.interactive_readiness_passes(changed))

    def test_readiness_failure_diagnostics_are_distinct_and_do_not_rescue_unrun_cases(self):
        device = Mock(spec=smoke.Device)
        device.adb.return_value = subprocess.CompletedProcess([], 0, "observed", "")
        receipt = {"guest_readiness": {"ready": False}, "error": "original readiness failure",
                   "cases": [{"id": c, "status": "not_run"} for c in smoke.CASE_IDS]}
        with patch.object(smoke.time, "monotonic", return_value=0):
            smoke.retain_case_failure_state(device, receipt, phase="after_guest_readiness_failure")
        self.assertNotIn("case_failure_observations", receipt)
        self.assertEqual("after_guest_readiness_failure", receipt["guest_readiness_failure_observations"]["phase"])
        self.assertEqual("original readiness failure", receipt["error"])
        self.assertFalse(receipt["guest_readiness"]["ready"])
        self.assertTrue(all(c["status"] == "not_run" for c in receipt["cases"]))
        self.assertEqual(8, device.adb.call_count)
        self.assertEqual([
            call("shell", "dumpsys", "-t", "3", "activity", "lastanr", timeout=5, check=False),
            call("shell", "dumpsys", "-t", "3", "activity", "lastanr-traces", timeout=5, check=False),
        ], device.adb.call_args_list[1:3])
        state = receipt["guest_readiness_failure_observations"]
        self.assertEqual("latest_global_records_not_automatically_package_bound", state["anr_record_scope"])
        self.assertEqual(["framework_log", "last_anr_state", "last_anr_traces", "package_pid",
                          "package_exit_history", "activity_stack", "package_processes", "low_memory_killer"],
                         list(state["checks"]))
        for name in ("last_anr_state", "last_anr_traces"):
            self.assertEqual("observed", state["checks"][name]["stdout_prefix"])
            self.assertEqual("observed", state["checks"][name]["stdout_tail"])
            self.assertFalse(state["checks"][name]["stdout_prefix_truncated"])
        self.assertTrue(all(c.kwargs["timeout"] == 5 for c in device.adb.call_args_list))
        device.launch.assert_not_called()

    def test_readiness_anr_records_keep_bounded_identity_and_tail_without_claiming_completeness(self):
        partial = b"ANR identity and first stack\n" + b"x" * 300000 + b"\xfffinal frame"
        decoded = partial.decode("utf-8", errors="replace")
        for failure in (subprocess.TimeoutExpired("dumpsys", 5, output=partial, stderr=b"partial error"),
                        smoke.SmokeFailure("Owned emulator process exited")):
            with self.subTest(failure=type(failure).__name__):
                device = Mock(spec=smoke.Device)
                device.adb.side_effect = [subprocess.CompletedProcess([], 0, "framework", ""),
                                         subprocess.CompletedProcess([], 1, decoded, "state unavailable"),
                                         failure] + [subprocess.CompletedProcess([], 0, "remaining", "")] * 5
                receipt = {"error": "original HOME failure", "guest_readiness": {"ready": False},
                           "cases": [{"id": c, "status": "not_run"} for c in smoke.CASE_IDS]}
                with patch.object(smoke.time, "monotonic", return_value=0):
                    smoke.retain_case_failure_state(device, receipt, phase="after_guest_readiness_failure")
                checks = receipt["guest_readiness_failure_observations"]["checks"]
                observed = checks["last_anr_state"]
                self.assertEqual(1, observed["exit_code"])
                self.assertEqual("state unavailable", observed["stderr"]["prefix"])
                for name in ("last_anr_state", "last_anr_traces"):
                    item = checks[name]
                    if name == "last_anr_traces" and isinstance(failure, smoke.SmokeFailure):
                        self.assertEqual("Owned emulator process exited", item["error"])
                        self.assertNotIn("stdout_prefix", item)
                        self.assertNotIn("exit_code", item)
                        continue
                    self.assertEqual(131072, item["stdout_prefix_retained_character_limit"])
                    self.assertEqual(decoded[:131072], item["stdout_prefix"])
                    self.assertEqual(decoded[-131072:], item["stdout_tail"])
                    self.assertTrue(item["stdout_prefix_truncated"])
                    self.assertTrue(item["stdout_tail_truncated"])
                    self.assertEqual(len(decoded), item["stdout"]["text_characters"])
                    self.assertEqual(hashlib.sha256(decoded.encode("utf-8")).hexdigest(), item["stdout"]["text_sha256"])
                    if name == "last_anr_traces":
                        self.assertEqual(5, item["command_timeout_seconds"])
                        self.assertTrue(item["stdout"]["decoded_from_bytes"])
                        self.assertEqual("partial error", item["stderr"]["prefix"])
                        self.assertNotIn("exit_code", item)
                self.assertEqual("remaining", checks["low_memory_killer"]["stdout_tail"])
                self.assertNotIn("stdout_prefix", checks["framework_log"])
                self.assertEqual(8, device.adb.call_count)
                self.assertEqual(8, len({c.args for c in device.adb.call_args_list}))
                self.assertEqual("original HOME failure", receipt["error"])
                self.assertFalse(receipt["guest_readiness"]["ready"])
                self.assertTrue(all(c["status"] == "not_run" for c in receipt["cases"]))
                device.launch.assert_not_called()

    def test_readiness_anr_records_share_the_existing_deadline_and_list_unattempted_reads(self):
        receipt, clock, device = {"error": "original HOME failure"}, ReadinessClock(), Mock(spec=smoke.Device)

        def read(*args, **kwargs):
            clock.now += min(4.5, kwargs["timeout"])
            raise subprocess.TimeoutExpired(args, kwargs["timeout"], output=b"partial", stderr=b"offline")

        device.adb.side_effect = read
        with patch.object(smoke.time, "monotonic", clock.monotonic):
            smoke.retain_case_failure_state(device, receipt, phase="after_guest_readiness_failure")
        state = receipt["guest_readiness_failure_observations"]
        self.assertEqual(20, state["seconds"])
        self.assertTrue(state["deadline_reached"])
        self.assertEqual([5, 5, 5, 5, 2], [c.kwargs["timeout"] for c in device.adb.call_args_list])
        self.assertEqual(["activity_stack", "package_processes", "low_memory_killer"], state["unattempted_checks"])
        self.assertEqual("original HOME failure", receipt["error"])
        for name in ("last_anr_state", "last_anr_traces"):
            self.assertEqual("partial", state["checks"][name]["stdout_prefix"])
            self.assertEqual("partial", state["checks"][name]["stdout_tail"])
            self.assertFalse(state["checks"][name]["stdout_prefix_truncated"])
            self.assertNotIn("exit_code", state["checks"][name])
        device.launch.assert_not_called()

    def test_actual_readiness_failure_handler_preserves_original_error_when_diagnostics_raise(self):
        import ast
        import inspect

        tree = ast.parse(inspect.getsource(smoke.main))
        handler = next(n for n in ast.walk(tree) if isinstance(n, ast.Try)
                       and len(n.body) == 1 and isinstance(n.body[0], ast.Expr)
                       and isinstance(n.body[0].value, ast.Call)
                       and isinstance(n.body[0].value.func, ast.Name)
                       and n.body[0].value.func.id == "wait_for_guest_ready")
        program = compile(ast.fix_missing_locations(ast.Module(body=[handler], type_ignores=[])), "readiness-handler", "exec")
        for diagnostic_error in (None, RuntimeError("diagnostic failure")):
            original, receipt, device = smoke.SmokeFailure("original readiness failure"), {}, object()
            readiness = Mock(side_effect=original)
            diagnostic = Mock(side_effect=diagnostic_error)
            namespace = {"wait_for_guest_ready": readiness, "retain_case_failure_state": diagnostic,
                         "bounded_text": smoke.bounded_text, "device": device, "receipt": receipt}
            with self.subTest(diagnostic_error=diagnostic_error), self.assertRaises(smoke.SmokeFailure) as raised:
                exec(program, namespace)
            self.assertIs(original, raised.exception)
            diagnostic.assert_called_once_with(device, receipt, phase="after_guest_readiness_failure")
            self.assertEqual(diagnostic_error is not None, "guest_readiness_diagnostics_error" in receipt)

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
                with patch.object(smoke.Ui, "checkpoint", side_effect=smoke.SmokeFailure("Capture failed")), \
                        patch.object(smoke, "retain_case_failure_state") as state:
                    with self.assertRaises(smoke.SmokeFailure) as raised:
                        smoke.exercise(device, Path(temporary), receipt)
                state.assert_called_once_with(device, receipt)
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

    def test_failed_case_observations_read_only_owned_package_and_framework_state(self):
        receipt = {"status": "failed", "error": "original launch timeout"}
        device = Mock(spec=smoke.Device)
        device.adb.return_value = subprocess.CompletedProcess([], 1, "diagnostic output", "unavailable")
        smoke.retain_case_failure_state(device, receipt)
        calls = device.adb.call_args_list
        self.assertEqual(6, len(calls))
        log_args = calls[0].args
        self.assertEqual(("logcat", "-b", "main", "-b", "system", "-b", "crash", "-b", "events",
                          "-d", "-v", "threadtime", "-t", "2000"), log_args[:14])
        self.assertEqual("*:S", log_args[-1])
        for tag in ("lowmemorykiller:I", "am_proc_start:I", "am_proc_died:I", "am_kill:I", "am_anr:I"):
            self.assertIn(tag, log_args)
        self.assertEqual([
            call("shell", "pidof", smoke.PACKAGE, timeout=5, check=False),
            call("shell", "dumpsys", "-t", "3", "activity", "exit-info", smoke.PACKAGE,
                 timeout=5, check=False),
            call("shell", "dumpsys", "-t", "3", "activity", "activities", timeout=5, check=False),
            call("shell", "dumpsys", "-t", "3", "activity", "processes", smoke.PACKAGE,
                 timeout=5, check=False),
            call("shell", "dumpsys", "-t", "3", "activity", "lmk", timeout=5, check=False),
        ], calls[1:])
        self.assertEqual({"timeout": 5, "check": False}, calls[0].kwargs)
        state = receipt["case_failure_observations"]
        self.assertEqual("after_failure_checkpoint", state["phase"])
        self.assertEqual(smoke.SERIAL, state["serial"])
        self.assertEqual(smoke.PACKAGE, state["package"])
        self.assertEqual("original launch timeout", receipt["error"])
        self.assertEqual("failed", receipt["status"])
        for observed in state["checks"].values():
            self.assertEqual(1, observed["exit_code"])
            self.assertEqual("diagnostic output", observed["stdout_tail"])
            self.assertEqual("unavailable", observed["stderr"]["prefix"])
            self.assertFalse(observed["stdout_tail_truncated"])
        device.launch.assert_not_called()
        device.shell.assert_not_called()

    def test_failed_case_observations_bound_text_and_keep_timeout_partials(self):
        receipt = {}
        device = Mock(spec=smoke.Device)
        partial = b"x" * 140000 + b"\xfflast"
        error = subprocess.TimeoutExpired("logcat", 5, output=partial, stderr=b"partial error")
        device.adb.side_effect = [error] + [subprocess.CompletedProcess([], 0, "y" * 40000, "")] * 5
        smoke.retain_case_failure_state(device, receipt)
        observed = receipt["case_failure_observations"]["checks"]["framework_log"]
        decoded = partial.decode("utf-8", errors="replace")
        self.assertEqual(5, observed["command_timeout_seconds"])
        self.assertNotIn("exit_code", observed)
        self.assertEqual(decoded[-131072:], observed["stdout_tail"])
        self.assertTrue(observed["stdout_tail_truncated"])
        self.assertTrue(observed["stdout"]["decoded_from_bytes"])
        self.assertEqual(hashlib.sha256(decoded.encode("utf-8")).hexdigest(), observed["stdout"]["text_sha256"])
        self.assertEqual("partial error", observed["stderr"]["prefix"])
        for name, check in receipt["case_failure_observations"]["checks"].items():
            if name != "framework_log":
                self.assertEqual("y" * 32768, check["stdout_tail"])
                self.assertTrue(check["stdout_tail_truncated"])

    def test_failed_case_observations_share_one_deadline_and_list_unattempted_reads(self):
        for delay, expected_timeouts, skipped in (
                (5, [5, 5, 5, 5], ["package_processes", "low_memory_killer"]),
                (4.5, [5, 5, 5, 5, 2], ["low_memory_killer"])):
            with self.subTest(delay=delay):
                receipt, clock = {"status": "failed", "error": "original"}, ReadinessClock()
                device = Mock(spec=smoke.Device)

                def timeout(*args, **kwargs):
                    clock.now += min(delay, kwargs["timeout"])
                    raise subprocess.TimeoutExpired(args, kwargs["timeout"], output=b"partial", stderr=b"offline")

                device.adb.side_effect = timeout
                with patch.object(smoke.time, "monotonic", clock.monotonic):
                    smoke.retain_case_failure_state(device, receipt)
                state = receipt["case_failure_observations"]
                self.assertEqual(20, state["seconds"])
                self.assertTrue(state["deadline_reached"])
                self.assertEqual(skipped, state["unattempted_checks"])
                self.assertEqual(expected_timeouts, [item.kwargs["timeout"] for item in device.adb.call_args_list])
                self.assertEqual("original", receipt["error"])
                for observed in state["checks"].values():
                    self.assertEqual("partial", observed["stdout_tail"])
                    self.assertEqual("offline", observed["stderr"]["prefix"])
                    self.assertNotIn("exit_code", observed)

    def test_failed_case_observations_keep_owned_process_errors_without_retries(self):
        receipt = {"status": "failed", "error": "original"}
        device = Mock(spec=smoke.Device)
        device.adb.side_effect = smoke.SmokeFailure("Owned emulator process exited")
        smoke.retain_case_failure_state(device, receipt)
        self.assertEqual(6, device.adb.call_count)
        self.assertEqual(6, len({item.args for item in device.adb.call_args_list}))
        self.assertTrue(all(item["error"] == "Owned emulator process exited"
                            for item in receipt["case_failure_observations"]["checks"].values()))
        self.assertEqual("original", receipt["error"])
        device.launch.assert_not_called()

    def test_case_state_follows_failed_checkpoint_without_replacing_initial_log_or_failure(self):
        with tempfile.TemporaryDirectory() as temporary:
            receipt = {"cases": [{"id": name, "status": "not_run"} for name in smoke.CASE_IDS]}
            device = Mock(spec=smoke.Device)
            original = smoke.SmokeFailure("Original launch timeout")
            device.launch.side_effect = original
            phases = []

            def read(*args, **kwargs):
                if args[0] == "logcat":
                    phases.append("initial_log" if "400" in args else "later_log")
                return subprocess.CompletedProcess([], 0, phases[-1], "")

            def checkpoint(name):
                self.assertEqual("failure", name)
                self.assertEqual("initial_log", (Path(temporary) / "failure-logcat.txt").read_text())
                phases.append("checkpoint")
                raise smoke.SmokeFailure("Empty dump 137")

            device.adb.side_effect = read
            with patch.object(smoke.Ui, "checkpoint", side_effect=checkpoint):
                with self.assertRaises(smoke.SmokeFailure) as raised:
                    smoke.exercise(device, Path(temporary), receipt)
            self.assertIs(original, raised.exception)
            self.assertEqual(["initial_log", "checkpoint", "later_log"], phases)
            self.assertEqual("initial_log", (Path(temporary) / "failure-logcat.txt").read_text())
            self.assertEqual("later_log", receipt["case_failure_observations"]["checks"]["framework_log"]["stdout_tail"])
            self.assertEqual("Empty dump 137", receipt["failure_checkpoint_error"])
            self.assertEqual("failed", receipt["cases"][0]["status"])
            self.assertTrue(all(row["status"] == "not_run" for row in receipt["cases"][1:]))

    def test_unexpected_case_state_error_cannot_mask_original_case_failure(self):
        receipt = {"cases": [{"id": name, "status": "not_run"} for name in smoke.CASE_IDS]}
        device = Mock(spec=smoke.Device)
        original = smoke.SmokeFailure("Original launch timeout")
        device.launch.side_effect = original

        def fail_after_observation(device, receipt):
            receipt["case_failure_observations"] = {"phase": "after_failure_checkpoint"}
            raise OSError("Unexpected diagnostic error")

        with patch.object(smoke, "retain_failure_log"), patch.object(smoke.Ui, "checkpoint"), \
                patch.object(smoke, "retain_case_failure_state", side_effect=fail_after_observation):
            with self.assertRaises(smoke.SmokeFailure) as raised:
                smoke.exercise(device, Path("unused"), receipt)
        self.assertIs(original, raised.exception)
        self.assertEqual("Unexpected diagnostic error", receipt["case_failure_observations"]["error"])
        self.assertEqual("after_failure_checkpoint", receipt["case_failure_observations"]["phase"])
        self.assertEqual("failed", receipt["cases"][0]["status"])
        self.assertTrue(all(row["status"] == "not_run" for row in receipt["cases"][1:]))

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
                    subprocess.CompletedProcess([], 0, "", ""),
                    subprocess.CompletedProcess([], status, "UI hierchary dumped to: " + path + "\n", ""),
                    subprocess.CompletedProcess([], 0, raw, ""),
                ]
                root = smoke.Ui(device, Path("unused"), receipt).snapshot()
                self.assertEqual("hierarchy", root.tag)
                self.assertEqual([
                    call.adb("shell", "rm", "-f", path, timeout=30, check=False),
                    call.adb("shell", "uiautomator", "dump", path, timeout=20, check=False),
                    call.adb("exec-out", "cat", path, timeout=30, check=False),
                ], device.mock_calls)
                self.assertEqual({"attempted": 1, "completed": 1, "exit_137": int(status == 137)},
                                 receipt["ui_hierarchy_dumps"])

    def test_failed_prior_dump_removal_cannot_reuse_stale_xml(self):
        device, receipt = Mock(spec=smoke.Device), {}
        original = smoke.SmokeFailure("Dump removal failed")
        device.adb.side_effect = original
        with self.assertRaisesRegex(smoke.SmokeFailure, "removal failed"):
            smoke.Ui(device, Path("unused"), receipt).snapshot()
        device.adb.assert_called_once_with("shell", "rm", "-f", smoke.UI_DUMP_PATH, timeout=30, check=False)
        self.assertEqual(0, receipt["ui_hierarchy_dumps"]["completed"])
        self.assertEqual(0, receipt["ui_hierarchy_dumps"]["attempted"])
        self.assertEqual("remove", receipt["ui_hierarchy_observations"]["first_failed_attempt"]["stage"])

    def test_unrelated_dump_failure_or_inexact_completion_is_rejected(self):
        marker = "UI hierchary dumped to: " + smoke.UI_DUMP_PATH
        for status, stdout, stderr in (
            (1, marker, ""), (137, "", ""), (137, marker, "Killed"),
            (137, marker + "\nERROR: dump failed", ""),
            (137, "UI hierchary dumped to: /sdcard/pyai-smoke-window.xml", ""),
            (137, "UI hierchary dumped to: /sdcard/another.xml", ""),
            (0, "", ""), (0, "ERROR: could not get idle state.", ""),
            (0, marker, "ERROR: null root node returned"),
            (0, marker + "\nERROR: dump failed", ""),
            (0, "UI hierchary dumped to: /sdcard/another.xml", ""),
        ):
            with self.subTest(status=status, stdout=stdout, stderr=stderr):
                device, receipt = Mock(spec=smoke.Device), {}
                device.adb.side_effect = [subprocess.CompletedProcess([], 0, "", ""),
                                           subprocess.CompletedProcess([], status, stdout, stderr)]
                with self.assertRaisesRegex(smoke.SmokeFailure, "UI hierarchy dump failed"):
                    smoke.Ui(device, Path("unused"), receipt).snapshot()
                self.assertEqual(2, device.adb.call_count)
                self.assertEqual(0, receipt["ui_hierarchy_dumps"]["completed"])
                failed = receipt["ui_hierarchy_observations"]["first_failed_attempt"]
                self.assertEqual("dump", failed["stage"])
                self.assertFalse(failed["dump_completion_passed"])
                self.assertNotIn("read", failed["commands"])
                self.assertEqual(stdout, failed["commands"]["dump"]["stdout"]["prefix"])

    def test_completion_message_cannot_replace_missing_truncated_or_invalid_xml(self):
        marker = "UI hierchary dumped to: " + smoke.UI_DUMP_PATH
        for raw in (None, "", '<hierarchy rotation="0"><node>',
                    '<other><node /></other>', '<hierarchy rotation="0" />',
                    '<hierarchy rotation="9"><node /></hierarchy>'):
            with self.subTest(raw=raw):
                device, receipt = Mock(spec=smoke.Device), {}
                read = (smoke.SmokeFailure("Fresh dump missing") if raw is None else
                        subprocess.CompletedProcess([], 0, raw, ""))
                device.adb.side_effect = [subprocess.CompletedProcess([], 0, "", ""),
                                           subprocess.CompletedProcess([], 137, marker, ""), read]
                with self.assertRaises((smoke.SmokeFailure, ET.ParseError)):
                    smoke.Ui(device, Path("unused"), receipt).snapshot()
                self.assertEqual(0, receipt["ui_hierarchy_dumps"]["completed"])
                self.assertEqual(0, receipt["ui_hierarchy_dumps"]["exit_137"])

    def test_capture_keeps_first_parse_input_separate_from_later_valid_hierarchies(self):
        device, receipt = Mock(spec=smoke.Device), {}
        marker = "UI hierchary dumped to: " + smoke.UI_DUMP_PATH
        valid = '<hierarchy rotation="0"><node package="' + smoke.PACKAGE + '" /></hierarchy>'
        malformed = "unexpected leading output\n" + valid
        results = lambda raw: [subprocess.CompletedProcess([], 0, "", ""),
                               subprocess.CompletedProcess([], 0, marker, ""),
                               subprocess.CompletedProcess([], 0, raw, "")]
        device.adb.side_effect = results(malformed) + results(valid) * 9
        try:
            ET.fromstring(malformed)
        except ET.ParseError as error:
            original = error
        ui = smoke.Ui(device, Path("unused"), receipt)
        with patch.object(smoke.ET, "fromstring", side_effect=original) as parse:
            with self.assertRaises(ET.ParseError) as raised:
                ui.snapshot()
            self.assertIs(original, raised.exception)
            parse.assert_called_once_with(malformed)
        for _ in range(9):
            self.assertEqual("hierarchy", ui.snapshot().tag)
        observed = receipt["ui_hierarchy_observations"]
        failed = observed["first_failed_attempt"]
        self.assertEqual(1, failed["sequence"])
        self.assertEqual("parse", failed["stage"])
        self.assertFalse(failed["completed"])
        self.assertEqual({"type": "ParseError", "message": str(original), "code": original.code,
                          "position": original.position}, failed["error"])
        raw = failed["commands"]["read"]["stdout"]
        self.assertEqual(malformed, raw["prefix"])
        self.assertEqual(malformed, raw["suffix"])
        self.assertEqual(len(malformed), raw["text_characters"])
        self.assertEqual(hashlib.sha256(malformed.encode("utf-8")).hexdigest(), raw["text_sha256"])
        self.assertFalse(raw["edges_truncated"])
        self.assertEqual(10, observed["attempted"])
        self.assertEqual(list(range(3, 11)), [row["sequence"] for row in observed["recent_attempts"]])
        self.assertEqual(10, observed["last_attempt"]["sequence"])
        self.assertTrue(observed["last_attempt"]["completed"])
        self.assertNotIn("error", observed["last_attempt"])
        self.assertEqual({"attempted": 10, "completed": 9, "exit_137": 0}, receipt["ui_hierarchy_dumps"])
        self.assertEqual(30, device.adb.call_count)

    def test_capture_timeouts_and_process_errors_keep_bounded_original_observations(self):
        marker = "UI hierchary dumped to: " + smoke.UI_DUMP_PATH
        for index, (stage, budget) in enumerate((("remove", 30), ("dump", 20), ("read", 30))):
            for original in (subprocess.TimeoutExpired("adb", budget, output=b"\xff" + b"x" * 5000,
                                                       stderr=b"stderr" * 700),
                             smoke.SmokeFailure("Owned emulator process exited")):
                with self.subTest(stage=stage, error=type(original).__name__):
                    device, receipt = Mock(spec=smoke.Device), {}
                    preceding = [subprocess.CompletedProcess([], 0, "", ""),
                                 subprocess.CompletedProcess([], 0, marker, "")]
                    device.adb.side_effect = preceding[:index] + [original]
                    with self.assertRaises(type(original)) as raised:
                        smoke.Ui(device, Path("unused"), receipt).snapshot()
                    self.assertIs(original, raised.exception)
                    self.assertEqual(index + 1, device.adb.call_count)
                    failed = receipt["ui_hierarchy_observations"]["first_failed_attempt"]
                    self.assertEqual(stage, failed["stage"])
                    self.assertFalse(failed["completed"])
                    command = failed["commands"][stage]
                    self.assertEqual(budget, command["timeout_seconds"])
                    self.assertNotIn("exit_code", command)
                    if isinstance(original, subprocess.TimeoutExpired):
                        self.assertEqual(budget, command["command_timeout_seconds"])
                        for stream in ("stdout", "stderr"):
                            raw = getattr(original, stream).decode("utf-8", errors="replace")
                            value = command[stream]
                            self.assertTrue(value["decoded_from_bytes"])
                            self.assertTrue(value["edges_truncated"])
                            self.assertEqual(1200, len(value["prefix"]))
                            self.assertEqual(1200, len(value["suffix"]))
                            self.assertEqual(len(raw), value["text_characters"])
                            self.assertEqual(hashlib.sha256(raw.encode("utf-8")).hexdigest(), value["text_sha256"])
                    else:
                        self.assertEqual(str(original), command["error"])
                    self.assertEqual(0, receipt["ui_hierarchy_dumps"]["completed"])

    def test_nonzero_removal_or_read_cannot_accept_xml_like_output(self):
        marker = "UI hierchary dumped to: " + smoke.UI_DUMP_PATH
        valid = '<hierarchy rotation="0"><node /></hierarchy>'
        for stage, results in (
            ("remove", [subprocess.CompletedProcess([], 1, valid, "removal failed")]),
            ("read", [subprocess.CompletedProcess([], 0, "", ""),
                       subprocess.CompletedProcess([], 0, marker, ""),
                       subprocess.CompletedProcess([], 1, valid, "read failed")]),
        ):
            with self.subTest(stage=stage):
                device, receipt = Mock(spec=smoke.Device), {}
                device.adb.side_effect = results
                with patch.object(smoke.ET, "fromstring") as parse, self.assertRaises(smoke.SmokeFailure):
                    smoke.Ui(device, Path("unused"), receipt).snapshot()
                parse.assert_not_called()
                self.assertEqual(len(results), device.adb.call_count)
                failed = receipt["ui_hierarchy_observations"]["first_failed_attempt"]
                self.assertEqual(stage, failed["stage"])
                self.assertEqual(1, failed["commands"][stage]["exit_code"])
                self.assertEqual(valid, failed["commands"][stage]["stdout"]["prefix"])
                self.assertEqual(0, receipt["ui_hierarchy_dumps"]["completed"])

    def test_zero_exit_read_with_diagnostic_stderr_cannot_accept_valid_xml(self):
        marker = "UI hierchary dumped to: " + smoke.UI_DUMP_PATH
        valid = '<hierarchy rotation="0"><node /></hierarchy>'
        diagnostic = "cat: read error\n"
        device, receipt = Mock(spec=smoke.Device), {}
        device.adb.side_effect = [subprocess.CompletedProcess([], 0, "", ""),
                                 subprocess.CompletedProcess([], 0, marker, ""),
                                 subprocess.CompletedProcess([], 0, valid, diagnostic)]
        with patch.object(smoke.ET, "fromstring") as parse, self.assertRaisesRegex(
                smoke.SmokeFailure, "Fresh UI hierarchy read failed"):
            smoke.Ui(device, Path("unused"), receipt).snapshot()
        parse.assert_not_called()
        self.assertEqual(3, device.adb.call_count)
        failed = receipt["ui_hierarchy_observations"]["first_failed_attempt"]
        self.assertEqual("read", failed["stage"])
        self.assertEqual(0, failed["commands"]["read"]["exit_code"])
        self.assertEqual(valid, failed["commands"]["read"]["stdout"]["prefix"])
        self.assertEqual(diagnostic, failed["commands"]["read"]["stderr"]["prefix"])
        self.assertEqual(0, receipt["ui_hierarchy_dumps"]["completed"])

    def test_incomplete_or_differently_scoped_receipts_fail(self):
        receipt = {"source_commit": "a" * 40, "scope": "owned-installed-aosp-emulator", "status": "passed",
                   "unsigned_apk_unchanged": True,
                   "emulation": {"mode": "software", "accel_argument": "off", "boot_timeout_seconds": 900,
                                 "unsupported_software_opt_in": True},
                   "guest_readiness": {"ready": True, "ui_dump_path": smoke.UI_DUMP_PATH,
                                       "last_checks": ready_checks(), "interactive": ready_interactive()},
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
