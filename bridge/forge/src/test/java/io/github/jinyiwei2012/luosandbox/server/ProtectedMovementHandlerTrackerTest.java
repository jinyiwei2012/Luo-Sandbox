package io.github.jinyiwei2012.luosandbox.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.jinyiwei2012.luosandbox.policy.AuthorityPolicy;
import io.github.jinyiwei2012.luosandbox.servercontrol.PermitAuthority;
import io.github.jinyiwei2012.luosandbox.servercontrol.ServerControlCoordinator;
import io.github.jinyiwei2012.luosandbox.session.BoundedLifecycle;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ProtectedMovementHandlerTrackerTest {
  private static final PermitAuthority.Binding BINDING =
      new PermitAuthority.Binding("actor", "world", "body", "session", 1, "boot", "connection");

  @Test
  void authorizedScopeDrainsOnlyAfterNormalReturnAndValidOldAuthorityReceipt() {
    Fixture fixture = new Fixture();
    Object server = new Object();
    Object listener = new Object();
    ProtectedMovementHandlerTracker<Object, Object, Player> tracker =
        new ProtectedMovementHandlerTracker<>(server, () -> true);
    var body = fixture.registry.current().orElseThrow();
    assertTrue(tracker.enter(server, listener, fixture.player, body));
    assertTrue(tracker.authorizeLast(server, listener, fixture.player, body, true));
    var receipt = fixture.guard.beginBindingPrepare(fixture.player, BINDING, 100).orElseThrow();

    assertTrue(tracker.observeDrained(server, listener, fixture.player, BINDING, receipt,
        fixture.guard, 101).isEmpty());
    tracker.normalReturn(server, listener);
    assertEquals("PROTECTED_MOVEMENT_HANDLER_DRAINED",
        tracker.observeDrained(server, listener, fixture.player, BINDING, receipt,
            fixture.guard, 101).orElseThrow().status());
    assertTrue(tracker.observeDrained(server, listener, fixture.player, BINDING, receipt,
        fixture.guard, 5_100).isEmpty());
  }

  @Test
  void nestedAndDeniedFramesCannotReleaseOuterAuthorizedScope() {
    Fixture fixture = new Fixture();
    Object server = new Object();
    Object listener = new Object();
    var tracker = new ProtectedMovementHandlerTracker<Object, Object, Player>(server, () -> true);
    var body = fixture.registry.current().orElseThrow();
    assertTrue(tracker.enter(server, listener, fixture.player, body));
    assertTrue(tracker.authorizeLast(server, listener, fixture.player, body, true));
    assertTrue(tracker.enter(server, listener, fixture.player, body));
    var receipt = fixture.guard.beginBindingPrepare(fixture.player, BINDING, 100).orElseThrow();

    tracker.normalReturn(server, listener);
    assertTrue(tracker.observeDrained(server, listener, fixture.player, BINDING, receipt,
        fixture.guard, 101).isEmpty());
    tracker.normalReturn(server, listener);
    assertEquals("PROTECTED_MOVEMENT_HANDLER_DRAINED",
        tracker.observeDrained(server, listener, fixture.player, BINDING, receipt,
            fixture.guard, 101).orElseThrow().status());
  }

  @Test
  void nestedUntrackedBypassFrameCannotReleaseOuterAuthorizedScope() {
    Fixture fixture = new Fixture();
    Object server = new Object();
    Object listener = new Object();
    var tracker = new ProtectedMovementHandlerTracker<Object, Object, Player>(server, () -> true);
    var body = fixture.registry.current().orElseThrow();
    assertTrue(tracker.enter(server, listener, fixture.player, body));
    assertTrue(tracker.authorizeLast(server, listener, fixture.player, body, true));
    assertTrue(tracker.enterUntracked(server, listener, fixture.player));
    var receipt = fixture.guard.beginBindingPrepare(fixture.player, BINDING, 100).orElseThrow();

    tracker.normalReturn(server, listener);
    assertTrue(tracker.observeDrained(server, listener, fixture.player, BINDING, receipt,
        fixture.guard, 101).isEmpty());
    tracker.normalReturn(server, listener);
    assertEquals("PROTECTED_MOVEMENT_HANDLER_DRAINED",
        tracker.observeDrained(server, listener, fixture.player, BINDING, receipt,
            fixture.guard, 101).orElseThrow().status());
  }

  @Test
  void unmatchedReturnWrongOwnerAndOverflowPoisonRatherThanFalseDrain() {
    Fixture fixture = new Fixture();
    Object server = new Object();
    Object listener = new Object();
    boolean[] owner = {true};
    var tracker = new ProtectedMovementHandlerTracker<Object, Object, Player>(server, () -> owner[0]);
    var body = fixture.registry.current().orElseThrow();
    for (int index = 0; index < 8; index++) {
      assertTrue(tracker.enter(server, listener, fixture.player, body));
    }
    assertFalse(tracker.enter(server, listener, fixture.player, body));
    var receipt = fixture.guard.beginBindingPrepare(fixture.player, BINDING, 100).orElseThrow();
    for (int index = 0; index < 9; index++) tracker.normalReturn(server, listener);
    assertTrue(tracker.observeDrained(server, listener, fixture.player, BINDING, receipt,
        fixture.guard, 101).isEmpty());

    owner[0] = false;
    tracker.normalReturn(server, listener);
    assertTrue(tracker.observeDrained(server, listener, fixture.player, BINDING, receipt,
        fixture.guard, 101).isEmpty());
  }

  @Test
  void exceptionWithoutReturnAndOwnerInvalidationRemainFalseNegative() {
    Fixture fixture = new Fixture();
    Object server = new Object();
    Object listener = new Object();
    var tracker = new ProtectedMovementHandlerTracker<Object, Object, Player>(server, () -> true);
    var body = fixture.registry.current().orElseThrow();
    assertTrue(tracker.enter(server, listener, fixture.player, body));
    assertTrue(tracker.authorizeLast(server, listener, fixture.player, body, true));
    var receipt = fixture.guard.beginBindingPrepare(fixture.player, BINDING, 100).orElseThrow();
    tracker.invalidate(server);
    assertTrue(tracker.observeDrained(server, listener, fixture.player, BINDING, receipt,
        fixture.guard, 101).isEmpty());
  }

  private static final class Fixture {
    private final Player player = new Player(new Object());
    private final ControlledIdentityRegistry<Player> registry;
    private final ControlledPacketGuard<Player> guard;

    private Fixture() {
      registry = new ControlledIdentityRegistry<>(
          new ControlledIdentityRegistry.IdentityAdapter<>() {
            @Override public Object playerIdentity(Player value) { return value; }
            @Override public Object connectionToken(Player value) { return value.connection; }
          },
          new ControlledIdentityRegistry.TrustedProvisionPolicy<>() {
            @Override public boolean mayProtectCandidate(
                Player value, Object identity, Object connection) { return true; }
            @Override public boolean mayBind(
                Player value, Object identity, Object connection, PermitAuthority.Binding binding,
                ServerControlCoordinator coordinator) { return true; }
          },
          (value, identity, connection) -> true);
      PermitAuthority permits = new PermitAuthority(BINDING);
      ServerControlCoordinator coordinator = coordinator(permits);
      assertTrue(registry.protectCandidate(player));
      assertTrue(registry.bindTrusted(player, BINDING, coordinator));
      guard = new ControlledPacketGuard<>(registry, () -> true, (value, body) -> Optional.empty());
    }
  }

  private static ServerControlCoordinator coordinator(PermitAuthority permits) {
    AuthorityPolicy.Binding policy =
        new AuthorityPolicy.Binding("actor", "world", "body", "session", 1);
    BoundedLifecycle lifecycle = new BoundedLifecycle(policy,
        new AuthorityPolicy.Region("minecraft:overworld", -4, 4, 0, 128, -4, 4),
        Set.of(AuthorityPolicy.Capability.values()), 100);
    return new ServerControlCoordinator(lifecycle, permits, BINDING,
        new ServerControlCoordinator.PermitNotifier() {
          @Override public void send(PermitAuthority.Permit permit) {}
          @Override public void revoke(
              PermitAuthority.Binding binding, PermitAuthority.RevokeReason reason) {}
        });
  }

  private record Player(Object connection) {}
}
