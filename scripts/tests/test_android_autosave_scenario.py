"""Host-only autosave scenario controls; no Android, subprocess or filesystem mutation.

The stateful UI models the four generic journeys. The separately tested typed
DataSource journey is mocked at its dispatch boundary and verified below; these
fixture results do not supply its installed controls or complete receipt.
"""

import contextlib
import copy
import importlib.util
from pathlib import Path
import unittest
from unittest.mock import patch
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("autosave_smoke", ROOT / "scripts/android_emulator_smoke.py")
smoke = importlib.util.module_from_spec(spec)
spec.loader.exec_module(smoke)

TITLE = "Draft title (up to 120 characters)"
OBJECTIVE = "Draft objective (optional)"
CONTEXT = "Draft context (optional)"
REQUESTS = "Requests per day"
SAVED = ("SyntheticEmulatorDraft", "SyntheticObjective", "SyntheticContext")
PENDING = ("SyntheticEmulatorDraft_pending", "SyntheticObjective_pending", "SyntheticContext")
UNSAVED = "Unsaved changes. Valid edits save after a short pause; Save locally saves them now."
INVALID = "Some entered workload values are invalid. Your text is retained; correct it before saving."


class ScenarioDevice:
    def __init__(self, fault=None):
        self.fault = fault
        self.values = {TITLE: "", OBJECTIVE: "", CONTEXT: "", REQUESTS: ""}
        if fault == "nonempty_request_baseline":
            self.values[REQUESTS] = "1"
        self.saved = None
        self.deleted = False
        self.open_failed = False
        self.clock = 0.0
        self.last_edit = None
        self.launches = 0
        self.restarts = 0
        self.rotations = []
        self.checkpoints = []
        self.actions = []
        self.dialog = None

    def valid(self):
        value = self.values[REQUESTS]
        return value == "" or (value.isascii() and value.isdigit() and
                               (value == "0" or not value.startswith("0")))

    def form(self):
        return tuple(self.values[label] for label in (TITLE, OBJECTIVE, CONTEXT, REQUESTS))

    def advance(self):
        # Each separately observed UI action takes one synthetic second. This
        # deliberately crosses the real product's 750ms debounce between edits;
        # the exercise must not require a disabled manual-save action afterward.
        self.clock += 1.0
        if (not self.deleted and self.last_edit is not None and
                self.clock - self.last_edit >= 0.750 and self.valid() and
                self.fault != "missing_ack"):
            self.saved = self.form()
            self.last_edit = None

    def launch(self):
        self.launches += 1

    def cold_restart(self):
        self.advance()
        self.restarts += 1
        self.launch()
        self.last_edit = None
        if self.deleted and self.fault == "resurrect_deleted":
            self.saved = (*SAVED, "")
            self.deleted = False
        if self.saved is None:
            self.open_failed = self.deleted
            self.values = {TITLE: "", OBJECTIVE: "", CONTEXT: "", REQUESTS: ""}
        else:
            self.values = dict(zip((TITLE, OBJECTIVE, CONTEXT, REQUESTS), self.saved))
            if self.fault == "stale_reload":
                self.values[OBJECTIVE] = "stale"
        return {"terminated_pid": str(100 + self.restarts),
                "relaunch_pid": str(200 + self.restarts), "method": "am force-stop"}


class ScenarioUi:
    def __init__(self, device, output, receipt):
        self.device = device

    def tick(self, action):
        self.device.advance()
        self.device.actions.append(action)

    @staticmethod
    def item(text, enabled=True):
        return ET.Element("node", text=text, enabled=str(enabled).lower())

    def editor(self, label):
        self.tick(("editor", label))
        value = self.device.values[label]
        # Android TextView exposes its hint as accessibility text when raw input is empty.
        if label == REQUESTS and value == "" and self.device.fault != "unhinted_request_control":
            value = "Unanswered"
        return self.item(value)

    def enter(self, label, value):
        self.tick(("enter", label, value))
        self.device.values[label] += value
        self.device.last_edit = self.device.clock

    def expect_fields(self, title, objective, context):
        self.tick(("fields", title, objective, context))
        smoke.require(tuple(self.device.values[label] for label in (TITLE, OBJECTIVE, CONTEXT)) ==
                      (title, objective, context), "Visible form differs from expected complete values")

    def enabled(self, label):
        d = self.device
        if label == "Save locally":
            if not d.valid() and d.fault == "save_enabled_for_invalid":
                return True
            return not d.deleted and d.valid() and d.form() != d.saved
        if label == "Saved local revision history":
            return not d.deleted and d.saved is not None
        if label == "Manage local drafts":
            return True
        raise AssertionError("Unexpected button " + label)

    def expect_button(self, label, enabled):
        self.tick(("button", label, enabled))
        smoke.require(self.enabled(label) is enabled, "Unexpected enabled state for " + label)

    def text(self, value, **kwargs):
        self.tick(("text", value))
        d = self.device
        if value == "Saved locally.":
            smoke.require(d.valid() and d.saved is not None and d.form() == d.saved,
                          "No complete saved acknowledgment")
        elif value == UNSAVED:
            smoke.require(not d.deleted and d.form() != d.saved, "Expected genuinely pending form")
        elif value == INVALID:
            smoke.require(not d.valid(), "Expected retained invalid workload")
        elif value == "SYNTHETIC DATA":
            pass
        elif value == "Active local draft ID: guest-draft":
            smoke.require(not d.open_failed, "Active draft unavailable")
        elif value == "Local ID: guest-draft":
            smoke.require(d.dialog == "library" and d.saved is not None and not d.deleted,
                          "Expected one actual saved library entry")
            title = "wrong snapshot" if d.fault == "wrong_library_snapshot" else d.saved[0]
            return self.item(title + "\nLocal ID: guest-draft")
        elif value == "Permanently delete local ID guest-draft":
            smoke.require(d.dialog == "confirm", "Deletion confirmation was not shown")
        elif value == "The selected local draft and its retained revision files have been removed.":
            smoke.require(d.dialog == "deleted" and d.saved is None, "Deletion was not acknowledged")
        elif value == "No local draft directories found.":
            smoke.require(d.dialog == "library" and d.saved is None, "Deleted draft reappeared")
        elif value == "Local draft could not be opened.":
            smoke.require(d.open_failed and d.saved is None, "Deleted draft reopened")
        else:
            raise smoke.SmokeFailure("Current product has no visible label: " + value)
        return self.item(value)

    def click(self, label, **kwargs):
        self.tick(("click", label))
        d = self.device
        if label == "Save locally":
            smoke.require(self.enabled(label), "Refusing to tap a disabled control")
            d.saved = d.form()
            d.last_edit = None
        elif label == "Manage local drafts":
            d.dialog = "library"
        elif label == "Delete local draft" and d.dialog == "entry":
            d.dialog = "confirm"
        elif label == "Delete local draft" and d.dialog == "confirm":
            d.saved = None
            d.deleted = True
            d.last_edit = None
            d.dialog = "deleted"
        elif label == "Close":
            if d.dialog == "deleted" and d.fault == "lose_invalid_after_deletion":
                d.values[REQUESTS] = ""
            d.dialog = None
        else:
            raise AssertionError("Unexpected action " + label)

    def tap(self, item):
        self.tick(("tap", item.get("text")))
        smoke.require(self.device.dialog == "library" and self.device.saved is not None,
                      "No saved library item can be selected")
        self.device.dialog = "entry"

    def rotate(self, value):
        self.tick(("rotate", value))
        self.device.rotations.append(value)
        if self.device.fault == "drop_invalid_input":
            self.device.values[REQUESTS] = ""
        if self.device.fault == "lose_title_on_rotation":
            self.device.values[TITLE] = SAVED[0]

    def checkpoint(self, name):
        self.tick(("checkpoint", name))
        self.device.checkpoints.append((name, copy.deepcopy(self.device.values)))
        if name == "03-cold-relaunch" and self.device.fault == "literal_request_hint":
            # A literal value appearing before pending input must not be treated as a hint.
            self.device.values[REQUESTS] = "Unanswered"


class CurrentAutosaveScenarioTest(unittest.TestCase):
    def run_scenario(self, fault=None):
        device = ScenarioDevice(fault)
        receipt = {"cases": [{"id": name, "status": "not_run"} for name in smoke.CASE_IDS]}
        with contextlib.ExitStack() as stack:
            stack.enter_context(patch.object(smoke, "Ui", ScenarioUi))
            data_source = stack.enter_context(patch.object(smoke, "exercise_data_source", autospec=True))
            log = stack.enter_context(patch.object(smoke, "retain_failure_log"))
            state = stack.enter_context(patch.object(smoke, "retain_case_failure_state"))
            # These commands are outside the fake UI contract and must never run.
            stack.enter_context(patch.object(smoke.subprocess, "run", side_effect=AssertionError("process forbidden")))
            stack.enter_context(patch.object(smoke.os, "fork", side_effect=AssertionError("fork forbidden")))
            stack.enter_context(patch.object(smoke.os, "kill", side_effect=AssertionError("signal forbidden")))
            error = None
            try:
                smoke.exercise(device, Path("/unused-host-scenario"), receipt)
            except smoke.SmokeFailure as caught:
                error = caught
            if error is None:
                data_source.assert_called_once()
                observed_ui, observed_device, observed_row = data_source.call_args.args
                self.assertIsInstance(observed_ui, ScenarioUi)
                self.assertIs(observed_ui.device, device)
                self.assertIs(observed_device, device)
                self.assertIs(observed_row, receipt["cases"][4])
                self.assertEqual(smoke.DATA_SOURCE_CASE_ID, observed_row["id"])
            else:
                data_source.assert_not_called()
        return device, receipt, error, log, state

    def require_failure(self, fault, case_index):
        device, receipt, error, log, state = self.run_scenario(fault)
        self.assertIsInstance(error, smoke.SmokeFailure)
        self.assertEqual(["passed"] * case_index + ["failed"] +
                         ["not_run"] * (len(smoke.CASE_IDS) - case_index - 1), [row["status"] for row in receipt["cases"]])
        self.assertEqual(str(error), receipt["cases"][case_index]["error"])
        self.assertEqual("failure", device.checkpoints[-1][0])
        log.assert_called_once()
        state.assert_called_once()
        return device, receipt

    def test_actual_journey_accepts_auto_acknowledgment_and_retains_invalid_pending_input(self):
        device, receipt, error, log, state = self.run_scenario()
        self.assertIsNone(error, str(error))
        self.assertEqual(["passed"] * 5, [row["status"] for row in receipt["cases"]])
        self.assertEqual("automatic-valid-form", receipt["cases"][1]["save_trigger"])
        self.assertEqual("invalid-workload-requests", receipt["cases"][2]["pending_input_constraint"])
        self.assertNotIn(("click", "Save locally"), device.actions)
        self.assertEqual([1, 0], device.rotations)
        self.assertEqual(2, device.restarts)
        self.assertEqual(3, device.launches)
        self.assertEqual([
            "01-installed-launch", "02-acknowledged-save", "03-cold-relaunch",
            "04-pending-landscape", "05-pending-portrait", "06-explicit-delete-confirmation",
            "07-deleted-editor-keeps-pending-fields", "08-deleted-draft-stays-absent-after-relaunch",
        ], [name for name, values in device.checkpoints])
        for name, values in device.checkpoints[3:7]:
            self.assertEqual(PENDING, tuple(values[label] for label in (TITLE, OBJECTIVE, CONTEXT)), name)
            self.assertEqual("invalid", values[REQUESTS], name)
        self.assertIsNone(device.saved)
        log.assert_not_called()
        state.assert_not_called()

    def test_hinted_and_unhinted_empty_raw_input_require_the_complete_entered_value(self):
        for fault, accessible in ((None, "Unanswered"), ("unhinted_request_control", "")):
            with self.subTest(accessible=accessible):
                probe = ScenarioDevice(fault)
                self.assertEqual("", probe.values[REQUESTS])
                self.assertEqual(accessible, ScenarioUi(probe, None, None).editor(REQUESTS).get("text"))
                device, receipt, error, log, state = self.run_scenario(fault)
                self.assertIsNone(error, str(error))
                self.assertEqual(["passed"] * 5, [row["status"] for row in receipt["cases"]])
                self.assertIn(("enter", REQUESTS, "invalid"), device.actions)
                self.assertEqual([1, 0], device.rotations)
                for name, values in device.checkpoints[3:7]:
                    self.assertEqual("invalid", values[REQUESTS], name)
                log.assert_not_called()
                state.assert_not_called()

    def test_literal_hint_text_cannot_substitute_for_empty_raw_input(self):
        device, receipt = self.require_failure("literal_request_hint", 2)
        self.assertEqual("Unansweredinvalid", device.values[REQUESTS])
        self.assertIn(("enter", REQUESTS, "invalid"), device.actions)
        self.assertNotIn(("enter", TITLE, "_pending"), device.actions)
        self.assertEqual("The complete invalid workload input was not retained",
                         receipt["cases"][2]["error"])

    def test_missing_automatic_acknowledgment_fails_before_cold_restart(self):
        device, _ = self.require_failure("missing_ack", 1)
        self.assertEqual(0, device.restarts)

    def test_wrong_cold_reopened_field_fails_saved_case(self):
        self.require_failure("stale_reload", 1)

    def test_nonempty_request_control_cannot_be_assumed_replaced(self):
        device, _ = self.require_failure("nonempty_request_baseline", 2)
        self.assertIn(("enter", REQUESTS, "invalid"), device.actions)
        self.assertEqual("1invalid", device.values[REQUESTS])
        self.assertNotIn(("enter", TITLE, "_pending"), device.actions)

    def test_invalid_input_must_disable_saving_before_pending_edits(self):
        device, _ = self.require_failure("save_enabled_for_invalid", 2)
        self.assertNotIn(("enter", TITLE, "_pending"), device.actions)

    def test_rotation_must_preserve_exact_invalid_raw_input(self):
        self.require_failure("drop_invalid_input", 2)

    def test_rotation_must_preserve_complete_pending_title(self):
        self.require_failure("lose_title_on_rotation", 2)

    def test_library_must_still_show_the_acknowledged_saved_snapshot(self):
        self.require_failure("wrong_library_snapshot", 3)

    def test_deletion_must_retain_invalid_input_and_pending_editor(self):
        self.require_failure("lose_invalid_after_deletion", 3)

    def test_deleted_draft_must_not_reappear_after_cold_restart(self):
        self.require_failure("resurrect_deleted", 3)


if __name__ == "__main__":
    unittest.main()
