"""Offline actual Python Hub / Java peer post-auth session interop evidence."""
from __future__ import annotations

import argparse, base64, contextlib, hashlib, json, subprocess, sys, types
from datetime import datetime, timedelta, timezone
from pathlib import Path

ROOT=Path(__file__).resolve().parents[2]
UPSTREAM=Path(r"C:\Agent-LuoTianyi\.slim\worktrees\minecraft-observation-adapter")
UPSTREAM_HEAD="f2108373367ce95ffa9fc442581e827580b04c4f"
SERVER=UPSTREAM/"server"
PY_FILES=tuple(SERVER/path for path in ("src/adapter/minecraft/control_auth.py","src/adapter/minecraft/postauth_keys.py","src/adapter/minecraft/postauth_frame.py","src/adapter/minecraft/postauth_session.py","tests/unit/adapter/test_minecraft_control_auth.py","tests/unit/adapter/test_minecraft_postauth_keys.py","tests/unit/adapter/test_minecraft_postauth_frame.py","tests/unit/adapter/test_minecraft_postauth_session.py"))
JAVA_FILES=tuple(ROOT/path for path in ("bridge/forge/src/main/java/io/github/jinyiwei2012/luosandbox/security/ControlAuth.java","bridge/forge/src/main/java/io/github/jinyiwei2012/luosandbox/security/ControlDeploymentManifest.java","bridge/forge/src/main/java/io/github/jinyiwei2012/luosandbox/security/PostAuthSessionKeys.java","bridge/forge/src/main/java/io/github/jinyiwei2012/luosandbox/security/PostAuthFrameCodec.java","bridge/forge/src/main/java/io/github/jinyiwei2012/luosandbox/transport/PostAuthPeerSession.java","bridge/forge/src/main/java/io/github/jinyiwei2012/luosandbox/wire/StrictJson.java","bridge/forge/src/test/java/io/github/jinyiwei2012/luosandbox/security/PostAuthSessionKeysTest.java","bridge/forge/src/test/java/io/github/jinyiwei2012/luosandbox/security/PostAuthFrameCodecTest.java","bridge/forge/src/test/java/io/github/jinyiwei2012/luosandbox/transport/PostAuthPeerSessionTest.java","bridge/forge/src/test/java/io/github/jinyiwei2012/luosandbox/transport/PostAuthSessionInteropTest.java"))
TOOL=ROOT/"scripts/minecraft/verify_postauth_session_interop.py"; TOOL_TEST=ROOT/"scripts/tests/test_postauth_session_interop.py"; SPEC=ROOT/"docs/decisions/minecraft-control-postauth-v2-draft.md"
SOURCES=PY_FILES+JAVA_FILES+(TOOL,TOOL_TEST,SPEC)
SCHEMA="postauth-session-v2-interop-v1"; JAVA_FQCN="io.github.jinyiwei2012.luosandbox.transport.PostAuthSessionInteropTest"; MAX_RESULT=1_048_576; CLASS_NAMES=("ControlAuth","ControlDeploymentManifest","PostAuthSessionKeys","PostAuthFrameCodec","PostAuthPeerSession","StrictJson")
CONNECTION="20000000-0000-4000-8000-000000000001"; CHALLENGE="10000000-0000-4000-8000-000000000001"; ROUTE="30000000-0000-4000-8000-000000000001"
class Blocked(RuntimeError): pass

def _read(path):
    try:
        if path.is_symlink() or not path.is_file(): raise Blocked("source_missing_or_not_regular")
        raw=path.read_bytes()
        if not raw or len(raw)>2_097_152: raise Blocked("source_size_invalid")
        return raw
    except OSError as error: raise Blocked("source_gate_failed") from error

def snapshot(): return {path:_read(path) for path in SOURCES}

@contextlib.contextmanager
def loaded(source):
    names=("src","src.adapter","src.adapter.minecraft","src.adapter.minecraft.control_auth","src.adapter.minecraft.postauth_keys","src.adapter.minecraft.postauth_frame","src.adapter.minecraft.postauth_session")
    files=dict(zip(names[3:],PY_FILES[:4])); missing=object(); previous={n:sys.modules.get(n,missing) for n in names}
    try:
        for name in names[:3]: module=types.ModuleType(name); module.__path__=[]; module.__package__=name; sys.modules[name]=module
        modules=[]
        for name in names[3:]:
            module=types.ModuleType(name); module.__file__=str(files[name]); module.__package__=name.rpartition(".")[0]; sys.modules[name]=module; exec(compile(source[files[name]],str(files[name]),"exec"),module.__dict__); modules.append(module)
        yield tuple(modules)
    finally:
        for name in reversed(names):
            if previous[name] is missing: sys.modules.pop(name,None)
            else: sys.modules[name]=previous[name]

class Future:
    def __init__(self): self.callback=None; self.failed=False
    def add_done_callback(self,callback): self.callback=callback
    def cancelled(self): return False
    def exception(self): return RuntimeError("failed") if self.failed else None
    def finish(self): self.callback(self)

def transcript(auth,keys,role):
    issued=datetime(2026,10,23,tzinfo=timezone.utc); context=auth.ControlAuthContext(peer_role=role,peer_install_id="server" if role=="SERVER_AUTHORITY" else "client",hub_install_id="hub",key_id="key",protocol="LUO_CONTROL_POSTAUTH_V2",mod_hash=bytes(range(32,64)),config_hash=bytes(range(128,160)),binding_hash=bytes(range(160,192)))
    return keys.AuthenticatedTranscript(context=context,peer_nonce=bytes(range(64,96)),hub_nonce=bytes(range(96,128)),challenge_id=CHALLENGE,issued_at=issued,expires_at=issued+timedelta(seconds=5))

def tj(value):
    c=value.context; return {"role":c.peer_role,"peerInstallId":c.peer_install_id,"hubInstallId":c.hub_install_id,"keyId":c.key_id,"protocol":c.protocol,"modHashHex":c.mod_hash.hex(),"configHashHex":c.config_hash.hex(),"bindingHashHex":c.binding_hash.hex(),"peerNonceHex":value.peer_nonce.hex(),"hubNonceHex":value.hub_nonce.hex(),"challengeId":value.challenge_id,"issuedAt":"2026-10-23T00:00:00.000Z","expiresAt":"2026-10-23T00:00:05.000Z"}

def make_trace(modules,role):
    auth,keys_mod,frame_mod,session_mod=modules; tr=transcript(auth,keys_mod,role); psk=bytes(range(96,128)) if role=="SERVER_AUTHORITY" else bytes(range(32)); keys=keys_mod.PostAuthSessionKeys(tr,psk); now=[0]; sent=[]; futures=[]; posted=[]; observed=[]
    def send(raw): sent.append(raw); f=Future(); futures.append(f); return f
    hub=session_mod.PostAuthHubSession(authenticated=tr,keys=keys,connection_id=CONNECTION,monotonic_millis=lambda:now[0],send=send,post=posted.append,owner_abort=posted.append,report_cleanup_unknown=lambda:None,fence=lambda:None,close=lambda:None,observe=observed.append)
    hub.start(); open_raw=sent[0]
    context=frame_mod.TrustedFrameContext(authenticated=tr,connection_id=CONNECTION)
    ack=frame_mod.encode_frame(frame_mod.PostAuthFrame(connection_id=CONNECTION,challenge_id=CHALLENGE,sequence=0,message_kind="HEARTBEAT",route_id="",source_install_id="",source_connection_id="",recipient_connection_id="",challenge_expires_at="",payload=b"{}"),context,keys,"PEER_TO_HUB")
    hub.receive(ack); futures[0].finish(); [posted.pop(0)() for _ in range(len(posted))]; now[0]=500; hub.tick(); heartbeat=sent[1]
    business=None
    if role=="SERVER_AUTHORITY":
        nonce=base64.urlsafe_b64encode(bytes(range(16,48))).decode().rstrip("="); payload=json.dumps({"security":"luosandbox.control-auth.v1","kind":"BODY_BIND_NOTICE","serverBootId":"50000000-0000-4000-8000-000000000001","gameConnectionId":"60000000-0000-4000-8000-000000000001","bodyId":"70000000-0000-4000-8000-000000000001","gameNonce":nonce},separators=(",",":")).encode()
        business=frame_mod.encode_frame(frame_mod.PostAuthFrame(connection_id=CONNECTION,challenge_id=CHALLENGE,sequence=2,message_kind="BODY_BIND_ROUTE",route_id=ROUTE,source_install_id="client",source_connection_id="40000000-0000-4000-8000-000000000001",recipient_connection_id=CONNECTION,challenge_expires_at="",payload=payload),context,keys,"HUB_TO_PEER")
    return {"name":role,"role":role,"pskHex":psk.hex(),"transcript":tj(tr),"pythonOpenHex":open_raw.hex(),"pythonHeartbeatHex":heartbeat.hex(),"pythonBusinessHex":None if business is None else business.hex()}

def input_document(source=None):
    source=snapshot() if source is None else source
    with loaded(source) as modules: return {"schema":SCHEMA,"traces":[make_trace(modules,role) for role in ("CLIENT_CONTROLLER","SERVER_AUTHORITY")]}

def hashes(source):
    return {str(path.relative_to(ROOT)).replace("\\","/") if path.is_relative_to(ROOT) else str(path):hashlib.sha256(source[path]).hexdigest() for path in SOURCES}

def _unique(pairs):
    result={}
    for key,value in pairs:
        if key in result: raise Blocked("duplicate_json_key")
        result[key]=value
    return result

def load_java(path,expected):
    try:
        if path.is_symlink() or not path.is_file(): raise Blocked("java_result_missing_or_not_regular")
        size=path.stat().st_size
        if size<=0 or size>MAX_RESULT: raise Blocked("java_result_size_invalid")
        raw=path.read_bytes()
    except OSError as error: raise Blocked("java_result_gate_failed") from error
    if len(raw)!=size or raw.startswith(b"\xef\xbb\xbf"): raise Blocked("java_result_encoding_invalid")
    try: value=json.loads(raw.decode("utf-8",errors="strict"),object_pairs_hook=_unique)
    except (UnicodeError,json.JSONDecodeError) as error: raise Blocked("java_result_invalid") from error
    if not isinstance(value,dict) or set(value)!={"schema","invocation","loadedClasses","traces"} or value["schema"]!=SCHEMA: raise Blocked("java_schema_invalid")
    inv=value["invocation"]
    if not isinstance(inv,dict) or set(inv)!={"testClass","forcedRerunRequired","executionAttestation"} or inv.get("testClass")!=JAVA_FQCN or inv.get("forcedRerunRequired") is not True or inv.get("executionAttestation")!="SEPARATE_RUNNER_OBSERVATION_REQUIRED": raise Blocked("java_invocation_invalid")
    classes=value["loadedClasses"]
    if not isinstance(classes,dict) or set(classes)!=set(CLASS_NAMES): raise Blocked("java_classes_invalid")
    packages={"PostAuthPeerSession":"transport","StrictJson":"wire"}
    for name,item in classes.items():
        package=packages.get(name,"security"); suffix=f"/io/github/jinyiwei2012/luosandbox/{package}/{name}.class"
        if not isinstance(item,dict) or set(item)!={"resource","sha256"} or not isinstance(item.get("resource"),str) or not 1<=len(item["resource"])<=2048 or not item["resource"].startswith(("file:/","jar:file:/")) or not item["resource"].replace("\\","/").endswith(suffix) or not isinstance(item.get("sha256"),str) or len(item["sha256"])!=64 or any(c not in "0123456789abcdef" for c in item["sha256"]): raise Blocked("java_class_invalid")
    traces=value["traces"]
    if not isinstance(traces,list) or len(traces)!=2: raise Blocked("trace_set_invalid")
    if any(not isinstance(t,dict) for t in traces): raise Blocked("trace_type_invalid")
    names=[t.get("name") for t in traces]
    if len(set(names))!=2 or set(names)!={"CLIENT_CONTROLLER","SERVER_AUTHORITY"}: raise Blocked("trace_names_invalid")
    for trace in traces:
        if set(trace)!={"name","peerAckHex","peerHeartbeatHex","peerBusinessHex","peerTimeoutClosed","peerSendFailureClosed"}: raise Blocked("trace_schema_invalid")
        if type(trace["peerTimeoutClosed"]) is not bool or type(trace["peerSendFailureClosed"]) is not bool: raise Blocked("trace_bool_invalid")
        for field in ("peerAckHex","peerHeartbeatHex"):
            text=trace[field]
            if not isinstance(text,str) or not text or len(text)%2 or any(c not in "0123456789abcdef" for c in text): raise Blocked("trace_hex_invalid")
        business=trace["peerBusinessHex"]
        if trace["name"]=="CLIENT_CONTROLLER":
            if not isinstance(business,str) or not business or len(business)%2 or any(c not in "0123456789abcdef" for c in business): raise Blocked("trace_business_invalid")
        elif business is not None: raise Blocked("trace_business_invalid")
    return value

def from_t(modules,data):
    auth,keys,_,_=modules; c=auth.ControlAuthContext(peer_role=data["role"],peer_install_id=data["peerInstallId"],hub_install_id=data["hubInstallId"],key_id=data["keyId"],protocol=data["protocol"],mod_hash=bytes.fromhex(data["modHashHex"]),config_hash=bytes.fromhex(data["configHashHex"]),binding_hash=bytes.fromhex(data["bindingHashHex"])); issued=datetime(2026,10,23,tzinfo=timezone.utc); return keys.AuthenticatedTranscript(context=c,peer_nonce=bytes.fromhex(data["peerNonceHex"]),hub_nonce=bytes.fromhex(data["hubNonceHex"]),challenge_id=data["challengeId"],issued_at=issued,expires_at=issued+timedelta(seconds=5))

def compare(java,expected,modules):
    auth,keys_mod,frame_mod,session_mod=modules; actual={t["name"]:t for t in java["traces"]}; errors=[]
    for source in expected["traces"]:
        got=actual[source["name"]]; required={"name","peerAckHex","peerHeartbeatHex","peerBusinessHex","peerTimeoutClosed","peerSendFailureClosed"}
        if set(got)!=required: errors.append(source["name"]+":schema"); continue
        if got["peerTimeoutClosed"] is False: errors.append(source["name"]+":timeout_not_closed")
        if got["peerSendFailureClosed"] is False: errors.append(source["name"]+":send_failure_not_closed")
        tr=from_t(modules,source["transcript"]); psk=bytes.fromhex(source["pskHex"]); owner=keys_mod.PostAuthSessionKeys(tr,psk); now=[0]; sent=[]; futures=[]; posted=[]; observed=[]
        def send(raw): sent.append(raw); f=Future(); futures.append(f); return f
        hub=session_mod.PostAuthHubSession(authenticated=tr,keys=owner,connection_id=CONNECTION,monotonic_millis=lambda:now[0],send=send,post=posted.append,owner_abort=posted.append,report_cleanup_unknown=lambda:None,fence=lambda:None,close=lambda:None,observe=observed.append)
        try:
            hub.start(); hub.receive(bytes.fromhex(got["peerAckHex"])); futures[0].finish(); [posted.pop(0)() for _ in range(len(posted))]; hub.receive(bytes.fromhex(got["peerHeartbeatHex"]))
            if got["peerBusinessHex"] is not None: hub.receive(bytes.fromhex(got["peerBusinessHex"]))
            if source["role"]=="CLIENT_CONTROLLER" and len(observed)!=1: errors.append(source["name"]+":business")
        except Exception: errors.append(source["name"]+":decode")
    return errors

def main(argv=None):
    parser=argparse.ArgumentParser(description=__doc__); parser.add_argument("--write-input",type=Path); parser.add_argument("--java-result",type=Path); parser.add_argument("--report",type=Path); args=parser.parse_args(argv)
    try:
        head=subprocess.check_output(["git","-C",str(UPSTREAM),"rev-parse","HEAD"],text=True,timeout=10).strip()
        if head!=UPSTREAM_HEAD: raise Blocked("upstream_head_mismatch")
        source=snapshot()
        with loaded(source) as modules:
            expected={"schema":SCHEMA,"traces":[make_trace(modules,role) for role in ("CLIENT_CONTROLLER","SERVER_AUTHORITY")]}
            if args.write_input: args.write_input.parent.mkdir(parents=True,exist_ok=True); args.write_input.write_text(json.dumps(expected,sort_keys=True)+"\n",encoding="utf-8")
            result={"status":"PYTHON_TRACES_VALIDATED","crossLanguage":"NOT_EXECUTED","traceCount":2,"sourceHashes":hashes(source),"upstreamHead":head,"limits":["offline_fixture_backed_continuous_instances_not_live_ipc","no_socket_role_registry_body_authority_or_activation","exclusive_single_owner_module_loader"]}
            if args.java_result:
                java=load_java(args.java_result,expected); errors=compare(java,expected,modules); result.update(errors=errors,status="JAVA_DOCUMENT_MATCHED_UNATTESTED" if not errors else "FAILED",crossLanguage="MATCHED_UNATTESTED" if not errors else "FAILED",javaDocumentProvenance="UNATTESTED_EXTERNAL_INPUT",javaInvocation=java["invocation"],javaLoadedClasses=java["loadedClasses"],provenanceLimitations=["external_java_document_is_not_execution_attestation","loaded_class_hashes_alone_do_not_prove_fresh_compilation","fresh_forced_junit_is_separate_parent_evidence"])
    except (Blocked,OSError,UnicodeError,subprocess.SubprocessError) as error: result={"status":"BLOCKED","crossLanguage":"BLOCKED","errors":[str(error) if isinstance(error,Blocked) else "session_interop_gate_failed"]}
    if args.report:
        try: args.report.parent.mkdir(parents=True,exist_ok=True); args.report.write_text(json.dumps(result,sort_keys=True)+"\n",encoding="utf-8")
        except OSError: result={"status":"BLOCKED","crossLanguage":"BLOCKED","errors":["report_output_failed"]}
    print(json.dumps({"status":result["status"],"crossLanguage":result["crossLanguage"]},sort_keys=True)); return 0 if result["status"] in {"PYTHON_TRACES_VALIDATED","JAVA_DOCUMENT_MATCHED_UNATTESTED"} else 2

if __name__=="__main__": raise SystemExit(main())
