"""Offline failure-context guards; no emulator, device, process or network execution."""

import contextlib
import importlib.util
import io
import json
from pathlib import Path
import types
import unittest
from unittest.mock import Mock, patch
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("scenario_context_smoke", ROOT / "scripts/android_emulator_smoke.py")
smoke = importlib.util.module_from_spec(spec)
spec.loader.exec_module(smoke)


def receipt():
    return {"cases": [{"id": name, "status": "not_run"} for name in smoke.CASE_IDS]}


def node(text, kind=smoke.TEXT_VIEW):
    return ET.Element("node", text=text, **{"class": kind, "package": smoke.PACKAGE,
        "bounds": "[0,0][100,100]", "enabled": "true"})


class ScenarioFailureContextTest(unittest.TestCase):
    def setUp(self):
        self.stack = contextlib.ExitStack()
        self.addCleanup(self.stack.close)
        for name in ("run", "Popen"):
            self.stack.enter_context(patch.object(smoke.subprocess, name,
                side_effect=AssertionError("Host process execution is prohibited")))
        self.stack.enter_context(patch.object(smoke.os, "fork",
            side_effect=AssertionError("Host fork is prohibited")))
        self.stack.enter_context(patch.object(smoke.os, "kill",
            side_effect=AssertionError("Host signals are prohibited")))

    def test_actual_duplicate_selector_keeps_failed_case_and_reports_safe_callsite(self):
        state, device, console = receipt(), Mock(spec=smoke.Device), io.StringIO()
        root = ET.Element("hierarchy")
        root.extend([node("SYNTHETIC DATA"), node("SYNTHETIC DATA")])
        with patch.object(smoke.Ui, "snapshot", return_value=root), \
                patch.object(smoke.Ui, "checkpoint"), patch.object(smoke, "retain_failure_log"), \
                patch.object(smoke, "retain_case_failure_state"), contextlib.redirect_stderr(console):
            with self.assertRaises(smoke.SmokeFailure) as raised:
                smoke.exercise(device, Path("unused-host-only-output"), state)
        self.assertEqual("Ambiguous UI selector; refusing to guess a target", str(raised.exception))
        self.assertEqual(["failed"] + ["not_run"] * 4, [row["status"] for row in state["cases"]])
        context = state["scenario_failure_context"]
        self.assertEqual(smoke.CASE_IDS[0], context["case"])
        self.assertEqual(["scenario", "ui_text", "ui_seek", "unique_visible_match"],
                         [row["operation"] for row in context["operations"]])
        source = (ROOT / "scripts/android_emulator_smoke.py").read_text().splitlines()
        self.assertIn('ui.text("SYNTHETIC DATA"', source[context["operations"][0]["line"] - 1])
        self.assertEqual("Installed synthetic case failure context: " + json.dumps(context, sort_keys=True) + "\n",
                         console.getvalue())
        device.launch.assert_called_once_with()
        device.shell.assert_not_called()

    def test_direct_data_source_helper_ambiguity_has_its_own_operation(self):
        state = receipt()
        state["cases"][4]["status"] = "failed"
        root = ET.Element("hierarchy")
        root.extend([node("Active local draft ID: draft-00000000-0000-0000-0000-000000000000") for _ in range(2)])
        ui = smoke.Ui(Mock(spec=smoke.Device), Path("unused"), state)
        with patch.object(ui, "snapshot", return_value=root):
            try:
                smoke.data_source_draft_id(ui)
            except smoke.SmokeFailure as error:
                context = smoke.scenario_failure_context(state, error)
            else:
                self.fail("Duplicate identities must reject")
        self.assertEqual(smoke.DATA_SOURCE_CASE_ID, context["case"])
        self.assertEqual(["data_source_draft_id", "ui_seek", "unique_visible_match"],
                         [row["operation"] for row in context["operations"]])

    def test_successful_unique_target_and_absent_target_routing_are_unchanged(self):
        state, device = receipt(), Mock(spec=smoke.Device)
        ui = smoke.Ui(device, Path("unused"), state)
        root = ET.Element("hierarchy")
        expected = node("fixed synthetic action", smoke.BUTTON)
        root.append(expected)
        with patch.object(ui, "snapshot", return_value=root):
            ui.click("fixed synthetic action", scroll=False)
        device.shell.assert_called_once_with("input", "tap", "50", "50")
        self.assertIs(expected, smoke.single_match(root, lambda item: item.get("text") == "fixed synthetic action"))
        self.assertIsNone(smoke.single_match(root, lambda item: item.get("text") == "absent"))
        self.assertNotIn("scenario_failure_context", state)
        self.assertEqual(["not_run"] * 5, [row["status"] for row in state["cases"]])

    def test_exception_arguments_traceback_locals_names_and_paths_are_never_copied(self):
        secret = "synthetic-private-value:/private/example/" + "x" * 100000
        class HostileError(Exception):
            def __str__(self):
                raise AssertionError("Do not format the exception")
            def __repr__(self):
                raise AssertionError("Do not inspect exception values")
        def foreign_selector_with_private_name(root):
            raise HostileError(secret)
        state = receipt()
        state["cases"][0]["status"] = "failed"
        ui = smoke.Ui(Mock(spec=smoke.Device), Path(secret), state)
        # seek's existing diagnostic formatter is outside this new context's scope.
        try:
            foreign_selector_with_private_name(ET.Element("hierarchy"))
        except HostileError as error:
            context = smoke.scenario_failure_context(state, error)
        rendered = json.dumps(context)
        self.assertEqual([], context["operations"])
        for forbidden in (secret, "foreign_selector_with_private_name", "private", "HostileError", str(ROOT)):
            self.assertNotIn(forbidden, rendered)
        self.assertLessEqual(len(rendered), 1024)

    def test_case_identity_rejects_foreign_ambiguous_and_malformed_rows(self):
        variants = [None, [], {}, {"cases": "private-value"},
            {"cases": [{"id": "private-value" * 10000, "status": "failed"}]},
            {"cases": [{"id": smoke.CASE_IDS[0], "status": "failed"}] * 2},
            {"cases": [None, "private-value"]},
            {"cases": [{"id": smoke.CASE_IDS[0], "status": "passed"}]},
            {"cases": [{"id": ["private-value"], "status": "failed"}]}]
        for state in variants:
            with self.subTest(state_type=type(state).__name__):
                context = smoke.scenario_failure_context(state, smoke.SmokeFailure("private-value"))
                self.assertEqual("unclassified", context["case"])
                self.assertEqual([], context["operations"])
                self.assertNotIn("private-value", json.dumps(context))

    def test_known_case_cannot_be_inferred_from_an_oversized_or_partial_case_list(self):
        for rows in ([{"id": smoke.CASE_IDS[0], "status": "failed"}],
                     [{"id": smoke.CASE_IDS[0], "status": "failed"}] * 10000):
            context = smoke.scenario_failure_context({"cases": rows}, smoke.SmokeFailure("hidden"))
            self.assertEqual("unclassified", context["case"])

    def test_foreign_function_with_allowlisted_name_cannot_claim_operation_identity(self):
        def seek():
            raise smoke.SmokeFailure("private-value")
        try:
            seek()
        except smoke.SmokeFailure as error:
            context = smoke.scenario_failure_context(receipt(), error)
        self.assertEqual([], context["operations"])

    def test_equal_but_distinct_code_object_cannot_claim_checked_in_source_identity(self):
        copied_code = smoke.single_match.__code__.replace()
        self.assertEqual(smoke.single_match.__code__, copied_code)
        self.assertIsNot(smoke.single_match.__code__, copied_code)
        copied_match = types.FunctionType(copied_code, smoke.single_match.__globals__)
        root = ET.Element("hierarchy")
        root.extend([node("same"), node("same")])
        try:
            copied_match(root, lambda item: True)
        except smoke.SmokeFailure as error:
            context = smoke.scenario_failure_context(receipt(), error)
        self.assertEqual([], context["operations"])

    def test_deep_repeated_real_seek_stack_has_bounded_source_only_context(self):
        state = receipt()
        state["cases"][0]["status"] = "failed"
        ui = smoke.Ui(Mock(spec=smoke.Device), Path("unused"), state)
        depth = 0
        def recurse(root):
            nonlocal depth
            depth += 1
            if depth == 50:
                raise smoke.SmokeFailure("private-value" * 10000)
            return ui.seek(recurse, "private-selector", scroll=False)
        with patch.object(ui, "snapshot", return_value=ET.Element("hierarchy")):
            try:
                ui.seek(recurse, "private-selector", scroll=False)
            except smoke.SmokeFailure as error:
                context = smoke.scenario_failure_context(state, error)
        self.assertEqual(8, len(context["operations"]))
        self.assertTrue(all(row["operation"] == "ui_seek" for row in context["operations"]))
        self.assertTrue(all(type(row["line"]) is int and row["line"] > 0 for row in context["operations"]))
        self.assertLessEqual(len(json.dumps(context)), 1024)
        self.assertNotIn("private", json.dumps(context))

    def test_failed_checkpoint_cannot_replace_original_exception_or_context(self):
        state, device, console = receipt(), Mock(spec=smoke.Device), io.StringIO()
        original = smoke.SmokeFailure("Original synthetic launch failure")
        device.launch.side_effect = original
        with patch.object(smoke.Ui, "checkpoint", side_effect=smoke.SmokeFailure("Later synthetic capture failure")), \
                patch.object(smoke, "retain_failure_log"), patch.object(smoke, "retain_case_failure_state"), \
                contextlib.redirect_stderr(console):
            with self.assertRaises(smoke.SmokeFailure) as raised:
                smoke.exercise(device, Path("unused"), state)
        self.assertIs(original, raised.exception)
        self.assertEqual("Later synthetic capture failure", state["failure_checkpoint_error"])
        self.assertEqual(["scenario"], [row["operation"] for row in state["scenario_failure_context"]["operations"]])
        self.assertNotIn("Later", console.getvalue())
        self.assertEqual(1, len(console.getvalue().splitlines()))

    def test_unwritable_diagnostic_console_cannot_mask_original_failure(self):
        state, device = receipt(), Mock(spec=smoke.Device)
        original = smoke.SmokeFailure("Original synthetic launch failure")
        device.launch.side_effect = original
        with patch.object(smoke, "print", side_effect=OSError("Console unavailable"), create=True), \
                patch.object(smoke.Ui, "checkpoint"), patch.object(smoke, "retain_failure_log"), \
                patch.object(smoke, "retain_case_failure_state"):
            with self.assertRaises(smoke.SmokeFailure) as raised:
                smoke.exercise(device, Path("unused"), state)
        self.assertIs(original, raised.exception)
        self.assertEqual(smoke.CASE_IDS[0], state["scenario_failure_context"]["case"])

    def test_failed_context_collection_cannot_mask_original_failure(self):
        state, device, console = receipt(), Mock(spec=smoke.Device), io.StringIO()
        original = smoke.SmokeFailure("Original synthetic launch failure")
        device.launch.side_effect = original
        with patch.object(smoke, "scenario_failure_context", side_effect=RuntimeError("private-value")), \
                patch.object(smoke.Ui, "checkpoint"), patch.object(smoke, "retain_failure_log"), \
                patch.object(smoke, "retain_case_failure_state"), contextlib.redirect_stderr(console):
            with self.assertRaises(smoke.SmokeFailure) as raised:
                smoke.exercise(device, Path("unused"), state)
        self.assertIs(original, raised.exception)
        self.assertEqual({"version": 1, "case": "unclassified", "operations": []}, state["scenario_failure_context"])
        self.assertNotIn("private-value", console.getvalue())

    def test_overridden_traceback_cannot_inject_a_changing_or_unbounded_line_value(self):
        code = smoke.Ui.seek.__code__
        admitted_line = next(line for _, _, line in code.co_lines() if line is not None)
        marker = "synthetic-sensitive-sentinel-" + "x" * 100000
        class FakeTrace:
            tb_frame = types.SimpleNamespace(f_code=code)
            tb_next = None
            reads = 0
            @property
            def tb_lineno(self):
                self.reads += 1
                return admitted_line if self.reads < 3 else marker
        fake = FakeTrace()
        class HostileError(Exception):
            accesses = 0
            @property
            def __traceback__(self):
                self.accesses += 1
                return fake
        original, state, console = HostileError("fixed synthetic error"), receipt(), io.StringIO()
        state["cases"][0]["status"] = "failed"
        with contextlib.redirect_stderr(console):
            smoke.retain_scenario_failure_context(state, original)
        context = state["scenario_failure_context"]
        self.assertLessEqual(len(json.dumps(context)), 1024)
        self.assertLessEqual(len(console.getvalue()), 1100)
        self.assertEqual([], context["operations"])
        self.assertEqual(0, original.accesses)
        self.assertEqual(0, fake.reads)
        self.assertTrue(marker not in console.getvalue(), "New context must omit the synthetic marker")

    def test_overridden_traceback_does_not_hide_actual_source_or_skip_failure_observations(self):
        class HostileFailure(smoke.SmokeFailure):
            accesses = 0
            @property
            def __traceback__(self):
                self.accesses += 1
                raise AssertionError("The instance override must never run")
            @__traceback__.setter
            def __traceback__(self, value):
                # contextlib restores the real traceback while rethrowing the
                # same exception; permit that unrelated standard-library write.
                BaseException.__traceback__.__set__(self, value)
        original, state, device = HostileFailure("Fixed synthetic original failure"), receipt(), Mock(spec=smoke.Device)
        device.launch.side_effect = original
        events, console = [], io.StringIO()
        with patch.object(smoke, "retain_failure_log", side_effect=lambda *args: events.append("log")), \
                patch.object(smoke.Ui, "checkpoint", side_effect=lambda *args: events.append("checkpoint")), \
                patch.object(smoke, "retain_case_failure_state", side_effect=lambda *args: events.append("state")), \
                contextlib.redirect_stderr(console):
            with self.assertRaises(smoke.SmokeFailure) as raised:
                smoke.exercise(device, Path("unused"), state)
        self.assertIs(original, raised.exception)
        self.assertEqual(0, original.accesses)
        self.assertEqual(["log", "checkpoint", "state"], events)
        self.assertEqual(smoke.CASE_IDS[0], state["scenario_failure_context"]["case"])
        self.assertEqual(["scenario"], [row["operation"] for row in state["scenario_failure_context"]["operations"]])

    def test_unexpected_stderr_write_error_preserves_original_and_all_failure_observations(self):
        class BrokenConsole:
            writes = 0
            def write(self, text):
                self.writes += 1
                raise RuntimeError("Fixed synthetic console failure")
            def flush(self):
                pass
        original, state, device = smoke.SmokeFailure("Fixed synthetic original failure"), receipt(), Mock(spec=smoke.Device)
        device.launch.side_effect = original
        events, console = [], BrokenConsole()
        with patch.object(smoke, "retain_failure_log", side_effect=lambda *args: events.append("log")), \
                patch.object(smoke.Ui, "checkpoint", side_effect=lambda *args: events.append("checkpoint")), \
                patch.object(smoke, "retain_case_failure_state", side_effect=lambda *args: events.append("state")), \
                contextlib.redirect_stderr(console):
            with self.assertRaises(smoke.SmokeFailure) as raised:
                smoke.exercise(device, Path("unused"), state)
        self.assertIs(original, raised.exception)
        self.assertEqual(1, console.writes)
        self.assertEqual(["log", "checkpoint", "state"], events)
        self.assertEqual(["failed"] + ["not_run"] * 4, [row["status"] for row in state["cases"]])
        self.assertEqual(smoke.CASE_IDS[0], state["scenario_failure_context"]["case"])


if __name__ == "__main__":
    unittest.main()
