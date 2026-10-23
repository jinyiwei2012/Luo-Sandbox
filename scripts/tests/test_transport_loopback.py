import importlib.util
import tempfile
import time
import unittest
from pathlib import Path
from unittest.mock import patch


SCRIPT = Path(__file__).resolve().parents[1] / "minecraft" / "verify_transport_loopback.py"
SPEC = importlib.util.spec_from_file_location("transport_loopback", SCRIPT)
loopback = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(loopback)


def xml(cases: str, output: str = "") -> str:
    return f'<testsuite failures="0" errors="0" skipped="0">{cases}<system-out>{output}</system-out></testsuite>'


class TransportLoopbackHarnessTest(unittest.TestCase):
    def test_gradle_command_forces_execution(self):
        command = loopback.gradle_command(Path("C:/sandbox/bridge/forge"))
        self.assertIn("--rerun-tasks", command)
        self.assertIn("*ControlTransportLoopbackTest", command)

    def test_missing_or_stale_xml_is_blocked(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "result.xml"
            with self.assertRaisesRegex(RuntimeError, "junit_missing_or_stale"):
                loopback.parse_junit(path, time.time())
            path.write_text(xml(""), encoding="utf-8")
            old = time.time() - 10
            path.touch()
            import os
            os.utime(path, (old, old))
            with self.assertRaisesRegex(RuntimeError, "junit_missing_or_stale"):
                loopback.parse_junit(path, time.time())

    def test_skipped_failed_or_wrong_role_xml_is_blocked(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "result.xml"
            path.write_text('<testsuite failures="1" errors="0" skipped="0"/>', encoding="utf-8")
            with self.assertRaisesRegex(RuntimeError, "junit_not_clean"):
                loopback.parse_junit(path, time.time() - 1)
            path.write_text(xml('<testcase name="SERVER_AUTHORITY"/><testcase name="OTHER"/>'), encoding="utf-8")
            with self.assertRaisesRegex(RuntimeError, "junit_roles_missing"):
                loopback.parse_junit(path, time.time() - 1)

    def test_fresh_two_role_metrics_are_authoritative(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "result.xml"
            metrics = '\n'.join('LOOPBACK_METRIC {"role":"%s","requiredDurationMs":2300,"measuredDurationMs":2300}' % role for role in sorted(loopback.REQUIRED_ROLES))
            cases = '<testcase name="SERVER_AUTHORITY"/><testcase name="CLIENT_CONTROLLER"/>'
            path.write_text(xml(cases, metrics), encoding="utf-8")
            result = loopback.parse_junit(path, time.time() - 1)
            self.assertEqual(sorted(loopback.REQUIRED_ROLES), result["roles"])

    def test_source_snapshot_uses_stable_unique_domain_keys_and_harness_content(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            server, forge = root / "server", root / "forge"
            harness = root / "harness.py"
            harness.write_text("first", encoding="utf-8")
            for path in loopback.source_files(server, forge, harness).values():
                if path == harness:
                    continue
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(path.name, encoding="utf-8")
            first = loopback.source_snapshot(server, forge, harness)
            self.assertIn("harness:scripts/minecraft/verify_transport_loopback.py", first)
            self.assertEqual(len(first), len(set(first)))
            harness.write_text("second", encoding="utf-8")
            second = loopback.source_snapshot(server, forge, harness)
            self.assertNotEqual(first["harness:scripts/minecraft/verify_transport_loopback.py"],
                                second["harness:scripts/minecraft/verify_transport_loopback.py"])

    def test_upstream_worktree_server_layout_is_resolved_for_sources_and_uvicorn(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            server = root / "server"
            server.mkdir()
            harness = root / "harness.py"
            harness.write_text("harness", encoding="utf-8")
            for path in loopback.source_files(root, root / "forge", harness).values():
                path.parent.mkdir(parents=True, exist_ok=True)
                if not path.exists():
                    path.write_text(path.name, encoding="utf-8")
            self.assertTrue(all("server" in str(path) for key, path in loopback.source_files(root, root / "forge", harness).items() if key.startswith("python:")))
            command = loopback.server_command(Path("python"), root, 8123)
            self.assertIn(str(server / "tests"), command)

    def test_startup_timeout_is_distinct_and_uses_proxy_free_opener(self):
        process = type("Process", (), {"poll": lambda self: None})()
        with tempfile.TemporaryDirectory() as temporary:
            with patch.object(loopback, "build_opener") as opener:
                opener.return_value.open.side_effect = OSError("unreachable")
                with self.assertRaisesRegex(loopback.StartupError, "python_loopback_timeout") as error:
                    loopback.wait_ready(1, process, Path(temporary) / "child.log", timeout=0)
            self.assertEqual("health_endpoint_not_ready_within_15s", error.exception.diagnostic)
            proxy_handler = opener.call_args.args[0]
            self.assertEqual({}, proxy_handler.proxies)

    def test_exited_startup_has_bounded_sanitized_diagnostic(self):
        with tempfile.TemporaryDirectory() as temporary:
            log = Path(temporary) / "child.log"
            self.assertEqual("child_exited_without_captured_log", loopback._startup_diagnostic(log))
            log.write_text("one\ntwo\n", encoding="utf-8")
            self.assertEqual("startup_log_captured:2", loopback._startup_diagnostic(log))

    def test_child_process_logs_to_owned_file_instead_of_undrained_pipe(self):
        source = SCRIPT.read_text(encoding="utf-8")
        child_invocation = source[source.index("server = subprocess.Popen") : source.index("started_at = time.time()")]
        self.assertNotIn("stdout=subprocess.PIPE", child_invocation)
        self.assertIn("stdout=child_output", child_invocation)

    def test_read_status_uses_proxy_free_opener(self):
        with patch.object(loopback, "build_opener") as opener:
            opener.return_value.open.return_value.__enter__.return_value.read.return_value = b'{"enabled":true}'
            self.assertEqual({"enabled": True}, loopback.read_status(1))
        self.assertEqual({}, opener.call_args.args[0].proxies)

    def test_loaded_module_hashes_rejects_mismatch(self):
        expected = {"python:src/adapter/minecraft/transport.py": "expected"}
        with patch.object(loopback, "read_status", return_value={"loadedModuleHashes": {**expected}}):
            self.assertEqual(expected, loopback.loaded_module_hashes(1, expected))
        with patch.object(loopback, "read_status", return_value={"loadedModuleHashes": {}}):
            with self.assertRaisesRegex(RuntimeError, "python_loaded_source_identity_invalid"):
                loopback.loaded_module_hashes(1, expected)


if __name__ == "__main__":
    unittest.main()
