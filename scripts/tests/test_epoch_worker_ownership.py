import contextlib
import builtins
import importlib.util
import io
import json
import sys
import tempfile
import types
import unittest
from pathlib import Path
from unittest import mock


SCRIPT = Path(__file__).resolve().parents[1] / "minecraft" / "verify_epoch_worker_ownership.py"
SPEC = importlib.util.spec_from_file_location("verify_epoch_worker_ownership", SCRIPT)
probe = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(probe)


class EpochWorkerOwnershipProbeTest(unittest.TestCase):
    def test_actual_lifecycle_uses_one_worker_and_per_operation_connections_close_there(self):
        with probe.loaded_upstream() as (store, _platform, _hashes):
            with tempfile.TemporaryDirectory() as temporary:
                result = probe.lifecycle_probe(store, Path(temporary) / "epoch")
        self.assertEqual("PASSED_TEST_DOUBLE", result["status"])
        self.assertTrue(result["singleWorkerThread"])
        self.assertTrue(result["connectionsCreatedAndClosedOnWorker"])
        self.assertTrue(result["lockClosed"])
        self.assertEqual([1, 1], result["committed"])
        self.assertEqual(2, result["readbackNextEpoch"])
        creates = [event for event in result["connectionEvents"]
                   if event["event"] == "connection_create"]
        closes = [event for event in result["connectionEvents"]
                  if event["event"] == "connection_close"]
        self.assertEqual(len(creates), len(closes))
        self.assertGreaterEqual(len(creates), 4)

    def test_cancel_queued_and_running_have_honest_bounded_cleanup(self):
        result = probe.cancellation_probe()
        self.assertTrue(result["queuedCancelBeforeStart"])
        self.assertFalse(result["queuedCreatedResources"])
        self.assertFalse(result["runningCancelReturned"])
        self.assertTrue(result["runningRetainedCapacityUntilCompletion"])
        self.assertEqual("released", result["runningResult"])
        self.assertEqual("EXECUTOR_SHUTDOWN_AFTER_ACTUAL_COMPLETION", result["cleanup"])

    def test_off_worker_read_is_observation_not_security_pass(self):
        with probe.loaded_upstream() as (store, _platform, _hashes):
            with tempfile.TemporaryDirectory() as temporary:
                result = probe.off_worker_observation(store, Path(temporary) / "epoch")
        self.assertIn(result["status"], {
            "NO_STORE_THREAD_AFFINITY_ENFORCEMENT", "OFF_WORKER_READ_REJECTED"})
        self.assertFalse(result["securityPass"])

    def test_close_failures_are_fenced_without_retry_or_native_resource_claim(self):
        with probe.loaded_upstream() as (store, _platform, _hashes):
            with tempfile.TemporaryDirectory() as temporary:
                result = probe.close_failure_probe(store, Path(temporary) / "epoch")
        self.assertEqual("FAILED_CLEANUP_FENCED_NO_RETRY", result["status"])
        self.assertFalse(result["productionResourceCleanupClaim"])
        self.assertEqual(
            ["fail_before_release", "release_then_throw"],
            [case["failureMode"] for case in result["cases"]],
        )
        for case in result["cases"]:
            self.assertEqual("FAILED_CLEANUP_FENCED_NO_RETRY", case["status"])
            self.assertTrue(case["firstCloseFailed"])
            self.assertTrue(case["secondCloseFailed"])
            self.assertTrue(case["storeClosedFlag"])
            self.assertFalse(case["resourceReleasedFlag"])
            self.assertTrue(case["cleanupUnknownFlag"])
            self.assertEqual(1, case["lockCloseAttempts"])
            self.assertEqual("TEST_DOUBLE_HAS_NO_NATIVE_HANDLE", case["harnessCleanup"])
        self.assertFalse(result["cases"][0]["lockReportedClosed"])
        self.assertTrue(result["cases"][1]["lockReportedClosed"])

    def test_full_probe_keeps_production_capability_unavailable_and_cleans_temp(self):
        with tempfile.TemporaryDirectory() as temporary:
            parent = Path(temporary)
            result = probe.run_probe(parent)
            self.assertEqual([], list(parent.iterdir()))
        self.assertEqual("TEST_ONLY_PROBE_COMPLETED", result["status"])
        self.assertFalse(result["productionDefaultCapability"]["verified"])
        self.assertEqual("EXECUTORS_SHUT_DOWN_AND_TEMP_DIRECTORY_REMOVED", result["cleanup"])
        self.assertEqual(
            "TEST_ONLY_NOT_PRODUCTION_WORKER_OR_PLATFORM_EVIDENCE",
            result["classification"],
        )
        self.assertIn(result["nativeWindowsLock"]["status"], {
            "PASSED_NATIVE_WINDOWS_LOCK_ONLY", "SKIPPED_NON_WINDOWS",
            "FAILED_NATIVE_WINDOWS_LOCK_OR_CLEANUP"})

    def test_loader_ignores_same_path_patched_modules_and_restores_every_identity(self):
        names = (
            "src", "src.adapter", "src.adapter.minecraft",
            "src.adapter.minecraft.epoch_store", "src.adapter.minecraft.epoch_platform",
        )
        sentinels = {}
        for name in names:
            sentinel = types.ModuleType(name)
            sentinel.marker = object()
            sentinel.__path__ = ["sentinel-path"]
            if name.endswith("epoch_store"):
                sentinel.__file__ = str(probe.SERVER_ROOT / probe.EPOCH_STORE_RELATIVE)
                sentinel.EpochStore = type("MustNotBeUsed", (), {
                    "provision": staticmethod(lambda *_a, **_k: self.fail("sentinel called"))})
            elif name.endswith("epoch_platform"):
                sentinel.__file__ = str(probe.SERVER_ROOT / probe.EPOCH_PLATFORM_RELATIVE)
            sentinels[name] = sentinel
        with mock.patch.dict(sys.modules, sentinels, clear=False):
            with probe.loaded_upstream() as (store, platform, _hashes):
                self.assertIsNot(store, sentinels["src.adapter.minecraft.epoch_store"])
                self.assertIsNot(platform, sentinels["src.adapter.minecraft.epoch_platform"])
                self.assertIsNot(sys.modules["src"], sentinels["src"])
            for name, sentinel in sentinels.items():
                self.assertIs(sys.modules[name], sentinel)

    def test_loader_restores_modules_after_platform_execution_failure_and_when_initially_absent(self):
        names = (
            "src", "src.adapter", "src.adapter.minecraft",
            "src.adapter.minecraft.epoch_store", "src.adapter.minecraft.epoch_platform",
        )
        saved = {name: sys.modules.pop(name, None) for name in names}
        original_compile = builtins.compile
        try:
            def fail_platform(source, filename, mode):
                if str(filename).endswith("epoch_platform.py"):
                    raise SyntaxError("injected")
                return original_compile(source, filename, mode)

            with mock.patch.object(builtins, "compile", side_effect=fail_platform):
                with self.assertRaises(SyntaxError):
                    with probe.loaded_upstream():
                        pass
            self.assertTrue(all(name not in sys.modules for name in names))
        finally:
            for name, value in saved.items():
                if value is not None:
                    sys.modules[name] = value

    def test_wrong_upstream_head_is_blocked(self):
        with mock.patch.object(probe.subprocess, "check_output", return_value="bad-head\n"):
            with self.assertRaisesRegex(probe.ProbeBlocked, "upstream_head_mismatch"):
                with probe.loaded_upstream():
                    pass

    def test_filesystem_oserror_is_sanitized_at_report_and_cli_boundary(self):
        secret = "secret path C:\\private"
        for method in ("is_file", "is_symlink", "resolve"):
            with self.subTest(method=method):
                output, errors = io.StringIO(), io.StringIO()
                report = Path(tempfile.gettempdir()) / "epoch-worker-sanitized-report.json"
                report.unlink(missing_ok=True)
                with mock.patch.object(Path, method, side_effect=OSError(secret)):
                    with contextlib.redirect_stdout(output), contextlib.redirect_stderr(errors):
                        code = probe.main(["--report", str(report)])
                self.assertEqual(2, code)
                self.assertNotIn(secret, output.getvalue() + errors.getvalue())
                self.assertNotIn("Traceback", output.getvalue() + errors.getvalue())
                if report.exists():
                    value = json.loads(report.read_text(encoding="utf-8"))
                    self.assertEqual("BLOCKED", value["status"])
                    self.assertNotIn(secret, json.dumps(value))
                    report.unlink()

    def test_report_directory_creation_failure_is_sanitized(self):
        self._assert_report_output_failure_sanitized("mkdir")

    def test_report_write_failure_is_sanitized(self):
        self._assert_report_output_failure_sanitized("write_text")

    def _assert_report_output_failure_sanitized(self, method):
        secret = "secret path C:\\private"
        output, errors = io.StringIO(), io.StringIO()
        report = Path(tempfile.gettempdir()) / "epoch-worker-output-failure.json"
        report.unlink(missing_ok=True)
        with mock.patch.object(probe, "run_probe", return_value={
            "status": "TEST_ONLY_PROBE_COMPLETED"
        }):
            target = Path if method == "write_text" else type(report.parent)
            with mock.patch.object(target, method, side_effect=OSError(secret)):
                with contextlib.redirect_stdout(output), contextlib.redirect_stderr(errors):
                    code = probe.main(["--report", str(report)])
        self.assertEqual(2, code)
        combined = output.getvalue() + errors.getvalue()
        self.assertNotIn(secret, combined)
        self.assertNotIn("Traceback", combined)
        self.assertEqual({"status": "BLOCKED"}, json.loads(output.getvalue()))
        self.assertFalse(report.exists())

    def test_native_probe_closes_unexpected_second_handle(self):
        class Handle:
            def __init__(self):
                self.closes = 0

            def close(self):
                self.closes += 1

        first, unexpected = Handle(), Handle()
        handles = [first, unexpected]

        class Backend:
            class WindowsLockHandle:
                @staticmethod
                def acquire(_path):
                    return handles.pop(0)

        original_name = probe.os.name
        with tempfile.TemporaryDirectory() as temporary:
            with mock.patch.object(probe.os, "name", "nt"):
                result = probe.native_windows_lock_probe(Backend, Path(temporary))
        self.assertEqual("FAILED_NATIVE_WINDOWS_LOCK_OR_CLEANUP", result["status"])
        self.assertFalse(result["secondHandleRejectedWhileOwned"])
        self.assertEqual([], handles)
        self.assertEqual(1, first.closes)
        self.assertEqual(1, unexpected.closes)

    @unittest.skipUnless(probe.os.name == "nt", "requires real Win32 CreateFileW")
    def test_native_windows_lock_rejects_second_and_reacquires_after_close(self):
        with probe.loaded_upstream() as (_store, platform, _hashes):
            with tempfile.TemporaryDirectory() as temporary:
                result = probe.native_windows_lock_probe(platform, Path(temporary))
        self.assertEqual("PASSED_NATIVE_WINDOWS_LOCK_ONLY", result["status"])
        self.assertTrue(result["secondHandleRejectedWhileOwned"])
        self.assertTrue(result["reacquiredAfterActualClose"])
        self.assertEqual("CLEAN", result["cleanupStatus"])
        self.assertFalse(result["aclOrDurabilityClaim"])


if __name__ == "__main__":
    unittest.main()
