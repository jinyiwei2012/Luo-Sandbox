"""Offline, bounded bidirectional Java/Python artifact contract checker.

This verifies artifacts only; it makes no authentication, game, or execution
claim. Reverse decoding is accepted only with a matching Java JUnit proof;
prepared artifacts alone never establish Python-to-Java interoperability.
"""
from __future__ import annotations

import argparse
import hashlib
import importlib.util
import json
import os
import sys
from pathlib import Path
from typing import Any


MAX_BYTES = 65536
MAX_OUTPUT_FILES = 64
SPEC_HASH = "7ee44ed20446533628e745ba591e14392af9f1b8e7ba44c2155b3bf72d34f1cb"


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def load_manifest(contract: Path) -> dict[str, Any]:
    return json.loads((contract / "manifest.json").read_text(encoding="utf-8"))


def verify_mirror(contract: Path, mirror: Path, spec: Path) -> list[str]:
    errors: list[str] = []
    try:
        manifest = load_manifest(contract)
    except (OSError, ValueError, json.JSONDecodeError):
        return ["invalid_manifest"]
    if manifest.get("specSha256") != SPEC_HASH or sha256(spec) != SPEC_HASH:
        errors.append("spec_hash_mismatch")
    paths = ["schema.json", "manifest.json", "README.md"]
    entries = manifest.get("fixtures", []) + manifest.get("rawFixtures", [])
    for item in entries:
        if not isinstance(item, dict) or not _safe_relative(item.get("path")):
            errors.append("invalid_manifest_path")
            continue
        paths.append(item["path"])
        canonical = contract / item["path"]
        if not canonical.is_file() or sha256(canonical) != item.get("sha256"):
            errors.append("canonical_fixture_hash_mismatch")
    for relative in paths:
        canonical, copied = contract / relative, mirror / relative
        if not copied.is_file() or sha256(canonical) != sha256(copied):
            errors.append("mirror_hash_mismatch")
    expected_paths = set(paths)
    canonical_paths = _tree_paths(contract)
    mirror_paths = _tree_paths(mirror)
    if canonical_paths != expected_paths:
        errors.append("canonical_inventory_mismatch")
    if mirror_paths != expected_paths:
        errors.append("mirror_inventory_mismatch")
    if sha256(contract / "schema.json") != manifest.get("schemaSha256"):
        errors.append("schema_hash_mismatch")
    return errors


def _safe_relative(value: object) -> bool:
    if not isinstance(value, str) or not value:
        return False
    path = Path(value)
    return not path.is_absolute() and ".." not in path.parts


def _tree_paths(root: Path) -> set[str]:
    if not root.is_dir():
        return set()
    return {str(path.relative_to(root)).replace("\\", "/") for path in root.rglob("*") if path.is_file()}


def expected_valid(manifest: dict[str, Any]) -> dict[str, dict[str, Any]]:
    return {Path(item["path"]).stem: item for item in manifest["fixtures"] if item["expectedResult"] == "accept"}


def output_files(root: Path) -> tuple[dict[str, Path], list[str]]:
    files: dict[str, Path] = {}
    errors: list[str] = []
    count = 0
    for directory, _, names in os.walk(root, followlinks=False):
        for name in names:
            if not name.endswith(".json") or name == "index.json":
                continue
            count += 1
            if count > MAX_OUTPUT_FILES:
                return files, ["java_output_file_budget_exceeded"]
            path = Path(directory) / name
            if path.is_symlink() or not path.is_file():
                errors.append("invalid_java_output_file")
            elif path.stat().st_size > MAX_BYTES:
                errors.append("java_output_oversized")
            elif path.stem in files:
                errors.append("duplicate_java_output_stem")
            else:
                files[path.stem] = path
    return files, errors


def load_decoder(agent_server: Path):
    if not agent_server.is_dir():
        raise ValueError("agent_server must be a server directory")
    wire_path = agent_server / "src" / "adapter" / "minecraft" / "wire.py"
    if not wire_path.is_file():
        raise ValueError("agent_server does not contain Minecraft wire codec")
    spec = importlib.util.spec_from_file_location("minecraft_contract_wire", wire_path)
    if spec is None or spec.loader is None:
        raise ValueError("Minecraft wire codec cannot be loaded")
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module.decode_message, module.encode_message


def verify_java_output(contract: Path, output: Path, decoder, encoder) -> tuple[list[str], int]:
    manifest = load_manifest(contract)
    expected = expected_valid(manifest)
    actual, errors = output_files(output) if output.is_dir() else ({}, ["missing_java_output_root"])
    errors += [f"missing_java_output:{name}" for name in sorted(set(expected) - set(actual))]
    errors += [f"unexpected_java_output:{name}" for name in sorted(set(actual) - set(expected))]
    checked = 0
    for name in sorted(set(expected) & set(actual)):
        try:
            java_data = actual[name].read_bytes()
            if len(java_data) > MAX_BYTES:
                raise ValueError("output exceeds frame cap")
            expected_data = (contract / expected[name]["path"]).read_bytes()
            java_message = decoder(java_data)
            canonical_message = decoder(expected_data)
            if java_message != canonical_message or decoder(encoder(java_message)) != canonical_message:
                raise ValueError("semantic mismatch")
            checked += 1
        except Exception:
            errors.append(f"java_output_invalid:{name}")
    return errors, checked


def prepare_python_output(contract: Path, output: Path, decoder, encoder) -> tuple[list[str], int]:
    """Create once or exactly verify a finite Python artifact directory."""
    manifest = load_manifest(contract)
    hashes: dict[str, str] = {}
    encoded_files: dict[str, bytes] = {}
    for name, item in sorted(expected_valid(manifest).items()):
        encoded = encoder(decoder((contract / item["path"]).read_bytes()))
        if len(encoded) > MAX_BYTES:
            return ["python_output_oversized"], 0
        filename = f"{name}.json"
        encoded_files[filename] = encoded
        hashes[filename] = hashlib.sha256(encoded).hexdigest()
    index = {
        "manifestSha256": sha256(contract / "manifest.json"),
        "specSha256": manifest["specSha256"],
        "files": hashes,
    }
    if output.exists() and any(output.iterdir()):
        return _verify_prepared_python_output(output, index, encoded_files)
    output.mkdir(parents=True, exist_ok=True)
    for filename, encoded in encoded_files.items():
        (output / filename).write_bytes(encoded)
    (output / "index.json").write_text(json.dumps(index, sort_keys=True) + "\n", encoding="utf-8")
    return [], len(hashes)


def _verify_prepared_python_output(output: Path, index: dict[str, Any], encoded_files: dict[str, bytes]) -> tuple[list[str], int]:
    expected_names = set(encoded_files) | {"index.json"}
    if not output.is_dir() or _tree_paths(output) != expected_names:
        return ["python_output_inventory_mismatch"], 0
    index_path = output / "index.json"
    try:
        existing = json.loads(index_path.read_text(encoding="utf-8"))
    except (OSError, ValueError, json.JSONDecodeError):
        return ["python_output_index_invalid"], 0
    if existing != index:
        return ["python_output_index_mismatch"], 0
    for filename, encoded in encoded_files.items():
        path = output / filename
        if path.is_symlink() or not path.is_file() or path.stat().st_size > MAX_BYTES:
            return ["python_output_file_invalid"], 0
        if path.read_bytes() != encoded:
            return ["python_output_content_mismatch"], 0
    return [], len(encoded_files)


def verify_reverse_proof(contract: Path, output: Path, proof: Path) -> list[str]:
    if not proof.is_file() or not (output / "index.json").is_file():
        return ["missing_java_reverse_proof"]
    try:
        result = json.loads(proof.read_text(encoding="utf-8"))
        index_hash = sha256(output / "index.json")
        manifest_hash = sha256(contract / "manifest.json")
    except (OSError, ValueError, json.JSONDecodeError):
        return ["invalid_java_reverse_proof"]
    if result != {
        "status": "PASSED",
        "checked": len(expected_valid(load_manifest(contract))),
        "expected": len(expected_valid(load_manifest(contract))),
        "sourceIndexSha256": index_hash,
        "manifestSha256": manifest_hash,
    }:
        return ["stale_or_invalid_java_reverse_proof"]
    return []


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--agent-server", required=True, type=Path)
    parser.add_argument("--java-output", required=True, type=Path)
    parser.add_argument("--python-output", type=Path)
    parser.add_argument("--java-reverse-report", type=Path)
    parser.add_argument("--report", type=Path)
    args = parser.parse_args(argv)
    root = Path(__file__).resolve().parents[2]
    contract = root / "contracts" / "minecraft" / "v1"
    mirror = args.agent_server / "tests" / "fixtures" / "minecraft_contract_v1"
    spec = root / "docs" / "decisions" / "minecraft-contract-v1.md"
    errors = verify_mirror(contract, mirror, spec)
    try:
        manifest = load_manifest(contract)
    except (OSError, ValueError, json.JSONDecodeError):
        manifest = {"fixtures": []}
        errors.append("invalid_manifest")
    status = "BLOCKED" if not args.java_output.is_dir() or errors else "FAILED"
    checked = 0
    reverse = "NOT_EXECUTED"
    if status != "BLOCKED":
        try:
            decoder, encoder = load_decoder(args.agent_server)
            output_errors, checked = verify_java_output(contract, args.java_output, decoder, encoder)
            errors.extend(output_errors)
            if args.python_output and not errors:
                reverse_errors, prepared = prepare_python_output(contract, args.python_output, decoder, encoder)
                errors.extend(reverse_errors)
                reverse = {"status": "PREPARED", "prepared": prepared}
                if not reverse_errors and args.java_reverse_report:
                    proof_errors = verify_reverse_proof(contract, args.python_output, args.java_reverse_report)
                    errors.extend(proof_errors)
                    if not proof_errors:
                        reverse = {"status": "PASSED", "checked": prepared}
            status = "PASSED" if not errors else "FAILED"
        except (OSError, ValueError, ImportError):
            errors.append("python_codec_unavailable")
            status = "BLOCKED"
    result = {
        "status": status,
        "javaToPython": {"checked": checked, "expected": len(expected_valid(manifest))},
        "pythonToJava": reverse,
        "errors": sorted(set(errors)),
    }
    if args.report:
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(json.dumps(result, sort_keys=True) + "\n", encoding="utf-8")
    print(json.dumps(result, sort_keys=True))
    return 0 if status == "PASSED" else 2


if __name__ == "__main__":
    raise SystemExit(main())
