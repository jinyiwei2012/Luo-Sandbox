package io.github.jinyiwei2012.luosandbox.server;

import io.github.jinyiwei2012.luosandbox.servercontrol.PermitAuthority;
import io.github.jinyiwei2012.luosandbox.servercontrol.ServerControlCoordinator;
import java.util.Objects;
import java.util.Optional;
import java.util.function.LongSupplier;

/**
 * Reference-identity index for one provisioned body and its actual game connection.
 *
 * <p>This class does not authenticate login names or UUIDs. Its trusted caller must separately prove
 * external authentication, epoch ownership, and game-connection ownership before binding. Those
 * proofs are intentionally outside this first event-defense implementation.
 */
public final class ControlledIdentityRegistry<P> {
  @FunctionalInterface
  interface CleanupPort<P> {
    void controlLost(ControlledBody<P> body, String evidenceId, long monotonicNowMillis);
  }

  public interface IdentityAdapter<P> {
    Object playerIdentity(P player);

    Object connectionToken(P player);
  }

  @FunctionalInterface
  public interface ProtectionMarker<P> {
    boolean markAndVerify(P player, Object playerIdentity, Object connectionToken);
  }

  /** Trusted provisioning policy; implementations own candidate and binding proof validation. */
  public interface TrustedProvisionPolicy<P> {
    boolean mayProtectCandidate(P player, Object playerIdentity, Object connectionToken);

    boolean mayBind(
        P player,
        Object playerIdentity,
        Object connectionToken,
        PermitAuthority.Binding binding,
        ServerControlCoordinator coordinator);
  }

  public record ControlledBody<P>(
      P player,
      Object playerIdentity,
      Object connectionToken,
      PermitAuthority.Binding binding,
      ServerControlCoordinator coordinator) {
    public ControlledBody {
      Objects.requireNonNull(player);
      Objects.requireNonNull(playerIdentity);
      Objects.requireNonNull(connectionToken);
      Objects.requireNonNull(binding);
      Objects.requireNonNull(coordinator);
    }
  }

  private record Candidate(Object playerIdentity, Object connectionToken) {}

  private final IdentityAdapter<P> adapter;
  private final TrustedProvisionPolicy<P> provisioning;
  private final ProtectionMarker<P> protectionMarker;
  private final LongSupplier monotonicMillis;
  private final CleanupPort<P> cleanup;
  private Candidate candidate;
  private ControlledBody<P> current;
  private ControlledBody<P> pendingCleanup;
  private boolean releaseConfirmed;

  public ControlledIdentityRegistry(
      IdentityAdapter<P> adapter,
      TrustedProvisionPolicy<P> provisioning,
      ProtectionMarker<P> protectionMarker) {
    this(adapter, provisioning, protectionMarker, () -> System.nanoTime() / 1_000_000L);
  }

  public ControlledIdentityRegistry(
      IdentityAdapter<P> adapter,
      TrustedProvisionPolicy<P> provisioning,
      ProtectionMarker<P> protectionMarker,
      LongSupplier monotonicMillis) {
    this(
        adapter,
        provisioning,
        protectionMarker,
        monotonicMillis,
        ControlledIdentityRegistry::controlLost);
  }

  ControlledIdentityRegistry(
      IdentityAdapter<P> adapter,
      TrustedProvisionPolicy<P> provisioning,
      ProtectionMarker<P> protectionMarker,
      LongSupplier monotonicMillis,
      CleanupPort<P> cleanup) {
    this.adapter = Objects.requireNonNull(adapter);
    this.provisioning = Objects.requireNonNull(provisioning);
    this.protectionMarker = Objects.requireNonNull(protectionMarker);
    this.monotonicMillis = Objects.requireNonNull(monotonicMillis);
    this.cleanup = Objects.requireNonNull(cleanup);
  }

  /** Protects a fixed provisioned connection before external binding proof is available. */
  public synchronized boolean protectCandidate(P player) {
    Objects.requireNonNull(player);
    Object identity = Objects.requireNonNull(adapter.playerIdentity(player));
    Object connection = Objects.requireNonNull(adapter.connectionToken(player));
    if (!provisioning.mayProtectCandidate(player, identity, connection)) return false;
    try {
      if (!protectionMarker.markAndVerify(player, identity, connection)) return false;
    } catch (RuntimeException error) {
      return false;
    }
    if (candidate != null
        && (candidate.playerIdentity() != identity || candidate.connectionToken() != connection)) {
      return false;
    }
    candidate = new Candidate(identity, connection);
    releaseConfirmed = false;
    return true;
  }

  /**
   * Installs a fully trusted binding. Replacing the body revokes the old coordinator first.
   */
  public synchronized boolean bindTrusted(
      P player, PermitAuthority.Binding binding, ServerControlCoordinator coordinator) {
    Objects.requireNonNull(player);
    Objects.requireNonNull(binding);
    Objects.requireNonNull(coordinator);
    Object identity = Objects.requireNonNull(adapter.playerIdentity(player));
    Object connection = Objects.requireNonNull(adapter.connectionToken(player));
    if (candidate == null
        || candidate.playerIdentity() != identity
        || candidate.connectionToken() != connection) {
      return false;
    }
    try {
      if (!provisioning.mayBind(player, identity, connection, binding, coordinator)) return false;
    } catch (RuntimeException error) {
      return false;
    }
    if (pendingCleanup != null) return false;
    if (current != null
        && (!matches(current, identity, connection)
            || !current.binding().equals(binding)
            || current.coordinator() != coordinator)) return false;
    current = new ControlledBody<>(player, identity, connection, binding, coordinator);
    releaseConfirmed = false;
    return true;
  }

  public synchronized Optional<ControlledBody<P>> resolve(P player) {
    if (player == null || current == null) return Optional.empty();
    Object identity = adapter.playerIdentity(player);
    Object connection = adapter.connectionToken(player);
    return matches(current, identity, connection) ? Optional.of(current) : Optional.empty();
  }

  public synchronized boolean isProtectedCandidate(P player) {
    if (player == null || candidate == null) return false;
    return candidate.playerIdentity() == adapter.playerIdentity(player)
        && candidate.connectionToken() == adapter.connectionToken(player);
  }

  public synchronized boolean fence(P player, PermitAuthority.RevokeReason reason) {
    Objects.requireNonNull(reason);
    ControlledBody<P> removed;
    Optional<ControlledBody<P>> resolved = resolve(player);
    if (resolved.isPresent()) {
      removed = resolved.get();
      current = null;
      pendingCleanup = removed;
    } else if (pendingCleanup != null
        && isProtectedCandidate(player)
        && matchesConnection(pendingCleanup, player)) {
      removed = pendingCleanup;
    } else {
      return false;
    }
    if (!cleanup(removed, "controlled-connection-fenced-" + reason.name().toLowerCase())) {
      releaseConfirmed = false;
      return false;
    }
    pendingCleanup = null;
    releaseConfirmed = true;
    return true;
  }

  /** Requires a successful authority fence before an online connection can leave quarantine. */
  public synchronized boolean clearCandidate(P player) {
    if (!isProtectedCandidate(player) || current != null || pendingCleanup != null
        || !releaseConfirmed) return false;
    candidate = null;
    releaseConfirmed = false;
    return true;
  }

  /** Trusted logout path: the exact connection is offline, so quarantine can be removed idempotently. */
  public synchronized boolean releaseOffline(P player, PermitAuthority.RevokeReason reason) {
    Objects.requireNonNull(reason);
    if (!isProtectedCandidate(player)) return current == null && pendingCleanup == null;
    ControlledBody<P> removed = current;
    if (removed != null && !matchesPlayer(removed, player)) return false;
    if (removed == null) removed = pendingCleanup;
    current = null;
    pendingCleanup = null;
    candidate = null;
    releaseConfirmed = false;
    if (removed != null) {
      cleanup(removed, "controlled-connection-offline-" + reason.name().toLowerCase());
    }
    return true;
  }

  /** Transfers only the default-deny quarantine across a server-observed player replacement. */
  public synchronized boolean transferCandidate(P previous, P replacement) {
    Objects.requireNonNull(previous);
    Objects.requireNonNull(replacement);
    if (!isProtectedCandidate(previous) || current != null || pendingCleanup != null
        || !releaseConfirmed) return false;
    Object previousConnection = Objects.requireNonNull(adapter.connectionToken(previous));
    Object replacementConnection = Objects.requireNonNull(adapter.connectionToken(replacement));
    if (previousConnection != replacementConnection) return false;
    if (!markReplacement(replacement, replacementConnection)) return false;
    candidate =
        new Candidate(
            Objects.requireNonNull(adapter.playerIdentity(replacement)), replacementConnection);
    releaseConfirmed = false;
    return true;
  }

  /**
   * Transfers only connection quarantine after a server-observed player replacement.
   * Pending cleanup remains attached to the old body and must still succeed before release or bind.
   */
  public synchronized boolean transferCandidateQuarantine(P previous, P replacement) {
    Objects.requireNonNull(previous);
    Objects.requireNonNull(replacement);
    if (!isProtectedCandidate(previous) || current != null) return false;
    Object previousConnection = Objects.requireNonNull(adapter.connectionToken(previous));
    Object replacementConnection = Objects.requireNonNull(adapter.connectionToken(replacement));
    if (previousConnection != replacementConnection
        || pendingCleanup != null && pendingCleanup.connectionToken() != previousConnection) {
      return false;
    }
    if (!markReplacement(replacement, replacementConnection)) return false;
    candidate =
        new Candidate(
            Objects.requireNonNull(adapter.playerIdentity(replacement)), replacementConnection);
    releaseConfirmed = false;
    return true;
  }

  public synchronized void deadlineElapsed(long monotonicNowMillis) {
    if (monotonicNowMillis < 0) throw new IllegalArgumentException("negative monotonic clock");
    if (current != null) current.coordinator().deadlineElapsed(monotonicNowMillis);
  }

  public synchronized void deadlineElapsed() {
    deadlineElapsed(monotonicMillis.getAsLong());
  }

  public synchronized Optional<ControlledBody<P>> current() {
    return Optional.ofNullable(current);
  }

  private static boolean matches(ControlledBody<?> body, Object identity, Object connection) {
    return body.playerIdentity() == identity && body.connectionToken() == connection;
  }

  private boolean matchesPlayer(ControlledBody<P> body, P player) {
    return body.playerIdentity() == adapter.playerIdentity(player)
        && body.connectionToken() == adapter.connectionToken(player);
  }

  private boolean matchesConnection(ControlledBody<P> body, P player) {
    return body.connectionToken() == adapter.connectionToken(player);
  }

  private boolean markReplacement(P replacement, Object connection) {
    Object identity = Objects.requireNonNull(adapter.playerIdentity(replacement));
    try {
      return protectionMarker.markAndVerify(replacement, identity, connection);
    } catch (RuntimeException error) {
      return false;
    }
  }

  private boolean cleanup(ControlledBody<P> body, String evidenceId) {
    try {
      cleanup.controlLost(body, evidenceId, monotonicMillis.getAsLong());
      return true;
    } catch (RuntimeException error) {
      return false;
    }
  }

  private static <P> void controlLost(
      ControlledBody<P> body, String evidenceId, long monotonicNowMillis) {
    body.coordinator()
        .controlLost(
            new io.github.jinyiwei2012.luosandbox.session.BoundedLifecycle.AuthorityEvidence(
                evidenceId),
            monotonicNowMillis);
  }
}
