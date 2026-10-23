"""Create and compare the frozen LP11 dummy body-proof known-answer vector."""
from __future__ import annotations

import argparse
import base64
import hashlib
import hmac
import json
import re
import sys
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Any


SCHEMA = "body-proof-v1"
DOMAIN = "luosandbox.body-bind.v1"
LABEL = "CLIENT_BODY_PROOF"
MAX_JSON_BYTES = 65536
UTC_FORMAT = "%Y-%m-%dT%H:%M:%S.%fZ"
UTC_PATTERN = re.compile(r"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z\Z")
ID_PATTERN = re.compile(r"[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}\Z")
UUID_PATTERN = re.compile(r"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\Z")

DUMMY_INPUTS = {
    "domain": DOMAIN,
    "label": LABEL,
    "serverInstallId": "server-test-1",
    "clientInstallId": "client-test-1",
    "bodyId": "00000000-0000-4000-8000-000000000103",
    "serverBootId": "00000000-0000-4000-8000-000000000101",
    "gameConnectionId": "00000000-0000-4000-8000-000000000102",
    "gameNonceB32": base64.urlsafe_b64encode(bytes(range(32, 64))).decode("ascii").rstrip("="),
    "issuedAt": "2026-10-23T00:00:00.000Z",
    "expiresAt": "2026-10-23T00:00:05.000Z",
    "bindingHashHex": bytes(range(64, 96)).hex(),
    "keyHex": bytes(range(32)).hex(),
}
REQUIRED_INPUTS = frozenset(DUMMY_INPUTS)


def _unique_object(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("duplicate_json_key")
        result[key] = value
    return result


def _lp(value: bytes) -> bytes:
    if len(value) > 0xFFFFFFFF:
        raise ValueError("lp_field_too_large")
    return len(value).to_bytes(4, "big") + value


def _ascii_id(value: object, field: str) -> bytes:
    if not isinstance(value, str) or not ID_PATTERN.fullmatch(value):
        raise ValueError(f"invalid_{field}")
    try:
        return value.encode("ascii")
    except UnicodeEncodeError as error:
        raise ValueError(f"invalid_{field}") from error


def _uuid(value: object, field: str) -> bytes:
    if not isinstance(value, str) or value == "00000000-0000-0000-0000-000000000000" or not UUID_PATTERN.fullmatch(value):
        raise ValueError(f"invalid_{field}")
    return value.encode("ascii")


def _utc(value: object, field: str) -> bytes:
    if not isinstance(value, str) or not UTC_PATTERN.fullmatch(value):
        raise ValueError(f"invalid_{field}")
    try:
        parsed = datetime.strptime(value, UTC_FORMAT).replace(tzinfo=timezone.utc)
    except ValueError as error:
        raise ValueError(f"invalid_{field}") from error
    if parsed.microsecond % 1000 != 0:
        raise ValueError(f"invalid_{field}")
    return value.encode("ascii")


def _raw32_hex(value: object, field: str) -> bytes:
    if not isinstance(value, str) or not re.fullmatch(r"[0-9a-f]{64}", value):
        raise ValueError(f"invalid_{field}")
    return bytes.fromhex(value)


def b32_decode(value: object, field: str) -> bytes:
    if not isinstance(value, str) or not re.fullmatch(r"[A-Za-z0-9_-]{43}", value):
        raise ValueError(f"invalid_{field}")
    try:
        decoded = base64.urlsafe_b64decode(value + "=")
    except (ValueError, UnicodeError) as error:
        raise ValueError(f"invalid_{field}") from error
    canonical = base64.urlsafe_b64encode(decoded).decode("ascii").rstrip("=")
    if len(decoded) != 32 or canonical != value:
        raise ValueError(f"invalid_{field}")
    return decoded


def validate_inputs(inputs: object) -> dict[str, Any]:
    if not isinstance(inputs, dict) or set(inputs) != REQUIRED_INPUTS:
        raise ValueError("invalid_input_fields")
    if inputs["domain"] != DOMAIN or inputs["label"] != LABEL:
        raise ValueError("invalid_domain_or_label")
    _ascii_id(inputs["serverInstallId"], "server_install_id")
    _ascii_id(inputs["clientInstallId"], "client_install_id")
    _uuid(inputs["bodyId"], "body_id")
    _uuid(inputs["serverBootId"], "server_boot_id")
    _uuid(inputs["gameConnectionId"], "game_connection_id")
    b32_decode(inputs["gameNonceB32"], "game_nonce")
    _utc(inputs["issuedAt"], "issued_at")
    _utc(inputs["expiresAt"], "expires_at")
    issued = datetime.strptime(inputs["issuedAt"], UTC_FORMAT).replace(tzinfo=timezone.utc)
    expires = datetime.strptime(inputs["expiresAt"], UTC_FORMAT).replace(tzinfo=timezone.utc)
    if expires != issued + timedelta(seconds=5):
        raise ValueError("invalid_expiry")
    _raw32_hex(inputs["bindingHashHex"], "binding_hash")
    _raw32_hex(inputs["keyHex"], "key")
    return inputs


def transcript(inputs: object) -> bytes:
    value = validate_inputs(inputs)
    fields = (
        DOMAIN.encode("ascii"), LABEL.encode("ascii"),
        _ascii_id(value["serverInstallId"], "server_install_id"),
        _ascii_id(value["clientInstallId"], "client_install_id"),
        _uuid(value["bodyId"], "body_id"),
        _uuid(value["serverBootId"], "server_boot_id"),
        _uuid(value["gameConnectionId"], "game_connection_id"),
        b32_decode(value["gameNonceB32"], "game_nonce"),
        _utc(value["issuedAt"], "issued_at"), _utc(value["expiresAt"], "expires_at"),
        _raw32_hex(value["bindingHashHex"], "binding_hash"),
    )
    return b"".join(_lp(field) for field in fields)


def vector(inputs: object = DUMMY_INPUTS) -> dict[str, Any]:
    validated = validate_inputs(dict(inputs) if isinstance(inputs, dict) else inputs)
    message = transcript(validated)
    mac = hmac.new(_raw32_hex(validated["keyHex"], "key"), message, hashlib.sha256).digest()
    return {
        "schema": SCHEMA,
        "inputs": dict(validated),
        "transcriptHex": message.hex(),
        "hmacHex": mac.hex(),
        "proof": base64.urlsafe_b64encode(mac).decode("ascii").rstrip("="),
    }


def _source_hashes(root: Path) -> dict[str, str]:
    result = {"scripts/minecraft/verify_body_proof_vectors.py": hashlib.sha256(Path(__file__).read_bytes()).hexdigest()}
    for source in root.glob("bridge/forge/src/main/java/**/BodyProofAuth.java"):
        result[str(source.relative_to(root)).replace("\\", "/")] = hashlib.sha256(source.read_bytes()).hexdigest()
    return result


def load_java_result(path: Path) -> dict[str, Any]:
    if not path.is_file() or path.is_symlink() or path.stat().st_size > MAX_JSON_BYTES:
        raise ValueError("java_result_missing_or_oversized")
    try:
        value = json.loads(path.read_text(encoding="utf-8"), object_pairs_hook=_unique_object)
    except (OSError, UnicodeError, json.JSONDecodeError) as error:
        raise ValueError("java_result_invalid_json") from error
    required = {"schema", "inputs", "transcriptHex", "hmacHex", "proof"}
    if not isinstance(value, dict) or set(value) != required or value.get("schema") != SCHEMA:
        raise ValueError("java_result_schema_invalid")
    validate_inputs(value["inputs"])
    for name, width in (("transcriptHex", None), ("hmacHex", 64), ("proof", 43)):
        field = value[name]
        if not isinstance(field, str) or (width is not None and len(field) != width):
            raise ValueError(f"java_result_invalid_{name}")
    try:
        bytes.fromhex(value["transcriptHex"])
        _raw32_hex(value["hmacHex"], "java_hmac")
        b32_decode(value["proof"], "java_proof")
    except ValueError as error:
        raise ValueError("java_result_encoding_invalid") from error
    return value


def compare_java_result(actual: dict[str, Any], expected: dict[str, Any]) -> list[str]:
    errors = []
    for name in ("inputs", "transcriptHex", "hmacHex", "proof"):
        if actual[name] != expected[name]:
            errors.append(f"mismatch:{name}")
    return errors


def report(java_result: Path | None = None) -> dict[str, Any]:
    expected = vector()
    result: dict[str, Any] = {
        **expected,
        "claims": ["dummy_known_answer_not_live_connection"],
        "sourceHashes": _source_hashes(Path(__file__).resolve().parents[2]),
        "pythonVector": {"status": "VALIDATED"},
        "crossLanguage": {"status": "NOT_EXECUTED"},
        "status": "PYTHON_VECTOR_VALIDATED",
    }
    if java_result is None:
        return result
    try:
        errors = compare_java_result(load_java_result(java_result), expected)
    except ValueError as error:
        result["crossLanguage"] = {"status": "BLOCKED", "errors": [str(error)]}
        result["status"] = "BLOCKED"
        return result
    result["crossLanguage"] = {"status": "PASSED" if not errors else "FAILED", "errors": errors}
    result["status"] = "CROSS_LANGUAGE_MATCHED" if not errors else "FAILED"
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
    print(json.dumps({"crossLanguage": result["crossLanguage"]["status"], "status": result["status"]}, sort_keys=True))
    return 0 if result["status"] in {"PYTHON_VECTOR_VALIDATED", "CROSS_LANGUAGE_MATCHED"} else 2


if __name__ == "__main__":
    raise SystemExit(main())
