import importlib.util
import json
import tempfile
import unittest
from pathlib import Path


SCRIPT = Path(__file__).resolve().parents[1] / "minecraft" / "verify_contract_interop.py"
SPEC = importlib.util.spec_from_file_location("interop", SCRIPT)
interop = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(interop)


class InteropRunnerTest(unittest.TestCase):
    def test_expected_valid_ignores_negative_corpus(self):
        fixtures = [{"path": "valid/one.json", "expectedResult": "accept"}, {"path": "invalid/two.json", "expectedResult": "reject"}]
        self.assertEqual({"one"}, set(interop.expected_valid({"fixtures": fixtures})))

    def test_output_discovery_excludes_index_and_oversized_bytes(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / "nested").mkdir()
            (root / "nested" / "one.json").write_text("{}", encoding="utf-8")
            (root / "index.json").write_text("{}", encoding="utf-8")
            (root / "big.json").write_bytes(b"x" * (interop.MAX_BYTES + 1))
            outputs, errors = interop.output_files(root)
            self.assertEqual({"one"}, set(outputs))
            self.assertEqual(["java_output_oversized"], errors)

    def test_output_discovery_rejects_duplicate_stems(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / "nested").mkdir()
            (root / "one.json").write_text("{}", encoding="utf-8")
            (root / "nested" / "one.json").write_text("{}", encoding="utf-8")
            _, errors = interop.output_files(root)
            self.assertIn("duplicate_java_output_stem", errors)

    def test_blocked_without_java_output_writes_non_secret_summary(self):
        with tempfile.TemporaryDirectory() as temporary:
            report = Path(temporary) / "report.json"
            result = interop.main([
                "--agent-server", str(Path(temporary) / "server"), "--java-output", str(Path(temporary) / "missing"), "--report", str(report),
            ])
            self.assertEqual(2, result)
            self.assertEqual("BLOCKED", json.loads(report.read_text(encoding="utf-8"))["status"])

    def test_manifest_relative_path_rejects_traversal(self):
        self.assertFalse(interop._safe_relative("../outside.json"))
        self.assertFalse(interop._safe_relative("C:/outside.json"))
        self.assertTrue(interop._safe_relative("valid/item.json"))

    def test_prepare_python_output_refuses_stale_directory(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / "valid").mkdir()
            (root / "valid" / "one.json").write_bytes(b"{}")
            (root / "manifest.json").write_text(json.dumps({
                "specSha256": "spec", "fixtures": [{"path": "valid/one.json", "expectedResult": "accept"}],
            }), encoding="utf-8")
            output = root / "output"
            output.mkdir()
            (output / "old.json").write_text("{}", encoding="utf-8")
            errors, prepared = interop.prepare_python_output(root, output, lambda value: value, lambda value: value)
            self.assertEqual(["python_output_inventory_mismatch"], errors)
            self.assertEqual(0, prepared)

    def test_prepare_python_output_verifies_existing_identical_artifacts(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            contract = root / "contract"
            (contract / "valid").mkdir(parents=True)
            fixture = b'{"fixture":true}'
            (contract / "valid" / "one.json").write_bytes(fixture)
            (contract / "manifest.json").write_text(json.dumps({
                "specSha256": "spec", "fixtures": [{"path": "valid/one.json", "expectedResult": "accept"}],
            }), encoding="utf-8")
            output = root / "output"
            decoder = lambda value: value
            encoder = lambda value: value
            self.assertEqual(([], 1), interop.prepare_python_output(contract, output, decoder, encoder))
            self.assertEqual(([], 1), interop.prepare_python_output(contract, output, decoder, encoder))
            original = (output / "one.json").read_bytes()
            (output / "one.json").write_bytes(b"changed")
            errors, _ = interop.prepare_python_output(contract, output, decoder, encoder)
            self.assertEqual(["python_output_content_mismatch"], errors)
            self.assertEqual(b"changed", (output / "one.json").read_bytes())

    def test_reverse_proof_rejects_missing_and_stale_index(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            self.assertEqual(["missing_java_reverse_proof"], interop.verify_reverse_proof(root, root, root / "proof.json"))


if __name__ == "__main__":
    unittest.main()
