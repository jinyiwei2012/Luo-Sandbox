package io.github.jinyiwei2012.luosandbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class ModMetadataTest {
  @Test
  void metadataBindsTheProjectModIdAndVersionWithoutLoadingForge() throws IOException {
    String modsToml = readResource("META-INF/mods.toml");
    String packMetadata = readResource("pack.mcmeta");

    assertTrue(ModIdentity.MOD_ID.matches("[a-z][a-z0-9_]{1,63}"));
    assertTrue(modsToml.contains("modId=\"" + ModIdentity.MOD_ID + "\""));
    assertTrue(modsToml.contains("version=\"" + ModIdentity.MOD_VERSION + "\""));
    assertTrue(modsToml.contains("loaderVersion=\"[47,)\""));
    assertTrue(modsToml.contains("modId=\"forge\""));
    assertTrue(modsToml.contains("versionRange=\"[47,)\""));
    assertTrue(modsToml.contains("modId=\"minecraft\""));
    assertTrue(modsToml.contains("versionRange=\"[1.20.1,1.21)\""));
    assertTrue(packMetadata.contains("\"pack_format\": 15"));
    assertTrue(!modsToml.contains("${"));
    assertEquals("luosandbox", ModIdentity.MOD_ID);
  }

  private static String readResource(String resourceName) throws IOException {
    try (InputStream stream = ModMetadataTest.class.getClassLoader().getResourceAsStream(resourceName)) {
      if (stream == null) {
        throw new IOException("Missing test resource: " + resourceName);
      }
      return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    }
  }
}
