import importlib.util
import json
import tempfile
import unittest
from pathlib import Path


SCRIPT = Path(__file__).resolve().parents[1] / "minecraft" / "verify_body_proof_vectors.py"
SPEC = importlib.util.spec_from_file_location("body_proof_vectors", SCRIPT)
vectors = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(vectors)


class BodyProofVectorTest(unittest.TestCase):
    def test_dummy_vector_has_fixed_lp11_order_and_known_answer(self):
        value = vectors.vector()
        self.assertEqual(
            "000000176c756f73616e64626f782e626f64792d62696e642e763100000011434c49454e545f424f44595f50524f4f460000000d7365727665722d746573742d310000000d636c69656e742d746573742d310000002430303030303030302d303030302d343030302d383030302d3030303030303030303130330000002430303030303030302d303030302d343030302d383030302d3030303030303030303130310000002430303030303030302d303030302d343030302d383030302d30303030303030303031303200000020202122232425262728292a2b2c2d2e2f303132333435363738393a3b3c3d3e3f00000018323032362d31302d32335430303a30303a30302e3030305a00000018323032362d31302d32335430303a30303a30352e3030305a00000020404142434445464748494a4b4c4d4e4f505152535455565758595a5b5c5d5e5f",
            value["transcriptHex"],
        )
        self.assertEqual("fc96e8da27702fc954f1d69aaae9277cf6b9d7cbbb655127542b2152421b8a30", value["hmacHex"])
        self.assertEqual("_Jbo2idwL8lU8daaquknfPa518u7ZVEnVCshUkIbijA", value["proof"])

    def test_lp_fields_preserve_raw_32_byte_nonce_and_hash(self):
        message = vectors.transcript(vectors.DUMMY_INPUTS)
        fields = []
        while message:
            size = int.from_bytes(message[:4], "big")
            fields.append(message[4:4 + size])
            message = message[4 + size:]
        self.assertEqual(11, len(fields))
        self.assertEqual(b"luosandbox.body-bind.v1", fields[0])
        self.assertEqual(b"CLIENT_BODY_PROOF", fields[1])
        self.assertEqual(bytes(range(32, 64)), fields[7])
        self.assertEqual(bytes(range(64, 96)), fields[10])

    def test_malformed_canonical_b32_and_invalid_body_uuids_are_rejected(self):
        invalid_b32 = dict(vectors.DUMMY_INPUTS, gameNonceB32="A" * 42 + "=")
        with self.assertRaisesRegex(ValueError, "invalid_game_nonce"):
            vectors.vector(invalid_b32)
        for body_id in ("body-1", "00000000-0000-4000-8000-00000000010A", "00000000-0000-0000-0000-000000000000"):
            with self.assertRaisesRegex(ValueError, "invalid_body_id"):
                vectors.vector(dict(vectors.DUMMY_INPUTS, bodyId=body_id))

    def test_different_valid_body_uuid_changes_the_known_answer(self):
        changed = vectors.vector(dict(vectors.DUMMY_INPUTS, bodyId="00000000-0000-4000-8000-000000000104"))
        self.assertNotEqual(vectors.vector()["proof"], changed["proof"])

    def test_missing_or_corrupt_java_result_is_blocked(self):
        with tempfile.TemporaryDirectory() as temporary:
            missing = vectors.report(Path(temporary) / "missing.json")
            self.assertEqual("BLOCKED", missing["crossLanguage"]["status"])
            corrupt = Path(temporary) / "corrupt.json"
            corrupt.write_text("{}", encoding="utf-8")
            self.assertEqual("BLOCKED", vectors.report(corrupt)["status"])

    def test_java_mismatched_input_or_output_fails_comparison(self):
        with tempfile.TemporaryDirectory() as temporary:
            expected = vectors.vector()
            mismatched = dict(expected)
            mismatched["inputs"] = dict(expected["inputs"], bodyId="00000000-0000-4000-8000-000000000104")
            path = Path(temporary) / "java.json"
            path.write_text(json.dumps(mismatched), encoding="utf-8")
            result = vectors.report(path)
            self.assertEqual("FAILED", result["crossLanguage"]["status"])
            self.assertIn("mismatch:inputs", result["crossLanguage"]["errors"])

    def test_no_java_result_is_python_validated_not_cross_language_pass(self):
        result = vectors.report()
        self.assertEqual("PYTHON_VECTOR_VALIDATED", result["status"])
        self.assertEqual("NOT_EXECUTED", result["crossLanguage"]["status"])


if __name__ == "__main__":
    unittest.main()
