"""Verify test-only Java/Python vectors for the non-normative post-auth v2 draft."""
from __future__ import annotations

import argparse
import base64
import hashlib
import hmac
import json
import re
from pathlib import Path
from typing import Any


SCHEMA = "postauth-v2-draft-vector-v1"
SECURITY = "luosandbox.control-auth.v1"
PROFILE = "LUO_CONTROL_POSTAUTH_V2"
KDF_DOMAIN = "LUO_CONTROL_SESSION_KEY_V2"
FRAME_DOMAIN = "LUO_CONTROL_FRAME_V2"
MAX_JSON_BYTES = 1_000_000
UUID_PATTERN = re.compile(
    r"(?!00000000-0000-0000-0000-000000000000\Z)"
    r"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\Z"
)
B32_PATTERN = re.compile(r"[A-Za-z0-9_-]{43}\Z")

DRAFT_PATH = "docs/decisions/minecraft-control-postauth-v2-draft.md"
CONTROL_AUTH_PATH = (
    "bridge/forge/src/main/java/io/github/jinyiwei2012/luosandbox/security/ControlAuth.java"
)
JAVA_TEST_PATH = (
    "bridge/forge/src/test/java/io/github/jinyiwei2012/luosandbox/security/"
    "PostAuthV2VectorTest.java"
)
PYTHON_TEST_PATH = "scripts/tests/test_postauth_v2_vectors.py"
SCRIPT_PATH = "scripts/minecraft/verify_postauth_v2_vectors.py"
SOURCE_PATHS = frozenset(
    {SCRIPT_PATH, PYTHON_TEST_PATH, JAVA_TEST_PATH, CONTROL_AUTH_PATH, DRAFT_PATH}
)
JAVA_TEST_FQCN = "io.github.jinyiwei2012.luosandbox.security.PostAuthV2VectorTest"
MAX_CLASS_RESOURCE_LENGTH = 2048
CONTROL_AUTH_CLASS_RESOURCE_SUFFIX = (
    "/io/github/jinyiwei2012/luosandbox/security/ControlAuth.class"
)

ROLE_INPUTS = {
    "CLIENT_CONTROLLER": {
        "peerInstallId": "client-dummy-1",
        "keyId": "dummy-client-key-1",
        "rolePskHex": bytes(range(32)).hex(),
        "peerNonceHex": bytes(range(32, 64)).hex(),
        "challengeId": "10000000-0000-4000-8000-000000000001",
        "connectionId": "20000000-0000-4000-8000-000000000001",
    },
    "SERVER_AUTHORITY": {
        "peerInstallId": "server-dummy-1",
        "keyId": "dummy-server-key-1",
        "rolePskHex": bytes(range(96, 128)).hex(),
        "peerNonceHex": bytes(range(128, 160)).hex(),
        "challengeId": "10000000-0000-4000-8000-000000000002",
        "connectionId": "20000000-0000-4000-8000-000000000002",
    },
}

COMMON_INPUTS = {
    "security": SECURITY,
    "hubInstallId": "hub-dummy-1",
    "protocol": "luosandbox.minecraft.v1",
    "modHashHex": bytes(range(160, 192)).hex(),
    "hubNonceHex": bytes(range(192, 224)).hex(),
    "issuedAt": "2026-10-23T00:00:00.000Z",
    "expiresAt": "2026-10-23T00:00:05.000Z",
    "configHashHex": bytes(range(224, 256)).hex(),
    "bindingHashHex": bytes(range(64, 96)).hex(),
}

ROUTE_ID = "30000000-0000-4000-8000-000000000001"
SERVER_BOOT_ID = "40000000-0000-4000-8000-000000000001"
GAME_CONNECTION_ID = "50000000-0000-4000-8000-000000000001"
BODY_ID = "60000000-0000-4000-8000-000000000001"
GAME_NONCE = base64.urlsafe_b64encode(bytes(range(16, 48))).decode("ascii").rstrip("=")
CHALLENGE_EXPIRES_AT = "2026-10-23T00:00:05.000Z"


def _unique_object(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("duplicate_json_key")
        result[key] = value
    return result


def lp_u32be(*values: bytes) -> bytes:
    output = bytearray()
    for value in values:
        if len(value) > 0xFFFFFFFF:
            raise ValueError("lp_field_too_large")
        output.extend(len(value).to_bytes(4, "big"))
        output.extend(value)
    return bytes(output)


def _ascii(value: str) -> bytes:
    try:
        encoded = value.encode("ascii")
    except UnicodeEncodeError as error:
        raise ValueError("non_ascii_field") from error
    if not encoded or b"\0" in encoded:
        raise ValueError("invalid_ascii_field")
    return encoded


def _raw32(value: str, field: str) -> bytes:
    if not isinstance(value, str) or not re.fullmatch(r"[0-9a-f]{64}", value):
        raise ValueError(f"invalid_{field}")
    return bytes.fromhex(value)


def b32(value: bytes) -> str:
    if len(value) != 32:
        raise ValueError("expected_raw32")
    return base64.urlsafe_b64encode(value).decode("ascii").rstrip("=")


def decode_b32(value: object) -> bytes:
    if not isinstance(value, str) or not B32_PATTERN.fullmatch(value):
        raise ValueError("invalid_b32")
    decoded = base64.urlsafe_b64decode(value + "=")
    if len(decoded) != 32 or b32(decoded) != value:
        raise ValueError("noncanonical_b32")
    return decoded


def canonical_json(value: dict[str, Any]) -> bytes:
    return json.dumps(value, separators=(",", ":"), ensure_ascii=True).encode("ascii")


def role_inputs(role: str) -> dict[str, str]:
    if role not in ROLE_INPUTS:
        raise ValueError("invalid_role")
    return {"peerRole": role, **COMMON_INPUTS, **ROLE_INPUTS[role]}


def auth_transcript(inputs: dict[str, str]) -> bytes:
    return lp_u32be(
        _ascii(SECURITY),
        b"PEER_PROOF",
        _ascii(inputs["peerRole"]),
        b"AGENT_HUB",
        _ascii(inputs["peerInstallId"]),
        _ascii(inputs["hubInstallId"]),
        _ascii(inputs["keyId"]),
        _ascii(inputs["protocol"]),
        _raw32(inputs["modHashHex"], "mod_hash"),
        _raw32(inputs["peerNonceHex"], "peer_nonce"),
        _raw32(inputs["hubNonceHex"], "hub_nonce"),
        _ascii(inputs["challengeId"]),
        _ascii(inputs["issuedAt"]),
        _ascii(inputs["expiresAt"]),
        _raw32(inputs["configHashHex"], "config_hash"),
        _raw32(inputs["bindingHashHex"], "binding_hash"),
    )


def derive(inputs: dict[str, str], direction: str) -> tuple[bytes, bytes, bytes, bytes]:
    if direction not in {"PEER_TO_HUB", "HUB_TO_PEER"}:
        raise ValueError("invalid_direction")
    transcript = auth_transcript(inputs)
    digest = hashlib.sha256(transcript).digest()
    derivation = lp_u32be(_ascii(KDF_DOMAIN), digest, _ascii(direction))
    key = hmac.new(_raw32(inputs["rolePskHex"], "role_psk"), derivation, hashlib.sha256).digest()
    return transcript, digest, derivation, key


def frame_input(
    inputs: dict[str, str], direction: str, sequence: int, message_kind: str,
    route_id: str, source_install_id: str, source_connection_id: str,
    recipient_connection_id: str, challenge_expires_at: str, payload: bytes,
    *, profile: str = PROFILE,
) -> bytes:
    if not 0 <= sequence <= 0x7FFFFFFFFFFFFFFF:
        raise ValueError("invalid_sequence")
    return lp_u32be(
        _ascii(FRAME_DOMAIN), _ascii(SECURITY), b"AUTHENTICATED_FRAME", _ascii(profile),
        _ascii(inputs["peerRole"]), _ascii(inputs["peerInstallId"]),
        _ascii(inputs["hubInstallId"]), _ascii(inputs["connectionId"]),
        _ascii(inputs["challengeId"]), _ascii(direction), sequence.to_bytes(8, "big"),
        _ascii(message_kind), route_id.encode("ascii"), source_install_id.encode("ascii"),
        source_connection_id.encode("ascii"), recipient_connection_id.encode("ascii"),
        challenge_expires_at.encode("ascii"), payload,
    )


def session_open_payload(connection_id: str) -> bytes:
    if not UUID_PATTERN.fullmatch(connection_id):
        raise ValueError("invalid_connection_id")
    return (
        b'{"security":"luosandbox.control-auth.v1","kind":"SESSION_OPEN","connectionId":"'
        + connection_id.encode("ascii") + b'"}'
    )


def _notice_payload() -> bytes:
    return canonical_json({
        "security": SECURITY, "kind": "BODY_BIND_NOTICE", "serverBootId": SERVER_BOOT_ID,
        "gameConnectionId": GAME_CONNECTION_ID, "bodyId": BODY_ID, "gameNonce": GAME_NONCE,
    })


def _candidate_payload() -> bytes:
    return canonical_json({
        "security": SECURITY, "kind": "BODY_BOUND", "serverBootId": SERVER_BOOT_ID,
        "gameConnectionId": GAME_CONNECTION_ID, "bodyId": BODY_ID,
    })


def _frame_specs(role: str, inputs: dict[str, str]) -> list[dict[str, Any]]:
    empty = {"routeId": "", "sourceInstallId": "", "sourceConnectionId": "",
             "recipientConnectionId": "", "challengeExpiresAt": ""}
    specs = [
        {"name": f"{role.lower().replace('_', '-')}-session-open", "direction": "HUB_TO_PEER", "sequence": 0,
         "messageKind": "SESSION_OPEN", "payload": session_open_payload(inputs["connectionId"]), **empty},
        {"name": f"{role.lower().replace('_', '-')}-heartbeat-peer-ack", "direction": "PEER_TO_HUB", "sequence": 0,
         "messageKind": "HEARTBEAT", "payload": b"{}", **empty},
        {"name": f"{role.lower().replace('_', '-')}-heartbeat-hub", "direction": "HUB_TO_PEER", "sequence": 1,
         "messageKind": "HEARTBEAT", "payload": b"{}", **empty},
    ]
    if role == "CLIENT_CONTROLLER":
        specs.append({"name": "client-controller-body-bind-notice", "direction": "PEER_TO_HUB",
                      "sequence": 1, "messageKind": "BODY_BIND_NOTICE",
                      "payload": _notice_payload(), **empty})
    else:
        specs.extend([
            {"name": "server-authority-body-bind-route", "direction": "HUB_TO_PEER", "sequence": 2,
             "messageKind": "BODY_BIND_ROUTE", "routeId": ROUTE_ID,
             "sourceInstallId": ROLE_INPUTS["CLIENT_CONTROLLER"]["peerInstallId"],
             "sourceConnectionId": ROLE_INPUTS["CLIENT_CONTROLLER"]["connectionId"],
             "recipientConnectionId": inputs["connectionId"], "challengeExpiresAt": "",
             "payload": _notice_payload()},
            {"name": "server-authority-body-bound-candidate", "direction": "PEER_TO_HUB", "sequence": 1,
             "messageKind": "BODY_BOUND_CANDIDATE", "routeId": ROUTE_ID,
             "sourceInstallId": "", "sourceConnectionId": "",
             "recipientConnectionId": inputs["connectionId"],
             "challengeExpiresAt": CHALLENGE_EXPIRES_AT, "payload": _candidate_payload()},
        ])
    return specs


def vectors() -> list[dict[str, Any]]:
    output = []
    for role in ("CLIENT_CONTROLLER", "SERVER_AUTHORITY"):
        inputs = role_inputs(role)
        for spec in _frame_specs(role, inputs):
            transcript, digest, derivation, key = derive(inputs, spec["direction"])
            mac_input = frame_input(
                inputs, spec["direction"], spec["sequence"], spec["messageKind"],
                spec["routeId"], spec["sourceInstallId"], spec["sourceConnectionId"],
                spec["recipientConnectionId"], spec["challengeExpiresAt"], spec["payload"],
            )
            mac = hmac.new(key, mac_input, hashlib.sha256).digest()
            output.append({
                "name": spec["name"], "peerRole": role, "direction": spec["direction"],
                "dummyRolePskHex": inputs["rolePskHex"], "inputs": inputs,
                "authTranscriptHex": transcript.hex(), "authDigestHex": digest.hex(),
                "keyDerivationInputHex": derivation.hex(), "derivedKeyHex": key.hex(),
                "sequence": spec["sequence"], "messageKind": spec["messageKind"],
                "routeId": spec["routeId"], "sourceInstallId": spec["sourceInstallId"],
                "sourceConnectionId": spec["sourceConnectionId"],
                "recipientConnectionId": spec["recipientConnectionId"],
                "challengeExpiresAt": spec["challengeExpiresAt"],
                "rawPayloadHex": spec["payload"].hex(), "frameMacInputHex": mac_input.hex(),
                "macHex": mac.hex(), "b32Proof": b32(mac),
            })
    return output


def validate_budget(raw_payload: bytes, encoded_message: bytes) -> None:
    if len(raw_payload) > 2048:
        raise ValueError("payload_too_large")
    if len(encoded_message) > 4096:
        raise ValueError("message_too_large")


def _budget_fixtures() -> dict[str, Any]:
    payload = bytes(index % 251 for index in range(2048))
    message = b"x" * 4096
    validate_budget(payload, message)
    return {
        "rawPayload2048": {"length": len(payload), "sha256": hashlib.sha256(payload).hexdigest()},
        "encodedMessage4096": {"length": len(message), "sha256": hashlib.sha256(message).hexdigest()},
        "note": "Independent size-boundary fixtures; not a general frame codec or wire-parser claim",
    }


def expected_document() -> dict[str, Any]:
    return {
        "schema": SCHEMA,
        "status": "DRAFT_NON_NORMATIVE_TEST_ONLY",
        "dummyCredentials": True,
        "limitations": [
            "cryptographic_composition_only_not_deployable_protocol_implementation",
            "does_not_test_complete_wire_parser_bounded_queue_or_runtime_currentness",
            "draft_change_invalidates_vectors",
        ],
        "vectors": vectors(),
        "budgetFixtures": _budget_fixtures(),
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
    _validate_source_hashes(result)
    return result


def _validate_source_hashes(value: object) -> None:
    if not isinstance(value, dict) or set(value) != SOURCE_PATHS:
        raise ValueError("source_hash_keyset_invalid")
    if any(not isinstance(digest, str) or not re.fullmatch(r"[0-9a-f]{64}", digest)
           for digest in value.values()):
        raise ValueError("source_hash_value_invalid")


def load_java_result(path: Path) -> dict[str, Any]:
    if not path.is_file() or path.is_symlink() or path.stat().st_size > MAX_JSON_BYTES:
        raise ValueError("java_result_missing_or_oversized")
    try:
        value = json.loads(path.read_text(encoding="utf-8"), object_pairs_hook=_unique_object)
    except (OSError, UnicodeError, json.JSONDecodeError) as error:
        raise ValueError("java_result_invalid_json") from error
    required = {"schema", "status", "dummyCredentials", "limitations", "vectors",
                "budgetFixtures", "javaArtifactIdentity", "invocation"}
    if not isinstance(value, dict) or set(value) != required or value.get("schema") != SCHEMA:
        raise ValueError("java_result_schema_invalid")
    if value.get("dummyCredentials") is not True or not isinstance(value.get("vectors"), list):
        raise ValueError("java_result_content_invalid")
    identity = value.get("javaArtifactIdentity")
    invocation = value.get("invocation")
    if not isinstance(identity, dict) or set(identity) != {"controlAuthClassResource", "controlAuthClassSha256"}:
        raise ValueError("java_artifact_identity_invalid")
    if not isinstance(invocation, dict) or set(invocation) != {"testClass", "forcedRerunRequired"}:
        raise ValueError("java_invocation_invalid")
    resource = identity.get("controlAuthClassResource")
    artifact_hash = identity.get("controlAuthClassSha256")
    if (not isinstance(resource, str) or not resource or len(resource) > MAX_CLASS_RESOURCE_LENGTH
            or "\x00" in resource or not resource.startswith(("file:/", "jar:file:/"))
            or not resource.replace("\\", "/").endswith(CONTROL_AUTH_CLASS_RESOURCE_SUFFIX)):
        raise ValueError("java_artifact_resource_invalid")
    if not isinstance(artifact_hash, str) or not re.fullmatch(r"[0-9a-f]{64}", artifact_hash):
        raise ValueError("java_artifact_hash_invalid")
    if invocation.get("testClass") != JAVA_TEST_FQCN:
        raise ValueError("java_invocation_test_class_invalid")
    if invocation.get("forcedRerunRequired") is not True:
        raise ValueError("java_invocation_forced_rerun_flag_invalid")
    for vector in value["vectors"]:
        if not isinstance(vector, dict) or set(vector) != set(vectors()[0]):
            raise ValueError("java_vector_schema_invalid")
        decode_b32(vector.get("b32Proof"))
        for field in ("authTranscriptHex", "authDigestHex", "keyDerivationInputHex", "derivedKeyHex",
                      "rawPayloadHex", "frameMacInputHex", "macHex"):
            try:
                bytes.fromhex(vector[field])
            except (KeyError, TypeError, ValueError) as error:
                raise ValueError(f"java_vector_invalid_{field}") from error
    return value


def compare_java_result(actual: dict[str, Any], expected: dict[str, Any]) -> list[str]:
    errors = []
    for field in ("schema", "status", "dummyCredentials", "limitations", "vectors", "budgetFixtures"):
        if actual[field] != expected[field]:
            errors.append(f"mismatch:{field}")
    return errors


def report(java_result: Path | None = None, *, source_root: Path | None = None) -> dict[str, Any]:
    expected = expected_document()
    result = {
        **expected,
        "sourceHashes": None,
        "pythonVector": {"status": "VALIDATED"},
        "crossLanguage": {"status": "NOT_EXECUTED", "errors": []},
        "javaArtifactIdentity": None,
        "invocation": None,
        "java_document_provenance": "NOT_PROVIDED",
        "provenance_limitations": [
            "loaded_class_hash_does_not_alone_prove_source_correspondence_or_freshness",
            "external_java_documents_can_be_forged_and_are_not_execution_attestation",
        ],
        "status": "PYTHON_VECTOR_VALIDATED",
    }
    try:
        root = source_root if source_root is not None else Path(__file__).resolve().parents[2]
        result["sourceHashes"] = _source_hashes(root)
    except ValueError as error:
        result["crossLanguage"] = {"status": "BLOCKED", "errors": [str(error)]}
        result["status"] = "BLOCKED"
        return result
    except OSError:
        result["crossLanguage"] = {"status": "BLOCKED", "errors": ["source_hash_gate_failed"]}
        result["status"] = "BLOCKED"
        return result
    except Exception:
        result["crossLanguage"] = {"status": "BLOCKED", "errors": ["source_hash_gate_failed"]}
        result["status"] = "BLOCKED"
        return result
    if java_result is None:
        return result
    try:
        actual = load_java_result(java_result)
        errors = compare_java_result(actual, expected)
    except ValueError as error:
        result["crossLanguage"] = {"status": "BLOCKED", "errors": [str(error)]}
        result["status"] = "BLOCKED"
        return result
    result["javaArtifactIdentity"] = actual["javaArtifactIdentity"]
    result["invocation"] = actual["invocation"]
    result["java_document_provenance"] = "UNATTESTED_EXTERNAL_INPUT"
    result["crossLanguage"] = {
        "status": "MATCHED_UNATTESTED" if not errors else "FAILED", "errors": errors
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
        "PYTHON_VECTOR_VALIDATED", "JAVA_DOCUMENT_MATCHED_UNATTESTED"
    } else 2


if __name__ == "__main__":
    raise SystemExit(main())
