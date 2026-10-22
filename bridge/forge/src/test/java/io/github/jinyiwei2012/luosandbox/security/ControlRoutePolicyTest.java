package io.github.jinyiwei2012.luosandbox.security;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ControlRoutePolicyTest {
  private static final String BODY = "body";
  private static final String CLIENT = "client";
  private static final String SERVER = "server";
  private static final String HUB = "hub";
  private static final ControlRoutePolicy.Binding BINDING =
      new ControlRoutePolicy.Binding(BODY, CLIENT, SERVER, HUB);

  @Test
  void permitsFrozenBootstrapBindingAndBoundRequestSequence() {
    assertAllowed(ControlRoutePolicy.Path.WS_PEER_TO_HUB, peer(ControlAuth.Role.CLIENT_CONTROLLER, CLIENT,
        ControlRoutePolicy.ConnectionAuthState.UNAUTHENTICATED, ControlRoutePolicy.BodyBindingState.UNBOUND),
        hub(ControlRoutePolicy.ConnectionAuthState.UNAUTHENTICATED, ControlRoutePolicy.BodyBindingState.UNBOUND), "HELLO");
    assertAllowed(ControlRoutePolicy.Path.WS_PEER_TO_HUB, clientOrigin(ControlRoutePolicy.ConnectionAuthState.PENDING,
        ControlRoutePolicy.BodyBindingState.UNBOUND), hub(ControlRoutePolicy.ConnectionAuthState.PENDING,
        ControlRoutePolicy.BodyBindingState.UNBOUND), "PROOF");
    assertAllowed(ControlRoutePolicy.Path.WS_HUB_TO_PEER, registry(ControlRoutePolicy.ConnectionAuthState.PENDING),
        serverDestination(ControlRoutePolicy.ConnectionAuthState.PENDING, ControlRoutePolicy.BodyBindingState.UNBOUND), "CHALLENGE");
    assertAllowed(ControlRoutePolicy.Path.WS_HUB_TO_PEER, registry(ControlRoutePolicy.ConnectionAuthState.AUTHENTICATED),
        serverDestination(ControlRoutePolicy.ConnectionAuthState.PENDING, ControlRoutePolicy.BodyBindingState.UNBOUND), "READY");
    assertAllowed(ControlRoutePolicy.Path.WS_HUB_TO_PEER, registry(ControlRoutePolicy.ConnectionAuthState.PENDING),
        client(ControlRoutePolicy.ConnectionAuthState.PENDING, ControlRoutePolicy.BodyBindingState.UNBOUND), "CHALLENGE");
    assertAllowed(ControlRoutePolicy.Path.WS_HUB_TO_PEER, registry(ControlRoutePolicy.ConnectionAuthState.AUTHENTICATED),
        client(ControlRoutePolicy.ConnectionAuthState.PENDING, ControlRoutePolicy.BodyBindingState.UNBOUND), "READY");
    assertAllowed(ControlRoutePolicy.Path.WS_PEER_TO_HUB, server(ControlRoutePolicy.ConnectionAuthState.AUTHENTICATED,
        ControlRoutePolicy.BodyBindingState.UNBOUND), hub(ControlRoutePolicy.ConnectionAuthState.AUTHENTICATED,
        ControlRoutePolicy.BodyBindingState.UNBOUND), "BODY_BOUND");
    assertAllowed(ControlRoutePolicy.Path.WS_HUB_TO_PEER, registry(ControlRoutePolicy.ConnectionAuthState.AUTHENTICATED),
        serverDestination(ControlRoutePolicy.ConnectionAuthState.AUTHENTICATED, ControlRoutePolicy.BodyBindingState.UNBOUND), "BINDING_PREPARE");
    assertAllowed(ControlRoutePolicy.Path.WS_PEER_TO_HUB, server(ControlRoutePolicy.ConnectionAuthState.AUTHENTICATED,
        ControlRoutePolicy.BodyBindingState.PREPARING), hub(ControlRoutePolicy.ConnectionAuthState.AUTHENTICATED,
        ControlRoutePolicy.BodyBindingState.PREPARING), "BINDING_READY");
    assertAllowed(ControlRoutePolicy.Path.WS_HUB_TO_PEER, registry(ControlRoutePolicy.ConnectionAuthState.AUTHENTICATED),
        serverDestination(ControlRoutePolicy.ConnectionAuthState.AUTHENTICATED, ControlRoutePolicy.BodyBindingState.PREPARING), "BINDING_COMMIT");
    assertAllowed(ControlRoutePolicy.Path.WS_PEER_TO_HUB, server(ControlRoutePolicy.ConnectionAuthState.AUTHENTICATED,
        ControlRoutePolicy.BodyBindingState.BOUND), hub(ControlRoutePolicy.ConnectionAuthState.AUTHENTICATED,
        ControlRoutePolicy.BodyBindingState.BOUND), "BINDING_ACTIVE");
    assertAllowed(ControlRoutePolicy.Path.SIMPLE_SERVER_TO_CLIENT, boundServerOrigin(), client(
        ControlRoutePolicy.ConnectionAuthState.AUTHENTICATED, ControlRoutePolicy.BodyBindingState.PREPARING), "BODY_ACTIVE");
    assertAllowed(ControlRoutePolicy.Path.WS_HUB_TO_PEER, agent(), boundServerDestination(), "request");
    assertAllowed(ControlRoutePolicy.Path.WS_HUB_TO_PEER, registry(ControlRoutePolicy.ConnectionAuthState.AUTHENTICATED),
        boundServerDestination(), "BINDING_PREPARE");
  }

  @Test
  void deniesUnauthenticatedBusinessAndAuthenticatedUnboundAgent() {
    assertDenied(ControlRoutePolicy.Path.WS_HUB_TO_PEER, agent(ControlRoutePolicy.ConnectionAuthState.UNAUTHENTICATED,
        ControlRoutePolicy.BodyBindingState.UNBOUND), boundServerDestination(), "request");
    assertDenied(ControlRoutePolicy.Path.WS_HUB_TO_PEER, registry(ControlRoutePolicy.ConnectionAuthState.UNAUTHENTICATED),
        boundServerDestination(), "BINDING_PREPARE");
    assertDenied(ControlRoutePolicy.Path.WS_HUB_TO_PEER, registry(ControlRoutePolicy.ConnectionAuthState.UNAUTHENTICATED),
        serverDestination(ControlRoutePolicy.ConnectionAuthState.UNAUTHENTICATED, ControlRoutePolicy.BodyBindingState.UNBOUND), "BINDING_COMMIT");
    assertDenied(ControlRoutePolicy.Path.WS_HUB_TO_PEER, registry(ControlRoutePolicy.ConnectionAuthState.UNAUTHENTICATED),
        serverDestination(ControlRoutePolicy.ConnectionAuthState.UNAUTHENTICATED, ControlRoutePolicy.BodyBindingState.UNBOUND), "HEARTBEAT");
    assertFenced(ControlRoutePolicy.Path.WS_HUB_TO_PEER, agent(ControlRoutePolicy.ConnectionAuthState.AUTHENTICATED,
        ControlRoutePolicy.BodyBindingState.UNBOUND), boundServerDestination(), "request");
  }

  @Test
  void validatesHubAndServerDestinationIdentitiesAndRejectsReverseRoutes() {
    assertDenied(ControlRoutePolicy.Path.WS_HUB_TO_PEER, agentWithInstall(SERVER), boundServerDestination(), "request");
    assertDenied(ControlRoutePolicy.Path.WS_HUB_TO_PEER, registry(ControlRoutePolicy.ConnectionAuthState.PENDING),
        clientWithInstall("other-client", ControlRoutePolicy.ConnectionAuthState.PENDING,
            ControlRoutePolicy.BodyBindingState.UNBOUND), "CHALLENGE");
    assertDenied(ControlRoutePolicy.Path.WS_HUB_TO_PEER, registry(ControlRoutePolicy.ConnectionAuthState.AUTHENTICATED),
        serverDestination(ControlRoutePolicy.ConnectionAuthState.AUTHENTICATED,
            ControlRoutePolicy.BodyBindingState.UNBOUND), "READY");
    assertDenied(ControlRoutePolicy.Path.WS_HUB_TO_PEER, registry(ControlRoutePolicy.ConnectionAuthState.AUTHENTICATED),
        client(ControlRoutePolicy.ConnectionAuthState.AUTHENTICATED,
            ControlRoutePolicy.BodyBindingState.UNBOUND), "READY");
    assertDenied(ControlRoutePolicy.Path.WS_HUB_TO_PEER, registry(ControlRoutePolicy.ConnectionAuthState.UNAUTHENTICATED),
        serverDestination(ControlRoutePolicy.ConnectionAuthState.PENDING,
            ControlRoutePolicy.BodyBindingState.UNBOUND), "READY");
    assertDenied(ControlRoutePolicy.Path.WS_HUB_TO_PEER, registry(ControlRoutePolicy.ConnectionAuthState.AUTHENTICATED),
        serverDestination(ControlRoutePolicy.ConnectionAuthState.UNAUTHENTICATED,
            ControlRoutePolicy.BodyBindingState.UNBOUND), "READY");
    assertDenied(ControlRoutePolicy.Path.WS_HUB_TO_PEER, agent(), client(ControlRoutePolicy.ConnectionAuthState.AUTHENTICATED,
        ControlRoutePolicy.BodyBindingState.BOUND), "request");
    assertDenied(ControlRoutePolicy.Path.WS_HUB_TO_PEER, registry(ControlRoutePolicy.ConnectionAuthState.AUTHENTICATED),
        client(ControlRoutePolicy.ConnectionAuthState.AUTHENTICATED, ControlRoutePolicy.BodyBindingState.UNBOUND), "BINDING_PREPARE");
    assertDenied(ControlRoutePolicy.Path.WS_HUB_TO_PEER, agent(), serverWithInstall("other-server",
        ControlRoutePolicy.ConnectionAuthState.AUTHENTICATED, ControlRoutePolicy.BodyBindingState.BOUND), "request");
    assertDenied(ControlRoutePolicy.Path.WS_PEER_TO_HUB,
        server(ControlRoutePolicy.ConnectionAuthState.AUTHENTICATED, ControlRoutePolicy.BodyBindingState.BOUND),
        boundServerDestination(), "receipt");
    assertDenied(ControlRoutePolicy.Path.SIMPLE_CLIENT_TO_SERVER, clientOrigin(ControlRoutePolicy.ConnectionAuthState.PENDING,
        ControlRoutePolicy.BodyBindingState.UNBOUND), client(ControlRoutePolicy.ConnectionAuthState.PENDING,
        ControlRoutePolicy.BodyBindingState.UNBOUND), "BODY_PROOF");
    assertDenied(ControlRoutePolicy.Path.WS_HUB_TO_PEER, agent(), boundServerDestination(), "unknown");
  }

  @Test
  void allowsSimpleChannelCandidateAndBoundControlRoutes() {
    assertAllowed(ControlRoutePolicy.Path.SIMPLE_SERVER_TO_CLIENT, server(ControlRoutePolicy.ConnectionAuthState.PENDING,
        ControlRoutePolicy.BodyBindingState.UNBOUND), client(ControlRoutePolicy.ConnectionAuthState.PENDING,
        ControlRoutePolicy.BodyBindingState.UNBOUND), "BODY_CHALLENGE");
    assertAllowed(ControlRoutePolicy.Path.SIMPLE_CLIENT_TO_SERVER, clientOrigin(ControlRoutePolicy.ConnectionAuthState.PENDING,
        ControlRoutePolicy.BodyBindingState.UNBOUND), serverDestination(ControlRoutePolicy.ConnectionAuthState.PENDING,
        ControlRoutePolicy.BodyBindingState.UNBOUND), "BODY_PROOF");
    assertAllowed(ControlRoutePolicy.Path.SIMPLE_SERVER_TO_CLIENT, boundServerOrigin(), boundClientDestination(), "PERMIT");
    assertAllowed(ControlRoutePolicy.Path.SIMPLE_SERVER_TO_CLIENT, boundServerOrigin(), boundClientDestination(), "REVOKE");
    assertDenied(ControlRoutePolicy.Path.SIMPLE_SERVER_TO_CLIENT, server(
        ControlRoutePolicy.ConnectionAuthState.AUTHENTICATED, ControlRoutePolicy.BodyBindingState.PREPARING),
        client(ControlRoutePolicy.ConnectionAuthState.AUTHENTICATED, ControlRoutePolicy.BodyBindingState.PREPARING), "BODY_ACTIVE");
  }

  private static void assertAllowed(ControlRoutePolicy.Path path, ControlRoutePolicy.Origin origin,
      ControlRoutePolicy.Destination destination, String kind) {
    assertEquals(ControlRoutePolicy.Decision.ALLOW,
        ControlRoutePolicy.check(path, origin, destination, BINDING, kind));
  }

  private static void assertDenied(ControlRoutePolicy.Path path, ControlRoutePolicy.Origin origin,
      ControlRoutePolicy.Destination destination, String kind) {
    assertEquals(ControlRoutePolicy.Decision.DEFAULT_DENY,
        ControlRoutePolicy.check(path, origin, destination, BINDING, kind));
  }

  private static void assertFenced(ControlRoutePolicy.Path path, ControlRoutePolicy.Origin origin,
      ControlRoutePolicy.Destination destination, String kind) {
    assertEquals(ControlRoutePolicy.Decision.FENCE_REQUIRED,
        ControlRoutePolicy.check(path, origin, destination, BINDING, kind));
  }

  private static ControlRoutePolicy.Origin peer(ControlAuth.Role role, String install,
      ControlRoutePolicy.ConnectionAuthState auth, ControlRoutePolicy.BodyBindingState state) {
    return new ControlRoutePolicy.Origin(role, install, BODY, auth, state, ControlRoutePolicy.Producer.PEER);
  }

  private static ControlRoutePolicy.Origin server(ControlRoutePolicy.ConnectionAuthState auth,
      ControlRoutePolicy.BodyBindingState state) {
    return peer(ControlAuth.Role.SERVER_AUTHORITY, SERVER, auth, state);
  }

  private static ControlRoutePolicy.Origin agent() {
    return agent(ControlRoutePolicy.ConnectionAuthState.AUTHENTICATED, ControlRoutePolicy.BodyBindingState.BOUND);
  }

  private static ControlRoutePolicy.Origin agent(ControlRoutePolicy.ConnectionAuthState auth,
      ControlRoutePolicy.BodyBindingState state) {
    return new ControlRoutePolicy.Origin(ControlAuth.Role.AGENT_HUB, HUB, BODY, auth, state,
        ControlRoutePolicy.Producer.INTERNAL_AGENT);
  }

  private static ControlRoutePolicy.Origin agentWithInstall(String install) {
    return new ControlRoutePolicy.Origin(ControlAuth.Role.AGENT_HUB, install, BODY,
        ControlRoutePolicy.ConnectionAuthState.AUTHENTICATED, ControlRoutePolicy.BodyBindingState.BOUND,
        ControlRoutePolicy.Producer.INTERNAL_AGENT);
  }

  private static ControlRoutePolicy.Origin registry(ControlRoutePolicy.ConnectionAuthState auth) {
    return new ControlRoutePolicy.Origin(ControlAuth.Role.AGENT_HUB, HUB, BODY, auth,
        ControlRoutePolicy.BodyBindingState.UNBOUND, ControlRoutePolicy.Producer.INTERNAL_REGISTRY);
  }

  private static ControlRoutePolicy.Destination hub(ControlRoutePolicy.ConnectionAuthState auth,
      ControlRoutePolicy.BodyBindingState state) {
    return new ControlRoutePolicy.Destination(ControlAuth.Role.AGENT_HUB, HUB, BODY, auth, state);
  }

  private static ControlRoutePolicy.Destination serverDestination(ControlRoutePolicy.ConnectionAuthState auth,
      ControlRoutePolicy.BodyBindingState state) {
    return new ControlRoutePolicy.Destination(ControlAuth.Role.SERVER_AUTHORITY, SERVER, BODY, auth, state);
  }

  private static ControlRoutePolicy.Destination serverWithInstall(String install,
      ControlRoutePolicy.ConnectionAuthState auth, ControlRoutePolicy.BodyBindingState state) {
    return new ControlRoutePolicy.Destination(ControlAuth.Role.SERVER_AUTHORITY, install, BODY, auth, state);
  }

  private static ControlRoutePolicy.Origin clientOrigin(ControlRoutePolicy.ConnectionAuthState auth,
      ControlRoutePolicy.BodyBindingState state) {
    return peer(ControlAuth.Role.CLIENT_CONTROLLER, CLIENT, auth, state);
  }

  private static ControlRoutePolicy.Destination client(ControlRoutePolicy.ConnectionAuthState auth,
      ControlRoutePolicy.BodyBindingState state) {
    return new ControlRoutePolicy.Destination(ControlAuth.Role.CLIENT_CONTROLLER, CLIENT, BODY, auth, state);
  }

  private static ControlRoutePolicy.Destination clientWithInstall(String install,
      ControlRoutePolicy.ConnectionAuthState auth, ControlRoutePolicy.BodyBindingState state) {
    return new ControlRoutePolicy.Destination(ControlAuth.Role.CLIENT_CONTROLLER, install, BODY, auth, state);
  }

  private static ControlRoutePolicy.Destination boundServerDestination() {
    return serverDestination(ControlRoutePolicy.ConnectionAuthState.AUTHENTICATED, ControlRoutePolicy.BodyBindingState.BOUND);
  }

  private static ControlRoutePolicy.Origin boundServerOrigin() {
    return server(ControlRoutePolicy.ConnectionAuthState.AUTHENTICATED, ControlRoutePolicy.BodyBindingState.BOUND);
  }

  private static ControlRoutePolicy.Destination boundClientDestination() {
    return client(ControlRoutePolicy.ConnectionAuthState.AUTHENTICATED, ControlRoutePolicy.BodyBindingState.BOUND);
  }
}
