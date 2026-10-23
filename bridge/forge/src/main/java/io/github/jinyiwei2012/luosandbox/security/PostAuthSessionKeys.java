package io.github.jinyiwei2012.luosandbox.security;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.List;

/** Unassembled v2 directional key owner; it grants no authenticated session capability. */
public final class PostAuthSessionKeys implements AutoCloseable {
  public static final String KDF_DOMAIN = "LUO_CONTROL_SESSION_KEY_V2";
  private byte[] peerToHub;
  private byte[] hubToPeer;
  private final byte[] bindingDigest;
  private boolean closed;

  private PostAuthSessionKeys(byte[] peerToHub, byte[] hubToPeer, byte[] bindingDigest) {
    this.peerToHub = peerToHub.clone();
    this.hubToPeer = hubToPeer.clone();
    this.bindingDigest = bindingDigest.clone();
  }

  /** Derive keys from caller-supplied trusted handshake context; this does not authenticate it. */
  public static PostAuthSessionKeys derive(byte[] rolePsk, ControlAuth.Transcript transcript) {
    if (transcript == null || transcript.peerRole() == ControlAuth.Role.AGENT_HUB
        || !ControlDeploymentManifest.PROTOCOL.equals(transcript.protocol())) {
      throw new IllegalArgumentException("invalid v2 handshake context");
    }
    if (rolePsk == null) throw new IllegalArgumentException("invalid role PSK");
    byte[] psk = null;
    byte[] authenticated = null;
    byte[] digest = null;
    byte[] peerToHub = null;
    byte[] hubToPeer = null;
    try {
      psk = rolePsk.clone();
      ControlAuth.requireHash(psk);
      authenticated = ControlAuth.transcript(ControlAuth.Label.PEER_PROOF, transcript);
      digest = sha256(authenticated);
      peerToHub = ControlAuth.hmac(
          psk, lp(List.of(ascii(KDF_DOMAIN), digest, ascii("PEER_TO_HUB"))));
      hubToPeer = ControlAuth.hmac(
          psk, lp(List.of(ascii(KDF_DOMAIN), digest, ascii("HUB_TO_PEER"))));
      return new PostAuthSessionKeys(peerToHub, hubToPeer, digest);
    } finally {
      erase(psk);
      erase(authenticated);
      erase(digest);
      erase(peerToHub);
      erase(hubToPeer);
    }
  }

  public synchronized byte[] mac(PostAuthFrameCodec.Direction direction, byte[] input) {
    requireOpen();
    if (direction == null || input == null) throw new IllegalArgumentException("invalid MAC input");
    return ControlAuth.hmac(key(direction), input.clone());
  }

  public synchronized boolean verify(
      PostAuthFrameCodec.Direction direction, byte[] input, String encodedMac) {
    requireOpen();
    try {
      byte[] supplied = ControlAuth.decodeB32(encodedMac);
      return MessageDigest.isEqual(mac(direction, input), supplied);
    } catch (IllegalArgumentException error) {
      return false;
    }
  }

  @Override public synchronized void close() {
    if (closed) return;
    Arrays.fill(peerToHub, (byte) 0);
    Arrays.fill(hubToPeer, (byte) 0);
    closed = true;
  }

  @Override public String toString() { return "PostAuthSessionKeys[redacted]"; }

  synchronized void requireContext(ControlAuth.Transcript transcript) {
    requireOpen();
    if (transcript == null || transcript.peerRole() == ControlAuth.Role.AGENT_HUB
        || !ControlDeploymentManifest.PROTOCOL.equals(transcript.protocol())) {
      throw new IllegalArgumentException("session key context mismatch");
    }
    byte[] authenticated = null;
    byte[] supplied = null;
    try {
      authenticated = ControlAuth.transcript(ControlAuth.Label.PEER_PROOF, transcript);
      supplied = sha256(authenticated);
      if (!MessageDigest.isEqual(bindingDigest, supplied)) {
        throw new IllegalArgumentException("session key context mismatch");
      }
    } finally {
      erase(authenticated);
      erase(supplied);
    }
  }

  private byte[] key(PostAuthFrameCodec.Direction direction) {
    return direction == PostAuthFrameCodec.Direction.PEER_TO_HUB ? peerToHub : hubToPeer;
  }

  private void requireOpen() {
    if (closed) throw new IllegalStateException("session keys are closed");
  }

  private static byte[] lp(List<byte[]> fields) {
    int size = fields.stream().mapToInt(field -> 4 + field.length).sum();
    ByteBuffer buffer = ByteBuffer.allocate(size);
    for (byte[] field : fields) buffer.putInt(field.length).put(field);
    return buffer.array();
  }

  private static byte[] ascii(String value) {
    return value.getBytes(StandardCharsets.US_ASCII);
  }

  private static byte[] sha256(byte[] value) {
    try { return MessageDigest.getInstance("SHA-256").digest(value); }
    catch (NoSuchAlgorithmException error) { throw new IllegalStateException("SHA-256 unavailable", error); }
  }

  private static void erase(byte[] value) {
    if (value != null) Arrays.fill(value, (byte) 0);
  }
}
