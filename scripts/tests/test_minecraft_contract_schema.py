"""Structural checks for the frozen Minecraft v1 contract artifacts."""
import hashlib
import json
import unittest
from pathlib import Path

import jsonschema


ROOT = Path(__file__).parents[2]
CONTRACT = ROOT / "contracts" / "minecraft" / "v1"


class MinecraftContractSchemaTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.schema = json.loads((CONTRACT / "schema.json").read_text(encoding="utf-8"))
        cls.validator = jsonschema.Draft202012Validator(cls.schema)
        cls.manifest = json.loads((CONTRACT / "manifest.json").read_text(encoding="utf-8"))

    def test_schema_is_a_valid_draft_2020_12_schema(self):
        jsonschema.Draft202012Validator.check_schema(self.schema)

    def test_manifest_lists_every_fixture_exactly_once(self):
        listed = {entry["path"] for entry in self.manifest["fixtures"]}
        actual = {
            path.relative_to(CONTRACT).as_posix()
            for directory in (CONTRACT / "valid", CONTRACT / "invalid")
            for path in directory.glob("*.json")
        }
        self.assertEqual(listed, actual)
        raw_listed = {entry["path"] for entry in self.manifest["rawFixtures"]}
        raw_actual = {
            path.relative_to(CONTRACT).as_posix()
            for path in (CONTRACT / "invalid").glob("*.hex")
        } | {"invalid/raw_i63_exponent.txt"}
        self.assertEqual(raw_listed, raw_actual)

    def test_fixtures_match_their_declared_validation_layer(self):
        for entry in self.manifest["fixtures"]:
            document = json.loads((CONTRACT / entry["path"]).read_text(encoding="utf-8"))
            errors = list(self.validator.iter_errors(document))
            with self.subTest(path=entry["path"]):
                if entry["expectedLayer"] == "schema":
                    self.assertEqual(entry["expectedResult"], "accept" if not errors else "reject")
                else:
                    self.assertEqual(entry["expectedLayer"], "semantic")
                    self.assertEqual(entry["expectedResult"], "reject")
                    self.assertFalse(errors, "semantic fixtures must be structurally valid")

    def test_schema_enforces_targeted_contract_matrices(self):
        rejected = {
            "invalid/dimension_uppercase.json",
            "invalid/request_say_null_basis.json",
            "invalid/request_look_null_basis.json",
            "invalid/request_walk_null_basis.json",
            "invalid/admission_rejected_target_terminal.json",
            "invalid/cancel_result_already_terminal_backpressure.json",
            "invalid/cancel_result_not_found_backpressure.json",
            "invalid/reconcile_rejected_target_terminal.json",
            "invalid/receipt_succeeded_undetermined.json",
            "invalid/receipt_succeeded_missing_evidence.json",
            "invalid/receipt_timed_out_missing_evidence.json",
            "invalid/receipt_cancelled_missing_evidence.json",
            "invalid/receipt_disconnected_missing_evidence.json",
        }
        for relative in rejected:
            with self.subTest(path=relative):
                document = json.loads((CONTRACT / relative).read_text(encoding="utf-8"))
                self.assertTrue(list(self.validator.iter_errors(document)))
        decimal = json.loads((CONTRACT / "valid/observation_decimal_positions.json").read_text(encoding="utf-8"))
        self.validator.validate(decimal)

    def test_manifest_hashes_schema_and_all_fixture_bytes(self):
        self.assertEqual(
            hashlib.sha256((CONTRACT / "schema.json").read_bytes()).hexdigest(),
            self.manifest["schemaSha256"],
        )
        for entry in self.manifest["fixtures"] + self.manifest["rawFixtures"]:
            with self.subTest(path=entry["path"]):
                self.assertEqual(
                    hashlib.sha256((CONTRACT / entry["path"]).read_bytes()).hexdigest(),
                    entry["sha256"],
                )

    def test_invalid_utf8_fixture_is_not_text(self):
        raw = bytes.fromhex((CONTRACT / "invalid" / "raw_invalid_utf8.hex").read_text(encoding="ascii").strip())
        with self.assertRaises(UnicodeDecodeError):
            raw.decode("utf-8")

    def test_i63_exponent_fixture_is_a_semantic_not_schema_case(self):
        raw = (CONTRACT / "invalid" / "raw_i63_exponent.txt").read_text(encoding="ascii")
        self.assertIn('"sessionEpoch":0e0', raw)
        self.validator.validate(json.loads(raw))

    def test_frozen_spec_hash(self):
        expected = self.manifest["specSha256"]
        actual = hashlib.sha256((ROOT / "docs" / "decisions" / "minecraft-contract-v1.md").read_bytes()).hexdigest()
        self.assertEqual(actual, expected)


if __name__ == "__main__":
    unittest.main()
