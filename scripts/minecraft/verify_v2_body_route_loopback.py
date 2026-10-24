"""Run the test-only concurrent Java/Python v2 BODY route loopback."""

from __future__ import annotations

import argparse
import hashlib
import importlib.util
import json
import os
import secrets
import subprocess
import tempfile
import time
import xml.etree.ElementTree as ET
from pathlib import Path
from urllib.request import ProxyHandler, build_opener

BASE_PATH = Path(__file__).with_name("verify_v2_transport_loopback.py")
SPEC = importlib.util.spec_from_file_location("v2_loopback_base", BASE_PATH)
base = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(base)

JUNIT = Path(
    "build/test-results/test/TEST-io.github.jinyiwei2012.luosandbox.transport.PostAuthV2BodyRouteLoopbackTest.xml"
)


def source_files(agent: Path, forge: Path) -> dict[str, Path]:
    files = base.source_files(agent, forge)
    files["harness:scripts/minecraft/verify_v2_body_route_loopback.py"] = Path(
        __file__
    ).resolve()
    files["harness-test:scripts/tests/test_v2_body_route_loopback.py"] = (
        Path(__file__).resolve().parents[1] / "tests/test_v2_body_route_loopback.py"
    )
    files[
        "java:src/test/java/io/github/jinyiwei2012/luosandbox/transport/PostAuthV2BodyRouteLoopbackTest.java"
    ] = (
        forge
        / "src/test/java/io/github/jinyiwei2012/luosandbox/transport/PostAuthV2BodyRouteLoopbackTest.java"
    )
    return files


def snapshot(agent: Path, forge: Path) -> dict[str, str]:
    return {
        name: hashlib.sha256(base._read(path)).hexdigest()
        for name, path in source_files(agent, forge).items()
    }


def read_route_status(port: int) -> dict[str, object]:
    with build_opener(ProxyHandler({})).open(
        f"http://127.0.0.1:{port}/minecraft/v2/test-route-status", timeout=1
    ) as response:
        raw = response.read(4097)
    value = base._decode_json_object(raw, 4096, "INVALID_ROUTE_STATUS_JSON")
    if set(value) != {
        "nonAuthoritativeCandidateCount",
        "routeRawPayloadMatched",
        "coordinatorPresent",
    }:
        raise base.VerificationError("INVALID_ROUTE_STATUS_SCHEMA")
    if (
        type(value["nonAuthoritativeCandidateCount"]) is not int
        or type(value["routeRawPayloadMatched"]) is not bool
        or type(value["coordinatorPresent"]) is not bool
    ):
        raise base.VerificationError("INVALID_ROUTE_STATUS_TYPES")
    return value


def parse_junit(path: Path, started: float) -> dict[str, object]:
    if (
        path.is_symlink()
        or not path.is_file()
        or not 0 < path.stat().st_size <= base.MAX_JUNIT_BYTES
        or path.stat().st_mtime < started - 2
    ):
        raise base.VerificationError("JUNIT_MISSING_OR_STALE")
    raw = path.read_bytes()
    if b"<!DOCTYPE" in raw.upper() or b"<!ENTITY" in raw.upper():
        raise base.VerificationError("JUNIT_UNSAFE_XML")
    try:
        suite = ET.fromstring(raw)
    except ET.ParseError as error:
        raise base.VerificationError("JUNIT_INVALID_XML") from error
    cases = suite.findall("testcase")
    if (
        suite.attrib.get("failures") != "0"
        or suite.attrib.get("errors") != "0"
        or suite.attrib.get("skipped") != "0"
        or len(cases) != 1
    ):
        raise base.VerificationError("JUNIT_NOT_CLEAN")
    if (
        cases[0].attrib.get("name")
        != "actualNoticeRouteAndMacValidSyntheticCandidate()"
    ):
        raise base.VerificationError("JUNIT_CASE_INVALID")
    output = "".join(node.text or "" for node in suite.findall("system-out"))
    lines = [
        line for line in output.splitlines() if line.startswith("V2_BODY_ROUTE_METRIC ")
    ]
    if len(lines) != 1:
        raise base.VerificationError("ROUTE_METRIC_MISSING")
    metric = json.loads(
        lines[0].removeprefix("V2_BODY_ROUTE_METRIC "),
        object_pairs_hook=base._unique_json_object,
    )
    if type(metric) is not dict or set(metric) != {
        "scenario",
        "candidateCount",
        "rawPayloadMatched",
    }:
        raise base.VerificationError("ROUTE_METRIC_INVALID")
    if (
        type(metric["scenario"]) is not str
        or metric["scenario"] != "NOTICE_ROUTE_SYNTHETIC_CANDIDATE"
        or type(metric["candidateCount"]) is not int
        or metric["candidateCount"] != 1
        or type(metric["rawPayloadMatched"]) is not bool
        or metric["rawPayloadMatched"] is not True
    ):
        raise base.VerificationError("ROUTE_METRIC_INVALID")
    return {
        "junitSha256": hashlib.sha256(raw).hexdigest(),
        "routeMetric": metric,
        "_junitRaw": raw,
    }


def run(agent: Path, forge: Path, python: Path) -> dict[str, object]:
    base._require_prerequisites(agent, forge, python)
    try:
        head_result = subprocess.run(
            ["git", "rev-parse", "HEAD"],
            cwd=agent,
            text=True,
            capture_output=True,
            check=False,
        )
    except OSError as error:
        raise base.PrerequisiteError("GIT_UNAVAILABLE") from error
    if head_result.returncode != 0:
        raise base.PrerequisiteError("GIT_HEAD_UNAVAILABLE")
    head = head_result.stdout.strip()
    if head != base.EXPECTED_AGENT_HEAD:
        raise base.VerificationError("UNEXPECTED_UPSTREAM_HEAD")
    before = snapshot(agent, forge)
    port = base.free_port()
    artifact = forge.parents[1] / ".gradle-user-home" / "interop"
    artifact.mkdir(parents=True, exist_ok=True)
    receipt_dir = tempfile.TemporaryDirectory(prefix="luosandbox-v2-route-")
    receipt = Path(receipt_dir.name) / "receipt.json"
    token, nonce = secrets.token_hex(32), secrets.token_hex(16)
    env = os.environ.copy()
    env.update(
        {
            "PYTHONPATH": str(base.server_root(agent)),
            "LUOSANDBOX_LOOPBACK_SHUTDOWN_TOKEN": token,
            "LUOSANDBOX_LOOPBACK_SHUTDOWN_RECEIPT": str(receipt),
            "LUOSANDBOX_LOOPBACK_SHUTDOWN_NONCE": nonce,
        }
    )
    log = (artifact / f"v2-body-route-{time.time_ns()}.log").open("w", encoding="utf-8")
    process = None
    cleanup = "NOT_STARTED"
    shutdown = None
    primary_error = None
    started = time.time()
    try:
        try:
            process = subprocess.Popen(
                base.server_command(python, agent, port),
                cwd=base.server_root(agent),
                env=env,
                stdin=subprocess.DEVNULL,
                stdout=log,
                stderr=subprocess.STDOUT,
                text=True,
            )
        except OSError as error:
            raise base.PrerequisiteError("PYTHON_LOOPBACK_START_UNAVAILABLE") from error
        base.wait_ready(port, process)
        initial_status = base.read_status(port)
        base.validate_loaded(initial_status, before, source_files(agent, forge))
        junit = forge / JUNIT
        if junit.exists():
            junit.unlink()
        java_env = os.environ.copy()
        java_env.update(
            {
                "RUN_MINECRAFT_V2_BODY_ROUTE_LOOPBACK": "1",
                "MINECRAFT_V2_LOOPBACK_URL": f"ws://127.0.0.1:{port}/minecraft/v2/ws",
                "MINECRAFT_V2_STATUS_URL": f"http://127.0.0.1:{port}/minecraft/v2/test-route-status",
                "GRADLE_USER_HOME": str(forge.parents[1] / ".gradle-user-home"),
            }
        )
        command = [
            base.gradle_command(forge)[0],
            "--offline",
            "--no-daemon",
            "--rerun-tasks",
            "--max-workers=2",
            "test",
            "--tests",
            "*PostAuthV2BodyRouteLoopbackTest",
        ]
        try:
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
        except subprocess.TimeoutExpired as error:
            raise base.VerificationError("JAVA_ROUTE_TEST_TIMEOUT") from error
        except OSError as error:
            raise base.PrerequisiteError("GRADLE_START_UNAVAILABLE") from error
        if completed.returncode != 0:
            raise base.VerificationError(
                "JAVA_ROUTE_TEST_FAILED", javaExitCode=completed.returncode
            )
        evidence = parse_junit(junit, started)
        raw_junit = evidence.pop("_junitRaw")
        evidence.update(base.archive_junit(raw_junit, artifact, "v2-body-route-junit"))
        route_status = read_route_status(port)
        if (
            type(route_status["nonAuthoritativeCandidateCount"]) is not int
            or route_status["nonAuthoritativeCandidateCount"] != 0
            or route_status["routeRawPayloadMatched"] is not True
            or route_status["coordinatorPresent"] is not False
        ):
            raise base.VerificationError("ROUTE_RUNTIME_EVIDENCE_INVALID")
        if snapshot(agent, forge) != before:
            raise base.VerificationError("SOURCE_CHANGED_DURING_RUN")
        cleanup, shutdown = base.orderly_cleanup(process, port, token, receipt, nonce)
        return {
            "status": "PASS",
            "scope": "TEST_V2_BODY_ROUTE_LOOPBACK",
            "actuation": "DISABLED",
            "agentHead": head,
            "sourceHashes": before,
            "loadedModuleHashes": initial_status["loadedModuleHashes"],
            "loadedModulePaths": initial_status["loadedModulePaths"],
            "routeStatus": route_status,
            "cleanup": cleanup,
            "shutdownReceipt": shutdown,
            "javaExitCode": completed.returncode,
            "pythonExitCode": process.returncode,
            **evidence,
        }
    except (KeyboardInterrupt, SystemExit):
        raise
    except base.LoopbackError as error:
        primary_error = error
    except (OSError, ValueError, subprocess.SubprocessError) as error:
        primary_error = base.VerificationError("HARNESS_EXECUTION_FAILED")
        primary_error.__cause__ = error
    except Exception as error:
        primary_error = base.VerificationError("UNEXPECTED_HARNESS_FAILURE")
        primary_error.__cause__ = error
    finally:
        if process is not None and cleanup != "ORDERLY_APP_SHUTDOWN_AND_PROCESS_EXITED":
            try:
                cleanup, shutdown = base.orderly_cleanup(
                    process, port, token, receipt, nonce
                )
            except base.LoopbackError as shutdown_error:
                cleanup = str(
                    shutdown_error.evidence.get("cleanup")
                    or base.fallback_cleanup(process)
                )
                if primary_error is None:
                    primary_error = shutdown_error
                else:
                    primary_error.evidence["shutdownFailure"] = shutdown_error.code
            except (OSError, subprocess.SubprocessError):
                cleanup = base.fallback_cleanup(process)
                if primary_error is None:
                    primary_error = base.VerificationError("ORDERLY_SHUTDOWN_FAILED")
        log.close()
        receipt_dir.cleanup()
    if primary_error is not None:
        primary_error.evidence["cleanup"] = cleanup
        if shutdown is not None:
            primary_error.evidence["shutdownReceipt"] = shutdown
        raise primary_error
    raise base.VerificationError("UNEXPECTED_HARNESS_FAILURE")


def main(argv=None) -> int:
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
    except base.LoopbackError as error:
        result = {
            "status": error.status,
            "scope": "TEST_V2_BODY_ROUTE_LOOPBACK",
            "actuation": "DISABLED",
            "failure": error.code,
            **error.evidence,
        }
        code = 2
    except Exception:
        result = {
            "status": "FAILED",
            "scope": "TEST_V2_BODY_ROUTE_LOOPBACK",
            "actuation": "DISABLED",
            "failure": "UNEXPECTED_HARNESS_FAILURE",
        }
        code = 2
    try:
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(
            json.dumps(result, sort_keys=True) + "\n", encoding="utf-8"
        )
    except OSError:
        print(
            json.dumps(
                {"status": "FAILED", "scope": "TEST_V2_BODY_ROUTE_LOOPBACK"},
                sort_keys=True,
            )
        )
        return 2
    print(
        json.dumps(
            {"status": result["status"], "scope": result["scope"]}, sort_keys=True
        )
    )
    return code


if __name__ == "__main__":
    raise SystemExit(main())
