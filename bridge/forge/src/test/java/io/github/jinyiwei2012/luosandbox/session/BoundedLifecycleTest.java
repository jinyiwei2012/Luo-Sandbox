package io.github.jinyiwei2012.luosandbox.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.jinyiwei2012.luosandbox.policy.AuthorityPolicy;
import java.util.Set;
import org.junit.jupiter.api.Test;

class BoundedLifecycleTest {
  private static final AuthorityPolicy.Binding BINDING = new AuthorityPolicy.Binding("actor", "world", "body", "session", 1);
  private static final AuthorityPolicy.Region REGION = new AuthorityPolicy.Region("minecraft:overworld", -8, 8, 0, 128, -8, 8);
  private static final AuthorityPolicy.Observation OBSERVATION = new AuthorityPolicy.Observation("obs", "boot", 1, true, "minecraft:overworld", new AuthorityPolicy.Position(0, 64, 0));
  private static final BoundedLifecycle.AuthorityEvidence EVIDENCE = new BoundedLifecycle.AuthorityEvidence("server-event");

  @Test
  void fifoQueuedCancelAndActiveCancelHaveDifferentTruthfulStates() {
    BoundedLifecycle lifecycle = lifecycle();
    admit(lifecycle, "one", AuthorityPolicy.Capability.LOOK, 200);
    admit(lifecycle, "two", AuthorityPolicy.Capability.PUBLIC_SAY, 200);
    assertEquals("one", lifecycle.beginNextEffect(OBSERVATION, 100, 101).orElseThrow().proposal().requestId());
    lifecycle.cancelTarget("two", 102);
    assertEquals(BoundedLifecycle.Terminal.CANCELLED, lifecycle.record("two").orElseThrow().terminal());
    assertEquals(BoundedLifecycle.Effect.NONE, lifecycle.record("two").orElseThrow().effect());
    lifecycle.cancelTarget("one", 102);
    assertEquals(BoundedLifecycle.Phase.CANCEL_REQUESTED, lifecycle.record("one").orElseThrow().phase());
    assertEquals(null, lifecycle.record("one").orElseThrow().terminal());
  }

  @Test
  void duplicateReplayRequiresEquivalentProposalAndStillChecksBinding() {
    BoundedLifecycle lifecycle = lifecycle();
    assertTrue(admit(lifecycle, "id", AuthorityPolicy.Capability.QUERY, 200).accepted());
    assertTrue(admit(lifecycle, "id", AuthorityPolicy.Capability.QUERY, 200).accepted());
    assertEquals(AuthorityPolicy.Code.ID_CONFLICT, admit(lifecycle, "id", AuthorityPolicy.Capability.QUERY, 201).code());
    AuthorityPolicy.Proposal wrong = new AuthorityPolicy.Proposal("id", new AuthorityPolicy.Binding("actor", "world", "body", "other", 1), AuthorityPolicy.Capability.QUERY, 100, 200, null, null, null, 0);
    assertEquals(AuthorityPolicy.Code.BINDING_MISMATCH, lifecycle.admit(wrong, OBSERVATION, 100, 100).code());
  }

  @Test
  void deadlineExpiresQueuedAsTimedOutNoneAndRetainedTerminalPrunes() {
    BoundedLifecycle lifecycle = lifecycle();
    admit(lifecycle, "expired", AuthorityPolicy.Capability.LOOK, 101);
    assertFalse(lifecycle.beginNextEffect(OBSERVATION, 100, 101).isPresent());
    assertEquals(BoundedLifecycle.Terminal.TIMED_OUT, lifecycle.record("expired").orElseThrow().terminal());
    assertEquals(BoundedLifecycle.Effect.NONE, lifecycle.record("expired").orElseThrow().effect());
    lifecycle.admit(proposal("new", AuthorityPolicy.Capability.QUERY, 300_201), OBSERVATION, 300_101, 300_101);
    assertTrue(lifecycle.record("expired").isEmpty());
  }

  @Test
  void unknownIsImmutableFencesBodyAndDoesNotFabricateQueuedUnknown() {
    BoundedLifecycle lifecycle = lifecycle();
    admit(lifecycle, "active", AuthorityPolicy.Capability.LOOK, 200);
    admit(lifecycle, "queued", AuthorityPolicy.Capability.LOOK, 200);
    lifecycle.beginNextEffect(OBSERVATION, 100, 101);
    assertFalse(lifecycle.markUnknown("queued", EVIDENCE, 102));
    assertTrue(lifecycle.markUnknown("active", EVIDENCE, 102));
    assertTrue(lifecycle.quarantined());
    assertFalse(lifecycle.complete("active", BoundedLifecycle.Terminal.SUCCEEDED, BoundedLifecycle.Effect.COMPLETE, EVIDENCE, 103));
    assertTrue(admit(lifecycle, "query", AuthorityPolicy.Capability.QUERY, 200).accepted());
    assertEquals(AuthorityPolicy.Code.QUARANTINED, admit(lifecycle, "later", AuthorityPolicy.Capability.LOOK, 200).code());
  }

  @Test
  void emergencyAndHeartbeatFailClosedWithoutEquatingSlowModelToControlLoss() {
    BoundedLifecycle lifecycle = lifecycle();
    admit(lifecycle, "effect", AuthorityPolicy.Capability.LOOK, 200);
    lifecycle.beginNextEffect(OBSERVATION, 100, 101);
    lifecycle.emergencyRevoke();
    assertTrue(lifecycle.emergencyRevoked());
    assertEquals(BoundedLifecycle.Phase.CANCEL_REQUESTED, lifecycle.record("effect").orElseThrow().phase());
    assertEquals(AuthorityPolicy.Code.QUARANTINED, admit(lifecycle, "blocked", AuthorityPolicy.Capability.LOOK, 200).code());
    lifecycle.heartbeat(1_000);
    assertFalse(lifecycle.controlLost(2_999));
    assertTrue(lifecycle.controlLost(3_001));
  }

  @Test
  void epochChangeNeedsExternallySuppliedFenceAndPreservesOldHistory() {
    BoundedLifecycle lifecycle = lifecycle();
    admit(lifecycle, "old", AuthorityPolicy.Capability.LOOK, 200);
    lifecycle.beginNextEffect(OBSERVATION, 100, 101);
    AuthorityPolicy.Binding next = new AuthorityPolicy.Binding("actor", "world", "body", "next", 2);
    assertFalse(lifecycle.advanceEpoch(next, null, 102));
    assertTrue(lifecycle.advanceEpoch(next, new BoundedLifecycle.FenceProof(EVIDENCE, BoundedLifecycle.PermitFence.REVOKED, BoundedLifecycle.BodyFence.QUIESCENT), 102));
    assertEquals(BoundedLifecycle.Phase.CANCEL_REQUESTED, lifecycle.record("old").orElseThrow().phase());
    assertFalse(lifecycle.emergencyRevoked());
  }

  @Test
  void rejectedAdmissionIsCanonicalAcrossEffectAndRecordCapacityRelease() {
    BoundedLifecycle lifecycle = lifecycle();
    for (int index = 0; index < BoundedLifecycle.MAX_EFFECT_WAITING; index++) {
      admit(lifecycle, "queued-" + index, AuthorityPolicy.Capability.LOOK, 5_000);
    }
    BoundedLifecycle.Admission queuedRejection = admit(lifecycle, "queued-rejected",
        AuthorityPolicy.Capability.LOOK, 5_000);
    assertEquals(AuthorityPolicy.Code.BACKPRESSURE, queuedRejection.code());
    lifecycle.cancelTarget("queued-0", 101);
    assertEquals(queuedRejection, admit(lifecycle, "queued-rejected", AuthorityPolicy.Capability.LOOK, 5_000));

    BoundedLifecycle full = lifecycle();
    for (int index = 0; index < BoundedLifecycle.MAX_RECORDS; index++) {
      admit(full, "record-" + index, AuthorityPolicy.Capability.LOOK, 5_000);
      full.beginNextEffect(OBSERVATION, 100, 101);
      assertTrue(full.complete("record-" + index, BoundedLifecycle.Terminal.SUCCEEDED,
          BoundedLifecycle.Effect.COMPLETE, EVIDENCE, 0));
    }
    BoundedLifecycle.Admission fullRejection = admit(full, "record-rejected",
        AuthorityPolicy.Capability.QUERY, 5_000);
    assertEquals(AuthorityPolicy.Code.BACKPRESSURE, fullRejection.code());
    full.admit(proposal("capacity-prune", AuthorityPolicy.Capability.QUERY, 5_000), OBSERVATION, 100, 300_000);
    assertEquals(fullRejection, full.admit(proposal("record-rejected", AuthorityPolicy.Capability.QUERY,
        5_000), OBSERVATION, 100, 300_000));
  }

  @Test
  void finiteDecisionCacheFailsClosedWithoutEvictingAcceptedRecords() {
    BoundedLifecycle lifecycle = lifecycle();
    admit(lifecycle, "accepted", AuthorityPolicy.Capability.QUERY, 5_000);
    for (int index = 0; index < BoundedLifecycle.MAX_ADMISSION_DECISIONS - 1; index++) {
      AuthorityPolicy.Proposal denied = staleBasisProposal("denied-" + index, 5_000);
      assertEquals(AuthorityPolicy.Code.STALE_BASIS, AuthorityPolicy.checkAdmission(denied, BINDING,
          OBSERVATION, REGION, Set.of(AuthorityPolicy.Capability.values()), false, 100));
      assertEquals(AuthorityPolicy.Code.STALE_BASIS,
          lifecycle.admit(denied, OBSERVATION, 100, 100).code());
    }
    BoundedLifecycle.Admission overflow = admit(lifecycle, "overflow", AuthorityPolicy.Capability.QUERY, 5_000);
    assertEquals(AuthorityPolicy.Code.BACKPRESSURE, overflow.code());
    assertFalse(overflow.canonical());
    assertTrue(lifecycle.record("accepted").orElseThrow().admission().accepted());
  }

  @Test
  void queryReservationsAreIndependentBoundedAndReleaseOnlyAtTerminal() {
    BoundedLifecycle lifecycle = lifecycle();
    admit(lifecycle, "effect", AuthorityPolicy.Capability.LOOK, 5_000);
    assertEquals("effect", lifecycle.beginNextEffect(OBSERVATION, 100, 101).orElseThrow().proposal().requestId());
    for (int index = 0; index < BoundedLifecycle.MAX_CONTROL_RESERVATIONS; index++) {
      assertTrue(admit(lifecycle, "query-" + index, AuthorityPolicy.Capability.QUERY, 5_000).accepted());
    }
    BoundedLifecycle.Admission rejection = admit(lifecycle, "query-rejected", AuthorityPolicy.Capability.QUERY,
        5_000);
    assertEquals(AuthorityPolicy.Code.BACKPRESSURE, rejection.code());
    assertTrue(lifecycle.complete("query-0", BoundedLifecycle.Terminal.SUCCEEDED,
        BoundedLifecycle.Effect.COMPLETE, EVIDENCE, 101));
    assertEquals(rejection, admit(lifecycle, "query-rejected", AuthorityPolicy.Capability.QUERY, 5_000));
    assertTrue(admit(lifecycle, "query-new", AuthorityPolicy.Capability.QUERY, 5_000).accepted());
  }

  @Test
  void queuedEffectsCannotCompleteWithSuccessPartialOrUnknown() {
    BoundedLifecycle lifecycle = lifecycle();
    admit(lifecycle, "active", AuthorityPolicy.Capability.LOOK, 5_000);
    admit(lifecycle, "queued", AuthorityPolicy.Capability.LOOK, 5_000);
    lifecycle.beginNextEffect(OBSERVATION, 100, 101);
    assertFalse(lifecycle.complete("queued", BoundedLifecycle.Terminal.SUCCEEDED,
        BoundedLifecycle.Effect.COMPLETE, EVIDENCE, 102));
    assertFalse(lifecycle.complete("queued", BoundedLifecycle.Terminal.FAILED,
        BoundedLifecycle.Effect.PARTIAL, EVIDENCE, 102));
    assertFalse(lifecycle.complete("queued", BoundedLifecycle.Terminal.UNKNOWN,
        BoundedLifecycle.Effect.UNKNOWN, EVIDENCE, 102));
  }

  @Test
  void dispatchUsesMonotonicDeadlineAfterWallClockJump() {
    BoundedLifecycle lifecycle = lifecycle();
    assertTrue(lifecycle.admit(proposal("jump", AuthorityPolicy.Capability.LOOK, 200), OBSERVATION, 100, 100)
        .accepted());
    assertEquals("jump", lifecycle.beginNextEffect(OBSERVATION, 201, 150).orElseThrow().proposal().requestId());
  }

  @Test
  void activeRecordKeepsItsCanonicalAdmissionBeyondDecisionRetention() {
    BoundedLifecycle lifecycle = lifecycle();
    AuthorityPolicy.Proposal proposal = proposal("long-active", AuthorityPolicy.Capability.LOOK, 5_000);
    BoundedLifecycle.Admission accepted = lifecycle.admit(proposal, OBSERVATION, 100, 100);
    assertTrue(accepted.accepted());
    assertEquals("long-active", lifecycle.beginNextEffect(OBSERVATION, 100, 101).orElseThrow()
        .proposal().requestId());

    assertEquals(accepted, lifecycle.admit(proposal, OBSERVATION, 300_100, 300_100));
    assertEquals(BoundedLifecycle.Phase.EXECUTING,
        lifecycle.record("long-active").orElseThrow().phase());
  }

  private static BoundedLifecycle lifecycle() { return new BoundedLifecycle(BINDING, REGION, Set.of(AuthorityPolicy.Capability.values()), 100); }
  private static BoundedLifecycle.Admission admit(BoundedLifecycle lifecycle, String id, AuthorityPolicy.Capability capability, long deadline) { return lifecycle.admit(proposal(id, capability, deadline), OBSERVATION, 100, 100); }
  private static AuthorityPolicy.Proposal proposal(String id, AuthorityPolicy.Capability capability, long deadline) {
    AuthorityPolicy.Basis basis = capability == AuthorityPolicy.Capability.QUERY ? null : new AuthorityPolicy.Basis("obs", "boot", 1);
    AuthorityPolicy.Position target = capability == AuthorityPolicy.Capability.QUERY ? null : new AuthorityPolicy.Position(1, 64, 0);
    return new AuthorityPolicy.Proposal(id, BINDING, capability, 100, deadline, basis, "minecraft:overworld", target, 2);
  }
  private static AuthorityPolicy.Proposal staleBasisProposal(String id, long deadline) {
    return new AuthorityPolicy.Proposal(id, BINDING, AuthorityPolicy.Capability.LOOK, 100, deadline,
        new AuthorityPolicy.Basis("other-observation", "boot", 1), "minecraft:overworld",
        new AuthorityPolicy.Position(1, 64, 0), 0);
  }
}
