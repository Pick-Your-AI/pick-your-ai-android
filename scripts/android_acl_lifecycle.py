"""Prove termination of one ACL command's subtree in a dedicated non-root child.

There is no command-line entry point or privileged Python process. The caller
supplies one already-validated, fixed sudo/setfacl command. This child changes
only its own Linux subreaper attribute; the workload never runs beneath it.
"""

from __future__ import annotations

import ctypes
import errno
import json
import os
import signal
import subprocess
import tempfile
import time


SCOPE = "owned-acl-command-subtree-v1"
WAIT_ALL = 0x40000000  # Linux __WALL includes non-SIGCHLD clone children.
COMMAND_SECONDS = 10
QUIESCE_SECONDS = 2
SUPERVISOR_SECONDS = 15
SUPERVISOR_JOIN_SECONDS = 3
OUTPUT_LIMIT = 8192
TRANSPORT_LIMIT = 32768
MAX_REAPED = 32


class SupervisorDeadline(TimeoutError):
    """The sole waiter reached its deadline without reaping this direct child."""


def process_identity() -> dict:
    return {"uid": os.getuid(), "effective_uid": os.geteuid(), "gid": os.getgid(),
            "effective_gid": os.getegid(), "supplementary_groups": sorted(os.getgroups())}


def enable_subreaper(observed: dict) -> None:
    libc = ctypes.CDLL(None, use_errno=True)
    libc.prctl.restype = ctypes.c_int
    result = libc.prctl(ctypes.c_int(36), ctypes.c_ulong(1), ctypes.c_ulong(0),
                        ctypes.c_ulong(0), ctypes.c_ulong(0))  # PR_SET_CHILD_SUBREAPER
    observed["set_result"] = result
    if result != 0:
        raise OSError(ctypes.get_errno(), "Cannot enable this process's child subreaper")
    enabled = ctypes.c_int(0)
    result = libc.prctl(ctypes.c_int(37), ctypes.byref(enabled), ctypes.c_ulong(0),
                        ctypes.c_ulong(0), ctypes.c_ulong(0))  # PR_GET_CHILD_SUBREAPER
    observed.update(get_result=result, enabled=enabled.value)
    if result != 0 or enabled.value != 1:
        raise RuntimeError("This process's subreaper readback did not confirm the setting")


def read_output(stream) -> tuple[str, bool]:
    stream.flush()
    size = stream.seek(0, os.SEEK_END)
    stream.seek(max(0, size - OUTPUT_LIMIT))
    return stream.read(OUTPUT_LIMIT).decode("utf-8", errors="replace"), size > OUTPUT_LIMIT


def supervise(arguments: list[str], environment: dict, *, command_seconds=COMMAND_SECONDS,
              quiesce_seconds=QUIESCE_SECONDS) -> dict:
    """Internal process loop; tests substitute harmless commands, never ACL tools."""
    observed = {"scope": SCOPE, "arguments": arguments, "supervisor_identity": process_identity(),
                "subreaper": {}, "command_started": False, "quiescent": False, "reaped": []}
    started = time.monotonic()
    interrupted = []
    process = None
    direct_status = None
    termination_sent = False
    deadline = started + command_seconds + quiesce_seconds
    def interrupted_by(signum, frame):
        interrupted.append(signum)
    signal.signal(signal.SIGTERM, interrupted_by)
    signal.signal(signal.SIGINT, interrupted_by)
    signal.signal(signal.SIGCHLD, signal.SIG_DFL)
    with tempfile.TemporaryFile() as stdout, tempfile.TemporaryFile() as stderr:
        try:
            enable_subreaper(observed["subreaper"])
            try:
                os.waitpid(-1, os.WNOHANG | WAIT_ALL)
            except ChildProcessError as error:
                if error.errno != errno.ECHILD:
                    raise
                observed["initial_wait_errno"] = errno.ECHILD
            else:
                raise RuntimeError("The dedicated supervisor unexpectedly already has children")
            try:
                process = subprocess.Popen(arguments, env=environment, stdin=subprocess.DEVNULL,
                                           stdout=stdout, stderr=stderr, close_fds=True)
                observed.update(command_started=True, command_pid=process.pid)
            except Exception as error:
                observed["error"] = str(error)[-2000:]
            while True:
                try:
                    pid, status = os.waitpid(-1, os.WNOHANG | WAIT_ALL)
                except ChildProcessError as error:
                    if error.errno != errno.ECHILD:
                        raise
                    observed.update(quiescent=True, final_wait_errno=errno.ECHILD)
                    break
                if pid:
                    if len(observed["reaped"]) >= MAX_REAPED:
                        raise RuntimeError("The fixed ACL command exceeded the bounded descendant inventory")
                    observed["reaped"].append({"pid": pid, "wait_status": status})
                    if process is not None and pid == process.pid:
                        direct_status = status
                        # This loop is the sole owner of wait status, including sudo.
                        process.returncode = os.waitstatus_to_exitcode(status)
                        observed["exit_code"] = process.returncode
                    continue
                now = time.monotonic()
                if not termination_sent and (interrupted or now - started >= command_seconds):
                    termination_sent = True
                    observed["timeout_seconds"] = command_seconds
                    if interrupted:
                        observed["interrupted_signals"] = interrupted[:8]
                    deadline = min(deadline, now + quiesce_seconds)
                    if process is not None and direct_status is None:
                        # An unreaped direct child cannot have its PID reused. No
                        # privileged kill command or signal to an unrelated PID.
                        try:
                            os.kill(process.pid, signal.SIGTERM)
                            observed["termination_signal"] = int(signal.SIGTERM)
                        except OSError as error:
                            observed["termination_error"] = str(error)[-2000:]
                if now >= deadline:
                    observed["error"] = "A command descendant may remain; quiescence was not established"
                    break
                time.sleep(min(0.02, max(0, deadline - now)))
            if process is not None and observed["quiescent"] and direct_status is None:
                observed["quiescent"] = False
                observed["error"] = "The command wait status was lost; no completion inference is allowed"
        except BaseException as error:
            observed["error"] = str(error)[-2000:]
        finally:
            observed["stdout"], stdout_truncated = read_output(stdout)
            observed["stderr"], stderr_truncated = read_output(stderr)
            observed["output_truncated"] = stdout_truncated or stderr_truncated
            observed["elapsed_seconds"] = round(time.monotonic() - started, 6)
            if observed["elapsed_seconds"] >= command_seconds:
                observed.setdefault("timeout_seconds", command_seconds)
            if interrupted:
                observed["interrupted_signals"] = interrupted[:8]
    return observed


def wait_for_supervisor(pid: int, seconds: float) -> int:
    deadline = time.monotonic() + seconds
    while True:
        ended, status = os.waitpid(pid, os.WNOHANG)
        if ended == pid:
            return status
        if time.monotonic() >= deadline:
            raise SupervisorDeadline("The dedicated ACL supervisor did not finish within its deadline")
        time.sleep(min(0.02, max(0, deadline - time.monotonic())))


def observe(arguments: list[str], environment: dict, expected_identity: dict, destination: dict) -> None:
    """Run one dedicated same-identity supervisor and retain its completion proof."""
    current = process_identity()
    if (current != expected_identity or type(current["uid"]) is not int or current["uid"] <= 0 or
            current["uid"] != current["effective_uid"] or current["gid"] <= 0 or
            current["gid"] != current["effective_gid"]):
        raise RuntimeError("An ACL supervisor must retain the original non-root process identity")
    destination.update(arguments=arguments, scope=SCOPE, quiescent=False)
    failure = None
    status = None
    with tempfile.TemporaryFile() as transport:
        pid = os.fork()
        if pid == 0:
            try:
                if process_identity() != expected_identity:
                    raise RuntimeError("The dedicated supervisor changed process identity")
                result = supervise(arguments, environment)
                payload = json.dumps(result).encode("utf-8")
                if len(payload) > TRANSPORT_LIMIT:
                    raise RuntimeError("The command completion receipt exceeded its retention bound")
                transport.write(payload)
                transport.flush()
                os._exit(0)
            except BaseException:
                os._exit(1)  # No complete proof: the caller must refuse restoration.
        destination["supervisor_pid"] = pid
        try:
            status = wait_for_supervisor(pid, SUPERVISOR_SECONDS)
        except BaseException as error:
            failure = error
            destination["supervisor_error"] = str(error)[-2000:]
            if isinstance(error, SupervisorDeadline):
                try:
                    # Only this dedicated deadline proves that our sole wait
                    # did not reap the same-identity direct child.
                    os.kill(pid, signal.SIGTERM)
                    status = wait_for_supervisor(pid, SUPERVISOR_JOIN_SECONDS)
                except BaseException as cleanup_error:
                    destination["supervisor_interruption_error"] = str(cleanup_error)[-2000:]
            else:
                # An asynchronous interruption can follow waitpid's successful
                # reap before its result is returned. That PID is then reusable.
                destination["supervisor_interruption_error"] = (
                    "Supervisor wait ownership is uncertain; no signal or additional wait was attempted")
        if status is not None:
            destination["supervisor_wait_status"] = status
            try:
                if not os.WIFEXITED(status) or os.WEXITSTATUS(status) != 0:
                    raise RuntimeError("The ACL supervisor did not return a complete ordinary result")
                transport.seek(0)
                payload = transport.read(TRANSPORT_LIMIT + 1)
                if not payload or len(payload) > TRANSPORT_LIMIT:
                    raise RuntimeError("The ACL supervisor receipt is empty or exceeds its bound")
                result = json.loads(payload)
                if result.get("arguments") != arguments or result.get("supervisor_identity") != expected_identity:
                    raise RuntimeError("The ACL supervisor replaced its command or process identity")
                destination.update(result)
            except BaseException as error:
                destination["supervisor_error"] = str(error)[-2000:]
                if failure is None:
                    failure = error
    if failure is not None:
        raise failure


def quiescence_passes(observed: dict, identity: dict) -> bool:
    if not (isinstance(observed, dict) and observed.get("scope") == SCOPE and
            observed.get("supervisor_identity") == identity and observed.get("quiescent") is True and
            observed.get("subreaper") == {"set_result": 0, "get_result": 0, "enabled": 1} and
            all(type(value) is int for value in observed.get("subreaper", {}).values()) and
            type(observed.get("initial_wait_errno")) is int and observed["initial_wait_errno"] == errno.ECHILD and
            type(observed.get("final_wait_errno")) is int and observed["final_wait_errno"] == errno.ECHILD and
            type(observed.get("supervisor_pid")) is int and observed["supervisor_pid"] > 0 and
            type(observed.get("supervisor_wait_status")) is int and observed["supervisor_wait_status"] == 0 and
            isinstance(observed.get("reaped"), list) and len(observed["reaped"]) <= MAX_REAPED):
        return False
    reaped = observed["reaped"]
    if any(not isinstance(item, dict) or type(item.get("pid")) is not int or item["pid"] <= 0 or
           type(item.get("wait_status")) is not int or
           not (os.WIFEXITED(item["wait_status"]) or os.WIFSIGNALED(item["wait_status"])) for item in reaped):
        return False
    if len({item["pid"] for item in reaped}) != len(reaped):
        return False
    if observed.get("command_started") is False:
        return not reaped and "command_pid" not in observed and "exit_code" not in observed
    matches = [item for item in reaped if item["pid"] == observed.get("command_pid")]
    return (observed.get("command_started") is True and type(observed.get("command_pid")) is int and
            len(matches) == 1 and type(observed.get("exit_code")) is int and
            observed["exit_code"] == os.waitstatus_to_exitcode(matches[0]["wait_status"]))
