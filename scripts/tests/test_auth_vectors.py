import importlib.util
import json
import tempfile
import unittest
from pathlib import Path


SCRIPT = Path(__file__).resolve().parents[1] / "minecraft" / "verify_auth_vectors.py"
SPEC = importlib.util.spec_from_file_location("auth_vectors", SCRIPT)
vectors = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(vectors)


class AuthVectorRunnerTest(unittest.TestCase):
    def test_missing_java_output_is_blocked(self):
        with tempfile.TemporaryDirectory() as temporary:
            report = Path(temporary) / "report.json"
            result = vectors.main(["--agent-server", temporary, "--java-vectors", str(Path(temporary) / "missing.json"), "--report", str(report)])
            self.assertEqual(2, result)
            self.assertEqual("BLOCKED", json.loads(report.read_text(encoding="utf-8"))["status"])

    def test_tampered_vector_is_rejected_before_auth_module_use(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "vector.json"
            path.write_text(json.dumps({"vectors": {"HUB_PROOF": {}, "PEER_PROOF": {}}}), encoding="utf-8")
            with self.assertRaises(ValueError):
                vectors.load_vector(path)

    def test_duplicate_vector_label_is_rejected(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "vector.json"
            path.write_text('{"vectors":{"HUB_PROOF":{},"HUB_PROOF":{}}}', encoding="utf-8")
            with self.assertRaises(ValueError):
                vectors.load_vector(path)

    def test_fake_module_label_validation_is_not_actual_evidence(self):
        class FakeAuth:
            class ControlAuthError(ValueError):
                pass
        errors, checked = vectors.verify({"vectors": {}}, FakeAuth)
        self.assertEqual(["java_vector_parameters_invalid"], errors)
        self.assertEqual(0, checked)


if __name__ == "__main__":
    unittest.main()
