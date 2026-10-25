"""Default-dormant dummy loader experiment. Running requires separate native authorization.

Predeclared ancestor mutation SID sets are external test inputs, never inferred or grown.
The result is limited to dummy loader semantics, not production custody or activation.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import secrets
import time
import zipfile
import xml.etree.ElementTree as ET
from pathlib import Path
from urllib.parse import unquote_to_bytes, urlsplit

import verify_v2_connector_loopback as helper

APPROVED = Path(r"C:\Users\Administrator\AppData\Local\Temp\opencode")
CLASS = (
    "io.github.jinyiwei2012.luosandbox.transport.DeploymentCredentialLoaderNativeTest"
)
XML = Path(f"bridge/forge/build/test-results/test/TEST-{CLASS}.xml")
PROFILE = "PROTECTED_AUTO_INHERITED_SNAPSHOT_V1"
LAYOUT = "BOUNDED_SELF_RELATIVE_LAYOUT_V1"
STATUSES = {
    "BLOCKED_PREREQUISITE",
    "BLOCKED_LOADER_REJECTION",
    "DUMMY_LOADER_NATIVE_PASS_LIMITED",
    "FAILED",
}
Error = helper.Error
PREFIX = "io.github.jinyiwei2012.luosandbox.transport."
CLASS_NAMES = ("com.sun.jna.Native", "com.sun.jna.platform.win32.Kernel32") + tuple(
    PREFIX + name
    for name in (
        "DeploymentCredentialLoaderNativeTest",
        "DeploymentCredentialLoaderNativeTest$CreatedTarget",
        "DeploymentCredentialLoaderNativeTest$Trust",
        "DeploymentCredentialLoaderNativeTest$Probe",
        "DeploymentCredentialLoaderNativeTest$Owner",
        "DeploymentCredentialLoaderNativeTest$EvidenceFailure",
        "DeploymentCredentialLoaderTest",
        "WindowsDeploymentMechanismNativeTest",
        "WindowsDeploymentMechanismNativeTest$FixtureSecurity",
        "DeploymentCredentialLoader",
        "DeploymentCredentialLoader$CustodyAttemptReceipt",
        "DeploymentCredentialLoader$CustodyAttemptReceipt$NativeRecord",
        "DeploymentCredentialLoader$HandoffSlot",
        "DeploymentCredentialLoader$Provisioning",
        "DeploymentCredentialLoader$Secret",
        "DeploymentCredentialLoader$TrustedDeploymentPolicy",
        "DeploymentCredentialLoader$CustodyAssessment",
        "WindowsDeploymentCustody",
        "WindowsDeploymentCustody$NativeApi",
        "WindowsDeploymentCustody$Snapshot",
    )
)
JAR_HASHES = (
    "91a814ac4f40d60dee91d842e1a8ad874c62197984403d0e3c30d39e55cf53b3",
    "8ce969116cac95bd61b07a8d5e07174b352e63301473caac72c395e3c08488d2",
)


def read(path, limit=2_097_152):
    helper.safe_path(path)
    if not path.is_file() or path.stat().st_size > limit:
        raise Error("ARTIFACT_LIMIT")
    with path.open("rb") as stream:
        value = stream.read(limit + 1)
    if len(value) > limit:
        raise Error("ARTIFACT_LIMIT")
    return value


def sid_set(text):
    if type(text) is not str or not text:
        raise Error("PREDECLARED_SIDS_REQUIRED")
    values = text.split(",")
    if not 1 <= len(values) <= 16 or len(values) != len(set(values)):
        raise Error("SID_SET_INVALID")
    for value in values:
        if not re.fullmatch(r"(?:[0-9a-f]{2}){12,68}", value):
            raise Error("SID_ENCODING_INVALID")
        raw = bytes.fromhex(value)
        if raw[0] != 1 or raw[1] > 15 or len(raw) != 8 + raw[1] * 4:
            raise Error("SID_ENCODING_INVALID")
    return text


def sources(root):
    package = root / "bridge/forge/src"
    files = [
        root / "scripts/minecraft/verify_java_loader_native.py",
        root / "scripts/tests/test_java_loader_native.py",
        root / "scripts/minecraft/verify_v2_connector_loopback.py",
        root / "scripts/minecraft/verify_v2_transport_loopback.py",
        package
        / "test/java/io/github/jinyiwei2012/luosandbox/transport/DeploymentCredentialLoaderNativeTest.java",
        package
        / "test/java/io/github/jinyiwei2012/luosandbox/transport/DeploymentCredentialLoaderTest.java",
        package
        / "test/java/io/github/jinyiwei2012/luosandbox/transport/WindowsDeploymentMechanismNativeTest.java",
    ]
    files += [
        package / f"main/java/io/github/jinyiwei2012/luosandbox/{name}"
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
        str(p.relative_to(root)): hashlib.sha256(read(p)).hexdigest() for p in files
    }


def gates(root, output, head, content, security, namespace):
    trust = tuple(sid_set(v) for v in (content, security, namespace))
    helper.safe_path(root)
    helper.safe_path(output, exists=False)
    if (
        output.exists()
        or output == APPROVED
        or not output.is_relative_to(APPROVED)
        or not output.parent.is_dir()
    ):
        raise Error("EXCLUSIVE_APPROVED_OUTPUT_REQUIRED")
    if (
        os.name != "nt"
        or not re.fullmatch(r"[0-9a-f]{40}", head)
        or helper.clean(root) != head
    ):
        raise Error("EXPECTED_COMMITTED_CLEAN_HEAD_REQUIRED")
    return trust, sources(root)


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


def validate_xml(path, started, nonce, allow_failed=False, archived=None):
    raw = read(path, 1_048_576) if archived is None else archived
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
        or len(cases) != 1
        or cases[0].get("name") != "dummyFiveFileLoader()"
    ):
        raise Error("EXACT_SINGLE_CASE_REQUIRED")
    if not allow_failed and any(
        suite.get(k) != v
        for k, v in {
            "tests": "1",
            "failures": "0",
            "errors": "0",
            "skipped": "0",
        }.items()
    ):
        raise Error("NATIVE_CASE_NOT_CLEAN")
    lines = [
        line
        for n in suite.findall("system-out")
        for line in (n.text or "").splitlines()
        if line.startswith("JAVA_LOADER_RESULT ")
    ]
    if len(lines) != 1:
        raise Error("RESULT_IDENTITY_INVALID")
    parts = lines[0].split(" ", 3)
    expected = f"profile={PROFILE};layout={LAYOUT};scope=TEST_ONLY_DUMMY"
    if (
        len(parts) != 4
        or parts[1] != nonce
        or parts[2] not in STATUSES
        or parts[3] != expected
    ):
        raise Error("RESULT_IDENTITY_INVALID")
    return raw, parts[2]


def exclusive(path, value):
    helper.safe_path(path, exists=False)
    temporary = path.with_name("." + path.name + "-" + secrets.token_hex(8))
    try:
        with temporary.open("xb") as stream:
            if stream.write(value) != len(value):
                raise OSError("short evidence write")
            stream.flush()
            os.fsync(stream.fileno())
        os.link(temporary, path)
    finally:
        if temporary.exists():
            try:
                temporary.unlink()
            except OSError:
                pass


def artifact(path, value, result):
    try:
        exclusive(path, value)
    except OSError:
        result["evidenceFailure"] = "EVIDENCE_WRITE_FAILED"
        raise


def local_file_resource(value):
    """Decode only absolute, authority-free Windows file URLs, not pathname aliases."""
    if type(value) is not str or any(ord(c) < 32 or c in "\\?#" for c in value):
        raise Error("CLASS_RESOURCE_URL_INVALID")
    if re.search(r"%(?![0-9A-Fa-f]{2})", value) or re.search(
        r"%(?:2f|5c|3a|00)", value, re.IGNORECASE
    ):
        raise Error("CLASS_RESOURCE_URL_INVALID")
    try:
        parsed = urlsplit(value)
        if parsed.scheme != "file" or parsed.netloc or parsed.query or parsed.fragment:
            raise ValueError
        decoded = unquote_to_bytes(parsed.path).decode("utf-8", "strict")
        if not re.fullmatch(r"/[A-Za-z]:/[^\\\x00-\x1f]+", decoded):
            raise ValueError
        parts = decoded[4:].split("/")
        if any(
            not p
            or p in {".", ".."}
            or p.endswith((".", " "))
            or any(c in '<>:"|?*' for c in p)
            for p in parts
        ):
            raise ValueError
        return Path(decoded[1:])
    except (ValueError, UnicodeError):
        raise Error("CLASS_RESOURCE_URL_INVALID") from None


def match_resource(value, expected_path, entry=None):
    if entry is None:
        actual = local_file_resource(value)
    else:
        if (
            type(value) is not str
            or not value.startswith("jar:")
            or value.count("!/") != 1
        ):
            raise Error("CLASS_RESOURCE_URL_INVALID")
        inner, actual_entry = value[4:].split("!/", 1)
        if actual_entry != entry:
            raise Error("CLASS_RESOURCE_PROVENANCE_MISMATCH")
        actual = local_file_resource(inner)
    if os.path.normcase(str(actual)) != os.path.normcase(str(expected_path)):
        raise Error("CLASS_RESOURCE_PROVENANCE_MISMATCH")


def validate_identity(output, nonce, root):
    properties = {}
    for line in (
        read(output / "loader-runtime.properties", 65_536)
        .decode("latin-1")
        .splitlines()
    ):
        if not line or line.startswith("#"):
            continue
        key, separator, value = line.partition("=")
        if not separator or key in properties:
            raise Error("RUNTIME_IDENTITY_INVALID")
        properties[key] = (
            value.replace("\\:", ":").replace("\\=", "=").replace("\\\\", "\\")
        )
    if (
        properties.get("nonce") != nonce
        or properties.get("profile") != PROFILE
        or properties.get("layout") != LAYOUT
    ):
        raise Error("RUNTIME_IDENTITY_SELECTION_MISMATCH")
    for key in (
        "java.vendor",
        "java.runtime.version",
        "os.name",
        "os.version",
        "os.arch",
        "sun.arch.data.model",
        "pointerBytes",
        "structureSize",
    ):
        if not properties.get(key):
            raise Error("RUNTIME_IDENTITY_MISSING")
    if properties.get("classCount") != str(len(CLASS_NAMES)):
        raise Error("CLASS_LIST_MISMATCH")
    allowed = {
        "nonce",
        "profile",
        "layout",
        "java.vendor",
        "java.runtime.version",
        "os.name",
        "os.version",
        "os.arch",
        "sun.arch.data.model",
        "pointerBytes",
        "structureSize",
        "classCount",
    }
    allowed |= {
        f"{field}.{i}"
        for i in range(len(CLASS_NAMES))
        for field in ("className", "resource", "classSha")
    }
    allowed |= {
        f"{field}.{i}"
        for i in range(2)
        for field in ("jarPath", "jarSize", "jarSha", "jarUse")
    }
    if set(properties) != allowed:
        raise Error("CLASS_FIELDS_MISMATCH")
    jars = []
    for i, expected in enumerate(JAR_HASHES):
        jar = Path(properties.get(f"jarPath.{i}", ""))
        raw_jar = read(jar, 16_777_216)
        if (
            properties.get(f"jarSha.{i}") != expected
            or hashlib.sha256(raw_jar).hexdigest() != expected
        ):
            raise Error("JNA_IDENTITY_MISMATCH")
        if properties[f"jarSize.{i}"] != str(len(raw_jar)) or properties[
            f"jarUse.{i}"
        ] != (
            "USED_CORE" if i == 0 else "AVAILABLE_RESOURCE_NOT_NATIVE_PLATFORM_BINDING"
        ):
            raise Error("JNA_USE_OR_SIZE_MISMATCH")
        jars.append(jar)
    for i, name in enumerate(CLASS_NAMES):
        relative = name.replace(".", "/") + ".class"
        raw_class = read(output / f"loader-class-{i}.bin", 1_048_576)
        if properties[f"className.{i}"] != name or hashlib.sha256(
            raw_class
        ).hexdigest() != properties.get(f"classSha.{i}"):
            raise Error("EXECUTED_CLASS_MISMATCH")
        if i < 2:
            with zipfile.ZipFile(jars[i]) as archive:
                entry = archive.getinfo(relative)
                if entry.file_size > 1_048_576 or archive.read(relative) != raw_class:
                    raise Error("JNA_CLASS_ENTRY_MISMATCH")
            match_resource(properties[f"resource.{i}"], jars[i], relative)
        else:
            tree = (
                "test"
                if name.split(".")[-1].split("$")[0]
                in {
                    "DeploymentCredentialLoaderNativeTest",
                    "DeploymentCredentialLoaderTest",
                    "WindowsDeploymentMechanismNativeTest",
                }
                else "main"
            )
            actual = root / f"bridge/forge/build/classes/java/{tree}" / relative
            if read(actual, 1_048_576) != raw_class:
                raise Error("BUILD_CLASS_MISMATCH")
            match_resource(properties[f"resource.{i}"], actual)
    return properties


def observe_xml(root, output, started, nonce, prior, result):
    path = root / XML
    if not path.exists() or path.stat().st_mtime_ns < started:
        raise Error("STALE_OR_MISSING_XML")
    raw = read(path, 1_048_576)
    if prior is not None and prior == (
        hashlib.sha256(raw).hexdigest(),
        path.stat().st_mtime_ns,
    ):
        raise Error("UNCHANGED_XML")
    try:
        exclusive(output / "fresh-junit.xml", raw)
    except OSError:
        result["evidenceFailure"] = "EVIDENCE_WRITE_FAILED"
        raise
    result["xmlProvenance"] = "FRESH_XML_OBSERVATION_UNATTRIBUTED"
    _, status = validate_xml(
        path, started, nonce, allow_failed="failure" in result, archived=raw
    )
    result["xmlProvenance"] = "NONCE_VALIDATED_RESULT"
    return raw, status


def run(root, output, head, content, security, namespace):
    trust, before = gates(root, output, head, content, security, namespace)
    output.mkdir()
    nonce = secrets.token_hex(16)
    result = {
        "status": "FAILED",
        "scope": "TEST_ONLY_DUMMY",
        "javaCustody": "NOT_VALIDATED",
        "dedicatedForge": "NOT_ESTABLISHED",
        "processLaunched": False,
        "nativePhaseEntered": False,
        "forceKilled": False,
    }
    process = None
    started = None
    prior = None
    try:
        old = root / XML
        if old.exists():
            raw = read(old, 1_048_576)
            prior = (hashlib.sha256(raw).hexdigest(), old.stat().st_mtime_ns)
            artifact(
                output / "prior-xml.json",
                json.dumps(
                    {
                        "sha256": hashlib.sha256(raw).hexdigest(),
                        "mtimeNs": old.stat().st_mtime_ns,
                    }
                ).encode(),
                result,
            )
        env = os.environ.copy()
        # Only this experiment is opted in, regardless of inherited developer settings.
        for name in list(env):
            if name.startswith("RUN_") and ("NATIVE" in name or "LOOPBACK" in name):
                del env[name]
        env.update(
            {
                "RUN_JAVA_LOADER_NATIVE": "1",
                "JAVA_LOADER_NONCE": nonce,
                "JAVA_LOADER_OUTPUT": str(output),
                "JAVA_LOADER_CONTENT_SIDS": trust[0],
                "JAVA_LOADER_SECURITY_SIDS": trust[1],
                "JAVA_LOADER_NAMESPACE_SIDS": trust[2],
                "GRADLE_USER_HOME": str(root / ".gradle-user-home"),
                "LUOSANDBOX_PYTHON_INTEROP_DIR": str(
                    root / ".gradle-user-home/interop/python-to-java-v1"
                ),
            }
        )
        started = time.time_ns()
        process = helper.LoggedProcess(
            command(root), root / "bridge/forge", env, output / "gradle.log"
        )
        result.update(processLaunched=True, ownedPid=process.pid)
        exit_code = process.wait(90)
        result["javaExit"] = exit_code
        if exit_code != 0:
            result["failure"] = "JAVA_PROCESS_FAILED"
    except (helper.helper.LoopbackError, OSError, ValueError, ET.ParseError) as failure:
        code = getattr(failure, "code", "EXECUTION_OR_EVIDENCE_FAILED")
        if "failure" in result:
            result["secondaryFailure"] = code
        else:
            result["failure"] = code
        if getattr(failure, "native_cleanup", None) is not None:
            result["nativeCleanup"] = failure.native_cleanup
            result["forceKilled"] = failure.native_cleanup.get("forceKilled", True)
    finally:
        if process is not None:
            try:
                process.finish()
            except BaseException:
                result.update(status="FAILED", cleanup="UNKNOWN")
            result["ownedTreeCleanup"] = process.tree.snapshot()
            result["forceKilled"] = process.tree.force_killed
            if (
                process.tree.force_killed
                or process.tree.failure
                or process.reader.failure
            ):
                result["status"] = "FAILED"
        if process is not None and started is not None:
            try:
                raw, status = observe_xml(root, output, started, nonce, prior, result)
                attempted = (
                    f"JAVA_LOADER_NATIVE_PHASE_ATTEMPTED {nonce}".encode() in raw
                )
                initialized = f"JAVA_LOADER_API_INITIALIZED {nonce}".encode() in raw
                result.update(
                    nativePhaseAttempted=attempted, apiInitialized=initialized
                )
                identity = validate_identity(output, nonce, root) if attempted else None
                if status == "DUMMY_LOADER_NATIVE_PASS_LIMITED" and (
                    not attempted or not initialized or identity is None
                ):
                    raise Error("REQUIRED_NATIVE_IDENTITY_MISSING")
                if b"RETAINED_NOT_DELETED" in raw:
                    status = "FAILED"
                    result["fixtureCleanup"] = "RETAINED_NOT_DELETED"
                if (
                    "failure" not in result
                    and not result.get("cleanup")
                    and not result["forceKilled"]
                    and not result.get("evidenceFailure")
                ):
                    result.update(
                        status=status,
                        nonce=nonce,
                        sourceHashes=before,
                        runtimeIdentity=identity,
                    )
            except (
                helper.helper.LoopbackError,
                OSError,
                ValueError,
                ET.ParseError,
                zipfile.BadZipFile,
                KeyError,
            ) as failure:
                code = getattr(failure, "code", "XML_OR_IDENTITY_INVALID")
                if "failure" in result:
                    result["secondaryFailure"] = code
                else:
                    result["failure"] = code
                result["status"] = "FAILED"
        try:
            if sources(root) != before or helper.clean(root) != head:
                result.update(status="FAILED", sourceIntegrity="DRIFT")
                result.setdefault("failure", "SOURCE_OR_HEAD_DRIFT")
        except (helper.helper.LoopbackError, OSError):
            result.update(status="FAILED", sourceIntegrity="UNKNOWN")
        try:
            rows = [
                f"{hashlib.sha256(read(p)).hexdigest()}  {p.name}"
                for p in sorted(output.iterdir())
                if p.is_file()
            ]
            artifact(
                output / "artifacts.sha256", ("\n".join(rows) + "\n").encode(), result
            )
        except (OSError, helper.helper.LoopbackError):
            result.update(status="FAILED", evidenceFailure="EVIDENCE_WRITE_FAILED")
        try:
            artifact(
                output / "report.json",
                json.dumps(result, sort_keys=True).encode(),
                result,
            )
        except (OSError, helper.helper.LoopbackError):
            result.update(status="FAILED", evidenceFailure="EVIDENCE_WRITE_FAILED")
    return result


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("sandbox", "output"):
        parser.add_argument("--" + name, type=Path, required=True)
    for name in ("expected-head", "content-sids", "security-sids", "namespace-sids"):
        parser.add_argument("--" + name, required=True)
    args = parser.parse_args(argv)
    try:
        result = run(
            args.sandbox,
            args.output,
            args.expected_head,
            args.content_sids,
            args.security_sids,
            args.namespace_sids,
        )
    except (helper.helper.LoopbackError, OSError) as failure:
        result = {
            "status": "BLOCKED_PREREQUISITE",
            "failure": getattr(failure, "code", "OUTPUT_INVALID"),
        }
    print(json.dumps(result, sort_keys=True))
    return 0 if result["status"] == "DUMMY_LOADER_NATIVE_PASS_LIMITED" else 2


if __name__ == "__main__":
    raise SystemExit(main())
