package io.github.jinyiwei2012.luosandbox.security;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.List;

/** Stateless BODY proof primitives, independent of WS authentication and world authority. */
public final class BodyProofAuth {
  public static final String DOMAIN = "luosandbox.body-bind.v1";
  public static final String LABEL = "CLIENT_BODY_PROOF";

  private BodyProofAuth() {}

  public record Transcript(
      String serverInstallId,
      String clientInstallId,
      String bodyId,
      String serverBootId,
      String gameConnectionId,
      byte[] gameNonce,
      Instant issuedAt,
      Instant expiresAt,
      byte[] bindingHash) {
    public Transcript {
      ControlAuth.requireId(serverInstallId);
      ControlAuth.requireId(clientInstallId);
      ControlAuth.requireUuid(bodyId);
      ControlAuth.requireUuid(serverBootId);
      ControlAuth.requireUuid(gameConnectionId);
      ControlAuth.requireHash(gameNonce);
      ControlAuth.requireHash(bindingHash);
      ControlAuth.requireUtc(issuedAt);
      ControlAuth.requireUtc(expiresAt);
      gameNonce = gameNonce.clone();
      bindingHash = bindingHash.clone();
    }

    @Override public byte[] gameNonce() { return gameNonce.clone(); }
    @Override public byte[] bindingHash() { return bindingHash.clone(); }
    @Override public String toString() { return "BodyProofAuth.Transcript[redacted]"; }
  }

  /** Eleven U32BE-length-prefixed fields; nonce/hash are raw bytes, other fields strict ASCII. */
  public static byte[] transcript(Transcript value) {
    List<byte[]> fields = List.of(
        ascii(DOMAIN), ascii(LABEL), ascii(value.serverInstallId()),
        ascii(value.clientInstallId()), ascii(value.bodyId()), ascii(value.serverBootId()),
        ascii(value.gameConnectionId()), value.gameNonce(),
        ascii(ControlAuth.formatUtc(value.issuedAt())),
        ascii(ControlAuth.formatUtc(value.expiresAt())), value.bindingHash());
    int size = fields.stream().mapToInt(field -> 4 + field.length).sum();
    ByteBuffer buffer = ByteBuffer.allocate(size);
    for (byte[] field : fields) buffer.putInt(field.length).put(field);
    return buffer.array();
  }

  public static byte[] hmac(byte[] bodyKey, Transcript value) {
    return ControlAuth.hmac(bodyKey, transcript(value));
  }

  public static String proof(byte[] bodyKey, Transcript value) {
    return ControlAuth.b32(hmac(bodyKey, value));
  }

  public static boolean verify(byte[] bodyKey, Transcript value, String proof) {
    try {
      byte[] supplied = ControlAuth.decodeB32(proof);
      return MessageDigest.isEqual(hmac(bodyKey, value), supplied);
    } catch (IllegalArgumentException error) {
      return false;
    }
  }

  private static byte[] ascii(String value) {
    return value.getBytes(StandardCharsets.US_ASCII);
  }
}
