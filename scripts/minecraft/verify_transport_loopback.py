"""Verify opt-in Java/Python loopback evidence without enabling actuation."""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import socket
import subprocess
import sys
import time
import xml.etree.ElementTree as element_tree
from pathlib import Path
from urllib.request import ProxyHandler, build_opener


TIMEOUT_SECONDS = 90
MAX_JUNIT_BYTES = 1024 * 1024
REQUIRED_ROLES = {"SERVER_AUTHORITY", "CLIENT_CONTROLLER"}
REQUIRED_DURATION_MS = 2300
JUNIT_RELATIVE = Path("build/test-results/test/TEST-io.github.jinyiwei2012.luosandbox.transport.ControlTransportLoopbackTest.xml")
STARTUP_TIMEOUT_SECONDS = 15
MAX_STARTUP_LOG_LINES = 8


class StartupError(RuntimeError):
    def __init__(self, code: str, diagnostic: str):
        super().__init__(code)
        self.diagnostic = diagnostic


class LoopbackError(RuntimeError):
    def __init__(self, code: str, diagnostic: dict[str, object]):
        super().__init__(code)
        self.diagnostic = diagnostic


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def server_root(agent_server: Path) -> Path:
    """Return the server package from the isolated upstream worktree."""
    candidate = agent_server / "server"
    return candidate if candidate.is_dir() else agent_server


def source_files(agent_server: Path, forge: Path, harness: Path | None = None) -> dict[str, Path]:
    server = server_root(agent_server)
    return {
        "harness:scripts/minecraft/verify_transport_loopback.py": harness or Path(__file__).resolve(),
        "python:src/adapter/minecraft/transport.py": server / "src/adapter/minecraft/transport.py",
        "python:src/adapter/minecraft/control_auth.py": server / "src/adapter/minecraft/control_auth.py",
        "python:src/adapter/minecraft/current_roles.py": server / "src/adapter/minecraft/current_roles.py",
        "python:src/web/minecraft.py": server / "src/web/minecraft.py",
        "python:tests/support/minecraft_loopback_app.py": server / "tests/support/minecraft_loopback_app.py",
        "java:src/main/java/io/github/jinyiwei2012/luosandbox/transport/ControlWebSocketTransport.java": forge / "src/main/java/io/github/jinyiwei2012/luosandbox/transport/ControlWebSocketTransport.java",
        "java:src/main/java/io/github/jinyiwei2012/luosandbox/transport/ControlTransportConfig.java": forge / "src/main/java/io/github/jinyiwei2012/luosandbox/transport/ControlTransportConfig.java",
        "java:src/main/java/io/github/jinyiwei2012/luosandbox/security/ControlAuth.java": forge / "src/main/java/io/github/jinyiwei2012/luosandbox/security/ControlAuth.java",
        "java:src/test/java/io/github/jinyiwei2012/luosandbox/transport/ControlTransportLoopbackTest.java": forge / "src/test/java/io/github/jinyiwei2012/luosandbox/transport/ControlTransportLoopbackTest.java",
    }


def source_snapshot(agent_server: Path, forge: Path, harness: Path | None = None) -> dict[str, str]:
    files = source_files(agent_server, forge, harness)
    if len(files) != len(set(files)) or any(not path.is_file() for path in files.values()):
        raise ValueError("reviewed_loopback_sources_missing")
    return {identifier: sha256(path) for identifier, path in files.items()}


def validate_agent_server(path: Path) -> None:
    if not path.is_dir() or not server_root(path).is_dir():
        raise ValueError("agent_server_missing")


def free_port() -> int:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as listener:
        listener.bind(("127.0.0.1", 0))
        return listener.getsockname()[1]


def wait_ready(port: int, process: subprocess.Popen[str], log_path: Path, timeout: float = STARTUP_TIMEOUT_SECONDS) -> None:
    opener = build_opener(ProxyHandler({}))
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if process.poll() is not None:
            raise StartupError("python_loopback_exited", _startup_diagnostic(log_path))
        try:
            with opener.open(f"http://127.0.0.1:{port}/minecraft/v1/test-status", timeout=0.2) as response:
                status = json.loads(response.read().decode("utf-8"))
                if status.get("enabled") and status.get("routeRegistered"):
                    return
        except (OSError, ValueError):
            pass
        time.sleep(0.1)
    raise StartupError("python_loopback_timeout", "health_endpoint_not_ready_within_15s")


def read_status(port: int) -> dict[str, object]:
    opener = build_opener(ProxyHandler({}))
    with opener.open(f"http://127.0.0.1:{port}/minecraft/v1/test-status", timeout=0.5) as response:
        value = json.loads(response.read().decode("utf-8"))
    if not isinstance(value, dict):
        raise ValueError("invalid_loopback_status")
    return value


def loaded_module_identity(port: int, expected: dict[str, str], files: dict[str, Path]) -> dict[str, object]:
    """Read test-only source identity evidence without exposing protocol material."""
    status = read_status(port)
    hashes = status.get("loadedModuleHashes")
    paths = status.get("loadedModulePaths")
    python_expected = {name: digest for name, digest in expected.items() if name.startswith("python:")}
    expected_paths = {name: str(files[name].resolve()) for name in python_expected}
    if (
        not isinstance(hashes, dict)
        or not isinstance(paths, dict)
        or set(hashes) != set(python_expected)
        or set(paths) != set(python_expected)
        or any(hashes.get(name) != digest for name, digest in python_expected.items())
        or any(paths.get(name) != path for name, path in expected_paths.items())
    ):
        raise RuntimeError("python_loaded_source_identity_invalid")
    return {
        "hashes": {name: hashes[name] for name in sorted(hashes)},
        "paths": {name: paths[name] for name in sorted(paths)},
    }


def _startup_diagnostic(log_path: Path) -> str:
    if not log_path.is_file():
        return "child_exited_without_captured_log"
    lines = [line.strip() for line in log_path.read_text(encoding="utf-8", errors="replace").splitlines() if line.strip()]
    if not lines:
        return "child_exited_without_log"
    return "startup_log_captured:" + str(min(len(lines), MAX_STARTUP_LOG_LINES))


def tcp_evidence(port: int, process_id: int) -> list[dict[str, str]]:
    command = [
        "powershell",
        "-NoProfile",
        "-NonInteractive",
        "-Command",
        (
            f"Get-NetTCPConnection -ErrorAction SilentlyContinue | Where-Object {{ $_.OwningProcess -eq {process_id} "
            f"-and ($_.LocalPort -eq {port} -or $_.RemotePort -eq {port}) }} | "
            "Select-Object -Property State,LocalAddress,LocalPort,RemoteAddress,RemotePort | ConvertTo-Json -Compress"
        ),
    ]
    completed = subprocess.run(command, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
                               text=True, timeout=5, check=False)
    if completed.returncode != 0 or not completed.stdout.strip():
        return []
    value = json.loads(completed.stdout)
    records = value if isinstance(value, list) else [value]
    return [{key: str(item[key]) for key in ("State", "LocalAddress", "LocalPort", "RemoteAddress", "RemotePort") if key in item}
            for item in records if isinstance(item, dict)]


def server_command(python: Path, agent_server: Path, port: int) -> list[str]:
    server = server_root(agent_server)
    return [str(python), "-m", "uvicorn", "support.minecraft_loopback_app:app", "--app-dir",
            str(server / "tests"), "--host", "127.0.0.1", "--port", str(port), "--workers", "1",
            "--no-proxy-headers", "--ws-max-size", "4096", "--log-level", "warning"]


def gradle_command(forge: Path) -> list[str]:
    executable = forge / ("gradlew.bat" if os.name == "nt" else "gradlew")
    return [str(executable), "--offline", "--no-daemon", "--rerun-tasks", "test", "--tests",
            "*ControlTransportLoopbackTest"]


def parse_junit(path: Path, started_at: float) -> dict[str, object]:
    if not path.is_file() or path.stat().st_size > MAX_JUNIT_BYTES or path.stat().st_mtime < started_at - 2:
        raise RuntimeError("junit_missing_or_stale")
    raw = path.read_bytes()
    if b"<!DOCTYPE" in raw.upper() or b"<!ENTITY" in raw.upper():
        raise RuntimeError("junit_unsafe_xml")
    try:
        suite = element_tree.fromstring(raw)
    except element_tree.ParseError as error:
        raise RuntimeError("junit_invalid_xml") from error
    if suite.tag != "testsuite" or suite.attrib.get("failures") != "0" or suite.attrib.get("errors") != "0" or suite.attrib.get("skipped") != "0":
        raise RuntimeError("junit_not_clean")
    cases = suite.findall("testcase")
    if len(cases) != 2 or any(case.find("failure") is not None or case.find("error") is not None or case.find("skipped") is not None for case in cases):
        raise RuntimeError("junit_cases_invalid")
    metrics: dict[str, dict[str, int]] = {}
    output = "".join(node.text or "" for node in suite.findall("system-out"))
    for line in output.splitlines():
        if not line.startswith("LOOPBACK_METRIC "):
            continue
        try:
            item = json.loads(line.removeprefix("LOOPBACK_METRIC "))
            role = item["role"]
            if role in metrics or type(item["requiredDurationMs"]) is not int or type(item["measuredDurationMs"]) is not int:
                raise ValueError
            metrics[role] = {"requiredDurationMs": item["requiredDurationMs"], "measuredDurationMs": item["measuredDurationMs"]}
        except (KeyError, TypeError, ValueError, json.JSONDecodeError):
            raise RuntimeError("junit_metrics_invalid") from None
    roles = {role for role in REQUIRED_ROLES if any(role in case.attrib.get("name", "") for case in cases)}
    if roles != REQUIRED_ROLES or set(metrics) != REQUIRED_ROLES:
        raise RuntimeError("junit_roles_missing")
    if any(item["requiredDurationMs"] != REQUIRED_DURATION_MS or item["measuredDurationMs"] < REQUIRED_DURATION_MS for item in metrics.values()):
        raise RuntimeError("junit_duration_invalid")
    return {"roles": sorted(roles), "perRoleHeartbeat": metrics, "junitSha256": hashlib.sha256(raw).hexdigest()}


def run(agent_server: Path, forge: Path, python: Path) -> dict[str, object]:
    validate_agent_server(agent_server)
    before = source_snapshot(agent_server, forge)
    files = source_files(agent_server, forge)
    junit = forge / JUNIT_RELATIVE
    if junit.exists():
        junit.unlink()
    port = free_port()
    artifact_dir = forge.parents[1] / ".gradle-user-home" / "interop"
    artifact_dir.mkdir(parents=True, exist_ok=True)
    child_log = artifact_dir / f"loopback-uvicorn-{time.time_ns()}.log"
    child_output = child_log.open("w", encoding="utf-8")
    server = subprocess.Popen(server_command(python, agent_server, port), cwd=server_root(agent_server), stdin=subprocess.DEVNULL,
                              stdout=child_output, stderr=subprocess.STDOUT, text=True,
                              env=os.environ.copy())
    started_at = time.time()
    result: dict[str, object] | None = None
    try:
        wait_ready(port, server, child_log)
        loaded = loaded_module_identity(port, before, files)
        environment = os.environ.copy()
        environment.update({"RUN_MINECRAFT_TRANSPORT_LOOPBACK": "1",
                            "MINECRAFT_LOOPBACK_URL": f"ws://127.0.0.1:{port}/minecraft/v1/ws",
                            "GRADLE_USER_HOME": str(forge.parents[1] / ".gradle-user-home")})
        completed = subprocess.run(gradle_command(forge), cwd=forge, env=environment, stdin=subprocess.DEVNULL,
                                    stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True,
                                    timeout=TIMEOUT_SECONDS, check=False)
        if completed.returncode != 0:
            try:
                status = read_status(port)
            except (OSError, ValueError):
                status = {
                    "diagnostics": "unavailable",
                    "process": "exited" if server.poll() is not None else "alive",
                }
                if server.poll() is not None:
                    status["startupDiagnostic"] = _startup_diagnostic(child_log)
            raise LoopbackError("java_test_failed", {
                "status": status,
                "tcp": tcp_evidence(port, server.pid),
                "sourceHashes": before,
            })
        evidence = parse_junit(junit, started_at)
        after = source_snapshot(agent_server, forge)
        if after != before:
            raise RuntimeError("source_changed_during_run")
        result = {"status": "PASS", "actuation": "DISABLED", "prewireMaxBytes": {"value": 4096, "status": "CONFIGURED"},
                  "sourceHashes": before, "loadedModuleHashes": loaded["hashes"],
                  "loadedModulePaths": loaded["paths"], **evidence,
                  "cleanup": "OWNED_PROCESS_TERMINATION_PENDING"}
        return result
    finally:
        server.terminate()
        try:
            server.wait(timeout=5)
        except subprocess.TimeoutExpired:
            server.kill()
            server.wait(timeout=5)
        child_output.close()
        if result is not None:
            result["cleanup"] = "OWNED_PROCESS_TERMINATED_AND_WAITED"


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--agent-server", required=True, type=Path)
    parser.add_argument("--forge", required=True, type=Path)
    parser.add_argument("--python", default=Path(sys.executable), type=Path)
    parser.add_argument("--report", required=True, type=Path)
    args = parser.parse_args(argv)
    try:
        result = run(args.agent_server.resolve(), args.forge.resolve(), args.python.resolve())
        exit_code = 0
    except StartupError as error:
        result, exit_code = {"status": "BLOCKED", "actuation": "DISABLED", "failure": str(error),
                              "startupDiagnostic": error.diagnostic}, 2
    except LoopbackError as error:
        result, exit_code = {"status": "BLOCKED", "actuation": "DISABLED", "failure": str(error), **error.diagnostic}, 2
    except (OSError, ValueError, RuntimeError, subprocess.TimeoutExpired) as error:
        result, exit_code = {"status": "BLOCKED", "actuation": "DISABLED", "failure": str(error) or type(error).__name__}, 2
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(result, sort_keys=True) + "\n", encoding="utf-8")
    print(json.dumps({"status": result["status"], "actuation": "DISABLED"}, sort_keys=True))
    return exit_code


if __name__ == "__main__":
    raise SystemExit(main())
