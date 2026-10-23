package io.github.jinyiwei2012.luosandbox.security;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Bounded, single-use BODY proof owner for exact game connection references.
 * No WS origin/notice matching, epoch, world admission, permit, transport or body activation lives here.
 * All monotonic inputs are milliseconds from the same trusted clock origin.
 */
public final class PendingBodyProof<C> {
  public static final int MAX_PENDING = 2;
  public static final long TTL_MILLIS = 5_000;
  private static final SecureRandom RANDOM = new SecureRandom();
  private static final Set<String> RESPONSE_KEYS = Set.of(
      "serverBootId", "gameConnectionId", "bodyId", "clientInstallId", "gameNonce",
      "issuedAt", "expiresAt", "proof");

  public record BodyChallenge(
      String serverBootId, String gameConnectionId, String bodyId, String gameNonce,
      String issuedAt, String expiresAt) {
    @Override public String toString() { return "BodyChallenge[redacted]"; }
  }

  /** Raw repeated response fields, preserving duplicates for rejection before any wire codec exists. */
  public record Field(String name, String value) {
    @Override public String toString() { return "BodyProofField[redacted]"; }
  }

  /** Opaque result: proof of this tuple only, never world/epoch/permit authorization. */
  public static final class VerifiedGameConnectionProof<C> {
    private enum Acceptance { AVAILABLE, CONSUMED, INVALIDATED }

    private final C connection;
    private final String serverInstallId;
    private final String clientInstallId;
    private final BodyChallenge challenge;
    private final byte[] bindingHash;
    private final PendingBodyProof<C> verifier;
    private final long issuedMonotonicMillis;
    private final long deadlineMonotonicMillis;
    private Acceptance acceptance = Acceptance.AVAILABLE;

    private VerifiedGameConnectionProof(
        C connection, String serverInstallId, String clientInstallId,
        BodyChallenge challenge, byte[] bindingHash, PendingBodyProof<C> verifier,
        long issuedMonotonicMillis, long deadlineMonotonicMillis) {
      this.connection = connection;
      this.serverInstallId = serverInstallId;
      this.clientInstallId = clientInstallId;
      this.challenge = challenge;
      this.bindingHash = bindingHash.clone();
      this.verifier = verifier;
      this.issuedMonotonicMillis = issuedMonotonicMillis;
      this.deadlineMonotonicMillis = deadlineMonotonicMillis;
    }

    public boolean matchesConnection(C actual) { return connection == actual; }
    public String serverInstallId() { return serverInstallId; }
    public String clientInstallId() { return clientInstallId; }
    public BodyChallenge challenge() { return challenge; }
    public byte[] bindingHash() { return bindingHash.clone(); }
    @Override public String toString() { return "VerifiedGameConnectionProof[redacted]"; }
  }

  private record State(BodyProofAuth.Transcript transcript, BodyChallenge challenge,
      long issuedMonotonicMillis, long deadlineMonotonicMillis) {}

  private final String serverInstallId;
  private final String clientInstallId;
  private final String bodyId;
  private final byte[] bindingHash;
  private final byte[] bodyKey;
  private final Supplier<byte[]> nonces;
  private final Supplier<String> connectionIds;
  private final IdentityHashMap<C, State> pending = new IdentityHashMap<>();
  private final IdentityHashMap<VerifiedGameConnectionProof<C>, Boolean> verified =
      new IdentityHashMap<>();
  private long lastMonotonicMillis = -1;
  private boolean disabled;

  public PendingBodyProof(
      String serverInstallId, String clientInstallId, String bodyId,
      byte[] bindingHash, byte[] bodyKey) {
    this(serverInstallId, clientInstallId, bodyId, bindingHash, bodyKey,
        PendingBodyProof::randomNonce, () -> UUID.randomUUID().toString());
  }

  PendingBodyProof(
      String serverInstallId, String clientInstallId, String bodyId,
      byte[] bindingHash, byte[] bodyKey, Supplier<byte[]> nonces,
      Supplier<String> connectionIds) {
    ControlAuth.requireId(serverInstallId);
    ControlAuth.requireId(clientInstallId);
    ControlAuth.requireUuid(bodyId);
    ControlAuth.requireHash(bindingHash);
    ControlAuth.requireHash(bodyKey);
    this.serverInstallId = serverInstallId;
    this.clientInstallId = clientInstallId;
    this.bodyId = bodyId;
    this.bindingHash = bindingHash.clone();
    this.bodyKey = bodyKey.clone();
    this.nonces = Objects.requireNonNull(nonces);
    this.connectionIds = Objects.requireNonNull(connectionIds);
  }

  public synchronized Optional<BodyChallenge> begin(
      C actualConnection, String serverBootId, long monotonicNowMillis, Instant utcNow) {
    if (!checkClocks(monotonicNowMillis, utcNow)) return Optional.empty();
    expire(monotonicNowMillis);
    if (actualConnection == null || pending.containsKey(actualConnection)) return Optional.empty();
    invalidateVerified(actualConnection);
    if (pending.size() + verified.size() >= MAX_PENDING) return Optional.empty();
    try {
      ControlAuth.requireUuid(serverBootId);
      long deadline = Math.addExact(monotonicNowMillis, TTL_MILLIS);
      Instant expires = utcNow.plusMillis(TTL_MILLIS);
      try {
        ControlAuth.requireUtc(expires);
      } catch (IllegalArgumentException error) {
        disable();
        return Optional.empty();
      }
      BodyProofAuth.Transcript transcript = new BodyProofAuth.Transcript(
          serverInstallId, clientInstallId, bodyId, serverBootId, connectionIds.get(),
          nonces.get(), utcNow, expires, bindingHash);
      BodyChallenge challenge = new BodyChallenge(
          serverBootId, transcript.gameConnectionId(), bodyId,
          ControlAuth.b32(transcript.gameNonce()), ControlAuth.formatUtc(utcNow),
          ControlAuth.formatUtc(expires));
      pending.put(actualConnection, new State(transcript, challenge, monotonicNowMillis, deadline));
      return Optional.of(challenge);
    } catch (ArithmeticException | java.time.DateTimeException error) {
      disable();
      return Optional.empty();
    } catch (IllegalArgumentException error) {
      return Optional.empty();
    }
  }

  /** Consumes the exact pending entry before response validation, including null or duplicate fields. */
  public synchronized Optional<VerifiedGameConnectionProof<C>> consume(
      C actualConnection, List<Field> response, long monotonicNowMillis, Instant utcNow) {
    State state = pending.remove(actualConnection);
    if (!checkClocks(monotonicNowMillis, utcNow)) return Optional.empty();
    expire(monotonicNowMillis);
    if (state == null || monotonicNowMillis < state.issuedMonotonicMillis()
        || monotonicNowMillis >= state.deadlineMonotonicMillis()) return Optional.empty();
    try {
      if (response == null || response.size() != RESPONSE_KEYS.size()) return Optional.empty();
      Map<String, String> fields = new HashMap<>();
      for (Field field : response) {
        if (field == null || field.name() == null || field.value() == null
            || fields.putIfAbsent(field.name(), field.value()) != null) return Optional.empty();
      }
      if (!fields.keySet().equals(RESPONSE_KEYS)) return Optional.empty();
      BodyChallenge challenge = state.challenge();
      if (!challenge.serverBootId().equals(fields.get("serverBootId"))
          || !challenge.gameConnectionId().equals(fields.get("gameConnectionId"))
          || !challenge.bodyId().equals(fields.get("bodyId"))
          || !clientInstallId.equals(fields.get("clientInstallId"))
          || !challenge.gameNonce().equals(fields.get("gameNonce"))
          || !challenge.issuedAt().equals(fields.get("issuedAt"))
          || !challenge.expiresAt().equals(fields.get("expiresAt"))) return Optional.empty();
      if (state.transcript().issuedAt().isAfter(utcNow.plusMillis(1_000))
          || !utcNow.isBefore(state.transcript().expiresAt())) return Optional.empty();
      if (!BodyProofAuth.verify(bodyKey, state.transcript(), fields.get("proof"))) {
        return Optional.empty();
      }
      var proof = new VerifiedGameConnectionProof<>(actualConnection, serverInstallId,
          clientInstallId, challenge, bindingHash, this, state.issuedMonotonicMillis(),
          state.deadlineMonotonicMillis());
      verified.put(proof, Boolean.TRUE);
      return Optional.of(proof);
    } catch (IllegalArgumentException | java.time.DateTimeException error) {
      return Optional.empty();
    }
  }

  public synchronized boolean discard(C actualConnection) {
    boolean removed = pending.remove(actualConnection) != null;
    return invalidateVerified(actualConnection) || removed;
  }

  public synchronized boolean conditionalDiscard(C actualConnection, String expectedGameConnectionId) {
    try { ControlAuth.requireUuid(expectedGameConnectionId); }
    catch (IllegalArgumentException error) { return false; }
    State state = pending.get(actualConnection);
    if (state == null || !state.challenge().gameConnectionId().equals(expectedGameConnectionId)) {
      return false;
    }
    pending.remove(actualConnection);
    return true;
  }

  public synchronized int count(long monotonicNowMillis, Instant utcNow) {
    if (!checkClocks(monotonicNowMillis, utcNow)) return 0;
    expire(monotonicNowMillis);
    return pending.size();
  }

  public synchronized boolean disabled() { return disabled; }

  /** Local verifier capability consumption; caller supplies only its trusted local monotonic time. */
  synchronized boolean acceptVerified(
      VerifiedGameConnectionProof<C> proof, C actualConnection, long monotonicNowMillis) {
    if (disabled || proof == null || proof.verifier != this) return false;
    if (monotonicNowMillis < 0 || monotonicNowMillis < lastMonotonicMillis) {
      disable();
      return false;
    }
    lastMonotonicMillis = monotonicNowMillis;
    expire(monotonicNowMillis);
    if (proof.acceptance != VerifiedGameConnectionProof.Acceptance.AVAILABLE
        || !verified.containsKey(proof) || proof.connection != actualConnection
        || monotonicNowMillis < proof.issuedMonotonicMillis
        || monotonicNowMillis >= proof.deadlineMonotonicMillis) {
      invalidate(proof);
      return false;
    }
    proof.acceptance = VerifiedGameConnectionProof.Acceptance.CONSUMED;
    verified.remove(proof);
    return true;
  }

  synchronized boolean invalidateVerified(VerifiedGameConnectionProof<C> proof) {
    return invalidate(proof);
  }

  synchronized int trackedCount() { return pending.size() + verified.size(); }

  synchronized void close() { disable(); }

  @Override public String toString() { return "PendingBodyProof[redacted]"; }

  private boolean checkClocks(long monotonicNowMillis, Instant utcNow) {
    if (disabled) return false;
    try {
      ControlAuth.requireUtc(utcNow);
      if (utcNow.isBefore(Instant.EPOCH)
          || monotonicNowMillis < 0 || monotonicNowMillis < lastMonotonicMillis) {
        disable();
        return false;
      }
      lastMonotonicMillis = monotonicNowMillis;
      return true;
    } catch (RuntimeException error) {
      disable();
      return false;
    }
  }

  private void expire(long monotonicNowMillis) {
    pending.entrySet().removeIf(entry -> monotonicNowMillis >= entry.getValue().deadlineMonotonicMillis());
    var iterator = verified.keySet().iterator();
    while (iterator.hasNext()) {
      var proof = iterator.next();
      if (monotonicNowMillis >= proof.deadlineMonotonicMillis) {
        proof.acceptance = VerifiedGameConnectionProof.Acceptance.INVALIDATED;
        iterator.remove();
      }
    }
  }

  private void disable() {
    pending.clear();
    for (var proof : verified.keySet()) {
      proof.acceptance = VerifiedGameConnectionProof.Acceptance.INVALIDATED;
    }
    verified.clear();
    disabled = true;
  }

  private boolean invalidateVerified(C actualConnection) {
    boolean removed = false;
    var iterator = verified.keySet().iterator();
    while (iterator.hasNext()) {
      var proof = iterator.next();
      if (proof.connection == actualConnection) {
        proof.acceptance = VerifiedGameConnectionProof.Acceptance.INVALIDATED;
        iterator.remove();
        removed = true;
      }
    }
    return removed;
  }

  private boolean invalidate(VerifiedGameConnectionProof<C> proof) {
    if (proof == null || proof.verifier != this
        || proof.acceptance != VerifiedGameConnectionProof.Acceptance.AVAILABLE) {
      return false;
    }
    proof.acceptance = VerifiedGameConnectionProof.Acceptance.INVALIDATED;
    verified.remove(proof);
    return true;
  }

  private static byte[] randomNonce() {
    byte[] nonce = new byte[32];
    RANDOM.nextBytes(nonce);
    return nonce;
  }
}
