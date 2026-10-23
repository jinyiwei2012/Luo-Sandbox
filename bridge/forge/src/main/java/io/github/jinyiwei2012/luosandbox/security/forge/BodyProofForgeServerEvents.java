package io.github.jinyiwei2012.luosandbox.security.forge;

import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import net.minecraft.network.Connection;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;

/**
 * Explicit, unregistered stop-only Forge event adapter for one immutable server generation.
 * No logout connection capture or actual event-bus delivery is established by this adapter.
 */
public final class BodyProofForgeServerEvents {
  public enum EventResult { CLOSED, QUARANTINED, WRONG_THREAD, NOT_OWNER, STOPPING_NOT_OBSERVED }

  private final Core core;

  public BodyProofForgeServerEvents(MinecraftServer server,
      BodyProofServerLifecycle<MinecraftServer, Connection> lifecycle,
      BodyProofServerLifecycle.OwnerHandle<MinecraftServer, Connection> owner) {
    Objects.requireNonNull(server);
    Objects.requireNonNull(lifecycle);
    Objects.requireNonNull(owner);
    core = new Core(server, server::isSameThread,
        () -> lifecycle.onServerStopping(server, owner),
        () -> lifecycle.onServerStopped(server, owner));
  }

  public EventResult onServerStopping(ServerStoppingEvent event) {
    return core.stopping(event == null ? null : event.getServer());
  }

  public EventResult onServerStopped(ServerStoppedEvent event) {
    return core.stopped(event == null ? null : event.getServer());
  }

  /** Tiny package-private state seam so tests need no Minecraft server/world bootstrap. */
  static final class Core {
    private final Object server;
    private final BooleanSupplier thread;
    private final Supplier<BodyProofServerLifecycle.StopResult> stopping;
    private final Supplier<BodyProofServerLifecycle.StopResult> stopped;
    private boolean stoppingAccepted;
    private EventResult lastResult;

    Core(Object server, BooleanSupplier thread,
        Supplier<BodyProofServerLifecycle.StopResult> stopping,
        Supplier<BodyProofServerLifecycle.StopResult> stopped) {
      this.server = Objects.requireNonNull(server);
      this.thread = Objects.requireNonNull(thread);
      this.stopping = Objects.requireNonNull(stopping);
      this.stopped = Objects.requireNonNull(stopped);
    }

    EventResult stopping(Object eventServer) {
      EventResult rejected = gate(eventServer);
      if (rejected != null) return rejected;
      if (lastResult == EventResult.CLOSED) return EventResult.CLOSED;
      EventResult result = cleanup(stopping);
      if (result == EventResult.CLOSED || result == EventResult.QUARANTINED) {
        stoppingAccepted = true;
        lastResult = result;
      }
      return result;
    }

    EventResult stopped(Object eventServer) {
      EventResult rejected = gate(eventServer);
      if (rejected != null) return rejected;
      if (!stoppingAccepted) return EventResult.STOPPING_NOT_OBSERVED;
      if (lastResult == EventResult.CLOSED) return EventResult.CLOSED;
      EventResult result = cleanup(stopped);
      if (result == EventResult.CLOSED || result == EventResult.QUARANTINED) lastResult = result;
      return result;
    }

    private EventResult gate(Object eventServer) {
      if (eventServer == null || eventServer != server) return EventResult.NOT_OWNER;
      try { return thread.getAsBoolean() ? null : EventResult.WRONG_THREAD; }
      catch (RuntimeException error) { return EventResult.WRONG_THREAD; }
    }

    private EventResult cleanup(Supplier<BodyProofServerLifecycle.StopResult> operation) {
      try {
        return switch (operation.get()) {
          case CLOSED -> EventResult.CLOSED;
          case QUARANTINED -> EventResult.QUARANTINED;
          case WRONG_THREAD -> EventResult.WRONG_THREAD;
          case NOT_OWNER -> EventResult.NOT_OWNER;
        };
      } catch (RuntimeException error) {
        return EventResult.QUARANTINED;
      }
    }
  }
}
