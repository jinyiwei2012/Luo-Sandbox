package io.github.jinyiwei2012.luosandbox.wire;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CanonicalMessageCodecTest {
  private static final Path ROOT = Path.of("..", "..", "contracts", "minecraft", "v1").normalize();

  @Test
  void acceptsAllCommittedValidFixturesAndRoundTripsThem() throws Exception {
    for (Path file : Files.list(ROOT.resolve("valid")).sorted().toList()) {
      byte[] bytes = Files.readAllBytes(file);
      CanonicalMessageCodec.CanonicalEnvelope envelope = CanonicalMessageCodec.decode(bytes);
      assertEquals(envelope, CanonicalMessageCodec.decode(CanonicalMessageCodec.encode(envelope)), file.toString());
    }
  }

  @Test
  void verifiesManifestHashesAndFixtureExpectations() throws Exception {
    Manifest manifest = readManifest();
    assertEquals("minecraft-contract-v1", manifest.version());
    assertEquals(52, manifest.fixtures().size(), "manifest parser must retain every fixture");
    assertEquals(2, manifest.rawFixtures().size(), "manifest parser must retain raw fixtures");
    assertEquals(sha256(Path.of("..", "..", "docs", "decisions", "minecraft-contract-v1.md")),
        manifest.specSha256());
    assertEquals(sha256(ROOT.resolve("schema.json")), manifest.schemaSha256());
    for (Fixture fixture : manifest.fixtures()) {
      Path file = ROOT.resolve(fixture.path());
      assertEquals(fixture.sha256(), sha256(file), file.toString());
      byte[] bytes = file.toString().endsWith(".hex") ? hex(Files.readString(file).trim()) : Files.readAllBytes(file);
      if ("accept".equals(fixture.expectedResult())) {
        assertEquals(fixture.type(), CanonicalMessageCodec.decode(bytes).type(), file.toString());
      } else {
        assertThrows(CanonicalMessageCodec.WireException.class,
            () -> CanonicalMessageCodec.decode(bytes), file.toString());
      }
    }
    for (RawFixture fixture : manifest.rawFixtures()) {
      Path file = ROOT.resolve(fixture.path());
      assertEquals(fixture.sha256(), sha256(file), file.toString());
      byte[] bytes = file.toString().endsWith(".hex") ? hex(Files.readString(file).trim()) : Files.readAllBytes(file);
      assertThrows(CanonicalMessageCodec.WireException.class,
          () -> CanonicalMessageCodec.decode(bytes), file.toString());
    }
  }

  @Test
  void emitsRegeneratedCanonicalOutputsForExternalParityConsumers() throws Exception {
    Path output = Path.of("build", "cross-language", "java-to-python");
    if (Files.exists(output)) {
      try (var files = Files.walk(output)) {
        files.sorted(Comparator.reverseOrder()).forEach(path -> {
          try { Files.delete(path); } catch (java.io.IOException error) { throw new RuntimeException(error); }
        });
      }
    }
    Files.createDirectories(output);
    StringBuilder index = new StringBuilder();
    try (var files = Files.list(ROOT.resolve("valid"))) {
      for (Path fixture : files.sorted().toList()) {
        byte[] encoded = CanonicalMessageCodec.encode(CanonicalMessageCodec.decode(Files.readAllBytes(fixture)));
        Path generated = output.resolve(fixture.getFileName());
        Files.write(generated, encoded);
        index.append(fixture.getFileName()).append(' ').append(sha256(generated)).append('\n');
      }
    }
    Files.writeString(output.resolve("index.sha256"), index.toString(), StandardCharsets.US_ASCII);
  }

  @Test
  void rejectsAllCommittedInvalidFixturesIncludingRawForms() throws Exception {
    for (Path file : Files.list(ROOT.resolve("invalid")).sorted().toList()) {
      byte[] bytes = file.toString().endsWith(".hex")
          ? hex(Files.readString(file).trim()) : Files.readAllBytes(file);
      assertThrows(CanonicalMessageCodec.WireException.class,
          () -> CanonicalMessageCodec.decode(bytes), file.toString());
    }
  }

  @Test
  void normalizesF64AndPreservesDecimalLexicalCategory() throws Exception {
    CanonicalMessageCodec.CanonicalEnvelope envelope = CanonicalMessageCodec.decode(Files.readAllBytes(ROOT.resolve("valid/observation_decimal_positions.json")));
    Map<String, Object> state = (Map<String, Object>) envelope.payload().get("state");
    Map<String, Object> position = (Map<String, Object>) state.get("position");
    assertEquals(0.5d, ((Number) position.get("x")).doubleValue());
    assertInstanceOf(Double.class, position.get("x"));
    assertInstanceOf(Double.class, position.get("y"));
  }

  @Test
  void rejectsStructureSemanticAndDeadlineCounterexamples() throws Exception {
    assertCategory(CanonicalMessageCodec.ErrorCategory.STRUCTURE, "{\"protocol\":\"luosandbox.minecraft.v1\"}");
    assertCategory(CanonicalMessageCodec.ErrorCategory.SEMANTIC, text("valid/request_query_bootstrap.json").replace("\"sessionEpoch\":0", "\"sessionEpoch\":1.0"));
    assertCategory(CanonicalMessageCodec.ErrorCategory.SEMANTIC, text("valid/request_query_bootstrap.json").replace("00:00:05.000Z", "00:00:00.000Z"));
  }

  @Test
  void validatesTextUnicodeAndPythonCompatibleLeadingWhitespace() throws Exception {
    String base = text("valid/request_say.json");
    assertCategory(CanonicalMessageCodec.ErrorCategory.SEMANTIC, replaceSay(base, ""));
    assertCategory(CanonicalMessageCodec.ErrorCategory.LEXICAL, replaceSay(base, "\\uD800"));
    assertCategory(CanonicalMessageCodec.ErrorCategory.LEXICAL, replaceSay(base, "\\uDC00"));
    assertCategory(CanonicalMessageCodec.ErrorCategory.SEMANTIC, replaceSay(base, " \\/x"));
    assertCategory(CanonicalMessageCodec.ErrorCategory.SEMANTIC, replaceSay(base, "\\u00a0/x"));
    assertCategory(CanonicalMessageCodec.ErrorCategory.SEMANTIC, replaceSay(base, "\\u0085/x"));
    CanonicalMessageCodec.decode(replaceSay(base, "😀 /ok").getBytes(StandardCharsets.UTF_8));
    CanonicalMessageCodec.decode(replaceSay(base, "x/y").getBytes(StandardCharsets.UTF_8));
    String observation = text("valid/observation_public_current.json");
    assertCategory(CanonicalMessageCodec.ErrorCategory.SEMANTIC, observation.replace("\"untrustedText\":[]", "\"untrustedText\":[{\"eventId\":\"aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa\",\"text\":\"\"}]"));
    assertCategory(CanonicalMessageCodec.ErrorCategory.LEXICAL, observation.replace("\"untrustedText\":[]", "\"untrustedText\":[{\"eventId\":\"aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa\",\"text\":\"\\uD800\"}]"));
    CanonicalMessageCodec.decode(observation.replace("\"untrustedText\":[]", "\"untrustedText\":[{\"eventId\":\"aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa\",\"text\":\"😀\"}]").getBytes(StandardCharsets.UTF_8));
  }

  @Test
  void encoderRejectsLocallyConstructedInvalidTextAndAcceptsEmoji() throws Exception {
    CanonicalMessageCodec.CanonicalEnvelope base = CanonicalMessageCodec.decode(
        Files.readAllBytes(ROOT.resolve("valid/request_say.json")));
    assertEncodeSemantic(base, replacePayloadText(base.payload(), ""));
    assertEncodeSemantic(base, replacePayloadText(base.payload(), "\ud800"));
    assertEncodeSemantic(base, replacePayloadText(base.payload(), "\udc00"));
    assertEncodeSemantic(base, replacePayloadText(base.payload(), " /command"));
    assertEncodeSemantic(base, replacePayloadText(base.payload(), "\u00a0/command"));
    assertEncodeSemantic(base, replacePayloadText(base.payload(), "\u0085/command"));
    CanonicalMessageCodec.encode(envelope(base, replacePayloadText(base.payload(), "😀 ok")));

    CanonicalMessageCodec.CanonicalEnvelope observation = CanonicalMessageCodec.decode(
        Files.readAllBytes(ROOT.resolve("valid/observation_public_current.json")));
    assertEncodeSemantic(observation, replaceUntrustedText(observation.payload(), ""));
    assertEncodeSemantic(observation, replaceUntrustedText(observation.payload(), "\ud800"));
    assertEncodeSemantic(observation, replaceUntrustedText(observation.payload(), "\udc00"));
    CanonicalMessageCodec.encode(envelope(observation, replaceUntrustedText(observation.payload(), "😀")));
  }

  private static void assertCategory(CanonicalMessageCodec.ErrorCategory category, String json) {
    CanonicalMessageCodec.WireException exception = assertThrows(CanonicalMessageCodec.WireException.class,
        () -> CanonicalMessageCodec.decode(json.getBytes(StandardCharsets.UTF_8)));
    assertEquals(category, exception.category());
  }

  private static void assertEncodeSemantic(CanonicalMessageCodec.CanonicalEnvelope base,
      Map<String, Object> payload) {
    CanonicalMessageCodec.WireException exception = assertThrows(CanonicalMessageCodec.WireException.class,
        () -> CanonicalMessageCodec.encode(envelope(base, payload)));
    assertEquals(CanonicalMessageCodec.ErrorCategory.SEMANTIC, exception.category());
  }

  private static CanonicalMessageCodec.CanonicalEnvelope envelope(
      CanonicalMessageCodec.CanonicalEnvelope base, Map<String, Object> payload) {
    return new CanonicalMessageCodec.CanonicalEnvelope(base.protocol(), base.type(), base.actorId(),
        base.worldId(), base.bodyId(), base.sessionId(), base.sessionEpoch(), base.messageId(),
        base.timestamp(), payload);
  }

  private static Map<String, Object> replacePayloadText(Map<String, Object> source, String text) {
    Map<String, Object> payload = mutableMap(source);
    Map<String, Object> intent = mutableMap((Map<String, Object>) payload.get("intent"));
    intent.put("message", text);
    payload.put("intent", intent);
    return payload;
  }

  private static Map<String, Object> replaceUntrustedText(Map<String, Object> source, String text) {
    Map<String, Object> payload = mutableMap(source);
    payload.put("untrustedText", List.of(Map.of(
        "eventId", "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", "text", text)));
    return payload;
  }

  private static Map<String, Object> mutableMap(Map<String, Object> source) {
    Map<String, Object> result = new java.util.LinkedHashMap<>();
    for (Map.Entry<String, Object> entry : source.entrySet()) {
      Object value = entry.getValue();
      result.put(entry.getKey(), value instanceof Map<?, ?> map ? mutableMap((Map<String, Object>) map) : value);
    }
    return result;
  }

  private static String text(String path) throws Exception { return Files.readString(ROOT.resolve(path)); }
  private static String replaceSay(String document, String message) {
    String replacement = "\"message\":\"" + message + "\"";
    return document.replaceFirst("\\\"message\\\":\\\"[^\\\"]*\\\"",
        java.util.regex.Matcher.quoteReplacement(replacement));
  }
  private static byte[] hex(String value) { byte[] bytes = new byte[value.length() / 2]; for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) Integer.parseInt(value.substring(i * 2, i * 2 + 2), 16); return bytes; }
  private static String sha256(Path file) throws Exception {
    byte[] digest = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file));
    StringBuilder result = new StringBuilder();
    for (byte value : digest) result.append(String.format("%02x", value));
    return result.toString();
  }

  private static Manifest readManifest() throws Exception {
    try (JsonParser parser = new JsonFactory().createParser(Files.readAllBytes(ROOT.resolve("manifest.json")))) {
      if (parser.nextToken() != JsonToken.START_OBJECT) throw new AssertionError("Manifest root");
      String version = null;
      String spec = null;
      String schema = null;
      List<Fixture> fixtures = new ArrayList<>();
      List<RawFixture> rawFixtures = new ArrayList<>();
      while (parser.nextToken() != JsonToken.END_OBJECT) {
        String field = parser.currentName();
        parser.nextToken();
        if (field.equals("version")) version = parser.getText();
        else if (field.equals("specSha256")) spec = parser.getText();
        else if (field.equals("schemaSha256")) schema = parser.getText();
        else if (field.equals("fixtures")) readFixtures(parser, fixtures, false);
        else if (field.equals("rawFixtures")) readFixtures(parser, rawFixtures, true);
        else parser.skipChildren();
      }
      return new Manifest(version, spec, schema, List.copyOf(fixtures), List.copyOf(rawFixtures));
    }
  }

  private static <T> void readFixtures(JsonParser parser, List<T> output, boolean raw) throws Exception {
    if (parser.currentToken() != JsonToken.START_ARRAY) throw new AssertionError("Manifest fixture array");
    while (parser.nextToken() != JsonToken.END_ARRAY) {
      String path = null;
      String hash = null;
      String result = null;
      String type = null;
      while (parser.nextToken() != JsonToken.END_OBJECT) {
        String name = parser.currentName();
        parser.nextToken();
        if (name.equals("path")) path = parser.getText();
        else if (name.equals("sha256")) hash = parser.getText();
        else if (name.equals("expectedResult")) result = parser.getText();
        else if (name.equals("type")) type = parser.getText();
        else parser.skipChildren();
      }
      if (raw) output.add((T) new RawFixture(path, hash));
      else output.add((T) new Fixture(path, hash, result, type));
    }
  }

  private record Manifest(String version, String specSha256, String schemaSha256, List<Fixture> fixtures, List<RawFixture> rawFixtures) {}
  private record Fixture(String path, String sha256, String expectedResult, String type) {}
  private record RawFixture(String path, String sha256) {}
}
