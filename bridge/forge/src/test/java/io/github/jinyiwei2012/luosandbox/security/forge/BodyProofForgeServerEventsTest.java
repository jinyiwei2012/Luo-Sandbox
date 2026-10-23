package io.github.jinyiwei2012.luosandbox.security.forge;

import static org.junit.jupiter.api.Assertions.*;

import io.github.jinyiwei2012.luosandbox.security.BodyProofIngress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import org.junit.jupiter.api.Test;

class BodyProofForgeServerEventsTest {
  @Test void matchingStopIsIdempotentAndStoppedCannotInventStopping() {
    Object server = new Object();
    AtomicInteger calls = new AtomicInteger();
    var core = new BodyProofForgeServerEvents.Core(server, () -> true,
        () -> { calls.incrementAndGet(); return BodyProofServerLifecycle.StopResult.CLOSED; },
        () -> { calls.incrementAndGet(); return BodyProofServerLifecycle.StopResult.CLOSED; });
    assertEquals(BodyProofForgeServerEvents.EventResult.STOPPING_NOT_OBSERVED, core.stopped(server));
    assertEquals(0, calls.get());
    assertEquals(BodyProofForgeServerEvents.EventResult.CLOSED, core.stopping(server));
    assertEquals(BodyProofForgeServerEvents.EventResult.CLOSED, core.stopping(server));
    assertEquals(BodyProofForgeServerEvents.EventResult.CLOSED, core.stopped(server));
    assertEquals(1, calls.get());
  }

  @Test void wrongServerAndThreadDoNotAccessCleanupOrAcceptedState() {
    Object server = new Object();
    AtomicInteger threadCalls = new AtomicInteger();
    AtomicInteger cleanupCalls = new AtomicInteger();
    var core = new BodyProofForgeServerEvents.Core(server,
        () -> { threadCalls.incrementAndGet(); return false; },
        () -> { cleanupCalls.incrementAndGet(); return BodyProofServerLifecycle.StopResult.CLOSED; },
        () -> { cleanupCalls.incrementAndGet(); return BodyProofServerLifecycle.StopResult.CLOSED; });
    assertEquals(BodyProofForgeServerEvents.EventResult.NOT_OWNER, core.stopping(null));
    assertEquals(BodyProofForgeServerEvents.EventResult.NOT_OWNER, core.stopped(new Object()));
    assertEquals(0, threadCalls.get());
    assertEquals(BodyProofForgeServerEvents.EventResult.WRONG_THREAD, core.stopping(server));
    assertEquals(BodyProofForgeServerEvents.EventResult.WRONG_THREAD, core.stopped(server));
    assertEquals(0, cleanupCalls.get());
    var throwing = new BodyProofForgeServerEvents.Core(server,
        () -> { throw new IllegalStateException(); },
        () -> { throw new AssertionError(); }, () -> { throw new AssertionError(); });
    assertEquals(BodyProofForgeServerEvents.EventResult.WRONG_THREAD, throwing.stopping(server));
  }

  @Test void actualLifecycleQuarantineRetainsOwnerAndRetriesUntilClosed() {
    Object server = new Object();
    boolean[] ingressThread = {true};
    var registration = new BodyProofChannelRegistration<Object, Object>(() -> true, value -> {});
    var lifecycle = new BodyProofServerLifecycle<Object, Object>(registration, () -> true);
    var ingress = ingress(ingressThread);
    var owner = lifecycle.start(server, ingress, value -> {}).orElseThrow();
    var core = new BodyProofForgeServerEvents.Core(server, () -> true,
        () -> lifecycle.onServerStopping(server, owner),
        () -> lifecycle.onServerStopped(server, owner));
    ingressThread[0] = false;
    assertEquals(BodyProofForgeServerEvents.EventResult.QUARANTINED, core.stopping(server));
    assertEquals(BodyProofForgeServerEvents.EventResult.QUARANTINED, core.stopped(server));
    assertEquals(BodyProofServerLifecycle.Stage.STOPPING_QUARANTINED, lifecycle.stage().orElseThrow());
    assertTrue(registration.install(server, ingress(new boolean[] {true}), value -> {}).isEmpty());
    ingressThread[0] = true;
    assertEquals(BodyProofForgeServerEvents.EventResult.CLOSED, core.stopped(server));
    assertEquals(BodyProofForgeServerEvents.EventResult.CLOSED, core.stopped(server));
    assertEquals(BodyProofServerLifecycle.Stage.CLOSED, lifecycle.stage().orElseThrow());
  }

  @Test void staleGenerationCannotCleanupNewOwnerAndRuntimeFailureQuarantines() {
    Object server = new Object();
    var registration = new BodyProofChannelRegistration<Object, Object>(() -> true, value -> {});
    var lifecycle = new BodyProofServerLifecycle<Object, Object>(registration, () -> true);
    var old = lifecycle.start(server, ingress(new boolean[] {true}), value -> {}).orElseThrow();
    lifecycle.onServerStopping(server, old);
    lifecycle.start(server, ingress(new boolean[] {true}), value -> {}).orElseThrow();
    var stale = new BodyProofForgeServerEvents.Core(server, () -> true,
        () -> lifecycle.onServerStopping(server, old),
        () -> lifecycle.onServerStopped(server, old));
    assertEquals(BodyProofForgeServerEvents.EventResult.NOT_OWNER, stale.stopping(server));
    assertEquals(BodyProofForgeServerEvents.EventResult.STOPPING_NOT_OBSERVED, stale.stopped(server));
    assertEquals(BodyProofServerLifecycle.Stage.ACTIVE, lifecycle.stage().orElseThrow());
    var throwing = new BodyProofForgeServerEvents.Core(server, () -> true,
        () -> { throw new IllegalStateException("cleanup unavailable"); },
        () -> BodyProofServerLifecycle.StopResult.CLOSED);
    assertEquals(BodyProofForgeServerEvents.EventResult.QUARANTINED, throwing.stopping(server));
    assertEquals(BodyProofForgeServerEvents.EventResult.CLOSED, throwing.stopped(server));
  }

  @Test void productionApiUsesRealEventsButHasNoAutomaticRegistrationOrPlayerLookup() throws Exception {
    assertEquals(BodyProofForgeServerEvents.EventResult.class,
        BodyProofForgeServerEvents.class.getMethod("onServerStopping", ServerStoppingEvent.class).getReturnType());
    assertEquals(BodyProofForgeServerEvents.EventResult.class,
        BodyProofForgeServerEvents.class.getMethod("onServerStopped", ServerStoppedEvent.class).getReturnType());
    String source = Files.readString(Path.of("src/main/java/io/github/jinyiwei2012/luosandbox/security/forge/BodyProofForgeServerEvents.java"));
    for (String forbidden : new String[] {"@SubscribeEvent", "EVENT_BUS", "registerForge(",
        "PlayerLoggedOutEvent", "getPlayer(", "getUUID(", "getSender("}) {
      assertFalse(source.contains(forbidden), forbidden);
    }
  }

  private static BodyProofIngress<Object> ingress(boolean[] thread) {
    return new BodyProofIngress<>("server-1", "client-1",
        "00000000-0000-4000-8000-000000000103", new byte[32], new byte[32],
        () -> thread[0], () -> 100, () -> Instant.parse("2026-10-23T00:00:00.000Z"));
  }
}
