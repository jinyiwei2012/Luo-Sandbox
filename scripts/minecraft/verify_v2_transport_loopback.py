"""Run and attest the test-only Java/Python v2 WebSocket loopback."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import secrets
import socket
import subprocess
import sys
import tempfile
import time
import xml.etree.ElementTree as element_tree
from pathlib import Path
from urllib.request import ProxyHandler, Request, build_opener

TIMEOUT_SECONDS = 120
STARTUP_TIMEOUT_SECONDS = 20
SHUTDOWN_TIMEOUT_SECONDS = 15
MAX_JUNIT_BYTES = 1_048_576
MAX_RECEIPT_BYTES = 16_384
MAX_STATUS_BYTES = 65_536
MAX_SHUTDOWN_ACK_BYTES = 4_096
REQUIRED_ROLES = {"SERVER_AUTHORITY", "CLIENT_CONTROLLER"}
REQUIRED_DURATION_MS = 2300
JUNIT_RELATIVE = Path(
    "build/test-results/test/TEST-io.github.jinyiwei2012.luosandbox.transport.PostAuthV2LoopbackTest.xml"
)
EXPECTED_AGENT_HEAD = "da013ad5f55d56c87fbc0afe7e9a03cfd4ab6e1b"


class LoopbackError(RuntimeError):
    status = "FAILED"

    def __init__(self, code: str, **evidence: object) -> None:
        super().__init__(code)
        self.code = code
        self.evidence = evidence


class PrerequisiteError(LoopbackError):
    status = "BLOCKED"


class VerificationError(LoopbackError):
    status = "FAILED"


def server_root(agent: Path) -> Path:
    candidate = agent / "server"
    return candidate if candidate.is_dir() else agent


def source_files(
    agent: Path, forge: Path, harness: Path | None = None
) -> dict[str, Path]:
    server = server_root(agent)
    python = (
        "deployment_files.py",
        "deployment_acl.py",
        "deployment_manifest.py",
        "v2_startup.py",
        "control_auth.py",
        "current_roles.py",
        "postauth_keys.py",
        "postauth_frame.py",
        "postauth_session.py",
        "transport.py",
        "wire.py",
        "typed_ingress.py",
        "epoch_store.py",
        "control_binding.py",
        "v2_transport.py",
    )
    java = {
        "security/ControlAuth.java",
        "security/ControlDeploymentManifest.java",
        "security/PostAuthSessionKeys.java",
        "security/PostAuthFrameCodec.java",
        "transport/PostAuthPeerSession.java",
        "wire/StrictJson.java",
    }
    result = {
        "harness:scripts/minecraft/verify_v2_transport_loopback.py": harness
        or Path(__file__).resolve(),
        "harness-test:scripts/tests/test_v2_transport_loopback.py": Path(__file__)
        .resolve()
        .parents[1]
        / "tests/test_v2_transport_loopback.py",
        "python:tests/support/minecraft_v2_loopback_app.py": server
        / "tests/support/minecraft_v2_loopback_app.py",
        "java:src/test/java/io/github/jinyiwei2012/luosandbox/transport/PostAuthV2LoopbackTest.java": forge
        / "src/test/java/io/github/jinyiwei2012/luosandbox/transport/PostAuthV2LoopbackTest.java",
    }
    result.update(
        {
            f"python:src/adapter/minecraft/{name}": server
            / f"src/adapter/minecraft/{name}"
            for name in python
        }
    )
    result.update(
        {
            f"java:src/main/java/io/github/jinyiwei2012/luosandbox/{name}": forge
            / f"src/main/java/io/github/jinyiwei2012/luosandbox/{name}"
            for name in java
        }
    )
    return result


def _read(path: Path) -> bytes:
    try:
        valid = (
            not path.is_symlink()
            and path.is_file()
            and 0 < path.stat().st_size <= 2_097_152
        )
    except OSError as error:
        raise VerificationError("REVIEWED_SOURCE_INVALID") from error
    if not valid:
        raise VerificationError("REVIEWED_SOURCE_INVALID")
    return path.read_bytes()


def source_snapshot(
    agent: Path, forge: Path, harness: Path | None = None
) -> dict[str, str]:
    files = source_files(agent, forge, harness)
    if len(files) != len(set(files)):
        raise VerificationError("REVIEWED_SOURCE_IDENTITY_DUPLICATE")
    return {
        name: hashlib.sha256(_read(path)).hexdigest() for name, path in files.items()
    }


def free_port() -> int:
    try:
        with socket.socket() as listener:
            listener.bind(("127.0.0.1", 0))
            return listener.getsockname()[1]
    except OSError as error:
        raise PrerequisiteError("LOOPBACK_PORT_UNAVAILABLE") from error


def server_command(python: Path, agent: Path, port: int) -> list[str]:
    app = server_root(agent) / "tests/support/minecraft_v2_loopback_app.py"
    return [str(python), str(app), "--host", "127.0.0.1", "--port", str(port)]


def gradle_command(forge: Path) -> list[str]:
    executable = forge / ("gradlew.bat" if os.name == "nt" else "gradlew")
    return [
        str(executable),
        "--offline",
        "--no-daemon",
        "--rerun-tasks",
        "--max-workers=2",
        "test",
        "--tests",
        "*PostAuthV2LoopbackTest",
    ]


def _opener():
    return build_opener(ProxyHandler({}))


def _unique_json_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("duplicate JSON key")
        result[key] = value
    return result


def _decode_json_object(raw: bytes, limit: int, code: str) -> dict[str, object]:
    if len(raw) > limit:
        raise VerificationError(code)
    try:
        value = json.loads(raw.decode("utf-8"), object_pairs_hook=_unique_json_object)
    except (UnicodeError, ValueError, TypeError) as error:
        raise VerificationError(code) from error
    if type(value) is not dict:
        raise VerificationError(code)
    return value


def _is_exact_string_dict(value: object) -> bool:
    return type(value) is dict and all(
        type(key) is str and type(item) is str for key, item in value.items()
    )


def validate_status(value: dict[str, object]) -> dict[str, object]:
    expected_keys = {
        "enabled",
        "routeRegistered",
        "activeRoles",
        "pendingCount",
        "completedSessions",
        "applicationFrames",
        "loadedModuleHashes",
        "loadedModulePaths",
    }
    if set(value) != expected_keys:
        raise VerificationError("INVALID_LOOPBACK_STATUS_SCHEMA")
    if type(value["enabled"]) is not bool or type(value["routeRegistered"]) is not bool:
        raise VerificationError("INVALID_LOOPBACK_STATUS_TYPES")
    if value["enabled"] is not True or value["routeRegistered"] is not True:
        raise VerificationError("INVALID_LOOPBACK_STATUS_VALUES")
    roles = value["activeRoles"]
    if type(roles) is not list or any(type(role) is not str for role in roles):
        raise VerificationError("INVALID_LOOPBACK_STATUS_TYPES")
    if any(
        type(value[name]) is not int
        for name in ("pendingCount", "completedSessions", "applicationFrames")
    ):
        raise VerificationError("INVALID_LOOPBACK_STATUS_TYPES")
    if not _is_exact_string_dict(
        value["loadedModuleHashes"]
    ) or not _is_exact_string_dict(value["loadedModulePaths"]):
        raise VerificationError("INVALID_LOOPBACK_STATUS_TYPES")
    return value


def read_status(port: int) -> dict[str, object]:
    with _opener().open(
        f"http://127.0.0.1:{port}/minecraft/v2/test-status", timeout=1
    ) as response:
        raw = response.read(MAX_STATUS_BYTES + 1)
    return validate_status(
        _decode_json_object(raw, MAX_STATUS_BYTES, "INVALID_LOOPBACK_STATUS_JSON")
    )


def wait_ready(
    port: int, process: subprocess.Popen, timeout: float = STARTUP_TIMEOUT_SECONDS
) -> None:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if process.poll() is not None:
            raise PrerequisiteError("PYTHON_LOOPBACK_START_FAILED")
        try:
            read_status(port)
            return
        except (OSError, ValueError):
            pass
        time.sleep(0.1)
    raise PrerequisiteError("PYTHON_LOOPBACK_START_TIMEOUT")


def validate_loaded(
    status: dict[str, object], expected: dict[str, str], files: dict[str, Path]
) -> None:
    hashes, paths = status.get("loadedModuleHashes"), status.get("loadedModulePaths")
    wanted = {
        key: value for key, value in expected.items() if key.startswith("python:")
    }
    wanted_paths = {key: str(files[key].resolve()) for key in wanted}
    if hashes != wanted or paths != wanted_paths:
        raise VerificationError("PYTHON_LOADED_SOURCE_IDENTITY_INVALID")


def parse_junit(path: Path, started: float) -> dict[str, object]:
    if (
        not path.is_file()
        or path.stat().st_size > MAX_JUNIT_BYTES
        or path.stat().st_mtime < started - 2
    ):
        raise VerificationError("JUNIT_MISSING_OR_STALE")
    raw = path.read_bytes()
    if b"<!DOCTYPE" in raw.upper() or b"<!ENTITY" in raw.upper():
        raise VerificationError("JUNIT_UNSAFE_XML")
    try:
        suite = element_tree.fromstring(raw)
    except element_tree.ParseError as error:
        raise VerificationError("JUNIT_INVALID_XML") from error
    cases = suite.findall("testcase")
    if (
        suite.attrib.get("failures") != "0"
        or suite.attrib.get("errors") != "0"
        or suite.attrib.get("skipped") != "0"
        or len(cases) != 2
    ):
        raise VerificationError("JUNIT_NOT_CLEAN")
    metrics = {}
    output = "".join(node.text or "" for node in suite.findall("system-out"))
    try:
        for line in output.splitlines():
            if line.startswith("V2_LOOPBACK_METRIC "):
                item = json.loads(
                    line.removeprefix("V2_LOOPBACK_METRIC "),
                    object_pairs_hook=_unique_json_object,
                )
                if type(item) is not dict or set(item) != {
                    "role",
                    "requiredDurationMs",
                    "measuredDurationMs",
                    "applicationFrames",
                }:
                    raise VerificationError("JUNIT_METRICS_INVALID")
                role = item.get("role")
                if (
                    type(role) is not str
                    or type(item.get("requiredDurationMs")) is not int
                    or type(item.get("measuredDurationMs")) is not int
                    or type(item.get("applicationFrames")) is not int
                    or role in metrics
                ):
                    raise VerificationError("JUNIT_METRICS_INVALID")
                metrics[role] = item
    except (ValueError, TypeError) as error:
        raise VerificationError("JUNIT_METRICS_INVALID") from error
    case_names = [case.attrib.get("name") for case in cases]
    if (
        len(set(case_names)) != 2
        or set(case_names) != REQUIRED_ROLES
        or set(metrics) != set(case_names)
    ):
        raise VerificationError("JUNIT_ROLES_MISSING")
    if any(
        item["requiredDurationMs"] != REQUIRED_DURATION_MS
        or item["measuredDurationMs"] < REQUIRED_DURATION_MS
        or item["applicationFrames"] != 0
        for item in metrics.values()
    ):
        raise VerificationError("JUNIT_RUNTIME_EVIDENCE_INVALID")
    return {
        "roles": sorted(case_names),
        "perRoleHeartbeat": metrics,
        "junitSha256": hashlib.sha256(raw).hexdigest(),
    }


def request_shutdown(port: int, token: str) -> None:
    request = Request(
        f"http://127.0.0.1:{port}/minecraft/v2/test-shutdown",
        method="POST",
        headers={"X-Loopback-Shutdown": token},
    )
    try:
        with _opener().open(request, timeout=3) as response:
            raw = response.read(MAX_SHUTDOWN_ACK_BYTES + 1)
            if response.status != 202:
                raise VerificationError("ORDERLY_SHUTDOWN_REJECTED")
            ack = _decode_json_object(
                raw, MAX_SHUTDOWN_ACK_BYTES, "ORDERLY_SHUTDOWN_ACK_INVALID"
            )
            if set(ack) != {"accepted"} or ack["accepted"] is not True:
                raise VerificationError("ORDERLY_SHUTDOWN_ACK_INVALID")
    except OSError as error:
        raise VerificationError("ORDERLY_SHUTDOWN_UNAVAILABLE") from error


def validate_shutdown_receipt(path: Path, nonce: str) -> dict[str, object]:
    if (
        path.is_symlink()
        or not path.is_file()
        or not 0 < path.stat().st_size <= MAX_RECEIPT_BYTES
    ):
        raise VerificationError("SHUTDOWN_RECEIPT_MISSING")
    try:
        receipt = json.loads(
            path.read_text(encoding="utf-8"), object_pairs_hook=_unique_json_object
        )
    except (OSError, UnicodeError, ValueError) as error:
        raise VerificationError("SHUTDOWN_RECEIPT_INVALID") from error
    expected_keys = {
        "schema",
        "nonce",
        "shutdownComplete",
        "sessionsDrained",
        "peerCloseAttempts",
        "peerCloseFailures",
        "temporaryCleanupAttempted",
        "temporaryCleanupSucceeded",
        "cleanupErrors",
    }
    if (
        type(receipt) is not dict
        or set(receipt) != expected_keys
        or receipt.get("schema") != "luosandbox.v2-loopback-shutdown.v1"
        or receipt.get("nonce") != nonce
    ):
        raise VerificationError("SHUTDOWN_RECEIPT_INVALID")
    if (
        type(receipt.get("peerCloseAttempts")) is not int
        or type(receipt.get("peerCloseFailures")) is not int
    ):
        raise VerificationError("SHUTDOWN_RECEIPT_INVALID")
    if (
        receipt.get("shutdownComplete") is not True
        or receipt.get("sessionsDrained") is not True
        or receipt["peerCloseAttempts"] != 2
        or receipt["peerCloseFailures"] != 0
        or receipt.get("temporaryCleanupAttempted") is not True
        or receipt.get("temporaryCleanupSucceeded") is not True
        or receipt.get("cleanupErrors") != []
    ):
        raise VerificationError("APP_CLEANUP_FAILED")
    return receipt


def fallback_cleanup(process: subprocess.Popen) -> str:
    if process.poll() is not None:
        process.wait(timeout=1)
        return "OWNED_PROCESS_EXITED_AND_WAITED"
    try:
        process.terminate()
        process.wait(timeout=5)
        return "OWNED_PROCESS_TERMINATED_AND_WAITED"
    except (OSError, subprocess.TimeoutExpired):
        process.kill()
        process.wait(timeout=5)
        return "OWNED_PROCESS_KILLED_AND_WAITED"


def orderly_cleanup(
    process: subprocess.Popen, port: int, token: str, receipt_path: Path, nonce: str
) -> tuple[str, dict[str, object]]:
    if process.poll() is not None:
        process.wait(timeout=1)
        raise VerificationError(
            "UNEXPECTED_SERVER_EXIT", cleanup="OWNED_PROCESS_EXITED_AND_WAITED"
        )
    request_shutdown(port, token)
    try:
        returncode = process.wait(timeout=SHUTDOWN_TIMEOUT_SECONDS)
    except subprocess.TimeoutExpired as error:
        raise VerificationError("ORDERLY_SHUTDOWN_TIMEOUT") from error
    if type(returncode) is not int:
        raise VerificationError("ORDERLY_SHUTDOWN_EXIT_INVALID")
    if returncode != 0:
        raise VerificationError(
            "ORDERLY_SHUTDOWN_NONZERO_EXIT", pythonExitCode=returncode
        )
    receipt = validate_shutdown_receipt(receipt_path, nonce)
    return "ORDERLY_APP_SHUTDOWN_AND_PROCESS_EXITED", receipt


def _require_prerequisites(agent: Path, forge: Path, python: Path) -> None:
    gradle = Path(gradle_command(forge)[0])
    if not python.is_file() or not gradle.is_file():
        raise PrerequisiteError("REQUIRED_EXECUTABLE_MISSING")
    if os.name != "nt":
        raise PrerequisiteError("NTFS_PLATFORM_UNAVAILABLE")
    if not (
        server_root(agent) / "tests/support/minecraft_v2_loopback_app.py"
    ).is_file():
        raise PrerequisiteError("LOOPBACK_SUPPORT_APP_MISSING")


def run(agent: Path, forge: Path, python: Path) -> dict[str, object]:
    _require_prerequisites(agent, forge, python)
    try:
        head_result = subprocess.run(
            ["git", "rev-parse", "HEAD"],
            cwd=agent,
            text=True,
            capture_output=True,
            check=False,
        )
    except OSError as error:
        raise PrerequisiteError("GIT_UNAVAILABLE") from error
    if head_result.returncode != 0:
        raise PrerequisiteError("GIT_HEAD_UNAVAILABLE")
    head = head_result.stdout.strip()
    if head != EXPECTED_AGENT_HEAD:
        raise VerificationError("UNEXPECTED_UPSTREAM_HEAD")
    before = source_snapshot(agent, forge)
    files = source_files(agent, forge)
    junit = forge / JUNIT_RELATIVE
    if junit.exists():
        junit.unlink()
    port = free_port()
    artifact = forge.parents[1] / ".gradle-user-home" / "interop"
    artifact.mkdir(parents=True, exist_ok=True)
    process = None
    log = None
    primary_error: LoopbackError | None = None
    result: dict[str, object] | None = None
    cleanup = "NOT_STARTED"
    shutdown_receipt: dict[str, object] | None = None
    with tempfile.TemporaryDirectory(prefix="luosandbox-v2-runner-") as shutdown_dir:
        receipt_path = Path(shutdown_dir) / "shutdown-receipt.json"
        token, nonce = secrets.token_hex(32), secrets.token_hex(16)
        environment = os.environ.copy()
        server = server_root(agent)
        environment.update(
            {
                "PYTHONPATH": str(server),
                "LUOSANDBOX_LOOPBACK_SHUTDOWN_TOKEN": token,
                "LUOSANDBOX_LOOPBACK_SHUTDOWN_RECEIPT": str(receipt_path),
                "LUOSANDBOX_LOOPBACK_SHUTDOWN_NONCE": nonce,
            }
        )
        log = (artifact / f"v2-loopback-{time.time_ns()}.log").open(
            "w", encoding="utf-8"
        )
        try:
            try:
                process = subprocess.Popen(
                    server_command(python, agent, port),
                    cwd=server,
                    stdin=subprocess.DEVNULL,
                    stdout=log,
                    stderr=subprocess.STDOUT,
                    text=True,
                    env=environment,
                )
            except OSError as error:
                raise PrerequisiteError("PYTHON_LOOPBACK_START_UNAVAILABLE") from error
            started = time.time()
            wait_ready(port, process)
            validate_loaded(read_status(port), before, files)
            java_environment = os.environ.copy()
            java_environment.update(
                {
                    "RUN_MINECRAFT_V2_LOOPBACK": "1",
                    "MINECRAFT_V2_LOOPBACK_URL": f"ws://127.0.0.1:{port}/minecraft/v2/ws",
                    "GRADLE_USER_HOME": str(forge.parents[1] / ".gradle-user-home"),
                }
            )
            try:
                completed = subprocess.run(
                    gradle_command(forge),
                    cwd=forge,
                    env=java_environment,
                    stdin=subprocess.DEVNULL,
                    stdout=subprocess.PIPE,
                    stderr=subprocess.STDOUT,
                    text=True,
                    timeout=TIMEOUT_SECONDS,
                    check=False,
                )
            except subprocess.TimeoutExpired as error:
                raise VerificationError("JAVA_TEST_TIMEOUT") from error
            except OSError as error:
                raise PrerequisiteError("GRADLE_START_UNAVAILABLE") from error
            if type(completed.returncode) is not int:
                raise VerificationError("JAVA_TEST_EXIT_INVALID")
            if completed.returncode != 0:
                raise VerificationError(
                    "JAVA_TEST_FAILED", javaExitCode=completed.returncode
                )
            evidence = parse_junit(junit, started)
            status = read_status(port)
            if (
                status["activeRoles"] != []
                or status["pendingCount"] != 0
                or status["completedSessions"] != 2
                or status["applicationFrames"] != 0
            ):
                raise VerificationError("PYTHON_RUNTIME_CLEANUP_INVALID")
            if source_snapshot(agent, forge) != before:
                raise VerificationError("SOURCE_CHANGED_DURING_RUN")
            cleanup, shutdown_receipt = orderly_cleanup(
                process, port, token, receipt_path, nonce
            )
            result = {
                "status": "PASS",
                "scope": "TEST_V2_LOOPBACK",
                "actuation": "DISABLED",
                "agentHead": head,
                "sourceHashes": before,
                "loadedModuleHashes": status["loadedModuleHashes"],
                "loadedModulePaths": status["loadedModulePaths"],
                "pythonRuntime": status,
                **evidence,
                "cleanup": cleanup,
                "shutdownReceipt": shutdown_receipt,
                "javaExitCode": completed.returncode,
                "pythonExitCode": process.returncode,
            }
        except LoopbackError as error:
            primary_error = error
        except (OSError, ValueError, subprocess.SubprocessError) as error:
            primary_error = VerificationError("HARNESS_EXECUTION_FAILED")
            primary_error.__cause__ = error
        finally:
            if (
                process is not None
                and cleanup != "ORDERLY_APP_SHUTDOWN_AND_PROCESS_EXITED"
            ):
                try:
                    cleanup, shutdown_receipt = orderly_cleanup(
                        process, port, token, receipt_path, nonce
                    )
                except LoopbackError as shutdown_error:
                    cleanup = str(
                        shutdown_error.evidence.get("cleanup")
                        or fallback_cleanup(process)
                    )
                    if primary_error is None:
                        primary_error = shutdown_error
                    else:
                        primary_error.evidence["shutdownFailure"] = shutdown_error.code
                except (OSError, subprocess.SubprocessError):
                    cleanup = fallback_cleanup(process)
                    if primary_error is None:
                        primary_error = VerificationError("ORDERLY_SHUTDOWN_FAILED")
            if log is not None:
                log.close()
        if primary_error is not None:
            primary_error.evidence.update({"cleanup": cleanup})
            if shutdown_receipt is not None:
                primary_error.evidence["shutdownReceipt"] = shutdown_receipt
            raise primary_error
    assert result is not None
    return result


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--agent-server", required=True, type=Path)
    parser.add_argument("--forge", required=True, type=Path)
    parser.add_argument("--python", type=Path, default=Path(sys.executable))
    parser.add_argument("--report", required=True, type=Path)
    args = parser.parse_args(argv)
    try:
        result, code = (
            run(
                args.agent_server.resolve(), args.forge.resolve(), args.python.resolve()
            ),
            0,
        )
    except LoopbackError as error:
        result = {
            "status": error.status,
            "scope": "TEST_V2_LOOPBACK",
            "actuation": "DISABLED",
            "failure": error.code,
            **error.evidence,
        }
        code = 2
    except BaseException:
        result = {
            "status": "FAILED",
            "scope": "TEST_V2_LOOPBACK",
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
