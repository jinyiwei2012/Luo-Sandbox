"""Run a test-only offline ownership probe against the read-only upstream epoch store.

This is evidence about thread/resource behavior, not a production worker, ACL
verification, power-loss proof, coordinator integration, or capability enablement.
"""
from __future__ import annotations

import argparse
import contextlib
import hashlib
import json
import os
import subprocess
import sys
import tempfile
import threading
import types
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from typing import Any


UPSTREAM_ROOT = Path(
    r"C:\Agent-LuoTianyi\.slim\worktrees\minecraft-observation-adapter"
)
SERVER_ROOT = UPSTREAM_ROOT / "server"
EXPECTED_UPSTREAM_HEAD = "2078976ac88c405d3c4a8e4b4bbeec605b6c99d3"
EPOCH_STORE_RELATIVE = "src/adapter/minecraft/epoch_store.py"
EPOCH_PLATFORM_RELATIVE = "src/adapter/minecraft/epoch_platform.py"
TOOL_RELATIVE = "scripts/minecraft/verify_epoch_worker_ownership.py"
TEST_RELATIVE = "scripts/tests/test_epoch_worker_ownership.py"
PROVISION = "00000000-0000-4000-8000-000000000001"
BODY = "00000000-0000-4000-8000-000000000002"


class ProbeBlocked(RuntimeError):
    pass


def _source_bytes(path: Path) -> bytes:
    try:
        if path.is_symlink() or not path.is_file():
            raise ProbeBlocked("required_source_missing_or_not_regular")
        return path.read_bytes()
    except OSError as error:
        raise ProbeBlocked("source_gate_failed") from error


def _resolve(path: Path) -> Path:
    try:
        return path.resolve()
    except OSError as error:
        raise ProbeBlocked("source_gate_failed") from error


@contextlib.contextmanager
def loaded_upstream():
    """Freshly execute frozen bytes and restore modules; exclusive single-owner use only.

    This temporarily replaces global ``sys.modules`` entries and is not a
    thread-safe production import manager.
    """
    expected_store = _resolve(SERVER_ROOT / EPOCH_STORE_RELATIVE)
    expected_platform = _resolve(SERVER_ROOT / EPOCH_PLATFORM_RELATIVE)
    try:
        if not SERVER_ROOT.is_dir():
            raise ProbeBlocked("upstream_server_root_missing")
        actual_head = subprocess.check_output(
            ["git", "-C", str(UPSTREAM_ROOT), "rev-parse", "HEAD"],
            text=True, stderr=subprocess.DEVNULL, timeout=10,
        ).strip()
    except (OSError, subprocess.SubprocessError) as error:
        raise ProbeBlocked("upstream_identity_unavailable") from error
    if actual_head != EXPECTED_UPSTREAM_HEAD:
        raise ProbeBlocked("upstream_head_mismatch")
    store_bytes = _source_bytes(expected_store)
    platform_bytes = _source_bytes(expected_platform)
    root = _resolve(Path(__file__)).parents[2]
    tool_bytes = _source_bytes(root / TOOL_RELATIVE)
    test_bytes = _source_bytes(root / TEST_RELATIVE)
    hashes = {
        TOOL_RELATIVE: hashlib.sha256(tool_bytes).hexdigest(),
        TEST_RELATIVE: hashlib.sha256(test_bytes).hexdigest(),
        str(expected_store): hashlib.sha256(store_bytes).hexdigest(),
        str(expected_platform): hashlib.sha256(platform_bytes).hexdigest(),
    }
    package_paths = {
        "src": SERVER_ROOT / "src",
        "src.adapter": SERVER_ROOT / "src" / "adapter",
        "src.adapter.minecraft": SERVER_ROOT / "src" / "adapter" / "minecraft",
    }
    module_sources = {
        "src.adapter.minecraft.epoch_store": (expected_store, store_bytes),
        "src.adapter.minecraft.epoch_platform": (expected_platform, platform_bytes),
    }
    affected = tuple(package_paths) + tuple(module_sources)
    missing = object()
    previous = {name: sys.modules.get(name, missing) for name in affected}
    try:
        for name, path in package_paths.items():
            package = types.ModuleType(name)
            package.__path__ = [str(path)]
            package.__package__ = name
            sys.modules[name] = package
        loaded = {}
        for name, (path, source) in module_sources.items():
            module = types.ModuleType(name)
            module.__file__ = str(path)
            module.__package__ = name.rpartition(".")[0]
            sys.modules[name] = module
            try:
                code = compile(source, str(path), "exec")
                exec(code, module.__dict__)
            except (OSError, UnicodeError) as error:
                raise ProbeBlocked("upstream_module_execution_failed") from error
            loaded[name] = module
        yield (loaded["src.adapter.minecraft.epoch_store"],
               loaded["src.adapter.minecraft.epoch_platform"], hashes)
    finally:
        for name in reversed(affected):
            original = previous[name]
            if original is missing:
                sys.modules.pop(name, None)
            else:
                sys.modules[name] = original


class RecordingLock:
    def __init__(self, *, fail_close: bool = False) -> None:
        self.acquire_thread = threading.get_ident()
        self.close_threads: list[int] = []
        self.closed = False
        self.fail_close = fail_close

    def close(self) -> None:
        self.close_threads.append(threading.get_ident())
        if self.fail_close:
            raise RuntimeError("injected test-double close failure")
        self.closed = True


class TestDoublePlatform:
    """TEST DOUBLE only: verified=True is not production ACL/VFS evidence."""

    def __init__(self, store_module: Any, *, fail_close: bool = False) -> None:
        self.store_module = store_module
        self.fail_close = fail_close
        self.lock: RecordingLock | None = None

    def capability(self, _directory: Path):
        return self.store_module.PlatformCapability(
            True, "TEST_DOUBLE_ONLY_NOT_PRODUCTION_ACL_EVIDENCE"
        )

    def acquire_lock(self, _lock_path: Path) -> RecordingLock:
        self.lock = RecordingLock(fail_close=self.fail_close)
        return self.lock


class ConnectionProxy:
    def __init__(self, connection: Any, events: list[dict[str, Any]], kind: str) -> None:
        self._connection = connection
        self._events = events
        self._kind = kind
        self._events.append({"event": "connection_create", "kind": kind,
                             "thread": threading.get_ident()})

    def execute(self, *args, **kwargs):
        return self._connection.execute(*args, **kwargs)

    def close(self) -> None:
        self._events.append({"event": "connection_close", "kind": self._kind,
                             "thread": threading.get_ident()})
        self._connection.close()


class ScopedConnectionInstrumentation:
    def __init__(self, module: Any) -> None:
        self.module = module
        self.events: list[dict[str, Any]] = []
        self.original_create = module._connect_create
        self.original_rw = module._connect_rw

    def __enter__(self):
        def create(path):
            return ConnectionProxy(self.original_create(path), self.events, "create")

        def rw(path):
            return ConnectionProxy(self.original_rw(path), self.events, "rw")

        self.module._connect_create = create
        self.module._connect_rw = rw
        return self

    def __exit__(self, *_exc) -> None:
        self.module._connect_create = self.original_create
        self.module._connect_rw = self.original_rw


def _ready(store_module: Any, candidate: Any):
    return store_module.VerifiedReadyContext(
        candidate.provision_id, candidate.body_id, candidate.expected_last_epoch,
        candidate.expected_generation, candidate.next_epoch, "test-worker-evidence",
    )


def lifecycle_probe(store_module: Any, root: Path) -> dict[str, Any]:
    platform = TestDoublePlatform(store_module)
    operation_threads: list[tuple[str, int]] = []
    with ScopedConnectionInstrumentation(store_module) as instrumentation:
        with ThreadPoolExecutor(max_workers=1, thread_name_prefix="epoch-owner") as executor:
            def call(name, operation):
                def run():
                    operation_threads.append((name, threading.get_ident()))
                    return operation()
                return executor.submit(run).result(timeout=10)

            store = call("provision", lambda: store_module.EpochStore.provision(
                root, PROVISION, BODY, platform=platform))
            candidate = call("read_candidate", store.read_candidate)
            committed = call("commit_ready", lambda: store.commit_ready(
                candidate, _ready(store_module, candidate)))
            readback = call("readback", store.read_candidate)
            call("close", store.close)
    worker_threads = {thread for _, thread in operation_threads}
    connection_threads = {event["thread"] for event in instrumentation.events}
    lock = platform.lock
    return {
        "status": "PASSED_TEST_DOUBLE",
        "operationThreads": operation_threads,
        "singleWorkerThread": len(worker_threads) == 1,
        "connectionEvents": instrumentation.events,
        "connectionsCreatedAndClosedOnWorker": connection_threads == worker_threads,
        "lockAcquireThread": None if lock is None else lock.acquire_thread,
        "lockCloseThreads": [] if lock is None else lock.close_threads,
        "lockClosed": bool(lock and lock.closed),
        "committed": [committed.last_epoch, committed.generation],
        "readbackNextEpoch": readback.next_epoch,
    }


def cancellation_probe() -> dict[str, Any]:
    started = threading.Event()
    release = threading.Event()
    queued_ran = threading.Event()
    running_thread: list[int] = []
    with ThreadPoolExecutor(max_workers=1, thread_name_prefix="epoch-cancel") as executor:
        def blocked():
            running_thread.append(threading.get_ident())
            started.set()
            if not release.wait(timeout=10):
                raise TimeoutError("test release was not signalled")
            return "released"

        running = executor.submit(blocked)
        if not started.wait(timeout=10):
            release.set()
            raise ProbeBlocked("running_probe_did_not_start")
        queued = executor.submit(lambda: queued_ran.set())
        queued_cancelled = queued.cancel()
        running_cancelled = running.cancel()
        retained_before_release = not running.done()
        release.set()
        running_result = running.result(timeout=10)
    return {
        "queuedCancelBeforeStart": queued_cancelled,
        "queuedCreatedResources": queued_ran.is_set(),
        "runningCancelReturned": running_cancelled,
        "runningRetainedCapacityUntilCompletion": retained_before_release,
        "runningResult": running_result,
        "runningThread": running_thread[0],
        "cleanup": "EXECUTOR_SHUTDOWN_AFTER_ACTUAL_COMPLETION",
    }


def off_worker_observation(store_module: Any, root: Path) -> dict[str, Any]:
    platform = TestDoublePlatform(store_module)
    with ThreadPoolExecutor(max_workers=1, thread_name_prefix="epoch-offworker") as executor:
        store = executor.submit(
            store_module.EpochStore.provision, root, PROVISION, BODY, platform=platform
        ).result(timeout=10)
        try:
            candidate = store.read_candidate()
            observation = "NO_STORE_THREAD_AFFINITY_ENFORCEMENT"
            next_epoch = candidate.next_epoch
        except Exception as error:  # Observation only; either result is recorded honestly.
            observation = "OFF_WORKER_READ_REJECTED"
            next_epoch = None
            error_type = type(error).__name__
        else:
            error_type = None
        finally:
            executor.submit(store.close).result(timeout=10)
    return {"status": observation, "nextEpoch": next_epoch, "errorType": error_type,
            "securityPass": False}


def close_failure_probe(store_module: Any, root: Path) -> dict[str, Any]:
    platform = TestDoublePlatform(store_module, fail_close=True)
    store = store_module.EpochStore.provision(root, PROVISION, BODY, platform=platform)
    first_failed = False
    try:
        store.close()
    except RuntimeError:
        first_failed = True
    store.close()  # _closed is already true, so no retry reaches the lock double.
    lock = platform.lock
    return {
        "status": "KNOWN_LIMITATION_FAILED_CLEANUP",
        "firstCloseFailed": first_failed,
        "storeClosedFlag": store._closed,
        "lockCloseAttempts": 0 if lock is None else len(lock.close_threads),
        "lockReportedClosed": bool(lock and lock.closed),
        "harnessCleanup": "TEST_DOUBLE_HAS_NO_NATIVE_HANDLE",
    }


def native_windows_lock_probe(platform_module: Any, root: Path) -> dict[str, Any]:
    if os.name != "nt":
        return {"status": "SKIPPED_NON_WINDOWS"}
    lock_path = root / "native.lock"
    try:
        lock_path.touch()
    except OSError as error:
        raise ProbeBlocked("native_lock_fixture_failed") from error
    first = None
    unexpected_second = None
    reacquired = None
    rejected = False
    cleanup_errors = 0
    try:
        first = platform_module.WindowsLockHandle.acquire(lock_path)
        try:
            unexpected_second = platform_module.WindowsLockHandle.acquire(lock_path)
        except Exception:
            rejected = True
        try:
            first.close()
            first = None
        except Exception:
            cleanup_errors += 1
        if rejected and cleanup_errors == 0:
            reacquired = platform_module.WindowsLockHandle.acquire(lock_path)
    finally:
        for handle in (unexpected_second, reacquired, first):
            if handle is not None:
                try:
                    handle.close()
                except Exception:
                    cleanup_errors += 1
    passed = rejected and reacquired is not None and cleanup_errors == 0
    return {
        "status": ("PASSED_NATIVE_WINDOWS_LOCK_ONLY" if passed
                   else "FAILED_NATIVE_WINDOWS_LOCK_OR_CLEANUP"),
        "secondHandleRejectedWhileOwned": rejected,
        "reacquiredAfterActualClose": reacquired is not None,
        "cleanupStatus": "CLEAN" if cleanup_errors == 0 else "UNKNOWN_OR_FAILED",
        "cleanupErrors": cleanup_errors,
        "aclOrDurabilityClaim": False,
    }


def run_probe(temp_parent: Path | None = None) -> dict[str, Any]:
    cleanup = "NOT_STARTED"
    with loaded_upstream() as (store, platform, hashes):
        production_capability = store._UnsupportedPlatform().capability(Path("."))
        with tempfile.TemporaryDirectory(prefix="epoch-worker-", dir=temp_parent) as temporary:
            root = Path(temporary)
            result = {
            "schema": "epoch-worker-ownership-test-only-v1",
            "classification": "TEST_ONLY_NOT_PRODUCTION_WORKER_OR_PLATFORM_EVIDENCE",
            "upstreamExpectedHead": EXPECTED_UPSTREAM_HEAD,
            "loadedModules": {
                "epochStore": str(Path(store.__file__).resolve()),
                "epochPlatform": str(Path(platform.__file__).resolve()),
            },
            "sourceHashes": hashes,
            "productionDefaultCapability": {
                "verified": production_capability.verified,
                "reason": production_capability.reason,
            },
            "lifecycle": lifecycle_probe(store, root / "lifecycle"),
            "cancellation": cancellation_probe(),
            "offWorkerObservation": off_worker_observation(store, root / "off-worker"),
            "closeFailure": close_failure_probe(store, root / "close-failure"),
            "nativeWindowsLock": native_windows_lock_probe(platform, root),
            "claimsExcluded": [
                "production_worker_or_coordinator_implementation",
                "acl_authorization_or_power_loss_durability",
                "store_thread_affinity_enforcement",
                "production_capability_enablement",
            ],
            "cleanup": "PENDING_TEMP_DIRECTORY_EXIT",
            "status": "TEST_ONLY_PROBE_COMPLETED",
            }
        cleanup = "EXECUTORS_SHUT_DOWN_AND_TEMP_DIRECTORY_REMOVED"
        result["cleanup"] = cleanup
    return result


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--report", type=Path)
    args = parser.parse_args(argv)
    try:
        result = run_probe()
    except ProbeBlocked as error:
        result = {"status": "BLOCKED", "errors": [str(error)]}
    except OSError:
        result = {"status": "BLOCKED", "errors": ["filesystem_operation_failed"]}
    if args.report:
        try:
            args.report.parent.mkdir(parents=True, exist_ok=True)
            args.report.write_text(json.dumps(result, sort_keys=True) + "\n", encoding="utf-8")
        except OSError:
            result = {"status": "BLOCKED", "errors": ["report_output_failed"]}
    print(json.dumps({"status": result["status"]}, sort_keys=True))
    return 0 if result["status"] == "TEST_ONLY_PROBE_COMPLETED" else 2


if __name__ == "__main__":
    raise SystemExit(main())
