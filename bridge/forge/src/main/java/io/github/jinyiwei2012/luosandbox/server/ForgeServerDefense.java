package io.github.jinyiwei2012.luosandbox.server;

import io.github.jinyiwei2012.luosandbox.servercontrol.PermitAuthority;
import java.util.Objects;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.ServerChatEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityTravelToDimensionEvent;
import net.minecraftforge.event.entity.item.ItemTossEvent;
import net.minecraftforge.event.entity.player.AttackEntityEvent;
import net.minecraftforge.event.entity.player.EntityItemPickupEvent;
import net.minecraftforge.event.entity.player.PlayerContainerEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.Event;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * Forge event-level default-deny defense for already trusted controlled connections.
 *
 * <p>This is intentionally not complete ordinary-packet enforcement. Movement and packet paths that
 * do not emit these cancellable events still require the separately reviewed packet guard.
 */
public final class ForgeServerDefense {
  public static final String ENABLE_PROPERTY = "luosandbox.serverDefense.enabled";

  public enum Action {
    LEFT_CLICK_BLOCK,
    RIGHT_CLICK_BLOCK,
    RIGHT_CLICK_ITEM,
    ENTITY_INTERACT,
    ENTITY_ATTACK,
    ITEM_TOSS,
    ITEM_PICKUP,
    CHAT,
    DIMENSION_TRAVEL
  }

  @FunctionalInterface
  public interface DefensePolicy {
    boolean deny(PermitAuthority.Binding binding, Action action);
  }

  private static final ControlledIdentityRegistry.IdentityAdapter<ServerPlayer> FORGE_IDENTITY =
      new ControlledIdentityRegistry.IdentityAdapter<>() {
        @Override
        public Object playerIdentity(ServerPlayer player) {
          return player;
        }

        @Override
        public Object connectionToken(ServerPlayer player) {
          return player.connection.connection;
        }
      };
  private static final ControlledIdentityRegistry.ProtectionMarker<ServerPlayer> FORGE_MARKER =
      (player, identity, connection) -> {
        if (!player.getServer().isSameThread() || identity != player
            || connection != player.connection.connection
            || !(player.connection instanceof ControlledConnectionMarker marker)) return false;
        marker.luosandbox$markEverControlled();
        return marker.luosandbox$everControlled();
      };

  private final ControlledIdentityRegistry<ServerPlayer> registry;
  private final DefensePolicy policy;
  private final ModContainerCloseWorkTracker containerCloseWork = new ModContainerCloseWorkTracker();

  public ForgeServerDefense(
      ControlledIdentityRegistry<ServerPlayer> registry, DefensePolicy policy) {
    this.registry = Objects.requireNonNull(registry);
    this.policy = Objects.requireNonNull(policy);
  }

  /** Creates an empty registry whose provisioning owner denies every candidate and binding. */
  public static ForgeServerDefense inert() {
    ControlledIdentityRegistry.TrustedProvisionPolicy<ServerPlayer> denyProvisioning =
        new ControlledIdentityRegistry.TrustedProvisionPolicy<>() {
          @Override
          public boolean mayProtectCandidate(
              ServerPlayer player, Object playerIdentity, Object connectionToken) {
            return false;
          }

          @Override
          public boolean mayBind(
              ServerPlayer player,
              Object playerIdentity,
              Object connectionToken,
              PermitAuthority.Binding binding,
              io.github.jinyiwei2012.luosandbox.servercontrol.ServerControlCoordinator coordinator) {
            return false;
          }
        };
    return new ForgeServerDefense(
        new ControlledIdentityRegistry<>(FORGE_IDENTITY, denyProvisioning, FORGE_MARKER),
        (binding, action) -> true);
  }

  public ControlledIdentityRegistry<ServerPlayer> registry() {
    return registry;
  }

  @SubscribeEvent
  public void onLeftClickBlock(PlayerInteractEvent.LeftClickBlock event) {
    deny(event, event.getEntity(), Action.LEFT_CLICK_BLOCK);
  }

  @SubscribeEvent
  public void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
    deny(event, event.getEntity(), Action.RIGHT_CLICK_BLOCK);
  }

  @SubscribeEvent
  public void onRightClickItem(PlayerInteractEvent.RightClickItem event) {
    deny(event, event.getEntity(), Action.RIGHT_CLICK_ITEM);
  }

  @SubscribeEvent
  public void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
    deny(event, event.getEntity(), Action.ENTITY_INTERACT);
  }

  @SubscribeEvent
  public void onEntityInteractSpecific(PlayerInteractEvent.EntityInteractSpecific event) {
    deny(event, event.getEntity(), Action.ENTITY_INTERACT);
  }

  @SubscribeEvent
  public void onEntityAttack(AttackEntityEvent event) {
    deny(event, event.getEntity(), Action.ENTITY_ATTACK);
  }

  @SubscribeEvent
  public void onItemToss(ItemTossEvent event) {
    deny(event, event.getPlayer(), Action.ITEM_TOSS);
  }

  @SubscribeEvent
  public void onItemPickup(EntityItemPickupEvent event) {
    deny(event, event.getEntity(), Action.ITEM_PICKUP);
  }

  @SubscribeEvent
  public void onChat(ServerChatEvent event) {
    deny(event, event.getPlayer(), Action.CHAT);
  }

  @SubscribeEvent
  public void onDimensionTravel(EntityTravelToDimensionEvent event) {
    if (event.getEntity() instanceof ServerPlayer player) {
      deny(event, player, Action.DIMENSION_TRAVEL);
    }
  }

  @SubscribeEvent
  public void onContainerOpen(PlayerContainerEvent.Open event) {
    if (event.getEntity() instanceof ServerPlayer player) {
      var server = player.getServer();
      ModContainerCloseWorkTracker.requireOwner(server::isSameThread);
      if (registry.isProtectedCandidate(player) || registry.resolve(player).isPresent()) {
        var preparation = prepareContainerClose(registry, containerCloseWork, player);
        try {
          registry.fence(player, PermitAuthority.RevokeReason.CONTROL_LOST);
        } catch (RuntimeException | Error failure) {
          if (preparation.fatal() != null) throw preparation.fatal();
          throw failure;
        }
        Runnable delegate = player::closeContainer;
        var call = preparation.captureFailed() ? null : containerCloseWork.register(
            server::isSameThread, server, player, preparation.capture(), delegate);
        try {
          server.execute(call == null ? delegate : call.runnable());
        } catch (RuntimeException | Error failure) {
          if (call != null) call.threw();
          throw failure;
        } finally {
          if (preparation.fatal() != null) throw preparation.fatal();
        }
        if (call != null) call.returned();
      }
    }
  }

  /** Narrow deterministic seam for the one mod-owned container-close submit site. */
  static <P> void submitContainerClose(
      ControlledIdentityRegistry<P> registry, ModContainerCloseWorkTracker tracker,
      java.util.function.BooleanSupplier owner, Object server, P player,
      java.util.function.Consumer<Runnable> execute, Runnable closeContainer) {
    ModContainerCloseWorkTracker.requireOwner(owner);
    if (registry.isProtectedCandidate(player) || registry.resolve(player).isPresent()) {
      var preparation = prepareContainerClose(registry, tracker, player);
      try {
        registry.fence(player, PermitAuthority.RevokeReason.CONTROL_LOST);
      } catch (RuntimeException | Error failure) {
        if (preparation.fatal() != null) throw preparation.fatal();
        throw failure;
      }
      try {
        if (preparation.captureFailed()) execute.accept(closeContainer);
        else tracker.submit(owner, server, player, preparation.capture(), execute, closeContainer);
      } finally {
        if (preparation.fatal() != null) throw preparation.fatal();
      }
    }
  }

  private record ContainerClosePreparation<P>(
      java.util.Optional<ControlledIdentityRegistry.ContainerCloseCapture<P>> capture,
      boolean captureFailed, Error fatal) {}

  /** Accounting capture may fail, but must never skip the original fence/close defense. */
  private static <P> ContainerClosePreparation<P> prepareContainerClose(
      ControlledIdentityRegistry<P> registry, ModContainerCloseWorkTracker tracker, P player) {
    java.util.Optional<ControlledIdentityRegistry.ContainerCloseCapture<P>> capture =
        java.util.Optional.empty();
    boolean failed = false;
    Error fatal = null;
    try {
      capture = registry.captureContainerClose(player);
    } catch (RuntimeException failure) {
      tracker.invalidate();
      failed = true;
    } catch (Error failure) {
      tracker.invalidate();
      failed = true;
      fatal = failure;
    }
    return new ContainerClosePreparation<>(capture, failed, fatal);
  }

  @SubscribeEvent
  public void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
    if (event.getEntity() instanceof ServerPlayer player) {
      registry.releaseOffline(player, PermitAuthority.RevokeReason.CONTROL_LOST);
    }
  }

  @SubscribeEvent
  public void onRespawn(PlayerEvent.PlayerRespawnEvent event) {
    fence(event.getEntity());
  }

  @SubscribeEvent
  public void onClone(PlayerEvent.Clone event) {
    if (event.getOriginal() instanceof ServerPlayer previous
        && event.getEntity() instanceof ServerPlayer replacement) {
      fence(previous);
      registry.transferCandidateQuarantine(previous, replacement);
    }
  }

  @SubscribeEvent
  public void onChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
    fence(event.getEntity());
  }

  @SubscribeEvent
  public void onPlayerTick(TickEvent.PlayerTickEvent event) {
    if (event.side.isServer()
        && event.phase == TickEvent.Phase.END
        && event.player instanceof ServerPlayer player
        && registry.resolve(player).isPresent()) {
      registry.deadlineElapsed();
    }
  }

  private void fence(net.minecraft.world.entity.player.Player player) {
    if (player instanceof ServerPlayer serverPlayer) {
      registry.fence(serverPlayer, PermitAuthority.RevokeReason.CONTROL_LOST);
    }
  }

  private void deny(Event event, net.minecraft.world.entity.player.Player player, Action action) {
    if (!(player instanceof ServerPlayer serverPlayer)) return;
    boolean candidate = registry.isProtectedCandidate(serverPlayer);
    var controlled = registry.resolve(serverPlayer);
    if (shouldDeny(
        candidate, controlled.map(ControlledIdentityRegistry.ControlledBody::binding), policy, action)) {
      event.setCanceled(true);
    }
  }

  static boolean shouldDeny(
      boolean protectedCandidate,
      java.util.Optional<PermitAuthority.Binding> controlledBinding,
      DefensePolicy policy,
      Action action) {
    Objects.requireNonNull(controlledBinding);
    Objects.requireNonNull(policy);
    Objects.requireNonNull(action);
    return (protectedCandidate || controlledBinding.isPresent())
        && controlledBinding.map(binding -> policy.deny(binding, action)).orElse(true);
  }
}
