import contextlib, copy, importlib.util, io, json, tempfile, unittest
from pathlib import Path
from unittest import mock

SCRIPT=Path(__file__).resolve().parents[1]/"minecraft"/"verify_postauth_codec_interop.py"
SPEC=importlib.util.spec_from_file_location("postauth_codec_interop",SCRIPT); interop=importlib.util.module_from_spec(SPEC); assert SPEC.loader; SPEC.loader.exec_module(interop)

def document(expected):
    cases=[]
    for source in expected["cases"]:
        item={"name":source["name"],"accepted":source["accepted"]}
        if source["accepted"]: item.update(javaRawHex=source["pythonRawHex"],javaMacInputHex=source["pythonMacInputHex"],javaMacHex=source["pythonMacHex"])
        cases.append(item)
    return {"schema":interop.SCHEMA,"invocation":{"testClass":interop.JAVA_FQCN,"forcedRerunRequired":True,"executionAttestation":"SEPARATE_RUNNER_OBSERVATION_REQUIRED"},"loadedClasses":{name:{"resource":f"file:/dummy/io/github/jinyiwei2012/luosandbox/security/{name}.class","sha256":"ab"*32} for name in ("PostAuthSessionKeys","PostAuthFrameCodec")},"cases":cases}

class PostAuthCodecInteropTest(unittest.TestCase):
    def test_one_operation_reads_each_required_source_exactly_once(self):
        original=interop._read; counts={}
        def once(path):
            counts[path]=counts.get(path,0)+1
            if counts[path]>1: raise AssertionError(f"source reread: {path}")
            return original(path)
        with mock.patch.object(interop,"_read",side_effect=once):
            snapshot=interop.read_source_snapshot()
            with interop.loaded_python(snapshot) as modules:
                expected={"schema":interop.SCHEMA,"cases":interop.build_cases(*modules)}
                interop.source_hashes(snapshot)
                interop.compare(document(expected),expected,modules,snapshot)
        self.assertEqual(set(interop.SOURCES),set(counts)); self.assertTrue(all(value==1 for value in counts.values()))

    def test_frozen_snapshot_executes_and_hashes_same_bytes_after_disk_reader_changes(self):
        snapshot=interop.read_source_snapshot(); expected_hash=interop.source_hashes(snapshot)
        with mock.patch.object(interop,"_read",side_effect=AssertionError("must not reread")):
            with interop.loaded_python(snapshot) as modules:
                expected={"schema":interop.SCHEMA,"cases":interop.build_cases(*modules)}
                self.assertEqual(expected_hash,interop.source_hashes(snapshot))
                self.assertEqual([],interop.compare(document(expected),expected,modules,snapshot))

    def test_loader_restores_existing_modules_after_success_and_partial_failure(self):
        names=("src","src.adapter","src.adapter.minecraft","src.adapter.minecraft.control_auth","src.adapter.minecraft.postauth_keys","src.adapter.minecraft.postauth_frame")
        sentinels={name:object() for name in names}; saved={name:__import__("sys").modules.get(name) for name in names}
        modules=__import__("sys").modules
        try:
            for name,value in sentinels.items(): modules[name]=value
            snapshot=interop.read_source_snapshot()
            with interop.loaded_python(snapshot): pass
            for name,value in sentinels.items(): self.assertIs(modules[name],value)
            broken=dict(snapshot); broken[interop.PY_FRAME]=b"raise RuntimeError('injected')"
            with self.assertRaises(RuntimeError):
                with interop.loaded_python(broken): pass
            for name,value in sentinels.items(): self.assertIs(modules[name],value)
        finally:
            for name,value in saved.items():
                if value is None: modules.pop(name,None)
                else: modules[name]=value

    def test_matrix_covers_roles_directions_kinds_and_negatives(self):
        value=interop.input_document(); self.assertEqual(18,len(value["cases"])); names={c["name"] for c in value["cases"]}
        for token in ("CLIENT_CONTROLLER-SESSION_OPEN","SERVER_AUTHORITY-SESSION_OPEN","CLIENT-NOTICE","SERVER-ROUTE","SERVER-CANDIDATE","bad-deep"): self.assertIn(token,names)

    def test_actual_python_outputs_are_self_decodable(self):
        snapshot=interop.read_source_snapshot(); value=interop.input_document(snapshot)
        with interop.loaded_python(snapshot) as (auth,keys_mod,frame_mod):
            for case in value["cases"]:
                if not case["accepted"]: continue
                transcript=interop.from_transcript(keys_mod,auth,case["transcript"]); context=frame_mod.TrustedFrameContext(authenticated=transcript,connection_id=case["currentConnectionId"]); owner=keys_mod.PostAuthSessionKeys(transcript,bytes.fromhex(case["pskHex"])); parsed=frame_mod.parse_frame(bytes.fromhex(case["pythonRawHex"]),context,owner,case["direction"]); self.assertEqual(case["frame"],interop.frame_json(parsed))

    def test_matching_external_document_is_unattested_and_mismatch_failed(self):
        expected=interop.input_document(); value=document(expected)
        with tempfile.TemporaryDirectory() as temporary:
            path=Path(temporary)/"java.json"; path.write_text(json.dumps(value),encoding="utf-8"); java=interop.load_java(path,expected); self.assertEqual([],interop.compare(java,expected))
            value["cases"][0]["javaMacHex"]="00"*32; path.write_text(json.dumps(value),encoding="utf-8"); self.assertTrue(interop.compare(interop.load_java(path,expected),expected))

    def test_strict_java_schema_duplicate_names_metadata_missing_symlink_oversize(self):
        expected=interop.input_document(); base=document(expected); mutations=[]
        value=copy.deepcopy(base); value["extra"]=1; mutations.append(value)
        value=copy.deepcopy(base); value["cases"][0]["extra"]=1; mutations.append(value)
        value=copy.deepcopy(base); value["cases"][1]["name"]=value["cases"][0]["name"]; mutations.append(value)
        value=copy.deepcopy(base); value["invocation"]["forcedRerunRequired"]=1; mutations.append(value)
        value=copy.deepcopy(base); value["loadedClasses"]["PostAuthSessionKeys"]["sha256"]="AB"*32; mutations.append(value)
        with tempfile.TemporaryDirectory() as temporary:
            root=Path(temporary); path=root/"java.json"
            for value in mutations:
                path.write_text(json.dumps(value),encoding="utf-8")
                with self.assertRaises(interop.Blocked): interop.load_java(path,expected)
            path.write_bytes(b"x"*(interop.MAX_RESULT+1))
            with self.assertRaises(interop.Blocked): interop.load_java(path,expected)
            target=root/"target.json"; target.write_text(json.dumps(base),encoding="utf-8"); link=root/"link.json"
            try: link.symlink_to(target)
            except OSError: return
            with self.assertRaises(interop.Blocked): interop.load_java(link,expected)

    def test_duplicate_json_key_and_oserror_output_are_sanitized(self):
        expected=interop.input_document()
        with tempfile.TemporaryDirectory() as temporary:
            path=Path(temporary)/"java.json"; path.write_text('{"schema":"a","schema":"b"}',encoding="utf-8")
            with self.assertRaises(interop.Blocked): interop.load_java(path,expected)
        secret="secret C:\\private"; output=io.StringIO(); errors=io.StringIO()
        with mock.patch.object(Path,"is_file",side_effect=OSError(secret)),contextlib.redirect_stdout(output),contextlib.redirect_stderr(errors): code=interop.main([])
        self.assertEqual(2,code); self.assertNotIn(secret,output.getvalue()+errors.getvalue())

if __name__=="__main__": unittest.main()
