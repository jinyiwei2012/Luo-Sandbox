"""Mock-only runner tests; no native launch or filesystem credential reads."""

import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import Mock, patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "minecraft"))
import verify_java_loader_native as runner

SID = "010100000000000512000000"


class LoaderNativeRunnerTests(unittest.TestCase):
    def test_publication_failures_are_specific_and_primary_error_is_retained(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory).resolve()
            process = Mock()
            process.pid = 4
            process.wait.side_effect = runner.Error("OUTPUT_QUOTA_EXCEEDED")
            process.tree.force_killed = True
            process.tree.failure = None
            process.reader.failure = None
            process.tree.snapshot.return_value = {"forceKilled": True}
            original = runner.exclusive
            for target in ("report.json", "artifacts.sha256"):

                def write(path, value):
                    if path.name == target:
                        raise OSError("specific write failure")
                    return original(path, value)

                output = root / target.replace(".", "-")
                with patch.object(
                    runner, "gates", return_value=((SID, SID, SID), {})
                ), patch.object(
                    runner.helper, "LoggedProcess", return_value=process
                ), patch.object(
                    runner, "sources", return_value={}
                ), patch.object(
                    runner.helper, "clean", return_value="a" * 40
                ), patch.object(
                    runner, "exclusive", side_effect=write
                ):
                    result = runner.run(root, output, "a" * 40, SID, SID, SID)
                self.assertEqual("OUTPUT_QUOTA_EXCEEDED", result["failure"])
                self.assertEqual("EVIDENCE_WRITE_FAILED", result["evidenceFailure"])
                self.assertEqual("FAILED", result["status"])

    def test_post_link_temp_cleanup_failure_does_not_contradict_published_file(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory).resolve() / "report.json"
            with patch.object(Path, "unlink", side_effect=OSError("temp unlink")):
                runner.exclusive(path, b"published")
            self.assertEqual(b"published", path.read_bytes())

    def test_wait_throws_fresh_malformed_xml_still_archived_after_cleanup(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory).resolve()
            xml = root / runner.XML
            xml.parent.mkdir(parents=True)
            process = Mock()
            process.pid = 4

            def waited(timeout):
                xml.write_bytes(b"<malformed after timeout")
                raise runner.Error("OWNED_TREE_WAIT_TIMEOUT")

            process.wait.side_effect = waited
            process.tree.force_killed = True
            process.tree.failure = None
            process.reader.failure = None
            process.tree.snapshot.return_value = {"forceKilled": True}
            with patch.object(
                runner, "gates", return_value=((SID, SID, SID), {})
            ), patch.object(
                runner.helper, "LoggedProcess", return_value=process
            ), patch.object(
                runner, "sources", return_value={}
            ), patch.object(
                runner.helper, "clean", return_value="a" * 40
            ):
                result = runner.run(root, root / "out", "a" * 40, SID, SID, SID)
            self.assertEqual("OWNED_TREE_WAIT_TIMEOUT", result["failure"])
            self.assertEqual(
                b"<malformed after timeout", (root / "out/fresh-junit.xml").read_bytes()
            )
            process.finish.assert_called_once()

    def test_named_identity_binds_current_build_and_actual_jar_entries(self):
        import hashlib
        import zipfile

        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory).resolve() / "class space café"
            root.mkdir()
            out = root / "out"
            out.mkdir()
            props = {
                "nonce": "abc",
                "profile": runner.PROFILE,
                "layout": runner.LAYOUT,
                "classCount": str(len(runner.CLASS_NAMES)),
            }
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
                props[key] = "test"
            jars = []
            hashes = []
            for i in range(2):
                jar = root / f"jna-{i}.jar"
                jars.append(jar)
                with zipfile.ZipFile(jar, "w") as archive:
                    archive.writestr(
                        runner.CLASS_NAMES[i].replace(".", "/") + ".class",
                        f"class-{i}".encode(),
                    )
                hashes.append(hashlib.sha256(jar.read_bytes()).hexdigest())
                props.update(
                    {
                        f"jarPath.{i}": str(jar),
                        f"jarSize.{i}": str(jar.stat().st_size),
                        f"jarSha.{i}": hashes[i],
                        f"jarUse.{i}": (
                            "USED_CORE"
                            if i == 0
                            else "AVAILABLE_RESOURCE_NOT_NATIVE_PLATFORM_BINDING"
                        ),
                    }
                )
            for i, name in enumerate(runner.CLASS_NAMES):
                raw = f"class-{i}".encode()
                relative = name.replace(".", "/") + ".class"
                (out / f"loader-class-{i}.bin").write_bytes(raw)
                if i < 2:
                    url = "jar:" + jars[i].as_uri() + "!/" + relative
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
                    path = root / f"bridge/forge/build/classes/java/{tree}" / relative
                    path.parent.mkdir(parents=True, exist_ok=True)
                    path.write_bytes(raw)
                    url = path.as_uri()
                props.update(
                    {
                        f"className.{i}": name,
                        f"resource.{i}": url,
                        f"classSha.{i}": hashlib.sha256(raw).hexdigest(),
                    }
                )

            def write(values):
                (out / "loader-runtime.properties").write_text(
                    "\n".join(f"{k}={v}" for k, v in values.items()), encoding="latin-1"
                )

            with patch.object(runner, "JAR_HASHES", tuple(hashes)):
                write(props)
                self.assertEqual(
                    "abc", runner.validate_identity(out, "abc", root)["nonce"]
                )
                # Java URL.toExternalForm emits file:/C:/..., unlike Path.as_uri's file:///C:/...
                from urllib.parse import urlsplit

                java_props = dict(props)
                for i in range(len(runner.CLASS_NAMES)):
                    value = props[f"resource.{i}"]
                    if i < 2:
                        inner, entry = value[4:].split("!/", 1)
                        java_props[f"resource.{i}"] = (
                            "jar:file:" + urlsplit(inner).path + "!/" + entry
                        )
                    else:
                        java_props[f"resource.{i}"] = "file:" + urlsplit(value).path
                write(java_props)
                self.assertEqual(
                    "abc", runner.validate_identity(out, "abc", root)["nonce"]
                )
                for value in (
                    "file://remote/C:/wrong.class",
                    "file://localhost/C:/wrong.class",
                    "https://host/class",
                    "file:/C:/wrong%ZZ.class",
                    "file:/C:/wrong%FF.class",
                    "file:/C:/wrong.class?query",
                    "file:/C:/wrong.class#fragment",
                    "file:C:/relative.class",
                    "file:/C:/../wrong.class",
                    "file:/C:/wrong%2fentry.class",
                    "jar:file:/C:/wrong.jar!/"
                    + runner.CLASS_NAMES[0].replace(".", "/")
                    + ".class",
                    props["resource.0"] + "/extra",
                    props["resource.0"].replace("Native.class", "../Native.class"),
                ):
                    bad = dict(java_props)
                    index = 0 if value.startswith("jar:") else 3
                    bad[f"resource.{index}"] = value
                    write(bad)
                    with self.assertRaises(runner.Error):
                        runner.validate_identity(out, "abc", root)
                for key in (
                    "className.3",
                    "resource.4",
                    "jarUse.1",
                    "classCount",
                    "classSha.5",
                ):
                    bad = dict(props)
                    bad[key] = "wrong"
                    write(bad)
                    with self.assertRaises(runner.Error):
                        runner.validate_identity(out, "abc", root)
                bad = dict(props)
                bad["className.99"] = "extra"
                write(bad)
                with self.assertRaises(runner.Error):
                    runner.validate_identity(out, "abc", root)
                write(props)
                (out / "loader-class-4.bin").write_bytes(b"wrong")
                with self.assertRaises(runner.Error):
                    runner.validate_identity(out, "abc", root)

    def test_nonzero_missing_result_fresh_xml_is_archived_and_process_failure_primary(
        self,
    ):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory).resolve()
            xml = root / runner.XML
            xml.parent.mkdir(parents=True)
            process = Mock()
            process.pid = 3

            def waited(timeout):
                xml.write_bytes(b"<broken fresh xml")
                return 1

            process.wait.side_effect = waited
            process.tree.force_killed = False
            process.tree.failure = None
            process.reader.failure = None
            process.tree.snapshot.return_value = {"forceKilled": False}
            with patch.object(
                runner, "gates", return_value=((SID, SID, SID), {})
            ), patch.object(
                runner.helper, "LoggedProcess", return_value=process
            ), patch.object(
                runner, "sources", return_value={}
            ), patch.object(
                runner.helper, "clean", return_value="a" * 40
            ):
                result = runner.run(root, root / "output", "a" * 40, SID, SID, SID)
            self.assertEqual("JAVA_PROCESS_FAILED", result["failure"])
            self.assertEqual(
                b"<broken fresh xml", (root / "output/fresh-junit.xml").read_bytes()
            )
            self.assertEqual(
                "FRESH_XML_OBSERVATION_UNATTRIBUTED", result["xmlProvenance"]
            )
            process.finish.assert_called_once()

    def test_duplicate_result_not_default_skip_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory).resolve() / "suite.xml"
            record = f"JAVA_LOADER_RESULT abc BLOCKED_PREREQUISITE profile={runner.PROFILE};layout={runner.LAYOUT};scope=TEST_ONLY_DUMMY"
            path.write_text(
                f'<testsuite name="{runner.CLASS}" tests="1" failures="0" errors="0" skipped="0"><testcase name="dummyFiveFileLoader()"/><system-out>{record}\n{record}</system-out></testsuite>',
                encoding="utf-8",
            )
            with self.assertRaises(runner.Error):
                runner.validate_xml(path, 0, "abc")

    def test_atomic_evidence_never_overwrites_existing(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory).resolve() / "report.json"
            runner.exclusive(path, b"first")
            with self.assertRaises(OSError):
                runner.exclusive(path, b"second")
            self.assertEqual(b"first", path.read_bytes())

    def test_output_existing_alias_and_dirty_source_fail_before_launch(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory).resolve()
            with patch.object(runner, "APPROVED", root), patch.object(
                runner.helper, "clean", return_value="b" * 40
            ):
                with self.assertRaises(runner.Error):
                    runner.gates(root, root, "a" * 40, SID, SID, SID)
                with self.assertRaises(runner.Error):
                    runner.gates(root, root / "new", "a" * 40, SID, SID, SID)
            with patch.object(
                runner.helper,
                "safe_path",
                side_effect=runner.Error("PATH_ALIAS_OR_REPARSE"),
            ), patch.object(runner.helper, "LoggedProcess") as launch:
                with self.assertRaises(runner.Error):
                    runner.gates(root, root / "new", "a" * 40, SID, SID, SID)
                launch.assert_not_called()

    def test_default_skipped_and_duplicate_result_xml_never_native_pass(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory).resolve() / "case.xml"
            path.write_text(
                f'<testsuite name="{runner.CLASS}" tests="1" failures="0" errors="0" skipped="1"><testcase name="dummyFiveFileLoader()"><skipped/></testcase></testsuite>',
                encoding="utf-8",
            )
            with self.assertRaises(runner.Error):
                runner.validate_xml(path, 0, "abc")

    def test_trust_missing_invalid_duplicate_before_any_process(self):
        for value in (None, "", "00", SID + "," + SID):
            with self.assertRaises(runner.Error):
                runner.sid_set(value)
        self.assertEqual(SID, runner.sid_set(SID))

    def test_gate_failure_has_zero_effects(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory).resolve()
            output = root / "new"
            with patch.object(
                runner, "gates", side_effect=runner.Error("DIRTY")
            ), patch.object(runner.helper, "LoggedProcess") as launch:
                with self.assertRaises(runner.Error):
                    runner.run(root, output, "a" * 40, SID, SID, SID)
            launch.assert_not_called()
            self.assertFalse(output.exists())

    def test_exact_selector_and_fresh_single_nonce_result(self):
        self.assertEqual(runner.CLASS, runner.command(Path("C:/sandbox"))[3])
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory).resolve() / "case.xml"
            for status in runner.STATUSES:
                raw = f'<testsuite name="{runner.CLASS}" tests="1" failures="0" errors="0" skipped="0"><testcase name="dummyFiveFileLoader()"/><system-out>JAVA_LOADER_RESULT abc {status} profile={runner.PROFILE};layout={runner.LAYOUT};scope=TEST_ONLY_DUMMY</system-out></testsuite>'
                path.write_text(raw, encoding="utf-8")
                self.assertEqual(status, runner.validate_xml(path, 0, "abc")[1])
                with self.assertRaises(runner.Error):
                    runner.validate_xml(path, 0, "wrong")
                with self.assertRaises(runner.Error):
                    runner.validate_xml(path, path.stat().st_mtime_ns + 1, "abc")

    def test_constructor_unknown_and_forcekill_never_pass(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory).resolve()
            failure = runner.Error("CONSTRUCTOR_UNKNOWN")
            failure.native_cleanup = {
                "ownedPid": 7,
                "cleanupUnknown": True,
                "forceKilled": True,
            }
            with patch.object(
                runner, "gates", return_value=((SID, SID, SID), {})
            ), patch.object(
                runner.helper, "LoggedProcess", side_effect=failure
            ), patch.object(
                runner, "sources", return_value={}
            ), patch.object(
                runner.helper, "clean", return_value="a" * 40
            ):
                result = runner.run(root, root / "new", "a" * 40, SID, SID, SID)
            self.assertEqual("FAILED", result["status"])
            self.assertEqual(7, result["nativeCleanup"]["ownedPid"])

    def test_timeout_quota_cleanup_and_report_failure(self):
        for category in ("OUTPUT_QUOTA_EXCEEDED", "OWNED_TREE_WAIT_TIMEOUT"):
            with tempfile.TemporaryDirectory() as directory:
                root = Path(directory).resolve()
                process = Mock()
                process.pid = 12
                process.wait.side_effect = runner.Error(category)
                process.tree.force_killed = True
                process.tree.failure = None
                process.reader.failure = None
                process.tree.snapshot.return_value = {"forceKilled": True}
                with patch.object(
                    runner, "gates", return_value=((SID, SID, SID), {})
                ), patch.object(
                    runner.helper, "LoggedProcess", return_value=process
                ), patch.object(
                    runner, "exclusive", side_effect=OSError("write")
                ), patch.object(
                    runner, "sources", return_value={}
                ), patch.object(
                    runner.helper, "clean", return_value="a" * 40
                ):
                    result = runner.run(root, root / "new", "a" * 40, SID, SID, SID)
                process.finish.assert_called_once()
                self.assertEqual("FAILED", result["status"])
                self.assertEqual(category, result["failure"])
                self.assertEqual("EVIDENCE_WRITE_FAILED", result["evidenceFailure"])


if __name__ == "__main__":
    unittest.main()
