"""Test-only actual Java/Python v2 post-auth crypto/codec interop harness."""
from __future__ import annotations

import argparse
import base64
import contextlib
import hashlib
import json
import subprocess
import sys
import types
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parents[2]
UPSTREAM = Path(r"C:\Agent-LuoTianyi\.slim\worktrees\minecraft-observation-adapter")
UPSTREAM_HEAD = "35b4386d46e9df77ddf871fcb5eedf17c4590b18"
SERVER = UPSTREAM / "server"
PY_AUTH = SERVER / "src/adapter/minecraft/control_auth.py"
PY_KEYS = SERVER / "src/adapter/minecraft/postauth_keys.py"
PY_FRAME = SERVER / "src/adapter/minecraft/postauth_frame.py"
PY_KEYS_TEST = SERVER / "tests/unit/adapter/test_minecraft_postauth_keys.py"
PY_FRAME_TEST = SERVER / "tests/unit/adapter/test_minecraft_postauth_frame.py"
JAVA_KEYS = ROOT / "bridge/forge/src/main/java/io/github/jinyiwei2012/luosandbox/security/PostAuthSessionKeys.java"
JAVA_FRAME = ROOT / "bridge/forge/src/main/java/io/github/jinyiwei2012/luosandbox/security/PostAuthFrameCodec.java"
JAVA_KEYS_TEST = ROOT / "bridge/forge/src/test/java/io/github/jinyiwei2012/luosandbox/security/PostAuthSessionKeysTest.java"
JAVA_FRAME_TEST = ROOT / "bridge/forge/src/test/java/io/github/jinyiwei2012/luosandbox/security/PostAuthFrameCodecTest.java"
JAVA_INTEROP = ROOT / "bridge/forge/src/test/java/io/github/jinyiwei2012/luosandbox/security/PostAuthCodecInteropTest.java"
TOOL = ROOT / "scripts/minecraft/verify_postauth_codec_interop.py"
TOOL_TEST = ROOT / "scripts/tests/test_postauth_codec_interop.py"
SPEC = ROOT / "docs/decisions/minecraft-control-postauth-v2-draft.md"
SOURCES = (PY_AUTH, PY_KEYS, PY_FRAME, PY_KEYS_TEST, PY_FRAME_TEST, JAVA_KEYS,
           JAVA_FRAME, JAVA_KEYS_TEST, JAVA_FRAME_TEST, JAVA_INTEROP, TOOL, TOOL_TEST, SPEC)
SCHEMA = "postauth-codec-v2-interop-v1"
JAVA_FQCN = "io.github.jinyiwei2012.luosandbox.security.PostAuthCodecInteropTest"
MAX_RESULT = 2_097_152
MAX_SOURCE_BYTES = 2_097_152
CONNECTION = "20000000-0000-4000-8000-000000000001"
CHALLENGE = "10000000-0000-4000-8000-000000000001"
ROUTE = "30000000-0000-4000-8000-000000000001"
SOURCE = "40000000-0000-4000-8000-000000000001"
BOOT = "50000000-0000-4000-8000-000000000001"
GAME = "60000000-0000-4000-8000-000000000001"
BODY = "70000000-0000-4000-8000-000000000001"


class Blocked(RuntimeError): pass


def _read(path: Path) -> bytes:
    try:
        if path.is_symlink() or not path.is_file(): raise Blocked("source_missing_or_not_regular")
        size = path.stat().st_size
        if size <= 0 or size > MAX_SOURCE_BYTES: raise Blocked("source_oversized_or_empty")
        raw = path.read_bytes()
        if len(raw) != size: raise Blocked("source_changed_during_read")
        return raw
    except OSError as error:
        raise Blocked("source_gate_failed") from error


def _unique(pairs):
    result = {}
    for key, value in pairs:
        if key in result: raise Blocked("duplicate_json_key")
        result[key] = value
    return result


def read_source_snapshot():
    """Read every required source exactly once for one evidence operation."""
    snapshot = {path: _read(path) for path in SOURCES}
    if set(snapshot) != set(SOURCES): raise Blocked("source_snapshot_set_invalid")
    return snapshot


@contextlib.contextmanager
def loaded_python(source_snapshot):
    """Execute frozen snapshot bytes; exclusive single-owner process use only."""
    names = ("src", "src.adapter", "src.adapter.minecraft",
             "src.adapter.minecraft.control_auth", "src.adapter.minecraft.postauth_keys",
             "src.adapter.minecraft.postauth_frame")
    if set(source_snapshot) != set(SOURCES): raise Blocked("source_snapshot_set_invalid")
    sources = {names[3]: (PY_AUTH, source_snapshot[PY_AUTH]),
               names[4]: (PY_KEYS, source_snapshot[PY_KEYS]),
               names[5]: (PY_FRAME, source_snapshot[PY_FRAME])}
    missing = object(); previous = {name: sys.modules.get(name, missing) for name in names}
    try:
        for name in names[:3]:
            module = types.ModuleType(name); module.__path__ = []; module.__package__ = name
            sys.modules[name] = module
        loaded = {}
        for name in names[3:]:
            path, source = sources[name]
            module = types.ModuleType(name); module.__file__ = str(path); module.__package__ = name.rpartition(".")[0]
            sys.modules[name] = module; exec(compile(source, str(path), "exec"), module.__dict__); loaded[name] = module
        yield loaded[names[3]], loaded[names[4]], loaded[names[5]]
    finally:
        for name in reversed(names):
            if previous[name] is missing: sys.modules.pop(name, None)
            else: sys.modules[name] = previous[name]


def _auth(auth, role):
    context = auth.ControlAuthContext(peer_role=role, peer_install_id="server" if role == "SERVER_AUTHORITY" else "client",
        hub_install_id="hub", key_id="key", protocol="LUO_CONTROL_POSTAUTH_V2",
        mod_hash=bytes(range(32, 64)), config_hash=bytes(range(128, 160)), binding_hash=bytes(range(160, 192)))
    issued = datetime(2026, 10, 23, tzinfo=timezone.utc)
    return context, issued


def _transcript(keys, auth, role):
    context, issued = _auth(auth, role)
    return keys.AuthenticatedTranscript(context=context, peer_nonce=bytes(range(64, 96)),
        hub_nonce=bytes(range(96, 128)), challenge_id=CHALLENGE, issued_at=issued,
        expires_at=issued + timedelta(seconds=5))


def _notice():
    nonce = base64.urlsafe_b64encode(bytes(range(16, 48))).decode().rstrip("=")
    return json.dumps({"security":"luosandbox.control-auth.v1","kind":"BODY_BIND_NOTICE",
        "serverBootId":BOOT,"gameConnectionId":GAME,"bodyId":BODY,"gameNonce":nonce}, separators=(",", ":")).encode()


def _candidate():
    return json.dumps({"security":"luosandbox.control-auth.v1","kind":"BODY_BOUND",
        "serverBootId":BOOT,"gameConnectionId":GAME,"bodyId":BODY}, separators=(",", ":")).encode()


def build_cases(auth, keys_mod, frame_mod):
    specs = []
    for role in ("CLIENT_CONTROLLER", "SERVER_AUTHORITY"):
        specs.append((f"{role}-SESSION_OPEN", role, "HUB_TO_PEER", None, 0, "SESSION_OPEN", {},
                      (b'{"security":"luosandbox.control-auth.v1","kind":"SESSION_OPEN","connectionId":"'
                       + CONNECTION.encode() + b'"}')))
        for direction in ("PEER_TO_HUB", "HUB_TO_PEER"):
            specs.append((f"{role}-HEARTBEAT-{direction}", role, direction, CONNECTION,
                          0 if direction == "PEER_TO_HUB" else 1, "HEARTBEAT", {}, b"{}"))
    specs += [
        ("CLIENT-NOTICE", "CLIENT_CONTROLLER", "PEER_TO_HUB", CONNECTION, 1, "BODY_BIND_NOTICE", {}, _notice()),
        ("SERVER-ROUTE", "SERVER_AUTHORITY", "HUB_TO_PEER", CONNECTION, 2, "BODY_BIND_ROUTE",
         {"route_id":ROUTE,"source_install_id":"client","source_connection_id":SOURCE,"recipient_connection_id":CONNECTION}, _notice()),
        ("SERVER-CANDIDATE", "SERVER_AUTHORITY", "PEER_TO_HUB", CONNECTION, 1, "BODY_BOUND_CANDIDATE",
         {"route_id":ROUTE,"recipient_connection_id":CONNECTION,"challenge_expires_at":"2026-10-23T00:00:05.000Z"}, _candidate()),
    ]
    cases = []
    for name, role, direction, current, sequence, kind, changes, payload in specs:
        transcript = _transcript(keys_mod, auth, role); psk = bytes(range(32)) if role == "CLIENT_CONTROLLER" else bytes(range(96,128))
        context = frame_mod.TrustedFrameContext(authenticated=transcript, connection_id=current)
        values = dict(connection_id=CONNECTION, challenge_id=CHALLENGE, sequence=sequence, message_kind=kind,
                      route_id="", source_install_id="", source_connection_id="", recipient_connection_id="",
                      challenge_expires_at="", payload=payload); values.update(changes)
        frame = frame_mod.PostAuthFrame(**values); owner = keys_mod.PostAuthSessionKeys(transcript, psk)
        raw = frame_mod.encode_frame(frame, context, owner, direction)
        mac_input = frame_mod._mac_input(frame, context, direction)
        outer = json.loads(raw)
        cases.append({"name":name,"accepted":True,"role":role,"direction":direction,"currentConnectionId":current,
            "pskHex":psk.hex(),"transcript":transcript_json(transcript),"frame":frame_json(frame),
            "pythonRawHex":raw.hex(),"pythonMacInputHex":mac_input.hex(),
            "pythonMacHex":base64.urlsafe_b64decode(outer["mac"] + "=").hex()})
    base = next(case for case in cases if case["name"] == "CLIENT_CONTROLLER-HEARTBEAT-PEER_TO_HUB")
    negatives = {
        "bad-bom": b"\xef\xbb\xbf" + bytes.fromhex(base["pythonRawHex"]),
        "bad-duplicate": bytes.fromhex(base["pythonRawHex"]).replace(b'{', b'{"security":"x",', 1),
        "bad-unknown": bytes.fromhex(base["pythonRawHex"]).replace(b'{', b'{"unknown":"x",', 1),
        "bad-bool-seq": bytes.fromhex(base["pythonRawHex"]).replace(b'"sequence":0', b'"sequence":true'),
        "bad-float-seq": bytes.fromhex(base["pythonRawHex"]).replace(b'"sequence":0', b'"sequence":0.0'),
        "bad-mac-padding": bytes.fromhex(base["pythonRawHex"]).replace(b'"mac":"', b'"mac":"=', 1),
        "bad-payload-padding": bytes.fromhex(base["pythonRawHex"]).replace(b'"payload":"e30"', b'"payload":"e30="'),
        "bad-tamper": bytes.fromhex(base["pythonRawHex"]).replace(b'"sequence":0', b'"sequence":1'),
        "bad-deep": b'[[[[[{}]]]]]'
    }
    for name, raw in negatives.items():
        item = dict(base); item.update(name=name, accepted=False, pythonRawHex=raw.hex())
        cases.append(item)
    return cases


def transcript_json(value):
    c=value.context
    return {"role":c.peer_role,"peerInstallId":c.peer_install_id,"hubInstallId":c.hub_install_id,"keyId":c.key_id,
        "protocol":c.protocol,"modHashHex":c.mod_hash.hex(),"configHashHex":c.config_hash.hex(),"bindingHashHex":c.binding_hash.hex(),
        "peerNonceHex":value.peer_nonce.hex(),"hubNonceHex":value.hub_nonce.hex(),"challengeId":value.challenge_id,
        "issuedAt":value.issued_at.strftime("%Y-%m-%dT%H:%M:%S.000Z"),"expiresAt":value.expires_at.strftime("%Y-%m-%dT%H:%M:%S.000Z")}


def frame_json(value):
    return {"connectionId":value.connection_id,"challengeId":value.challenge_id,"sequence":value.sequence,
        "messageKind":value.message_kind,"routeId":value.route_id,"sourceInstallId":value.source_install_id,
        "sourceConnectionId":value.source_connection_id,"recipientConnectionId":value.recipient_connection_id,
        "challengeExpiresAt":value.challenge_expires_at,"payloadHex":value.payload.hex()}


def input_document(source_snapshot=None):
    snapshot = read_source_snapshot() if source_snapshot is None else source_snapshot
    with loaded_python(snapshot) as modules: return {"schema":SCHEMA,"cases":build_cases(*modules)}


def source_hashes(source_snapshot=None):
    snapshot = read_source_snapshot() if source_snapshot is None else source_snapshot
    result={}
    for path in SOURCES:
        key=str(path.relative_to(ROOT)).replace("\\","/") if path.is_relative_to(ROOT) else str(path)
        result[key]=hashlib.sha256(snapshot[path]).hexdigest()
    if len(result)!=len(SOURCES): raise Blocked("source_hash_set_invalid")
    return result


def load_java(path, expected):
    try:
        if path.is_symlink() or not path.is_file(): raise Blocked("java_result_missing_or_not_regular")
        size=path.stat().st_size
        if size<=0 or size>MAX_RESULT: raise Blocked("java_result_oversized_or_empty")
        raw=path.read_bytes()
    except OSError as error: raise Blocked("java_result_gate_failed") from error
    if len(raw)!=size or raw.startswith(b"\xef\xbb\xbf"): raise Blocked("java_result_encoding_invalid")
    try: value=json.loads(raw.decode("utf-8"), object_pairs_hook=_unique)
    except (UnicodeError,json.JSONDecodeError) as error: raise Blocked("java_result_invalid_json") from error
    if not isinstance(value,dict) or set(value)!={"schema","invocation","loadedClasses","cases"} or value["schema"]!=SCHEMA: raise Blocked("java_schema_invalid")
    inv=value["invocation"]
    if not isinstance(inv,dict) or set(inv)!={"testClass","forcedRerunRequired","executionAttestation"} or inv["testClass"]!=JAVA_FQCN or inv["forcedRerunRequired"] is not True or inv["executionAttestation"]!="SEPARATE_RUNNER_OBSERVATION_REQUIRED": raise Blocked("java_invocation_invalid")
    classes=value["loadedClasses"]
    if not isinstance(classes,dict) or set(classes)!={"PostAuthSessionKeys","PostAuthFrameCodec"}: raise Blocked("java_classes_invalid")
    for name,item in classes.items():
        suffix=f"/io/github/jinyiwei2012/luosandbox/security/{name}.class"
        if not isinstance(item,dict) or set(item)!={"resource","sha256"} or not isinstance(item["resource"],str) or not item["resource"].startswith(("file:/","jar:file:/")) or not item["resource"].replace("\\","/").endswith(suffix) or not isinstance(item["sha256"],str) or len(item["sha256"])!=64 or any(c not in "0123456789abcdef" for c in item["sha256"]): raise Blocked("java_class_identity_invalid")
    cases=value["cases"]
    if not isinstance(cases,list) or len(cases)!=len(expected["cases"]): raise Blocked("java_case_count_invalid")
    names=[item.get("name") for item in cases if isinstance(item,dict)]
    if len(names)!=len(cases) or len(set(names))!=len(names) or set(names)!={c["name"] for c in expected["cases"]}: raise Blocked("java_case_set_invalid")
    expected_by_name={case["name"]:case for case in expected["cases"]}
    for item in cases:
        source=expected_by_name[item["name"]]
        required={"name","accepted"} | ({"javaRawHex","javaMacInputHex","javaMacHex"} if source["accepted"] else set())
        if set(item)!=required or item.get("accepted") is not source["accepted"]: raise Blocked("java_case_schema_invalid")
        if source["accepted"]:
            for field in ("javaRawHex","javaMacInputHex","javaMacHex"):
                field_value=item.get(field)
                if not isinstance(field_value,str) or len(field_value)%2 or any(c not in "0123456789abcdef" for c in field_value): raise Blocked("java_case_hex_invalid")
            if len(item["javaMacHex"])!=64: raise Blocked("java_case_mac_invalid")
    return value


def compare(java, expected, modules=None, source_snapshot=None):
    actual={c["name"]:c for c in java["cases"]}; errors=[]
    if modules is None:
        snapshot = read_source_snapshot() if source_snapshot is None else source_snapshot
        with loaded_python(snapshot) as loaded:
            return compare(java, expected, loaded, snapshot)
    auth,keys_mod,frame_mod=modules
    if modules is not None:
        for source in expected["cases"]:
            got=actual[source["name"]]
            required={"name","accepted"} | ({"javaRawHex","javaMacInputHex","javaMacHex"} if source["accepted"] else set())
            if set(got)!=required or got.get("accepted") is not source["accepted"]: errors.append(source["name"]+":schema"); continue
            if source["accepted"]:
                if got["javaMacInputHex"]!=source["pythonMacInputHex"] or got["javaMacHex"]!=source["pythonMacHex"]: errors.append(source["name"]+":mac")
                try:
                    transcript=from_transcript(keys_mod,auth,source["transcript"]); context=frame_mod.TrustedFrameContext(authenticated=transcript,connection_id=source["currentConnectionId"]); owner=keys_mod.PostAuthSessionKeys(transcript,bytes.fromhex(source["pskHex"])); parsed=frame_mod.parse_frame(bytes.fromhex(got["javaRawHex"]),context,owner,source["direction"])
                    if frame_json(parsed)!=source["frame"]: errors.append(source["name"]+":semantic")
                except Exception: errors.append(source["name"]+":decode")
    return errors


def from_transcript(keys_mod,auth,data):
    context=auth.ControlAuthContext(peer_role=data["role"],peer_install_id=data["peerInstallId"],hub_install_id=data["hubInstallId"],key_id=data["keyId"],protocol=data["protocol"],mod_hash=bytes.fromhex(data["modHashHex"]),config_hash=bytes.fromhex(data["configHashHex"]),binding_hash=bytes.fromhex(data["bindingHashHex"]))
    return keys_mod.AuthenticatedTranscript(context=context,peer_nonce=bytes.fromhex(data["peerNonceHex"]),hub_nonce=bytes.fromhex(data["hubNonceHex"]),challenge_id=data["challengeId"],issued_at=datetime.strptime(data["issuedAt"],"%Y-%m-%dT%H:%M:%S.000Z").replace(tzinfo=timezone.utc),expires_at=datetime.strptime(data["expiresAt"],"%Y-%m-%dT%H:%M:%S.000Z").replace(tzinfo=timezone.utc))


def main(argv=None):
    parser=argparse.ArgumentParser(description=__doc__); parser.add_argument("--write-input",type=Path); parser.add_argument("--java-result",type=Path); parser.add_argument("--report",type=Path); args=parser.parse_args(argv)
    try:
        head=subprocess.check_output(["git","-C",str(UPSTREAM),"rev-parse","HEAD"],text=True,timeout=10).strip()
        if head!=UPSTREAM_HEAD: raise Blocked("upstream_head_mismatch")
        snapshot=read_source_snapshot()
        with loaded_python(snapshot) as modules:
            expected={"schema":SCHEMA,"cases":build_cases(*modules)}
            if args.write_input: args.write_input.parent.mkdir(parents=True,exist_ok=True); args.write_input.write_text(json.dumps(expected,sort_keys=True)+"\n",encoding="utf-8")
            result={"status":"PYTHON_CASES_VALIDATED","crossLanguage":"NOT_EXECUTED","caseCount":len(expected["cases"]),"sourceHashes":source_hashes(snapshot),"upstreamHead":head,"limits":["codec_only_no_queue_session_currentness_or_body_authority","upstream_dependency_committed_locally_not_pushed_or_published","fresh_python_module_execution_requires_exclusive_single_owner_process_use"]}
            if args.java_result:
                java=load_java(args.java_result,expected); errors=compare(java,expected,modules,snapshot); result.update(errors=errors,crossLanguage="MATCHED_UNATTESTED" if not errors else "FAILED",status="JAVA_DOCUMENT_MATCHED_UNATTESTED" if not errors else "FAILED",javaDocumentProvenance="UNATTESTED_EXTERNAL_INPUT",javaInvocation=java["invocation"],javaLoadedClasses=java["loadedClasses"],provenanceLimitations=["external_java_document_is_not_execution_attestation","loaded_class_hashes_alone_do_not_prove_fresh_compilation","fresh_forced_junit_is_separate_parent_evidence"])
    except (Blocked,OSError,UnicodeError,subprocess.SubprocessError) as error:
        result={"status":"BLOCKED","crossLanguage":"BLOCKED","errors":[str(error) if isinstance(error,Blocked) else "interop_gate_failed"]}
    if args.report:
        try: args.report.parent.mkdir(parents=True,exist_ok=True); args.report.write_text(json.dumps(result,sort_keys=True)+"\n",encoding="utf-8")
        except OSError: result={"status":"BLOCKED","crossLanguage":"BLOCKED","errors":["report_output_failed"]}
    print(json.dumps({"status":result["status"],"crossLanguage":result["crossLanguage"]},sort_keys=True)); return 0 if result["status"] in {"PYTHON_CASES_VALIDATED","JAVA_DOCUMENT_MATCHED_UNATTESTED"} else 2


if __name__=="__main__": raise SystemExit(main())
