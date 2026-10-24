package io.github.jinyiwei2012.luosandbox.servercontrol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.jinyiwei2012.luosandbox.policy.AuthorityPolicy;
import io.github.jinyiwei2012.luosandbox.session.BoundedLifecycle;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ServerControlCoordinatorTest {
  private static final AuthorityPolicy.Binding POLICY_BINDING = new AuthorityPolicy.Binding("actor", "world", "body", "session", 1);
  private static final PermitAuthority.Binding PERMIT_BINDING = new PermitAuthority.Binding("actor", "world", "body", "session", 1, "boot", "connection");
  private static final AuthorityPolicy.Region REGION = new AuthorityPolicy.Region("minecraft:overworld", -4, 4, 0, 128, -4, 4);
  private static final AuthorityPolicy.Observation OBS = new AuthorityPolicy.Observation("obs", "boot", 1, true, "minecraft:overworld", new AuthorityPolicy.Position(0, 64, 0));
  private static final PermitAuthority.Bounds BOUNDS = new PermitAuthority.Bounds("minecraft:overworld", new PermitAuthority.Position(-4, 0, -4), new PermitAuthority.Position(4, 128, 4), 2);

  @Test void acceptedRequestDoesNotIssueUntilDispatchAndNotification() {
    FakeNotifier notifier = new FakeNotifier(false);
    ServerControlCoordinator coordinator = coordinator(notifier);
    assertTrue(coordinator.admit(proposal("walk", 500), OBS, 100, 100).accepted());
    assertEquals(ServerControlCoordinator.DispatchStatus.PERMIT_ISSUED,
        coordinator.dispatchNext(context(101), new ServerControlCoordinator.PermitGrant(BOUNDS, 500)).status());
    assertEquals(1, notifier.permits);
  }

  @Test void queuedCancelAndExpiredRequestsNeverIssuePermit() {
    FakeNotifier notifier = new FakeNotifier(false);
    ServerControlCoordinator coordinator = coordinator(notifier);
    coordinator.admit(proposal("one", 500), OBS, 100, 100);
    coordinator.admit(proposal("two", 500), OBS, 100, 100);
    coordinator.cancel("two", 101);
    assertEquals(ServerControlCoordinator.DispatchStatus.PERMIT_ISSUED,
        coordinator.dispatchNext(context(101), new ServerControlCoordinator.PermitGrant(BOUNDS, 500)).status());
    assertEquals(ServerControlCoordinator.DispatchStatus.NO_WORK,
        coordinator.dispatchNext(context(102), new ServerControlCoordinator.PermitGrant(BOUNDS, 500)).status());
  }

  @Test void failedNotificationMakesUnknownRatherThanSuccess() {
    ServerControlCoordinator coordinator = coordinator(new FakeNotifier(true));
    coordinator.admit(proposal("walk", 500), OBS, 100, 100);
    assertEquals(ServerControlCoordinator.DispatchStatus.UNKNOWN,
        coordinator.dispatchNext(context(101), new ServerControlCoordinator.PermitGrant(BOUNDS, 500)).status());
    assertEquals(ServerControlCoordinator.DispatchStatus.NO_WORK,
        coordinator.dispatchNext(context(102), new ServerControlCoordinator.PermitGrant(BOUNDS, 500)).status());
  }

  @Test void terminalEvidenceIsImmutableAndControlLossRevokesPermit() {
    FakeNotifier notifier = new FakeNotifier(false);
    ServerControlCoordinator coordinator = coordinator(notifier);
    coordinator.admit(proposal("walk", 500), OBS, 100, 100);
    PermitAuthority.Permit permit = coordinator.dispatchNext(context(101),
        new ServerControlCoordinator.PermitGrant(BOUNDS, 500)).permit().orElseThrow();
    coordinator.controlLost(new BoundedLifecycle.AuthorityEvidence("control-lost"), 102);
    assertEquals(PermitAuthority.Gate.DENY, coordinator.gate(permit.permitId(), context(102).permitContext(), new PermitAuthority.Position(0, 64, 0), 0, 0));
    assertTrue(notifier.revokes > 0);
  }

  @Test void controlLossMakesActiveUnknownAndFencesIdleFutureEffects() {
    ServerControlCoordinator coordinator = coordinator(new FakeNotifier(false));
    coordinator.admit(proposal("walk", 500), OBS, 100, 100);
    coordinator.dispatchNext(context(101), new ServerControlCoordinator.PermitGrant(BOUNDS, 500));
    coordinator.controlLost(new BoundedLifecycle.AuthorityEvidence("control-lost"), 102);
    assertEquals(BoundedLifecycle.Terminal.UNKNOWN, coordinator.record("walk").orElseThrow().terminal());
    assertTrue(coordinator.quarantined());
    ServerControlCoordinator idle = coordinator(new FakeNotifier(false));
    idle.controlLost(new BoundedLifecycle.AuthorityEvidence("idle-control-lost"), 101);
    assertFalse(idle.admit(proposal("blocked", 500), OBS, 100, 102).accepted());
  }

  @Test void bindingPrepareFenceRevokesPermitAndMakesExecutionImmutableUnknown() {
    FakeNotifier notifier = new FakeNotifier(false);
    ServerControlCoordinator coordinator = coordinator(notifier);
    coordinator.admit(proposal("walk", 500), OBS, 100, 100);
    PermitAuthority.Permit permit = coordinator.dispatchNext(context(101),
        new ServerControlCoordinator.PermitGrant(BOUNDS, 500)).permit().orElseThrow();

    assertTrue(coordinator.fenceForBindingPrepare(PERMIT_BINDING,
        new BoundedLifecycle.AuthorityEvidence("prepare"), 102));
    assertTrue(coordinator.quarantined());
    assertEquals(BoundedLifecycle.Terminal.UNKNOWN,
        coordinator.record("walk").orElseThrow().terminal());
    assertEquals(PermitAuthority.Gate.DENY, coordinator.gate(permit.permitId(),
        context(102).permitContext(), new PermitAuthority.Position(0, 64, 0), 0, 0));
    assertEquals(ServerControlCoordinator.DispatchStatus.NO_WORK,
        coordinator.dispatchNext(context(103),
            new ServerControlCoordinator.PermitGrant(BOUNDS, 500)).status());
    assertEquals(1, notifier.revokes);
  }

  @Test void bindingPrepareFenceWrongTupleDoesNotMutateCurrentAuthority() {
    ServerControlCoordinator coordinator = coordinator(new FakeNotifier(false));
    coordinator.admit(proposal("walk", 500), OBS, 100, 100);
    assertFalse(coordinator.fenceForBindingPrepare(
        new PermitAuthority.Binding("actor", "world", "body", "session", 1, "boot", "wrong"),
        new BoundedLifecycle.AuthorityEvidence("prepare"), 101));
    assertFalse(coordinator.quarantined());
    assertEquals(ServerControlCoordinator.DispatchStatus.PERMIT_ISSUED,
        coordinator.dispatchNext(context(101),
            new ServerControlCoordinator.PermitGrant(BOUNDS, 500)).status());
  }

  @Test void bindingPrepareNotifierFailureCannotLeaveLocalAuthorityAlive() {
    ServerControlCoordinator coordinator = coordinator(new FakeNotifier(false, true));
    coordinator.admit(proposal("walk", 500), OBS, 100, 100);
    coordinator.dispatchNext(context(101), new ServerControlCoordinator.PermitGrant(BOUNDS, 500));
    assertFalse(coordinator.fenceForBindingPrepare(PERMIT_BINDING,
        new BoundedLifecycle.AuthorityEvidence("prepare"), 102));
    assertTrue(coordinator.quarantined());
    assertEquals(BoundedLifecycle.Terminal.UNKNOWN,
        coordinator.record("walk").orElseThrow().terminal());
  }

  @Test void notifierFailureCannotInterruptLocalUnknownFence() {
    ServerControlCoordinator coordinator = coordinator(new FakeNotifier(true, true));
    coordinator.admit(proposal("walk", 500), OBS, 100, 100);
    assertEquals(ServerControlCoordinator.DispatchStatus.UNKNOWN,
        coordinator.dispatchNext(context(101), new ServerControlCoordinator.PermitGrant(BOUNDS, 500)).status());
    assertEquals(BoundedLifecycle.Terminal.UNKNOWN, coordinator.record("walk").orElseThrow().terminal());
    assertTrue(coordinator.quarantined());
  }

  @Test void deadlineFencesPermitButKeepsLifecycleAwaitingTrustedTerminalEvidence() {
    FakeNotifier notifier = new FakeNotifier(false);
    ServerControlCoordinator coordinator = coordinator(notifier);
    coordinator.admit(proposal("walk", 102), OBS, 100, 100);
    PermitAuthority.Permit permit = coordinator.dispatchNext(context(101),
        new ServerControlCoordinator.PermitGrant(BOUNDS, 500)).permit().orElseThrow();
    assertTrue(coordinator.deadlineElapsed(102));
    assertEquals(BoundedLifecycle.Phase.CANCEL_REQUESTED, coordinator.record("walk").orElseThrow().phase());
    assertEquals(PermitAuthority.Gate.DENY, coordinator.gate(permit.permitId(), context(102).permitContext(),
        new PermitAuthority.Position(0, 64, 0), 0, 0));
    assertFalse(coordinator.dispatchNext(context(103), new ServerControlCoordinator.PermitGrant(BOUNDS, 500)).permit().isPresent());
  }

  @Test void rebindAdvancesBothAuthoritiesAndRejectsOldEpoch() {
    ServerControlCoordinator coordinator = coordinator(new FakeNotifier(false));
    coordinator.controlLost(new BoundedLifecycle.AuthorityEvidence("control-lost"), 101);
    AuthorityPolicy.Binding nextPolicy = new AuthorityPolicy.Binding("actor", "world", "body", "next", 2);
    PermitAuthority.Binding nextPermit = new PermitAuthority.Binding("actor", "world", "body", "next", 2,
        "next-boot", "next-connection");
    BoundedLifecycle.FenceProof fence = new BoundedLifecycle.FenceProof(
        new BoundedLifecycle.AuthorityEvidence("quiescent-server-event"),
        BoundedLifecycle.PermitFence.REVOKED, BoundedLifecycle.BodyFence.QUIESCENT);
    assertTrue(coordinator.rebind(nextPolicy, nextPermit,
        new ServerControlCoordinator.RebindProof(fence,
            new ServerControlCoordinator.QuiescenceProof("quiescent-server-event")), 101));
    assertFalse(coordinator.admit(proposal("old", 500), OBS, 100, 102).accepted());
    assertTrue(coordinator.admit(proposal("new", nextPolicy, 500), OBS, 100, 102).accepted());
  }

  @Test void invalidRebindDoesNotAdvanceEitherAuthority() {
    ServerControlCoordinator coordinator = coordinator(new FakeNotifier(false));
    coordinator.controlLost(new BoundedLifecycle.AuthorityEvidence("control-lost"), 101);
    AuthorityPolicy.Binding nextPolicy = new AuthorityPolicy.Binding("actor", "world", "body", "next", 2);
    PermitAuthority.Binding wrongPermit = new PermitAuthority.Binding("actor", "world", "other", "next", 2,
        "next-boot", "next-connection");
    BoundedLifecycle.FenceProof fence = new BoundedLifecycle.FenceProof(
        new BoundedLifecycle.AuthorityEvidence("quiescent-server-event"),
        BoundedLifecycle.PermitFence.REVOKED, BoundedLifecycle.BodyFence.QUIESCENT);
    assertFalse(coordinator.rebind(nextPolicy, wrongPermit,
        new ServerControlCoordinator.RebindProof(fence,
            new ServerControlCoordinator.QuiescenceProof("quiescent-server-event")), 101));
    assertFalse(coordinator.admit(proposal("next", nextPolicy, 500), OBS, 100, 102).accepted());
  }

  @Test void lookOrientationComesFromAcceptedTargetAndTrustedOrigin() {
    ServerControlCoordinator coordinator = coordinator(new FakeNotifier(false));
    AuthorityPolicy.Proposal look = new AuthorityPolicy.Proposal("look", POLICY_BINDING,
        AuthorityPolicy.Capability.LOOK, 100, 500, new AuthorityPolicy.Basis("obs", "boot", 1),
        "minecraft:overworld", new AuthorityPolicy.Position(-0.07, 64, -3.999), 0);
    assertTrue(coordinator.admit(look, OBS, 100, 100).accepted());
    PermitAuthority.Bounds lookBounds = new PermitAuthority.Bounds("minecraft:overworld",
        new PermitAuthority.Position(-4, 0, -4), new PermitAuthority.Position(4, 128, 4), 0);
    PermitAuthority.Permit permit = coordinator.dispatchNext(context(101),
        new ServerControlCoordinator.PermitGrant(lookBounds, 500)).permit().orElseThrow();
    assertEquals(179, permit.approvedOrientation().yaw(), 0.02);
    assertEquals(0, permit.approvedOrientation().pitch(), 0.001);
  }

  @Test void coincidentLookTargetFailsClosedButVerticalTargetPreservesTrustedYaw() {
    ServerControlCoordinator coincident = coordinator(new FakeNotifier(false));
    AuthorityPolicy.Proposal same = new AuthorityPolicy.Proposal("same", POLICY_BINDING,
        AuthorityPolicy.Capability.LOOK, 100, 500, new AuthorityPolicy.Basis("obs", "boot", 1),
        "minecraft:overworld", new AuthorityPolicy.Position(0, 64, 0), 0);
    assertTrue(coincident.admit(same, OBS, 100, 100).accepted());
    PermitAuthority.Bounds lookBounds = new PermitAuthority.Bounds("minecraft:overworld",
        new PermitAuthority.Position(-4, 0, -4), new PermitAuthority.Position(4, 128, 4), 0);
    assertEquals(ServerControlCoordinator.DispatchStatus.DISPATCH_FAILED,
        coincident.dispatchNext(context(101), new ServerControlCoordinator.PermitGrant(lookBounds, 500)).status());

    ServerControlCoordinator vertical = coordinator(new FakeNotifier(false));
    AuthorityPolicy.Proposal above = new AuthorityPolicy.Proposal("above", POLICY_BINDING,
        AuthorityPolicy.Capability.LOOK, 100, 500, new AuthorityPolicy.Basis("obs", "boot", 1),
        "minecraft:overworld", new AuthorityPolicy.Position(0, 65, 0), 0);
    assertTrue(vertical.admit(above, OBS, 100, 100).accepted());
    PermitAuthority.Permit permit = vertical.dispatchNext(context(101, 37, 0),
        new ServerControlCoordinator.PermitGrant(lookBounds, 500)).permit().orElseThrow();
    assertEquals(37, permit.approvedOrientation().yaw(), 0.001);
    assertEquals(-90, permit.approvedOrientation().pitch(), 0.001);
  }

  @Test void narrowerGrantRejectsLookTargetOutsideAndLeavesNoExecutingPermit() {
    FakeNotifier notifier = new FakeNotifier(false);
    ServerControlCoordinator coordinator = coordinator(notifier);
    AuthorityPolicy.Proposal look = new AuthorityPolicy.Proposal("narrow-look", POLICY_BINDING,
        AuthorityPolicy.Capability.LOOK, 100, 500, new AuthorityPolicy.Basis("obs", "boot", 1),
        "minecraft:overworld", new AuthorityPolicy.Position(4, 64, 0), 0);
    assertTrue(coordinator.admit(look, OBS, 100, 100).accepted());
    PermitAuthority.Bounds narrow = new PermitAuthority.Bounds("minecraft:overworld",
        new PermitAuthority.Position(-1, 0, -1), new PermitAuthority.Position(1, 128, 1), 0);

    ServerControlCoordinator.DispatchResult result = coordinator.dispatchNext(context(101),
        new ServerControlCoordinator.PermitGrant(narrow, 500));
    assertEquals(ServerControlCoordinator.DispatchStatus.DISPATCH_FAILED, result.status());
    assertTrue(result.permit().isEmpty());
    assertEquals(BoundedLifecycle.Terminal.FAILED,
        coordinator.record("narrow-look").orElseThrow().terminal());
    assertEquals(BoundedLifecycle.Effect.NONE,
        coordinator.record("narrow-look").orElseThrow().effect());
    assertEquals(0, notifier.permits);
  }

  @Test void narrowerGrantRejectsWalkTargetOutsideAndLeavesNoExecutingPermit() {
    FakeNotifier notifier = new FakeNotifier(false);
    ServerControlCoordinator coordinator = coordinator(notifier);
    AuthorityPolicy.Proposal walk = new AuthorityPolicy.Proposal("narrow-walk", POLICY_BINDING,
        AuthorityPolicy.Capability.BOUNDED_WALK, 100, 500,
        new AuthorityPolicy.Basis("obs", "boot", 1), "minecraft:overworld",
        new AuthorityPolicy.Position(2, 64, 0), 2);
    assertTrue(coordinator.admit(walk, OBS, 100, 100).accepted());
    PermitAuthority.Bounds narrow = new PermitAuthority.Bounds("minecraft:overworld",
        new PermitAuthority.Position(-1, 0, -1), new PermitAuthority.Position(1, 128, 1), 2);

    ServerControlCoordinator.DispatchResult result = coordinator.dispatchNext(context(101),
        new ServerControlCoordinator.PermitGrant(narrow, 500));
    assertEquals(ServerControlCoordinator.DispatchStatus.DISPATCH_FAILED, result.status());
    assertTrue(result.permit().isEmpty());
    assertEquals(BoundedLifecycle.Terminal.FAILED,
        coordinator.record("narrow-walk").orElseThrow().terminal());
    assertEquals(BoundedLifecycle.Effect.NONE,
        coordinator.record("narrow-walk").orElseThrow().effect());
    assertEquals(0, notifier.permits);
  }

  @Test void targetOnInclusiveGrantBoundaryIsAcceptedWithoutOrientationDrift() {
    ServerControlCoordinator coordinator = coordinator(new FakeNotifier(false));
    AuthorityPolicy.Proposal look = new AuthorityPolicy.Proposal("edge", POLICY_BINDING,
        AuthorityPolicy.Capability.LOOK, 100, 500, new AuthorityPolicy.Basis("obs", "boot", 1),
        "minecraft:overworld", new AuthorityPolicy.Position(1, 64, 0), 0);
    assertTrue(coordinator.admit(look, OBS, 100, 100).accepted());
    PermitAuthority.Bounds edge = new PermitAuthority.Bounds("minecraft:overworld",
        new PermitAuthority.Position(-1, 0, -1), new PermitAuthority.Position(1, 128, 1), 0);

    PermitAuthority.Permit permit = coordinator.dispatchNext(context(101),
        new ServerControlCoordinator.PermitGrant(edge, 500)).permit().orElseThrow();
    assertEquals(-90, permit.approvedOrientation().yaw(), 0.001);
    assertEquals(0, permit.approvedOrientation().pitch(), 0.001);
  }

  private static ServerControlCoordinator coordinator(FakeNotifier notifier) {
    BoundedLifecycle lifecycle = new BoundedLifecycle(POLICY_BINDING, REGION, Set.of(AuthorityPolicy.Capability.values()), 100);
    return new ServerControlCoordinator(lifecycle, new PermitAuthority(PERMIT_BINDING), PERMIT_BINDING, notifier);
  }
  private static AuthorityPolicy.Proposal proposal(String id, long deadline) { return new AuthorityPolicy.Proposal(id, POLICY_BINDING, AuthorityPolicy.Capability.BOUNDED_WALK, 100, deadline, new AuthorityPolicy.Basis("obs", "boot", 1), "minecraft:overworld", new AuthorityPolicy.Position(2, 64, 0), 2); }
  private static AuthorityPolicy.Proposal proposal(String id, AuthorityPolicy.Binding binding, long deadline) { return new AuthorityPolicy.Proposal(id, binding, AuthorityPolicy.Capability.BOUNDED_WALK, 100, deadline, new AuthorityPolicy.Basis("obs", "boot", 1), "minecraft:overworld", new AuthorityPolicy.Position(2, 64, 0), 2); }
  private static ServerControlCoordinator.DispatchContext context(long now) { return context(now, 0, 0); }
  private static ServerControlCoordinator.DispatchContext context(long now, double yaw, double pitch) { return new ServerControlCoordinator.DispatchContext(new PermitAuthority.CurrentContext(PERMIT_BINDING, "minecraft:overworld", new PermitAuthority.Position(0, 64, 0), now, 500, 500), OBS, 100, yaw, pitch); }
  private static final class FakeNotifier implements ServerControlCoordinator.PermitNotifier {
    private final boolean fails;
    private final boolean revokeFails;
    private int permits;
    private int revokes;
    private FakeNotifier(boolean fails) { this(fails, false); }
    private FakeNotifier(boolean fails, boolean revokeFails) { this.fails = fails; this.revokeFails = revokeFails; }
    @Override public void send(PermitAuthority.Permit permit) { if (fails) throw new IllegalStateException("send failed"); permits++; }
    @Override public void revoke(PermitAuthority.Binding binding, PermitAuthority.RevokeReason reason) { revokes++; if (revokeFails) throw new IllegalStateException("revoke failed"); }
  }
}
