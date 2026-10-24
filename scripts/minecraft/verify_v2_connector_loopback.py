"""Opt-in observational connector experiment; importing/unit testing never launches a server.

Later execution requires committed clean repositories and a fresh approved output directory.
This runner does not establish binding readiness, worker jobs, or production capability.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import secrets
import subprocess
import sys
import threading
import ctypes
from ctypes import wintypes as W
import time
import xml.etree.ElementTree as ET
from pathlib import Path
from urllib.request import Request

import verify_v2_transport_loopback as helper

UPSTREAM = "57e46850c56079a4e95c645234b696eebbaaaf4f"
APPROVED = Path(r"C:\Users\Administrator\AppData\Local\Temp\opencode")
CLASS = "io.github.jinyiwei2012.luosandbox.transport.V2ConnectorLoopbackTest"
CASES = {"SERVER_normal()", "CLIENT_normal()", "SERVER_wrongPsk()"}
XML = Path(f"build/test-results/test/TEST-{CLASS}.xml")
LIMIT = 2_097_152
Error = helper.PrerequisiteError


class WindowsApi:
    """Exact handles only: suspended root assigned to kill-on-close job before resume."""

    def __init__(self):
        if os.name != "nt":
            raise Error("WINDOWS_JOB_REQUIRED")
        self.k = ctypes.WinDLL("kernel32", use_last_error=True)
        self.spawn_cleanup_unknown = False
        signatures = {
            "CreateFileW": (
                [
                    W.LPCWSTR,
                    W.DWORD,
                    W.DWORD,
                    ctypes.c_void_p,
                    W.DWORD,
                    W.DWORD,
                    W.HANDLE,
                ],
                W.HANDLE,
            ),
            "CreateJobObjectW": ([ctypes.c_void_p, W.LPCWSTR], W.HANDLE),
            "SetInformationJobObject": (
                [W.HANDLE, ctypes.c_int, ctypes.c_void_p, W.DWORD],
                W.BOOL,
            ),
            "QueryInformationJobObject": (
                [W.HANDLE, ctypes.c_int, ctypes.c_void_p, W.DWORD, ctypes.c_void_p],
                W.BOOL,
            ),
            "AssignProcessToJobObject": ([W.HANDLE, W.HANDLE], W.BOOL),
            "TerminateJobObject": ([W.HANDLE, W.UINT], W.BOOL),
            "TerminateProcess": ([W.HANDLE, W.UINT], W.BOOL),
            "CloseHandle": ([W.HANDLE], W.BOOL),
            "ResumeThread": ([W.HANDLE], W.DWORD),
            "WaitForSingleObject": ([W.HANDLE, W.DWORD], W.DWORD),
            "GetExitCodeProcess": ([W.HANDLE, ctypes.POINTER(W.DWORD)], W.BOOL),
            "CreatePipe": (
                [
                    ctypes.POINTER(W.HANDLE),
                    ctypes.POINTER(W.HANDLE),
                    ctypes.c_void_p,
                    W.DWORD,
                ],
                W.BOOL,
            ),
            "SetHandleInformation": ([W.HANDLE, W.DWORD, W.DWORD], W.BOOL),
            "InitializeProcThreadAttributeList": (
                [ctypes.c_void_p, W.DWORD, W.DWORD, ctypes.POINTER(ctypes.c_size_t)],
                W.BOOL,
            ),
            "UpdateProcThreadAttribute": (
                [
                    ctypes.c_void_p,
                    W.DWORD,
                    ctypes.c_size_t,
                    ctypes.c_void_p,
                    ctypes.c_size_t,
                    ctypes.c_void_p,
                    ctypes.c_void_p,
                ],
                W.BOOL,
            ),
            "DeleteProcThreadAttributeList": ([ctypes.c_void_p], None),
            "CreateProcessW": (
                [
                    W.LPCWSTR,
                    W.LPWSTR,
                    ctypes.c_void_p,
                    ctypes.c_void_p,
                    W.BOOL,
                    W.DWORD,
                    ctypes.c_void_p,
                    W.LPCWSTR,
                    ctypes.c_void_p,
                    ctypes.c_void_p,
                ],
                W.BOOL,
            ),
        }
        for name, (args, result) in signatures.items():
            function = getattr(self.k, name)
            function.argtypes = args
            function.restype = result

    @staticmethod
    def check(ok):
        if not ok:
            raise Error("WINDOWS_HANDLE_OPERATION_FAILED")

    def job(self):
        handle = self.k.CreateJobObjectW(None, None)
        self.check(handle)

        # JOBOBJECT_EXTENDED_LIMIT_INFORMATION, ABI-aligned through ctypes fields.
        class Basic(ctypes.Structure):
            _fields_ = [
                ("times", ctypes.c_int64 * 2),
                ("flags", W.DWORD),
                ("minimum", ctypes.c_size_t),
                ("maximum", ctypes.c_size_t),
                ("active", W.DWORD),
                ("affinity", ctypes.c_size_t),
                ("priority", W.DWORD),
                ("scheduling", W.DWORD),
            ]

        class Extended(ctypes.Structure):
            _fields_ = [
                ("basic", Basic),
                ("io", ctypes.c_uint64 * 6),
                ("memory", ctypes.c_size_t * 4),
            ]

        info = Extended()
        info.basic.flags = 0x2000
        try:
            self.check(
                self.k.SetInformationJobObject(
                    handle, 9, ctypes.byref(info), ctypes.sizeof(info)
                )
            )
        except BaseException:
            self.close(handle)
            raise
        return handle

    def spawn_suspended(self, command, cwd, env):
        class SA(ctypes.Structure):
            _fields_ = [
                ("size", W.DWORD),
                ("descriptor", ctypes.c_void_p),
                ("inherit", W.BOOL),
            ]

        class SI(ctypes.Structure):
            _fields_ = [
                ("cb", W.DWORD),
                ("reserved", W.LPWSTR),
                ("desktop", W.LPWSTR),
                ("title", W.LPWSTR),
                ("x", W.DWORD),
                ("y", W.DWORD),
                ("xs", W.DWORD),
                ("ys", W.DWORD),
                ("xc", W.DWORD),
                ("yc", W.DWORD),
                ("fill", W.DWORD),
                ("flags", W.DWORD),
                ("show", W.WORD),
                ("cb2", W.WORD),
                ("reserved2", ctypes.c_void_p),
                ("stdin", W.HANDLE),
                ("stdout", W.HANDLE),
                ("stderr", W.HANDLE),
            ]

        class SIX(ctypes.Structure):
            _fields_ = [("si", SI), ("attributes", ctypes.c_void_p)]

        class PI(ctypes.Structure):
            _fields_ = [
                ("process", W.HANDLE),
                ("thread", W.HANDLE),
                ("pid", W.DWORD),
                ("tid", W.DWORD),
            ]

        read, write = W.HANDLE(), W.HANDLE()
        sa = SA(ctypes.sizeof(SA), None, True)
        self.check(
            self.k.CreatePipe(
                ctypes.byref(read), ctypes.byref(write), ctypes.byref(sa), 0
            )
        )
        attributes = None
        initialized = False
        null_input = None
        try:
            null_input = self.k.CreateFileW(
                "NUL", 0x80000000, 3, ctypes.byref(sa), 3, 0, None
            )
            self.check(null_input and null_input != ctypes.c_void_p(-1).value)
            self.check(self.k.SetHandleInformation(read, 1, 0))
            size = ctypes.c_size_t()
            self.k.InitializeProcThreadAttributeList(None, 1, 0, ctypes.byref(size))
            attributes = ctypes.create_string_buffer(size.value)
            self.check(
                self.k.InitializeProcThreadAttributeList(
                    attributes, 1, 0, ctypes.byref(size)
                )
            )
            initialized = True
            handles = (W.HANDLE * 2)(write.value, null_input)
            self.check(
                self.k.UpdateProcThreadAttribute(
                    attributes, 0, 0x20002, handles, ctypes.sizeof(handles), None, None
                )
            )
            startup = SIX()
            startup.si.cb = ctypes.sizeof(startup)
            startup.si.flags = 0x100
            startup.si.stdout = write
            startup.si.stderr = write
            startup.si.stdin = null_input
            startup.attributes = ctypes.cast(attributes, ctypes.c_void_p)
            pi = PI()
            environment = ctypes.create_unicode_buffer(
                "\0".join(f"{k}={v}" for k, v in sorted(env.items())) + "\0\0"
            )
            # cmd is itself suspended/contained before it can launch the .bat descendants.
            args = (
                [
                    os.environ.get("COMSPEC", r"C:\Windows\System32\cmd.exe"),
                    "/d",
                    "/s",
                    "/c",
                    subprocess.list2cmdline(command),
                ]
                if str(command[0]).lower().endswith(".bat")
                else command
            )
            command_line = subprocess.list2cmdline(args)
            if str(command[0]).lower().endswith(".bat"):
                command_line = (
                    subprocess.list2cmdline(args[:1])
                    + ' /d /s /c "'
                    + subprocess.list2cmdline(command)
                    + '"'
                )
            self.check(
                self.k.CreateProcessW(
                    None,
                    ctypes.create_unicode_buffer(command_line),
                    None,
                    None,
                    True,
                    0x4 | 0x400 | 0x80000 | 0x08000000,
                    environment,
                    str(cwd),
                    ctypes.byref(startup),
                    ctypes.byref(pi),
                )
            )
            # Return raw custody before any CRT conversion. OwnedTree now owns the suspended
            # process/thread/read handle even if stream adoption subsequently fails.
            owned_read = read.value
            read = W.HANDLE()
            return pi.process, pi.thread, pi.pid, owned_read
        finally:
            for cleanup in (
                lambda: (
                    self.k.DeleteProcThreadAttributeList(attributes)
                    if initialized
                    else None
                ),
                lambda: self.close(write),
                lambda: self.close(read),
                lambda: self.close(null_input),
            ):
                try:
                    cleanup()
                except BaseException:
                    self.spawn_cleanup_unknown = True

    def assign(self, job, process):
        self.check(self.k.AssignProcessToJobObject(job, process))

    def resume(self, thread):
        self.check(self.k.ResumeThread(thread) != 0xFFFFFFFF)

    def kill(self, job):
        self.check(self.k.TerminateJobObject(job, 1))

    def kill_suspended(self, process):
        self.check(self.k.TerminateProcess(process, 1))

    def adopt_read(self, handle):
        """Consumes the raw handle on ALL paths, with exactly one owner at each boundary."""
        import msvcrt

        fd = None
        try:
            fd = msvcrt.open_osfhandle(handle, os.O_RDONLY | os.O_BINARY)
        except BaseException:
            try:
                self.close(handle)
            except BaseException:
                self.spawn_cleanup_unknown = True
            raise
        try:
            return os.fdopen(fd, "rb", buffering=0)
        except BaseException:
            try:
                os.close(fd)  # open_osfhandle transferred raw custody to the CRT fd
            except BaseException:
                self.spawn_cleanup_unknown = True
            raise

    def close(self, handle):
        if handle:
            self.check(self.k.CloseHandle(handle))

    def poll(self, process):
        waited = self.k.WaitForSingleObject(process, 0)
        if waited == 0x102:
            return None
        self.check(waited == 0)
        code = W.DWORD()
        self.check(self.k.GetExitCodeProcess(process, ctypes.byref(code)))
        return code.value

    def active(self, job):
        info = (W.DWORD * 12)()
        # BASIC_ACCOUNTING_INFORMATION: four64bit times then4DWORD counters.
        self.check(
            self.k.QueryInformationJobObject(job, 1, info, ctypes.sizeof(info), None)
        )
        return info[10]


class OwnedTree:
    def __init__(self, command, cwd, env, api=None):
        self.api = api or WindowsApi()
        self.job = self.process = self.thread = None
        self.stream = None
        self.force_killed = False
        self.failure = None
        self.pid = None
        self.assigned = False
        self.root_exit_confirmed = False
        self.tree_exit_confirmed = False
        self.cleanup_unknown = False
        self.containment_attempts = 0
        self.kill_lock = threading.Lock()
        self.closed = False
        try:
            self.job = self.api.job()
            self.process, self.thread, self.pid, read_handle = self.api.spawn_suspended(
                command, cwd, env
            )
            self.stream = self.api.adopt_read(read_handle)
            if getattr(self.api, "spawn_cleanup_unknown", False):
                self.unknown("SPAWN_HANDLE_CLEANUP_UNKNOWN")
                raise Error("SPAWN_HANDLE_CLEANUP_UNKNOWN")
            self.api.assign(self.job, self.process)
            self.assigned = True
            self.api.resume(self.thread)
            self.api.close(self.thread)
            self.thread = None
        except BaseException as original:
            if getattr(self.api, "spawn_cleanup_unknown", False):
                self.unknown("SPAWN_HANDLE_CLEANUP_UNKNOWN")
            try:
                self.contain()
                # One explicitly bounded second attempt on a still-unconfirmed root/tree.
                # force_killed is policy evidence, NEVER a guard against necessary cleanup.
                if not self.tree_exit_confirmed:
                    self.contain()
                if self.stream:
                    try:
                        self.stream.close()
                    except BaseException:
                        self.unknown("PIPE_CLOSE_UNKNOWN")
            finally:
                self.close()
            attach_cleanup(original, self.snapshot())
            raise

    def poll(self):
        return self.api.poll(self.process)

    def contain(self):
        with self.kill_lock:
            if self.tree_exit_confirmed or self.containment_attempts >= 2:
                return
            self.force_killed = True
            self.containment_attempts += 1
            try:
                if self.process is None:
                    self.tree_exit_confirmed = True
                    return
                if self.assigned:
                    self.api.kill(self.job)
                else:
                    self.api.kill_suspended(self.process)
            except BaseException:
                self.unknown(
                    "JOB_KILL_UNKNOWN" if self.assigned else "ROOT_TERMINATION_UNKNOWN"
                )
            # Even a failed termination call cannot skip independent exit observation.
            try:
                self.wait(0.25)
            except BaseException:
                self.unknown(
                    "TREE_EXIT_UNKNOWN" if self.assigned else "ROOT_EXIT_UNKNOWN"
                )

    def unknown(self, code):
        self.cleanup_unknown = True
        if self.failure is None:
            self.failure = code

    def snapshot(self):
        return {
            "ownedPid": self.pid,
            "rootAssigned": self.assigned,
            "rootExitConfirmed": self.root_exit_confirmed,
            "treeExitConfirmed": self.tree_exit_confirmed,
            "containmentAttempts": self.containment_attempts,
            "cleanupUnknown": self.cleanup_unknown,
            "forceKilled": self.force_killed,
            "failure": self.failure,
        }

    def wait(self, timeout):
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            code = self.poll()
            if code is not None:
                self.root_exit_confirmed = True
                if not self.assigned or self.api.active(self.job) == 0:
                    self.tree_exit_confirmed = True
                    return code
            time.sleep(0.01)
        raise Error("OWNED_TREE_WAIT_TIMEOUT")

    def close(self):
        if self.closed:
            return
        self.closed = True
        for name in ("thread", "process", "job"):
            handle = getattr(self, name)
            try:
                if handle:
                    self.api.close(handle)
            except BaseException:
                self.unknown("HANDLE_CLOSE_UNKNOWN")
            setattr(self, name, None)


def attach_cleanup(failure, evidence):
    """Preserve the original exception object; expose sanitized ownership even before publication."""
    try:
        failure.native_cleanup = evidence
    except BaseException:
        pass  # Catastrophic exception objects may not permit evidence attachment


class BoundedReader:
    def __init__(self, stream, output, contain, limit=LIMIT):
        self.stream = stream
        self.output = output
        self.contain = contain
        self.limit = limit
        self.saved = 0
        self.failure = None
        self.thread = threading.Thread(
            target=self._read, name="bounded-owned-output", daemon=True
        )

    def fail(self, code):
        if self.failure is None:
            self.failure = code
            try:
                self.contain()
            except BaseException:
                self.failure = "OUTPUT_CONTAINMENT_UNKNOWN"

    def _read(self):
        try:
            while True:
                chunk = self.stream.read(8192)
                if not chunk:
                    break
                if len(chunk) > 8192:
                    self.fail("PIPE_CHUNK_INVALID")
                    chunk = chunk[:8192]
                remaining = self.limit - self.saved
                if remaining > 0:
                    persisted = chunk[:remaining]
                    self.output.write(persisted)
                    self.output.flush()
                    self.saved += len(persisted)
                if len(chunk) > remaining:
                    self.fail("OUTPUT_QUOTA_EXCEEDED")
        except BaseException:
            self.fail("OUTPUT_READER_FAILED")
        finally:
            try:
                self.stream.close()
            except BaseException:
                self.fail("PIPE_CLOSE_FAILED")

    def start(self):
        self.thread.start()

    def finish(self):
        self.thread.join(3)
        if self.thread.is_alive():
            self.fail("OUTPUT_READER_UNKNOWN")
            self.thread.join(1)
        if self.failure:
            raise Error(self.failure)


class LoggedProcess:
    def __init__(self, command, cwd, env, path):
        self.tree = OwnedTree(command, cwd, env)
        self.log = None
        try:
            self.log = path.open("xb")
            self.reader = BoundedReader(self.tree.stream, self.log, self.tree.contain)
            self.reader.start()
        except BaseException as original:
            for action in (
                self.tree.contain,
                self.tree.contain,
                lambda: self.log.close() if self.log else None,
                self.tree.stream.close,
                self.tree.close,
            ):
                try:
                    action()
                except BaseException:
                    self.tree.unknown("PROCESS_PUBLICATION_CLEANUP_UNKNOWN")
            attach_cleanup(original, self.tree.snapshot())
            raise
        self.pid = self.tree.pid

    def poll(self):
        if self.reader.failure:
            raise Error(self.reader.failure)
        return self.tree.poll()

    def wait(self, timeout):
        try:
            code = self.tree.wait(timeout)
            self.reader.finish()
            if self.tree.force_killed or self.tree.failure:
                raise Error("FORCED_OR_UNKNOWN_TREE")
            return code
        except BaseException:
            self.tree.contain()
            raise

    def finish(self):
        first = None
        try:
            try:
                self.tree.wait(0.25)
            except BaseException:
                self.tree.contain()
            try:
                self.tree.wait(3)
            except BaseException:
                self.tree.unknown("TREE_CLEANUP_UNKNOWN")
                self.tree.contain()
            try:
                self.reader.finish()
            except BaseException as failure:
                first = failure
            finally:
                try:
                    self.log.close()
                except BaseException:
                    self.tree.unknown("LOG_CLOSE_UNKNOWN")
        finally:
            self.tree.close()
        if first:
            raise first


def probe(command, cwd):
    # Narrow trusted local git/java/python probes; use same tree and active output limit.
    import tempfile

    with tempfile.TemporaryDirectory(
        dir=APPROVED, prefix="connector-probe-"
    ) as directory:
        path = Path(directory) / "probe.log"
        process = LoggedProcess(command, cwd, os.environ.copy(), path)
        original = None
        try:
            code = process.wait(10)
            raw = path.read_bytes()
            if code:
                raise Error("LOCAL_PROBE_FAILED")
            return raw
        except BaseException as failure:
            original = failure
            raise
        finally:
            try:
                process.finish()
            except BaseException:
                if original is None:
                    raise
            if original is not None:
                attach_cleanup(original, process.tree.snapshot())


def safe_path(path: Path, *, exists: bool = True) -> Path:
    if not path.is_absolute() or ".." in path.parts:
        raise Error("PATH_NOT_ABSOLUTE_CANONICAL")
    for part in (path, *path.parents):
        if part.exists() or part.is_symlink():
            info = part.lstat()
            if part.is_symlink() or getattr(info, "st_file_attributes", 0) & 0x400:
                raise Error("PATH_ALIAS_OR_REPARSE")
    if exists and not path.exists():
        raise Error("PATH_MISSING")
    if os.path.normcase(str(path)) != os.path.normcase(str(path.resolve())):
        raise Error("PATH_ALIAS")
    return path


def bounded(path: Path, limit: int = LIMIT) -> bytes:
    safe_path(path)
    if not path.is_file() or not 0 <= path.stat().st_size <= limit:
        raise Error("FILE_LIMIT")
    with path.open("rb") as stream:
        data = stream.read(limit + 1)
    if len(data) > limit:
        raise Error("FILE_LIMIT")
    return data


def git(root: Path, *args: str) -> str:
    return probe(["git", *args], root).decode("utf-8", "strict").strip()


def clean(root: Path, pin: str | None = None) -> str:
    head = git(root, "rev-parse", "HEAD")
    if not re.fullmatch(r"[0-9a-f]{40}", head) or pin is not None and head != pin:
        raise Error("HEAD_MISMATCH")
    if git(root, "status", "--porcelain=v1", "--untracked-files=all"):
        raise Error("REPOSITORY_NOT_COMMITTED_CLEAN")
    return head


def inventory(agent: Path, forge: Path) -> dict[str, Path]:
    # Reuse only the old helper's declared file inventory, never its run/main or old pin.
    files = helper.source_files(agent, forge)
    files.pop(
        "java:src/test/java/io/github/jinyiwei2012/luosandbox/transport/PostAuthV2LoopbackTest.java"
    )
    server = helper.server_root(agent)
    for name in ("epoch_platform", "epoch_worker"):
        files[f"python:src/adapter/minecraft/{name}.py"] = (
            server / f"src/adapter/minecraft/{name}.py"
        )
    for name in (
        "V2PeerConnector",
        "V2JdkWebSocketAdapter",
        "V2OwnerMailbox",
        "V2SessionSendAdapter",
    ):
        key = f"java:src/main/java/io/github/jinyiwei2012/luosandbox/transport/{name}.java"
        files[key] = forge / key.removeprefix("java:")
    files[f"java:src/test/java/{CLASS.replace('.', '/')}.java"] = (
        forge / f"src/test/java/{CLASS.replace('.', '/')}.java"
    )
    files["runner:scripts/minecraft/verify_v2_connector_loopback.py"] = Path(
        __file__
    ).absolute()
    files["runner-test:scripts/tests/test_v2_connector_loopback.py"] = (
        Path(__file__).absolute().parents[1] / "tests/test_v2_connector_loopback.py"
    )
    return files


def snapshot(files: dict[str, Path]) -> dict[str, str]:
    return {
        key: hashlib.sha256(bounded(path)).hexdigest() for key, path in files.items()
    }


def status(port: int, endpoint: str = "test-status") -> dict:
    if (
        type(port) is not int
        or not 1 <= port <= 65535
        or endpoint
        not in {"test-status", "test-route-status", "test-headless-admission-status"}
    ):
        raise Error("STATUS_ENDPOINT_INVALID")
    with helper._opener().open(
        f"http://127.0.0.1:{port}/minecraft/v2/{endpoint}", timeout=1
    ) as response:
        return helper._decode_json_object(
            response.read(65_537), 65_536, "STATUS_INVALID"
        )


def observational(value: dict, route: dict, mode: dict) -> dict:
    helper.validate_status(value)
    if value["pendingCount"] < 0 or value["completedSessions"] < 0:
        raise Error("STATUS_COUNTER_INVALID")
    if set(value["activeRoles"]) - helper.REQUIRED_ROLES or len(
        set(value["activeRoles"])
    ) != len(value["activeRoles"]):
        raise Error("STATUS_ROLE_INVALID")
    if (
        mode != {"mode": "OBSERVATIONAL"}
        or route.get("coordinatorPresent") is not False
    ):
        raise Error("NOT_OBSERVATIONAL")
    return {
        "applicationFrames": "NOT_MEASURED",
        "hubApplicationReady": "NOT_EXPOSED",
        "hubAckCount": "NOT_EXPOSED",
        "hubHeartbeatCount": "NOT_EXPOSED",
        "workerJobs": "NOT_MEASURED",
        "workerConstruction": "SOURCE_BACKED_ABSENT_IN_OBSERVATIONAL_BRANCH",
        "mode": "OBSERVATIONAL",
        "coordinatorPresent": False,
        "activeRoles": value["activeRoles"],
        "pendingCount": value["pendingCount"],
        "completedSessions": value["completedSessions"],
    }


def junit(path: Path, started_ns: int, nonce: str) -> bytes:
    raw = bounded(path, 1_048_576)
    if (
        path.stat().st_mtime_ns < started_ns
        or b"<!DOCTYPE" in raw.upper()
        or b"<!ENTITY" in raw.upper()
    ):
        raise Error("JUNIT_STALE_OR_UNSAFE")
    try:
        root = ET.fromstring(raw)
    except ET.ParseError as exc:
        raise Error("JUNIT_INVALID") from exc
    cases = root.findall("testcase")
    if (
        root.get("name") != CLASS
        or {c.get("name") for c in cases} != CASES
        or len(cases) != 3
    ):
        raise Error("JUNIT_CASES_INVALID")
    if any(
        root.get(key) != value
        for key, value in {
            "tests": "3",
            "failures": "0",
            "errors": "0",
            "skipped": "0",
        }.items()
    ):
        raise Error("JUNIT_NOT_CLEAN")
    if any(
        c.find(tag) is not None
        for c in cases
        for tag in ("failure", "error", "skipped")
    ):
        raise Error("JUNIT_CASE_NOT_CLEAN")
    output = "".join(n.text or "" for n in root.findall("system-out"))
    if f"CONNECTOR_RUN_COMPLETE {nonce}" not in output:
        raise Error("JUNIT_RUN_IDENTITY_MISSING")
    return raw


def runtime_versions(python: Path) -> dict:
    code = "import sys,importlib.metadata as m,json; print(json.dumps({'python':sys.version,'uvicorn':m.version('uvicorn'),'websockets':m.version('websockets'),'wsproto':m.version('wsproto')}))"
    raw = probe([str(python), "-c", code], python.parent)
    if len(raw) > 4096:
        raise Error("RUNTIME_VERSIONS_UNAVAILABLE")
    return helper._decode_json_object(raw, 4096, "RUNTIME_VERSIONS_INVALID")


def command(forge: Path) -> list[str]:
    return [
        str(forge / "gradlew.bat"),
        "test",
        "--tests",
        CLASS,
        "--offline",
        "--no-daemon",
        "--rerun-tasks",
        "--max-workers=2",
    ]


def gates(
    agent: Path, sandbox: Path, python: Path, output: Path
) -> tuple[Path, dict, dict, str]:
    for path in (agent, sandbox, python):
        safe_path(path)
    safe_path(output, exists=False)
    safe_path(APPROVED)
    if not output.is_relative_to(APPROVED) or output == APPROVED or output.exists():
        raise Error("OUTPUT_NOT_EXCLUSIVE_APPROVED")
    if not output.parent.is_dir() or not python.is_file() or os.name != "nt":
        raise Error("PLATFORM_OR_EXECUTABLE_INVALID")
    clean(agent, UPSTREAM)
    sandbox_head = clean(sandbox)
    forge = sandbox / "bridge/forge"
    if not (forge / "gradlew.bat").is_file() or (forge / XML).exists():
        raise Error("WRAPPER_MISSING_OR_PREEXISTING_JUNIT")
    files = inventory(agent, forge)
    before = snapshot(files)
    return forge, files, before, sandbox_head


def manifest(output: Path) -> None:
    rows = []
    for path in sorted(output.rglob("*")):
        if path.is_file() and path.name != "artifacts.sha256":
            rows.append(
                f"{hashlib.sha256(bounded(path)).hexdigest()}  {path.relative_to(output)}"
            )
    (output / "artifacts.sha256").write_text("\n".join(rows) + "\n", encoding="utf-8")


def run(agent: Path, sandbox: Path, python: Path, output: Path) -> dict:
    forge, files, before, sandbox_head = gates(agent, sandbox, python, output)
    output.mkdir()  # All provenance/output gates precede the first filesystem/process/network effect.
    process = None
    java_process = None
    force_killed = False
    result = {
        "status": "BLOCKED",
        "scope": "OBSERVATIONAL_CONNECTOR_ONLY",
        "networkExecuted": False,
    }
    nonce, token = secrets.token_hex(16), secrets.token_hex(32)
    receipt = output / "shutdown-receipt.json"
    try:
        versions = runtime_versions(python)
        jdk = probe(["java", "-XshowSettings:properties", "-version"], sandbox)
        if len(jdk) > 65_536:
            raise Error("JDK_VERSION_UNAVAILABLE")
        (output / "jdk.txt").write_bytes(jdk)
        port = helper.free_port()
        environment = os.environ.copy()
        environment.update(
            {
                "PYTHONPATH": str(helper.server_root(agent)),
                "PYTHONDONTWRITEBYTECODE": "1",
                "LUOSANDBOX_LOOPBACK_MODE": "OBSERVATIONAL",
                "LUOSANDBOX_LOOPBACK_SHUTDOWN_TOKEN": token,
                "LUOSANDBOX_LOOPBACK_SHUTDOWN_NONCE": nonce,
                "LUOSANDBOX_LOOPBACK_SHUTDOWN_RECEIPT": str(receipt),
            }
        )
        if process is None:
            process = LoggedProcess(
                helper.server_command(python, agent, port),
                cwd=helper.server_root(agent),
                env=environment,
                path=output / "hub.log",
            )
            result.update(networkExecuted=True, ownedPid=process.pid)
            deadline = time.monotonic() + 20
            while True:
                if process.poll() is not None:
                    raise Error("OWNED_PROCESS_EARLY_EXIT")
                try:
                    ready = status(port)
                    helper.validate_loaded(ready, before, files)
                    observational(
                        ready,
                        status(port, "test-route-status"),
                        status(port, "test-headless-admission-status"),
                    )
                    break
                except OSError:
                    if time.monotonic() >= deadline:
                        raise Error("STARTUP_TIMEOUT")
                    time.sleep(0.1)
            java_env = os.environ.copy()
            java_env.update(
                {
                    "RUN_V2_CONNECTOR_LOOPBACK": "1",
                    "V2_CONNECTOR_LOOPBACK_URL": f"ws://127.0.0.1:{port}/minecraft/v2/ws",
                    "V2_CONNECTOR_RUN_NONCE": nonce,
                    "V2_CONNECTOR_EVIDENCE_DIR": str(output),
                    "GRADLE_USER_HOME": str(sandbox / ".gradle-user-home"),
                    "LUOSANDBOX_PYTHON_INTEROP_DIR": str(
                        sandbox / ".gradle-user-home/interop/python-to-java-v1"
                    ),
                }
            )
            started = time.time_ns()
            java_process = LoggedProcess(
                command(forge), forge, java_env, output / "gradle.log"
            )
            if java_process.wait(120):
                raise Error("JAVA_FAILED")
            raw = junit(forge / XML, started, nonce)
            (output / "junit.xml").write_bytes(raw)
            artifacts = list((forge / "build/classes/java").rglob("*.class")) + list(
                (forge / "build/libs").glob("*.jar")
            )
            (output / "build-artifacts.sha256").write_text(
                "\n".join(
                    f"{hashlib.sha256(bounded(path, 134_217_728)).hexdigest()}  {path.relative_to(forge)}"
                    for path in artifacts
                )
                + "\n",
                encoding="utf-8",
            )
            final = status(port)
            helper.validate_loaded(final, before, files)
            observed = observational(
                final,
                status(port, "test-route-status"),
                status(port, "test-headless-admission-status"),
            )
            if (
                final["activeRoles"]
                or final["pendingCount"] != 0
                or final["completedSessions"] != 3
            ):
                raise Error("HUB_NOT_DRAINED")
            if process.poll() is not None:
                raise Error("OWNED_PROCESS_LOST")
            helper.request_shutdown(port, token)
            exit_code = process.wait(timeout=15)
            if exit_code != 0:
                raise Error("SHUTDOWN_EXIT_FAILED")
            shutdown = helper.validate_shutdown_receipt(receipt, nonce)
            if shutdown["epochReopen"] is not None:
                raise Error("UNEXPECTED_WORKER_ACTIVITY")
            if (
                snapshot(files) != before
                or clean(agent, UPSTREAM) != UPSTREAM
                or clean(sandbox) != sandbox_head
            ):
                raise Error("SOURCE_OR_REPOSITORY_DRIFT")
            result.update(
                status="PASS",
                hub=observed,
                javaExit=0,
                pythonExit=exit_code,
                shutdownReceipt=shutdown,
                sandboxHead=sandbox_head,
                upstreamHead=UPSTREAM,
                sourceHashes=before,
                sourceClosure="DECLARED_NOT_ALL_JDK_OR_INTERPRETER_DEPENDENCIES",
                readinessAttribution="NO_PID_OR_NONCE_IN_READINESS; CLOSED_BY_OWNED_EXIT_AND_AUTHENTICATED_RECEIPT",
                resourceQuiescence="NOT_CLAIMED",
            )
            result["runtimeVersions"] = versions
    except (
        helper.LoopbackError,
        OSError,
        ValueError,
        subprocess.SubprocessError,
    ) as exc:
        result["failure"] = (
            exc.code
            if isinstance(exc, helper.LoopbackError)
            else "BOUNDED_EXECUTION_FAILED"
        )
        native = getattr(exc, "native_cleanup", None)
        if native is not None:
            result["nativeCleanup"] = native
            force_killed = force_killed or native["forceKilled"]
    finally:
        for owned in (java_process, process):
            if owned is None:
                continue
            try:
                owned.finish()
            except BaseException:
                result["status"] = "BLOCKED"
                result["cleanup"] = "UNKNOWN"
            force_killed = force_killed or owned.tree.force_killed
            if owned.tree.failure or owned.reader.failure:
                result["status"] = "BLOCKED"
            if isinstance(owned.tree, OwnedTree):
                result.setdefault("ownedTreeCleanup", []).append(owned.tree.snapshot())
        result["forceKilled"] = force_killed
        if force_killed:
            result["status"] = "BLOCKED"
        (output / "report.json").write_text(
            json.dumps(result, sort_keys=True) + "\n", encoding="utf-8"
        )
        manifest(output)
    return result


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("agent", "sandbox", "python", "output"):
        parser.add_argument(f"--{name}", required=True, type=Path)
    args = parser.parse_args(argv)
    try:
        result = run(args.agent, args.sandbox, args.python, args.output)
        print(json.dumps({"status": result["status"], "scope": result["scope"]}))
        return 0 if result["status"] == "PASS" else 2
    except helper.LoopbackError as exc:
        evidence = {"status": "BLOCKED", "failure": exc.code}
        if getattr(exc, "native_cleanup", None) is not None:
            evidence["nativeCleanup"] = exc.native_cleanup
        print(json.dumps(evidence))
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
