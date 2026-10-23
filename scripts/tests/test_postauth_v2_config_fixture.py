import copy
import importlib.util
import json
import tempfile
import unittest
from pathlib import Path


SCRIPT = Path(__file__).resolve().parents[1] / "minecraft" / "verify_postauth_v2_config_fixture.py"
SPEC = importlib.util.spec_from_file_location("postauth_v2_config_fixture", SCRIPT)
fixture = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(fixture)
REPO = Path(__file__).resolve().parents[2]
RAW_FIXTURE = REPO / fixture.FIXTURE_PATH


class PostAuthV2ConfigFixtureTest(unittest.TestCase):
    def test_checked_in_fixture_is_exact_ascii_single_line_one_lf_and_known_answer(self):
        raw = RAW_FIXTURE.read_bytes()
        self.assertTrue(raw.endswith(b"\n"))
        self.assertNotIn(b"\r", raw)
        self.assertEqual(1, raw.count(b"\n"))
        raw.decode("ascii")
        value = fixture.fixture_vector(raw)
        self.assertEqual(174, value["rawFixtureLength"])
        self.assertEqual(
            "ccc9c87688d504d256a6671ee24f270dac9f08bc6595a3cbd326f3093e172ac1",
            value["rawFixtureSha256"],
        )
        self.assertEqual(16, self._lp_count(bytes.fromhex(value["peerProofTranscriptHex"])))
        self.assertEqual(value["rawFixtureSha256"], value["dummyTranscriptInputs"]["configHashHex"])

    def test_parser_rejects_duplicate_unknown_missing_type_bom_utf8_and_wrong_literals(self):
        valid = fixture.EXPECTED_FIELDS
        invalid = [
            b'{"security":"a","security":"b","postAuthProfile":"x","sessionKdfDomain":"y","frameMacDomain":"z"}',
            json.dumps({**valid, "unknown": "x"}).encode(),
            json.dumps({key: value for key, value in valid.items() if key != "security"}).encode(),
            json.dumps({**valid, "security": 1}).encode(),
            b"\xef\xbb\xbf" + json.dumps(valid).encode(),
            b"\xff",
        ]
        for field in valid:
            invalid.append(json.dumps({**valid, field: valid[field] + "_ALIAS"}).encode())
        for raw in invalid:
            with self.assertRaises(ValueError):
                fixture.parse_fixture(raw)

    def test_same_semantics_different_raw_bytes_change_hash_transcript_and_hmac(self):
        original = fixture.fixture_vector(RAW_FIXTURE.read_bytes())
        alternate_raw = json.dumps(fixture.EXPECTED_FIELDS, indent=2).encode("ascii") + b"\n"
        alternate = fixture.fixture_vector(alternate_raw)
        self.assertEqual(original["fixtureFields"], alternate["fixtureFields"])
        for field in ("rawFixtureSha256", "peerProofTranscriptHex", "dummyPeerProofHmacHex"):
            self.assertNotEqual(original[field], alternate[field])

    def test_mod_binding_hashes_are_independent_from_raw_config_hash(self):
        value = fixture.fixture_vector(RAW_FIXTURE.read_bytes())
        inputs = value["dummyTranscriptInputs"]
        self.assertNotEqual(inputs["configHashHex"], inputs["modHashHex"])
        self.assertNotEqual(inputs["configHashHex"], inputs["bindingHashHex"])
        self.assertNotEqual(inputs["modHashHex"], inputs["bindingHashHex"])

    def test_source_gate_requires_exact_six_regular_nonsymlink_files(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            for relative in fixture.SOURCE_PATHS:
                path = root / relative
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(b"source")
            self.assertEqual(fixture.SOURCE_PATHS, set(fixture._source_hashes(root)))
            (root / fixture.DRAFT_PATH).unlink()
            with self.assertRaisesRegex(ValueError, "source_missing_or_not_regular"):
                fixture._source_hashes(root)

    def test_no_java_is_not_executed_and_missing_or_malformed_is_blocked(self):
        self.assertEqual("NOT_EXECUTED", fixture.report()["crossLanguage"]["status"])
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            self.assertEqual("BLOCKED", fixture.report(root / "missing.json")["status"])
            bad = root / "bad.json"
            bad.write_text("{}", encoding="utf-8")
            self.assertEqual("BLOCKED", fixture.report(bad)["status"])

    def test_external_java_document_match_is_unattested_and_mismatch_fails(self):
        with tempfile.TemporaryDirectory() as temporary:
            expected = fixture.expected_document(RAW_FIXTURE)
            document = {
                **expected,
                "javaArtifactIdentity": {
                    "controlAuthClassResource": "file:/dummy/io/github/jinyiwei2012/luosandbox/security/ControlAuth.class",
                    "controlAuthClassSha256": "ab" * 32,
                },
                "invocation": {"testClass": fixture.JAVA_TEST_FQCN, "forcedRerunRequired": True},
            }
            path = Path(temporary) / "java.json"
            path.write_text(json.dumps(document), encoding="utf-8")
            matched = fixture.report(path)
            self.assertEqual("JAVA_DOCUMENT_MATCHED_UNATTESTED", matched["status"])
            self.assertEqual("UNATTESTED_EXTERNAL_INPUT", matched["java_document_provenance"])
            changed = copy.deepcopy(document)
            changed["rawFixtureLength"] += 1
            path.write_text(json.dumps(changed), encoding="utf-8")
            self.assertEqual("FAILED", fixture.report(path)["status"])

    @staticmethod
    def _lp_count(value):
        count = 0
        while value:
            length = int.from_bytes(value[:4], "big")
            value = value[4 + length:]
            count += 1
        return count


if __name__ == "__main__":
    unittest.main()
