import importlib.util
import tempfile
import time
import unittest
import json
from pathlib import Path
from unittest.mock import Mock, patch

SCRIPT = (
    Path(__file__).resolve().parents[1] / "minecraft/verify_v2_body_route_loopback.py"
)
SPEC = importlib.util.spec_from_file_location("v2_route_loopback", SCRIPT)
route = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(route)


class V2BodyRouteHarnessTest(unittest.TestCase):
    def test_source_closure_includes_route_production_and_test_bridge(self):
        files = route.source_files(Path("C:/agent"), Path("C:/sandbox/bridge/forge"))
        self.assertIn("python:src/adapter/minecraft/v2_body_routes.py", files)
        self.assertIn(
            "java:src/test/java/io/github/jinyiwei2012/luosandbox/transport/PostAuthV2BodyRouteLoopbackTest.java",
            files,
        )
        self.assertIn(
            "harness:scripts/minecraft/verify_v2_body_route_loopback.py", files
        )

    def test_parse_junit_requires_one_exact_route_metric(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "route.xml"
            path.write_text(
                '<testsuite failures="0" errors="0" skipped="0"><testcase name="actualNoticeRouteAndMacValidSyntheticCandidate()"/><system-out>V2_BODY_ROUTE_METRIC {"scenario":"NOTICE_ROUTE_SYNTHETIC_CANDIDATE","candidateCount":1,"rawPayloadMatched":true}</system-out></testsuite>',
                encoding="utf-8",
            )
            value = route.parse_junit(path, time.time() - 1)
            self.assertEqual(1, value["routeMetric"]["candidateCount"])
            self.assertEqual(path.read_bytes(), value["_junitRaw"])

    def test_parse_junit_rejects_unsafe_malformed_wrong_case_and_extra_test(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "route.xml"
            bad_values = (
                ("<!DOCTYPE x><testsuite/>", "JUNIT_UNSAFE_XML"),
                ("<testsuite>", "JUNIT_INVALID_XML"),
                (
                    '<testsuite failures="0" errors="0" skipped="0"><testcase name="wrong"/></testsuite>',
                    "JUNIT_CASE_INVALID",
                ),
                (
                    '<testsuite failures="0" errors="0" skipped="0"><testcase name="actualNoticeRouteAndMacValidSyntheticCandidate()"/><testcase name="extra"/></testsuite>',
                    "JUNIT_NOT_CLEAN",
                ),
            )
            for raw, code in bad_values:
                path.write_text(raw, encoding="utf-8")
                with self.assertRaisesRegex(route.base.VerificationError, code):
                    route.parse_junit(path, time.time() - 1)

    def test_parse_junit_metric_types_and_keys_are_exact(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "route.xml"
            for metric in (
                {
                    "scenario": "NOTICE_ROUTE_SYNTHETIC_CANDIDATE",
                    "candidateCount": True,
                    "rawPayloadMatched": True,
                },
                {
                    "scenario": "NOTICE_ROUTE_SYNTHETIC_CANDIDATE",
                    "candidateCount": 1.0,
                    "rawPayloadMatched": True,
                },
                {
                    "scenario": "NOTICE_ROUTE_SYNTHETIC_CANDIDATE",
                    "candidateCount": "1",
                    "rawPayloadMatched": True,
                },
                {
                    "scenario": "NOTICE_ROUTE_SYNTHETIC_CANDIDATE",
                    "candidateCount": None,
                    "rawPayloadMatched": True,
                },
                {
                    "scenario": "NOTICE_ROUTE_SYNTHETIC_CANDIDATE",
                    "candidateCount": 1,
                    "rawPayloadMatched": 1,
                },
                {
                    "scenario": "NOTICE_ROUTE_SYNTHETIC_CANDIDATE",
                    "candidateCount": 1,
                    "rawPayloadMatched": True,
                    "extra": 1,
                },
            ):
                raw = (
                    '<testsuite failures="0" errors="0" skipped="0">'
                    '<testcase name="actualNoticeRouteAndMacValidSyntheticCandidate()"/>'
                    f"<system-out>V2_BODY_ROUTE_METRIC {json.dumps(metric)}</system-out></testsuite>"
                )
                path.write_text(raw, encoding="utf-8")
                with self.assertRaisesRegex(
                    route.base.VerificationError, "ROUTE_METRIC_INVALID"
                ):
                    route.parse_junit(path, time.time() - 1)

    def test_wrong_head_rejects_before_process_spawn(self):
        completed = Mock(returncode=0, stdout="wrong\n")
        with patch.object(route.base, "_require_prerequisites"), patch.object(
            route.subprocess, "run", return_value=completed
        ), patch.object(route.subprocess, "Popen") as spawn:
            with self.assertRaisesRegex(
                route.base.VerificationError, "UNEXPECTED_UPSTREAM_HEAD"
            ):
                route.run(Path("C:/agent"), Path("C:/forge"), Path("C:/python.exe"))
            spawn.assert_not_called()

    def test_cli_reports_blocked_failed_and_unexpected_symbolically(self):
        with tempfile.TemporaryDirectory() as temporary:
            report = Path(temporary) / "report.json"
            argv = [
                "--agent-server",
                temporary,
                "--forge",
                temporary,
                "--python",
                temporary,
                "--report",
                str(report),
            ]
            for error, status, failure in (
                (
                    route.base.PrerequisiteError("REQUIRED_EXECUTABLE_MISSING"),
                    "BLOCKED",
                    "REQUIRED_EXECUTABLE_MISSING",
                ),
                (
                    route.base.VerificationError("JAVA_ROUTE_TEST_TIMEOUT"),
                    "FAILED",
                    "JAVA_ROUTE_TEST_TIMEOUT",
                ),
                (
                    RuntimeError("raw backend path"),
                    "FAILED",
                    "UNEXPECTED_HARNESS_FAILURE",
                ),
            ):
                with patch.object(route, "run", side_effect=error):
                    self.assertEqual(2, route.main(argv))
                value = json.loads(report.read_text(encoding="utf-8"))
                self.assertEqual(status, value["status"])
                self.assertEqual(failure, value["failure"])
                self.assertNotIn("raw backend path", json.dumps(value))


if __name__ == "__main__":
    unittest.main()
