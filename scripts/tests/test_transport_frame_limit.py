import importlib.util
import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch


SCRIPT = Path(__file__).resolve().parents[1] / "minecraft" / "verify_transport_frame_limit.py"
SPEC = importlib.util.spec_from_file_location("transport_frame_limit", SCRIPT)
frame_limit = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(frame_limit)


class TransportFrameLimitTest(unittest.TestCase):
    class FakeProcess:
        def __init__(self, *, exited=False, wait_results=()):
            self.exited = exited
            self.wait_results = iter(wait_results)
            self.terminated = False
            self.killed = False

        def poll(self):
            return 0 if self.exited else None

        def terminate(self):
            self.terminated = True

        def kill(self):
            self.killed = True

        def wait(self, *, timeout):
            result = next(self.wait_results)
            if isinstance(result, BaseException):
                raise result
            self.exited = True
            return result

    def test_server_command_is_single_worker_loopback_without_proxy_headers(self):
        command = frame_limit.server_command(Path("python"), Path("C:/agent/server"), 8123)
        self.assertIn("--workers", command)
        self.assertIn("1", command)
        self.assertIn("--no-proxy-headers", command)
        self.assertEqual("4096", command[command.index("--ws-max-size") + 1])

    def test_padded_hello_is_valid_json_at_requested_utf8_size(self):
        message = frame_limit.padded_hello("SERVER_AUTHORITY", 4096)
        self.assertEqual(4096, len(message.encode("utf-8")))
        self.assertEqual("HELLO", __import__("json").loads(message)["kind"])

    def test_missing_1009_is_a_failure_not_a_configured_pass(self):
        class Socket:
            async def send(self, _):
                pass

            async def recv(self):
                return "unexpected"

            async def __aenter__(self):
                return self

            async def __aexit__(self, *_):
                pass

        with patch("websockets.connect", return_value=Socket()):
            with self.assertRaisesRegex(frame_limit.FrameLimitError, "oversize_message_not_closed"):
                __import__("asyncio").run(frame_limit._oversize_probe("ws://test", False))

    def test_source_snapshot_rejects_missing_required_module(self):
        with tempfile.TemporaryDirectory() as temporary:
            with self.assertRaisesRegex(frame_limit.FrameLimitError, "reviewed_frame_limit_sources_missing"):
                frame_limit.source_snapshot(Path(temporary))

    def test_report_path_requires_fresh_interop_target(self):
        with tempfile.TemporaryDirectory() as temporary:
            with self.assertRaisesRegex(frame_limit.FrameLimitError, "report_path_not_fresh"):
                frame_limit._validated_report_path(Path(temporary) / "report.json")

    def test_owned_process_normal_termination_is_reported_accurately(self):
        process = self.FakeProcess(wait_results=(0,))
        self.assertEqual("terminated", frame_limit._cleanup_owned_process(process))
        self.assertTrue(process.terminated)
        self.assertFalse(process.killed)

    def test_owned_process_already_exited_is_reported_accurately(self):
        process = self.FakeProcess(exited=True)
        self.assertEqual("exited", frame_limit._cleanup_owned_process(process))
        self.assertFalse(process.terminated)
        self.assertFalse(process.killed)

    def test_owned_process_timeout_then_kill_is_reported_accurately(self):
        process = self.FakeProcess(wait_results=(subprocess.TimeoutExpired("wait", 5), 0))
        self.assertEqual("killed_after_terminate_timeout", frame_limit._cleanup_owned_process(process))
        self.assertTrue(process.terminated)
        self.assertTrue(process.killed)

    def test_owned_process_kill_wait_failure_cannot_pass(self):
        process = self.FakeProcess(
            wait_results=(subprocess.TimeoutExpired("wait", 5), subprocess.TimeoutExpired("wait", 5))
        )
        with self.assertRaisesRegex(frame_limit.FrameLimitError, "owned_process_kill_cleanup_failed"):
            frame_limit._cleanup_owned_process(process)


if __name__ == "__main__":
    unittest.main()
