package io.github.jinyiwei2012.luosandbox.inputcontrol;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Pure client key-lease gate. Server-side spatial and cumulative-distance enforcement remains
 * authoritative: this class only preserves immutable approved bound metadata across renewals.
 */
public final class InputLease {
  public static final long MAX_LEASE_MILLIS = 750L;
  private static final int MAX_SEEN_PERMITS = 64;

  public enum Intent { LOOK, BOUNDED_WALK }
  public enum InputKey { FORWARD, BACK, LEFT, RIGHT }

  /** This port owns controller keys only; it never observes or releases human-owned keys. */
  public interface KeySink {
    void setOwned(Set<InputKey> keys);
    void releaseAllOwned();
  }

  /** Implementations must invoke callbacks only on the legal client thread. */
  public interface ClientThreadDispatcher {
    void dispatch(Runnable callback);
    long monotonicNow();
  }

  public record Binding(String actorId, String worldId, String bodyId, UUID sessionId,
                        long sessionEpoch, UUID serverBootId, UUID gameConnectionId) {
    public Binding {
      if (blank(actorId) || blank(worldId) || blank(bodyId) || sessionId == null || sessionEpoch < 0
          || serverBootId == null || gameConnectionId == null) throw new IllegalArgumentException("invalid binding");
    }
  }

  public record Position(double x, double y, double z) {
    public Position {
      if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) throw new IllegalArgumentException("invalid position");
    }
  }

  public record Bounds(String dimensionId, Position min, Position max, double maximumDistance) {
    public Bounds {
      if (blank(dimensionId) || min == null || max == null || !Double.isFinite(maximumDistance)
          || min.x() > max.x() || min.y() > max.y() || min.z() > max.z()
          || maximumDistance < 0 || maximumDistance > 4) throw new IllegalArgumentException("invalid bounds");
    }
  }

  /** Trusted transport creates this from the actual bound Server/game connection, not payload claims. */
  public record TrustedServerContext(Binding binding, String originId) {
    public TrustedServerContext {
      if (binding == null || blank(originId)) throw new IllegalArgumentException("missing trusted context");
    }
  }

  /** Registry-created higher-epoch quiescence evidence, never a peer-provided boolean. */
  public record HigherEpochProof(Binding binding, String proofId) {
    public HigherEpochProof {
      if (binding == null || blank(proofId)) throw new IllegalArgumentException("missing higher epoch proof");
    }
  }

  /** Remaining values are calculated from externally trusted server fields and clocks. */
  public record Permit(UUID permitId, Binding binding, String requestId, Intent intent, Bounds bounds,
                       long leaseMillis, long utcRemainingMillis, long requestRemainingMillis,
                       long grantRemainingMillis) {
    public Permit {
      if (permitId == null || binding == null || blank(requestId) || intent == null || bounds == null
          || leaseMillis <= 0 || leaseMillis > MAX_LEASE_MILLIS || utcRemainingMillis <= 0
          || requestRemainingMillis <= 0 || grantRemainingMillis <= 0
          || (intent == Intent.LOOK && bounds.maximumDistance() != 0)
          || (intent == Intent.BOUNDED_WALK && bounds.maximumDistance() <= 0)) throw new IllegalArgumentException("invalid permit");
    }
  }

  private final KeySink keySink;
  private final ClientThreadDispatcher dispatcher;
  private final AtomicBoolean emergency = new AtomicBoolean();
  private final LinkedHashSet<UUID> seenPermitIds = new LinkedHashSet<>();
  private Binding binding;
  private Binding revokedBinding;
  private UUID activePermit;
  private String activeRequest;
  private Intent activeIntent;
  private Bounds activeBounds;
  private long leaseDeadline;
  private long requestDeadline;
  private long lastMonotonic;
  private long generation;
  private boolean denied;

  public InputLease(Binding initialBinding, KeySink keySink, ClientThreadDispatcher dispatcher) {
    binding = Objects.requireNonNull(initialBinding, "initialBinding");
    this.keySink = Objects.requireNonNull(keySink, "keySink");
    this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
  }

  /** Accepts only a new, externally trusted permit matching the full currently live tuple. */
  public synchronized boolean acceptPermit(TrustedServerContext trusted, Permit permit, long monotonicNow) {
    if (!advanceClock(monotonicNow) || trusted == null || permit == null || emergency.get() || denied
        || !binding.equals(trusted.binding()) || !binding.equals(permit.binding()) || binding.equals(revokedBinding)) {
      denyAndRelease();
      return false;
    }
    if (expired(monotonicNow)) deactivate();
    if (seenPermitIds.contains(permit.permitId()) || seenPermitIds.size() >= MAX_SEEN_PERMITS) {
      denyAndRelease();
      return false;
    }
    try {
      long duration = Math.min(Math.min(permit.leaseMillis(), permit.utcRemainingMillis()),
          Math.min(permit.requestRemainingMillis(), Math.min(permit.grantRemainingMillis(), MAX_LEASE_MILLIS)));
      long candidateLeaseDeadline = Math.addExact(monotonicNow, duration);
      long candidateRequestDeadline = Math.addExact(monotonicNow, permit.requestRemainingMillis());
      if (duration <= 0 || (activeRequest != null && activeRequest.equals(permit.requestId())
          && (candidateRequestDeadline > requestDeadline || activeIntent != permit.intent() || !activeBounds.equals(permit.bounds())))) {
        denyAndRelease();
        return false;
      }
      seenPermitIds.add(permit.permitId());
      activePermit = permit.permitId();
      activeRequest = permit.requestId();
      activeIntent = permit.intent();
      activeBounds = permit.bounds();
      leaseDeadline = candidateLeaseDeadline;
      requestDeadline = activeRequest.equals(permit.requestId()) && requestDeadline > 0
          ? Math.min(requestDeadline, candidateRequestDeadline) : candidateRequestDeadline;
      generation++;
      return true;
    } catch (ArithmeticException error) {
      denyAndRelease();
      return false;
    }
  }

  /** Schedules only four typed walk keys; LOOK never authorizes movement. */
  public synchronized boolean applyMovement(Set<InputKey> requested, long monotonicNow) {
    if (!advanceClock(monotonicNow) || !active(monotonicNow) || activeIntent != Intent.BOUNDED_WALK
        || requested == null || !Set.of(InputKey.FORWARD, InputKey.BACK, InputKey.LEFT, InputKey.RIGHT).containsAll(requested)) {
      denyAndRelease();
      return false;
    }
    Set<InputKey> immutable = Set.copyOf(requested);
    long expectedGeneration = generation;
    try {
      dispatcher.dispatch(() -> applyIfCurrent(expectedGeneration, immutable));
      return true;
    } catch (RuntimeException error) {
      denyAndRelease();
      return false;
    }
  }

  public synchronized void revoke(Binding tuple) {
    if (tuple != null && tuple.equals(binding)) { revokedBinding = tuple; denyAndRelease(); }
  }

  /** IO-safe latch first; actual key mutation is always dispatched through the client-thread port. */
  public void emergencyStop(Binding tuple) {
    if (tuple == null) return;
    synchronized (this) {
      if (tuple.equals(binding)) { revokedBinding = tuple; emergency.set(true); denyAndRelease(); }
    }
  }

  /** Only externally verified higher-epoch quiescence evidence can clear a fence for a new tuple. */
  public synchronized boolean advanceBinding(HigherEpochProof proof) {
    if (proof == null || !sameBody(proof.binding(), binding) || proof.binding().sessionEpoch() <= binding.sessionEpoch()) return false;
    deactivate();
    binding = proof.binding();
    revokedBinding = null;
    emergency.set(false);
    denied = false;
    seenPermitIds.clear();
    return true;
  }

  public synchronized boolean tick(long monotonicNow) {
    if (!advanceClock(monotonicNow)) { denyAndRelease(); return false; }
    if (expired(monotonicNow)) { deactivate(); return false; }
    return active(monotonicNow);
  }

  public synchronized void focusLost() { deactivate(); }
  public synchronized void guiOpened() { deactivate(); }
  public synchronized void worldUnloaded() { deactivate(); }
  public synchronized void disconnected() { deactivate(); }
  public synchronized void shutdown() { deactivate(); }

  private synchronized void applyIfCurrent(long expectedGeneration, Set<InputKey> keys) {
    long now = dispatcher.monotonicNow();
    if (!advanceClock(now)) {
      denyAndRelease();
      return;
    }
    if (expectedGeneration != generation || emergency.get() || denied) return;
    if (expired(now)) {
      deactivate();
      return;
    }
    if (!active(now) || activeIntent != Intent.BOUNDED_WALK) {
      denyAndRelease();
      return;
    }
    try { keySink.setOwned(keys); } catch (RuntimeException error) { denyAndRelease(); }
  }

  private boolean advanceClock(long now) { if (now < 0 || now < lastMonotonic) return false; lastMonotonic = now; return true; }
  private boolean active(long now) { return activePermit != null && now < leaseDeadline && now < requestDeadline; }
  private boolean expired(long now) { return activePermit != null && (now >= leaseDeadline || now >= requestDeadline); }
  private static boolean sameBody(Binding a, Binding b) { return a.actorId().equals(b.actorId()) && a.worldId().equals(b.worldId()) && a.bodyId().equals(b.bodyId()); }
  private synchronized void denyAndRelease() { denied = true; deactivate(); }
  private synchronized void deactivate() {
    activePermit = null; activeRequest = null; activeIntent = null; activeBounds = null; leaseDeadline = 0; requestDeadline = 0; generation++;
    try { dispatcher.dispatch(() -> { try { keySink.releaseAllOwned(); } catch (RuntimeException error) { synchronized (InputLease.this) { denied = true; } } }); }
    catch (RuntimeException error) { denied = true; }
  }
  private static boolean blank(String value) { return value == null || value.isBlank(); }
}
