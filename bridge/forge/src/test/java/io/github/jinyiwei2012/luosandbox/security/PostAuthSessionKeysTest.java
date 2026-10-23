package io.github.jinyiwei2012.luosandbox.security;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

class PostAuthSessionKeysTest {
  @Test
  void derivesBothDirectionsFromActualPeerProofTranscriptKnownAnswers() throws Exception {
    for (ControlAuth.Role role : new ControlAuth.Role[] {
        ControlAuth.Role.CLIENT_CONTROLLER, ControlAuth.Role.SERVER_AUTHORITY}) {
      ControlAuth.Transcript transcript = transcript(role);
      byte[] psk = range(role == ControlAuth.Role.CLIENT_CONTROLLER ? 0 : 96);
      PostAuthSessionKeys keys = PostAuthSessionKeys.derive(psk, transcript);
      byte[] auth = ControlAuth.transcript(ControlAuth.Label.PEER_PROOF, transcript);
      byte[] digest = MessageDigest.getInstance("SHA-256").digest(auth);
      for (PostAuthFrameCodec.Direction direction : PostAuthFrameCodec.Direction.values()) {
        byte[] expected = hmac(psk, lp(PostAuthSessionKeys.KDF_DOMAIN.getBytes(StandardCharsets.US_ASCII),
            digest, direction.name().getBytes(StandardCharsets.US_ASCII)));
        byte[] message = "fixed-message".getBytes(StandardCharsets.US_ASCII);
        assertArrayEquals(hmac(expected, message), keys.mac(direction, message));
      }
      assertFalse(java.util.Arrays.equals(
          keys.mac(PostAuthFrameCodec.Direction.PEER_TO_HUB, new byte[0]),
          keys.mac(PostAuthFrameCodec.Direction.HUB_TO_PEER, new byte[0])));
      assertEquals("PostAuthSessionKeys[redacted]", keys.toString());
      keys.close();
    }
  }

  @Test
  void inputArraysAreDefensiveAndClosedKeysRejectAllCrypto() {
    byte[] psk = range(0);
    byte[] original = psk.clone();
    PostAuthSessionKeys keys = PostAuthSessionKeys.derive(psk, transcript(ControlAuth.Role.CLIENT_CONTROLLER));
    psk[0] ^= 1;
    byte[] input = "input".getBytes(StandardCharsets.US_ASCII);
    String proof = ControlAuth.b32(keys.mac(PostAuthFrameCodec.Direction.PEER_TO_HUB, input));
    input[0] ^= 1;
    assertTrue(keys.verify(PostAuthFrameCodec.Direction.PEER_TO_HUB,
        "input".getBytes(StandardCharsets.US_ASCII), proof));
    assertArrayEquals(original, range(0));
    keys.close();
    keys.close();
    assertThrows(IllegalStateException.class,
        () -> keys.mac(PostAuthFrameCodec.Direction.PEER_TO_HUB, new byte[0]));
    assertThrows(IllegalStateException.class,
        () -> keys.verify(PostAuthFrameCodec.Direction.HUB_TO_PEER, new byte[0], "A".repeat(43)));
  }

  @Test
  void rejectsAgentHubV1ProtocolAndWrongPskWidth() {
    assertThrows(IllegalArgumentException.class,
        () -> PostAuthSessionKeys.derive(new byte[31], transcript(ControlAuth.Role.CLIENT_CONTROLLER)));
    assertThrows(IllegalArgumentException.class,
        () -> PostAuthSessionKeys.derive(range(0), transcript(ControlAuth.Role.AGENT_HUB)));
    ControlAuth.Transcript v1 = new ControlAuth.Transcript(ControlAuth.Role.CLIENT_CONTROLLER,
        "peer", "hub", "key", "luosandbox.minecraft.v1", range(32), range(64), range(96),
        "10000000-0000-4000-8000-000000000001", Instant.parse("2026-10-23T00:00:00Z"),
        Instant.parse("2026-10-23T00:00:05Z"), range(128), range(160));
    assertThrows(IllegalArgumentException.class, () -> PostAuthSessionKeys.derive(range(0), v1));
  }

  static ControlAuth.Transcript transcript(ControlAuth.Role role) {
    return new ControlAuth.Transcript(role, role == ControlAuth.Role.SERVER_AUTHORITY ? "server" : "client",
        "hub", "key", "LUO_CONTROL_POSTAUTH_V2", range(32), range(64), range(96),
        "10000000-0000-4000-8000-000000000001", Instant.parse("2026-10-23T00:00:00Z"),
        Instant.parse("2026-10-23T00:00:05Z"), range(128), range(160));
  }

  static byte[] range(int start) {
    byte[] value = new byte[32];
    for (int index = 0; index < value.length; index++) value[index] = (byte) (start + index);
    return value;
  }

  private static byte[] lp(byte[]... fields) {
    int size = 0;
    for (byte[] field : fields) size += 4 + field.length;
    ByteBuffer buffer = ByteBuffer.allocate(size);
    for (byte[] field : fields) buffer.putInt(field.length).put(field);
    return buffer.array();
  }

  private static byte[] hmac(byte[] key, byte[] input) throws Exception {
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(key, "HmacSHA256"));
    return mac.doFinal(input);
  }
}
