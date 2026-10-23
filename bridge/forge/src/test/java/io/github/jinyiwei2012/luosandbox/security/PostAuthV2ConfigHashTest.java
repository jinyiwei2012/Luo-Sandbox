package io.github.jinyiwei2012.luosandbox.security;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

/** Test-only raw configHash fixture evidence; not a production deployment configuration. */
class PostAuthV2ConfigHashTest {
  private static final String SCHEMA = "postauth-v2-config-fixture-v1";
  private static final String STATUS = "TEST_FIXTURE_ONLY_NOT_PRODUCTION_DEPLOYMENT_CONFIG";
  private static final String FIXTURE = "scripts/tests/fixtures/postauth_v2_config_raw.json";
  private static final String PEER = "client-config-dummy-1";
  private static final String HUB = "hub-config-dummy-1";
  private static final String KEY_ID = "dummy-config-key-1";
  private static final String PROTOCOL = "luosandbox.minecraft.v1";
  private static final String CHALLENGE = "70000000-0000-4000-8000-000000000001";
  private static final Instant ISSUED = Instant.parse("2026-10-23T00:00:00.000Z");
  private static final Instant EXPIRES = Instant.parse("2026-10-23T00:00:05.000Z");
  private static final HexFormat HEX = HexFormat.of();

  @Test
  void rawFixtureHashIsBoundByActualControlAuthTranscriptAndHmac() throws Exception {
    byte[] raw = Files.readAllBytes(repoRoot().resolve(FIXTURE));
    byte[] configHash = sha256(raw);
    ControlAuth.Transcript value = new ControlAuth.Transcript(
        ControlAuth.Role.CLIENT_CONTROLLER, PEER, HUB, KEY_ID, PROTOCOL, range(32), range(64),
        range(96), CHALLENGE, ISSUED, EXPIRES, configHash, range(128));
    byte[] transcript = ControlAuth.transcript(ControlAuth.Label.PEER_PROOF, value);
    byte[] mac = ControlAuth.hmac(range(0), transcript);
    assertEquals(16, lpFieldCount(transcript));
    assertArrayEquals(configHash, lpField(transcript, 14));
    assertFalse(MessageDigest.isEqual(configHash, range(32)));
    assertFalse(MessageDigest.isEqual(configHash, range(128)));

    String output = System.getenv("LUOSANDBOX_POSTAUTH_V2_CONFIG_OUTPUT");
    if (output != null && !output.isBlank()) {
      export(Path.of(output), raw, configHash, transcript, mac);
    }
  }

  @Test
  void sameSemanticFieldsWithDifferentRawBytesChangeTranscriptAndHmac() throws Exception {
    byte[] raw = Files.readAllBytes(repoRoot().resolve(FIXTURE));
    byte[] alternate = ("{\n  \"security\": \"luosandbox.control-auth.v1\",\n"
        + "  \"postAuthProfile\": \"LUO_CONTROL_POSTAUTH_V2\",\n"
        + "  \"sessionKdfDomain\": \"LUO_CONTROL_SESSION_KEY_V2\",\n"
        + "  \"frameMacDomain\": \"LUO_CONTROL_FRAME_V2\"\n}\n")
        .getBytes(StandardCharsets.US_ASCII);
    byte[] originalTranscript = transcript(sha256(raw));
    byte[] alternateTranscript = transcript(sha256(alternate));
    assertFalse(MessageDigest.isEqual(sha256(raw), sha256(alternate)));
    assertFalse(MessageDigest.isEqual(originalTranscript, alternateTranscript));
    assertFalse(MessageDigest.isEqual(ControlAuth.hmac(range(0), originalTranscript),
        ControlAuth.hmac(range(0), alternateTranscript)));
  }

  private static byte[] transcript(byte[] configHash) {
    return ControlAuth.transcript(ControlAuth.Label.PEER_PROOF, new ControlAuth.Transcript(
        ControlAuth.Role.CLIENT_CONTROLLER, PEER, HUB, KEY_ID, PROTOCOL, range(32), range(64),
        range(96), CHALLENGE, ISSUED, EXPIRES, configHash, range(128)));
  }

  private static Path repoRoot() {
    return Path.of("..", "..").toAbsolutePath().normalize();
  }

  private static byte[] range(int start) {
    byte[] value = new byte[32];
    for (int index = 0; index < value.length; index++) value[index] = (byte) (start + index);
    return value;
  }

  private static byte[] sha256(byte[] value) throws Exception {
    return MessageDigest.getInstance("SHA-256").digest(value);
  }

  private static int lpFieldCount(byte[] encoded) {
    ByteBuffer buffer = ByteBuffer.wrap(encoded);
    int count = 0;
    while (buffer.hasRemaining()) {
      int length = buffer.getInt();
      assertTrue(length >= 0 && length <= buffer.remaining());
      buffer.position(buffer.position() + length);
      count++;
    }
    return count;
  }

  private static byte[] lpField(byte[] encoded, int expectedIndex) {
    ByteBuffer buffer = ByteBuffer.wrap(encoded);
    int index = 0;
    while (buffer.hasRemaining()) {
      int length = buffer.getInt();
      byte[] value = new byte[length];
      buffer.get(value);
      if (index++ == expectedIndex) return value;
    }
    throw new IllegalArgumentException("missing LP field");
  }

  private static byte[] classBytes() throws Exception {
    try (InputStream input = ControlAuth.class.getResourceAsStream("ControlAuth.class")) {
      assertNotNull(input);
      return input.readAllBytes();
    }
  }

  private static void export(Path requested, byte[] raw, byte[] configHash, byte[] transcript,
      byte[] mac) throws Exception {
    Path path = requested.toAbsolutePath().normalize();
    Path approved = Path.of("..", "..", ".gradle-user-home", "interop").toAbsolutePath().normalize();
    assertTrue(path.startsWith(approved));
    Files.createDirectories(path.getParent());
    try (JsonGenerator json = new JsonFactory().createGenerator(Files.newOutputStream(path))) {
      json.writeStartObject();
      json.writeStringField("schema", SCHEMA);
      json.writeStringField("status", STATUS);
      json.writeArrayFieldStart("claims");
      json.writeString("raw_fixture_bytes_sha256_bound_into_existing_controlauth_peer_proof_transcript");
      json.writeString("fixture_key_order_and_single_lf_are_test_construction_not_normative_canonical_json");
      json.writeString("does_not_prove_production_loader_allowlist_or_deployment_manifest");
      json.writeEndArray();
      json.writeObjectFieldStart("fixtureFields");
      json.writeStringField("security", ControlAuth.DOMAIN);
      json.writeStringField("postAuthProfile", "LUO_CONTROL_POSTAUTH_V2");
      json.writeStringField("sessionKdfDomain", "LUO_CONTROL_SESSION_KEY_V2");
      json.writeStringField("frameMacDomain", "LUO_CONTROL_FRAME_V2");
      json.writeEndObject();
      json.writeNumberField("rawFixtureLength", raw.length);
      json.writeStringField("rawFixtureSha256", HEX.formatHex(configHash));
      json.writeObjectFieldStart("dummyTranscriptInputs");
      json.writeStringField("peerRole", "CLIENT_CONTROLLER");
      json.writeStringField("peerInstallId", PEER);
      json.writeStringField("hubInstallId", HUB);
      json.writeStringField("keyId", KEY_ID);
      json.writeStringField("protocol", PROTOCOL);
      json.writeStringField("modHashHex", HEX.formatHex(range(32)));
      json.writeStringField("peerNonceHex", HEX.formatHex(range(64)));
      json.writeStringField("hubNonceHex", HEX.formatHex(range(96)));
      json.writeStringField("challengeId", CHALLENGE);
      json.writeStringField("issuedAt", ControlAuth.formatUtc(ISSUED));
      json.writeStringField("expiresAt", ControlAuth.formatUtc(EXPIRES));
      json.writeStringField("bindingHashHex", HEX.formatHex(range(128)));
      json.writeStringField("dummyRolePskHex", HEX.formatHex(range(0)));
      json.writeStringField("configHashHex", HEX.formatHex(configHash));
      json.writeEndObject();
      json.writeStringField("peerProofTranscriptHex", HEX.formatHex(transcript));
      json.writeStringField("dummyPeerProofHmacHex", HEX.formatHex(mac));
      json.writeObjectFieldStart("javaArtifactIdentity");
      json.writeStringField("controlAuthClassResource",
          String.valueOf(ControlAuth.class.getResource("ControlAuth.class")));
      json.writeStringField("controlAuthClassSha256", HEX.formatHex(sha256(classBytes())));
      json.writeEndObject();
      json.writeObjectFieldStart("invocation");
      json.writeStringField("testClass", PostAuthV2ConfigHashTest.class.getName());
      json.writeBooleanField("forcedRerunRequired", true);
      json.writeEndObject();
      json.writeEndObject();
    }
  }
}
