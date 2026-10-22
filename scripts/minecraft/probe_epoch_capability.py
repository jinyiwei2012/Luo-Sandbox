"""Read-only epoch capability evidence with optional isolated dummy checks.

The report never activates the epoch store.  Dummy checks operate only in an
explicit empty scratch directory and do not prove power-loss durability.
"""
from __future__ import annotations

import argparse
import hashlib
import importlib
import importlib.util
import json
import os
import platform
import sqlite3
import subprocess
import sys
import types
from pathlib import Path
from typing import Any


MAX_REPORT_BYTES = 65536
CHILD_TIMEOUT_SECONDS = 5


def _sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def _path_chain_safe(path: Path) -> bool:
    current = path
    while True:
        if not _path_entry_safe(current):
            return False
        if current.parent == current:
            return True
        current = current.parent


def _is_reparse_point(path: Path) -> bool:
    try:
        attributes = getattr(path.lstat(), "st_file_attributes", 0)
    except OSError:
        return True
    return bool(attributes & 0x400)


def _path_entry_safe(path: Path) -> bool:
    try:
        return not path.is_symlink() and not _is_reparse_point(path)
    except OSError:
        return False


def _validate_existing_directory(path: Path) -> Path:
    if not path.exists() or not path.is_dir() or not _path_chain_safe(path):
        raise ValueError("existing_directory_required")
    return path


def _load_upstream(server: Path) -> tuple[Any, Any, dict[str, str]]:
    root = _validate_existing_directory(server)
    platform_path = root / "src" / "adapter" / "minecraft" / "epoch_platform.py"
    acl_path = root / "src" / "adapter" / "minecraft" / "epoch_acl.py"
    if not platform_path.is_file() or not acl_path.is_file():
        raise ValueError("upstream_modules_missing")
    saved_modules = {name: module for name, module in sys.modules.items() if name == "src" or name.startswith("src.")}
    try:
        for name in list(saved_modules):
            sys.modules.pop(name, None)
        _package("src", root / "src")
        _package("src.adapter", root / "src" / "adapter")
        _package("src.adapter.minecraft", root / "src" / "adapter" / "minecraft")
        _load_exact("src.adapter.minecraft.epoch_store", root / "src" / "adapter" / "minecraft" / "epoch_store.py")
        epoch_platform = _load_exact("src.adapter.minecraft.epoch_platform", platform_path)
        epoch_acl = _load_exact("src.adapter.minecraft.epoch_acl", acl_path)
        if Path(epoch_platform.__file__).absolute() != platform_path.absolute() or Path(epoch_acl.__file__).absolute() != acl_path.absolute():
            raise ValueError("upstream_module_identity_mismatch")
        return epoch_platform, epoch_acl, {
            "epochPlatformSourceSha256": _sha256(platform_path),
            "epochAclSourceSha256": _sha256(acl_path),
        }
    finally:
        for name in list(sys.modules):
            if name == "src" or name.startswith("src."):
                sys.modules.pop(name, None)
        sys.modules.update(saved_modules)


def _package(name: str, path: Path) -> None:
    module = types.ModuleType(name)
    module.__path__ = [str(path)]
    sys.modules[name] = module


def _load_exact(name: str, path: Path) -> Any:
    spec = importlib.util.spec_from_file_location(name, path)
    if spec is None or spec.loader is None:
        raise ValueError("upstream_module_unloadable")
    module = importlib.util.module_from_spec(spec)
    sys.modules[name] = module
    spec.loader.exec_module(module)
    return module


def _path_facts(path: Path) -> dict[str, bool]:
    return {"exists": path.exists(), "isDirectory": path.is_dir(), "isSymlink": path.is_symlink(),
            "reparsePoint": _is_reparse_point(path)}


def _platform_checks(epoch_platform: Any, epoch_acl: Any, directory: Path) -> dict[str, Any]:
    result: dict[str, Any] = {"productionCapability": {"verified": False, "reason": "unavailable"},
                              "volumeFacts": "UNKNOWN", "aclAssessment": "UNKNOWN"}
    try:
        capability = epoch_platform.WindowsEpochPlatform().capability(directory)
        result["productionCapability"] = {"verified": bool(capability.verified), "reason": capability.reason}
        facts = epoch_platform.WindowsEpochPlatform().volume_facts(directory)
        result["volumeFacts"] = {"local": facts.local, "persistentAcls": facts.persistent_acls,
                                 "reparsePoint": facts.reparse_point, "fixedDriveQualifier": "UNKNOWN"}
        assessment = epoch_acl.WindowsAclVerifier().assess_path(directory)
        result["aclAssessment"] = {"verified": assessment.verified, "reason": assessment.reason}
    except Exception:
        result["diagnostic"] = "NATIVE_PROBE_UNAVAILABLE"
    return result


def _validate_scratch(scratch: Path, target: Path, server: Path) -> Path:
    scratch = _validate_existing_directory(scratch)
    if any(scratch.iterdir()) or _related(scratch, target) or _related(scratch, server):
        raise ValueError("scratch_not_isolated_empty_directory")
    return scratch


def _related(first: Path, second: Path) -> bool:
    return first == second or first in second.parents or second in first.parents


def _dummy_sqlite(scratch: Path) -> dict[str, Any]:
    database = scratch / "dummy.sqlite3"
    connection = sqlite3.connect(database, isolation_level=None, timeout=0)
    try:
        delete = connection.execute("PRAGMA journal_mode=DELETE").fetchone() == ("delete",)
        connection.execute("PRAGMA synchronous=EXTRA")
        extra = connection.execute("PRAGMA synchronous").fetchone() == (3,)
        connection.execute("CREATE TABLE dummy_epoch (value INTEGER NOT NULL)")
        connection.execute("BEGIN IMMEDIATE")
        connection.execute("INSERT INTO dummy_epoch VALUES (1)")
        connection.execute("COMMIT")
    finally:
        connection.close()
    reopened = sqlite3.connect(database, timeout=0)
    try:
        commit_reopen = reopened.execute("SELECT value FROM dummy_epoch").fetchall() == [(1,)]
    finally:
        reopened.close()
    return {"deleteJournalReadback": delete, "extraSynchronousReadback": extra,
            "commitReopen": commit_reopen, "powerLossDurability": "UNKNOWN"}


def _dummy_lock(scratch: Path, server: Path, epoch_platform: Any) -> dict[str, str]:
    if os.name != "nt":
        return {"secondOpen": "UNKNOWN", "reacquire": "UNKNOWN", "diagnostic": "WINDOWS_ONLY"}
    lock = scratch / "dummy.lock"
    lock.write_bytes(b"dummy")
    parent = epoch_platform.WindowsLockHandle.acquire(lock)
    child_code = (
        "import json,sys; from pathlib import Path; "
        "from src.adapter.minecraft.epoch_platform import WindowsLockHandle; "
        "from src.adapter.minecraft.epoch_store import EpochStoreError; "
        "\ntry:\n h=WindowsLockHandle.acquire(Path(sys.argv[1])); h.close(); print(json.dumps({'result':'ALLOWED'})); sys.exit(0)"
        "\nexcept EpochStoreError as error:\n print(json.dumps({'result':'SHARING' if 'already held' in str(error) else 'API_ERROR'})); sys.exit(3 if 'already held' in str(error) else 4)"
        "\nexcept Exception:\n print(json.dumps({'result':'API_ERROR'})); sys.exit(4)"
    )
    env = {key: os.environ[key] for key in ("SystemRoot", "PATH", "TEMP", "TMP") if key in os.environ}
    try:
        child = subprocess.run([sys.executable, "-c", child_code, str(lock)], cwd=str(server), env=env,
                               capture_output=True, timeout=CHILD_TIMEOUT_SECONDS, check=False)
        try:
            child_result = json.loads(child.stdout.decode("utf-8"))
        except (UnicodeDecodeError, json.JSONDecodeError):
            child_result = {"result": "INVALID"}
        second = "REJECTED" if child.returncode == 3 and child_result == {"result": "SHARING"} else "UNKNOWN"
    except (OSError, subprocess.TimeoutExpired):
        second = "UNKNOWN"
    finally:
        parent.close()
    try:
        reopened = epoch_platform.WindowsLockHandle.acquire(lock)
        reopened.close()
        reacquire = "REACQUIRED"
    except Exception:
        reacquire = "UNKNOWN"
    return {"secondOpen": second, "reacquire": reacquire}


def probe(server: Path, directory: Path, dummy_checks: bool, scratch: Path | None) -> dict[str, Any]:
    target = _validate_existing_directory(directory)
    try:
        epoch_platform, epoch_acl, hashes = _load_upstream(server)
    except Exception as error:
        return {"status": "BLOCKED", "diagnostic": "TOOL_MODULE_LOAD_FAILED", "errorType": type(error).__name__}
    report: dict[str, Any] = {
        "status": "UNAVAILABLE", "runtime": {"python": platform.python_version(), "sqlite": sqlite3.sqlite_version,
        "os": platform.system(), "sysPlatform": sys.platform}, "target": _path_facts(target), "checks": hashes |
        _platform_checks(epoch_platform, epoch_acl, target),
        "unknown": ["hardware_durability", "vfs_flush", "production_lock_lifetime", "production_activation"],
    }
    if dummy_checks:
        if scratch is None:
            raise ValueError("scratch_required")
        owned = _validate_scratch(scratch, target, _validate_existing_directory(server))
        report["dummyChecks"] = {"sqlite": _dummy_sqlite(owned), "lock": _dummy_lock(owned, server, epoch_platform)}
    return report


def _validate_report(report: Path, target: Path, server: Path, scratch: Path | None) -> Path:
    original = report.absolute()
    if report.exists() or not report.parent.is_dir() or not _path_chain_safe(report.parent):
        raise ValueError("report_must_be_new_under_existing_safe_parent")
    if _related(original, target) or _related(original, server) or scratch is not None and _related(original, scratch):
        raise ValueError("report_location_conflict")
    return original


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--agent-server", required=True, type=Path)
    parser.add_argument("--directory", required=True, type=Path)
    parser.add_argument("--dummy-checks", action="store_true")
    parser.add_argument("--scratch-directory", type=Path)
    parser.add_argument("--report", type=Path)
    args = parser.parse_args(argv)
    try:
        target = _validate_existing_directory(args.directory)
        server = _validate_existing_directory(args.agent_server)
        scratch = args.scratch_directory.absolute() if args.scratch_directory else None
        if args.report:
            _validate_report(args.report, target, server, scratch)
        result = probe(server, target, args.dummy_checks, scratch)
    except (OSError, ValueError) as error:
        result = {"status": "BLOCKED", "diagnostic": "INVALID_OR_UNAVAILABLE_INPUT", "errorType": type(error).__name__}
    encoded = json.dumps(result, sort_keys=True)
    if len(encoded.encode("utf-8")) > MAX_REPORT_BYTES:
        result, encoded = {"status": "BLOCKED", "diagnostic": "REPORT_BUDGET_EXCEEDED"}, ""
        encoded = json.dumps(result, sort_keys=True)
    if args.report and result["status"] != "BLOCKED":
        with args.report.open("x", encoding="utf-8") as output:
            output.write(encoded + "\n")
    print(encoded)
    return 0 if result["status"] == "UNAVAILABLE" else 2


if __name__ == "__main__":
    raise SystemExit(main())
