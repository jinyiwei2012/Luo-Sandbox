package io.github.jinyiwei2012.luosandbox.security;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import io.github.jinyiwei2012.luosandbox.wire.StrictJson;
import org.junit.jupiter.api.Test;

class PostAuthFrameCodecTest {
  private static final String CONNECTION = "20000000-0000-4000-8000-000000000001";
  private static final String CHALLENGE = "10000000-0000-4000-8000-000000000001";
  private static final String ROUTE = "30000000-0000-4000-8000-000000000001";
  private static final String SOURCE_CONNECTION = "40000000-0000-4000-8000-000000000001";
  private static final String BOOT = "50000000-0000-4000-8000-000000000001";
  private static final String GAME = "60000000-0000-4000-8000-000000000001";
  private static final String BODY = "70000000-0000-4000-8000-000000000001";

  @Test
  void reorderedOuterKeysAndLegalWhitespacePreserveAllFrameSemantics() throws Exception {
    for (var role : List.of(ControlAuth.Role.CLIENT_CONTROLLER, ControlAuth.Role.SERVER_AUTHORITY)) {
      try (var keys = keys(role)) {
        for (var direction : PostAuthFrameCodec.Direction.values()) {
          var current = context(role, CONNECTION);
          assertOuterVariants(frame(CONNECTION, 1, "HEARTBEAT", "", "", "", "", "",
              "{}".getBytes(StandardCharsets.US_ASCII)), current, direction, keys);
        }
        assertOuterVariants(frame(CONNECTION, 0, "SESSION_OPEN", "", "", "", "", "",
            PostAuthFrameCodec.sessionOpenPayload(CONNECTION).getBytes(StandardCharsets.US_ASCII)),
            context(role, null), PostAuthFrameCodec.Direction.HUB_TO_PEER, keys);
        if (role == ControlAuth.Role.CLIENT_CONTROLLER) {
          assertOuterVariants(frame(CONNECTION, 1, "BODY_BIND_NOTICE", "", "", "", "", "", notice()),
              context(role, CONNECTION), PostAuthFrameCodec.Direction.PEER_TO_HUB, keys);
        } else {
          assertOuterVariants(frame(CONNECTION, 2, "BODY_BIND_ROUTE", ROUTE, "client-1",
              SOURCE_CONNECTION, CONNECTION, "", notice()), context(role, CONNECTION),
              PostAuthFrameCodec.Direction.HUB_TO_PEER, keys);
          assertOuterVariants(frame(CONNECTION, 1, "BODY_BOUND_CANDIDATE", ROUTE, "", "", CONNECTION,
              "2026-10-23T00:00:05.000Z", candidate()), context(role, CONNECTION),
              PostAuthFrameCodec.Direction.PEER_TO_HUB, keys);
        }
      }
    }
  }

  @Test
  void exactKeySetStillRejectsEveryOmissionExtraReplacementAndRawDuplicate() throws Exception {
    var context = context(ControlAuth.Role.CLIENT_CONTROLLER, CONNECTION);
    var direction = PostAuthFrameCodec.Direction.PEER_TO_HUB;
    try (var keys = keys(ControlAuth.Role.CLIENT_CONTROLLER)) {
      byte[] encoded = PostAuthFrameCodec.encode(frame(CONNECTION, 0, "HEARTBEAT", "", "", "", "", "",
          "{}".getBytes(StandardCharsets.US_ASCII)), context, direction, keys);
      Map<String, Object> raw = StrictJson.decodeObject(encoded);
      assertEquals(14, raw.size());
      for (String key : raw.keySet()) {
        var omitted = new LinkedHashMap<>(raw);
        omitted.remove(key);
        reject(outerJson(omitted, new ArrayList<>(omitted.keySet())), context, keys);
        omitted.put("unknown", raw.get(key));
        assertEquals(14, omitted.size());
        reject(outerJson(omitted, new ArrayList<>(omitted.keySet())), context, keys);
        String original = outerJson(raw, new ArrayList<>(raw.keySet()));
        // Deliberately construct duplicate JSON text, never a map that collapses duplicates.
        reject(original.substring(0, original.length() - 1) + "," + member(key, raw.get(key)) + "}",
            context, keys);
        Object conflicting = key.equals("sequence") ? 1L : "conflicting";
        reject(original.substring(0, original.length() - 1) + "," + member(key, conflicting) + "}",
            context, keys);
      }
      var extra = new LinkedHashMap<>(raw);
      extra.put("unknown", "x");
      reject(outerJson(extra, new ArrayList<>(extra.keySet())), context, keys);
      var tampered = new LinkedHashMap<>(raw);
      tampered.put("sequence", 1L);
      reject(outerJson(tampered, new ArrayList<>(tampered.keySet())), context, keys);
    }
  }

  private static void assertOuterVariants(PostAuthFrameCodec.Frame frame,
      PostAuthFrameCodec.Context context, PostAuthFrameCodec.Direction direction,
      PostAuthSessionKeys keys) throws Exception {
    byte[] encoded = PostAuthFrameCodec.encode(frame, context, direction, keys);
    var baseline = PostAuthFrameCodec.decode(encoded, context, direction, keys);
    Map<String, Object> raw = StrictJson.decodeObject(encoded);
    List<String> reverse = new ArrayList<>(raw.keySet());
    Collections.reverse(reverse);
    List<String> macFirst = new ArrayList<>(raw.keySet());
    macFirst.remove("mac");
    macFirst.remove("sequence");
    macFirst.add(0, "mac");
    macFirst.add("sequence");
    String reverseJson = outerJson(raw, reverse);
    String macFirstJson = outerJson(raw, macFirst);
    List<String> spaced = new ArrayList<>();
    for (String key : macFirst) spaced.add(member(key, raw.get(key)).replaceFirst(":", " \t: "));
    String whitespace = " \r\n\t{\n  " + String.join(" ,\n\t", spaced) + "\n}\r\n ";
    for (String variant : List.of(reverseJson, macFirstJson, whitespace)) {
      var decoded = PostAuthFrameCodec.decode(variant.getBytes(StandardCharsets.UTF_8), context, direction, keys);
      assertEquals(baseline.connectionId(), decoded.connectionId());
      assertEquals(baseline.challengeId(), decoded.challengeId());
      assertEquals(baseline.sequence(), decoded.sequence());
      assertEquals(baseline.messageKind(), decoded.messageKind());
      assertEquals(baseline.routeId(), decoded.routeId());
      assertEquals(baseline.sourceInstallId(), decoded.sourceInstallId());
      assertEquals(baseline.sourceConnectionId(), decoded.sourceConnectionId());
      assertEquals(baseline.recipientConnectionId(), decoded.recipientConnectionId());
      assertEquals(baseline.challengeExpiresAt(), decoded.challengeExpiresAt());
      assertEquals(baseline.mac(), decoded.mac());
      assertArrayEquals(baseline.payload(), decoded.payload());
      assertArrayEquals(PostAuthFrameCodec.macInput(baseline, context, direction),
          PostAuthFrameCodec.macInput(decoded, context, direction));
      var unsigned = frame(decoded.connectionId(), decoded.sequence(), decoded.messageKind(),
          decoded.routeId(), decoded.sourceInstallId(), decoded.sourceConnectionId(),
          decoded.recipientConnectionId(), decoded.challengeExpiresAt(), decoded.payload());
      assertArrayEquals(encoded, PostAuthFrameCodec.encode(unsigned, context, direction, keys));
    }
  }

  private static String outerJson(Map<String, Object> raw, List<String> order) {
    List<String> members = new ArrayList<>();
    for (String key : order) members.add(member(key, raw.get(key)));
    return "{" + String.join(",", members) + "}";
  }

  private static String member(String key, Object value) {
    // Genuine encoded fixture fields contain no escaping characters; payload is base64url.
    return "\"" + key + "\":" + (value instanceof Number ? value : "\"" + value + "\"");
  }

  @Test
  void exactSessionOpenAndHeartbeatsRoundTripWithIndependentDirections() {
    try (PostAuthSessionKeys keys = keys(ControlAuth.Role.CLIENT_CONTROLLER)) {
      PostAuthFrameCodec.Context bootstrap = context(ControlAuth.Role.CLIENT_CONTROLLER, null);
      var open = frame(CONNECTION, 0, "SESSION_OPEN", "", "", "", "", "",
          PostAuthFrameCodec.sessionOpenPayload(CONNECTION).getBytes(StandardCharsets.US_ASCII));
      byte[] encoded = PostAuthFrameCodec.encode(open, bootstrap,
          PostAuthFrameCodec.Direction.HUB_TO_PEER, keys);
      assertEquals(open.payload().length, PostAuthFrameCodec.decode(encoded, bootstrap,
          PostAuthFrameCodec.Direction.HUB_TO_PEER, keys).payload().length);

      PostAuthFrameCodec.Context current = context(ControlAuth.Role.CLIENT_CONTROLLER, CONNECTION);
      for (PostAuthFrameCodec.Direction direction : PostAuthFrameCodec.Direction.values()) {
        var heartbeat = frame(CONNECTION, direction == PostAuthFrameCodec.Direction.PEER_TO_HUB ? 0 : 1,
            "HEARTBEAT", "", "", "", "", "", "{}".getBytes(StandardCharsets.US_ASCII));
        assertArrayEquals(heartbeat.payload(), PostAuthFrameCodec.decode(
            PostAuthFrameCodec.encode(heartbeat, current, direction, keys), current, direction, keys).payload());
      }
    }
  }

  @Test
  void allBusinessKindsRoundTripOnlyForFrozenRoleDirectionMetadata() {
    byte[] notice = notice();
    try (PostAuthSessionKeys clientKeys = keys(ControlAuth.Role.CLIENT_CONTROLLER)) {
      var noticeFrame = frame(CONNECTION, 1, "BODY_BIND_NOTICE", "", "", "", "", "", notice);
      assertDoesNotThrow(() -> PostAuthFrameCodec.decode(PostAuthFrameCodec.encode(noticeFrame,
          context(ControlAuth.Role.CLIENT_CONTROLLER, CONNECTION), PostAuthFrameCodec.Direction.PEER_TO_HUB,
          clientKeys), context(ControlAuth.Role.CLIENT_CONTROLLER, CONNECTION),
          PostAuthFrameCodec.Direction.PEER_TO_HUB, clientKeys));
    }
    try (PostAuthSessionKeys serverKeys = keys(ControlAuth.Role.SERVER_AUTHORITY)) {
      PostAuthFrameCodec.Context server = context(ControlAuth.Role.SERVER_AUTHORITY, CONNECTION);
      var route = frame(CONNECTION, 2, "BODY_BIND_ROUTE", ROUTE, "client-1", SOURCE_CONNECTION,
          CONNECTION, "", notice);
      assertDoesNotThrow(() -> PostAuthFrameCodec.decode(PostAuthFrameCodec.encode(route, server,
          PostAuthFrameCodec.Direction.HUB_TO_PEER, serverKeys), server,
          PostAuthFrameCodec.Direction.HUB_TO_PEER, serverKeys));
      var candidate = frame(CONNECTION, 1, "BODY_BOUND_CANDIDATE", ROUTE, "", "", CONNECTION,
          "2026-10-23T00:00:05.000Z", candidate());
      assertDoesNotThrow(() -> PostAuthFrameCodec.decode(PostAuthFrameCodec.encode(candidate, server,
          PostAuthFrameCodec.Direction.PEER_TO_HUB, serverKeys), server,
          PostAuthFrameCodec.Direction.PEER_TO_HUB, serverKeys));
      assertThrows(PostAuthFrameCodec.CodecException.class, () -> PostAuthFrameCodec.encode(route, server,
          PostAuthFrameCodec.Direction.PEER_TO_HUB, serverKeys));
    }
  }

  @Test
  void macInputHasExactEighteenFieldsAndTamperingEveryBoundFieldChangesBytes() {
    var context = context(ControlAuth.Role.SERVER_AUTHORITY, CONNECTION);
    var original = frame(CONNECTION, 1, "BODY_BOUND_CANDIDATE", ROUTE, "", "", CONNECTION,
        "2026-10-23T00:00:05.000Z", candidate());
    byte[] baseline = PostAuthFrameCodec.macInput(original, context, PostAuthFrameCodec.Direction.PEER_TO_HUB);
    assertEquals(18, lpCount(baseline));
    assertArrayEquals(ByteBuffer.allocate(8).putLong(1).array(), lpField(baseline, 10));
    String otherConnection = "20000000-0000-4000-8000-000000000002";
    byte[][] variants = {
        PostAuthFrameCodec.macInput(frame(otherConnection, 1, original.messageKind(), ROUTE,
            "", "", otherConnection, original.challengeExpiresAt(), original.payload()),
            context(ControlAuth.Role.SERVER_AUTHORITY, otherConnection), PostAuthFrameCodec.Direction.PEER_TO_HUB),
        PostAuthFrameCodec.macInput(original,
            new PostAuthFrameCodec.Context(changed(PostAuthSessionKeysTest.transcript(ControlAuth.Role.SERVER_AUTHORITY),
                ControlAuth.Role.SERVER_AUTHORITY, "other", "hub", "key",
                PostAuthSessionKeysTest.transcript(ControlAuth.Role.SERVER_AUTHORITY).modHash(),
                PostAuthSessionKeysTest.transcript(ControlAuth.Role.SERVER_AUTHORITY).peerNonce(),
                PostAuthSessionKeysTest.transcript(ControlAuth.Role.SERVER_AUTHORITY).hubNonce(), CHALLENGE,
                PostAuthSessionKeysTest.transcript(ControlAuth.Role.SERVER_AUTHORITY).issuedAt(),
                PostAuthSessionKeysTest.transcript(ControlAuth.Role.SERVER_AUTHORITY).expiresAt(),
                PostAuthSessionKeysTest.transcript(ControlAuth.Role.SERVER_AUTHORITY).configHash(),
                PostAuthSessionKeysTest.transcript(ControlAuth.Role.SERVER_AUTHORITY).bindingHash()), CONNECTION),
            PostAuthFrameCodec.Direction.PEER_TO_HUB),
        PostAuthFrameCodec.macInput(frame(CONNECTION, 2, original.messageKind(), ROUTE, "", "", CONNECTION,
            original.challengeExpiresAt(), original.payload()), context, PostAuthFrameCodec.Direction.PEER_TO_HUB),
        PostAuthFrameCodec.macInput(frame(CONNECTION, 1, original.messageKind(),
            "30000000-0000-4000-8000-000000000002", "", "", CONNECTION,
            original.challengeExpiresAt(), original.payload()), context, PostAuthFrameCodec.Direction.PEER_TO_HUB),
        PostAuthFrameCodec.macInput(frame(CONNECTION, 1, original.messageKind(), ROUTE, "", "", CONNECTION,
            "2026-10-23T00:00:04.000Z", original.payload()), context,
            PostAuthFrameCodec.Direction.PEER_TO_HUB),
        PostAuthFrameCodec.macInput(frame(CONNECTION, 1, original.messageKind(), ROUTE, "", "", CONNECTION,
            original.challengeExpiresAt(), "x".getBytes(StandardCharsets.US_ASCII)), context,
            PostAuthFrameCodec.Direction.PEER_TO_HUB),
    };
    for (byte[] changed : variants) assertFalse(Arrays.equals(baseline, changed));
  }

  @Test
  void wrongKeyDirectionMacAndCanonicalBase64AreRejected() {
    var context = context(ControlAuth.Role.CLIENT_CONTROLLER, CONNECTION);
    var heartbeat = frame(CONNECTION, 0, "HEARTBEAT", "", "", "", "", "",
        "{}".getBytes(StandardCharsets.US_ASCII));
    try (PostAuthSessionKeys keys = keys(ControlAuth.Role.CLIENT_CONTROLLER);
         PostAuthSessionKeys wrong = PostAuthSessionKeys.derive(PostAuthSessionKeysTest.range(1),
             PostAuthSessionKeysTest.transcript(ControlAuth.Role.CLIENT_CONTROLLER))) {
      byte[] encoded = PostAuthFrameCodec.encode(heartbeat, context,
          PostAuthFrameCodec.Direction.PEER_TO_HUB, keys);
      assertThrows(PostAuthFrameCodec.CodecException.class, () -> PostAuthFrameCodec.decode(encoded,
          context, PostAuthFrameCodec.Direction.PEER_TO_HUB, wrong));
      assertThrows(PostAuthFrameCodec.CodecException.class, () -> PostAuthFrameCodec.decode(encoded,
          context, PostAuthFrameCodec.Direction.HUB_TO_PEER, keys));
      String text = new String(encoded, StandardCharsets.UTF_8);
      reject(text.replace("\"payload\":\"e30\"", "\"payload\":\"e30=\""), context, keys);
      reject(text.replaceFirst("\"mac\":\"([A-Za-z0-9_-]{43})\"", "\"mac\":\"$1=\""), context, keys);
    }
  }

  @Test
  void fullHandshakeTranscriptBindingRejectsEveryContextMutationOnEncodeAndDecode() {
    ControlAuth.Transcript base = PostAuthSessionKeysTest.transcript(ControlAuth.Role.CLIENT_CONTROLLER);
    var current = new PostAuthFrameCodec.Context(base, CONNECTION);
    var heartbeat = frame(CONNECTION, 0, "HEARTBEAT", "", "", "", "", "",
        "{}".getBytes(StandardCharsets.US_ASCII));
    try (PostAuthSessionKeys keys = PostAuthSessionKeys.derive(PostAuthSessionKeysTest.range(0), base)) {
      byte[] encoded = PostAuthFrameCodec.encode(heartbeat, current,
          PostAuthFrameCodec.Direction.PEER_TO_HUB, keys);
      for (ControlAuth.Transcript changed : transcriptMutations(base)) {
        var wrong = new PostAuthFrameCodec.Context(changed, CONNECTION);
        assertThrows(PostAuthFrameCodec.CodecException.class, () -> PostAuthFrameCodec.encode(
            heartbeat, wrong, PostAuthFrameCodec.Direction.PEER_TO_HUB, keys));
        assertThrows(PostAuthFrameCodec.CodecException.class, () -> PostAuthFrameCodec.decode(
            encoded, wrong, PostAuthFrameCodec.Direction.PEER_TO_HUB, keys));
      }
      ControlAuth.Transcript equal = new ControlAuth.Transcript(base.peerRole(), base.peerInstallId(),
          base.hubInstallId(), base.keyId(), base.protocol(), base.modHash(), base.peerNonce(),
          base.hubNonce(), base.challengeId(), base.issuedAt(), base.expiresAt(),
          base.configHash(), base.bindingHash());
      assertDoesNotThrow(() -> PostAuthFrameCodec.decode(encoded,
          new PostAuthFrameCodec.Context(equal, CONNECTION),
          PostAuthFrameCodec.Direction.PEER_TO_HUB, keys));
    }
  }

  @Test
  void routeAndCandidateRecipientMustEqualOuterServerConnection() {
    String other = "20000000-0000-4000-8000-000000000002";
    var server = context(ControlAuth.Role.SERVER_AUTHORITY, CONNECTION);
    try (PostAuthSessionKeys keys = keys(ControlAuth.Role.SERVER_AUTHORITY)) {
      var route = frame(CONNECTION, 2, "BODY_BIND_ROUTE", ROUTE, "client-1", SOURCE_CONNECTION,
          other, "", notice());
      var candidate = frame(CONNECTION, 1, "BODY_BOUND_CANDIDATE", ROUTE, "", "", other,
          "2026-10-23T00:00:05.000Z", candidate());
      assertThrows(PostAuthFrameCodec.CodecException.class, () -> PostAuthFrameCodec.encode(route,
          server, PostAuthFrameCodec.Direction.HUB_TO_PEER, keys));
      assertThrows(PostAuthFrameCodec.CodecException.class, () -> PostAuthFrameCodec.encode(candidate,
          server, PostAuthFrameCodec.Direction.PEER_TO_HUB, keys));
    }
  }

  @Test
  void strictOuterJsonSequenceAndSizeLimitsAreIndependent() {
    var context = context(ControlAuth.Role.CLIENT_CONTROLLER, CONNECTION);
    try (PostAuthSessionKeys keys = keys(ControlAuth.Role.CLIENT_CONTROLLER)) {
      var heartbeat = frame(CONNECTION, 0, "HEARTBEAT", "", "", "", "", "",
          "{}".getBytes(StandardCharsets.US_ASCII));
      String text = new String(PostAuthFrameCodec.encode(heartbeat, context,
          PostAuthFrameCodec.Direction.PEER_TO_HUB, keys), StandardCharsets.UTF_8);
      for (String invalid : new String[] {
          "\ufeff" + text, text.replace("{", "{\"unknown\":\"x\","),
          text.replace("\"kind\":", "\"kind\":\"x\",\"kind\":"),
          text.replace("\"sequence\":0", "\"sequence\":true"),
          text.replace("\"sequence\":0", "\"sequence\":0.0"),
          text.replace("\"sequence\":0", "\"sequence\":-1"),
          text + "x"}) reject(invalid, context, keys);
      assertThrows(PostAuthFrameCodec.CodecException.class, () -> PostAuthFrameCodec.encode(
          frame(CONNECTION, 0, "HEARTBEAT", "", "", "", "", "", new byte[2049]),
          context, PostAuthFrameCodec.Direction.PEER_TO_HUB, keys));
      byte[] maximum = new byte[2048];
      assertThrows(PostAuthFrameCodec.CodecException.class, () -> PostAuthFrameCodec.encode(
          frame(CONNECTION, 0, "HEARTBEAT", "", "", "", "", "", maximum),
          context, PostAuthFrameCodec.Direction.PEER_TO_HUB, keys));
      assertThrows(PostAuthFrameCodec.CodecException.class, () -> PostAuthFrameCodec.decode(
          new byte[4097], context, PostAuthFrameCodec.Direction.PEER_TO_HUB, keys));
    }
  }

  @Test
  void nestedPayloadSchemasAndSessionOpenBytesAreExact() {
    var client = context(ControlAuth.Role.CLIENT_CONTROLLER, CONNECTION);
    try (PostAuthSessionKeys keys = keys(ControlAuth.Role.CLIENT_CONTROLLER)) {
      var badHeartbeat = frame(CONNECTION, 0, "HEARTBEAT", "", "", "", "", "",
          "{ }".getBytes(StandardCharsets.US_ASCII));
      assertThrows(PostAuthFrameCodec.CodecException.class, () -> PostAuthFrameCodec.encode(
          badHeartbeat, client, PostAuthFrameCodec.Direction.PEER_TO_HUB, keys));
      var badNotice = frame(CONNECTION, 1, "BODY_BIND_NOTICE", "", "", "", "", "",
          new String(notice(), StandardCharsets.US_ASCII).replace("}", ",\"extra\":\"x\"}")
              .getBytes(StandardCharsets.US_ASCII));
      assertThrows(PostAuthFrameCodec.CodecException.class, () -> PostAuthFrameCodec.encode(
          badNotice, client, PostAuthFrameCodec.Direction.PEER_TO_HUB, keys));
      var open = frame(CONNECTION, 0, "SESSION_OPEN", "", "", "", "", "",
          (PostAuthFrameCodec.sessionOpenPayload(CONNECTION) + " ").getBytes(StandardCharsets.US_ASCII));
      assertThrows(PostAuthFrameCodec.CodecException.class, () -> PostAuthFrameCodec.encode(open,
          context(ControlAuth.Role.CLIENT_CONTROLLER, null), PostAuthFrameCodec.Direction.HUB_TO_PEER, keys));
    }
  }

  private static PostAuthSessionKeys keys(ControlAuth.Role role) {
    return PostAuthSessionKeys.derive(PostAuthSessionKeysTest.range(
        role == ControlAuth.Role.CLIENT_CONTROLLER ? 0 : 96), PostAuthSessionKeysTest.transcript(role));
  }

  private static PostAuthFrameCodec.Context context(ControlAuth.Role role, String connection) {
    return new PostAuthFrameCodec.Context(PostAuthSessionKeysTest.transcript(role), connection);
  }

  private static ControlAuth.Transcript[] transcriptMutations(ControlAuth.Transcript base) {
    return new ControlAuth.Transcript[] {
        changed(base, base.peerRole() == ControlAuth.Role.CLIENT_CONTROLLER
            ? ControlAuth.Role.SERVER_AUTHORITY : ControlAuth.Role.CLIENT_CONTROLLER,
            base.peerInstallId(), base.hubInstallId(), base.keyId(), base.modHash(), base.peerNonce(),
            base.hubNonce(), base.challengeId(), base.issuedAt(), base.expiresAt(), base.configHash(), base.bindingHash()),
        changed(base, base.peerRole(), "other", base.hubInstallId(), base.keyId(), base.modHash(), base.peerNonce(),
            base.hubNonce(), base.challengeId(), base.issuedAt(), base.expiresAt(), base.configHash(), base.bindingHash()),
        changed(base, base.peerRole(), base.peerInstallId(), "other", base.keyId(), base.modHash(), base.peerNonce(),
            base.hubNonce(), base.challengeId(), base.issuedAt(), base.expiresAt(), base.configHash(), base.bindingHash()),
        changed(base, base.peerRole(), base.peerInstallId(), base.hubInstallId(), "other", base.modHash(), base.peerNonce(),
            base.hubNonce(), base.challengeId(), base.issuedAt(), base.expiresAt(), base.configHash(), base.bindingHash()),
        changed(base, base.peerRole(), base.peerInstallId(), base.hubInstallId(), base.keyId(), flip(base.modHash()), base.peerNonce(),
            base.hubNonce(), base.challengeId(), base.issuedAt(), base.expiresAt(), base.configHash(), base.bindingHash()),
        changed(base, base.peerRole(), base.peerInstallId(), base.hubInstallId(), base.keyId(), base.modHash(), flip(base.peerNonce()),
            base.hubNonce(), base.challengeId(), base.issuedAt(), base.expiresAt(), base.configHash(), base.bindingHash()),
        changed(base, base.peerRole(), base.peerInstallId(), base.hubInstallId(), base.keyId(), base.modHash(), base.peerNonce(),
            flip(base.hubNonce()), base.challengeId(), base.issuedAt(), base.expiresAt(), base.configHash(), base.bindingHash()),
        changed(base, base.peerRole(), base.peerInstallId(), base.hubInstallId(), base.keyId(), base.modHash(), base.peerNonce(),
            base.hubNonce(), "10000000-0000-4000-8000-000000000002", base.issuedAt(), base.expiresAt(), base.configHash(), base.bindingHash()),
        changed(base, base.peerRole(), base.peerInstallId(), base.hubInstallId(), base.keyId(), base.modHash(), base.peerNonce(),
            base.hubNonce(), base.challengeId(), base.issuedAt().plusMillis(1), base.expiresAt(), base.configHash(), base.bindingHash()),
        changed(base, base.peerRole(), base.peerInstallId(), base.hubInstallId(), base.keyId(), base.modHash(), base.peerNonce(),
            base.hubNonce(), base.challengeId(), base.issuedAt(), base.expiresAt().plusMillis(1), base.configHash(), base.bindingHash()),
        changed(base, base.peerRole(), base.peerInstallId(), base.hubInstallId(), base.keyId(), base.modHash(), base.peerNonce(),
            base.hubNonce(), base.challengeId(), base.issuedAt(), base.expiresAt(), flip(base.configHash()), base.bindingHash()),
        changed(base, base.peerRole(), base.peerInstallId(), base.hubInstallId(), base.keyId(), base.modHash(), base.peerNonce(),
            base.hubNonce(), base.challengeId(), base.issuedAt(), base.expiresAt(), base.configHash(), flip(base.bindingHash())),
    };
  }

  private static ControlAuth.Transcript changed(ControlAuth.Transcript base, ControlAuth.Role role,
      String peer, String hub, String keyId, byte[] mod, byte[] peerNonce, byte[] hubNonce,
      String challenge, Instant issued, Instant expires, byte[] config, byte[] binding) {
    return new ControlAuth.Transcript(role, peer, hub, keyId, base.protocol(), mod, peerNonce,
        hubNonce, challenge, issued, expires, config, binding);
  }

  private static byte[] flip(byte[] value) { byte[] copy = value.clone(); copy[0] ^= 1; return copy; }

  private static PostAuthFrameCodec.Frame frame(String connection, long sequence, String kind,
      String route, String sourceInstall, String sourceConnection, String recipient,
      String expires, byte[] payload) {
    return new PostAuthFrameCodec.Frame(connection, CHALLENGE, sequence, kind, route,
        sourceInstall, sourceConnection, recipient, expires, payload, "");
  }

  private static byte[] notice() {
    String nonce = Base64.getUrlEncoder().withoutPadding().encodeToString(PostAuthSessionKeysTest.range(16));
    return ("{\"security\":\"luosandbox.control-auth.v1\",\"kind\":\"BODY_BIND_NOTICE\","
        + "\"serverBootId\":\"" + BOOT + "\",\"gameConnectionId\":\"" + GAME
        + "\",\"bodyId\":\"" + BODY + "\",\"gameNonce\":\"" + nonce + "\"}")
        .getBytes(StandardCharsets.US_ASCII);
  }

  private static byte[] candidate() {
    return ("{\"security\":\"luosandbox.control-auth.v1\",\"kind\":\"BODY_BOUND\","
        + "\"serverBootId\":\"" + BOOT + "\",\"gameConnectionId\":\"" + GAME
        + "\",\"bodyId\":\"" + BODY + "\"}").getBytes(StandardCharsets.US_ASCII);
  }

  private static void reject(String value, PostAuthFrameCodec.Context context, PostAuthSessionKeys keys) {
    assertThrows(PostAuthFrameCodec.CodecException.class, () -> PostAuthFrameCodec.decode(
        value.getBytes(StandardCharsets.UTF_8), context, PostAuthFrameCodec.Direction.PEER_TO_HUB, keys));
  }

  private static int lpCount(byte[] value) {
    int count = 0;
    ByteBuffer buffer = ByteBuffer.wrap(value);
    while (buffer.hasRemaining()) { int size = buffer.getInt(); buffer.position(buffer.position() + size); count++; }
    return count;
  }

  private static byte[] lpField(byte[] value, int target) {
    ByteBuffer buffer = ByteBuffer.wrap(value);
    for (int index = 0; buffer.hasRemaining(); index++) {
      byte[] field = new byte[buffer.getInt()]; buffer.get(field); if (index == target) return field;
    }
    throw new AssertionError();
  }
}
