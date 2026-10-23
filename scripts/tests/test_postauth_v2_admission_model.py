"""Deterministic executable-spec model for draft v2 admission and dispatch.

TEST ONLY: this model is not production coordinator/asyncio/store code and does
not prove that any existing runtime implements the non-normative, not-frozen
draft. Cryptography, wire parsing, SQLite IO, and actual epoch allocation are
outside this model. READY before an UNDISPATCHED ticket is conservatively
rejected and fenced as a test precondition because the draft only specifies
early READY retention while DISPATCHING; this does not freeze that wire case.
"""

from __future__ import annotations

import unittest
from dataclasses import dataclass, replace


ROUTE_TTL = 5.0
ROUTE_CAPACITY = 2
PENDING_CAPACITY = 8

TRANSITIONS = (
    ("PREFLIGHT", "current immutable result", "FINAL_ADMISSION"),
    ("FINAL_ADMISSION", "all exact checks pass", "PENDING + UNDISPATCHED"),
    ("UNDISPATCHED", "dispatch current", "DISPATCHING"),
    ("DISPATCHING", "one matching early READY", "RETAINED_UNAUTHORIZED"),
    ("DISPATCHING", "send returns current", "DELIVERED_CURRENT"),
    ("DELIVERED_CURRENT", "validated READY + CAS", "WOULD_COMMIT"),
    ("ANY", "disconnect/replacement/failure", "SYNC_FENCE then async close"),
    ("OLD_TICKET", "completion after replacement", "IGNORED"),
)


class FakeClock:
    def __init__(self) -> None:
        self.monotonic = 50.0
        self.utc_valid = True

    def advance(self, seconds: float) -> None:
        self.monotonic += seconds


@dataclass(frozen=True)
class Connection:
    install_id: str
    connection_id: str
    identity: object


@dataclass
class Route:
    route_id: str
    tuple_value: tuple[str, ...]
    controller: Connection
    server: Connection
    deadline: float
    state: str = "ROUTED"
    terminal_at: float | None = None


class RouteTable:
    """Global capacity includes live records and unexpired terminal tombstones."""

    def __init__(self, clock: FakeClock) -> None:
        self.clock = clock
        self.records: dict[str, Route] = {}

    def prune(self) -> None:
        expired = []
        for key, value in self.records.items():
            if self.clock.monotonic >= value.deadline:
                if value.state == "ROUTED":
                    value.state = "INVALIDATED"
                    value.terminal_at = self.clock.monotonic
                expired.append(key)
        for key in expired:
            del self.records[key]

    def create(self, route_id: str, tuple_value: tuple[str, ...], controller: Connection,
               server: Connection) -> Route | None:
        self.prune()
        if len(self.records) >= ROUTE_CAPACITY or route_id in self.records:
            return None
        route = Route(route_id, tuple_value, controller, server,
                      self.clock.monotonic + ROUTE_TTL)
        self.records[route_id] = route
        return route

    def terminal(self, route: Route, state: str) -> None:
        route.state = state
        route.terminal_at = self.clock.monotonic


@dataclass(eq=False)
class Job:
    job_id: str
    state: str
    identity: object
    route_id: str
    route_identity: object
    coordinator_generation: int
    store_identity: object
    store_generation: int
    server_identity: object
    controller_identity: object
    unavailable: bool = False
    completed: bool = False


class StoreWorker:
    """One abstract worker: one running plus one queued, no IO or threads."""

    def __init__(self, owner_complete=None, logical_outstanding=None,
                 *, affinity_verified: bool = True) -> None:
        self.affinity_verified = affinity_verified
        self.owner_complete = owner_complete
        self.logical_outstanding = logical_outstanding or (lambda: len(self.jobs))
        self.jobs: list[Job] = []
        self.worker_count = 1
        self.rejected = 0

    def submit(self, job_id: str, binding: dict | None = None) -> Job | None:
        if (not self.affinity_verified or len(self.jobs) >= 2
                or self.logical_outstanding() >= 2):
            self.rejected += 1
            return None
        binding = binding or {}
        job = Job(
            job_id, "RUNNING" if not self.jobs else "QUEUED", object(),
            binding.get("route_id", ""), binding.get("route_identity", object()),
            binding.get("coordinator_generation", -1), binding.get("store_identity", object()),
            binding.get("store_generation", -1), binding.get("server_identity", object()),
            binding.get("controller_identity", object()),
        )
        self.jobs.append(job)
        return job

    def make_unavailable(self, job: Job) -> None:
        job.unavailable = True  # Timeout/cancel/obsolete does not release a slot.

    def complete(self, job: Job, overrides: dict | None = None) -> "Snapshot | None":
        if (not self.jobs or self.jobs[0] is not job or job.completed
                or job.state != "RUNNING"):
            return None
        self.jobs.pop(0)
        job.completed = True
        if self.jobs:
            if self.jobs[0].state != "QUEUED":
                raise AssertionError("next worker job must be queued")
            self.jobs[0].state = "RUNNING"
        if job.unavailable or self.owner_complete is None:
            return None
        return self.owner_complete(job, overrides or {})


@dataclass(frozen=True)
class Snapshot:
    identity: object
    store_identity: object
    provision_id: str
    body_id: str
    expected_epoch: int
    store_generation: int
    next_epoch: int
    coordinator_generation: int
    route_id: str
    server_identity: object
    controller_identity: object


@dataclass
class Pending:
    identity: object
    route_id: str
    tuple_value: tuple[str, ...]
    phase: str = "PREPARE"


@dataclass
class Ready:
    pending_identity: object
    value: str


@dataclass
class Ticket:
    identity: object
    pending_identity: object
    coordinator_generation: int
    server_identity: object
    controller_identity: object
    route_id: str
    state: str = "UNDISPATCHED"
    retained_ready: Ready | None = None
    retries: int = 0


class AdmissionModel:
    """Single-owner-turn state machine; every method is synchronous/manual."""

    def __init__(self, clock: FakeClock) -> None:
        self.clock = clock
        self.routes = RouteTable(clock)
        self.worker = StoreWorker(self._complete_preflight, self._logical_outstanding)
        self.store_identity = object()
        self.store_generation = 1
        self.provision_id = "provision"
        self.expected_epoch = 7
        self.store_available = True
        self.quarantined = False
        self.generation = 1
        self.server = Connection("server", "server-connection", object())
        self.controller = Connection("controller", "controller-connection", object())
        self.server_current = True
        self.controller_current = True
        self.coordinator_authority = True
        self.pending: dict[object, Pending] = {}
        self.tickets: dict[object, Ticket] = {}
        self.issued_snapshots: dict[object, Snapshot] = {}
        self.events: list[str] = []
        self.send_count = 0
        self.would_commit_count = 0
        self.epoch_write_count = 0
        self.close_effects = 0

    def snapshot(self, route: Route, *, identity: object | None = None) -> Snapshot:
        return Snapshot(
            identity or object(), self.store_identity, self.provision_id, route.tuple_value[2],
            self.expected_epoch, self.store_generation, self.expected_epoch + 1,
            self.generation, route.route_id,
            route.server.identity, route.controller.identity,
        )

    def _logical_outstanding(self) -> int:
        return len(self.worker.jobs) + len(self.issued_snapshots)

    def submit_preflight(self, route: Route, job_id: str = "preflight") -> Job | None:
        self.prune_obsolete_capabilities()
        return self.worker.submit(job_id, {
            "route_id": route.route_id,
            "route_identity": route,
            "coordinator_generation": self.generation,
            "store_identity": self.store_identity,
            "store_generation": self.store_generation,
            "server_identity": self.server.identity,
            "controller_identity": self.controller.identity,
        })

    def prune_obsolete_capabilities(self) -> None:
        self.routes.prune()
        self.issued_snapshots = {
            identity: snapshot for identity, snapshot in self.issued_snapshots.items()
            if (snapshot.route_id in self.routes.records
                and snapshot.coordinator_generation == self.generation)
        }

    def cancel_snapshot(self, snapshot: Snapshot) -> None:
        registered = self.issued_snapshots.get(snapshot.identity)
        if registered is snapshot:
            self.issued_snapshots.pop(snapshot.identity, None)
            route = self.routes.records.get(snapshot.route_id)
            if route is not None and route.state == "ROUTED":
                self.routes.terminal(route, "INVALIDATED")

    def _invalidate_route_capabilities(self, route: Route) -> None:
        stale = [
            identity for identity, snapshot in self.issued_snapshots.items()
            if snapshot.route_id == route.route_id
        ]
        for identity in stale:
            self.issued_snapshots.pop(identity, None)

    def _complete_preflight(self, job: Job, overrides: dict) -> Snapshot | None:
        route = self.routes.records.get(job.route_id)
        current = (
            route is job.route_identity
            and route is not None
            and route.state == "ROUTED"
            and self.clock.monotonic < route.deadline
            and job.coordinator_generation == self.generation
            and job.store_identity is self.store_identity
            and job.store_generation == self.store_generation
            and job.server_identity is self.server.identity
            and job.controller_identity is self.controller.identity
            and self.server_current
            and self.controller_current
            and self.coordinator_authority
        )
        if not current:
            return None
        value = self.snapshot(route)
        if overrides:
            value = replace(value, **overrides)
        self.issued_snapshots[value.identity] = value
        return value

    def accept_notice(self, route_id: str, tuple_value: tuple[str, ...],
                      authenticated_controller_identity: object) -> Route | None:
        if (not self.controller_current
                or authenticated_controller_identity is not self.controller.identity):
            self.events.append("notice_rejected_not_current_controller")
            return None
        route = self.routes.create(route_id, tuple_value, self.controller, self.server)
        if route is not None:
            return route
        # Draft-mandatory global-capacity response: exact Controller loss and local fence.
        self.controller_current = False
        self.coordinator_authority = False
        self.generation += 1
        self.fence_local("global_route_capacity_controller_loss")
        self.tickets.clear()
        self.events.append("controller_close_effect")
        self.close_effects += 1
        return None

    def admit_prepare(self, route: Route, snapshot: Snapshot, *, utc_cap_valid: bool = True,
                      external_precheck=None) -> Ticket | None:
        # Reentrant/external checks are pre-phase only; final turn revalidates everything.
        if external_precheck is not None:
            external_precheck()
        owned_route = self.routes.records.get(route.route_id)
        registered = self.issued_snapshots.get(snapshot.identity)
        correlated_capability = registered is not None and registered.route_id == route.route_id
        if owned_route is not route:
            if owned_route is not None and owned_route.state == "ROUTED":
                self.routes.terminal(owned_route, "INVALIDATED")
                self._invalidate_route_capabilities(owned_route)
            elif correlated_capability:
                self.issued_snapshots.pop(snapshot.identity, None)
            self.events.append("detached_route_rejected")
            return None
        issued_snapshot = registered
        valid = (
            self.store_available
            and not self.quarantined
            and self.coordinator_authority
            and self.server_current
            and self.controller_current
            and issued_snapshot is snapshot
            and snapshot.store_identity is self.store_identity
            and snapshot.provision_id == self.provision_id
            and type(snapshot.expected_epoch) is int
            and snapshot.expected_epoch >= 0
            and snapshot.expected_epoch == self.expected_epoch
            and type(snapshot.next_epoch) is int
            and snapshot.next_epoch == snapshot.expected_epoch + 1
            and snapshot.store_generation == self.store_generation
            and snapshot.coordinator_generation == self.generation
            and snapshot.route_id == route.route_id
            and snapshot.body_id == route.tuple_value[2]
            and route.state == "ROUTED"
            and self.clock.monotonic < route.deadline
            and utc_cap_valid
            and self.clock.utc_valid
            and route.server.identity is self.server.identity
            and route.controller.identity is self.controller.identity
            and snapshot.server_identity is self.server.identity
            and snapshot.controller_identity is self.controller.identity
            and len(self.pending) < PENDING_CAPACITY
        )
        if not valid:
            if correlated_capability:
                self.issued_snapshots.pop(snapshot.identity, None)
            if route.state == "ROUTED":
                self.routes.terminal(route, "INVALIDATED")
                self._invalidate_route_capabilities(route)
            self.events.append("admission_rejected")
            return None

        # Final admission turn: no callback/yield/IO. Validation precedes atomic publication.
        pending_identity = object()
        ticket_identity = object()
        pending = Pending(pending_identity, route.route_id, route.tuple_value)
        ticket = Ticket(ticket_identity, pending_identity, self.generation,
                        self.server.identity, self.controller.identity, route.route_id)
        self.issued_snapshots.pop(snapshot.identity, None)
        self.routes.terminal(route, "CONSUMED")
        self.pending[pending_identity] = pending
        self.tickets[ticket_identity] = ticket
        self.events.extend(("snapshot_consumed", "route_consumed", "pending_published",
                            "ticket_published"))
        return ticket

    def _ticket_current(self, ticket: Ticket) -> bool:
        pending = self.pending.get(ticket.pending_identity)
        return (
            self.tickets.get(ticket.identity) is ticket
            and pending is not None
            and pending.identity is ticket.pending_identity
            and pending.phase == "PREPARE"
            and ticket.coordinator_generation == self.generation
            and ticket.server_identity is self.server.identity
            and ticket.controller_identity is self.controller.identity
        )

    def start_dispatch(self, ticket: Ticket) -> bool:
        if ticket.state != "UNDISPATCHED" or not self._ticket_current(ticket):
            ticket.state = "STALE_OR_UNKNOWN"
            return False
        ticket.state = "DISPATCHING"
        self.send_count += 1
        self.events.append("send_started")
        return True

    def receive_ready(self, ticket: Ticket, ready: Ready, *, cas_matches: bool = True) -> bool:
        if ticket.state == "UNDISPATCHED":
            self.fence_local("ready_before_dispatch")
            ticket.state = "STALE_OR_UNKNOWN"
            return False
        if ticket.state == "DISPATCHING":
            if (ticket.retained_ready is not None
                    or ready.pending_identity is not ticket.pending_identity):
                self.fence_local("duplicate_or_conflicting_early_ready")
                ticket.state = "STALE_OR_UNKNOWN"
                return False
            ticket.retained_ready = ready
            self.events.append("ready_retained_unauthorized")
            return False
        if ticket.state != "DELIVERED_CURRENT" or not self._ticket_current(ticket):
            return False
        return self._process_ready(ticket, ready, cas_matches=cas_matches)

    def complete_dispatch(self, ticket: Ticket, outcome: str = "success",
                          *, cas_matches: bool = True) -> bool:
        # Captured ticket identity prevents old completion from mutating replacement state.
        if self.tickets.get(ticket.identity) is not ticket:
            self.events.append("stale_completion_ignored")
            return False
        if outcome != "success" or not self._ticket_current(ticket):
            ticket.state = "STALE_OR_UNKNOWN"
            ticket.retained_ready = None
            self.fence_local(f"send_{outcome}_or_stale")
            return False
        ticket.state = "DELIVERED_CURRENT"
        self.events.append("delivered_current")
        if ticket.retained_ready is None:
            return True
        ready = ticket.retained_ready
        ticket.retained_ready = None
        return self._process_ready(ticket, ready, cas_matches=cas_matches)

    def _process_ready(self, ticket: Ticket, ready: Ready, *, cas_matches: bool) -> bool:
        # Fresh currentness immediately before the abstract epoch-write gate.
        if (ready.pending_identity is not ticket.pending_identity
                or not self._ticket_current(ticket)):
            self.fence_local("ready_currentness_failed")
            ticket.state = "STALE_OR_UNKNOWN"
            return False
        if not cas_matches:
            self.fence_local("ready_cas_mismatch")
            ticket.state = "STALE_OR_UNKNOWN"
            return False
        self.epoch_write_count += 1  # Observable gate only; no real epoch store operation.
        self.would_commit_count += 1
        self.events.append("validated_ready_would_commit")
        return True

    def fence_local(self, reason: str) -> None:
        self.pending.clear()
        self.issued_snapshots.clear()
        for route in self.routes.records.values():
            if route.state == "ROUTED":
                self.routes.terminal(route, "INVALIDATED")
        for ticket in self.tickets.values():
            ticket.retained_ready = None
            if ticket.state != "DELIVERED_CURRENT":
                ticket.state = "STALE_OR_UNKNOWN"
        self.events.extend(("pending_cleared", "routes_fenced", f"local_fence:{reason}"))

    def disconnect(self, which: str) -> None:
        self.generation += 1
        if "server" in which:
            self.server_current = False
        if "controller" in which:
            self.controller_current = False
        self.coordinator_authority = False
        self.fence_local(f"{which}_disconnect")
        self.tickets.clear()
        self.events.append("async_close_effect")
        self.close_effects += 1

    def replace(self, which: str, *, same_values: bool = False) -> None:
        old = self.server if which == "server" else self.controller
        replacement = Connection(old.install_id if same_values else old.install_id + "-new",
                                 old.connection_id if same_values else old.connection_id + "-new",
                                 object())
        self.generation += 1
        self.coordinator_authority = False
        self.fence_local(f"{which}_replacement")
        self.tickets.clear()
        if which == "server":
            self.server = replacement
            self.server_current = True
        else:
            self.controller = replacement
            self.controller_current = True
        self.coordinator_authority = True
        self.events.append("async_close_effect")
        self.close_effects += 1


class PostAuthV2AdmissionExecutableSpecTest(unittest.TestCase):
    def setUp(self) -> None:
        self.clock = FakeClock()
        self.model = AdmissionModel(self.clock)
        self.route = self.model.routes.create(
            "route-1", ("boot", "game", "body", "nonce"),
            self.model.controller, self.model.server)
        assert self.route is not None

    def admit(self) -> Ticket:
        ticket = self.model.admit_prepare(self.route, self.trusted_snapshot(self.route))
        assert ticket is not None
        return ticket

    def trusted_snapshot(self, route: Route, overrides: dict | None = None) -> Snapshot:
        job = self.model.submit_preflight(route)
        assert job is not None
        snapshot = self.model.worker.complete(job, overrides)
        assert snapshot is not None
        return snapshot

    def test_model_is_explicitly_nonproduction_and_transition_table_has_eight_rows(self):
        self.assertIn("TEST ONLY", __doc__)
        self.assertIn("does not prove", " ".join(__doc__.split()))
        self.assertEqual(8, len(TRANSITIONS))

    def test_final_turn_atomically_consumes_capabilities_and_publishes_ticket(self):
        snapshot = self.trusted_snapshot(self.route)
        ticket = self.model.admit_prepare(self.route, snapshot)
        self.assertEqual("CONSUMED", self.route.state)
        self.assertNotIn(snapshot.identity, self.model.issued_snapshots)
        self.assertIn(ticket.pending_identity, self.model.pending)
        self.assertEqual("UNDISPATCHED", ticket.state)
        self.assertEqual([
            "snapshot_consumed", "route_consumed", "pending_published", "ticket_published"
        ], self.model.events)
        self.assertIsNone(self.model.admit_prepare(self.route, snapshot))

    def test_reentrant_precheck_is_followed_by_full_currentness_revalidation(self):
        snapshot = self.trusted_snapshot(self.route)
        ticket = self.model.admit_prepare(
            self.route, snapshot,
            external_precheck=lambda: self.model.replace("controller", same_values=True))
        self.assertIsNone(ticket)
        self.assertEqual({}, self.model.pending)
        self.assertEqual("INVALIDATED", self.route.state)

    def test_exact_connection_identity_not_equal_install_or_connection_strings(self):
        snapshot = self.trusted_snapshot(self.route)
        self.model.replace("controller", same_values=True)
        self.assertIsNone(self.model.admit_prepare(self.route, snapshot))

    def test_detached_route_clone_is_rejected_and_owned_record_invalidated(self):
        snapshot = self.trusted_snapshot(self.route)
        detached = Route(
            self.route.route_id, self.route.tuple_value, self.route.controller,
            self.route.server, self.route.deadline)
        self.assertIsNone(self.model.admit_prepare(detached, snapshot))
        self.assertEqual("INVALIDATED", self.route.state)
        self.assertEqual({}, self.model.pending)
        self.assertIsNone(self.model.admit_prepare(self.route, snapshot))

    def test_snapshot_all_binding_fields_and_worker_provenance_are_strict(self):
        mutations = (
            ("provision", lambda value: replace(value, provision_id="other")),
            ("expected_epoch", lambda value: replace(value, expected_epoch=6)),
            ("expected_epoch_bool", lambda value: replace(value, expected_epoch=True)),
            ("next_epoch", lambda value: replace(value, next_epoch=9)),
            ("next_epoch_bool", lambda value: replace(value, next_epoch=True)),
            ("store_generation", lambda value: replace(value, store_generation=2)),
            ("body", lambda value: replace(value, body_id="other-body")),
        )
        for name, mutate in mutations:
            with self.subTest(mutation=name):
                self.setUp()
                job = self.model.submit_preflight(self.route)
                self.assertIsNotNone(job)
                field_changes = {
                    "provision": {"provision_id": "other"},
                    "expected_epoch": {"expected_epoch": 6},
                    "expected_epoch_bool": {"expected_epoch": True},
                    "next_epoch": {"next_epoch": 9},
                    "next_epoch_bool": {"next_epoch": True},
                    "store_generation": {"store_generation": 2},
                    "body": {"body_id": "other-body"},
                }
                mutation = self.model.worker.complete(job, field_changes[name])
                self.assertIsNotNone(mutation)
                self.assertIsNone(self.model.admit_prepare(self.route, mutation))
                self.assertEqual("INVALIDATED", self.route.state)
                self.assertEqual({}, self.model.pending)
                self.assertEqual({}, self.model.issued_snapshots)
                self.assertEqual(0, self.model.send_count)

        self.setUp()
        original = self.model.snapshot(self.route)
        unissued_clone = replace(original, identity=object())
        self.assertIsNone(self.model.admit_prepare(self.route, unissued_clone))
        self.assertEqual("INVALIDATED", self.route.state)
        self.assertEqual({}, self.model.issued_snapshots)

    def test_global_route_capacity_includes_live_and_five_second_tombstones(self):
        second = self.model.routes.create(
            "route-2", ("b2", "g2", "body2", "n2"), self.model.controller,
            self.model.server)
        self.assertIsNotNone(second)
        self.model.routes.terminal(self.route, "INVALIDATED")
        self.assertIsNone(self.model.routes.create(
            "route-3", ("b3", "g3", "body3", "n3"), self.model.controller,
            self.model.server))
        self.clock.advance(ROUTE_TTL)
        third = self.model.routes.create(
            "route-3", ("b3", "g3", "body3", "n3"), self.model.controller,
            self.model.server)
        self.assertIsNotNone(third)
        self.assertNotIn("route-1", self.model.routes.records)

    def test_route_retention_uses_original_receipt_deadline_for_live_and_terminal(self):
        terminal = self.model.routes.create(
            "route-2", ("b2", "g2", "body2", "n2"), self.model.controller,
            self.model.server)
        self.model.routes.terminal(terminal, "INVALIDATED")
        self.clock.advance(ROUTE_TTL - 1)
        self.model.routes.prune()
        self.assertIn(self.route.route_id, self.model.routes.records)
        self.assertIn(terminal.route_id, self.model.routes.records)
        self.clock.advance(1)
        self.model.routes.prune()
        self.assertEqual("INVALIDATED", self.route.state)
        self.assertNotIn(self.route.route_id, self.model.routes.records)
        self.assertNotIn(terminal.route_id, self.model.routes.records)

    def test_global_capacity_full_closes_exact_controller_and_keeps_server_current(self):
        second = self.model.accept_notice(
            "route-2", ("b2", "g2", "body2", "n2"), self.model.controller.identity)
        self.assertIsNotNone(second)
        self.model.routes.terminal(self.route, "INVALIDATED")
        before_ids = set(self.model.routes.records)
        self.assertIsNone(self.model.accept_notice(
            "route-3", ("b3", "g3", "body3", "n3"), self.model.controller.identity))
        self.assertEqual(before_ids, set(self.model.routes.records))
        self.assertFalse(self.model.controller_current)
        self.assertTrue(self.model.server_current)
        self.assertFalse(self.model.coordinator_authority)
        self.assertEqual({}, self.model.pending)
        self.assertIn("routes_fenced", self.model.events)
        self.assertIn("controller_close_effect", self.model.events)
        self.assertNotIn("server_close_effect", self.model.events)

    def test_notice_origin_uses_authenticated_controller_identity_not_payload_values(self):
        impostor = Connection(
            self.model.controller.install_id, self.model.controller.connection_id, object())
        self.assertIsNone(self.model.accept_notice(
            "route-impostor", ("b", "g", "body", "n"), impostor.identity))
        self.assertTrue(self.model.controller_current)
        self.assertTrue(self.model.server_current)
        self.assertNotIn("route-impostor", self.model.routes.records)

    def test_pending_capacity_eight_is_separate_from_route_capacity_two(self):
        self.model.pending = {object(): Pending(object(), "existing", ("x",))
                              for _ in range(PENDING_CAPACITY)}
        self.assertIsNone(self.model.admit_prepare(
            self.route, self.trusted_snapshot(self.route)))
        self.assertEqual(PENDING_CAPACITY, len(self.model.pending))
        self.assertEqual("INVALIDATED", self.route.state)

    def test_worker_capacity_counts_obsolete_jobs_until_actual_completion(self):
        worker = self.model.worker
        running = self.model.submit_preflight(self.route, "running")
        queued = self.model.submit_preflight(self.route, "queued")
        self.assertEqual(("RUNNING", "QUEUED"), (running.state, queued.state))
        worker.make_unavailable(running)
        worker.make_unavailable(queued)
        self.assertIsNone(self.model.submit_preflight(self.route, "third"))
        self.assertEqual(2, len(worker.jobs))
        self.assertIsNone(worker.complete(running))
        self.assertEqual("RUNNING", queued.state)
        replacement = self.model.submit_preflight(self.route, "replacement")
        self.assertIsNotNone(replacement)
        self.assertEqual(1, worker.worker_count)

    def test_completed_job_mints_once_and_unconsumed_handoff_counts_toward_limit(self):
        first_job = self.model.submit_preflight(self.route, "first")
        first = self.model.worker.complete(first_job)
        self.assertIsNotNone(first)
        self.assertIsNone(self.model.worker.complete(first_job))
        second_job = self.model.submit_preflight(self.route, "second")
        self.assertIsNotNone(second_job)
        self.assertIsNone(self.model.submit_preflight(self.route, "third"))
        self.assertEqual(2, self.model._logical_outstanding())
        self.model.worker.make_unavailable(second_job)
        self.assertIsNone(self.model.worker.complete(second_job))
        self.assertEqual(1, self.model._logical_outstanding())
        self.assertIsNotNone(self.model.submit_preflight(self.route, "replacement"))

    def test_worker_completion_requires_exact_running_head_identity(self):
        running = self.model.submit_preflight(self.route, "same")
        queued = self.model.submit_preflight(self.route, "same")
        clone = replace(running)
        independently_equal = Job(
            running.job_id, running.state, running.identity, running.route_id,
            running.route_identity, running.coordinator_generation,
            running.store_identity, running.store_generation,
            running.server_identity, running.controller_identity,
            running.unavailable, running.completed,
        )
        self.assertIsNone(self.model.worker.complete(clone))
        self.assertIsNone(self.model.worker.complete(independently_equal))
        self.assertIsNone(self.model.worker.complete(queued))
        self.assertEqual([running, queued], self.model.worker.jobs)
        self.assertEqual(("RUNNING", "QUEUED"), (running.state, queued.state))
        self.assertEqual({}, self.model.issued_snapshots)

        first = self.model.worker.complete(running)
        self.assertIsNotNone(first)
        self.assertEqual([queued], self.model.worker.jobs)
        self.assertEqual("RUNNING", queued.state)
        self.assertIsNone(self.model.worker.complete(running))
        second = self.model.worker.complete(queued)
        self.assertIsNotNone(second)
        self.assertEqual([], self.model.worker.jobs)
        self.assertIsNone(self.model.worker.complete(queued))
        self.assertEqual(2, len(self.model.issued_snapshots))

    def test_invalid_admission_removes_all_handoffs_for_owned_route_only(self):
        first_job = self.model.submit_preflight(self.route, "first")
        second_job = self.model.submit_preflight(self.route, "second")
        first = self.model.worker.complete(first_job)
        second = self.model.worker.complete(second_job)
        self.assertEqual(2, len(self.model.issued_snapshots))

        self.assertIsNone(self.model.admit_prepare(
            self.route, replace(first, provision_id="invalid")))
        self.assertEqual("INVALIDATED", self.route.state)
        self.assertEqual({}, self.model.issued_snapshots)

        other = self.model.routes.create(
            "other-route", ("boot", "game", "other-body", "nonce"),
            self.model.controller, self.model.server)
        other_snapshot = self.trusted_snapshot(other)
        detached = Route(
            self.route.route_id, self.route.tuple_value, self.route.controller,
            self.route.server, self.route.deadline)
        self.assertIsNone(self.model.admit_prepare(detached, second))
        self.assertIs(self.model.issued_snapshots.get(other_snapshot.identity), other_snapshot)

    def test_handoff_registry_clears_on_cancel_disconnect_fence_and_expiry(self):
        for terminal in ("cancel", "disconnect", "fence", "expiry"):
            with self.subTest(terminal=terminal):
                self.setUp()
                snapshot = self.trusted_snapshot(self.route)
                self.assertEqual(1, len(self.model.issued_snapshots))
                if terminal == "cancel":
                    self.model.cancel_snapshot(snapshot)
                elif terminal == "disconnect":
                    self.model.disconnect("controller")
                elif terminal == "fence":
                    self.model.fence_local("test")
                else:
                    self.clock.advance(ROUTE_TTL)
                    self.model.prune_obsolete_capabilities()
                self.assertEqual({}, self.model.issued_snapshots)

    def test_repeated_expired_and_rejected_preflights_keep_capability_state_bounded(self):
        maximum = 0
        for index in range(200):
            route = self.model.routes.create(
                f"route-{index + 10}", ("boot", "game", f"body-{index}", "nonce"),
                self.model.controller, self.model.server)
            self.assertIsNotNone(route)
            snapshot = self.trusted_snapshot(route)
            maximum = max(maximum, self.model._logical_outstanding())
            if index % 2:
                self.assertIsNone(self.model.admit_prepare(
                    route, replace(snapshot, provision_id="invalid")))
            else:
                self.clock.advance(ROUTE_TTL)
                self.model.prune_obsolete_capabilities()
            self.assertLessEqual(self.model._logical_outstanding(), 2)
            self.assertEqual({}, self.model.issued_snapshots)
            self.model.routes.prune()
        self.assertLessEqual(maximum, 2)
        self.assertEqual(0, self.model._logical_outstanding())

    def test_unverified_store_affinity_is_disabled_without_io(self):
        worker = StoreWorker(affinity_verified=False)
        self.assertIsNone(worker.submit("job"))
        self.assertEqual([], worker.jobs)
        self.assertEqual(1, worker.worker_count)

    def test_preflight_completion_after_expiry_or_replacement_is_discarded(self):
        for mutation in ("expiry", "controller", "server", "coordinator"):
            with self.subTest(mutation=mutation):
                self.setUp()
                job = self.model.submit_preflight(self.route)
                self.assertIsNotNone(job)
                if mutation == "expiry":
                    self.clock.advance(ROUTE_TTL)
                elif mutation in ("controller", "server"):
                    self.model.replace(mutation)
                else:
                    self.model.generation += 1
                snapshot = self.model.worker.complete(job)
                self.assertIsNone(snapshot)
                self.assertEqual({}, self.model.pending)
                self.assertEqual({}, self.model.issued_snapshots)

    def test_local_deadline_and_utc_cap_do_not_claim_server_monotonic_proof(self):
        snapshot = self.trusted_snapshot(self.route)
        self.assertIsNone(self.model.admit_prepare(
            self.route, snapshot, utc_cap_valid=False))
        self.assertNotIn("server_monotonic", " ".join(self.model.events))

    def test_disconnect_before_admission_has_no_pending_or_send(self):
        snapshot = self.trusted_snapshot(self.route)
        self.model.disconnect("controller")
        self.assertIsNone(self.model.admit_prepare(self.route, snapshot))
        self.assertEqual(0, self.model.send_count)
        self.assertEqual({}, self.model.pending)

    def test_disconnect_after_admission_sync_fences_before_async_close(self):
        self.admit()
        self.model.disconnect("controller")
        self.assertEqual({}, self.model.pending)
        self.assertLess(self.model.events.index("pending_cleared"),
                        self.model.events.index("async_close_effect"))
        self.assertLess(self.model.events.index("routes_fenced"),
                        self.model.events.index("async_close_effect"))

    def test_dispatch_success_requires_pre_and_post_currentness(self):
        ticket = self.admit()
        self.assertTrue(self.model.start_dispatch(ticket))
        self.assertTrue(self.model.complete_dispatch(ticket))
        self.assertEqual("DELIVERED_CURRENT", ticket.state)
        self.assertEqual(1, self.model.send_count)
        self.assertEqual(0, ticket.retries)

    def test_send_failure_cancel_unknown_and_disconnect_are_fenced_without_retry(self):
        for outcome in ("failure", "cancelled", "unknown"):
            with self.subTest(outcome=outcome):
                self.setUp()
                ticket = self.admit()
                self.model.start_dispatch(ticket)
                self.assertFalse(self.model.complete_dispatch(ticket, outcome))
                self.assertEqual("STALE_OR_UNKNOWN", ticket.state)
                self.assertEqual({}, self.model.pending)
                self.assertEqual(1, self.model.send_count)
                self.assertEqual(0, ticket.retries)

        self.setUp()
        ticket = self.admit()
        self.model.start_dispatch(ticket)
        self.model.disconnect("server")
        self.assertFalse(self.model.complete_dispatch(ticket))
        self.assertEqual(1, self.model.send_count)

    def test_early_ready_is_retained_once_without_epoch_write_or_phase_change(self):
        ticket = self.admit()
        self.model.start_dispatch(ticket)
        ready = Ready(ticket.pending_identity, "ready")
        self.assertFalse(self.model.receive_ready(ticket, ready))
        self.assertIs(ticket.retained_ready, ready)
        self.assertEqual("PREPARE", self.model.pending[ticket.pending_identity].phase)
        self.assertEqual(0, self.model.epoch_write_count)
        self.assertEqual(0, self.model.would_commit_count)

    def test_duplicate_or_conflicting_early_ready_fails_closed(self):
        for second in ("duplicate", "conflicting"):
            with self.subTest(second=second):
                self.setUp()
                ticket = self.admit()
                self.model.start_dispatch(ticket)
                self.model.receive_ready(ticket, Ready(ticket.pending_identity, "first"))
                identity = ticket.pending_identity if second == "duplicate" else object()
                self.assertFalse(self.model.receive_ready(ticket, Ready(identity, second)))
                self.assertEqual("STALE_OR_UNKNOWN", ticket.state)
                self.assertEqual({}, self.model.pending)
                self.assertIsNone(ticket.retained_ready)

    def test_normal_completion_processes_retained_ready_after_fresh_checks(self):
        ticket = self.admit()
        self.model.start_dispatch(ticket)
        self.model.receive_ready(ticket, Ready(ticket.pending_identity, "ready"))
        self.assertTrue(self.model.complete_dispatch(ticket))
        self.assertEqual("DELIVERED_CURRENT", ticket.state)
        self.assertEqual(1, self.model.epoch_write_count)
        self.assertEqual(1, self.model.would_commit_count)

    def test_cas_mismatch_fences_and_never_records_would_commit(self):
        ticket = self.admit()
        self.model.start_dispatch(ticket)
        self.model.receive_ready(ticket, Ready(ticket.pending_identity, "ready"))
        self.assertFalse(self.model.complete_dispatch(ticket, cas_matches=False))
        self.assertEqual("STALE_OR_UNKNOWN", ticket.state)
        self.assertEqual(0, self.model.epoch_write_count)
        self.assertEqual(0, self.model.would_commit_count)
        self.assertEqual({}, self.model.pending)

    def test_ready_before_undispatched_is_conservatively_rejected_not_normatively_defined(self):
        ticket = self.admit()
        self.assertFalse(self.model.receive_ready(
            ticket, Ready(ticket.pending_identity, "too-early")))
        self.assertEqual("STALE_OR_UNKNOWN", ticket.state)
        self.assertEqual(0, self.model.epoch_write_count)

    def test_old_ticket_completion_cannot_mutate_replacement_same_value_identity(self):
        old = self.admit()
        self.model.start_dispatch(old)
        self.model.replace("controller", same_values=True)

        route = self.model.routes.create(
            "route-new", ("boot", "game", "body", "nonce"),
            self.model.controller, self.model.server)
        self.assertIsNotNone(route)
        new = self.model.admit_prepare(route, self.trusted_snapshot(route))
        replacement = (new.state, len(self.model.pending), self.model.would_commit_count)
        self.assertFalse(self.model.complete_dispatch(old))
        self.assertEqual(replacement,
                         (new.state, len(self.model.pending), self.model.would_commit_count))
        self.assertIn("stale_completion_ignored", self.model.events)

    def test_boundary_schedule_table_has_no_stale_epoch_write_or_retry(self):
        traces = {}
        boundaries = (
            "before_preflight", "after_preflight", "after_admit", "after_send",
            "after_early_ready", "after_delivered",
        )
        for boundary in boundaries:
            self.setUp()
            snapshot = self.trusted_snapshot(self.route)
            ticket = None
            if boundary == "before_preflight":
                self.model.disconnect("controller")
            elif boundary == "after_preflight":
                self.model.disconnect("controller")
            else:
                ticket = self.model.admit_prepare(self.route, snapshot)
                if boundary == "after_admit":
                    self.model.disconnect("controller")
                else:
                    self.model.start_dispatch(ticket)
                    if boundary == "after_send":
                        self.model.disconnect("controller")
                    elif boundary == "after_early_ready":
                        self.model.receive_ready(
                            ticket, Ready(ticket.pending_identity, "ready"))
                        self.model.disconnect("controller")
                    else:
                        self.model.complete_dispatch(ticket)
                        self.model.disconnect("controller")
            if ticket is not None and ticket.state == "DISPATCHING":
                self.model.complete_dispatch(ticket)
            traces[boundary] = (
                len(self.model.pending), self.model.epoch_write_count,
                0 if ticket is None else ticket.retries,
            )
        self.assertEqual(6, len(traces))
        self.assertTrue(all(pending == 0 for pending, _, _ in traces.values()))
        self.assertTrue(all(writes == 0 for _, writes, _ in traces.values()))
        self.assertTrue(all(retries == 0 for _, _, retries in traces.values()))


if __name__ == "__main__":
    unittest.main()
