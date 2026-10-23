package io.github.jinyiwei2012.luosandbox.server;

import io.github.jinyiwei2012.luosandbox.server.ControlledPacketGuard.Decision;
import io.github.jinyiwei2012.luosandbox.server.ControlledPacketGuard.MovementProposal;
import io.github.jinyiwei2012.luosandbox.server.ControlledPacketGuard.PacketCategory;
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
      this.server = null;
      bridge = null;
      this.handle = null;
      return true;
    }
  }

  private static final OwnerManager<MinecraftServer, ServerPlayer> OWNERS = new OwnerManager<>();

  private final ControlledIdentityRegistry<P> registry;
  private final ControlledPacketGuard.CurrentStateProvider<P> currentState;

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

  public Decision evaluateVehicleMovement(
      ControlledPacketGuard.TrustedThreadGate threadGate, Supplier<P> playerSupplier) {
    Objects.requireNonNull(threadGate);
    Objects.requireNonNull(playerSupplier);
    if (!threadGate.isServerThreadOwner()) return Decision.DENY;
    P player = Objects.requireNonNull(playerSupplier.get());
    return guard().evaluate(player, PacketCategory.VEHICLE_MOVEMENT, null);
  }

  private ControlledPacketGuard<P> guard() {
    return new ControlledPacketGuard<>(registry, () -> true, currentState);
  }

  private boolean sameOwner(ForgePacketDefenseBridge<?> other) {
    return registry == other.registry && currentState == other.currentState;
  }
}
