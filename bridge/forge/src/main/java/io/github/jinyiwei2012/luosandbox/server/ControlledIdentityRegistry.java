package io.github.jinyiwei2012.luosandbox.server;

import io.github.jinyiwei2012.luosandbox.servercontrol.PermitAuthority;
import io.github.jinyiwei2012.luosandbox.servercontrol.ServerControlCoordinator;
import io.github.jinyiwei2012.luosandbox.session.BoundedLifecycle;
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
  private static final long PREPARE_FENCE_LIFETIME_MILLIS = 5_000;

  /** Opaque proof only that the captured old authority was synchronously revoked. */
  public static final class PrepareFenceReceipt {
    public enum Status { OLD_AUTHORITY_REVOKED }

    private final Object identity = new Object();
    private PrepareFenceReceipt() {}
    public Status status() { return Status.OLD_AUTHORITY_REVOKED; }
  }

  private record PrepareFenceState<P>(
      PrepareFenceReceipt receipt,
      ControlledBody<P> body,
      long generation,
      long createdAt,
      long deadline) {}
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
  private long generation;
  private boolean generationExhausted;
  private long lastPrepareMonotonic = -1;
  private PrepareFenceState<P> prepareFence;

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
    if (generationExhausted) return false;
    long capturedGeneration = generation;
    Candidate capturedCandidate = candidate;
    ControlledBody<P> capturedCurrent = current;
    ControlledBody<P> capturedPendingCleanup = pendingCleanup;
    Object identity = Objects.requireNonNull(adapter.playerIdentity(player));
    Object connection = Objects.requireNonNull(adapter.connectionToken(player));
    if (!provisioning.mayProtectCandidate(player, identity, connection)) return false;
    try {
      if (!protectionMarker.markAndVerify(player, identity, connection)) return false;
    } catch (RuntimeException error) {
      return false;
    }
    if (generation != capturedGeneration
        || candidate != capturedCandidate
        || current != capturedCurrent
        || pendingCleanup != capturedPendingCleanup) return false;
    if (candidate != null
        && (candidate.playerIdentity() != identity || candidate.connectionToken() != connection)) {
      return false;
    }
    if (!reserveGeneration()) return false;
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
    if (generationExhausted) return false;
    long capturedGeneration = generation;
    Candidate capturedCandidate = candidate;
    ControlledBody<P> capturedCurrent = current;
    ControlledBody<P> capturedPendingCleanup = pendingCleanup;
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
    if (generation != capturedGeneration
        || candidate != capturedCandidate
        || current != capturedCurrent
        || pendingCleanup != capturedPendingCleanup) return false;
    if (pendingCleanup != null) return false;
    if (current != null
        && (!matches(current, identity, connection)
            || !current.binding().equals(binding)
            || current.coordinator() != coordinator)) return false;
    if (!reserveGeneration()) return false;
    current = new ControlledBody<>(player, identity, connection, binding, coordinator);
    releaseConfirmed = false;
    return true;
  }

  synchronized Optional<PrepareFenceReceipt> beginBindingPrepare(
      P player, PermitAuthority.Binding expectedBinding, long monotonicNowMillis) {
    Objects.requireNonNull(player);
    Objects.requireNonNull(expectedBinding);
    ControlledBody<P> captured = current;
    long capturedGeneration = generation;
    if (captured == null || captured.player() != player
        || !captured.binding().equals(expectedBinding)) return Optional.empty();
    Object identity;
    Object connection;
    try {
      identity = Objects.requireNonNull(adapter.playerIdentity(player));
      connection = Objects.requireNonNull(adapter.connectionToken(player));
    } catch (RuntimeException error) {
      failClosedCaptured(captured, capturedGeneration, monotonicNowMillis);
      return Optional.empty();
    } catch (Error error) {
      failClosedCaptured(captured, capturedGeneration, monotonicNowMillis);
      throw error;
    }
    if (current != captured || generation != capturedGeneration
        || !matches(captured, identity, connection)) return Optional.empty();
    if (monotonicNowMillis < 0 || monotonicNowMillis < lastPrepareMonotonic) {
      failClosedCurrent(monotonicNowMillis);
      return Optional.empty();
    }
    lastPrepareMonotonic = monotonicNowMillis;
    if (monotonicNowMillis > Long.MAX_VALUE - PREPARE_FENCE_LIFETIME_MILLIS) {
      failClosedCurrent(monotonicNowMillis);
      return Optional.empty();
    }
    if (prepareFence != null) return Optional.empty();

    ControlledBody<P> removed = captured;
    long operationGeneration = capturedGeneration;
    current = null;
    pendingCleanup = removed;
    releaseConfirmed = false;
    boolean generationAdvanced = advanceGeneration();
    long fencedGeneration = generation;
    BoundedLifecycle.AuthorityEvidence evidence =
        new BoundedLifecycle.AuthorityEvidence("binding-prepare-authority-revoked");
    boolean fenced = removed.coordinator().fenceForBindingPrepare(expectedBinding, evidence, monotonicNowMillis);
    if (!fenced
        || current != null
        || pendingCleanup != removed
        || generation != fencedGeneration
        || !generationAdvanced
        || operationGeneration == generation
        || generationExhausted) return Optional.empty();
    pendingCleanup = null;
    releaseConfirmed = true;
    PrepareFenceReceipt receipt = new PrepareFenceReceipt();
    prepareFence = new PrepareFenceState<>(receipt, removed, generation, monotonicNowMillis,
        monotonicNowMillis + PREPARE_FENCE_LIFETIME_MILLIS);
    return Optional.of(receipt);
  }

  synchronized boolean validatesPrepareFence(
      PrepareFenceReceipt receipt, P player, PermitAuthority.Binding binding, long monotonicNowMillis) {
    if (receipt == null || player == null || binding == null || monotonicNowMillis < 0
        || monotonicNowMillis < lastPrepareMonotonic) return false;
    PrepareFenceState<P> captured = prepareFence;
    long capturedGeneration = generation;
    if (captured == null || captured.receipt() != receipt
        || captured.generation() != capturedGeneration
        || !captured.body().binding().equals(binding)) return false;
    lastPrepareMonotonic = monotonicNowMillis;
    if (monotonicNowMillis >= captured.deadline()) {
      prepareFence = null;
      return false;
    }
    Object identity = null;
    Object connection = null;
    RuntimeException ordinary = null;
    Error fatal = null;
    try {
      identity = adapter.playerIdentity(player);
    } catch (RuntimeException error) {
      ordinary = error;
    } catch (Error error) {
      fatal = error;
    }
    try {
      connection = adapter.connectionToken(player);
    } catch (RuntimeException error) {
      if (ordinary == null) ordinary = error;
    } catch (Error error) {
      if (fatal == null) fatal = error;
    }
    boolean stillCaptured = prepareFence == captured && generation == capturedGeneration;
    if (ordinary != null || fatal != null) {
      if (stillCaptured) prepareFence = null;
      if (fatal != null) throw fatal;
      return false;
    }
    return stillCaptured
        && captured.receipt() == receipt
        && captured.generation() == generation
        && captured.deadline() == captured.createdAt() + PREPARE_FENCE_LIFETIME_MILLIS
        && monotonicNowMillis < captured.deadline()
        && captured.body().player() == player
        && captured.body().playerIdentity() == identity
        && captured.body().connectionToken() == connection
        && captured.body().binding().equals(binding);
  }

  /** Read-only identity of the live PREPARE fence, used only to bind evidence; grants nothing. */
  record PrepareFenceView<P>(ControlledBody<P> body, long generation, long deadline) {}

  synchronized Optional<PrepareFenceView<P>> prepareFenceView(
      PrepareFenceReceipt receipt, P player, PermitAuthority.Binding binding, long monotonicNowMillis) {
    if (!validatesPrepareFence(receipt, player, binding, monotonicNowMillis)) {
      return Optional.empty();
    }
    PrepareFenceState<P> captured = prepareFence;
    return Optional.of(
        new PrepareFenceView<>(captured.body(), captured.generation(), captured.deadline()));
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
      advanceGeneration();
    } else if (pendingCleanup != null
        && isProtectedCandidate(player)
        && matchesConnection(pendingCleanup, player)) {
      removed = pendingCleanup;
    } else if (prepareFence != null && matchesPlayer(prepareFence.body(), player)) {
      advanceGeneration();
      return true;
    } else {
      return false;
    }
    if (!cleanup(removed, "controlled-connection-fenced-" + reason.name().toLowerCase())) {
      releaseConfirmed = false;
      return false;
    }
    pendingCleanup = null;
    releaseConfirmed = true;
    advanceGeneration();
    return true;
  }

  /** Requires a successful authority fence before an online connection can leave quarantine. */
  public synchronized boolean clearCandidate(P player) {
    if (!isProtectedCandidate(player) || current != null || pendingCleanup != null
        || !releaseConfirmed) return false;
    candidate = null;
    releaseConfirmed = false;
    advanceGeneration();
    return true;
  }

  /** Trusted logout path: the exact connection is offline, so quarantine can be removed idempotently. */
  public synchronized boolean releaseOffline(P player, PermitAuthority.RevokeReason reason) {
    Objects.requireNonNull(reason);
    if (player == null) return false;
    ControlledBody<P> removed = current;
    PrepareFenceState<P> capturedFence = prepareFence;
    long capturedGeneration = generation;
    if (removed == null && capturedFence != null) {
      ControlledBody<P> body = capturedFence.body();
      if (body.player() != player) return false;
      Object identity;
      Object connection;
      try {
        identity = adapter.playerIdentity(player);
        connection = adapter.connectionToken(player);
      } catch (RuntimeException error) {
        if (prepareFence == capturedFence && generation == capturedGeneration) prepareFence = null;
        return false;
      } catch (Error error) {
        if (prepareFence == capturedFence && generation == capturedGeneration) prepareFence = null;
        throw error;
      }
      if (prepareFence != capturedFence
          || generation != capturedGeneration
          || body.playerIdentity() != identity
          || body.connectionToken() != connection) return false;
    } else {
      if (!isProtectedCandidate(player)) {
        return current == null && pendingCleanup == null && prepareFence == null;
      }
      if (removed != null && !matchesPlayer(removed, player)) return false;
      if (removed == null) removed = pendingCleanup;
    }
    boolean changed = current != null || pendingCleanup != null || candidate != null
        || releaseConfirmed || prepareFence != null;
    current = null;
    pendingCleanup = null;
    candidate = null;
    releaseConfirmed = false;
    if (changed) advanceGeneration();
    if (removed != null) {
      cleanup(removed, "controlled-connection-offline-" + reason.name().toLowerCase());
    }
    return true;
  }

  /** Transfers only the default-deny quarantine across a server-observed player replacement. */
  public synchronized boolean transferCandidate(P previous, P replacement) {
    Objects.requireNonNull(previous);
    Objects.requireNonNull(replacement);
    if (generationExhausted) return false;
    long capturedGeneration = generation;
    Candidate capturedCandidate = candidate;
    ControlledBody<P> capturedCurrent = current;
    ControlledBody<P> capturedPendingCleanup = pendingCleanup;
    boolean capturedReleaseConfirmed = releaseConfirmed;
    if (capturedCandidate == null || capturedCurrent != null || capturedPendingCleanup != null
        || !capturedReleaseConfirmed) return false;
    Object previousIdentity = Objects.requireNonNull(adapter.playerIdentity(previous));
    Object previousConnection = Objects.requireNonNull(adapter.connectionToken(previous));
    Object replacementConnection = Objects.requireNonNull(adapter.connectionToken(replacement));
    Object replacementIdentity = Objects.requireNonNull(adapter.playerIdentity(replacement));
    if (capturedCandidate.playerIdentity() != previousIdentity
        || capturedCandidate.connectionToken() != previousConnection
        || previousConnection != replacementConnection) return false;
    try {
      if (!protectionMarker.markAndVerify(
          replacement, replacementIdentity, replacementConnection)) return false;
    } catch (RuntimeException error) {
      return false;
    }
    if (generation != capturedGeneration
        || candidate != capturedCandidate
        || current != capturedCurrent
        || pendingCleanup != capturedPendingCleanup
        || releaseConfirmed != capturedReleaseConfirmed) return false;
    if (!reserveGeneration()) return false;
    candidate = new Candidate(replacementIdentity, replacementConnection);
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
    if (generationExhausted) return false;
    long capturedGeneration = generation;
    Candidate capturedCandidate = candidate;
    ControlledBody<P> capturedCurrent = current;
    ControlledBody<P> capturedPendingCleanup = pendingCleanup;
    boolean capturedReleaseConfirmed = releaseConfirmed;
    if (capturedCandidate == null || capturedCurrent != null) return false;
    Object previousIdentity = Objects.requireNonNull(adapter.playerIdentity(previous));
    Object previousConnection = Objects.requireNonNull(adapter.connectionToken(previous));
    Object replacementConnection = Objects.requireNonNull(adapter.connectionToken(replacement));
    Object replacementIdentity = Objects.requireNonNull(adapter.playerIdentity(replacement));
    if (capturedCandidate.playerIdentity() != previousIdentity
        || capturedCandidate.connectionToken() != previousConnection) return false;
    if (previousConnection != replacementConnection
        || capturedPendingCleanup != null
            && capturedPendingCleanup.connectionToken() != previousConnection) {
      return false;
    }
    try {
      if (!protectionMarker.markAndVerify(
          replacement, replacementIdentity, replacementConnection)) return false;
    } catch (RuntimeException error) {
      return false;
    }
    if (generation != capturedGeneration
        || candidate != capturedCandidate
        || current != capturedCurrent
        || pendingCleanup != capturedPendingCleanup
        || releaseConfirmed != capturedReleaseConfirmed) return false;
    if (!reserveGeneration()) return false;
    candidate = new Candidate(replacementIdentity, replacementConnection);
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

  private void failClosedCurrent(long now) {
    ControlledBody<P> body = current;
    if (body == null) return;
    current = null;
    pendingCleanup = body;
    releaseConfirmed = false;
    advanceGeneration();
    body.coordinator().fenceForBindingPrepare(
        body.binding(), new BoundedLifecycle.AuthorityEvidence("binding-prepare-clock-invalid"),
        Math.max(0, now));
  }

  private void failClosedCaptured(ControlledBody<P> body, long capturedGeneration, long now) {
    if (current != body || generation != capturedGeneration) return;
    failClosedCurrent(now);
  }

  private boolean advanceGeneration() {
    prepareFence = null;
    if (generationExhausted || generation == Long.MAX_VALUE) {
      generationExhausted = true;
      return false;
    }
    generation++;
    if (generation == Long.MAX_VALUE) generationExhausted = true;
    return true;
  }

  private boolean reserveGeneration() {
    if (generationExhausted || generation == Long.MAX_VALUE) {
      prepareFence = null;
      generationExhausted = true;
      return false;
    }
    generation++;
    prepareFence = null;
    if (generation == Long.MAX_VALUE) generationExhausted = true;
    return true;
  }
}
