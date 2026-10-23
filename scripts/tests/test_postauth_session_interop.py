import contextlib, copy, importlib.util, io, json, tempfile, unittest
from pathlib import Path
from unittest import mock
SCRIPT=Path(__file__).resolve().parents[1]/"minecraft"/"verify_postauth_session_interop.py"; SPEC=importlib.util.spec_from_file_location("session_interop",SCRIPT); interop=importlib.util.module_from_spec(SPEC); assert SPEC.loader; SPEC.loader.exec_module(interop)

def document(expected):
    traces=[]
    for source in expected["traces"]:
        traces.append({"name":source["name"],"peerAckHex":source["pythonOpenHex"],"peerHeartbeatHex":source["pythonHeartbeatHex"],"peerBusinessHex":"00" if source["role"]=="CLIENT_CONTROLLER" else None,"peerTimeoutClosed":True,"peerSendFailureClosed":True})
    packages={"PostAuthPeerSession":"transport","StrictJson":"wire"}
    return {"schema":interop.SCHEMA,"invocation":{"testClass":interop.JAVA_FQCN,"forcedRerunRequired":True,"executionAttestation":"SEPARATE_RUNNER_OBSERVATION_REQUIRED"},"loadedClasses":{name:{"resource":f"file:/dummy/io/github/jinyiwei2012/luosandbox/{packages.get(name,'security')}/{name}.class","sha256":"ab"*32} for name in interop.CLASS_NAMES},"traces":traces}

class SessionInteropHarnessTest(unittest.TestCase):
    def test_actual_python_hub_traces_cover_both_roles(self):
        value=interop.input_document(); self.assertEqual(2,len(value["traces"])); self.assertEqual({"CLIENT_CONTROLLER","SERVER_AUTHORITY"},{t["role"] for t in value["traces"]}); self.assertTrue(all(t["pythonOpenHex"] and t["pythonHeartbeatHex"] for t in value["traces"]))

    def test_source_snapshot_reads_exact_closure_once_and_survives_reader_failure(self):
        original=interop._read; counts={}
        def once(path): counts[path]=counts.get(path,0)+1; self.assertEqual(1,counts[path]); return original(path)
        with mock.patch.object(interop,"_read",side_effect=once): source=interop.snapshot()
        self.assertEqual(set(interop.SOURCES),set(counts)); expected_hash=interop.hashes(source)
        with mock.patch.object(interop,"_read",side_effect=AssertionError("reread")):
            with interop.loaded(source) as modules:
                expected={"schema":interop.SCHEMA,"traces":[interop.make_trace(modules,r) for r in ("CLIENT_CONTROLLER","SERVER_AUTHORITY")]}; self.assertEqual(expected_hash,interop.hashes(source)); self.assertIsInstance(expected,dict)

    def test_wellformed_external_document_is_unattested_and_mismatch_failed(self):
        expected=interop.input_document(); value=document(expected)
        with tempfile.TemporaryDirectory() as temporary:
            path=Path(temporary)/"java.json"; path.write_text(json.dumps(value),encoding="utf-8"); self.assertEqual(2,len(interop.load_java(path,expected)["traces"]))
            value["traces"][0]["peerTimeoutClosed"]=False; path.write_text(json.dumps(value),encoding="utf-8")
            java=interop.load_java(path,expected); source=interop.snapshot()
            with interop.loaded(source) as modules:
                self.assertIn("CLIENT_CONTROLLER:timeout_not_closed",interop.compare(java,expected,modules))

    def test_strict_schema_type_metadata_hex_names_and_classes_block(self):
        expected=interop.input_document(); base=document(expected); mutations=[]
        for root in ([],None,1): mutations.append(root)
        value=copy.deepcopy(base); value["extra"]=1; mutations.append(value)
        value=copy.deepcopy(base); value["invocation"]=[]; mutations.append(value)
        value=copy.deepcopy(base); value["invocation"]["forcedRerunRequired"]=1; mutations.append(value)
        value=copy.deepcopy(base); value["loadedClasses"]={}; mutations.append(value)
        value=copy.deepcopy(base); value["loadedClasses"]["PostAuthSessionKeys"]["sha256"]="AB"*32; mutations.append(value)
        value=copy.deepcopy(base); value["traces"][0]=1; mutations.append(value)
        value=copy.deepcopy(base); value["traces"][1]["name"]="CLIENT_CONTROLLER"; mutations.append(value)
        value=copy.deepcopy(base); value["traces"][0]["peerAckHex"]="0"; mutations.append(value)
        value=copy.deepcopy(base); value["traces"][0]["peerBusinessHex"]=None; mutations.append(value)
        value=copy.deepcopy(base); value["traces"][1]["peerBusinessHex"]="00"; mutations.append(value)
        value=copy.deepcopy(base); value["traces"][0]["unknown"]=1; mutations.append(value)
        for field in ("peerTimeoutClosed","peerSendFailureClosed"):
            for invalid in (0,1,"true",None):
                value=copy.deepcopy(base); value["traces"][0][field]=invalid; mutations.append(value)
        with tempfile.TemporaryDirectory() as temporary:
            path=Path(temporary)/"java.json"
            for value in mutations:
                path.write_text(json.dumps(value),encoding="utf-8")
                with self.assertRaises(interop.Blocked): interop.load_java(path,expected)

    def test_wellformed_false_outcomes_load_but_compare_failed_with_precise_reasons(self):
        expected=interop.input_document(); base=document(expected)
        with tempfile.TemporaryDirectory() as temporary:
            path=Path(temporary)/"java.json"
            for field,reason in (("peerTimeoutClosed","CLIENT_CONTROLLER:timeout_not_closed"),
                                 ("peerSendFailureClosed","CLIENT_CONTROLLER:send_failure_not_closed")):
                value=copy.deepcopy(base); value["traces"][0][field]=False
                path.write_text(json.dumps(value),encoding="utf-8")
                java=interop.load_java(path,expected)
                source=interop.snapshot()
                with interop.loaded(source) as modules:
                    errors=interop.compare(java,expected,modules)
                self.assertIn(reason,errors)
                output=io.StringIO()
                with contextlib.redirect_stdout(output): code=interop.main(["--java-result",str(path)])
                self.assertEqual(2,code)
                self.assertEqual("FAILED",json.loads(output.getvalue())["status"])

    def test_duplicate_bom_missing_symlink_oversize_and_oserrors_are_sanitized(self):
        expected=interop.input_document()
        with tempfile.TemporaryDirectory() as temporary:
            root=Path(temporary); path=root/"java.json"
            for raw in (b'{"schema":"a","schema":"b"}',b'\xef\xbb\xbf{}',b'x'*(interop.MAX_RESULT+1)):
                path.write_bytes(raw)
                with self.assertRaises(interop.Blocked): interop.load_java(path,expected)
            with self.assertRaises(interop.Blocked): interop.load_java(root/"missing",expected)
            target=root/"target"; target.write_text(json.dumps(document(expected)),encoding="utf-8"); link=root/"link"
            try: link.symlink_to(target)
            except OSError: link=None
            if link:
                with self.assertRaises(interop.Blocked): interop.load_java(link,expected)
        secret="secret C:\\private"; out=io.StringIO(); err=io.StringIO()
        with mock.patch.object(Path,"is_file",side_effect=OSError(secret)),contextlib.redirect_stdout(out),contextlib.redirect_stderr(err): code=interop.main([])
        self.assertEqual(2,code); self.assertNotIn(secret,out.getvalue()+err.getvalue())

if __name__=="__main__": unittest.main()
