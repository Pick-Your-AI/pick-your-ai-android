"""Offline synthetic scroll-capability controls; no Android or host input runs."""

import copy
import contextlib
import importlib.util
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import Mock, patch
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("scroll_target_smoke", ROOT / "scripts/android_emulator_smoke.py")
smoke = importlib.util.module_from_spec(spec)
spec.loader.exec_module(smoke)
SCROLL = "android.widget.ScrollView"


def node(kind=SCROLL, *, box="[46,149][673,1059]", scrollable="true", enabled="true", text=""):
    return ET.Element("node", **{"class": kind, "package": smoke.PACKAGE, "bounds": box,
        "scrollable": scrollable, "enabled": enabled, "text": text})


def dialog_hierarchy():
    """Small authored model of the observed two-class shape; no original XML is embedded."""
    root = ET.Element("hierarchy", rotation="0")
    root.append(node(smoke.TEXT_VIEW, box="[88,102][631,149]", scrollable="false", text="Add new selection"))
    content = node()
    panel = node(box="[46,1059][673,1168]", scrollable="false")
    panel.set("resource-id", "android:id/buttonPanel")
    root.extend([content, panel])
    return root, content, panel


def typed_form_hierarchy():
    """A fully visible typed form; viewport observation does not imply scroll capability."""
    root, content, panel = dialog_hierarchy()
    label = "data_source · primary · selection_" + "a" * 32
    root[0].set("text", label)
    content.set("scrollable", "false")
    for index, field in enumerate((smoke.DATA_SOURCE_REFERENCE_LABEL, smoke.DATA_SOURCE_MINUTES_LABEL)):
        start = 200 + index * 150
        content.append(node(smoke.TEXT_VIEW, box=f"[60,{start}][650,{start + 30}]", scrollable="false", text=field))
        supply = node(smoke.CHECKBOX, box=f"[60,{start + 30}][650,{start + 60}]", scrollable="false",
                      text="Supply " + field)
        supply.attrib.update(checkable="true", checked="false")
        content.append(supply)
        content.append(node(smoke.EDITOR, box=f"[60,{start + 60}][650,{start + 100}]", scrollable="false",
                            enabled="false", text=smoke.DATA_SOURCE_OPTIONAL_HINT))
    content.append(node(smoke.TEXT_VIEW, box="[60,600][650,640]", scrollable="false", text=smoke.DATA_SOURCE_ENABLED_LABEL))
    content.append(node(smoke.BUTTON, box="[60,640][650,700]", scrollable="false", text="Not supplied"))
    panel.append(node(smoke.BUTTON, box="[312,1066][652,1161]", scrollable="false", text=smoke.COMPONENT_COMMIT))
    return root, content, panel, {"draft_id": "draft-00000000-0000-0000-0000-000000000000",
        "selection_id": "selection_" + "a" * 32, "selection_label": label, "selection_enabled": False}


def phase_form_hierarchy(phase):
    root, content, panel, identity = typed_form_hierarchy()
    if phase != "initial":
        supply, editor = smoke.data_source_optional_controls(root, smoke.DATA_SOURCE_MINUTES_LABEL)
        supply.set("checked", "true")
        editor.set("enabled", "true")
        editor.set("text", "60")
        smoke.data_source_enabled_control(root).set("text", "True")
    return root, content, panel, identity


class ScrollTargetGuardTest(unittest.TestCase):
    def setUp(self):
        self.stack = contextlib.ExitStack()
        self.addCleanup(self.stack.close)
        for name in ("run", "Popen"):
            self.stack.enter_context(patch.object(smoke.subprocess, name,
                side_effect=AssertionError("Host process execution is prohibited")))
        for name in ("fork", "kill"):
            self.stack.enter_context(patch.object(smoke.os, name,
                side_effect=AssertionError("Host fork/signals are prohibited")))

    def test_add_form_editor_search_scrolls_content_without_selecting_non_scrollable_button_panel(self):
        class FormDocument:
            def __init__(self):
                self.bottom = False
                self.commands = []
            def snapshot(self):
                root, content, panel = dialog_hierarchy()
                label = node(smoke.TEXT_VIEW, box="[46,1041][673,1059]", scrollable="false",
                             text="Settings JSON object (maximum 16,384 characters)")
                content.append(label)
                if self.bottom:
                    label.set("bounds", "[46,400][673,440]")
                    content.append(node(smoke.EDITOR, box="[46,440][673,530]", scrollable="false", text="{}"))
                return root
            def adb(self, *args, timeout, check):
                self.commands.append(args)
                if args[:4] != ("shell", "input", "mouse", "scroll"):
                    raise AssertionError("Only the synthetic wheel operation is permitted")
                self.bottom = args[-1] == "VSCROLL,-2"
                return subprocess.CompletedProcess(args, 0, "", "")
        document = FormDocument()
        ui = smoke.Ui(document, Path("unused-host-only-output"), {})
        with patch.object(ui, "snapshot", side_effect=document.snapshot):
            editor = ui.editor("Settings JSON object (maximum 16,384 characters)")
        self.assertEqual("{}", editor.get("text"))
        self.assertEqual([
            ("shell", "input", "mouse", "scroll", "661", "604", "--axis", "VSCROLL,2"),
            ("shell", "input", "mouse", "scroll", "661", "604", "--axis", "VSCROLL,-2"),
        ], document.commands)

    def test_scroll_target_is_based_on_capabilities_not_order_id_or_panel_name(self):
        for kind in (SCROLL, smoke.LIST_VIEW):
            for reverse in (False, True):
                with self.subTest(kind=kind, reverse=reverse):
                    root, content, panel = dialog_hierarchy()
                    content.set("class", kind)
                    panel.set("class", kind)
                    # A name does not grant input authority or veto an actually eligible target.
                    content.set("resource-id", "android:id/buttonPanel")
                    panel.set("resource-id", "")
                    if reverse:
                        root[:] = list(reversed(list(root)))
                    device, observed = Mock(spec=smoke.Device), {}
                    device.adb.return_value = subprocess.CompletedProcess([], 0, "", "")
                    self.assertTrue(smoke.Ui(device, Path("unused"), {}).scroll(root, True,
                                    scroll_kind=kind, observation=observed))
                    self.assertEqual([46, 149, 673, 1059], observed["scroll_view_bounds"])
                    device.adb.assert_called_once_with("shell", "input", "mouse", "scroll", "661", "604",
                                                       "--axis", "VSCROLL,-2", timeout=30, check=False)

    def test_two_eligible_targets_remain_ambiguous_without_any_input_or_observation(self):
        for kind in (SCROLL, smoke.LIST_VIEW):
            root, content, panel = dialog_hierarchy()
            for target in (content, panel):
                target.set("class", kind)
                target.set("scrollable", "true")
            device, observed = Mock(spec=smoke.Device), {}
            with self.subTest(kind=kind), self.assertRaisesRegex(smoke.SmokeFailure, "Ambiguous UI selector"):
                smoke.Ui(device, Path("unused"), {}).scroll(root, True, scroll_kind=kind, observation=observed)
            device.adb.assert_not_called()
            device.shell.assert_not_called()
            self.assertEqual({}, observed)

    def test_disabled_missing_or_noncanonical_capabilities_cannot_generate_wheel_input(self):
        for kind in (SCROLL, smoke.LIST_VIEW):
            for field in ("scrollable", "enabled"):
                for value in (None, "false", "", "True", "TRUE", "1", "true "):
                    with self.subTest(kind=kind, field=field, value=value):
                        target = node(kind)
                        if value is None:
                            del target.attrib[field]
                        else:
                            target.set(field, value)
                        root, device, observed = ET.Element("hierarchy"), Mock(spec=smoke.Device), {}
                        root.append(target)
                        self.assertFalse(smoke.Ui(device, Path("unused"), {}).scroll(root, False,
                                         scroll_kind=kind, observation=observed))
                        device.adb.assert_not_called()
                        device.shell.assert_not_called()
                        self.assertEqual({}, observed)

    def test_ineligible_duplicate_does_not_make_one_eligible_target_ambiguous(self):
        for field in ("scrollable", "enabled"):
            root, content, panel = dialog_hierarchy()
            panel.set("scrollable", "true")
            panel.set(field, "false")
            device = Mock(spec=smoke.Device)
            device.adb.return_value = subprocess.CompletedProcess([], 0, "", "")
            with self.subTest(field=field):
                self.assertTrue(smoke.Ui(device, Path("unused"), {}).scroll(root, False))
            device.adb.assert_called_once_with("shell", "input", "mouse", "scroll", "661", "604",
                                               "--axis", "VSCROLL,2", timeout=30, check=False)

    def test_eligible_flags_cannot_override_package_visibility_bounds_or_requested_class(self):
        for attrs in ({"package": "foreign.fixture"}, {"bounds": "[0,0][0,10]"},
                      {"bounds": "malformed"}, {"class": smoke.LIST_VIEW}):
            root = ET.Element("hierarchy")
            target = node()
            target.attrib.update(attrs)
            root.append(target)
            device = Mock(spec=smoke.Device)
            with self.subTest(attrs=attrs):
                self.assertFalse(smoke.Ui(device, Path("unused"), {}).scroll(root, True))
            device.adb.assert_not_called()
            device.shell.assert_not_called()

    def test_no_eligible_scroll_target_keeps_missing_editor_search_fail_closed(self):
        root, content, panel = dialog_hierarchy()
        content.set("enabled", "false")
        device, state = Mock(spec=smoke.Device), {}
        ui = smoke.Ui(device, Path("unused"), state)
        with patch.object(ui, "snapshot", return_value=root):
            with self.assertRaisesRegex(smoke.SmokeFailure, "UI control not found"):
                ui.editor("Synthetic missing editor")
        device.adb.assert_not_called()
        device.shell.assert_not_called()
        self.assertEqual("failed", state["ui_hierarchy_observations"]["navigation"]["last_search"]["status"])

    def test_typed_observation_uses_enabled_content_even_when_it_cannot_scroll(self):
        for capability in ("true", "false", None):
            for reverse in (False, True):
                with self.subTest(capability=capability, reverse=reverse), tempfile.TemporaryDirectory() as temporary:
                    root, content, panel, identity = typed_form_hierarchy()
                    if capability is None:
                        del content.attrib["scrollable"]
                    else:
                        content.set("scrollable", capability)
                    if reverse:
                        root[:] = list(reversed(list(root)))
                    device = Mock(spec=smoke.Device)
                    ui = smoke.Ui(device, Path(temporary), {})
                    with patch.object(ui, "snapshot", return_value=root), patch.object(ui, "scroll") as scroll:
                        observed = smoke.observe_data_source(ui, identity, "initial")
                    self.assertEqual(identity["selection_label"], observed["selection_label"])
                    self.assertIsNone(observed["refresh_minutes"])
                    self.assertIsNone(observed["enabled"])
                    self.assertFalse(observed["connector_supplied"])
                    self.assertFalse(observed["refresh_supplied"])
                    self.assertEqual({"connector", "minutes", "enabled"}, set(observed["frames"]))
                    self.assertEqual(3, len(list(Path(temporary).glob("*.xml"))))
                    scroll.assert_not_called()
                    device.adb.assert_not_called()
                    device.shell.assert_not_called()

    def test_typed_observation_rejects_two_content_viewports_without_inputs_or_frames(self):
        with tempfile.TemporaryDirectory() as temporary:
            root, content, panel, identity = typed_form_hierarchy()
            root.append(copy.deepcopy(content))
            device = Mock(spec=smoke.Device)
            ui = smoke.Ui(device, Path(temporary), {})
            with patch.object(ui, "snapshot", return_value=root):
                with self.assertRaisesRegex(smoke.SmokeFailure, "Ambiguous UI selector"):
                    smoke.observe_data_source(ui, identity, "initial")
            self.assertEqual([], list(Path(temporary).iterdir()))
            device.adb.assert_not_called()
            device.shell.assert_not_called()

    def test_typed_observation_cannot_use_action_panel_as_disabled_or_missing_content_fallback(self):
        for enabled in (None, "false", "", "True"):
            with self.subTest(enabled=enabled), tempfile.TemporaryDirectory() as temporary:
                root, content, panel, identity = typed_form_hierarchy()
                if enabled is None:
                    del content.attrib["enabled"]
                else:
                    content.set("enabled", enabled)
                panel.set("scrollable", "true")
                device = Mock(spec=smoke.Device)
                ui = smoke.Ui(device, Path(temporary), {})
                with patch.object(ui, "snapshot", return_value=root):
                    with self.assertRaisesRegex(smoke.SmokeFailure, "no unique scroll viewport"):
                        smoke.observe_data_source(ui, identity, "initial")
                self.assertEqual([], list(Path(temporary).iterdir()))
                device.adb.assert_not_called()
                device.shell.assert_not_called()

    def test_typed_viewport_does_not_admit_a_different_form_identity(self):
        with tempfile.TemporaryDirectory() as temporary:
            root, content, panel, identity = typed_form_hierarchy()
            root[0].set("text", "A different synthetic form")
            device = Mock(spec=smoke.Device)
            ui = smoke.Ui(device, Path(temporary), {})
            with patch.object(ui, "snapshot", return_value=root), patch.object(smoke.time, "sleep"):
                with self.assertRaisesRegex(smoke.SmokeFailure, "exact DataSource form"):
                    smoke.observe_data_source(ui, identity, "initial")
            self.assertEqual([], list(Path(temporary).iterdir()))
            device.adb.assert_not_called()
            device.shell.assert_not_called()

    def reject_observation_without_input_or_frames(self, root, identity, phase, message):
        with tempfile.TemporaryDirectory() as temporary:
            device = Mock(spec=smoke.Device)
            ui = smoke.Ui(device, Path(temporary), {})
            with patch.object(ui, "snapshot", return_value=root):
                with self.assertRaisesRegex(smoke.SmokeFailure, message):
                    smoke.observe_data_source(ui, identity, phase)
            self.assertEqual([], list(Path(temporary).iterdir()))
            device.adb.assert_not_called()
            device.shell.assert_not_called()

    def test_all_phases_reject_typed_controls_inside_excluded_overlapping_button_panel(self):
        for phase in smoke.DATA_SOURCE_FRAME_PREFIXES:
            with self.subTest(phase=phase):
                root, content, panel, identity = phase_form_hierarchy(phase)
                panel.set("bounds", "[46,149][673,1168]")
                panel.extend(list(content))
                content[:] = []
                self.reject_observation_without_input_or_frames(root, identity, phase, "outside the selected content viewport")

    def test_all_phases_reject_disabled_content_using_renamed_overlapping_panel_bounds(self):
        for phase in smoke.DATA_SOURCE_FRAME_PREFIXES:
            with self.subTest(phase=phase):
                root, content, panel, identity = phase_form_hierarchy(phase)
                content.set("enabled", "false")
                del panel.attrib["resource-id"]
                panel.set("bounds", "[46,149][673,1168]")
                self.reject_observation_without_input_or_frames(root, identity, phase, "outside the selected content viewport")

    def test_each_optional_row_and_enabled_choice_must_belong_to_selected_content_in_every_phase(self):
        for phase in smoke.DATA_SOURCE_FRAME_PREFIXES:
            for label, length in ((smoke.DATA_SOURCE_REFERENCE_LABEL, 3),
                                  (smoke.DATA_SOURCE_MINUTES_LABEL, 3), (smoke.DATA_SOURCE_ENABLED_LABEL, 2)):
                with self.subTest(phase=phase, label=label):
                    root, content, panel, identity = phase_form_hierarchy(phase)
                    outside = node("android.widget.LinearLayout", box="[46,149][673,1059]", scrollable="false")
                    children = list(content)
                    start = next(i for i, item in enumerate(children) if item.get("text") == label)
                    for item in children[start:start + length]:
                        content.remove(item)
                        outside.append(item)
                    root.append(outside)
                    self.reject_observation_without_input_or_frames(root, identity, phase, "outside the selected content viewport")

    def test_outside_duplicates_still_fail_global_uniqueness_before_content_admission(self):
        for phase in smoke.DATA_SOURCE_FRAME_PREFIXES:
            for label in (smoke.DATA_SOURCE_REFERENCE_LABEL, smoke.DATA_SOURCE_MINUTES_LABEL, smoke.DATA_SOURCE_ENABLED_LABEL):
                with self.subTest(phase=phase, label=label):
                    root, content, panel, identity = phase_form_hierarchy(phase)
                    field = next(item for item in content if item.get("text") == label)
                    panel.append(copy.deepcopy(field))
                    self.reject_observation_without_input_or_frames(root, identity, phase, "Ambiguous UI selector")

    def test_all_phases_accept_nested_controls_in_non_scrollable_selected_content(self):
        for phase in smoke.DATA_SOURCE_FRAME_PREFIXES:
            with self.subTest(phase=phase), tempfile.TemporaryDirectory() as temporary:
                root, content, panel, identity = phase_form_hierarchy(phase)
                nested = node("android.widget.LinearLayout", box="[46,149][673,1059]", scrollable="false")
                nested.extend(list(content))
                content[:] = [nested]
                self.assertEqual("false", content.get("scrollable"))
                device = Mock(spec=smoke.Device)
                ui = smoke.Ui(device, Path(temporary), {})
                with patch.object(ui, "snapshot", return_value=root), patch.object(ui, "scroll") as scroll:
                    observed = smoke.observe_data_source(ui, identity, phase)
                self.assertEqual(identity["selection_id"], observed["selection_id"])
                self.assertEqual(60 if phase != "initial" else None, observed["refresh_minutes"])
                self.assertIs(observed["enabled"], True if phase != "initial" else None)
                self.assertEqual(3, len(list(Path(temporary).glob("*.xml"))))
                scroll.assert_not_called()
                device.adb.assert_not_called()
                device.shell.assert_not_called()


if __name__ == "__main__":
    unittest.main()
