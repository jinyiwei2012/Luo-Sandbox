package io.github.jinyiwei2012.luosandbox.security;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Actual Java parser half of the local frozen-manifest interop harness. */
class DeploymentManifestInteropTest {
  private static final String SCHEMA = "deployment-manifest-v2-interop-v1";
  private static final HexFormat HEX = HexFormat.of();

  @Test
  void consumesPythonGeneratedCasesAndExportsActualJavaResults() throws Exception {
    String inputValue = System.getenv("LUOSANDBOX_DEPLOYMENT_MANIFEST_INPUT");
    String outputValue = System.getenv("LUOSANDBOX_DEPLOYMENT_MANIFEST_OUTPUT");
    if (inputValue == null || inputValue.isBlank() || outputValue == null || outputValue.isBlank()) {
      return; // Explicit interop invocation supplies both paths; ordinary suite remains offline.
    }
    Path input = approved(Path.of(inputValue));
    Path output = approved(Path.of(outputValue));
    List<Map<String, Object>> cases = readCases(input);
    assertEquals(22, cases.size());
    List<Result> results = new ArrayList<>();
    for (Map<String, Object> item : cases) results.add(run(item));
    Files.createDirectories(output.getParent());
    try (JsonGenerator json = new JsonFactory().createGenerator(Files.newOutputStream(output))) {
      json.writeStartObject();
      json.writeStringField("schema", SCHEMA);
      json.writeObjectFieldStart("invocation");
      json.writeStringField("testClass", DeploymentManifestInteropTest.class.getName());
      json.writeBooleanField("forcedRerunRequired", true);
      json.writeStringField("executionAttestation", "SEPARATE_RUNNER_OBSERVATION_REQUIRED");
      json.writeEndObject();
      json.writeObjectFieldStart("loadedClassIdentity");
      json.writeStringField("resource",
          String.valueOf(ControlDeploymentManifest.class.getResource("ControlDeploymentManifest.class")));
      try (var stream = ControlDeploymentManifest.class.getResourceAsStream("ControlDeploymentManifest.class")) {
        assertNotNull(stream);
        json.writeStringField("sha256",
            HEX.formatHex(MessageDigest.getInstance("SHA-256").digest(stream.readAllBytes())));
      }
      json.writeEndObject();
      json.writeArrayFieldStart("cases");
      for (Result result : results) result.write(json);
      json.writeEndArray();
      json.writeEndObject();
    }
  }

  private static Result run(Map<String, Object> value) {
    String name = string(value, "name");
    byte[] raw = HEX.parseHex(string(value, "rawHex"));
    boolean expectedAccepted = bool(value, "accepted");
    try {
      ControlDeploymentManifest manifest = ControlDeploymentManifest.parse(raw);
      boolean trusted = false;
      if (value.containsKey("trustedVerified")) {
        try {
          manifest.verifyArtifacts(ControlAuth.Role.valueOf(string(value, "role")),
              string(value, "peerInstallId"), string(value, "hubInstallId"),
              HEX.parseHex(string(value, "configHashHex")),
              HEX.parseHex(string(value, "modHex")), HEX.parseHex(string(value, "policyHex")),
              HEX.parseHex(string(value, "bindingHex")));
          trusted = true;
        } catch (IllegalArgumentException error) {
          trusted = false;
        }
      }
      assertTrue(expectedAccepted, "Java accepted expected-negative case " + name);
      return new Result(name, true, HEX.formatHex(manifest.encode()),
          HEX.formatHex(manifest.configHash()), trusted);
    } catch (IllegalArgumentException error) {
      assertFalse(expectedAccepted, "Java rejected expected-valid case " + name);
      return new Result(name, false, null, null, null);
    }
  }

  private static Path approved(Path requested) {
    Path path = requested.toAbsolutePath().normalize();
    Path approved = Path.of("..", "..", ".gradle-user-home", "interop").toAbsolutePath().normalize();
    assertTrue(path.startsWith(approved), "interop files must remain in ignored Gradle home");
    return path;
  }

  private static List<Map<String, Object>> readCases(Path input) throws Exception {
    try (JsonParser parser = new JsonFactory().createParser(Files.readAllBytes(input))) {
      assertEquals(JsonToken.START_OBJECT, parser.nextToken());
      assertEquals("cases", parser.nextFieldName());
      assertEquals(JsonToken.START_ARRAY, parser.nextToken());
      List<Map<String, Object>> cases = new ArrayList<>();
      while (parser.nextToken() != JsonToken.END_ARRAY) {
        assertEquals(JsonToken.START_OBJECT, parser.currentToken());
        Map<String, Object> value = new LinkedHashMap<>();
        while (parser.nextToken() != JsonToken.END_OBJECT) {
          String name = parser.currentName();
          JsonToken token = parser.nextToken();
          Object field = switch (token) {
            case VALUE_STRING -> parser.getText();
            case VALUE_TRUE -> Boolean.TRUE;
            case VALUE_FALSE -> Boolean.FALSE;
            default -> throw new IllegalArgumentException("unexpected interop field type");
          };
          assertNull(value.put(name, field), "duplicate interop field");
        }
        cases.add(value);
      }
      assertEquals("schema", parser.nextFieldName());
      assertEquals(SCHEMA, parser.nextTextValue());
      assertEquals(JsonToken.END_OBJECT, parser.nextToken());
      assertNull(parser.nextToken());
      return cases;
    }
  }

  private static String string(Map<String, Object> value, String name) {
    return assertInstanceOf(String.class, value.get(name));
  }

  private static boolean bool(Map<String, Object> value, String name) {
    return assertInstanceOf(Boolean.class, value.get(name));
  }

  private record Result(String name, boolean accepted, String encodedRawHex,
      String configHashHex, Boolean trustedVerified) {
    void write(JsonGenerator json) throws Exception {
      json.writeStartObject();
      json.writeStringField("name", name);
      json.writeBooleanField("accepted", accepted);
      if (accepted) {
        json.writeStringField("encodedRawHex", encodedRawHex);
        json.writeStringField("configHashHex", configHashHex);
        json.writeBooleanField("trustedVerified", trustedVerified);
      }
      json.writeEndObject();
    }
  }
}
