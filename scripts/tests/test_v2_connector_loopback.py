"""Offline-only runner contract tests: no HTTP, socket binding, or process launch."""

import importlib.util
import json
import sys
import tempfile
import time
import io
import importlib.metadata
import contextlib
import unittest
from pathlib import Path
from unittest.mock import patch

SCRIPTS = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SCRIPTS / "minecraft"))
import verify_v2_connector_loopback as runner


class ConnectorRunnerTests(unittest.TestCase):
    def versions(self, **updates):
        value = dict(
            python="3.10.21",
            uvicorn="0.50.1",
            websockets="12.0",
            wsproto="NOT_INSTALLED",
            configuredBackend="AUTO",
            selectionEvidence="NOT_OBSERVED",
        )
        value.update(updates)
        return value

    def test_alternative_metadata_is_optional_but_never_runtime_selection(self):
        for selected, values in [
            ("AUTO", {}),
            (
                "wsproto",
                dict(
                    configuredBackend="wsproto",
                    websockets="NOT_INSTALLED",
                    wsproto="1.2.0",
                ),
            ),
        ]:
            value = runner.validate_versions(self.versions(**values), selected)
            self.assertEqual("NOT_OBSERVED", value["selectionEvidence"])
        for values, selected in [
            (dict(uvicorn="NOT_INSTALLED"), "AUTO"),
            (dict(python="bad"), "AUTO"),
            (dict(wsproto="NOT_INSTALLED", websockets="NOT_INSTALLED"), "AUTO"),
            (dict(configuredBackend="wsproto"), "wsproto"),
            (dict(extra="x"), "AUTO"),
        ]:
            with self.assertRaises(runner.Error):
                runner.validate_versions(self.versions(**values), selected)

    def test_actual_version_program_catches_only_optional_package_not_found(self):
        def metadata(name):
            if name == "wsproto":
                raise importlib.metadata.PackageNotFoundError(name)
            return "12.0" if name == "websockets" else "0.50.1"

        output = io.StringIO()
        with patch.object(
            importlib.metadata, "version", side_effect=metadata
        ), contextlib.redirect_stdout(output):
            exec(runner.VERSION_CODE, {})
        value = runner.validate_versions(json.loads(output.getvalue()))
        self.assertEqual("NOT_INSTALLED", value["wsproto"])
        for failure in (
            RuntimeError("unexpected"),
            importlib.metadata.PackageNotFoundError("uvicorn"),
        ):
            with patch.object(importlib.metadata, "version", side_effect=failure):
                with self.assertRaises(type(failure)):
                    exec(runner.VERSION_CODE, {})

    def test_failed_probes_keep_sanitized_combined_receipt_and_native_facts(self):
        for kind in ("nonzero", "timeout", "quota", "reader", "cleanup"):
            with tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary).resolve()
                process = unittest.mock.Mock()
                process.tree.snapshot.return_value = {
                    "ownedPid": 7,
                    "cleanupUnknown": kind == "cleanup",
                    "forceKilled": kind == "quota",
                }
                process.tree.closed = True
                process.tree.force_killed = kind == "quota"
                process.tree.failure = "UNKNOWN" if kind == "cleanup" else None
                process.reader.failure = "READER_FAILED" if kind == "reader" else None
                process.wait.return_value = 4 if kind == "nonzero" else 0
                if kind in ("timeout", "quota", "reader"):
                    process.wait.side_effect = runner.Error("EXPECTED_FAILURE")

                def spawn(command, cwd, env, path):
                    path.write_bytes(b"secret=DO_NOT_EXPORT\nprivate traceback\n")
                    return process

                with patch.object(runner, "APPROVED", root), patch.object(
                    runner, "LoggedProcess", side_effect=spawn
                ):
                    with self.assertRaises(runner.Error) as caught:
                        runner.probe(["fixed"], root, root, "runtime-versions")
                receipt = caught.exception.probe_evidence
                artifact = Path(receipt["artifact"])
                self.assertNotIn("DO_NOT_EXPORT", artifact.read_text())
                self.assertEqual(
                    (
                        4
                        if kind == "nonzero"
                        else (0 if kind == "cleanup" else "UNAVAILABLE")
                    ),
                    receipt["exitCode"],
                )
                self.assertEqual("RETAINED_SANITIZED", receipt["diagnosticState"])
                self.assertEqual(7, receipt["nativeCleanup"]["ownedPid"])
                self.assertFalse((artifact.parent / "combined.private").exists())
                process.finish.assert_called_once()

    def test_version_probe_failure_prevents_network_effects(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary).resolve()
            failure = runner.Error("LOCAL_PROBE_FAILED")
            failure.probe_evidence = {"identity": "runtime-versions", "exitCode": 1}
            with patch.object(
                runner, "gates", return_value=(root, {}, {}, "head")
            ), patch.object(
                runner, "runtime_versions", side_effect=failure
            ), patch.object(
                runner.helper, "free_port"
            ) as port, patch.object(
                runner, "LoggedProcess"
            ) as launch, patch.object(
                runner, "status"
            ) as http:
                result = runner.run(root, root, root / "python.exe", root / "output")
            port.assert_not_called()
            launch.assert_not_called()
            http.assert_not_called()
            self.assertEqual("BLOCKED", result["status"])
            self.assertFalse(result["networkExecuted"])
            self.assertEqual(1, result["probeEvidence"]["exitCode"])

    def test_diagnostic_copy_failure_retains_private_owned_evidence_as_unknown(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary).resolve()
            process = unittest.mock.Mock()
            process.wait.return_value = 2
            process.tree.snapshot.return_value = {"ownedPid": 9}
            process.tree.closed = True

            def spawn(command, cwd, env, path):
                path.write_bytes(b"private combined output")
                return process

            original_open = Path.open

            def opened(path, *args, **kwargs):
                if path.name == "sanitized-combined.json":
                    raise OSError("copy failed")
                return original_open(path, *args, **kwargs)

            with patch.object(runner, "APPROVED", root), patch.object(
                runner, "LoggedProcess", side_effect=spawn
            ), patch.object(Path, "open", opened):
                with self.assertRaises(runner.Error) as caught:
                    runner.probe(["fixed"], root, root, "runtime-versions")
            receipt = caught.exception.probe_evidence
            self.assertEqual("UNKNOWN", receipt["diagnosticState"])
            self.assertTrue(
                (Path(receipt["privateDirectory"]) / "combined.private").exists()
            )

    def test_required_runtime_schema_is_checked_after_mocked_probe(self):
        with patch.object(runner, "probe", return_value=b'{"python":"3.10"}'):
            with self.assertRaises(runner.Error):
                runner.runtime_versions(Path("C:/python.exe"), Path("C:/evidence"))

    def value(self):
        return dict(
            enabled=True,
            routeRegistered=True,
            activeRoles=[],
            pendingCount=0,
            completedSessions=3,
            applicationFrames=999,
            loadedModuleHashes={},
            loadedModulePaths={},
        )

    def test_unexposed_and_constant_fields_are_not_claimed_measured(self):
        result = runner.observational(
            self.value(), {"coordinatorPresent": False}, {"mode": "OBSERVATIONAL"}
        )
        self.assertEqual("NOT_MEASURED", result["applicationFrames"])
        self.assertEqual("NOT_EXPOSED", result["hubAckCount"])
        self.assertEqual("NOT_EXPOSED", result["hubHeartbeatCount"])
        self.assertEqual("NOT_MEASURED", result["workerJobs"])

    def test_missing_status_or_coordinator_or_wrong_mode_blocks(self):
        for status, route, mode in [
            ({}, {}, {}),
            (self.value(), {}, {"mode": "OBSERVATIONAL"}),
            (
                self.value(),
                {"coordinatorPresent": False},
                {"mode": "HEADLESS_ADMISSION"},
            ),
        ]:
            with self.assertRaises(runner.helper.LoopbackError):
                runner.observational(status, route, mode)

    def test_bad_role_and_bool_counter_rejected(self):
        for key, value in [
            ("activeRoles", ["AGENT_HUB"]),
            ("pendingCount", True),
            ("completedSessions", -1),
        ]:
            status = self.value()
            status[key] = value
            with self.assertRaises(runner.helper.LoopbackError):
                runner.observational(
                    status, {"coordinatorPresent": False}, {"mode": "OBSERVATIONAL"}
                )

    def test_gate_failure_has_no_launch_http_port_or_output_effect(self):
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary) / "new"
            with patch.object(
                runner, "gates", side_effect=runner.Error("DIRTY")
            ), patch.object(runner.subprocess, "Popen") as spawn, patch.object(
                runner.helper, "free_port"
            ) as port, patch.object(
                runner, "status"
            ) as http:
                with self.assertRaises(runner.Error):
                    runner.run(
                        Path(temporary), Path(temporary), Path(sys.executable), output
                    )
                spawn.assert_not_called()
                port.assert_not_called()
                http.assert_not_called()
                self.assertFalse(output.exists())

    def test_clean_pin_and_untracked_are_mandatory(self):
        with patch.object(
            runner, "git", side_effect=[runner.UPSTREAM, "?? unexpected"]
        ):
            with self.assertRaises(runner.Error):
                runner.clean(Path("."), runner.UPSTREAM)
        with patch.object(runner, "git", return_value="0" * 40):
            with self.assertRaises(runner.Error):
                runner.clean(Path("."), runner.UPSTREAM)

    def test_paths_reject_relative_and_parent_alias(self):
        for path in (Path("relative"), Path(tempfile.gettempdir()) / ".." / "alias"):
            with self.assertRaises(runner.Error):
                runner.safe_path(path, exists=False)

    def test_declared_inventory_includes_worker_imports_and_actual_core(self):
        files = runner.inventory(Path("C:/agent"), Path("C:/sandbox/bridge/forge"))
        for part in (
            "epoch_platform.py",
            "epoch_worker.py",
            "V2JdkWebSocketAdapter.java",
            "V2PeerConnector.java",
            "V2ConnectorLoopbackTest.java",
        ):
            self.assertTrue(any(k.endswith(part) for k in files), part)
        self.assertTrue(
            any(k.endswith("verify_v2_transport_loopback.py") for k in files)
        )

    def test_loaded_modules_must_match_paths_and_hashes(self):
        with self.assertRaises(runner.helper.LoopbackError):
            runner.helper.validate_loaded(
                {"loadedModuleHashes": {}, "loadedModulePaths": {}},
                {"python:x": "abc"},
                {"python:x": Path("C:/wrong")},
            )

    def test_fresh_exact_three_cases_and_nonce_required(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary).resolve() / "test.xml"
            cases = "".join(f'<testcase name="{name}"/>' for name in runner.CASES)
            raw = f'<testsuite name="{runner.CLASS}" tests="3" failures="0" errors="0" skipped="0">{cases}<system-out>CONNECTOR_RUN_COMPLETE abc</system-out></testsuite>'
            path.write_text(raw, encoding="utf-8")
            self.assertEqual(raw.encode(), runner.junit(path, 0, "abc"))
            with self.assertRaises(runner.Error):
                runner.junit(path, time.time_ns() + 10**9, "abc")
            with self.assertRaises(runner.Error):
                runner.junit(path, 0, "wrong")
            path.write_text(raw.replace('skipped="0"', 'skipped="3"'), encoding="utf-8")
            with self.assertRaises(runner.Error):
                runner.junit(path, 0, "abc")

    def test_shutdown_missing_or_wrong_nonce_cannot_pass(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "receipt.json"
            with self.assertRaises(runner.helper.LoopbackError):
                runner.helper.validate_shutdown_receipt(path, "nonce")
            path.write_text(json.dumps({"nonce": "wrong"}), encoding="utf-8")
            with self.assertRaises(runner.helper.LoopbackError):
                runner.helper.validate_shutdown_receipt(path, "nonce")

    def test_forced_cleanup_is_blocked_not_mock_network_pass(self):
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary).resolve() / "new"
            process = unittest.mock.Mock()
            process.poll.return_value = None
            process.pid = 123
            process.tree.force_killed = True
            process.tree.failure = None
            process.reader.failure = None
            with patch.object(
                runner, "gates", return_value=(Path(temporary), {}, {}, "head")
            ), patch.object(runner, "runtime_versions", return_value={}), patch.object(
                runner,
                "probe",
                return_value=b"jdk",
            ), patch.object(
                runner.helper, "free_port", return_value=1234
            ), patch.object(
                runner, "LoggedProcess", return_value=process
            ), patch.object(
                runner, "status", side_effect=runner.Error("BAD_IDENTITY")
            ):
                result = runner.run(
                    Path(temporary), Path(temporary), Path(sys.executable), output
                )
            self.assertEqual("BLOCKED", result["status"])
            self.assertTrue(result["forceKilled"])
            process.finish.assert_called_once()
            self.assertEqual("BAD_IDENTITY", result["failure"])

    def test_exact_gradle_class_and_offline_flags(self):
        args = runner.command(Path("C:/sandbox/bridge/forge"))
        self.assertEqual(runner.CLASS, args[args.index("--tests") + 1])
        for flag in ("--offline", "--rerun-tasks", "--no-daemon", "--max-workers=2"):
            self.assertIn(flag, args)

    def test_existing_output_rejected_before_git_or_snapshot(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary).resolve()
            with patch.object(runner, "APPROVED", root), patch.object(
                runner, "clean"
            ) as clean:
                with self.assertRaises(runner.Error):
                    runner.gates(root, root, Path(sys.executable).resolve(), root)
                clean.assert_not_called()

    def test_symlink_source_is_not_followed(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary).resolve() / "source.py"
            path.write_text("source", encoding="utf-8")
            with patch.object(Path, "is_symlink", return_value=True):
                with self.assertRaises(runner.Error):
                    runner.bounded(path)

    def test_unsafe_xml_rejected_without_parser(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary).resolve() / "unsafe.xml"
            path.write_text("<!DOCTYPE suite><testsuite/>", encoding="utf-8")
            with self.assertRaises(runner.Error):
                runner.junit(path, 0, "nonce")

    def test_huge_nonnewline_pipe_is_bounded_and_contained_once(self):
        output = io.BytesIO()
        kills = []
        reader = runner.BoundedReader(
            io.BytesIO(b"x" * (runner.LIMIT + 17000)),
            output,
            lambda: kills.append("kill"),
        )
        reader.start()
        with self.assertRaises(runner.Error):
            reader.finish()
        self.assertEqual(runner.LIMIT, len(output.getvalue()))
        self.assertEqual(["kill"], kills)
        self.assertFalse(reader.thread.is_alive())

    def test_reader_exception_and_exit_quota_race_are_sticky(self):
        class Broken:
            def read(self, size):
                raise OSError("read")

            def close(self):
                pass

        for stream in (Broken(), io.BytesIO(b"x" * 17)):
            kills = []
            reader = runner.BoundedReader(
                stream, io.BytesIO(), lambda: kills.append(1), limit=16
            )
            reader.start()
            reader.thread.join(1)
            with self.assertRaises(runner.Error):
                reader.finish()
            self.assertEqual([1], kills)
            self.assertFalse(reader.thread.is_alive())

    def test_job_assignment_resume_and_cleanup_use_only_owned_handles(self):
        class Api:
            def __init__(self, failure=None):
                self.calls = []
                self.failure = failure
                self.active_count = 0

            def job(self):
                self.calls.append("job")
                return "job"

            def spawn_suspended(self, *args):
                self.calls.append("suspended")
                return "process", "thread", 12, io.BytesIO()

            def adopt_read(self, handle):
                return handle

            def assign(self, *args):
                self.calls.append("assign")
                if self.failure == "assign":
                    raise runner.Error("assign")

            def resume(self, thread):
                self.calls.append("resume")
                if self.failure == "resume":
                    raise runner.Error("resume")

            def kill_suspended(self, process):
                self.calls.append("kill_suspended")

            def kill(self, job):
                self.calls.append("kill_job")
                if self.failure == "kill":
                    raise runner.Error("kill")

            def close(self, handle):
                self.calls.append("close_" + handle)

            def poll(self, process):
                return 0

            def active(self, job):
                return self.active_count

        for failure in ("assign", "resume"):
            api = Api(failure)
            with self.assertRaises(runner.Error):
                runner.OwnedTree([], Path("."), {}, api)
            if failure == "assign":
                self.assertNotIn("resume", api.calls)
                self.assertIn("kill_suspended", api.calls)
            else:
                self.assertIn("kill_job", api.calls)
            for handle in ("thread", "process", "job"):
                self.assertIn("close_" + handle, api.calls)
        api = Api()
        tree = runner.OwnedTree([], Path("."), {}, api)
        self.assertLess(api.calls.index("assign"), api.calls.index("resume"))
        api.active_count = 1
        with self.assertRaises(runner.Error):
            tree.wait(0)
        tree.contain()
        tree.contain()
        self.assertEqual(2, api.calls.count("kill_job"))
        tree.close()
        api = Api("kill")
        tree = runner.OwnedTree([], Path("."), {}, api)
        tree.contain()
        self.assertEqual("JOB_KILL_UNKNOWN", tree.failure)
        tree.close()

    def test_preassignment_failures_expose_original_and_bounded_exit_confirmation(self):
        class Api:
            def __init__(self, conversion=False, terminate=False, exits=False):
                self.conversion = conversion
                self.terminate = terminate
                self.exits = exits
                self.calls = []
                self.original = runner.Error("CONVERSION" if conversion else "ASSIGN")

            def job(self):
                return "job"

            def spawn_suspended(self, *args):
                return "root", "thread", 77, "read"

            def adopt_read(self, read):
                if self.conversion:
                    self.calls.append("close_read")
                    raise self.original
                return io.BytesIO()

            def assign(self, *args):
                raise self.original

            def resume(self, *args):
                self.calls.append("RESUME")

            def kill_suspended(self, root):
                self.calls.append("terminate_root")
                if self.terminate:
                    raise runner.Error("TERMINATE")

            def poll(self, root):
                return 1 if self.exits else None

            def close(self, handle):
                self.calls.append("close_" + handle)

        for conversion, terminate, exits in [
            (False, True, False),
            (False, False, False),
            (True, True, False),
            (False, False, True),
        ]:
            api = Api(conversion, terminate, exits)
            with self.assertRaises(runner.Error) as caught:
                runner.OwnedTree([], Path("."), {}, api)
            self.assertIs(api.original, caught.exception)
            state = caught.exception.native_cleanup
            self.assertEqual(77, state["ownedPid"])
            self.assertFalse(state["rootAssigned"])
            self.assertEqual(exits, state["rootExitConfirmed"])
            self.assertEqual(not exits, state["cleanupUnknown"])
            self.assertEqual(1 if exits else 2, api.calls.count("terminate_root"))
            self.assertNotIn("RESUME", api.calls)
            for handle in ("root", "thread", "job"):
                self.assertIn("close_" + handle, api.calls)
            if conversion:
                self.assertEqual(1, api.calls.count("close_read"))

    def test_constructor_unknown_does_not_skip_remaining_hub_cleanup_or_report(self):
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary).resolve() / "output"
            hub = unittest.mock.Mock()
            hub.poll.return_value = None
            hub.pid = 10
            hub.tree.force_killed = True
            hub.tree.failure = "UNKNOWN"
            hub.reader.failure = None
            failure = runner.Error("JAVA_NATIVE_CONSTRUCTION")
            runner.attach_cleanup(
                failure, {"ownedPid": 99, "forceKilled": True, "cleanupUnknown": True}
            )
            with patch.object(
                runner, "gates", return_value=(Path(temporary), {}, {}, "head")
            ), patch.object(runner, "runtime_versions", return_value={}), patch.object(
                runner, "probe", return_value=b"jdk"
            ), patch.object(
                runner.helper, "free_port", return_value=1234
            ), patch.object(
                runner, "LoggedProcess", side_effect=[hub, failure]
            ), patch.object(
                runner, "status", return_value={}
            ), patch.object(
                runner.helper, "validate_loaded"
            ), patch.object(
                runner, "observational", return_value={}
            ):
                result = runner.run(
                    Path(temporary), Path(temporary), Path(sys.executable), output
                )
            hub.finish.assert_called_once()
            self.assertEqual("BLOCKED", result["status"])
            self.assertTrue(result["forceKilled"])
            self.assertEqual(99, result["nativeCleanup"]["ownedPid"])
            self.assertTrue((output / "report.json").is_file())

    def test_assigned_job_active_query_unknown_is_not_root_exit_proof(self):
        api = unittest.mock.Mock()
        api.job.return_value = "job"
        api.spawn_suspended.return_value = ("root", "thread", 55, "read")
        api.adopt_read.return_value = io.BytesIO()
        api.spawn_cleanup_unknown = False
        api.poll.return_value = 0
        api.active.side_effect = runner.Error("QUERY_FAILED")
        tree = runner.OwnedTree([], Path("."), {}, api)
        tree.contain()
        self.assertTrue(tree.root_exit_confirmed)
        self.assertFalse(tree.tree_exit_confirmed)
        self.assertTrue(tree.cleanup_unknown)
        self.assertTrue(tree.assigned)
        tree.close()

    def test_crt_fd_transfer_failure_closes_current_owner_only(self):
        api = object.__new__(runner.WindowsApi)
        api.close = unittest.mock.Mock()
        api.spawn_cleanup_unknown = False
        import msvcrt

        with patch.object(msvcrt, "open_osfhandle", return_value=123), patch.object(
            runner.os, "fdopen", side_effect=OSError("fdopen")
        ), patch.object(runner.os, "close") as close_fd:
            with self.assertRaises(OSError):
                api.adopt_read(456)
            close_fd.assert_called_once_with(123)
            api.close.assert_not_called()
        with patch.object(msvcrt, "open_osfhandle", side_effect=OSError("convert")):
            with self.assertRaises(OSError):
                api.adopt_read(456)
        api.close.assert_called_once_with(456)


if __name__ == "__main__":
    unittest.main()
