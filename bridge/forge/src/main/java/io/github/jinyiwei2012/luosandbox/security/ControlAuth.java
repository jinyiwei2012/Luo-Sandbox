package io.github.jinyiwei2012.luosandbox.security;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.List;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Stateless v1 transcript, HMAC, and strict field primitives. No transport or key storage. */
public final class ControlAuth {
  public static final String DOMAIN = "luosandbox.control-auth.v1";
  private static final Pattern ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}");
  private static final Pattern UUID = Pattern.compile("(?!00000000-0000-0000-0000-000000000000$)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
  private static final Pattern B32 = Pattern.compile("[A-Za-z0-9_-]{43}");
  private static final DateTimeFormatter UTC = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

  private ControlAuth() {}

  public enum Role { SERVER_AUTHORITY, CLIENT_CONTROLLER, AGENT_HUB }
  public enum Label { HUB_PROOF, PEER_PROOF, READY_PROOF }

  public record Transcript(Role peerRole, String peerInstallId, String hubInstallId, String keyId,
      String protocol, byte[] modHash, byte[] peerNonce, byte[] hubNonce, String challengeId,
      Instant issuedAt, Instant expiresAt, byte[] configHash, byte[] bindingHash) {
    public Transcript {
      requireId(peerInstallId); requireId(hubInstallId); requireId(keyId); requireId(protocol);
      requireUuid(challengeId); requireHash(modHash); requireHash(peerNonce); requireHash(hubNonce);
      requireHash(configHash); requireHash(bindingHash); requireUtc(issuedAt); requireUtc(expiresAt);
      modHash = modHash.clone(); peerNonce = peerNonce.clone(); hubNonce = hubNonce.clone();
      configHash = configHash.clone(); bindingHash = bindingHash.clone();
    }
    @Override public byte[] modHash() { return modHash.clone(); }
    @Override public byte[] peerNonce() { return peerNonce.clone(); }
    @Override public byte[] hubNonce() { return hubNonce.clone(); }
    @Override public byte[] configHash() { return configHash.clone(); }
    @Override public byte[] bindingHash() { return bindingHash.clone(); }
  }

  public static byte[] transcript(Label label, Transcript value) {
    return lp(List.of(ascii(DOMAIN), ascii(label.name()), ascii(value.peerRole().name()), ascii(Role.AGENT_HUB.name()),
        ascii(value.peerInstallId()), ascii(value.hubInstallId()), ascii(value.keyId()), ascii(value.protocol()),
        value.modHash(), value.peerNonce(), value.hubNonce(), ascii(value.challengeId()), ascii(formatUtc(value.issuedAt())),
        ascii(formatUtc(value.expiresAt())), value.configHash(), value.bindingHash()));
  }

  public static byte[] hmac(byte[] key, byte[] transcript) {
    requireHash(key);
    try { Mac mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(key.clone(), "HmacSHA256")); return mac.doFinal(transcript); }
    catch (Exception error) { throw new IllegalStateException("HMAC-SHA256 unavailable", error); }
  }

  public static boolean verify(byte[] key, Label expected, Transcript transcript, String encodedMac) {
    try { return MessageDigest.isEqual(hmac(key, transcript(expected, transcript)), decodeB32(encodedMac)); }
    catch (IllegalArgumentException error) { return false; }
  }

  public static String b32(byte[] value) {
    requireHash(value);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
  }

  public static byte[] decodeB32(String value) {
    if (value == null || !B32.matcher(value).matches()) throw new IllegalArgumentException("invalid B32");
    byte[] decoded = Base64.getUrlDecoder().decode(value);
    if (decoded.length != 32 || !b32(decoded).equals(value)) throw new IllegalArgumentException("noncanonical B32");
    return decoded;
  }

  public static String formatUtc(Instant value) {
    requireUtc(value);
    return UTC.format(value);
  }

  public static void requireId(String value) { if (value == null || !ID.matcher(value).matches()) throw new IllegalArgumentException("invalid ASCII Id"); }
  public static void requireUuid(String value) { if (value == null || !UUID.matcher(value).matches()) throw new IllegalArgumentException("invalid UUID"); }
  public static void requireHash(byte[] value) { if (value == null || value.length != 32) throw new IllegalArgumentException("expected 32 bytes"); }
  public static void requireUtc(Instant value) { if (value == null || value.getNano() % 1_000_000 != 0 || value.atOffset(ZoneOffset.UTC).getYear() < 1 || value.atOffset(ZoneOffset.UTC).getYear() > 9999) throw new IllegalArgumentException("invalid millisecond UTC"); }

  private static byte[] lp(List<byte[]> values) {
    int size = values.stream().mapToInt(value -> 4 + value.length).sum();
    ByteBuffer result = ByteBuffer.allocate(size);
    for (byte[] value : values) result.putInt(value.length).put(value);
    return result.array();
  }

  private static byte[] ascii(String value) {
    for (int index = 0; index < value.length(); index++) if (value.charAt(index) > 0x7f || value.charAt(index) == 0) throw new IllegalArgumentException("non-ASCII transcript field");
    return value.getBytes(StandardCharsets.US_ASCII);
  }
}
