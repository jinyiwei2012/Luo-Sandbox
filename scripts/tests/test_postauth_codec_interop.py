import contextlib, copy, importlib.util, io, json, tempfile, unittest
from pathlib import Path
from unittest import mock

SCRIPT = (
    Path(__file__).resolve().parents[1]
    / "minecraft"
    / "verify_postauth_codec_interop.py"
)
SPEC = importlib.util.spec_from_file_location("postauth_codec_interop", SCRIPT)
interop = importlib.util.module_from_spec(SPEC)
assert SPEC.loader
SPEC.loader.exec_module(interop)


def document(expected):
    cases = []
    for source in expected["cases"]:
        item = {"name": source["name"], "accepted": source["accepted"]}
        if source["accepted"]:
            item.update(
                javaRawHex=source["pythonRawHex"],
                javaMacInputHex=source["pythonMacInputHex"],
                javaMacHex=source["pythonMacHex"],
            )
        cases.append(item)
    return {
        "schema": interop.SCHEMA,
        "invocation": {
            "testClass": interop.JAVA_FQCN,
            "forcedRerunRequired": True,
            "executionAttestation": "SEPARATE_RUNNER_OBSERVATION_REQUIRED",
        },
        "loadedClasses": {
            name: {
                "resource": f"file:/dummy/io/github/jinyiwei2012/luosandbox/security/{name}.class",
                "sha256": "ab" * 32,
            }
            for name in ("PostAuthSessionKeys", "PostAuthFrameCodec")
        },
        "cases": cases,
    }


class PostAuthCodecInteropTest(unittest.TestCase):
    def test_existing_input_is_preserved(self):
        with tempfile.TemporaryDirectory() as temporary:
            target = Path(temporary) / "input.json"
            target.write_bytes(b"original")
            with mock.patch.object(interop, "require_upstream_revision") as gate:
                self.assertEqual(2, self.run_main("--write-input", str(target)))
                gate.assert_not_called()
            self.assertEqual(b"original", target.read_bytes())

    def test_symlink_input_target_is_preserved(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            target = root / "target"
            link = root / "input"
            target.write_bytes(b"original")
            try:
                link.symlink_to(target)
            except OSError as error:
                self.skipTest(f"symlink creation unavailable: {type(error).__name__}")
            self.assertEqual(2, self.run_main("--write-input", str(link)))
            self.assertTrue(link.is_symlink())
            self.assertEqual(b"original", target.read_bytes())

    def test_partial_write_failure_cleans_only_owned_input(self):
        with tempfile.TemporaryDirectory() as temporary:
            target = Path(temporary) / "input"
            report = Path(temporary) / "report"
            original_open = Path.open

            class PartialWriter:
                def __init__(self, stream):
                    self.stream = stream

                def __enter__(self):
                    return self

                def __exit__(self, *args):
                    self.stream.close()

                def fileno(self):
                    return self.stream.fileno()

                def write(self, raw):
                    self.stream.write(raw[:7])
                    self.stream.flush()
                    raise OSError("private injected failure")

            def failing_open(path, *args, **kwargs):
                stream = original_open(path, *args, **kwargs)
                return (
                    PartialWriter(stream)
                    if path == target and args == ("xb",)
                    else stream
                )

            with mock.patch.object(Path, "open", failing_open):
                self.assertEqual(
                    2,
                    self.run_main(
                        "--write-input", str(target), "--report", str(report)
                    ),
                )
            self.assertFalse(target.exists())
            self.assertEqual(
                ["interop_gate_failed"], json.loads(report.read_text())["errors"]
            )

    def test_postgate_replacement_is_retained_with_unknown_cleanup(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            target = root / "input"
            report = root / "report"
            calls = 0

            def postgate(snapshot):
                nonlocal calls
                calls += 1
                if calls == 2:
                    target.rename(root / "original-created-input")
                    target.write_bytes(b"replacement")
                    raise interop.Blocked("declared_source_changed")

            with mock.patch.object(interop, "require_unchanged", side_effect=postgate):
                self.assertEqual(
                    2,
                    self.run_main(
                        "--write-input", str(target), "--report", str(report)
                    ),
                )
            self.assertEqual(b"replacement", target.read_bytes())
            self.assertIn(
                "input_cleanup_unknown", json.loads(report.read_text())["errors"]
            )

    def test_output_direct_and_canonical_conflicts_have_no_output_effects(self):
        for left, right in (
            ("--write-input", "--report"),
            ("--write-input", "--java-result"),
            ("--report", "--java-result"),
        ):
            with self.subTest(
                left=left, right=right
            ), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                target = root / "existing"
                target.write_bytes(b"original")
                alias = root / "absent" / ".." / "existing"
                for other in (target, alias):
                    with mock.patch.object(
                        interop, "require_upstream_revision"
                    ) as gate:
                        self.assertEqual(
                            2, self.run_main(left, str(target), right, str(other))
                        )
                        gate.assert_not_called()
                self.assertEqual(b"original", target.read_bytes())
                self.assertFalse((root / "absent").exists())

    def test_report_source_and_hardlink_alias_conflicts_preserve_sources(self):
        for source in interop.SOURCES:
            before = source.read_bytes()
            self.assertEqual(2, self.run_main("--report", str(source)))
            self.assertEqual(before, source.read_bytes())
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            source = root / "source"
            alias = root / "alias"
            source.write_bytes(b"original")
            try:
                alias.hardlink_to(source)
            except OSError as error:
                self.skipTest(f"hardlink creation unavailable: {type(error).__name__}")
            with mock.patch.object(interop, "SOURCES", (source,)):
                self.assertEqual(2, self.run_main("--report", str(alias)))
            self.assertEqual(b"original", source.read_bytes())

    def run_main(self, *args):
        output = io.StringIO()
        with contextlib.redirect_stdout(output):
            code = interop.main(list(args))
        return code

    def test_exact_pin_clean_snapshot_and_report(self):
        self.assertEqual(
            "57e46850c56079a4e95c645234b696eebbaaaf4f", interop.UPSTREAM_HEAD
        )
        snapshot = interop.read_source_snapshot()
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            report = root / "report.json"
            target = root / "input.json"
            self.assertEqual(
                0, self.run_main("--write-input", str(target), "--report", str(report))
            )
            result = json.loads(report.read_text())
            self.assertEqual(interop.UPSTREAM_HEAD, result["upstreamHead"])
            self.assertEqual("LUO_CONTROL_POSTAUTH_V2", result["profile"])
            self.assertEqual(
                "limited_declared_source_inventory", result["sourceInventoryScope"]
            )
            self.assertEqual(13, len(result["sourceHashes"]))
            self.assertEqual(interop.source_hashes(snapshot), result["sourceHashes"])
            self.assertEqual(
                interop.input_document(snapshot), json.loads(target.read_text())
            )

    def test_head_mismatch_blocks_before_capture_or_execution_without_fresh_input(self):
        with tempfile.TemporaryDirectory() as temporary:
            target = Path(temporary) / "input.json"
            with mock.patch.object(
                interop, "_git", return_value=b"0" * 40
            ), mock.patch.object(
                interop, "read_source_snapshot"
            ) as capture, mock.patch.object(
                interop, "loaded_python"
            ) as execute:
                self.assertEqual(2, self.run_main("--write-input", str(target)))
            capture.assert_not_called()
            execute.assert_not_called()
            self.assertFalse(target.exists())

    def test_dirty_and_unexpected_untracked_paths_block_before_capture(self):
        for status in (b" M server/changed.py\0", b"?? unexpected.txt\0"):
            with self.subTest(status=status), mock.patch.object(
                interop, "_git", side_effect=[interop.UPSTREAM_HEAD.encode(), status]
            ), mock.patch.object(interop, "read_source_snapshot") as capture:
                self.assertEqual(2, self.run_main())
                capture.assert_not_called()

    def test_reviewed_hash_mismatch_and_nonapproved_upstream_blob_mismatch_reject(self):
        snapshot = interop.read_source_snapshot()
        for path in interop.APPROVED_SHA256:
            changed = dict(snapshot)
            changed[path] += b"\n"
            with self.subTest(path=path), self.assertRaisesRegex(
                interop.Blocked, "approved_source_hash_mismatch"
            ):
                interop.require_source_identity(changed)
        changed = dict(snapshot)
        changed[interop.PY_AUTH] += b"\n"
        with self.assertRaisesRegex(interop.Blocked, "upstream_blob_identity_mismatch"):
            interop.require_source_identity(changed)

    def test_head_dirty_and_source_changes_during_operation_block_without_input(self):
        snapshot = interop.read_source_snapshot()
        changed = dict(snapshot)
        changed[interop.TOOL_TEST] += b"\n"
        for failure in ("head", "dirty", "source"):
            with self.subTest(
                failure=failure
            ), tempfile.TemporaryDirectory() as temporary:
                target = Path(temporary) / "input.json"
                revision = [
                    interop.UPSTREAM_HEAD,
                    interop.Blocked(
                        "upstream_head_mismatch"
                        if failure == "head"
                        else "upstream_worktree_dirty"
                    ),
                ]
                with contextlib.ExitStack() as stack:
                    if failure == "source":
                        stack.enter_context(
                            mock.patch.object(
                                interop,
                                "read_source_snapshot",
                                side_effect=[snapshot, changed],
                            )
                        )
                    else:
                        stack.enter_context(
                            mock.patch.object(
                                interop,
                                "require_upstream_revision",
                                side_effect=revision,
                            )
                        )
                    self.assertEqual(2, self.run_main("--write-input", str(target)))
                self.assertFalse(target.exists())

    def test_change_after_input_write_removes_fresh_input(self):
        with tempfile.TemporaryDirectory() as temporary:
            target = Path(temporary) / "input.json"
            with mock.patch.object(
                interop,
                "require_unchanged",
                side_effect=[None, interop.Blocked("declared_source_changed")],
            ):
                self.assertEqual(2, self.run_main("--write-input", str(target)))
            self.assertFalse(target.exists())

    def test_hash_mismatch_preflight_never_executes_or_writes_input(self):
        snapshot = interop.read_source_snapshot()
        snapshot[interop.PY_FRAME] += b"\n"
        with tempfile.TemporaryDirectory() as temporary:
            target = Path(temporary) / "input.json"
            with mock.patch.object(
                interop, "read_source_snapshot", return_value=snapshot
            ), mock.patch.object(interop, "loaded_python") as execute:
                self.assertEqual(2, self.run_main("--write-input", str(target)))
            execute.assert_not_called()
            self.assertFalse(target.exists())

    def test_source_size_and_symlink_limits(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            path = root / "source.py"
            for data in (b"", b"x" * (interop.MAX_SOURCE_BYTES + 1)):
                path.write_bytes(data)
                with self.assertRaises(interop.Blocked):
                    interop._read(path)
            path.write_bytes(b"pass\n")
            with mock.patch.object(Path, "is_symlink", return_value=True):
                with self.assertRaises(interop.Blocked):
                    interop._read(path)

    def test_one_operation_reads_each_required_source_exactly_once(self):
        original = interop._read
        counts = {}

        def once(path):
            counts[path] = counts.get(path, 0) + 1
            if counts[path] > 1:
                raise AssertionError(f"source reread: {path}")
            return original(path)

        with mock.patch.object(interop, "_read", side_effect=once):
            snapshot = interop.read_source_snapshot()
            with interop.loaded_python(snapshot) as modules:
                expected = {
                    "schema": interop.SCHEMA,
                    "cases": interop.build_cases(*modules),
                }
                interop.source_hashes(snapshot)
                interop.compare(document(expected), expected, modules, snapshot)
        self.assertEqual(set(interop.SOURCES), set(counts))
        self.assertTrue(all(value == 1 for value in counts.values()))

    def test_frozen_snapshot_executes_and_hashes_same_bytes_after_disk_reader_changes(
        self,
    ):
        snapshot = interop.read_source_snapshot()
        expected_hash = interop.source_hashes(snapshot)
        with mock.patch.object(
            interop, "_read", side_effect=AssertionError("must not reread")
        ):
            with interop.loaded_python(snapshot) as modules:
                expected = {
                    "schema": interop.SCHEMA,
                    "cases": interop.build_cases(*modules),
                }
                self.assertEqual(expected_hash, interop.source_hashes(snapshot))
                self.assertEqual(
                    [], interop.compare(document(expected), expected, modules, snapshot)
                )

    def test_loader_restores_existing_modules_after_success_and_partial_failure(self):
        names = (
            "src",
            "src.adapter",
            "src.adapter.minecraft",
            "src.adapter.minecraft.control_auth",
            "src.adapter.minecraft.postauth_keys",
            "src.adapter.minecraft.postauth_frame",
        )
        sentinels = {name: object() for name in names}
        saved = {name: __import__("sys").modules.get(name) for name in names}
        modules = __import__("sys").modules
        try:
            for name, value in sentinels.items():
                modules[name] = value
            snapshot = interop.read_source_snapshot()
            with interop.loaded_python(snapshot):
                pass
            for name, value in sentinels.items():
                self.assertIs(modules[name], value)
            broken = dict(snapshot)
            broken[interop.PY_FRAME] = b"raise RuntimeError('injected')"
            with self.assertRaises(RuntimeError):
                with interop.loaded_python(broken):
                    pass
            for name, value in sentinels.items():
                self.assertIs(modules[name], value)
        finally:
            for name, value in saved.items():
                if value is None:
                    modules.pop(name, None)
                else:
                    modules[name] = value

    def test_matrix_covers_roles_directions_kinds_and_negatives(self):
        value = interop.input_document()
        self.assertEqual(18, len(value["cases"]))
        names = {c["name"] for c in value["cases"]}
        for token in (
            "CLIENT_CONTROLLER-SESSION_OPEN",
            "SERVER_AUTHORITY-SESSION_OPEN",
            "CLIENT-NOTICE",
            "SERVER-ROUTE",
            "SERVER-CANDIDATE",
            "bad-deep",
        ):
            self.assertIn(token, names)
        self.assertEqual(
            {
                "CLIENT_CONTROLLER-SESSION_OPEN",
                "SERVER_AUTHORITY-SESSION_OPEN",
                "CLIENT_CONTROLLER-HEARTBEAT-PEER_TO_HUB",
                "CLIENT_CONTROLLER-HEARTBEAT-HUB_TO_PEER",
                "SERVER_AUTHORITY-HEARTBEAT-PEER_TO_HUB",
                "SERVER_AUTHORITY-HEARTBEAT-HUB_TO_PEER",
                "CLIENT-NOTICE",
                "SERVER-ROUTE",
                "SERVER-CANDIDATE",
            },
            {case["name"] for case in value["cases"] if case["accepted"]},
        )
        self.assertEqual(
            {
                "bad-bom",
                "bad-duplicate",
                "bad-unknown",
                "bad-bool-seq",
                "bad-float-seq",
                "bad-mac-padding",
                "bad-payload-padding",
                "bad-tamper",
                "bad-deep",
            },
            {case["name"] for case in value["cases"] if not case["accepted"]},
        )

    def test_actual_python_outputs_are_self_decodable(self):
        snapshot = interop.read_source_snapshot()
        value = interop.input_document(snapshot)
        with interop.loaded_python(snapshot) as (auth, keys_mod, frame_mod):
            for case in value["cases"]:
                if not case["accepted"]:
                    continue
                transcript = interop.from_transcript(keys_mod, auth, case["transcript"])
                context = frame_mod.TrustedFrameContext(
                    authenticated=transcript, connection_id=case["currentConnectionId"]
                )
                owner = keys_mod.PostAuthSessionKeys(
                    transcript, bytes.fromhex(case["pskHex"])
                )
                parsed = frame_mod.parse_frame(
                    bytes.fromhex(case["pythonRawHex"]),
                    context,
                    owner,
                    case["direction"],
                )
                self.assertEqual(case["frame"], interop.frame_json(parsed))

    def test_matching_external_document_is_unattested_and_mismatch_failed(self):
        expected = interop.input_document()
        value = document(expected)
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "java.json"
            path.write_text(json.dumps(value), encoding="utf-8")
            java = interop.load_java(path, expected)
            self.assertEqual([], interop.compare(java, expected))
            value["cases"][0]["javaMacHex"] = "00" * 32
            path.write_text(json.dumps(value), encoding="utf-8")
            self.assertTrue(
                interop.compare(interop.load_java(path, expected), expected)
            )

    def test_strict_java_schema_duplicate_names_metadata_missing_symlink_oversize(self):
        expected = interop.input_document()
        base = document(expected)
        mutations = []
        value = copy.deepcopy(base)
        value["extra"] = 1
        mutations.append(value)
        value = copy.deepcopy(base)
        value["cases"][0]["extra"] = 1
        mutations.append(value)
        value = copy.deepcopy(base)
        value["cases"][1]["name"] = value["cases"][0]["name"]
        mutations.append(value)
        value = copy.deepcopy(base)
        value["invocation"]["forcedRerunRequired"] = 1
        mutations.append(value)
        value = copy.deepcopy(base)
        value["loadedClasses"]["PostAuthSessionKeys"]["sha256"] = "AB" * 32
        mutations.append(value)
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            path = root / "java.json"
            for value in mutations:
                path.write_text(json.dumps(value), encoding="utf-8")
                with self.assertRaises(interop.Blocked):
                    interop.load_java(path, expected)
            path.write_bytes(b"x" * (interop.MAX_RESULT + 1))
            with self.assertRaises(interop.Blocked):
                interop.load_java(path, expected)
            target = root / "target.json"
            target.write_text(json.dumps(base), encoding="utf-8")
            link = root / "link.json"
            try:
                link.symlink_to(target)
            except OSError:
                return
            with self.assertRaises(interop.Blocked):
                interop.load_java(link, expected)

    def test_duplicate_json_key_and_oserror_output_are_sanitized(self):
        expected = interop.input_document()
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "java.json"
            path.write_text('{"schema":"a","schema":"b"}', encoding="utf-8")
            with self.assertRaises(interop.Blocked):
                interop.load_java(path, expected)
        secret = "secret C:\\private"
        output = io.StringIO()
        errors = io.StringIO()
        with mock.patch.object(
            Path, "is_file", side_effect=OSError(secret)
        ), contextlib.redirect_stdout(output), contextlib.redirect_stderr(errors):
            code = interop.main([])
        self.assertEqual(2, code)
        self.assertNotIn(secret, output.getvalue() + errors.getvalue())


if __name__ == "__main__":
    unittest.main()
