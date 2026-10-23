package io.github.jinyiwei2012.luosandbox.security;

import com.fasterxml.jackson.core.JsonFactory;
import io.github.jinyiwei2012.luosandbox.wire.StrictJson;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Directional BODY-only JSON syntax codec, not authentication or pending ownership.
 *
 * <p>Future transport must consume/discard a bounded invalid proof attempt against the actual
 * connection even when decode fails. Decoding first and abandoning failure does not consume the
 * pending challenge. No handler, connection lookup, MAC verification or authority is installed here.
 */
public final class BodyProofMessageCodec {
  public static final int MAX_BYTES = 4096;
  private static final Set<String> CHALLENGE_KEYS = Set.of("security", "kind", "serverBootId",
      "gameConnectionId", "bodyId", "gameNonce", "issuedAt", "expiresAt");
  private static final Set<String> PROOF_KEYS = Set.of("security", "kind", "serverBootId",
      "gameConnectionId", "bodyId", "clientInstallId", "gameNonce", "issuedAt", "expiresAt", "proof");

  private BodyProofMessageCodec() {}

  public record Proof(String serverBootId, String gameConnectionId, String bodyId,
      String clientInstallId, String gameNonce, String issuedAt, String expiresAt, String proof) {
    public Proof {
      validate(serverBootId, gameConnectionId, bodyId, gameNonce, issuedAt, expiresAt);
      ControlAuth.requireId(clientInstallId);
      ControlAuth.decodeB32(proof);
    }

    /** Pure conversion only; the caller still owns exact-connection one-use consume semantics. */
    public List<PendingBodyProof.Field> fields() {
      return List.of(new PendingBodyProof.Field("serverBootId", serverBootId),
          new PendingBodyProof.Field("gameConnectionId", gameConnectionId),
          new PendingBodyProof.Field("bodyId", bodyId),
          new PendingBodyProof.Field("clientInstallId", clientInstallId),
          new PendingBodyProof.Field("gameNonce", gameNonce),
          new PendingBodyProof.Field("issuedAt", issuedAt),
          new PendingBodyProof.Field("expiresAt", expiresAt),
          new PendingBodyProof.Field("proof", proof));
    }

    @Override public String toString() { return "BodyProofMessageCodec.Proof[redacted]"; }
  }

  /** Sanitized failure for any lexical/schema/value error; no raw payload is included. */
  public static final class CodecException extends IllegalArgumentException {
    private CodecException() { super("Invalid BODY message"); }
  }

  public static byte[] encodeChallenge(PendingBodyProof.BodyChallenge challenge) {
    try {
      validate(challenge.serverBootId(), challenge.gameConnectionId(), challenge.bodyId(),
          challenge.gameNonce(), challenge.issuedAt(), challenge.expiresAt());
      return encode(base("BODY_CHALLENGE", challenge.serverBootId(), challenge.gameConnectionId(),
          challenge.bodyId(), challenge.gameNonce(), challenge.issuedAt(), challenge.expiresAt()));
    } catch (RuntimeException error) {
      throw new CodecException();
    }
  }

  public static PendingBodyProof.BodyChallenge decodeChallenge(byte[] bytes) {
    Map<String, String> fields = decode(bytes, CHALLENGE_KEYS, "BODY_CHALLENGE");
    return new PendingBodyProof.BodyChallenge(fields.get("serverBootId"), fields.get("gameConnectionId"),
        fields.get("bodyId"), fields.get("gameNonce"), fields.get("issuedAt"), fields.get("expiresAt"));
  }

  public static byte[] encodeProof(Proof proof) {
    try {
      Map<String, String> fields = base("BODY_PROOF", proof.serverBootId(), proof.gameConnectionId(),
          proof.bodyId(), proof.gameNonce(), proof.issuedAt(), proof.expiresAt());
      fields.put("clientInstallId", proof.clientInstallId());
      fields.put("proof", proof.proof());
      return encode(fields);
    } catch (RuntimeException error) {
      throw new CodecException();
    }
  }

  public static Proof decodeProof(byte[] bytes) {
    Map<String, String> fields = decode(bytes, PROOF_KEYS, "BODY_PROOF");
    return new Proof(fields.get("serverBootId"), fields.get("gameConnectionId"), fields.get("bodyId"),
        fields.get("clientInstallId"), fields.get("gameNonce"), fields.get("issuedAt"),
        fields.get("expiresAt"), fields.get("proof"));
  }

  private static Map<String, String> decode(byte[] bytes, Set<String> keys, String kind) {
    try {
      if (bytes == null || bytes.length > MAX_BYTES) throw new CodecException();
      Map<String, Object> raw = StrictJson.decodeObject(bytes);
      if (!raw.keySet().equals(keys)) throw new CodecException();
      Map<String, String> fields = new LinkedHashMap<>();
      for (var entry : raw.entrySet()) {
        if (!(entry.getValue() instanceof String value)) throw new CodecException();
        fields.put(entry.getKey(), value);
      }
      if (!ControlAuth.DOMAIN.equals(fields.get("security")) || !kind.equals(fields.get("kind"))) {
        throw new CodecException();
      }
      validate(fields.get("serverBootId"), fields.get("gameConnectionId"), fields.get("bodyId"),
          fields.get("gameNonce"), fields.get("issuedAt"), fields.get("expiresAt"));
      if (kind.equals("BODY_PROOF")) {
        ControlAuth.requireId(fields.get("clientInstallId"));
        ControlAuth.decodeB32(fields.get("proof"));
      }
      return fields;
    } catch (StrictJson.DecodeException | RuntimeException error) {
      throw new CodecException();
    }
  }

  private static Map<String, String> base(String kind, String boot, String connection,
      String body, String nonce, String issued, String expires) {
    Map<String, String> fields = new LinkedHashMap<>();
    fields.put("security", ControlAuth.DOMAIN);
    fields.put("kind", kind);
    fields.put("serverBootId", boot);
    fields.put("gameConnectionId", connection);
    fields.put("bodyId", body);
    fields.put("gameNonce", nonce);
    fields.put("issuedAt", issued);
    fields.put("expiresAt", expires);
    return fields;
  }

  private static byte[] encode(Map<String, String> fields) {
    try {
      ByteArrayOutputStream output = new ByteArrayOutputStream();
      try (var generator = new JsonFactory().createGenerator(output)) {
        generator.writeStartObject();
        for (var entry : fields.entrySet()) generator.writeStringField(entry.getKey(), entry.getValue());
        generator.writeEndObject();
      }
      byte[] bytes = output.toByteArray();
      if (bytes.length > MAX_BYTES) throw new CodecException();
      return bytes;
    } catch (IOException error) {
      throw new CodecException();
    }
  }

  private static void validate(String boot, String connection, String body, String nonce,
      String issued, String expires) {
    ControlAuth.requireUuid(boot);
    ControlAuth.requireUuid(connection);
    ControlAuth.requireUuid(body);
    ControlAuth.decodeB32(nonce);
    Instant start = utc(issued);
    Instant end = utc(expires);
    if (!start.plusMillis(PendingBodyProof.TTL_MILLIS).equals(end)) throw new CodecException();
  }

  private static Instant utc(String value) {
    Instant parsed = Instant.parse(value);
    ControlAuth.requireUtc(parsed);
    if (!ControlAuth.formatUtc(parsed).equals(value)) throw new CodecException();
    return parsed;
  }
}
