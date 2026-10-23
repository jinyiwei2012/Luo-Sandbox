package io.github.jinyiwei2012.luosandbox.security;

import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Explicit single-connection offline client proof owner, not world/session/permit authority.
 * A reentrant/replayed challenge conservatively closes the connection, never signs twice.
 * Key erasure covers owned arrays only; transient JCA copies cannot be guaranteed erased.
 */
public final class BodyProofClientOwner<C> {
  public record Provisioning(String serverInstallId, String clientInstallId, String bodyId,
      byte[] bindingHash, byte[] bodyKey) {
    public Provisioning {
      ControlAuth.requireId(serverInstallId);
      ControlAuth.requireId(clientInstallId);
      ControlAuth.requireUuid(bodyId);
      ControlAuth.requireHash(bindingHash);
      ControlAuth.requireHash(bodyKey);
      bindingHash = bindingHash.clone();
      bodyKey = bodyKey.clone();
    }
    @Override public byte[] bindingHash() { return bindingHash.clone(); }
    @Override public byte[] bodyKey() { return bodyKey.clone(); }
    @Override public String toString() { return "BodyClientProvisioning[redacted]"; }
  }

  @FunctionalInterface public interface TrustedThreadGate { boolean isOwnerThread(); }
  @FunctionalInterface public interface ProofSender<C> { void send(C connection, byte[] proof) throws Exception; }
  @FunctionalInterface public interface ConnectionCloser<C> { void close(C connection); }
  public enum Stage { READY, SENDING, PROOF_SUBMITTED, CLOSED }
  public enum Result { PROOF_SUBMITTED, REJECTED, SEND_UNKNOWN, WRONG_THREAD, CLOSED }
  private record Clocks(long monotonicMillis, Instant utc) {}

  private final C connection;
  private final String serverInstallId;
  private final String clientInstallId;
  private final String bodyId;
  private final byte[] bindingHash;
  private final byte[] key;
  private final TrustedThreadGate thread;
  private final LongSupplier monotonicMillis;
  private final Supplier<Instant> utc;
  private final ProofSender<C> sender;
  private final ConnectionCloser<C> closer;
  private Stage stage = Stage.READY;
  private Object operation;
  private long lastMonotonic = -1;

  public BodyProofClientOwner(C exactConnection, Provisioning provisioning, TrustedThreadGate thread,
      LongSupplier monotonicMillis, Supplier<Instant> utc, ProofSender<C> sender,
      ConnectionCloser<C> closer) {
    connection = Objects.requireNonNull(exactConnection);
    Objects.requireNonNull(provisioning);
    serverInstallId = provisioning.serverInstallId();
    clientInstallId = provisioning.clientInstallId();
    bodyId = provisioning.bodyId();
    bindingHash = provisioning.bindingHash();
    key = provisioning.bodyKey();
    this.thread = Objects.requireNonNull(thread);
    this.monotonicMillis = Objects.requireNonNull(monotonicMillis);
    this.utc = Objects.requireNonNull(utc);
    this.sender = Objects.requireNonNull(sender);
    this.closer = Objects.requireNonNull(closer);
  }

  public Result handleChallenge(Supplier<C> actualConnection, byte[] frame) {
    if (!onThread()) return Result.WRONG_THREAD;
    Object token;
    boolean duplicate;
    synchronized (this) {
      if (stage == Stage.CLOSED) return Result.CLOSED;
      duplicate = stage != Stage.READY;
      if (duplicate) token = null;
      else {
        token = new Object();
        operation = token;
        stage = Stage.SENDING; // Reserve before any supplier/clock callback.
      }
    }
    if (duplicate) { close(); return Result.REJECTED; }
    byte[] proofFrame;
    try {
      C actual = actualConnection.get();
      if (!active(token)) return Result.REJECTED;
      if (actual != connection) { close(); return Result.REJECTED; }
      var challenge = BodyProofMessageCodec.decodeChallenge(frame);
      if (!bodyId.equals(challenge.bodyId())) { close(); return Result.REJECTED; }
      Clocks first = clocks(token);
      if (first == null) { close(); return Result.REJECTED; }
      validateTime(challenge, first.utc());
      Clocks second = clocks(token);
      if (second == null) { close(); return Result.REJECTED; }
      validateTime(challenge, second.utc());
      if (second.monotonicMillis() - first.monotonicMillis() >= PendingBodyProof.TTL_MILLIS) {
        close(); return Result.REJECTED;
      }
      synchronized (this) {
        if (!active(token)) return Result.REJECTED;
        var transcript = new BodyProofAuth.Transcript(serverInstallId, clientInstallId, bodyId,
            challenge.serverBootId(), challenge.gameConnectionId(), ControlAuth.decodeB32(challenge.gameNonce()),
            Instant.parse(challenge.issuedAt()), Instant.parse(challenge.expiresAt()), bindingHash);
        proofFrame = BodyProofMessageCodec.encodeProof(new BodyProofMessageCodec.Proof(
            challenge.serverBootId(), challenge.gameConnectionId(), bodyId, clientInstallId,
            challenge.gameNonce(), challenge.issuedAt(), challenge.expiresAt(),
            BodyProofAuth.proof(key, transcript)));
      }
      if (!active(token)) return Result.REJECTED;
    } catch (RuntimeException error) {
      close(); return Result.REJECTED;
    }
    try {
      sender.send(connection, proofFrame); // No owner monitor across external submission.
      synchronized (this) {
        if (!active(token)) return Result.SEND_UNKNOWN;
        stage = Stage.PROOF_SUBMITTED;
        return Result.PROOF_SUBMITTED;
      }
    } catch (Exception error) {
      if (error instanceof InterruptedException) Thread.currentThread().interrupt();
      close();
      return Result.SEND_UNKNOWN;
    }
  }

  public boolean close() {
    if (!onThread()) return false;
    synchronized (this) {
      if (stage == Stage.CLOSED) return true;
      stage = Stage.CLOSED;
      operation = null;
      Arrays.fill(key, (byte) 0);
    }
    try { closer.close(connection); }
    catch (RuntimeException ignored) { /* Never restore key/state or claim physical disconnect */ }
    return true;
  }

  private Clocks clocks(Object token) {
    long monotonic = monotonicMillis.getAsLong();
    if (!active(token)) return null;
    Instant wall = utc.get();
    if (!active(token)) return null;
    ControlAuth.requireUtc(wall);
    if (wall.isBefore(Instant.EPOCH)) throw new IllegalArgumentException("negative UTC");
    synchronized (this) {
      if (!active(token)) return null;
      if (monotonic < 0 || monotonic < lastMonotonic) throw new IllegalArgumentException("invalid clock");
      lastMonotonic = monotonic;
    }
    return new Clocks(monotonic, wall);
  }

  private static void validateTime(PendingBodyProof.BodyChallenge challenge, Instant now) {
    Instant skewLimit = now.plusMillis(1_000);
    ControlAuth.requireUtc(skewLimit);
    if (Instant.parse(challenge.issuedAt()).isAfter(skewLimit)
        || !now.isBefore(Instant.parse(challenge.expiresAt()))) {
      throw new IllegalArgumentException("challenge outside time window");
    }
  }

  private synchronized boolean active(Object token) {
    return stage == Stage.SENDING && operation == token;
  }
  private boolean onThread() {
    try { return thread.isOwnerThread(); }
    catch (RuntimeException error) { return false; }
  }
  @Override public String toString() { return "BodyProofClientOwner[redacted]"; }
}
