package io.github.jinyiwei2012.luosandbox.security.forge;

import io.github.jinyiwei2012.luosandbox.security.BodyProofIngress;
import io.github.jinyiwei2012.luosandbox.security.PendingBodyProof;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;

/** Explicit offline adapter core; no channel, enqueue, sender lookup or authority activation. */
public final class BodyProofChannelHandler<S, C> {
  public enum Direction { PLAY_TO_SERVER, OTHER }
  public enum Decision { VERIFIED, REJECTED, WRONG_THREAD, STALE_OWNER }
  public record Context<C>(Direction direction, C connection) {}
  @FunctionalInterface public interface Observer<C> {
    void verified(PendingBodyProof.VerifiedGameConnectionProof<C> proof, C exactConnection);
  }
  @FunctionalInterface public interface ConnectionCloser<C> { void close(C connection); }

  public static final class OwnerHandle<S, C> {
    private final BodyProofChannelHandler<S, C> manager;
    private final S server;
    private OwnerHandle(BodyProofChannelHandler<S, C> manager, S server) {
      this.manager = manager; this.server = server;
    }
    @Override public String toString() { return "BodyProofOwnerHandle[redacted]"; }
  }

  private final BodyProofIngress.TrustedThreadGate thread;
  private final ConnectionCloser<C> closer;
  private S server;
  private OwnerHandle<S, C> handle;
  private BodyProofIngress<C> ingress;
  private Observer<C> observer;

  public BodyProofChannelHandler(BodyProofIngress.TrustedThreadGate thread, ConnectionCloser<C> closer) {
    this.thread = Objects.requireNonNull(thread);
    this.closer = Objects.requireNonNull(closer);
  }

  /** Production connection type and fixed sanitized close reason; does not register anything. */
  public static <S> BodyProofChannelHandler<S, Connection> forConnections(
      BodyProofIngress.TrustedThreadGate thread) {
    return new BodyProofChannelHandler<>(thread,
        connection -> connection.disconnect(Component.literal("BODY proof rejected")));
  }

  public Optional<OwnerHandle<S, C>> install(S server, BodyProofIngress<C> ingress, Observer<C> observer) {
    if (!onThread()) return Optional.empty();
    synchronized (this) {
      Objects.requireNonNull(server); Objects.requireNonNull(ingress); Objects.requireNonNull(observer);
      if (handle != null) {
        return this.server == server && this.ingress == ingress && this.observer == observer
            ? Optional.of(handle) : Optional.empty();
      }
      this.server = server; this.ingress = ingress; this.observer = observer;
      handle = new OwnerHandle<>(this, server);
      return Optional.of(handle);
    }
  }

  /** Captured generation must be rechecked at execution, before resolving any lazy context. */
  public Decision dispatch(S server, OwnerHandle<S, C> captured,
      Supplier<Context<C>> context, BodyProofNetworkFrame frame) {
    if (!onThread()) return Decision.WRONG_THREAD;
    synchronized (this) {
      if (!owns(server, captured)) return Decision.STALE_OWNER;
      BodyProofIngress<C> capturedIngress = ingress;
      Observer<C> capturedObserver = observer;
      Context<C> actual;
      try { actual = context.get(); }
      catch (RuntimeException error) { return Decision.REJECTED; }
      if (actual == null || actual.connection() == null) return Decision.REJECTED;
      C connection = actual.connection();
      if (!owns(server, captured) || ingress != capturedIngress) return Decision.STALE_OWNER;
      if (actual.direction() != Direction.PLAY_TO_SERVER) {
        capturedIngress.disconnect(() -> connection);
        closeExact(connection);
        return Decision.REJECTED;
      }
      var result = capturedIngress.handleProof(() -> connection,
          frame == null || frame.malformed() ? null : frame.raw());
      if (!owns(server, captured) || ingress != capturedIngress) {
        result.proof().ifPresent(capturedIngress::invalidateVerified);
        return Decision.STALE_OWNER;
      }
      if (result.status() == BodyProofIngress.Status.VERIFIED) {
        var proof = result.proof().orElseThrow();
        try {
          capturedObserver.verified(proof, connection);
        } catch (Throwable error) {
          Throwable cleanup = null;
          try { capturedIngress.invalidateVerified(proof); }
          catch (Throwable failure) { cleanup = remember(cleanup, failure); }
          try { closer.close(connection); }
          catch (Throwable failure) { cleanup = remember(cleanup, failure); }
          if (error instanceof Error fatal) {
            if (cleanup != null && cleanup != fatal) fatal.addSuppressed(cleanup);
            throw fatal;
          }
          if (cleanup instanceof Error fatal) throw fatal;
          return Decision.REJECTED;
        }
        if (!owns(server, captured) || ingress != capturedIngress) {
          capturedIngress.invalidateVerified(proof);
          return Decision.STALE_OWNER;
        }
        return Decision.VERIFIED;
      }
      if (result.status() != BodyProofIngress.Status.WRONG_THREAD) closeExact(connection);
      return result.status() == BodyProofIngress.Status.WRONG_THREAD
          ? Decision.WRONG_THREAD : Decision.REJECTED;
    }
  }

  public boolean disconnect(S server, OwnerHandle<S, C> owner, Supplier<C> connection) {
    if (!onThread()) return false;
    synchronized (this) { return owns(server, owner) && ingress.disconnect(connection); }
  }

  public boolean uninstall(S server, OwnerHandle<S, C> owner) {
    if (!onThread()) return false;
    synchronized (this) {
      if (!owns(server, owner)) return false;
      try { if (!ingress.close()) return false; }
      catch (RuntimeException error) { return false; }
      this.server = null; handle = null; ingress = null; observer = null;
      return true;
    }
  }

  private boolean owns(S server, OwnerHandle<S, C> candidate) {
    return candidate != null && candidate == handle && candidate.manager == this
        && candidate.server == server && this.server == server;
  }
  private boolean onThread() {
    try { return thread.isOwnerThread(); }
    catch (RuntimeException error) { return false; }
  }
  private void closeExact(C connection) {
    try { closer.close(connection); }
    catch (RuntimeException ignored) { /* Failure is rejection, never observer retry or authority */ }
  }
  private static Throwable remember(Throwable first, Throwable next) {
    if (first == null) return next;
    if (!(first instanceof Error) && next instanceof Error) {
      next.addSuppressed(first); return next;
    }
    first.addSuppressed(next); return first;
  }
}
