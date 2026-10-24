package io.github.jinyiwei2012.luosandbox.server;

import io.github.jinyiwei2012.luosandbox.server.ControlledPacketGuard.Decision;
import io.github.jinyiwei2012.luosandbox.server.ControlledPacketGuard.MovementProposal;
import io.github.jinyiwei2012.luosandbox.server.ControlledPacketGuard.PacketCategory;
import io.github.jinyiwei2012.luosandbox.servercontrol.PermitAuthority;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * Server-only adapter between mapped movement packets and the pure controlled packet guard.
 *
 * <p>The trusted state provider is installed by the server authority owner. The default installation
 * supplies no state and therefore cannot authorize controlled movement or create body bindings.
 */
public final class ForgePacketDefenseBridge<P> {
  public static final class OwnerHandle<S> {
    private final OwnerManager<S, ?> owner;
    private final S server;

    private OwnerHandle(OwnerManager<S, ?> owner, S server) {
      this.owner = owner;
      this.server = server;
    }

    public boolean uninstall(S expectedServer) {
      return owner.remove(expectedServer, this);
    }
  }

  static final class OwnerManager<S, P> {
    private S server;
    private ForgePacketDefenseBridge<P> bridge;
    private OwnerHandle<S> handle;

    synchronized Optional<OwnerHandle<S>> install(S server, ForgePacketDefenseBridge<P> bridge) {
      Objects.requireNonNull(server);
      Objects.requireNonNull(bridge);
      if (this.server == null) {
        this.server = server;
        this.bridge = bridge;
        handle = new OwnerHandle<>(this, server);
        return Optional.of(handle);
      }
      return this.server == server && this.bridge.sameOwner(bridge)
          ? Optional.of(handle) : Optional.empty();
    }

    synchronized Optional<ForgePacketDefenseBridge<P>> resolve(S server) {
      return this.server == server ? Optional.of(bridge) : Optional.empty();
    }

    synchronized boolean remove(S server, OwnerHandle<S> handle) {
      if (this.server != server || this.handle != handle || handle.server != server) return false;
      bridge.invalidateOwner(server);
      this.server = null;
      bridge = null;
      this.handle = null;
      return true;
    }
  }

  private static final OwnerManager<MinecraftServer, ServerPlayer> OWNERS = new OwnerManager<>();

  private final ControlledIdentityRegistry<P> registry;
  private final ControlledPacketGuard.CurrentStateProvider<P> currentState;
  private ProtectedMovementHandlerTracker<Object, Object, P> movementTracker;

  public ForgePacketDefenseBridge(
      ControlledIdentityRegistry<P> registry,
      ControlledPacketGuard.CurrentStateProvider<P> currentState) {
    this.registry = Objects.requireNonNull(registry);
    this.currentState = Objects.requireNonNull(currentState);
  }

  /** Installs the exact event-defense registry with an explicit server-owned state provider. */
  public static Optional<OwnerHandle<MinecraftServer>> install(
      MinecraftServer server,
      ControlledIdentityRegistry<ServerPlayer> registry,
      ControlledPacketGuard.CurrentStateProvider<ServerPlayer> currentState) {
    return OWNERS.install(server, new ForgePacketDefenseBridge<>(registry, currentState));
  }

  public static Optional<OwnerHandle<MinecraftServer>> installInert(
      MinecraftServer server, ControlledIdentityRegistry<ServerPlayer> registry) {
    return install(server, registry, (player, body) -> Optional.empty());
  }

  public static Decision handleMovePlayer(
      MinecraftServer server,
      Supplier<ServerPlayer> playerSupplier,
      ServerboundMovePlayerPacket packet) {
    Objects.requireNonNull(server);
    Objects.requireNonNull(playerSupplier);
    Objects.requireNonNull(packet);
    if (!server.isSameThread()) return Decision.DENY;
    Optional<ForgePacketDefenseBridge<ServerPlayer>> installed = OWNERS.resolve(server);
    if (installed.isEmpty()) return Decision.BYPASS_OTHER_PLAYER;
    ServerPlayer player = Objects.requireNonNull(playerSupplier.get());
    if (player.getServer() != server) return Decision.DENY;
    MovementProposal movement =
        MovementProposal.positionAndRotation(
            packet.getX(player.getX()),
            packet.getY(player.getY()),
            packet.getZ(player.getZ()),
            packet.getYRot(player.getYRot()),
            packet.getXRot(player.getXRot()),
            packet.isOnGround());
    return installed.get().evaluatePlayerMovement(() -> true, () -> player, () -> movement);
  }

  public static Decision enterMovePlayer(
      MinecraftServer server,
      Supplier<Object> listenerSupplier,
      Supplier<ServerPlayer> playerSupplier,
      ServerboundMovePlayerPacket packet) {
    Objects.requireNonNull(listenerSupplier);
    if (!server.isSameThread()) return Decision.DENY;
    Optional<ForgePacketDefenseBridge<ServerPlayer>> installed = OWNERS.resolve(server);
    if (installed.isEmpty()) return Decision.BYPASS_OTHER_PLAYER;
    Object listener = Objects.requireNonNull(listenerSupplier.get());
    ServerPlayer player = Objects.requireNonNull(playerSupplier.get());
    if (player.getServer() != server) return Decision.DENY;
    MovementProposal movement = MovementProposal.positionAndRotation(
        packet.getX(player.getX()), packet.getY(player.getY()), packet.getZ(player.getZ()),
        packet.getYRot(player.getYRot()), packet.getXRot(player.getXRot()), packet.isOnGround());
    return installed.get().evaluateTrackedPlayerMovement(
        server, listener, () -> true, player, movement);
  }

  public static void returnMovePlayer(MinecraftServer server, Object listener) {
    if (!server.isSameThread()) return;
    OWNERS.resolve(server).ifPresent(bridge -> bridge.returnTrackedPlayerMovement(server, listener));
  }

  public static Decision handleMoveVehicle(
      MinecraftServer server, Supplier<ServerPlayer> playerSupplier) {
    Objects.requireNonNull(server);
    Objects.requireNonNull(playerSupplier);
    if (!server.isSameThread()) return Decision.DENY;
    Optional<ForgePacketDefenseBridge<ServerPlayer>> installed = OWNERS.resolve(server);
    if (installed.isEmpty()) return Decision.BYPASS_OTHER_PLAYER;
    ServerPlayer player = Objects.requireNonNull(playerSupplier.get());
    if (player.getServer() != server) return Decision.DENY;
    return installed.get().evaluateVehicleMovement(() -> true, () -> player);
  }

  public static Decision handleGameplay(
      MinecraftServer server, Supplier<ServerPlayer> playerSupplier, PacketCategory category) {
    Objects.requireNonNull(server);
    Objects.requireNonNull(playerSupplier);
    Objects.requireNonNull(category);
    if (!server.isSameThread()) return Decision.DENY;
    Optional<ForgePacketDefenseBridge<ServerPlayer>> installed = OWNERS.resolve(server);
    if (installed.isEmpty()) return Decision.BYPASS_OTHER_PLAYER;
    ServerPlayer player = Objects.requireNonNull(playerSupplier.get());
    if (player.getServer() != server) return Decision.DENY;
    return installed.get().evaluateGameplay(() -> true, () -> player, category);
  }

  public static Decision handleDeferredGameplay(
      MinecraftServer server,
      Supplier<Object> listenerSupplier,
      Supplier<ServerPlayer> playerSupplier,
      PacketCategory category) {
    Objects.requireNonNull(server);
    Objects.requireNonNull(listenerSupplier);
    Objects.requireNonNull(playerSupplier);
    Objects.requireNonNull(category);
    if (!isDeferredCategory(category)) return Decision.DENY;
    if (!server.isSameThread()) return Decision.DENY;
    Optional<ForgePacketDefenseBridge<ServerPlayer>> installed = OWNERS.resolve(server);
    if (installed.isEmpty()) {
      return markerDecision(listenerSupplier) ? Decision.DENY : Decision.BYPASS_OTHER_PLAYER;
    }
    return installed.get().evaluateDeferredGameplay(
        () -> true, listenerSupplier, playerSupplier, category);
  }

  public Decision evaluateDeferredGameplay(
      ControlledPacketGuard.TrustedThreadGate threadGate,
      Supplier<Object> listenerSupplier,
      Supplier<P> playerSupplier,
      PacketCategory category) {
    Objects.requireNonNull(threadGate);
    Objects.requireNonNull(listenerSupplier);
    Objects.requireNonNull(playerSupplier);
    Objects.requireNonNull(category);
    if (!isDeferredCategory(category) || !threadGate.isServerThreadOwner()) return Decision.DENY;
    if (markerDecision(listenerSupplier)) return Decision.DENY;
    P player;
    try {
      player = Objects.requireNonNull(playerSupplier.get());
    } catch (RuntimeException error) {
      return Decision.DENY;
    }
    return guard().evaluate(player, category, null);
  }

  public Decision evaluatePlayerMovement(
      ControlledPacketGuard.TrustedThreadGate threadGate,
      Supplier<P> playerSupplier,
      Supplier<MovementProposal> movementSupplier) {
    Objects.requireNonNull(threadGate);
    Objects.requireNonNull(playerSupplier);
    Objects.requireNonNull(movementSupplier);
    if (!threadGate.isServerThreadOwner()) return Decision.DENY;
    P player = Objects.requireNonNull(playerSupplier.get());
    MovementProposal movement = Objects.requireNonNull(movementSupplier.get());
    return guard().evaluate(player, PacketCategory.PLAYER_MOVEMENT, movement);
  }

  Decision evaluateTrackedPlayerMovement(
      Object server,
      Object listener,
      ControlledPacketGuard.TrustedThreadGate threadGate,
      P player,
      MovementProposal movement) {
    if (!threadGate.isServerThreadOwner()) return Decision.DENY;
    Optional<ControlledIdentityRegistry.ControlledBody<P>> captured = registry.current();
    if (captured.isEmpty() || captured.get().player() != player) {
      if (movementTracker != null && !movementTracker.enterUntracked(server, listener, player)) {
        return Decision.DENY;
      }
      return guard().evaluate(player, PacketCategory.PLAYER_MOVEMENT, movement);
    }
    ProtectedMovementHandlerTracker<Object, Object, P> tracker = tracker(server, threadGate);
    if (!tracker.enter(server, listener, player, captured.get())) return Decision.DENY;
    Decision decision = guard().evaluate(player, PacketCategory.PLAYER_MOVEMENT, movement);
    if (decision == Decision.ALLOW_MOVEMENT_PROPOSAL) {
      boolean remainsCurrent = registry.current().orElse(null) == captured.get();
      if (!tracker.authorizeLast(server, listener, player, captured.get(), remainsCurrent)) {
        return Decision.DENY;
      }
    }
    return decision;
  }

  void returnTrackedPlayerMovement(Object server, Object listener) {
    ProtectedMovementHandlerTracker<Object, Object, P> tracker = movementTracker;
    if (tracker != null) tracker.normalReturn(server, listener);
  }

  /**
   * Read-only observation that tracked authorized movement handlers have returned normally after the
   * exact old authority was revoked. Not READY, quiescence or permission to restore control.
   */
  Optional<ProtectedMovementHandlerTracker.DrainedObservation> observeProtectedMovementDrained(
      Object server,
      Object listener,
      P player,
      PermitAuthority.Binding binding,
      ControlledIdentityRegistry.PrepareFenceReceipt receipt,
      long monotonicNowMillis) {
    ProtectedMovementHandlerTracker<Object, Object, P> tracker = movementTracker;
    if (tracker == null) return Optional.empty();
    return tracker.observeDrained(
        server, listener, player, binding, receipt, guard(), monotonicNowMillis);
  }

  public Decision evaluateVehicleMovement(
      ControlledPacketGuard.TrustedThreadGate threadGate, Supplier<P> playerSupplier) {
    Objects.requireNonNull(threadGate);
    Objects.requireNonNull(playerSupplier);
    if (!threadGate.isServerThreadOwner()) return Decision.DENY;
    P player = Objects.requireNonNull(playerSupplier.get());
    return guard().evaluate(player, PacketCategory.VEHICLE_MOVEMENT, null);
  }

  public Decision evaluateGameplay(
      ControlledPacketGuard.TrustedThreadGate threadGate,
      Supplier<P> playerSupplier,
      PacketCategory category) {
    Objects.requireNonNull(threadGate);
    Objects.requireNonNull(playerSupplier);
    Objects.requireNonNull(category);
    if (!threadGate.isServerThreadOwner()) return Decision.DENY;
    P player = Objects.requireNonNull(playerSupplier.get());
    return guard().evaluate(player, category, null);
  }

  private ControlledPacketGuard<P> guard() {
    return new ControlledPacketGuard<>(registry, () -> true, currentState);
  }

  private boolean sameOwner(ForgePacketDefenseBridge<?> other) {
    return registry == other.registry && currentState == other.currentState;
  }

  private ProtectedMovementHandlerTracker<Object, Object, P> tracker(
      Object server, ControlledPacketGuard.TrustedThreadGate threadGate) {
    if (movementTracker == null) {
      movementTracker = new ProtectedMovementHandlerTracker<>(server, threadGate::isServerThreadOwner);
    }
    return movementTracker;
  }

  private void invalidateOwner(Object server) {
    if (movementTracker != null) movementTracker.invalidate(server);
  }

  private static boolean isDeferredCategory(PacketCategory category) {
    return category == PacketCategory.CHAT
        || category == PacketCategory.COMMAND
        || category == PacketCategory.BOOK_EDIT
        || category == PacketCategory.SIGN_UPDATE;
  }

  private static boolean markerDecision(Supplier<Object> listenerSupplier) {
    try {
      Object listener = Objects.requireNonNull(listenerSupplier.get());
      if (!(listener instanceof ControlledConnectionMarker marker)) return true;
      return marker.luosandbox$everControlled();
    } catch (RuntimeException error) {
      return true;
    }
  }
}
