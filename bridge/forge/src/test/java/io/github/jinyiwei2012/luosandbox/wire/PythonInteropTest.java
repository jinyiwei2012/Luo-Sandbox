package io.github.jinyiwei2012.luosandbox.wire;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.StreamReadFeature;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/** Optional real Python-encoder to Java-decoder evidence. */
class PythonInteropTest {
  private static final Path CONTRACT = Path.of("..", "..", "contracts", "minecraft", "v1").normalize();
  private static final Path PROOF = Path.of("build", "cross-language", "python-to-java-result.json");
  private static final JsonFactory MANIFEST_JSON = JsonFactory.builder()
      .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
      .build();

  @Test
  void decodesPreparedPythonMessagesAgainstCanonicalFixtures() throws Exception {
    String configured = System.getenv("LUOSANDBOX_PYTHON_INTEROP_DIR");
    Assumptions.assumeTrue(configured != null && !configured.isBlank(), "Python interop artifacts were not prepared");
    Path output = Path.of(configured);
    assertTrue(Files.isDirectory(output), "Python interop artifact directory is missing");
    Files.deleteIfExists(PROOF);
    Map<String, Object> index = object(StrictJson.decodeObject(Files.readAllBytes(output.resolve("index.json"))));
    Set<String> expected = expectedNames();
    Map<String, Object> files = object(index.get("files"));
    assertEquals(expected, files.keySet());
    assertEquals(sha256(CONTRACT.resolve("manifest.json")), index.get("manifestSha256"));
    assertEquals("7ee44ed20446533628e745ba591e14392af9f1b8e7ba44c2155b3bf72d34f1cb", index.get("specSha256"));
    try (var paths = Files.list(output)) {
      assertEquals(expected.size() + 1, paths.count(), "unexpected Python artifact inventory");
    }
    for (String filename : expected) {
      assertSafeName(filename);
      Path generated = output.resolve(filename);
      assertTrue(Files.isRegularFile(generated) && !Files.isSymbolicLink(generated));
      assertTrue(Files.size(generated) <= StrictJson.MAX_FRAME_BYTES);
      assertEquals(files.get(filename), sha256(generated));
      CanonicalMessageCodec.CanonicalEnvelope python = CanonicalMessageCodec.decode(Files.readAllBytes(generated));
      CanonicalMessageCodec.CanonicalEnvelope canonical = CanonicalMessageCodec.decode(Files.readAllBytes(CONTRACT.resolve("valid").resolve(filename)));
      assertEquals(canonical, python, filename);
      assertEquals(canonical, CanonicalMessageCodec.decode(CanonicalMessageCodec.encode(python)), filename);
    }
    writeProof(expected.size(), sha256(output.resolve("index.json")), sha256(CONTRACT.resolve("manifest.json")));
  }

  private static Set<String> expectedNames() throws Exception {
    Path manifest = CONTRACT.resolve("manifest.json");
    Set<String> names = new LinkedHashSet<>();
    try (JsonParser parser = MANIFEST_JSON.createParser(Files.readAllBytes(manifest))) {
      assertEquals(JsonToken.START_OBJECT, parser.nextToken());
      int tokens = 0;
      while (parser.nextToken() != JsonToken.END_OBJECT) {
        assertTrue(++tokens <= 20_000, "manifest token budget");
        String field = parser.currentName();
        assertEquals(JsonToken.FIELD_NAME, parser.currentToken());
        JsonToken value = parser.nextToken();
        if (!"fixtures".equals(field)) {
          parser.skipChildren();
          continue;
        }
        assertEquals(JsonToken.START_ARRAY, value);
        while (parser.nextToken() != JsonToken.END_ARRAY) {
          assertEquals(JsonToken.START_OBJECT, parser.currentToken());
          String path = null;
          String expectedResult = null;
          while (parser.nextToken() != JsonToken.END_OBJECT) {
            assertEquals(JsonToken.FIELD_NAME, parser.currentToken());
            String fixtureField = parser.currentName();
            JsonToken fixtureValue = parser.nextToken();
            if ("path".equals(fixtureField) || "expectedResult".equals(fixtureField)) {
              assertEquals(JsonToken.VALUE_STRING, fixtureValue);
              if ("path".equals(fixtureField)) path = parser.getText();
              else expectedResult = parser.getText();
            } else {
              parser.skipChildren();
            }
          }
          if ("accept".equals(expectedResult)) {
            assertTrue(path != null && path.startsWith("valid/") && path.endsWith(".json"));
            names.add(Path.of(path).getFileName().toString());
          }
        }
      }
    }
    assertEquals(26, names.size());
    return names;
  }

  private static void writeProof(int count, String indexHash, String manifestHash) throws Exception {
    Files.createDirectories(PROOF.getParent());
    try (ByteArrayOutputStream bytes = new ByteArrayOutputStream(); JsonGenerator json = new com.fasterxml.jackson.core.JsonFactory().createGenerator(bytes)) {
      json.writeStartObject(); json.writeStringField("status", "PASSED"); json.writeNumberField("checked", count);
      json.writeNumberField("expected", count); json.writeStringField("sourceIndexSha256", indexHash);
      json.writeStringField("manifestSha256", manifestHash); json.writeEndObject(); json.flush();
      Files.write(PROOF, bytes.toByteArray());
    }
  }

  private static void assertSafeName(String value) {
    assertFalse(value.contains("/") || value.contains("\\") || value.contains("..") || !value.endsWith(".json"));
  }

  @SuppressWarnings("unchecked") private static Map<String, Object> object(Object value) { return (Map<String, Object>) value; }
  @SuppressWarnings("unchecked") private static List<Object> list(Object value) { return (List<Object>) value; }
  private static String sha256(Path file) throws Exception {
    StringBuilder result = new StringBuilder();
    for (byte value : MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file))) result.append(String.format("%02x", value));
    return result.toString();
  }
}
