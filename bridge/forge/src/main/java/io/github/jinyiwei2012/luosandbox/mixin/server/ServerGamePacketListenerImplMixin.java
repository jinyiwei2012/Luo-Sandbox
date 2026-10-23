package io.github.jinyiwei2012.luosandbox.mixin.server;

import io.github.jinyiwei2012.luosandbox.server.ControlledPacketGuard;
import io.github.jinyiwei2012.luosandbox.server.ForgePacketDefenseBridge;
import net.minecraft.network.protocol.game.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Movement defense anchored after vanilla transfers packet handling to the server thread.
 *
 * <p>The bridge remains inert without explicit installation and trusted current-state authority.
 * Runtime application and injector cardinality remain future startup evidence.
 */
@Mixin(ServerGamePacketListenerImpl.class)
abstract class ServerGamePacketListenerImplMixin {
  private static final String THREAD_HANDOFF =
      "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread("
          + "Lnet/minecraft/network/protocol/Packet;"
          + "Lnet/minecraft/network/PacketListener;"
          + "Lnet/minecraft/server/level/ServerLevel;)V";
  @Shadow @Final private MinecraftServer server;
  @Shadow public ServerPlayer player;

  @Inject(
      method = "handleMovePlayer",
      at =
          @At(
              value = "INVOKE",
              target = THREAD_HANDOFF,
              shift = At.Shift.AFTER),
      require = 1,
      allow = 1,
      cancellable = true)
  private void luosandbox$afterMovementThreadHandoff(
      ServerboundMovePlayerPacket packet, CallbackInfo callback) {
    if (ForgePacketDefenseBridge.handleMovePlayer(server, () -> player, packet)
        == ControlledPacketGuard.Decision.DENY) {
      callback.cancel();
    }
  }

  @Inject(
      method = "handleMoveVehicle",
      at =
          @At(
              value = "INVOKE",
              target = THREAD_HANDOFF,
              shift = At.Shift.AFTER),
      require = 1,
      allow = 1,
      cancellable = true)
  private void luosandbox$afterVehicleThreadHandoff(
      ServerboundMoveVehiclePacket packet, CallbackInfo callback) {
    if (ForgePacketDefenseBridge.handleMoveVehicle(server, () -> player)
        == ControlledPacketGuard.Decision.DENY) {
      callback.cancel();
    }
  }

  @Inject(method = "handlePlayerAction", at = @At(value = "INVOKE", target = THREAD_HANDOFF, shift = At.Shift.AFTER), require = 1, allow = 1, cancellable = true)
  private void luosandbox$denyPlayerAction(ServerboundPlayerActionPacket packet, CallbackInfo callback) { deny(ControlledPacketGuard.PacketCategory.MINE, callback); }

  @Inject(method = "handleUseItemOn", at = @At(value = "INVOKE", target = THREAD_HANDOFF, shift = At.Shift.AFTER), require = 1, allow = 1, cancellable = true)
  private void luosandbox$denyUseItemOn(ServerboundUseItemOnPacket packet, CallbackInfo callback) { deny(ControlledPacketGuard.PacketCategory.PLACE, callback); }

  @Inject(method = "handleUseItem", at = @At(value = "INVOKE", target = THREAD_HANDOFF, shift = At.Shift.AFTER), require = 1, allow = 1, cancellable = true)
  private void luosandbox$denyUseItem(ServerboundUseItemPacket packet, CallbackInfo callback) { deny(ControlledPacketGuard.PacketCategory.USE, callback); }

  @Inject(method = "handleInteract", at = @At(value = "INVOKE", target = THREAD_HANDOFF, shift = At.Shift.AFTER), require = 1, allow = 1, cancellable = true)
  private void luosandbox$denyInteract(ServerboundInteractPacket packet, CallbackInfo callback) { deny(ControlledPacketGuard.PacketCategory.ATTACK, callback); }

  @Inject(method = "handleContainerClick", at = @At(value = "INVOKE", target = THREAD_HANDOFF, shift = At.Shift.AFTER), require = 1, allow = 1, cancellable = true)
  private void luosandbox$denyContainerClick(ServerboundContainerClickPacket packet, CallbackInfo callback) { deny(ControlledPacketGuard.PacketCategory.INVENTORY, callback); }

  @Inject(method = "handlePlaceRecipe", at = @At(value = "INVOKE", target = THREAD_HANDOFF, shift = At.Shift.AFTER), require = 1, allow = 1, cancellable = true)
  private void luosandbox$denyPlaceRecipe(ServerboundPlaceRecipePacket packet, CallbackInfo callback) { deny(ControlledPacketGuard.PacketCategory.INVENTORY, callback); }

  @Inject(method = "handleContainerButtonClick", at = @At(value = "INVOKE", target = THREAD_HANDOFF, shift = At.Shift.AFTER), require = 1, allow = 1, cancellable = true)
  private void luosandbox$denyContainerButton(ServerboundContainerButtonClickPacket packet, CallbackInfo callback) { deny(ControlledPacketGuard.PacketCategory.INVENTORY, callback); }

  @Inject(method = "handleSetCreativeModeSlot", at = @At(value = "INVOKE", target = THREAD_HANDOFF, shift = At.Shift.AFTER), require = 1, allow = 1, cancellable = true)
  private void luosandbox$denyCreativeSlot(ServerboundSetCreativeModeSlotPacket packet, CallbackInfo callback) { deny(ControlledPacketGuard.PacketCategory.CREATIVE_INVENTORY, callback); }

  @Inject(method = "handlePlayerAbilities", at = @At(value = "INVOKE", target = THREAD_HANDOFF, shift = At.Shift.AFTER), require = 1, allow = 1, cancellable = true)
  private void luosandbox$denyAbilities(ServerboundPlayerAbilitiesPacket packet, CallbackInfo callback) { deny(ControlledPacketGuard.PacketCategory.OTHER_GAMEPLAY, callback); }

  @Inject(method = "handlePlayerCommand", at = @At(value = "INVOKE", target = THREAD_HANDOFF, shift = At.Shift.AFTER), require = 1, allow = 1, cancellable = true)
  private void luosandbox$denyPlayerCommand(ServerboundPlayerCommandPacket packet, CallbackInfo callback) { deny(ControlledPacketGuard.PacketCategory.COMMAND, callback); }

  @Inject(method = "handleAnimate", at = @At(value = "INVOKE", target = THREAD_HANDOFF, shift = At.Shift.AFTER), require = 1, allow = 1, cancellable = true)
  private void luosandbox$denyAnimate(ServerboundSwingPacket packet, CallbackInfo callback) { deny(ControlledPacketGuard.PacketCategory.OTHER_GAMEPLAY, callback); }

  @Inject(method = "handleRecipeBookSeenRecipePacket", at = @At(value = "INVOKE", target = THREAD_HANDOFF, shift = At.Shift.AFTER), require = 1, allow = 1, cancellable = true)
  private void luosandbox$denyRecipeSeen(ServerboundRecipeBookSeenRecipePacket packet, CallbackInfo callback) { deny(ControlledPacketGuard.PacketCategory.INVENTORY, callback); }

  @Inject(method = "handleRecipeBookChangeSettingsPacket", at = @At(value = "INVOKE", target = THREAD_HANDOFF, shift = At.Shift.AFTER), require = 1, allow = 1, cancellable = true)
  private void luosandbox$denyRecipeSettings(ServerboundRecipeBookChangeSettingsPacket packet, CallbackInfo callback) { deny(ControlledPacketGuard.PacketCategory.INVENTORY, callback); }

  @Inject(method = "handlePlayerInput", at = @At(value = "INVOKE", target = THREAD_HANDOFF, shift = At.Shift.AFTER), require = 1, allow = 1, cancellable = true)
  private void luosandbox$denyPlayerInput(ServerboundPlayerInputPacket packet, CallbackInfo callback) { deny(ControlledPacketGuard.PacketCategory.KEY_INPUT, callback); }

  @Inject(method = "handlePaddleBoat", at = @At(value = "INVOKE", target = THREAD_HANDOFF, shift = At.Shift.AFTER), require = 1, allow = 1, cancellable = true)
  private void luosandbox$denyPaddleBoat(ServerboundPaddleBoatPacket packet, CallbackInfo callback) { deny(ControlledPacketGuard.PacketCategory.VEHICLE_MOVEMENT, callback); }

  @Inject(method = "handleSetCarriedItem", at = @At(value = "INVOKE", target = THREAD_HANDOFF, shift = At.Shift.AFTER), require = 1, allow = 1, cancellable = true)
  private void luosandbox$denySetCarriedItem(ServerboundSetCarriedItemPacket packet, CallbackInfo callback) { deny(ControlledPacketGuard.PacketCategory.INVENTORY, callback); }

  private void deny(ControlledPacketGuard.PacketCategory category, CallbackInfo callback) {
    if (ForgePacketDefenseBridge.handleGameplay(server, () -> player, category)
        == ControlledPacketGuard.Decision.DENY) callback.cancel();
  }
}
