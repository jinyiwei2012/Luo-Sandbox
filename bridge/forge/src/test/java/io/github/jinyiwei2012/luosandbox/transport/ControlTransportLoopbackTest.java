package io.github.jinyiwei2012.luosandbox.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.jinyiwei2012.luosandbox.security.ControlAuth;
import java.net.URI;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** Real JDK WebSocket interoperability against the opt-in Python loopback harness. */
class ControlTransportLoopbackTest {
  @ParameterizedTest(name = "{0}")
  @EnumSource(value = ControlAuth.Role.class, names = {"SERVER_AUTHORITY", "CLIENT_CONTROLLER"})
  void authenticatesAndSustainsApplicationHeartbeats(ControlAuth.Role role) throws Exception {
    Assumptions.assumeTrue("1".equals(System.getenv("RUN_MINECRAFT_TRANSPORT_LOOPBACK")));
    URI endpoint = URI.create(required("MINECRAFT_LOOPBACK_URL"));
    byte[] key = role == ControlAuth.Role.SERVER_AUTHORITY ? range(0) : range(32);
    String install = role == ControlAuth.Role.SERVER_AUTHORITY ? "loopback-server" : "loopback-client";
    String keyId = "loopback-" + role.name().toLowerCase();
    ControlTransportConfig config = new ControlTransportConfig(true, endpoint, role, install,
        "loopback-hub", keyId, ControlTransportConfig.PROTOCOL, fill('m'), fill('c'), fill('b'), key);
    AtomicReference<ControlWebSocketTransport.StopReason> reason = new AtomicReference<>();
    try (ControlWebSocketTransport transport = new ControlWebSocketTransport(config, (state, value) -> reason.set(value))) {
      assertTrue(transport.start());
      awaitState(transport, ControlWebSocketTransport.State.AUTHENTICATED, Duration.ofSeconds(6));
      long heartbeatStarted = System.nanoTime();
      long heartbeatDeadline = heartbeatStarted + Duration.ofMillis(2_300).toNanos();
      while (System.nanoTime() < heartbeatDeadline
          && transport.state() == ControlWebSocketTransport.State.AUTHENTICATED) {
        assertTrue(transport.sendHeartbeat());
        Thread.sleep(300);
      }
      assertEquals(ControlWebSocketTransport.State.AUTHENTICATED, transport.state(),
          () -> "heartbeat interoperability stopped: " + transport.reason());
      assertEquals(ControlWebSocketTransport.StopReason.DISABLED, reason.get());
      long measuredMs = Duration.ofNanos(System.nanoTime() - heartbeatStarted).toMillis();
      assertTrue(measuredMs >= 2_300);
      System.out.println("LOOPBACK_METRIC {\"role\":\"" + role.name()
          + "\",\"requiredDurationMs\":2300,\"measuredDurationMs\":" + measuredMs + "}");
    }
  }

  private static void awaitState(ControlWebSocketTransport transport,
      ControlWebSocketTransport.State expected, Duration timeout) throws InterruptedException {
    long deadline = System.nanoTime() + timeout.toNanos();
    while (System.nanoTime() < deadline && transport.state() != expected
        && transport.state() != ControlWebSocketTransport.State.CLOSED) Thread.sleep(20);
    assertEquals(expected, transport.state(), () -> "transport stopped: " + transport.reason());
  }

  private static String required(String name) {
    String value = System.getenv(name);
    if (value == null || value.isBlank()) throw new IllegalStateException(name + " is required");
    return value;
  }

  private static byte[] range(int start) {
    byte[] result = new byte[32];
    IntStream.range(0, 32).forEach(index -> result[index] = (byte) (start + index));
    return result;
  }

  private static byte[] fill(char value) {
    byte[] result = new byte[32];
    java.util.Arrays.fill(result, (byte) value);
    return result;
  }
}
