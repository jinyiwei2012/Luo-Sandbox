package io.github.jinyiwei2012.luosandbox.server;

import io.github.jinyiwei2012.luosandbox.servercontrol.PermitAuthority;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Sealed, one-use, server-local evidence about which binding-PREPARE scopes were observed drained.
 *
 * <p>This is evidence only, never authority. It is not READY, not a rebind or commit proof, and it
 * cannot create permits or be delivered on the wire. Only some scopes have an observer today; the
 * others are reported {@link Status#UNAVAILABLE}, so {@link Snapshot#complete()} cannot be true
 * until every scope has a real observer. Instances are created only by
 * {@link ForgePacketDefenseBridge} on the server owner thread and have identity-only equality.
 */
public final class BindingQuiescenceObservation {
  /** Scopes that complete binding quiescence would have to cover. */
  public enum Scope {
    MOVEMENT_HANDLER,
    LOCAL_PERMIT_AUTHORITY,
    CHAT_DEFERRED,
    PERMIT_QUEUE_WATCHDOG,
    CLIENT_PERMIT_CONSUMPTION,
    SCHEDULED_SERVER_TASKS
  }

  /** Per-scope observation result. Only DRAINED counts toward completeness. */
  public enum Status {
    DRAINED,
    NOT_DRAINED,
    UNAVAILABLE,
    FAILED
  }

  /** Immutable per-scope status snapshot. */
  public static final class Snapshot {
    private final Map<Scope, Status> statuses;

    private Snapshot(Map<Scope, Status> statuses) {
      this.statuses = statuses;
    }

    static Snapshot of(Map<Scope, Status> source) {
      Objects.requireNonNull(source);
      EnumMap<Scope, Status> copy = new EnumMap<>(Scope.class);
      for (Scope scope : Scope.values()) {
        copy.put(scope, Objects.requireNonNull(source.get(scope), scope.name()));
      }
      return new Snapshot(Collections.unmodifiableMap(copy));
    }

    public Status status(Scope scope) {
      return statuses.get(Objects.requireNonNull(scope));
    }

    public Map<Scope, Status> statuses() {
      return statuses;
    }

    /** True only if every scope is DRAINED; always false while any scope lacks an observer. */
    public boolean complete() {
      for (Scope scope : Scope.values()) {
        if (statuses.get(scope) != Status.DRAINED) return false;
      }
      return true;
    }
  }

  private final Object owner;
  private final Object server;
  private final Object listener;
  private final Object player;
  private final Object body;
  private final PermitAuthority.Binding binding;
  private final Object receipt;
  private final long generation;
  private final long mintedAtMillis;
  private final long deadlineMillis;
  private final Snapshot mintSnapshot;
  private final AtomicBoolean spent = new AtomicBoolean();

  private BindingQuiescenceObservation(
      Object owner,
      Object server,
      Object listener,
      Object player,
      Object body,
      PermitAuthority.Binding binding,
      Object receipt,
      long generation,
      long mintedAtMillis,
      long deadlineMillis,
      Snapshot mintSnapshot) {
    this.owner = owner;
    this.server = server;
    this.listener = listener;
    this.player = player;
    this.body = body;
    this.binding = binding;
    this.receipt = receipt;
    this.generation = generation;
    this.mintedAtMillis = mintedAtMillis;
    this.deadlineMillis = deadlineMillis;
    this.mintSnapshot = mintSnapshot;
  }

  /** Package-private: only the owning bridge mints, after validating the live PREPARE fence. */
  static BindingQuiescenceObservation create(
      Object owner,
      Object server,
      Object listener,
      Object player,
      Object body,
      PermitAuthority.Binding binding,
      Object receipt,
      long generation,
      long mintedAtMillis,
      long deadlineMillis,
      Snapshot mintSnapshot) {
    return new BindingQuiescenceObservation(
        Objects.requireNonNull(owner),
        Objects.requireNonNull(server),
        Objects.requireNonNull(listener),
        Objects.requireNonNull(player),
        Objects.requireNonNull(body),
        Objects.requireNonNull(binding),
        Objects.requireNonNull(receipt),
        generation,
        mintedAtMillis,
        deadlineMillis,
        Objects.requireNonNull(mintSnapshot));
  }

  /** Status snapshot taken when this observation was minted; informational only. */
  public Snapshot mintSnapshot() {
    return mintSnapshot;
  }

  public boolean isSpent() {
    return spent.get();
  }

  /** Marks the observation spent; returns true only for the first caller. */
  boolean consume() {
    return spent.compareAndSet(false, true);
  }

  void poison() {
    spent.set(true);
  }

  boolean matches(
      Object expectedOwner,
      Object expectedServer,
      Object expectedListener,
      Object expectedPlayer,
      Object expectedBody,
      PermitAuthority.Binding expectedBinding,
      Object expectedReceipt) {
    return owner == expectedOwner
        && server == expectedServer
        && listener == expectedListener
        && player == expectedPlayer
        && body == expectedBody
        && binding.equals(expectedBinding)
        && receipt == expectedReceipt;
  }

  boolean sameFence(Object expectedBody, long expectedGeneration, long expectedDeadlineMillis) {
    return body == expectedBody
        && generation == expectedGeneration
        && deadlineMillis == expectedDeadlineMillis;
  }

  boolean timeWindowContains(long nowMillis) {
    return nowMillis >= mintedAtMillis && nowMillis < deadlineMillis;
  }

  boolean expiredAt(long nowMillis) {
    return nowMillis >= deadlineMillis;
  }
}
