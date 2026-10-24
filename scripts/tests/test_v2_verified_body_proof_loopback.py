import importlib.util
import json
import tempfile
import time
import unittest
from pathlib import Path
from unittest.mock import patch

SCRIPT = (
    Path(__file__).resolve().parents[1]
    / "minecraft/verify_v2_verified_body_proof_loopback.py"
)
SPEC = importlib.util.spec_from_file_location("verified_proof", SCRIPT)
proof = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(proof)


class VerifiedBodyProofHarnessTest(unittest.TestCase):
    def test_closure_contains_actual_proof_bridge_dependencies(self):
        files = proof.source_files(Path("C:/agent"), Path("C:/forge"))
        for key in (
            "java:src/main/java/io/github/jinyiwei2012/luosandbox/security/BodyProofV2CandidateBridge.java",
            "java:src/main/java/io/github/jinyiwei2012/luosandbox/security/BodyProofIngress.java",
            "java:src/main/java/io/github/jinyiwei2012/luosandbox/security/PendingBodyProof.java",
            "java:src/test/java/io/github/jinyiwei2012/luosandbox/transport/PostAuthV2VerifiedBodyProofLoopbackTest.java",
        ):
            self.assertIn(key, files)

    def test_metric_types_are_exact(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "result.xml"
            metric = {
                "scenario": "VERIFIED_LP11_NOTICE_ROUTE_CANDIDATE",
                "mode": "OBSERVATIONAL",
                "proofVerified": True,
                "proofConsumed": True,
                "candidateCount": 1,
                "rawPayloadMatched": True,
                "expiryOriginal": True,
            }
            raw = (
                '<testsuite failures="0" errors="0" skipped="0"><testcase name="verifiedLp11NoticeRouteCandidate()"/><system-out>V2_VERIFIED_BODY_PROOF_METRIC '
                + json.dumps(metric)
                + "</system-out></testsuite>"
            )
            path.write_text(raw, encoding="utf-8")
            self.assertEqual(
                1,
                proof.parse_junit(path, time.time() - 1)["proofMetric"][
                    "candidateCount"
                ],
            )
            metric["candidateCount"] = True
            path.write_text(
                raw.replace('"candidateCount": 1', '"candidateCount": true'),
                encoding="utf-8",
            )
            with self.assertRaises(proof.base.base.VerificationError):
                proof.parse_junit(path, time.time() - 1)

    def test_each_false_observed_boolean_is_rejected(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "result.xml"
            for field in (
                "proofVerified",
                "proofConsumed",
                "rawPayloadMatched",
                "expiryOriginal",
            ):
                metric = {
                    "scenario": "VERIFIED_LP11_NOTICE_ROUTE_CANDIDATE",
                    "mode": "OBSERVATIONAL",
                    "proofVerified": True,
                    "proofConsumed": True,
                    "candidateCount": 1,
                    "rawPayloadMatched": True,
                    "expiryOriginal": True,
                }
                metric[field] = False
                raw = (
                    '<testsuite failures="0" errors="0" skipped="0">'
                    '<testcase name="verifiedLp11NoticeRouteCandidate()"/>'
                    f"<system-out>V2_VERIFIED_BODY_PROOF_METRIC {json.dumps(metric)}</system-out></testsuite>"
                )
                path.write_text(raw, encoding="utf-8")
                with self.assertRaises(proof.base.base.VerificationError):
                    proof.parse_junit(path, time.time() - 1)

    def test_proof_status_schema_and_types_are_strict(self):
        value = {
            "nonAuthoritativeCandidateCount": 0,
            "routeNoticeStructureValid": True,
            "routeNoticeSha256": ["a" * 64],
            "coordinatorPresent": False,
        }
        with patch.object(proof, "build_opener") as opener:
            response = opener.return_value.open.return_value.__enter__.return_value
            response.read.return_value = json.dumps(value).encode()
            self.assertEqual(value, proof.read_proof_status(1))
            for changed in (
                {**value, "nonAuthoritativeCandidateCount": False},
                {**value, "routeNoticeStructureValid": 1},
                {**value, "routeNoticeSha256": ["short"]},
                {**value, "extra": 1},
            ):
                response.read.return_value = json.dumps(changed).encode()
                with self.assertRaises(proof.base.base.VerificationError):
                    proof.read_proof_status(1)

    def test_headless_metric_and_status_are_strict(self):
        metric = {
            "scenario": "VERIFIED_LP11_NOTICE_ROUTE_CANDIDATE",
            "mode": "HEADLESS_ADMISSION",
            "proofVerified": True,
            "proofConsumed": True,
            "candidateCount": 1,
            "rawPayloadMatched": True,
            "expiryOriginal": True,
            "admissionCount": 1,
            "pendingCount": 1,
            "ticketCount": 1,
            "prepareCount": 1,
            "workerOutstandingCount": 1,
            "pendingPhase": "PREPARE",
            "pendingCommitted": False,
            "activePresent": False,
            "ingressPresent": False,
            "stageScopePresent": False,
            "bindingCommitCount": 0,
            "productionCapabilityVerified": False,
            "platformReason": "LOCK_ONLY_NOT_PRODUCTION_CAPABILITY",
        }
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "result.xml"
            path.write_text(
                '<testsuite failures="0" errors="0" skipped="0">'
                '<testcase name="verifiedLp11NoticeRouteCandidate()"/>'
                f"<system-out>V2_VERIFIED_BODY_PROOF_METRIC {json.dumps(metric)}</system-out></testsuite>",
                encoding="utf-8",
            )
            self.assertEqual(
                1,
                proof.parse_junit(path, time.time() - 1, "HEADLESS_ADMISSION")[
                    "proofMetric"
                ]["prepareCount"],
            )
            metric["productionCapabilityVerified"] = True
            path.write_text(
                '<testsuite failures="0" errors="0" skipped="0">'
                '<testcase name="verifiedLp11NoticeRouteCandidate()"/>'
                f"<system-out>V2_VERIFIED_BODY_PROOF_METRIC {json.dumps(metric)}</system-out></testsuite>",
                encoding="utf-8",
            )
            with self.assertRaises(proof.base.base.VerificationError):
                proof.parse_junit(path, time.time() - 1, "HEADLESS_ADMISSION")


if __name__ == "__main__":
    unittest.main()
