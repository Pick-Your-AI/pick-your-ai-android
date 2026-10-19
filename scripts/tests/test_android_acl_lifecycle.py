"""Test command-subtree ownership with harmless local processes and syscall doubles.

Actual lifecycle cases run only static Python children inside a disposable test
directory. They do not invoke the production non-root ACL entry, sudo, setfacl,
getfacl, SDKs or devices. The test runner's existing UID is retained.
"""

import copy
import errno
import importlib.util
import json
import os
from pathlib import Path
import signal
import subprocess
import sys
import tempfile
import time
import unittest
from unittest.mock import MagicMock, patch


ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("acl_lifecycle", ROOT / "scripts/android_acl_lifecycle.py")
lifecycle = importlib.util.module_from_spec(spec)
spec.loader.exec_module(lifecycle)
ENVIRONMENT = {"PATH": "/usr/bin:/bin", "LANG": "C.UTF-8", "LC_ALL": "C.UTF-8"}


def complete_observation():
    identity = {"uid": 1001, "effective_uid": 1001, "gid": 1001, "effective_gid": 1001,
                "supplementary_groups": [4, 1001]}
    return {"scope": lifecycle.SCOPE, "arguments": ["fixed command"], "supervisor_identity": identity,
            "supervisor_pid": 100, "supervisor_wait_status": 0, "command_started": True,
            "command_pid": 101, "exit_code": 0, "quiescent": True,
            "subreaper": {"set_result": 0, "get_result": 0, "enabled": 1},
            "initial_wait_errno": errno.ECHILD, "final_wait_errno": errno.ECHILD,
            "reaped": [{"pid": 101, "wait_status": 0}]}


class AclLifecycleTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.directory = Path(self.temporary.name)

    def run_harmless_subtree(self, script, *, command_seconds=2, quiesce_seconds=0.3):
        with tempfile.TemporaryFile() as transport:
            pid = os.fork()
            if pid == 0:
                try:
                    observed = lifecycle.supervise([sys.executable, "-I", "-c", script], ENVIRONMENT,
                                                   command_seconds=command_seconds,
                                                   quiesce_seconds=quiesce_seconds)
                    transport.write(json.dumps(observed).encode())
                    transport.flush()
                    os._exit(0)
                except BaseException:
                    os._exit(1)
            status = lifecycle.wait_for_supervisor(pid, 5)
            self.assertTrue(os.WIFEXITED(status))
            self.assertEqual(0, os.WEXITSTATUS(status))
            transport.seek(0)
            observed = json.loads(transport.read())
            observed.update(supervisor_pid=pid, supervisor_wait_status=status)
            return observed

    def test_actual_normal_command_is_reaped_before_positive_absence(self):
        observed = self.run_harmless_subtree("print('harmless complete')")
        self.assertEqual(0, observed["exit_code"])
        self.assertEqual("harmless complete\n", observed["stdout"])
        self.assertEqual("", observed["stderr"])
        self.assertEqual(1, len(observed["reaped"]))
        self.assertTrue(lifecycle.quiescence_passes(observed, lifecycle.process_identity()))
        self.assertNotIn("timeout_seconds", observed)

    def test_actual_exit_one_does_not_finish_before_late_orphan(self):
        marker = self.directory / "late-operation"
        script = "\n".join([
            "import os, time", "from pathlib import Path", "child = os.fork()", "if child == 0:",
            "    time.sleep(0.15)", "    Path(" + repr(str(marker)) + ").write_text('finished')",
            "    os._exit(0)", "os._exit(1)"])
        observed = self.run_harmless_subtree(script)
        self.assertEqual(1, observed["exit_code"])
        self.assertEqual("finished", marker.read_text())
        self.assertGreaterEqual(observed["elapsed_seconds"], 0.12)
        self.assertEqual(2, len(observed["reaped"]))
        self.assertTrue(lifecycle.quiescence_passes(observed, lifecycle.process_identity()))

    def test_actual_double_fork_is_adopted_and_reaped(self):
        marker = self.directory / "double-fork-operation"
        script = "\n".join([
            "import os, time", "from pathlib import Path", "first = os.fork()", "if first == 0:",
            "    second = os.fork()", "    if second == 0:", "        time.sleep(0.1)",
            "        Path(" + repr(str(marker)) + ").write_text('finished')", "        os._exit(0)",
            "    os._exit(0)", "os._exit(0)"])
        observed = self.run_harmless_subtree(script)
        self.assertEqual("finished", marker.read_text())
        self.assertEqual(0, observed["exit_code"])
        self.assertEqual(3, len(observed["reaped"]))
        self.assertTrue(lifecycle.quiescence_passes(observed, lifecycle.process_identity()))

    def test_actual_lingering_orphan_cannot_supply_quiescence_proof(self):
        marker = self.directory / "operation-after-timeout"
        script = "\n".join([
            "import os, signal, time", "from pathlib import Path", "child = os.fork()", "if child == 0:",
            "    signal.signal(signal.SIGTERM, signal.SIG_IGN)", "    time.sleep(0.3)",
            "    Path(" + repr(str(marker)) + ").write_text('late')", "    os._exit(0)", "os._exit(1)"])
        observed = self.run_harmless_subtree(script, command_seconds=0.08, quiesce_seconds=0.06)
        self.assertFalse(observed["quiescent"])
        self.assertFalse(lifecycle.quiescence_passes(observed, lifecycle.process_identity()))
        self.assertNotIn("final_wait_errno", observed)
        self.assertFalse(marker.exists())
        # The self-limiting harmless orphan completes its only file write before
        # this directory is removed. No signal or operation targets another job.
        deadline = time.monotonic() + 2
        while not marker.exists() and time.monotonic() < deadline:
            time.sleep(0.02)
        self.assertEqual("late", marker.read_text())

    def test_actual_command_timeout_reaps_its_signalled_direct_child(self):
        observed = self.run_harmless_subtree("import time; time.sleep(1)",
                                            command_seconds=0.08, quiesce_seconds=0.3)
        self.assertEqual(-int(signal.SIGTERM), observed["exit_code"])
        self.assertEqual(0.08, observed["timeout_seconds"])
        self.assertEqual(int(signal.SIGTERM), observed["termination_signal"])
        self.assertTrue(lifecycle.quiescence_passes(observed, lifecycle.process_identity()))

    def test_failed_subreaper_setup_never_starts_a_command(self):
        with patch.object(lifecycle, "enable_subreaper", side_effect=OSError("setting denied")), \
                patch.object(lifecycle.signal, "signal"), patch.object(lifecycle.subprocess, "Popen") as process:
            observed = lifecycle.supervise(["never executed"], ENVIRONMENT)
        process.assert_not_called()
        self.assertFalse(observed["command_started"])
        self.assertFalse(observed["quiescent"])
        self.assertIn("setting denied", observed["error"])

    def test_subreaper_set_and_get_results_are_both_checked(self):
        for set_result, get_result, enabled in ((-1, 0, 1), (0, -1, 1), (0, 0, 0)):
            libc = MagicMock()
            def prctl(operation, argument, *unused):
                if operation.value == 36:
                    return set_result
                argument._obj.value = enabled
                return get_result
            libc.prctl.side_effect = prctl
            with self.subTest(set_result=set_result, get_result=get_result, enabled=enabled), \
                    patch.object(lifecycle.ctypes, "CDLL", return_value=libc):
                with self.assertRaises((OSError, RuntimeError)):
                    lifecycle.enable_subreaper({})

    def test_zero_nonblocking_wait_means_live_child_until_reaped(self):
        no_children = ChildProcessError(errno.ECHILD, "no child")
        process = MagicMock(pid=101)
        process.returncode = None
        waits = [no_children, (0, 0), (101, 0), no_children]
        with patch.object(lifecycle, "enable_subreaper", side_effect=lambda result: result.update(
                set_result=0, get_result=0, enabled=1)), patch.object(lifecycle.signal, "signal"), \
                patch.object(lifecycle.subprocess, "Popen", return_value=process), \
                patch.object(lifecycle.os, "waitpid", side_effect=waits) as wait, \
                patch.object(lifecycle.time, "sleep") as sleep:
            observed = lifecycle.supervise(["never executed"], ENVIRONMENT)
        sleep.assert_called_once()
        self.assertTrue(observed["quiescent"])
        self.assertEqual(0, observed["exit_code"])
        self.assertEqual(4, wait.call_count)
        self.assertTrue(all(call.args == (-1, os.WNOHANG | lifecycle.WAIT_ALL) for call in wait.call_args_list))
        process.wait.assert_not_called()
        process.poll.assert_not_called()
        process.communicate.assert_not_called()

    def test_receipt_cannot_substitute_exit_zero_for_descendant_absence(self):
        valid = complete_observation()
        self.assertTrue(lifecycle.quiescence_passes(valid, valid["supervisor_identity"]))
        for key, value in (("quiescent", False), ("final_wait_errno", None),
                           ("supervisor_wait_status", 256), ("reaped", []),
                           ("exit_code", False), ("command_pid", None),
                           ("subreaper", {"set_result": 0, "get_result": 0, "enabled": True}),
                           ("reaped", [{"pid": 101, "wait_status": 0}, {"pid": 101, "wait_status": 0}])):
            changed = copy.deepcopy(valid)
            changed[key] = value
            with self.subTest(key=key):
                self.assertFalse(lifecycle.quiescence_passes(changed, valid["supervisor_identity"]))

    def test_production_entry_rejects_root_or_changed_identity_before_fork(self):
        expected = complete_observation()["supervisor_identity"]
        for current in (dict(expected, uid=0, effective_uid=0), dict(expected, gid=993),
                        dict(expected, supplementary_groups=[993, 1001])):
            with self.subTest(current=current), patch.object(lifecycle, "process_identity", return_value=current), \
                    patch.object(lifecycle.os, "fork") as fork:
                with self.assertRaisesRegex(RuntimeError, "original non-root"):
                    lifecycle.observe(["never executed"], ENVIRONMENT, expected, {})
                fork.assert_not_called()

    def test_supervisor_timeout_preserves_original_error_and_no_proof(self):
        expected = complete_observation()["supervisor_identity"]
        failure = lifecycle.SupervisorDeadline("original supervisor timeout")
        with patch.object(lifecycle, "process_identity", return_value=expected), \
                patch.object(lifecycle.os, "fork", return_value=12345), \
                patch.object(lifecycle, "wait_for_supervisor", side_effect=[failure, TimeoutError("still running")]) as wait, \
                patch.object(lifecycle.os, "kill") as kill:
            observed = {}
            with self.assertRaises(TimeoutError) as raised:
                lifecycle.observe(["never executed"], ENVIRONMENT, expected, observed)
        self.assertIs(failure, raised.exception)
        kill.assert_called_once_with(12345, signal.SIGTERM)
        self.assertEqual([((12345, 15),), ((12345, 3),)], [(call.args,) for call in wait.call_args_list])
        self.assertFalse(observed["quiescent"])
        self.assertIn("still running", observed["supervisor_interruption_error"])

    def test_post_reap_keyboard_interrupt_never_signals_a_reusable_pid(self):
        expected = complete_observation()["supervisor_identity"]
        failure = KeyboardInterrupt("interrupted after the owned wait reaped its child")
        def reaped_then_interrupted(pid, seconds):
            self.assertEqual((pid, 0), lifecycle.os.waitpid(pid, os.WNOHANG))
            raise failure
        with patch.object(lifecycle, "process_identity", return_value=expected), \
                patch.object(lifecycle.os, "fork", return_value=31337), \
                patch.object(lifecycle.os, "waitpid", return_value=(31337, 0)) as reap, \
                patch.object(lifecycle, "wait_for_supervisor", side_effect=reaped_then_interrupted) as wait, \
                patch.object(lifecycle.os, "kill") as kill:
            observed = {}
            with self.assertRaises(KeyboardInterrupt) as raised:
                lifecycle.observe(["never executed"], ENVIRONMENT, expected, observed)
        self.assertIs(failure, raised.exception)
        kill.assert_not_called()
        reap.assert_called_once_with(31337, os.WNOHANG)
        wait.assert_called_once_with(31337, lifecycle.SUPERVISOR_SECONDS)
        self.assertFalse(observed["quiescent"])
        self.assertNotIn("supervisor_wait_status", observed)
        self.assertIn("interrupted after", observed["supervisor_error"])

    def test_dedicated_deadline_follows_only_a_wait_that_did_not_reap(self):
        with patch.object(lifecycle.os, "waitpid", return_value=(0, 0)) as wait, \
                patch.object(lifecycle.time, "monotonic", side_effect=[10.0, 25.0]), \
                patch.object(lifecycle.time, "sleep") as sleep:
            with self.assertRaises(lifecycle.SupervisorDeadline):
                lifecycle.wait_for_supervisor(31337, 15)
        wait.assert_called_once_with(31337, os.WNOHANG)
        sleep.assert_not_called()

    def test_unclassified_timeout_never_establishes_numeric_pid_ownership(self):
        expected = complete_observation()["supervisor_identity"]
        failure = TimeoutError("unclassified wait failure")
        with patch.object(lifecycle, "process_identity", return_value=expected), \
                patch.object(lifecycle.os, "fork", return_value=31337), \
                patch.object(lifecycle, "wait_for_supervisor", side_effect=failure) as wait, \
                patch.object(lifecycle.os, "kill") as kill:
            observed = {}
            with self.assertRaises(TimeoutError) as raised:
                lifecycle.observe(["never executed"], ENVIRONMENT, expected, observed)
        self.assertIs(failure, raised.exception)
        kill.assert_not_called()
        wait.assert_called_once_with(31337, lifecycle.SUPERVISOR_SECONDS)
        self.assertFalse(observed["quiescent"])
        self.assertNotIn("supervisor_wait_status", observed)


if __name__ == "__main__":
    unittest.main()
