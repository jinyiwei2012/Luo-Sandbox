package io.github.jinyiwei2012.luosandbox.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.jinyiwei2012.luosandbox.policy.AuthorityPolicy;
import io.github.jinyiwei2012.luosandbox.servercontrol.PermitAuthority;
import io.github.jinyiwei2012.luosandbox.servercontrol.ServerControlCoordinator;
import io.github.jinyiwei2012.luosandbox.session.BoundedLifecycle;
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

  private static PermitAuthority.Binding nextBinding() {
    return new PermitAuthority.Binding(
        "actor", "world", "body", "next-session", 2, "boot", "next-connection");
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

  private static final class PlayerHandle {
    private final Object identity;
    private final Object connection;

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
