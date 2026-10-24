package io.github.jinyiwei2012.luosandbox.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.jinyiwei2012.luosandbox.policy.AuthorityPolicy;
import io.github.jinyiwei2012.luosandbox.servercontrol.PermitAuthority;
import io.github.jinyiwei2012.luosandbox.servercontrol.ServerControlCoordinator;
import io.github.jinyiwei2012.luosandbox.session.BoundedLifecycle;
import java.lang.reflect.Field;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ControlledIdentityRegistryTest {
  private static final PermitAuthority.Binding BINDING =
      new PermitAuthority.Binding("actor", "world", "body", "session", 1, "boot", "connection");

  @Test
  void resolvesOnlyExactPlayerAndConnectionReferences() {
    Fixture fixture = new Fixture();
    PlayerHandle player = new PlayerHandle(new Object(), new Object());

    assertTrue(fixture.registry.protectCandidate(player));
    assertTrue(fixture.registry.bindTrusted(player, BINDING, coordinator(BINDING, fixture.firstNotifier)));
    assertSame(player, fixture.registry.resolve(player).orElseThrow().player());

    assertFalse(
        fixture.registry.resolve(new PlayerHandle(player.identity, player.connection)).isPresent());
    assertFalse(fixture.registry.resolve(new PlayerHandle(player.identity, new Object())).isPresent());
  }

  @Test
  void untrustedCandidateAndBindingAreRejectedWithoutMatchingOtherPlayers() {
    Fixture fixture = new Fixture();
    fixture.allowProvision = false;
    PlayerHandle rejected = new PlayerHandle(new Object(), new Object());
    PlayerHandle other = new PlayerHandle(new Object(), new Object());

    assertFalse(fixture.registry.protectCandidate(rejected));
    assertFalse(fixture.registry.isProtectedCandidate(rejected));
    assertFalse(fixture.registry.isProtectedCandidate(other));
    assertFalse(
        fixture.registry.bindTrusted(rejected, BINDING, coordinator(BINDING, fixture.firstNotifier)));
  }

  @Test
  void successfulFenceThenExplicitReleasePermitsSecondBinding() {
    Fixture fixture = new Fixture();
    PlayerHandle first = new PlayerHandle(new Object(), new Object());
    PlayerHandle second = new PlayerHandle(new Object(), new Object());
    assertTrue(fixture.registry.protectCandidate(first));
    assertTrue(fixture.registry.bindTrusted(first, BINDING, coordinator(BINDING, fixture.firstNotifier)));

    assertFalse(fixture.registry.protectCandidate(second));
    assertFalse(fixture.registry.clearCandidate(first));
    assertTrue(fixture.registry.fence(first, PermitAuthority.RevokeReason.REBIND));
    assertTrue(fixture.registry.clearCandidate(first));
    assertTrue(fixture.registry.protectCandidate(second));
    assertTrue(
        fixture.registry.bindTrusted(
            second, nextBinding(), coordinator(nextBinding(), fixture.secondNotifier)));

    assertEquals(1, fixture.firstNotifier.revokes);
    assertTrue(fixture.registry.resolve(first).isEmpty());
    assertSame(second, fixture.registry.resolve(second).orElseThrow().player());
  }

  @Test
  void candidateCannotBeSilentlyReplaced() {
    Fixture fixture = new Fixture();
    PlayerHandle first = new PlayerHandle(new Object(), new Object());
    PlayerHandle second = new PlayerHandle(new Object(), new Object());
    assertTrue(fixture.registry.protectCandidate(first));
    assertFalse(fixture.registry.protectCandidate(second));
    assertTrue(fixture.registry.isProtectedCandidate(first));
    assertFalse(fixture.registry.isProtectedCandidate(second));
  }

  @Test
  void markerRunsBeforeCandidatePublicationAndFailureLeavesRegistryEmpty() {
    PlayerHandle player = new PlayerHandle(new Object(), new Object());
    boolean[] observedCandidate = {false};
    @SuppressWarnings("unchecked")
    ControlledIdentityRegistry<PlayerHandle>[] holder = new ControlledIdentityRegistry[1];
    holder[0] = new ControlledIdentityRegistry<>(
        new ControlledIdentityRegistry.IdentityAdapter<>() {
          @Override public Object playerIdentity(PlayerHandle value) { return value; }
          @Override public Object connectionToken(PlayerHandle value) { return value.connection; }
        },
        new ControlledIdentityRegistry.TrustedProvisionPolicy<>() {
          @Override public boolean mayProtectCandidate(PlayerHandle value, Object identity, Object connection) { return true; }
          @Override public boolean mayBind(PlayerHandle value, Object identity, Object connection, PermitAuthority.Binding binding, ServerControlCoordinator coordinator) { return true; }
        },
        (value, identity, connection) -> {
          observedCandidate[0] = holder[0].isProtectedCandidate(value);
          return false;
        });
    assertFalse(holder[0].protectCandidate(player));
    assertFalse(observedCandidate[0]);
    assertFalse(holder[0].isProtectedCandidate(player));
    assertTrue(holder[0].current().isEmpty());
  }

  @Test
  void throwingMarkerFailsClosedButSuccessfulMarkRemainsAfterLaterPolicyFailure() {
    PlayerHandle throwing = new PlayerHandle(new Object(), new Object());
    ControlledIdentityRegistry<PlayerHandle> broken = registryWithMarker((player, identity, connection) -> {
      throw new IllegalStateException("marker unavailable");
    });
    assertFalse(broken.protectCandidate(throwing));
    assertFalse(broken.isProtectedCandidate(throwing));

    PlayerHandle marked = new PlayerHandle(new Object(), new Object());
    ControlledIdentityRegistry<PlayerHandle> downstreamFailure = registryWithMarker((player, identity, connection) -> {
      player.marked = true;
      return true;
    });
    assertTrue(downstreamFailure.protectCandidate(marked));
    assertTrue(marked.marked);
    assertFalse(downstreamFailure.bindTrusted(marked, BINDING, coordinator(BINDING, new CountingNotifier())));
    assertTrue(marked.marked);
  }

  @Test
  void directReplacementIsForbiddenWhileFirstBodyIsCurrent() {
    Fixture fixture = new Fixture();
    PlayerHandle first = new PlayerHandle(new Object(), new Object());
    PlayerHandle second = new PlayerHandle(new Object(), new Object());
    ServerControlCoordinator shared = coordinator(BINDING, fixture.firstNotifier);
    assertTrue(fixture.registry.protectCandidate(first));
    assertTrue(fixture.registry.bindTrusted(first, BINDING, shared));
    assertFalse(fixture.registry.clearCandidate(first));
    assertFalse(fixture.registry.protectCandidate(second));
    assertFalse(fixture.registry.bindTrusted(second, BINDING, shared));
    assertSame(first, fixture.registry.current().orElseThrow().player());
  }

  @Test
  void fenceClearsBindingButKeepsConnectionDefaultDenied() {
    Fixture fixture = new Fixture();
    PlayerHandle player = new PlayerHandle(new Object(), new Object());
    assertTrue(fixture.registry.protectCandidate(player));
    assertTrue(fixture.registry.bindTrusted(player, BINDING, coordinator(BINDING, fixture.firstNotifier)));

    assertTrue(fixture.registry.fence(player, PermitAuthority.RevokeReason.CONTROL_LOST));
    assertTrue(fixture.registry.current().isEmpty());
    assertTrue(fixture.registry.isProtectedCandidate(player));
    assertTrue(fixture.registry.clearCandidate(player));
    assertEquals(1, fixture.firstNotifier.revokes);
  }

  @Test
  void candidateQuarantineTransfersOnlyAcrossSameConnectionReference() {
    Fixture fixture = new Fixture();
    Object connection = new Object();
    PlayerHandle previous = new PlayerHandle(new Object(), connection);
    PlayerHandle replacement = new PlayerHandle(new Object(), connection);
    PlayerHandle wrongConnection = new PlayerHandle(new Object(), new Object());
    assertTrue(fixture.registry.protectCandidate(previous));
    assertTrue(fixture.registry.bindTrusted(previous, BINDING,
        coordinator(BINDING, fixture.firstNotifier)));
    assertTrue(fixture.registry.fence(previous, PermitAuthority.RevokeReason.REBIND));

    assertFalse(fixture.registry.transferCandidate(previous, wrongConnection));
    assertTrue(fixture.registry.isProtectedCandidate(previous));
    assertTrue(fixture.registry.transferCandidate(previous, replacement));
    assertFalse(fixture.registry.isProtectedCandidate(previous));
    assertTrue(fixture.registry.isProtectedCandidate(replacement));
    assertTrue(fixture.registry.current().isEmpty());
  }

  @Test
  void fenceClearsCurrentBeforeClockFailureAndKeepsCandidateProtected() {
    Fixture fixture = new Fixture(() -> { throw new IllegalStateException("clock failed"); },
        (body, evidenceId, now) -> {});
    PlayerHandle player = new PlayerHandle(new Object(), new Object());
    assertTrue(fixture.registry.protectCandidate(player));
    assertTrue(fixture.registry.bindTrusted(player, BINDING, coordinator(BINDING, fixture.firstNotifier)));

    assertFalse(fixture.registry.fence(player, PermitAuthority.RevokeReason.CONTROL_LOST));
    assertTrue(fixture.registry.current().isEmpty());
    assertTrue(fixture.registry.isProtectedCandidate(player));
    assertFalse(fixture.registry.clearCandidate(player));
    assertFalse(fixture.registry.protectCandidate(new PlayerHandle(new Object(), new Object())));
  }

  @Test
  void cleanupFailureKeepsOldCandidateAndForbidsSecondBody() {
    Fixture fixture = new Fixture(() -> 101, (body, evidenceId, now) -> {
      throw new IllegalStateException("cleanup failed");
    });
    PlayerHandle first = new PlayerHandle(new Object(), new Object());
    PlayerHandle second = new PlayerHandle(new Object(), new Object());
    assertTrue(fixture.registry.protectCandidate(first));
    assertTrue(fixture.registry.bindTrusted(first, BINDING, coordinator(BINDING, fixture.firstNotifier)));
    assertFalse(fixture.registry.fence(first, PermitAuthority.RevokeReason.REBIND));
    assertTrue(fixture.registry.current().isEmpty());
    assertTrue(fixture.registry.isProtectedCandidate(first));
    assertFalse(fixture.registry.clearCandidate(first));
    assertFalse(fixture.registry.protectCandidate(second));
    assertFalse(fixture.registry.bindTrusted(
        second, nextBinding(), coordinator(nextBinding(), fixture.secondNotifier)));
    assertTrue(ForgeServerDefense.shouldDeny(
        fixture.registry.isProtectedCandidate(first), java.util.Optional.empty(),
        (binding, action) -> false, ForgeServerDefense.Action.RIGHT_CLICK_ITEM));
  }

  @Test
  void failedCleanupCanRetryAndOfflineReleaseIsIdempotent() {
    int[] attempts = {0};
    Fixture fixture = new Fixture(() -> 101, (body, evidenceId, now) -> {
      if (attempts[0]++ == 0) throw new IllegalStateException("first cleanup failed");
    });
    PlayerHandle player = new PlayerHandle(new Object(), new Object());
    assertTrue(fixture.registry.protectCandidate(player));
    assertTrue(fixture.registry.bindTrusted(player, BINDING,
        coordinator(BINDING, fixture.firstNotifier)));
    assertFalse(fixture.registry.fence(player, PermitAuthority.RevokeReason.CONTROL_LOST));
    assertTrue(fixture.registry.fence(player, PermitAuthority.RevokeReason.CONTROL_LOST));
    assertTrue(fixture.registry.clearCandidate(player));

    PlayerHandle offline = new PlayerHandle(new Object(), new Object());
    assertTrue(fixture.registry.protectCandidate(offline));
    assertTrue(fixture.registry.releaseOffline(
        offline, PermitAuthority.RevokeReason.CONTROL_LOST));
    assertTrue(fixture.registry.releaseOffline(
        offline, PermitAuthority.RevokeReason.CONTROL_LOST));
    assertFalse(fixture.registry.isProtectedCandidate(offline));
  }

  @Test
  void failedCloneFenceTransfersQuarantineAndRetriesCleanupBySameConnection() {
    int[] attempts = {0};
    Fixture fixture = new Fixture(() -> 101, (body, evidenceId, now) -> {
      if (attempts[0]++ == 0) throw new IllegalStateException("clone cleanup failed");
    });
    Object connection = new Object();
    PlayerHandle previous = new PlayerHandle(new Object(), connection);
    PlayerHandle replacement = new PlayerHandle(new Object(), connection);
    PlayerHandle wrongConnection = new PlayerHandle(new Object(), new Object());
    assertTrue(fixture.registry.protectCandidate(previous));
    assertTrue(fixture.registry.bindTrusted(previous, BINDING,
        coordinator(BINDING, fixture.firstNotifier)));

    assertFalse(fixture.registry.fence(previous, PermitAuthority.RevokeReason.CONTROL_LOST));
    assertTrue(fixture.registry.current().isEmpty());
    assertFalse(fixture.registry.transferCandidateQuarantine(previous, wrongConnection));
    assertTrue(fixture.registry.transferCandidateQuarantine(previous, replacement));
    assertFalse(fixture.registry.isProtectedCandidate(previous));
    assertTrue(fixture.registry.isProtectedCandidate(replacement));
    assertTrue(ForgeServerDefense.shouldDeny(
        fixture.registry.isProtectedCandidate(replacement), java.util.Optional.empty(),
        (binding, action) -> false, ForgeServerDefense.Action.RIGHT_CLICK_ITEM));
    assertFalse(fixture.registry.clearCandidate(replacement));
    assertFalse(fixture.registry.bindTrusted(replacement, nextBinding(),
        coordinator(nextBinding(), fixture.secondNotifier)));

    assertTrue(fixture.registry.fence(replacement, PermitAuthority.RevokeReason.CONTROL_LOST));
    assertTrue(fixture.registry.clearCandidate(replacement));
    assertFalse(fixture.registry.isProtectedCandidate(replacement));
  }

  @Test
  void offlineReplacementReleasesOldPendingCleanupWithoutRestoringAuthority() {
    Fixture fixture = new Fixture(() -> 101, (body, evidenceId, now) -> {
      throw new IllegalStateException("cleanup remains unavailable");
    });
    Object connection = new Object();
    PlayerHandle previous = new PlayerHandle(new Object(), connection);
    PlayerHandle replacement = new PlayerHandle(new Object(), connection);
    assertTrue(fixture.registry.protectCandidate(previous));
    assertTrue(fixture.registry.bindTrusted(previous, BINDING,
        coordinator(BINDING, fixture.firstNotifier)));
    assertFalse(fixture.registry.fence(previous, PermitAuthority.RevokeReason.CONTROL_LOST));
    assertTrue(fixture.registry.transferCandidateQuarantine(previous, replacement));

    assertTrue(fixture.registry.releaseOffline(
        replacement, PermitAuthority.RevokeReason.CONTROL_LOST));
    assertTrue(fixture.registry.current().isEmpty());
    assertFalse(fixture.registry.isProtectedCandidate(replacement));
  }

  @Test
  void prepareFenceReceiptIsSingleBoundedExactAndInvalidatedByFurtherLifecycleChange() {
    Fixture fixture = new Fixture();
    PlayerHandle player = new PlayerHandle(new Object(), new Object());
    assertTrue(fixture.registry.protectCandidate(player));
    assertTrue(fixture.registry.bindTrusted(
        player, BINDING, coordinator(BINDING, fixture.firstNotifier)));

    ControlledIdentityRegistry.PrepareFenceReceipt receipt =
        fixture.registry.beginBindingPrepare(player, BINDING, 100).orElseThrow();
    assertTrue(fixture.registry.validatesPrepareFence(receipt, player, BINDING, 5_099));
    assertFalse(fixture.registry.validatesPrepareFence(receipt, player, BINDING, 5_100));
    assertTrue(fixture.registry.beginBindingPrepare(player, BINDING, 101).isEmpty());
    assertTrue(fixture.registry.clearCandidate(player));
    assertFalse(fixture.registry.validatesPrepareFence(receipt, player, BINDING, 102));
  }

  @Test
  void prepareFenceWrongBindingOrIdentityDoesNotFenceAndInvalidClockFailsClosed() {
    Fixture fixture = new Fixture();
    PlayerHandle player = new PlayerHandle(new Object(), new Object());
    PlayerHandle clone = new PlayerHandle(player.identity, player.connection);
    assertTrue(fixture.registry.protectCandidate(player));
    assertTrue(fixture.registry.bindTrusted(
        player, BINDING, coordinator(BINDING, fixture.firstNotifier)));
    assertTrue(fixture.registry.beginBindingPrepare(clone, BINDING, 100).isEmpty());
    assertTrue(fixture.registry.beginBindingPrepare(player, nextBinding(), 100).isEmpty());
    assertTrue(fixture.registry.current().isPresent());
    assertTrue(fixture.registry.beginBindingPrepare(player, BINDING, Long.MAX_VALUE).isEmpty());
    assertTrue(fixture.registry.current().isEmpty());
    assertTrue(fixture.registry.isProtectedCandidate(player));
  }

  @Test
  void offlineReleaseInvalidatesReceiptAndRepeatedEmptyReleaseHasNoCleanupCallback() {
    int[] cleanups = {0};
    Fixture fixture = new Fixture(() -> 101, (body, evidenceId, now) -> cleanups[0]++);
    PlayerHandle player = new PlayerHandle(new Object(), new Object());
    assertTrue(fixture.registry.protectCandidate(player));
    assertTrue(fixture.registry.bindTrusted(
        player, BINDING, coordinator(BINDING, fixture.firstNotifier)));
    ControlledIdentityRegistry.PrepareFenceReceipt receipt =
        fixture.registry.beginBindingPrepare(player, BINDING, 100).orElseThrow();
    assertTrue(fixture.registry.validatesPrepareFence(receipt, player, BINDING, 101));

    PlayerHandle clone = new PlayerHandle(player.identity, player.connection);
    assertFalse(fixture.registry.releaseOffline(clone, PermitAuthority.RevokeReason.REBIND));
    assertTrue(fixture.registry.validatesPrepareFence(receipt, player, BINDING, 102));

    assertTrue(fixture.registry.releaseOffline(player, PermitAuthority.RevokeReason.REBIND));
    assertFalse(fixture.registry.validatesPrepareFence(receipt, player, BINDING, 103));
    assertEquals(0, cleanups[0]);
    assertTrue(fixture.registry.releaseOffline(player, PermitAuthority.RevokeReason.REBIND));
    assertEquals(0, cleanups[0]);
  }

  @Test
  void beginPrepareRechecksAfterEachReentrantIdentitySupplier() {
    for (boolean mutateFromIdentity : new boolean[] {true, false}) {
      PlayerHandle player = new PlayerHandle(new Object(), new Object());
      @SuppressWarnings("unchecked")
      ControlledIdentityRegistry<PlayerHandle>[] holder = new ControlledIdentityRegistry[1];
      boolean[] armed = {false};
      boolean[] fired = {false};
      ControlledIdentityRegistry.IdentityAdapter<PlayerHandle> adapter =
          new ControlledIdentityRegistry.IdentityAdapter<>() {
            private void mutate(PlayerHandle value, boolean identityCall) {
              if (armed[0] && !fired[0] && mutateFromIdentity == identityCall) {
                fired[0] = true;
                holder[0].releaseOffline(value, PermitAuthority.RevokeReason.REBIND);
              }
            }

            @Override
            public Object playerIdentity(PlayerHandle value) {
              mutate(value, true);
              return value.identity;
            }

            @Override
            public Object connectionToken(PlayerHandle value) {
              mutate(value, false);
              return value.connection;
            }
          };
      holder[0] = registry(adapter);
      assertTrue(holder[0].protectCandidate(player));
      assertTrue(holder[0].bindTrusted(
          player, BINDING, coordinator(BINDING, new CountingNotifier())));
      armed[0] = true;

      assertTrue(holder[0].beginBindingPrepare(player, BINDING, 100).isEmpty());
      assertTrue(fired[0]);
      assertTrue(holder[0].current().isEmpty());
    }
  }

  @Test
  void validationRechecksAfterEachReentrantIdentitySupplier() {
    for (boolean mutateFromIdentity : new boolean[] {true, false}) {
      PlayerHandle player = new PlayerHandle(new Object(), new Object());
      @SuppressWarnings("unchecked")
      ControlledIdentityRegistry<PlayerHandle>[] holder = new ControlledIdentityRegistry[1];
      boolean[] armed = {false};
      boolean[] fired = {false};
      ControlledIdentityRegistry.IdentityAdapter<PlayerHandle> adapter =
          new ControlledIdentityRegistry.IdentityAdapter<>() {
            private void mutate(PlayerHandle value, boolean identityCall) {
              if (armed[0] && !fired[0] && mutateFromIdentity == identityCall) {
                fired[0] = true;
                holder[0].releaseOffline(value, PermitAuthority.RevokeReason.REBIND);
              }
            }

            @Override
            public Object playerIdentity(PlayerHandle value) {
              mutate(value, true);
              return value.identity;
            }

            @Override
            public Object connectionToken(PlayerHandle value) {
              mutate(value, false);
              return value.connection;
            }
          };
      holder[0] = registry(adapter);
      assertTrue(holder[0].protectCandidate(player));
      assertTrue(holder[0].bindTrusted(
          player, BINDING, coordinator(BINDING, new CountingNotifier())));
      ControlledIdentityRegistry.PrepareFenceReceipt receipt =
          holder[0].beginBindingPrepare(player, BINDING, 100).orElseThrow();
      armed[0] = true;

      assertFalse(holder[0].validatesPrepareFence(receipt, player, BINDING, 101));
      assertTrue(fired[0]);
      assertFalse(holder[0].validatesPrepareFence(receipt, player, BINDING, 102));
    }
  }

  @Test
  void identitySupplierExceptionsNeverMintOrValidateReceipt() {
    PlayerHandle player = new PlayerHandle(new Object(), new Object());
    boolean[] throwsNow = {false};
    ControlledIdentityRegistry<PlayerHandle> registry = registry(
        new ControlledIdentityRegistry.IdentityAdapter<>() {
          @Override public Object playerIdentity(PlayerHandle value) {
            if (throwsNow[0]) throw new IllegalStateException("identity unavailable");
            return value.identity;
          }
          @Override public Object connectionToken(PlayerHandle value) { return value.connection; }
        });
    assertTrue(registry.protectCandidate(player));
    assertTrue(registry.bindTrusted(player, BINDING,
        coordinator(BINDING, new CountingNotifier())));
    throwsNow[0] = true;
    assertTrue(registry.beginBindingPrepare(player, BINDING, 100).isEmpty());
    assertTrue(registry.current().isEmpty());
  }

  @Test
  void exhaustedGenerationStillFencesRealLifecycleAndPermitWithoutMintingReceipt()
      throws Exception {
    PlayerHandle player = new PlayerHandle(new Object(), new Object());
    Fixture fixture = new Fixture();
    ActiveCoordinator active = activeCoordinator();
    assertTrue(fixture.registry.protectCandidate(player));
    assertTrue(fixture.registry.bindTrusted(player, BINDING, active.coordinator));
    setField(fixture.registry, "generation", Long.MAX_VALUE);

    assertTrue(fixture.registry.beginBindingPrepare(player, BINDING, 102).isEmpty());
    assertTrue(fixture.registry.current().isEmpty());
    assertTrue(active.permits.activePermit().isEmpty());
    assertTrue(active.coordinator.quarantined());
    assertEquals(BoundedLifecycle.Terminal.UNKNOWN,
        active.coordinator.record("executing").orElseThrow().terminal());
    assertEquals(ServerControlCoordinator.DispatchStatus.NO_WORK,
        active.coordinator.dispatchNext(dispatchContext(103),
            new ServerControlCoordinator.PermitGrant(bounds(), 500)).status());
    assertFalse(fixture.registry.bindTrusted(
        player, BINDING, coordinator(BINDING, new CountingNotifier())));
    assertTrue(fixture.registry.beginBindingPrepare(player, BINDING, 103).isEmpty());
  }

  @Test
  void lastGenerationCandidatePublishesOnceThenBindingCannotPublish() throws Exception {
    Fixture fixture = new Fixture();
    PlayerHandle player = new PlayerHandle(new Object(), new Object());
    setField(fixture.registry, "generation", Long.MAX_VALUE - 1);

    assertTrue(fixture.registry.protectCandidate(player));
    assertTrue(fixture.registry.isProtectedCandidate(player));
    assertFalse(fixture.registry.bindTrusted(
        player, BINDING, coordinator(BINDING, fixture.firstNotifier)));
    assertTrue(fixture.registry.current().isEmpty());
    assertTrue(fixture.registry.isProtectedCandidate(player));
    assertTrue(fixture.registry.beginBindingPrepare(player, BINDING, 100).isEmpty());
  }

  @Test
  void lastGenerationTransfersPublishOnceThenFurtherTransfersCannotReplaceCandidate()
      throws Exception {
    Fixture fixture = new Fixture();
    Object connection = new Object();
    PlayerHandle first = new PlayerHandle(new Object(), connection);
    PlayerHandle replacement = new PlayerHandle(new Object(), connection);
    PlayerHandle rejected = new PlayerHandle(new Object(), connection);
    assertTrue(fixture.registry.protectCandidate(first));
    assertTrue(fixture.registry.bindTrusted(
        first, BINDING, coordinator(BINDING, fixture.firstNotifier)));
    assertTrue(fixture.registry.fence(first, PermitAuthority.RevokeReason.REBIND));
    setField(fixture.registry, "generation", Long.MAX_VALUE - 1);

    assertTrue(fixture.registry.transferCandidate(first, replacement));
    assertTrue(fixture.registry.isProtectedCandidate(replacement));
    assertFalse(fixture.registry.transferCandidate(replacement, rejected));
    assertTrue(fixture.registry.isProtectedCandidate(replacement));
    assertFalse(fixture.registry.isProtectedCandidate(rejected));

    int[] attempts = {0};
    Fixture quarantine = new Fixture(() -> 101, (body, evidenceId, now) -> {
      if (attempts[0]++ == 0) throw new IllegalStateException("cleanup unavailable");
    });
    PlayerHandle old = new PlayerHandle(new Object(), connection);
    PlayerHandle clone = new PlayerHandle(new Object(), connection);
    PlayerHandle secondClone = new PlayerHandle(new Object(), connection);
    assertTrue(quarantine.registry.protectCandidate(old));
    assertTrue(quarantine.registry.bindTrusted(
        old, BINDING, coordinator(BINDING, quarantine.firstNotifier)));
    assertFalse(quarantine.registry.fence(old, PermitAuthority.RevokeReason.REBIND));
    setField(quarantine.registry, "generation", Long.MAX_VALUE - 1);

    assertTrue(quarantine.registry.transferCandidateQuarantine(old, clone));
    assertTrue(quarantine.registry.isProtectedCandidate(clone));
    assertFalse(quarantine.registry.transferCandidateQuarantine(clone, secondClone));
    assertTrue(quarantine.registry.isProtectedCandidate(clone));
    assertFalse(quarantine.registry.isProtectedCandidate(secondClone));
    assertTrue(quarantine.registry.releaseOffline(
        clone, PermitAuthority.RevokeReason.CONTROL_LOST));
  }

  private static PermitAuthority.Binding nextBinding() {
    return new PermitAuthority.Binding(
        "actor", "world", "body", "next-session", 2, "boot", "next-connection");
  }

  private static ControlledIdentityRegistry<PlayerHandle> registryWithMarker(
      ControlledIdentityRegistry.ProtectionMarker<PlayerHandle> marker) {
    return new ControlledIdentityRegistry<>(
        new ControlledIdentityRegistry.IdentityAdapter<>() {
          @Override public Object playerIdentity(PlayerHandle player) { return player; }
          @Override public Object connectionToken(PlayerHandle player) { return player.connection; }
        },
        new ControlledIdentityRegistry.TrustedProvisionPolicy<>() {
          @Override public boolean mayProtectCandidate(PlayerHandle player, Object identity, Object connection) { return true; }
          @Override public boolean mayBind(PlayerHandle player, Object identity, Object connection, PermitAuthority.Binding binding, ServerControlCoordinator coordinator) { return false; }
        }, marker);
  }

  private static ControlledIdentityRegistry<PlayerHandle> registry(
      ControlledIdentityRegistry.IdentityAdapter<PlayerHandle> adapter) {
    return new ControlledIdentityRegistry<>(
        adapter,
        new ControlledIdentityRegistry.TrustedProvisionPolicy<>() {
          @Override public boolean mayProtectCandidate(
              PlayerHandle player, Object identity, Object connection) { return true; }
          @Override public boolean mayBind(
              PlayerHandle player, Object identity, Object connection,
              PermitAuthority.Binding binding, ServerControlCoordinator coordinator) { return true; }
        },
        (player, identity, connection) -> true);
  }

  private static ServerControlCoordinator coordinator(
      PermitAuthority.Binding binding, CountingNotifier notifier) {
    AuthorityPolicy.Binding policyBinding =
        new AuthorityPolicy.Binding(
            binding.actorId(),
            binding.worldId(),
            binding.bodyId(),
            binding.sessionId(),
            binding.epoch());
    AuthorityPolicy.Region region =
        new AuthorityPolicy.Region("minecraft:overworld", -4, 4, 0, 128, -4, 4);
    BoundedLifecycle lifecycle =
        new BoundedLifecycle(
            policyBinding, region, Set.of(AuthorityPolicy.Capability.values()), 100);
    return new ServerControlCoordinator(
        lifecycle, new PermitAuthority(binding), binding, notifier);
  }

  private static ActiveCoordinator activeCoordinator() {
    AuthorityPolicy.Binding policy = new AuthorityPolicy.Binding(
        "actor", "world", "body", "session", 1);
    BoundedLifecycle lifecycle = new BoundedLifecycle(policy,
        new AuthorityPolicy.Region("minecraft:overworld", -4, 4, 0, 128, -4, 4),
        Set.of(AuthorityPolicy.Capability.values()), 100);
    PermitAuthority permits = new PermitAuthority(BINDING);
    ServerControlCoordinator coordinator = new ServerControlCoordinator(
        lifecycle, permits, BINDING, new CountingNotifier());
    AuthorityPolicy.Observation observation = observation();
    assertTrue(coordinator.admit(proposal("executing"), observation, 100, 100).accepted());
    assertTrue(coordinator.admit(proposal("waiting"), observation, 100, 100).accepted());
    assertEquals(ServerControlCoordinator.DispatchStatus.PERMIT_ISSUED,
        coordinator.dispatchNext(dispatchContext(101),
            new ServerControlCoordinator.PermitGrant(bounds(), 500)).status());
    assertTrue(permits.activePermit().isPresent());
    return new ActiveCoordinator(coordinator, permits);
  }

  private static AuthorityPolicy.Proposal proposal(String requestId) {
    return new AuthorityPolicy.Proposal(requestId,
        new AuthorityPolicy.Binding("actor", "world", "body", "session", 1),
        AuthorityPolicy.Capability.BOUNDED_WALK, 100, 500,
        new AuthorityPolicy.Basis("obs", "boot", 1), "minecraft:overworld",
        new AuthorityPolicy.Position(2, 64, 0), 2);
  }

  private static AuthorityPolicy.Observation observation() {
    return new AuthorityPolicy.Observation("obs", "boot", 1, true,
        "minecraft:overworld", new AuthorityPolicy.Position(0, 64, 0));
  }

  private static PermitAuthority.Bounds bounds() {
    return new PermitAuthority.Bounds("minecraft:overworld",
        new PermitAuthority.Position(-4, 0, -4),
        new PermitAuthority.Position(4, 128, 4), 2);
  }

  private static ServerControlCoordinator.DispatchContext dispatchContext(long now) {
    return new ServerControlCoordinator.DispatchContext(
        new PermitAuthority.CurrentContext(BINDING, "minecraft:overworld",
            new PermitAuthority.Position(0, 64, 0), now, 500, 500),
        observation(), 100, 0, 0);
  }

  private static void setField(Object target, String name, Object value) throws Exception {
    Field field = target.getClass().getDeclaredField(name);
    field.setAccessible(true);
    field.set(target, value);
  }

  private record ActiveCoordinator(
      ServerControlCoordinator coordinator, PermitAuthority permits) {}

  private static final class PlayerHandle {
    private final Object identity;
    private final Object connection;
    private boolean marked;

    private PlayerHandle(Object identity, Object connection) {
      this.identity = identity;
      this.connection = connection;
    }
  }

  private static final class Fixture {
    private boolean allowProvision = true;
    private final CountingNotifier firstNotifier = new CountingNotifier();
    private final CountingNotifier secondNotifier = new CountingNotifier();
    private final ControlledIdentityRegistry<PlayerHandle> registry;

    private Fixture() {
      this(() -> 101, (body, evidenceId, now) ->
          body.coordinator().controlLost(
              new BoundedLifecycle.AuthorityEvidence(evidenceId), now));
    }

    private Fixture(
        java.util.function.LongSupplier monotonicMillis,
        ControlledIdentityRegistry.CleanupPort<PlayerHandle> cleanup) {
      registry = new ControlledIdentityRegistry<>(
            new ControlledIdentityRegistry.IdentityAdapter<>() {
              @Override
              public Object playerIdentity(PlayerHandle player) {
                return player;
              }

              @Override
              public Object connectionToken(PlayerHandle player) {
                return player.connection;
              }
            },
            new ControlledIdentityRegistry.TrustedProvisionPolicy<>() {
              @Override
              public boolean mayProtectCandidate(
                  PlayerHandle player, Object playerIdentity, Object connectionToken) {
                return allowProvision;
              }

              @Override
              public boolean mayBind(
                  PlayerHandle player,
                  Object playerIdentity,
                  Object connectionToken,
                  PermitAuthority.Binding binding,
                  ServerControlCoordinator coordinator) {
                return allowProvision;
              }
            },
            (player, identity, connection) -> player.marked = true,
            monotonicMillis,
            cleanup);
    }
  }

  private static final class CountingNotifier implements ServerControlCoordinator.PermitNotifier {
    private int revokes;

    @Override
    public void send(PermitAuthority.Permit permit) {}

    @Override
    public void revoke(
        PermitAuthority.Binding binding, PermitAuthority.RevokeReason reason) {
      revokes++;
    }
  }
}
