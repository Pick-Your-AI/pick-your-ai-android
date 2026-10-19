"""Exercise credential-boundary rejection and receipt flow without host operations."""

import copy
import importlib.util
import json
import os
from pathlib import Path
import stat
import subprocess
import sys
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch
import zipfile


ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("kvm_process", ROOT / "scripts/android_kvm_process.py")
boundary = importlib.util.module_from_spec(spec)
spec.loader.exec_module(boundary)


def original_identity():
    return {"uid": 1001, "effective_uid": 1001, "gid": 1001, "effective_gid": 1001,
            "supplementary_groups": [4, 100, 118, 999, 1001], "read_write_access": False,
            "kvm_group": {"name": "kvm", "gid": 993},
            "device": {"path": "/dev/kvm", "mode": stat.S_IFCHR | 0o660, "uid": 0, "gid": 993,
                       "filesystem_device": 5, "inode": 101, "rdev": 2560}}


def selected_identity():
    selected = original_identity()
    selected.update(gid=993, effective_gid=993, read_write_access=True,
                    sudo_uid="1001", sudo_gid="1001")
    return selected


def context():
    return {"environment": {"PYAI_EMULATOR_MODE": "kvm", "GITHUB_SHA": "a" * 40,
                            "PYAI_EXPECTED_APK_SHA256": "b" * 64, "PYAI_KVM_PROCESS": boundary.MARKER,
                            "PYAI_EMULATOR_BINARY": str(Path(sys.executable).resolve())},
            "child_command": ["/usr/bin/python3.12", "-I", str(ROOT / "scripts/android_kvm_process.py"), "--child"],
            "source_commit": "a" * 40, "source_tree": "c" * 40}


def policy_receipt():
    before, selected = original_identity(), context()
    return {"schema_version": 1, "scope": boundary.MARKER, "status": "policy_checked",
            "before": before, "context": selected,
            # A read-only ordinary local file stands in for package identity; it is never executed as an emulator.
            "emulator_package": {"name": boundary.EMULATOR_ARCHIVE_NAME, "url": boundary.EMULATOR_ARCHIVE_URL,
                                 "sha256": boundary.EMULATOR_ARCHIVE_SHA256, "bytes": 1,
                                 "binary": selected["environment"]["PYAI_EMULATOR_BINARY"],
                                 "binary_sha256": boundary.file_digest(Path(selected["environment"]["PYAI_EMULATOR_BINARY"]))},
            "policy_listing": {"exit_code": 0, "stdout": "exact command allowed\n", "stderr": "",
                               "output_truncated": False,
                               "arguments": boundary.sudo_arguments(before, selected, listing=True)}}


class KvmProcessBoundaryTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.report = Path(self.temporary.name) / "boundary/receipt.json"
        self.report_patch = patch.object(boundary, "REPORT", self.report)
        self.report_patch.start()
        self.addCleanup(self.report_patch.stop)

    def save_policy(self):
        self.report.parent.mkdir()
        boundary.write_receipt(policy_receipt())

    def observe_original(self, destination):
        destination.update(original_identity())

    def test_policy_listing_names_exact_numeric_user_group_and_absolute_child(self):
        with patch.object(boundary, "current_context", return_value=context()), \
                patch.object(boundary, "observe_identity", side_effect=self.observe_original), \
                patch.object(boundary.subprocess, "run", return_value=subprocess.CompletedProcess([], 0, "allowed\n", "")) as run:
            boundary.check_policy()
        receipt = boundary.read_receipt()
        self.assertEqual("policy_checked", receipt["status"])
        arguments = run.call_args.args[0]
        self.assertEqual(["/usr/bin/sudo", "--non-interactive", "--user=#1001", "--group=#993", "--list", "--",
                          *context()["child_command"]], arguments)
        self.assertEqual(15, run.call_args.kwargs["timeout"])
        self.assertNotIn("inside", receipt)
        self.assertNotIn("--preserve-env", " ".join(arguments))
        with self.assertRaises(boundary.BoundaryFailure):
            boundary.complete_boundary(receipt, context())

    def test_policy_denial_or_ambiguous_listing_cannot_invoke_a_child(self):
        for result in (subprocess.CompletedProcess([], 1, "", "not permitted"),
                       subprocess.CompletedProcess([], 0, "allowed", "policy warning"),
                       subprocess.CompletedProcess([], 0, "x" * 8193, "")):
            with self.subTest(result=result), tempfile.TemporaryDirectory() as temporary, \
                    patch.object(boundary, "REPORT", Path(temporary) / "boundary/receipt.json"), \
                    patch.object(boundary, "current_context", return_value=context()), \
                    patch.object(boundary, "observe_identity", side_effect=self.observe_original), \
                    patch.object(boundary.subprocess, "run", return_value=result) as run:
                with self.assertRaisesRegex(boundary.BoundaryFailure, "no fallback"):
                    boundary.check_policy()
                self.assertEqual(1, run.call_count)
                self.assertEqual("policy_failed", boundary.read_receipt()["status"])
                self.assertNotIn("inside", boundary.read_receipt())

    def test_policy_timeout_retains_partial_result_and_original_exception(self):
        failure = subprocess.TimeoutExpired("sudo", 15, output=b"partial policy", stderr=b"waiting")
        with patch.object(boundary, "current_context", return_value=context()), \
                patch.object(boundary, "observe_identity", side_effect=self.observe_original), \
                patch.object(boundary.subprocess, "run", side_effect=failure):
            with self.assertRaises(subprocess.TimeoutExpired) as raised:
                boundary.check_policy()
        self.assertIs(failure, raised.exception)
        result = boundary.read_receipt()["policy_listing"]
        self.assertEqual(15, result["timeout_seconds"])
        self.assertIn("partial policy", result["stdout"])
        self.assertNotIn("exit_code", result)

    def test_environment_is_explicit_and_excludes_secrets_and_loader_controls(self):
        environment = {key: "value" for key in boundary.ENVIRONMENT_KEYS}
        environment.update(GITHUB_ACTIONS="true", RUNNER_ENVIRONMENT="github-hosted", RUNNER_OS="Linux",
                           RUNNER_ARCH="X64", PYAI_EMULATOR_MODE="kvm", GITHUB_SHA="a" * 40,
                           PYAI_EXPECTED_APK_SHA256="b" * 64, GITHUB_RUN_ID="12", GITHUB_RUN_ATTEMPT="1",
                           ANDROID_HOME=self.temporary.name, JAVA_HOME=self.temporary.name,
                           RUNNER_TEMP=self.temporary.name, GITHUB_WORKSPACE=str(ROOT),
                           GITHUB_TOKEN="not-a-real-token", PYTHONPATH="/unexpected", PYTHONHOME="/unexpected",
                           LD_PRELOAD="/unexpected", LD_LIBRARY_PATH="/unexpected", SUDO_UID="0", PATH="/unexpected")
        with patch.dict(os.environ, environment, clear=True):
            selected = boundary.selected_environment()
        self.assertEqual(set(boundary.ENVIRONMENT_KEYS) | {"PATH", "LANG", "LC_ALL", "PYAI_KVM_PROCESS", "PYAI_EMULATOR_BINARY"}, set(selected))
        self.assertEqual(self.temporary.name + "/bin:/usr/bin:/bin", selected["PATH"])
        self.assertEqual(boundary.MARKER, selected["PYAI_KVM_PROCESS"])
        for key in boundary.ENVIRONMENT_KEYS:
            incomplete = dict(environment)
            incomplete.pop(key)
            with self.subTest(missing=key), patch.dict(os.environ, incomplete, clear=True):
                with self.assertRaises(boundary.BoundaryFailure):
                    boundary.selected_environment()
        for key, value in (("PYAI_EMULATOR_MODE", "software"), ("RUNNER_ENVIRONMENT", "self-hosted"),
                           ("RUNNER_OS", "macOS"), ("RUNNER_ARCH", "ARM64"), ("GITHUB_ACTIONS", "false"),
                           ("GITHUB_SHA", "main"), ("PYAI_EXPECTED_APK_SHA256", "b" * 63),
                           ("GITHUB_RUN_ATTEMPT", "0"), ("ANDROID_HOME", "relative")):
            invalid = dict(environment, **{key: value})
            with self.subTest(key=key, value=value), patch.dict(os.environ, invalid, clear=True):
                with self.assertRaises(boundary.BoundaryFailure):
                    boundary.selected_environment()

    def test_interpreter_is_absolute_isolated_and_same_python_minor(self):
        with patch.object(sys, "executable", "/usr/bin/python3"), patch.object(sys, "version_info", (3, 12, 9)):
            arguments = boundary.fixed_child_command()
        self.assertEqual(str(Path("/usr/bin/python3").resolve()), arguments[0])
        self.assertEqual(["-I", str(ROOT / "scripts/android_kvm_process.py"), "--child"], arguments[1:])
        for executable, version in (("/unexpected/python", (3, 12, 9)), ("/usr/bin/python3", (3, 13, 0))):
            with patch.object(sys, "executable", executable), patch.object(sys, "version_info", version):
                with self.assertRaises(boundary.BoundaryFailure):
                    boundary.fixed_child_command()

    def test_identity_rejects_root_mismatched_user_and_noncharacter_or_linked_device(self):
        for uid, effective_uid, mode, owner, group in (
                (0, 0, stat.S_IFCHR | 0o660, 0, 993), (1001, 0, stat.S_IFCHR | 0o660, 0, 993),
                (1001, 1001, stat.S_IFREG | 0o660, 0, 993),
                (1001, 1001, stat.S_IFLNK | 0o777, 0, 993),
                (1001, 1001, stat.S_IFCHR | 0o660, 1001, 993),
                (1001, 1001, stat.S_IFCHR | 0o660, 0, 0)):
            metadata = SimpleNamespace(st_mode=mode, st_uid=owner, st_gid=group, st_dev=5, st_ino=101, st_rdev=2560)
            with self.subTest(uid=uid, effective_uid=effective_uid, mode=mode, owner=owner, group=group), \
                    patch.object(os, "getuid", return_value=uid), patch.object(os, "geteuid", return_value=effective_uid), \
                    patch.object(os, "getgid", return_value=1001), patch.object(os, "getegid", return_value=1001), \
                    patch.object(os, "getgroups", return_value=[1001]), \
                    patch.object(Path, "lstat", return_value=metadata), patch.object(os, "access") as access:
                with self.assertRaises(boundary.BoundaryFailure):
                    boundary.observe_identity({})
                access.assert_not_called()

    def test_device_observation_allows_inaccessible_parent_and_uses_effective_access(self):
        metadata = SimpleNamespace(st_mode=stat.S_IFCHR | 0o660, st_uid=0, st_gid=993, st_dev=5, st_ino=101, st_rdev=2560)
        with patch.object(os, "getuid", return_value=1001), patch.object(os, "geteuid", return_value=1001), \
                patch.object(os, "getgid", return_value=1001), patch.object(os, "getegid", return_value=1001), \
                patch.object(os, "getgroups", return_value=[1001, 4]), \
                patch.object(boundary.grp, "getgrnam", return_value=SimpleNamespace(gr_name="kvm", gr_gid=993)), \
                patch.object(Path, "lstat", return_value=metadata), patch.object(os, "access", return_value=False) as access:
            observed = {}
            boundary.observe_identity(observed)
        self.assertFalse(observed["read_write_access"])
        self.assertEqual([4, 1001], observed["supplementary_groups"])
        access.assert_called_once_with(Path("/dev/kvm"), os.R_OK | os.W_OK, effective_ids=True)

    def test_device_must_match_the_existing_named_kvm_group_without_correction(self):
        metadata = SimpleNamespace(st_mode=stat.S_IFCHR | 0o660, st_uid=0, st_gid=993, st_dev=5, st_ino=101, st_rdev=2560)
        for group in (SimpleNamespace(gr_name="kvm", gr_gid=0), SimpleNamespace(gr_name="kvm", gr_gid=100),
                      SimpleNamespace(gr_name="docker", gr_gid=993), KeyError("no kvm group")):
            with self.subTest(group=group), patch.object(os, "getuid", return_value=1001), \
                    patch.object(os, "geteuid", return_value=1001), patch.object(os, "getgid", return_value=1001), \
                    patch.object(os, "getegid", return_value=1001), patch.object(os, "getgroups", return_value=[1001]), \
                    patch.object(Path, "lstat", return_value=metadata), patch.object(os, "access") as access, \
                    patch.object(boundary.grp, "getgrnam", **({"side_effect": group} if isinstance(group, Exception)
                                                            else {"return_value": group})) as lookup:
                with self.assertRaises((boundary.BoundaryFailure, KeyError)):
                    boundary.observe_identity({})
                lookup.assert_called_once_with("kvm")
                access.assert_not_called()

    def test_child_records_verified_identity_then_execs_only_fixed_harness_with_clean_environment(self):
        self.save_policy()
        receipt = boundary.read_receipt()
        receipt["status"] = "invoking"
        boundary.write_receipt(receipt)
        with patch.object(boundary, "current_context", return_value=context()), \
                patch.object(boundary, "observe_identity", side_effect=lambda observed: observed.update(selected_identity())), \
                patch.dict(os.environ, dict(context()["environment"], SUDO_UID="1001", SUDO_GID="1001"), clear=True), \
                patch.object(os, "execve", side_effect=SystemExit(0)) as execute:
            with self.assertRaises(SystemExit):
                boundary.enter_child()
        self.assertEqual("inside_verified", boundary.read_receipt()["status"])
        self.assertEqual(selected_identity(), boundary.read_receipt()["inside"])
        execute.assert_called_once_with("/usr/bin/python3.12",
                                        ["/usr/bin/python3.12", "-I", str(ROOT / "scripts/android_emulator_smoke.py")],
                                        context()["environment"])

    def test_child_cannot_exec_after_wrong_credentials_device_access_or_environment(self):
        variants = [("uid", 0), ("effective_uid", 1002), ("gid", 1001), ("effective_gid", 0),
                    ("read_write_access", False), ("sudo_uid", "0"), ("sudo_gid", "993"),
                    ("device", dict(original_identity()["device"], inode=999))]
        self.save_policy()
        for key, value in variants:
            receipt = policy_receipt()
            receipt["status"] = "invoking"
            boundary.write_receipt(receipt)
            selected = dict(selected_identity(), **{key: value})
            with self.subTest(key=key), patch.object(boundary, "current_context", return_value=context()), \
                    patch.object(boundary, "observe_identity", side_effect=lambda observed: observed.update(selected)), \
                    patch.dict(os.environ, context()["environment"], clear=True), patch.object(os, "execve") as execute:
                with self.assertRaises(boundary.BoundaryFailure):
                    boundary.enter_child()
                execute.assert_not_called()
                self.assertEqual("inside_failed", boundary.read_receipt()["status"])
        for changed_context in (dict(context(), source_commit="d" * 40),
                                dict(context(), environment=dict(context()["environment"], PYAI_EMULATOR_MODE="software"))):
            receipt = policy_receipt()
            receipt["status"] = "invoking"
            boundary.write_receipt(receipt)
            with patch.object(boundary, "current_context", return_value=changed_context), \
                    patch.dict(os.environ, context()["environment"], clear=True), patch.object(os, "execve") as execute:
                with self.assertRaises(boundary.BoundaryFailure):
                    boundary.enter_child()
                execute.assert_not_called()

    def successful_child(self, arguments, **keywords):
        receipt = boundary.read_receipt()
        self.assertEqual("invoking", receipt["status"])
        receipt.update(status="inside_verified", inside=selected_identity())
        boundary.write_receipt(receipt)
        return subprocess.CompletedProcess(arguments, 0)

    def test_parent_records_all_phases_and_executes_one_exact_environment_bearing_command(self):
        self.save_policy()
        with patch.object(boundary, "current_context", return_value=context()), \
                patch.object(boundary, "observe_identity", side_effect=self.observe_original), \
                patch.object(subprocess, "run", side_effect=self.successful_child) as run:
            boundary.run_harness()
        receipt = boundary.read_receipt()
        boundary.complete_boundary(receipt, context())
        self.assertEqual(original_identity(), receipt["before"])
        self.assertEqual(selected_identity(), receipt["inside"])
        self.assertEqual(original_identity(), receipt["after"])
        run.assert_called_once_with(boundary.sudo_arguments(original_identity(), context(), listing=False),
                                    env=context()["environment"], check=False)
        self.assertNotIn("--list", run.call_args.args[0])
        self.assertIn("--preserve-env=" + ",".join(sorted(context()["environment"])), run.call_args.args[0])

    def test_actual_environment_policy_denial_is_terminal_even_after_successful_listing(self):
        self.save_policy()
        with patch.object(boundary, "current_context", return_value=context()), \
                patch.object(boundary, "observe_identity", side_effect=self.observe_original), \
                patch.object(subprocess, "run", return_value=subprocess.CompletedProcess([], 1)) as run:
            with self.assertRaisesRegex(boundary.BoundaryFailure, "no fallback"):
                boundary.run_harness()
        receipt = boundary.read_receipt()
        self.assertEqual(1, run.call_count)
        self.assertEqual(1, receipt["child_exit_code"])
        self.assertEqual("failed", receipt["status"])
        self.assertNotIn("inside", receipt)
        self.assertEqual(original_identity(), receipt["after"])

    def test_zero_exit_without_child_identity_is_not_success(self):
        self.save_policy()
        with patch.object(boundary, "current_context", return_value=context()), \
                patch.object(boundary, "observe_identity", side_effect=self.observe_original), \
                patch.object(subprocess, "run", return_value=subprocess.CompletedProcess([], 0)):
            with self.assertRaisesRegex(boundary.BoundaryFailure, "never verified"):
                boundary.run_harness()
        self.assertEqual("failed", boundary.read_receipt()["status"])

    def test_child_failure_is_preserved_when_after_observation_also_fails(self):
        self.save_policy()
        failure = OSError("original invocation failure")
        calls = []
        def observe(destination):
            calls.append(destination)
            if len(calls) == 1:
                self.observe_original(destination)
            else:
                raise OSError("later device observation failed")
        with patch.object(boundary, "current_context", return_value=context()), \
                patch.object(boundary, "observe_identity", side_effect=observe), \
                patch.object(subprocess, "run", side_effect=failure):
            with self.assertRaises(OSError) as raised:
                boundary.run_harness()
        self.assertIs(failure, raised.exception)
        receipt = boundary.read_receipt()
        self.assertEqual("original invocation failure", receipt["error"])
        self.assertEqual("later device observation failed", receipt["after_error"])

    def test_changed_invoking_groups_after_child_success_cannot_pass(self):
        self.save_policy()
        calls = []
        def observe(destination):
            self.observe_original(destination)
            calls.append(destination)
            if len(calls) == 2:
                destination["supplementary_groups"].append(993)
        with patch.object(boundary, "current_context", return_value=context()), \
                patch.object(boundary, "observe_identity", side_effect=observe), \
                patch.object(subprocess, "run", side_effect=self.successful_child):
            with self.assertRaises(boundary.BoundaryFailure):
                boundary.run_harness()
        self.assertEqual("failed", boundary.read_receipt()["status"])

    def test_final_receipt_requires_each_phase_exact_identity_and_real_integer_success(self):
        receipt = policy_receipt()
        receipt.update(status="passed", inside=selected_identity(), invocation_before=original_identity(),
                       after=original_identity(), child_exit_code=0)
        boundary.complete_boundary(receipt, context())
        for key, value in (("status", "inside_verified"), ("inside", {}), ("after", {}),
                           ("invocation_before", {}), ("child_exit_code", False),
                           ("error", "failed"), ("context", dict(context(), source_tree="d" * 40))):
            with self.subTest(key=key):
                invalid = copy.deepcopy(receipt)
                invalid[key] = value
                with self.assertRaises(boundary.BoundaryFailure):
                    boundary.complete_boundary(invalid, context())
        for key, value in (("timeout_seconds", 15), ("exit_code", False), ("stderr", "warning"),
                           ("output_truncated", True), ("arguments", ["/usr/bin/id"])):
            with self.subTest(policy=key):
                invalid = copy.deepcopy(receipt)
                invalid["policy_listing"][key] = value
                with self.assertRaises(boundary.BoundaryFailure):
                    boundary.complete_boundary(invalid, context())

    def test_final_context_failure_is_recorded_before_the_receipt_can_claim_success(self):
        self.save_policy()
        failure = boundary.BoundaryFailure("final APK or checkout validation failed")
        with patch.object(boundary, "current_context", side_effect=[context(), failure]), \
                patch.object(boundary, "observe_identity", side_effect=self.observe_original), \
                patch.object(subprocess, "run", side_effect=self.successful_child):
            with self.assertRaises(boundary.BoundaryFailure) as raised:
                boundary.run_harness()
        self.assertIs(failure, raised.exception)
        receipt = boundary.read_receipt()
        self.assertEqual("failed", receipt["status"])
        self.assertEqual(str(failure), receipt["error"])
        self.assertEqual(0, receipt["child_exit_code"])
        self.assertEqual(original_identity(), receipt["after"])

    def test_archive_hash_and_owned_extraction_paths_are_checked_before_use(self):
        archive = Path(self.temporary.name) / "emulator.zip"
        destination = Path(self.temporary.name) / "destination"
        with zipfile.ZipFile(archive, "w") as zipped:
            zipped.writestr("emulator/emulator", "synthetic executable contents")
        with self.assertRaisesRegex(boundary.BoundaryFailure, "hash differs"):
            boundary.verify_archive(archive, destination)
        with patch.object(boundary, "EMULATOR_ARCHIVE_SHA256", boundary.file_digest(archive)):
            observed = boundary.verify_archive(archive, destination)
        self.assertEqual(archive.stat().st_size, observed["bytes"])
        self.assertFalse(destination.exists())
        for member, link_target in (("../outside", None), ("/absolute", None), ("other/tool", None),
                                    ("emulator/link", "../../outside"), ("emulator/link", "/absolute")):
            with self.subTest(member=member, link_target=link_target):
                with zipfile.ZipFile(archive, "w") as zipped:
                    zipped.writestr("emulator/emulator", "synthetic executable contents")
                    if link_target is None:
                        zipped.writestr(member, "unexpected")
                    else:
                        info = zipfile.ZipInfo(member)
                        info.external_attr = (stat.S_IFLNK | 0o777) << 16
                        zipped.writestr(info, link_target)
                with patch.object(boundary, "EMULATOR_ARCHIVE_SHA256", boundary.file_digest(archive)):
                    with self.assertRaisesRegex(boundary.BoundaryFailure, "escapes"):
                        boundary.verify_archive(archive, destination)
                self.assertFalse(destination.exists())

    def test_package_identity_cannot_substitute_another_binary_or_archive(self):
        receipt = policy_receipt()
        boundary.require_emulator_package(receipt, inspect_binary=True)
        for key, value in (("sha256", "d" * 64), ("name", "emulator-latest.zip"), ("url", "https://example.invalid/tool"),
                           ("binary", "/another/tool"), ("binary_sha256", "0" * 64), ("bytes", 0)):
            with self.subTest(key=key):
                invalid = copy.deepcopy(receipt)
                invalid["emulator_package"][key] = value
                with self.assertRaises(boundary.BoundaryFailure):
                    boundary.require_emulator_package(invalid, inspect_binary=True)

    def test_tool_preparation_requires_policy_and_verifies_archive_before_extraction(self):
        self.save_policy()
        source_archive = Path(self.temporary.name) / "synthetic.zip"
        with zipfile.ZipFile(source_archive, "w") as zipped:
            zipped.writestr("emulator/emulator", "synthetic fixture; never executed")
        destination = Path(self.temporary.name) / boundary.EMULATOR_DIRECTORY
        binary = destination / "emulator/emulator"
        selected = context()
        selected["environment"].update(RUNNER_TEMP=self.temporary.name, PYAI_EMULATOR_BINARY=str(binary))
        receipt = policy_receipt()
        receipt.update(context=selected)
        receipt.pop("emulator_package")
        boundary.write_receipt(receipt)
        def fake_tools(arguments, **keywords):
            if arguments[0] == "/usr/bin/curl":
                Path(arguments[arguments.index("--output") + 1]).write_bytes(source_archive.read_bytes())
            elif arguments[0] == "/usr/bin/unzip":
                self.assertEqual(boundary.file_digest(source_archive), boundary.file_digest(Path(arguments[2])))
                binary.parent.mkdir()
                binary.write_text("synthetic fixture; never executed")
                binary.chmod(0o700)
            else:
                self.fail("Unexpected tool command")
            return subprocess.CompletedProcess(arguments, 0)
        with patch.object(boundary, "current_context", return_value=selected), \
                patch.object(boundary, "observe_identity", side_effect=self.observe_original), \
                patch.object(boundary, "EMULATOR_ARCHIVE_SHA256", boundary.file_digest(source_archive)), \
                patch.object(subprocess, "run", side_effect=fake_tools) as run:
            boundary.prepare_emulator()
            boundary.require_emulator_package(boundary.read_receipt(), inspect_binary=True)
        self.assertEqual(2, run.call_count)
        self.assertEqual("policy_checked", boundary.read_receipt()["status"])
        self.assertFalse((destination / boundary.EMULATOR_ARCHIVE_NAME).exists())
        self.assertTrue(binary.is_file())
        self.assertEqual([190, 120], [call.kwargs["timeout"] for call in run.call_args_list])

    def test_unapproved_or_hash_mismatched_preparation_never_extracts_or_launches(self):
        self.save_policy()
        receipt = boundary.read_receipt()
        receipt["status"] = "policy_failed"
        boundary.write_receipt(receipt)
        with patch.object(subprocess, "run") as run:
            with self.assertRaises(boundary.BoundaryFailure):
                boundary.prepare_emulator()
            run.assert_not_called()
        receipt = policy_receipt()
        receipt["context"]["environment"]["RUNNER_TEMP"] = self.temporary.name
        boundary.write_receipt(receipt)
        def wrong_download(arguments, **keywords):
            Path(arguments[arguments.index("--output") + 1]).write_bytes(b"wrong archive")
            return subprocess.CompletedProcess(arguments, 0)
        with patch.object(boundary, "current_context", return_value=receipt["context"]), \
                patch.object(boundary, "observe_identity", side_effect=self.observe_original), \
                patch.object(subprocess, "run", side_effect=wrong_download) as run:
            with self.assertRaisesRegex(boundary.BoundaryFailure, "hash differs"):
                boundary.prepare_emulator()
        self.assertEqual(1, run.call_count)
        self.assertEqual("prepare_failed", boundary.read_receipt()["status"])
        self.assertNotIn("inside", boundary.read_receipt())

    def test_missing_or_changed_derived_tool_path_never_executes_child(self):
        self.save_policy()
        for value in (None, "/unexpected/tool"):
            receipt = policy_receipt()
            receipt["status"] = "invoking"
            boundary.write_receipt(receipt)
            environment = dict(context()["environment"])
            if value is None:
                environment.pop("PYAI_EMULATOR_BINARY")
            else:
                environment["PYAI_EMULATOR_BINARY"] = value
            with self.subTest(value=value), patch.dict(os.environ, environment, clear=True), \
                    patch.object(boundary, "current_context") as selected, patch.object(os, "execve") as execute:
                with self.assertRaises(boundary.BoundaryFailure):
                    boundary.enter_child()
                selected.assert_not_called()
                execute.assert_not_called()

    def test_context_binds_real_checkout_tree_and_original_apk_before_any_sudo_command(self):
        root = Path(self.temporary.name)
        apk = root / "preview/build/outputs/apk/debug/preview-debug-unsigned.apk"
        apk.parent.mkdir(parents=True)
        apk.write_bytes(b"synthetic unsigned handoff")
        environment = {"GITHUB_SHA": "a" * 40, "PYAI_EXPECTED_APK_SHA256": boundary.file_digest(apk)}
        def git_result(arguments, **keywords):
            self.assertEqual("/usr/bin/git", arguments[0])
            output = ("a" * 40 if arguments[-1] == "HEAD" else "c" * 40) if arguments[1] == "rev-parse" else ""
            return subprocess.CompletedProcess(arguments, 0, output, "")
        with patch.object(boundary, "ROOT", root), patch.object(Path, "cwd", return_value=root), \
                patch.object(boundary, "selected_environment", return_value=environment), \
                patch.object(boundary, "fixed_child_command", return_value=context()["child_command"]), \
                patch.object(boundary.platform, "freedesktop_os_release", return_value={"ID": "ubuntu", "VERSION_ID": "24.04"}), \
                patch.object(boundary.platform, "system", return_value="Linux"), \
                patch.object(boundary.platform, "machine", return_value="x86_64"), \
                patch.object(subprocess, "run", side_effect=git_result) as run:
            observed = boundary.current_context()
            self.assertEqual("a" * 40, observed["source_commit"])
            self.assertEqual("c" * 40, observed["source_tree"])
            self.assertEqual(environment["PYAI_EXPECTED_APK_SHA256"], observed["unsigned_apk_sha256"])
            self.assertEqual(4, run.call_count)
            apk.write_bytes(b"changed handoff")
            with self.assertRaisesRegex(boundary.BoundaryFailure, "handoff"):
                boundary.current_context()

    def test_host_os_or_checkout_drift_fails_before_tool_preparation(self):
        for release, machine, system in (({"ID": "debian", "VERSION_ID": "24.04"}, "x86_64", "Linux"),
                                         ({"ID": "ubuntu", "VERSION_ID": "24.10"}, "x86_64", "Linux"),
                                         ({"ID": "ubuntu", "VERSION_ID": "24.04"}, "aarch64", "Linux"),
                                         ({"ID": "ubuntu", "VERSION_ID": "24.04"}, "x86_64", "Darwin")):
            with self.subTest(release=release, machine=machine, system=system), \
                    patch.object(boundary, "selected_environment", return_value={}), \
                    patch.object(boundary.platform, "freedesktop_os_release", return_value=release), \
                    patch.object(boundary.platform, "system", return_value=system), \
                    patch.object(boundary.platform, "machine", return_value=machine), patch.object(subprocess, "run") as run:
                with self.assertRaises(boundary.BoundaryFailure):
                    boundary.current_context()
                run.assert_not_called()


if __name__ == "__main__":
    unittest.main()
