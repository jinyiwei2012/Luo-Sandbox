package io.github.jinyiwei2012.luosandbox.server;

import io.github.jinyiwei2012.luosandbox.servercontrol.PermitAuthority;
import java.util.ArrayDeque;
import java.util.Objects;
import java.util.Optional;

/**
 * Bounded owner-thread observation of protected player-movement handler scopes.
 *
 * <p>A drained observation says only that tracked, normally-returned movement handler invocations
 * for one exact old controlled body have left this bounded scope. It is not body quiescence, READY,
 * world-mutation completion, or permission to restore control.
 */
public final class ProtectedMovementHandlerTracker<S, L, P> {
  private static final int MAX_DEPTH = 8;

  @FunctionalInterface
  public interface TrustedThreadGate {
    boolean isOwner();
  }

  public static final class DrainedObservation {
    private DrainedObservation() {}

    public String status() {
      return "PROTECTED_MOVEMENT_HANDLER_DRAINED";
    }
  }

  private static final class Frame<P> {
    private final ControlledIdentityRegistry.ControlledBody<P> body;
    private boolean authorized;

    private Frame(ControlledIdentityRegistry.ControlledBody<P> body) {
      this.body = body;
    }
  }

  private final S server;
  private final TrustedThreadGate owner;
  private final ArrayDeque<Frame<P>> frames = new ArrayDeque<>(MAX_DEPTH);
  private L listener;
  private P player;
  private ControlledIdentityRegistry.ControlledBody<P> body;
  private long generation;
  private long drainedGeneration = -1;
  private boolean poisoned;

  public ProtectedMovementHandlerTracker(S server, TrustedThreadGate owner) {
    this.server = Objects.requireNonNull(server);
    this.owner = Objects.requireNonNull(owner);
  }

  boolean enter(
      S expectedServer,
      L expectedListener,
      P expectedPlayer,
      ControlledIdentityRegistry.ControlledBody<P> expectedBody) {
    if (!owner.isOwner()) return false;
    Objects.requireNonNull(expectedServer);
    Objects.requireNonNull(expectedListener);
    Objects.requireNonNull(expectedPlayer);
    Objects.requireNonNull(expectedBody);
    if (poisoned || server != expectedServer || frames.size() >= MAX_DEPTH) {
      poisoned = true;
      return false;
    }
    if (!frames.isEmpty()
        && (listener != expectedListener || player != expectedPlayer || body != expectedBody)) {
      poisoned = true;
      return false;
    }
    if (frames.isEmpty()) {
      listener = expectedListener;
      player = expectedPlayer;
      body = expectedBody;
    }
    frames.addLast(new Frame<>(expectedBody));
    return true;
  }

  boolean enterUntracked(S expectedServer, L expectedListener, P expectedPlayer) {
    if (!owner.isOwner()) return false;
    Objects.requireNonNull(expectedServer);
    Objects.requireNonNull(expectedListener);
    Objects.requireNonNull(expectedPlayer);
    if (poisoned || server != expectedServer || frames.size() >= MAX_DEPTH) {
      poisoned = true;
      return false;
    }
    if (!frames.isEmpty() && (listener != expectedListener || player != expectedPlayer)) {
      poisoned = true;
      return false;
    }
    if (frames.isEmpty()) {
      listener = expectedListener;
      player = expectedPlayer;
      body = null;
    }
    frames.addLast(new Frame<>(null));
    return true;
  }

  boolean authorizeLast(
      S expectedServer,
      L expectedListener,
      P expectedPlayer,
      ControlledIdentityRegistry.ControlledBody<P> expectedBody,
      boolean remainsCurrent) {
    if (!owner.isOwner()) return false;
    Frame<P> frame = frames.peekLast();
    if (poisoned
        || frame == null
        || server != expectedServer
        || listener != expectedListener
        || player != expectedPlayer
        || body != expectedBody
        || frame.body != expectedBody
        || !remainsCurrent) {
      poisoned = true;
      return false;
    }
    frame.authorized = true;
    return true;
  }

  void normalReturn(S expectedServer, L expectedListener) {
    if (!owner.isOwner()) {
      poisoned = true;
      return;
    }
    Frame<P> frame = frames.peekLast();
    if (poisoned || frame == null || server != expectedServer || listener != expectedListener) {
      poisoned = true;
      return;
    }
    frames.removeLast();
    if (frames.isEmpty() && frame.authorized) {
      generation++;
      drainedGeneration = generation;
    }
  }

  Optional<DrainedObservation> observeDrained(
      S expectedServer,
      L expectedListener,
      P expectedPlayer,
      PermitAuthority.Binding expectedBinding,
      ControlledIdentityRegistry.PrepareFenceReceipt receipt,
      ControlledPacketGuard<P> guard,
      long monotonicNowMillis) {
    if (!owner.isOwner()) return Optional.empty();
    if (poisoned
        || !frames.isEmpty()
        || drainedGeneration != generation
        || server != expectedServer
        || listener != expectedListener
        || player != expectedPlayer
        || body == null
        || body.player() != expectedPlayer
        || !body.binding().equals(expectedBinding)) return Optional.empty();
    if (!guard.validatesBindingPrepareFence(
        receipt, expectedPlayer, expectedBinding, monotonicNowMillis)) return Optional.empty();
    return Optional.of(new DrainedObservation());
  }

  void invalidate(S expectedServer) {
    if (server != expectedServer) return;
    frames.clear();
    listener = null;
    player = null;
    body = null;
    poisoned = true;
    generation++;
    drainedGeneration = -1;
  }
}
