package io.github.jinyiwei2012.luosandbox.mixin.server;

import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Build-time injection probe anchored after vanilla transfers movement handling to the server thread.
 *
 * <p>The callback intentionally reads no world or connection state and performs no mutation. Runtime
 * application and injector cardinality remain future startup evidence, not a claim of this probe.
 */
@Mixin(ServerGamePacketListenerImpl.class)
abstract class ServerGamePacketListenerImplMixin {
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
      allow = 1)
  private void luosandbox$afterMovementThreadHandoff(
      ServerboundMovePlayerPacket packet, CallbackInfo callback) {}
}
