package io.github.jinyiwei2012.luosandbox.security;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

class PendingBodyProofTest {
  private static final Instant NOW = BodyProofAuthTest.NOW;

  private static PendingBodyProof<Object> owner() {
    return new PendingBodyProof<>("server-1", "client-1", BodyProofAuthTest.BODY,
        BodyProofAuthTest.hash(), BodyProofAuthTest.key(), BodyProofAuthTest::key,
        () -> BodyProofAuthTest.CONNECTION);
  }

  private static List<PendingBodyProof.Field> response(PendingBodyProof.BodyChallenge challenge) {
    BodyProofAuth.Transcript transcript = new BodyProofAuth.Transcript("server-1", "client-1",
        challenge.bodyId(), challenge.serverBootId(), challenge.gameConnectionId(),
        ControlAuth.decodeB32(challenge.gameNonce()), Instant.parse(challenge.issuedAt()),
        Instant.parse(challenge.expiresAt()), BodyProofAuthTest.hash());
    return new ArrayList<>(List.of(
        new PendingBodyProof.Field("serverBootId", challenge.serverBootId()),
        new PendingBodyProof.Field("gameConnectionId", challenge.gameConnectionId()),
        new PendingBodyProof.Field("bodyId", challenge.bodyId()),
        new PendingBodyProof.Field("clientInstallId", "client-1"),
        new PendingBodyProof.Field("gameNonce", challenge.gameNonce()),
        new PendingBodyProof.Field("issuedAt", challenge.issuedAt()),
        new PendingBodyProof.Field("expiresAt", challenge.expiresAt()),
        new PendingBodyProof.Field("proof", BodyProofAuth.proof(BodyProofAuthTest.key(), transcript))));
  }

  @Test void exactConnectionIdentityAndOneUseOpaqueResult() {
    PendingBodyProof<Object> owner = owner();
    Object actual = new EqualConnection();
    Object impostor = new EqualConnection();
    var challenge = owner.begin(actual, BodyProofAuthTest.BOOT, 100, NOW).orElseThrow();
    assertTrue(owner.consume(impostor, response(challenge), 101, NOW).isEmpty());
    var verified = owner.consume(actual, response(challenge), 102, NOW).orElseThrow();
    assertTrue(verified.matchesConnection(actual));
    assertFalse(verified.matchesConnection(impostor));
    assertTrue(owner.consume(actual, response(challenge), 103, NOW).isEmpty());
    byte[] hash = verified.bindingHash();
    hash[0] = 0;
    assertEquals(0x44, verified.bindingHash()[0]);
    assertEquals("VerifiedGameConnectionProof[redacted]", verified.toString());
  }

  @Test void provisionedBodyMustBeCanonicalNonzeroUuid() {
    assertEquals(BodyProofAuthTest.BODY,
        owner().begin(new Object(), BodyProofAuthTest.BOOT, 0, NOW).orElseThrow().bodyId());
    for (String invalid : new String[] {"body-1", "00000000-0000-4000-8000-00000000010A",
        "00000000-0000-0000-0000-000000000000"}) {
      assertThrows(IllegalArgumentException.class, () -> new PendingBodyProof<>(
          "server-1", "client-1", invalid, BodyProofAuthTest.hash(), BodyProofAuthTest.key()));
    }
  }

  @Test void capacityDuplicateExpiryAndDiscardAreBounded() {
    PendingBodyProof<Object> owner = owner();
    Object first = new Object();
    Object second = new Object();
    assertTrue(owner.begin(first, BodyProofAuthTest.BOOT, 0, NOW).isPresent());
    assertTrue(owner.begin(first, BodyProofAuthTest.BOOT, 1, NOW).isEmpty());
    assertTrue(owner.begin(second, BodyProofAuthTest.BOOT, 2, NOW).isPresent());
    assertTrue(owner.begin(new Object(), BodyProofAuthTest.BOOT, 3, NOW).isEmpty());
    assertEquals(2, owner.count(4, NOW));
    assertEquals(1, owner.count(5_000, NOW));
    assertTrue(owner.discard(second));
    assertFalse(owner.discard(second));
    assertEquals(0, owner.count(5_001, NOW));
  }

  @Test void malformedDuplicateAndInvalidMacConsumeBeforeValidation() {
    for (int mode = 0; mode < 4; mode++) {
      PendingBodyProof<Object> owner = owner();
      Object connection = new Object();
      var challenge = owner.begin(connection, BodyProofAuthTest.BOOT, 0, NOW).orElseThrow();
      List<PendingBodyProof.Field> fields = response(challenge);
      if (mode == 0) fields = null;
      if (mode == 1) fields.set(7, fields.get(0));
      if (mode == 2) fields.set(7, new PendingBodyProof.Field("proof", "A".repeat(43)));
      if (mode == 3) fields.set(2, new PendingBodyProof.Field("bodyId", "other-body"));
      assertTrue(owner.consume(connection, fields, 1, NOW).isEmpty());
      assertTrue(owner.consume(connection, response(challenge), 2, NOW).isEmpty());
    }
  }

  @Test void everyRepeatedFieldMutationConsumesAndCannotBeReplayed() {
    for (int index = 0; index < 8; index++) {
      PendingBodyProof<Object> owner = owner();
      Object connection = new Object();
      var challenge = owner.begin(connection, BodyProofAuthTest.BOOT, 0, NOW).orElseThrow();
      var fields = response(challenge);
      var field = fields.get(index);
      fields.set(index, new PendingBodyProof.Field(field.name(), field.value() + "x"));
      assertTrue(owner.consume(connection, fields, 1, NOW).isEmpty(), field.name());
      assertTrue(owner.consume(connection, response(challenge), 2, NOW).isEmpty());
    }
  }

  @Test void concurrentConsumeHasExactlyOneWinner() throws Exception {
    PendingBodyProof<Object> owner = owner();
    Object connection = new Object();
    var challenge = owner.begin(connection, BodyProofAuthTest.BOOT, 100, NOW).orElseThrow();
    var executor = Executors.newFixedThreadPool(2);
    try {
      var first = executor.submit(() -> owner.consume(connection, response(challenge), 101, NOW));
      var second = executor.submit(() -> owner.consume(connection, response(challenge), 101, NOW));
      assertEquals(1, (first.get().isPresent() ? 1 : 0) + (second.get().isPresent() ? 1 : 0));
    } finally {
      executor.shutdownNow();
    }
  }

  @Test void utcSkewAndExpiryBoundariesAreExact() {
    for (int skew : new int[] {1000, 1001, -4999, -5000}) {
      PendingBodyProof<Object> owner = owner();
      Object connection = new Object();
      var challenge = owner.begin(connection, BodyProofAuthTest.BOOT, 100, NOW).orElseThrow();
      boolean accepted = owner.consume(connection, response(challenge), 101,
          NOW.minusMillis(skew)).isPresent();
      assertEquals(skew == 1000 || skew == -4999, accepted, "skew " + skew);
    }
    PendingBodyProof<Object> owner = owner();
    Object connection = new Object();
    var challenge = owner.begin(connection, BodyProofAuthTest.BOOT, 100, NOW).orElseThrow();
    assertTrue(owner.consume(connection, response(challenge), 5100, NOW).isEmpty());
  }

  @Test void clockFailureClearsAllAndPermanentlyDisables() {
    PendingBodyProof<Object> rollback = owner();
    rollback.begin(new Object(), BodyProofAuthTest.BOOT, 100, NOW);
    assertEquals(0, rollback.count(99, NOW));
    assertTrue(rollback.disabled());
    assertTrue(rollback.begin(new Object(), BodyProofAuthTest.BOOT, 200, NOW).isEmpty());
    PendingBodyProof<Object> overflow = owner();
    assertTrue(overflow.begin(new Object(), BodyProofAuthTest.BOOT, Long.MAX_VALUE, NOW).isEmpty());
    assertTrue(overflow.disabled());
    PendingBodyProof<Object> invalidUtc = owner();
    invalidUtc.begin(new Object(), BodyProofAuthTest.BOOT, 0, NOW);
    assertEquals(0, invalidUtc.count(1, null));
    assertTrue(invalidUtc.disabled());
    PendingBodyProof<Object> utcOverflow = owner();
    assertTrue(utcOverflow.begin(new Object(), BodyProofAuthTest.BOOT, 0,
        Instant.parse("9999-12-31T23:59:59.999Z")).isEmpty());
    assertTrue(utcOverflow.disabled());
    PendingBodyProof<Object> negative = owner();
    assertTrue(negative.begin(new Object(), BodyProofAuthTest.BOOT, -1, NOW).isEmpty());
    assertTrue(negative.disabled());
  }

  @Test void proofResultCannotBeConstructedOrGrantWorldAuthority() throws Exception {
    for (var constructor : PendingBodyProof.VerifiedGameConnectionProof.class.getDeclaredConstructors()) {
      assertTrue(Modifier.isPrivate(constructor.getModifiers()));
    }
    for (String file : List.of("BodyProofAuth.java", "PendingBodyProof.java")) {
      String source = Files.readString(Path.of("src/main/java/io/github/jinyiwei2012/luosandbox/security", file));
      assertFalse(source.contains("import net.minecraft"));
      assertFalse(source.contains("import net.minecraftforge"));
      assertFalse(source.contains("import io.github.jinyiwei2012.luosandbox.server"));
      assertFalse(source.contains("import io.github.jinyiwei2012.luosandbox.inputcontrol"));
    }
  }

  private static final class EqualConnection {
    @Override public boolean equals(Object value) { return value instanceof EqualConnection; }
    @Override public int hashCode() { return 1; }
  }
}
