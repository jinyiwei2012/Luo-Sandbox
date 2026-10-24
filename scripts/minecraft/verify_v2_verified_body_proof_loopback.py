"""Run the real LP11-to-non-authoritative-candidate loopback evidence gate."""

from __future__ import annotations

import argparse
import hashlib
import importlib.util
import json
import os
import subprocess
import tempfile
import time
import xml.etree.ElementTree as ET
from pathlib import Path
from urllib.request import ProxyHandler, build_opener

BASE = Path(__file__).with_name("verify_v2_body_route_loopback.py")
SPEC = importlib.util.spec_from_file_location("v2_route_base", BASE)
base = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(base)

EXPECTED_HEAD = "0b8545cb3760e206c86df0809155e63370c2ed44"
JUNIT = Path(
    "build/test-results/test/TEST-io.github.jinyiwei2012.luosandbox.transport.PostAuthV2VerifiedBodyProofLoopbackTest.xml"
)


def source_files(agent: Path, forge: Path) -> dict[str, Path]:
    files = base.source_files(agent, forge)
    java = forge / "src"
    additions = {
        "harness:scripts/minecraft/verify_v2_verified_body_proof_loopback.py": Path(
            __file__
        ).resolve(),
        "harness-test:scripts/tests/test_v2_verified_body_proof_loopback.py": Path(
            __file__
        )
        .resolve()
        .parents[1]
        / "tests/test_v2_verified_body_proof_loopback.py",
        "java:src/test/java/io/github/jinyiwei2012/luosandbox/transport/PostAuthV2VerifiedBodyProofLoopbackTest.java": java
        / "test/java/io/github/jinyiwei2012/luosandbox/transport/PostAuthV2VerifiedBodyProofLoopbackTest.java",
        "java:src/test/java/io/github/jinyiwei2012/luosandbox/security/BodyProofIngressTestAccess.java": java
        / "test/java/io/github/jinyiwei2012/luosandbox/security/BodyProofIngressTestAccess.java",
    }
    for name in (
        "BodyProofV2CandidateBridge.java",
        "BodyProofIngress.java",
        "PendingBodyProof.java",
        "BodyProofAuth.java",
        "BodyProofMessageCodec.java",
    ):
        additions[
            f"java:src/main/java/io/github/jinyiwei2012/luosandbox/security/{name}"
        ] = (java / f"main/java/io/github/jinyiwei2012/luosandbox/security/{name}")
    additions[
        "java:src/main/java/io/github/jinyiwei2012/luosandbox/security/forge/BodyProofChannelHandler.java"
    ] = (
        java
        / "main/java/io/github/jinyiwei2012/luosandbox/security/forge/BodyProofChannelHandler.java"
    )
    additions[
        "java:src/main/java/io/github/jinyiwei2012/luosandbox/security/forge/BodyProofNetworkFrame.java"
    ] = (
        java
        / "main/java/io/github/jinyiwei2012/luosandbox/security/forge/BodyProofNetworkFrame.java"
    )
    files.update(additions)
    return files


def snapshot(agent: Path, forge: Path) -> dict[str, str]:
    return {
        name: hashlib.sha256(base.base._read(path)).hexdigest()
        for name, path in source_files(agent, forge).items()
    }


def parse_junit(path: Path, started: float) -> dict[str, object]:
    if (
        path.is_symlink()
        or not path.is_file()
        or not 0 < path.stat().st_size <= base.base.MAX_JUNIT_BYTES
        or path.stat().st_mtime < started - 2
    ):
        raise base.base.VerificationError("JUNIT_MISSING_OR_STALE")
    raw = path.read_bytes()
    if b"<!DOCTYPE" in raw.upper() or b"<!ENTITY" in raw.upper():
        raise base.base.VerificationError("JUNIT_UNSAFE_XML")
    try:
        suite = ET.fromstring(raw)
    except ET.ParseError as error:
        raise base.base.VerificationError("JUNIT_INVALID_XML") from error
    cases = suite.findall("testcase")
    if (
        suite.attrib.get("failures") != "0"
        or suite.attrib.get("errors") != "0"
        or suite.attrib.get("skipped") != "0"
        or len(cases) != 1
    ):
        raise base.base.VerificationError("JUNIT_NOT_CLEAN")
    if cases[0].attrib.get("name") != "verifiedLp11NoticeRouteCandidate()":
        raise base.base.VerificationError("JUNIT_CASE_INVALID")
    output = "".join(node.text or "" for node in suite.findall("system-out"))
    lines = [
        line
        for line in output.splitlines()
        if line.startswith("V2_VERIFIED_BODY_PROOF_METRIC ")
    ]
    if len(lines) != 1:
        raise base.base.VerificationError("PROOF_METRIC_MISSING")
    metric = json.loads(
        lines[0].removeprefix("V2_VERIFIED_BODY_PROOF_METRIC "),
        object_pairs_hook=base.base._unique_json_object,
    )
    expected = {
        "scenario",
        "proofVerified",
        "proofConsumed",
        "candidateCount",
        "rawPayloadMatched",
        "expiryOriginal",
    }
    if type(metric) is not dict or set(metric) != expected:
        raise base.base.VerificationError("PROOF_METRIC_INVALID")
    if (
        metric["scenario"] != "VERIFIED_LP11_NOTICE_ROUTE_CANDIDATE"
        or any(
            type(metric[key]) is not bool or metric[key] is not True
            for key in (
                "proofVerified",
                "proofConsumed",
                "rawPayloadMatched",
                "expiryOriginal",
            )
        )
        or type(metric["candidateCount"]) is not int
        or metric["candidateCount"] != 1
    ):
        raise base.base.VerificationError("PROOF_METRIC_INVALID")
    return {
        "junitSha256": hashlib.sha256(raw).hexdigest(),
        "proofMetric": metric,
        "_junitRaw": raw,
    }


def read_proof_status(port: int) -> dict[str, object]:
    with build_opener(ProxyHandler({})).open(
        f"http://127.0.0.1:{port}/minecraft/v2/test-verified-proof-status",
        timeout=1,
    ) as response:
        raw = response.read(8193)
    value = base.base._decode_json_object(raw, 8192, "INVALID_PROOF_STATUS_JSON")
    if set(value) != {
        "nonAuthoritativeCandidateCount",
        "routeNoticeStructureValid",
        "routeNoticeSha256",
        "coordinatorPresent",
    }:
        raise base.base.VerificationError("INVALID_PROOF_STATUS_SCHEMA")
    if (
        type(value["nonAuthoritativeCandidateCount"]) is not int
        or type(value["routeNoticeStructureValid"]) is not bool
        or type(value["routeNoticeSha256"]) is not list
        or any(
            type(item) is not str or len(item) != 64
            for item in value["routeNoticeSha256"]
        )
        or len(value["routeNoticeSha256"]) > 2
        or type(value["coordinatorPresent"]) is not bool
    ):
        raise base.base.VerificationError("INVALID_PROOF_STATUS_TYPES")
    return value


def run(agent: Path, forge: Path, python: Path) -> dict[str, object]:
    base.base._require_prerequisites(agent, forge, python)
    head = subprocess.run(
        ["git", "rev-parse", "HEAD"],
        cwd=agent,
        text=True,
        capture_output=True,
        check=False,
    )
    if head.returncode != 0:
        raise base.base.PrerequisiteError("GIT_HEAD_UNAVAILABLE")
    if head.stdout.strip() != EXPECTED_HEAD:
        raise base.base.VerificationError("UNEXPECTED_UPSTREAM_HEAD")
    before = snapshot(agent, forge)
    port = base.base.free_port()
    artifact = forge.parents[1] / ".gradle-user-home" / "interop"
    artifact.mkdir(parents=True, exist_ok=True)
    receipt_dir = tempfile.TemporaryDirectory(prefix="luosandbox-v2-proof-")
    receipt = Path(receipt_dir.name) / "receipt.json"
    token, nonce = base.secrets.token_hex(32), base.secrets.token_hex(16)
    environment = os.environ.copy()
    environment.update(
        {
            "PYTHONPATH": str(base.base.server_root(agent)),
            "LUOSANDBOX_LOOPBACK_SHUTDOWN_TOKEN": token,
            "LUOSANDBOX_LOOPBACK_SHUTDOWN_RECEIPT": str(receipt),
            "LUOSANDBOX_LOOPBACK_SHUTDOWN_NONCE": nonce,
        }
    )
    log = (artifact / f"v2-verified-proof-{time.time_ns()}.log").open(
        "w", encoding="utf-8"
    )
    process = subprocess.Popen(
        base.base.server_command(python, agent, port),
        cwd=base.base.server_root(agent),
        env=environment,
        stdin=subprocess.DEVNULL,
        stdout=log,
        stderr=subprocess.STDOUT,
        text=True,
    )
    cleanup = "NOT_STARTED"
    started = time.time()
    try:
        base.base.wait_ready(port, process)
        status = base.base.read_status(port)
        base.base.validate_loaded(status, before, source_files(agent, forge))
        junit = forge / JUNIT
        if junit.exists():
            junit.unlink()
        java_env = os.environ.copy()
        java_env.update(
            {
                "RUN_MINECRAFT_V2_VERIFIED_BODY_PROOF_LOOPBACK": "1",
                "MINECRAFT_V2_LOOPBACK_URL": f"ws://127.0.0.1:{port}/minecraft/v2/ws",
                "MINECRAFT_V2_STATUS_URL": f"http://127.0.0.1:{port}/minecraft/v2/test-verified-proof-status",
                "GRADLE_USER_HOME": str(forge.parents[1] / ".gradle-user-home"),
            }
        )
        command = [
            base.base.gradle_command(forge)[0],
            "--offline",
            "--no-daemon",
            "--rerun-tasks",
            "--max-workers=2",
            "test",
            "--tests",
            "*PostAuthV2VerifiedBodyProofLoopbackTest",
        ]
        completed = subprocess.run(
            command,
            cwd=forge,
            env=java_env,
            stdin=subprocess.DEVNULL,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
            timeout=120,
        )
        if completed.returncode != 0:
            raise base.base.VerificationError(
                "JAVA_PROOF_TEST_FAILED", javaExitCode=completed.returncode
            )
        evidence = parse_junit(junit, started)
        raw = evidence.pop("_junitRaw")
        evidence.update(
            base.base.archive_junit(raw, artifact, "v2-verified-body-proof-junit")
        )
        route_status = read_proof_status(port)
        if (
            route_status["nonAuthoritativeCandidateCount"] != 0
            or route_status["routeNoticeStructureValid"] is not True
            or route_status["coordinatorPresent"] is not False
            or not route_status["routeNoticeSha256"]
        ):
            raise base.base.VerificationError("PROOF_RUNTIME_EVIDENCE_INVALID")
        if snapshot(agent, forge) != before:
            raise base.base.VerificationError("SOURCE_CHANGED_DURING_RUN")
        cleanup, shutdown = base.base.orderly_cleanup(
            process, port, token, receipt, nonce
        )
        return {
            "status": "PASS",
            "scope": "TEST_V2_VERIFIED_BODY_PROOF_LOOPBACK",
            "actuation": "DISABLED",
            "agentHead": EXPECTED_HEAD,
            "sourceHashes": before,
            "loadedModuleHashes": status["loadedModuleHashes"],
            "loadedModulePaths": status["loadedModulePaths"],
            "routeStatus": route_status,
            "cleanup": cleanup,
            "shutdownReceipt": shutdown,
            "javaExitCode": completed.returncode,
            "pythonExitCode": process.returncode,
            **evidence,
        }
    finally:
        if process.poll() is None:
            base.base.fallback_cleanup(process)
        log.close()
        receipt_dir.cleanup()


def main(argv=None):
    parser = argparse.ArgumentParser()
    parser.add_argument("--agent-server", required=True, type=Path)
    parser.add_argument("--forge", required=True, type=Path)
    parser.add_argument("--python", required=True, type=Path)
    parser.add_argument("--report", required=True, type=Path)
    args = parser.parse_args(argv)
    try:
        result = run(
            args.agent_server.resolve(), args.forge.resolve(), args.python.resolve()
        )
        code = 0
    except base.base.LoopbackError as error:
        result = {
            "status": error.status,
            "scope": "TEST_V2_VERIFIED_BODY_PROOF_LOOPBACK",
            "actuation": "DISABLED",
            "failure": error.code,
            **error.evidence,
        }
        code = 2
    except Exception:
        result = {
            "status": "FAILED",
            "scope": "TEST_V2_VERIFIED_BODY_PROOF_LOOPBACK",
            "actuation": "DISABLED",
            "failure": "UNEXPECTED_HARNESS_FAILURE",
        }
        code = 2
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(result, sort_keys=True) + "\n", encoding="utf-8")
    print(
        json.dumps(
            {"status": result["status"], "scope": result["scope"]}, sort_keys=True
        )
    )
    return code


if __name__ == "__main__":
    raise SystemExit(main())
