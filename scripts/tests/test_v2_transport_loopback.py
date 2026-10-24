import importlib.util
import json
import os
import tempfile
import time
import unittest
from pathlib import Path
from unittest.mock import Mock, patch

SCRIPT = (
    Path(__file__).resolve().parents[1] / "minecraft/verify_v2_transport_loopback.py"
)
SPEC = importlib.util.spec_from_file_location("v2_loopback", SCRIPT)
loopback = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(loopback)
SUPPORT_SCRIPT = Path(
    "C:/Agent-LuoTianyi/.slim/worktrees/minecraft-observation-adapter/server/tests/support/minecraft_v2_loopback_app.py"
)


def xml(cases, output="", failures="0", errors="0", skipped="0"):
    return f'<testsuite failures="{failures}" errors="{errors}" skipped="{skipped}">{cases}<system-out>{output}</system-out></testsuite>'


def receipt(nonce="nonce", **changes):
    value = {
        "schema": "luosandbox.v2-loopback-shutdown.v1",
        "nonce": nonce,
        "shutdownComplete": True,
        "sessionsDrained": True,
        "peerCloseAttempts": 2,
        "peerCloseFailures": 0,
        "temporaryCleanupAttempted": True,
        "temporaryCleanupSucceeded": True,
        "cleanupErrors": [],
    }
    value.update(changes)
    return value


def status(**changes):
    value = {
        "enabled": True,
        "routeRegistered": True,
        "activeRoles": [],
        "pendingCount": 0,
        "completedSessions": 0,
        "applicationFrames": 0,
        "loadedModuleHashes": {},
        "loadedModulePaths": {},
    }
    value.update(changes)
    return value


class V2LoopbackHarnessTest(unittest.TestCase):
    def test_gradle_command_forces_exact_offline_execution(self):
        command = loopback.gradle_command(Path("C:/sandbox/bridge/forge"))
        self.assertEqual(
            [
                "--offline",
                "--no-daemon",
                "--rerun-tasks",
                "--max-workers=2",
                "test",
                "--tests",
                "*PostAuthV2LoopbackTest",
            ],
            command[1:],
        )

    def test_server_command_uses_support_script_without_conftest_app_import(self):
        command = loopback.server_command(Path("C:/python.exe"), Path("C:/agent"), 1234)
        self.assertEqual(Path("C:/python.exe"), Path(command[0]))
        self.assertTrue(
            command[1]
            .replace("\\", "/")
            .endswith("tests/support/minecraft_v2_loopback_app.py")
        )
        self.assertEqual(["--host", "127.0.0.1", "--port", "1234"], command[2:])

    def test_junit_requires_fresh_exact_two_roles_runtime_evidence(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "result.xml"
            with self.assertRaisesRegex(
                loopback.VerificationError, "JUNIT_MISSING_OR_STALE"
            ):
                loopback.parse_junit(path, time.time())
            cases = '<testcase name="SERVER_AUTHORITY"/><testcase name="CLIENT_CONTROLLER"/>'
            metrics = "\n".join(
                f'V2_LOOPBACK_METRIC {{"role":"{role}","requiredDurationMs":2300,"measuredDurationMs":2301,"applicationFrames":0}}'
                for role in sorted(loopback.REQUIRED_ROLES)
            )
            path.write_text(xml(cases, metrics), encoding="utf-8")
            self.assertEqual(
                sorted(loopback.REQUIRED_ROLES),
                loopback.parse_junit(path, time.time() - 1)["roles"],
            )
            path.write_text(
                xml(
                    cases,
                    metrics.replace(
                        '"applicationFrames":0', '"applicationFrames":1', 1
                    ),
                ),
                encoding="utf-8",
            )
            with self.assertRaisesRegex(
                loopback.VerificationError, "JUNIT_RUNTIME_EVIDENCE_INVALID"
            ):
                loopback.parse_junit(path, time.time() - 1)

    def test_junit_rejects_failure_unsafe_wrong_count_and_stale(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "result.xml"
            path.write_text(xml("", failures="1"), encoding="utf-8")
            with self.assertRaisesRegex(loopback.VerificationError, "JUNIT_NOT_CLEAN"):
                loopback.parse_junit(path, time.time() - 1)
            path.write_text("<!DOCTYPE x><testsuite/>", encoding="utf-8")
            with self.assertRaisesRegex(loopback.VerificationError, "JUNIT_UNSAFE_XML"):
                loopback.parse_junit(path, time.time() - 1)
            path.write_text(
                xml('<testcase name="SERVER_AUTHORITY"/>'), encoding="utf-8"
            )
            with self.assertRaisesRegex(loopback.VerificationError, "JUNIT_NOT_CLEAN"):
                loopback.parse_junit(path, time.time() - 1)
            old = time.time() - 10
            os.utime(path, (old, old))
            with self.assertRaisesRegex(
                loopback.VerificationError, "JUNIT_MISSING_OR_STALE"
            ):
                loopback.parse_junit(path, time.time())

    def test_junit_rejects_wrong_roles_missing_and_invalid_metrics(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "result.xml"
            path.write_text(
                xml('<testcase name="OTHER_ONE"/><testcase name="OTHER_TWO"/>'),
                encoding="utf-8",
            )
            with self.assertRaisesRegex(
                loopback.VerificationError, "JUNIT_ROLES_MISSING"
            ):
                loopback.parse_junit(path, time.time() - 1)
            correct = '<testcase name="SERVER_AUTHORITY"/><testcase name="CLIENT_CONTROLLER"/>'
            path.write_text(
                xml(correct, "V2_LOOPBACK_METRIC not-json"), encoding="utf-8"
            )
            with self.assertRaisesRegex(
                loopback.VerificationError, "JUNIT_METRICS_INVALID"
            ):
                loopback.parse_junit(path, time.time() - 1)

    def test_junit_rejects_role_prefix_suffix_and_duplicate_cases(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "result.xml"
            metrics = "\n".join(
                f'V2_LOOPBACK_METRIC {{"role":"{role}","requiredDurationMs":2300,"measuredDurationMs":2301,"applicationFrames":0}}'
                for role in sorted(loopback.REQUIRED_ROLES)
            )
            for names in [
                ("NOT_SERVER_AUTHORITY", "CLIENT_CONTROLLER"),
                ("SERVER_AUTHORITY_suffix", "CLIENT_CONTROLLER"),
                ("SERVER_AUTHORITY", "prefix_CLIENT_CONTROLLER"),
                ("SERVER_AUTHORITY", "SERVER_AUTHORITY"),
            ]:
                cases = "".join(f'<testcase name="{name}"/>' for name in names)
                path.write_text(xml(cases, metrics), encoding="utf-8")
                with self.assertRaisesRegex(
                    loopback.VerificationError, "JUNIT_ROLES_MISSING"
                ):
                    loopback.parse_junit(path, time.time() - 1)

    def test_junit_integer_evidence_rejects_bool_float_string_and_null(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "result.xml"
            cases = '<testcase name="SERVER_AUTHORITY"/><testcase name="CLIENT_CONTROLLER"/>'
            for field in (
                "requiredDurationMs",
                "measuredDurationMs",
                "applicationFrames",
            ):
                for invalid in (True, False, 1.0, "1", None):
                    items = []
                    for role in sorted(loopback.REQUIRED_ROLES):
                        item = {
                            "role": role,
                            "requiredDurationMs": 2300,
                            "measuredDurationMs": 2301,
                            "applicationFrames": 0,
                        }
                        item[field] = invalid
                        items.append("V2_LOOPBACK_METRIC " + json.dumps(item))
                    path.write_text(xml(cases, "\n".join(items)), encoding="utf-8")
                    with self.assertRaisesRegex(
                        loopback.VerificationError, "JUNIT_METRICS_INVALID"
                    ):
                        loopback.parse_junit(path, time.time() - 1)

    def test_source_closure_is_exact_and_contains_imported_transport(self):
        files = loopback.source_files(
            Path("C:/agent"), Path("C:/sandbox/bridge/forge"), Path("C:/harness.py")
        )
        expected_python = {
            "deployment_files.py",
            "deployment_acl.py",
            "deployment_manifest.py",
            "v2_startup.py",
            "control_auth.py",
            "current_roles.py",
            "postauth_keys.py",
            "postauth_frame.py",
            "postauth_session.py",
            "transport.py",
            "wire.py",
            "typed_ingress.py",
            "epoch_store.py",
            "control_binding.py",
            "v2_transport.py",
        }
        actual_python = {
            key.rsplit("/", 1)[-1] for key in files if key.startswith("python:src/")
        }
        self.assertEqual(expected_python, actual_python)
        self.assertEqual(25, len(files))
        self.assertIn("python:tests/support/minecraft_v2_loopback_app.py", files)
        self.assertIn("harness-test:scripts/tests/test_v2_transport_loopback.py", files)

    def test_source_snapshot_rejects_omission_invalid_and_changed_hash(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "source.py"
            path.write_text("one", encoding="utf-8")
            with patch.object(
                loopback, "source_files", return_value={"python:a.py": path}
            ):
                first = loopback.source_snapshot(Path(), Path())
                path.write_text("two", encoding="utf-8")
                second = loopback.source_snapshot(Path(), Path())
                self.assertNotEqual(first, second)
            path.unlink()
            with patch.object(
                loopback, "source_files", return_value={"python:a.py": path}
            ):
                with self.assertRaisesRegex(
                    loopback.VerificationError, "REVIEWED_SOURCE_INVALID"
                ):
                    loopback.source_snapshot(Path(), Path())

    def test_loaded_identity_failclosed_for_omission_path_and_hash(self):
        expected = {"python:a.py": "a", "python:transport.py": "t", "java:b.java": "b"}
        files = {
            "python:a.py": Path("C:/agent/a.py"),
            "python:transport.py": Path("C:/agent/transport.py"),
            "java:b.java": Path("C:/forge/b.java"),
        }
        hashes = {"python:a.py": "a", "python:transport.py": "t"}
        paths = {key: str(files[key].resolve()) for key in hashes}
        loopback.validate_loaded(
            {"loadedModuleHashes": hashes, "loadedModulePaths": paths}, expected, files
        )
        bad_values = [
            ({"python:a.py": "a"}, {"python:a.py": paths["python:a.py"]}),
            ({**hashes, "python:transport.py": "wrong"}, paths),
            (hashes, {**paths, "python:transport.py": "wrong"}),
        ]
        for bad_hashes, bad_paths in bad_values:
            with self.assertRaisesRegex(
                loopback.VerificationError, "PYTHON_LOADED_SOURCE_IDENTITY_INVALID"
            ):
                loopback.validate_loaded(
                    {"loadedModuleHashes": bad_hashes, "loadedModulePaths": bad_paths},
                    expected,
                    files,
                )

    def test_wait_ready_distinguishes_start_prerequisite(self):
        process = Mock()
        process.poll.return_value = 1
        with self.assertRaisesRegex(
            loopback.PrerequisiteError, "PYTHON_LOOPBACK_START_FAILED"
        ):
            loopback.wait_ready(1, process, timeout=0.1)
        with patch.object(loopback, "build_opener") as opener:
            opener.return_value.open.return_value.__enter__.return_value.read.return_value = json.dumps(
                status()
            ).encode()
            process.poll.return_value = None
            loopback.wait_ready(1, process, timeout=0.1)
            self.assertEqual({}, opener.call_args.args[0].proxies)

    def test_wait_ready_propagates_malformed_or_disabled_status_as_failed(self):
        process = Mock()
        process.poll.return_value = None
        with patch.object(
            loopback,
            "read_status",
            side_effect=loopback.VerificationError("INVALID_LOOPBACK_STATUS_TYPES"),
        ):
            with self.assertRaisesRegex(
                loopback.VerificationError, "INVALID_LOOPBACK_STATUS_TYPES"
            ):
                loopback.wait_ready(1, process, timeout=0.1)
        with self.assertRaisesRegex(
            loopback.VerificationError, "INVALID_LOOPBACK_STATUS_VALUES"
        ):
            loopback.validate_status(status(enabled=False))

    def test_shutdown_receipt_accepts_only_fresh_complete_positive_cleanup(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "receipt.json"
            path.write_text(json.dumps(receipt()), encoding="utf-8")
            self.assertTrue(
                loopback.validate_shutdown_receipt(path, "nonce")["shutdownComplete"]
            )
            for changes, code in [
                ({"nonce": "stale"}, "SHUTDOWN_RECEIPT_INVALID"),
                (
                    {
                        "peerCloseFailures": 1,
                        "cleanupErrors": ["PEER_CLOSE_FAILED"],
                        "shutdownComplete": False,
                    },
                    "APP_CLEANUP_FAILED",
                ),
                (
                    {
                        "temporaryCleanupSucceeded": False,
                        "cleanupErrors": ["TEMPORARY_CLEANUP_FAILED"],
                        "shutdownComplete": False,
                    },
                    "APP_CLEANUP_FAILED",
                ),
            ]:
                path.write_text(json.dumps(receipt(**changes)), encoding="utf-8")
                with self.assertRaisesRegex(loopback.VerificationError, code):
                    loopback.validate_shutdown_receipt(path, "nonce")
            path.unlink()
            with self.assertRaisesRegex(
                loopback.VerificationError, "SHUTDOWN_RECEIPT_MISSING"
            ):
                loopback.validate_shutdown_receipt(path, "nonce")

    def test_shutdown_receipt_integer_fields_reject_non_exact_ints(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "receipt.json"
            for field in ("peerCloseAttempts", "peerCloseFailures"):
                for invalid in (True, False, 1.0, "1", None):
                    path.write_text(
                        json.dumps(receipt(**{field: invalid})), encoding="utf-8"
                    )
                    with self.assertRaisesRegex(
                        loopback.VerificationError, "SHUTDOWN_RECEIPT_INVALID"
                    ):
                        loopback.validate_shutdown_receipt(path, "nonce")

    def test_orderly_cleanup_requires_running_zero_exit_and_receipt(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "receipt.json"
            path.write_text(json.dumps(receipt()), encoding="utf-8")
            process = Mock()
            process.poll.return_value = None
            process.wait.return_value = 0
            with patch.object(loopback, "request_shutdown") as request:
                cleanup, value = loopback.orderly_cleanup(
                    process, 1, "token", path, "nonce"
                )
            request.assert_called_once_with(1, "token")
            self.assertEqual("ORDERLY_APP_SHUTDOWN_AND_PROCESS_EXITED", cleanup)
            self.assertTrue(value["shutdownComplete"])
            process.poll.return_value = 1
            with self.assertRaisesRegex(
                loopback.VerificationError, "UNEXPECTED_SERVER_EXIT"
            ):
                loopback.orderly_cleanup(process, 1, "token", path, "nonce")
            process.poll.return_value = None
            process.wait.return_value = 3
            with patch.object(loopback, "request_shutdown"):
                with self.assertRaisesRegex(
                    loopback.VerificationError, "ORDERLY_SHUTDOWN_NONZERO_EXIT"
                ):
                    loopback.orderly_cleanup(process, 1, "token", path, "nonce")

    def test_orderly_cleanup_rejects_timeout_and_shutdown_request_error(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "receipt.json"
            process = Mock()
            process.poll.return_value = None
            process.wait.side_effect = loopback.subprocess.TimeoutExpired("server", 15)
            with patch.object(loopback, "request_shutdown"):
                with self.assertRaisesRegex(
                    loopback.VerificationError, "ORDERLY_SHUTDOWN_TIMEOUT"
                ):
                    loopback.orderly_cleanup(process, 1, "token", path, "nonce")
            process.wait.side_effect = None
            with patch.object(
                loopback,
                "request_shutdown",
                side_effect=loopback.VerificationError("ORDERLY_SHUTDOWN_REJECTED"),
            ):
                with self.assertRaisesRegex(
                    loopback.VerificationError, "ORDERLY_SHUTDOWN_REJECTED"
                ):
                    loopback.orderly_cleanup(process, 1, "token", path, "nonce")

    def test_fallback_cleanup_terminates_kills_and_waits_only_owned_child(self):
        exited = Mock()
        exited.poll.return_value = 0
        self.assertEqual(
            "OWNED_PROCESS_EXITED_AND_WAITED", loopback.fallback_cleanup(exited)
        )
        running = Mock()
        running.poll.return_value = None
        self.assertEqual(
            "OWNED_PROCESS_TERMINATED_AND_WAITED", loopback.fallback_cleanup(running)
        )
        killed = Mock()
        killed.poll.return_value = None
        killed.wait.side_effect = [loopback.subprocess.TimeoutExpired("x", 5), 0]
        self.assertEqual(
            "OWNED_PROCESS_KILLED_AND_WAITED", loopback.fallback_cleanup(killed)
        )
        killed.kill.assert_called_once()

    def test_cli_reports_failed_symbolically_without_raw_exception(self):
        with tempfile.TemporaryDirectory() as temporary:
            report_path = Path(temporary) / "report.json"
            error = loopback.VerificationError("JAVA_TEST_FAILED", javaExitCode=1)
            with patch.object(loopback, "run", side_effect=error):
                code = loopback.main(
                    [
                        "--agent-server",
                        temporary,
                        "--forge",
                        temporary,
                        "--python",
                        temporary,
                        "--report",
                        str(report_path),
                    ]
                )
            value = json.loads(report_path.read_text(encoding="utf-8"))
            self.assertEqual(2, code)
            self.assertEqual("FAILED", value["status"])
            self.assertEqual("JAVA_TEST_FAILED", value["failure"])
            self.assertNotIn(str(Path(temporary)), json.dumps(value))

    def test_cli_reports_blocked_prerequisite_and_nonzero(self):
        with tempfile.TemporaryDirectory() as temporary:
            report_path = Path(temporary) / "report.json"
            with patch.object(
                loopback,
                "run",
                side_effect=loopback.PrerequisiteError("REQUIRED_EXECUTABLE_MISSING"),
            ):
                code = loopback.main(
                    [
                        "--agent-server",
                        temporary,
                        "--forge",
                        temporary,
                        "--python",
                        temporary,
                        "--report",
                        str(report_path),
                    ]
                )
            value = json.loads(report_path.read_text(encoding="utf-8"))
            self.assertEqual(2, code)
            self.assertEqual("BLOCKED", value["status"])
            self.assertEqual("REQUIRED_EXECUTABLE_MISSING", value["failure"])

    def test_read_status_rejects_nonobject_as_failed_gate(self):
        with patch.object(loopback, "build_opener") as opener:
            opener.return_value.open.return_value.__enter__.return_value.read.return_value = (
                b"[]"
            )
            with self.assertRaisesRegex(
                loopback.VerificationError, "INVALID_LOOPBACK_STATUS"
            ):
                loopback.read_status(1)

    def test_status_schema_types_and_integer_fields_are_strict(self):
        loopback.validate_status(status())
        malformed = [
            {**status(), "extra": 1},
            {key: value for key, value in status().items() if key != "enabled"},
            status(enabled=1),
            status(routeRegistered="true"),
            status(activeRoles=""),
            status(loadedModuleHashes=[]),
            status(loadedModulePaths={"x": 1}),
        ]
        for value in malformed:
            with self.assertRaises(loopback.VerificationError):
                loopback.validate_status(value)
        for field in ("pendingCount", "completedSessions", "applicationFrames"):
            for invalid in (True, False, 1.0, "1", None):
                with self.assertRaisesRegex(
                    loopback.VerificationError, "INVALID_LOOPBACK_STATUS_TYPES"
                ):
                    loopback.validate_status(status(**{field: invalid}))

    def test_status_json_rejects_duplicate_bom_and_oversize_with_bounded_read(self):
        for raw in (b'{"enabled":true,"enabled":true}', b"\xef\xbb\xbf{}"):
            with self.assertRaisesRegex(
                loopback.VerificationError, "INVALID_LOOPBACK_STATUS_JSON"
            ):
                loopback._decode_json_object(
                    raw, loopback.MAX_STATUS_BYTES, "INVALID_LOOPBACK_STATUS_JSON"
                )
        with patch.object(loopback, "build_opener") as opener:
            response = opener.return_value.open.return_value.__enter__.return_value
            response.read.return_value = b"x" * (loopback.MAX_STATUS_BYTES + 1)
            with self.assertRaisesRegex(
                loopback.VerificationError, "INVALID_LOOPBACK_STATUS_JSON"
            ):
                loopback.read_status(1)
            response.read.assert_called_once_with(loopback.MAX_STATUS_BYTES + 1)

    def test_shutdown_ack_is_bounded_and_exact(self):
        with patch.object(loopback, "build_opener") as opener:
            response = opener.return_value.open.return_value.__enter__.return_value
            response.status = 202
            for raw in (
                b'{"accepted":true,"extra":1}',
                b'{"accepted":1}',
                b'{"accepted":true,"accepted":true}',
                b"x" * (loopback.MAX_SHUTDOWN_ACK_BYTES + 1),
            ):
                response.read.return_value = raw
                with self.assertRaises(loopback.VerificationError):
                    loopback.request_shutdown(1, "a" * 64)
            self.assertTrue(
                all(
                    call.args == (loopback.MAX_SHUTDOWN_ACK_BYTES + 1,)
                    for call in response.read.call_args_list
                )
            )

    def test_support_shutdown_token_rejects_bad_length_nonascii_before_compare(self):
        spec = importlib.util.spec_from_file_location(
            "v2_loopback_support", SUPPORT_SCRIPT
        )
        support = importlib.util.module_from_spec(spec)
        assert spec.loader is not None
        spec.loader.exec_module(support)
        token = "a" * support.SHUTDOWN_TOKEN_HEX_LENGTH
        self.assertTrue(support._shutdown_token_matches(token, token))
        with patch.object(support.secrets, "compare_digest") as compare:
            for candidate in (None, "a" * 63, "a" * 65, "é" * 64, "G" * 64):
                self.assertFalse(support._shutdown_token_matches(candidate, token))
            compare.assert_not_called()


if __name__ == "__main__":
    unittest.main()
