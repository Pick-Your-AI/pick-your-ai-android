"""Host-only navigation models; no Android, SDK, input or privileged command runs."""

import copy
import importlib.util
from pathlib import Path
import subprocess
import unittest
from unittest.mock import Mock, patch
import xml.etree.ElementTree as ET


ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("navigation_smoke", ROOT / "scripts/android_emulator_smoke.py")
smoke = importlib.util.module_from_spec(spec)
spec.loader.exec_module(smoke)


def node(text="", kind="android.widget.TextView", package=smoke.PACKAGE, box="[20,20][280,64]"):
    return ET.Element("node", text=text, **{
        "class": kind, "package": package, "bounds": box, "enabled": "true"})


class ScrollDocument:
    """Synthetic clipped viewports with separately modelled wheel and touch-fling paths.

    The chosen document/factor/fling distance exposes a navigation counterexample;
    it does not reconstruct missing XML or measure an emulator's movement.
    """

    def __init__(self, *, target=550, height=400, extent=2200, offset=0):
        self.target, self.height, self.extent, self.offset = target, height, extent, offset
        self.commands, self.snapshots, self.offsets = [], 0, []

    def move(self, delta):
        self.offset = max(0, min(self.extent - self.height, self.offset + delta))

    def adb(self, *arguments, timeout=30, check=False):
        self.commands.append(arguments)
        if arguments[:4] != ("shell", "input", "mouse", "scroll"):
            raise AssertionError("Unexpected non-wheel device operation")
        if arguments[6] != "--axis" or not arguments[7].startswith("VSCROLL,"):
            raise AssertionError("Unexpected wheel axis")
        self.move(-64 * int(arguments[7].split(",")[1]))
        return subprocess.CompletedProcess(arguments, 0, "", "")

    def shell(self, *arguments):
        # Exercise the real old scroll implementation in the baseline witness.
        self.commands.append(arguments)
        if arguments[:2] != ("input", "swipe"):
            raise AssertionError("Unexpected legacy device operation")
        self.move(1000 if int(arguments[3]) > int(arguments[5]) else -1000)
        return ""

    def snapshot(self):
        self.snapshots += 1
        self.offsets.append(self.offset)
        root = ET.Element("hierarchy", rotation="0")
        container = node(kind="android.widget.ScrollView", box=f"[0,0][300,{self.height}]")
        root.append(container)
        container.append(node("Synthetic viewport " + str(self.offset)))
        top, bottom = self.target - self.offset, self.target + 44 - self.offset
        if bottom > 0 and top < self.height:
            container.append(node("SAVE LOCALLY", smoke.BUTTON,
                                  box=f"[20,{max(0, top)}][280,{min(self.height, bottom)}]"))
        return root


class UiNavigationGuardTest(unittest.TestCase):
    def test_wheel_scan_observes_intervening_target_that_touch_fling_skips(self):
        document, receipt = ScrollDocument(), {}
        ui = smoke.Ui(document, Path("unused"), receipt)
        with patch.object(ui, "snapshot", side_effect=document.snapshot):
            found = ui.text("Save locally", kind=smoke.BUTTON)
        self.assertEqual("SAVE LOCALLY", found.get("text"))
        self.assertEqual([0, 0, 0, 128, 256], document.offsets)
        self.assertEqual(3, len(document.commands))
        self.assertTrue(all(command[:4] == ("shell", "input", "mouse", "scroll") for command in document.commands))
        search = receipt["ui_hierarchy_observations"]["navigation"]["last_search"]
        self.assertEqual("found", search["status"])
        self.assertEqual(["scrolled", "unchanged", "scrolled", "scrolled", "found"],
                         [frame["result"] for frame in search["recent_frames"]])

    def test_viewport_after_the_sixty_fourth_event_is_checked_before_failure(self):
        # Before the final event, the control is just outside the viewport.
        target = 63 * 128 + 400 + 1
        document, receipt = ScrollDocument(target=target, extent=12000), {}
        ui = smoke.Ui(document, Path("unused"), receipt)
        with patch.object(ui, "snapshot", side_effect=document.snapshot):
            self.assertEqual("SAVE LOCALLY", ui.text("Save locally", kind=smoke.BUTTON).get("text"))
        down = [c for c in document.commands if c[-1] == "VSCROLL,-2"]
        self.assertEqual(64, len(down))
        self.assertEqual(8192, document.offsets[-1])
        search = receipt["ui_hierarchy_observations"]["navigation"]["last_search"]
        self.assertEqual(67, search["snapshots"])
        self.assertEqual({"direction": "down", "step": 64, "result": "found"},
                         {key: search["recent_frames"][-1][key] for key in ("direction", "step", "result")})
        self.assertNotIn("wheel_command", search["recent_frames"][-1])

    def test_absent_target_caps_each_direction_without_an_unchecked_last_event(self):
        document, receipt = ScrollDocument(target=100000, extent=50000, offset=15000), {}
        ui = smoke.Ui(document, Path("unused"), receipt)
        with patch.object(ui, "snapshot", side_effect=document.snapshot):
            with self.assertRaisesRegex(smoke.SmokeFailure, "UI control not found: Save locally"):
                ui.text("Save locally", kind=smoke.BUTTON)
        self.assertEqual(64, sum(c[-1] == "VSCROLL,2" for c in document.commands))
        self.assertEqual(64, sum(c[-1] == "VSCROLL,-2" for c in document.commands))
        self.assertEqual(130, document.snapshots)
        search = receipt["ui_hierarchy_observations"]["navigation"]["last_search"]
        self.assertEqual("failed", search["status"])
        self.assertEqual("step_limit", search["recent_frames"][-1]["result"])
        self.assertNotIn("wheel_command", search["recent_frames"][-1])
        self.assertEqual(16, len(search["recent_frames"]))

    def test_repeat_end_resets_before_down_scan_and_never_reuses_a_stale_match(self):
        document, receipt = ScrollDocument(target=100000), {}
        ui = smoke.Ui(document, Path("unused"), receipt)
        with patch.object(ui, "snapshot", side_effect=document.snapshot):
            with self.assertRaises(smoke.SmokeFailure):
                ui.text("Save locally", kind=smoke.BUTTON)
        self.assertEqual(1, sum(c[-1] == "VSCROLL,2" for c in document.commands))
        self.assertLess(sum(c[-1] == "VSCROLL,-2" for c in document.commands), 64)
        self.assertEqual(1800, document.offsets[-1])
        search = receipt["ui_hierarchy_observations"]["navigation"]["last_search"]
        self.assertEqual("unchanged", search["recent_frames"][-1]["result"])

    def test_clipped_button_is_scrolled_out_of_a_reported_bar_before_tapping(self):
        class BarDocument(ScrollDocument):
            def snapshot(self):
                root = super().snapshot()
                bar = node(box=self.bar_box, kind="android.view.View")
                bar.set("resource-id", self.bar_id)
                root.append(bar)
                return root

            def shell(self, *arguments):
                if arguments[:2] == ("input", "tap"):
                    self.commands.append(arguments)
                    self.taps.append(tuple(map(int, arguments[2:])))
                    return ""
                return super().shell(*arguments)

        for target, offset, bar_box, bar_id, covered_y in (
                (550, 200, "[0,340][300,400]", "android:id/navigationBarBackground", (340, 400)),
                (50, 25, "[0,0][300,60]", "android:id/statusBarBackground", (0, 60))):
            with self.subTest(bar_id=bar_id):
                document, receipt = BarDocument(target=target, offset=offset), {}
                document.bar_box, document.bar_id, document.taps = bar_box, bar_id, []
                ui = smoke.Ui(document, Path("unused"), receipt)
                with patch.object(ui, "snapshot", side_effect=document.snapshot):
                    ui.click("Save locally")
                self.assertEqual(1, len(document.taps))
                self.assertFalse(covered_y[0] <= document.taps[0][1] < covered_y[1])
                self.assertGreater(document.snapshots, 1)
                search = receipt["ui_hierarchy_observations"]["navigation"]["last_search"]
                self.assertTrue(search["recent_frames"][0]["matched_center_in_system_bar"])
                self.assertFalse(search["recent_frames"][-1]["matched_center_in_system_bar"])
                self.assertEqual("found", search["recent_frames"][-1]["result"])

    def test_wheel_coordinates_exclude_reported_portrait_and_landscape_bars(self):
        for viewport, nav_box, status_box, expected in (
                ("[0,0][720,1280]", "[0,1196][720,1280]", "[0,0][720,42]", ("708", "619")),
                ("[0,0][1280,720]", "[1196,0][1280,720]", "[0,0][1280,42]", ("1184", "381"))):
            for reverse in (False, True):
                with self.subTest(viewport=viewport, reverse=reverse):
                    root = ET.Element("hierarchy", rotation="0")
                    root.append(node(kind="android.widget.ScrollView", box=viewport))
                    bars = []
                    for identity, box in (("android:id/navigationBarBackground", nav_box),
                                          ("android:id/statusBarBackground", status_box)):
                        bar = node(box=box, kind="android.view.View")
                        bar.set("resource-id", identity)
                        bars.append(bar)
                    root.extend(reversed(bars) if reverse else bars)
                    device, observed = Mock(spec=smoke.Device), {}
                    device.adb.return_value = subprocess.CompletedProcess([], 0, "", "")
                    self.assertTrue(smoke.Ui(device, Path("unused"), {}).scroll(root, True, observation=observed))
                    device.adb.assert_called_once_with("shell", "input", "mouse", "scroll", *expected,
                                                       "--axis", "VSCROLL,-2", timeout=30, check=False)
                    self.assertNotEqual(observed["scroll_view_bounds"], observed["unobscured_scroll_bounds"])

    def test_foreign_duplicate_invalid_or_ambiguous_bars_fail_before_device_input(self):
        cases = [({"package": "foreign.app"}, False), ({}, True),
                 ({"bounds": "malformed"}, False), ({"bounds": "[0,340][0,400]"}, False),
                 ({"bounds": "[50,50][100,100]"}, False), ({"bounds": "[0,0][300,400]"}, False)]
        for attributes, duplicate in cases:
            with self.subTest(attributes=attributes, duplicate=duplicate):
                root = ScrollDocument().snapshot()
                bar = node(box="[0,340][300,400]", kind="android.view.View")
                bar.set("resource-id", "android:id/navigationBarBackground")
                bar.attrib.update(attributes)
                root.append(bar)
                if duplicate:
                    root.append(copy.deepcopy(bar))
                device, receipt = Mock(spec=smoke.Device), {}
                ui = smoke.Ui(device, Path("unused"), receipt)
                with patch.object(ui, "snapshot", return_value=root):
                    with self.assertRaises(smoke.SmokeFailure):
                        ui.text("Save locally", kind=smoke.BUTTON)
                device.adb.assert_not_called()

    def test_occluded_wait_cannot_reuse_an_earlier_unobscured_target(self):
        device, receipt = Mock(spec=smoke.Device), {}
        ui = smoke.Ui(device, Path("unused"), receipt)
        root = ET.Element("hierarchy", rotation="0")
        target = node("Save locally", smoke.BUTTON, box="[20,300][280,338]")
        bar = node(box="[0,340][300,400]", kind="android.view.View")
        bar.set("resource-id", "android:id/navigationBarBackground")
        root.extend([target, bar])
        with patch.object(ui, "snapshot", return_value=root):
            self.assertEqual("Save locally", ui.text("Save locally", kind=smoke.BUTTON).get("text"))
        target.set("bounds", "[20,350][280,394]")
        with patch.object(ui, "snapshot", return_value=root) as snapshot, patch.object(smoke.time, "sleep"):
            with self.assertRaises(smoke.SmokeFailure):
                ui.text("Save locally", kind=smoke.BUTTON, scroll=False)
        self.assertEqual(8, snapshot.call_count)
        device.adb.assert_not_called()
        search = receipt["ui_hierarchy_observations"]["navigation"]["last_search"]
        self.assertEqual("failed", search["status"])
        self.assertTrue(all(frame["matched_center_in_system_bar"] for frame in search["recent_frames"]))

    def test_non_scrolling_lookup_keeps_eight_fresh_waits_and_no_device_input(self):
        document, receipt = ScrollDocument(target=100000), {}
        ui = smoke.Ui(document, Path("unused"), receipt)
        with patch.object(ui, "snapshot", side_effect=document.snapshot), patch.object(smoke.time, "sleep") as sleep:
            with self.assertRaises(smoke.SmokeFailure):
                ui.text("Save locally", kind=smoke.BUTTON, scroll=False)
        self.assertEqual(8, document.snapshots)
        self.assertEqual([], document.commands)
        self.assertEqual([((0.25,), {})] * 8, [(c.args, c.kwargs) for c in sleep.call_args_list])

    def test_ambiguous_target_or_owned_scroll_container_fails_before_any_input(self):
        for ambiguous_target in (True, False):
            with self.subTest(ambiguous_target=ambiguous_target):
                device, receipt = Mock(spec=smoke.Device), {}
                root = ET.Element("hierarchy", rotation="0")
                kind = smoke.BUTTON if ambiguous_target else "android.widget.ScrollView"
                root.extend([node("Save locally" if ambiguous_target else "", kind) for _ in range(2)])
                ui = smoke.Ui(device, Path("unused"), receipt)
                with patch.object(ui, "snapshot", return_value=root):
                    with self.assertRaisesRegex(smoke.SmokeFailure, "Ambiguous UI selector"):
                        ui.text("Save locally", kind=smoke.BUTTON)
                device.adb.assert_not_called()

    def test_foreign_invisible_or_invalid_scroll_bounds_never_receive_wheel_input(self):
        containers = [node(kind="android.widget.ScrollView", package="foreign.app"),
                      node(kind="android.widget.ScrollView", box="[0,0][0,100]"),
                      node(kind="android.widget.ScrollView", box="[0,30][100,20]"),
                      node(kind="android.widget.ScrollView", box="malformed")]
        for container in containers:
            with self.subTest(container=container.attrib):
                device, receipt = Mock(spec=smoke.Device), {}
                root = ET.Element("hierarchy", rotation="0")
                root.extend([container, node("Save locally", smoke.BUTTON, package="foreign.app")])
                ui = smoke.Ui(device, Path("unused"), receipt)
                with patch.object(ui, "snapshot", return_value=root):
                    with self.assertRaises(smoke.SmokeFailure):
                        ui.text("Save locally", kind=smoke.BUTTON)
                device.adb.assert_not_called()

    def test_wheel_point_stays_inside_observed_bounds_and_direction_is_explicit(self):
        for box, expected in (("[10,30][710,1030]", ("698", "530")),
                              ("[10,30][11,31]", ("10", "30"))):
            for down, axis in ((False, "VSCROLL,2"), (True, "VSCROLL,-2")):
                with self.subTest(box=box, down=down):
                    device, observed = Mock(spec=smoke.Device), {}
                    device.adb.return_value = subprocess.CompletedProcess([], 0, "", "")
                    root = ET.Element("hierarchy", rotation="0")
                    root.append(node(kind="android.widget.ScrollView", box=box))
                    self.assertTrue(smoke.Ui(device, Path("unused"), {}).scroll(root, down, observation=observed))
                    device.adb.assert_called_once_with("shell", "input", "mouse", "scroll", *expected,
                                                       "--axis", axis, timeout=30, check=False)
                    self.assertEqual(0, observed["exit_code"])

    def test_wheel_command_noise_nonzero_and_boolean_status_cannot_supply_ui_success(self):
        xml = '<hierarchy rotation="0"><node text="Save locally" /></hierarchy>'
        for code, stdout, stderr in ((1, "", ""), (False, "", ""), (0, "", "diagnostic"), (0, xml, "")):
            with self.subTest(code=code, stdout=stdout, stderr=stderr):
                device, receipt = Mock(spec=smoke.Device), {}
                device.adb.return_value = subprocess.CompletedProcess([], code, stdout, stderr)
                ui = smoke.Ui(device, Path("unused"), receipt)
                with patch.object(ui, "snapshot", return_value=ScrollDocument().snapshot()) as snapshot:
                    with self.assertRaisesRegex(smoke.SmokeFailure, "wheel command"):
                        ui.text("Save locally", kind=smoke.BUTTON)
                self.assertEqual(1, snapshot.call_count)
                first = receipt["ui_hierarchy_observations"]["navigation"]["first_failed_search"]
                command = first["recent_frames"][-1]["wheel_command"]
                self.assertEqual(stdout, command["stdout"]["prefix"])
                self.assertEqual(stderr, command["stderr"]["prefix"])

    def test_wheel_timeout_and_owned_process_failure_preserve_original_without_another_snapshot(self):
        failures = [subprocess.TimeoutExpired(["owned-wheel"], 30, output=b"partial", stderr=b"diagnostic"),
                    smoke.SmokeFailure("Owned emulator process stopped")]
        for original in failures:
            with self.subTest(original=type(original).__name__):
                device, receipt = Mock(spec=smoke.Device), {}
                device.adb.side_effect = original
                ui = smoke.Ui(device, Path("unused"), receipt)
                with patch.object(ui, "snapshot", return_value=ScrollDocument().snapshot()) as snapshot:
                    with self.assertRaises(type(original)) as raised:
                        ui.text("Save locally", kind=smoke.BUTTON)
                self.assertIs(original, raised.exception)
                self.assertEqual(1, snapshot.call_count)
                command = receipt["ui_hierarchy_observations"]["navigation"]["last_search"]["recent_frames"][-1]["wheel_command"]
                if isinstance(original, subprocess.TimeoutExpired):
                    self.assertEqual(30, command["command_timeout_seconds"])
                    self.assertEqual("partial", command["stdout"]["prefix"])
                else:
                    self.assertIn("Owned emulator process stopped", command["error"])

    def test_failed_fresh_snapshot_is_not_retried_or_replaced_by_navigation(self):
        device, receipt = Mock(spec=smoke.Device), {}
        original = smoke.SmokeFailure("Fresh hierarchy rejected")
        ui = smoke.Ui(device, Path("unused"), receipt)
        with patch.object(ui, "snapshot", side_effect=original) as snapshot:
            with self.assertRaises(smoke.SmokeFailure) as raised:
                ui.text("Save locally", kind=smoke.BUTTON)
        self.assertIs(original, raised.exception)
        self.assertEqual(1, snapshot.call_count)
        device.adb.assert_not_called()
        self.assertEqual("failed", receipt["ui_hierarchy_observations"]["navigation"]["first_failed_search"]["status"])

    def test_navigation_retains_first_failure_with_bounded_searches_frames_and_labels(self):
        device, receipt = Mock(spec=smoke.Device), {}
        ui = smoke.Ui(device, Path("unused"), receipt)
        missing = ET.Element("hierarchy", rotation="0")
        missing.extend(node("synthetic" * 100) for _ in range(30))
        with patch.object(ui, "snapshot", return_value=missing):
            with self.assertRaises(smoke.SmokeFailure):
                ui.text("missing" * 100, kind=smoke.BUTTON)
        navigation = receipt["ui_hierarchy_observations"]["navigation"]
        first = navigation["first_failed_search"]
        preserved = copy.deepcopy(first)
        present = ET.Element("hierarchy", rotation="0")
        present.append(node("Save locally", smoke.BUTTON))
        with patch.object(ui, "snapshot", return_value=present):
            for _ in range(12):
                self.assertEqual("Save locally", ui.text("Save locally", kind=smoke.BUTTON).get("text"))
        self.assertEqual(preserved, navigation["first_failed_search"])
        self.assertEqual(13, navigation["attempted"])
        self.assertEqual(8, len(navigation["recent_searches"]))
        self.assertEqual("found", navigation["last_search"]["status"])
        self.assertEqual(120, len(first["description"]))
        frame = first["recent_frames"][0]
        self.assertEqual(30, frame["visible_label_count"])
        self.assertTrue(frame["visible_labels_truncated"])
        self.assertEqual(24, len(frame["visible_labels"]))
        self.assertTrue(all(len(text) == 120 for text in frame["visible_labels"]))


if __name__ == "__main__":
    unittest.main()
