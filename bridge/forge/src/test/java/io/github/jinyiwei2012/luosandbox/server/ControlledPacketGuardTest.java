package io.github.jinyiwei2012.luosandbox.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.jinyiwei2012.luosandbox.policy.AuthorityPolicy;
import io.github.jinyiwei2012.luosandbox.server.ControlledPacketGuard.Decision;
import io.github.jinyiwei2012.luosandbox.server.ControlledPacketGuard.MovementProposal;
import io.github.jinyiwei2012.luosandbox.server.ControlledPacketGuard.PacketCategory;
import io.github.jinyiwei2012.luosandbox.servercontrol.PermitAuthority;
import io.github.jinyiwei2012.luosandbox.servercontrol.ServerControlCoordinator;
import io.github.jinyiwei2012.luosandbox.session.BoundedLifecycle;
import java.util.EnumSet;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ControlledPacketGuardTest {
  private static final PermitAuthority.Binding BINDING =
      new PermitAuthority.Binding("actor", "world", "body", "session", 1, "boot", "connection");
  private static final AuthorityPolicy.Binding POLICY_BINDING =
      new AuthorityPolicy.Binding("actor", "world", "body", "session", 1);
  private static final AuthorityPolicy.Region REGION =
      new AuthorityPolicy.Region("minecraft:overworld", -4, 4, 0, 128, -4, 4);
  private static final AuthorityPolicy.Observation OBSERVATION =
      new AuthorityPolicy.Observation(
          "obs", "boot", 1, true, "minecraft:overworld", new AuthorityPolicy.Position(0, 64, 0));

  @Test
  void wrongThreadDeniesBeforeIdentityOrStateCallbacks() {
    AtomicInteger identityCalls = new AtomicInteger();
    AtomicInteger stateCalls = new AtomicInteger();
    PlayerHandle player = new PlayerHandle(new Object());
    ControlledIdentityRegistry<PlayerHandle> registry =
        registry(identityCalls, (body, evidence, now) -> {});
    ControlledPacketGuard<PlayerHandle> guard =
        new ControlledPacketGuard<>(
            registry,
            () -> false,
            (ignored, body) -> {
              stateCalls.incrementAndGet();
              return Optional.empty();
            });

    assertEquals(Decision.DENY, guard.evaluate(player, PacketCategory.KEEPALIVE, null));
    assertEquals(0, identityCalls.get());
    assertEquals(0, stateCalls.get());
  }

  @Test
  void ordinaryPlayerBypassesWhileProtectedCandidateDefaultsToDeny() {
    Fixture fixture = new Fixture();
    PlayerHandle other = new PlayerHandle(new Object());
    assertEquals(
        Decision.BYPASS_OTHER_PLAYER,
        fixture.guard(other).evaluate(other, PacketCategory.ATTACK, null));

    assertTrue(fixture.registry.protectCandidate(fixture.player));
    assertEquals(
        Decision.DENY,
        fixture.guard(fixture.player).evaluate(fixture.player, PacketCategory.ATTACK, null));
  }

  @Test
  void healthAllowlistAndForgePayloadRouteAreNarrow() {
    Fixture fixture = new Fixture();
    fixture.bindWithoutPermit();
    ControlledPacketGuard<PlayerHandle> guard = fixture.guard(fixture.player);
    Set<PacketCategory> health =
        EnumSet.of(
            PacketCategory.KEEPALIVE,
            PacketCategory.PONG,
            PacketCategory.CHAT_ACK,
            PacketCategory.ACCEPT_TELEPORT,
            PacketCategory.CLIENT_INFORMATION,
            PacketCategory.CHAT_SESSION_UPDATE,
            PacketCategory.RESOURCE_PACK_RESPONSE,
            PacketCategory.CONTAINER_CLOSE);
    for (PacketCategory category : PacketCategory.values()) {
      if (health.contains(category)) {
        assertEquals(Decision.ALLOW_HEALTH, guard.evaluate(fixture.player, category, null), category.name());
      }
    }
    assertEquals(
        Decision.BYPASS_TO_FORGE,
        guard.evaluate(fixture.player, PacketCategory.FORGE_CUSTOM_PAYLOAD, null));
    assertEquals(Decision.DENY, guard.evaluate(fixture.player, PacketCategory.OTHER_GAMEPLAY, null));
  }

  @Test
  void controlledGameplayCategoriesDefaultDenyWithoutBroadUnknownAllowance() {
    Fixture fixture = new Fixture();
    fixture.bindWithoutPermit();
    ControlledPacketGuard<PlayerHandle> guard = fixture.guard(fixture.player);
    for (PacketCategory category :
        EnumSet.of(
            PacketCategory.CHAT,
            PacketCategory.COMMAND,
            PacketCategory.ATTACK,
            PacketCategory.USE,
            PacketCategory.PLACE,
            PacketCategory.MINE,
            PacketCategory.INVENTORY,
            PacketCategory.CREATIVE_INVENTORY,
            PacketCategory.VEHICLE_MOVEMENT,
            PacketCategory.KEY_INPUT,
            PacketCategory.OTHER_GAMEPLAY)) {
      assertEquals(Decision.DENY, guard.evaluate(fixture.player, category, null), category.name());
    }
  }

  @Test
  void movementRequiresTrustedFullBindingCurrentContextAndPermit() {
    Fixture fixture = new Fixture();
    fixture.bindWithoutPermit();
    MovementProposal movement = MovementProposal.positionAndRotation(0, 64, 0, 0, 0, true);
    assertEquals(
        Decision.DENY,
        fixture.guard(fixture.player).evaluate(fixture.player, PacketCategory.PLAYER_MOVEMENT, movement));

    fixture.state = fixture.stateWithBinding(wrongBinding(), "missing");
    assertEquals(
        Decision.DENY,
        fixture.guard(fixture.player).evaluate(fixture.player, PacketCategory.PLAYER_MOVEMENT, movement));
  }

  @Test
  void lookPermitUsesTrustedFallbackAndRejectsPositionChange() {
    Fixture fixture = new Fixture();
    fixture.issue(PermitAuthority.Intent.LOOK, 0, 500);
    MovementProposal rotationOnly =
        new MovementProposal(
            OptionalDouble.empty(),
            OptionalDouble.empty(),
            OptionalDouble.empty(),
            OptionalDouble.of(1),
            OptionalDouble.of(1),
            Optional.of(true));
    assertEquals(
        Decision.ALLOW_MOVEMENT_PROPOSAL,
        fixture.guard(fixture.player).evaluate(fixture.player, PacketCategory.PLAYER_MOVEMENT, rotationOnly));
    assertEquals(
        Decision.DENY,
        fixture
            .guard(fixture.player)
            .evaluate(
                fixture.player,
                PacketCategory.PLAYER_MOVEMENT,
                MovementProposal.positionAndRotation(0.1, 64, 0, 1, 1, true)));
  }

  @Test
  void walkPermitEnforcesCumulativeDistanceBoundsAndExpiry() {
    Fixture fixture = new Fixture();
    fixture.issue(PermitAuthority.Intent.BOUNDED_WALK, 2, 500);
    ControlledPacketGuard<PlayerHandle> guard = fixture.guard(fixture.player);
    assertEquals(
        Decision.ALLOW_MOVEMENT_PROPOSAL,
        guard.evaluate(
            fixture.player,
            PacketCategory.PLAYER_MOVEMENT,
            MovementProposal.positionAndRotation(1, 64, 0, 0, 0, true)));
    fixture.advanceTrustedPosition(1, 64, 0, 102);
    assertEquals(
        Decision.ALLOW_MOVEMENT_PROPOSAL,
        guard.evaluate(
            fixture.player,
            PacketCategory.PLAYER_MOVEMENT,
            MovementProposal.positionAndRotation(0, 64, 0, 0, 0, true)));
    fixture.advanceTrustedPosition(0, 64, 0, 103);
    assertEquals(
        Decision.DENY,
        guard.evaluate(
            fixture.player,
            PacketCategory.PLAYER_MOVEMENT,
            MovementProposal.positionAndRotation(1, 64, 0, 0, 0, true)));
    assertEquals(
        Decision.DENY,
        guard.evaluate(
            fixture.player,
            PacketCategory.PLAYER_MOVEMENT,
            MovementProposal.positionAndRotation(5, 64, 0, 0, 0, true)));
    fixture.advanceTrustedPosition(0, 64, 0, 500);
    assertEquals(
        Decision.DENY,
        guard.evaluate(
            fixture.player,
            PacketCategory.PLAYER_MOVEMENT,
            MovementProposal.positionAndRotation(0, 64, 0, 0, 0, true)));
  }

  @Test
  void revokedOrLostControlAndNonFinitePacketsDeny() {
    Fixture fixture = new Fixture();
    fixture.issue(PermitAuthority.Intent.BOUNDED_WALK, 2, 500);
    assertThrows(
        IllegalArgumentException.class,
        () -> MovementProposal.positionAndRotation(Double.NaN, 64, 0, 0, 0, true));
    fixture.coordinator.controlLost(new BoundedLifecycle.AuthorityEvidence("lost"), 102);
    assertEquals(
        Decision.DENY,
        fixture
            .guard(fixture.player)
            .evaluate(
                fixture.player,
                PacketCategory.PLAYER_MOVEMENT,
                MovementProposal.positionAndRotation(0, 64, 0, 0, 0, true)));
  }

  private static PermitAuthority.Binding wrongBinding() {
    return new PermitAuthority.Binding("actor", "world", "other", "session", 1, "boot", "connection");
  }

  private static ControlledIdentityRegistry<PlayerHandle> registry(
      AtomicInteger identityCalls, ControlledIdentityRegistry.CleanupPort<PlayerHandle> cleanup) {
    return new ControlledIdentityRegistry<>(
        new ControlledIdentityRegistry.IdentityAdapter<>() {
          @Override
          public Object playerIdentity(PlayerHandle player) {
            identityCalls.incrementAndGet();
            return player;
          }

          @Override
          public Object connectionToken(PlayerHandle player) {
            identityCalls.incrementAndGet();
            return player.connection;
          }
        },
        new AllowProvisioning(),
        (player, identity, connection) -> true,
        () -> 101,
        cleanup);
  }

  private static final class Fixture {
    private final PlayerHandle player = new PlayerHandle(new Object());
    private final ControlledIdentityRegistry<PlayerHandle> registry =
        registry(new AtomicInteger(), (body, evidence, now) -> {});
    private ServerControlCoordinator coordinator;
    private ControlledPacketGuard.TrustedCurrentState state;

    private ControlledPacketGuard<PlayerHandle> guard(PlayerHandle target) {
      return new ControlledPacketGuard<>(registry, () -> true, (player, body) -> Optional.ofNullable(state));
    }

    private void bindWithoutPermit() {
      coordinator = coordinator(new PermitAuthority(BINDING));
      assertTrue(registry.protectCandidate(player));
      assertTrue(registry.bindTrusted(player, BINDING, coordinator));
    }

    private void issue(PermitAuthority.Intent intent, double distance, long deadline) {
      PermitAuthority permits = new PermitAuthority(BINDING);
      coordinator = coordinator(permits);
      assertTrue(registry.protectCandidate(player));
      assertTrue(registry.bindTrusted(player, BINDING, coordinator));
      AuthorityPolicy.Capability capability =
          intent == PermitAuthority.Intent.LOOK
              ? AuthorityPolicy.Capability.LOOK
              : AuthorityPolicy.Capability.BOUNDED_WALK;
      AuthorityPolicy.Proposal proposal =
          new AuthorityPolicy.Proposal(
              "request",
              POLICY_BINDING,
              capability,
              100,
              deadline,
              new AuthorityPolicy.Basis("obs", "boot", 1),
              "minecraft:overworld",
              intent == PermitAuthority.Intent.LOOK
                  ? new AuthorityPolicy.Position(0, 64, 1)
                  : new AuthorityPolicy.Position(distance, 64, 0),
              distance);
      assertTrue(coordinator.admit(proposal, OBSERVATION, 100, 100).accepted());
      PermitAuthority.Bounds bounds =
          new PermitAuthority.Bounds(
              "minecraft:overworld",
              new PermitAuthority.Position(-4, 0, -4),
              new PermitAuthority.Position(4, 128, 4),
              distance);
      PermitAuthority.CurrentContext context = context(BINDING, 0, 64, 0, 101, deadline);
      PermitAuthority.Permit permit =
          coordinator
              .dispatchNext(
                  new ServerControlCoordinator.DispatchContext(context, OBSERVATION, 100, 0, 0),
                  new ServerControlCoordinator.PermitGrant(bounds, deadline))
              .permit()
              .orElseThrow();
      state = new ControlledPacketGuard.TrustedCurrentState(BINDING, permit.permitId(), context, 0, 0);
    }

    private ControlledPacketGuard.TrustedCurrentState stateWithBinding(
        PermitAuthority.Binding binding, String permitId) {
      return new ControlledPacketGuard.TrustedCurrentState(
          binding, permitId, context(binding, 0, 64, 0, 101, 500), 0, 0);
    }

    private void advanceTrustedPosition(double x, double y, double z, long now) {
      state =
          new ControlledPacketGuard.TrustedCurrentState(
              BINDING, state.permitId(), context(BINDING, x, y, z, now, 500), state.yaw(), state.pitch());
    }
  }

  private static ServerControlCoordinator coordinator(PermitAuthority permits) {
    BoundedLifecycle lifecycle =
        new BoundedLifecycle(
            POLICY_BINDING, REGION, Set.of(AuthorityPolicy.Capability.values()), 100);
    return new ServerControlCoordinator(
        lifecycle,
        permits,
        BINDING,
        new ServerControlCoordinator.PermitNotifier() {
          @Override
          public void send(PermitAuthority.Permit permit) {}

          @Override
          public void revoke(
              PermitAuthority.Binding binding, PermitAuthority.RevokeReason reason) {}
        });
  }

  private static PermitAuthority.CurrentContext context(
      PermitAuthority.Binding binding,
      double x,
      double y,
      double z,
      long now,
      long deadline) {
    return new PermitAuthority.CurrentContext(
        binding,
        "minecraft:overworld",
        new PermitAuthority.Position(x, y, z),
        now,
        deadline,
        deadline);
  }

  private record PlayerHandle(Object connection) {}

  private static final class AllowProvisioning
      implements ControlledIdentityRegistry.TrustedProvisionPolicy<PlayerHandle> {
    @Override
    public boolean mayProtectCandidate(
        PlayerHandle player, Object playerIdentity, Object connectionToken) {
      return true;
    }

    @Override
    public boolean mayBind(
        PlayerHandle player,
        Object playerIdentity,
        Object connectionToken,
        PermitAuthority.Binding binding,
        ServerControlCoordinator coordinator) {
      return true;
    }
  }
}
