"""Executable specification model for selected post-auth v2 draft state rules.

TEST-ONLY: this is not production transport code, a wire parser, cryptographic
evidence, or proof that an existing transport implements the draft.
"""

from __future__ import annotations

import json
import unittest
from dataclasses import dataclass


PROFILE = "LUO_CONTROL_POSTAUTH_V2"
I63_MAX = (1 << 63) - 1
OPEN_TIMEOUT = 5.0
ALLOWED_KINDS = {
    "SESSION_OPEN",
    "HEARTBEAT",
    "BODY_BIND_NOTICE",
    "BODY_BIND_ROUTE",
    "BODY_BOUND_CANDIDATE",
}
ALLOWED_DIRECTIONS = {
    "SESSION_OPEN": {"HUB_TO_PEER"},
    "HEARTBEAT": {"PEER_TO_HUB", "HUB_TO_PEER"},
    "BODY_BIND_NOTICE": {"PEER_TO_HUB"},
    "BODY_BIND_ROUTE": {"HUB_TO_PEER"},
    "BODY_BOUND_CANDIDATE": {"PEER_TO_HUB"},
}

# Human-reviewable executable-spec transition table. Tests exercise every row.
TRANSITIONS = (
    ("PEER_READY", "valid SESSION_OPEN", "PEER_OPEN + enqueue ACK seq0"),
    ("HUB_WAIT_ACK", "valid ACK seq0 {}", "APPLICATION_READY"),
    ("HUB_WAIT_ACK", "BODY_BIND_ROUTE request", "REJECTED_NOT_READY"),
    ("OPEN", "atomic queue admission", "sequence assigned + immutable append"),
    ("OPEN", "precommit failure", "no sequence consumed; explicit test policy"),
    ("OPEN", "post-admission uncertainty", "fence + queue clear before close"),
    ("ANY", "monotonic rollback", "permanent exact-session fence"),
    ("REPLACEMENT", "old generation callback", "ignored"),
)


class FakeClock:
    def __init__(self) -> None:
        self.value = 100.0

    def now(self) -> float:
        return self.value

    def advance(self, seconds: float) -> None:
        self.value += seconds

    def rollback(self, seconds: float) -> None:
        self.value -= seconds


@dataclass(frozen=True)
class Frame:
    generation: int
    direction: str
    sequence: int
    kind: str
    payload: bytes
    encoded_size: int


class FakeSender:
    """Synchronous controllable sender; callbacks are invoked only by the test."""

    def __init__(self) -> None:
        self.sent: list[Frame] = []
        self.pending: list[tuple[Frame, object]] = []
        self.reenter = None

    def send(self, frame: Frame, callback) -> None:
        self.sent.append(frame)
        self.pending.append((frame, callback))
        if self.reenter is not None:
            action, self.reenter = self.reenter, None
            action()

    def complete(self, outcome: str = "success", index: int = 0) -> None:
        _, callback = self.pending.pop(index)
        callback(outcome)


class Session:
    """Abstract state owner using verified-message booleans, not MAC operations."""

    def __init__(
        self,
        name: str,
        clock: FakeClock,
        sender: FakeSender,
        *,
        capacity: int = 2,
        precommit_failure_closes: bool = False,
    ) -> None:
        if capacity not in (2, 4):
            raise ValueError("test capacity must be 2 or 4")
        self.name = name
        self.clock = clock
        self.sender = sender
        self.capacity = capacity  # Test parameter; not a proposed production limit.
        self.precommit_failure_closes = precommit_failure_closes
        self.generation = 1
        self.alive = True
        self.role_owned = True
        self.routes_owned = True
        self.close_effect = False
        self.events: list[str] = []
        self.queue: list[Frame] = []
        self.inflight: Frame | None = None
        self.next_sequence = {"PEER_TO_HUB": 0, "HUB_TO_PEER": 0}
        self.last_monotonic = clock.now()
        self.state = "AUTHENTICATED"
        self.connection_id: str | None = None
        self.challenge_id = "challenge-current"
        self.peer_open_deadline: float | None = None
        self.hub_ack_deadline: float | None = None

    def observe_time(self) -> bool:
        now = self.clock.now()
        if now < self.last_monotonic:
            self.fatal("monotonic_rollback")
            return False
        self.last_monotonic = now
        if self.peer_open_deadline is not None and now >= self.peer_open_deadline:
            self.fatal("peer_open_timeout")
            return False
        if self.hub_ack_deadline is not None and now >= self.hub_ack_deadline:
            self.fatal("hub_ack_timeout")
            return False
        return self.alive

    def start_peer_wait_open(self) -> None:
        self.state = "PEER_READY"
        self.peer_open_deadline = self.clock.now() + OPEN_TIMEOUT

    def start_hub_session_open(self, connection_id: str) -> Frame | None:
        self.connection_id = connection_id
        frame = self.admit(
            "HUB_TO_PEER", "SESSION_OPEN", f"SESSION_OPEN:{connection_id}".encode(), 256
        )
        if frame is not None:
            self.state = "HUB_WAIT_ACK"
            self.hub_ack_deadline = self.clock.now() + OPEN_TIMEOUT
        return frame

    def receive_session_open(
        self,
        *,
        verified: bool,
        generation: int,
        challenge_id: str,
        connection_id: str,
        exact_payload: bool,
    ) -> bool:
        if not self.observe_time():
            return False
        if (self.state != "PEER_READY" or not verified or generation != self.generation
                or challenge_id != self.challenge_id or not exact_payload or not connection_id):
            self.fatal("invalid_session_open")
            return False
        self.connection_id = connection_id  # First adoption of the Hub-assigned ID.
        self.peer_open_deadline = None
        self.state = "PEER_OPEN"
        ack = self.admit("PEER_TO_HUB", "HEARTBEAT", b"{}", 180)
        return ack is not None and ack.sequence == 0

    def receive_open_ack(
        self,
        *,
        verified: bool,
        generation: int,
        connection_id: str,
        challenge_id: str,
        sequence: int,
        payload: bytes,
        direction: str = "PEER_TO_HUB",
        profile: str = PROFILE,
        kind: str = "HEARTBEAT",
    ) -> bool:
        if not self.observe_time():
            return False
        valid = (
            self.state == "HUB_WAIT_ACK"
            and verified
            and generation == self.generation
            and connection_id == self.connection_id
            and challenge_id == self.challenge_id
            and sequence == 0
            and payload == b"{}"
            and direction == "PEER_TO_HUB"
            and profile == PROFILE
            and kind == "HEARTBEAT"
        )
        if not valid:
            self.fatal("invalid_open_ack")
            return False
        self.hub_ack_deadline = None
        self.state = "APPLICATION_READY"
        return True

    def queue_peer_business(self, payload: bytes = b"notice") -> Frame | None:
        if self.state != "PEER_OPEN":
            self.fatal("business_before_session_open")
            return None
        return self.admit("PEER_TO_HUB", "BODY_BIND_NOTICE", payload, 240)

    def queue_body_route(self) -> Frame | None:
        if self.state != "APPLICATION_READY":
            self.events.append("route_rejected_not_ready")
            return None
        return self.admit("HUB_TO_PEER", "BODY_BIND_ROUTE", b"route", 240)

    def admit(
        self,
        direction: str,
        kind: str,
        payload: bytes,
        encoded_size: int,
        *,
        generation: int | None = None,
        encode_ok: bool = True,
    ) -> Frame | None:
        owner_generation = self.generation if generation is None else generation
        provisional = self.next_sequence.get(direction)
        if not self.alive or owner_generation != self.generation:
            self.events.append("preadmission_rejected")
            return None
        if not encode_ok or encoded_size > 4096:
            self.events.append("construction_rejected")
            if self.precommit_failure_closes:
                self.fatal("construction_policy_close")
            return None
        if kind not in ALLOWED_KINDS:
            self.fatal("unknown_kind")
            return None
        if direction not in self.next_sequence or direction not in ALLOWED_DIRECTIONS[kind]:
            self.fatal("wrong_direction")
            return None
        if len(self.queue) >= self.capacity:
            self.fatal("queue_full")
            return None
        if provisional is None or provisional > I63_MAX:
            self.fatal("sequence_overflow")
            return None
        frame = Frame(owner_generation, direction, provisional, kind, payload, encoded_size)
        self.queue.append(frame)  # Atomic commit point in this single-owner model.
        self.next_sequence[direction] = provisional + 1
        self.events.append(f"admitted:{direction}:{provisional}:{kind}")
        self._drain()
        return frame

    def _drain(self) -> None:
        if not self.alive or self.inflight is not None or not self.queue:
            return
        frame = self.queue[0]
        self.inflight = frame
        captured_generation = frame.generation

        def completed(outcome: str) -> None:
            if captured_generation != self.generation:
                self.events.append("stale_completion_ignored")
                return
            if not self.alive or self.inflight != frame:
                return
            if outcome != "success":
                self.fatal(f"post_admission_{outcome}")
                return
            self.queue.pop(0)
            self.inflight = None
            self.events.append(f"sent:{frame.direction}:{frame.sequence}:{frame.kind}")
            self._drain()

        self.sender.send(frame, completed)

    def fatal(self, reason: str) -> None:
        if not self.alive:
            return
        self.role_owned = False
        self.events.append("role_removed")
        self.routes_owned = False
        self.events.append("routes_fenced")
        self.queue.clear()
        self.inflight = None
        self.alive = False
        self.events.append(f"closed:{reason}")
        self.close_effect = True

    def reconnect(self, connection_id: str) -> None:
        self.generation += 1
        self.alive = True
        self.role_owned = True
        self.routes_owned = True
        self.close_effect = False
        self.queue = []
        self.inflight = None
        self.next_sequence = {"PEER_TO_HUB": 0, "HUB_TO_PEER": 0}
        self.last_monotonic = self.clock.now()
        self.state = "AUTHENTICATED"
        self.connection_id = connection_id
        self.peer_open_deadline = None
        self.hub_ack_deadline = None
        self.events.append(f"replacement:{self.generation}")


class PostAuthV2ExecutableSpecTest(unittest.TestCase):
    """Deterministic tests of abstract state outcomes, not production implementation tests."""

    def setUp(self) -> None:
        self.clock = FakeClock()

    def test_transition_table_is_exercised_and_explicitly_test_only(self):
        self.assertEqual(8, len(TRANSITIONS))
        self.assertIn("explicit test policy", " ".join(item[2] for item in TRANSITIONS))
        self.assertIn("TEST-ONLY", __doc__)

    def test_session_open_first_adopts_hub_id_and_ack_precedes_peer_business(self):
        sender = FakeSender()
        peer = Session("peer", self.clock, sender, capacity=4)
        peer.start_peer_wait_open()
        self.assertEqual([], sender.sent)
        self.assertTrue(peer.receive_session_open(
            verified=True, generation=1, challenge_id="challenge-current",
            connection_id="hub-id-1", exact_payload=True))
        business = peer.queue_peer_business()
        self.assertEqual("hub-id-1", peer.connection_id)
        self.assertEqual(1, business.sequence)
        self.assertEqual(["HEARTBEAT"], [frame.kind for frame in sender.sent])
        sender.complete("success")
        self.assertEqual(["HEARTBEAT", "BODY_BIND_NOTICE"], [frame.kind for frame in sender.sent])

    def test_hub_application_ready_only_after_exact_valid_ack_and_route_is_gated(self):
        sender = FakeSender()
        hub = Session("hub", self.clock, sender)
        hub.start_hub_session_open("hub-id-1")
        self.assertIsNone(hub.queue_body_route())
        self.assertEqual("HUB_WAIT_ACK", hub.state)
        self.assertTrue(hub.receive_open_ack(
            verified=True, generation=1, connection_id="hub-id-1",
            challenge_id="challenge-current", sequence=0, payload=b"{}"))
        self.assertEqual("APPLICATION_READY", hub.state)
        self.assertIsNotNone(hub.queue_body_route())

    def test_invalid_ack_dimensions_each_close_exact_session(self):
        mutations = (
            {"verified": False}, {"generation": 2}, {"connection_id": "other"},
            {"challenge_id": "other"}, {"sequence": 1}, {"payload": b"{ }"},
            {"direction": "HUB_TO_PEER"}, {"profile": "alias"}, {"kind": "UNKNOWN"},
        )
        defaults = dict(verified=True, generation=1, connection_id="hub-id-1",
                        challenge_id="challenge-current", sequence=0, payload=b"{}")
        for mutation in mutations:
            with self.subTest(mutation=mutation):
                hub = Session("hub", self.clock, FakeSender())
                hub.start_hub_session_open("hub-id-1")
                self.assertFalse(hub.receive_open_ack(**(defaults | mutation)))
                self.assertFalse(hub.alive)

    def test_separate_exact_boundary_deadlines_and_monotonic_rollback_fence(self):
        peer = Session("peer", self.clock, FakeSender())
        peer.start_peer_wait_open()
        self.clock.advance(OPEN_TIMEOUT)
        self.assertFalse(peer.observe_time())
        self.assertIn("closed:peer_open_timeout", peer.events)

        self.clock = FakeClock()
        hub = Session("hub", self.clock, FakeSender())
        hub.start_hub_session_open("hub-id")
        self.clock.advance(OPEN_TIMEOUT)
        self.assertFalse(hub.observe_time())
        self.assertIn("closed:hub_ack_timeout", hub.events)

        self.clock = FakeClock()
        session = Session("rollback", self.clock, FakeSender())
        self.clock.advance(1)
        self.assertTrue(session.observe_time())
        self.clock.rollback(2)
        self.assertFalse(session.observe_time())
        self.clock.advance(20)
        self.assertFalse(session.observe_time())

    def test_atomic_admission_precommit_failures_do_not_consume_sequence(self):
        for closes in (False, True):
            for kwargs in ({"encode_ok": False}, {"encoded_size": 4097}):
                with self.subTest(closes=closes, kwargs=kwargs):
                    session = Session("pipe", self.clock, FakeSender(), capacity=2,
                                      precommit_failure_closes=closes)
                    parameters = dict(direction="PEER_TO_HUB", kind="HEARTBEAT",
                                      payload=b"{}", encoded_size=100)
                    parameters.update(kwargs)
                    self.assertIsNone(session.admit(**parameters))
                    self.assertEqual(0, session.next_sequence["PEER_TO_HUB"])
                    self.assertEqual(not closes, session.alive)
                    if not closes:
                        accepted = session.admit(
                            "PEER_TO_HUB", "HEARTBEAT", b"{}", 4096)
                        self.assertEqual(0, accepted.sequence)

    def test_unknown_kind_wrong_direction_and_queue_full_are_mandatory_fatal(self):
        for closes in (False, True):
            mandatory_cases = (
                ("unknown_kind", lambda session: session.admit(
                    "PEER_TO_HUB", "UNKNOWN", b"x", 10)),
                ("wrong_direction", lambda session: session.admit(
                    "HUB_TO_PEER", "BODY_BOUND_CANDIDATE", b"candidate", 10)),
                ("queue_full", self._fill_then_overflow),
            )
            for reason, action in mandatory_cases:
                with self.subTest(closes=closes, reason=reason):
                    session = Session("mandatory", self.clock, FakeSender(), capacity=2,
                                      precommit_failure_closes=closes)
                    before = session.next_sequence.copy()
                    if reason == "queue_full":
                        before = {"PEER_TO_HUB": 2, "HUB_TO_PEER": 0}
                    self.assertIsNone(action(session))
                    self.assertEqual(before, session.next_sequence)
                    self._assert_fenced_before_close(session, reason)

    @staticmethod
    def _fill_then_overflow(session: Session) -> Frame | None:
        session.admit("PEER_TO_HUB", "HEARTBEAT", b"{}", 10)
        session.admit("PEER_TO_HUB", "BODY_BIND_NOTICE", b"a", 10)
        return session.admit("PEER_TO_HUB", "BODY_BIND_NOTICE", b"b", 10)

    def test_queue_capacity_is_explicit_test_parameter_and_candidate_direction_is_allowed(self):
        for capacity in (2, 4):
            with self.subTest(capacity=capacity):
                session = Session("bounded", self.clock, FakeSender(), capacity=capacity)
                candidate = session.admit(
                    "PEER_TO_HUB", "BODY_BOUND_CANDIDATE", b"candidate", 10)
                self.assertIsNotNone(candidate)
                self.assertEqual(0, candidate.sequence)

    def test_construction_failure_policy_remains_selectable(self):
        for closes in (False, True):
            with self.subTest(closes=closes):
                session = Session("bounded", self.clock, FakeSender(), capacity=2,
                                  precommit_failure_closes=closes)
                self.assertIsNone(session.admit(
                    "PEER_TO_HUB", "HEARTBEAT", b"{}", 10, encode_ok=False))
                self.assertEqual(0, session.next_sequence["PEER_TO_HUB"])
                self.assertEqual(not closes, session.alive)

    def test_single_inflight_ordered_drain_and_independent_direction_sequences(self):
        sender = FakeSender()
        session = Session("ordered", self.clock, sender, capacity=4)
        a = session.admit("PEER_TO_HUB", "HEARTBEAT", b"{}", 10)
        b = session.admit("HUB_TO_PEER", "HEARTBEAT", b"{}", 10)
        c = session.admit("PEER_TO_HUB", "BODY_BIND_NOTICE", b"n", 10)
        self.assertEqual((0, 0, 1), (a.sequence, b.sequence, c.sequence))
        self.assertEqual([a], sender.sent)
        sender.complete()
        self.assertEqual([a, b], sender.sent)
        sender.complete()
        self.assertEqual([a, b, c], sender.sent)

    def test_post_admission_failures_fence_and_clear_before_close_effect(self):
        for outcome in ("exception", "cancelled", "unknown"):
            with self.subTest(outcome=outcome):
                sender = FakeSender()
                session = Session("failure", self.clock, sender, capacity=4)
                session.admit("PEER_TO_HUB", "HEARTBEAT", b"{}", 10)
                session.admit("PEER_TO_HUB", "BODY_BIND_NOTICE", b"n", 10)
                sender.complete(outcome)
                self.assertEqual([], session.queue)
                self.assertFalse(session.role_owned)
                self.assertFalse(session.routes_owned)
                self.assertLess(session.events.index("role_removed"),
                                session.events.index(f"closed:post_admission_{outcome}"))
                self.assertLess(session.events.index("routes_fenced"),
                                session.events.index(f"closed:post_admission_{outcome}"))

    def test_reentrant_reconnect_makes_old_completion_powerless(self):
        sender = FakeSender()
        session = Session("generation", self.clock, sender)
        sender.reenter = lambda: session.reconnect("replacement-id")
        session.admit("PEER_TO_HUB", "HEARTBEAT", b"{}", 10)
        replacement_snapshot = (session.generation, session.connection_id, session.alive,
                                list(session.queue), session.next_sequence.copy())
        sender.complete("success")
        self.assertEqual(replacement_snapshot,
                         (session.generation, session.connection_id, session.alive,
                          session.queue, session.next_sequence))
        self.assertIn("stale_completion_ignored", session.events)

    def test_stale_enqueue_handle_is_rejected_without_advancing_replacement(self):
        session = Session("stale", self.clock, FakeSender())
        old_generation = session.generation
        session.reconnect("new-id")
        self.assertIsNone(session.admit(
            "PEER_TO_HUB", "HEARTBEAT", b"{}", 10, generation=old_generation))
        self.assertEqual(0, session.next_sequence["PEER_TO_HUB"])
        self.assertTrue(session.alive)

    def test_sequence_overflow_closes_without_wrap_or_reuse(self):
        session = Session("overflow", self.clock, FakeSender())
        session.next_sequence["PEER_TO_HUB"] = I63_MAX
        last = session.admit("PEER_TO_HUB", "HEARTBEAT", b"{}", 10)
        self.assertEqual(I63_MAX, last.sequence)
        self.assertIsNone(session.admit("PEER_TO_HUB", "HEARTBEAT", b"{}", 10))
        self.assertFalse(session.alive)
        self.assertEqual(I63_MAX + 1, session.next_sequence["PEER_TO_HUB"])

    def test_ordering_trace_permutations_have_distinct_fail_closed_outcomes(self):
        traces = {}

        hub = Session("ack-first", self.clock, FakeSender())
        hub.start_hub_session_open("id")
        hub.receive_open_ack(verified=True, generation=1, connection_id="id",
                             challenge_id="challenge-current", sequence=0, payload=b"{}")
        traces["ack_then_route"] = hub.queue_body_route() is not None

        hub = Session("route-first", self.clock, FakeSender())
        hub.start_hub_session_open("id")
        traces["route_before_ack"] = hub.queue_body_route() is not None

        before = Session("disconnect-before", self.clock, FakeSender())
        before.fatal("disconnect")
        traces["disconnect_before_admission"] = before.admit(
            "PEER_TO_HUB", "HEARTBEAT", b"{}", 10) is not None

        sender = FakeSender()
        after = Session("disconnect-after", self.clock, sender)
        after.admit("PEER_TO_HUB", "HEARTBEAT", b"{}", 10)
        after.fatal("disconnect")
        sender.complete("success")
        traces["disconnect_after_queue_before_completion"] = after.alive

        self.assertEqual({
            "ack_then_route": True,
            "route_before_ack": False,
            "disconnect_before_admission": False,
            "disconnect_after_queue_before_completion": False,
        }, traces)
        self.assertEqual(4, len(traces))

    def test_server_send_uncertainty_does_not_close_unrelated_controller(self):
        server_sender = FakeSender()
        server = Session("server", self.clock, server_sender)
        controller = Session("controller", self.clock, FakeSender())
        server.admit("HUB_TO_PEER", "BODY_BIND_ROUTE", b"route", 100)
        server_sender.complete("unknown")
        self.assertFalse(server.alive)
        self.assertTrue(controller.alive)
        self.assertTrue(controller.role_owned)

    def _assert_fenced_before_close(self, session: Session, reason: str) -> None:
        self.assertFalse(session.alive)
        self.assertFalse(session.role_owned)
        self.assertFalse(session.routes_owned)
        self.assertEqual([], session.queue)
        self.assertTrue(session.close_effect)
        close = session.events.index(f"closed:{reason}")
        self.assertLess(session.events.index("role_removed"), close)
        self.assertLess(session.events.index("routes_fenced"), close)


if __name__ == "__main__":
    unittest.main()
