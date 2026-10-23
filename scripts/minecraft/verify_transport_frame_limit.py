"""Exercise Uvicorn's pre-ASGI WebSocket message-size enforcement."""
from __future__ import annotations

import argparse
import asyncio
import base64
import hashlib
import json
import os
import socket
import subprocess
import sys
import time
from pathlib import Path
from urllib.request import ProxyHandler, build_opener


FRAME_LIMIT_BYTES = 4096
STARTUP_TIMEOUT_SECONDS = 15
TOTAL_TIMEOUT_SECONDS = 90
SUBPROTOCOL = "luosandbox.minecraft.v1"
REPORT_DIRECTORY = Path(".gradle-user-home/interop")


class FrameLimitError(RuntimeError):
    """A probe precondition or observation did not meet its acceptance rule."""


def _sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def _server_root(agent_server: Path) -> Path:
    candidate = agent_server / "server"
    return candidate if candidate.is_dir() else agent_server


def source_files(agent_server: Path, tool: Path | None = None) -> dict[str, Path]:
    server = _server_root(agent_server)
    return {
        "harness:scripts/minecraft/verify_transport_frame_limit.py": tool or Path(__file__).resolve(),
        "python:src/adapter/minecraft/transport.py": server / "src/adapter/minecraft/transport.py",
        "python:src/web/minecraft.py": server / "src/web/minecraft.py",
        "python:tests/support/minecraft_loopback_app.py": server / "tests/support/minecraft_loopback_app.py",
    }


def source_snapshot(agent_server: Path, tool: Path | None = None) -> dict[str, str]:
    files = source_files(agent_server, tool)
    if any(not path.is_file() for path in files.values()):
        raise FrameLimitError("reviewed_frame_limit_sources_missing")
    return {name: _sha256(path) for name, path in files.items()}


def _free_port() -> int:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as listener:
        listener.bind(("127.0.0.1", 0))
        return listener.getsockname()[1]


def server_command(python: Path, agent_server: Path, port: int) -> list[str]:
    server = _server_root(agent_server)
    return [
        str(python), "-m", "uvicorn", "support.minecraft_loopback_app:app", "--app-dir", str(server / "tests"),
        "--host", "127.0.0.1", "--port", str(port), "--workers", "1", "--no-proxy-headers",
        "--ws-max-size", str(FRAME_LIMIT_BYTES), "--log-level", "warning",
    ]


def _status(port: int) -> dict[str, object]:
    opener = build_opener(ProxyHandler({}))
    with opener.open(f"http://127.0.0.1:{port}/minecraft/v1/test-status", timeout=0.5) as response:
        value = json.loads(response.read().decode("utf-8"))
    if not isinstance(value, dict):
        raise FrameLimitError("invalid_test_status")
    return value


def _wait_ready(port: int, process: subprocess.Popen[str], deadline: float) -> None:
    while time.monotonic() < deadline:
        if process.poll() is not None:
            raise FrameLimitError("uvicorn_exited_before_ready")
        try:
            status = _status(port)
            if status.get("enabled") is True and status.get("routeRegistered") is True:
                return
        except (OSError, ValueError, json.JSONDecodeError):
            pass
        time.sleep(0.1)
    raise FrameLimitError("uvicorn_startup_timeout")


def _b32(value: bytes) -> str:
    return base64.urlsafe_b64encode(value).decode("ascii").rstrip("=")


def hello(role: str) -> str:
    install = "loopback-server" if role == "SERVER_AUTHORITY" else "loopback-client"
    value = {
        "security": "luosandbox.control-auth.v1",
        "kind": "HELLO",
        "peerRole": role,
        "peerInstallId": install,
        "hubInstallId": "loopback-hub",
        "keyId": f"loopback-{role.lower()}",
        "protocol": SUBPROTOCOL,
        "modHash": _b32(b"m" * 32),
        "configHash": _b32(b"c" * 32),
        "bindingHash": _b32(b"b" * 32),
        "peerNonce": _b32(bytes(range(32))),
    }
    return json.dumps(value, separators=(",", ":"), ensure_ascii=True)


def padded_hello(role: str, size: int) -> str:
    message = hello(role)
    remaining = size - len(message.encode("utf-8"))
    if remaining < 0:
        raise FrameLimitError("valid_hello_exceeds_requested_size")
    return message + " " * remaining


async def _boundary_probe(uri: str) -> None:
    import websockets
    from websockets.exceptions import ConnectionClosed

    payload = padded_hello("SERVER_AUTHORITY", FRAME_LIMIT_BYTES)
    async with websockets.connect(uri, subprotocols=[SUBPROTOCOL], compression=None, open_timeout=5, close_timeout=0.5) as websocket:
        await websocket.send(payload)
        try:
            challenge = json.loads(await asyncio.wait_for(websocket.recv(), timeout=2))
        except ConnectionClosed as error:
            raise FrameLimitError(f"boundary_hello_close_code_{error.code}") from error
        if challenge.get("kind") != "CHALLENGE":
            raise FrameLimitError("boundary_hello_not_accepted")


async def _oversize_probe(uri: str, fragmented: bool) -> int:
    import websockets
    from websockets.exceptions import ConnectionClosed

    payload = padded_hello("CLIENT_CONTROLLER", FRAME_LIMIT_BYTES + 1)
    async with websockets.connect(uri, subprotocols=[SUBPROTOCOL], compression=None, open_timeout=5, close_timeout=0.5) as websocket:
        await websocket.send([payload[:2000], payload[2000:]] if fragmented else payload)
        try:
            await asyncio.wait_for(websocket.recv(), timeout=2)
        except ConnectionClosed as error:
            if error.code == 1009:
                return error.code
            raise FrameLimitError(f"oversize_close_code_{error.code}") from error
        raise FrameLimitError("oversize_message_not_closed")


def _wait_transport_cleanup(port: int, deadline: float) -> None:
    while time.monotonic() < deadline:
        status = _status(port)
        if status.get("activeRoleCount") == 0:
            return
        time.sleep(0.05)
    raise FrameLimitError("boundary_connection_not_released")


def _validated_report_path(report: Path) -> Path:
    root = Path(__file__).resolve().parents[2]
    allowed = (root / REPORT_DIRECTORY).resolve()
    target = report.resolve()
    if target.parent != allowed or target.exists():
        raise FrameLimitError("report_path_not_fresh_trusted_interop_target")
    return target


def _cleanup_owned_process(process: subprocess.Popen[str]) -> str:
    """Stop only the spawned child and report the verified cleanup outcome."""
    if process.poll() is not None:
        return "exited"
    try:
        process.terminate()
        process.wait(timeout=5)
        return "terminated"
    except subprocess.TimeoutExpired:
        try:
            process.kill()
            process.wait(timeout=5)
        except (OSError, subprocess.TimeoutExpired) as error:
            raise FrameLimitError("owned_process_kill_cleanup_failed") from error
        return "killed_after_terminate_timeout"
    except OSError as error:
        raise FrameLimitError("owned_process_terminate_cleanup_failed") from error


def run(agent_server: Path, python: Path) -> dict[str, object]:
    started = time.monotonic()
    deadline = started + TOTAL_TIMEOUT_SECONDS
    before = source_snapshot(agent_server)
    port = _free_port()
    log_directory = Path(__file__).resolve().parents[2] / REPORT_DIRECTORY
    log_directory.mkdir(parents=True, exist_ok=True)
    log = log_directory / f"frame-limit-uvicorn-{time.time_ns()}.log"
    output = log.open("w", encoding="utf-8")
    process = subprocess.Popen(
        server_command(python, agent_server, port), cwd=_server_root(agent_server), stdin=subprocess.DEVNULL,
        stdout=output, stderr=subprocess.STDOUT, text=True, env=os.environ.copy(),
    )
    evidence: dict[str, object] | None = None
    cleanup: str | None = None
    cleanup_failure: FrameLimitError | None = None
    try:
        _wait_ready(port, process, min(deadline, time.monotonic() + STARTUP_TIMEOUT_SECONDS))
        status = _status(port)
        loaded = status.get("loadedModuleHashes")
        expected = {name: digest for name, digest in before.items() if name.startswith("python:")}
        if not isinstance(loaded, dict) or any(loaded.get(name) != digest for name, digest in expected.items()):
            raise FrameLimitError("loaded_source_identity_invalid")
        asyncio.run(_boundary_probe(f"ws://127.0.0.1:{port}/minecraft/v1/ws"))
        _wait_transport_cleanup(port, min(deadline, time.monotonic() + 3))
        unfragmented = asyncio.run(_oversize_probe(f"ws://127.0.0.1:{port}/minecraft/v1/ws", False))
        fragmented = asyncio.run(_oversize_probe(f"ws://127.0.0.1:{port}/minecraft/v1/ws", True))
        if time.monotonic() > deadline:
            raise FrameLimitError("frame_probe_timeout")
        if source_snapshot(agent_server) != before:
            raise FrameLimitError("source_changed_during_run")
        evidence = {
            "status": "PASS",
            "actuation": "DISABLED",
            "limitBytes": FRAME_LIMIT_BYTES,
            "limitEvidence": "VERIFIED_AT_UVICORN_PROTOCOL_LAYER",
            "boundaryHello": {"bytes": FRAME_LIMIT_BYTES, "result": "CHALLENGE"},
            "oversizeText": {"bytes": FRAME_LIMIT_BYTES + 1, "closeCode": unfragmented},
            "fragmentedAggregate": {"bytes": FRAME_LIMIT_BYTES + 1, "closeCode": fragmented},
            "sourceHashes": before,
            "loadedModuleHashes": {name: loaded[name] for name in sorted(expected)},
        }
    finally:
        try:
            cleanup = _cleanup_owned_process(process)
        except FrameLimitError as error:
            cleanup_failure = error
        finally:
            output.close()
        if cleanup_failure is not None and sys.exc_info()[0] is None:
            raise cleanup_failure
    if evidence is None or cleanup is None:
        raise FrameLimitError("frame_probe_evidence_missing")
    evidence["processCleanup"] = cleanup
    evidence["elapsedMs"] = int((time.monotonic() - started) * 1000)
    return evidence


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--agent-server", required=True, type=Path)
    parser.add_argument("--python", default=Path(sys.executable), type=Path)
    parser.add_argument("--report", required=True, type=Path)
    args = parser.parse_args(argv)
    try:
        report = _validated_report_path(args.report)
    except FrameLimitError as error:
        print(json.dumps({"status": "BLOCKED", "actuation": "DISABLED", "failure": str(error)}, sort_keys=True))
        return 2
    try:
        result, exit_code = run(args.agent_server.resolve(), args.python.resolve()), 0
    except (OSError, ValueError, RuntimeError, subprocess.TimeoutExpired) as error:
        result, exit_code = {"status": "BLOCKED", "actuation": "DISABLED", "failure": str(error) or type(error).__name__}, 2
    report.write_text(json.dumps(result, sort_keys=True) + "\n", encoding="utf-8")
    print(json.dumps({"status": result["status"], "actuation": "DISABLED"}, sort_keys=True))
    return exit_code


if __name__ == "__main__":
    raise SystemExit(main())
