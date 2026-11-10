"""Verify the frozen P0 pilot inputs against local, read-only evidence.

Inputs under test: ``tests/p0-pilot/pilot-inputs.json`` and the launch input
template it names.  This tool only reads files: it never installs, launches,
downloads, or executes anything, and it writes a report only when asked to.
"""

from __future__ import annotations

import argparse
import hashlib
import io
import json
import os
import re
import struct
import sys
import zipfile
from pathlib import Path
from typing import Any

SCHEMA = "luosandbox.p0-pilot-inputs.v1"
STATUS = "P0_INPUTS_FROZEN_P0_INCOMPLETE"
VERIFIED = "P0_STATIC_INPUTS_VERIFIED"
RECORD_PATH = "tests/p0-pilot/pilot-inputs.json"
MAX_RECORD_BYTES = 64 * 1024
HASH_RE = re.compile(r"[0-9a-f]{64}")
NAME_RE = re.compile(r"[A-Za-z0-9_]{3,16}")
UUID_RE = re.compile(r"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
PROPERTY_KEY_RE = re.compile(r"[a-z][a-z0-9.-]*")
SEED_RE = re.compile(r"-?[0-9]+")

TOP_LEVEL_KEYS = frozenset({
    "schema", "status", "recordedOn", "claims", "artifactRoot", "artifacts",
    "serverPropertySource", "java", "launchInput", "network", "identities",
    "world", "datapacks", "blocked",
})
ARTIFACT_KEYS = frozenset({"file", "bytes", "sha256"})
SERVER_SOURCE_KEYS = frozenset({"bundler", "versionsList", "innerJarSha256", "markerKeys"})
JAVA_KEYS = frozenset({
    "majorVersion", "observedRuntimeVersion", "observedImplementor",
    "observedAtUtc", "fingerprint",
})
FINGERPRINT_KEYS = frozenset({"path", "bytes", "sha256"})
LAUNCH_KEYS = frozenset({"path", "role", "bytes", "sha256"})
NETWORK_KEYS = frozenset({"scope", "serverIp", "serverPort"})
IDENTITY_KEYS = frozenset({"role", "name", "predictedOfflineUuid", "measuredOfflineUuid"})
WORLD_KEYS = frozenset({"levelName", "dimension", "region", "validUntil"})
REGION_KEYS = frozenset({"minX", "maxX", "minZ", "maxZ"})
IDENTITY_ROLES = frozenset({"CONTROLLED_BODY", "HUMAN_REFERENCE"})
EXPECTED_PROPERTY_KEYS = frozenset({
    "server-ip", "server-port", "online-mode", "white-list", "enforce-whitelist",
    "enable-rcon", "enable-query", "max-players", "level-name", "level-type",
    "level-seed", "generate-structures", "allow-nether", "max-world-size",
    "difficulty", "gamemode", "force-gamemode", "hardcore", "spawn-monsters",
    "motd",
})
LOOPBACK_IP = "127.0.0.1"
MANIFEST_PATH = "docs/validation-resource-manifest.md"
PREFLIGHT_PATH = "docs/validation-preflight.md"
LIMITATIONS = (
    "static_evidence_only",
    "launch_input_is_not_effective_configuration",
    "predicted_offline_uuids_are_not_measured",
    "constant_pool_strings_do_not_prove_runtime_behaviour",
    "no_launch_or_compatibility_claim",
)


def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _pass(**details: Any) -> dict[str, Any]:
    return {"status": "PASS", "errors": [], **details}


def _failed(errors: list[str], **details: Any) -> dict[str, Any]:
    return {"status": "FAILED", "errors": sorted(errors), **details}


def _blocked(errors: list[str], **details: Any) -> dict[str, Any]:
    return {"status": "BLOCKED", "errors": sorted(errors), **details}


def offline_player_uuid(name: str) -> str:
    """Reproduce Java ``UUID.nameUUIDFromBytes("OfflinePlayer:" + name)``."""
    digest = bytearray(hashlib.md5(("OfflinePlayer:" + name).encode("utf-8")).digest())
    digest[6] = (digest[6] & 0x0F) | 0x30
    digest[8] = (digest[8] & 0x3F) | 0x80
    text = digest.hex()
    return "-".join((text[0:8], text[8:12], text[12:16], text[16:20], text[20:32]))


def parse_properties(raw: bytes) -> dict[str, str]:
    """Parse the strict launch-input syntax; raise ValueError with a stable code."""
    if raw.startswith(b"\xef\xbb\xbf"):
        raise ValueError("properties_bom_forbidden")
    try:
        text = raw.decode("ascii")
    except UnicodeDecodeError as error:
        raise ValueError("properties_non_ascii") from error
    if "\r" in text:
        raise ValueError("properties_cr_forbidden")
    if not text.endswith("\n"):
        raise ValueError("properties_missing_final_newline")
    parsed: dict[str, str] = {}
    for line in text.split("\n")[:-1]:
        if not line:
            raise ValueError("properties_blank_line")
        if line.startswith("#"):
            continue
        if "=" not in line:
            raise ValueError("properties_missing_separator")
        key, _, value = line.partition("=")
        if not PROPERTY_KEY_RE.fullmatch(key):
            raise ValueError("properties_key_invalid")
        if key in parsed:
            raise ValueError("properties_duplicate_key")
        if not value:
            raise ValueError("properties_value_empty")
        if value != value.strip() or any(not 0x20 <= ord(char) <= 0x7E for char in value):
            raise ValueError("properties_value_characters")
        decoded: list[str] = []
        index = 0
        while index < len(value):
            char = value[index]
            if char != "\\":
                decoded.append(char)
                index += 1
            elif index + 1 < len(value) and value[index + 1] == ":":
                decoded.append(":")
                index += 2
            else:
                raise ValueError("properties_escape_invalid")
        parsed[key] = "".join(decoded)
    if not parsed:
        raise ValueError("properties_empty")
    return parsed


def _unique_object(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("record_duplicate_key:" + key)
        result[key] = value
    return result


def load_record(path: Path) -> dict[str, Any]:
    """Read the record strictly: no BOM, no duplicate keys, bounded size."""
    if path.is_symlink() or not path.is_file():
        raise ValueError("record_unavailable")
    if path.stat().st_size > MAX_RECORD_BYTES:
        raise ValueError("record_oversized")
    raw = path.read_bytes()
    if raw.startswith(b"\xef\xbb\xbf"):
        raise ValueError("record_bom_forbidden")
    try:
        text = raw.decode("utf-8")
    except UnicodeDecodeError as error:
        raise ValueError("record_invalid_utf8") from error
    try:
        value = json.loads(text, object_pairs_hook=_unique_object)
    except json.JSONDecodeError as error:
        raise ValueError("record_invalid_json") from error
    if not isinstance(value, dict):
        raise ValueError("record_not_object")
    return value

def _is_int(value: Any) -> bool:
    return isinstance(value, int) and not isinstance(value, bool)


def _is_hash(value: Any) -> bool:
    return isinstance(value, str) and bool(HASH_RE.fullmatch(value))


def check_record(record: dict[str, Any]) -> dict[str, Any]:
    """Validate the schema and literal constants of the frozen record."""
    if not isinstance(record, dict):
        return _failed(["record_not_object"])
    errors: list[str] = []
    if set(record) != TOP_LEVEL_KEYS:
        errors.append("record_key_set_invalid")
    if record.get("schema") != SCHEMA:
        errors.append("record_schema_invalid")
    if record.get("status") != STATUS:
        errors.append("record_status_invalid")
    if not isinstance(record.get("recordedOn"), str) or not record["recordedOn"]:
        errors.append("record_date_invalid")
    claims = record.get("claims")
    if not isinstance(claims, list) or not claims or any(not isinstance(item, str) or not item for item in claims):
        errors.append("claims_invalid")
    if not isinstance(record.get("artifactRoot"), str) or not record["artifactRoot"]:
        errors.append("artifact_root_invalid")

    artifacts = record.get("artifacts")
    if not isinstance(artifacts, list) or len(artifacts) != 6:
        errors.append("artifact_count_invalid")
        artifacts = []
    files: set[str] = set()
    for index, artifact in enumerate(artifacts):
        label = "artifact_%d" % index
        if not isinstance(artifact, dict) or set(artifact) != ARTIFACT_KEYS:
            errors.append(label + "_keys_invalid")
            continue
        name = artifact["file"]
        if not isinstance(name, str) or not name or "/" in name or "\\" in name:
            errors.append(label + "_file_invalid")
        elif name in files:
            errors.append(label + "_file_duplicated")
        else:
            files.add(name)
        if not _is_int(artifact["bytes"]) or artifact["bytes"] <= 0:
            errors.append(label + "_size_invalid")
        if not _is_hash(artifact["sha256"]):
            errors.append(label + "_hash_invalid")

    source = record.get("serverPropertySource")
    if not isinstance(source, dict) or set(source) != SERVER_SOURCE_KEYS:
        errors.append("server_property_source_invalid")
    else:
        for key in ("bundler", "versionsList"):
            if not isinstance(source.get(key), str) or not source[key]:
                errors.append("server_property_source_" + key + "_invalid")
        if not _is_hash(source.get("innerJarSha256")):
            errors.append("server_property_source_hash_invalid")
        markers = source.get("markerKeys")
        if not isinstance(markers, list) or not markers or any(not isinstance(item, str) or not item for item in markers):
            errors.append("server_property_source_markers_invalid")

    java = record.get("java")
    if not isinstance(java, dict) or set(java) != JAVA_KEYS:
        errors.append("java_invalid")
    else:
        if not _is_int(java.get("majorVersion")) or java["majorVersion"] <= 0:
            errors.append("java_major_version_invalid")
        for key in ("observedRuntimeVersion", "observedImplementor", "observedAtUtc"):
            if not isinstance(java.get(key), str) or not java[key]:
                errors.append("java_" + key + "_invalid")
        fingerprint = java.get("fingerprint")
        if not isinstance(fingerprint, list) or not fingerprint:
            errors.append("java_fingerprint_invalid")
        else:
            paths: set[str] = set()
            for index, entry in enumerate(fingerprint):
                label = "java_fingerprint_%d" % index
                if not isinstance(entry, dict) or set(entry) != FINGERPRINT_KEYS:
                    errors.append(label + "_keys_invalid")
                    continue
                entry_path = entry["path"]
                if (not isinstance(entry_path, str) or not entry_path
                        or entry_path.startswith("/") or ".." in entry_path.split("/")):
                    errors.append(label + "_path_invalid")
                elif entry_path in paths:
                    errors.append(label + "_path_duplicated")
                else:
                    paths.add(entry_path)
                if not _is_int(entry["bytes"]) or entry["bytes"] <= 0:
                    errors.append(label + "_size_invalid")
                if not _is_hash(entry["sha256"]):
                    errors.append(label + "_hash_invalid")

    launch = record.get("launchInput")
    if not isinstance(launch, dict) or set(launch) != LAUNCH_KEYS:
        errors.append("launch_input_invalid")
    else:
        if not isinstance(launch.get("path"), str) or not launch["path"] or launch["path"].startswith("/"):
            errors.append("launch_input_path_invalid")
        if launch.get("role") != "LAUNCH_INPUT_NOT_EFFECTIVE_CONFIG":
            errors.append("launch_input_role_invalid")
        if not _is_int(launch.get("bytes")) or launch["bytes"] <= 0:
            errors.append("launch_input_size_invalid")
        if not _is_hash(launch.get("sha256")):
            errors.append("launch_input_hash_invalid")

    network = record.get("network")
    if not isinstance(network, dict) or set(network) != NETWORK_KEYS:
        errors.append("network_invalid")
    else:
        if network.get("scope") != "LOOPBACK_ONLY":
            errors.append("network_scope_invalid")
        if network.get("serverIp") != LOOPBACK_IP:
            errors.append("network_ip_invalid")
        port = network.get("serverPort")
        if not _is_int(port) or not 1 <= port <= 65535:
            errors.append("network_port_invalid")

    identities = record.get("identities")
    if not isinstance(identities, list) or len(identities) != 2:
        errors.append("identity_count_invalid")
    else:
        roles: set[str] = set()
        names: set[str] = set()
        uuids: set[str] = set()
        for index, identity in enumerate(identities):
            label = "identity_%d" % index
            if not isinstance(identity, dict) or set(identity) != IDENTITY_KEYS:
                errors.append(label + "_keys_invalid")
                continue
            role = identity["role"]
            if role not in IDENTITY_ROLES:
                errors.append(label + "_role_invalid")
            elif role in roles:
                errors.append(label + "_role_duplicated")
            else:
                roles.add(role)
            name = identity["name"]
            if not isinstance(name, str) or not NAME_RE.fullmatch(name):
                errors.append(label + "_name_invalid")
            elif name in names:
                errors.append(label + "_name_duplicated")
            else:
                names.add(name)
            predicted = identity["predictedOfflineUuid"]
            if not isinstance(predicted, str) or not UUID_RE.fullmatch(predicted):
                errors.append(label + "_predicted_uuid_invalid")
            elif predicted in uuids:
                errors.append(label + "_predicted_uuid_duplicated")
            else:
                uuids.add(predicted)
            measured = identity["measuredOfflineUuid"]
            if measured is not None and (not isinstance(measured, str) or not UUID_RE.fullmatch(measured)):
                errors.append(label + "_measured_uuid_invalid")
        if roles and roles != IDENTITY_ROLES:
            errors.append("identity_roles_incomplete")

    world = record.get("world")
    if not isinstance(world, dict) or set(world) != WORLD_KEYS:
        errors.append("world_invalid")
    else:
        if not isinstance(world.get("levelName"), str) or not world["levelName"]:
            errors.append("world_level_name_invalid")
        if world.get("dimension") != "minecraft:overworld":
            errors.append("world_dimension_invalid")
        if not isinstance(world.get("validUntil"), str) or not world["validUntil"]:
            errors.append("world_validity_invalid")
        region = world.get("region")
        if not isinstance(region, dict) or set(region) != REGION_KEYS:
            errors.append("region_invalid")
        else:
            values = [region["minX"], region["maxX"], region["minZ"], region["maxZ"]]
            if any(not _is_int(value) for value in values):
                errors.append("region_type_invalid")
            elif not (values[0] == values[2] == -values[1] == -values[3] and values[1] > 0):
                errors.append("region_not_symmetric")

    datapacks = record.get("datapacks")
    if not isinstance(datapacks, dict) or set(datapacks) != {"external"}:
        errors.append("datapacks_invalid")
    elif datapacks["external"] != []:
        errors.append("datapacks_external_not_empty")

    blocked = record.get("blocked")
    if not isinstance(blocked, list) or not blocked or any(not isinstance(item, str) or not item for item in blocked):
        errors.append("blocked_invalid")

    details = {"artifactCount": len(artifacts), "identityCount": len(identities) if isinstance(identities, list) else 0}
    return _failed(errors, **details) if errors else _pass(**details)


def _property_invariant_errors(properties: dict[str, str], record: dict[str, Any]) -> list[str]:
    errors: list[str] = []
    network = record.get("network") if isinstance(record.get("network"), dict) else {}
    world = record.get("world") if isinstance(record.get("world"), dict) else {}
    region = world.get("region") if isinstance(world.get("region"), dict) else {}
    identities = record.get("identities") if isinstance(record.get("identities"), list) else []

    def require(key: str, value: str, code: str) -> None:
        if properties.get(key) != value:
            errors.append(code)

    require("server-ip", LOOPBACK_IP, "template_server_ip_invalid")
    if network.get("serverIp") != LOOPBACK_IP:
        errors.append("record_network_ip_invalid")
    require("server-port", str(network.get("serverPort")), "template_server_port_invalid")
    require("online-mode", "false", "template_online_mode_invalid")
    require("white-list", "true", "template_white_list_invalid")
    require("enforce-whitelist", "true", "template_enforce_whitelist_invalid")
    require("enable-rcon", "false", "template_rcon_invalid")
    require("enable-query", "false", "template_query_invalid")
    require("max-players", str(len(identities)), "template_max_players_invalid")
    require("level-name", str(world.get("levelName")), "template_level_name_invalid")
    require("level-type", "minecraft:flat", "template_level_type_invalid")
    require("generate-structures", "false", "template_generate_structures_invalid")
    require("allow-nether", "false", "template_allow_nether_invalid")
    require("max-world-size", str(region.get("maxX")), "template_max_world_size_invalid")
    require("difficulty", "easy", "template_difficulty_invalid")
    require("gamemode", "survival", "template_gamemode_invalid")
    require("force-gamemode", "true", "template_force_gamemode_invalid")
    require("hardcore", "false", "template_hardcore_invalid")
    require("spawn-monsters", "false", "template_spawn_monsters_invalid")
    seed = properties.get("level-seed")
    if not isinstance(seed, str) or not SEED_RE.fullmatch(seed):
        errors.append("template_level_seed_invalid")
    motd = properties.get("motd")
    if not isinstance(motd, str) or not motd.strip():
        errors.append("template_motd_invalid")
    return errors


def check_launch_input(root: Path, record: dict[str, Any]) -> dict[str, Any]:
    """Compare the launch input template with its record and the decisions."""
    launch = record.get("launchInput")
    if not isinstance(launch, dict) or not isinstance(launch.get("path"), str):
        return _blocked(["launch_input_record_invalid"])
    path = root / launch["path"]
    if path.is_symlink() or not path.is_file():
        return _blocked(["launch_input_unavailable"])
    raw = path.read_bytes()
    errors: list[str] = []
    if len(raw) != launch.get("bytes"):
        errors.append("launch_input_size_mismatch")
    if sha256_bytes(raw) != launch.get("sha256"):
        errors.append("launch_input_hash_mismatch")
    try:
        properties = parse_properties(raw)
    except ValueError as error:
        errors.append(str(error))
        return _failed(errors)
    if set(properties) != EXPECTED_PROPERTY_KEYS:
        errors.append("launch_input_key_set_invalid")
    errors.extend(_property_invariant_errors(properties, record))
    details = {"keyCount": len(properties), "properties": properties}
    return _failed(errors, **details) if errors else _pass(**details)

_CONSTANT_POOL_SIZES = {
    3: 4, 4: 4, 5: 8, 6: 8, 7: 2, 8: 2, 9: 4, 10: 4, 11: 4, 12: 4,
    15: 3, 16: 2, 17: 4, 18: 4, 19: 2, 20: 2,
}


def string_constants(data: bytes) -> list[str]:
    """Return the CONSTANT_String values of a class file's constant pool."""
    if len(data) < 10 or data[:4] != b"\xca\xfe\xba\xbe":
        raise ValueError("class_magic_invalid")
    count = struct.unpack(">H", data[8:10])[0]
    if count < 1:
        raise ValueError("class_constant_pool_count_invalid")
    utf8: dict[int, str] = {}
    string_indexes: list[int] = []
    offset = 10
    index = 1
    while index < count:
        if offset >= len(data):
            raise ValueError("class_constant_pool_truncated")
        tag = data[offset]
        if tag == 1:
            if offset + 3 > len(data):
                raise ValueError("class_constant_pool_truncated")
            length = struct.unpack(">H", data[offset + 1:offset + 3])[0]
            start = offset + 3
            end = start + length
            if end > len(data):
                raise ValueError("class_constant_pool_truncated")
            utf8[index] = data[start:end].decode("utf-8", errors="replace")
            offset = end
            index += 1
            continue
        size = _CONSTANT_POOL_SIZES.get(tag)
        if size is None:
            raise ValueError("class_constant_tag_unknown_%d" % tag)
        if offset + 1 + size > len(data):
            raise ValueError("class_constant_pool_truncated")
        if tag == 8:
            string_indexes.append(struct.unpack(">H", data[offset + 1:offset + 3])[0])
        offset += 1 + size
        index += 2 if tag in (5, 6) else 1
    return [utf8[item] for item in string_indexes if item in utf8]


def _read_versions_list(bundler: zipfile.ZipFile, entry: str) -> tuple[str, str]:
    try:
        raw = bundler.read(entry)
    except KeyError as error:
        raise ValueError("versions_list_missing") from error
    rows = [line for line in raw.decode("utf-8", errors="replace").splitlines() if line.strip()]
    if len(rows) != 1:
        raise ValueError("versions_list_row_count_invalid")
    parts = rows[0].split("\t")
    if len(parts) != 3:
        raise ValueError("versions_list_row_invalid")
    digest, _version, relative = parts
    return digest, relative


def check_server_property_keys(root: Path, record: dict[str, Any], properties: dict[str, str]) -> dict[str, Any]:
    """Prove every template key exists in the frozen server's property class."""
    source = record.get("serverPropertySource")
    artifact_root = record.get("artifactRoot")
    if not isinstance(source, dict) or not isinstance(artifact_root, str):
        return _blocked(["server_property_source_missing"])
    bundler_name = source.get("bundler")
    versions_list = source.get("versionsList")
    if not isinstance(bundler_name, str) or not isinstance(versions_list, str):
        return _blocked(["server_property_source_missing"])
    jar = root / artifact_root / bundler_name
    if jar.is_symlink() or not jar.is_file():
        return _blocked(["frozen_server_jar_unavailable"])
    try:
        with zipfile.ZipFile(jar) as bundler:
            digest, relative = _read_versions_list(bundler, versions_list)
            entry = "META-INF/versions/" + relative
            try:
                inner_bytes = bundler.read(entry)
            except KeyError:
                return _failed(["inner_jar_missing"])
    except zipfile.BadZipFile:
        return _failed(["bundler_invalid"])
    except ValueError as error:
        return _failed([str(error)])
    errors: list[str] = []
    inner_hash = sha256_bytes(inner_bytes)
    if inner_hash != digest:
        errors.append("inner_jar_hash_mismatch_versions_list")
    if inner_hash != source.get("innerJarSha256"):
        errors.append("inner_jar_hash_mismatch_record")
    markers = source.get("markerKeys") if isinstance(source.get("markerKeys"), list) else []
    marker_bytes = [str(marker).encode("ascii") for marker in markers]
    candidates: list[tuple[str, str, set[str]]] = []
    try:
        with zipfile.ZipFile(io.BytesIO(inner_bytes)) as archive:
            for entry_name in archive.namelist():
                if not entry_name.endswith(".class"):
                    continue
                data = archive.read(entry_name)
                if not all(marker in data for marker in marker_bytes):
                    continue
                try:
                    strings = set(string_constants(data))
                except ValueError:
                    continue
                if set(markers) <= strings:
                    candidates.append((entry_name, sha256_bytes(data), strings))
    except zipfile.BadZipFile:
        return _failed(["inner_jar_invalid"])
    missing: list[str] = []
    if len(candidates) != 1:
        errors.append("property_class_not_unique")
    else:
        missing = sorted(set(properties) - candidates[0][2])
        if missing:
            errors.append("template_key_missing_in_server")
    details: dict[str, Any] = {
        "innerJarSha256": inner_hash,
        "propertyClasses": [name for name, _digest, _strings in candidates],
        "missingKeys": missing,
    }
    if candidates:
        details["propertyClassSha256"] = candidates[0][1]
    return _failed(errors, **details) if errors else _pass(**details)


def check_artifacts(root: Path, record: dict[str, Any]) -> dict[str, Any]:
    """Recompute the frozen artifacts' sizes and digests."""
    artifact_root = record.get("artifactRoot")
    artifacts = record.get("artifacts")
    if not isinstance(artifact_root, str) or not isinstance(artifacts, list) or not artifacts:
        return _blocked(["artifact_record_invalid"])
    base = root / artifact_root
    missing = [
        str(item.get("file"))
        for item in artifacts
        if not isinstance(item, dict) or not (base / str(item.get("file"))).is_file()
    ]
    if missing:
        return _blocked(["artifact_missing"], missing=missing)
    errors: list[str] = []
    verified: dict[str, str] = {}
    for item in artifacts:
        path = base / item["file"]
        if path.stat().st_size != item["bytes"]:
            errors.append("artifact_size_mismatch:" + item["file"])
        digest = sha256_file(path)
        verified[item["file"]] = digest
        if digest != item["sha256"]:
            errors.append("artifact_hash_mismatch:" + item["file"])
    return _failed(errors, verified=verified) if errors else _pass(verified=verified)


def _release_fields(path: Path) -> dict[str, str]:
    fields: dict[str, str] = {}
    for line in path.read_text(encoding="utf-8", errors="replace").splitlines():
        if "=" not in line:
            continue
        key, value = line.split("=", 1)
        fields[key.strip()] = value.strip().strip('"')
    return fields


def check_java(jdk_home: Path | None, record: dict[str, Any]) -> dict[str, Any]:
    """Compare the observed JDK installation with the recorded fingerprint."""
    if jdk_home is None:
        return _blocked(["jdk_home_unavailable"])
    home = Path(jdk_home)
    if home.is_symlink() or not home.is_dir():
        return _blocked(["jdk_home_unavailable"])
    java = record.get("java")
    if not isinstance(java, dict) or not isinstance(java.get("fingerprint"), list):
        return _blocked(["java_record_invalid"])
    errors: list[str] = []
    verified: dict[str, str] = {}
    for entry in java["fingerprint"]:
        if not isinstance(entry, dict) or not isinstance(entry.get("path"), str):
            errors.append("java_fingerprint_entry_invalid")
            continue
        path = home / entry["path"]
        if not path.is_file():
            errors.append("java_file_missing:" + entry["path"])
            continue
        if path.stat().st_size != entry.get("bytes"):
            errors.append("java_size_mismatch:" + entry["path"])
        digest = sha256_file(path)
        verified[entry["path"]] = digest
        if digest != entry.get("sha256"):
            errors.append("java_hash_mismatch:" + entry["path"])
    release = home / "release"
    if not release.is_file():
        errors.append("java_release_missing")
    else:
        fields = _release_fields(release)
        if fields.get("JAVA_RUNTIME_VERSION") != java.get("observedRuntimeVersion"):
            errors.append("java_runtime_version_mismatch")
        version = fields.get("JAVA_VERSION", "")
        try:
            major = int(version.split(".")[0])
        except (ValueError, IndexError):
            major = -1
        if major != java.get("majorVersion"):
            errors.append("java_major_version_mismatch")
    details = {"verified": verified}
    return _failed(errors, **details) if errors else _pass(**details)

def check_identities(record: dict[str, Any]) -> dict[str, Any]:
    """Recompute the predicted offline UUIDs from the fixed nicknames."""
    identities = record.get("identities")
    if not isinstance(identities, list) or not identities:
        return _blocked(["identity_record_invalid"])
    errors: list[str] = []
    predicted: dict[str, str] = {}
    for identity in identities:
        if not isinstance(identity, dict) or not isinstance(identity.get("name"), str):
            errors.append("identity_entry_invalid")
            continue
        name = identity["name"]
        actual = offline_player_uuid(name)
        predicted[name] = actual
        if actual != identity.get("predictedOfflineUuid"):
            errors.append("identity_prediction_mismatch:" + name)
    details = {"predicted": predicted}
    return _failed(errors, **details) if errors else _pass(**details)


def document_needles(record: dict[str, Any]) -> dict[str, list[str]]:
    """Values the reviewed documents must carry for the record to be frozen."""
    manifest: list[str] = []
    artifacts = record.get("artifacts")
    for artifact in artifacts if isinstance(artifacts, list) else []:
        if isinstance(artifact, dict) and isinstance(artifact.get("sha256"), str):
            manifest.append(artifact["sha256"])
    java = record.get("java") if isinstance(record.get("java"), dict) else {}
    fingerprint = java.get("fingerprint")
    for entry in fingerprint if isinstance(fingerprint, list) else []:
        if isinstance(entry, dict) and isinstance(entry.get("sha256"), str):
            manifest.append(entry["sha256"])
    launch = record.get("launchInput") if isinstance(record.get("launchInput"), dict) else {}
    if isinstance(launch.get("sha256"), str):
        manifest.append(launch["sha256"])
    preflight: list[str] = []
    identities = record.get("identities")
    for identity in identities if isinstance(identities, list) else []:
        if not isinstance(identity, dict):
            continue
        for key in ("name", "predictedOfflineUuid"):
            if isinstance(identity.get(key), str):
                preflight.append(identity[key])
    return {MANIFEST_PATH: manifest, PREFLIGHT_PATH: preflight}


def check_documents(root: Path, record: dict[str, Any]) -> dict[str, Any]:
    """Require the reviewed documents to carry the frozen values."""
    errors: list[str] = []
    missing: list[str] = []
    for relative, needles in document_needles(record).items():
        path = root / relative
        if not path.is_file():
            errors.append("document_missing:" + relative)
            continue
        text = path.read_text(encoding="utf-8", errors="replace")
        for needle in needles:
            if needle not in text:
                missing.append(relative + ":" + needle)
    if missing:
        errors.append("document_needle_missing")
    details = {"missing": missing}
    return _failed(errors, **details) if errors else _pass(**details)


def _summarize(checks: dict[str, dict[str, Any]]) -> dict[str, Any]:
    statuses = [check.get("status") for check in checks.values()]
    if "FAILED" in statuses:
        overall = "FAILED"
    elif "BLOCKED" in statuses:
        overall = "BLOCKED"
    else:
        overall = VERIFIED
    return {
        "schema": "luosandbox.p0-pilot-verification.v1",
        "status": overall,
        "checks": checks,
        "limitations": list(LIMITATIONS),
    }


def report(root: Path | None = None, jdk_home: Path | None = None) -> dict[str, Any]:
    """Run every read-only check and aggregate the outcome."""
    repo = root if root is not None else Path(__file__).resolve().parents[2]
    checks: dict[str, dict[str, Any]] = {}
    try:
        record = load_record(repo / RECORD_PATH)
    except ValueError as error:
        checks["record"] = _blocked([str(error)])
        for name in ("launch_input", "server_property_keys", "artifacts", "java", "identities", "documents"):
            checks[name] = _blocked(["record_unavailable"])
        return _summarize(checks)
    except OSError:
        checks["record"] = _blocked(["record_unreadable"])
        for name in ("launch_input", "server_property_keys", "artifacts", "java", "identities", "documents"):
            checks[name] = _blocked(["record_unreadable"])
        return _summarize(checks)
    checks["record"] = check_record(record)
    if checks["record"]["status"] != "PASS":
        for name in ("launch_input", "server_property_keys", "artifacts", "java", "identities", "documents"):
            checks[name] = _blocked(["record_invalid"])
        return _summarize(checks)
    checks["launch_input"] = check_launch_input(repo, record)
    properties = checks["launch_input"].get("properties")
    if isinstance(properties, dict):
        checks["server_property_keys"] = check_server_property_keys(repo, record, properties)
    else:
        checks["server_property_keys"] = _blocked(["launch_input_unavailable"])
    checks["artifacts"] = check_artifacts(repo, record)
    checks["java"] = check_java(jdk_home, record)
    checks["identities"] = check_identities(record)
    checks["documents"] = check_documents(repo, record)
    return _summarize(checks)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path)
    parser.add_argument("--jdk-home", type=Path)
    parser.add_argument("--report", type=Path)
    args = parser.parse_args(argv)
    jdk_home = args.jdk_home
    if jdk_home is None:
        configured = os.environ.get("JAVA_HOME")
        jdk_home = Path(configured) if configured else None
    result = report(args.root, jdk_home)
    if args.report:
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(
            json.dumps(result, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
            encoding="utf-8",
        )
    summary = {
        "status": result["status"],
        "checks": {name: check["status"] for name, check in result["checks"].items()},
    }
    print(json.dumps(summary, sort_keys=True))
    return 0 if result["status"] == VERIFIED else 2


if __name__ == "__main__":
    sys.exit(main())
