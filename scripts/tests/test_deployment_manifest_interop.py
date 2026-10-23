import copy
import contextlib
import importlib.util
import io
import json
import tempfile
import unittest
from pathlib import Path
from unittest import mock


SCRIPT = Path(__file__).resolve().parents[1] / "minecraft" / "verify_deployment_manifest_interop.py"
SPEC = importlib.util.spec_from_file_location("deployment_manifest_interop", SCRIPT)
interop = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(interop)


def java_document(expected):
    import hashlib
    cases = []
    for case in expected["cases"]:
        item = {"name": case["name"], "accepted": case["accepted"]}
        if case["accepted"]:
            item.update(
                encodedRawHex=case["rawHex"],
                configHashHex=hashlib.sha256(bytes.fromhex(case["rawHex"])).hexdigest(),
                trustedVerified=case["trustedVerified"],
            )
        cases.append(item)
    return {
        "schema": interop.SCHEMA,
        "invocation": {
            "testClass": interop.JAVA_TEST_FQCN,
            "forcedRerunRequired": True,
            "executionAttestation": "SEPARATE_RUNNER_OBSERVATION_REQUIRED",
        },
        "loadedClassIdentity": {
            "resource": "file:/dummy/io/github/jinyiwei2012/luosandbox/security/ControlDeploymentManifest.class",
            "sha256": "ab" * 32,
        },
        "cases": cases,
    }


class DeploymentManifestInteropHarnessTest(unittest.TestCase):
    def test_case_matrix_has_both_roles_boundaries_and_negative_corpus(self):
        document = interop.input_document()
        names = {case["name"] for case in document["cases"]}
        self.assertEqual(22, len(document["cases"]))
        for name in ("client", "server-max-id", "id-129", "escaped-equivalent",
                     "trusted-binding-mismatch"):
            self.assertIn(name, names)

    def test_python_actual_module_accepts_and_rejects_declared_cases(self):
        document = interop.input_document()
        with interop.loaded_python_manifest() as module:
            for case in document["cases"]:
                raw = bytes.fromhex(case["rawHex"])
                if case["accepted"]:
                    self.assertEqual(raw, module.parse_deployment_manifest(raw).raw_bytes)
                else:
                    with self.assertRaises(module.DeploymentManifestError):
                        module.parse_deployment_manifest(raw)

    def test_well_formed_matching_java_document_remains_unattested(self):
        expected = interop.input_document()
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "java.json"
            path.write_text(json.dumps(java_document(expected)), encoding="utf-8")
            java = interop.load_java_result(path, expected)
            self.assertEqual([], interop.compare_java(java, expected))
            with mock.patch.object(interop, "input_document", return_value=expected):
                output = io.StringIO()
                with contextlib.redirect_stdout(output):
                    code = interop.main(["--java-result", str(path)])
            self.assertEqual(0, code)
            self.assertIn("JAVA_DOCUMENT_MATCHED_UNATTESTED", output.getvalue())

    def test_absent_java_is_not_executed_and_mismatch_is_failed(self):
        expected = interop.input_document()
        with tempfile.TemporaryDirectory() as temporary:
            missing = Path(temporary) / "missing.json"
            result = self._main_json(["--java-result", str(missing)])
            self.assertEqual("BLOCKED", result["status"])
            document = java_document(expected)
            document["cases"][0]["encodedRawHex"] = "00"
            path = Path(temporary) / "mismatch.json"
            path.write_text(json.dumps(document), encoding="utf-8")
            java = interop.load_java_result(path, expected)
            self.assertTrue(interop.compare_java(java, expected))

    def test_java_schema_metadata_and_cases_are_strict_fail_closed(self):
        expected = interop.input_document()
        base = java_document(expected)
        mutations = []
        for section in (None, "invocation", "loadedClassIdentity"):
            value = copy.deepcopy(base)
            (value if section is None else value[section])["unknown"] = "x"
            mutations.append(value)
        value = copy.deepcopy(base); value["cases"][0]["unknown"] = "x"; mutations.append(value)
        value = copy.deepcopy(base); value["cases"].pop(); mutations.append(value)
        value = copy.deepcopy(base); value["cases"][1]["name"] = value["cases"][0]["name"]; mutations.append(value)
        for field, bad in (("sha256", "AB" * 32), ("sha256", "a" * 63),
                           ("resource", ""), ("resource", "http://bad/ControlDeploymentManifest.class")):
            value = copy.deepcopy(base); value["loadedClassIdentity"][field] = bad; mutations.append(value)
        for field, bad in (("testClass", "Wrong"), ("forcedRerunRequired", 1),
                           ("forcedRerunRequired", False), ("executionAttestation", "EXECUTED")):
            value = copy.deepcopy(base); value["invocation"][field] = bad; mutations.append(value)
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "java.json"
            for value in mutations:
                path.write_text(json.dumps(value), encoding="utf-8")
                with self.assertRaises(interop.Blocked):
                    interop.load_java_result(path, expected)

    def test_duplicate_json_keys_malformed_symlink_and_oversized_are_blocked(self):
        expected = interop.input_document()
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            duplicate = root / "duplicate.json"
            duplicate.write_text('{"schema":"x","schema":"y"}', encoding="utf-8")
            malformed = root / "malformed.json"
            malformed.write_text("{", encoding="utf-8")
            oversized = root / "oversized.json"
            oversized.write_bytes(b"x" * (interop.MAX_JAVA_JSON_BYTES + 1))
            for path in (duplicate, malformed, oversized):
                with self.assertRaises(interop.Blocked):
                    interop.load_java_result(path, expected)
            target = root / "target.json"
            target.write_text(json.dumps(java_document(expected)), encoding="utf-8")
            link = root / "link.json"
            try:
                link.symlink_to(target)
            except OSError:
                return
            with self.assertRaises(interop.Blocked):
                interop.load_java_result(link, expected)

    def test_source_and_output_oserrors_are_sanitized(self):
        secret = "secret C:\\private"
        output, errors = io.StringIO(), io.StringIO()
        with mock.patch.object(Path, "is_file", side_effect=OSError(secret)):
            with contextlib.redirect_stdout(output), contextlib.redirect_stderr(errors):
                code = interop.main([])
        self.assertEqual(2, code)
        self.assertNotIn(secret, output.getvalue() + errors.getvalue())
        with mock.patch.object(interop, "input_document", return_value={"schema": interop.SCHEMA, "cases": []}), \
             mock.patch.object(interop, "source_hashes", return_value={}), \
             mock.patch.object(Path, "write_text", side_effect=OSError(secret)):
            output, errors = io.StringIO(), io.StringIO()
            with contextlib.redirect_stdout(output), contextlib.redirect_stderr(errors):
                code = interop.main(["--report", "report.json"])
        self.assertEqual(2, code)
        self.assertNotIn(secret, output.getvalue() + errors.getvalue())

    @staticmethod
    def _main_json(args):
        output = io.StringIO()
        with contextlib.redirect_stdout(output):
            interop.main(args)
        return json.loads(output.getvalue())


if __name__ == "__main__":
    unittest.main()
