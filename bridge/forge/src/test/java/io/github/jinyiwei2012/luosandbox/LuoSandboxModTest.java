package io.github.jinyiwei2012.luosandbox;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.jinyiwei2012.luosandbox.server.ForgeServerDefense;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class LuoSandboxModTest {
  @AfterEach
  void clearFeatureFlag() {
    System.clearProperty(ForgeServerDefense.ENABLE_PROPERTY);
  }

  @Test
  void serverDefenseIsDisabledByDefaultAndRequiresExplicitTrue() {
    assertFalse(LuoSandboxMod.serverDefenseEnabled());
    System.setProperty(ForgeServerDefense.ENABLE_PROPERTY, "true");
    assertTrue(LuoSandboxMod.serverDefenseEnabled());
  }
}
