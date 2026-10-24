"""Explicit local-only native smoke. Never invokes the network runner or Gradle."""

from __future__ import annotations

import ctypes
import hashlib
import json
import os
import platform
import struct
import sys
import tempfile
import time
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "minecraft"))
import verify_v2_connector_loopback as runner

pytestmark = [
    pytest.mark.skipif(os.name != "nt", reason="Windows Job smoke requires Windows"),
    pytest.mark.skipif(
        os.environ.get("RUN_V2_CONNECTOR_NATIVE_SMOKE") != "1",
        reason="explicit RUN_V2_CONNECTOR_NATIVE_SMOKE=1 required; local nonnetwork only",
    ),
]
APPROVED = Path(r"C:\Users\Administrator\AppData\Local\Temp\opencode")


class AuditApi(runner.WindowsApi):
    """Instrumentation only; all native operations delegate to the actual runner."""

    def __init__(self):
        super().__init__()
        self.events = []
        self.structure_sizes = {}

    def check(self, ok):
        # Observe actual runner-local ctypes instances, not a duplicate ABI declaration.
        caller = sys._getframe(1)
        try:
            for name in ("info", "startup", "pi", "sa"):
                value = caller.f_locals.get(name)
                if isinstance(value, ctypes.Structure):
                    self.structure_sizes[type(value).__name__] = ctypes.sizeof(value)
        finally:
            del caller
        super().check(ok)

    def assign(self, job, process):
        super().assign(job, process)
        self.events.append("assigned")

    def resume(self, thread):
        assert self.events == ["assigned"]
        super().resume(thread)
        self.events.append("resumed")


@pytest.fixture(scope="module")
def evidence():
    directory = Path(
        tempfile.mkdtemp(prefix="connector-native-cases-", dir=APPROVED)
    ).resolve()
    report = {
        "scope": "LOCAL_NONNETWORK_NATIVE_SMOKE",
        "networkExecuted": False,
        "directory": str(directory),
        "windows": platform.platform(),
        "windowsVersion": list(sys.getwindowsversion()),
        "architecture": platform.machine(),
        "python": sys.version,
        "pythonBits": struct.calcsize("P") * 8,
        "ctypes": {
            "HANDLE": ctypes.sizeof(ctypes.c_void_p),
            "DWORD": ctypes.sizeof(ctypes.c_uint32),
        },
        "cases": [],
        "overallDeadlineSeconds": 45,
        "startedMonotonic": time.monotonic(),
        "resourceQuiescence": "NOT_CLAIMED",
    }
    yield directory, report
    (directory / "smoke.json").write_text(
        json.dumps(report, indent=2), encoding="utf-8"
    )
    rows = [
        f"{hashlib.sha256(p.read_bytes()).hexdigest()}  {p.name}"
        for p in directory.iterdir()
        if p.is_file()
    ]
    (directory / "artifacts.sha256").write_text(
        "\n".join(rows) + "\n", encoding="utf-8"
    )
    print("NATIVE_SMOKE_EVIDENCE " + str(directory))


def execute(evidence, name, command, *, quota=False, child_file=None):
    directory, report = evidence
    row = {
        "name": name,
        "command": command,
        "escapedCommand": runner.subprocess.list2cmdline(command),
        "status": "BLOCKED",
    }
    report["cases"].append(row)
    process = None
    api = AuditApi()
    start = time.monotonic()
    # Inject instrumentation without substituting any native implementation.
    original = runner.WindowsApi
    runner.WindowsApi = lambda: api
    try:
        process = runner.LoggedProcess(
            command, directory, os.environ.copy(), directory / f"{name}.log"
        )
        row["rootPid"] = process.pid
        if child_file is not None:
            deadline = time.monotonic() + 5
            while not child_file.exists() and time.monotonic() < deadline:
                if process.poll() is not None:
                    raise AssertionError("root exited before child handshake")
                time.sleep(0.01)
            assert child_file.exists()
            assert child_file.stat().st_size < 1024
            pid = int(child_file.read_text(encoding="ascii"))
            row["childPid"] = pid
            # An exact, read-only process handle to the PID reported by our owned root.
            kernel = api.k
            kernel.OpenProcess.argtypes = [
                ctypes.c_uint32,
                ctypes.c_int,
                ctypes.c_uint32,
            ]
            kernel.OpenProcess.restype = ctypes.c_void_p
            child = kernel.OpenProcess(0x100000 | 0x1000, False, pid)
            assert child
            try:
                process.tree.contain()
                assert process.tree.tree_exit_confirmed
                assert kernel.WaitForSingleObject(child, 3000) == 0
                row["childExitConfirmed"] = True
            finally:
                api.close(child)
        if quota:
            with pytest.raises(runner.Error):
                process.wait(8)
            assert process.reader.failure == "OUTPUT_QUOTA_EXCEEDED"
            assert process.tree.force_killed
            row["underlyingRunStatus"] = "FAIL_FORCE_KILLED_EXPECTED_NEGATIVE_PATH"
        elif child_file is not None:
            with pytest.raises(runner.Error):
                process.wait(5)
        else:
            assert process.wait(5) == 0
            assert not process.tree.force_killed
        assert process.tree.api.active(process.tree.job) == 0
        assert not process.reader.thread.is_alive()
        assert process.reader.saved <= runner.LIMIT
        row.update(
            jobActive=0,
            readerAlive=False,
            persistedBytes=process.reader.saved,
            outputLimit=runner.LIMIT,
            ownership=process.tree.snapshot(),
            nativeEvents=api.events,
            actualRunnerStructureSizes=api.structure_sizes,
        )
        assert api.events == ["assigned", "resumed"]
        row["status"] = "PASS"
    except BaseException as failure:
        row["failureType"] = type(failure).__name__
        row["nativeCleanup"] = getattr(failure, "native_cleanup", None)
        raise
    finally:
        runner.WindowsApi = original
        if process is not None:
            try:
                process.finish()
            except runner.Error:
                if not quota:
                    row["status"] = "BLOCKED"
                    raise
            finally:
                row["finalOwnership"] = process.tree.snapshot()
                row["handlesClosed"] = process.tree.closed
                if process.tree.cleanup_unknown or not process.tree.tree_exit_confirmed:
                    row["status"] = "BLOCKED"
        row["durationSeconds"] = time.monotonic() - start
        (directory / "progress.json").write_text(
            json.dumps(report, indent=2), encoding="utf-8"
        )
        assert row["durationSeconds"] < 25, "bounded native case deadline exceeded"
        assert (
            time.monotonic() - report["startedMonotonic"] < 45
        ), "overall smoke deadline exceeded"
        assert (
            row["status"] == "PASS"
        ), "native cleanup not confirmed; see exact PID evidence"


def test_01_natural_root_pipe(evidence):
    execute(
        evidence,
        "natural",
        [sys.executable, "-c", "print('LOCAL_NATIVE_ROOT_OK', flush=True)"],
    )


def test_02_owned_child_tree(evidence):
    path = evidence[0] / "child.pid"
    code = (
        "import subprocess,sys,pathlib,time; "
        "child=subprocess.Popen([sys.executable,'-c','import time; time.sleep(15)']); "
        f"pathlib.Path({str(path)!r}).write_text(str(child.pid),encoding='ascii'); "
        "print('OWNED_CHILD_STARTED',flush=True); time.sleep(15)"
    )
    execute(evidence, "tree", [sys.executable, "-c", code], child_file=path)


def test_03_spaced_bat_arguments(evidence):
    directory = evidence[0] / "spaced script directory"
    directory.mkdir()
    batch = directory / "native arguments.bat"
    batch.write_text(
        '@echo off\r\n"%~1" -c "import sys; assert sys.argv[1:]==[\'value with spaces\',\'literal\']; print(\'BATCH_ARGS_OK\')" "%~2" "%~3"\r\n',
        encoding="ascii",
    )
    execute(
        evidence, "batch", [str(batch), sys.executable, "value with spaces", "literal"]
    )


def test_04_real_output_quota(evidence):
    execute(
        evidence,
        "quota",
        [
            sys.executable,
            "-c",
            "import os,time; data=b'x'*8192; [os.write(1,data) for _ in range(300)]; time.sleep(15)",
        ],
        quota=True,
    )
