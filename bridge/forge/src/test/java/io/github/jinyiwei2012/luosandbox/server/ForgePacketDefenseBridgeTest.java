package io.github.jinyiwei2012.luosandbox.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.jinyiwei2012.luosandbox.policy.AuthorityPolicy;
import io.github.jinyiwei2012.luosandbox.server.ControlledPacketGuard.Decision;
import io.github.jinyiwei2012.luosandbox.server.ControlledPacketGuard.MovementProposal;
import io.github.jinyiwei2012.luosandbox.servercontrol.PermitAuthority;
import io.github.jinyiwei2012.luosandbox.servercontrol.ServerControlCoordinator;
import io.github.jinyiwei2012.luosandbox.session.BoundedLifecycle;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ForgePacketDefenseBridgeTest {
  static final PermitAuthority.Binding BINDING =
      new PermitAuthority.Binding("actor", "world", "body", "session", 1, "boot", "connection");
  private static final AuthorityPolicy.Binding POLICY_BINDING =
      new AuthorityPolicy.Binding("actor", "world", "body", "session", 1);

  @Test
  void wrongThreadDeniesBeforePlayerMovementOrStateCallbacks() {
    Fixture fixture = new Fixture();
    AtomicInteger playerCalls = new AtomicInteger();
    AtomicInteger movementCalls = new AtomicInteger();
    AtomicInteger stateCalls = new AtomicInteger();
    ForgePacketDefenseBridge<PlayerHandle> bridge =
        new ForgePacketDefenseBridge<>(
            fixture.registry,
            (player, body) -> {
              stateCalls.incrementAndGet();
              return Optional.empty();
            });
    Decision decision =
        bridge.evaluatePlayerMovement(
            () -> false,
            () -> {
              playerCalls.incrementAndGet();
              return fixture.player;
            },
            () -> {
              movementCalls.incrementAndGet();
              return movement();
            });
    assertEquals(Decision.DENY, decision);
    assertEquals(0, playerCalls.get());
    assertEquals(0, movementCalls.get());
    assertEquals(0, stateCalls.get());
  }

  @Test
  void ordinaryPlayerBypassesAndProtectedCandidateDenies() {
    Fixture fixture = new Fixture();
    PlayerHandle other = new PlayerHandle(new Object());
    ForgePacketDefenseBridge<PlayerHandle> bridge = fixture.bridge(Optional.empty());
    assertEquals(
        Decision.BYPASS_OTHER_PLAYER,
        bridge.evaluatePlayerMovement(() -> true, () -> other, ForgePacketDefenseBridgeTest::movement));
    assertTrue(fixture.registry.protectCandidate(fixture.player));
    assertEquals(
        Decision.DENY,
        bridge.evaluatePlayerMovement(
            () -> true, () -> fixture.player, ForgePacketDefenseBridgeTest::movement));
  }

  @Test
  void controlledMissingOrExceptionalProviderDeniesAndVehicleAlwaysDenies() {
    Fixture fixture = new Fixture();
    fixture.bind();
    assertEquals(
        Decision.DENY,
        fixture
            .bridge(Optional.empty())
            .evaluatePlayerMovement(
                () -> true, () -> fixture.player, ForgePacketDefenseBridgeTest::movement));
    ForgePacketDefenseBridge<PlayerHandle> exceptional =
        new ForgePacketDefenseBridge<>(
            fixture.registry,
            (player, body) -> {
              throw new IllegalStateException("state unavailable");
            });
    assertEquals(
        Decision.DENY,
        exceptional.evaluatePlayerMovement(
            () -> true, () -> fixture.player, ForgePacketDefenseBridgeTest::movement));
    assertEquals(
        Decision.DENY,
        exceptional.evaluateVehicleMovement(() -> true, () -> fixture.player));
  }

  @Test
  void exactBoundPermitRoutesPlayerMovementOnly() {
    Fixture fixture = new Fixture();
    ControlledPacketGuard.TrustedCurrentState state = fixture.issueLook();
    ForgePacketDefenseBridge<PlayerHandle> bridge = fixture.bridge(Optional.of(state));
    assertEquals(
        Decision.ALLOW_MOVEMENT_PROPOSAL,
        bridge.evaluatePlayerMovement(
            () -> true, () -> fixture.player, ForgePacketDefenseBridgeTest::movement));
    assertEquals(Decision.DENY, bridge.evaluateVehicleMovement(() -> true, () -> fixture.player));
  }

  @Test
  void trackedMovementRequiresExactCurrentBodyAndNormalReturn() {
    Fixture fixture = new Fixture();
    ControlledPacketGuard.TrustedCurrentState state = fixture.issueLook();
    ForgePacketDefenseBridge<PlayerHandle> bridge = fixture.bridge(Optional.of(state));
    Object server = new Object();
    Object listener = new Object();
    assertEquals(Decision.ALLOW_MOVEMENT_PROPOSAL,
        bridge.evaluateTrackedPlayerMovement(
            server, listener, () -> true, fixture.player, movement()));
    bridge.returnTrackedPlayerMovement(server, listener);
    assertEquals(Decision.DENY,
        bridge.evaluateVehicleMovement(() -> true, () -> fixture.player));
  }

  @Test
  void realPermitDecisionAuthorizesFrameAndDrainAppearsOnlyAfterNormalReturnPostRevocation() {
    Fixture fixture = new Fixture();
    ControlledPacketGuard.TrustedCurrentState state = fixture.issueLook();
    ForgePacketDefenseBridge<PlayerHandle> bridge = fixture.bridge(Optional.of(state));
    ControlledPacketGuard<PlayerHandle> guard = fixture.guard(Optional.of(state));
    Object server = new Object();
    Object listener = new Object();

    assertEquals(Decision.ALLOW_MOVEMENT_PROPOSAL,
        bridge.evaluateTrackedPlayerMovement(
            server, listener, () -> true, fixture.player, movement()));
    ControlledIdentityRegistry.PrepareFenceReceipt receipt =
        guard.beginBindingPrepare(fixture.player, BINDING, 200).orElseThrow();
    assertEquals(ControlledIdentityRegistry.PrepareFenceReceipt.Status.OLD_AUTHORITY_REVOKED,
        receipt.status());

    assertTrue(bridge.observeProtectedMovementDrained(
        server, listener, fixture.player, BINDING, receipt, 201).isEmpty());
    bridge.returnTrackedPlayerMovement(server, listener);
    assertEquals("PROTECTED_MOVEMENT_HANDLER_DRAINED",
        bridge.observeProtectedMovementDrained(
            server, listener, fixture.player, BINDING, receipt, 201).orElseThrow().status());

    assertTrue(bridge.observeProtectedMovementDrained(
        new Object(), listener, fixture.player, BINDING, receipt, 201).isEmpty());
    assertTrue(bridge.observeProtectedMovementDrained(
        server, new Object(), fixture.player, BINDING, receipt, 201).isEmpty());
    assertTrue(bridge.observeProtectedMovementDrained(
        server, listener, new PlayerHandle(new Object()), BINDING, receipt, 201).isEmpty());
    PermitAuthority.Binding otherBinding =
        new PermitAuthority.Binding("actor", "world", "body", "session", 2, "boot", "connection");
    assertTrue(bridge.observeProtectedMovementDrained(
        server, listener, fixture.player, otherBinding, receipt, 201).isEmpty());
    assertTrue(bridge.observeProtectedMovementDrained(
        server, listener, fixture.player, BINDING, receipt, 5_200).isEmpty());
  }

  @Test
  void deniedMovementNeverYieldsPositiveDrain() {
    Fixture fixture = new Fixture();
    fixture.bind();
    Optional<ControlledPacketGuard.TrustedCurrentState> none = Optional.empty();
    ForgePacketDefenseBridge<PlayerHandle> bridge = fixture.bridge(none);
    ControlledPacketGuard<PlayerHandle> guard = fixture.guard(none);
    Object server = new Object();
    Object listener = new Object();

    assertEquals(Decision.DENY,
        bridge.evaluateTrackedPlayerMovement(
            server, listener, () -> true, fixture.player, movement()));
    ControlledIdentityRegistry.PrepareFenceReceipt receipt =
        guard.beginBindingPrepare(fixture.player, BINDING, 200).orElseThrow();
    bridge.returnTrackedPlayerMovement(server, listener);
    assertTrue(bridge.observeProtectedMovementDrained(
        server, listener, fixture.player, BINDING, receipt, 201).isEmpty());
  }

  @Test
  void reentrantlyFencedMovementNeverYieldsPositiveDrain() {
    Fixture fixture = new Fixture();
    ControlledPacketGuard.TrustedCurrentState state = fixture.issueLook();
    AtomicReference<ControlledIdentityRegistry.PrepareFenceReceipt> fenced = new AtomicReference<>();
    ForgePacketDefenseBridge<PlayerHandle> bridge =
        new ForgePacketDefenseBridge<>(
            fixture.registry,
            (player, body) -> {
              fenced.compareAndSet(
                  null, fixture.registry.beginBindingPrepare(player, BINDING, 200).orElse(null));
              return Optional.of(state);
            });
    Object server = new Object();
    Object listener = new Object();

    assertEquals(Decision.DENY,
        bridge.evaluateTrackedPlayerMovement(
            server, listener, () -> true, fixture.player, movement()));
    ControlledIdentityRegistry.PrepareFenceReceipt receipt = fenced.get();
    assertNotNull(receipt);
    bridge.returnTrackedPlayerMovement(server, listener);
    assertTrue(bridge.observeProtectedMovementDrained(
        server, listener, fixture.player, BINDING, receipt, 201).isEmpty());
  }

  @Test
  void gameplayCategoriesDenyControlledWithoutConsultingStateProvider() {
    Fixture fixture = new Fixture();
    fixture.bind();
    AtomicInteger stateCalls = new AtomicInteger();
    ForgePacketDefenseBridge<PlayerHandle> bridge =
        new ForgePacketDefenseBridge<>(
            fixture.registry,
            (player, body) -> {
              stateCalls.incrementAndGet();
              return Optional.empty();
            });
    for (ControlledPacketGuard.PacketCategory category :
        Set.of(
            ControlledPacketGuard.PacketCategory.MINE,
            ControlledPacketGuard.PacketCategory.PLACE,
            ControlledPacketGuard.PacketCategory.USE,
            ControlledPacketGuard.PacketCategory.ATTACK,
            ControlledPacketGuard.PacketCategory.INVENTORY,
            ControlledPacketGuard.PacketCategory.CREATIVE_INVENTORY,
            ControlledPacketGuard.PacketCategory.COMMAND,
            ControlledPacketGuard.PacketCategory.KEY_INPUT,
            ControlledPacketGuard.PacketCategory.VEHICLE_MOVEMENT,
            ControlledPacketGuard.PacketCategory.OTHER_GAMEPLAY)) {
      assertEquals(
          Decision.DENY,
          bridge.evaluateGameplay(() -> true, () -> fixture.player, category),
          category.name());
    }
    assertEquals(0, stateCalls.get());
    PlayerHandle other = new PlayerHandle(new Object());
    assertEquals(
        Decision.BYPASS_OTHER_PLAYER,
        bridge.evaluateGameplay(
            () -> true, () -> other, ControlledPacketGuard.PacketCategory.INVENTORY));
  }

  @Test
  void gameplayWrongThreadDoesNotReadPlayerOrProvider() {
    Fixture fixture = new Fixture();
    AtomicInteger playerCalls = new AtomicInteger();
    AtomicInteger stateCalls = new AtomicInteger();
    ForgePacketDefenseBridge<PlayerHandle> bridge =
        new ForgePacketDefenseBridge<>(
            fixture.registry,
            (player, body) -> {
              stateCalls.incrementAndGet();
              return Optional.empty();
            });
    assertEquals(
        Decision.DENY,
        bridge.evaluateGameplay(
            () -> false,
            () -> {
              playerCalls.incrementAndGet();
              return fixture.player;
            },
            ControlledPacketGuard.PacketCategory.ATTACK));
    assertEquals(0, playerCalls.get());
    assertEquals(0, stateCalls.get());
  }

  @Test
  void deferredGameplayUsesMonotonicListenerMarkerBeforeOwnerOrPlayerLookup() {
    Object server = new Object();
    AtomicInteger markerCalls = new AtomicInteger();
    AtomicInteger playerCalls = new AtomicInteger();
    FakeMarker controlled = new FakeMarker(true, markerCalls);
    assertEquals(
        Decision.DENY,
        deferred(
            () -> false,
            () -> controlled,
            () -> {
              playerCalls.incrementAndGet();
              return null;
            },
            ControlledPacketGuard.PacketCategory.BOOK_EDIT));
    assertEquals(0, markerCalls.get());
    assertEquals(0, playerCalls.get());

    assertEquals(
        Decision.DENY,
        deferred(
            () -> true,
            () -> controlled,
            () -> {
              playerCalls.incrementAndGet();
              return null;
            },
            ControlledPacketGuard.PacketCategory.SIGN_UPDATE));
    assertEquals(1, markerCalls.get());
    assertEquals(0, playerCalls.get());
  }

  @Test
  void deferredGameplayNeverControlledBypassesAndBrokenMarkerFailsClosed() {
    AtomicInteger playerCalls = new AtomicInteger();
    assertEquals(
        Decision.BYPASS_OTHER_PLAYER,
        deferred(
            () -> true,
            () -> new FakeMarker(false, new AtomicInteger()),
            () -> {
              playerCalls.incrementAndGet();
              return null;
            },
            ControlledPacketGuard.PacketCategory.CHAT));
    assertEquals(0, playerCalls.get());
    assertEquals(
        Decision.DENY,
        deferred(
            () -> true,
            () -> new Object(),
            () -> null,
            ControlledPacketGuard.PacketCategory.COMMAND));
    assertEquals(
        Decision.DENY,
        deferred(
            () -> true,
            () -> { throw new IllegalStateException("marker read failed"); },
            () -> null,
            ControlledPacketGuard.PacketCategory.CHAT));
    assertEquals(
        Decision.DENY,
        deferred(
            () -> true,
            () -> new FakeMarker(false, new AtomicInteger()),
            () -> null,
            ControlledPacketGuard.PacketCategory.INVENTORY));
  }

  @Test
  void deferredMarkerSurvivesRegistryReleaseAndBridgeOwnerRemoval() {
    Fixture fixture = new Fixture();
    FakeMarker marker = new FakeMarker(false, new AtomicInteger());
    marker.luosandbox$markEverControlled();
    assertTrue(fixture.registry.protectCandidate(fixture.player));
    assertTrue(fixture.registry.releaseOffline(
        fixture.player, PermitAuthority.RevokeReason.CONTROL_LOST));
    assertTrue(fixture.registry.current().isEmpty());
    assertEquals(
        Decision.DENY,
        fixture
            .bridge(Optional.empty())
            .evaluateDeferredGameplay(
                () -> true,
                () -> marker,
                () -> fixture.player,
                ControlledPacketGuard.PacketCategory.BOOK_EDIT));
  }

  private static Decision deferred(
      ControlledPacketGuard.TrustedThreadGate thread,
      java.util.function.Supplier<Object> marker,
      java.util.function.Supplier<PlayerHandle> player,
      ControlledPacketGuard.PacketCategory category) {
    if (!thread.isServerThreadOwner()) return Decision.DENY;
    try {
      Object listener = marker.get();
      if (!(listener instanceof ControlledConnectionMarker controlled)) return Decision.DENY;
      if (controlled.luosandbox$everControlled()) return Decision.DENY;
    } catch (RuntimeException error) {
      return Decision.DENY;
    }
    if (category != ControlledPacketGuard.PacketCategory.CHAT
        && category != ControlledPacketGuard.PacketCategory.COMMAND
        && category != ControlledPacketGuard.PacketCategory.BOOK_EDIT
        && category != ControlledPacketGuard.PacketCategory.SIGN_UPDATE) return Decision.DENY;
    return Decision.BYPASS_OTHER_PLAYER;
  }

  @Test
  void ownerManagerRejectsLastWriterWinsAndWrongServerRemoval() {
    ForgePacketDefenseBridge.OwnerManager<Object, PlayerHandle> owners =
        new ForgePacketDefenseBridge.OwnerManager<>();
    Fixture first = new Fixture();
    Fixture replacement = new Fixture();
    Object serverA = new Object();
    Object serverB = new Object();
    ControlledPacketGuard.CurrentStateProvider<PlayerHandle> provider =
        (player, body) -> Optional.empty();
    ForgePacketDefenseBridge<PlayerHandle> bridgeA =
        new ForgePacketDefenseBridge<>(first.registry, provider);
    ForgePacketDefenseBridge<PlayerHandle> sameOwner =
        new ForgePacketDefenseBridge<>(first.registry, provider);
    ForgePacketDefenseBridge<PlayerHandle> bridgeB =
        new ForgePacketDefenseBridge<>(replacement.registry, provider);

    ForgePacketDefenseBridge.OwnerHandle<Object> handleA =
        owners.install(serverA, bridgeA).orElseThrow();
    assertEquals(handleA, owners.install(serverA, sameOwner).orElseThrow());
    assertEquals(bridgeA, owners.resolve(serverA).orElseThrow());
    assertTrue(owners.install(serverA, bridgeB).isEmpty());
    assertTrue(owners.install(serverB, bridgeB).isEmpty());
    assertEquals(bridgeA, owners.resolve(serverA).orElseThrow());
    assertTrue(owners.resolve(serverB).isEmpty());
    assertEquals(false, handleA.uninstall(serverB));
    assertEquals(bridgeA, owners.resolve(serverA).orElseThrow());
    assertTrue(handleA.uninstall(serverA));
    assertTrue(owners.resolve(serverA).isEmpty());
    assertEquals(false, handleA.uninstall(serverA));

    ForgePacketDefenseBridge.OwnerHandle<Object> replacementHandle =
        owners.install(serverA, bridgeB).orElseThrow();
    assertEquals(false, handleA.uninstall(serverA));
    assertEquals(bridgeB, owners.resolve(serverA).orElseThrow());
    assertTrue(replacementHandle.uninstall(serverA));
  }

  static MovementProposal movement() {
    return MovementProposal.positionAndRotation(0, 64, 0, 0, 0, true);
  }

  static final class Fixture {
    final PlayerHandle player = new PlayerHandle(new Object());
    Runnable identityRead = () -> {};
    int identityReads;
    Object connectionOverride;
    int sends;
    int revokes;
    final ControlledIdentityRegistry<PlayerHandle> registry =
        new ControlledIdentityRegistry<>(
            new ControlledIdentityRegistry.IdentityAdapter<>() {
              @Override
              public Object playerIdentity(PlayerHandle player) {
                identityReads++;
                identityRead.run();
                return player;
              }

              @Override
              public Object connectionToken(PlayerHandle player) {
                identityReads++;
                return connectionOverride == null ? player.connection : connectionOverride;
              }
            },
            new ControlledIdentityRegistry.TrustedProvisionPolicy<>() {
              @Override
              public boolean mayProtectCandidate(
                  PlayerHandle player, Object identity, Object connection) {
                return true;
              }

              @Override
              public boolean mayBind(
                  PlayerHandle player,
                  Object identity,
                  Object connection,
                  PermitAuthority.Binding binding,
                  ServerControlCoordinator coordinator) {
                return true;
              }
            },
            (player, identity, connection) -> true);
    ServerControlCoordinator coordinator;

    ForgePacketDefenseBridge<PlayerHandle> bridge(
        Optional<ControlledPacketGuard.TrustedCurrentState> state) {
      return new ForgePacketDefenseBridge<>(registry, (player, body) -> state);
    }

    ControlledPacketGuard<PlayerHandle> guard(
        Optional<ControlledPacketGuard.TrustedCurrentState> state) {
      return new ControlledPacketGuard<>(registry, () -> true, (player, body) -> state);
    }

    void bind() {
      coordinator = coordinator(new ServerControlCoordinator.PermitNotifier() {
        @Override public void send(PermitAuthority.Permit permit) { sends++; }
        @Override public void revoke(
            PermitAuthority.Binding binding, PermitAuthority.RevokeReason reason) { revokes++; }
      });
      assertTrue(registry.protectCandidate(player));
      assertTrue(registry.bindTrusted(player, BINDING, coordinator));
    }

    ControlledPacketGuard.TrustedCurrentState issueLook() {
      bind();
      AuthorityPolicy.Observation observation =
          new AuthorityPolicy.Observation(
              "obs", "boot", 1, true, "minecraft:overworld", new AuthorityPolicy.Position(0, 64, 0));
      AuthorityPolicy.Proposal proposal =
          new AuthorityPolicy.Proposal(
              "look",
              POLICY_BINDING,
              AuthorityPolicy.Capability.LOOK,
              100,
              500,
              new AuthorityPolicy.Basis("obs", "boot", 1),
              "minecraft:overworld",
              new AuthorityPolicy.Position(0, 64, 1),
              0);
      assertTrue(coordinator.admit(proposal, observation, 100, 100).accepted());
      PermitAuthority.CurrentContext context =
          new PermitAuthority.CurrentContext(
              BINDING,
              "minecraft:overworld",
              new PermitAuthority.Position(0, 64, 0),
              101,
              500,
              500);
      PermitAuthority.Bounds bounds =
          new PermitAuthority.Bounds(
              "minecraft:overworld",
              new PermitAuthority.Position(-1, 0, -1),
              new PermitAuthority.Position(1, 128, 1),
              0);
      PermitAuthority.Permit permit =
          coordinator
              .dispatchNext(
                  new ServerControlCoordinator.DispatchContext(context, observation, 100, 0, 0),
                  new ServerControlCoordinator.PermitGrant(bounds, 500))
              .permit()
              .orElseThrow();
      return new ControlledPacketGuard.TrustedCurrentState(
          BINDING, permit.permitId(), context, 0, 0);
    }
  }

  static ServerControlCoordinator coordinator() {
    return coordinator(new ServerControlCoordinator.PermitNotifier() {
      @Override public void send(PermitAuthority.Permit permit) {}
      @Override public void revoke(
          PermitAuthority.Binding binding, PermitAuthority.RevokeReason reason) {}
    });
  }

  private static ServerControlCoordinator coordinator(ServerControlCoordinator.PermitNotifier notifier) {
    AuthorityPolicy.Region region =
        new AuthorityPolicy.Region("minecraft:overworld", -4, 4, 0, 128, -4, 4);
    BoundedLifecycle lifecycle =
        new BoundedLifecycle(
            POLICY_BINDING, region, Set.of(AuthorityPolicy.Capability.values()), 100);
    return new ServerControlCoordinator(
        lifecycle,
        new PermitAuthority(BINDING),
        BINDING,
        notifier);
  }

  record PlayerHandle(Object connection) {}

  private static final class FakeMarker implements ControlledConnectionMarker {
    private boolean marked;
    private final AtomicInteger reads;
    private FakeMarker(boolean marked, AtomicInteger reads) { this.marked = marked; this.reads = reads; }
    @Override public boolean luosandbox$everControlled() { reads.incrementAndGet(); return marked; }
    @Override public void luosandbox$markEverControlled() { marked = true; }
  }
}
