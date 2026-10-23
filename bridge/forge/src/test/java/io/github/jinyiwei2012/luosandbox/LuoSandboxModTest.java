package io.github.jinyiwei2012.luosandbox;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.jinyiwei2012.luosandbox.server.ForgeServerDefense;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import net.minecraftforge.eventbus.api.SubscribeEvent;

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

  @Test
  void lifecycleInstallsAndRemovesDefenseOnExactServerEvents() throws Exception {
    assertTrue(
        LuoSandboxMod.ServerDefenseLifecycle.class
            .getDeclaredMethod("onServerStarted", net.minecraftforge.event.server.ServerStartedEvent.class)
            .isAnnotationPresent(SubscribeEvent.class));
    assertTrue(
        LuoSandboxMod.ServerDefenseLifecycle.class
            .getDeclaredMethod("onServerStopped", net.minecraftforge.event.server.ServerStoppedEvent.class)
            .isAnnotationPresent(SubscribeEvent.class));
    assertTrue(
        java.util.Arrays.stream(LuoSandboxMod.ServerDefenseLifecycle.class.getDeclaredFields())
            .anyMatch(
                field ->
                    field.getType().getName().endsWith("ForgePacketDefenseBridge$OwnerHandle")));
  }
}
