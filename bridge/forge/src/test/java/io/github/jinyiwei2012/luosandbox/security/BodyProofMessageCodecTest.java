package io.github.jinyiwei2012.luosandbox.security;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.core.JsonFactory;
import io.github.jinyiwei2012.luosandbox.wire.StrictJson;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class BodyProofMessageCodecTest {
  private static final String BOOT = "00000000-0000-4000-8000-000000000101";
  private static final String CONNECTION = "00000000-0000-4000-8000-000000000102";
  private static final String BODY = "00000000-0000-4000-8000-000000000103";
  private static final String NONCE = ControlAuth.b32(new byte[32]);
  private static final String ISSUED = "2026-10-23T00:00:00.000Z";
  private static final String EXPIRES = "2026-10-23T00:00:05.000Z";

  private static PendingBodyProof.BodyChallenge challenge() {
    return new PendingBodyProof.BodyChallenge(BOOT, CONNECTION, BODY, NONCE, ISSUED, EXPIRES);
  }

  private static BodyProofMessageCodec.Proof proof() {
    return new BodyProofMessageCodec.Proof(BOOT, CONNECTION, BODY, "client-1", NONCE,
        ISSUED, EXPIRES, NONCE);
  }

  @Test void directionalRoundTripsAndExactFieldSets() throws Exception {
    byte[] challenge = BodyProofMessageCodec.encodeChallenge(challenge());
    byte[] proof = BodyProofMessageCodec.encodeProof(proof());
    assertEquals(challenge(), BodyProofMessageCodec.decodeChallenge(challenge));
    assertEquals(proof(), BodyProofMessageCodec.decodeProof(proof));
    assertEquals(8, StrictJson.decodeObject(challenge).size());
    assertEquals(10, StrictJson.decodeObject(proof).size());
    assertThrows(BodyProofMessageCodec.CodecException.class, () -> BodyProofMessageCodec.decodeProof(challenge));
    assertThrows(BodyProofMessageCodec.CodecException.class, () -> BodyProofMessageCodec.decodeChallenge(proof));
    var reversed = new LinkedHashMap<String, Object>();
    StrictJson.decodeObject(proof).entrySet().stream().sorted(Map.Entry.<String, Object>comparingByKey().reversed())
        .forEach(entry -> reversed.put(entry.getKey(), entry.getValue()));
    assertEquals(proof(), BodyProofMessageCodec.decodeProof(json(reversed)));
  }

  @Test void everyMissingExtraAndWrongTypeFieldIsRejected() throws Exception {
    for (boolean isProof : new boolean[] {false, true}) {
      Map<String, Object> valid = StrictJson.decodeObject(isProof
          ? BodyProofMessageCodec.encodeProof(proof()) : BodyProofMessageCodec.encodeChallenge(challenge()));
      for (String name : valid.keySet()) {
        Map<String, Object> missing = new LinkedHashMap<>(valid);
        missing.remove(name);
        reject(json(missing), isProof);
        for (Object wrong : Arrays.asList(null, 1, true, Map.of("nested", "value"), java.util.List.of("value"))) {
          Map<String, Object> mutated = new LinkedHashMap<>(valid);
          mutated.put(name, wrong);
          reject(json(mutated), isProof);
        }
      }
      Map<String, Object> extra = new LinkedHashMap<>(valid);
      extra.put("bindingHash", NONCE);
      reject(json(extra), isProof);
      for (String name : new String[] {"security", "kind"}) {
        Map<String, Object> wrong = new LinkedHashMap<>(valid);
        wrong.put(name, "WRONG");
        reject(json(wrong), isProof);
      }
    }
  }

  @Test void strictLexicalAndByteBoundaries() throws Exception {
    byte[] valid = BodyProofMessageCodec.encodeProof(proof());
    String text = new String(valid, StandardCharsets.UTF_8);
    reject((text.substring(0, text.length() - 1) + ",\"kind\":\"BODY_PROOF\"}").getBytes(StandardCharsets.UTF_8), true);
    reject(new byte[] {(byte) 0xc3, 0x28}, true);
    byte[] bom = new byte[valid.length + 3];
    bom[0] = (byte) 0xef; bom[1] = (byte) 0xbb; bom[2] = (byte) 0xbf;
    System.arraycopy(valid, 0, bom, 3, valid.length);
    reject(bom, true);
    reject(text.replace("client-1", "\\uD800").getBytes(StandardCharsets.UTF_8), true);
    reject((text + "{}").getBytes(StandardCharsets.UTF_8), true);
    reject("[]".getBytes(StandardCharsets.UTF_8), true);
    reject("{\"x\":{\"x\":{\"x\":{\"x\":{\"x\":1}}}}}".getBytes(StandardCharsets.UTF_8), true);
    byte[] boundary = Arrays.copyOf(valid, 4096);
    Arrays.fill(boundary, valid.length, boundary.length, (byte) ' ');
    assertEquals(proof(), BodyProofMessageCodec.decodeProof(boundary));
    byte[] tooLarge = Arrays.copyOf(boundary, 4097);
    tooLarge[4096] = ' ';
    reject(tooLarge, true);
  }

  @Test void fieldValidatorsAndEncodeValidationAreStrict() throws Exception {
    Map<String, Object> valid = StrictJson.decodeObject(BodyProofMessageCodec.encodeProof(proof()));
    String[][] mutations = {
      {"bodyId", "body-1"}, {"serverBootId", "00000000-0000-0000-0000-000000000000"},
      {"gameConnectionId", "00000000-0000-4000-8000-00000000010A"},
      {"gameNonce", NONCE + "="}, {"proof", "A".repeat(42) + "B"},
      {"clientInstallId", "bad id"}, {"issuedAt", "2026-10-23T00:00:00Z"},
      {"issuedAt", "2026-10-23T00:00:00.0000Z"}, {"expiresAt", "2026-10-23T00:00:05.001Z"},
      {"expiresAt", "2026-10-23T00:00:05.000+00:00"}, {"issuedAt", "0000-01-01T00:00:00.000Z"}
    };
    for (String[] mutation : mutations) {
      Map<String, Object> fields = new LinkedHashMap<>(valid);
      fields.put(mutation[0], mutation[1]);
      reject(json(fields), true);
    }
    assertThrows(BodyProofMessageCodec.CodecException.class,
        () -> BodyProofMessageCodec.encodeChallenge(new PendingBodyProof.BodyChallenge(
            BOOT, CONNECTION, "body-1", NONCE, ISSUED, EXPIRES)));
    assertThrows(BodyProofMessageCodec.CodecException.class, () -> BodyProofMessageCodec.encodeProof(null));
  }

  @Test void syntaxConversionDoesNotAuthenticateAndRealPendingOwnsConsumption() {
    PendingBodyProof<Object> owner = new PendingBodyProof<>("server-1", "client-1", BODY,
        new byte[32], new byte[32], () -> new byte[32], () -> CONNECTION);
    Object actual = new Object();
    var challenge = owner.begin(actual, BOOT, 0, java.time.Instant.parse(ISSUED)).orElseThrow();
    BodyProofAuth.Transcript transcript = new BodyProofAuth.Transcript("server-1", "client-1", BODY,
        BOOT, CONNECTION, new byte[32], java.time.Instant.parse(ISSUED),
        java.time.Instant.parse(EXPIRES), new byte[32]);
    var response = new BodyProofMessageCodec.Proof(BOOT, CONNECTION, BODY, "client-1", NONCE,
        ISSUED, EXPIRES, BodyProofAuth.proof(new byte[32], transcript));
    var decoded = BodyProofMessageCodec.decodeProof(BodyProofMessageCodec.encodeProof(response));
    assertTrue(owner.consume(new Object(), decoded.fields(), 1, java.time.Instant.parse(ISSUED)).isEmpty());
    assertTrue(owner.consume(actual, decoded.fields(), 2, java.time.Instant.parse(ISSUED)).isPresent());
    assertTrue(owner.consume(actual, decoded.fields(), 3, java.time.Instant.parse(ISSUED)).isEmpty());

    Object invalidAttempt = new Object();
    owner.begin(invalidAttempt, BOOT, 4, java.time.Instant.parse(ISSUED)).orElseThrow();
    reject(new byte[] {'['}, true);
    assertEquals(1, owner.count(5, java.time.Instant.parse(ISSUED)), "decode failure owns no pending state");
    assertTrue(owner.consume(invalidAttempt, null, 6, java.time.Instant.parse(ISSUED)).isEmpty());
    assertEquals(0, owner.count(7, java.time.Instant.parse(ISSUED)));

    Object wrongMac = new Object();
    owner.begin(wrongMac, BOOT, 8, java.time.Instant.parse(ISSUED)).orElseThrow();
    assertTrue(owner.consume(wrongMac, proof().fields(), 9, java.time.Instant.parse(ISSUED)).isEmpty());
    Object stale = new Object();
    owner.begin(stale, BOOT, 10, java.time.Instant.parse(ISSUED)).orElseThrow();
    assertTrue(owner.consume(stale, decoded.fields(), 5010, java.time.Instant.parse(ISSUED)).isEmpty());
  }

  @Test void architectureRemainsSyntaxOnly() throws Exception {
    String source = Files.readString(Path.of("src/main/java/io/github/jinyiwei2012/luosandbox/security/BodyProofMessageCodec.java"));
    assertFalse(source.contains("import net.minecraft"));
    assertFalse(source.contains("import net.minecraftforge"));
    assertFalse(source.contains("import io.github.jinyiwei2012.luosandbox.server"));
    assertFalse(source.contains("VerifiedGameConnectionProof"));
    assertFalse(source.contains("BodyProofAuth.verify"));
  }

  private static void reject(byte[] bytes, boolean proof) {
    var error = assertThrows(BodyProofMessageCodec.CodecException.class, () -> {
      if (proof) BodyProofMessageCodec.decodeProof(bytes);
      else BodyProofMessageCodec.decodeChallenge(bytes);
    });
    assertEquals("Invalid BODY message", error.getMessage());
  }

  private static byte[] json(Map<String, Object> fields) throws Exception {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    try (var generator = new JsonFactory().createGenerator(output)) {
      generator.writeStartObject();
      for (var entry : fields.entrySet()) {
        generator.writeFieldName(entry.getKey());
        Object value = entry.getValue();
        if (value == null) generator.writeNull();
        else if (value instanceof String text) generator.writeString(text);
        else if (value instanceof Number number) generator.writeNumber(number.intValue());
        else if (value instanceof Boolean bool) generator.writeBoolean(bool);
        else if (value instanceof Map) { generator.writeStartObject(); generator.writeStringField("nested", "value"); generator.writeEndObject(); }
        else { generator.writeStartArray(); generator.writeString("value"); generator.writeEndArray(); }
      }
      generator.writeEndObject();
    }
    return output.toByteArray();
  }
}
