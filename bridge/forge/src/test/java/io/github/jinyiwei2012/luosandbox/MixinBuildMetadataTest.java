package io.github.jinyiwei2012.luosandbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MixinBuildMetadataTest {
  private static final Path CONFIG = Path.of("src", "main", "resources", "luosandbox.mixins.json");
  private static final Path MIXIN =
      Path.of(
          "src",
          "main",
          "java",
          "io",
          "github",
          "jinyiwei2012",
          "luosandbox",
          "mixin",
          "server",
          "ServerGamePacketListenerImplMixin.java");

  @Test
  void mixinConfigurationIsRequiredJava17AndNamesTheSingleServerProbe() throws Exception {
    Map<String, Object> values = readTopLevelConfig();
    assertEquals(Boolean.TRUE, values.get("required"));
    assertEquals("0.8.5", values.get("minVersion"));
    assertEquals("JAVA_17", values.get("compatibilityLevel"));
    assertEquals("luosandbox.refmap.json", values.get("refmap"));
    assertEquals("server.ServerGamePacketListenerImplMixin", values.get("mixin"));
    assertEquals(1, values.get("defaultRequire"));
  }

  @Test
  void movementDefenseUsesPostThreadHandoffAnchorsAndNoUnsafeHandlers() throws Exception {
    String source = Files.readString(MIXIN);
    String[] handlers = {
      "handleMovePlayer", "handleMoveVehicle", "handlePlayerAction", "handleUseItemOn",
      "handleUseItem", "handleInteract", "handleContainerClick", "handlePlaceRecipe",
      "handleContainerButtonClick", "handleSetCreativeModeSlot", "handlePlayerAbilities",
      "handlePlayerCommand", "handleAnimate", "handleRecipeBookSeenRecipePacket",
      "handleRecipeBookChangeSettingsPacket", "handlePlayerInput", "handlePaddleBoat",
      "handleSetCarriedItem"
    };
    for (String handler : handlers) {
      assertTrue(source.contains("method = \"" + handler + "\""), handler);
    }
    assertTrue(source.contains("PacketUtils;ensureRunningOnSameThread("));
    assertTrue(source.contains("shift = At.Shift.AFTER"));
    assertTrue(source.contains("require = 1"));
    assertTrue(source.contains("allow = 1"));
    assertEquals(18, source.split("shift = At.Shift.AFTER", -1).length - 1);
    assertEquals(23, source.split("require = 1", -1).length - 1);
    assertEquals(23, source.split("allow = 1", -1).length - 1);
    assertEquals(23, source.split("cancellable = true", -1).length - 1);
    assertTrue(source.contains("ForgePacketDefenseBridge.handleMovePlayer"));
    assertTrue(source.contains("ForgePacketDefenseBridge.handleMoveVehicle"));
    assertTrue(source.contains("implements ControlledConnectionMarker"));
    assertTrue(source.contains("@Unique private boolean luosandbox$everControlled"));
    assertFalse(source.contains("luosandbox$everControlled = false"));
    assertFalse(source.contains("handleCustomPayload"));
    assertFalse(source.contains("net.minecraft.client"));
  }

  @Test
  void stableDeferredHelpersUseExactDescriptorsAndListenerFirstDenial() throws Exception {
    String source = Files.readString(MIXIN);
    String[] selectors = {
      "broadcastChatMessage(Lnet/minecraft/network/chat/PlayerChatMessage;)V",
      "performChatCommand(Lnet/minecraft/network/protocol/game/ServerboundChatCommandPacket;Lnet/minecraft/network/chat/LastSeenMessages;)V",
      "updateBookContents(Ljava/util/List;I)V",
      "signBook(Lnet/minecraft/server/network/FilteredText;Ljava/util/List;I)V",
      "updateSignText(Lnet/minecraft/network/protocol/game/ServerboundSignUpdatePacket;Ljava/util/List;)V"
    };
    for (String selector : selectors) {
      assertTrue(source.contains("method = \"" + selector + "\""), selector);
    }
    assertEquals(5, source.split(java.util.regex.Pattern.quote("at = @At(\"HEAD\")"), -1).length - 1);
    assertTrue(source.contains("denyDeferred(ControlledPacketGuard.PacketCategory.CHAT, callback)"));
    assertTrue(source.contains("denyDeferred(ControlledPacketGuard.PacketCategory.COMMAND, callback)"));
    assertEquals(2, source.split(java.util.regex.Pattern.quote("denyDeferred(ControlledPacketGuard.PacketCategory.BOOK_EDIT, callback)"), -1).length - 1);
    assertTrue(source.contains("denyDeferred(ControlledPacketGuard.PacketCategory.SIGN_UPDATE, callback)"));
    assertTrue(source.contains("handleDeferredGameplay(server, () -> this, () -> player, category)"));
    assertTrue(source.contains("== ControlledPacketGuard.Decision.DENY) callback.cancel()"));
    assertFalse(source.contains("remap = false"));
    assertFalse(source.contains("@Dynamic"));
    assertFalse(source.contains("lambda$handle"));
  }

  private static Map<String, Object> readTopLevelConfig() throws Exception {
    Map<String, Object> values = new HashMap<>();
    try (JsonParser parser = new JsonFactory().createParser(Files.readAllBytes(CONFIG))) {
      assertEquals(JsonToken.START_OBJECT, parser.nextToken());
      while (parser.nextToken() != JsonToken.END_OBJECT) {
        String name = parser.currentName();
        JsonToken value = parser.nextToken();
        if (value == JsonToken.VALUE_TRUE || value == JsonToken.VALUE_FALSE) {
          values.put(name, parser.getBooleanValue());
        } else if (value == JsonToken.VALUE_STRING) {
          values.put(name, parser.getValueAsString());
        } else if ("mixins".equals(name) && value == JsonToken.START_ARRAY) {
          assertEquals(JsonToken.VALUE_STRING, parser.nextToken());
          values.put("mixin", parser.getValueAsString());
          assertEquals(JsonToken.END_ARRAY, parser.nextToken());
        } else if ("injectors".equals(name) && value == JsonToken.START_OBJECT) {
          assertEquals(JsonToken.FIELD_NAME, parser.nextToken());
          assertEquals("defaultRequire", parser.currentName());
          assertEquals(JsonToken.VALUE_NUMBER_INT, parser.nextToken());
          values.put("defaultRequire", parser.getIntValue());
          assertEquals(JsonToken.END_OBJECT, parser.nextToken());
        } else {
          parser.skipChildren();
        }
      }
    }
    return values;
  }
}
