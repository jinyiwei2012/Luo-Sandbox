package io.github.jinyiwei2012.luosandbox.transport;

import java.util.Objects;
import java.util.function.BooleanSupplier;

/** Fixed, unwired owner handoff primitive; validity is not authentication or cleanup evidence. */
final class V2OwnerMailbox {
  enum EnqueueResult { ACCEPTED, STOPPED }
  enum Reason {
    OWNER_STOPPED, ENQUEUE_FAILED, EARLY_INLINE, WRONG_OWNER, DUPLICATE_PUMP,
    SEND_CELL_FULL, ACTION_FAILED, EXTERNAL_ABORT, INVALID_INPUT
  }
  @FunctionalInterface interface OwnerEnqueue { EnqueueResult enqueue(Runnable pump); }
  interface Handler {
    void timerTurn();
    void terminal(Reason reason);
    /** Arbitrary-thread containment only; must not access owner-confined session/cleanup state. */
    void invalidated(Reason reason);
  }

  private static final class Handoff {
    boolean accepted;
    boolean running;
  }

  private final Object lock = new Object();
  private final BooleanSupplier ownerGate;
  private final OwnerEnqueue enqueue;
  private final Handler handler;
  private Handoff handoff;
  private Runnable send;
  private boolean sendRunning;
  private boolean timer;
  private boolean stopped;
  private Reason terminal;
  private boolean notificationClaimed;
  private boolean terminalAttempted;
  private boolean terminalHandoffAttempted;

  V2OwnerMailbox(BooleanSupplier ownerGate, OwnerEnqueue enqueue, Handler handler) {
    this.ownerGate = Objects.requireNonNull(ownerGate);
    this.enqueue = Objects.requireNonNull(enqueue);
    this.handler = Objects.requireNonNull(handler);
  }

  boolean isValid() {
    synchronized (lock) { return terminal == null && !stopped; }
  }

  boolean postSendCompletion(Runnable action) {
    Handoff schedule = null;
    boolean accepted = false;
    synchronized (lock) {
      if (terminal == null && !stopped) {
        if (action == null) invalidateLocked(Reason.INVALID_INPUT);
        else if (send != null && send != action) invalidateLocked(Reason.SEND_CELL_FULL);
        else {
          send = action;
          accepted = true;
          if (handoff == null) schedule = reserveLocked();
        }
      }
    }
    notifyInvalidated();
    if (schedule != null) dispatch(schedule);
    return accepted;
  }

  void signalTimer() {
    Handoff schedule = null;
    synchronized (lock) {
      if (terminal == null && !stopped) {
        timer = true;
        if (handoff == null) schedule = reserveLocked();
      }
    }
    if (schedule != null) dispatch(schedule);
  }

  /**
   * True records the first reason only, never promises terminal execution. A later explicit request
   * can return false while scheduling the sole terminal handoff after an infrastructure fault.
   */
  boolean requestAbort(Reason reason) {
    Handoff schedule = null;
    boolean first;
    synchronized (lock) {
      first = terminal == null && !stopped;
      if (first) {
        invalidateLocked(reason == null ? Reason.INVALID_INPUT : reason);
      }
      if (!stopped && !terminalAttempted && handoff == null && !terminalHandoffAttempted) {
        terminalHandoffAttempted = true;
        schedule = reserveLocked();
      }
    }
    notifyInvalidated();
    if (schedule != null) dispatch(schedule);
    return first;
  }

  /** Independent lifecycle publication; accepted owner work may never run after this call. */
  void publishStopped() {
    synchronized (lock) {
      stopped = true;
      invalidateLocked(Reason.OWNER_STOPPED);
    }
    notifyInvalidated();
  }

  private Handoff reserveLocked() {
    handoff = new Handoff();
    return handoff;
  }

  private void invalidateLocked(Reason reason) {
    if (terminal == null) terminal = reason;
    if (!sendRunning) send = null;
    timer = false;
  }

  private void notifyInvalidated() {
    Reason reason;
    synchronized (lock) {
      if (terminal == null || notificationClaimed) return;
      notificationClaimed = true;
      reason = terminal;
    }
    try { handler.invalidated(reason); }
    catch (Throwable ignored) { /* Containment notification never masks an owner action failure. */ }
  }

  private void dispatch(Handoff token) {
    EnqueueResult result;
    try { result = enqueue.enqueue(() -> pump(token)); }
    catch (Throwable ignored) { result = null; }
    synchronized (lock) {
      if (result != EnqueueResult.ACCEPTED) {
        if (result == EnqueueResult.STOPPED) stopped = true;
        invalidateLocked(result == EnqueueResult.STOPPED ? Reason.OWNER_STOPPED : Reason.ENQUEUE_FAILED);
        if (handoff == token && !token.running) handoff = null;
      } else if (handoff == token && !stopped) {
        token.accepted = true;
      }
    }
    notifyInvalidated();
  }

  private void infrastructureFailure(Handoff token, Reason reason) {
    synchronized (lock) {
      invalidateLocked(reason);
      // A stale/duplicate invocation cannot clear a successor or a running invocation's token.
      if (handoff == token && !token.running) handoff = null;
    }
    notifyInvalidated();
  }

  private boolean owner() {
    try { return ownerGate.getAsBoolean(); }
    catch (Throwable ignored) { return false; }
  }

  private void pump(Handoff token) {
    Reason rejected = null;
    synchronized (lock) {
      if (handoff != token || token.running) rejected = Reason.DUPLICATE_PUMP;
      else if (stopped) rejected = Reason.OWNER_STOPPED;
      else if (!token.accepted) rejected = Reason.EARLY_INLINE;
    }
    if (rejected != null) { infrastructureFailure(token, rejected); return; }
    if (!owner()) { infrastructureFailure(token, Reason.WRONG_OWNER); return; }
    synchronized (lock) {
      if (handoff != token || token.running) rejected = Reason.DUPLICATE_PUMP;
      else if (stopped) rejected = Reason.OWNER_STOPPED;
      else token.running = true;
    }
    if (rejected != null) { infrastructureFailure(token, rejected); return; }

    Error ownerError = null;
    boolean timerUsed = false;
    boolean canRearm = true;
    int ordinaryActions = 0;
    try {
      for (int count = 0; count < 3; count++) {
        if (!owner()) {
          canRearm = false;
          infrastructureFailure(token, Reason.WRONG_OWNER);
          break;
        }
        Runnable action = null;
        Reason terminalAction = null;
        boolean timerAction = false;
        synchronized (lock) {
          if (handoff != token || !token.running || stopped) break;
          if (terminal != null) {
            if (terminalAttempted) break;
            terminalAttempted = true;
            terminalAction = terminal;
          } else if (ordinaryActions == 2) {
            break; // Reserve the third action exclusively for terminal handling before Error rethrow.
          } else if (send != null) {
            sendRunning = true;
            action = send;
            ordinaryActions++;
          } else if (timer && !timerUsed) {
            timer = false;
            timerUsed = true;
            timerAction = true;
            ordinaryActions++;
          } else break;
        }
        try {
          if (terminalAction != null) handler.terminal(terminalAction);
          else if (timerAction) handler.timerTurn();
          else action.run();
        } catch (Throwable failure) {
          if (ownerError == null && failure instanceof Error error) ownerError = error;
          synchronized (lock) { invalidateLocked(Reason.ACTION_FAILED); }
          notifyInvalidated();
        } finally {
          if (action != null) {
            synchronized (lock) { sendRunning = false; send = null; }
          }
        }
      }
    } finally {
      Handoff next = null;
      synchronized (lock) {
        if (handoff == token) {
          handoff = null;
          token.running = false;
          if (canRearm && !stopped && (terminal != null ? !terminalAttempted : send != null || timer)) {
            next = reserveLocked();
          }
        }
      }
      if (next != null) dispatch(next);
    }
    if (ownerError != null) throw ownerError;
  }
}
