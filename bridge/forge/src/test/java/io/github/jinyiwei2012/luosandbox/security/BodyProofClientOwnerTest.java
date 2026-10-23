package io.github.jinyiwei2012.luosandbox.security;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class BodyProofClientOwnerTest {
  private static final String BODY = "00000000-0000-4000-8000-000000000103";
  private static final String BOOT = "00000000-0000-4000-8000-000000000101";
  private static final Instant NOW = Instant.parse("2026-10-23T00:00:00.000Z");

  private static BodyProofClientOwner.Provisioning provisioning() {
    return new BodyProofClientOwner.Provisioning("server-1", "client-1", BODY, new byte[32], new byte[32]);
  }
  private static byte[] challenge() {
    return BodyProofMessageCodec.encodeChallenge(new PendingBodyProof.BodyChallenge(BOOT,
        "00000000-0000-4000-8000-000000000102", BODY, ControlAuth.b32(new byte[32]),
        ControlAuth.formatUtc(NOW), ControlAuth.formatUtc(NOW.plusMillis(5000))));
  }

  @Test void provisioningIsCanonicalDefensiveAndRedacted() {
    byte[] key = new byte[32];
    byte[] hash = new byte[32];
    var p = new BodyProofClientOwner.Provisioning("server-1", "client-1", BODY, hash, key);
    key[0] = 1; hash[0] = 2; p.bodyKey()[0] = 3; p.bindingHash()[0] = 4;
    assertEquals(0, p.bodyKey()[0]); assertEquals(0, p.bindingHash()[0]);
    assertEquals("BodyClientProvisioning[redacted]", p.toString());
    assertThrows(IllegalArgumentException.class, () -> new BodyProofClientOwner.Provisioning(
        "server-1", "client-1", "body-1", hash, key));
  }

  @Test void wrongThreadTouchesNothingAndConstructorDoesNotSampleClocks() {
    AtomicInteger reads = new AtomicInteger();
    var owner = new BodyProofClientOwner<>(new Object(), provisioning(), () -> false,
        () -> { reads.incrementAndGet(); return 0; },
        () -> { reads.incrementAndGet(); return NOW; },
        (connection, proof) -> { throw new AssertionError(); }, connection -> { throw new AssertionError(); });
    assertEquals(0, reads.get());
    assertEquals(BodyProofClientOwner.Result.WRONG_THREAD,
        owner.handleChallenge(() -> { throw new AssertionError(); }, challenge()));
    assertFalse(owner.close()); assertEquals(0, reads.get());
  }

  @Test void actualSubmittedProofVerifiesWithServerAndReplayCannotSignAgain() {
    Object connection = new Object();
    var pending = new PendingBodyProof<Object>("server-1", "client-1", BODY, new byte[32], new byte[32]);
    var challenge = pending.begin(connection, BOOT, 0, NOW).orElseThrow();
    AtomicInteger sends = new AtomicInteger();
    AtomicInteger closes = new AtomicInteger();
    var owner = new BodyProofClientOwner<>(connection, provisioning(), () -> true, () -> 0, () -> NOW,
        (actual, raw) -> {
          assertSame(connection, actual);
          var decoded = BodyProofMessageCodec.decodeProof(raw);
          assertEquals(BODY, decoded.bodyId());
          assertTrue(pending.consume(actual, decoded.fields(), 1, NOW).isPresent());
          sends.incrementAndGet();
        }, actual -> { assertSame(connection, actual); closes.incrementAndGet(); });
    byte[] raw = BodyProofMessageCodec.encodeChallenge(challenge);
    assertEquals(BodyProofClientOwner.Result.PROOF_SUBMITTED, owner.handleChallenge(() -> connection, raw));
    assertEquals(BodyProofClientOwner.Result.REJECTED, owner.handleChallenge(() -> connection, raw));
    assertEquals(BodyProofClientOwner.Result.CLOSED, owner.handleChallenge(() -> connection, challenge()));
    assertEquals(1, sends.get()); assertEquals(1, closes.get());
    assertTrue(owner.close()); assertEquals(1, closes.get());
  }

  @Test void wrongReferenceMalformedAndBodyMismatchCloseOnlyOwnedConnection() {
    for (int mode = 0; mode < 3; mode++) {
      Object connection = new EqualConnection();
      AtomicInteger closes = new AtomicInteger();
      var owner = new BodyProofClientOwner<>(connection, provisioning(), () -> true, () -> 0, () -> NOW,
          (actual, raw) -> { throw new AssertionError(); },
          actual -> { assertSame(connection, actual); closes.incrementAndGet(); });
      byte[] raw = mode == 1 ? null : challenge();
      if (mode == 2) raw = new String(raw, java.nio.charset.StandardCharsets.UTF_8)
          .replace(BODY, "00000000-0000-4000-8000-000000000104")
          .getBytes(java.nio.charset.StandardCharsets.UTF_8);
      Object actual = mode == 0 ? new EqualConnection() : connection;
      assertEquals(BodyProofClientOwner.Result.REJECTED, owner.handleChallenge(() -> actual, raw));
      assertEquals(1, closes.get());
    }
  }

  @Test void utcSkewExpiryAndClockFailuresAreClosed() {
    for (int skew : new int[] {-1000, -1001, 4999, 5000}) {
      Object connection = new Object();
      AtomicInteger sends = new AtomicInteger();
      var owner = new BodyProofClientOwner<>(connection, provisioning(), () -> true, () -> 0,
          () -> NOW.plusMillis(skew), (actual, raw) -> sends.incrementAndGet(), actual -> {});
      boolean valid = skew == -1000 || skew == 4999;
      assertEquals(valid ? BodyProofClientOwner.Result.PROOF_SUBMITTED : BodyProofClientOwner.Result.REJECTED,
          owner.handleChallenge(() -> connection, challenge()), "skew=" + skew);
      assertEquals(valid ? 1 : 0, sends.get());
    }
    for (int failure = 0; failure < 5; failure++) {
      final int mode = failure;
      AtomicInteger ticks = new AtomicInteger();
      Object connection = new Object();
      LongSupplier clock = () -> {
        if (mode == 0) throw new IllegalStateException();
        if (mode == 1) return ticks.getAndIncrement() == 0 ? 100 : 99;
        if (mode == 4) return ticks.getAndIncrement() == 0 ? 0 : 5000;
        return 0;
      };
      Supplier<Instant> utc = () -> mode == 2 ? null : mode == 3 ? NOW.plusNanos(1) : NOW;
      var owner = new BodyProofClientOwner<>(connection, provisioning(), () -> true, clock, utc,
          (actual, raw) -> { throw new AssertionError(); }, actual -> {});
      assertEquals(BodyProofClientOwner.Result.REJECTED, owner.handleChallenge(() -> connection, challenge()));
      assertEquals(BodyProofClientOwner.Result.CLOSED, owner.handleChallenge(() -> connection, challenge()));
    }
  }

  @Test void reentrantSupplierOrClockCloseCannotSendOrReviveKeyOwner() {
    for (int mode = 0; mode < 3; mode++) {
      final int selected = mode;
      var holder = new java.util.concurrent.atomic.AtomicReference<BodyProofClientOwner<Object>>();
      Object connection = new Object();
      AtomicInteger closes = new AtomicInteger();
      var owner = new BodyProofClientOwner<>(connection, provisioning(), () -> true,
          () -> { if (selected == 1) holder.get().close(); return 0; },
          () -> { if (selected == 2) holder.get().close(); return NOW; },
          (actual, raw) -> { throw new AssertionError(); }, actual -> closes.incrementAndGet());
      holder.set(owner);
      assertEquals(BodyProofClientOwner.Result.REJECTED, owner.handleChallenge(() -> {
        if (selected == 0) owner.close(); return connection;
      }, challenge()));
      assertEquals(1, closes.get());
      assertEquals(BodyProofClientOwner.Result.CLOSED, owner.handleChallenge(() -> connection, challenge()));
    }
  }

  @Test void senderReentrancyExceptionAndInterruptionNeverRetryOrResurrect() {
    for (int mode = 0; mode < 4; mode++) {
      final int selected = mode;
      Object connection = new Object();
      var holder = new java.util.concurrent.atomic.AtomicReference<BodyProofClientOwner<Object>>();
      AtomicInteger sends = new AtomicInteger();
      AtomicInteger closes = new AtomicInteger();
      var owner = new BodyProofClientOwner<>(connection, provisioning(), () -> true, () -> 0, () -> NOW,
          (actual, raw) -> {
            sends.incrementAndGet();
            if (selected == 0) holder.get().close();
            if (selected == 1) holder.get().handleChallenge(() -> connection, challenge());
            if (selected == 2) throw new Exception("unknown");
            if (selected == 3) throw new InterruptedException();
          }, actual -> { closes.incrementAndGet(); throw new IllegalStateException("close failed"); });
      holder.set(owner);
      try {
        assertEquals(BodyProofClientOwner.Result.SEND_UNKNOWN, owner.handleChallenge(() -> connection, challenge()));
        assertEquals(1, sends.get()); assertEquals(1, closes.get());
        assertEquals(selected == 3, Thread.currentThread().isInterrupted());
        assertEquals(BodyProofClientOwner.Result.CLOSED, owner.handleChallenge(() -> connection, challenge()));
      } finally { Thread.interrupted(); }
    }
  }

  @Test void architectureRemainsPureAndResultCarriesNoProof() throws Exception {
    String source = Files.readString(Path.of("src/main/java/io/github/jinyiwei2012/luosandbox/security/BodyProofClientOwner.java"));
    for (String forbidden : new String[] {"import net.minecraft", "import net.minecraftforge",
        "import io.github.jinyiwei2012.luosandbox.server", "import io.github.jinyiwei2012.luosandbox.inputcontrol"}) {
      assertFalse(source.contains(forbidden));
    }
    assertTrue(source.contains("Arrays.fill(key, (byte) 0)"));
    assertTrue(BodyProofClientOwner.Result.class.isEnum());
  }

  private static final class EqualConnection {
    @Override public boolean equals(Object other) { return other instanceof EqualConnection; }
    @Override public int hashCode() { return 1; }
  }
}
