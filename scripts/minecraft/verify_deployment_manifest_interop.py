"""Run test-only Java/Python interop for the frozen v2 deployment manifest."""
from __future__ import annotations

import argparse
import contextlib
import hashlib
import json
import subprocess
import sys
import types
from pathlib import Path
from typing import Any


ROOT = Path(__file__).resolve().parents[2]
UPSTREAM = Path(r"C:\Agent-LuoTianyi\.slim\worktrees\minecraft-observation-adapter")
UPSTREAM_HEAD = "05acdd0fea9c8fd28f7b5d1242cdd73ea9203966"
PY_SOURCE = UPSTREAM / "server/src/adapter/minecraft/deployment_manifest.py"
PY_TEST = UPSTREAM / "server/tests/unit/adapter/test_minecraft_deployment_manifest.py"
JAVA_SOURCE = ROOT / "bridge/forge/src/main/java/io/github/jinyiwei2012/luosandbox/security/ControlDeploymentManifest.java"
JAVA_TEST = ROOT / "bridge/forge/src/test/java/io/github/jinyiwei2012/luosandbox/security/ControlDeploymentManifestTest.java"
JAVA_INTEROP = ROOT / "bridge/forge/src/test/java/io/github/jinyiwei2012/luosandbox/security/DeploymentManifestInteropTest.java"
TOOL = ROOT / "scripts/minecraft/verify_deployment_manifest_interop.py"
TOOL_TEST = ROOT / "scripts/tests/test_deployment_manifest_interop.py"
SPEC = ROOT / "docs/decisions/minecraft-control-postauth-v2-draft.md"
SOURCES = (PY_SOURCE, PY_TEST, JAVA_SOURCE, JAVA_TEST, JAVA_INTEROP, TOOL, TOOL_TEST, SPEC)
SCHEMA = "deployment-manifest-v2-interop-v1"
JAVA_TEST_FQCN = "io.github.jinyiwei2012.luosandbox.security.DeploymentManifestInteropTest"
CLASS_SUFFIX = "/io/github/jinyiwei2012/luosandbox/security/ControlDeploymentManifest.class"
MAX_JAVA_JSON_BYTES = 1_048_576
MOD = b"interop-public-mod-manifest"
POLICY = b"interop-public-policy-config"
BINDING = b"interop-public-binding-manifest"


class Blocked(RuntimeError):
    pass


def _unique_object(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise Blocked("duplicate_json_key")
        result[key] = value
    return result


def _read(path: Path) -> bytes:
    try:
        if path.is_symlink() or not path.is_file():
            raise Blocked("source_missing_or_not_regular")
        return path.read_bytes()
    except OSError as error:
        raise Blocked("source_gate_failed") from error


@contextlib.contextmanager
def loaded_python_manifest():
    source = _read(PY_SOURCE)
    names = ("src", "src.adapter", "src.adapter.minecraft",
             "src.adapter.minecraft.deployment_manifest")
    missing = object()
    previous = {name: sys.modules.get(name, missing) for name in names}
    try:
        for name in names[:-1]:
            module = types.ModuleType(name)
            module.__path__ = []
            module.__package__ = name
            sys.modules[name] = module
        module = types.ModuleType(names[-1])
        module.__file__ = str(PY_SOURCE)
        module.__package__ = "src.adapter.minecraft"
        sys.modules[names[-1]] = module
        exec(compile(source, str(PY_SOURCE), "exec"), module.__dict__)
        yield module
    finally:
        for name in reversed(names):
            if previous[name] is missing:
                sys.modules.pop(name, None)
            else:
                sys.modules[name] = previous[name]


def _manifest(module: Any, role: str, peer: str, hub: str, mod: bytes = MOD):
    digest = hashlib.sha256
    return module.DeploymentManifest(
        schema="luosandbox.minecraft-control-deployment.v2",
        security="luosandbox.control-auth.v1",
        peer_role=role,
        peer_install_id=peer,
        hub_install_id=hub,
        protocol="LUO_CONTROL_POSTAUTH_V2",
        post_auth_profile="LUO_CONTROL_POSTAUTH_V2",
        session_kdf_domain="LUO_CONTROL_SESSION_KEY_V2",
        frame_mac_domain="LUO_CONTROL_FRAME_V2",
        frame_max_utf8_bytes=4096,
        payload_max_raw_bytes=2048,
        ascii_id_max_bytes=128,
        outbound_queue_frames=8,
        open_timeout_ms=5000,
        heartbeat_interval_ms=500,
        heartbeat_timeout_ms=2000,
        route_capacity=2,
        route_ttl_ms=5000,
        binding_pending_capacity=8,
        store_outstanding_capacity=2,
        mod_manifest_sha256=digest(mod).hexdigest(),
        policy_config_sha256=digest(POLICY).hexdigest(),
        binding_manifest_sha256=digest(BINDING).hexdigest(),
    )


def build_cases(module: Any) -> list[dict[str, Any]]:
    maximum = "a" + "A9._:/-" * 18 + "A"
    valid_specs = (
        ("client", "CLIENT_CONTROLLER", "a", "h", MOD),
        ("server-max-id", "SERVER_AUTHORITY", maximum, maximum, b"server-mod"),
    )
    cases = []
    for name, role, peer, hub, mod in valid_specs:
        value = _manifest(module, role, peer, hub, mod)
        identity = module.TrustedDeploymentIdentity(
            peer_role=role, peer_install_id=peer, hub_install_id=hub,
            expected_config_hash=value.config_hash)
        artifacts = module.TrustedDeploymentArtifacts(
            mod_manifest_bytes=mod, policy_config_bytes=POLICY,
            binding_manifest_bytes=BINDING)
        module.verify_trusted_deployment(value, identity, artifacts)
        cases.append({
            "name": name, "rawHex": value.raw_bytes.hex(), "accepted": True,
            "role": role, "peerInstallId": peer, "hubInstallId": hub,
            "configHashHex": value.config_hash.hex(), "modHex": mod.hex(),
            "policyHex": POLICY.hex(), "bindingHex": BINDING.hex(),
            "trustedVerified": True,
        })

    base = _manifest(module, "CLIENT_CONTROLLER", "peer-1", "hub-1").raw_bytes
    text = base.decode("ascii")
    negatives = {
        "id-space": text.replace('"peer-1"', '"bad id"'),
        "id-quote": text.replace('"peer-1"', '"bad\\\"id"'),
        "id-leading-punct": text.replace('"peer-1"', '"-bad"'),
        "id-129": text.replace('"peer-1"', '"a' + 'x' * 128 + '"'),
        "unknown-key": text.replace('"security":', '"unknown":"x","security":'),
        "missing-key": text.replace('"schema":"luosandbox.minecraft-control-deployment.v2",', ''),
        "reordered": text.replace(
            '"schema":"luosandbox.minecraft-control-deployment.v2","security":"luosandbox.control-auth.v1",',
            '"security":"luosandbox.control-auth.v1","schema":"luosandbox.minecraft-control-deployment.v2",'),
        "crlf": text.replace("\n", "\r\n"),
        "extra-lf": text + "\n",
        "boolean-number": text.replace('"frameMaxUtf8Bytes":4096', '"frameMaxUtf8Bytes":true'),
        "float-number": text.replace('"frameMaxUtf8Bytes":4096', '"frameMaxUtf8Bytes":4096.0'),
        "escaped-equivalent": text.replace("peer-1", "peer\\u002d1"),
        "uppercase-hash": text.replace(
            hashlib.sha256(MOD).hexdigest(), hashlib.sha256(MOD).hexdigest().upper()),
    }
    for name, raw in negatives.items():
        cases.append({"name": name, "rawHex": raw.encode("ascii").hex(), "accepted": False})

    value = _manifest(module, "CLIENT_CONTROLLER", "peer-1", "hub-1")
    mismatch_variants = {
        "trusted-role-mismatch": ("SERVER_AUTHORITY", "peer-1", "hub-1", value.config_hash, MOD, POLICY, BINDING),
        "trusted-peer-mismatch": ("CLIENT_CONTROLLER", "other", "hub-1", value.config_hash, MOD, POLICY, BINDING),
        "trusted-hub-mismatch": ("CLIENT_CONTROLLER", "peer-1", "other", value.config_hash, MOD, POLICY, BINDING),
        "trusted-config-mismatch": ("CLIENT_CONTROLLER", "peer-1", "hub-1", b"x" * 32, MOD, POLICY, BINDING),
        "trusted-mod-mismatch": ("CLIENT_CONTROLLER", "peer-1", "hub-1", value.config_hash, MOD + b"x", POLICY, BINDING),
        "trusted-policy-mismatch": ("CLIENT_CONTROLLER", "peer-1", "hub-1", value.config_hash, MOD, POLICY + b"x", BINDING),
        "trusted-binding-mismatch": ("CLIENT_CONTROLLER", "peer-1", "hub-1", value.config_hash, MOD, POLICY, BINDING + b"x"),
    }
    for name, fields in mismatch_variants.items():
        role, peer, hub, config, mod, policy, binding = fields
        cases.append({
            "name": name, "rawHex": value.raw_bytes.hex(), "accepted": True,
            "role": role, "peerInstallId": peer, "hubInstallId": hub,
            "configHashHex": config.hex(), "modHex": mod.hex(),
            "policyHex": policy.hex(), "bindingHex": binding.hex(),
            "trustedVerified": False,
        })
    return cases


def input_document() -> dict[str, Any]:
    with loaded_python_manifest() as module:
        return {"schema": SCHEMA, "cases": build_cases(module)}


def _lower_hex(value: object, width: int | None = None) -> bool:
    return (isinstance(value, str) and (width is None or len(value) == width)
            and bool(value) and all(character in "0123456789abcdef" for character in value))


def _validate_case(case: object, expected: dict[str, Any]) -> dict[str, Any]:
    if not isinstance(case, dict):
        raise Blocked("java_case_invalid")
    accepted = expected["accepted"]
    required = ({"name", "accepted", "encodedRawHex", "configHashHex", "trustedVerified"}
                if accepted else {"name", "accepted"})
    if set(case) != required or case.get("name") != expected["name"] or case.get("accepted") is not accepted:
        raise Blocked("java_case_schema_invalid")
    if accepted:
        if not _lower_hex(case.get("encodedRawHex")) or len(case["encodedRawHex"]) % 2:
            raise Blocked("java_case_raw_invalid")
        if not _lower_hex(case.get("configHashHex"), 64):
            raise Blocked("java_case_hash_invalid")
        if type(case.get("trustedVerified")) is not bool:
            raise Blocked("java_case_trusted_flag_invalid")
    return case


def load_java_result(path: Path, expected: dict[str, Any]) -> dict[str, Any]:
    try:
        if path.is_symlink() or not path.is_file():
            raise Blocked("java_result_missing_or_not_regular")
        size = path.stat().st_size
        if size <= 0 or size > MAX_JAVA_JSON_BYTES:
            raise Blocked("java_result_oversized_or_empty")
        raw = path.read_bytes()
    except OSError as error:
        raise Blocked("java_result_gate_failed") from error
    if len(raw) != size or raw.startswith(b"\xef\xbb\xbf"):
        raise Blocked("java_result_encoding_invalid")
    try:
        java = json.loads(raw.decode("utf-8", errors="strict"), object_pairs_hook=_unique_object)
    except (UnicodeError, json.JSONDecodeError) as error:
        raise Blocked("java_result_invalid_json") from error
    if not isinstance(java, dict) or set(java) != {"schema", "invocation", "loadedClassIdentity", "cases"}:
        raise Blocked("java_schema_invalid")
    if java.get("schema") != SCHEMA:
        raise Blocked("java_schema_invalid")
    invocation = java.get("invocation")
    if (not isinstance(invocation, dict)
            or set(invocation) != {"testClass", "forcedRerunRequired", "executionAttestation"}
            or invocation.get("testClass") != JAVA_TEST_FQCN
            or invocation.get("forcedRerunRequired") is not True
            or invocation.get("executionAttestation") != "SEPARATE_RUNNER_OBSERVATION_REQUIRED"):
        raise Blocked("java_invocation_invalid")
    identity = java.get("loadedClassIdentity")
    if (not isinstance(identity, dict) or set(identity) != {"resource", "sha256"}
            or not isinstance(identity["resource"], str)
            or not 1 <= len(identity["resource"]) <= 2048
            or not identity["resource"].startswith(("file:/", "jar:file:/"))
            or not identity["resource"].replace("\\", "/").endswith(CLASS_SUFFIX)
            or not _lower_hex(identity.get("sha256"), 64)):
        raise Blocked("java_loaded_class_identity_invalid")
    cases = java.get("cases")
    if not isinstance(cases, list) or len(cases) != len(expected["cases"]):
        raise Blocked("java_case_count_invalid")
    names = []
    expected_by_name = {case["name"]: case for case in expected["cases"]}
    for case in cases:
        if not isinstance(case, dict) or not isinstance(case.get("name"), str):
            raise Blocked("java_case_invalid")
        names.append(case["name"])
    if len(set(names)) != len(names):
        raise Blocked("java_duplicate_case_name")
    if set(names) != set(expected_by_name):
        raise Blocked("java_case_set_invalid")
    java["cases"] = [_validate_case(case, expected_by_name[case["name"]]) for case in cases]
    return java


def compare_java(java: dict[str, Any], expected: dict[str, Any]) -> list[str]:
    expected_cases = {case["name"]: case for case in expected["cases"]}
    actual_cases = {case["name"]: case for case in java["cases"]}
    errors = []
    with loaded_python_manifest() as module:
        for name, source in expected_cases.items():
            actual = actual_cases[name]
            if actual.get("accepted") != source["accepted"]:
                errors.append(f"{name}:acceptance")
                continue
            raw = bytes.fromhex(source["rawHex"])
            if source["accepted"]:
                try:
                    parsed = module.parse_deployment_manifest(bytes.fromhex(actual["encodedRawHex"]))
                except (ValueError, TypeError):
                    errors.append(f"{name}:bytes_or_hash")
                    continue
                if parsed.raw_bytes != raw or actual.get("configHashHex") != hashlib.sha256(raw).hexdigest():
                    errors.append(f"{name}:bytes_or_hash")
                if actual.get("trustedVerified") != source["trustedVerified"]:
                    errors.append(f"{name}:trusted_verification")
    return errors


def source_hashes() -> dict[str, str]:
    result: dict[str, str] = {}
    for path in SOURCES:
        result[str(path.relative_to(ROOT)).replace("\\", "/") if path.is_relative_to(ROOT) else str(path)] = hashlib.sha256(_read(path)).hexdigest()
    expected = {
        str(path.relative_to(ROOT)).replace("\\", "/") if path.is_relative_to(ROOT) else str(path)
        for path in SOURCES
    }
    if set(result) != expected or any(not _lower_hex(value, 64) for value in result.values()):
        raise Blocked("source_hash_set_invalid")
    return result


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--write-input", type=Path)
    parser.add_argument("--java-result", type=Path)
    parser.add_argument("--report", type=Path)
    args = parser.parse_args(argv)
    try:
        head = subprocess.check_output(["git", "-C", str(UPSTREAM), "rev-parse", "HEAD"], text=True, timeout=10).strip()
        if head != UPSTREAM_HEAD:
            raise Blocked("upstream_head_mismatch")
        expected = input_document()
        if args.write_input:
            args.write_input.parent.mkdir(parents=True, exist_ok=True)
            args.write_input.write_text(json.dumps(expected, sort_keys=True) + "\n", encoding="utf-8")
        result: dict[str, Any] = {
            "status": "PYTHON_CASES_VALIDATED", "crossLanguage": "NOT_EXECUTED",
            "caseCount": len(expected["cases"]), "sourceHashes": source_hashes(),
            "upstreamHead": head,
            "limits": ["no_filesystem_loader_acl_or_activation_claim", "upstream_dependency_committed_locally_not_pushed_or_published"],
        }
        if args.java_result:
            java = load_java_result(args.java_result, expected)
            errors = compare_java(java, expected)
            result["errors"] = errors
            result["crossLanguage"] = "MATCHED_UNATTESTED" if not errors else "FAILED"
            result["status"] = "JAVA_DOCUMENT_MATCHED_UNATTESTED" if not errors else "FAILED"
            result["javaDocumentProvenance"] = "UNATTESTED_EXTERNAL_INPUT"
            result["javaLoadedClassIdentity"] = java.get("loadedClassIdentity")
            result["javaInvocation"] = java.get("invocation")
            result["provenanceLimitations"] = [
                "external_java_document_is_not_execution_attestation",
                "loaded_class_hash_alone_does_not_prove_fresh_source_compilation",
                "fresh_forced_junit_execution_is_separate_parent_evidence",
            ]
    except (Blocked, OSError, UnicodeError, subprocess.SubprocessError) as error:
        code = str(error) if isinstance(error, Blocked) else "interop_gate_failed"
        result = {"status": "BLOCKED", "crossLanguage": "BLOCKED", "errors": [code]}
    if args.report:
        try:
            args.report.parent.mkdir(parents=True, exist_ok=True)
            args.report.write_text(json.dumps(result, sort_keys=True) + "\n", encoding="utf-8")
        except OSError:
            result = {"status": "BLOCKED", "crossLanguage": "BLOCKED", "errors": ["report_output_failed"]}
    print(json.dumps({"status": result["status"], "crossLanguage": result["crossLanguage"]}, sort_keys=True))
    return 0 if result["status"] in {
        "PYTHON_CASES_VALIDATED", "JAVA_DOCUMENT_MATCHED_UNATTESTED"} else 2


if __name__ == "__main__":
    raise SystemExit(main())
