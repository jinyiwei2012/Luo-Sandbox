"""Run the real LP11-to-non-authoritative-candidate loopback evidence gate."""

from __future__ import annotations

import argparse
import ctypes
import hashlib
import importlib.util
import json
import os
import subprocess
import tempfile
import time
import xml.etree.ElementTree as ET
from datetime import datetime, timezone
from pathlib import Path
from platform import platform
from urllib.request import ProxyHandler, build_opener

BASE = Path(__file__).with_name("verify_v2_body_route_loopback.py")
SPEC = importlib.util.spec_from_file_location("v2_route_base", BASE)
base = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(base)

EXPECTED_HEAD = "ca12c72f4cfa4e794b88897242fe882e1811b54b"
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
    server = base.base.server_root(agent)
    for name in ("epoch_platform.py", "epoch_worker.py"):
        additions[f"python:src/adapter/minecraft/{name}"] = (
            server / f"src/adapter/minecraft/{name}"
        )
    for path in (
        "src/adapter/minecraft/control_binding.py",
        "src/adapter/minecraft/v2_body_routes.py",
        "src/adapter/minecraft/v2_transport.py",
        "tests/unit/adapter/test_minecraft_control_binding.py",
        "tests/unit/adapter/test_minecraft_v2_body_routes.py",
        "tests/unit/adapter/test_minecraft_v2_transport.py",
        "tests/unit/adapter/test_minecraft_deployment_files.py",
    ):
        additions[f"frozen:{path}"] = server / path
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


def runtime_environment(python: Path) -> dict[str, str]:
    python_version = subprocess.run(
        [str(python), "--version"], capture_output=True, text=True, check=True
    )
    java_version = subprocess.run(
        ["java", "-version"], capture_output=True, text=True, check=True
    )
    filesystem = ctypes.create_unicode_buffer(64)
    kernel = ctypes.WinDLL("kernel32", use_last_error=True)
    kernel.GetVolumeInformationW.argtypes = (
        ctypes.c_wchar_p,
        ctypes.c_wchar_p,
        ctypes.c_uint,
        ctypes.c_void_p,
        ctypes.c_void_p,
        ctypes.c_void_p,
        ctypes.c_wchar_p,
        ctypes.c_uint,
    )
    kernel.GetVolumeInformationW.restype = ctypes.c_int
    if not kernel.GetVolumeInformationW(
        "C:\\", None, 0, None, None, None, filesystem, len(filesystem)
    ):
        raise base.base.PrerequisiteError("FILESYSTEM_IDENTITY_UNAVAILABLE")
    return {
        "pythonExecutable": str(python.resolve()),
        "pythonVersion": (python_version.stdout or python_version.stderr).strip(),
        "javaVersion": (java_version.stderr or java_version.stdout).splitlines()[0],
        "windowsVersion": platform(),
        "filesystemName": filesystem.value,
    }


def parse_junit(
    path: Path, started: float, mode: str = "OBSERVATIONAL"
) -> dict[str, object]:
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
        "mode",
        "proofVerified",
        "proofConsumed",
        "candidateCount",
        "rawPayloadMatched",
        "expiryOriginal",
    }
    headless = {
        "admissionCount",
        "pendingCount",
        "ticketCount",
        "prepareCount",
        "workerOutstandingCount",
        "pendingPhase",
        "pendingCommitted",
        "activePresent",
        "ingressPresent",
        "stageScopePresent",
        "bindingCommitCount",
        "productionCapabilityVerified",
        "platformReason",
    }
    if mode == "HEADLESS_ADMISSION":
        expected |= headless
    if type(metric) is not dict or set(metric) != expected:
        raise base.base.VerificationError("PROOF_METRIC_INVALID")
    if (
        metric["scenario"] != "VERIFIED_LP11_NOTICE_ROUTE_CANDIDATE"
        or metric["mode"] != mode
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
    if mode == "HEADLESS_ADMISSION" and (
        any(
            type(metric[key]) is not int or metric[key] != 1
            for key in (
                "admissionCount",
                "pendingCount",
                "ticketCount",
                "prepareCount",
                "workerOutstandingCount",
            )
        )
        or metric["pendingPhase"] != "PREPARE"
        or any(
            type(metric[key]) is not bool or metric[key] is not False
            for key in (
                "pendingCommitted",
                "activePresent",
                "ingressPresent",
                "stageScopePresent",
                "productionCapabilityVerified",
            )
        )
        or type(metric["bindingCommitCount"]) is not int
        or metric["bindingCommitCount"] != 0
        or metric["platformReason"] != "LOCK_ONLY_NOT_PRODUCTION_CAPABILITY"
    ):
        raise base.base.VerificationError("PROOF_METRIC_INVALID")
    return {
        "junitSha256": hashlib.sha256(raw).hexdigest(),
        "proofMetric": metric,
        "_junitRaw": raw,
    }


def read_proof_status(port: int, mode: str = "OBSERVATIONAL") -> dict[str, object]:
    endpoint = (
        "test-headless-admission-status"
        if mode == "HEADLESS_ADMISSION"
        else "test-verified-proof-status"
    )
    with build_opener(ProxyHandler({})).open(
        f"http://127.0.0.1:{port}/minecraft/v2/{endpoint}",
        timeout=1,
    ) as response:
        raw = response.read(8193)
    value = base.base._decode_json_object(raw, 8192, "INVALID_PROOF_STATUS_JSON")
    if mode == "HEADLESS_ADMISSION":
        expected = {
            "mode",
            "activeRoles",
            "nonAuthoritativeCandidateCount",
            "admissionCount",
            "coordinatorPendingCount",
            "ticketCount",
            "prepareCount",
            "commandTypes",
            "workerOutstandingCount",
            "workerState",
            "pendingPhase",
            "pendingCommitted",
            "activePresent",
            "ingressPresent",
            "stageScopePresent",
            "bindingCommitCount",
            "platformReason",
            "productionCapabilityVerified",
        }
        if set(value) != expected:
            raise base.base.VerificationError("INVALID_PROOF_STATUS_SCHEMA")
        if (
            value["mode"] != mode
            or value["activeRoles"] != []
            or any(
                type(value[key]) is not int or value[key] != 0
                for key in (
                    "nonAuthoritativeCandidateCount",
                    "admissionCount",
                    "coordinatorPendingCount",
                    "ticketCount",
                    "workerOutstandingCount",
                )
            )
            or type(value["prepareCount"]) is not int
            or value["prepareCount"] != 1
            or value["commandTypes"] != ["BindingPrepare"]
            or value["workerState"] != "AVAILABLE"
            or value["pendingPhase"] is not None
            or value["pendingCommitted"] is not None
            or any(
                type(value[key]) is not bool or value[key] is not False
                for key in (
                    "activePresent",
                    "ingressPresent",
                    "stageScopePresent",
                    "productionCapabilityVerified",
                )
            )
            or type(value["bindingCommitCount"]) is not int
            or value["bindingCommitCount"] != 0
            or value["platformReason"] != "LOCK_ONLY_NOT_PRODUCTION_CAPABILITY"
        ):
            raise base.base.VerificationError("INVALID_PROOF_STATUS_TYPES")
        return value
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


def run_mode(agent: Path, forge: Path, python: Path, mode: str) -> dict[str, object]:
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
            "LUOSANDBOX_LOOPBACK_MODE": mode,
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
                "MINECRAFT_V2_EVIDENCE_MODE": mode,
                "GRADLE_USER_HOME": str(forge.parents[1] / ".gradle-user-home"),
            }
        )
        if mode == "HEADLESS_ADMISSION":
            java_env["MINECRAFT_V2_STATUS_URL"] = (
                f"http://127.0.0.1:{port}/minecraft/v2/test-headless-admission-status"
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
        evidence = parse_junit(junit, started, mode)
        raw = evidence.pop("_junitRaw")
        evidence.update(
            base.base.archive_junit(raw, artifact, "v2-verified-body-proof-junit")
        )
        route_status = read_proof_status(port, mode)
        if mode == "OBSERVATIONAL" and (
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
        if mode == "HEADLESS_ADMISSION":
            if (
                shutdown["workerClosed"] is not True
                or shutdown["workerThreadDead"] is not True
                or shutdown["workerOutstandingCount"] != 0
                or shutdown["epochReopen"]
                != {
                    "expectedLastEpoch": 0,
                    "expectedGeneration": 0,
                    "nextEpoch": 1,
                }
            ):
                raise base.base.VerificationError("HEADLESS_CLEANUP_EVIDENCE_INVALID")
        return {
            "status": "PASS",
            "scope": "TEST_V2_VERIFIED_BODY_PROOF_LOOPBACK",
            "mode": mode,
            "actuation": "DISABLED",
            "agentHead": EXPECTED_HEAD,
            "selectedPort": port,
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


def run(agent: Path, forge: Path, python: Path) -> dict[str, object]:
    started = datetime.now(timezone.utc)
    observational = run_mode(agent, forge, python, "OBSERVATIONAL")
    headless = run_mode(agent, forge, python, "HEADLESS_ADMISSION")
    ended = datetime.now(timezone.utc)
    return {
        "status": "PASS",
        "scope": "TEST_V2_FINAL_EVIDENCE",
        "startedAt": started.isoformat(),
        "endedAt": ended.isoformat(),
        "durationMs": int((ended - started).total_seconds() * 1000),
        "environment": runtime_environment(python),
        "modes": {"modeA": observational, "modeB": headless},
    }


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
            "scope": "TEST_V2_FINAL_EVIDENCE",
            "actuation": "DISABLED",
            "failure": error.code,
            **error.evidence,
        }
        code = 2
    except Exception:
        result = {
            "status": "FAILED",
            "scope": "TEST_V2_FINAL_EVIDENCE",
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
