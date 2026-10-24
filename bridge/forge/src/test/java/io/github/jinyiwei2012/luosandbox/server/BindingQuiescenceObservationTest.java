package io.github.jinyiwei2012.luosandbox.server;

import static io.github.jinyiwei2012.luosandbox.server.BindingQuiescenceObservation.Scope;
import static io.github.jinyiwei2012.luosandbox.server.BindingQuiescenceObservation.Status;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.jinyiwei2012.luosandbox.server.ControlledPacketGuard.Decision;
import io.github.jinyiwei2012.luosandbox.server.ForgePacketDefenseBridgeTest.Fixture;
import io.github.jinyiwei2012.luosandbox.server.ForgePacketDefenseBridgeTest.PlayerHandle;
import io.github.jinyiwei2012.luosandbox.servercontrol.PermitAuthority;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class BindingQuiescenceObservationTest {
  private static final PermitAuthority.Binding BINDING = ForgePacketDefenseBridgeTest.BINDING;
  private static final ControlledPacketGuard.TrustedThreadGate OWNER = () -> true;
  private static final ControlledPacketGuard.TrustedThreadGate FOREIGN = () -> false;

  /** Real chain: LOOK permit, tracked movement, real PREPARE; movement optionally still inside. */
  private static final class Prepared {
    final Fixture fixture = new Fixture();
    final Object server = new Object();
    final Object listener = new Object();
    final ForgePacketDefenseBridge<PlayerHandle> bridge;
    final ControlledIdentityRegistry.PrepareFenceReceipt receipt;
    final ControlledPacketGuard.TrustedThreadGate trackerGate;

    Prepared(boolean returnMovement) {
      this(returnMovement, OWNER);
    }

    Prepared(boolean returnMovement, ControlledPacketGuard.TrustedThreadGate trackerGate) {
      this.trackerGate = trackerGate;
      ControlledPacketGuard.TrustedCurrentState state = fixture.issueLook();
      bridge = fixture.bridge(Optional.of(state));
      assertEquals(
          Decision.ALLOW_MOVEMENT_PROPOSAL,
          bridge.evaluateTrackedPlayerMovement(
              server, listener, trackerGate, fixture.player,
              ForgePacketDefenseBridgeTest.movement()));
      receipt = fixture.guard(Optional.of(state)).beginBindingPrepare(fixture.player, BINDING, 200)
          .orElseThrow();
      if (returnMovement) bridge.returnTrackedPlayerMovement(server, listener);
    }

    Optional<BindingQuiescenceObservation> mint(long now) {
      return bridge.mintBindingQuiescenceObservation(
          OWNER, server, listener, fixture.player, BINDING, receipt, now);
    }

    Optional<BindingQuiescenceObservation.Snapshot> redeem(
        BindingQuiescenceObservation observation, long now) {
      return bridge.redeemBindingQuiescenceObservation(
          OWNER, observation, server, listener, fixture.player, BINDING, receipt, now);
    }
  }

  private static void assertOnlyObservableScopesDrained(
      BindingQuiescenceObservation.Snapshot snapshot) {
    assertEquals(Status.DRAINED, snapshot.status(Scope.MOVEMENT_HANDLER));
    assertEquals(Status.DRAINED, snapshot.status(Scope.LOCAL_PERMIT_AUTHORITY));
    assertEquals(Status.UNAVAILABLE, snapshot.status(Scope.CHAT_DEFERRED));
    assertEquals(Status.UNAVAILABLE, snapshot.status(Scope.PERMIT_QUEUE_WATCHDOG));
    assertEquals(Status.UNAVAILABLE, snapshot.status(Scope.CLIENT_PERMIT_CONSUMPTION));
    assertEquals(Status.UNAVAILABLE, snapshot.status(Scope.SCHEDULED_SERVER_TASKS));
    assertFalse(snapshot.complete());
  }

  @Test
  void oldBodyDrainCannotSatisfySamePlayerAndFullBindingReboundBody() {
    Prepared p = new Prepared(true);
    assertOnlyObservableScopesDrained(p.mint(201).orElseThrow().mintSnapshot());
    assertTrue(p.fixture.registry.fence(p.fixture.player, PermitAuthority.RevokeReason.CONTROL_LOST));
    assertTrue(p.fixture.registry.bindTrusted(p.fixture.player, BINDING, p.fixture.coordinator));
    ControlledIdentityRegistry.PrepareFenceReceipt next =
        p.fixture.registry.beginBindingPrepare(p.fixture.player, BINDING, 300).orElseThrow();
    BindingQuiescenceObservation observation = p.bridge.mintBindingQuiescenceObservation(
        OWNER, p.server, p.listener, p.fixture.player, BINDING, next, 301).orElseThrow();
    assertEquals(Status.NOT_DRAINED, observation.mintSnapshot().status(Scope.MOVEMENT_HANDLER));
    assertFalse(observation.mintSnapshot().complete());
  }

  @Test
  void samplingTrackerGateRefencesAndRejectsStaleMintAndRedeem() {
    for (boolean redeem : new boolean[] {false, true}) {
      AtomicReference<Runnable> callback = new AtomicReference<>(() -> {});
      Prepared p = new Prepared(true, () -> { callback.get().run(); return true; });
      BindingQuiescenceObservation observation = redeem ? p.mint(201).orElseThrow() : null;
      AtomicBoolean once = new AtomicBoolean();
      callback.set(() -> {
        if (!once.compareAndSet(false, true)) return;
        assertTrue(p.fixture.registry.fence(p.fixture.player, PermitAuthority.RevokeReason.CONTROL_LOST));
        assertTrue(p.fixture.registry.bindTrusted(p.fixture.player, BINDING, p.fixture.coordinator));
        assertTrue(p.fixture.registry.beginBindingPrepare(p.fixture.player, BINDING, 202).isPresent());
      });
      if (redeem) {
        assertTrue(p.redeem(observation, 202).isEmpty());
        assertTrue(observation.isSpent());
      } else {
        assertTrue(p.mint(202).isEmpty());
      }
      assertTrue(once.get());
    }
  }

  @Test
  void samplingIdentityAdapterReleaseRejectsStaleEvidence() {
    for (boolean redeem : new boolean[] {false, true}) {
      AtomicReference<Runnable> callback = new AtomicReference<>(() -> {});
      // Arm only at the tracker sampling boundary, after the initial validated fence view.
      AtomicBoolean armed = new AtomicBoolean();
      Prepared sampled = new Prepared(true, () -> { callback.get().run(); return true; });
      BindingQuiescenceObservation sampledObservation = redeem ? sampled.mint(201).orElseThrow() : null;
      callback.set(() -> {
        if (armed.compareAndSet(false, true)) {
          sampled.fixture.identityRead = () -> {
            sampled.fixture.identityRead = () -> {};
            assertTrue(sampled.fixture.registry.releaseOffline(
                sampled.fixture.player, PermitAuthority.RevokeReason.CONTROL_LOST));
          };
        }
      });
      if (redeem) {
        assertTrue(sampled.redeem(sampledObservation, 202).isEmpty());
        assertTrue(sampledObservation.isSpent());
      } else {
        assertTrue(sampled.mint(202).isEmpty());
      }
      assertTrue(armed.get());
    }
  }

  @Test
  void nearExpiryMintDoesNotRefreshAndRegistryRelativeRollbackRejects() {
    Prepared p = new Prepared(true);
    BindingQuiescenceObservation near = p.mint(5_199).orElseThrow();
    assertTrue(p.redeem(near, 5_200).isEmpty());
    assertTrue(near.isSpent());
    Prepared rollback = new Prepared(true);
    BindingQuiescenceObservation observation = rollback.mint(201).orElseThrow();
    assertTrue(rollback.fixture.registry.validatesPrepareFence(
        rollback.receipt, rollback.fixture.player, BINDING, 300));
    assertTrue(rollback.mint(250).isEmpty());
    assertTrue(rollback.redeem(observation, 250).isEmpty());
    assertTrue(observation.isSpent());
  }

  @Test
  void eachFullBindingFieldAndActualConnectionIdentityRejectAndSpend() {
    PermitAuthority.Binding[] mismatches = {
      new PermitAuthority.Binding("other", "world", "body", "session", 1, "boot", "connection"),
      new PermitAuthority.Binding("actor", "other", "body", "session", 1, "boot", "connection"),
      new PermitAuthority.Binding("actor", "world", "other", "session", 1, "boot", "connection"),
      new PermitAuthority.Binding("actor", "world", "body", "other", 1, "boot", "connection"),
      new PermitAuthority.Binding("actor", "world", "body", "session", 2, "boot", "connection"),
      new PermitAuthority.Binding("actor", "world", "body", "session", 1, "other", "connection"),
      new PermitAuthority.Binding("actor", "world", "body", "session", 1, "boot", "other")
    };
    for (PermitAuthority.Binding mismatch : mismatches) {
      Prepared p = new Prepared(true);
      BindingQuiescenceObservation observation = p.mint(201).orElseThrow();
      assertTrue(p.bridge.redeemBindingQuiescenceObservation(
          OWNER, observation, p.server, p.listener, p.fixture.player, mismatch, p.receipt, 202).isEmpty());
      assertTrue(observation.isSpent());
    }
    Prepared p = new Prepared(true);
    BindingQuiescenceObservation observation = p.mint(201).orElseThrow();
    p.fixture.connectionOverride = new Object();
    assertTrue(p.mint(202).isEmpty());
    assertTrue(p.redeem(observation, 202).isEmpty());
    assertTrue(observation.isSpent());
  }

  @Test
  void rejectedOwnerGatesNeverReadIdentityOrTrackerState() {
    Thread owner = Thread.currentThread();
    AtomicInteger trackerCalls = new AtomicInteger();
    Prepared p = new Prepared(true, () -> {
      assertSame(owner, Thread.currentThread());
      trackerCalls.incrementAndGet();
      return true;
    });
    ControlledPacketGuard.TrustedThreadGate[] gates = {
      null, FOREIGN, () -> { throw new IllegalStateException("gate"); }
    };
    for (ControlledPacketGuard.TrustedThreadGate gate : gates) {
      BindingQuiescenceObservation observation = p.mint(201).orElseThrow();
      int identities = p.fixture.identityReads;
      int tracked = trackerCalls.get();
      assertTrue(p.bridge.mintBindingQuiescenceObservation(
          gate, p.server, p.listener, p.fixture.player, BINDING, p.receipt, 202).isEmpty());
      assertTrue(p.bridge.redeemBindingQuiescenceObservation(
          gate, observation, p.server, p.listener, p.fixture.player, BINDING, p.receipt, 202).isEmpty());
      assertTrue(observation.isSpent());
      assertEquals(identities, p.fixture.identityReads);
      assertEquals(tracked, trackerCalls.get());
    }
  }

  @Test
  void offThreadUninstallPublishesOnlyAndOwnerRejectsAllObservationAndMovement() throws Exception {
    Thread owner = Thread.currentThread();
    AtomicInteger calls = new AtomicInteger();
    Prepared p = new Prepared(true, () -> {
      assertSame(owner, Thread.currentThread());
      calls.incrementAndGet();
      return true;
    });
    ForgePacketDefenseBridge.OwnerManager<Object, PlayerHandle> owners =
        new ForgePacketDefenseBridge.OwnerManager<>();
    ForgePacketDefenseBridge.OwnerHandle<Object> handle = owners.install(p.server, p.bridge).orElseThrow();
    BindingQuiescenceObservation observation = p.mint(201).orElseThrow();
    int before = calls.get();
    CountDownLatch start = new CountDownLatch(1);
    CountDownLatch published = new CountDownLatch(1);
    AtomicReference<Throwable> failure = new AtomicReference<>();
    Thread teardown = new Thread(() -> {
      try {
        if (!start.await(5, TimeUnit.SECONDS)) throw new AssertionError("start timeout");
        assertTrue(handle.uninstall(p.server));
      } catch (Throwable error) { failure.set(error); }
      finally { published.countDown(); }
    });
    teardown.start();
    start.countDown();
    assertTrue(published.await(5, TimeUnit.SECONDS));
    teardown.join();
    assertEquals(null, failure.get());
    assertEquals(before, calls.get());
    int identities = p.fixture.identityReads;
    assertTrue(p.mint(202).isEmpty());
    assertTrue(p.redeem(observation, 202).isEmpty());
    assertTrue(observation.isSpent());
    assertTrue(p.bridge.observeProtectedMovementDrained(
        p.server, p.listener, p.fixture.player, BINDING, p.receipt, 202).isEmpty());
    assertEquals(Decision.DENY, p.bridge.evaluateTrackedPlayerMovement(
        p.server, p.listener, OWNER, p.fixture.player, ForgePacketDefenseBridgeTest.movement()));
    assertEquals(identities, p.fixture.identityReads);
    assertEquals(before, calls.get());
  }

  @Test
  void samplingRechecksOwnerGateBeforeTrackerAndCoordinatorReads() {
    Prepared p = new Prepared(true);
    AtomicInteger calls = new AtomicInteger();
    int[] identityReadsAtBoundary = {-1};
    ControlledPacketGuard.TrustedThreadGate gate = () -> {
      if (calls.incrementAndGet() == 1) return true;
      identityReadsAtBoundary[0] = p.fixture.identityReads;
      return false;
    };
    assertTrue(p.bridge.mintBindingQuiescenceObservation(
        gate, p.server, p.listener, p.fixture.player, BINDING, p.receipt, 201).isEmpty());
    assertEquals(2, calls.get());
    assertEquals(identityReadsAtBoundary[0], p.fixture.identityReads);
  }

  @Test
  void uninstallWithoutTrackerDeniesBeforeRegistryOrStateProvider() {
    Fixture fixture = new Fixture();
    AtomicInteger reads = new AtomicInteger();
    ForgePacketDefenseBridge<PlayerHandle> bridge = new ForgePacketDefenseBridge<>(
        fixture.registry, (player, body) -> { reads.incrementAndGet(); return Optional.empty(); });
    Object server = new Object();
    ForgePacketDefenseBridge.OwnerManager<Object, PlayerHandle> owners =
        new ForgePacketDefenseBridge.OwnerManager<>();
    assertTrue(owners.install(server, bridge).orElseThrow().uninstall(server));
    assertEquals(Decision.DENY, bridge.evaluateTrackedPlayerMovement(
        server, new Object(), OWNER, fixture.player, ForgePacketDefenseBridgeTest.movement()));
    assertEquals(0, reads.get());
    assertEquals(0, fixture.identityReads);
  }

  @Test
  void resamplingDoesNotSendRevokeRestoreOrDeliver() {
    Prepared p = new Prepared(true);
    int sends = p.fixture.sends;
    int revokes = p.fixture.revokes;
    for (int now = 201; now < 211; now += 2) {
      assertOnlyObservableScopesDrained(p.redeem(p.mint(now).orElseThrow(), now + 1).orElseThrow());
    }
    assertEquals(sends, p.fixture.sends);
    assertEquals(revokes, p.fixture.revokes);
    assertTrue(p.fixture.registry.current().isEmpty());
    assertTrue(p.fixture.coordinator.bindingPrepareFenceHolds(BINDING));
  }

  @Test
  void realChainMintAndRedeemOnceReportsObservableScopesButNeverComplete() {
    Prepared p = new Prepared(true);
    BindingQuiescenceObservation observation = p.mint(201).orElseThrow();
    assertOnlyObservableScopesDrained(observation.mintSnapshot());
    assertFalse(observation.isSpent());
    assertOnlyObservableScopesDrained(p.redeem(observation, 202).orElseThrow());
    assertTrue(observation.isSpent());
  }

  @Test
  void movementNotYetReturnedIsNotDrainedUntilResampledAtRedeem() {
    Prepared p = new Prepared(false);
    BindingQuiescenceObservation observation = p.mint(201).orElseThrow();
    assertEquals(Status.NOT_DRAINED, observation.mintSnapshot().status(Scope.MOVEMENT_HANDLER));
    assertEquals(Status.DRAINED, observation.mintSnapshot().status(Scope.LOCAL_PERMIT_AUTHORITY));
    assertFalse(observation.mintSnapshot().complete());
    p.bridge.returnTrackedPlayerMovement(p.server, p.listener);
    assertOnlyObservableScopesDrained(p.redeem(observation, 202).orElseThrow());

    Prepared stillInside = new Prepared(false);
    BindingQuiescenceObservation second = stillInside.mint(201).orElseThrow();
    BindingQuiescenceObservation.Snapshot snapshot = stillInside.redeem(second, 202).orElseThrow();
    assertEquals(Status.NOT_DRAINED, snapshot.status(Scope.MOVEMENT_HANDLER));
    assertFalse(snapshot.complete());
  }

  @Test
  void deadlineIsTheOriginalReceiptDeadlineAndTimeoutConsumes() {
    Prepared edge = new Prepared(true);
    BindingQuiescenceObservation last = edge.mint(201).orElseThrow();
    assertOnlyObservableScopesDrained(edge.redeem(last, 5_199).orElseThrow());

    Prepared p = new Prepared(true);
    BindingQuiescenceObservation observation = p.mint(201).orElseThrow();
    assertTrue(p.redeem(observation, 5_200).isEmpty());
    assertTrue(observation.isSpent());
    assertTrue(p.redeem(observation, 202).isEmpty());

    Prepared late = new Prepared(true);
    assertTrue(late.mint(5_200).isEmpty());
  }

  @Test
  void clockGoingBackwardsFailsAndConsumes() {
    Prepared p = new Prepared(true);
    BindingQuiescenceObservation observation = p.mint(210).orElseThrow();
    assertTrue(p.redeem(observation, 205).isEmpty());
    assertTrue(observation.isSpent());
    assertTrue(p.redeem(observation, 211).isEmpty());
  }

  @Test
  void secondRedeemFailsEvenWithIdenticalInputs() {
    Prepared p = new Prepared(true);
    BindingQuiescenceObservation observation = p.mint(201).orElseThrow();
    assertTrue(p.redeem(observation, 202).isPresent());
    assertTrue(p.redeem(observation, 203).isEmpty());
    assertTrue(p.redeem(null, 203).isEmpty());
  }

  @Test
  void foreignThreadCannotMintOrRedeemAndFailedRedeemConsumes() throws Exception {
    Thread ownerThread = Thread.currentThread();
    ControlledPacketGuard.TrustedThreadGate gate = () -> Thread.currentThread() == ownerThread;
    Prepared p = new Prepared(true, gate);

    AtomicReference<Optional<BindingQuiescenceObservation>> foreignMint = new AtomicReference<>();
    Thread minter =
        new Thread(
            () ->
                foreignMint.set(
                    p.bridge.mintBindingQuiescenceObservation(
                        gate, p.server, p.listener, p.fixture.player, BINDING, p.receipt, 201)));
    minter.start();
    minter.join();
    assertTrue(foreignMint.get().isEmpty());
    assertTrue(
        p.bridge.mintBindingQuiescenceObservation(
                FOREIGN, p.server, p.listener, p.fixture.player, BINDING, p.receipt, 201)
            .isEmpty());

    BindingQuiescenceObservation observation =
        p.bridge.mintBindingQuiescenceObservation(
                gate, p.server, p.listener, p.fixture.player, BINDING, p.receipt, 201)
            .orElseThrow();
    AtomicReference<Optional<BindingQuiescenceObservation.Snapshot>> foreignRedeem =
        new AtomicReference<>();
    Thread redeemer =
        new Thread(
            () ->
                foreignRedeem.set(
                    p.bridge.redeemBindingQuiescenceObservation(
                        gate, observation, p.server, p.listener, p.fixture.player, BINDING,
                        p.receipt, 202)));
    redeemer.start();
    redeemer.join();
    assertTrue(foreignRedeem.get().isEmpty());
    assertTrue(observation.isSpent());
    assertTrue(
        p.bridge.redeemBindingQuiescenceObservation(
                gate, observation, p.server, p.listener, p.fixture.player, BINDING, p.receipt, 203)
            .isEmpty());
  }

  @Test
  void wrongExactIdentitiesFailAndEachConsumes() {
    PermitAuthority.Binding otherBinding =
        new PermitAuthority.Binding("actor", "world", "body", "session", 2, "boot", "connection");

    Prepared wrongServer = new Prepared(true);
    BindingQuiescenceObservation a = wrongServer.mint(201).orElseThrow();
    assertTrue(
        wrongServer.bridge.redeemBindingQuiescenceObservation(
                OWNER, a, new Object(), wrongServer.listener, wrongServer.fixture.player, BINDING,
                wrongServer.receipt, 202)
            .isEmpty());
    assertTrue(wrongServer.redeem(a, 203).isEmpty());

    Prepared wrongListener = new Prepared(true);
    BindingQuiescenceObservation b = wrongListener.mint(201).orElseThrow();
    assertTrue(
        wrongListener.bridge.redeemBindingQuiescenceObservation(
                OWNER, b, wrongListener.server, new Object(), wrongListener.fixture.player,
                BINDING, wrongListener.receipt, 202)
            .isEmpty());
    assertTrue(wrongListener.redeem(b, 203).isEmpty());

    Prepared wrongPlayer = new Prepared(true);
    BindingQuiescenceObservation c = wrongPlayer.mint(201).orElseThrow();
    assertTrue(
        wrongPlayer.bridge.redeemBindingQuiescenceObservation(
                OWNER, c, wrongPlayer.server, wrongPlayer.listener,
                new PlayerHandle(new Object()), BINDING, wrongPlayer.receipt, 202)
            .isEmpty());
    assertTrue(wrongPlayer.redeem(c, 203).isEmpty());

    Prepared wrongBinding = new Prepared(true);
    BindingQuiescenceObservation d = wrongBinding.mint(201).orElseThrow();
    assertTrue(
        wrongBinding.bridge.redeemBindingQuiescenceObservation(
                OWNER, d, wrongBinding.server, wrongBinding.listener, wrongBinding.fixture.player,
                otherBinding, wrongBinding.receipt, 202)
            .isEmpty());
    assertTrue(wrongBinding.redeem(d, 203).isEmpty());

    Prepared wrongReceipt = new Prepared(true);
    Prepared otherReceiptSource = new Prepared(true);
    BindingQuiescenceObservation e = wrongReceipt.mint(201).orElseThrow();
    assertTrue(
        wrongReceipt.bridge.redeemBindingQuiescenceObservation(
                OWNER, e, wrongReceipt.server, wrongReceipt.listener, wrongReceipt.fixture.player,
                BINDING, otherReceiptSource.receipt, 202)
            .isEmpty());
    assertTrue(wrongReceipt.redeem(e, 203).isEmpty());
  }

  @Test
  void mintRejectsMismatchedIdentitiesAndMissingFence() {
    Prepared p = new Prepared(true);
    assertTrue(
        p.bridge.mintBindingQuiescenceObservation(
                OWNER, p.server, p.listener, new PlayerHandle(new Object()), BINDING, p.receipt,
                201)
            .isEmpty());
    Prepared other = new Prepared(true);
    assertTrue(
        p.bridge.mintBindingQuiescenceObservation(
                OWNER, p.server, p.listener, p.fixture.player, BINDING, other.receipt, 201)
            .isEmpty());
    assertTrue(
        p.bridge.mintBindingQuiescenceObservation(
                OWNER, p.server, p.listener, p.fixture.player, BINDING, null, 201)
            .isEmpty());
  }

  @Test
  void observationFromOneFixtureIsNotRedeemableInAnother() {
    Prepared first = new Prepared(true);
    Prepared second = new Prepared(true);
    BindingQuiescenceObservation observation = first.mint(201).orElseThrow();
    assertTrue(
        second.bridge.redeemBindingQuiescenceObservation(
                OWNER, observation, second.server, second.listener, second.fixture.player,
                BINDING, second.receipt, 202)
            .isEmpty());
    assertTrue(observation.isSpent());
    assertTrue(first.redeem(observation, 203).isEmpty());
    // The unrelated second fixture is unaffected.
    assertOnlyObservableScopesDrained(
        second.redeem(second.mint(204).orElseThrow(), 205).orElseThrow());
  }

  @Test
  void sameFixtureDifferentBridgeInstanceIsNotTheMintingOwner() {
    Prepared p = new Prepared(true);
    BindingQuiescenceObservation observation = p.mint(201).orElseThrow();
    ForgePacketDefenseBridge<PlayerHandle> lookalike =
        new ForgePacketDefenseBridge<>(p.fixture.registry, (player, body) -> Optional.empty());
    assertTrue(
        lookalike.redeemBindingQuiescenceObservation(
                OWNER, observation, p.server, p.listener, p.fixture.player, BINDING, p.receipt,
                202)
            .isEmpty());
    assertTrue(observation.isSpent());
  }

  @Test
  void controlLossInvalidatesUnredeemedObservation() {
    Prepared p = new Prepared(true);
    BindingQuiescenceObservation observation = p.mint(201).orElseThrow();
    assertTrue(p.fixture.registry.fence(p.fixture.player, PermitAuthority.RevokeReason.CONTROL_LOST));
    assertTrue(p.redeem(observation, 202).isEmpty());
    assertTrue(observation.isSpent());
  }

  @Test
  void offlineReleaseInvalidatesUnredeemedObservation() {
    Prepared p = new Prepared(true);
    BindingQuiescenceObservation observation = p.mint(201).orElseThrow();
    assertTrue(
        p.fixture.registry.releaseOffline(p.fixture.player, PermitAuthority.RevokeReason.CONTROL_LOST));
    assertTrue(p.redeem(observation, 202).isEmpty());
  }

  @Test
  void newPrepareGenerationInvalidatesPriorObservation() {
    Prepared p = new Prepared(true);
    BindingQuiescenceObservation observation = p.mint(201).orElseThrow();
    // A second PREPARE cannot be opened while the first fence is live.
    assertTrue(p.fixture.registry.beginBindingPrepare(p.fixture.player, BINDING, 202).isEmpty());
    // Control loss, re-binding the same trusted body and a fresh real PREPARE: new generation.
    assertTrue(p.fixture.registry.fence(p.fixture.player, PermitAuthority.RevokeReason.CONTROL_LOST));
    assertTrue(p.fixture.registry.bindTrusted(p.fixture.player, BINDING, p.fixture.coordinator));
    ControlledIdentityRegistry.PrepareFenceReceipt next =
        p.fixture.registry.beginBindingPrepare(p.fixture.player, BINDING, 300).orElseThrow();
    assertNotSame(p.receipt, next);
    assertTrue(
        p.bridge.redeemBindingQuiescenceObservation(
                OWNER, observation, p.server, p.listener, p.fixture.player, BINDING, next, 301)
            .isEmpty());
    assertTrue(observation.isSpent());
  }

  @Test
  void ownerUninstallInvalidatesUnredeemedObservation() {
    Prepared p = new Prepared(true);
    ForgePacketDefenseBridge.OwnerManager<Object, PlayerHandle> owners =
        new ForgePacketDefenseBridge.OwnerManager<>();
    ForgePacketDefenseBridge.OwnerHandle<Object> handle =
        owners.install(p.server, p.bridge).orElseThrow();
    BindingQuiescenceObservation observation = p.mint(201).orElseThrow();
    assertTrue(handle.uninstall(p.server));
    assertTrue(p.redeem(observation, 202).isEmpty());
    assertTrue(observation.isSpent());
    assertTrue(p.mint(203).isEmpty());
  }

  @Test
  void observerExceptionIsFailedNeverDrained() {
    AtomicBoolean trackerGateThrows = new AtomicBoolean();
    ControlledPacketGuard.TrustedThreadGate trackerGate =
        () -> {
          if (trackerGateThrows.get()) throw new IllegalStateException("observer failure");
          return true;
        };
    Prepared p = new Prepared(true, trackerGate);
    trackerGateThrows.set(true);
    BindingQuiescenceObservation observation = p.mint(201).orElseThrow();
    assertEquals(Status.FAILED, observation.mintSnapshot().status(Scope.MOVEMENT_HANDLER));
    assertFalse(observation.mintSnapshot().complete());
    BindingQuiescenceObservation.Snapshot redeemed = p.redeem(observation, 202).orElseThrow();
    assertEquals(Status.FAILED, redeemed.status(Scope.MOVEMENT_HANDLER));
    assertEquals(Status.DRAINED, redeemed.status(Scope.LOCAL_PERMIT_AUTHORITY));
    assertFalse(redeemed.complete());
  }

  @Test
  void throwingRedeemGateConsumesAndPoisons() {
    Prepared p = new Prepared(true);
    BindingQuiescenceObservation observation = p.mint(201).orElseThrow();
    assertTrue(
        p.bridge.redeemBindingQuiescenceObservation(
                () -> {
                  throw new IllegalStateException("gate failure");
                },
                observation, p.server, p.listener, p.fixture.player, BINDING, p.receipt, 202)
            .isEmpty());
    assertTrue(observation.isSpent());
    assertTrue(p.redeem(observation, 203).isEmpty());
  }

  @Test
  void redeemDoesNotMintOrRevivePermitAuthority() {
    Prepared p = new Prepared(true);
    BindingQuiescenceObservation observation = p.mint(201).orElseThrow();
    p.redeem(observation, 202).orElseThrow();
    // No current body, no restored authority: control stays revoked after redeem.
    assertTrue(p.fixture.registry.current().isEmpty());
    assertEquals(
        Decision.DENY,
        p.bridge.evaluatePlayerMovement(
            () -> true, () -> p.fixture.player, ForgePacketDefenseBridgeTest::movement));
  }

  @Test
  void identityEqualityOnlyAndNoPublicConstructionOrAuthorityLeak() throws Exception {
    Prepared p = new Prepared(true);
    BindingQuiescenceObservation one = p.mint(201).orElseThrow();
    assertNotEquals(one, p.mint(202).orElseThrow());
    assertSame(Object.class, BindingQuiescenceObservation.class.getMethod("equals", Object.class)
        .getDeclaringClass());
    assertSame(Object.class, BindingQuiescenceObservation.class.getMethod("hashCode")
        .getDeclaringClass());

    assertTrue(Modifier.isFinal(BindingQuiescenceObservation.class.getModifiers()));
    for (Constructor<?> constructor : BindingQuiescenceObservation.class.getDeclaredConstructors()) {
      assertTrue(Modifier.isPrivate(constructor.getModifiers()), constructor.toString());
    }
    for (Constructor<?> constructor :
        BindingQuiescenceObservation.Snapshot.class.getDeclaredConstructors()) {
      assertTrue(Modifier.isPrivate(constructor.getModifiers()), constructor.toString());
    }
    for (Class<?> type :
        new Class<?>[] {
          BindingQuiescenceObservation.class, BindingQuiescenceObservation.Snapshot.class
        }) {
      for (java.lang.reflect.Field field : type.getDeclaredFields()) {
        assertTrue(
            Modifier.isPrivate(field.getModifiers()) || field.isSynthetic(), field.toString());
      }
      for (Method method : type.getDeclaredMethods()) {
        int modifiers = method.getModifiers();
        if (!Modifier.isPublic(modifiers) && !Modifier.isProtected(modifiers)) continue;
        assertFalse(Modifier.isStatic(modifiers), "public static method: " + method);
        Class<?> returned = method.getReturnType();
        assertFalse(returned.getName().contains("servercontrol"), method.toString());
        assertFalse(returned == ControlledIdentityRegistry.PrepareFenceReceipt.class, method.toString());
        assertFalse(returned == ControlledIdentityRegistry.ControlledBody.class, method.toString());
        assertFalse(returned == PermitAuthority.Binding.class, method.toString());
        assertFalse(returned == PermitAuthority.Permit.class, method.toString());
        assertFalse(returned == PermitAuthority.class, method.toString());
        assertFalse(returned == Object.class, method.toString());
        for (Class<?> parameter : method.getParameterTypes()) {
          assertFalse(parameter.getName().contains("servercontrol"), method.toString());
        }
        String name = method.getName().toLowerCase();
        assertFalse(name.contains("ready"), name);
        assertFalse(name.contains("commit"), name);
        assertFalse(name.contains("rebind"), name);
        assertFalse(name.contains("permit"), name);
      }
    }
  }
}
