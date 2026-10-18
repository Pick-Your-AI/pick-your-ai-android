"""Boot-failure diagnostics with fake time/devices; no SDK or guest is invoked."""

import ast
import copy
import hashlib
import importlib.util
from pathlib import Path
import subprocess
import unittest
from unittest.mock import Mock, call, patch


ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("boot_diagnostic_smoke", ROOT / "scripts/android_emulator_smoke.py")
smoke = importlib.util.module_from_spec(spec)
spec.loader.exec_module(smoke)

OWNED_NAME = "pyai_smoke_" + "a" * 32
IDENTITY_COMMANDS = (
    ("get-state",),
    ("emu", "avd", "name"),
    ("shell", "getprop", "ro.kernel.qemu"),
    ("shell", "getprop", "init.svc.bootanim"),
)
FRAMEWORK_COMMANDS = (
    ("logcat", "-b", "main", "-b", "system", "-b", "crash", "-b", "events",
     "-d", "-v", "threadtime", "-t", "2000", "SystemServer:V", "SystemServerTiming:V", "*:I"),
    ("shell", "dumpsys", "-l"),
)


class Clock:
    def __init__(self):
        self.now = 0.0

    def monotonic(self):
        return self.now


def failed_receipt():
    return {"status": "failed", "error": "original boot failure",
            "boot_wait": {"completed": False, "timeout_seconds": 600},
            "cases": [{"id": name, "status": "not_run"} for name in smoke.CASE_IDS]}


def owned_answers():
    return [subprocess.CompletedProcess([], 0, text, "")
            for text in ("device\n", OWNED_NAME + "\nOK\n", "1\n", "stopped\n")]


class BootFailureFrameworkTest(unittest.TestCase):
    def assert_original_failure(self, receipt):
        self.assertEqual(failed_receipt(), {key: receipt[key] for key in failed_receipt()})
        self.assertNotIn("device", receipt)
        self.assertNotIn("guest_readiness", receipt)
        self.assertNotIn("activity_launches", receipt)

    def assert_stream(self, observed, value):
        text = value.decode("utf-8", errors="replace") if isinstance(value, bytes) else value
        self.assertEqual({"available": True, "decoded_from_bytes": isinstance(value, bytes),
                          "text_characters": len(text),
                          "text_sha256": hashlib.sha256(text.encode("utf-8")).hexdigest(),
                          "prefix": text[:1200], "suffix": text[-1200:],
                          "edges_truncated": len(text) > 2400}, observed)

    def test_owned_prefix_adds_only_bounded_read_only_framework_observations(self):
        receipt, clock = failed_receipt(), Clock()
        device = Mock(spec=smoke.Device, avd_name=OWNED_NAME)
        device.adb.side_effect = owned_answers() + [
            subprocess.CompletedProcess([], 7, "recorded framework state\n", "log unavailable\n"),
            subprocess.CompletedProcess([], 0, "Found 1 services:\n0\tactivity: [android.app.IActivityManager]\n", ""),
        ]
        with patch.object(smoke.time, "monotonic", clock.monotonic):
            smoke.retain_boot_failure_state(device, receipt)
        self.assertEqual([call(*args, timeout=5, check=False)
                          for args in IDENTITY_COMMANDS + FRAMEWORK_COMMANDS], device.adb.call_args_list)
        state = receipt["boot_failure_observations"]
        self.assertEqual(20, state["timeout_seconds"])
        self.assertEqual(["adb_state", "emulator_name", "emulator_property", "boot_animation"],
                         list(state["checks"]))
        framework = state["framework"]
        self.assertEqual("after_boot_failure", framework["phase"])
        self.assertEqual("emulator-5554", framework["serial"])
        self.assertIs(framework["target_verified"], True)
        self.assertEqual([], framework["unattempted_checks"])
        self.assertIs(framework["deadline_reached"], False)
        self.assertEqual(["framework_log", "framework_services"], list(framework["checks"]))
        for name, args, code in zip(framework["checks"], FRAMEWORK_COMMANDS, (7, 0)):
            observed = framework["checks"][name]
            self.assertEqual(list(args), observed["arguments"])
            self.assertEqual(5, observed["timeout_seconds"])
            self.assertEqual(code, observed["exit_code"])
            self.assertEqual("completed", observed["status"])
            self.assertNotIn("passed", observed)
        self.assert_stream(framework["checks"]["framework_log"]["stderr"], "log unavailable\n")
        self.assert_original_failure(receipt)
        device.launch.assert_not_called()
        device.shell.assert_not_called()
        device.verify_identity.assert_not_called()

    def test_incomplete_or_foreign_prefix_prevents_every_framework_command(self):
        failures = (
            (0, subprocess.CompletedProcess([], 0, "offline\n", "")),
            (0, subprocess.CompletedProcess([], False, "device\n", "")),
            (1, subprocess.CompletedProcess([], 0, "pyai_smoke_" + "b" * 32 + "\nOK\n", "")),
            (1, subprocess.CompletedProcess([], 0, "noise\n" + OWNED_NAME + "\nOK\n", "")),
            (1, subprocess.CompletedProcess([], 0, "x" * 1300 + "\n" + OWNED_NAME + "\nOK\n", "")),
            (1, subprocess.CompletedProcess([], 0, OWNED_NAME + "\nOK\n", " \n")),
            (2, subprocess.CompletedProcess([], 0, "0\n", "")),
            (2, subprocess.CompletedProcess([], 1, "1\n", "")),
            (2, subprocess.TimeoutExpired(["adb"], 5, output=b"1\n", stderr=b"")),
            (2, smoke.SmokeFailure("Owned emulator process exited")),
        )
        for index, answer in failures:
            receipt, clock = failed_receipt(), Clock()
            device = Mock(spec=smoke.Device, avd_name=OWNED_NAME)
            answers = owned_answers()
            answers[index] = answer
            device.adb.side_effect = answers
            with self.subTest(index=index, answer=repr(answer)), \
                    patch.object(smoke.time, "monotonic", clock.monotonic):
                smoke.retain_boot_failure_state(device, receipt)
                framework = receipt["boot_failure_observations"]["framework"]
                self.assertIs(framework["target_verified"], False)
                self.assertTrue(framework["identity_error"])
                self.assertEqual({}, framework["checks"])
                self.assertEqual(["framework_log", "framework_services"], framework["unattempted_checks"])
                self.assertEqual([call(*args, timeout=5, check=False) for args in IDENTITY_COMMANDS],
                                 device.adb.call_args_list)
                self.assert_original_failure(receipt)

    def test_complete_long_streams_keep_exact_decoded_metadata_and_bounded_tails(self):
        receipt, clock = failed_receipt(), Clock()
        outputs = ["start\n" + "\u2603" * 132000 + "\nend", "service\n" + "s" * 33000 + "\nlast"]
        stderr = "warning\n" + "e" * 2500 + "\nlast error"
        device = Mock(spec=smoke.Device, avd_name=OWNED_NAME)
        device.adb.side_effect = owned_answers() + [subprocess.CompletedProcess([], 1, text, stderr) for text in outputs]
        with patch.object(smoke.time, "monotonic", clock.monotonic):
            smoke.retain_boot_failure_state(device, receipt)
        checks = receipt["boot_failure_observations"]["framework"]["checks"]
        for (name, observed), text, limit in zip(checks.items(), outputs, (131072, 32768)):
            with self.subTest(name=name):
                self.assert_stream(observed["stdout"], text)
                self.assert_stream(observed["stderr"], stderr)
                self.assertEqual(limit, observed["stdout_retained_character_limit"])
                self.assertEqual(text[-limit:], observed["stdout_tail"])
                self.assertIs(observed["stdout_tail_truncated"], True)
                self.assertEqual(1, observed["exit_code"])
                self.assertEqual("completed", observed["status"])
        self.assert_original_failure(receipt)

    def test_timeout_keeps_partial_decoded_streams_and_does_not_retry_command(self):
        receipt, clock = failed_receipt(), Clock()
        stdout = b"begin\n" + b"x" * 132000 + b"\xff\xfe\nend"
        stderr = b"error\n" + b"z" * 2500 + b"\xff\nlast error"
        answers = iter(owned_answers())
        device = Mock(spec=smoke.Device, avd_name=OWNED_NAME)

        def respond(*args, **kwargs):
            if args in IDENTITY_COMMANDS:
                return next(answers)
            if args == FRAMEWORK_COMMANDS[0]:
                clock.now += kwargs["timeout"]
                raise subprocess.TimeoutExpired(["adb", *args], kwargs["timeout"], output=stdout, stderr=stderr)
            self.assertEqual(FRAMEWORK_COMMANDS[1], args)
            return subprocess.CompletedProcess([], 0, "Found 0 services:\n", "")

        device.adb.side_effect = respond
        with patch.object(smoke.time, "monotonic", clock.monotonic):
            smoke.retain_boot_failure_state(device, receipt)
        state = receipt["boot_failure_observations"]
        observed = state["framework"]["checks"]["framework_log"]
        self.assert_stream(observed["stdout"], stdout)
        self.assert_stream(observed["stderr"], stderr)
        self.assertEqual(stdout.decode("utf-8", errors="replace")[-131072:], observed["stdout_tail"])
        self.assertIs(observed["stdout_tail_truncated"], True)
        self.assertEqual(5, observed["command_timeout_seconds"])
        self.assertEqual(5, observed["seconds"])
        self.assertEqual("timed_out", observed["status"])
        self.assertNotIn("exit_code", observed)
        self.assertEqual("completed", state["framework"]["checks"]["framework_services"]["status"])
        self.assertEqual(6, device.adb.call_count)
        self.assert_original_failure(receipt)

    def test_original_and_framework_commands_share_one_twenty_second_budget(self):
        for prefix_seconds, log_seconds, expected_calls, unattempted in (
                (4.5, 2, [5, 5, 5, 5, 2], ["framework_services"]),
                (5, 0, [5, 5, 5, 5], ["framework_log", "framework_services"]),
                (3.5, 4, [5, 5, 5, 5, 5, 2], [])):
            receipt, clock = failed_receipt(), Clock()
            answers = iter(owned_answers())
            device = Mock(spec=smoke.Device, avd_name=OWNED_NAME)

            def respond(*args, **kwargs):
                if args in IDENTITY_COMMANDS:
                    clock.now += prefix_seconds
                    return next(answers)
                self.assertGreater(kwargs["timeout"], 0)
                self.assertLessEqual(kwargs["timeout"], 5)
                self.assertLessEqual(kwargs["timeout"], 20 - clock.now)
                if args == FRAMEWORK_COMMANDS[0] and log_seconds < kwargs["timeout"]:
                    clock.now += log_seconds
                    return subprocess.CompletedProcess([], 0, "recorded state\n", "")
                clock.now += kwargs["timeout"]
                raise subprocess.TimeoutExpired(["adb", *args], kwargs["timeout"], output=b"partial", stderr=None)

            device.adb.side_effect = respond
            with self.subTest(prefix_seconds=prefix_seconds), patch.object(smoke.time, "monotonic", clock.monotonic):
                smoke.retain_boot_failure_state(device, receipt)
                state = receipt["boot_failure_observations"]
                self.assertEqual(expected_calls, [item.kwargs["timeout"] for item in device.adb.call_args_list])
                self.assertEqual(20, state["seconds"])
                self.assertIs(state["framework"]["target_verified"], True)
                self.assertEqual(unattempted, state["framework"]["unattempted_checks"])
                self.assertIs(state["deadline_reached"], True)
                self.assertIs(state["framework"]["deadline_reached"], True)
                self.assert_original_failure(receipt)

    def test_owned_process_exit_prevents_subprocess_and_records_unavailable_streams(self):
        receipt, clock = failed_receipt(), Clock()
        process = Mock()
        process.poll.side_effect = [None] * 4 + [7, 7]
        device = smoke.Device(Path("/unused/adb"), {"OWNED_TEST": "yes"}, process, OWNED_NAME, receipt)
        with patch.object(smoke.time, "monotonic", clock.monotonic), \
                patch.object(smoke, "command", side_effect=owned_answers()) as command:
            smoke.retain_boot_failure_state(device, receipt)
        self.assertEqual([call(["/unused/adb", "-s", "emulator-5554", *args],
                               env={"OWNED_TEST": "yes"}, timeout=5, check=False)
                          for args in IDENTITY_COMMANDS], command.call_args_list)
        framework = receipt["boot_failure_observations"]["framework"]
        self.assertEqual([], framework["unattempted_checks"])
        for observed in framework["checks"].values():
            self.assertEqual("error", observed["status"])
            self.assertEqual("Owned emulator process exited", observed["error"])
            self.assertEqual({"available": False}, observed["stdout"])
            self.assertEqual({"available": False}, observed["stderr"])
            self.assertNotIn("exit_code", observed)
            self.assertNotIn("stdout_tail", observed)
        self.assert_original_failure(receipt)

    def test_timeout_without_streams_is_distinct_from_complete_empty_output(self):
        receipt, clock = failed_receipt(), Clock()
        device = Mock(spec=smoke.Device, avd_name=OWNED_NAME)
        device.adb.side_effect = owned_answers() + [
            subprocess.TimeoutExpired(["adb"], 5), subprocess.CompletedProcess([], 0, "", "")]
        with patch.object(smoke.time, "monotonic", clock.monotonic):
            smoke.retain_boot_failure_state(device, receipt)
        checks = receipt["boot_failure_observations"]["framework"]["checks"]
        timeout, completed = checks["framework_log"], checks["framework_services"]
        self.assertEqual("timed_out", timeout["status"])
        self.assertEqual({"available": False}, timeout["stdout"])
        self.assertEqual({"available": False}, timeout["stderr"])
        self.assertEqual("", timeout["stdout_tail"])
        self.assertIs(timeout["stdout_tail_truncated"], False)
        self.assertNotIn("exit_code", timeout)
        self.assertEqual("completed", completed["status"])
        self.assert_stream(completed["stdout"], "")
        self.assert_stream(completed["stderr"], "")
        self.assertEqual(0, completed["exit_code"])
        self.assert_original_failure(receipt)

    def test_actual_main_boot_handler_reraises_original_failure_after_diagnostic_error(self):
        # Execute only the real boot try/except, never main's provisioning or install code.
        module = ast.parse((ROOT / "scripts/android_emulator_smoke.py").read_text())
        main = next(item for item in module.body if isinstance(item, ast.FunctionDef) and item.name == "main")
        handlers = [item for item in ast.walk(main) if isinstance(item, ast.Try)
                    and any(isinstance(statement, ast.Expr) and isinstance(statement.value, ast.Call)
                            and isinstance(statement.value.func, ast.Name)
                            and statement.value.func.id == "wait_for_boot_completed" for statement in item.body)]
        self.assertEqual(1, len(handlers))
        fragment = ast.Module(body=[copy.deepcopy(handlers[0])], type_ignores=[])
        code = compile(ast.fix_missing_locations(fragment), str(ROOT / "scripts/android_emulator_smoke.py"), "exec")
        for diagnostic_error in (None, OSError("diagnostic capture failed")):
            receipt, original = failed_receipt(), smoke.SmokeFailure("original boot failure")
            context = dict(smoke.__dict__, device=object(), receipt=receipt, boot_timeout=600,
                           wait_for_boot_completed=Mock(side_effect=original),
                           retain_boot_failure_state=Mock(side_effect=diagnostic_error))
            with self.subTest(diagnostic_error=diagnostic_error), self.assertRaises(smoke.SmokeFailure) as raised:
                exec(code, context)
            self.assertIs(original, raised.exception)
            context["retain_boot_failure_state"].assert_called_once_with(context["device"], receipt)
            if diagnostic_error is not None:
                self.assertEqual("diagnostic capture failed", receipt["boot_failure_diagnostics_error"])
            self.assert_original_failure(receipt)


if __name__ == "__main__":
    unittest.main()
