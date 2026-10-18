"""Host-side guard tests; these do not install or run an Android app."""

import copy
import importlib.util
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("emulator_smoke", ROOT / "scripts/android_emulator_smoke.py")
smoke = importlib.util.module_from_spec(spec)
spec.loader.exec_module(smoke)


def node(text="", kind="android.widget.TextView", package=smoke.PACKAGE, box="[0,0][100,100]"):
    return ET.Element("node", text=text, **{"class": kind, "package": package, "bounds": box, "enabled": "true"})


class EmulatorSmokeGuardTest(unittest.TestCase):
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

    def test_incomplete_or_differently_scoped_receipts_fail(self):
        receipt = {"source_commit": "a" * 40, "scope": "owned-installed-aosp-emulator", "status": "passed",
                   "unsigned_apk_unchanged": True,
                   "cases": [{"id": name, "status": "passed"} for name in smoke.CASE_IDS]}
        smoke.complete_receipt(receipt, "a" * 40)
        invalid = []
        for key, value in (("source_commit", "b" * 40), ("scope", "simulated"),
                           ("status", "failed"), ("unsigned_apk_unchanged", False)):
            changed = copy.deepcopy(receipt)
            changed[key] = value
            invalid.append(changed)
        missing, duplicate, skipped = [copy.deepcopy(receipt) for _ in range(3)]
        missing["cases"].pop()
        duplicate["cases"][1] = duplicate["cases"][0]
        skipped["cases"][2]["status"] = "not_run"
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
