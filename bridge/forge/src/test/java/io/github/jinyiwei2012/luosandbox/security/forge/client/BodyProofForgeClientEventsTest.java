package io.github.jinyiwei2012.luosandbox.security.forge.client;

import static org.junit.jupiter.api.Assertions.*;

import io.github.jinyiwei2012.luosandbox.security.BodyProofClientOwner;
import io.github.jinyiwei2012.luosandbox.security.forge.BodyProofChannelRegistration;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import org.junit.jupiter.api.Test;

class BodyProofForgeClientEventsTest {
  private static final String BODY = "00000000-0000-4000-8000-000000000103";
  private static final Instant NOW = Instant.parse("2026-10-23T00:00:00.000Z");

  @Test
  void nullAndEqualButDifferentConnectionsNeverReachThreadOrCleanup() {
    var connection = new EqualConnection();
    AtomicInteger threadReads = new AtomicInteger();
    AtomicInteger cleanupCalls = new AtomicInteger();
    var core = new BodyProofForgeClientEvents.Core(
        connection,
        () -> {
          threadReads.incrementAndGet();
          return true;
        },
        () -> {
          cleanupCalls.incrementAndGet();
          return true;
        });

    assertFalse(core.loggingOut(null));
    assertFalse(core.loggingOut(new EqualConnection()));
    assertEquals(0, threadReads.get());
    assertEquals(0, cleanupCalls.get());
  }

  @Test
  void exactLogoutClosesActualOwnerAndRepeatedEventCannotCloseReplacement() {
    var fixture = new Fixture();
    Object connection = new Object();
    var first = fixture.install(connection);
    var firstOwner = fixture.lastOwner;
    var old = new BodyProofForgeClientEvents.Core(
        connection, () -> fixture.thread, () -> fixture.registration.uninstallClient(first));

    assertTrue(old.loggingOut(connection));
    assertEquals(1, fixture.closes.get());
    assertEquals(
        BodyProofClientOwner.Result.CLOSED,
        firstOwner.handleChallenge(() -> connection, new byte[] {'{'}));

    var replacement = fixture.install(connection);
    assertFalse(old.loggingOut(connection));
    assertEquals(1, fixture.closes.get());
    assertTrue(fixture.registration.uninstallClient(replacement));
    assertEquals(2, fixture.closes.get());
  }

  @Test
  void wrongThreadAndOwnerCloseGateFailureRetainReservationAndBlockReplacement() {
    var fixture = new Fixture();
    Object connection = new Object();
    var handle = fixture.install(connection);
    var core = new BodyProofForgeClientEvents.Core(
        connection, () -> fixture.thread, () -> fixture.registration.uninstallClient(handle));

    fixture.thread = false;
    assertFalse(core.loggingOut(connection));
    assertTrue(fixture.registration.installClient(new Object(), () -> true, ignored -> fail()).isEmpty());
    assertEquals(0, fixture.closes.get());

    fixture.thread = true;
    fixture.ownerThread = false;
    assertFalse(core.loggingOut(connection));
    assertTrue(fixture.registration.installClient(new Object(), () -> true, ignored -> fail()).isEmpty());
    assertEquals(0, fixture.closes.get());

    fixture.ownerThread = true;
    assertTrue(core.loggingOut(connection));
    assertEquals(1, fixture.closes.get());
  }

  @Test
  void staleHandleIsIsolatedWhenSameConnectionReferenceIsReused() {
    var fixture = new Fixture();
    Object connection = new Object();
    var oldHandle = fixture.install(connection);
    assertTrue(fixture.registration.uninstallClient(oldHandle));
    var newHandle = fixture.install(connection);
    var stale = new BodyProofForgeClientEvents.Core(
        connection, () -> true, () -> fixture.registration.uninstallClient(oldHandle));

    assertFalse(stale.loggingOut(connection));
    assertEquals(1, fixture.closes.get());
    assertTrue(fixture.registration.uninstallClient(newHandle));
    assertEquals(2, fixture.closes.get());
  }

  @Test
  void productionApiUsesRealLogoutEventWithoutAutomaticRegistrationOrCommonReferences()
      throws Exception {
    assertEquals(
        boolean.class,
        BodyProofForgeClientEvents.class
            .getMethod("onLoggingOut", ClientPlayerNetworkEvent.LoggingOut.class)
            .getReturnType());
    String source = Files.readString(Path.of(
        "src/main/java/io/github/jinyiwei2012/luosandbox/security/forge/client/BodyProofForgeClientEvents.java"));
    for (String forbidden : new String[] {
        "@SubscribeEvent", "EVENT_BUS", "Minecraft.getInstance", "getPlayer(", "getUUID(",
        "getName(", "enqueue", "registerForge("
    }) {
      assertFalse(source.contains(forbidden), forbidden);
    }
    Path common = Path.of("src/main/java/io/github/jinyiwei2012/luosandbox");
    try (var files = Files.walk(common)) {
      assertTrue(files.filter(path -> path.toString().endsWith(".java"))
          .filter(path -> !path.toString().replace('\\', '/').contains("/security/forge/client/"))
          .noneMatch(path -> contains(path, "BodyProofForgeClientEvents")));
    }
  }

  private static boolean contains(Path path, String text) {
    try {
      return Files.readString(path).contains(text);
    } catch (java.io.IOException error) {
      throw new java.io.UncheckedIOException(error);
    }
  }

  private static final class Fixture {
    boolean thread = true;
    boolean ownerThread = true;
    final AtomicInteger closes = new AtomicInteger();
    BodyProofClientOwner<Object> lastOwner;
    final BodyProofChannelRegistration<Object, Object> registration =
        new BodyProofChannelRegistration<>(() -> true, ignored -> {});

    Fixture() {
      registration.bindSenders((connection, raw) -> {}, (connection, raw) -> {});
    }

    BodyProofChannelRegistration.ClientHandle<Object> install(Object connection) {
      return registration.installClient(connection, () -> thread, sender -> {
        lastOwner = new BodyProofClientOwner<>(
            connection,
            new BodyProofClientOwner.Provisioning(
                "server-1", "client-1", BODY, new byte[32], new byte[32]),
            () -> ownerThread,
            () -> 100,
            () -> NOW,
            sender,
            ignored -> closes.incrementAndGet());
        return lastOwner;
      }).orElseThrow();
    }
  }

  private static final class EqualConnection {
    @Override
    public boolean equals(Object other) {
      return other instanceof EqualConnection;
    }

    @Override
    public int hashCode() {
      return 1;
    }
  }
}
