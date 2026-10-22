package io.github.jinyiwei2012.luosandbox.transport;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.jinyiwei2012.luosandbox.security.ControlAuth;
import java.net.URI;
import org.junit.jupiter.api.Test;

class ControlTransportConfigTest {
  private static final byte[] HASH = new byte[32];

  @Test
  void disabledTransportDoesNotStartOrConnect() {
    ControlTransportConfig config = config(false, URI.create("ws://127.0.0.1:8765/control"));
    try (ControlWebSocketTransport transport = new ControlWebSocketTransport(config, (state, reason) -> {})) {
      assertFalse(transport.start());
      assertFalse(transport.sendHeartbeat());
    }
  }

  @Test
  void rejectsNonLiteralLoopbackAndCredentialBearingEndpoints() {
    assertThrows(IllegalArgumentException.class, () -> config(false, URI.create("ws://localhost:8765")));
    assertThrows(IllegalArgumentException.class, () -> config(false, URI.create("wss://127.0.0.1:8765")));
    assertThrows(IllegalArgumentException.class, () -> config(false, URI.create("ws://key@127.0.0.1:8765")));
    assertThrows(IllegalArgumentException.class, () -> config(false, URI.create("ws://127.0.0.1:8765?a=b")));
  }

  private static ControlTransportConfig config(boolean enabled, URI endpoint) {
    return new ControlTransportConfig(enabled, endpoint, ControlAuth.Role.CLIENT_CONTROLLER,
        "client-1", "hub-1", "key-1", ControlTransportConfig.PROTOCOL, HASH, HASH, HASH, HASH);
  }
}
