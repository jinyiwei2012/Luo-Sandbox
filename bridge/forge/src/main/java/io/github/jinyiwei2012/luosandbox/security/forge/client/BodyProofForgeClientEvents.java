package io.github.jinyiwei2012.luosandbox.security.forge.client;

import io.github.jinyiwei2012.luosandbox.security.BodyProofClientOwner;
import io.github.jinyiwei2012.luosandbox.security.forge.BodyProofChannelRegistration;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import net.minecraft.network.Connection;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;

/**
 * Explicit, unregistered logout adapter for one immutable BODY client generation.
 *
 * <p>The event-carried connection must be the captured connection reference. This adapter never
 * falls back to current Minecraft/player state and never schedules cleanup after logout.
 */
public final class BodyProofForgeClientEvents {
  private final Core core;

  public BodyProofForgeClientEvents(
      BodyProofChannelRegistration<?, Connection> registration,
      BodyProofChannelRegistration.ClientHandle<Connection> client,
      Connection exactConnection,
      BodyProofClientOwner.TrustedThreadGate clientThread) {
    Objects.requireNonNull(registration);
    Objects.requireNonNull(client);
    Objects.requireNonNull(exactConnection);
    Objects.requireNonNull(clientThread);
    core = new Core(
        exactConnection, clientThread::isOwnerThread, () -> registration.uninstallClient(client));
  }

  /** Returns true only when this exact generation closed; false leaves ownership unchanged. */
  public boolean onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
    return core.loggingOut(event == null ? null : event.getConnection());
  }

  /** Package-private seam avoids bootstrapping client classes in lifecycle unit tests. */
  static final class Core {
    private final Object connection;
    private final BooleanSupplier thread;
    private final BooleanSupplier uninstall;

    Core(Object connection, BooleanSupplier thread, BooleanSupplier uninstall) {
      this.connection = Objects.requireNonNull(connection);
      this.thread = Objects.requireNonNull(thread);
      this.uninstall = Objects.requireNonNull(uninstall);
    }

    boolean loggingOut(Object eventConnection) {
      if (eventConnection == null || eventConnection != connection) return false;
      try {
        if (!thread.getAsBoolean()) return false;
      } catch (RuntimeException error) {
        return false;
      }
      try {
        return uninstall.getAsBoolean();
      } catch (RuntimeException error) {
        return false;
      }
    }
  }
}
