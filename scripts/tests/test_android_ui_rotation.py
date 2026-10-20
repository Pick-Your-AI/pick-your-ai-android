"""Host-only rotation synchronization guards; no adb, SDK or device is invoked."""

from __future__ import annotations

import importlib.util
from pathlib import Path
import subprocess
import unittest
from unittest import mock
import xml.etree.ElementTree as ET


SPEC = importlib.util.spec_from_file_location(
    "android_ui_rotation_subject", Path(__file__).parents[1] / "android_emulator_smoke.py")
SMOKE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(SMOKE)


def rotation_dump(*, mode="USER_ROTATION_LOCKED", current=1, requested=1, paused=0, display=0):
    name = ("ROTATION_0", "ROTATION_90", "ROTATION_180", "ROTATION_270")[requested]
    return (
        "ACTIVITY MANAGER ACTIVITIES (dumpsys activity activities)\n"
        f"Display #{display} (activities from top to bottom):\n"
        "  * Task{synthetic activity state}\n"
        "  DisplayRotation\n"
        "    mCurrentAppOrientation=SCREEN_ORIENTATION_UNSPECIFIED\n"
        f"    mRotation={current} mDeferredRotationPauseCount={paused}\n"
        f"    mUserRotationMode={mode} mUserRotation={name} "
        "mCameraRotationMode=0 mAllowAllRotations=false\n"
        "  FoldController\n"
        "    mShouldDisableRotationSensor=false\n"
        "\n  KeyguardController:\n")


def hierarchy(rotation, *, package=SMOKE.PACKAGE, bounds="[0,0][720,1280]"):
    root = ET.Element("hierarchy", rotation=str(rotation))
    ET.SubElement(root, "node", package=package, bounds=bounds,
                  text="Synthetic pending fields", enabled="true")
    return root


class Clock:
    def __init__(self):
        self.now = 0.0

    def monotonic(self):
        return self.now

    def sleep(self, seconds):
        if seconds < 0:
            raise AssertionError("negative sleep")
        self.now += seconds


class RotationDevice:
    """Model the two documented mode/angle and UiAutomation restoration boundaries."""

    def __init__(self, clock, *, mode="USER_ROTATION_FREE"):
        self.clock = clock
        self.mode, self.current, self.requested = mode, 0, 0
        self.pending_at = None
        self.calls, self.captures = [], []
        self.lock_count = 0
        self.lock_result = self.query_result = None
        self.query_transform = None
        self.command_delay = 0.01
        self.capture_delay = 2.0
        self.capture_error = None
        self.capture_result = None
        self.revert_after_capture = False
        self.ui = None

    def apply_pending(self):
        if (self.mode == "USER_ROTATION_LOCKED" and self.pending_at is not None
                and self.clock.now >= self.pending_at):
            self.current = self.requested
            self.pending_at = None

    @staticmethod
    def result(value, arguments):
        if isinstance(value, BaseException):
            raise value
        return value if value is not None else subprocess.CompletedProcess(arguments, 0, "", "")

    def adb(self, *arguments, timeout=30, check=True):
        self.calls.append({"arguments": arguments, "timeout": timeout, "started": self.clock.now})
        self.clock.now += self.command_delay
        if arguments[:6] == ("shell", "wm", "user-rotation", "-d", "0", "lock"):
            self.lock_count += 1
            if self.lock_result is not None:
                return self.result(self.lock_result, arguments)
            self.mode = "USER_ROTATION_LOCKED"
            self.requested = int(arguments[6])
            self.pending_at = self.clock.now + 0.2
            return self.result(None, arguments)
        if arguments == SMOKE.ROTATION_STATE_QUERY:
            if self.query_result is not None:
                return self.result(self.query_result, arguments)
            self.apply_pending()
            text = rotation_dump(mode=self.mode, current=self.current, requested=self.requested)
            if self.query_transform is not None:
                text = self.query_transform(text)
            return subprocess.CompletedProcess(arguments, 0, text, "")
        raise AssertionError("Unexpected device command: " + repr(arguments))

    def shell(self, *arguments):
        # This legacy operation is used only by the external unchanged-source
        # witness. Changing the stored angle cannot itself select locked mode.
        if arguments[:4] != ("settings", "put", "system", "user_rotation"):
            raise AssertionError("Unexpected legacy operation")
        self.calls.append({"arguments": ("shell", *arguments), "timeout": 30, "started": self.clock.now})
        self.requested = int(arguments[4])
        if self.mode == "USER_ROTATION_LOCKED":
            self.pending_at = self.clock.now + 0.2
        return ""

    def capture(self, *, deadline=None):
        self.apply_pending()
        stored_mode, stored_rotation = self.mode, self.current
        self.captures.append({"started": self.clock.now, "mode": stored_mode,
                              "rotation": stored_rotation, "deadline": deadline})
        self.ui.observations["attempted"] += 1
        if self.capture_error is not None:
            raise self.capture_error
        root = (self.capture_result(stored_rotation) if self.capture_result is not None
                else hierarchy(stored_rotation))
        self.clock.now += self.capture_delay
        self.apply_pending()
        # The dump's connect/disconnect saves and restores the earlier state.
        # This fixture deliberately models the interleaving where the XML was
        # sampled before a pending rotation and the disconnect restores it.
        self.mode, self.current = stored_mode, stored_rotation
        if stored_mode == "USER_ROTATION_LOCKED":
            self.requested = stored_rotation
            self.pending_at = None
        if self.revert_after_capture:
            self.mode, self.current = "USER_ROTATION_FREE", 0
        return root


class UiRotationGuardTest(unittest.TestCase):
    def setUp(self):
        self.clock = Clock()
        self.monotonic = mock.patch.object(SMOKE.time, "monotonic", self.clock.monotonic)
        self.sleep = mock.patch.object(SMOKE.time, "sleep", self.clock.sleep)
        self.monotonic.start()
        self.sleep.start()
        self.addCleanup(self.monotonic.stop)
        self.addCleanup(self.sleep.stop)

    def make_ui(self, *, mode="USER_ROTATION_FREE"):
        device = RotationDevice(self.clock, mode=mode)
        receipt = {}
        ui = SMOKE.Ui(device, Path("unused-host-only-output"), receipt)
        device.ui = ui
        ui.snapshot = mock.Mock(side_effect=device.capture)
        return ui, device, receipt

    def test_observed_free_mode_requires_explicit_lock_before_any_capture(self):
        ui, device, _ = self.make_ui()
        ui.rotate(1)
        self.assertEqual(device.lock_count, 1)
        self.assertEqual(device.calls[0]["arguments"],
                         ("shell", "wm", "user-rotation", "-d", "0", "lock", "1"))
        self.assertEqual((device.mode, device.current, device.requested), ("USER_ROTATION_LOCKED", 1, 1))
        self.assertTrue(device.captures)
        self.assertTrue(all(capture["mode"] == "USER_ROTATION_LOCKED" and capture["rotation"] == 1
                            for capture in device.captures))

    def test_applied_rotation_is_observed_before_capture_can_restore_old_angle(self):
        ui, device, _ = self.make_ui(mode="USER_ROTATION_LOCKED")
        ui.rotate(1)
        self.assertEqual(device.captures[0]["rotation"], 1)
        self.assertEqual(device.current, 1)
        attempt = ui.observations["rotations"]["last_request"]
        self.assertEqual(attempt["first_state"]["state"]["current_rotation"], 0)
        self.assertEqual(attempt["last_state"]["phase"], "after_capture")
        self.assertEqual(attempt["last_state"]["state"]["current_rotation"], 1)

    def test_both_landscape_and_portrait_require_distinct_fresh_xml_and_postcapture_state(self):
        ui, device, _ = self.make_ui()
        ui.rotate(1)
        first = ui.observations["rotations"]["last_request"]
        ui.rotate(0)
        second = ui.observations["rotations"]["last_request"]
        self.assertEqual(device.lock_count, 2)
        self.assertEqual([row["requested_rotation"] for row in (first, second)], [1, 0])
        self.assertEqual([row["status"] for row in (first, second)], ["passed", "passed"])
        self.assertNotEqual(first["last_snapshot"]["sequence"], second["last_snapshot"]["sequence"])
        self.assertNotEqual(first["last_snapshot"]["parsed_xml_sha256"], second["last_snapshot"]["parsed_xml_sha256"])
        self.assertEqual(second["last_state"]["state"]["current_rotation"], 0)

    def test_parser_selects_current_default_display_not_another_display_or_history(self):
        free = rotation_dump(mode="USER_ROTATION_FREE", current=0, requested=1)
        other = rotation_dump(current=1, display=7).split("\n", 1)[1]
        actual = SMOKE.default_display_rotation_state(free + other)
        self.assertEqual(actual, {"display_id": 0, "current_rotation": 0, "deferred_pause_count": 0,
                                  "user_rotation_mode": "USER_ROTATION_FREE", "user_rotation": 1})
        historical = free + "    RotationLockHistory\n      mode=USER_ROTATION_LOCKED, rotation=ROTATION_90\n"
        self.assertEqual(SMOKE.default_display_rotation_state(historical), actual)
        self.assertIsNone(SMOKE.default_display_rotation_state(other))

    def test_parser_rejects_ambiguous_missing_malformed_and_incomplete_observations(self):
        good = rotation_dump()
        bad = [None, b"bytes", "", good.replace("Display #0", "Display #2"),
               good + good.split("\n", 1)[1], good.replace("  FoldController", "  DisplayRotation\n  FoldController"),
               good.replace("    mRotation=1 mDeferredRotationPauseCount=0\n", ""),
               good.replace("mRotation=1", "mRotation=True"),
               good.replace("mRotation=1 mDeferredRotationPauseCount=0", "mRotation=1 mDeferredRotationPauseCount=0\n    mRotation=0 mDeferredRotationPauseCount=0"),
               good.replace("mRotation=1 mDeferredRotationPauseCount=0", "mRotation=1 mDeferredRotationPauseCount=0\n    mRotation=malformed"),
               good.replace("  FoldController", "    mUserRotationMode=malformed\n  FoldController"),
               good.replace("USER_ROTATION_LOCKED", "USER_ROTATION_UNKNOWN"),
               good.replace("ROTATION_90", "ROTATION_45"), good + "*** DUMP TIMEOUT (3000ms) ***\n",
               good + "Error dumping service info: synthetic failure\n",
               good + "\x00", good + "x" * SMOKE.UI_ROTATION_STATE_TEXT_LIMIT]
        for text in bad:
            with self.subTest(text=repr(text)[:100]):
                self.assertIsNone(SMOKE.default_display_rotation_state(text))

    def test_lock_noise_nonzero_boolean_or_nontext_cannot_open_state_or_capture(self):
        for code, stdout, stderr in [(1, "", ""), (False, "", ""), (0, "diagnostic", ""),
                                     (0, "", "diagnostic"), (0, b"", ""), (0, "", None)]:
            with self.subTest(code=code, stdout=stdout, stderr=stderr):
                ui, device, _ = self.make_ui()
                device.lock_result = subprocess.CompletedProcess([], code, stdout, stderr)
                with self.assertRaises(SMOKE.SmokeFailure):
                    ui.rotate(1)
                self.assertEqual(len(device.calls), 1)
                ui.snapshot.assert_not_called()
                self.assertEqual(ui.observations["rotations"]["last_request"]["status"], "failed")

    def test_lock_timeout_and_owned_device_failure_preserve_original_error(self):
        for original in [subprocess.TimeoutExpired("owned lock", 5, output=b"partial", stderr=b"detail"),
                         SMOKE.SmokeFailure("owned emulator exited")]:
            with self.subTest(error=type(original).__name__):
                ui, device, _ = self.make_ui()
                device.lock_result = original
                with self.assertRaises(type(original)) as seen:
                    ui.rotate(1)
                self.assertIs(seen.exception, original)
                self.assertEqual(len(device.calls), 1)
                ui.snapshot.assert_not_called()
                command = ui.observations["rotations"]["last_request"]["lock_command"]
                if isinstance(original, subprocess.TimeoutExpired):
                    self.assertEqual(command["stdout"]["prefix"], "partial")
                    self.assertEqual(command["stderr"]["prefix"], "detail")

    def test_state_command_failure_is_terminal_even_with_valid_looking_fields(self):
        for code, stdout, stderr in [(1, rotation_dump(), ""), (False, rotation_dump(), ""),
                                     (0, rotation_dump(), "diagnostic"),
                                     (0, rotation_dump() + "*** DUMP TIMEOUT ***\n", ""),
                                     (0, rotation_dump() + "x" * SMOKE.UI_ROTATION_STATE_TEXT_LIMIT, "")]:
            with self.subTest(code=code, length=len(stdout), stderr=stderr):
                ui, device, _ = self.make_ui()
                device.query_result = subprocess.CompletedProcess([], code, stdout, stderr)
                with self.assertRaises(SMOKE.SmokeFailure):
                    ui.rotate(1)
                self.assertEqual(len(device.calls), 2)
                ui.snapshot.assert_not_called()
                self.assertEqual(ui.observations["rotations"]["last_request"]["state_probes"], 1)

    def test_free_wrong_angle_or_deferred_rotation_never_starts_uiautomation(self):
        for text in [rotation_dump(mode="USER_ROTATION_FREE"), rotation_dump(current=0),
                     rotation_dump(requested=0), rotation_dump(paused=1)]:
            with self.subTest(text=text):
                ui, device, _ = self.make_ui()
                device.query_result = subprocess.CompletedProcess([], 0, text, "")
                started = self.clock.now
                with self.assertRaisesRegex(SMOKE.SmokeFailure, "requested rotation"):
                    ui.rotate(1)
                self.assertEqual(device.lock_count, 1)
                ui.snapshot.assert_not_called()
                self.assertAlmostEqual(self.clock.now - started, 30)

    def test_late_state_result_does_not_start_uiautomation(self):
        ui, device, _ = self.make_ui()
        device.query_result = subprocess.CompletedProcess([], 0, rotation_dump(), "")
        original_adb = device.adb

        def delayed(*args, **kwargs):
            result = original_adb(*args, **kwargs)
            if args == SMOKE.ROTATION_STATE_QUERY:
                self.clock.now = 30
            return result

        device.adb = delayed
        with self.assertRaisesRegex(SMOKE.SmokeFailure, "after its deadline"):
            ui.rotate(1)
        ui.snapshot.assert_not_called()

    def test_snapshot_failure_is_terminal_and_never_relocks(self):
        ui, device, _ = self.make_ui()
        original = ET.ParseError("first fresh XML was malformed")
        device.capture_error = original
        with self.assertRaises(ET.ParseError) as seen:
            ui.rotate(1)
        self.assertIs(seen.exception, original)
        self.assertEqual(device.lock_count, 1)
        self.assertEqual(ui.snapshot.call_count, 1)
        self.assertEqual(ui.observations["rotations"]["last_request"]["status"], "failed")

    def test_wrong_rotation_foreign_package_or_nonpositive_bounds_never_pass(self):
        for result in [lambda _: hierarchy(0), lambda _: hierarchy(1, package="foreign.example"),
                       lambda _: hierarchy(1, bounds="[0,0][0,1280]")]:
            with self.subTest(result=result):
                ui, device, _ = self.make_ui()
                device.capture_result = result
                device.capture_delay = 0.1
                with self.assertRaises(SMOKE.SmokeFailure):
                    ui.rotate(1)
                self.assertEqual(device.lock_count, 1)
                self.assertEqual(ui.observations["rotations"]["last_request"]["status"], "failed")

    def test_postcapture_reversion_cannot_supply_success_or_another_lock(self):
        ui, device, _ = self.make_ui()
        device.revert_after_capture = True
        with self.assertRaisesRegex(SMOKE.SmokeFailure, "requested rotation"):
            ui.rotate(1)
        self.assertEqual(device.lock_count, 1)
        self.assertEqual(ui.snapshot.call_count, 1)
        attempt = ui.observations["rotations"]["last_request"]
        self.assertEqual(attempt["last_snapshot"]["rotation"], "1")
        self.assertEqual(attempt["last_post_capture_state"]["phase"], "after_capture")
        self.assertEqual(attempt["last_post_capture_state"]["state"]["user_rotation_mode"],
                         "USER_ROTATION_FREE")
        self.assertGreater(attempt["last_state"]["sequence"],
                           attempt["last_post_capture_state"]["sequence"])

    def test_all_commands_and_fresh_snapshot_share_the_original_thirty_second_budget(self):
        ui, device, _ = self.make_ui()
        device.query_result = subprocess.CompletedProcess([], 0, rotation_dump(mode="USER_ROTATION_FREE"), "")
        with self.assertRaises(SMOKE.SmokeFailure):
            ui.rotate(1)
        self.assertTrue(all(0 < call["timeout"] <= min(5, 30 - call["started"])
                            for call in device.calls))
        self.assertTrue(all(call["started"] < 30 for call in device.calls))
        self.assertAlmostEqual(self.clock.now, 30)
        ui, device, _ = self.make_ui()
        start = self.clock.now
        ui.rotate(0)
        self.assertEqual(device.captures[0]["deadline"], start + 30)

    def test_state_timeout_retains_partial_original_without_another_query(self):
        ui, device, _ = self.make_ui()
        original = subprocess.TimeoutExpired("owned state query", 5, output=b"partial display", stderr=b"partial error")
        device.query_result = original
        with self.assertRaises(subprocess.TimeoutExpired) as seen:
            ui.rotate(1)
        self.assertIs(seen.exception, original)
        self.assertEqual(len(device.calls), 2)
        ui.snapshot.assert_not_called()
        command = ui.observations["rotations"]["last_request"]["last_state"]["command"]
        self.assertEqual(command["stdout_tail"], "partial display")
        self.assertEqual(command["stderr"]["prefix"], "partial error")

    def test_diagnostics_bound_history_and_preserve_first_failure_across_later_success(self):
        ui, device, _ = self.make_ui()
        device.query_result = subprocess.CompletedProcess([], 0, rotation_dump(mode="USER_ROTATION_FREE"), "")
        with self.assertRaises(SMOKE.SmokeFailure):
            ui.rotate(1)
        rotations = ui.observations["rotations"]
        first = rotations["first_failed_request"]
        self.assertGreater(first["state_probes"], 16)
        self.assertEqual(len(first["recent_states"]), 16)
        self.assertEqual(first["first_state"]["sequence"], 1)
        self.assertEqual(first["last_state"]["sequence"], first["state_probes"])
        self.assertTrue(all("command" not in row for row in first["recent_states"]))
        device.query_result = None
        for requested in [1, 0, 1, 0, 1, 0, 1, 0, 1]:
            ui.rotate(requested)
        self.assertEqual(len(rotations["recent_requests"]), 8)
        self.assertIs(rotations["first_failed_request"], first)
        self.assertEqual(rotations["last_request"]["status"], "passed")
        self.assertEqual(first["status"], "failed")

    def test_fresh_xml_completing_at_deadline_is_not_accepted(self):
        ui, device, _ = self.make_ui()

        def late_capture(*, deadline=None):
            self.clock.now = deadline
            return hierarchy(1)

        ui.snapshot = mock.Mock(side_effect=late_capture)
        with self.assertRaises(SMOKE.SmokeFailure):
            ui.rotate(1)
        self.assertEqual(ui.snapshot.call_count, 1)
        self.assertFalse(any(row["phase"] == "after_capture" and row.get("state")
                             for row in ui.observations["rotations"]["last_request"]["recent_states"]))

    def test_wrong_then_correct_xml_requires_a_new_capture_and_postcondition(self):
        ui, device, _ = self.make_ui()
        results = iter([hierarchy(0), hierarchy(1)])
        device.capture_result = lambda _: next(results)
        ui.rotate(1)
        self.assertEqual(ui.snapshot.call_count, 2)
        self.assertEqual(device.lock_count, 1)
        attempt = ui.observations["rotations"]["last_request"]
        self.assertEqual(attempt["last_snapshot"]["sequence"], 2)
        self.assertEqual(attempt["last_state"]["phase"], "after_capture")
        self.assertEqual(attempt["status"], "passed")

    def test_invalid_rotation_is_rejected_before_any_guest_command(self):
        for value in [True, False, -1, 4, "1", 1.0, None]:
            with self.subTest(value=value):
                ui, device, _ = self.make_ui()
                with self.assertRaises(SMOKE.SmokeFailure):
                    ui.rotate(value)
                self.assertFalse(device.calls)
                ui.snapshot.assert_not_called()
                self.assertNotIn("rotations", ui.observations)


if __name__ == "__main__":
    unittest.main()
