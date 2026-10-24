package io.github.jinyiwei2012.luosandbox.mixin.server;

import io.github.jinyiwei2012.luosandbox.server.ControlledPacketGuard;
import io.github.jinyiwei2012.luosandbox.server.ControlledConnectionMarker;
import io.github.jinyiwei2012.luosandbox.server.ForgePacketDefenseBridge;
import java.util.List;
import net.minecraft.network.chat.LastSeenMessages;
import net.minecraft.network.chat.PlayerChatMessage;
import net.minecraft.network.protocol.game.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.server.network.FilteredText;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
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
abstract class ServerGamePacketListenerImplMixin implements ControlledConnectionMarker {
  private static final String THREAD_HANDOFF =
      "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread("
          + "Lnet/minecraft/network/protocol/Packet;"
          + "Lnet/minecraft/network/PacketListener;"
          + "Lnet/minecraft/server/level/ServerLevel;)V";
  @Shadow @Final private MinecraftServer server;
  @Shadow public ServerPlayer player;
  @Unique private boolean luosandbox$everControlled;

  @Override
  public boolean luosandbox$everControlled() {
    return luosandbox$everControlled;
  }

  @Override
  public void luosandbox$markEverControlled() {
    luosandbox$everControlled = true;
  }

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
    if (ForgePacketDefenseBridge.enterMovePlayer(server, () -> this, () -> player, packet)
        == ControlledPacketGuard.Decision.DENY) {
      callback.cancel();
    }
  }

  // RETURN targets every normal return opcode, so the injection count is intentionally unbounded
  @Inject(method = "handleMovePlayer", at = @At("RETURN"), require = 1)
  private void luosandbox$movementHandlerReturned(
      ServerboundMovePlayerPacket packet, CallbackInfo callback) {
    ForgePacketDefenseBridge.returnMovePlayer(server, this);
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

  @Inject(
      method = "broadcastChatMessage(Lnet/minecraft/network/chat/PlayerChatMessage;)V",
      at = @At("HEAD"), require = 1, allow = 1, cancellable = true)
  private void luosandbox$denyDeferredChat(PlayerChatMessage message, CallbackInfo callback) {
    denyDeferred(ControlledPacketGuard.PacketCategory.CHAT, callback);
  }

  @Inject(
      method = "performChatCommand(Lnet/minecraft/network/protocol/game/ServerboundChatCommandPacket;Lnet/minecraft/network/chat/LastSeenMessages;)V",
      at = @At("HEAD"), require = 1, allow = 1, cancellable = true)
  private void luosandbox$denyDeferredCommand(
      ServerboundChatCommandPacket packet, LastSeenMessages lastSeen, CallbackInfo callback) {
    denyDeferred(ControlledPacketGuard.PacketCategory.COMMAND, callback);
  }

  @Inject(
      method = "updateBookContents(Ljava/util/List;I)V",
      at = @At("HEAD"), require = 1, allow = 1, cancellable = true)
  private void luosandbox$denyDeferredBook(
      List<FilteredText> filtered, int slot, CallbackInfo callback) {
    denyDeferred(ControlledPacketGuard.PacketCategory.BOOK_EDIT, callback);
  }

  @Inject(
      method = "signBook(Lnet/minecraft/server/network/FilteredText;Ljava/util/List;I)V",
      at = @At("HEAD"), require = 1, allow = 1, cancellable = true)
  private void luosandbox$denyDeferredSignedBook(
      FilteredText title, List<FilteredText> filtered, int slot, CallbackInfo callback) {
    denyDeferred(ControlledPacketGuard.PacketCategory.BOOK_EDIT, callback);
  }

  @Inject(
      method = "updateSignText(Lnet/minecraft/network/protocol/game/ServerboundSignUpdatePacket;Ljava/util/List;)V",
      at = @At("HEAD"), require = 1, allow = 1, cancellable = true)
  private void luosandbox$denyDeferredSign(
      ServerboundSignUpdatePacket packet, List<FilteredText> filtered, CallbackInfo callback) {
    denyDeferred(ControlledPacketGuard.PacketCategory.SIGN_UPDATE, callback);
  }

  private void denyDeferred(ControlledPacketGuard.PacketCategory category, CallbackInfo callback) {
    if (ForgePacketDefenseBridge.handleDeferredGameplay(server, () -> this, () -> player, category)
        == ControlledPacketGuard.Decision.DENY) callback.cancel();
  }
}
