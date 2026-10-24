package io.github.jinyiwei2012.luosandbox.security;

import com.fasterxml.jackson.core.JsonFactory;
import io.github.jinyiwei2012.luosandbox.wire.StrictJson;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Strict, unassembled v2 frame crypto/JSON codec with no queue, socket, or authority state. */
public final class PostAuthFrameCodec {
  public static final String FRAME_DOMAIN = "LUO_CONTROL_FRAME_V2";
  public static final String KIND = "AUTHENTICATED_FRAME";
  public static final String PROFILE = "LUO_CONTROL_POSTAUTH_V2";
  public static final int MAX_FRAME_BYTES = 4096;
  public static final int MAX_PAYLOAD_BYTES = 2048;
  private static final long I63_MAX = Long.MAX_VALUE;
  private static final List<String> OUTER_KEYS = List.of("security", "kind", "profile",
      "connectionId", "challengeId", "sequence", "messageKind", "routeId",
      "sourceInstallId", "sourceConnectionId", "recipientConnectionId",
      "challengeExpiresAt", "payload", "mac");
  private static final Set<String> NOTICE_KEYS = Set.of("security", "kind", "serverBootId",
      "gameConnectionId", "bodyId", "gameNonce");
  private static final Set<String> CANDIDATE_KEYS = Set.of("security", "kind", "serverBootId",
      "gameConnectionId", "bodyId");
  private static final Pattern PAYLOAD_B64 = Pattern.compile("[A-Za-z0-9_-]*");

  private PostAuthFrameCodec() {}

  public enum Direction { PEER_TO_HUB, HUB_TO_PEER }

  /** Trusted session identity supplied by a future transport owner, never payload-derived. */
  public record Context(ControlAuth.Transcript transcript, String currentConnectionId) {
    public Context {
      if (transcript == null || transcript.peerRole() == ControlAuth.Role.AGENT_HUB
          || !ControlDeploymentManifest.PROTOCOL.equals(transcript.protocol())) throw invalid();
      if (currentConnectionId != null) ControlAuth.requireUuid(currentConnectionId);
    }
  }

  /** Immutable frame data only; acceptance/currentness/sequence progression remain transport-owned. */
  public record Frame(String connectionId, String challengeId, long sequence, String messageKind,
      String routeId, String sourceInstallId, String sourceConnectionId,
      String recipientConnectionId, String challengeExpiresAt, byte[] payload, String mac) {
    public Frame {
      if (payload == null) throw invalid();
      payload = payload.clone();
    }
    @Override public byte[] payload() { return payload.clone(); }
    @Override public String toString() { return "PostAuthFrameCodec.Frame[redacted]"; }
  }

  /** Encode and MAC one already-selected frame. No queue or sequence allocation occurs here. */
  public static byte[] encode(Frame frame, Context context, Direction direction,
      PostAuthSessionKeys keys) {
    try {
      keys.requireContext(context.transcript());
      Frame validated = validate(frame, context, direction, MacMode.EMPTY);
      validatePayload(validated, context, direction);
      byte[] input = macInput(validated, context, direction);
      String mac = ControlAuth.b32(keys.mac(direction, input));
      byte[] encoded = encodeOuter(validated, mac);
      if (encoded.length > MAX_FRAME_BYTES) throw invalid();
      return encoded;
    } catch (CodecException error) {
      throw error;
    } catch (RuntimeException error) {
      throw invalid();
    }
  }

  /** Decode, verify MAC, then validate payload schema; it performs no business side effects. */
  public static Frame decode(byte[] encoded, Context context, Direction direction,
      PostAuthSessionKeys keys) {
    try {
      keys.requireContext(context.transcript());
      if (encoded == null || encoded.length == 0 || encoded.length > MAX_FRAME_BYTES) throw invalid();
      Map<String, Object> raw = StrictJson.decodeObject(encoded.clone());
      if (!raw.keySet().equals(Set.copyOf(OUTER_KEYS))) throw invalid();
      for (var entry : raw.entrySet()) {
        if (!entry.getKey().equals("sequence") && !(entry.getValue() instanceof String)) throw invalid();
      }
      Object sequenceValue = raw.get("sequence");
      if (!(sequenceValue instanceof Long sequence) || sequence < 0 || sequence > I63_MAX) throw invalid();
      Frame frame = new Frame(string(raw, "connectionId"), string(raw, "challengeId"), sequence,
          string(raw, "messageKind"), string(raw, "routeId"), string(raw, "sourceInstallId"),
          string(raw, "sourceConnectionId"), string(raw, "recipientConnectionId"),
          string(raw, "challengeExpiresAt"), decodePayload(string(raw, "payload")), string(raw, "mac"));
      requireLiteral(raw, "security", ControlAuth.DOMAIN);
      requireLiteral(raw, "kind", KIND);
      requireLiteral(raw, "profile", PROFILE);
      Frame structurallyValid = validate(frame, context, direction, MacMode.REQUIRED);
      if (!keys.verify(direction, macInput(structurallyValid, context, direction), frame.mac())) throw invalid();
      validatePayload(structurallyValid, context, direction);
      return structurallyValid;
    } catch (StrictJson.DecodeException | CodecException error) {
      throw invalid();
    } catch (RuntimeException error) {
      throw invalid();
    }
  }

  /** Exact 18-field LP input, exposed for independent byte-level verification without keys. */
  public static byte[] macInput(Frame frame, Context context, Direction direction) {
    Frame value = validate(frame, context, direction, MacMode.IGNORED);
    List<byte[]> fields = List.of(ascii(FRAME_DOMAIN), ascii(ControlAuth.DOMAIN), ascii(KIND),
        ascii(PROFILE), ascii(context.transcript().peerRole().name()),
        ascii(context.transcript().peerInstallId()), ascii(context.transcript().hubInstallId()),
        ascii(value.connectionId()), ascii(value.challengeId()),
        ascii(direction.name()), ByteBuffer.allocate(8).putLong(value.sequence()).array(),
        ascii(value.messageKind()), ascii(value.routeId()), ascii(value.sourceInstallId()),
        ascii(value.sourceConnectionId()), ascii(value.recipientConnectionId()),
        ascii(value.challengeExpiresAt()), value.payload());
    int size = fields.stream().mapToInt(field -> 4 + field.length).sum();
    ByteBuffer buffer = ByteBuffer.allocate(size);
    for (byte[] field : fields) buffer.putInt(field.length).put(field);
    return buffer.array();
  }

  public static String sessionOpenPayload(String connectionId) {
    ControlAuth.requireUuid(connectionId);
    return "{\"security\":\"luosandbox.control-auth.v1\",\"kind\":\"SESSION_OPEN\","
        + "\"connectionId\":\"" + connectionId + "\"}";
  }

  private enum MacMode { EMPTY, REQUIRED, IGNORED }

  private static Frame validate(Frame frame, Context context, Direction direction, MacMode macMode) {
    if (frame == null || context == null || direction == null) throw invalid();
    ControlAuth.requireUuid(frame.connectionId());
    ControlAuth.requireUuid(frame.challengeId());
    if (!frame.challengeId().equals(context.transcript().challengeId()) || frame.sequence() < 0) throw invalid();
    for (String value : List.of(frame.messageKind(), frame.routeId(), frame.sourceInstallId(),
        frame.sourceConnectionId(), frame.recipientConnectionId(), frame.challengeExpiresAt())) ascii(value);
    ControlAuth.requireId(frame.messageKind());
    if (frame.payload().length > MAX_PAYLOAD_BYTES) throw invalid();
    if (macMode == MacMode.REQUIRED) ControlAuth.decodeB32(frame.mac());
    else if (macMode == MacMode.EMPTY && frame.mac() != null && !frame.mac().isEmpty()) throw invalid();
    if (context.currentConnectionId() == null) {
      if (!(direction == Direction.HUB_TO_PEER && frame.messageKind().equals("SESSION_OPEN"))) throw invalid();
    } else if (!frame.connectionId().equals(context.currentConnectionId())) throw invalid();
    validateMetadata(frame, context, direction);
    return frame;
  }

  private static void validateMetadata(Frame frame, Context context, Direction direction) {
    boolean empty = frame.routeId().isEmpty() && frame.sourceInstallId().isEmpty()
        && frame.sourceConnectionId().isEmpty() && frame.recipientConnectionId().isEmpty()
        && frame.challengeExpiresAt().isEmpty();
    switch (frame.messageKind()) {
      case "SESSION_OPEN" -> {
        if (direction != Direction.HUB_TO_PEER || frame.sequence() != 0 || !empty) throw invalid();
      }
      case "HEARTBEAT" -> { if (!empty) throw invalid(); }
      case "BODY_BIND_NOTICE" -> {
        if (direction != Direction.PEER_TO_HUB
            || context.transcript().peerRole() != ControlAuth.Role.CLIENT_CONTROLLER || !empty) throw invalid();
      }
      case "BODY_BIND_ROUTE" -> {
        if (direction != Direction.HUB_TO_PEER
            || context.transcript().peerRole() != ControlAuth.Role.SERVER_AUTHORITY
            || frame.routeId().isEmpty() || frame.sourceInstallId().isEmpty()
            || frame.sourceConnectionId().isEmpty() || frame.recipientConnectionId().isEmpty()
            || !frame.challengeExpiresAt().isEmpty()) throw invalid();
        ControlAuth.requireUuid(frame.routeId());
        ControlAuth.requireId(frame.sourceInstallId());
        ControlAuth.requireUuid(frame.sourceConnectionId());
        ControlAuth.requireUuid(frame.recipientConnectionId());
        if (!frame.recipientConnectionId().equals(frame.connectionId())) throw invalid();
      }
      case "BODY_BOUND_CANDIDATE" -> {
        if (direction != Direction.PEER_TO_HUB
            || context.transcript().peerRole() != ControlAuth.Role.SERVER_AUTHORITY
            || frame.routeId().isEmpty() || !frame.sourceInstallId().isEmpty()
            || !frame.sourceConnectionId().isEmpty() || frame.recipientConnectionId().isEmpty()
            || frame.challengeExpiresAt().isEmpty()) throw invalid();
        ControlAuth.requireUuid(frame.routeId());
        ControlAuth.requireUuid(frame.recipientConnectionId());
        if (!frame.recipientConnectionId().equals(frame.connectionId())) throw invalid();
        utc(frame.challengeExpiresAt());
      }
      default -> throw invalid();
    }
  }

  private static void validatePayload(Frame frame, Context context, Direction direction) {
    byte[] payload = frame.payload();
    switch (frame.messageKind()) {
      case "SESSION_OPEN" -> {
        if (!MessageDigest.isEqual(payload,
            sessionOpenPayload(frame.connectionId()).getBytes(StandardCharsets.US_ASCII))) throw invalid();
      }
      case "HEARTBEAT" -> { if (!MessageDigest.isEqual(payload, new byte[] {'{', '}'})) throw invalid(); }
      case "BODY_BIND_NOTICE", "BODY_BIND_ROUTE" -> validateNotice(payload);
      case "BODY_BOUND_CANDIDATE" -> validateCandidate(payload);
      default -> throw invalid();
    }
  }

  private static void validateNotice(byte[] payload) {
    Map<String, Object> value = payloadObject(payload, NOTICE_KEYS);
    requireLiteral(value, "security", ControlAuth.DOMAIN);
    requireLiteral(value, "kind", "BODY_BIND_NOTICE");
    ControlAuth.requireUuid(string(value, "serverBootId"));
    ControlAuth.requireUuid(string(value, "gameConnectionId"));
    ControlAuth.requireUuid(string(value, "bodyId"));
    ControlAuth.decodeB32(string(value, "gameNonce"));
  }

  private static void validateCandidate(byte[] payload) {
    Map<String, Object> value = payloadObject(payload, CANDIDATE_KEYS);
    requireLiteral(value, "security", ControlAuth.DOMAIN);
    requireLiteral(value, "kind", "BODY_BOUND");
    ControlAuth.requireUuid(string(value, "serverBootId"));
    ControlAuth.requireUuid(string(value, "gameConnectionId"));
    ControlAuth.requireUuid(string(value, "bodyId"));
  }

  private static Map<String, Object> payloadObject(byte[] payload, Set<String> keys) {
    try {
      Map<String, Object> value = StrictJson.decodeObject(payload);
      if (!value.keySet().equals(keys)) throw invalid();
      for (Object field : value.values()) if (!(field instanceof String)) throw invalid();
      return value;
    } catch (StrictJson.DecodeException error) {
      throw invalid();
    }
  }

  private static byte[] encodeOuter(Frame frame, String mac) {
    try {
      ByteArrayOutputStream output = new ByteArrayOutputStream();
      try (var json = new JsonFactory().createGenerator(output)) {
        json.writeStartObject();
        json.writeStringField("security", ControlAuth.DOMAIN);
        json.writeStringField("kind", KIND);
        json.writeStringField("profile", PROFILE);
        json.writeStringField("connectionId", frame.connectionId());
        json.writeStringField("challengeId", frame.challengeId());
        json.writeNumberField("sequence", frame.sequence());
        json.writeStringField("messageKind", frame.messageKind());
        json.writeStringField("routeId", frame.routeId());
        json.writeStringField("sourceInstallId", frame.sourceInstallId());
        json.writeStringField("sourceConnectionId", frame.sourceConnectionId());
        json.writeStringField("recipientConnectionId", frame.recipientConnectionId());
        json.writeStringField("challengeExpiresAt", frame.challengeExpiresAt());
        json.writeStringField("payload", encodePayload(frame.payload()));
        json.writeStringField("mac", mac);
        json.writeEndObject();
      }
      return output.toByteArray();
    } catch (Exception error) { throw invalid(); }
  }

  private static String encodePayload(byte[] payload) {
    return Base64.getUrlEncoder().withoutPadding().encodeToString(payload);
  }

  private static byte[] decodePayload(String value) {
    if (!PAYLOAD_B64.matcher(value).matches() || value.contains("=")) throw invalid();
    try {
      byte[] decoded = Base64.getUrlDecoder().decode(value);
      if (decoded.length > MAX_PAYLOAD_BYTES || !encodePayload(decoded).equals(value)) throw invalid();
      return decoded;
    } catch (IllegalArgumentException error) { throw invalid(); }
  }

  private static String string(Map<String, Object> value, String name) {
    Object field = value.get(name);
    if (!(field instanceof String result)) throw invalid();
    return result;
  }

  private static void requireLiteral(Map<String, Object> value, String name, String expected) {
    if (!expected.equals(string(value, name))) throw invalid();
  }

  private static Instant utc(String value) {
    try {
      Instant instant = Instant.parse(value);
      ControlAuth.requireUtc(instant);
      if (!ControlAuth.formatUtc(instant).equals(value)) throw invalid();
      return instant;
    } catch (RuntimeException error) { throw invalid(); }
  }

  private static byte[] ascii(String value) {
    if (value == null) throw invalid();
    for (int index = 0; index < value.length(); index++) {
      if (value.charAt(index) == 0 || value.charAt(index) > 0x7f) throw invalid();
    }
    return value.getBytes(StandardCharsets.US_ASCII);
  }

  /** Sanitized parse/crypto/schema failure; no raw frame or key material is exposed. */
  public static final class CodecException extends IllegalArgumentException {
    private CodecException() { super("invalid post-auth frame"); }
  }

  private static CodecException invalid() { return new CodecException(); }
}
