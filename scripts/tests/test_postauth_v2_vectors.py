import copy
import importlib.util
import io
import json
import tempfile
import unittest
from pathlib import Path
from unittest import mock
from contextlib import redirect_stdout


SCRIPT = Path(__file__).resolve().parents[1] / "minecraft" / "verify_postauth_v2_vectors.py"
SPEC = importlib.util.spec_from_file_location("postauth_v2_vectors", SCRIPT)
vectors = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(vectors)


class PostAuthV2VectorTest(unittest.TestCase):
    def test_vectors_cover_both_roles_directions_bootstrap_and_body_samples(self):
        values = vectors.vectors()
        self.assertEqual(9, len(values))
        self.assertEqual({"CLIENT_CONTROLLER", "SERVER_AUTHORITY"}, {v["peerRole"] for v in values})
        self.assertEqual({"PEER_TO_HUB", "HUB_TO_PEER"}, {v["direction"] for v in values})
        by_name = {v["name"]: v for v in values}
        for role in ("client-controller", "server-authority"):
            opened = by_name[f"{role}-session-open"]
            self.assertEqual(0, opened["sequence"])
            expected_payload = vectors.session_open_payload(opened["inputs"]["connectionId"])
            self.assertEqual(expected_payload.hex(), opened["rawPayloadHex"])
            ack = by_name[f"{role}-heartbeat-peer-ack"]
            self.assertEqual((0, "7b7d"), (ack["sequence"], ack["rawPayloadHex"]))
            hub = by_name[f"{role}-heartbeat-hub"]
            self.assertEqual((1, "7b7d"), (hub["sequence"], hub["rawPayloadHex"]))
        self.assertIn("client-controller-body-bind-notice", by_name)
        self.assertIn("server-authority-body-bind-route", by_name)
        candidate = by_name["server-authority-body-bound-candidate"]
        self.assertEqual(vectors.CHALLENGE_EXPIRES_AT, candidate["challengeExpiresAt"])
        self.assertNotEqual(vectors.BODY_ID, vectors.GAME_CONNECTION_ID)

    def test_lp16_digest_kdf_lp18_u64_and_mac_are_independently_recomputed(self):
        for value in vectors.vectors():
            transcript = bytes.fromhex(value["authTranscriptHex"])
            fields = self._split_lp(transcript)
            self.assertEqual(16, len(fields))
            self.assertEqual(b"PEER_PROOF", fields[1])
            self.assertEqual(value["inputs"]["peerRole"].encode(), fields[2])
            self.assertEqual(vectors.hashlib.sha256(transcript).hexdigest(), value["authDigestHex"])
            derivation = bytes.fromhex(value["keyDerivationInputHex"])
            self.assertEqual(3, len(self._split_lp(derivation)))
            key = vectors.hmac.new(bytes.fromhex(value["dummyRolePskHex"]), derivation,
                                   vectors.hashlib.sha256).digest()
            self.assertEqual(key.hex(), value["derivedKeyHex"])
            mac_input = bytes.fromhex(value["frameMacInputHex"])
            frame_fields = self._split_lp(mac_input)
            self.assertEqual(18, len(frame_fields))
            self.assertEqual(value["sequence"].to_bytes(8, "big"), frame_fields[10])
            mac = vectors.hmac.new(key, mac_input, vectors.hashlib.sha256).digest()
            self.assertEqual(mac.hex(), value["macHex"])
            self.assertEqual(vectors.b32(mac), value["b32Proof"])

    def test_field_role_direction_sequence_profile_and_expiry_tampering_changes_mac(self):
        original = vectors.vectors()[-1]
        inputs = original["inputs"]
        payload = bytes.fromhex(original["rawPayloadHex"])
        base = bytes.fromhex(original["frameMacInputHex"])
        mutations = [
            vectors.frame_input(inputs, original["direction"], original["sequence"] + 1,
                                original["messageKind"], original["routeId"],
                                original["sourceInstallId"], original["sourceConnectionId"],
                                original["recipientConnectionId"], original["challengeExpiresAt"], payload),
            vectors.frame_input(inputs, original["direction"], original["sequence"],
                                original["messageKind"], original["routeId"],
                                original["sourceInstallId"], original["sourceConnectionId"],
                                original["recipientConnectionId"], "2026-10-23T00:00:04.000Z", payload),
            vectors.frame_input(inputs, original["direction"], original["sequence"],
                                original["messageKind"], original["routeId"],
                                original["sourceInstallId"], original["sourceConnectionId"],
                                original["recipientConnectionId"], original["challengeExpiresAt"], payload,
                                profile="LUO_CONTROL_POSTAUTH_V2_ALIAS"),
        ]
        reflected = vectors.derive(inputs, "HUB_TO_PEER")[3]
        actual_key = bytes.fromhex(original["derivedKeyHex"])
        self.assertNotEqual(reflected, actual_key)
        changed_role = vectors.role_inputs("CLIENT_CONTROLLER")
        changed_role["connectionId"] = inputs["connectionId"]
        changed = vectors.frame_input(changed_role, original["direction"], original["sequence"],
                                      original["messageKind"], original["routeId"],
                                      original["sourceInstallId"], original["sourceConnectionId"],
                                      original["recipientConnectionId"], original["challengeExpiresAt"], payload)
        mutations.append(changed)
        original_mac = vectors.hmac.new(actual_key, base, vectors.hashlib.sha256).digest()
        for mutation in mutations:
            self.assertNotEqual(base, mutation)
            self.assertNotEqual(original_mac,
                                vectors.hmac.new(actual_key, mutation, vectors.hashlib.sha256).digest())

    def test_canonical_b32_and_uuid_validation(self):
        proof = vectors.vectors()[0]["b32Proof"]
        self.assertEqual(bytes.fromhex(vectors.vectors()[0]["macHex"]), vectors.decode_b32(proof))
        for invalid in (proof + "=", "A" * 42 + "=", "A" * 42 + "*"):
            with self.assertRaises(ValueError):
                vectors.decode_b32(invalid)
        for invalid in ("", "00000000-0000-0000-0000-000000000000", "BODY"):
            with self.assertRaises(ValueError):
                vectors.session_open_payload(invalid)

    def test_payload_and_whole_message_boundaries_are_independent(self):
        vectors.validate_budget(b"p" * 2048, b"m" * 4096)
        with self.assertRaisesRegex(ValueError, "payload_too_large"):
            vectors.validate_budget(b"p" * 2049, b"m" * 10)
        with self.assertRaisesRegex(ValueError, "message_too_large"):
            vectors.validate_budget(b"p" * 10, b"m" * 4097)

    def test_java_absence_is_not_pass_and_unknown_or_malformed_output_is_blocked(self):
        self.assertEqual("NOT_EXECUTED", vectors.report()["crossLanguage"]["status"])
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            self.assertEqual("BLOCKED", vectors.report(root / "missing.json")["status"])
            for name, value in (("malformed", "{"), ("unknown", json.dumps({"schema": vectors.SCHEMA,
                                                                              "unknown": True}))):
                path = root / f"{name}.json"
                path.write_text(value, encoding="utf-8")
                self.assertEqual("BLOCKED", vectors.report(path)["crossLanguage"]["status"])

    def test_source_hash_gate_requires_exact_regular_readable_nonsymlink_files(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            for relative in vectors.SOURCE_PATHS:
                path = root / relative
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(relative.encode("ascii"))
            hashes = vectors._source_hashes(root)
            self.assertEqual(vectors.SOURCE_PATHS, set(hashes))
            extra = root / "extra.txt"
            extra.write_text("ignored", encoding="ascii")
            self.assertEqual(vectors.SOURCE_PATHS, set(vectors._source_hashes(root)))

            missing = root / vectors.DRAFT_PATH
            missing.unlink()
            with self.assertRaisesRegex(ValueError, "source_missing_or_not_regular"):
                vectors._source_hashes(root)
            missing.write_text("draft", encoding="ascii")

            target = root / vectors.CONTROL_AUTH_PATH
            target.unlink()
            try:
                target.symlink_to(root / vectors.DRAFT_PATH)
            except OSError:
                self.skipTest("symlinks unavailable on this host")
            with self.assertRaisesRegex(ValueError, "source_is_symlink"):
                vectors._source_hashes(root)

    def test_source_hash_gate_blocks_unreadable_and_invalid_keysets(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            for relative in vectors.SOURCE_PATHS:
                path = root / relative
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(b"source")
            original = Path.read_bytes

            def fail_one(path):
                if str(path).replace("\\", "/").endswith(vectors.JAVA_TEST_PATH):
                    raise OSError("denied")
                return original(path)

            with mock.patch.object(Path, "read_bytes", fail_one):
                result = vectors.report(source_root=root)
            self.assertEqual("BLOCKED", result["status"])
            self.assertIn("source_unreadable", result["crossLanguage"]["errors"][0])

            valid = {relative: "ab" * 32 for relative in vectors.SOURCE_PATHS}
            vectors._validate_source_hashes(valid)
            missing = dict(valid)
            missing.pop(vectors.DRAFT_PATH)
            extra = {**valid, "unexpected/source": "ab" * 32}
            mutated = dict(valid)
            mutated["mutated/source"] = mutated.pop(vectors.DRAFT_PATH)
            bad_digest = dict(valid)
            bad_digest[vectors.DRAFT_PATH] = "AB" * 32
            for invalid in (missing, extra, mutated):
                with self.assertRaisesRegex(ValueError, "source_hash_keyset_invalid"):
                    vectors._validate_source_hashes(invalid)
            with self.assertRaisesRegex(ValueError, "source_hash_value_invalid"):
                vectors._validate_source_hashes(bad_digest)

    def test_unknown_source_hash_failure_is_sanitized_and_cli_returns_nonzero(self):
        output = io.StringIO()
        with mock.patch.object(vectors, "_source_hashes", side_effect=RuntimeError("secret detail")):
            with redirect_stdout(output):
                code = vectors.main([])
        self.assertEqual(2, code)
        value = json.loads(output.getvalue())
        self.assertEqual({"crossLanguage": "BLOCKED", "status": "BLOCKED"}, value)
        self.assertNotIn("secret detail", output.getvalue())

    def test_source_path_oserror_is_sanitized_in_report_and_cli(self):
        secret = "secret detail C:\\private\\secret"
        for method in ("is_file", "is_symlink"):
            with self.subTest(method=method):
                with mock.patch.object(Path, method, side_effect=OSError(secret)):
                    result = vectors.report()
                    output = io.StringIO()
                    with redirect_stdout(output):
                        code = vectors.main([])
                self.assertEqual("BLOCKED", result["status"])
                self.assertEqual(["source_hash_gate_failed"], result["crossLanguage"]["errors"])
                self.assertNotIn(secret, json.dumps(result))
                self.assertEqual(2, code)
                self.assertEqual(
                    {"crossLanguage": "BLOCKED", "status": "BLOCKED"},
                    json.loads(output.getvalue()),
                )
                self.assertNotIn(secret, output.getvalue())

    def test_java_mismatch_fails_full_vector_input_and_byte_comparison(self):
        with tempfile.TemporaryDirectory() as temporary:
            expected = vectors.expected_document()
            java = copy.deepcopy(expected)
            java["javaArtifactIdentity"] = {
                "controlAuthClassResource": "file:/dummy/io/github/jinyiwei2012/luosandbox/security/ControlAuth.class",
                "controlAuthClassSha256": "00" * 32,
            }
            java["invocation"] = {"testClass": vectors.JAVA_TEST_FQCN, "forcedRerunRequired": True}
            original = java["vectors"][0]["frameMacInputHex"]
            java["vectors"][0]["frameMacInputHex"] = original[:-1] + ("0" if original[-1] != "0" else "1")
            path = Path(temporary) / "java.json"
            path.write_text(json.dumps(java), encoding="utf-8")
            report = vectors.report(path)
            self.assertEqual("FAILED", report["crossLanguage"]["status"])
            self.assertIn("mismatch:vectors", report["crossLanguage"]["errors"])

    def test_well_formed_external_java_document_matches_but_remains_unattested(self):
        with tempfile.TemporaryDirectory() as temporary:
            java = vectors.expected_document()
            java["javaArtifactIdentity"] = {
                "controlAuthClassResource": "file:/dummy/io/github/jinyiwei2012/luosandbox/security/ControlAuth.class",
                "controlAuthClassSha256": "ab" * 32,
            }
            java["invocation"] = {
                "testClass": vectors.JAVA_TEST_FQCN,
                "forcedRerunRequired": True,
            }
            path = Path(temporary) / "java.json"
            path.write_text(json.dumps(java), encoding="utf-8")
            result = vectors.report(path)
            self.assertEqual("JAVA_DOCUMENT_MATCHED_UNATTESTED", result["status"])
            self.assertEqual("MATCHED_UNATTESTED", result["crossLanguage"]["status"])
            self.assertEqual("UNATTESTED_EXTERNAL_INPUT", result["java_document_provenance"])
            self.assertIn(
                "loaded_class_hash_does_not_alone_prove_source_correspondence_or_freshness",
                result["provenance_limitations"],
            )

    def test_java_metadata_is_strict_and_fail_closed(self):
        base = vectors.expected_document()
        base["javaArtifactIdentity"] = {
            "controlAuthClassResource": "file:/dummy/io/github/jinyiwei2012/luosandbox/security/ControlAuth.class",
            "controlAuthClassSha256": "ab" * 32,
        }
        base["invocation"] = {
            "testClass": vectors.JAVA_TEST_FQCN,
            "forcedRerunRequired": True,
        }
        mutations = []
        for field, value in (
            ("controlAuthClassSha256", "AB" * 32),
            ("controlAuthClassSha256", "a" * 63),
            ("controlAuthClassResource", ""),
            ("controlAuthClassResource", "arbitrary-identity"),
            ("controlAuthClassResource", "x" * (vectors.MAX_CLASS_RESOURCE_LENGTH + 1)),
        ):
            document = copy.deepcopy(base)
            document["javaArtifactIdentity"][field] = value
            mutations.append(document)
        for field, value in (
            ("testClass", "PostAuthV2VectorTest"),
            ("forcedRerunRequired", 1),
            ("forcedRerunRequired", False),
        ):
            document = copy.deepcopy(base)
            document["invocation"][field] = value
            mutations.append(document)
        for section in ("javaArtifactIdentity", "invocation"):
            document = copy.deepcopy(base)
            document[section]["unknown"] = "bad"
            mutations.append(document)
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "java.json"
            for document in mutations:
                path.write_text(json.dumps(document), encoding="utf-8")
                self.assertEqual("BLOCKED", vectors.report(path)["crossLanguage"]["status"])

    @staticmethod
    def _split_lp(value):
        fields = []
        while value:
            size = int.from_bytes(value[:4], "big")
            fields.append(value[4:4 + size])
            value = value[4 + size:]
        return fields


if __name__ == "__main__":
    unittest.main()
