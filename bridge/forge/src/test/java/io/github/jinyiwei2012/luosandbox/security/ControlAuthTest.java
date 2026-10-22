package io.github.jinyiwei2012.luosandbox.security;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

class ControlAuthTest {
  private static final byte[] KEY = HexFormat.of().parseHex("000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f");

  @Test
  void producesFrozenPeerProofVectorAndDomainSeparatedLabels() {
    ControlAuth.Transcript transcript = vector();
    byte[] peer = ControlAuth.transcript(ControlAuth.Label.PEER_PROOF, transcript);
    assertEquals(416, peer.length);
    assertTrue(HexFormat.of().formatHex(peer).startsWith("0000001a6c756f73616e64626f782e636f6e74726f6c2d617574682e76310000000a504545525f50524f4f46"));
    assertEquals("95141dc1c381eb134b3d1f3df405d6e2ee2058e31f99b1269348b4f56f2a4921", HexFormat.of().formatHex(ControlAuth.hmac(KEY, peer)));
    assertFalse(java.util.Arrays.equals(peer, ControlAuth.transcript(ControlAuth.Label.HUB_PROOF, transcript)));
    assertFalse(java.util.Arrays.equals(peer, ControlAuth.transcript(ControlAuth.Label.READY_PROOF, transcript)));
  }

  @Test
  void b32AndVerificationRejectTamperingReflectionAndCrossRoleKey() {
    ControlAuth.Transcript transcript = vector();
    String peer = ControlAuth.b32(ControlAuth.hmac(KEY, ControlAuth.transcript(ControlAuth.Label.PEER_PROOF, transcript)));
    assertTrue(ControlAuth.verify(KEY, ControlAuth.Label.PEER_PROOF, transcript, peer));
    assertFalse(ControlAuth.verify(KEY, ControlAuth.Label.HUB_PROOF, transcript, peer));
    assertFalse(ControlAuth.verify(new byte[32], ControlAuth.Label.PEER_PROOF, transcript, peer));
    assertFalse(ControlAuth.verify(KEY, ControlAuth.Label.PEER_PROOF, transcript, peer.substring(0, 42) + "A"));
    assertThrows(IllegalArgumentException.class, () -> ControlAuth.decodeB32(peer + "="));
  }

  @Test
  void constructorDefensivelyCopiesSensitiveByteArrays() {
    byte[] hash = new byte[32];
    ControlAuth.Transcript transcript = new ControlAuth.Transcript(ControlAuth.Role.CLIENT_CONTROLLER, "client-1", "hub-1", "key", "proto", hash, new byte[32], new byte[32], "00000000-0000-4000-8000-000000000001", Instant.parse("2026-10-23T00:00:00Z"), Instant.parse("2026-10-23T00:00:05Z"), new byte[32], new byte[32]);
    hash[0] = 99;
    assertEquals(0, transcript.modHash()[0]);
    byte[] output = transcript.modHash(); output[0] = 44;
    assertEquals(0, transcript.modHash()[0]);
  }

  @Test
  void emitsNonsecretDummyVectorsForLaterCrossLanguageConsumption() throws Exception {
    Path output = Path.of("build", "security-vectors");
    Files.createDirectories(output);
    ControlAuth.Transcript transcript = vector();
    StringBuilder text = new StringBuilder();
    for (ControlAuth.Label label : ControlAuth.Label.values()) {
      text.append(label).append(' ')
          .append(HexFormat.of().formatHex(ControlAuth.transcript(label, transcript))).append(' ')
          .append(HexFormat.of().formatHex(ControlAuth.hmac(KEY, ControlAuth.transcript(label, transcript)))).append('\n');
    }
    Files.writeString(output.resolve("control-auth-v1-dummy.txt"), text);
  }

  private static ControlAuth.Transcript vector() {
    return new ControlAuth.Transcript(ControlAuth.Role.CLIENT_CONTROLLER, "client-1", "hub-1", "test-key-1", "luosandbox.minecraft.v1", new byte[32], repeat(0x11), repeat(0x22), "00000000-0000-4000-8000-000000000001", Instant.parse("2026-10-23T00:00:00Z"), Instant.parse("2026-10-23T00:00:05Z"), repeat(0x33), repeat(0x44));
  }

  private static byte[] repeat(int value) { byte[] bytes = new byte[32]; java.util.Arrays.fill(bytes, (byte) value); return bytes; }
}
