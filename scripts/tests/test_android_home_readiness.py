"""Host-only regressions for the exact pre-installation null-root observation."""

import copy
import hashlib
import importlib.util
from pathlib import Path
import subprocess
import unittest
from unittest.mock import Mock, patch

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("home_smoke", ROOT / "scripts/android_emulator_smoke.py")
smoke = importlib.util.module_from_spec(spec)
spec.loader.exec_module(smoke)

# Actual run39146579521 / job117732554493 / artifact12143473819, before installation.
# This is the complete 58-byte public platform diagnostic, not a passing dump.
NULL_ROOT = "ERROR: null root node returned by UiTestAutomationBridge.\n"
NULL_ROOT_SHA256 = "406bd8df88b9c43b78dc2a75c35ee3f96f79a5b3ea922e8ead222b4c5b5752f9"
HOME_XML = '<hierarchy rotation="0"><node package="com.android.launcher3" bounds="[0,0][100,100]" /></hierarchy>'
HOME_FOCUS = (
    "  mCurrentFocus=Window{abc u0 com.android.launcher3/com.android.launcher3.uioverrides.QuickstepLauncher}\n"
    "  mFocusedWindow=Window{abc u0 com.android.launcher3/com.android.launcher3.uioverrides.QuickstepLauncher}\n"
)


def result(stdout="", stderr="", code=0):
    return subprocess.CompletedProcess([], code, stdout, stderr)


def answers(null_count=1):
    values = [result("Test barrier passed\nFinished application barriers!\n")]
    for _ in range(null_count):
        values.extend([result(), result(stderr=NULL_ROOT)])
    return values + [result(), result("UI hierchary dumped to: " + smoke.UI_DUMP_PATH + "\n"),
                     result(HOME_XML), result(HOME_FOCUS)]


class Clock:
    def __init__(self):
        self.now = 0.0
        self.sleeps = []

    def monotonic(self):
        return self.now

    def sleep(self, seconds):
        self.sleeps.append(seconds)
        self.now += seconds


class HomeReadinessTest(unittest.TestCase):
    def exercise(self, values, *, deadline=180, clock=None, device=None):
        device = device or Mock(spec=smoke.Device)
        readiness, clock = {}, clock or Clock()
        if values is not None:
            device.adb.side_effect = values
        failure = None
        with patch.object(smoke.time, "monotonic", clock.monotonic), \
                patch.object(smoke.time, "sleep", clock.sleep):
            try:
                smoke.establish_interactive_readiness(device, readiness, deadline)
            except Exception as error:
                failure = error
        return readiness["interactive"], device, clock, failure

    def assert_failed(self, state, failure, kind=smoke.SmokeFailure):
        self.assertIsInstance(failure, kind)
        self.assertFalse(state["ready"])
        self.assertFalse(smoke.interactive_readiness_passes(state))

    def test_exact_observed_null_root_can_be_followed_by_fresh_positive_home(self):
        self.assertEqual(58, len(NULL_ROOT.encode("utf-8")))
        self.assertEqual(NULL_ROOT_SHA256, hashlib.sha256(NULL_ROOT.encode("utf-8")).hexdigest())
        device, readiness, clock = Mock(spec=smoke.Device), {}, Clock()
        device.adb.side_effect = answers()
        failure = None
        with patch.object(smoke.time, "monotonic", clock.monotonic), \
                patch.object(smoke.time, "sleep", clock.sleep):
            try:
                smoke.establish_interactive_readiness(device, readiness, 180)
            except smoke.SmokeFailure as error:
                failure = error
        self.assertIsNone(failure, "The exact observed unavailable HOME root must remain recorded while waiting for fresh HOME evidence")
        state = readiness["interactive"]
        self.assertTrue(smoke.interactive_readiness_passes(state))
        history = state["ui_hierarchy_observations"]
        self.assertEqual([1, 2], [item["sequence"] for item in history["recent_attempts"]])
        self.assertEqual(history["recent_attempts"][0], history["first_failed_attempt"])
        self.assertEqual(history["recent_attempts"][-1], history["last_attempt"])
        self.assertEqual(NULL_ROOT, history["first_failed_attempt"]["commands"]["dump"]["stderr"]["prefix"])
        self.assertEqual(NULL_ROOT_SHA256,
                         history["first_failed_attempt"]["commands"]["dump"]["stderr"]["text_sha256"])
        self.assertEqual({"attempted": 2, "completed": 1, "exit_137": 0}, state["ui_hierarchy_dumps"])
        self.assertEqual([smoke.FRAMEWORK_BARRIER,
                          ("shell", "rm", "-f", smoke.UI_DUMP_PATH),
                          ("shell", "uiautomator", "dump", smoke.UI_DUMP_PATH),
                          ("shell", "rm", "-f", smoke.UI_DUMP_PATH),
                          ("shell", "uiautomator", "dump", smoke.UI_DUMP_PATH),
                          ("exec-out", "cat", smoke.UI_DUMP_PATH), smoke.HOME_FOCUS_QUERY],
                         [call.args for call in device.adb.call_args_list])
        device.launch.assert_not_called()
        device.shell.assert_not_called()

    def test_first_or_third_fresh_home_observation_preserves_exact_counter_and_marker_rules(self):
        for null_count, final_code in ((0, 0), (2, 0), (2, 137)):
            values = answers(null_count)
            values[-3].returncode = final_code
            with self.subTest(null_count=null_count, final_code=final_code):
                state, device, clock, failure = self.exercise(values)
                self.assertIsNone(failure)
                self.assertTrue(smoke.interactive_readiness_passes(state))
                observations = state["ui_hierarchy_observations"]
                self.assertEqual(list(range(1, null_count + 2)),
                                 [row["sequence"] for row in observations["recent_attempts"]])
                self.assertEqual({"attempted": null_count + 1, "completed": 1,
                                  "exit_137": int(final_code == 137)}, state["ui_hierarchy_dumps"])
                self.assertEqual([0.25] * null_count, clock.sleeps)
                self.assertEqual(5 + null_count * 2, device.adb.call_count)
                self.assertEqual(null_count > 0, "first_failed_attempt" in observations)
                self.assertEqual(1, sum(call.args == smoke.FRAMEWORK_BARRIER
                                        for call in device.adb.call_args_list))

    def test_third_null_root_stops_without_xml_read_focus_or_discarding_the_first_failure(self):
        values = answers(3)[:7]
        state, device, clock, failure = self.exercise(values)
        self.assert_failed(state, failure)
        self.assertEqual("UI hierarchy dump failed (0): " + NULL_ROOT, str(failure))
        self.assertEqual(7, device.adb.call_count)
        self.assertEqual([0.25, 0.25], clock.sleeps)
        observations = state["ui_hierarchy_observations"]
        self.assertEqual(3, observations["attempted"])
        self.assertEqual([1, 2, 3], [row["sequence"] for row in observations["recent_attempts"]])
        self.assertEqual(observations["recent_attempts"][0], observations["first_failed_attempt"])
        self.assertEqual(observations["recent_attempts"][2], observations["last_attempt"])
        self.assertTrue(all(smoke.home_null_root_attempt(row) for row in observations["recent_attempts"]))
        self.assertTrue(all(set(row["commands"]) == {"remove", "dump"}
                            for row in observations["recent_attempts"]))
        self.assertEqual({"attempted": 3, "completed": 0, "exit_137": 0}, state["ui_hierarchy_dumps"])
        self.assertEqual({}, state["focus"])
        device.launch.assert_not_called()
        device.shell.assert_not_called()

    def test_all_attempts_share_the_original_deadline_and_one_framework_barrier(self):
        clock = Clock()
        timed_values = iter(zip(answers(2), (100, 125, 145, 160, 165, 175, 177, 178, 179)))

        def answer(*args, **kwargs):
            value, clock.now = next(timed_values)
            return value

        state, device, clock, failure = self.exercise(answer, clock=clock)
        self.assertIsNone(failure)
        self.assertTrue(smoke.interactive_readiness_passes(state))
        self.assertEqual([120, 30, 20, 30, 20, 14.75, 5, 3, 2],
                         [call.kwargs["timeout"] for call in device.adb.call_args_list])
        self.assertTrue(all(call.kwargs["check"] is False for call in device.adb.call_args_list))
        self.assertEqual(179, state["seconds"])
        self.assertEqual([0.25, 0.25], clock.sleeps)

    def test_null_root_at_deadline_or_sleep_exhaustion_cannot_begin_another_attempt(self):
        for finish in (180, 179.9):
            clock, values = Clock(), iter(enumerate(answers()))

            def answer(*args, **kwargs):
                index, value = next(values)
                if index == 2:
                    clock.now = finish
                return value

            with self.subTest(finish=finish):
                state, device, clock, failure = self.exercise(answer, clock=clock)
                self.assert_failed(state, failure)
                self.assertEqual("UI hierarchy dump failed (0): " + NULL_ROOT, str(failure))
                self.assertEqual(3, device.adb.call_count)
                self.assertEqual(1, state["ui_hierarchy_observations"]["attempted"])
                self.assertEqual(180, clock.now)
                self.assertEqual(0 if finish == 180 else 1, len(clock.sleeps))
                if clock.sleeps:
                    self.assertAlmostEqual(0.1, clock.sleeps[0])

    def test_retried_xml_or_focus_completing_at_deadline_cannot_mark_home_ready(self):
        for finish_index in (5, 6):
            clock, values = Clock(), iter(enumerate(answers()))

            def answer(*args, **kwargs):
                index, value = next(values)
                if index == finish_index:
                    clock.now = 180
                return value

            with self.subTest(finish_index=finish_index):
                state, device, clock, failure = self.exercise(answer, clock=clock)
                self.assert_failed(state, failure)
                self.assertEqual(finish_index + 1, device.adb.call_count)
                self.assertEqual(1, state["ui_hierarchy_observations"]["first_failed_attempt"]["sequence"])
                self.assertEqual(int(finish_index == 6), state["ui_hierarchy_dumps"]["completed"])
                self.assertEqual([0.25], clock.sleeps)

    def test_unrelated_dump_results_are_terminal_and_never_retried(self):
        marker = "UI hierchary dumped to: " + smoke.UI_DUMP_PATH + "\n"
        for failed_dump in (
            result(stderr=NULL_ROOT, code=1), result(stderr=NULL_ROOT, code=137),
            result(stderr=NULL_ROOT, code=False), result("unexpected output\n", NULL_ROOT),
            result(stderr=NULL_ROOT.rstrip("\n")), result(stderr=NULL_ROOT + "additional failure\n"),
            result(stderr="ERROR: could not get idle state.\n"), result(),
            result(marker, NULL_ROOT), result("UI hierchary dumped to: /sdcard/stale.xml\n"),
        ):
            values = answers()
            values[2] = failed_dump
            with self.subTest(stdout=failed_dump.stdout, stderr=failed_dump.stderr, code=failed_dump.returncode):
                state, device, clock, failure = self.exercise(values)
                self.assert_failed(state, failure)
                self.assertEqual(3, device.adb.call_count)
                self.assertEqual([], clock.sleeps)
                self.assertEqual(1, state["ui_hierarchy_observations"]["attempted"])
                self.assertEqual({}, state["focus"])

    def test_second_dump_timeout_preserves_both_original_failure_and_partial_streams(self):
        original = subprocess.TimeoutExpired(["adb", "uiautomator", "dump"], 20,
                                             output=b"", stderr=NULL_ROOT.encode("utf-8"))
        values = answers()
        values[4] = original
        state, device, clock, failure = self.exercise(values)
        self.assert_failed(state, failure, subprocess.TimeoutExpired)
        self.assertIs(original, failure)
        self.assertEqual(5, device.adb.call_count)
        self.assertEqual([0.25], clock.sleeps)
        observations = state["ui_hierarchy_observations"]
        self.assertTrue(smoke.home_null_root_attempt(observations["first_failed_attempt"]))
        final = observations["last_attempt"]
        self.assertEqual("TimeoutExpired", final["error"]["type"])
        self.assertEqual(20, final["commands"]["dump"]["command_timeout_seconds"])
        self.assertNotIn("exit_code", final["commands"]["dump"])
        self.assertEqual(NULL_ROOT_SHA256, final["commands"]["dump"]["stderr"]["text_sha256"])
        self.assertTrue(final["commands"]["dump"]["stderr"]["decoded_from_bytes"])
        self.assertFalse(smoke.home_null_root_attempt(final))
        self.assertEqual({"attempted": 2, "completed": 0, "exit_137": 0}, state["ui_hierarchy_dumps"])

    def test_failed_second_removal_cannot_read_preexisting_xml_or_erase_the_first_error(self):
        values = answers()
        values[3] = result(stderr="Permission denied\n", code=1)
        state, device, clock, failure = self.exercise(values)
        self.assert_failed(state, failure)
        self.assertIn("Prior UI hierarchy removal failed", str(failure))
        self.assertEqual(4, device.adb.call_count)
        self.assertEqual([0.25], clock.sleeps)
        observations = state["ui_hierarchy_observations"]
        self.assertTrue(smoke.home_null_root_attempt(observations["first_failed_attempt"]))
        self.assertEqual("remove", observations["last_attempt"]["stage"])
        self.assertEqual({"remove"}, set(observations["last_attempt"]["commands"]))
        self.assertEqual({"attempted": 1, "completed": 0, "exit_137": 0}, state["ui_hierarchy_dumps"])
        self.assertNotIn(("exec-out", "cat", smoke.UI_DUMP_PATH),
                         [call.args for call in device.adb.call_args_list])

    def test_noisy_successful_removal_does_not_authorize_a_null_root_retry(self):
        for stdout, stderr in (("removed something\n", ""), ("", "unrelated diagnostic\n")):
            values = answers()
            values[1] = result(stdout, stderr)
            with self.subTest(stdout=stdout, stderr=stderr):
                state, device, clock, failure = self.exercise(values)
                self.assert_failed(state, failure)
                self.assertEqual(3, device.adb.call_count)
                self.assertEqual([], clock.sleeps)
                self.assertFalse(smoke.home_null_root_attempt(state["ui_hierarchy_observations"]["last_attempt"]))

    def test_later_dump_marker_cannot_replace_fresh_read_and_parse(self):
        for read_result, error_type in (
            (result(""), smoke.ET.ParseError), (result("stale unparseable data"), smoke.ET.ParseError),
            (result('<hierarchy rotation="0" />'), smoke.SmokeFailure),
            (result(HOME_XML, "read failed\n"), smoke.SmokeFailure),
            (result(HOME_XML, code=1), smoke.SmokeFailure),
        ):
            values = answers()
            values[5] = read_result
            with self.subTest(read_result=read_result):
                state, device, clock, failure = self.exercise(values)
                self.assert_failed(state, failure, error_type)
                self.assertEqual(6, device.adb.call_count)
                self.assertEqual({}, state["focus"])
                self.assertEqual([0.25], clock.sleeps)
                self.assertEqual(0, state["ui_hierarchy_dumps"]["completed"])
                self.assertTrue(smoke.home_null_root_attempt(
                    state["ui_hierarchy_observations"]["first_failed_attempt"]))

    def test_retries_cannot_override_wrong_home_user_rotation_or_focus(self):
        for xml, focus in (
            (HOME_XML.replace("com.android.launcher3", smoke.PACKAGE), HOME_FOCUS),
            (HOME_XML.replace('rotation="0"', 'rotation="1"'), HOME_FOCUS),
            (HOME_XML.replace("[0,0][100,100]", "[0,0][0,0]"), HOME_FOCUS),
            (HOME_XML, HOME_FOCUS.replace("u0 ", "u10 ")),
            (HOME_XML, HOME_FOCUS + HOME_FOCUS),
            (HOME_XML, HOME_FOCUS.replace(smoke.HOME_WINDOW, "Application Not Responding: com.android.systemui")),
        ):
            values = answers(2)
            values[-2], values[-1] = result(xml), result(focus)
            with self.subTest(xml=xml, focus=focus):
                state, device, clock, failure = self.exercise(values)
                self.assert_failed(state, failure)
                self.assertEqual(9, device.adb.call_count)
                self.assertEqual(3, state["ui_hierarchy_observations"]["attempted"])
                self.assertEqual([0.25, 0.25], clock.sleeps)

    def test_owned_process_loss_between_attempts_stops_before_another_adb_command(self):
        process = Mock()
        process.poll.side_effect = [None, None, None, 13]
        device = smoke.Device(Path("owned-adb"), {"OWNED_GUEST_TEST": "1"}, process, "pyai-owned", {})
        with patch.object(smoke, "command", side_effect=answers()[:3]) as command:
            state, _, clock, failure = self.exercise(None, device=device)
        self.assert_failed(state, failure)
        self.assertEqual("Owned emulator process exited", str(failure))
        self.assertEqual(3, command.call_count)
        self.assertEqual(4, process.poll.call_count)
        self.assertTrue(all(call.args[0][:3] == ["owned-adb", "-s", smoke.SERIAL]
                            for call in command.call_args_list))
        self.assertEqual([0.25], clock.sleeps)
        observations = state["ui_hierarchy_observations"]
        self.assertTrue(smoke.home_null_root_attempt(observations["first_failed_attempt"]))
        self.assertEqual({"remove"}, set(observations["last_attempt"]["commands"]))
        self.assertEqual("Owned emulator process exited", observations["last_attempt"]["commands"]["remove"]["error"])

    def test_guest_readiness_failure_keeps_all_four_app_cases_not_run(self):
        device, clock = Mock(spec=smoke.Device), Clock()
        receipt = {"cases": [{"id": case, "status": "not_run"} for case in smoke.CASE_IDS]}
        device.adb.side_effect = [result(expected[-1]) for _, _, expected in smoke.GUEST_READINESS_COMMANDS] + answers(3)[:7]
        with patch.object(smoke.time, "monotonic", clock.monotonic), \
                patch.object(smoke.time, "sleep", clock.sleep), self.assertRaises(smoke.SmokeFailure):
            smoke.wait_for_guest_ready(device, receipt)
        device.verify_identity.assert_called_once_with()
        device.launch.assert_not_called()
        device.shell.assert_not_called()
        self.assertEqual(16, device.adb.call_count)
        self.assertFalse(receipt["guest_readiness"]["ready"])
        self.assertTrue(smoke.guest_readiness_checks_pass(receipt["guest_readiness"]["last_checks"]))
        self.assertEqual(["not_run"] * 4, [case["status"] for case in receipt["cases"]])
        self.assertNotIn("ui_hierarchy_observations", receipt)
        self.assertFalse(any(call.args and call.args[0] == "install" for call in device.adb.call_args_list))

    def test_application_snapshot_still_fails_on_first_null_root_without_a_retry(self):
        device, receipt = Mock(spec=smoke.Device), {}
        device.adb.side_effect = answers()[1:]
        with patch.object(smoke.time, "monotonic", return_value=0), patch.object(smoke.time, "sleep") as sleep, \
                self.assertRaises(smoke.SmokeFailure):
            smoke.Ui(device, Path("unused"), receipt).snapshot()
        self.assertEqual(2, device.adb.call_count)
        self.assertEqual(1, receipt["ui_hierarchy_observations"]["attempted"])
        self.assertEqual({"attempted": 1, "completed": 0, "exit_137": 0}, receipt["ui_hierarchy_dumps"])
        sleep.assert_not_called()

    def test_ready_receipt_rejects_missing_changed_or_reordered_retry_history(self):
        valid, _, _, failure = self.exercise(answers(2))
        self.assertIsNone(failure)
        for path, value in (
            (("home_snapshot_attempt_limit",), None), (("home_snapshot_attempt_limit",), True),
            (("home_snapshot_attempt_limit",), 4), (("phase",), "after_installation"),
            (("serial",), "emulator-5556"), (("home_package",), smoke.PACKAGE),
            (("ui_hierarchy_observations", "attempted"), True),
            (("ui_hierarchy_observations", "attempted"), 2),
            (("ui_hierarchy_observations", "attempted"), 4),
            (("ui_hierarchy_observations", "recent_attempts"), None),
            (("ui_hierarchy_observations", "first_failed_attempt"), None),
            (("ui_hierarchy_observations", "last_attempt"), {}),
            (("ui_hierarchy_observations", "recent_attempts", 0, "sequence"), True),
            (("ui_hierarchy_observations", "recent_attempts", 1, "sequence"), 1),
            (("ui_hierarchy_observations", "recent_attempts", 0, "completed"), True),
            (("ui_hierarchy_observations", "recent_attempts", 0, "error"), None),
            (("ui_hierarchy_observations", "recent_attempts", 0, "stage"), "parse"),
            (("ui_hierarchy_dumps", "attempted"), 2),
            (("ui_hierarchy_dumps", "completed"), True),
            (("ui_hierarchy_dumps", "completed"), 3),
            (("ui_hierarchy_dumps", "exit_137"), 1),
        ):
            changed = copy.deepcopy(valid)
            target = changed
            for key in path[:-1]:
                target = target[key]
            target[path[-1]] = value
            with self.subTest(path=path, value=value):
                self.assertFalse(smoke.interactive_readiness_passes(changed))
        for mutation in (lambda rows: rows.reverse(), lambda rows: rows.pop(0),
                         lambda rows: rows.append(copy.deepcopy(rows[0]))):
            changed = copy.deepcopy(valid)
            mutation(changed["ui_hierarchy_observations"]["recent_attempts"])
            self.assertFalse(smoke.interactive_readiness_passes(changed))

    def test_ready_receipt_rejects_predecessor_command_or_stream_substitution(self):
        valid, _, _, failure = self.exercise(answers())
        self.assertIsNone(failure)
        mutations = [
            (("ui_dump_path",), "/sdcard/stale.xml"),
            (("dump_completion_passed",), True),
            (("error", "type"), "TimeoutExpired"),
            (("error", "message"), "UI hierarchy dump failed (0): " + NULL_ROOT.rstrip("\n")),
            (("commands", "read"), {}), (("commands", "dump", "exit_code"), False),
            (("commands", "dump", "exit_code"), 137),
            (("commands", "dump", "arguments"), ["shell", "uiautomator", "dump", "/sdcard/stale.xml"]),
            (("commands", "remove", "arguments"), ["shell", "rm", smoke.UI_DUMP_PATH]),
            (("commands", "remove", "exit_code"), 1),
            (("commands", "remove", "exit_code"), False),
        ]
        for command in ("remove", "dump"):
            mutations.extend([(("commands", command, "command_timeout_seconds"), 1),
                              (("commands", command, "error"), "owned process exited")])
            for stream in ("stdout", "stderr"):
                mutations.extend(( ("commands", command, stream, field), value)
                                 for field, value in (("available", False), ("decoded_from_bytes", True),
                                                       ("text_characters", False), ("text_sha256", "0" * 64),
                                                       ("prefix", "substituted"), ("suffix", "substituted"),
                                                       ("edges_truncated", True)))
        for path, value in mutations:
            changed = copy.deepcopy(valid)
            target = changed["ui_hierarchy_observations"]["recent_attempts"][0]
            for key in path[:-1]:
                target = target[key]
            target[path[-1]] = value
            with self.subTest(path=path, value=value):
                self.assertFalse(smoke.interactive_readiness_passes(changed))


if __name__ == "__main__":
    unittest.main()
