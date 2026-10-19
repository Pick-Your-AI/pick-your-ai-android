"""Synthetic adversarial receipt controls; these do not claim JVM case execution."""

from copy import deepcopy
import codecs
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch
import xml.etree.ElementTree as ET

SCRIPT = Path(__file__).resolve().parents[1] / "check_conformance_fixtures.py"
SPEC = importlib.util.spec_from_file_location("conformance_receipts", SCRIPT)
subject = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(subject)


class ConformanceFixtureTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.lock, cls.expected = subject.load_inputs()

    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="android-fixture-receipt-test-")
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.reports = self.root / "reports"
        self.reports.mkdir()
        self.suites = {}
        for name, cases in self.expected.items():
            suite = ET.Element("testsuite", name=name, tests=str(len(cases)), skipped="0", failures="0",
                               errors="0", timestamp="2026-10-18T00:00:00", hostname="SYNTHETIC", time="1.0")
            ET.SubElement(suite, "properties")
            for case_name, observation in cases.items():
                case = ET.SubElement(suite, "testcase", name=case_name, classname=name, time="0.001")
                if observation is not None:
                    marker = subject.MARKERS[0 if name == subject.WIRE_CLASS else 1]
                    ET.SubElement(case, "system-out").text = marker + " " + json.dumps(observation) + "\n"
            ET.SubElement(suite, "system-out")
            ET.SubElement(suite, "system-err")
            self.suites[name] = suite
        self.write_reports()

    def write_reports(self):
        for name, suite in self.suites.items():
            ET.ElementTree(suite).write(self.report_path(name), encoding="utf-8", xml_declaration=True)

    def report_path(self, name):
        return self.reports / ("TEST-" + name + ".xml")

    def qualify(self):
        self.write_reports()
        return subject.qualify(self.reports, 0)

    def wire(self):
        return self.suites[subject.WIRE_CLASS]

    def first_case(self):
        return self.wire().find("testcase")

    def replace_observation(self, value):
        self.first_case().find("system-out").text = "ANDROID_WIRE_CASE " + json.dumps(value) + "\n"

    def test_exact_neutral_bytes_profile_and_private_historical_inputs_remain_pinned(self):
        self.assertEqual(self.lock["neutralProducer"]["cases"], 223)
        self.assertEqual(self.lock["neutralProducer"]["accepted"], 141)
        self.assertEqual(self.lock["privateAndroidStorage"]["historicalFormats"], [1, 2, 3, 4, 5])
        self.assertEqual(sum(len(value) for value in self.expected.values()), 260)
        copied = self.root / "source"
        shutil.copytree(subject.ROOT / subject.RESOURCE, copied / subject.RESOURCE)
        for name in ("consumer-lock.json", "cross-client-v1.json.gz", "local-migrations-v1.json"):
            path = copied / subject.RESOURCE / name
            original = path.read_bytes()
            for bad in (original[:-1], original + b" ", bytes([original[0] ^ 1]) + original[1:]):
                with self.subTest(path=name, length=len(bad)):
                    path.write_bytes(bad)
                    with self.assertRaises(ValueError):
                        subject.load_inputs(copied)
            path.write_bytes(original)
        subject.load_inputs(copied)

    def test_complete_synthetic_parser_control_has_exact_classes_cases_and_observations(self):
        result = self.qualify()
        self.assertEqual(result["tests"], 260)
        self.assertEqual([len(suite["methods"]) for suite in result["suites"]], [225, 35])
        self.assertEqual([len(suite["observations"]) for suite in result["suites"]], [223, 34])
        self.assertEqual(result["wireBundleVersion"], "apple-cross-client-1")
        self.assertEqual(result["migrationBundleVersion"], "android-local-migrations-1")

    def test_nonzero_and_boolean_command_statuses_never_qualify(self):
        for value in (-1, 1, 130, None, False, True, "0", 0.0):
            with self.subTest(value=value), self.assertRaises(ValueError):
                subject.qualify(self.reports, value)

    def test_missing_empty_truncated_and_oversized_reports_fail_closed(self):
        path = self.report_path(subject.WIRE_CLASS)
        raw = path.read_bytes()
        for bad in (b"", raw[:-20], b"x" * (subject.MAX_REPORT_BYTES + 1)):
            path.write_bytes(bad)
            with self.assertRaises((ValueError, ET.ParseError)):
                subject.qualify(self.reports, 0)
        path.unlink()
        with self.assertRaises(OSError):
            subject.qualify(self.reports, 0)

    def test_dtd_entity_and_unknown_result_containers_are_rejected(self):
        path = self.report_path(subject.WIRE_CLASS)
        raw = path.read_bytes()
        for token in (b"<!DOCTYPE testsuite []>", b"<!ENTITY synthetic 'bad'>"):
            path.write_bytes(token + raw)
            with self.assertRaises(ValueError):
                subject.qualify(self.reports, 0)
        ET.SubElement(self.wire(), "testsuite", tests="0")
        with self.assertRaises(ValueError):
            self.qualify()

    def test_plain_utf8_reports_preserve_original_hashes_and_alternate_encodings_reject(self):
        raw = self.report_path(subject.WIRE_CLASS).read_bytes()
        for value in (raw, codecs.BOM_UTF8 + raw, raw.split(b"?>", 1)[1].lstrip()):
            with self.subTest(transport="utf-8", bom=value.startswith(codecs.BOM_UTF8)):
                result = subject.qualify_xml(value, subject.WIRE_CLASS, self.expected[subject.WIRE_CLASS])
                self.assertEqual(len(result["observations"]), 223)
                self.assertEqual(result["xmlBytes"], len(value))
                self.assertEqual(result["xmlSHA256"], hashlib.sha256(value).hexdigest())
        body = raw.decode("utf-8").split("?>", 1)[1]
        for encoding, declaration, bom in (
            ("utf-16-le", "UTF-16", b""), ("utf-16-le", "UTF-16", codecs.BOM_UTF16_LE),
            ("utf-16-be", "UTF-16", b""), ("utf-16-be", "UTF-16", codecs.BOM_UTF16_BE),
            ("utf-32-le", "UTF-32", b""), ("utf-32-le", "UTF-32", codecs.BOM_UTF32_LE),
            ("utf-32-be", "UTF-32", b""), ("utf-32-be", "UTF-32", codecs.BOM_UTF32_BE),
        ):
            value = bom + ('<?xml version="1.0" encoding="' + declaration + '"?>' + body).encode(encoding)
            with self.subTest(encoding=encoding, bom=bool(bom)), self.assertRaises(ValueError):
                subject.qualify_xml(value, subject.WIRE_CLASS, self.expected[subject.WIRE_CLASS])

    def test_internal_entity_cannot_expand_behind_an_alternate_xml_encoding(self):
        body = self.report_path(subject.WIRE_CLASS).read_text().split("?>", 1)[1]
        body = body.replace("apple-cross-client-1", "&fixture;", 1)
        document = '<!DOCTYPE testsuite [<!ENTITY fixture "apple-cross-client-1">]>' + body
        for encoding, declaration, bom in (
            ("utf-8", "UTF-8", b""), ("utf-8", "UTF-8", codecs.BOM_UTF8),
            ("utf-16-le", "UTF-16", b""), ("utf-16-le", "UTF-16", codecs.BOM_UTF16_LE),
            ("utf-16-be", "UTF-16", b""), ("utf-16-be", "UTF-16", codecs.BOM_UTF16_BE),
            ("utf-32-le", "UTF-32", b""), ("utf-32-le", "UTF-32", codecs.BOM_UTF32_LE),
            ("utf-32-be", "UTF-32", b""), ("utf-32-be", "UTF-32", codecs.BOM_UTF32_BE),
        ):
            value = bom + ('<?xml version="1.0" encoding="' + declaration + '"?>' + document).encode(encoding)
            with self.subTest(encoding=encoding, bom=bool(bom)), self.assertRaises(ValueError):
                subject.qualify_xml(value, subject.WIRE_CLASS, self.expected[subject.WIRE_CLASS])

    def test_raw_nul_invalid_utf8_and_conflicting_declared_encoding_fail_before_xml_parse(self):
        raw = self.report_path(subject.WIRE_CLASS).read_bytes()
        body = raw.split(b"?>", 1)[1]
        invalid = (raw + b"\x00", raw + b"\xff", b'<?xml version="1.0" encoding="UTF-16"?>' + body,
                   b'<?xml version="1.0" encoding="ISO-8859-1"?>' + body,
                   b'<?xml version="1.0" encoding="us-ascii"?>' + body)
        for value in invalid:
            with self.subTest(prefix=value[:55]), patch.object(subject.ET, "fromstring") as parse:
                with self.assertRaises(ValueError):
                    subject.qualify_xml(value, subject.WIRE_CLASS, self.expected[subject.WIRE_CLASS])
                parse.assert_not_called()

    def test_wrong_or_unqualified_suite_and_case_identities_are_rejected(self):
        for target, field in ((self.wire(), "name"), (self.first_case(), "classname")):
            original = target.get(field)
            for wrong in ("CrossClientFixtureTest", subject.MIGRATION_CLASS, "other." + original):
                with self.subTest(field=field, wrong=wrong):
                    target.set(field, wrong)
                    with self.assertRaises(ValueError):
                        self.qualify()
            target.set(field, original)

    def test_missing_repeated_unknown_and_padded_case_totals_cannot_pass(self):
        original = deepcopy(self.wire())
        for mode in ("missing", "duplicate", "unknown", "padding", "zero"):
            with self.subTest(mode=mode):
                self.suites[subject.WIRE_CLASS] = deepcopy(original)
                suite = self.wire()
                cases = suite.findall("testcase")
                if mode == "missing":
                    suite.remove(cases[0])
                elif mode == "duplicate":
                    cases[1].set("name", cases[0].get("name"))
                elif mode == "unknown":
                    cases[0].set("name", "wire:SYNTHETIC-unknown")
                elif mode == "padding":
                    suite.append(deepcopy(cases[0]))
                    suite.set("tests", "226")
                else:
                    suite.set("tests", "0")
                with self.assertRaises(ValueError):
                    self.qualify()

    def test_failure_error_skip_retry_and_unknown_case_statuses_cannot_hide(self):
        original = deepcopy(self.first_case())
        for tag in ("failure", "error", "skipped", "flakyFailure", "rerunFailure", "testcase"):
            with self.subTest(tag=tag):
                case = self.first_case()
                child = ET.SubElement(case, tag)
                with self.assertRaises(ValueError):
                    self.qualify()
                case.remove(child)
        self.first_case().set("status", "skipped")
        with self.assertRaises(ValueError):
            self.qualify()
        self.assertNotIn("status", original.attrib)

    def test_report_failure_counters_and_nonfinite_durations_are_rejected(self):
        for field in ("failures", "errors", "skipped"):
            self.wire().set(field, "1")
            with self.subTest(field=field), self.assertRaises(ValueError):
                self.qualify()
            self.wire().set(field, "0")
        for value in ("NaN", "Infinity", "-1", "1e10", ""):
            for target in (self.wire(), self.first_case()):
                prior = target.get("time")
                target.set("time", value)
                with self.subTest(value=value), self.assertRaises(ValueError):
                    self.qualify()
                target.set("time", prior)

    def test_wire_types_distinguish_booleans_numbers_nulls_and_missing_fields(self):
        wanted = deepcopy(self.expected[subject.WIRE_CLASS][self.first_case().get("name")])
        for field, value in (("accepted", 1), ("accepted", "true"), ("preserved", 1),
                             ("preserved", None), ("bundleVersion", "apple-cross-client-2")):
            changed = {**wanted, field: value}
            self.replace_observation(changed)
            with self.subTest(field=field, value=value), self.assertRaises(ValueError):
                self.qualify()
        for changed in ({key: value for key, value in wanted.items() if key != "preserved"},
                        {**wanted, "unexpected": True}):
            self.replace_observation(changed)
            with self.assertRaises(ValueError):
                self.qualify()

    def test_rejected_wire_cases_require_null_preservation(self):
        suite = self.wire()
        case = next(case for case in suite.findall("testcase")
                    if self.expected[subject.WIRE_CLASS][case.get("name")] is not None
                    and not self.expected[subject.WIRE_CLASS][case.get("name")]["accepted"])
        wanted = self.expected[subject.WIRE_CLASS][case.get("name")]
        for value in (False, True, 0, "unknown"):
            case.find("system-out").text = "ANDROID_WIRE_CASE " + json.dumps({**wanted, "preserved": value})
            with self.subTest(value=value), self.assertRaises(ValueError):
                self.qualify()

    def test_private_format_values_original_hashes_and_history_outcomes_are_exact(self):
        suite = self.suites[subject.MIGRATION_CLASS]
        case = suite.find("testcase")
        wanted = deepcopy(self.expected[subject.MIGRATION_CLASS][case.get("name")])
        for field, value in (("currentFormat", True), ("currentFormat", 1.0), ("currentFormat", 4),
                             ("originalPreserved", 1), ("originalPreserved", False),
                             ("inputSha256", "0" * 64), ("outcome", "MIGRATED"),
                             ("bundleVersion", "apple-local-migrations-1")):
            case.find("system-out").text = "ANDROID_MIGRATION_CASE " + json.dumps({**wanted, field: value})
            with self.subTest(field=field, value=value), self.assertRaises(ValueError):
                self.qualify()

    def test_missing_duplicate_and_cross_case_observations_are_rejected(self):
        first, second = self.wire().findall("testcase")[:2]
        original = first.find("system-out").text
        for text in ("", original + original, second.find("system-out").text):
            first.find("system-out").text = text
            with self.subTest(length=len(text)), self.assertRaises(ValueError):
                self.qualify()

    def test_moved_observations_cannot_qualify_from_suite_stderr_or_guard(self):
        case = self.first_case()
        observation = case.find("system-out").text
        guard = next(case for case in self.wire().findall("testcase")
                     if case.get("name") in subject.GUARDS[subject.WIRE_CLASS])
        for location in (self.wire().find("system-out"), ET.SubElement(case, "system-err"),
                         ET.SubElement(guard, "system-out")):
            location.text = observation
            with self.assertRaises(ValueError):
                self.qualify()
            location.text = ""

    def test_observation_framing_and_nested_xml_outputs_are_strict(self):
        output = self.first_case().find("system-out")
        original = output.text
        for text in (original.replace("CASE ", "CASE\t"), " " + original, original + "extra\n",
                     "ANDROID_MIGRATION_CASE " + original.split(" ", 1)[1]):
            output.text = text
            with self.assertRaises(ValueError):
                self.qualify()
        output.text = original
        ET.SubElement(output, "failure")
        with self.assertRaises(ValueError):
            self.qualify()

    def test_duplicate_escaped_keys_nonfinite_values_and_surrogates_are_rejected(self):
        output = self.first_case().find("system-out")
        original = output.text
        variants = (
            original.replace('"accepted": true', '"accepted": true, "acce\\u0070ted": true'),
            original.replace('"preserved": true', '"preserved": NaN'),
            original.replace('"preserved": true', '"preserved": Infinity'),
            original.replace('"preserved": true', '"preserved": 1e9999'),
            original.replace('"preserved": true', '"preserved": "\\ud800"'),
        )
        for text in variants:
            output.text = text
            with self.subTest(text=text[-60:]), self.assertRaises(ValueError):
                self.qualify()

    def test_nonfinite_and_duplicate_keys_reject_in_standalone_protocol_parser(self):
        for raw in ('{"a": 1, "\\u0061": 2}', '{"a": NaN}', '{"a": Infinity}', '"\\udfff"'):
            with self.subTest(raw=raw), self.assertRaises(ValueError):
                subject.strict_json(raw)
        self.assertFalse(subject.strictly_equal({"value": True}, {"value": 1}))
        self.assertFalse(subject.strictly_equal({"value": None}, {}))

    def test_output_cannot_unlink_source_reports_symlinks_hardlinks_or_directories(self):
        source_file = subject.ROOT / subject.RESOURCE / "consumer-lock.json"
        source_bytes = source_file.read_bytes()
        report = self.report_path(subject.WIRE_CLASS)
        original_report = report.read_bytes()
        for path in (source_file, report, self.reports):
            with self.subTest(path=path), self.assertRaises(ValueError):
                subject.prepare_output(path, self.reports)
        target = self.root / "target.json"
        target.write_text("SYNTHETIC original")
        link = self.root / "symlink.json"
        link.symlink_to(target)
        hard = self.root / "hardlink.json"
        os.link(target, hard)
        for path in (link, hard, target):
            with self.subTest(path=path), self.assertRaises(ValueError):
                subject.prepare_output(path, self.reports)
        self.assertEqual(source_file.read_bytes(), source_bytes)
        self.assertEqual(report.read_bytes(), original_report)
        self.assertEqual(target.read_text(), "SYNTHETIC original")

    def test_old_success_is_removed_before_failed_attempt_then_valid_recovery(self):
        output = self.root / "qualified.json"
        command = [sys.executable, "-B", str(SCRIPT), "--reports", str(self.reports),
                   "--exit-code", "0", "--output", str(output)]
        result = subprocess.run(command, capture_output=True, text=True)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertTrue(output.is_file())
        self.first_case().find("system-out").text = ""
        self.write_reports()
        result = subprocess.run(command, capture_output=True, text=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertFalse(output.exists())
        self.assertFalse(list(self.root.glob(".fixture-receipt-*")))
        self.assertIn("rejected", result.stderr)
        wanted = self.expected[subject.WIRE_CLASS][self.first_case().get("name")]
        self.replace_observation(wanted)
        self.write_reports()
        recovered = subprocess.run(command, capture_output=True, text=True)
        self.assertEqual(recovered.returncode, 0, recovered.stderr)
        self.assertEqual(json.loads(output.read_text())["tests"], 260)

    def test_nonzero_command_status_removes_old_success_without_reading_reports(self):
        output = self.root / "qualified.json"
        output.write_text('{"old": "SYNTHETIC successful receipt"}')
        result = subprocess.run([sys.executable, "-B", str(SCRIPT), "--reports", str(self.reports),
                                 "--exit-code", "130", "--output", str(output)], capture_output=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertFalse(output.exists())

    def test_atomic_publication_failure_leaves_no_success_or_temporary_output(self):
        output = self.root / "qualified.json"
        output.write_text("SYNTHETIC old success")
        prepared = subject.prepare_output(output, self.reports)
        with patch.object(subject.os, "replace", side_effect=OSError("SYNTHETIC disk failure")):
            with self.assertRaises(OSError):
                subject.publish(prepared, self.qualify())
        self.assertFalse(output.exists())
        self.assertFalse(list(self.root.glob(".fixture-receipt-*")))


if __name__ == "__main__":
    unittest.main()
