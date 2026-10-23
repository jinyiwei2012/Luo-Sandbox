package io.github.jinyiwei2012.luosandbox;

import io.github.jinyiwei2012.luosandbox.server.ForgeServerDefense;
import io.github.jinyiwei2012.luosandbox.server.ForgePacketDefenseBridge;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** Forge loading boundary. Server defense remains disabled unless explicitly opted in. */
@Mod(ModIdentity.MOD_ID)
public final class LuoSandboxMod {
  public LuoSandboxMod() {
    if (serverDefenseEnabled()) {
      MinecraftForge.EVENT_BUS.register(new ServerDefenseLifecycle());
    }
  }

  static boolean serverDefenseEnabled() {
    return Boolean.getBoolean(ForgeServerDefense.ENABLE_PROPERTY);
  }

  static final class ServerDefenseLifecycle {
    private ForgeServerDefense defense;
    private ForgePacketDefenseBridge.OwnerHandle<net.minecraft.server.MinecraftServer> owner;

    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent event) {
      ForgeServerDefense candidate = ForgeServerDefense.inert();
      var installed = ForgePacketDefenseBridge.installInert(event.getServer(), candidate.registry());
      if (installed.isEmpty()) return;
      owner = installed.get();
      defense = candidate;
      MinecraftForge.EVENT_BUS.register(candidate);
    }

    @SubscribeEvent
    public void onServerStopped(ServerStoppedEvent event) {
      if (defense == null || owner == null || !owner.uninstall(event.getServer())) return;
      MinecraftForge.EVENT_BUS.unregister(defense);
      defense = null;
      owner = null;
    }
  }
}
