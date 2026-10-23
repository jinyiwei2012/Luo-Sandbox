package io.github.jinyiwei2012.luosandbox.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.junit.jupiter.api.Test;

class ForgeServerDefenseTest {
  @Test
  void declaresEveryRequiredDefaultDenyAction() {
    assertEquals(
        EnumSet.of(
            ForgeServerDefense.Action.LEFT_CLICK_BLOCK,
            ForgeServerDefense.Action.RIGHT_CLICK_BLOCK,
            ForgeServerDefense.Action.RIGHT_CLICK_ITEM,
            ForgeServerDefense.Action.ENTITY_INTERACT,
            ForgeServerDefense.Action.ENTITY_ATTACK,
            ForgeServerDefense.Action.ITEM_TOSS,
            ForgeServerDefense.Action.ITEM_PICKUP,
            ForgeServerDefense.Action.CHAT,
            ForgeServerDefense.Action.DIMENSION_TRAVEL),
        EnumSet.allOf(ForgeServerDefense.Action.class));
  }

  @Test
  void registersExpectedForgeEventHandlersWithoutClientTypes() {
    Set<String> handlers =
        java.util.Arrays.stream(ForgeServerDefense.class.getDeclaredMethods())
            .filter(method -> method.isAnnotationPresent(SubscribeEvent.class))
            .map(Method::getName)
            .collect(Collectors.toSet());
    assertTrue(
        handlers.containsAll(
            Set.of(
                "onLeftClickBlock",
                "onRightClickBlock",
                "onRightClickItem",
                "onEntityInteract",
                "onEntityInteractSpecific",
                "onEntityAttack",
                "onItemToss",
                "onItemPickup",
                "onChat",
                "onDimensionTravel",
                "onContainerOpen",
                "onLogout",
                "onClone",
                "onRespawn",
                "onChangedDimension",
                "onPlayerTick")));
    for (Method handler : ForgeServerDefense.class.getDeclaredMethods()) {
      for (Class<?> parameter : handler.getParameterTypes()) {
        assertTrue(!parameter.getName().startsWith("net.minecraft.client"));
      }
    }
  }

  @Test
  void quarantinedCandidateRemainsDefaultDeniedWithoutCurrentBinding() {
    assertTrue(
        ForgeServerDefense.shouldDeny(
            true,
            Optional.empty(),
            (binding, action) -> false,
            ForgeServerDefense.Action.RIGHT_CLICK_ITEM));
  }

  @Test
  void saveToFileIsNotRegisteredAsAuthorityLoss() {
    Set<Class<?>> handledEvents =
        java.util.Arrays.stream(ForgeServerDefense.class.getDeclaredMethods())
            .filter(method -> method.isAnnotationPresent(SubscribeEvent.class))
            .flatMap(method -> java.util.Arrays.stream(method.getParameterTypes()))
            .collect(Collectors.toSet());
    assertTrue(
        handledEvents.stream()
            .noneMatch(type -> type.getName().endsWith("PlayerEvent$SaveToFile")));
  }
}
