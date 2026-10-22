"""Bounded offline verifier for Java control-auth dummy vectors."""
from __future__ import annotations

import argparse
import hashlib
import importlib.util
import json
import sys
from datetime import datetime, timezone
from pathlib import Path
from typing import Any


MAX_BYTES = 65536
LABELS = ("HUB_PROOF", "PEER_PROOF", "READY_PROOF")
PEER_MAC = "95141dc1c381eb134b3d1f3df405d6e2ee2058e31f99b1269348b4f56f2a4921"


def load_control_auth(agent_server: Path):
    source = agent_server / "src" / "adapter" / "minecraft" / "control_auth.py"
    if not source.is_file():
        raise ValueError("control_auth_source_missing")
    spec = importlib.util.spec_from_file_location("auth_vector_control_auth", source)
    if spec is None or spec.loader is None:
        raise ValueError("control_auth_source_unloadable")
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


def load_vector(path: Path) -> dict[str, Any]:
    if not path.is_file() or path.is_symlink() or path.stat().st_size > MAX_BYTES:
        raise ValueError("java_vector_missing_or_oversized")
    try:
        data = json.loads(path.read_text(encoding="utf-8"), object_pairs_hook=_unique_object)
    except (OSError, UnicodeError, json.JSONDecodeError) as error:
        raise ValueError("java_vector_invalid_json") from error
    required = {
        "keyHex", "peerRole", "peerInstallId", "hubInstallId", "keyId", "protocol", "modHashHex",
        "peerNonceHex", "hubNonceHex", "challengeId", "issuedAt", "expiresAt", "configHashHex",
        "bindingHashHex", "vectors",
    }
    if not isinstance(data, dict) or set(data) != required or not isinstance(data["vectors"], dict) \
            or set(data["vectors"]) != set(LABELS):
        raise ValueError("java_vector_labels_invalid")
    return data


def _unique_object(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("duplicate_json_key")
        result[key] = value
    return result


def verify(vector: dict[str, Any], control_auth: Any) -> tuple[list[str], int]:
    try:
        context = control_auth.ControlAuthContext(
            peer_role=vector["peerRole"], peer_install_id=vector["peerInstallId"],
            hub_install_id=vector["hubInstallId"], key_id=vector["keyId"], protocol=vector["protocol"],
            mod_hash=bytes.fromhex(vector["modHashHex"]), config_hash=bytes.fromhex(vector["configHashHex"]),
            binding_hash=bytes.fromhex(vector["bindingHashHex"]),
        )
        key = bytes.fromhex(vector["keyHex"])
        peer_nonce = bytes.fromhex(vector["peerNonceHex"])
        hub_nonce = bytes.fromhex(vector["hubNonceHex"])
        issued = datetime.strptime(vector["issuedAt"], "%Y-%m-%dT%H:%M:%S.%fZ").replace(tzinfo=timezone.utc)
        expires = datetime.strptime(vector["expiresAt"], "%Y-%m-%dT%H:%M:%S.%fZ").replace(tzinfo=timezone.utc)
        challenge_id = vector["challengeId"]
    except (AttributeError, KeyError, TypeError, ValueError, control_auth.ControlAuthError):
        return ["java_vector_parameters_invalid"], 0
    errors: list[str] = []
    checked = 0
    for label in LABELS:
        item = vector["vectors"].get(label)
        if not isinstance(item, dict) or set(item) != {"transcriptHex", "macHex"}:
            errors.append(f"invalid_vector:{label}")
            continue
        try:
            actual_transcript = control_auth.transcript(label, context, peer_nonce, hub_nonce, challenge_id, issued, expires)
            actual_mac = control_auth.b32_decode(control_auth.compute_proof(
                key, label, context, peer_nonce, hub_nonce, challenge_id, issued, expires))
            java_transcript = bytes.fromhex(item["transcriptHex"])
            java_mac = bytes.fromhex(item["macHex"])
        except (TypeError, ValueError, control_auth.ControlAuthError):
            errors.append(f"invalid_vector:{label}")
            continue
        if len(java_transcript) > MAX_BYTES or actual_transcript != java_transcript or actual_mac != java_mac:
            errors.append(f"mismatch:{label}")
            continue
        if label == "PEER_PROOF" and (len(actual_transcript) != 416 or item["macHex"] != PEER_MAC):
            errors.append("peer_normative_vector_mismatch")
            continue
        checked += 1
    return errors, checked


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--agent-server", required=True, type=Path)
    parser.add_argument("--java-vectors", required=True, type=Path)
    parser.add_argument("--report", type=Path)
    args = parser.parse_args(argv)
    errors: list[str] = []
    checked = 0
    try:
        vector = load_vector(args.java_vectors)
        errors, checked = verify(vector, load_control_auth(args.agent_server))
        status = "PASSED" if not errors else "FAILED"
    except (OSError, ValueError, ImportError):
        status = "BLOCKED"
        errors = ["java_vectors_or_python_auth_unavailable"]
    result = {"status": status, "checked": checked, "expected": len(LABELS), "errors": sorted(set(errors))}
    if args.report:
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(json.dumps(result, sort_keys=True) + "\n", encoding="utf-8")
    print(json.dumps(result, sort_keys=True))
    return 0 if status == "PASSED" else 2


if __name__ == "__main__":
    raise SystemExit(main())
