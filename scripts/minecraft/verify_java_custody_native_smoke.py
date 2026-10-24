"""Explicit native mechanism experiment; unit tests must mock process boundaries.

MECHANISM_SMOKE_PASS is never deployment custody or dedicated-server validation.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import secrets
import time
import xml.etree.ElementTree as ET
from pathlib import Path

import verify_v2_connector_loopback as process_helper

APPROVED = Path(r"C:\Users\Administrator\AppData\Local\Temp\opencode")
CLASS = (
    "io.github.jinyiwei2012.luosandbox.transport.WindowsDeploymentMechanismNativeTest"
)
CASES = {
    "heldIdentityAndRead()",
    "descriptorRepresentation()",
    "compatibleReadSharing()",
}
XML = Path(f"bridge/forge/build/test-results/test/TEST-{CLASS}.xml")
LIMIT = 2_097_152
Error = process_helper.Error


def read(path, limit=LIMIT):
    process_helper.safe_path(path)
    if not path.is_file() or path.stat().st_size > limit:
        raise Error("ARTIFACT_LIMIT")
    with path.open("rb") as stream:
        value = stream.read(limit + 1)
    if len(value) > limit:
        raise Error("ARTIFACT_LIMIT")
    return value


def sources(root):
    base = root / "bridge/forge/src"
    package = "java/io/github/jinyiwei2012/luosandbox/"
    files = [
        root / "scripts/minecraft/verify_java_custody_native_smoke.py",
        root / "scripts/tests/test_java_custody_native_smoke.py",
        root / "scripts/minecraft/verify_v2_connector_loopback.py",
        root / "scripts/minecraft/verify_v2_transport_loopback.py",
        base / f"test/{package}transport/WindowsDeploymentMechanismNativeTest.java",
    ]
    files += [
        base / f"main/{package}{name}"
        for name in (
            "transport/DeploymentCredentialLoader.java",
            "transport/WindowsDeploymentCustody.java",
            "transport/V2PeerConnector.java",
            "security/ControlDeploymentManifest.java",
            "security/ControlAuth.java",
            "wire/StrictJson.java",
        )
    ]
    return {
        str(path.relative_to(root)): hashlib.sha256(read(path)).hexdigest()
        for path in files
    }


def command(root):
    return [
        str(root / "bridge/forge/gradlew.bat"),
        "test",
        "--tests",
        CLASS,
        "--offline",
        "--no-daemon",
        "--rerun-tasks",
        "--max-workers=2",
    ]


def validate_xml(path, started, nonce):
    raw = read(path, 1_048_576)
    if (
        path.stat().st_mtime_ns < started
        or b"<!DOCTYPE" in raw.upper()
        or b"<!ENTITY" in raw.upper()
    ):
        raise Error("STALE_OR_UNSAFE_XML")
    suite = ET.fromstring(raw)
    cases = suite.findall("testcase")
    if (
        suite.get("name") != CLASS
        or len(cases) != 3
        or {c.get("name") for c in cases} != CASES
    ):
        raise Error("EXACT_CASES_REQUIRED")
    if any(
        suite.get(k) != v
        for k, v in {
            "tests": "3",
            "failures": "0",
            "errors": "0",
            "skipped": "0",
        }.items()
    ):
        raise Error("NATIVE_CASES_NOT_CLEAN")
    if any(
        c.find(tag) is not None
        for c in cases
        for tag in ("failure", "error", "skipped")
    ):
        raise Error("NATIVE_CASES_NOT_CLEAN")
    observed = {}
    output = "".join(n.text or "" for n in suite.findall("system-out"))
    for line in output.splitlines():
        if line.startswith("JAVA_CUSTODY_MECHANISM "):
            parts = line.split(" ", 4)
            if (
                len(parts) < 4
                or parts[1] != nonce
                or parts[2] not in {"0", "1", "2"}
                or parts[2] in observed
            ):
                raise Error("SCENARIO_IDENTITY_INVALID")
            observed[parts[2]] = parts[3]
    if set(observed) != {"0", "1", "2"}:
        raise Error("SCENARIO_RESULTS_MISSING")
    if (
        observed["0"] != "OBSERVED"
        or observed["2"] != "OBSERVED"
        or observed["1"] not in {"OBSERVED", "DESCRIPTOR_PROFILE_UNSUPPORTED"}
    ):
        raise Error("SCENARIO_FAILED_OR_UNKNOWN")
    status = (
        "BLOCKED_PROFILE"
        if "DESCRIPTOR_PROFILE_UNSUPPORTED" in observed.values()
        else "MECHANISM_SMOKE_PASS"
    )
    return raw, status, observed


def exclusive(path, raw):
    process_helper.safe_path(path, exists=False)
    temporary = path.with_name("." + path.name + "-" + secrets.token_hex(8))
    try:
        with temporary.open("xb") as stream:
            stream.write(raw)
            stream.flush()
            os.fsync(stream.fileno())
        os.link(
            temporary, path
        )  # Atomic no-replace publication on this exclusive local workspace.
    finally:
        if temporary.exists():
            temporary.unlink()


def publish_evidence(output, result):
    failed = False
    try:
        # Never traverse dummy native directories: UNKNOWN may retain live owners there.
        rows = [
            f"{hashlib.sha256(read(p)).hexdigest()}  {p.name}"
            for p in sorted(output.iterdir())
            if p.is_file() and p.name not in {"artifacts.sha256", "report.json"}
        ]
        exclusive(output / "artifacts.sha256", ("\n".join(rows) + "\n").encode())
    except (OSError, process_helper.helper.LoopbackError):
        failed = True
    if failed:
        result.update(status="BLOCKED", evidenceFailure="EVIDENCE_WRITE_FAILED")
    try:
        exclusive(output / "report.json", json.dumps(result, sort_keys=True).encode())
    except (OSError, process_helper.helper.LoopbackError):
        result.update(
            status="BLOCKED",
            evidenceFailure="EVIDENCE_WRITE_FAILED",
            reportPublication="UNKNOWN",
        )


def validate_identity(output, nonce):
    raw = read(output / "runtime-identity.properties", 65_536).decode("latin-1")
    properties = {}
    for line in raw.splitlines():
        if not line or line.startswith("#"):
            continue
        key, separator, value = line.partition("=")
        if not separator or key in properties:
            raise Error("RUNTIME_IDENTITY_INVALID")
        properties[key] = (
            value.replace("\\:", ":").replace("\\=", "=").replace("\\\\", "\\")
        )
    expected = (
        "91a814ac4f40d60dee91d842e1a8ad874c62197984403d0e3c30d39e55cf53b3",
        "8ce969116cac95bd61b07a8d5e07174b352e63301473caac72c395e3c08488d2",
    )
    if properties.get("nonce") != nonce:
        raise Error("RUNTIME_IDENTITY_NONCE_INVALID")
    for key in (
        "java.vendor",
        "java.runtime.version",
        "os.version",
        "os.arch",
        "sun.arch.data.model",
        "pointerBytes",
        "structureSize",
    ):
        if properties.get(key) in (None, "", "NOT_RETAINED"):
            raise Error("RUNTIME_IDENTITY_MISSING")
    for i, digest in enumerate(expected):
        if (
            properties.get(f"jarSha.{i}") != digest
            or not 0 < int(properties.get(f"jarSize.{i}", "0")) <= 16_777_216
        ):
            raise Error("JNA_IDENTITY_MISMATCH")
        jar = Path(properties.get(f"jarPath.{i}", ""))
        if hashlib.sha256(read(jar, 16_777_216)).hexdigest() != digest:
            raise Error("JNA_ARTIFACT_HASH_MISMATCH")
    for i in range(5):
        if hashlib.sha256(
            read(output / f"executed-class-{i}.bin", 1_048_576)
        ).hexdigest() != properties.get(f"classSha.{i}"):
            raise Error("EXECUTED_CLASS_IDENTITY_MISMATCH")
    return properties


def gates(root, output, expected_head):
    process_helper.safe_path(root)
    process_helper.safe_path(output, exists=False)
    if (
        output.exists()
        or output == APPROVED
        or not output.is_relative_to(APPROVED)
        or not output.parent.is_dir()
    ):
        raise Error("EXCLUSIVE_APPROVED_OUTPUT_REQUIRED")
    if os.name != "nt" or len(expected_head) != 40:
        raise Error("WINDOWS_AND_EXPLICIT_HEAD_REQUIRED")
    if process_helper.clean(root) != expected_head:
        raise Error("EXPECTED_COMMITTED_CLEAN_HEAD_REQUIRED")
    return sources(root)


def run(root, output, expected_head):
    before = gates(root, output, expected_head)
    output.mkdir()
    nonce = secrets.token_hex(16)
    result = {
        "status": "BLOCKED",
        "javaCustody": "JAVA_CUSTODY_NOT_VALIDATED",
        "dedicatedForgeClassloader": "NOT_ESTABLISHED",
        "nativeExecuted": False,
        "forceKilled": False,
    }
    process = None
    try:
        existing = root / XML
        if existing.exists():
            old = read(existing, 1_048_576)
            (output / "prior-xml-identity.json").write_text(
                json.dumps(
                    {
                        "sha256": hashlib.sha256(old).hexdigest(),
                        "mtimeNs": existing.stat().st_mtime_ns,
                        "size": len(old),
                    }
                ),
                encoding="utf-8",
            )
        environment = os.environ.copy()
        environment.update(
            {
                "RUN_JAVA_CUSTODY_MECHANISM_NATIVE": "1",
                "JAVA_CUSTODY_RUN_NONCE": nonce,
                "JAVA_CUSTODY_EVIDENCE_DIR": str(output),
                "GRADLE_USER_HOME": str(root / ".gradle-user-home"),
                "LUOSANDBOX_PYTHON_INTEROP_DIR": str(
                    root / ".gradle-user-home/interop/python-to-java-v1"
                ),
            }
        )
        started = time.time_ns()
        process = process_helper.LoggedProcess(
            command(root), root / "bridge/forge", environment, output / "gradle.log"
        )
        result.update(nativeExecuted=True, ownedPid=process.pid)
        if process.wait(90) != 0:
            raise Error("JAVA_PROCESS_FAILED")
        raw, status, observed = validate_xml(root / XML, started, nonce)
        identity = validate_identity(output, nonce)
        with (output / "fresh-junit.xml").open("xb") as stream:
            stream.write(raw)
        if sources(root) != before or process_helper.clean(root) != expected_head:
            raise Error("SOURCE_OR_HEAD_DRIFT")
        result.update(
            status=status,
            scenarios=observed,
            sourceHashes=before,
            nonce=nonce,
            runtimeIdentity=identity,
        )
    except (
        process_helper.helper.LoopbackError,
        OSError,
        ValueError,
        ET.ParseError,
    ) as failure:
        result["failure"] = getattr(failure, "code", "EXECUTION_OR_EVIDENCE_FAILED")
        native = getattr(failure, "native_cleanup", None)
        if native is not None:
            result["nativeCleanup"] = native
            result["forceKilled"] = native.get("forceKilled", True)
    finally:
        if process is not None:
            try:
                process.finish()
            except BaseException:
                result.update(status="BLOCKED", cleanup="UNKNOWN")
            result["ownedTreeCleanup"] = process.tree.snapshot()
            result["forceKilled"] = process.tree.force_killed
            if (
                process.tree.force_killed
                or process.tree.failure
                or process.reader.failure
            ):
                result["status"] = "BLOCKED"
        publish_evidence(output, result)
    return result


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--sandbox", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--expected-head", required=True)
    args = parser.parse_args(argv)
    try:
        result = run(args.sandbox, args.output, args.expected_head)
    except (process_helper.helper.LoopbackError, OSError) as failure:
        result = {
            "status": "BLOCKED",
            "failure": getattr(failure, "code", "EVIDENCE_WRITE_FAILED"),
        }
    print(json.dumps(result, sort_keys=True))
    return 0 if result["status"] == "MECHANISM_SMOKE_PASS" else 2


if __name__ == "__main__":
    raise SystemExit(main())
