package io.github.jinyiwei2012.luosandbox.security;

import java.time.Instant;
import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.Optional;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Offline single-owner BODY ingress. Syntax failure consumes the exact pending attempt without
 * reading clocks. Wrong-thread calls intentionally do not consume: a future adapter must schedule
 * trusted-thread processing or disconnect with trusted cleanup. No transport or authority is owned.
 */
public final class BodyProofIngress<C> {
  @FunctionalInterface
  public interface TrustedThreadGate {
    boolean isOwnerThread();
  }

  public enum Status { VERIFIED, REJECTED, WRONG_THREAD, CLOSED }

  public record ProofResult<C>(Status status,
      Optional<PendingBodyProof.VerifiedGameConnectionProof<C>> proof) {
    public ProofResult {
      Objects.requireNonNull(status);
      Objects.requireNonNull(proof);
      if ((status == Status.VERIFIED) != proof.isPresent()) {
        throw new IllegalArgumentException("inconsistent BODY result");
      }
    }
    @Override public String toString() { return "BodyProofIngress.ProofResult[" + status + "]"; }
  }

  private record Clocks(long monotonicMillis, Instant utc) {}

  private final PendingBodyProof<C> pending;
  private final TrustedThreadGate threadGate;
  private final LongSupplier monotonicMillis;
  private final Supplier<Instant> utc;
  // Exact same monotonic deadline as PendingBodyProof; pruned before every successful begin.
  private final IdentityHashMap<C, Long> owned = new IdentityHashMap<>();
  private boolean closed;

  public BodyProofIngress(String serverInstallId, String clientInstallId, String bodyId,
      byte[] bindingHash, byte[] bodyKey, TrustedThreadGate threadGate,
      LongSupplier monotonicMillis, Supplier<Instant> utc) {
    pending = new PendingBodyProof<>(serverInstallId, clientInstallId, bodyId, bindingHash, bodyKey);
    this.threadGate = Objects.requireNonNull(threadGate);
    this.monotonicMillis = Objects.requireNonNull(monotonicMillis);
    this.utc = Objects.requireNonNull(utc);
  }

  public Optional<PendingBodyProof.BodyChallenge> begin(Supplier<C> connection, String bootId) {
    if (!onOwnerThread()) return Optional.empty();
    synchronized (this) {
      if (closed) return Optional.empty();
      C actual = actual(connection);
      if (actual == null || closed) return Optional.empty();
      Clocks clocks = clocks();
      if (clocks == null || closed) return Optional.empty();
      prune(clocks.monotonicMillis());
      Optional<PendingBodyProof.BodyChallenge> challenge =
          pending.begin(actual, bootId, clocks.monotonicMillis(), clocks.utc());
      if (closed) {
        challenge.ifPresent(value -> pending.conditionalDiscard(actual, value.gameConnectionId()));
        closeOwned();
        return Optional.empty();
      }
      if (pending.disabled()) {
        closeOwned();
        return Optional.empty();
      }
      if (challenge.isPresent()) {
        owned.put(actual, Math.addExact(clocks.monotonicMillis(), PendingBodyProof.TTL_MILLIS));
      }
      return challenge;
    }
  }

  public ProofResult<C> handleProof(Supplier<C> connection, byte[] frame) {
    if (!onOwnerThread()) return result(Status.WRONG_THREAD);
    synchronized (this) {
      if (closed) return result(Status.CLOSED);
      C actual = actual(connection);
      if (actual == null) return result(Status.REJECTED);
      BodyProofMessageCodec.Proof decoded;
      try {
        decoded = BodyProofMessageCodec.decodeProof(frame);
      } catch (BodyProofMessageCodec.CodecException error) {
        pending.discard(actual);
        owned.remove(actual);
        return result(Status.REJECTED);
      }
      Clocks clocks = clocks();
      if (clocks == null) return result(Status.CLOSED);
      var verified = pending.consume(actual, decoded.fields(), clocks.monotonicMillis(), clocks.utc());
      owned.remove(actual);
      prune(clocks.monotonicMillis());
      if (pending.disabled()) {
        closeOwned();
        return result(Status.CLOSED);
      }
      return verified.isPresent()
          ? new ProofResult<>(Status.VERIFIED, verified) : result(Status.REJECTED);
    }
  }

  public boolean disconnect(Supplier<C> connection) {
    if (!onOwnerThread()) return false;
    synchronized (this) {
      if (closed) return false;
      C actual = actual(connection);
      if (actual == null) return false;
      owned.remove(actual);
      return pending.discard(actual);
    }
  }

  /**
   * Consumes one verifier-minted local provenance capability using this ingress's trusted clock.
   * The connection supplier runs before either ingress or verifier state is locked.
   */
  boolean acceptVerified(
      PendingBodyProof.VerifiedGameConnectionProof<C> proof, Supplier<C> connection) {
    if (!onOwnerThread()) return false;
    C actual = actual(connection);
    if (actual == null) return false;
    synchronized (this) {
      if (closed) return false;
      long now;
      try {
        now = monotonicMillis.getAsLong();
      } catch (RuntimeException error) {
        closeOwned();
        return false;
      }
      if (closed) return false;
      boolean accepted = pending.acceptVerified(proof, actual, now);
      if (pending.disabled()) closeOwned();
      return accepted;
    }
  }

  /** Invalidates only a capability minted by this ingress; grants no acceptance or authority. */
  public boolean invalidateVerified(PendingBodyProof.VerifiedGameConnectionProof<C> proof) {
    if (!onOwnerThread()) return false;
    synchronized (this) {
      if (closed) return false;
      return pending.invalidateVerified(proof);
    }
  }

  public boolean discardIssued(Supplier<C> connection, String expectedGameConnectionId) {
    if (!onOwnerThread()) return false;
    synchronized (this) {
      if (closed) return false;
      C actual = actual(connection);
      if (actual == null || !pending.conditionalDiscard(actual, expectedGameConnectionId)) return false;
      owned.remove(actual);
      return true;
    }
  }

  /** Idempotent owner-thread close. Wrong-thread calls neither lock nor alter owned state. */
  public boolean close() {
    if (!onOwnerThread()) return false;
    synchronized (this) {
      closeOwned();
      return true;
    }
  }

  // Package-private observation for bounded-state tests; no pending object escapes.
  int trackedCount() {
    if (!onOwnerThread()) return 0;
    synchronized (this) { return pending.trackedCount(); }
  }

  private boolean onOwnerThread() {
    try { return threadGate.isOwnerThread(); }
    catch (RuntimeException error) { return false; }
  }

  private C actual(Supplier<C> supplier) {
    try { return supplier == null ? null : supplier.get(); }
    catch (RuntimeException error) { return null; }
  }

  private Clocks clocks() {
    try {
      long now = monotonicMillis.getAsLong();
      if (closed) return null;
      Instant wall = utc.get();
      if (closed) return null;
      pending.count(now, wall); // Primitive validates clocks and permanently disables on failure.
      if (pending.disabled()) {
        closeOwned();
        return null;
      }
      return new Clocks(now, wall);
    } catch (RuntimeException error) {
      closeOwned();
      return null;
    }
  }

  private void prune(long now) {
    owned.entrySet().removeIf(entry -> now >= entry.getValue());
  }

  private void closeOwned() {
    pending.close();
    owned.clear();
    closed = true;
  }

  private ProofResult<C> result(Status status) {
    return new ProofResult<>(status, Optional.empty());
  }

  @Override public String toString() { return "BodyProofIngress[redacted]"; }
}
