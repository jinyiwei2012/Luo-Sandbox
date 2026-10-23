package io.github.jinyiwei2012.luosandbox.security.forge;

import static org.junit.jupiter.api.Assertions.*;

import io.github.jinyiwei2012.luosandbox.security.*;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class BodyProofChannelHandlerTest {
  private static final String BOOT = "00000000-0000-4000-8000-000000000101";
  private static final String BODY = "00000000-0000-4000-8000-000000000103";
  private static final Instant NOW = Instant.parse("2026-10-23T00:00:00.000Z");

  @Test void capturedGenerationCannotReachNewIngressAndWrongOwnerCannotRemove() {
    Fixture f = new Fixture();
    Object server = new Object();
    var ingress = f.ingress();
    BodyProofChannelHandler.Observer<Object> observer = proof -> f.observed++;
    var owner = f.handler.install(server, ingress, observer).orElseThrow();
    assertSame(owner, f.handler.install(server, ingress, observer).orElseThrow());
    assertTrue(f.handler.install(server, f.ingress(), observer).isEmpty());
    assertFalse(f.handler.uninstall(new Object(), owner));
    assertTrue(f.handler.uninstall(server, owner));
    var second = f.handler.install(server, f.ingress(), observer).orElseThrow();
    AtomicInteger contexts = new AtomicInteger();
    assertEquals(BodyProofChannelHandler.Decision.STALE_OWNER,
        f.handler.dispatch(server, owner, () -> { contexts.incrementAndGet(); return null; }, null));
    assertEquals(0, contexts.get());
    assertFalse(f.handler.uninstall(server, owner));
    assertTrue(f.handler.uninstall(server, second));
  }

  @Test void wrongThreadDoesNotReadContextOrCleanupAndCleanupFailureKeepsOwner() {
    Fixture f = new Fixture();
    Object server = new Object();
    var ingress = f.ingress();
    var owner = f.handler.install(server, ingress, proof -> f.observed++).orElseThrow();
    f.thread = false;
    assertEquals(BodyProofChannelHandler.Decision.WRONG_THREAD,
        f.handler.dispatch(server, owner, () -> { throw new AssertionError(); }, null));
    assertFalse(f.handler.disconnect(server, owner, () -> { throw new AssertionError(); }));
    assertFalse(f.handler.uninstall(server, owner));
    f.thread = true;
    f.ingressThread = false;
    assertFalse(f.handler.uninstall(server, owner));
    assertTrue(f.handler.install(server, f.ingress(), proof -> {}).isEmpty());
    f.ingressThread = true;
    assertTrue(f.handler.uninstall(server, owner));
  }

  @Test void verifiedProofIsObservedOnlyOnceAndReplayClosesExactConnection() {
    Fixture f = new Fixture();
    Object server = new Object();
    Object actual = new EqualConnection();
    Object wrong = new EqualConnection();
    var ingress = f.ingress();
    var challenge = ingress.begin(() -> actual, BOOT).orElseThrow();
    var owner = f.handler.install(server, ingress, proof -> {
      assertTrue(proof.matchesConnection(actual)); f.observed++;
    }).orElseThrow();
    var frame = proof(challenge);
    assertEquals(BodyProofChannelHandler.Decision.REJECTED, f.handler.dispatch(server, owner,
        () -> context(wrong), frame));
    assertSame(wrong, f.closed);
    assertEquals(BodyProofChannelHandler.Decision.VERIFIED, f.handler.dispatch(server, owner,
        () -> context(actual), frame));
    assertEquals(1, f.observed);
    assertEquals(BodyProofChannelHandler.Decision.REJECTED, f.handler.dispatch(server, owner,
        () -> context(actual), frame));
    assertSame(actual, f.closed);
    assertEquals(1, f.observed);
  }

  @Test void malformedDirectionWrongMacAndObserverFailureConsumeAndClose() {
    for (int mode = 0; mode < 5; mode++) {
      Fixture f = new Fixture();
      Object server = new Object();
      Object connection = new Object();
      var ingress = f.ingress();
      var challenge = ingress.begin(() -> connection, BOOT).orElseThrow();
      final int selected = mode;
      var owner = f.handler.install(server, ingress, value -> {
        f.observed++;
        if (selected == 4) throw new IllegalStateException("observer failed");
      }).orElseThrow();
      BodyProofNetworkFrame frame = mode == 0 ? BodyProofNetworkFrame.decode(null)
          : mode == 1 ? BodyProofNetworkFrame.raw(new byte[] {'{'}) : proof(challenge);
      if (mode == 3) {
        var invalid = new BodyProofMessageCodec.Proof(BOOT, challenge.gameConnectionId(), BODY,
            "client-1", challenge.gameNonce(), challenge.issuedAt(), challenge.expiresAt(),
            ControlAuth.b32(new byte[32]));
        frame = BodyProofNetworkFrame.raw(BodyProofMessageCodec.encodeProof(invalid));
      }
      var direction = mode == 2 ? BodyProofChannelHandler.Direction.OTHER
          : BodyProofChannelHandler.Direction.PLAY_TO_SERVER;
      assertEquals(BodyProofChannelHandler.Decision.REJECTED, f.handler.dispatch(server, owner,
          () -> new BodyProofChannelHandler.Context<>(direction, connection), frame));
      assertSame(connection, f.closed);
      assertEquals(BodyProofChannelHandler.Decision.REJECTED, f.handler.dispatch(server, owner,
          () -> context(connection), proof(challenge)));
      assertEquals(mode == 4 ? 1 : 0, f.observed);
    }
  }

  private static BodyProofChannelHandler.Context<Object> context(Object connection) {
    return new BodyProofChannelHandler.Context<>(BodyProofChannelHandler.Direction.PLAY_TO_SERVER, connection);
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
    boolean thread = true;
    boolean ingressThread = true;
    int observed;
    Object closed;
    final BodyProofChannelHandler<Object, Object> handler =
        new BodyProofChannelHandler<>(() -> thread, connection -> closed = connection);
    BodyProofIngress<Object> ingress() {
      return new BodyProofIngress<>("server-1", "client-1", BODY, new byte[32], new byte[32],
          () -> ingressThread, () -> 100, () -> NOW);
    }
  }

  private static final class EqualConnection {
    @Override public boolean equals(Object other) { return other instanceof EqualConnection; }
    @Override public int hashCode() { return 1; }
  }
}
