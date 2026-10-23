package io.github.jinyiwei2012.luosandbox.security.forge;

import static org.junit.jupiter.api.Assertions.*;

import io.github.jinyiwei2012.luosandbox.security.BodyProofIngress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import org.junit.jupiter.api.Test;

class BodyProofServerLifecycleTest {
  @Test void challengeTokensAreGenerationScopedAndCapacityBounded() {
    Fixture f = new Fixture();
    var first = f.lifecycle.start(f.server, f.ingress(), value -> {}).orElseThrow();
    Object connection = new Object();
    var issued = f.lifecycle.beginChallenge(f.server, first, () -> connection, BOOT).orElseThrow();
    assertTrue(f.lifecycle.beginChallenge(f.server, first, () -> connection, BOOT).isEmpty());
    assertTrue(f.lifecycle.beginChallenge(f.server, first, Object::new, BOOT).isPresent());
    assertTrue(f.lifecycle.beginChallenge(f.server, first, Object::new, BOOT).isEmpty());
    assertTrue(f.lifecycle.discardIssued(f.server, first, issued));
    assertFalse(f.lifecycle.discardIssued(f.server, first, issued));
    f.thread = false;
    assertTrue(f.lifecycle.beginChallenge(f.server, first, () -> { throw new AssertionError(); }, BOOT).isEmpty());
    f.thread = true;
    f.lifecycle.onServerStopping(f.server, first);
    var next = f.lifecycle.start(f.server, f.ingress(), value -> {}).orElseThrow();
    assertFalse(f.lifecycle.discardIssued(f.server, next, issued));
    assertTrue(f.lifecycle.beginChallenge(f.server, first, () -> { throw new AssertionError(); }, BOOT).isEmpty());
  }
  private static final String BOOT = "00000000-0000-4000-8000-000000000101";
  private static final String BODY = "00000000-0000-4000-8000-000000000103";

  @Test void explicitStartStopAndRestartFenceOldGenerationWithoutRegisteringAgain() {
    Fixture f = new Fixture();
    assertEquals(BodyProofServerLifecycle.Stage.NEW, f.lifecycle.stage().orElseThrow());
    assertEquals(0, f.registrations);
    var ingress = f.ingress();
    BodyProofChannelHandler.Observer<Object> observer = value -> {};
    var first = f.lifecycle.start(f.server, ingress, observer).orElseThrow();
    assertSame(first, f.lifecycle.start(f.server, ingress, observer).orElseThrow());
    assertTrue(f.lifecycle.start(f.server, f.ingress(), observer).isEmpty());
    assertTrue(f.lifecycle.start(new Object(), ingress, observer).isEmpty());
    assertEquals(BodyProofServerLifecycle.StopResult.CLOSED, f.lifecycle.onServerStopping(f.server, first));
    assertEquals(BodyProofServerLifecycle.StopResult.CLOSED, f.lifecycle.onServerStopped(f.server, first));
    assertEquals(BodyProofServerLifecycle.StopResult.CLOSED, f.lifecycle.onServerStopping(f.server, first));
    assertTrue(ingress.begin(Object::new, BOOT).isEmpty());
    var next = f.lifecycle.start(f.server, f.ingress(), observer).orElseThrow();
    assertNotSame(first, next);
    assertEquals(BodyProofServerLifecycle.StopResult.NOT_OWNER, f.lifecycle.onServerStopped(f.server, first));
    assertEquals(BodyProofServerLifecycle.Stage.ACTIVE, f.lifecycle.stage().orElseThrow());
    assertEquals(0, f.registrations);
    assertEquals(BodyProofServerLifecycle.StopResult.CLOSED, f.lifecycle.onServerStopped(f.server, next));
  }

  @Test void failedCloseRetainsLocalAndRegistrationOwnerUntilTrustedRetry() {
    Fixture f = new Fixture();
    f.registerFake();
    var ingress = f.ingress();
    var handle = f.lifecycle.start(f.server, ingress, value -> {}).orElseThrow();
    f.ingressThread = false;
    assertEquals(BodyProofServerLifecycle.StopResult.QUARANTINED, f.lifecycle.onServerStopping(f.server, handle));
    assertEquals(BodyProofServerLifecycle.Stage.STOPPING_QUARANTINED, f.lifecycle.stage().orElseThrow());
    assertTrue(f.lifecycle.start(f.server, f.ingress(), value -> {}).isEmpty());
    assertTrue(f.registration.install(f.server, f.ingress(), value -> {}).isEmpty());
    FakeContext context = new FakeContext();
    f.consumer.accept(BodyProofNetworkFrame.raw(new byte[] {'{'}), context);
    assertNotNull(context.work, "failed close must retain published registration route");
    assertEquals(BodyProofServerLifecycle.StopResult.QUARANTINED, f.lifecycle.onServerStopped(f.server, handle));
    f.ingressThread = true;
    assertEquals(BodyProofServerLifecycle.StopResult.CLOSED, f.lifecycle.onServerStopped(f.server, handle));
    assertEquals(BodyProofServerLifecycle.Stage.CLOSED, f.lifecycle.stage().orElseThrow());
  }

  @Test void wrongThreadAndWrongServerNeverReadConnectionSupplier() {
    Fixture f = new Fixture();
    var handle = f.lifecycle.start(f.server, f.ingress(), value -> {}).orElseThrow();
    AtomicInteger reads = new AtomicInteger();
    f.thread = false;
    assertFalse(f.lifecycle.disconnect(f.server, handle, () -> { reads.incrementAndGet(); return new Object(); }));
    assertEquals(BodyProofServerLifecycle.StopResult.WRONG_THREAD, f.lifecycle.onServerStopping(f.server, handle));
    assertEquals(BodyProofServerLifecycle.StopResult.WRONG_THREAD, f.lifecycle.onServerStopped(f.server, handle));
    assertTrue(f.lifecycle.stage().isEmpty());
    f.thread = true;
    assertFalse(f.lifecycle.disconnect(new Object(), handle, () -> { reads.incrementAndGet(); return new Object(); }));
    assertEquals(BodyProofServerLifecycle.StopResult.NOT_OWNER, f.lifecycle.onServerStopped(new Object(), handle));
    assertEquals(0, reads.get());
    assertEquals(BodyProofServerLifecycle.Stage.ACTIVE, f.lifecycle.stage().orElseThrow());
  }

  @Test void disconnectUsesExactCapturedConnectionNotEqualsOrPlayerLookup() {
    Fixture f = new Fixture();
    var ingress = f.ingress();
    var handle = f.lifecycle.start(f.server, ingress, value -> {}).orElseThrow();
    Object actual = new EqualConnection();
    Object collision = new EqualConnection();
    ingress.begin(() -> actual, BOOT).orElseThrow();
    assertFalse(f.lifecycle.disconnect(f.server, handle, () -> collision));
    assertTrue(f.lifecycle.disconnect(f.server, handle, () -> actual));
    assertFalse(f.lifecycle.disconnect(f.server, handle, () -> actual));
    assertTrue(f.lifecycle.disconnect(f.server, handle, () -> { throw new IllegalStateException(); }) == false);
  }

  @Test void lifecycleContainsNoAutomaticRegistrationOrWorldAuthority() throws Exception {
    String source = Files.readString(Path.of("src/main/java/io/github/jinyiwei2012/luosandbox/security/forge/BodyProofServerLifecycle.java"));
    assertFalse(source.contains("@SubscribeEvent"));
    assertFalse(source.contains("registerForge("));
    assertFalse(source.contains("EVENT_BUS"));
    assertFalse(source.contains("import net.minecraft"));
    assertFalse(source.contains("import net.minecraftforge"));
    assertFalse(source.contains("import io.github.jinyiwei2012.luosandbox.server"));
    assertEquals("BodyProofServerLifecycle[redacted]", new Fixture().lifecycle.toString());
  }

  private static final class Fixture {
    final Object server = new Object();
    boolean thread = true;
    boolean ingressThread = true;
    int registrations;
    BiConsumer<BodyProofNetworkFrame, BodyProofChannelRegistration.NetworkContext<Object>> consumer;
    final BodyProofChannelRegistration<Object, Object> registration =
        new BodyProofChannelRegistration<>(() -> thread, connection -> {});
    final BodyProofServerLifecycle<Object, Object> lifecycle =
        new BodyProofServerLifecycle<>(registration, () -> thread);
    BodyProofIngress<Object> ingress() {
      return new BodyProofIngress<>("server-1", "client-1", BODY, new byte[32], new byte[32],
          () -> ingressThread, () -> 100, () -> Instant.parse("2026-10-23T00:00:00.000Z"));
    }
    void registerFake() {
      registration.register(callback -> { registrations++; consumer = callback; return new Object(); });
    }
  }

  private static final class FakeContext implements BodyProofChannelRegistration.NetworkContext<Object> {
    Runnable work;
    @Override public Object connection() { return new Object(); }
    @Override public BodyProofChannelHandler.Direction direction() { return BodyProofChannelHandler.Direction.PLAY_TO_SERVER; }
    @Override public CompletableFuture<Void> enqueue(Runnable work) { this.work = work; return new CompletableFuture<>(); }
    @Override public void handled() {}
  }

  private static final class EqualConnection {
    @Override public boolean equals(Object other) { return other instanceof EqualConnection; }
    @Override public int hashCode() { return 1; }
  }
}
