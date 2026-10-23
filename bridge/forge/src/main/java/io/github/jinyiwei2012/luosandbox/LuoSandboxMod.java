package io.github.jinyiwei2012.luosandbox;

import io.github.jinyiwei2012.luosandbox.server.ForgeServerDefense;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;

/** Forge loading boundary. Server defense remains disabled unless explicitly opted in. */
@Mod(ModIdentity.MOD_ID)
public final class LuoSandboxMod {
  public LuoSandboxMod() {
    if (serverDefenseEnabled()) {
      MinecraftForge.EVENT_BUS.register(ForgeServerDefense.inert());
    }
  }

  static boolean serverDefenseEnabled() {
    return Boolean.getBoolean(ForgeServerDefense.ENABLE_PROPERTY);
  }
}
