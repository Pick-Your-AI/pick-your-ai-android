"""Test command-subtree ownership with harmless local processes and syscall doubles.

Actual lifecycle cases run only static Python children inside a disposable test
directory. They do not invoke the production non-root ACL entry, sudo, setfacl,
getfacl, SDKs or devices. The test runner's existing UID is retained.
"""

import copy
from contextlib import contextmanager
import errno
import importlib.util
import json
import os
from pathlib import Path
import signal
import select
import socket
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

    def join_fixture_supervisor(self, pid, seconds=5):
        try:
            return lifecycle.wait_for_supervisor(pid, seconds)
        except lifecycle.SupervisorDeadline as failure:
            # This sole wait establishes an unreaped direct child. A different
            # wait error does not authorize a signal to a potentially reused PID.
            try:
                os.kill(pid, signal.SIGKILL)
            except OSError as error:
                failure.add_note("Fixture SIGKILL failed: " + repr(error))
            try:
                status = lifecycle.wait_for_supervisor(pid, 2)
                failure.add_note("Fixture joined after SIGKILL with wait status " + str(status))
            except BaseException as error:
                failure.add_note("Owned fixture child remains unconfirmed: " + repr(error))
            raise

    @contextmanager
    def ready_lingering_orphan(self, marker, startup_seconds):
        """Hold one actual orphan alive until the test releases its owning supervisor.

        Readiness delays only Popen's return in this fixture. The production loop,
        clock and short command/quiescence deadlines remain unchanged. Keeping
        the supervisor alive after its observation lets that same owner reap the
        orphan instead of abandoning it to the test environment's init process.
        """
        parent, child = socket.socketpair()
        parent.settimeout(5)
        child.settimeout(5)
        pid = os.fork()
        if pid == 0:
            parent.close()
            ready_read, ready_write = os.pipe()
            release_read, release_write = os.pipe()
            ready_pid = None
            started_process = None
            readiness_error = None
            popen = lifecycle.subprocess.Popen
            script = "\n".join([
                "import os, signal, time", "from pathlib import Path",
                "time.sleep(" + repr(startup_seconds) + ")", "child = os.fork()", "if child == 0:",
                "    signal.signal(signal.SIGTERM, signal.SIG_IGN)",
                "    deadline = time.monotonic() + 2",
                "    while os.getppid() != " + str(os.getpid()) + ":",
                "        if time.monotonic() >= deadline: os._exit(3)",
                "        time.sleep(0.005)",
                "    os.write(" + str(ready_write) + ", str(os.getpid()).encode() + b'\\n')",
                "    os.close(" + str(ready_write) + ")",
                "    released = os.read(" + str(release_read) + ", 1)",
                "    os.close(" + str(release_read) + ")",
                "    if released != b'R': os._exit(2)",
                "    Path(" + repr(str(marker)) + ").write_text('late')",
                "    os._exit(0)", "os._exit(1)"])

            def start_and_wait_for_ready(*arguments, **keywords):
                nonlocal ready_pid, started_process, readiness_error
                started_process = popen(*arguments, **keywords, pass_fds=(ready_write, release_read))
                try:
                    if not select.select([ready_read], [], [], 2)[0]:
                        raise TimeoutError("Harmless orphan did not report readiness")
                    payload = os.read(ready_read, 64)
                    if not payload.endswith(b"\n") or not payload[:-1].isdigit():
                        raise RuntimeError("Harmless orphan readiness was malformed")
                    ready_pid = int(payload)
                except BaseException as error:
                    readiness_error = {"type": type(error).__name__, "message": str(error)}
                # Even failed readiness must transfer the existing process to
                # the production loop, which retains its original timeout rules.
                return started_process

            def send(value):
                payload = json.dumps(value).encode() + b"\n"
                if len(payload) > lifecycle.TRANSPORT_LIMIT:
                    raise RuntimeError("Harmless fixture receipt exceeded its bound")
                child.sendall(payload)

            status = 1
            try:
                with patch.object(lifecycle.subprocess, "Popen", side_effect=start_and_wait_for_ready):
                    observed = lifecycle.supervise([sys.executable, "-I", "-c", script], ENVIRONMENT,
                                                   command_seconds=0.08, quiesce_seconds=0.06)
                observed["orphan_ready"] = ready_pid is not None
                observed["readiness_error"] = readiness_error
                observed["orphan_alive_at_observation"] = ready_pid is not None and \
                    os.waitpid(ready_pid, os.WNOHANG | lifecycle.WAIT_ALL) == (0, 0)
                send(observed)
                with child.makefile("rb") as release:
                    if release.readline(32) != b"release\n":
                        raise RuntimeError("Harmless orphan release was not explicit")
                status = 0
            except BaseException:
                status = 1
            finally:
                # Only this fixture owns these descriptors and descendants. EOF
                # also releases the orphan if the supervising process is lost.
                os.close(ready_read)
                os.close(ready_write)
                os.close(release_read)
                try:
                    os.write(release_write, b"R")
                except BrokenPipeError:
                    pass
                os.close(release_write)
                reaped = []
                cleanup = {"complete": False, "reaped": reaped}
                if started_process is not None and started_process.returncode is None:
                    try:
                        ended, wait_status = os.waitpid(started_process.pid, os.WNOHANG | lifecycle.WAIT_ALL)
                        if ended:
                            reaped.append({"pid": ended, "wait_status": wait_status})
                            started_process.returncode = os.waitstatus_to_exitcode(wait_status)
                        else:
                            # A pre-readiness exception cannot discard ownership.
                            # Only our confirmed unreaped direct child is signalled.
                            os.kill(started_process.pid, signal.SIGKILL)
                    except OSError as error:
                        if error.errno != errno.ECHILD:
                            cleanup["termination_error"] = repr(error)
                deadline = time.monotonic() + 2
                while time.monotonic() < deadline:
                    try:
                        ended, wait_status = os.waitpid(-1, os.WNOHANG | lifecycle.WAIT_ALL)
                    except ChildProcessError as error:
                        if error.errno == errno.ECHILD:
                            cleanup.update(complete=True, final_wait_errno=errno.ECHILD)
                        break
                    if ended:
                        reaped.append({"pid": ended, "wait_status": wait_status})
                        if started_process is not None and ended == started_process.pid:
                            started_process.returncode = os.waitstatus_to_exitcode(wait_status)
                    else:
                        time.sleep(0.01)
                try:
                    send(cleanup)
                except BaseException:
                    status = 1
                child.close()
                os._exit(status if cleanup["complete"] else 1)
        child.close()
        with parent, parent.makefile("rb") as transport:
            def receive():
                payload = transport.readline(lifecycle.TRANSPORT_LIMIT + 1)
                self.assertTrue(payload.endswith(b"\n"))
                self.assertLessEqual(len(payload), lifecycle.TRANSPORT_LIMIT)
                return json.loads(payload)

            observed = None
            failure = None
            cleanup_errors = []
            try:
                observed = receive()
                observed["supervisor_pid"] = pid
                if observed.get("readiness_error") is not None:
                    error = observed["readiness_error"]
                    exception = TimeoutError if error["type"] == "TimeoutError" else RuntimeError
                    raise exception(error["message"])
                yield observed
            except BaseException as error:
                failure = error
            finally:
                try:
                    parent.sendall(b"release\n")
                    cleanup = receive()
                    self.assertTrue(cleanup["complete"], "Owned fixture descendants remain unconfirmed: " +
                                    json.dumps(cleanup, sort_keys=True))
                    self.assertEqual(errno.ECHILD, cleanup["final_wait_errno"])
                    if observed is not None and observed.get("orphan_ready"):
                        self.assertEqual(1, len(cleanup["reaped"]))
                        self.assertEqual(0, cleanup["reaped"][0]["wait_status"])
                except BaseException as error:
                    cleanup_errors.append(error)
                try:
                    status = self.join_fixture_supervisor(pid)
                    self.assertEqual(0, status)
                    if observed is not None:
                        observed["supervisor_wait_status"] = status
                except BaseException as error:
                    cleanup_errors.append(error)
                if failure is None and cleanup_errors:
                    failure = cleanup_errors.pop(0)
                if failure is not None:
                    for error in cleanup_errors:
                        failure.add_note("Fixture cleanup failed: " + repr(error))
                        for note in getattr(error, "__notes__", []):
                            failure.add_note(note)
                    raise failure

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
        for startup_seconds in (0, 0.2):
            with self.subTest(startup_seconds=startup_seconds):
                marker = self.directory / ("operation-after-timeout-" + str(startup_seconds))
                with self.ready_lingering_orphan(marker, startup_seconds) as observed:
                    self.assertTrue(observed["orphan_ready"])
                    self.assertTrue(observed["orphan_alive_at_observation"])
                    self.assertFalse(observed["quiescent"])
                    self.assertFalse(lifecycle.quiescence_passes(observed, lifecycle.process_identity()))
                    self.assertNotIn("final_wait_errno", observed)
                    self.assertFalse(marker.exists())
                # The complete, actually joined supervisor receipt still cannot
                # turn its earlier live-descendant observation into a proof.
                self.assertFalse(lifecycle.quiescence_passes(observed, lifecycle.process_identity()))
                # Explicit release and owned reaping precede directory removal.
                deadline = time.monotonic() + 2
                while not marker.exists() and time.monotonic() < deadline:
                    time.sleep(0.02)
                self.assertEqual("late", marker.read_text())

    def test_actual_pre_ready_startup_timeout_has_no_lingering_orphan(self):
        for startup_seconds in (0, 0.2):
            with self.subTest(startup_seconds=startup_seconds):
                marker = self.directory / ("unexpected-readiness-" + str(startup_seconds))
                script = "\n".join([
                    "import signal, time", "from pathlib import Path",
                    "time.sleep(" + repr(startup_seconds) + ")",
                    "signal.pause()",  # Explicitly held before readiness, until owned SIGTERM.
                    "Path(" + repr(str(marker)) + ").write_text('unexpected')"])
                observed = self.run_harmless_subtree(script, command_seconds=0.08)
                self.assertEqual(-int(signal.SIGTERM), observed["exit_code"])
                self.assertEqual(0.08, observed["timeout_seconds"])
                self.assertEqual(int(signal.SIGTERM), observed["termination_signal"])
                self.assertEqual(1, len(observed["reaped"]))
                self.assertEqual(errno.ECHILD, observed["final_wait_errno"])
                self.assertTrue(lifecycle.quiescence_passes(observed, lifecycle.process_identity()))
                self.assertFalse(marker.exists())

    def test_readiness_failure_keeps_ownership_before_fork(self):
        for readiness_exception in (None, OSError("injected readiness failure")):
            with self.subTest(readiness_exception=readiness_exception), tempfile.TemporaryFile() as transport:
                # The outer disposable owner also contains the old leaking
                # fixture: its static child finishes within five + two seconds.
                # It never signals an unclassified or unrelated process.
                pid = os.fork()
                if pid == 0:
                    result = {"yielded": False, "children_absent_on_return": False,
                              "outer_cleanup_complete": False, "outer_reaped": []}
                    try:
                        lifecycle.enable_subreaper({})
                        try:
                            with patch.object(select, "select", wraps=select.select,
                                              side_effect=readiness_exception):
                                with self.ready_lingering_orphan(self.directory / "late-startup", 5):
                                    result["yielded"] = True
                        except BaseException as error:
                            result["error"] = {"type": type(error).__name__, "message": str(error),
                                               "notes": getattr(error, "__notes__", [])}
                        first_wait = True
                        deadline = time.monotonic() + 8
                        while time.monotonic() < deadline:
                            try:
                                ended, status = os.waitpid(-1, os.WNOHANG | lifecycle.WAIT_ALL)
                            except ChildProcessError as error:
                                if error.errno != errno.ECHILD:
                                    raise
                                result.update(children_absent_on_return=first_wait,
                                              outer_cleanup_complete=True)
                                break
                            first_wait = False
                            if ended:
                                result["outer_reaped"].append({"pid": ended, "wait_status": status})
                            else:
                                time.sleep(0.01)
                        transport.write(json.dumps(result).encode())
                        transport.flush()
                        os._exit(0 if result["outer_cleanup_complete"] else 1)
                    except BaseException:
                        os._exit(2)
                status = self.join_fixture_supervisor(pid, 12)
                self.assertEqual(0, status)
                transport.seek(0)
                result = json.loads(transport.read())
                self.assertFalse(result["yielded"])
                expected = {"type": "TimeoutError", "message": "Harmless orphan did not report readiness"} \
                    if readiness_exception is None else {"type": "RuntimeError", "message": str(readiness_exception)}
                self.assertEqual(expected["type"], result["error"]["type"])
                self.assertEqual(expected["message"], result["error"]["message"])
                self.assertEqual([], result["error"]["notes"])
                self.assertTrue(result["children_absent_on_return"])
                self.assertTrue(result["outer_cleanup_complete"])
                self.assertEqual([], result["outer_reaped"])

    def test_fixture_deadline_recovery_has_a_separate_bounded_join(self):
        for recovery in (int(signal.SIGKILL), lifecycle.SupervisorDeadline("recovery deadline")):
            with self.subTest(recovery=recovery):
                failure = lifecycle.SupervisorDeadline("primary deadline")
                with patch.object(lifecycle, "wait_for_supervisor", side_effect=[failure, recovery]) as wait, \
                        patch.object(os, "kill") as kill:
                    with self.assertRaises(lifecycle.SupervisorDeadline) as caught:
                        self.join_fixture_supervisor(101)
                self.assertIs(failure, caught.exception)
                self.assertEqual([(101, 5), (101, 2)], [call.args for call in wait.call_args_list])
                kill.assert_called_once_with(101, signal.SIGKILL)
                message = "remains unconfirmed" if isinstance(recovery, BaseException) else "joined after SIGKILL"
                self.assertTrue(any(message in note for note in failure.__notes__))

    def test_fixture_unclassified_wait_error_does_not_authorize_a_signal(self):
        failure = ChildProcessError(errno.ECHILD, "status already unavailable")
        with patch.object(lifecycle, "wait_for_supervisor", side_effect=failure) as wait, \
                patch.object(os, "kill") as kill:
            with self.assertRaises(ChildProcessError) as caught:
                self.join_fixture_supervisor(101)
        self.assertIs(failure, caught.exception)
        wait.assert_called_once_with(101, 5)
        kill.assert_not_called()

    def test_fixture_recovery_syscalls_never_use_a_blocking_wait(self):
        for recovered in (True, False):
            with self.subTest(recovered=recovered):
                recovery = (101, int(signal.SIGKILL)) if recovered else (0, 0)
                with patch.object(os, "waitpid", side_effect=[(0, 0), recovery]) as wait, \
                        patch.object(os, "kill") as kill, \
                        patch.object(time, "monotonic", side_effect=[0, 5, 10, 12]), \
                        patch.object(time, "sleep") as sleep:
                    with self.assertRaises(lifecycle.SupervisorDeadline) as caught:
                        self.join_fixture_supervisor(101)
                self.assertEqual([(101, os.WNOHANG)] * 2, [call.args for call in wait.call_args_list])
                kill.assert_called_once_with(101, signal.SIGKILL)
                sleep.assert_not_called()
                message = "joined after SIGKILL" if recovered else "remains unconfirmed"
                self.assertTrue(any(message in note for note in caught.exception.__notes__))

    def test_fixture_preserves_primary_error_and_cleanup_uncertainty(self):
        primary = AssertionError("original readiness assertion")
        recovery = lifecycle.SupervisorDeadline("synthetic cleanup deadline")
        recovery.add_note("Owned fixture child remains unconfirmed: synthetic join failure")
        real_join = self.join_fixture_supervisor

        def join_then_report_failure(pid):
            real_join(pid)  # Reap the real child before injecting the error.
            raise recovery

        with patch.object(self, "join_fixture_supervisor", side_effect=join_then_report_failure):
            with self.assertRaises(AssertionError) as caught:
                with self.ready_lingering_orphan(self.directory / "primary-error-marker", 0):
                    raise primary
        self.assertIs(primary, caught.exception)
        self.assertTrue(any("synthetic cleanup deadline" in note for note in primary.__notes__))
        self.assertIn(recovery.__notes__[0], primary.__notes__)

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
