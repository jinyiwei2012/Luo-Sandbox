package io.github.jinyiwei2012.luosandbox.security.forge;

import io.github.jinyiwei2012.luosandbox.security.BodyProofIngress;
import io.github.jinyiwei2012.luosandbox.security.PendingBodyProof;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Explicit, unregistered server-generation lifecycle for an existing BODY registration owner.
 *
 * <p>No Forge events are subscribed here. A future adapter must prove owner-thread execution and
 * provide the historically captured exact connection on logout, not reread a replacement player's
 * connection. ServerStopped is only a cleanup retry, never proof that logout cleanup occurred.
 */
public final class BodyProofServerLifecycle<S, C> {
  public enum Stage { NEW, ACTIVE, STOPPING_QUARANTINED, CLOSED }
  public enum StopResult { CLOSED, QUARANTINED, WRONG_THREAD, NOT_OWNER }

  public static final class IssuedChallenge<S, C> {
    private final BodyProofServerLifecycle<S, C> lifecycle;
    private final OwnerHandle<S, C> owner;
    private final C connection;
    private final PendingBodyProof.BodyChallenge challenge;

    private IssuedChallenge(BodyProofServerLifecycle<S, C> lifecycle, OwnerHandle<S, C> owner,
        C connection, PendingBodyProof.BodyChallenge challenge) {
      this.lifecycle = lifecycle; this.owner = owner; this.connection = connection;
      this.challenge = challenge;
    }
    public PendingBodyProof.BodyChallenge challenge() { return challenge; }
    C exactConnection() { return connection; }
    @Override public String toString() { return "IssuedBodyChallenge[redacted]"; }
  }

  public static final class OwnerHandle<S, C> {
    private final BodyProofServerLifecycle<S, C> owner;
    private final S server;
    private final BodyProofChannelHandler.OwnerHandle<S, C> registrationHandle;

    private OwnerHandle(BodyProofServerLifecycle<S, C> owner, S server,
        BodyProofChannelHandler.OwnerHandle<S, C> registrationHandle) {
      this.owner = owner;
      this.server = server;
      this.registrationHandle = registrationHandle;
    }

    @Override public String toString() { return "BodyProofLifecycleOwner[redacted]"; }
  }

  private final BodyProofChannelRegistration<S, C> registration;
  private final BodyProofIngress.TrustedThreadGate thread;
  private Stage stage = Stage.NEW;
  private OwnerHandle<S, C> handle;
  private BodyProofIngress<C> ingress;
  private BodyProofChannelHandler.Observer<C> observer;

  public BodyProofServerLifecycle(BodyProofChannelRegistration<S, C> registration,
      BodyProofIngress.TrustedThreadGate thread) {
    this.registration = Objects.requireNonNull(registration);
    this.thread = Objects.requireNonNull(thread);
  }

  public Optional<OwnerHandle<S, C>> start(S server, BodyProofIngress<C> ingress,
      BodyProofChannelHandler.Observer<C> observer) {
    if (!onThread()) return Optional.empty();
    synchronized (this) {
      if (stage == Stage.STOPPING_QUARANTINED) return Optional.empty();
      if (stage == Stage.ACTIVE) {
        return handle.server == server && this.ingress == ingress && this.observer == observer
            ? Optional.of(handle) : Optional.empty();
      }
      try {
        var installed = registration.install(server, ingress, observer);
        if (installed.isEmpty()) return Optional.empty();
        handle = new OwnerHandle<>(this, server, installed.get());
        this.ingress = ingress;
        this.observer = observer;
        stage = Stage.ACTIVE;
        return Optional.of(handle);
      } catch (RuntimeException error) {
        return Optional.empty();
      }
    }
  }

  /** Exact connection must have been captured by a trusted adapter before player replacement. */
  public boolean disconnect(S server, OwnerHandle<S, C> captured, Supplier<C> exactConnection) {
    if (!onThread()) return false;
    synchronized (this) {
      if (!owns(server, captured) || stage == Stage.CLOSED) return false;
      try {
        return registration.disconnect(server, captured.registrationHandle, exactConnection);
      } catch (RuntimeException error) {
        return false;
      }
    }
  }

  public Optional<IssuedChallenge<S, C>> beginChallenge(S server, OwnerHandle<S, C> captured,
      Supplier<C> exactConnection, String bootId) {
    if (!onThread()) return Optional.empty();
    BodyProofIngress<C> originalIngress;
    synchronized (this) {
      if (!owns(server, captured) || stage != Stage.ACTIVE) return Optional.empty();
      originalIngress = ingress;
    }
    C actual;
    try { actual = Objects.requireNonNull(exactConnection.get()); }
    catch (RuntimeException error) { return Optional.empty(); }
    synchronized (this) {
      if (!owns(server, captured) || stage != Stage.ACTIVE || ingress != originalIngress) {
        return Optional.empty();
      }
    }
    Optional<PendingBodyProof.BodyChallenge> challenge;
    try { challenge = originalIngress.begin(() -> actual, bootId); }
    catch (RuntimeException error) { return Optional.empty(); }
    synchronized (this) {
      if (owns(server, captured) && stage == Stage.ACTIVE && ingress == originalIngress) {
        return challenge.map(value -> new IssuedChallenge<>(this, captured, actual, value));
      }
    }
    if (challenge.isPresent()) {
      try { originalIngress.discardIssued(() -> actual, challenge.get().gameConnectionId()); }
      catch (RuntimeException ignored) { /* Never publish a token or touch a replacement owner */ }
    }
    return Optional.empty();
  }

  public boolean discardIssued(S server, OwnerHandle<S, C> captured,
      IssuedChallenge<S, C> issued) {
    if (!onThread()) return false;
    synchronized (this) {
      if (!owns(server, captured) || stage == Stage.CLOSED || issued == null
          || issued.lifecycle != this || issued.owner != captured) return false;
      return ingress.discardIssued(() -> issued.connection, issued.challenge.gameConnectionId());
    }
  }

  public StopResult onServerStopping(S server, OwnerHandle<S, C> captured) {
    if (!onThread()) return StopResult.WRONG_THREAD;
    synchronized (this) { return stop(server, captured); }
  }

  public StopResult onServerStopped(S server, OwnerHandle<S, C> captured) {
    if (!onThread()) return StopResult.WRONG_THREAD;
    synchronized (this) { return stop(server, captured); }
  }

  /** Owner-thread observation only; a wrong-thread query does not acquire lifecycle state. */
  public Optional<Stage> stage() {
    if (!onThread()) return Optional.empty();
    synchronized (this) { return Optional.of(stage); }
  }

  private StopResult stop(S server, OwnerHandle<S, C> captured) {
    if (!owns(server, captured)) return StopResult.NOT_OWNER;
    if (stage == Stage.CLOSED) return StopResult.CLOSED;
    stage = Stage.STOPPING_QUARANTINED;
    try {
      if (!registration.uninstall(server, captured.registrationHandle)) {
        return StopResult.QUARANTINED;
      }
    } catch (RuntimeException error) {
      return StopResult.QUARANTINED;
    }
    ingress = null;
    observer = null;
    stage = Stage.CLOSED;
    return StopResult.CLOSED;
  }

  private boolean owns(S server, OwnerHandle<S, C> captured) {
    return captured != null && handle == captured && captured.owner == this
        && captured.server == server;
  }

  private boolean onThread() {
    try { return thread.isOwnerThread(); }
    catch (RuntimeException error) { return false; }
  }

  @Override public String toString() { return "BodyProofServerLifecycle[redacted]"; }
}
