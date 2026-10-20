"""Synthetic rejection controls for the typed profile's hash/XML checker.

The XML constructed here is deliberately test data, never a native execution
receipt. Actual Gradle output must be checked by the separate workflow step.
"""
from contextlib import redirect_stderr
from copy import deepcopy
import importlib.util
import io
import json
from pathlib import Path
import shutil
import sys
import tempfile
import unittest
from unittest import mock
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location(
    "android_typed_comparison_checker_tests", ROOT / "scripts/check_typed_comparison_fixtures.py")
checker = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = checker
spec.loader.exec_module(checker)


class TypedComparisonCheckerTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.lock, cls.captured, cls.cases = checker.load_inputs()
        cls.expected = checker.expected_native(cls.lock, cls.cases)

    def source_copy(self, root):
        for path in [checker.LOCK_PATH] + [pin["path"] for pin in self.lock["inputs"].values()]:
            destination = root / path
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(ROOT / path, destination)

    def document(self, class_name=checker.FIXTURE_CLASS):
        rows = self.expected[class_name]
        suite = ET.Element("testsuite", name=class_name, tests=str(len(rows)), skipped="0",
                           failures="0", errors="0", timestamp="2026-01-01T00:00:00",
                           hostname="synthetic-checker-control", time="1.0")
        ET.SubElement(suite, "properties")
        for name, observation in rows.items():
            case = ET.SubElement(suite, "testcase", name=name, classname=class_name, time="0.0")
            if observation is not None:
                ET.SubElement(case, "system-out").text = checker.MARKER + " " + json.dumps(observation) + "\n"
        ET.SubElement(suite, "system-out")
        ET.SubElement(suite, "system-err")
        return suite

    def raw(self, document):
        return ET.tostring(document, encoding="utf-8", xml_declaration=True)

    def qualify(self, document, class_name=checker.FIXTURE_CLASS):
        return checker.qualify_xml(self.raw(document), class_name, self.expected[class_name])

    def rejects(self, document, reason=None):
        with self.assertRaises(checker.CheckError) as error:
            self.qualify(document)
        if reason is not None:
            self.assertEqual(str(error.exception), reason)

    def test_all_original_reference_methods_and_vectors_execute_after_hash_checks(self):
        result = checker.verify_reference()
        self.assertEqual(result["referenceTests"], 19)
        self.assertEqual(len(set(result["referenceMethods"])), 19)
        self.assertEqual(len(result["vectors"]), 104)
        self.assertEqual({row["outcome"] for row in result["vectors"]}, {"true", "false", "unknown"})
        self.assertEqual(result["frozenReferenceSHA256"],
                         "092c8a99495f9b5ae960df40b27e33f15eefe6d1a7cd80119658fdc79ada1a5a")
        self.assertNotIn("t0_typed_comparison_prototype", sys.modules)
        self.assertNotIn("android_typed_comparison_reference_tests", sys.modules)

    def test_changed_lock_cannot_choose_a_new_executable_path_or_digest(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.source_copy(root)
            path = root / checker.LOCK_PATH
            value = json.loads(path.read_bytes())
            value["inputs"]["prototype"]["path"] = "unreviewed.py"
            path.write_text(json.dumps(value))
            with mock.patch.object(checker, "_reference_modules") as execute:
                with self.assertRaisesRegex(checker.CheckError, "^input_lock_identity$"):
                    checker.verify_reference(root)
                execute.assert_not_called()

    def test_every_input_including_late_frozen_control_precedes_any_reference_execution(self):
        for role, pin in self.lock["inputs"].items():
            with self.subTest(role=role), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                self.source_copy(root)
                path = root / pin["path"]
                original = path.read_bytes()
                path.write_bytes(bytes([original[0] ^ 1]) + original[1:])
                with mock.patch.object(checker, "_reference_modules") as execute:
                    with self.assertRaisesRegex(checker.CheckError, "^reference_input_identity$"):
                        checker.verify_reference(root)
                    execute.assert_not_called()

    def test_truncated_extended_missing_and_symlink_inputs_cannot_reuse_original_pin(self):
        for mutation in ("truncate", "extend", "missing", "symlink"):
            with self.subTest(mutation=mutation), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                self.source_copy(root)
                path = root / self.lock["inputs"]["corpus"]["path"]
                original = path.read_bytes()
                if mutation == "truncate":
                    path.write_bytes(original[:-1])
                elif mutation == "extend":
                    path.write_bytes(original + b"\n")
                else:
                    path.unlink()
                    if mutation == "symlink":
                        target = root / "synthetic-original.json"
                        target.write_bytes(original)
                        path.symlink_to(target)
                with self.assertRaises((checker.CheckError, OSError)):
                    checker.load_inputs(root)

    def test_reference_namespace_collision_preserves_existing_module(self):
        name = "t0_typed_comparison_prototype"
        existing = object()
        self.assertNotIn(name, sys.modules)
        sys.modules[name] = existing
        try:
            with self.assertRaisesRegex(checker.CheckError, "^reference_namespace_busy$"):
                checker.verify_reference()
            self.assertIs(sys.modules[name], existing)
        finally:
            del sys.modules[name]

    def test_explicit_synthetic_reports_exercise_all_named_cases_and_observations(self):
        with tempfile.TemporaryDirectory() as directory:
            reports = Path(directory)
            for class_name in self.expected:
                (reports / ("TEST-" + class_name + ".xml")).write_bytes(self.raw(self.document(class_name)))
            result = checker.qualify_native(reports, 0)
        self.assertEqual(result["tests"], 175)
        self.assertEqual(result["vectors"], 104)
        self.assertEqual(sum(len(suite["methods"]) for suite in result["suites"]), 175)
        self.assertEqual(sum(len(suite["observations"]) for suite in result["suites"]), 104)
        self.assertIn("XML", result["scope"])
        self.assertIn("No checkout/toolchain authentication", result["limits"])

    def test_actual_command_success_argument_is_required_and_not_boolean(self):
        for code in (None, True, False, 1, -1, 0.0, "0"):
            with self.subTest(code=code), self.assertRaisesRegex(checker.CheckError, "^test_command_failed$"):
                checker.qualify_native(Path("/missing-synthetic-reports"), code)

    def test_cli_requires_paired_report_path_and_explicit_exit_status(self):
        for arguments in (["--reports", "unused"], ["--test-exit-code", "0"]):
            stderr = io.StringIO()
            with redirect_stderr(stderr):
                self.assertEqual(checker.main(arguments), 1)
            self.assertIn("paired_report_arguments", stderr.getvalue())

    def test_missing_renamed_or_foreign_case_cannot_preserve_the_full_count(self):
        for mutation in ("missing", "rename", "foreign-class"):
            suite = self.document()
            case = suite.findall("testcase")[0]
            if mutation == "missing":
                suite.remove(case)
                suite.set("tests", str(len(self.expected[checker.FIXTURE_CLASS]) - 1))
            elif mutation == "rename":
                case.set("name", "synthetic-unrecognized-name")
            else:
                case.set("classname", checker.ADMISSION_CLASS)
            with self.subTest(mutation=mutation):
                self.rejects(suite)

    def test_duplicate_case_cannot_replace_a_missing_case_with_correct_totals(self):
        suite = self.document()
        first, second = suite.findall("testcase")[:2]
        first.set("name", second.get("name"))
        first.find("system-out").text = second.findtext("system-out")
        self.rejects(suite, "case_identity")

    def test_failed_skipped_retried_and_unknown_results_never_qualify(self):
        for tag in ("failure", "error", "skipped", "flakyFailure", "rerunFailure", "unknown"):
            suite = self.document()
            ET.SubElement(suite.findall("testcase")[0], tag)
            with self.subTest(tag=tag):
                self.rejects(suite, "case_result")
        for field in ("failures", "errors", "skipped"):
            suite = self.document()
            suite.set(field, "1")
            with self.subTest(field=field):
                self.rejects(suite, "suite_result")

    def test_typed_observation_binds_profile_case_operator_inputs_and_all_pins(self):
        keys = ("profile", "case", "operator", "outcome", "leftSHA256", "rightSHA256",
                "corpusSHA256", "prototypeSHA256", "inputsLockSHA256")
        for key in keys:
            suite = self.document()
            output = suite.findall("testcase")[0].find("system-out")
            observation = json.loads(output.text[len(checker.MARKER) + 1:])
            observation[key] = "different-synthetic-value"
            output.text = checker.MARKER + " " + json.dumps(observation)
            with self.subTest(key=key):
                self.rejects(suite, "case_observation_identity")

    def test_observation_type_null_missing_extra_and_duplicate_keys_remain_distinct(self):
        for mutation in ("boolean", "integer", "float", "null", "missing", "extra", "duplicate"):
            suite = self.document()
            output = suite.findall("testcase")[0].find("system-out")
            raw = output.text[len(checker.MARKER) + 1:]
            observation = json.loads(raw)
            if mutation == "missing":
                del observation["outcome"]
            elif mutation == "extra":
                observation["defaultDispatchAccepted"] = False
            elif mutation != "duplicate":
                observation["outcome"] = {"boolean": True, "integer": 1, "float": 1.0, "null": None}[mutation]
            encoded = json.dumps(observation)
            if mutation == "duplicate":
                encoded = encoded[:-1] + ',"outcome":"unknown"}'
            output.text = checker.MARKER + " " + encoded
            with self.subTest(mutation=mutation):
                self.rejects(suite)

    def test_observation_cannot_move_to_suite_properties_guards_or_stderr(self):
        for target in ("suite", "property-name", "property-value", "guard", "stderr"):
            suite = self.document()
            original = suite.findall("testcase")[0].findtext("system-out")
            if target == "suite":
                suite.find("system-out").text = original
            elif target.startswith("property"):
                ET.SubElement(suite.find("properties"), "property",
                              name=original if target == "property-name" else "synthetic",
                              value=original if target == "property-value" else "synthetic")
            elif target == "guard":
                guard = next(case for case in suite.findall("testcase") if case.get("name") in checker.FIXTURE_GUARDS)
                ET.SubElement(guard, "system-out").text = original
            else:
                ET.SubElement(suite.findall("testcase")[0], "system-err").text = original
            with self.subTest(target=target):
                self.rejects(suite)

    def test_missing_repeated_multiline_and_nested_output_cannot_qualify(self):
        for mutation in ("missing", "repeated", "multiline", "nested", "extra-line"):
            suite = self.document()
            case = suite.findall("testcase")[0]
            output = case.find("system-out")
            if mutation == "missing":
                case.remove(output)
            elif mutation == "repeated":
                ET.SubElement(case, "system-out").text = output.text
            elif mutation == "multiline":
                output.text += output.text
            elif mutation == "nested":
                ET.SubElement(output, "synthetic").text = "text"
            else:
                output.text += "\n"
            with self.subTest(mutation=mutation):
                self.rejects(suite)

    def test_xml_encoding_entities_truncation_and_extra_roots_are_rejected(self):
        original = self.raw(self.document())
        mutations = (
            original.decode().encode("utf-16"), b"\0" + original, b"\xff" + original,
            original.replace(b"encoding='utf-8'", b"encoding='iso-8859-1'"),
            b'<!DOCTYPE testsuite [<!ENTITY local "synthetic">]>' + original,
            original[:-20], original + b"<extra/>",
        )
        for raw in mutations:
            with self.subTest(size=len(raw)), self.assertRaises(checker.CheckError):
                checker.qualify_xml(raw, checker.FIXTURE_CLASS, self.expected[checker.FIXTURE_CLASS])

    def test_unknown_suite_shapes_counts_and_duration_aliases_are_rejected(self):
        for mutation in ("suite-name", "suite-field", "count-alias", "case-field", "negative-time",
                         "nan-time", "properties-duplicate", "suite-text", "property-child"):
            suite = self.document()
            if mutation == "suite-name":
                suite.set("name", checker.ADMISSION_CLASS)
            elif mutation == "suite-field":
                suite.set("synthetic", "extra")
            elif mutation == "count-alias":
                suite.set("tests", "0107")
            elif mutation == "case-field":
                suite.findall("testcase")[0].set("status", "passed")
            elif mutation == "negative-time":
                suite.set("time", "-1")
            elif mutation == "nan-time":
                suite.findall("testcase")[0].set("time", "NaN")
            elif mutation == "properties-duplicate":
                ET.SubElement(suite, "properties")
            elif mutation == "suite-text":
                suite.text = "unexpected"
            else:
                prop = ET.SubElement(suite.find("properties"), "property", name="synthetic", value="test")
                ET.SubElement(prop, "nested")
            with self.subTest(mutation=mutation):
                self.rejects(suite)

    def test_missing_second_suite_cannot_claim_175_native_cases(self):
        with tempfile.TemporaryDirectory() as directory:
            reports = Path(directory)
            (reports / ("TEST-" + checker.FIXTURE_CLASS + ".xml")).write_bytes(self.raw(self.document()))
            with self.assertRaises(OSError):
                checker.qualify_native(reports, 0)


if __name__ == "__main__":
    unittest.main()
