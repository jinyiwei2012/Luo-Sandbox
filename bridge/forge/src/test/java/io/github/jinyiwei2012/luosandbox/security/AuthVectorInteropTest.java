package io.github.jinyiwei2012.luosandbox.security;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

class AuthVectorInteropTest {
  private static final byte[] KEY = HexFormat.of().parseHex(
      "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f");

  @Test
  void emitsFixedDummyVectorsForPythonInterop() throws Exception {
    ControlAuth.Transcript transcript = new ControlAuth.Transcript(
        ControlAuth.Role.CLIENT_CONTROLLER, "client-1", "hub-1", "test-key-1",
        "luosandbox.minecraft.v1", new byte[32], repeat(0x11), repeat(0x22),
        "00000000-0000-4000-8000-000000000001", Instant.parse("2026-10-23T00:00:00Z"),
        Instant.parse("2026-10-23T00:00:05Z"), repeat(0x33), repeat(0x44));
    StringBuilder vectors = new StringBuilder();
    for (ControlAuth.Label label : ControlAuth.Label.values()) {
      byte[] encoded = ControlAuth.transcript(label, transcript);
      vectors.append("\"" + label + "\":{\"transcriptHex\":\"")
          .append(HexFormat.of().formatHex(encoded)).append("\",\"macHex\":\"")
          .append(HexFormat.of().formatHex(ControlAuth.hmac(KEY, encoded))).append("\"},");
    }
    assertEquals(416, ControlAuth.transcript(ControlAuth.Label.PEER_PROOF, transcript).length);
    Path output = Path.of("build", "security-vectors", "auth-v1.json");
    Files.createDirectories(output.getParent());
    String json = "{\"keyHex\":\"" + HexFormat.of().formatHex(KEY) + "\","
        + "\"peerRole\":\"CLIENT_CONTROLLER\",\"peerInstallId\":\"client-1\","
        + "\"hubInstallId\":\"hub-1\",\"keyId\":\"test-key-1\","
        + "\"protocol\":\"luosandbox.minecraft.v1\",\"modHashHex\":\""
        + HexFormat.of().formatHex(new byte[32]) + "\",\"peerNonceHex\":\""
        + HexFormat.of().formatHex(repeat(0x11)) + "\",\"hubNonceHex\":\""
        + HexFormat.of().formatHex(repeat(0x22)) + "\",\"challengeId\":\""
        + "00000000-0000-4000-8000-000000000001\",\"issuedAt\":\"2026-10-23T00:00:00.000Z\","
        + "\"expiresAt\":\"2026-10-23T00:00:05.000Z\",\"configHashHex\":\""
        + HexFormat.of().formatHex(repeat(0x33)) + "\",\"bindingHashHex\":\""
        + HexFormat.of().formatHex(repeat(0x44)) + "\",\"vectors\":{"
        + vectors.substring(0, vectors.length() - 1) + "}}\n";
    Files.writeString(output, json);
  }

  private static byte[] repeat(int value) {
    byte[] bytes = new byte[32];
    java.util.Arrays.fill(bytes, (byte) value);
    return bytes;
  }
}
