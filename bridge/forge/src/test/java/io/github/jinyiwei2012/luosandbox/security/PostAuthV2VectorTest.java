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
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Test-only evidence for the non-normative, not-frozen post-auth v2 draft. */
class PostAuthV2VectorTest {
  private static final String SCHEMA = "postauth-v2-draft-vector-v1";
  private static final String PROFILE = "LUO_CONTROL_POSTAUTH_V2";
  private static final String KDF_DOMAIN = "LUO_CONTROL_SESSION_KEY_V2";
  private static final String FRAME_DOMAIN = "LUO_CONTROL_FRAME_V2";
  private static final String HUB_INSTALL_ID = "hub-dummy-1";
  private static final String PROTOCOL = "luosandbox.minecraft.v1";
  private static final String ROUTE_ID = "30000000-0000-4000-8000-000000000001";
  private static final String SERVER_BOOT_ID = "40000000-0000-4000-8000-000000000001";
  private static final String GAME_CONNECTION_ID = "50000000-0000-4000-8000-000000000001";
  private static final String BODY_ID = "60000000-0000-4000-8000-000000000001";
  private static final String CHALLENGE_EXPIRES_AT = "2026-10-23T00:00:05.000Z";
  private static final Instant ISSUED_AT = Instant.parse("2026-10-23T00:00:00.000Z");
  private static final Instant EXPIRES_AT = Instant.parse("2026-10-23T00:00:05.000Z");
  private static final HexFormat HEX = HexFormat.of();

  @Test
  void exportsIndependentDummyVectorsUsingProductionControlAuthTranscriptAndHmac() throws Exception {
    List<Vector> vectors = new ArrayList<>();
    Inputs client = inputs(ControlAuth.Role.CLIENT_CONTROLLER);
    Inputs server = inputs(ControlAuth.Role.SERVER_AUTHORITY);
    addCommon(vectors, client);
    vectors.add(vector(client, "client-controller-body-bind-notice", "PEER_TO_HUB", 1,
        "BODY_BIND_NOTICE", "", "", "", "", "", noticePayload()));
    addCommon(vectors, server);
    vectors.add(vector(server, "server-authority-body-bind-route", "HUB_TO_PEER", 2,
        "BODY_BIND_ROUTE", ROUTE_ID, client.peerInstallId(), client.connectionId(),
        server.connectionId(), "", noticePayload()));
    vectors.add(vector(server, "server-authority-body-bound-candidate", "PEER_TO_HUB", 1,
        "BODY_BOUND_CANDIDATE", ROUTE_ID, "", "", server.connectionId(),
        CHALLENGE_EXPIRES_AT, candidatePayload()));

    assertEquals(9, vectors.size());
    assertTrue(vectors.stream().anyMatch(value -> value.peerRole().equals("CLIENT_CONTROLLER")));
    assertTrue(vectors.stream().anyMatch(value -> value.peerRole().equals("SERVER_AUTHORITY")));
    assertTrue(vectors.stream().anyMatch(value -> value.direction().equals("PEER_TO_HUB")));
    assertTrue(vectors.stream().anyMatch(value -> value.direction().equals("HUB_TO_PEER")));
    for (Vector value : vectors) {
      assertEquals(16, lpFieldCount(HEX.parseHex(value.authTranscriptHex())));
      assertEquals(3, lpFieldCount(HEX.parseHex(value.keyDerivationInputHex())));
      assertEquals(18, lpFieldCount(HEX.parseHex(value.frameMacInputHex())));
      assertEquals(43, value.b32Proof().length());
    }

    String output = System.getenv("LUOSANDBOX_POSTAUTH_V2_VECTOR_OUTPUT");
    if (output != null && !output.isBlank()) export(Path.of(output), vectors);
  }

  @Test
  void fieldDirectionRoleSequenceProfileExpiryAndCanonicalEncodingMutationsFailClosed()
      throws Exception {
    Inputs server = inputs(ControlAuth.Role.SERVER_AUTHORITY);
    byte[] payload = candidatePayload();
    byte[] original = frameInput(server, "PEER_TO_HUB", 1, "BODY_BOUND_CANDIDATE", ROUTE_ID,
        "", "", server.connectionId(), CHALLENGE_EXPIRES_AT, payload, PROFILE);
    byte[] key = derivedKey(server, "PEER_TO_HUB");
    byte[] mac = ControlAuth.hmac(key, original);
    assertFalse(MessageDigest.isEqual(mac, ControlAuth.hmac(key,
        frameInput(server, "PEER_TO_HUB", 2, "BODY_BOUND_CANDIDATE", ROUTE_ID, "", "",
            server.connectionId(), CHALLENGE_EXPIRES_AT, payload, PROFILE))));
    assertFalse(MessageDigest.isEqual(mac, ControlAuth.hmac(key,
        frameInput(server, "PEER_TO_HUB", 1, "BODY_BOUND_CANDIDATE", ROUTE_ID, "", "",
            server.connectionId(), "2026-10-23T00:00:04.000Z", payload, PROFILE))));
    assertFalse(MessageDigest.isEqual(mac, ControlAuth.hmac(key,
        frameInput(server, "PEER_TO_HUB", 1, "BODY_BOUND_CANDIDATE", ROUTE_ID, "", "",
            server.connectionId(), CHALLENGE_EXPIRES_AT, payload,
            "LUO_CONTROL_POSTAUTH_V2_ALIAS"))));
    assertFalse(MessageDigest.isEqual(key, derivedKey(server, "HUB_TO_PEER")));
    Inputs client = inputs(ControlAuth.Role.CLIENT_CONTROLLER);
    assertFalse(MessageDigest.isEqual(original,
        frameInput(client.withConnectionId(server.connectionId()), "PEER_TO_HUB", 1,
            "BODY_BOUND_CANDIDATE", ROUTE_ID, "", "", server.connectionId(),
            CHALLENGE_EXPIRES_AT, payload, PROFILE)));
    assertThrows(IllegalArgumentException.class, () -> ControlAuth.decodeB32(b32(mac) + "="));
    assertThrows(IllegalArgumentException.class, () -> sessionOpenPayload(
        "00000000-0000-0000-0000-000000000000"));
    assertThrows(IllegalArgumentException.class, () -> validateBudget(new byte[2049], new byte[10]));
    assertThrows(IllegalArgumentException.class, () -> validateBudget(new byte[10], new byte[4097]));
    validateBudget(new byte[2048], new byte[4096]);
  }

  private static void addCommon(List<Vector> vectors, Inputs inputs) throws Exception {
    String role = inputs.role().name().toLowerCase().replace('_', '-');
    vectors.add(vector(inputs, role + "-session-open", "HUB_TO_PEER", 0, "SESSION_OPEN",
        "", "", "", "", "", sessionOpenPayload(inputs.connectionId())));
    vectors.add(vector(inputs, role + "-heartbeat-peer-ack", "PEER_TO_HUB", 0, "HEARTBEAT",
        "", "", "", "", "", ascii("{}")));
    vectors.add(vector(inputs, role + "-heartbeat-hub", "HUB_TO_PEER", 1, "HEARTBEAT",
        "", "", "", "", "", ascii("{}")));
  }

  private static Inputs inputs(ControlAuth.Role role) {
    if (role == ControlAuth.Role.CLIENT_CONTROLLER) {
      return new Inputs(role, "client-dummy-1", "dummy-client-key-1", range(0), range(32),
          "10000000-0000-4000-8000-000000000001",
          "20000000-0000-4000-8000-000000000001");
    }
    return new Inputs(role, "server-dummy-1", "dummy-server-key-1", range(96), range(128),
        "10000000-0000-4000-8000-000000000002",
        "20000000-0000-4000-8000-000000000002");
  }

  private static Vector vector(Inputs inputs, String name, String direction, long sequence,
      String messageKind, String routeId, String sourceInstallId, String sourceConnectionId,
      String recipientConnectionId, String challengeExpiresAt, byte[] payload) throws Exception {
    byte[] transcript = ControlAuth.transcript(ControlAuth.Label.PEER_PROOF, inputs.transcript());
    byte[] digest = MessageDigest.getInstance("SHA-256").digest(transcript);
    byte[] derivation = lp(ascii(KDF_DOMAIN), digest, ascii(direction));
    byte[] key = ControlAuth.hmac(inputs.rolePsk(), derivation);
    byte[] frameInput = frameInput(inputs, direction, sequence, messageKind, routeId,
        sourceInstallId, sourceConnectionId, recipientConnectionId, challengeExpiresAt, payload,
        PROFILE);
    byte[] mac = ControlAuth.hmac(key, frameInput);
    return new Vector(name, inputs.role().name(), direction, HEX.formatHex(inputs.rolePsk()), inputs,
        HEX.formatHex(transcript), HEX.formatHex(digest), HEX.formatHex(derivation),
        HEX.formatHex(key), sequence, messageKind, routeId, sourceInstallId, sourceConnectionId,
        recipientConnectionId, challengeExpiresAt, HEX.formatHex(payload),
        HEX.formatHex(frameInput), HEX.formatHex(mac), b32(mac));
  }

  private static byte[] derivedKey(Inputs inputs, String direction) throws Exception {
    byte[] transcript = ControlAuth.transcript(ControlAuth.Label.PEER_PROOF, inputs.transcript());
    byte[] digest = MessageDigest.getInstance("SHA-256").digest(transcript);
    return ControlAuth.hmac(inputs.rolePsk(), lp(ascii(KDF_DOMAIN), digest, ascii(direction)));
  }

  private static byte[] frameInput(Inputs inputs, String direction, long sequence,
      String messageKind, String routeId, String sourceInstallId, String sourceConnectionId,
      String recipientConnectionId, String challengeExpiresAt, byte[] payload, String profile) {
    if (sequence < 0) throw new IllegalArgumentException("invalid sequence");
    return lp(ascii(FRAME_DOMAIN), ascii(ControlAuth.DOMAIN), ascii("AUTHENTICATED_FRAME"),
        ascii(profile), ascii(inputs.role().name()), ascii(inputs.peerInstallId()),
        ascii(HUB_INSTALL_ID), ascii(inputs.connectionId()), ascii(inputs.challengeId()),
        ascii(direction), ByteBuffer.allocate(8).putLong(sequence).array(), ascii(messageKind),
        ascii(routeId), ascii(sourceInstallId), ascii(sourceConnectionId),
        ascii(recipientConnectionId), ascii(challengeExpiresAt), payload);
  }

  private static byte[] sessionOpenPayload(String connectionId) {
    ControlAuth.requireUuid(connectionId);
    return ascii("{\"security\":\"luosandbox.control-auth.v1\",\"kind\":\"SESSION_OPEN\","
        + "\"connectionId\":\"" + connectionId + "\"}");
  }

  private static byte[] noticePayload() {
    return ascii("{\"security\":\"luosandbox.control-auth.v1\",\"kind\":\"BODY_BIND_NOTICE\","
        + "\"serverBootId\":\"" + SERVER_BOOT_ID + "\",\"gameConnectionId\":\""
        + GAME_CONNECTION_ID + "\",\"bodyId\":\"" + BODY_ID + "\",\"gameNonce\":\""
        + b32(range(16)) + "\"}");
  }

  private static byte[] candidatePayload() {
    return ascii("{\"security\":\"luosandbox.control-auth.v1\",\"kind\":\"BODY_BOUND\","
        + "\"serverBootId\":\"" + SERVER_BOOT_ID + "\",\"gameConnectionId\":\""
        + GAME_CONNECTION_ID + "\",\"bodyId\":\"" + BODY_ID + "\"}");
  }

  private static byte[] lp(byte[]... fields) {
    int size = 0;
    for (byte[] field : fields) size += 4 + field.length;
    ByteBuffer buffer = ByteBuffer.allocate(size);
    for (byte[] field : fields) buffer.putInt(field.length).put(field);
    return buffer.array();
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

  private static byte[] ascii(String value) {
    byte[] encoded = value.getBytes(StandardCharsets.US_ASCII);
    if (!new String(encoded, StandardCharsets.US_ASCII).equals(value)) {
      throw new IllegalArgumentException("non-ASCII field");
    }
    return encoded;
  }

  private static byte[] range(int start) {
    byte[] value = new byte[32];
    for (int index = 0; index < value.length; index++) value[index] = (byte) (start + index);
    return value;
  }

  private static String b32(byte[] value) {
    return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
  }

  private static void validateBudget(byte[] payload, byte[] encodedMessage) {
    if (payload.length > 2048) throw new IllegalArgumentException("payload too large");
    if (encodedMessage.length > 4096) throw new IllegalArgumentException("message too large");
  }

  private static String sha256(byte[] value) throws Exception {
    return HEX.formatHex(MessageDigest.getInstance("SHA-256").digest(value));
  }

  private static byte[] classBytes() throws Exception {
    try (InputStream input = ControlAuth.class.getResourceAsStream("ControlAuth.class")) {
      assertNotNull(input, "ControlAuth class artifact must be loadable");
      return input.readAllBytes();
    }
  }

  private static void export(Path requested, List<Vector> vectors) throws Exception {
    Path path = requested.toAbsolutePath().normalize();
    Path approved = Path.of("..", "..", ".gradle-user-home", "interop").toAbsolutePath().normalize();
    assertTrue(path.startsWith(approved), "vector export must remain in ignored Gradle interop home");
    Files.createDirectories(path.getParent());
    byte[] boundaryPayload = new byte[2048];
    for (int index = 0; index < boundaryPayload.length; index++) boundaryPayload[index] = (byte) (index % 251);
    byte[] boundaryMessage = new byte[4096];
    java.util.Arrays.fill(boundaryMessage, (byte) 'x');
    try (JsonGenerator json = new JsonFactory().createGenerator(Files.newOutputStream(path))) {
      json.writeStartObject();
      json.writeStringField("schema", SCHEMA);
      json.writeStringField("status", "DRAFT_NON_NORMATIVE_TEST_ONLY");
      json.writeBooleanField("dummyCredentials", true);
      json.writeArrayFieldStart("limitations");
      json.writeString("cryptographic_composition_only_not_deployable_protocol_implementation");
      json.writeString("does_not_test_complete_wire_parser_bounded_queue_or_runtime_currentness");
      json.writeString("draft_change_invalidates_vectors");
      json.writeEndArray();
      json.writeArrayFieldStart("vectors");
      for (Vector vector : vectors) writeVector(json, vector);
      json.writeEndArray();
      json.writeObjectFieldStart("budgetFixtures");
      writeBudget(json, "rawPayload2048", boundaryPayload);
      writeBudget(json, "encodedMessage4096", boundaryMessage);
      json.writeStringField("note", "Independent size-boundary fixtures; not a general frame codec or wire-parser claim");
      json.writeEndObject();
      json.writeObjectFieldStart("javaArtifactIdentity");
      json.writeStringField("controlAuthClassResource", String.valueOf(ControlAuth.class.getResource("ControlAuth.class")));
      json.writeStringField("controlAuthClassSha256", sha256(classBytes()));
      json.writeEndObject();
      json.writeObjectFieldStart("invocation");
      json.writeStringField("testClass", PostAuthV2VectorTest.class.getName());
      json.writeBooleanField("forcedRerunRequired", true);
      json.writeEndObject();
      json.writeEndObject();
    }
  }

  private static void writeBudget(JsonGenerator json, String name, byte[] value) throws Exception {
    json.writeObjectFieldStart(name);
    json.writeNumberField("length", value.length);
    json.writeStringField("sha256", sha256(value));
    json.writeEndObject();
  }

  private static void writeVector(JsonGenerator json, Vector value) throws Exception {
    json.writeStartObject();
    json.writeStringField("name", value.name());
    json.writeStringField("peerRole", value.peerRole());
    json.writeStringField("direction", value.direction());
    json.writeStringField("dummyRolePskHex", value.dummyRolePskHex());
    writeInputs(json, value.inputs());
    json.writeStringField("authTranscriptHex", value.authTranscriptHex());
    json.writeStringField("authDigestHex", value.authDigestHex());
    json.writeStringField("keyDerivationInputHex", value.keyDerivationInputHex());
    json.writeStringField("derivedKeyHex", value.derivedKeyHex());
    json.writeNumberField("sequence", value.sequence());
    json.writeStringField("messageKind", value.messageKind());
    json.writeStringField("routeId", value.routeId());
    json.writeStringField("sourceInstallId", value.sourceInstallId());
    json.writeStringField("sourceConnectionId", value.sourceConnectionId());
    json.writeStringField("recipientConnectionId", value.recipientConnectionId());
    json.writeStringField("challengeExpiresAt", value.challengeExpiresAt());
    json.writeStringField("rawPayloadHex", value.rawPayloadHex());
    json.writeStringField("frameMacInputHex", value.frameMacInputHex());
    json.writeStringField("macHex", value.macHex());
    json.writeStringField("b32Proof", value.b32Proof());
    json.writeEndObject();
  }

  private static void writeInputs(JsonGenerator json, Inputs value) throws Exception {
    json.writeObjectFieldStart("inputs");
    json.writeStringField("peerRole", value.role().name());
    json.writeStringField("security", ControlAuth.DOMAIN);
    json.writeStringField("hubInstallId", HUB_INSTALL_ID);
    json.writeStringField("protocol", PROTOCOL);
    json.writeStringField("modHashHex", HEX.formatHex(range(160)));
    json.writeStringField("hubNonceHex", HEX.formatHex(range(192)));
    json.writeStringField("issuedAt", ControlAuth.formatUtc(ISSUED_AT));
    json.writeStringField("expiresAt", ControlAuth.formatUtc(EXPIRES_AT));
    json.writeStringField("configHashHex", HEX.formatHex(range(224)));
    json.writeStringField("bindingHashHex", HEX.formatHex(range(64)));
    json.writeStringField("peerInstallId", value.peerInstallId());
    json.writeStringField("keyId", value.keyId());
    json.writeStringField("rolePskHex", HEX.formatHex(value.rolePsk()));
    json.writeStringField("peerNonceHex", HEX.formatHex(value.peerNonce()));
    json.writeStringField("challengeId", value.challengeId());
    json.writeStringField("connectionId", value.connectionId());
    json.writeEndObject();
  }

  private record Inputs(ControlAuth.Role role, String peerInstallId, String keyId, byte[] rolePsk,
      byte[] peerNonce, String challengeId, String connectionId) {
    ControlAuth.Transcript transcript() {
      return new ControlAuth.Transcript(role, peerInstallId, HUB_INSTALL_ID, keyId, PROTOCOL,
          range(160), peerNonce, range(192), challengeId, ISSUED_AT, EXPIRES_AT, range(224), range(64));
    }

    Inputs withConnectionId(String value) {
      return new Inputs(role, peerInstallId, keyId, rolePsk, peerNonce, challengeId, value);
    }
  }

  private record Vector(String name, String peerRole, String direction, String dummyRolePskHex,
      Inputs inputs, String authTranscriptHex, String authDigestHex, String keyDerivationInputHex,
      String derivedKeyHex, long sequence, String messageKind, String routeId,
      String sourceInstallId, String sourceConnectionId, String recipientConnectionId,
      String challengeExpiresAt, String rawPayloadHex, String frameMacInputHex, String macHex,
      String b32Proof) {}
}
