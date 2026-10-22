package io.github.jinyiwei2012.luosandbox.security;

import java.util.Set;

/** Default-deny routing matrix over immutable authenticated connection context. */
public final class ControlRoutePolicy {
  private ControlRoutePolicy() {}

  public enum Path {
    WS_PEER_TO_HUB,
    WS_HUB_TO_PEER,
    SIMPLE_CLIENT_TO_SERVER,
    SIMPLE_SERVER_TO_CLIENT
  }

  public enum ConnectionAuthState { UNAUTHENTICATED, PENDING, AUTHENTICATED }
  public enum BodyBindingState { UNBOUND, PREPARING, BOUND }
  public enum Producer { PEER, INTERNAL_AGENT, INTERNAL_REGISTRY }
  public enum Decision { ALLOW, DEFAULT_DENY, FENCE_REQUIRED }

  public record Origin(ControlAuth.Role role, String installId, String bodyId,
      ConnectionAuthState authState, BodyBindingState bindingState, Producer producer) {}
  public record Destination(ControlAuth.Role role, String installId, String bodyId,
      ConnectionAuthState authState, BodyBindingState bindingState) {}
  public record Binding(String bodyId, String controllerInstallId, String serverInstallId,
      String hubInstallId) {}

  public static Decision check(Path path, Origin origin, Destination destination, Binding binding,
      String kind) {
    if (!hasValidContext(path, origin, destination, binding, kind)) return Decision.DEFAULT_DENY;

    boolean allowed = switch (path) {
      case WS_PEER_TO_HUB -> peerToHub(origin, destination, binding, kind);
      case WS_HUB_TO_PEER -> hubToPeer(origin, destination, binding, kind);
      case SIMPLE_CLIENT_TO_SERVER -> simpleClientToServer(origin, destination, binding, kind);
      case SIMPLE_SERVER_TO_CLIENT -> simpleServerToClient(origin, destination, binding, kind);
    };
    if (allowed) return Decision.ALLOW;
    return isProtectedKind(kind) && hasConnectedBodyOwner(path, origin, destination, binding)
        ? Decision.FENCE_REQUIRED : Decision.DEFAULT_DENY;
  }

  private static boolean peerToHub(Origin origin, Destination destination, Binding binding, String kind) {
    if (!isHubDestination(destination, binding) || origin.producer() != Producer.PEER) return false;
    if (origin.authState() == ConnectionAuthState.UNAUTHENTICATED) return kind.equals("HELLO");
    if (origin.authState() == ConnectionAuthState.PENDING) return kind.equals("PROOF");
    if (origin.role() == ControlAuth.Role.CLIENT_CONTROLLER
        && origin.installId().equals(binding.controllerInstallId())) {
      return Set.of("BODY_BIND_NOTICE", "LOCAL_EMERGENCY_STOP", "HEARTBEAT").contains(kind);
    }
    if (origin.role() != ControlAuth.Role.SERVER_AUTHORITY
        || !origin.installId().equals(binding.serverInstallId())) return false;
    if (kind.equals("HEARTBEAT")) return true;
    if (kind.equals("BODY_BOUND")) return origin.bindingState() == BodyBindingState.UNBOUND;
    if (kind.equals("BINDING_READY")) return origin.bindingState() == BodyBindingState.PREPARING;
    if (kind.equals("BINDING_ACTIVE")) return origin.bindingState() == BodyBindingState.BOUND;
    return origin.bindingState() == BodyBindingState.BOUND
        && Set.of("observation", "admission", "receipt", "cancel_result", "reconcile_result")
            .contains(kind);
  }

  private static boolean hubToPeer(Origin origin, Destination destination, Binding binding, String kind) {
    if (origin.role() != ControlAuth.Role.AGENT_HUB
        || !origin.installId().equals(binding.hubInstallId())) return false;
    if (origin.producer() == Producer.INTERNAL_REGISTRY) {
      if (origin.authState() == ConnectionAuthState.PENDING) {
        return kind.equals("CHALLENGE") && isHandshakeDestination(destination, binding)
            && destination.authState() == ConnectionAuthState.PENDING;
      }
      if (kind.equals("READY")) {
        return isHandshakeDestination(destination, binding)
            && origin.authState() == ConnectionAuthState.AUTHENTICATED
            && destination.authState() == ConnectionAuthState.PENDING;
      }
      if (destination.role() != ControlAuth.Role.SERVER_AUTHORITY
          || !destination.installId().equals(binding.serverInstallId())) return false;
      if (!authenticated(origin) || !authenticated(destination)) return false;
      if (kind.equals("HEARTBEAT")) return true;
      if (kind.equals("BINDING_PREPARE")) return destination.bindingState() != BodyBindingState.PREPARING;
      return kind.equals("BINDING_COMMIT") && destination.bindingState() == BodyBindingState.PREPARING;
    }
    return origin.producer() == Producer.INTERNAL_AGENT
        && destination.role() == ControlAuth.Role.SERVER_AUTHORITY
        && destination.installId().equals(binding.serverInstallId()) && authenticated(origin)
        && origin.bindingState() == BodyBindingState.BOUND && authenticated(destination)
        && destination.bindingState() == BodyBindingState.BOUND
        && Set.of("request", "cancel", "reconcile").contains(kind);
  }

  private static boolean simpleClientToServer(Origin origin, Destination destination, Binding binding,
      String kind) {
    return origin.role() == ControlAuth.Role.CLIENT_CONTROLLER && origin.producer() == Producer.PEER
        && origin.installId().equals(binding.controllerInstallId())
        && destination.role() == ControlAuth.Role.SERVER_AUTHORITY
        && destination.installId().equals(binding.serverInstallId())
        && origin.authState() == ConnectionAuthState.PENDING
        && destination.authState() == ConnectionAuthState.PENDING && kind.equals("BODY_PROOF");
  }

  private static boolean simpleServerToClient(Origin origin, Destination destination, Binding binding,
      String kind) {
    if (origin.role() != ControlAuth.Role.SERVER_AUTHORITY || origin.producer() != Producer.PEER
        || !origin.installId().equals(binding.serverInstallId())
        || destination.role() != ControlAuth.Role.CLIENT_CONTROLLER
        || !destination.installId().equals(binding.controllerInstallId())) return false;
    if (origin.authState() == ConnectionAuthState.PENDING) {
      return destination.authState() == ConnectionAuthState.PENDING && kind.equals("BODY_CHALLENGE");
    }
    if (!authenticated(origin) || !authenticated(destination)) return false;
    if (kind.equals("BODY_ACTIVE")) {
      return origin.bindingState() == BodyBindingState.BOUND
          && destination.bindingState() == BodyBindingState.PREPARING;
    }
    return origin.bindingState() == BodyBindingState.BOUND
        && destination.bindingState() == BodyBindingState.BOUND
        && Set.of("PERMIT", "REVOKE", "HEARTBEAT").contains(kind);
  }

  private static boolean hasValidContext(Path path, Origin origin, Destination destination,
      Binding binding, String kind) {
    return path != null && origin != null && destination != null && binding != null && kind != null
        && binding.bodyId().equals(origin.bodyId()) && binding.bodyId().equals(destination.bodyId());
  }

  private static boolean isHubDestination(Destination destination, Binding binding) {
    return destination.role() == ControlAuth.Role.AGENT_HUB
        && destination.installId().equals(binding.hubInstallId());
  }

  private static boolean isHandshakeDestination(Destination destination, Binding binding) {
    return (destination.role() == ControlAuth.Role.SERVER_AUTHORITY
            && destination.installId().equals(binding.serverInstallId()))
        || (destination.role() == ControlAuth.Role.CLIENT_CONTROLLER
            && destination.installId().equals(binding.controllerInstallId()));
  }

  private static boolean authenticated(Origin origin) {
    return origin.authState() == ConnectionAuthState.AUTHENTICATED;
  }

  private static boolean authenticated(Destination destination) {
    return destination.authState() == ConnectionAuthState.AUTHENTICATED;
  }

  private static boolean hasConnectedBodyOwner(Path path, Origin origin, Destination destination,
      Binding binding) {
    if (!authenticated(origin)) return false;
    return switch (path) {
      case WS_PEER_TO_HUB -> isHubDestination(destination, binding)
          && ((origin.role() == ControlAuth.Role.CLIENT_CONTROLLER
                  && origin.installId().equals(binding.controllerInstallId()))
              || (origin.role() == ControlAuth.Role.SERVER_AUTHORITY
                  && origin.installId().equals(binding.serverInstallId())));
      case WS_HUB_TO_PEER -> origin.role() == ControlAuth.Role.AGENT_HUB
          && origin.installId().equals(binding.hubInstallId())
          && destination.role() == ControlAuth.Role.SERVER_AUTHORITY
          && destination.installId().equals(binding.serverInstallId());
      case SIMPLE_CLIENT_TO_SERVER -> origin.role() == ControlAuth.Role.CLIENT_CONTROLLER
          && origin.installId().equals(binding.controllerInstallId())
          && destination.role() == ControlAuth.Role.SERVER_AUTHORITY
          && destination.installId().equals(binding.serverInstallId());
      case SIMPLE_SERVER_TO_CLIENT -> origin.role() == ControlAuth.Role.SERVER_AUTHORITY
          && origin.installId().equals(binding.serverInstallId())
          && destination.role() == ControlAuth.Role.CLIENT_CONTROLLER
          && destination.installId().equals(binding.controllerInstallId());
    };
  }

  private static boolean isProtectedKind(String kind) {
    return Set.of("BODY_BOUND", "BINDING_READY", "BINDING_ACTIVE", "BINDING_PREPARE",
        "BINDING_COMMIT", "observation", "admission", "receipt", "cancel_result",
        "reconcile_result", "request", "cancel", "reconcile").contains(kind);
  }
}
