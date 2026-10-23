package io.github.jinyiwei2012.luosandbox.server;

import io.github.jinyiwei2012.luosandbox.servercontrol.PermitAuthority;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;

/**
 * Pure server-thread packet policy joining exact controlled identity to the permit coordinator.
 *
 * <p>This guard performs no authentication, IO, Netty access or world mutation. Movement ALLOW means
 * only that the proposed packet state passed the existing permit gate; it is not evidence that
 * vanilla accepted or committed the movement. The existing permit gate conservatively accounts an
 * allowed proposal before vanilla processing, so a later vanilla rejection may consume distance.
 */
public final class ControlledPacketGuard<P> {
  public enum PacketCategory {
    PLAYER_MOVEMENT,
    KEEPALIVE,
    PONG,
    CHAT_ACK,
    ACCEPT_TELEPORT,
    CLIENT_INFORMATION,
    CHAT_SESSION_UPDATE,
    RESOURCE_PACK_RESPONSE,
    CONTAINER_CLOSE,
    FORGE_CUSTOM_PAYLOAD,
    CHAT,
    COMMAND,
    ATTACK,
    USE,
    PLACE,
    MINE,
    INVENTORY,
    CREATIVE_INVENTORY,
    VEHICLE_MOVEMENT,
    KEY_INPUT,
    OTHER_GAMEPLAY
  }

  public enum Decision {
    BYPASS_OTHER_PLAYER,
    BYPASS_TO_FORGE,
    ALLOW_HEALTH,
    ALLOW_MOVEMENT_PROPOSAL,
    DENY
  }

  @FunctionalInterface
  public interface TrustedThreadGate {
    boolean isServerThreadOwner();
  }

  @FunctionalInterface
  public interface CurrentStateProvider<P> {
    Optional<TrustedCurrentState> current(
        P player, ControlledIdentityRegistry.ControlledBody<P> controlledBody);
  }

  /** Server-owned current state; none of these fields may originate from the packet payload. */
  public record TrustedCurrentState(
      PermitAuthority.Binding binding,
      String permitId,
      PermitAuthority.CurrentContext context,
      double yaw,
      double pitch) {
    public TrustedCurrentState {
      Objects.requireNonNull(binding);
      requireText(permitId);
      Objects.requireNonNull(context);
      if (!Double.isFinite(yaw) || !Double.isFinite(pitch)) {
        throw new IllegalArgumentException("non-finite trusted rotation");
      }
    }
  }

  /** Optional packet fields follow mapped vanilla fallback semantics against trusted current state. */
  public record MovementProposal(
      OptionalDouble x,
      OptionalDouble y,
      OptionalDouble z,
      OptionalDouble yaw,
      OptionalDouble pitch,
      Optional<Boolean> onGround) {
    public MovementProposal {
      x = requireFinite(x, "x");
      y = requireFinite(y, "y");
      z = requireFinite(z, "z");
      yaw = requireFinite(yaw, "yaw");
      pitch = requireFinite(pitch, "pitch");
      onGround = Objects.requireNonNull(onGround);
    }

    public static MovementProposal positionAndRotation(
        double x, double y, double z, double yaw, double pitch, boolean onGround) {
      return new MovementProposal(
          OptionalDouble.of(x),
          OptionalDouble.of(y),
          OptionalDouble.of(z),
          OptionalDouble.of(yaw),
          OptionalDouble.of(pitch),
          Optional.of(onGround));
    }

    PermitAuthority.Position proposedPosition(PermitAuthority.Position current) {
      return new PermitAuthority.Position(
          x.orElse(current.x()), y.orElse(current.y()), z.orElse(current.z()));
    }

    double proposedYaw(double current) {
      return yaw.orElse(current);
    }

    double proposedPitch(double current) {
      return pitch.orElse(current);
    }
  }

  private final ControlledIdentityRegistry<P> registry;
  private final TrustedThreadGate threadGate;
  private final CurrentStateProvider<P> currentState;

  public ControlledPacketGuard(
      ControlledIdentityRegistry<P> registry,
      TrustedThreadGate threadGate,
      CurrentStateProvider<P> currentState) {
    this.registry = Objects.requireNonNull(registry);
    this.threadGate = Objects.requireNonNull(threadGate);
    this.currentState = Objects.requireNonNull(currentState);
  }

  public Decision evaluate(P player, PacketCategory category, MovementProposal movement) {
    Objects.requireNonNull(player);
    Objects.requireNonNull(category);
    if (!threadGate.isServerThreadOwner()) return Decision.DENY;

    Optional<ControlledIdentityRegistry.ControlledBody<P>> controlled = registry.resolve(player);
    boolean candidate = registry.isProtectedCandidate(player);
    if (controlled.isEmpty() && !candidate) return Decision.BYPASS_OTHER_PLAYER;
    if (category == PacketCategory.FORGE_CUSTOM_PAYLOAD) return Decision.BYPASS_TO_FORGE;
    if (isHealth(category)) return Decision.ALLOW_HEALTH;
    if (category != PacketCategory.PLAYER_MOVEMENT || controlled.isEmpty() || movement == null) {
      return Decision.DENY;
    }

    ControlledIdentityRegistry.ControlledBody<P> body = controlled.get();
    try {
      Optional<TrustedCurrentState> supplied = currentState.current(player, body);
      if (supplied.isEmpty()) return Decision.DENY;
      TrustedCurrentState state = supplied.get();
      if (!body.binding().equals(state.binding())
          || !body.binding().equals(state.context().binding())) return Decision.DENY;
      PermitAuthority.Position proposed = movement.proposedPosition(state.context().position());
      PermitAuthority.Gate result =
          body.coordinator()
              .gate(
                  state.permitId(),
                  state.context(),
                  proposed,
                  movement.proposedYaw(state.yaw()),
                  movement.proposedPitch(state.pitch()));
      return result == PermitAuthority.Gate.ALLOW
          ? Decision.ALLOW_MOVEMENT_PROPOSAL
          : Decision.DENY;
    } catch (RuntimeException error) {
      return Decision.DENY;
    }
  }

  private static boolean isHealth(PacketCategory category) {
    return switch (category) {
      case KEEPALIVE,
          PONG,
          CHAT_ACK,
          ACCEPT_TELEPORT,
          CLIENT_INFORMATION,
          CHAT_SESSION_UPDATE,
          RESOURCE_PACK_RESPONSE,
          CONTAINER_CLOSE -> true;
      default -> false;
    };
  }

  private static OptionalDouble requireFinite(OptionalDouble value, String name) {
    Objects.requireNonNull(value);
    if (value.isPresent() && !Double.isFinite(value.getAsDouble())) {
      throw new IllegalArgumentException("non-finite " + name);
    }
    return value;
  }

  private static void requireText(String value) {
    if (value == null || value.isBlank()) throw new IllegalArgumentException("blank permit id");
  }
}
