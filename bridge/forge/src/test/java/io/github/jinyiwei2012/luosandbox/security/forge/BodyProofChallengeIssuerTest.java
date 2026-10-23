package io.github.jinyiwei2012.luosandbox.security.forge;

import static org.junit.jupiter.api.Assertions.*;

import io.github.jinyiwei2012.luosandbox.security.*;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class BodyProofChallengeIssuerTest {
  @Test void connectionSupplierRestartRejectsBeforeSenderAndDoesNotCreateNewPending() {
    Fixture f = new Fixture();
    Object connection = new Object();
    var oldIngress = f.ingress;
    var oldOwner = f.owner;
    var issuer = new BodyProofChallengeIssuer<>(f.lifecycle,
        (actual, bytes) -> { throw new AssertionError("rejected begin must not send"); },
        actual -> { throw new AssertionError("rejected begin must not close"); });
    assertEquals(BodyProofChallengeIssuer.Status.REJECTED,
        issuer.issueAndSend(f.server, oldOwner, () -> {
          f.lifecycle.onServerStopping(f.server, oldOwner);
          f.ingress = f.newIngress();
          f.owner = f.lifecycle.start(f.server, f.ingress, value -> {}).orElseThrow();
          return connection;
        }, BOOT).status());
    assertTrue(oldIngress.begin(() -> connection, BOOT).isEmpty());
    assertFalse(f.ingress.disconnect(() -> connection));
    assertTrue(f.lifecycle.beginChallenge(f.server, f.owner, () -> connection, BOOT).isPresent());
  }

  @Test void clockRestartRejectsOldTokenAndLeavesBothIngressesUncontaminated() {
    for (boolean fromUtc : new boolean[] {false, true}) {
      Fixture f = new Fixture();
      f.lifecycle.onServerStopping(f.server, f.owner);
      Object connection = new Object();
      boolean[] fired = {false};
      Runnable restart = () -> {
        if (fired[0]) return;
        fired[0] = true;
        f.lifecycle.onServerStopping(f.server, f.owner);
        f.ingress = f.newIngress();
        f.owner = f.lifecycle.start(f.server, f.ingress, value -> {}).orElseThrow();
      };
      var original = new BodyProofIngress<Object>("server-1", "client-1", BODY,
          new byte[32], new byte[32], () -> true,
          () -> { if (!fromUtc) restart.run(); return 100; },
          () -> { if (fromUtc) restart.run(); return Instant.parse("2026-10-23T00:00:00.000Z"); });
      f.owner = f.lifecycle.start(f.server, original, value -> {}).orElseThrow();
      var oldOwner = f.owner;
      var issuer = new BodyProofChallengeIssuer<>(f.lifecycle,
          (actual, bytes) -> { throw new AssertionError("clock restart must not send"); },
          actual -> { throw new AssertionError("clock restart must not close"); });
      assertEquals(BodyProofChallengeIssuer.Status.REJECTED,
          issuer.issueAndSend(f.server, oldOwner, () -> connection, BOOT).status());
      assertTrue(fired[0]);
      assertFalse(original.disconnect(() -> connection));
      assertTrue(original.begin(() -> connection, BOOT).isEmpty());
      assertFalse(f.ingress.disconnect(() -> connection));
      var replacement = f.lifecycle.beginChallenge(f.server, f.owner, () -> connection, BOOT).orElseThrow();
      assertFalse(f.lifecycle.discardIssued(f.server, oldOwner, replacement));
      assertTrue(f.lifecycle.discardIssued(f.server, f.owner, replacement));
    }
  }
  private static final String BOOT = "00000000-0000-4000-8000-000000000101";
  private static final String BODY = "00000000-0000-4000-8000-000000000103";

  @Test void sentMeansExactLocalSubmissionAndRetainsPending() {
    Fixture f = new Fixture();
    Object connection = new Object();
    var issuer = new BodyProofChallengeIssuer<>(f.lifecycle, (actual, bytes) -> {
      assertSame(connection, actual);
      assertEquals(BODY, BodyProofMessageCodec.decodeChallenge(bytes).bodyId());
      f.sends++;
    }, actual -> f.closed = actual);
    var result = issuer.issueAndSend(f.server, f.owner, () -> connection, BOOT);
    assertEquals(BodyProofChallengeIssuer.Status.SENT, result.status());
    assertEquals(1, f.sends);
    assertTrue(f.lifecycle.beginChallenge(f.server, f.owner, () -> connection, BOOT).isEmpty());
    assertNull(f.closed);
  }

  @Test void senderFailureDiscardsOnlyIssuedAttemptAndClosesExactConnection() {
    Fixture f = new Fixture();
    Object connection = new Object();
    var issuer = new BodyProofChallengeIssuer<>(f.lifecycle, (actual, bytes) -> {
      f.sends++; throw new Exception("submission unknown");
    }, actual -> { f.closed = actual; throw new IllegalStateException("close failed"); });
    assertEquals(BodyProofChallengeIssuer.Status.SEND_UNKNOWN,
        issuer.issueAndSend(f.server, f.owner, () -> connection, BOOT).status());
    assertSame(connection, f.closed);
    assertEquals(1, f.sends);
    assertTrue(f.lifecycle.beginChallenge(f.server, f.owner, () -> connection, BOOT).isPresent());
  }

  @Test void reentrantConsumeAThenCreateBThrowDoesNotDiscardB() {
    Fixture f = new Fixture();
    Object connection = new Object();
    var issuer = new BodyProofChallengeIssuer<>(f.lifecycle, (actual, bytes) -> {
      f.sends++;
      var a = BodyProofMessageCodec.decodeChallenge(bytes);
      assertEquals(BodyProofIngress.Status.VERIFIED,
          f.ingress.handleProof(() -> actual, response(a)).status());
      f.lifecycle.beginChallenge(f.server, f.owner, () -> actual, BOOT).orElseThrow();
      throw new Exception("failed after reentry");
    }, actual -> f.closed = actual);
    assertEquals(BodyProofChallengeIssuer.Status.SEND_UNKNOWN,
        issuer.issueAndSend(f.server, f.owner, () -> connection, BOOT).status());
    assertTrue(f.lifecycle.beginChallenge(f.server, f.owner, () -> connection, BOOT).isEmpty(), "B remains live");
    assertTrue(f.lifecycle.disconnect(f.server, f.owner, () -> connection));
    assertEquals(1, f.sends);
  }

  @Test void generationRestartDuringSenderCannotCleanupNewPendingAndWrongThreadIsLazy() {
    Fixture f = new Fixture();
    Object connection = new Object();
    var issuer = new BodyProofChallengeIssuer<>(f.lifecycle, (actual, bytes) -> {
      f.lifecycle.onServerStopping(f.server, f.owner);
      f.ingress = f.newIngress();
      f.owner = f.lifecycle.start(f.server, f.ingress, value -> {}).orElseThrow();
      f.lifecycle.beginChallenge(f.server, f.owner, () -> connection, BOOT).orElseThrow();
      throw new Exception("old generation send unknown");
    }, actual -> f.closed = actual);
    var old = f.owner;
    assertEquals(BodyProofChallengeIssuer.Status.SEND_UNKNOWN,
        issuer.issueAndSend(f.server, old, () -> connection, BOOT).status());
    assertTrue(f.lifecycle.beginChallenge(f.server, f.owner, () -> connection, BOOT).isEmpty());
    f.thread = false;
    assertEquals(BodyProofChallengeIssuer.Status.REJECTED,
        issuer.issueAndSend(f.server, f.owner, () -> { throw new AssertionError(); }, BOOT).status());
  }

  @Test void interruptionIsPreservedAndResultIsRedacted() {
    Fixture f = new Fixture();
    var issuer = new BodyProofChallengeIssuer<>(f.lifecycle,
        (actual, bytes) -> { throw new InterruptedException(); }, actual -> {});
    try {
      var result = issuer.issueAndSend(f.server, f.owner, Object::new, BOOT);
      assertEquals(BodyProofChallengeIssuer.Status.SEND_UNKNOWN, result.status());
      assertTrue(Thread.currentThread().isInterrupted());
      assertEquals("BodyChallengeSend[SEND_UNKNOWN]", result.toString());
    } finally { Thread.interrupted(); }
  }

  private static byte[] response(PendingBodyProof.BodyChallenge challenge) {
    var transcript = new BodyProofAuth.Transcript("server-1", "client-1", BODY, BOOT,
        challenge.gameConnectionId(), ControlAuth.decodeB32(challenge.gameNonce()),
        Instant.parse(challenge.issuedAt()), Instant.parse(challenge.expiresAt()), new byte[32]);
    return BodyProofMessageCodec.encodeProof(new BodyProofMessageCodec.Proof(BOOT,
        challenge.gameConnectionId(), BODY, "client-1", challenge.gameNonce(), challenge.issuedAt(),
        challenge.expiresAt(), BodyProofAuth.proof(new byte[32], transcript)));
  }

  private static final class Fixture {
    final Object server = new Object();
    boolean thread = true;
    int sends;
    Object closed;
    final BodyProofChannelRegistration<Object, Object> registration =
        new BodyProofChannelRegistration<>(() -> thread, actual -> {});
    final BodyProofServerLifecycle<Object, Object> lifecycle =
        new BodyProofServerLifecycle<>(registration, () -> thread);
    BodyProofIngress<Object> ingress = newIngress();
    BodyProofServerLifecycle.OwnerHandle<Object, Object> owner =
        lifecycle.start(server, ingress, value -> {}).orElseThrow();
    BodyProofIngress<Object> newIngress() {
      return new BodyProofIngress<>("server-1", "client-1", BODY, new byte[32], new byte[32],
          () -> thread, () -> 100, () -> Instant.parse("2026-10-23T00:00:00.000Z"));
    }
  }
}
