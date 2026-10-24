"""Offline only: no native processes, sockets, or HTTP."""

import hashlib
import sys
import tempfile
import types
import unittest
from pathlib import Path
from unittest.mock import Mock, patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "minecraft"))
import verify_java_custody_native_smoke as runner


class NativeRunnerTests(unittest.TestCase):
    def test_profile_selection_is_explicit_and_invalid_rejected_before_gates(self):
        self.assertEqual("STRICT_SNAPSHOT_V1", runner.STRICT_PROFILE)
        candidate = "PROTECTED_AUTO_INHERITED_SNAPSHOT_V1"
        self.assertEqual(candidate, runner.assessment_profile(candidate))
        for value in ("", "UNKNOWN", " strict_snapshot_v1", None):
            with patch.object(runner, "gates") as gates:
                with self.assertRaises(runner.Error):
                    runner.run(Path("C:/sandbox"), Path("C:/new"), "a" * 40, value)
                gates.assert_not_called()

    def test_profile_receipt_must_match_request_and_old_schema_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory).resolve() / "suite.xml"
            raw = self.xml()
            path.write_text(raw, encoding="utf-8")
            with self.assertRaises(runner.Error):
                runner.validate_xml(
                    path, 0, "abc", "PROTECTED_AUTO_INHERITED_SNAPSHOT_V1"
                )
            path.write_text(
                raw.replace("profileId=STRICT_SNAPSHOT_V1;", ""), encoding="utf-8"
            )
            with self.assertRaises(runner.Error):
                runner.validate_xml(path, 0, "abc")
            path.write_text(
                raw.replace(
                    "profileId=STRICT_SNAPSHOT_V1",
                    "profileId=PROTECTED_AUTO_INHERITED_SNAPSHOT_V1",
                ),
                encoding="utf-8",
            )
            self.assertEqual(
                "MECHANISM_SMOKE_PASS",
                runner.validate_xml(
                    path, 0, "abc", "PROTECTED_AUTO_INHERITED_SNAPSHOT_V1"
                )[1],
            )

    def test_assessment_schema_rejects_unknown_contradictions_and_impossible_ranges(
        self,
    ):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory).resolve() / "suite.xml"
            for before, after in (
                ("assessment=ACCEPTED", "assessment=UNKNOWN"),
                ("assessment=ACCEPTED", "assessment=ACL_REVISION"),
                ("descriptorBytes=64", "descriptorBytes=true"),
                ("descriptorBytes=64", "descriptorBytes=4097"),
                ("revision=1", "revision=NOT_AVAILABLE"),
                ("control=36868", "control=65536"),
                ("assessment=ACCEPTED", "assessment=ACCEPTED;extra=secret"),
            ):
                path.write_text(self.xml().replace(before, after), encoding="utf-8")
                with self.assertRaises(runner.Error):
                    runner.validate_xml(path, 0, "abc")

    def test_protected_dummy_facts_are_required_not_status_only(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory).resolve() / "suite.xml"
            for changed in (
                "productionProfileChanged=true",
                "testOwnerPolicy=DEPLOYMENT",
                "originalReadbackUnmodified=false",
            ):
                raw = self.xml()
                key = changed.split("=", 1)[0]
                import re

                raw = re.sub(key + r"=[^;<]+", changed, raw)
                path.write_text(raw, encoding="utf-8")
                with self.assertRaises(runner.Error):
                    runner.validate_xml(path, 0, "abc")

    def test_setup_postcondition_and_historical_observed_never_pass(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory).resolve() / "suite.xml"
            for status in (
                "FIXTURE_SETUP_FAILED",
                "FIXTURE_POSTCONDITION_FAILED",
                "OBSERVED",
                "CLEANUP_UNKNOWN",
            ):
                path.write_text(
                    self.xml(("OBSERVED", status, "OBSERVED")), encoding="utf-8"
                )
                with self.assertRaises(runner.Error):
                    runner.validate_xml(path, 0, "abc")

    def test_fabricated_complete_runtime_identity_passes_and_jar_hash_tampering_rejects(
        self,
    ):
        """Synthetic metadata/class bytes, not observed Java/native execution evidence."""
        sandbox = Path(__file__).resolve().parents[2]
        cache = (
            sandbox / ".gradle-user-home/caches/modules-2/files-2.1/net.java.dev.jna"
        )
        approved = (
            ("jna", "91a814ac4f40d60dee91d842e1a8ad874c62197984403d0e3c30d39e55cf53b3"),
            (
                "jna-platform",
                "8ce969116cac95bd61b07a8d5e07174b352e63301473caac72c395e3c08488d2",
            ),
        )
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory).resolve()
            properties = {
                "nonce": "fabricated-unit-nonce",
                "java.vendor": "FABRICATED_UNIT_FIXTURE",
                "java.runtime.version": "FABRICATED_NOT_OBSERVED",
                "os.version": "FABRICATED_NOT_OBSERVED",
                "os.arch": "FABRICATED_NOT_OBSERVED",
                "sun.arch.data.model": "64",
                "pointerBytes": "8",
                "structureSize": "52",
            }
            jars = []
            for index, (name, digest) in enumerate(approved):
                paths = list((cache / name / "5.12.1").glob(f"*/{name}-5.12.1.jar"))
                self.assertEqual(
                    1,
                    len(paths),
                    "Approved offline JAR fixture required; never download",
                )
                raw = runner.read(paths[0].resolve(), 16_777_216)
                self.assertEqual(digest, hashlib.sha256(raw).hexdigest())
                jar = output / f"fabricated-fixture-{index}.jar"
                jar.write_bytes(
                    raw
                )  # Read/hash only, never classload or initialize JNA.
                jars.append(jar)
                properties.update(
                    {
                        f"jarPath.{index}": str(jar),
                        f"jarSize.{index}": str(len(raw)),
                        f"jarSha.{index}": digest,
                    }
                )
            for index in range(5):
                raw = f"fabricated-unit-class-{index}".encode("ascii")
                (output / f"executed-class-{index}.bin").write_bytes(raw)
                properties[f"classSha.{index}"] = hashlib.sha256(raw).hexdigest()
            identity = output / "runtime-identity.properties"

            def write_properties():
                identity.write_text(
                    "\n".join(f"{key}={value}" for key, value in properties.items())
                    + "\n",
                    encoding="ascii",
                )

            write_properties()
            self.assertEqual(
                properties, runner.validate_identity(output, properties["nonce"])
            )
            for index, (_, digest) in enumerate(approved):
                with self.subTest(index=index, mutation="reported-approved-hash"):
                    properties[f"jarSha.{index}"] = "00" * 32
                    write_properties()
                    with self.assertRaisesRegex(runner.Error, "JNA_IDENTITY_MISMATCH"):
                        runner.validate_identity(output, properties["nonce"])
                    properties[f"jarSha.{index}"] = digest
                write_properties()
                with self.subTest(index=index, mutation="artifact-bytes"):
                    original = jars[index].read_bytes()
                    jars[index].write_bytes(bytes([original[0] ^ 1]) + original[1:])
                    with self.assertRaisesRegex(
                        runner.Error, "JNA_ARTIFACT_HASH_MISMATCH"
                    ):
                        runner.validate_identity(output, properties["nonce"])
                    jars[index].write_bytes(original)

    def test_reparse_output_gate_rejects_before_launch_or_evidence_overwrite(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory).resolve()
            output = root / "existing-evidence"
            output.mkdir()
            evidence = output / "report.json"
            evidence.write_bytes(b"original-evidence")
            original_lstat = Path.lstat
            observed = []

            def reparse_lstat(path, *args, **kwargs):
                info = original_lstat(path, *args, **kwargs)
                if path == output:
                    observed.append(path)
                    return types.SimpleNamespace(
                        st_file_attributes=0x400, st_mode=info.st_mode
                    )
                return info

            with patch.object(Path, "lstat", reparse_lstat), patch.object(
                runner.process_helper, "LoggedProcess"
            ) as launch, patch.object(runner, "publish_evidence") as publish:
                with self.assertRaisesRegex(runner.Error, "PATH_ALIAS_OR_REPARSE"):
                    runner.run(root, output, "a" * 40)
                launch.assert_not_called()
                publish.assert_not_called()
            self.assertTrue(
                observed, "Actual safe_path reparse attribute branch must execute"
            )
            self.assertEqual(b"original-evidence", evidence.read_bytes())
            self.assertEqual([evidence], list(output.iterdir()))

    def test_manifest_failure_publishes_only_blocked_report_last(self):
        import json

        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory).resolve()
            original = runner.exclusive

            def write(path, raw):
                if path.name == "artifacts.sha256":
                    raise OSError("manifest")
                return original(path, raw)

            result = {"status": "MECHANISM_SMOKE_PASS"}
            with patch.object(runner, "exclusive", side_effect=write):
                runner.publish_evidence(output, result)
            self.assertEqual(
                "BLOCKED", json.loads((output / "report.json").read_text())["status"]
            )

    def test_exclusive_publication_never_overwrites_and_rejects_alias(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory).resolve() / "protected"
            path.write_bytes(b"original")
            with self.assertRaises(OSError):
                runner.exclusive(path, b"replacement")
            self.assertEqual(b"original", path.read_bytes())
            with patch.object(
                runner.process_helper,
                "safe_path",
                side_effect=runner.Error("PATH_ALIAS_OR_REPARSE"),
            ):
                with self.assertRaises(runner.Error):
                    runner.exclusive(path, b"wrong")

    def test_missing_or_wrong_runtime_identity_rejects(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory).resolve()
            with self.assertRaises(runner.Error):
                runner.validate_identity(output, "abc")
            (output / "runtime-identity.properties").write_text(
                "nonce=abc\njarSha.0=wrong\n", encoding="ascii"
            )
            with self.assertRaises(runner.Error):
                runner.validate_identity(output, "abc")

    def test_source_drift_blocks_after_process_cleanup(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory).resolve()
            process = Mock()
            process.pid = 9
            process.wait.return_value = 0
            process.tree.force_killed = False
            process.tree.failure = None
            process.reader.failure = None
            process.tree.snapshot.return_value = {"forceKilled": False}
            with patch.object(
                runner, "gates", return_value={"old": "hash"}
            ), patch.object(
                runner.process_helper, "LoggedProcess", return_value=process
            ), patch.object(
                runner,
                "validate_xml",
                return_value=(b"xml", "MECHANISM_SMOKE_PASS", {}),
            ), patch.object(
                runner,
                "validate_identity",
                return_value={"assessmentProfile": runner.STRICT_PROFILE},
            ), patch.object(
                runner, "sources", return_value={"changed": "hash"}
            ):
                result = runner.run(root, root / "new", "a" * 40)
            process.finish.assert_called_once()
            self.assertEqual("SOURCE_OR_HEAD_DRIFT", result["failure"])
            self.assertEqual("BLOCKED", result["status"])

    def test_constructor_unknown_preserves_pid_and_skips_junit(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory).resolve()
            failure = runner.Error("NATIVE_CONSTRUCTION_FAILED")
            failure.native_cleanup = {
                "ownedPid": 88,
                "cleanupUnknown": True,
                "forceKilled": True,
            }
            with patch.object(runner, "gates", return_value={}), patch.object(
                runner.process_helper, "LoggedProcess", side_effect=failure
            ), patch.object(runner, "validate_xml") as xml:
                result = runner.run(root, root / "new", "a" * 40)
            self.assertEqual(88, result["nativeCleanup"]["ownedPid"])
            self.assertEqual("BLOCKED", result["status"])
            xml.assert_not_called()

    def test_report_and_manifest_failure_are_bounded_and_do_not_mask_reason(self):
        with tempfile.TemporaryDirectory() as directory:
            result = {"status": "BLOCKED", "failure": "ORIGINAL"}
            with patch.object(
                runner, "exclusive", side_effect=OSError("write")
            ) as writes:
                runner.publish_evidence(Path(directory).resolve(), result)
            self.assertEqual(2, writes.call_count)
            self.assertEqual("ORIGINAL", result["failure"])
            self.assertEqual("EVIDENCE_WRITE_FAILED", result["evidenceFailure"])

    def test_unsupported_is_legal_only_for_descriptor_case(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory).resolve() / "suite.xml"
            for states in (
                ("DESCRIPTOR_PROFILE_UNSUPPORTED", "OBSERVED", "OBSERVED"),
                ("OBSERVED", "OBSERVED", "DESCRIPTOR_PROFILE_UNSUPPORTED"),
            ):
                path.write_text(self.xml(states), encoding="utf-8")
                with self.assertRaises(runner.Error):
                    runner.validate_xml(path, 0, "abc")

    def xml(
        self,
        statuses=("OBSERVED", "PROTECTED_DUMMY_DESCRIPTOR_ACCEPTED", "OBSERVED"),
        nonce="abc",
    ):
        cases = "".join(f'<testcase name="{name}"/>' for name in runner.CASES)
        records = "\n".join(
            f"JAVA_CUSTODY_MECHANISM {nonce} {i} {status} "
            + (
                "fixture=NEW_DUMMY_PROTECTED_DACL;productionProfileChanged=false;testOwnerPolicy=FIXTURE_ONLY;originalReadbackUnmodified=true;profileId=STRICT_SNAPSHOT_V1;assessment="
                + (
                    "ACCEPTED"
                    if status == "PROTECTED_DUMMY_DESCRIPTOR_ACCEPTED"
                    else "ACL_REVISION"
                )
                + ";descriptorBytes=64;revision=1;control=36868"
                if i == 1
                else "safe"
            )
            for i, status in enumerate(statuses)
        )
        return f'<testsuite name="{runner.CLASS}" tests="3" failures="0" errors="0" skipped="0">{cases}<system-out>{records}</system-out></testsuite>'

    def test_selector_is_exact_and_offline(self):
        args = runner.command(Path("C:/sandbox"))
        self.assertEqual(runner.CLASS, args[args.index("--tests") + 1])
        self.assertIn("--rerun-tasks", args)
        self.assertIn("--offline", args)

    def test_exact_fresh_nonce_and_unsupported_are_not_custody_pass(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory).resolve() / "suite.xml"
            path.write_text(self.xml(), encoding="utf-8")
            self.assertEqual(
                "MECHANISM_SMOKE_PASS", runner.validate_xml(path, 0, "abc")[1]
            )
            with self.assertRaises(runner.Error):
                runner.validate_xml(path, 0, "wrong")
            with self.assertRaises(runner.Error):
                runner.validate_xml(path, path.stat().st_mtime_ns + 1, "abc")
            path.write_text(
                self.xml(("OBSERVED", "DESCRIPTOR_PROFILE_UNSUPPORTED", "OBSERVED")),
                encoding="utf-8",
            )
            self.assertEqual("BLOCKED_PROFILE", runner.validate_xml(path, 0, "abc")[1])
            path.write_text(
                self.xml(("CLEANUP_UNKNOWN", "BLOCKED_BY_PRIOR_UNKNOWN", "OBSERVED")),
                encoding="utf-8",
            )
            with self.assertRaises(runner.Error):
                runner.validate_xml(path, 0, "abc")

    def test_gate_failure_has_zero_launch_and_output_effects(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "new"
            with patch.object(
                runner, "gates", side_effect=runner.Error("DIRTY")
            ), patch.object(runner.process_helper, "LoggedProcess") as launch:
                with self.assertRaises(runner.Error):
                    runner.run(Path(directory), output, "0" * 40)
                launch.assert_not_called()
                self.assertFalse(output.exists())

    def test_actual_gates_require_clean_head_and_exclusive_output(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory).resolve()
            with patch.object(runner, "APPROVED", root), patch.object(
                runner.process_helper, "clean", return_value="a" * 40
            ), patch.object(runner, "sources", return_value={}):
                with self.assertRaises(runner.Error):
                    runner.gates(root, root, "a" * 40)
                with self.assertRaises(runner.Error):
                    runner.gates(root, root / "new", "b" * 40)
                self.assertEqual({}, runner.gates(root, root / "new", "a" * 40))

    def test_duplicate_results_and_default_skips_reject(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory).resolve() / "suite.xml"
            raw = self.xml()
            path.write_text(
                raw.replace(
                    "</system-out>",
                    "\nJAVA_CUSTODY_MECHANISM abc 0 OBSERVED\n</system-out>",
                ),
                encoding="utf-8",
            )
            with self.assertRaises(runner.Error):
                runner.validate_xml(path, 0, "abc")
            path.write_text(raw.replace('skipped="0"', 'skipped="3"'), encoding="utf-8")
            with self.assertRaises(runner.Error):
                runner.validate_xml(path, 0, "abc")

    def test_forced_quota_or_cleanup_failure_never_passes(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory).resolve()
            process = Mock()
            process.pid = 1
            process.wait.side_effect = runner.Error("OUTPUT_QUOTA_EXCEEDED")
            process.tree.force_killed = True
            process.tree.failure = None
            process.tree.snapshot.return_value = {"forceKilled": True}
            process.reader.failure = "quota"
            with patch.object(runner, "gates", return_value={}), patch.object(
                runner.process_helper, "LoggedProcess", return_value=process
            ):
                result = runner.run(root, root / "out", "0" * 40)
            self.assertEqual("BLOCKED", result["status"])
            self.assertTrue(result["forceKilled"])
            process.finish.assert_called_once()


if __name__ == "__main__":
    unittest.main()
