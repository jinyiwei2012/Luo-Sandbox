"""Verify a raw-byte configHash fixture for the non-normative post-auth v2 draft."""
from __future__ import annotations

import argparse
import hashlib
import hmac
import json
import re
from pathlib import Path
from typing import Any


SCHEMA = "postauth-v2-config-fixture-v1"
STATUS = "TEST_FIXTURE_ONLY_NOT_PRODUCTION_DEPLOYMENT_CONFIG"
SECURITY = "luosandbox.control-auth.v1"
PROFILE = "LUO_CONTROL_POSTAUTH_V2"
KDF_DOMAIN = "LUO_CONTROL_SESSION_KEY_V2"
FRAME_DOMAIN = "LUO_CONTROL_FRAME_V2"
EXPECTED_FIELDS = {
    "security": SECURITY,
    "postAuthProfile": PROFILE,
    "sessionKdfDomain": KDF_DOMAIN,
    "frameMacDomain": FRAME_DOMAIN,
}
FIXTURE_PATH = "scripts/tests/fixtures/postauth_v2_config_raw.json"
SCRIPT_PATH = "scripts/minecraft/verify_postauth_v2_config_fixture.py"
PYTHON_TEST_PATH = "scripts/tests/test_postauth_v2_config_fixture.py"
JAVA_TEST_PATH = (
    "bridge/forge/src/test/java/io/github/jinyiwei2012/luosandbox/security/"
    "PostAuthV2ConfigHashTest.java"
)
CONTROL_AUTH_PATH = (
    "bridge/forge/src/main/java/io/github/jinyiwei2012/luosandbox/security/ControlAuth.java"
)
DRAFT_PATH = "docs/decisions/minecraft-control-postauth-v2-draft.md"
SOURCE_PATHS = frozenset({FIXTURE_PATH, SCRIPT_PATH, PYTHON_TEST_PATH, JAVA_TEST_PATH,
                          CONTROL_AUTH_PATH, DRAFT_PATH})
JAVA_TEST_FQCN = "io.github.jinyiwei2012.luosandbox.security.PostAuthV2ConfigHashTest"
CLASS_RESOURCE_SUFFIX = "/io/github/jinyiwei2012/luosandbox/security/ControlAuth.class"
MAX_JSON_BYTES = 1_000_000
MAX_RESOURCE_LENGTH = 2048

DUMMY = {
    "peerRole": "CLIENT_CONTROLLER",
    "peerInstallId": "client-config-dummy-1",
    "hubInstallId": "hub-config-dummy-1",
    "keyId": "dummy-config-key-1",
    "protocol": "luosandbox.minecraft.v1",
    "modHashHex": bytes(range(32, 64)).hex(),
    "peerNonceHex": bytes(range(64, 96)).hex(),
    "hubNonceHex": bytes(range(96, 128)).hex(),
    "challengeId": "70000000-0000-4000-8000-000000000001",
    "issuedAt": "2026-10-23T00:00:00.000Z",
    "expiresAt": "2026-10-23T00:00:05.000Z",
    "bindingHashHex": bytes(range(128, 160)).hex(),
    "dummyRolePskHex": bytes(range(32)).hex(),
}


def _unique_object(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("duplicate_json_key")
        result[key] = value
    return result


def parse_fixture(raw: bytes) -> dict[str, str]:
    if raw.startswith(b"\xef\xbb\xbf"):
        raise ValueError("fixture_bom_forbidden")
    try:
        text = raw.decode("utf-8", errors="strict")
        value = json.loads(text, object_pairs_hook=_unique_object)
    except UnicodeDecodeError as error:
        raise ValueError("fixture_invalid_utf8") from error
    except json.JSONDecodeError as error:
        raise ValueError("fixture_invalid_json") from error
    if not isinstance(value, dict) or set(value) != set(EXPECTED_FIELDS):
        raise ValueError("fixture_field_set_invalid")
    if any(not isinstance(item, str) for item in value.values()):
        raise ValueError("fixture_field_type_invalid")
    for field, expected in EXPECTED_FIELDS.items():
        if value[field] != expected:
            raise ValueError(f"fixture_{field}_invalid")
    return value


def lp_u32be(*fields: bytes) -> bytes:
    output = bytearray()
    for field in fields:
        output.extend(len(field).to_bytes(4, "big"))
        output.extend(field)
    return bytes(output)


def _ascii(value: str) -> bytes:
    try:
        return value.encode("ascii")
    except UnicodeEncodeError as error:
        raise ValueError("non_ascii_transcript_field") from error


def _raw32(value: str) -> bytes:
    if not re.fullmatch(r"[0-9a-f]{64}", value):
        raise ValueError("invalid_raw32")
    return bytes.fromhex(value)


def fixture_vector(raw: bytes) -> dict[str, Any]:
    fields = parse_fixture(raw)
    config_hash = hashlib.sha256(raw).digest()
    transcript = lp_u32be(
        _ascii(SECURITY), b"PEER_PROOF", _ascii(DUMMY["peerRole"]), b"AGENT_HUB",
        _ascii(DUMMY["peerInstallId"]), _ascii(DUMMY["hubInstallId"]),
        _ascii(DUMMY["keyId"]), _ascii(DUMMY["protocol"]),
        _raw32(DUMMY["modHashHex"]), _raw32(DUMMY["peerNonceHex"]),
        _raw32(DUMMY["hubNonceHex"]), _ascii(DUMMY["challengeId"]),
        _ascii(DUMMY["issuedAt"]), _ascii(DUMMY["expiresAt"]), config_hash,
        _raw32(DUMMY["bindingHashHex"]),
    )
    mac = hmac.new(_raw32(DUMMY["dummyRolePskHex"]), transcript, hashlib.sha256).digest()
    return {
        "fixtureFields": fields,
        "rawFixtureLength": len(raw),
        "rawFixtureSha256": config_hash.hex(),
        "dummyTranscriptInputs": {**DUMMY, "configHashHex": config_hash.hex()},
        "peerProofTranscriptHex": transcript.hex(),
        "dummyPeerProofHmacHex": mac.hex(),
    }


def expected_document(fixture: Path) -> dict[str, Any]:
    raw = fixture.read_bytes()
    return {
        "schema": SCHEMA,
        "status": STATUS,
        "claims": [
            "raw_fixture_bytes_sha256_bound_into_existing_controlauth_peer_proof_transcript",
            "fixture_key_order_and_single_lf_are_test_construction_not_normative_canonical_json",
            "does_not_prove_production_loader_allowlist_or_deployment_manifest",
        ],
        **fixture_vector(raw),
    }


def _source_hashes(root: Path) -> dict[str, str]:
    result = {}
    for relative in sorted(SOURCE_PATHS):
        path = root / relative
        if path.is_symlink():
            raise ValueError(f"source_is_symlink:{relative}")
        if not path.is_file():
            raise ValueError(f"source_missing_or_not_regular:{relative}")
        try:
            result[relative] = hashlib.sha256(path.read_bytes()).hexdigest()
        except OSError as error:
            raise ValueError(f"source_unreadable:{relative}") from error
    if set(result) != SOURCE_PATHS:
        raise ValueError("source_hash_keyset_invalid")
    return result


def load_java_result(path: Path) -> dict[str, Any]:
    if path.is_symlink() or not path.is_file() or path.stat().st_size > MAX_JSON_BYTES:
        raise ValueError("java_result_missing_or_oversized")
    try:
        value = json.loads(path.read_text(encoding="utf-8"), object_pairs_hook=_unique_object)
    except (OSError, UnicodeError, json.JSONDecodeError) as error:
        raise ValueError("java_result_invalid_json") from error
    required = set(expected_document_fields()) | {"javaArtifactIdentity", "invocation"}
    if not isinstance(value, dict) or set(value) != required:
        raise ValueError("java_result_schema_invalid")
    identity = value["javaArtifactIdentity"]
    invocation = value["invocation"]
    if not isinstance(identity, dict) or set(identity) != {
            "controlAuthClassResource", "controlAuthClassSha256"}:
        raise ValueError("java_artifact_identity_invalid")
    resource = identity["controlAuthClassResource"]
    digest = identity["controlAuthClassSha256"]
    if (not isinstance(resource, str) or not resource or len(resource) > MAX_RESOURCE_LENGTH
            or not resource.startswith(("file:/", "jar:file:/"))
            or not resource.replace("\\", "/").endswith(CLASS_RESOURCE_SUFFIX)):
        raise ValueError("java_artifact_resource_invalid")
    if not isinstance(digest, str) or not re.fullmatch(r"[0-9a-f]{64}", digest):
        raise ValueError("java_artifact_hash_invalid")
    if not isinstance(invocation, dict) or set(invocation) != {"testClass", "forcedRerunRequired"}:
        raise ValueError("java_invocation_invalid")
    if invocation["testClass"] != JAVA_TEST_FQCN or invocation["forcedRerunRequired"] is not True:
        raise ValueError("java_invocation_content_invalid")
    return value


def expected_document_fields() -> frozenset[str]:
    return frozenset({"schema", "status", "claims", "fixtureFields", "rawFixtureLength",
                      "rawFixtureSha256", "dummyTranscriptInputs", "peerProofTranscriptHex",
                      "dummyPeerProofHmacHex"})


def report(java_result: Path | None = None, *, root: Path | None = None) -> dict[str, Any]:
    repo = root if root is not None else Path(__file__).resolve().parents[2]
    try:
        expected = expected_document(repo / FIXTURE_PATH)
        source_hashes = _source_hashes(repo)
    except ValueError as error:
        return {"status": "BLOCKED", "crossLanguage": {"status": "BLOCKED", "errors": [str(error)]}}
    except OSError:
        return {"status": "BLOCKED", "crossLanguage": {"status": "BLOCKED", "errors": ["source_gate_failed"]}}
    result = {
        **expected,
        "sourceHashes": source_hashes,
        "crossLanguage": {"status": "NOT_EXECUTED", "errors": []},
        "java_document_provenance": "NOT_PROVIDED",
        "provenanceLimitations": [
            "external_java_document_is_not_execution_attestation",
            "loaded_class_hash_does_not_prove_source_correspondence_or_freshness",
            "fixture_is_not_a_frozen_production_deployment_manifest",
        ],
        "status": "PYTHON_FIXTURE_VALIDATED",
    }
    if java_result is None:
        return result
    try:
        actual = load_java_result(java_result)
    except ValueError as error:
        result["crossLanguage"] = {"status": "BLOCKED", "errors": [str(error)]}
        result["status"] = "BLOCKED"
        return result
    errors = [f"mismatch:{field}" for field in expected_document_fields()
              if actual[field] != expected[field]]
    result["javaArtifactIdentity"] = actual["javaArtifactIdentity"]
    result["invocation"] = actual["invocation"]
    result["java_document_provenance"] = "UNATTESTED_EXTERNAL_INPUT"
    result["crossLanguage"] = {
        "status": "MATCHED_UNATTESTED" if not errors else "FAILED", "errors": sorted(errors)
    }
    result["status"] = "JAVA_DOCUMENT_MATCHED_UNATTESTED" if not errors else "FAILED"
    return result


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--java-result", type=Path)
    parser.add_argument("--report", type=Path)
    args = parser.parse_args(argv)
    result = report(args.java_result)
    if args.report:
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(json.dumps(result, sort_keys=True) + "\n", encoding="utf-8")
    print(json.dumps({"crossLanguage": result["crossLanguage"]["status"],
                      "status": result["status"]}, sort_keys=True))
    return 0 if result["status"] in {
        "PYTHON_FIXTURE_VALIDATED", "JAVA_DOCUMENT_MATCHED_UNATTESTED"} else 2


if __name__ == "__main__":
    raise SystemExit(main())
