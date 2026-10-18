"""Exercise the temporary ACL boundary with in-memory host/tool/process doubles.

These tests never run sudo, getfacl, setfacl, an SDK command or an emulator.
"""

import copy
from contextlib import ExitStack
import errno
import importlib.util
import io
import json
import os
from pathlib import Path
import signal
import stat
import subprocess
import sys
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch


ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("kvm_acl", ROOT / "scripts/android_kvm_acl.py")
boundary = importlib.util.module_from_spec(spec)
spec.loader.exec_module(boundary)
REAL_TOOL_IDENTITY = boundary.tool_identity
REAL_CURRENT_CONTEXT = boundary.current_context


def original_identity():
    return {"uid": 1001, "effective_uid": 1001, "gid": 1001, "effective_gid": 1001,
            "supplementary_groups": [4, 100, 118, 999, 1001], "read_write_access": False,
            "kvm_group": {"name": "kvm", "gid": 993},
            "device": {"path": "/dev/kvm", "mode": stat.S_IFCHR | 0o660, "uid": 0, "gid": 993,
                       "filesystem_device": 6, "inode": 440, "rdev": 2792}}


def context():
    return {"environment": {"PYAI_EMULATOR_MODE": "kvm", "GITHUB_SHA": "a" * 40,
                            "PYAI_EXPECTED_APK_SHA256": "b" * 64, "PYAI_KVM_PROCESS": boundary.MARKER,
                            "PYAI_EMULATOR_DISPLAY_PROFILE": "raster-720x1280-280",
                            "PYAI_EMULATOR_BINARY": str(Path(sys.executable).resolve())},
            "child_command": ["/usr/bin/python3.12", "-I", str(ROOT / "scripts/android_kvm_acl.py"), "--child"],
            "source_commit": "a" * 40, "source_tree": "c" * 40}


def trusted_tools():
    return {path: {"uid": 0, "gid": 0, "mode": stat.S_IFREG | (0o4755 if path == boundary.SUDO else 0o755),
                   "sha256": "d" * 64}
            for path in (boundary.SUDO, boundary.GETFACL, boundary.SETFACL)}


def successful_result(arguments, stdout=""):
    return subprocess.CompletedProcess(arguments, 0, stdout, "")


class KvmAclBoundaryTest(unittest.TestCase):
    def setUp(self):
        self.stack = ExitStack()
        self.addCleanup(self.stack.close)
        self.temporary = self.stack.enter_context(tempfile.TemporaryDirectory())
        self.report = Path(self.temporary) / "acl/receipt.json"
        self.stack.enter_context(patch.object(boundary, "REPORT", self.report))
        self.stack.enter_context(patch.object(boundary, "current_context", side_effect=context))
        self.stack.enter_context(patch.object(boundary, "tool_identity", side_effect=trusted_tools))
        self.stack.enter_context(patch.object(boundary.shared, "observe_identity", side_effect=self.observe_identity))
        self.run = self.stack.enter_context(patch.object(boundary.subprocess, "run", side_effect=self.fake_run))
        self.stack.enter_context(patch.object(boundary.lifecycle, "observe", side_effect=self.fake_lifecycle))
        self.identity = original_identity()
        self.acl = boundary.BASE_ACL
        self.events = []
        self.responses = {}
        self.lifecycle_overrides = {}

    def fake_lifecycle(self, arguments, environment, expected_identity, observed):
        failure = None
        try:
            result = self.fake_run(arguments, env=environment)
        except subprocess.TimeoutExpired as error:
            failure = error
            result = subprocess.CompletedProcess(arguments, -int(signal.SIGTERM), "partial", "")
        status = result.returncode << 8 if result.returncode >= 0 else -result.returncode
        observed.update(scope=boundary.lifecycle.SCOPE, arguments=arguments,
                        supervisor_identity=copy.deepcopy(expected_identity), supervisor_pid=900,
                        supervisor_wait_status=0, subreaper={"set_result": 0, "get_result": 0, "enabled": 1},
                        initial_wait_errno=errno.ECHILD, final_wait_errno=errno.ECHILD,
                        quiescent=True, command_started=True, command_pid=901,
                        reaped=[{"pid": 901, "wait_status": status}], exit_code=result.returncode,
                        stdout=result.stdout, stderr=result.stderr, output_truncated=False)
        observed.update(self.lifecycle_overrides.get(tuple(arguments), {}))
        if failure is not None:
            observed["timeout_seconds"] = failure.timeout
            raise failure

    def successful_child(self, receipt):
        with patch.dict(os.environ, context()["environment"], clear=True), patch.object(os, "execve") as execute:
            boundary.enter_child()
        execute.assert_called_once_with("/usr/bin/python3.12",
                                        ["/usr/bin/python3.12", "-I", str(ROOT / "scripts/android_emulator_smoke.py")],
                                        context()["environment"])
        return 0

    def run_successfully(self):
        self.prepare_receipt()
        with patch.object(boundary, "wait_for_child", side_effect=self.successful_child):
            boundary.run_harness()
        return boundary.read_receipt()

    def observe_identity(self, observed):
        observed.update(copy.deepcopy(self.identity))
        observed["read_write_access"] = self.acl == boundary.granted_acl(1001)

    def fake_run(self, arguments, **options):
        self.events.append((copy.deepcopy(arguments), copy.deepcopy(options)))
        response = self.responses.get(tuple(arguments))
        if response is not None:
            if callable(response):
                return response(arguments)
            if isinstance(response, BaseException):
                raise response
            return response
        if arguments in ([boundary.GETFACL, "--version"], [boundary.SETFACL, "--version"]):
            return successful_result(arguments, Path(arguments[0]).name + " 2.3.2\n")
        if arguments == boundary.ACL_ARGUMENTS:
            return successful_result(arguments, "\n".join(self.acl) + "\n\n")
        for restore in (False, True):
            if arguments == boundary.acl_command(1001, restore=restore, listing=True):
                return successful_result(arguments, "exact command allowed\n")
            if arguments == boundary.acl_command(1001, restore=restore):
                self.acl = boundary.BASE_ACL if restore else boundary.granted_acl(1001)
                return successful_result(arguments)
        raise AssertionError("Unexpected host command in an isolated test: " + repr(arguments))

    def prepare_receipt(self):
        boundary.check_policy()
        receipt = boundary.read_receipt()
        binary = context()["environment"]["PYAI_EMULATOR_BINARY"]
        # An ordinary read-only local Python file supplies a digest; it is never executed here.
        receipt["emulator_package"] = {"name": boundary.shared.EMULATOR_ARCHIVE_NAME,
                                       "url": boundary.shared.EMULATOR_ARCHIVE_URL,
                                       "sha256": boundary.shared.EMULATOR_ARCHIVE_SHA256, "bytes": 1,
                                       "binary": binary,
                                       "binary_sha256": boundary.shared.file_digest(Path(binary))}
        boundary.write_receipt(receipt)
        self.events.clear()
        return receipt

    def mutation_calls(self):
        return [arguments for arguments, _ in self.events
                if arguments and arguments[0] == boundary.SUDO and "--list" not in arguments]

    def test_both_exact_fixed_tool_policies_precede_any_provisioning(self):
        boundary.check_policy()
        receipt = boundary.read_receipt()
        self.assertEqual("policy_checked", receipt["status"])
        self.assertEqual([boundary.acl_command(1001, restore=False, listing=True),
                          boundary.acl_command(1001, restore=True, listing=True)],
                         [arguments for arguments, _ in self.events if arguments[0] == boundary.SUDO])
        self.assertEqual([], self.mutation_calls())
        self.assertEqual(boundary.BASE_ACL, boundary.acl_entries(receipt["before"]["acl"]))
        self.assertNotIn("grant_attempted", receipt)
        for arguments, options in self.events:
            self.assertEqual(boundary.COMMAND_ENVIRONMENT, options["env"])
            self.assertNotIn("POSIXLY_CORRECT", options["env"])
            self.assertNotIn("--group", " ".join(arguments))
            self.assertNotIn("--preserve-env", " ".join(arguments))
        with self.assertRaises(boundary.BoundaryFailure):
            boundary.complete_boundary(receipt, context())

    def test_denial_of_either_exact_policy_cannot_change_access(self):
        for restore in (False, True):
            with self.subTest(restore=restore), tempfile.TemporaryDirectory() as temporary, \
                    patch.object(boundary, "REPORT", Path(temporary) / "acl/receipt.json"):
                self.responses = {tuple(boundary.acl_command(1001, restore=restore, listing=True)):
                                  subprocess.CompletedProcess([], 1, "", "policy denied")}
                self.events.clear()
                with self.assertRaisesRegex(boundary.BoundaryFailure, "no fallback"):
                    boundary.check_policy()
                self.assertEqual("policy_failed", boundary.read_receipt()["status"])
                self.assertEqual([], self.mutation_calls())
                self.assertEqual(boundary.BASE_ACL, self.acl)

    def test_policy_timeout_retains_original_failure_and_never_grants(self):
        failure = subprocess.TimeoutExpired("sudo", 15, output=b"partial policy", stderr=b"pending")
        self.responses[tuple(boundary.acl_command(1001, restore=True, listing=True))] = failure
        with self.assertRaises(subprocess.TimeoutExpired) as raised:
            boundary.check_policy()
        self.assertIs(failure, raised.exception)
        receipt = boundary.read_receipt()
        self.assertEqual("policy_failed", receipt["status"])
        self.assertEqual("partial policy", receipt["policy"]["restore"]["stdout"])
        self.assertEqual("pending", receipt["policy"]["restore"]["stderr"])
        self.assertEqual(15, receipt["policy"]["restore"]["timeout_seconds"])
        self.assertEqual([], self.mutation_calls())

    def test_warnings_truncation_and_noninteger_success_cannot_authorize_a_grant(self):
        for result in (subprocess.CompletedProcess([], 0, "allowed", "warning"),
                       subprocess.CompletedProcess([], 0, "x" * 8193, ""),
                       subprocess.CompletedProcess([], False, "allowed", "")):
            with self.subTest(result=result), tempfile.TemporaryDirectory() as temporary, \
                    patch.object(boundary, "REPORT", Path(temporary) / "acl/receipt.json"):
                self.responses = {tuple(boundary.acl_command(1001, restore=False, listing=True)): result}
                self.events.clear()
                with self.assertRaises(boundary.BoundaryFailure):
                    boundary.check_policy()
                self.assertEqual([], self.mutation_calls())
                self.assertEqual("policy_failed", boundary.read_receipt()["status"])

    def test_existing_named_or_mask_acl_is_rejected_before_policy_or_mutation(self):
        for acl in (boundary.granted_acl(1001), boundary.granted_acl(2222),
                    ("user::rw-", "group::rw-", "mask::rw-", "other::---"),
                    ("user::rw-", "group::rw-", "other::r--")):
            with self.subTest(acl=acl), tempfile.TemporaryDirectory() as temporary, \
                    patch.object(boundary, "REPORT", Path(temporary) / "acl/receipt.json"):
                self.acl = acl
                self.events.clear()
                with self.assertRaises(boundary.BoundaryFailure):
                    boundary.check_policy()
                self.assertFalse(any(arguments[0] == boundary.SUDO for arguments, _ in self.events))
                self.assertEqual(acl, self.acl)

    def test_incomplete_or_decorated_acl_read_is_not_original_base_evidence(self):
        for result in (subprocess.CompletedProcess([], 0, "user::rw-\ngroup::rw-\nother::---\n", "warning"),
                       subprocess.CompletedProcess([], 0, "# file: /dev/kvm\nuser::rw-\ngroup::rw-\nother::---\n", ""),
                       subprocess.CompletedProcess([], 1, "user::rw-\ngroup::rw-\nother::---\n", "")):
            with self.subTest(result=result), tempfile.TemporaryDirectory() as temporary, \
                    patch.object(boundary, "REPORT", Path(temporary) / "acl/receipt.json"):
                self.responses = {tuple(boundary.ACL_ARGUMENTS): result}
                self.events.clear()
                with self.assertRaises(boundary.BoundaryFailure):
                    boundary.check_policy()
                self.assertFalse(any(arguments[0] == boundary.SUDO for arguments, _ in self.events))

    def test_original_receipt_requires_complete_nonroot_device_identity(self):
        receipt = self.prepare_receipt()
        for section, key, value in ((None, "effective_uid", 0), (None, "uid", True),
                                    (None, "supplementary_groups", [1001, 4]),
                                    ("device", "uid", False), ("device", "gid", 0),
                                    ("device", "inode", None), ("device", "rdev", True),
                                    ("device", "mode", stat.S_IFREG | 0o660),
                                    ("device", "mode", stat.S_IFCHR | 0o666)):
            changed = copy.deepcopy(receipt["before"])
            target = changed if section is None else changed[section]
            target[key] = value
            with self.subTest(section=section, key=key, value=value):
                with self.assertRaises(boundary.BoundaryFailure):
                    boundary.require_original(changed)

    def test_command_generation_rejects_root_boolean_or_command_text(self):
        for uid in (0, -1, True, "1001", "1001,other::rwx", None):
            with self.subTest(uid=uid), self.assertRaises(boundary.BoundaryFailure):
                boundary.acl_command(uid, restore=False)
        self.assertEqual(["/usr/bin/sudo", "--non-interactive", "--user=#0", "--", "/usr/bin/setfacl",
                          "--no-mask", "--modify=user:1001:rw-,mask::rw-", "--", "/dev/kvm"],
                         boundary.acl_command(1001, restore=False))
        self.assertEqual("--set=user::rw-,group::rw-,other::---",
                         boundary.acl_command(1001, restore=True)[-3])

    def test_trusted_tool_receipt_and_pinned_versions_are_required(self):
        receipt = self.prepare_receipt()
        for section, key, value in (("tools", boundary.SETFACL, None),
                                    ("tool_versions", "setfacl", {})):
            changed = copy.deepcopy(receipt)
            changed[section][key] = value
            with self.subTest(section=section, key=key), self.assertRaises(boundary.BoundaryFailure):
                boundary.require_tools(changed)
        for field, value in (("uid", 1001), ("mode", stat.S_IFREG | 0o777), ("sha256", "e" * 63)):
            changed = copy.deepcopy(receipt)
            changed["tools"][boundary.SETFACL][field] = value
            with self.subTest(field=field), self.assertRaises(boundary.BoundaryFailure):
                boundary.require_tools(changed)
        changed = copy.deepcopy(receipt)
        changed["tool_versions"]["getfacl"]["stdout"] = "getfacl 2.3.3\n"
        with self.assertRaises(boundary.BoundaryFailure):
            boundary.require_tools(changed)

    def test_changed_source_tool_or_device_after_policy_cannot_prepare_or_grant(self):
        receipt = self.prepare_receipt()
        for change in ("source", "tool", "device", "acl"):
            with self.subTest(change=change), ExitStack() as stack:
                self.identity = original_identity()
                self.acl = boundary.BASE_ACL
                if change == "source":
                    stack.enter_context(patch.object(boundary, "current_context", return_value=dict(context(), source_commit="f" * 40)))
                elif change == "tool":
                    tools = trusted_tools()
                    tools[boundary.SETFACL]["sha256"] = "e" * 64
                    stack.enter_context(patch.object(boundary, "tool_identity", return_value=tools))
                elif change == "device":
                    self.identity["device"]["inode"] += 1
                else:
                    self.acl = boundary.granted_acl(2222)
                self.events.clear()
                with self.assertRaises(boundary.BoundaryFailure):
                    boundary.require_pending(receipt)
                self.assertEqual([], self.mutation_calls())

    def test_same_mode_without_named_entry_is_not_grant_or_acl_capability_evidence(self):
        receipt = self.prepare_receipt()
        observed = copy.deepcopy(receipt["before"])
        observed["read_write_access"] = True
        with self.assertRaises(boundary.BoundaryFailure):
            boundary.require_granted(observed, receipt["before"])
        self.acl = boundary.granted_acl(1001)
        boundary.observe_state(observed)
        boundary.require_granted(observed, receipt["before"])
        self.assertEqual(receipt["before"]["device"]["mode"], observed["device"]["mode"])
        self.assertNotEqual(receipt["before"]["acl"], observed["acl"])

    def test_grant_cannot_change_real_effective_uid_gid_or_supplementary_groups(self):
        receipt = self.prepare_receipt()
        self.acl = boundary.granted_acl(1001)
        observed = {}
        boundary.observe_state(observed)
        for key, value in (("uid", 0), ("effective_uid", 0), ("gid", 993),
                           ("effective_gid", 993), ("supplementary_groups", [993, 1001])):
            changed = dict(observed, **{key: value})
            with self.subTest(key=key), self.assertRaises(boundary.BoundaryFailure):
                boundary.require_granted(changed, receipt["before"])

    def test_atomic_receipt_update_keeps_one_complete_json_document(self):
        receipt = self.prepare_receipt()
        receipt["status"] = "test checkpoint"
        boundary.write_receipt(receipt)
        self.assertEqual(receipt, json.loads(self.report.read_text()))
        self.assertEqual([self.report], list(self.report.parent.iterdir()))

    def test_linked_receipt_is_never_replaced(self):
        self.report.parent.mkdir()
        target = Path(self.temporary) / "unrelated.json"
        target.write_text("unrelated")
        self.report.symlink_to(target)
        with self.assertRaises(boundary.BoundaryFailure):
            boundary.write_receipt({"status": "passed"})
        self.assertEqual("unrelated", target.read_text())

    def test_success_grants_one_named_uid_then_restores_exact_original_acl(self):
        receipt = self.run_successfully()
        boundary.complete_boundary(receipt, context())
        self.assertEqual("passed", receipt["status"])
        self.assertEqual([boundary.acl_command(1001, restore=False), boundary.acl_command(1001, restore=True)],
                         self.mutation_calls())
        self.assertEqual(boundary.BASE_ACL, self.acl)
        self.assertEqual(receipt["before"], receipt["cleanup"]["after"])
        self.assertEqual("restored", receipt["cleanup"]["status"])
        for key in ("granted", "inside"):
            self.assertEqual(boundary.process_credentials(receipt["before"]),
                             boundary.process_credentials(receipt[key]))
            self.assertEqual(receipt["before"]["device"], receipt[key]["device"])
            self.assertTrue(receipt[key]["read_write_access"])
        for key in ("grant",):
            self.assertTrue(boundary.lifecycle.quiescence_passes(receipt[key],
                            boundary.process_credentials(receipt["before"])))

    def test_failed_grant_that_already_changed_acl_is_restored_after_proven_completion(self):
        self.prepare_receipt()
        def changed_then_failed(arguments):
            self.acl = boundary.granted_acl(1001)
            return subprocess.CompletedProcess(arguments, 1, "", "update failed after possible change")
        self.responses[tuple(boundary.acl_command(1001, restore=False))] = changed_then_failed
        with patch.object(boundary, "wait_for_child") as child, self.assertRaises(boundary.BoundaryFailure):
            boundary.run_harness()
        child.assert_not_called()
        receipt = boundary.read_receipt()
        self.assertEqual("failed", receipt["status"])
        self.assertEqual(1, receipt["grant"]["exit_code"])
        self.assertEqual("restored", receipt["cleanup"]["status"])
        self.assertEqual(receipt["before"], receipt["cleanup"]["after"])
        self.assertEqual(boundary.BASE_ACL, self.acl)

    def test_timed_out_partial_grant_restores_only_after_subtree_is_proved_reaped(self):
        self.prepare_receipt()
        failure = subprocess.TimeoutExpired("owned sudo", 10)
        def changed_then_timed_out(arguments):
            self.acl = boundary.granted_acl(1001)
            raise failure
        self.responses[tuple(boundary.acl_command(1001, restore=False))] = changed_then_timed_out
        with patch.object(boundary, "wait_for_child") as child, self.assertRaises(subprocess.TimeoutExpired) as raised:
            boundary.run_harness()
        self.assertIs(failure, raised.exception)
        child.assert_not_called()
        receipt = boundary.read_receipt()
        self.assertEqual("failed", receipt["status"])
        self.assertEqual(10, receipt["grant"]["timeout_seconds"])
        self.assertTrue(receipt["grant"]["quiescent"])
        self.assertEqual("restored", receipt["cleanup"]["status"])
        self.assertEqual(boundary.BASE_ACL, self.acl)

    def test_live_or_unproved_grant_subtree_blocks_all_later_device_mutation(self):
        self.prepare_receipt()
        self.lifecycle_overrides[tuple(boundary.acl_command(1001, restore=False))] = {
            "quiescent": False, "final_wait_errno": None, "error": "descendant may still run"}
        with patch.object(boundary, "wait_for_child") as child, self.assertRaises(boundary.BoundaryFailure):
            boundary.run_harness()
        child.assert_not_called()
        self.assertEqual([boundary.acl_command(1001, restore=False)], self.mutation_calls())
        self.assertEqual(boundary.granted_acl(1001), self.acl)
        self.assertEqual("failed", boundary.read_receipt()["cleanup"]["status"])
        with self.assertRaisesRegex(boundary.BoundaryFailure, "grant subtree"):
            boundary.cleanup_after_step()
        self.assertEqual([boundary.acl_command(1001, restore=False)], self.mutation_calls())

    def test_failed_restore_cannot_make_success_even_if_base_acl_was_already_written(self):
        self.prepare_receipt()
        def changed_then_failed(arguments):
            self.acl = boundary.BASE_ACL
            return subprocess.CompletedProcess(arguments, 1, "", "restoration warning")
        self.responses[tuple(boundary.acl_command(1001, restore=True))] = changed_then_failed
        with patch.object(boundary, "wait_for_child", side_effect=self.successful_child), \
                self.assertRaises(boundary.BoundaryFailure):
            boundary.run_harness()
        receipt = boundary.read_receipt()
        self.assertEqual("failed", receipt["status"])
        self.assertIn("cleanup_error", receipt)
        self.assertEqual(boundary.BASE_ACL, self.acl)
        with self.assertRaises(boundary.BoundaryFailure):
            boundary.complete_boundary(receipt, context())

    def test_interrupted_child_evidence_and_original_exception_survive_failed_restore(self):
        self.prepare_receipt()
        failure = RuntimeError("original harness failure")
        def failed_child(receipt):
            self.successful_child(receipt)
            raise failure
        self.responses[tuple(boundary.acl_command(1001, restore=True))] = subprocess.CompletedProcess([], 1, "", "denied")
        with patch.object(boundary, "wait_for_child", side_effect=failed_child), \
                self.assertRaises(RuntimeError) as raised:
            boundary.run_harness()
        self.assertIs(failure, raised.exception)
        receipt = boundary.read_receipt()
        self.assertEqual("original harness failure", receipt["error"])
        self.assertIn("cleanup_error", receipt)
        self.assertIn("inside", receipt)
        self.assertEqual("failed", receipt["status"])

    def test_replaced_device_or_foreign_acl_is_never_overwritten_by_cleanup(self):
        for change in ("device", "acl"):
            with self.subTest(change=change), tempfile.TemporaryDirectory() as temporary, \
                    patch.object(boundary, "REPORT", Path(temporary) / "acl/receipt.json"):
                self.identity = original_identity()
                self.acl = boundary.BASE_ACL
                self.prepare_receipt()
                def altered_after_child(receipt):
                    self.successful_child(receipt)
                    if change == "device":
                        self.identity["device"]["inode"] += 1
                    else:
                        self.acl = boundary.granted_acl(2222)
                    return 0
                with patch.object(boundary, "wait_for_child", side_effect=altered_after_child), \
                        self.assertRaises(boundary.BoundaryFailure):
                    boundary.run_harness()
                self.assertEqual([boundary.acl_command(1001, restore=False)], self.mutation_calls())
                self.assertEqual("failed", boundary.read_receipt()["status"])

    def test_always_cleanup_restores_interrupted_grant_but_does_not_rescue_its_run(self):
        receipt = self.prepare_receipt()
        receipt.update(status="grant_attempted", grant_attempted=True, grant={})
        boundary.write_receipt(receipt)
        boundary.observe_acl_command(receipt, receipt["grant"], restore=False)
        boundary.cleanup_after_step()
        receipt = boundary.read_receipt()
        self.assertEqual(boundary.BASE_ACL, self.acl)
        self.assertEqual("restored", receipt["cleanup"]["status"])
        self.assertEqual("failed", receipt["status"])
        self.assertIn("did not finish", receipt["error"])

    def test_always_cleanup_only_observes_an_already_restored_boundary(self):
        original = self.run_successfully()
        self.events.clear()
        boundary.cleanup_after_step()
        self.assertEqual([], self.mutation_calls())
        self.assertEqual(original, boundary.read_receipt())
        self.assertEqual([boundary.ACL_ARGUMENTS], [arguments for arguments, _ in self.events])

    def test_always_cleanup_without_a_grant_has_no_privileged_command(self):
        self.prepare_receipt()
        self.events.clear()
        boundary.cleanup_after_step()
        self.assertEqual([], self.events)

    def test_always_cleanup_refuses_a_still_running_previous_restore(self):
        self.prepare_receipt()
        self.lifecycle_overrides[tuple(boundary.acl_command(1001, restore=True))] = {
            "quiescent": False, "final_wait_errno": None, "error": "restore descendant may still run"}
        with patch.object(boundary, "wait_for_child", side_effect=self.successful_child), \
                self.assertRaises(boundary.BoundaryFailure):
            boundary.run_harness()
        before = list(self.mutation_calls())
        with self.assertRaisesRegex(boundary.BoundaryFailure, "previous restoration subtree"):
            boundary.cleanup_after_step()
        self.assertEqual(before, self.mutation_calls())
        self.assertEqual("failed", boundary.read_receipt()["status"])

    def test_failed_restore_recovery_keeps_original_failed_status_and_attempt(self):
        self.prepare_receipt()
        self.responses[tuple(boundary.acl_command(1001, restore=True))] = subprocess.CompletedProcess([], 1, "", "denied")
        with patch.object(boundary, "wait_for_child", side_effect=self.successful_child), \
                self.assertRaises(boundary.BoundaryFailure):
            boundary.run_harness()
        del self.responses[tuple(boundary.acl_command(1001, restore=True))]
        boundary.cleanup_after_step()
        receipt = boundary.read_receipt()
        self.assertEqual("failed", receipt["status"])
        self.assertEqual("restored", receipt["cleanup"]["status"])
        self.assertEqual(1, len(receipt["cleanup_attempts"]))
        self.assertEqual("failed", receipt["cleanup_attempts"][0]["status"])
        self.assertIn("cleanup_error", receipt)
        with self.assertRaises(boundary.BoundaryFailure):
            boundary.complete_boundary(receipt, context())

    def test_checkpoint_failure_prevents_any_acl_command(self):
        self.prepare_receipt()
        write = boundary.write_receipt
        failure = OSError("checkpoint not durable")
        def fail_before_grant(receipt):
            if receipt.get("status") == "grant_attempted":
                raise failure
            write(receipt)
        with patch.object(boundary, "write_receipt", side_effect=fail_before_grant), \
                self.assertRaises(OSError) as raised:
            boundary.run_harness()
        self.assertIs(failure, raised.exception)
        self.assertEqual([], self.mutation_calls())
        self.assertEqual("failed", boundary.read_receipt()["status"])

    def test_final_context_failure_is_persisted_after_successful_restoration(self):
        self.prepare_receipt()
        calls = [context(), context(), dict(context(), source_commit="f" * 40)]
        with patch.object(boundary, "current_context", side_effect=calls), \
                patch.object(boundary, "wait_for_child", side_effect=self.successful_child), \
                self.assertRaises(boundary.BoundaryFailure):
            boundary.run_harness()
        receipt = boundary.read_receipt()
        self.assertEqual("failed", receipt["status"])
        self.assertEqual("restored", receipt["cleanup"]["status"])
        self.assertIn("error", receipt)

    def test_child_requires_actual_received_scope_marker_and_same_acl_identity(self):
        receipt = self.prepare_receipt()
        self.acl = boundary.granted_acl(1001)
        receipt["status"] = "invoking"
        boundary.write_receipt(receipt)
        with patch.dict(os.environ, dict(context()["environment"], PYAI_KVM_PROCESS="wrong-boundary"), clear=True), \
                patch.object(os, "execve") as execute, self.assertRaises(boundary.BoundaryFailure):
            boundary.enter_child()
        execute.assert_not_called()
        self.assertEqual("inside_failed", boundary.read_receipt()["status"])

    def test_complete_receipt_rejects_missing_process_proof_or_masked_remaining_grant(self):
        receipt = self.run_successfully()
        for mutation in ("grant_proof", "restore_proof", "remaining_acl", "warning", "old_boundary", "prior_failure"):
            changed = copy.deepcopy(receipt)
            if mutation == "grant_proof":
                changed["grant"].pop("final_wait_errno")
            elif mutation == "restore_proof":
                changed["cleanup"]["command"]["quiescent"] = False
            elif mutation == "remaining_acl":
                changed["cleanup"]["after"]["acl"] = copy.deepcopy(changed["inside"]["acl"])
            elif mutation == "warning":
                changed["grant"]["stderr"] = "diagnostic despite exit zero"
            elif mutation == "old_boundary":
                changed["scope"] = boundary.shared.MARKER
            else:
                changed["cleanup_attempts"] = [{"status": "failed"}]
            with self.subTest(mutation=mutation), self.assertRaises(boundary.BoundaryFailure):
                boundary.complete_boundary(changed, context())

    def test_receipt_rejects_self_consistent_missing_or_wrong_display_profile(self):
        receipt = self.run_successfully()
        boundary.complete_boundary(receipt, context())
        for profile in (None, "", "pixel-2", "automatic", "raster-720x1280-280 "):
            changed = copy.deepcopy(receipt)
            selected = copy.deepcopy(context())
            for environment in (changed["context"]["environment"], selected["environment"]):
                if profile is None:
                    environment.pop("PYAI_EMULATOR_DISPLAY_PROFILE")
                else:
                    environment["PYAI_EMULATOR_DISPLAY_PROFILE"] = profile
            with self.subTest(profile=profile), self.assertRaises(boundary.BoundaryFailure):
                boundary.complete_boundary(changed, selected)

    def test_child_revalidates_the_actual_received_display_profile_before_device_or_exec(self):
        receipt = self.prepare_receipt()
        self.acl = boundary.granted_acl(1001)
        shared_context = copy.deepcopy(context())
        shared_context["environment"].pop("PYAI_EMULATOR_DISPLAY_PROFILE")
        for profile in (None, "pixel-2", "raster-720x1280-280 "):
            pending = copy.deepcopy(receipt)
            pending["status"] = "invoking"
            boundary.write_receipt(pending)
            environment = copy.deepcopy(context()["environment"])
            if profile is None:
                environment.pop("PYAI_EMULATOR_DISPLAY_PROFILE")
            else:
                environment["PYAI_EMULATOR_DISPLAY_PROFILE"] = profile
            with self.subTest(profile=profile), patch.dict(os.environ, environment, clear=True), \
                    patch.object(boundary, "current_context", side_effect=REAL_CURRENT_CONTEXT), \
                    patch.object(boundary.shared, "current_context", return_value=copy.deepcopy(shared_context)), \
                    patch.object(boundary, "observe_state") as observe, patch.object(os, "execve") as execute, \
                    self.assertRaises(boundary.BoundaryFailure):
                boundary.enter_child()
            observe.assert_not_called()
            execute.assert_not_called()
            retained = boundary.read_receipt()
            self.assertEqual("inside_failed", retained["status"])
            self.assertIn("startup display profile", retained["inside_error"])

    def test_actual_system_tool_validation_rejects_links_writable_parents_and_nonroot_owner(self):
        for defect in ("linked_tool", "writable_tool", "owner", "linked_parent", "writable_parent"):
            def metadata(path):
                mode, uid = (stat.S_IFREG | 0o755, 0) if str(path) in trusted_tools() else (stat.S_IFDIR | 0o755, 0)
                if str(path) == boundary.SETFACL:
                    if defect == "linked_tool":
                        mode = stat.S_IFLNK | 0o777
                    elif defect == "writable_tool":
                        mode = stat.S_IFREG | 0o777
                    elif defect == "owner":
                        uid = 1001
                if str(path) == "/usr/bin":
                    if defect == "linked_parent":
                        mode = stat.S_IFLNK | 0o777
                    elif defect == "writable_parent":
                        mode = stat.S_IFDIR | 0o777
                return SimpleNamespace(st_mode=mode, st_uid=uid, st_gid=0)
            with self.subTest(defect=defect), patch.object(Path, "lstat", metadata), \
                    patch.object(os, "access", return_value=True), \
                    patch.object(boundary.shared, "file_digest", return_value="d" * 64), \
                    self.assertRaises(boundary.BoundaryFailure):
                REAL_TOOL_IDENTITY()

    def test_workload_starts_in_its_own_session_with_only_selected_environment(self):
        process = SimpleNamespace(wait=lambda: 0)
        with patch.object(boundary.subprocess, "Popen", return_value=process) as popen:
            self.assertEqual(0, boundary.wait_for_child({"context": context()}))
        popen.assert_called_once_with(context()["child_command"], env=context()["environment"], start_new_session=True)

    def test_workload_interruption_signals_only_its_new_owned_group_and_preserves_error(self):
        failure = KeyboardInterrupt("original interruption")
        for group in (101, 102):
            process = SimpleNamespace(pid=101, poll=lambda: None)
            with self.subTest(group=group), patch.object(boundary.subprocess, "Popen", return_value=process), \
                    patch.object(process, "wait", create=True, side_effect=[failure, 0]), \
                    patch.object(os, "getpgid", return_value=group), patch.object(os, "killpg") as kill, \
                    self.assertRaises(KeyboardInterrupt) as raised:
                boundary.wait_for_child({"context": context()})
            self.assertIs(failure, raised.exception)
            if group == process.pid:
                kill.assert_called_once_with(101, signal.SIGINT)
            else:
                kill.assert_not_called()

    def test_workload_forced_interruption_is_bounded_and_does_not_replace_original_failure(self):
        failure = RuntimeError("original interruption")
        process = SimpleNamespace(pid=101, poll=lambda: None)
        with patch.object(boundary.subprocess, "Popen", return_value=process), \
                patch.object(process, "wait", create=True, side_effect=[failure, subprocess.TimeoutExpired("child", 5), 0]) as wait, \
                patch.object(os, "getpgid", return_value=101), patch.object(os, "killpg") as kill, \
                self.assertRaises(RuntimeError) as raised:
            boundary.wait_for_child({"context": context()})
        self.assertIs(failure, raised.exception)
        self.assertEqual([(101, signal.SIGINT), (101, signal.SIGKILL)], [call.args for call in kill.call_args_list])
        self.assertEqual([{}, {"timeout": 5}, {"timeout": 2}], [call.kwargs for call in wait.call_args_list])

    def test_persistence_error_cannot_replace_an_existing_harness_failure(self):
        failure = RuntimeError("original installed failure")
        with patch.object(boundary, "write_receipt", side_effect=OSError("write failed")), \
                patch("sys.stderr", new_callable=io.StringIO) as output:
            actual = boundary.persist_preserving_error({"status": "failed"}, failure)
        self.assertIs(failure, actual)
        self.assertIn("write failed", output.getvalue())

    def test_workflow_always_restores_before_accepting_both_exact_receipts(self):
        workflow = (ROOT / ".github/workflows/core.yml").read_text()
        installed = workflow.split("  android-installed:\n", 1)[1]
        steps = installed.split("      - ")[1:]
        def step_containing(text):
            matches = [(index, step) for index, step in enumerate(steps) if text in step]
            self.assertEqual(1, len(matches), text)
            return matches[0]
        policy, _ = step_containing("scripts/android_kvm_acl.py --check-policy")
        prepare, _ = step_containing("scripts/android_kvm_acl.py --prepare-emulator")
        run, _ = step_containing("scripts/android_kvm_acl.py --run")
        restore, cleanup = step_containing("scripts/android_kvm_acl.py --restore")
        accept, gate = step_containing("complete_boundary(boundary, context)")
        upload, artifact = step_containing("name: android-installed-kvm-")
        self.assertEqual(sorted([policy, prepare, run, restore, accept, upload]),
                         [policy, prepare, run, restore, accept, upload])
        self.assertIn("if: always()", cleanup)
        self.assertIn('complete_receipt(receipt, os.environ["GITHUB_SHA"], expected_mode="kvm",', gate)
        self.assertIn('expected_display_profile=context["environment"]["PYAI_EMULATOR_DISPLAY_PROFILE"]', gate)
        self.assertIn('PYAI_EMULATOR_DISPLAY_PROFILE: raster-720x1280-280', installed)
        self.assertIn('receipt["source_tree"] == context["source_tree"]', gate)
        self.assertIn('receipt["unsigned_apk_sha256"] == context["unsigned_apk_sha256"]', gate)
        self.assertIn('receipt["emulator_binary_sha256"] == boundary["emulator_package"]["binary_sha256"]', gate)
        self.assertIn("preview/build/reports/kvm-acl/", artifact)
        self.assertIn("preview/build/reports/emulator-smoke/", artifact)
        self.assertNotIn("scripts/android_kvm_process.py --", installed)


class KvmAclProfileContextTest(unittest.TestCase):
    def test_context_preserves_only_the_exact_explicit_profile_without_legacy_defaulting(self):
        shared_context = copy.deepcopy(context())
        shared_context["environment"].pop("PYAI_EMULATOR_DISPLAY_PROFILE")
        shared_context["environment"]["PYAI_KVM_PROCESS"] = boundary.shared.MARKER
        with patch.object(boundary.shared, "current_context", side_effect=lambda: copy.deepcopy(shared_context)), \
                patch.dict(os.environ, {"PYAI_EMULATOR_DISPLAY_PROFILE": "raster-720x1280-280",
                                        "PYTHONPATH": "/untrusted", "POSIXLY_CORRECT": "1"}, clear=True):
            selected = REAL_CURRENT_CONTEXT()
            self.assertEqual("raster-720x1280-280", selected["environment"]["PYAI_EMULATOR_DISPLAY_PROFILE"])
            self.assertEqual(boundary.MARKER, selected["environment"]["PYAI_KVM_PROCESS"])
            self.assertNotIn("PYTHONPATH", selected["environment"])
            self.assertNotIn("POSIXLY_CORRECT", selected["environment"])
            self.assertNotIn("PYAI_EMULATOR_DISPLAY_PROFILE", shared_context["environment"])
            for profile in (None, "", "pixel-2", "automatic", "raster-720x1280-280 "):
                supplied = {} if profile is None else {"PYAI_EMULATOR_DISPLAY_PROFILE": profile}
                with self.subTest(profile=profile), patch.dict(os.environ, supplied, clear=True), \
                        self.assertRaises(boundary.BoundaryFailure):
                    REAL_CURRENT_CONTEXT()


if __name__ == "__main__":
    unittest.main()
