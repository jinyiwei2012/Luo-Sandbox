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

    def test_schema_is_a_valid_draft_2020_12_schema(self):
        jsonschema.Draft202012Validator.check_schema(self.schema)

    def test_structural_fixtures_match_expected_schema_stage(self):
        for path in (CONTRACT / "valid").glob("*.json"):
            with self.subTest(path=path.name):
                self.validator.validate(json.loads(path.read_text(encoding="utf-8")))
        for path in (CONTRACT / "invalid").glob("*.json"):
            with self.subTest(path=path.name):
                with self.assertRaises(jsonschema.ValidationError):
                    self.validator.validate(json.loads(path.read_text(encoding="utf-8")))

    def test_valid_fixtures_cover_all_message_and_terminal_matrix_values(self):
        documents = [json.loads(path.read_text(encoding="utf-8")) for path in (CONTRACT / "valid").glob("*.json")]
        self.assertEqual(
            {document["type"] for document in documents},
            {"observation", "request", "admission", "cancel", "cancel_result", "receipt", "reconcile", "reconcile_result"},
        )
        self.assertEqual(
            {document["payload"]["status"] for document in documents if document["type"] == "receipt"},
            {"SUCCEEDED", "FAILED", "TIMED_OUT", "CANCELLED", "DISCONNECTED", "UNKNOWN"},
        )
        self.assertEqual(
            {document["payload"]["result"] for document in documents if document["type"] == "reconcile_result"},
            {"ADMISSION_REJECTED", "IN_FLIGHT", "KNOWN", "QUIESCENT", "BLOCKED", "UNKNOWN", "REJECTED"},
        )

    def test_manifest_hashes_all_json_fixture_bytes(self):
        manifest = json.loads((CONTRACT / "manifest.json").read_text(encoding="utf-8"))
        for entry in manifest["fixtures"]:
            with self.subTest(path=entry["path"]):
                self.assertEqual(hashlib.sha256((CONTRACT / entry["path"]).read_bytes()).hexdigest(), entry["sha256"])

    def test_invalid_utf8_fixture_is_not_text(self):
        raw = bytes.fromhex((CONTRACT / "invalid" / "raw_invalid_utf8.hex").read_text(encoding="ascii").strip())
        with self.assertRaises(UnicodeDecodeError):
            raw.decode("utf-8")

    def test_frozen_spec_hash(self):
        expected = "7ee44ed20446533628e745ba591e14392af9f1b8e7ba44c2155b3bf72d34f1cb"
        actual = hashlib.sha256((ROOT / "docs" / "decisions" / "minecraft-contract-v1.md").read_bytes()).hexdigest()
        self.assertEqual(actual, expected)
