package io.github.jinyiwei2012.luosandbox.mixin.server;

import io.github.jinyiwei2012.luosandbox.server.ControlledPacketGuard;
import io.github.jinyiwei2012.luosandbox.server.ForgePacketDefenseBridge;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundMoveVehiclePacket;
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
  @Shadow @Final private MinecraftServer server;
  @Shadow public ServerPlayer player;

  @Inject(
      method = "handleMovePlayer",
      at =
          @At(
              value = "INVOKE",
              target =
                  "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread("
                      + "Lnet/minecraft/network/protocol/Packet;"
                      + "Lnet/minecraft/network/PacketListener;"
                      + "Lnet/minecraft/server/level/ServerLevel;)V",
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
              target =
                  "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread("
                      + "Lnet/minecraft/network/protocol/Packet;"
                      + "Lnet/minecraft/network/PacketListener;"
                      + "Lnet/minecraft/server/level/ServerLevel;)V",
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
}
