"""Host-side guard tests; these do not install or run an Android app."""

import copy
import importlib.util
import io
import os
from pathlib import Path
import stat
import subprocess
import tempfile
import unittest
from unittest.mock import Mock, call, patch
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("emulator_smoke", ROOT / "scripts/android_emulator_smoke.py")
smoke = importlib.util.module_from_spec(spec)
spec.loader.exec_module(smoke)


def node(text="", kind="android.widget.TextView", package=smoke.PACKAGE, box="[0,0][100,100]"):
    return ET.Element("node", text=text, **{"class": kind, "package": package, "bounds": box, "enabled": "true"})


class EmulatorSmokeGuardTest(unittest.TestCase):
    def test_software_mode_is_explicit_and_never_uses_kvm_or_changes_host_access(self):
        with patch.dict(os.environ, {"PYAI_EMULATOR_MODE": "software"}), \
                patch.object(smoke, "check_kvm_access") as kvm, \
                patch.object(smoke, "command") as command, patch("sys.stdout", new_callable=io.StringIO):
            self.assertEqual({"mode": "software", "accel_argument": "off", "boot_timeout_seconds": 900,
                              "unsupported_software_opt_in": True}, smoke.emulation_settings())
            kvm.assert_not_called()
            command.assert_not_called()

    def test_default_hardware_mode_cannot_fall_back_after_denied_access(self):
        with patch.dict(os.environ, {}, clear=True), \
                patch.object(smoke, "check_kvm_access", side_effect=smoke.SmokeFailure("Access denied")) as kvm:
            with self.assertRaisesRegex(smoke.SmokeFailure, "Access denied"):
                smoke.emulation_settings()
            kvm.assert_called_once_with()

    def test_unknown_emulation_mode_fails_before_any_host_probe(self):
        with patch.dict(os.environ, {"PYAI_EMULATOR_MODE": "automatic"}), \
                patch.object(smoke, "check_kvm_access") as kvm:
            with self.assertRaisesRegex(smoke.SmokeFailure, "Unknown emulator mode"):
                smoke.emulation_settings()
            kvm.assert_not_called()

    def test_kvm_preflight_rejects_missing_regular_and_symlink_devices(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            regular = root / "regular"
            regular.touch()
            link = root / "link"
            link.symlink_to(regular)
            with patch.object(smoke.os, "access") as access, patch.object(smoke, "command") as command, \
                    patch("sys.stdout", new_callable=io.StringIO):
                for device in (root / "missing", regular, link):
                    with self.subTest(device=device.name), self.assertRaises(smoke.SmokeFailure):
                        smoke.check_kvm_access(device)
                access.assert_not_called()
                command.assert_not_called()

    def test_kvm_metadata_permission_error_fails_without_access_changes(self):
        with patch.object(Path, "lstat", side_effect=PermissionError), \
                patch.object(smoke.os, "access") as access, patch.object(smoke.os, "chown") as chown, \
                patch.object(smoke.os, "chmod") as chmod, patch.object(smoke, "command") as command, \
                patch("sys.stdout", new_callable=io.StringIO):
            with self.assertRaisesRegex(smoke.SmokeFailure, "absent or unreadable"):
                smoke.check_kvm_access()
            access.assert_not_called()
            chown.assert_not_called()
            chmod.assert_not_called()
            command.assert_not_called()

    def test_kvm_denied_access_fails_without_attempting_permission_repair(self):
        metadata = os.stat_result((stat.S_IFCHR | 0o660, 1, 1, 1, 1001, 108, 0, 0, 0, 0))
        with patch.object(Path, "lstat", return_value=metadata), \
                patch.object(smoke.os, "access", return_value=False) as access, \
                patch.object(smoke.os, "chown") as chown, patch.object(smoke.os, "chmod") as chmod, \
                patch.object(smoke, "command") as command, patch("sys.stdout", new_callable=io.StringIO):
            with self.assertRaisesRegex(smoke.SmokeFailure, "read/write access is unavailable"):
                smoke.check_kvm_access()
            access.assert_called_once_with(Path("/dev/kvm"), os.R_OK | os.W_OK, effective_ids=True)
            chown.assert_not_called()
            chmod.assert_not_called()
            command.assert_not_called()

    def test_kvm_existing_character_device_access_is_accepted_without_commands(self):
        metadata = os.stat_result((stat.S_IFCHR | 0o660, 1, 1, 1, 1001, 108, 0, 0, 0, 0))
        with patch.object(Path, "lstat", return_value=metadata), \
                patch.object(smoke.os, "access", return_value=True) as access, \
                patch.object(smoke.os, "chown") as chown, patch.object(smoke.os, "chmod") as chmod, \
                patch.object(smoke, "command") as command, patch("sys.stdout", new_callable=io.StringIO) as output:
            smoke.check_kvm_access()
            access.assert_called_once_with(Path("/dev/kvm"), os.R_OK | os.W_OK, effective_ids=True)
            self.assertIn("mode=crw-rw---- owner=1001 group=108", output.getvalue())
            chown.assert_not_called()
            chmod.assert_not_called()
            command.assert_not_called()

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

    def test_dump_requires_removal_then_fresh_xml_and_records_completed_137(self):
        path = "/sdcard/pyai-smoke-window.xml"
        raw = '<hierarchy rotation="0"><node package="' + smoke.PACKAGE + '" /></hierarchy>'
        for status in (0, 137):
            with self.subTest(status=status):
                device, receipt = Mock(spec=smoke.Device), {}
                device.adb.side_effect = [
                    subprocess.CompletedProcess([], status, "UI hierchary dumped to: " + path + "\n", ""),
                    subprocess.CompletedProcess([], 0, raw, ""),
                ]
                root = smoke.Ui(device, Path("unused"), receipt).snapshot()
                self.assertEqual("hierarchy", root.tag)
                self.assertEqual([
                    call.shell("rm", "-f", path),
                    call.adb("shell", "uiautomator", "dump", path, timeout=20, check=False),
                    call.adb("exec-out", "cat", path),
                ], device.mock_calls)
                self.assertEqual({"attempted": 1, "completed": 1, "exit_137": int(status == 137)},
                                 receipt["ui_hierarchy_dumps"])

    def test_failed_prior_dump_removal_cannot_reuse_stale_xml(self):
        device, receipt = Mock(spec=smoke.Device), {}
        device.shell.side_effect = smoke.SmokeFailure("Dump removal failed")
        with self.assertRaisesRegex(smoke.SmokeFailure, "removal failed"):
            smoke.Ui(device, Path("unused"), receipt).snapshot()
        device.adb.assert_not_called()
        self.assertEqual(0, receipt["ui_hierarchy_dumps"]["completed"])

    def test_unrelated_dump_failure_or_inexact_completion_is_rejected(self):
        marker = "UI hierchary dumped to: /sdcard/pyai-smoke-window.xml"
        for status, stdout, stderr in (
            (1, marker, ""), (137, "", ""), (137, marker, "Killed"),
            (137, marker + "\nERROR: dump failed", ""),
            (137, "UI hierchary dumped to: /sdcard/another.xml", ""),
        ):
            with self.subTest(status=status, stdout=stdout, stderr=stderr):
                device, receipt = Mock(spec=smoke.Device), {}
                device.adb.return_value = subprocess.CompletedProcess([], status, stdout, stderr)
                with self.assertRaisesRegex(smoke.SmokeFailure, "UI hierarchy dump failed"):
                    smoke.Ui(device, Path("unused"), receipt).snapshot()
                self.assertEqual(1, device.adb.call_count)
                self.assertEqual(0, receipt["ui_hierarchy_dumps"]["completed"])

    def test_completion_message_cannot_replace_missing_truncated_or_invalid_xml(self):
        marker = "UI hierchary dumped to: /sdcard/pyai-smoke-window.xml"
        for raw in (None, "", '<hierarchy rotation="0"><node>',
                    '<other><node /></other>', '<hierarchy rotation="0" />',
                    '<hierarchy rotation="9"><node /></hierarchy>'):
            with self.subTest(raw=raw):
                device, receipt = Mock(spec=smoke.Device), {}
                read = (smoke.SmokeFailure("Fresh dump missing") if raw is None else
                        subprocess.CompletedProcess([], 0, raw, ""))
                device.adb.side_effect = [subprocess.CompletedProcess([], 137, marker, ""), read]
                with self.assertRaises((smoke.SmokeFailure, ET.ParseError)):
                    smoke.Ui(device, Path("unused"), receipt).snapshot()
                self.assertEqual(0, receipt["ui_hierarchy_dumps"]["completed"])
                self.assertEqual(0, receipt["ui_hierarchy_dumps"]["exit_137"])

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
