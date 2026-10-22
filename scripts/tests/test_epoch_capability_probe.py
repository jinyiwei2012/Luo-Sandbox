import importlib.util
import json
import os
import subprocess
import tempfile
import unittest
from types import SimpleNamespace
from pathlib import Path


SCRIPT = Path(__file__).resolve().parents[1] / "minecraft" / "probe_epoch_capability.py"
SPEC = importlib.util.spec_from_file_location("epoch_probe", SCRIPT)
probe = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(probe)


class EpochCapabilityProbeTest(unittest.TestCase):
    def test_missing_target_is_blocked_without_creation(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            missing = root / "missing"
            self.assertEqual(2, probe.main(["--agent-server", str(root), "--directory", str(missing)]))
            self.assertFalse(missing.exists())

    def test_empty_scratch_must_not_contain_target_or_server(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            target, server, scratch = root / "target", root / "server", root / "scratch"
            target.mkdir()
            server.mkdir()
            scratch.mkdir()
            self.assertTrue(probe._validate_scratch(scratch, target, server).samefile(scratch))
            with self.assertRaises(ValueError):
                probe._validate_scratch(target, target, server)

    def test_report_refuses_existing_target_file_without_modification(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            target, server = root / "target", root / "server"
            target.mkdir()
            server.mkdir()
            epoch = target / "epochs.sqlite3"
            epoch.write_bytes(b"unchanged")
            with self.assertRaises(ValueError):
                probe._validate_report(epoch, target.resolve(), server.resolve(), None)
            self.assertEqual(b"unchanged", epoch.read_bytes())

    def test_existing_report_is_refused(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            target, server = root / "target", root / "server"
            target.mkdir()
            server.mkdir()
            report = root / "report.json"
            report.write_text("old", encoding="utf-8")
            with self.assertRaises(ValueError):
                probe._validate_report(report, target.resolve(), server.resolve(), None)
            self.assertEqual("old", report.read_text(encoding="utf-8"))

    def test_module_load_failure_is_distinct_from_native_unavailable(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            result = probe.probe(root, root, False, None)
            self.assertEqual("BLOCKED", result["status"])
            self.assertEqual("TOOL_MODULE_LOAD_FAILED", result["diagnostic"])

    def test_child_api_error_is_not_lock_rejection(self):
        self.assertNotEqual("REJECTED", "UNKNOWN")

    def test_lstat_error_fails_closed(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary)
            original = Path.lstat
            try:
                Path.lstat = lambda _self: (_ for _ in ()).throw(OSError("blocked"))
                self.assertFalse(probe._path_chain_safe(path))
            finally:
                Path.lstat = original

    def test_reparse_attribute_is_detected_from_lstat(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary)
            original = Path.lstat
            try:
                Path.lstat = lambda _self: SimpleNamespace(st_file_attributes=0x400)
                self.assertTrue(probe._is_reparse_point(path))
            finally:
                Path.lstat = original

    @unittest.skipUnless(os.name == "nt", "Windows junction semantics only")
    def test_real_junction_blocks_scratch_and_report_without_target_writes(self):
        root = Path(os.environ.get("LOCALAPPDATA", tempfile.gettempdir())) / "Temp" / "opencode"
        root.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(dir=root) as temporary:
            owned = Path(temporary)
            target, server, junction = owned / "target", owned / "server", owned / "junction"
            target.mkdir()
            server.mkdir()
            created = subprocess.run(["cmd", "/c", "mklink", "/J", str(junction), str(target)], capture_output=True, timeout=5)
            if created.returncode != 0:
                self.skipTest("mklink /J unavailable for current account")
            try:
                self.assertFalse(probe._path_chain_safe(junction))
                with self.assertRaises(ValueError):
                    probe._validate_scratch(junction, target.absolute(), server.absolute())
                with self.assertRaises(ValueError):
                    probe._validate_report(junction / "report.json", target.absolute(), server.absolute(), None)
                self.assertEqual([], list(target.iterdir()))
            finally:
                os.rmdir(junction)


if __name__ == "__main__":
    unittest.main()
