package io.github.jinyiwei2012.luosandbox.security;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class BodyProofIngressTest {
  private static final String BOOT = "00000000-0000-4000-8000-000000000101";
  private static final String BODY = "00000000-0000-4000-8000-000000000103";
  private static final Instant NOW = Instant.parse("2026-10-23T00:00:00.000Z");

  @Test void wrongThreadAndClosedAreLazyAndWrongThreadRetainsChallenge() {
    Fixture f = new Fixture();
    Object connection = new Object();
    var challenge = f.ingress.begin(() -> connection, BOOT).orElseThrow();
    byte[] proof = proof(challenge);
    f.owner = false;
    int clocks = f.clockReads.get();
    AtomicInteger suppliers = new AtomicInteger();
    assertEquals(BodyProofIngress.Status.WRONG_THREAD,
        f.ingress.handleProof(() -> { suppliers.incrementAndGet(); return connection; }, proof).status());
    assertTrue(f.ingress.begin(() -> { suppliers.incrementAndGet(); return connection; }, BOOT).isEmpty());
    assertFalse(f.ingress.disconnect(() -> { suppliers.incrementAndGet(); return connection; }));
    assertFalse(f.ingress.close());
    assertEquals(0, suppliers.get());
    assertEquals(clocks, f.clockReads.get());
    f.owner = true;
    assertEquals(BodyProofIngress.Status.VERIFIED, f.ingress.handleProof(() -> connection, proof).status());
    assertTrue(f.ingress.close());
    assertEquals(BodyProofIngress.Status.CLOSED,
        f.ingress.handleProof(() -> { throw new AssertionError("closed supplier read"); }, proof).status());
    assertTrue(f.ingress.close());
  }

  @Test void malformedAttemptsDiscardExactlyWithoutClockReads() {
    for (int mode = 0; mode < 9; mode++) {
      Fixture f = new Fixture();
      Object actual = new Object();
      var challenge = f.ingress.begin(() -> actual, BOOT).orElseThrow();
      byte[] valid = proof(challenge);
      String text = new String(valid, StandardCharsets.UTF_8);
      byte[] invalid = switch (mode) {
        case 0 -> null;
        case 1 -> new byte[4097];
        case 2 -> BodyProofMessageCodec.encodeChallenge(challenge);
        case 3 -> new byte[] {(byte) 0xc3, 0x28};
        case 4 -> (text.substring(0, text.length() - 1) + ",\"kind\":\"BODY_PROOF\"}").getBytes(StandardCharsets.UTF_8);
        case 5 -> "{}".getBytes(StandardCharsets.UTF_8);
        case 6 -> text.replace(BODY, "body-1").getBytes(StandardCharsets.UTF_8);
        case 7 -> text.replace(challenge.gameNonce(), "bad-nonce").getBytes(StandardCharsets.UTF_8);
        default -> text.replace("BODY_PROOF", "OTHER").getBytes(StandardCharsets.UTF_8);
      };
      int reads = f.clockReads.get();
      assertEquals(BodyProofIngress.Status.REJECTED, f.ingress.handleProof(() -> actual, invalid).status());
      assertEquals(reads, f.clockReads.get());
      assertEquals(0, f.ingress.trackedCount());
      assertEquals(BodyProofIngress.Status.REJECTED, f.ingress.handleProof(() -> actual, valid).status());
    }
  }

  @Test void wrongConnectionCollisionDoesNotConsumeAndReplayWrongMacExpiryReject() {
    Fixture f = new Fixture();
    Object actual = new EqualConnection();
    Object wrong = new EqualConnection();
    var challenge = f.ingress.begin(() -> actual, BOOT).orElseThrow();
    byte[] valid = proof(challenge);
    assertEquals(BodyProofIngress.Status.REJECTED, f.ingress.handleProof(() -> wrong, valid).status());
    var verified = f.ingress.handleProof(() -> actual, valid);
    assertEquals(BodyProofIngress.Status.VERIFIED, verified.status());
    assertTrue(verified.proof().orElseThrow().matchesConnection(actual));
    assertFalse(verified.proof().orElseThrow().matchesConnection(wrong));
    assertEquals(BodyProofIngress.Status.REJECTED, f.ingress.handleProof(() -> actual, valid).status());
    challenge = f.ingress.begin(() -> actual, BOOT).orElseThrow();
    var wrongMac = new BodyProofMessageCodec.Proof(BOOT, challenge.gameConnectionId(), BODY,
        "client-1", challenge.gameNonce(), challenge.issuedAt(), challenge.expiresAt(), ControlAuth.b32(new byte[32]));
    assertEquals(BodyProofIngress.Status.REJECTED,
        f.ingress.handleProof(() -> actual, BodyProofMessageCodec.encodeProof(wrongMac)).status());
    challenge = f.ingress.begin(() -> actual, BOOT).orElseThrow();
    f.now = 5000;
    assertEquals(BodyProofIngress.Status.REJECTED, f.ingress.handleProof(() -> actual, proof(challenge)).status());
  }

  @Test void clockFailureClosesAndClearsBothOwnedConnections() {
    for (int mode = 0; mode < 5; mode++) {
      Fixture f = new Fixture();
      f.now = 100;
      Object first = new Object();
      Object second = new Object();
      var challenge = f.ingress.begin(() -> first, BOOT).orElseThrow();
      f.ingress.begin(() -> second, BOOT).orElseThrow();
      if (mode == 0) f.throwClock = true;
      if (mode == 1) f.wall = null;
      if (mode == 2) f.now = 99;
      if (mode == 3) f.wall = NOW.plusNanos(1);
      if (mode == 4) {
        f.now = Long.MAX_VALUE;
        assertTrue(f.ingress.begin(Object::new, BOOT).isEmpty());
      } else {
        assertEquals(BodyProofIngress.Status.CLOSED,
            f.ingress.handleProof(() -> first, proof(challenge)).status());
      }
      assertEquals(0, f.ingress.trackedCount());
      f.throwClock = false; f.wall = NOW; f.now = 100;
      assertTrue(f.ingress.begin(Object::new, BOOT).isEmpty());
      assertEquals(BodyProofIngress.Status.CLOSED,
          f.ingress.handleProof(() -> first, proof(challenge)).status());
    }
  }

  @Test void repeatedExpiredConnectionsDoNotAccumulateTrackingOrEvictLiveEntries() {
    Fixture f = new Fixture();
    for (int index = 0; index < 100; index++) {
      f.now = index * 5000L;
      assertTrue(f.ingress.begin(Object::new, BOOT).isPresent());
      assertEquals(1, f.ingress.trackedCount());
    }
    Object second = new Object();
    assertTrue(f.ingress.begin(() -> second, BOOT).isPresent());
    assertTrue(f.ingress.begin(Object::new, BOOT).isEmpty());
    assertEquals(2, f.ingress.trackedCount());
    assertTrue(f.ingress.disconnect(() -> second));
    assertFalse(f.ingress.disconnect(() -> second));
    assertEquals(1, f.ingress.trackedCount());
    assertTrue(f.ingress.close());
    assertEquals(0, f.ingress.trackedCount());
  }

  @Test void connectionFailureBadBootAndThrowingThreadGateAreContained() {
    Fixture f = new Fixture();
    assertTrue(f.ingress.begin(() -> null, BOOT).isEmpty());
    assertTrue(f.ingress.begin(() -> { throw new IllegalStateException(); }, BOOT).isEmpty());
    assertTrue(f.ingress.begin(Object::new, "bad-boot").isEmpty());
    assertTrue(f.ingress.begin(Object::new, BOOT).isPresent());
    f.throwThread = true;
    assertEquals(BodyProofIngress.Status.WRONG_THREAD,
        f.ingress.handleProof(() -> { throw new AssertionError(); }, null).status());
  }

  @Test void resultInvariantRedactionAndArchitectureAreNonAuthorizing() throws Exception {
    assertThrows(IllegalArgumentException.class,
        () -> new BodyProofIngress.ProofResult<>(BodyProofIngress.Status.VERIFIED, Optional.empty()));
    assertEquals("BodyProofIngress.ProofResult[REJECTED]",
        new BodyProofIngress.ProofResult<>(BodyProofIngress.Status.REJECTED, Optional.empty()).toString());
    String source = Files.readString(Path.of("src/main/java/io/github/jinyiwei2012/luosandbox/security/BodyProofIngress.java"));
    assertFalse(source.contains("import net.minecraft"));
    assertFalse(source.contains("import net.minecraftforge"));
    assertFalse(source.contains("import io.github.jinyiwei2012.luosandbox.server"));
    assertFalse(source.contains("import io.github.jinyiwei2012.luosandbox.inputcontrol"));
    assertTrue(java.util.Arrays.stream(BodyProofIngress.class.getDeclaredFields())
        .filter(field -> field.getType() == PendingBodyProof.class)
        .allMatch(field -> java.lang.reflect.Modifier.isPrivate(field.getModifiers())));
  }

  private static byte[] proof(PendingBodyProof.BodyChallenge challenge) {
    var transcript = new BodyProofAuth.Transcript("server-1", "client-1", BODY, BOOT,
        challenge.gameConnectionId(), ControlAuth.decodeB32(challenge.gameNonce()),
        Instant.parse(challenge.issuedAt()), Instant.parse(challenge.expiresAt()), new byte[32]);
    return BodyProofMessageCodec.encodeProof(new BodyProofMessageCodec.Proof(BOOT,
        challenge.gameConnectionId(), BODY, "client-1", challenge.gameNonce(), challenge.issuedAt(),
        challenge.expiresAt(), BodyProofAuth.proof(new byte[32], transcript)));
  }

  private static final class Fixture {
    boolean owner = true;
    boolean throwThread;
    boolean throwClock;
    long now;
    Instant wall = NOW;
    final AtomicInteger clockReads = new AtomicInteger();
    final BodyProofIngress<Object> ingress = new BodyProofIngress<>("server-1", "client-1", BODY,
        new byte[32], new byte[32],
        () -> { if (throwThread) throw new IllegalStateException(); return owner; },
        () -> { clockReads.incrementAndGet(); if (throwClock) throw new IllegalStateException(); return now; },
        () -> { clockReads.incrementAndGet(); return wall; });
  }

  private static final class EqualConnection {
    @Override public boolean equals(Object other) { return other instanceof EqualConnection; }
    @Override public int hashCode() { return 1; }
  }
}
