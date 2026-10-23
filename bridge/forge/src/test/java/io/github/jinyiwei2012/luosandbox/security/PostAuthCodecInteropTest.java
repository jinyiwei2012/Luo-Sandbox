package io.github.jinyiwei2012.luosandbox.security;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Actual Java half of the local post-auth core interop harness. */
class PostAuthCodecInteropTest {
  private static final String SCHEMA = "postauth-codec-v2-interop-v1";
  private static final HexFormat HEX = HexFormat.of();

  @Test
  void consumesPythonFramesAndExportsActualJavaCodecResults() throws Exception {
    String inputValue = System.getenv("LUOSANDBOX_POSTAUTH_CODEC_INPUT");
    String outputValue = System.getenv("LUOSANDBOX_POSTAUTH_CODEC_OUTPUT");
    if (inputValue == null || inputValue.isBlank() || outputValue == null || outputValue.isBlank()) return;
    Path input = approved(Path.of(inputValue));
    Path output = approved(Path.of(outputValue));
    Map<String, Object> document = readObject(input);
    assertEquals(SCHEMA, document.get("schema"));
    List<?> cases = assertInstanceOf(List.class, document.get("cases"));
    assertEquals(18, cases.size());
    List<Result> results = new ArrayList<>();
    for (Object item : cases) results.add(run(cast(item)));
    Files.createDirectories(output.getParent());
    try (JsonGenerator json = new JsonFactory().createGenerator(Files.newOutputStream(output))) {
      json.writeStartObject();
      json.writeStringField("schema", SCHEMA);
      json.writeObjectFieldStart("invocation");
      json.writeStringField("testClass", PostAuthCodecInteropTest.class.getName());
      json.writeBooleanField("forcedRerunRequired", true);
      json.writeStringField("executionAttestation", "SEPARATE_RUNNER_OBSERVATION_REQUIRED");
      json.writeEndObject();
      json.writeObjectFieldStart("loadedClasses");
      writeClass(json, PostAuthSessionKeys.class);
      writeClass(json, PostAuthFrameCodec.class);
      json.writeEndObject();
      json.writeArrayFieldStart("cases");
      for (Result result : results) result.write(json);
      json.writeEndArray();
      json.writeEndObject();
    }
  }

  private static Result run(Map<String, Object> value) {
    String name = string(value, "name");
    boolean expected = bool(value, "accepted");
    try {
      ControlAuth.Transcript transcript = transcript(cast(value.get("transcript")));
      String current = value.get("currentConnectionId") == null ? null : string(value, "currentConnectionId");
      PostAuthFrameCodec.Context context = new PostAuthFrameCodec.Context(transcript, current);
      PostAuthFrameCodec.Direction direction = PostAuthFrameCodec.Direction.valueOf(string(value, "direction"));
      try (PostAuthSessionKeys keys = PostAuthSessionKeys.derive(HEX.parseHex(string(value, "pskHex")), transcript)) {
        PostAuthFrameCodec.Frame parsed = PostAuthFrameCodec.decode(
            HEX.parseHex(string(value, "pythonRawHex")), context, direction, keys);
        byte[] encoded = PostAuthFrameCodec.encode(withEmptyMac(parsed), context, direction, keys);
        byte[] macInput = PostAuthFrameCodec.macInput(parsed, context, direction);
        String mac;
        try { mac = extractString(encoded, "mac"); }
        catch (Exception error) { throw new IllegalArgumentException("encoded frame parse failed", error); }
        assertTrue(expected, "Java accepted negative case " + name);
        return new Result(name, true, HEX.formatHex(encoded), HEX.formatHex(macInput),
            HEX.formatHex(ControlAuth.decodeB32(mac)));
      }
    } catch (RuntimeException error) {
      assertFalse(expected, "Java rejected valid case " + name);
      return new Result(name, false, null, null, null);
    }
  }

  private static PostAuthFrameCodec.Frame withEmptyMac(PostAuthFrameCodec.Frame value) {
    return new PostAuthFrameCodec.Frame(value.connectionId(), value.challengeId(), value.sequence(),
        value.messageKind(), value.routeId(), value.sourceInstallId(), value.sourceConnectionId(),
        value.recipientConnectionId(), value.challengeExpiresAt(), value.payload(), "");
  }

  private static ControlAuth.Transcript transcript(Map<String, Object> value) {
    return new ControlAuth.Transcript(ControlAuth.Role.valueOf(string(value, "role")),
        string(value, "peerInstallId"), string(value, "hubInstallId"), string(value, "keyId"),
        string(value, "protocol"), HEX.parseHex(string(value, "modHashHex")),
        HEX.parseHex(string(value, "peerNonceHex")), HEX.parseHex(string(value, "hubNonceHex")),
        string(value, "challengeId"), Instant.parse(string(value, "issuedAt")),
        Instant.parse(string(value, "expiresAt")), HEX.parseHex(string(value, "configHashHex")),
        HEX.parseHex(string(value, "bindingHashHex")));
  }

  private static void writeClass(JsonGenerator json, Class<?> type) throws Exception {
    json.writeObjectFieldStart(type.getSimpleName());
    json.writeStringField("resource", String.valueOf(type.getResource(type.getSimpleName() + ".class")));
    try (var stream = type.getResourceAsStream(type.getSimpleName() + ".class")) {
      assertNotNull(stream);
      json.writeStringField("sha256", HEX.formatHex(
          MessageDigest.getInstance("SHA-256").digest(stream.readAllBytes())));
    }
    json.writeEndObject();
  }

  private static Map<String, Object> readObject(Path path) throws Exception {
    try (JsonParser parser = new JsonFactory().createParser(Files.readAllBytes(path))) {
      assertEquals(JsonToken.START_OBJECT, parser.nextToken());
      return object(parser);
    }
  }

  private static Map<String, Object> object(JsonParser parser) throws Exception {
    Map<String, Object> result = new LinkedHashMap<>();
    while (parser.nextToken() != JsonToken.END_OBJECT) {
      String name = parser.currentName();
      JsonToken token = parser.nextToken();
      assertNull(result.put(name, value(parser, token)), "duplicate input field");
    }
    return result;
  }

  private static List<Object> array(JsonParser parser) throws Exception {
    List<Object> result = new ArrayList<>();
    JsonToken token;
    while ((token = parser.nextToken()) != JsonToken.END_ARRAY) result.add(value(parser, token));
    return result;
  }

  private static Object value(JsonParser parser, JsonToken token) throws Exception {
    return switch (token) {
      case START_OBJECT -> object(parser);
      case START_ARRAY -> array(parser);
      case VALUE_STRING -> parser.getText();
      case VALUE_NUMBER_INT -> parser.getLongValue();
      case VALUE_TRUE -> Boolean.TRUE;
      case VALUE_FALSE -> Boolean.FALSE;
      case VALUE_NULL -> null;
      default -> throw new IllegalArgumentException("unsupported input token");
    };
  }

  private static String extractString(byte[] raw, String target) throws Exception {
    try (JsonParser parser = new JsonFactory().createParser(raw)) {
      while (parser.nextToken() != null) {
        if (parser.currentToken() == JsonToken.FIELD_NAME && target.equals(parser.currentName())) {
          return parser.nextTextValue();
        }
      }
    }
    throw new IllegalArgumentException("missing encoded field");
  }

  private static Path approved(Path requested) {
    Path path = requested.toAbsolutePath().normalize();
    Path approved = Path.of("..", "..", ".gradle-user-home", "interop").toAbsolutePath().normalize();
    assertTrue(path.startsWith(approved));
    return path;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> cast(Object value) {
    return (Map<String, Object>) assertInstanceOf(Map.class, value);
  }
  private static String string(Map<String, Object> value, String name) {
    return assertInstanceOf(String.class, value.get(name));
  }
  private static boolean bool(Map<String, Object> value, String name) {
    return assertInstanceOf(Boolean.class, value.get(name));
  }

  private record Result(String name, boolean accepted, String javaRawHex,
      String javaMacInputHex, String javaMacHex) {
    void write(JsonGenerator json) throws Exception {
      json.writeStartObject(); json.writeStringField("name", name); json.writeBooleanField("accepted", accepted);
      if (accepted) { json.writeStringField("javaRawHex", javaRawHex);
        json.writeStringField("javaMacInputHex", javaMacInputHex); json.writeStringField("javaMacHex", javaMacHex); }
      json.writeEndObject();
    }
  }
}
