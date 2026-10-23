package io.github.jinyiwei2012.luosandbox.security;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.core.JsonFactory;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

class BodyProofAuthTest {
  static final Instant NOW = Instant.parse("2026-10-23T00:00:00.000Z");
  static final String BOOT = "00000000-0000-0000-0000-000000000001";
  static final String CONNECTION = "00000000-0000-0000-0000-000000000002";
  static final String BODY = "00000000-0000-4000-8000-000000000103";

  static byte[] key() {
    byte[] value = new byte[32];
    for (int index = 0; index < value.length; index++) value[index] = (byte) index;
    return value;
  }

  static byte[] hash() {
    byte[] value = new byte[32];
    Arrays.fill(value, (byte) 0x44);
    return value;
  }

  static BodyProofAuth.Transcript vector() {
    return new BodyProofAuth.Transcript("server-1", "client-1", BODY, BOOT,
        CONNECTION, key(), NOW, NOW.plusMillis(5_000), hash());
  }

  @Test void elevenFieldKnownAnswerAndOptInActualJavaExport() throws Exception {
    BodyProofAuth.Transcript value = vector();
    byte[] transcript = BodyProofAuth.transcript(value);
    assertEquals(320, transcript.length);
    ByteBuffer buffer = ByteBuffer.wrap(transcript);
    int fields = 0;
    while (buffer.hasRemaining()) {
      int length = buffer.getInt();
      assertTrue(length >= 0 && length <= buffer.remaining());
      buffer.position(buffer.position() + length);
      fields++;
    }
    assertEquals(11, fields);
    String hmac = HexFormat.of().formatHex(BodyProofAuth.hmac(key(), value));
    assertEquals("42ef1d451e3f6a65f3d1134f65002a12f73cca811fc12b5822b3e122c0d9fafe", hmac);
    assertEquals("Qu8dRR4_amXz0RNPZQAqEvc8yoEfwStYIrPhIsDZ-v4", BodyProofAuth.proof(key(), value));
    assertTrue(BodyProofAuth.verify(key(), value, BodyProofAuth.proof(key(), value)));
    String output = System.getenv("LUOSANDBOX_BODY_VECTOR_OUTPUT");
    if (output != null && !output.isBlank()) {
      Path path = Path.of(output).toAbsolutePath().normalize();
      Path approved = Path.of("..", "..", ".gradle-user-home").toAbsolutePath().normalize();
      assertTrue(path.startsWith(approved), "vector export must remain in ignored Gradle home");
      Files.createDirectories(path.getParent());
      byte[] nonce = key();
      byte[] binding = key();
      for (int index = 0; index < 32; index++) {
        nonce[index] += 32;
        binding[index] += 64;
      }
      BodyProofAuth.Transcript exported = new BodyProofAuth.Transcript(
          "server-test-1", "client-test-1", BODY,
          "00000000-0000-4000-8000-000000000101",
          "00000000-0000-4000-8000-000000000102", nonce, NOW, NOW.plusMillis(5_000), binding);
      try (var json = new JsonFactory().createGenerator(Files.newOutputStream(path))) {
        json.writeStartObject();
        json.writeStringField("schema", "body-proof-v1");
        json.writeObjectFieldStart("inputs");
        json.writeStringField("domain", BodyProofAuth.DOMAIN);
        json.writeStringField("label", BodyProofAuth.LABEL);
        json.writeStringField("serverInstallId", exported.serverInstallId());
        json.writeStringField("clientInstallId", exported.clientInstallId());
        json.writeStringField("bodyId", exported.bodyId());
        json.writeStringField("serverBootId", exported.serverBootId());
        json.writeStringField("gameConnectionId", exported.gameConnectionId());
        json.writeStringField("gameNonceB32", ControlAuth.b32(nonce));
        json.writeStringField("issuedAt", ControlAuth.formatUtc(exported.issuedAt()));
        json.writeStringField("expiresAt", ControlAuth.formatUtc(exported.expiresAt()));
        json.writeStringField("bindingHashHex", HexFormat.of().formatHex(binding));
        json.writeStringField("keyHex", HexFormat.of().formatHex(key()));
        json.writeEndObject();
        json.writeStringField("transcriptHex", HexFormat.of().formatHex(BodyProofAuth.transcript(exported)));
        json.writeStringField("hmacHex", HexFormat.of().formatHex(BodyProofAuth.hmac(key(), exported)));
        json.writeStringField("proof", BodyProofAuth.proof(key(), exported));
        json.writeEndObject();
      }
    }
  }

  @Test void wrongKeyMutationAndNoncanonicalProofAreRejected() {
    String proof = BodyProofAuth.proof(key(), vector());
    byte[] wrong = key();
    wrong[0] ^= 1;
    assertFalse(BodyProofAuth.verify(wrong, vector(), proof));
    BodyProofAuth.Transcript mutated = new BodyProofAuth.Transcript("server-2", "client-1",
        BODY, BOOT, CONNECTION, key(), NOW, NOW.plusMillis(5_000), hash());
    assertFalse(BodyProofAuth.verify(key(), mutated, proof));
    assertFalse(BodyProofAuth.verify(key(), vector(), proof + "="));
    assertFalse(BodyProofAuth.verify(key(), vector(), null));
    assertFalse(BodyProofAuth.verify(key(), vector(), "A".repeat(42) + "B"));
  }

  @Test void arraysAreDefensiveAndValidatorsAreStrict() {
    byte[] nonce = key();
    byte[] hash = hash();
    BodyProofAuth.Transcript value = new BodyProofAuth.Transcript("server-1", "client-1",
        BODY, BOOT, CONNECTION, nonce, NOW, NOW.plusMillis(5_000), hash);
    nonce[0] = 99;
    hash[0] = 99;
    assertEquals(0, value.gameNonce()[0]);
    assertEquals(0x44, value.bindingHash()[0]);
    value.gameNonce()[0] = 42;
    assertEquals(0, value.gameNonce()[0]);
    assertEquals("BodyProofAuth.Transcript[redacted]", value.toString());
    assertThrows(IllegalArgumentException.class, () -> new BodyProofAuth.Transcript("非ASCII",
        "client-1", BODY, BOOT, CONNECTION, key(), NOW, NOW.plusMillis(5_000), hash()));
    assertThrows(IllegalArgumentException.class, () -> new BodyProofAuth.Transcript("server-1",
        "client-1", BODY, BOOT, CONNECTION, key(), NOW.plusNanos(1), NOW, hash()));
  }

  @Test void bodyIdentityRequiresCanonicalNonzeroUuidAndIsMacBound() {
    assertEquals(BODY, vector().bodyId());
    for (String invalid : new String[] {"body-1", "00000000-0000-4000-8000-00000000010A",
        "00000000-0000-0000-0000-000000000000"}) {
      assertThrows(IllegalArgumentException.class, () -> new BodyProofAuth.Transcript(
          "server-1", "client-1", invalid, BOOT, CONNECTION, key(), NOW,
          NOW.plusMillis(5_000), hash()));
    }
    BodyProofAuth.Transcript otherBody = new BodyProofAuth.Transcript("server-1", "client-1",
        "00000000-0000-4000-8000-000000000104", BOOT, CONNECTION, key(), NOW,
        NOW.plusMillis(5_000), hash());
    assertFalse(BodyProofAuth.verify(key(), otherBody, BodyProofAuth.proof(key(), vector())));
  }
}
