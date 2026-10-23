package io.github.jinyiwei2012.luosandbox.security.forge;

import static org.junit.jupiter.api.Assertions.*;

import io.github.jinyiwei2012.luosandbox.security.*;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import org.junit.jupiter.api.Test;

class BodyProofChannelRegistrationTest {
  private static final String BOOT = "00000000-0000-4000-8000-000000000101";
  private static final String BODY = "00000000-0000-4000-8000-000000000103";
  private static final Instant NOW = Instant.parse("2026-10-23T00:00:00.000Z");

  @Test void explicitRegistrationIsStrictAndIdempotent() {
    Fixture f = new Fixture();
    assertNull(f.consumer);
    Object first = f.owner.register(f.registrar);
    assertSame(first, f.owner.register(f.registrar));
    assertEquals(1, f.registrations);
    assertThrows(IllegalStateException.class, () -> f.owner.register(consumer -> new Object()));
    assertEquals("luosandbox:body_proof_v1", BodyProofChannelRegistration.CHANNEL);
    assertEquals(0, BodyProofChannelRegistration.MESSAGE_ID);
    assertTrue(BodyProofChannelRegistration.acceptsVersion("1"));
    for (String bad : new String[] {"ABSENT", "ACCEPTVANILLA", "2", "", "01"}) {
      assertFalse(BodyProofChannelRegistration.acceptsVersion(bad));
    }
    assertFalse(BodyProofChannelRegistration.acceptsVersion(null));
  }

  @Test void networkPhaseCapturesGenerationWithoutIngressAndOldQueueCannotReachNewOwner() {
    Fixture f = new Fixture();
    f.owner.register(f.registrar);
    var ingress = f.ingress();
    var handle = f.owner.install(f.server, ingress, value -> f.observed++).orElseThrow();
    Object connection = new Object();
    var challenge = ingress.begin(() -> connection, BOOT).orElseThrow();
    FakeContext context = new FakeContext(connection);
    int clocks = f.clocks;
    f.consumer.accept(proof(challenge), context);
    assertEquals(clocks, f.clocks);
    assertEquals(0, f.observed);
    assertTrue(context.handled);
    assertNotNull(context.work);
    assertTrue(f.owner.uninstall(f.server, handle));
    var fresh = f.ingress();
    f.owner.install(f.server, fresh, value -> f.observed++).orElseThrow();
    fresh.begin(() -> connection, BOOT).orElseThrow();
    clocks = f.clocks;
    context.work.run();
    assertEquals(clocks, f.clocks);
    assertEquals(0, f.observed);
    assertSame(connection, f.closed);
    assertTrue(fresh.disconnect(() -> connection), "stale queued frame must not consume new pending");
  }

  @Test void malformedConsumesOnQueueAndVerifiedObserverRunsOnce() {
    Fixture f = new Fixture();
    f.owner.register(f.registrar);
    var ingress = f.ingress();
    f.owner.install(f.server, ingress, value -> f.observed++).orElseThrow();
    Object connection = new Object();
    var challenge = ingress.begin(() -> connection, BOOT).orElseThrow();
    FakeContext malformed = new FakeContext(connection);
    f.consumer.accept(BodyProofNetworkFrame.decode(null), malformed);
    int clocks = f.clocks;
    malformed.work.run();
    assertEquals(clocks, f.clocks);
    FakeContext replay = new FakeContext(connection);
    f.consumer.accept(proof(challenge), replay);
    replay.work.run();
    assertEquals(0, f.observed);
    var next = ingress.begin(() -> connection, BOOT).orElseThrow();
    FakeContext valid = new FakeContext(connection);
    f.consumer.accept(proof(next), valid);
    valid.work.run();
    assertEquals(1, f.observed);
    valid.work.run();
    assertEquals(1, f.observed);
  }

  @Test void missingOwnerEnqueueFailureWrongThreadAndDirectionCloseExactConnection() {
    Fixture f = new Fixture();
    f.owner.register(f.registrar);
    Object connection = new Object();
    FakeContext absent = new FakeContext(connection);
    f.consumer.accept(BodyProofNetworkFrame.raw(new byte[] {'{'}), absent);
    assertSame(connection, f.closed);
    assertTrue(absent.handled);
    var ingress = f.ingress();
    var handle = f.owner.install(f.server, ingress, value -> f.observed++).orElseThrow();
    FakeContext failed = new FakeContext(connection);
    failed.failEnqueue = true;
    f.consumer.accept(BodyProofNetworkFrame.raw(new byte[] {'{'}), failed);
    assertTrue(failed.handled);
    FakeContext queued = new FakeContext(connection);
    f.consumer.accept(BodyProofNetworkFrame.raw(new byte[] {'{'}), queued);
    f.thread = false;
    int clocks = f.clocks;
    queued.work.run();
    assertEquals(clocks, f.clocks);
    assertFalse(f.owner.uninstall(f.server, handle));
    f.thread = true;
    FakeContext wrongDirection = new FakeContext(connection);
    wrongDirection.direction = BodyProofChannelHandler.Direction.OTHER;
    f.consumer.accept(BodyProofNetworkFrame.raw(new byte[] {'{'}), wrongDirection);
    wrongDirection.work.run();
    assertSame(connection, f.closed);
    assertEquals(0, f.observed);
  }

  @Test void failedIngressCleanupRetainsPublishedOwnerAndBlocksReplacement() {
    Fixture f = new Fixture();
    f.owner.register(f.registrar);
    var ingress = f.ingress();
    var handle = f.owner.install(f.server, ingress, value -> {}).orElseThrow();
    f.ingressThread = false;
    assertFalse(f.owner.uninstall(f.server, handle));
    assertTrue(f.owner.install(f.server, f.ingress(), value -> {}).isEmpty());
    FakeContext context = new FakeContext(new Object());
    f.consumer.accept(BodyProofNetworkFrame.raw(new byte[] {'{'}), context);
    assertNotNull(context.work, "failed cleanup must not withdraw route");
    f.ingressThread = true;
    assertTrue(f.owner.uninstall(f.server, handle));
  }

  private static BodyProofNetworkFrame proof(PendingBodyProof.BodyChallenge challenge) {
    var transcript = new BodyProofAuth.Transcript("server-1", "client-1", BODY, BOOT,
        challenge.gameConnectionId(), ControlAuth.decodeB32(challenge.gameNonce()),
        Instant.parse(challenge.issuedAt()), Instant.parse(challenge.expiresAt()), new byte[32]);
    return BodyProofNetworkFrame.raw(BodyProofMessageCodec.encodeProof(new BodyProofMessageCodec.Proof(
        BOOT, challenge.gameConnectionId(), BODY, "client-1", challenge.gameNonce(),
        challenge.issuedAt(), challenge.expiresAt(), BodyProofAuth.proof(new byte[32], transcript))));
  }

  private static final class Fixture {
    final Object server = new Object();
    boolean thread = true;
    boolean ingressThread = true;
    int observed;
    int clocks;
    int registrations;
    Object closed;
    BiConsumer<BodyProofNetworkFrame, BodyProofChannelRegistration.NetworkContext<Object>> consumer;
    final BodyProofChannelRegistration<Object, Object> owner =
        new BodyProofChannelRegistration<>(() -> thread, connection -> closed = connection);
    final BodyProofChannelRegistration.Registrar<Object> registrar = callback -> {
      registrations++; consumer = callback; return new Object();
    };
    BodyProofIngress<Object> ingress() {
      return new BodyProofIngress<>("server-1", "client-1", BODY, new byte[32], new byte[32],
          () -> ingressThread, () -> { clocks++; return 100; }, () -> { clocks++; return NOW; });
    }
  }

  private static final class FakeContext implements BodyProofChannelRegistration.NetworkContext<Object> {
    final Object connection;
    BodyProofChannelHandler.Direction direction = BodyProofChannelHandler.Direction.PLAY_TO_SERVER;
    Runnable work;
    boolean handled;
    boolean failEnqueue;
    FakeContext(Object connection) { this.connection = connection; }
    @Override public Object connection() { return connection; }
    @Override public BodyProofChannelHandler.Direction direction() { return direction; }
    @Override public CompletableFuture<Void> enqueue(Runnable work) {
      if (failEnqueue) throw new IllegalStateException("enqueue unavailable");
      this.work = work; return new CompletableFuture<>();
    }
    @Override public void handled() { handled = true; }
  }
}
