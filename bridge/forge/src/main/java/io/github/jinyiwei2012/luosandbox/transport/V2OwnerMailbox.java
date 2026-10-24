package io.github.jinyiwei2012.luosandbox.transport;

import java.util.Objects;
import java.util.function.BooleanSupplier;

/** Fixed, unwired owner handoff primitive; validity is not authentication or cleanup evidence. */
final class V2OwnerMailbox {
  enum EnqueueResult { ACCEPTED, STOPPED }
  enum OrdinaryKind { TEXT, PROGRESS, CLOSED, FAILED }
  enum Reason {
    OWNER_STOPPED, ENQUEUE_FAILED, EARLY_INLINE, WRONG_OWNER, DUPLICATE_PUMP,
    SEND_CELL_FULL, ORDINARY_CELL_FULL, CONTROL_CELL_FULL, ACTION_FAILED, EXTERNAL_ABORT,
    INVALID_INPUT
  }
  @FunctionalInterface interface OwnerEnqueue { EnqueueResult enqueue(Runnable pump); }
  interface Handler {
    default void ordinary(OrdinaryKind kind, int lane, long lease) {}
    default void buildTurn() {}
    default void controlTurn(boolean ping, int length) {}
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
  private static final int ORDINARY_CELLS = 8;
  private static final int BUILD = 8;
  private static final int CONTROL = 9;
  private static final int SEND = 10;
  private static final int TIMER = 11;
  private static final int NONTERMINAL_CELLS = 12;
  private final OrdinaryKind[] kinds = new OrdinaryKind[ORDINARY_CELLS];
  private final int[] lanes = new int[ORDINARY_CELLS];
  private final long[] leases = new long[ORDINARY_CELLS];
  private int runningCells;
  private int dirtyCells;
  private int cursor;
  private boolean build;
  private boolean control;
  private boolean controlPing;
  private int controlLength;
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

  /** Credit requires room for either text metadata or control, including running claims. */
  boolean hasIngressCapacity() {
    synchronized (lock) {
      if (terminal != null || stopped || control) return false;
      for (OrdinaryKind kind : kinds) if (kind == null) return true;
      return false;
    }
  }

  /** Only TEXT retains a lane reference; at most one occupied cell may refer to each lane. */
  boolean postOrdinary(OrdinaryKind kind, int lane, long lease) {
    Handoff schedule = null;
    boolean accepted = false;
    synchronized (lock) {
      if (terminal == null && !stopped) {
        if (kind == null || (kind == OrdinaryKind.TEXT
            ? lane < 0 || lane > 1 || lease <= 0 : lane != -1 || lease != 0)) {
          invalidateLocked(Reason.INVALID_INPUT);
        } else {
          int free = -1;
          boolean conflict = false;
          for (int i = 0; i < ORDINARY_CELLS; i++) {
            if (kinds[i] == null) {
              if (free < 0) free = i;
            } else if (kind == OrdinaryKind.PROGRESS && kinds[i] == kind
                && lanes[i] == lane && leases[i] == lease) {
              accepted = true;
              if ((runningCells & (1 << i)) != 0) dirtyCells |= 1 << i;
            } else if (kind == OrdinaryKind.TEXT && kinds[i] == kind && lanes[i] == lane) {
              conflict = true;
            }
          }
          if (!accepted) {
            if (conflict || free < 0) invalidateLocked(Reason.ORDINARY_CELL_FULL);
            else {
              kinds[free] = kind;
              lanes[free] = lane;
              leases[free] = lease;
              accepted = true;
            }
          }
          if (accepted && handoff == null) schedule = reserveLocked();
        }
      }
    }
    notifyInvalidated();
    if (schedule != null) dispatch(schedule);
    return accepted;
  }

  /** Socket identity is retained and checked by the connector, never by this wake cell. */
  boolean postBuild() {
    Handoff schedule = null;
    synchronized (lock) {
      if (terminal != null || stopped) return false;
      if ((runningCells & (1 << BUILD)) != 0) dirtyCells |= 1 << BUILD;
      build = true;
      if (handoff == null) schedule = reserveLocked();
    }
    if (schedule != null) dispatch(schedule);
    return true;
  }

  boolean postControl(boolean ping, int length) {
    Handoff schedule = null;
    boolean accepted = false;
    synchronized (lock) {
      if (terminal == null && !stopped) {
        if (length < 0 || length > 125) invalidateLocked(Reason.INVALID_INPUT);
        else if (control && (controlPing != ping || controlLength != length)) {
          invalidateLocked(Reason.CONTROL_CELL_FULL);
        } else {
          control = true;
          controlPing = ping;
          controlLength = length;
          accepted = true;
          if (handoff == null) schedule = reserveLocked();
        }
      }
    }
    notifyInvalidated();
    if (schedule != null) dispatch(schedule);
    return accepted;
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
    for (int i = 0; i < ORDINARY_CELLS; i++) {
      if ((runningCells & (1 << i)) == 0) clearOrdinaryLocked(i);
    }
    if ((runningCells & (1 << BUILD)) == 0) build = false;
    if ((runningCells & (1 << CONTROL)) == 0) control = false;
    if (!sendRunning) send = null;
    timer = false;
  }

  private void clearOrdinaryLocked(int cell) {
    kinds[cell] = null;
    lanes[cell] = 0;
    leases[cell] = 0;
  }

  private boolean occupiedLocked(int cell) {
    if (cell < ORDINARY_CELLS) return kinds[cell] != null;
    return switch (cell) {
      case BUILD -> build;
      case CONTROL -> control;
      case SEND -> send != null;
      case TIMER -> timer;
      default -> false;
    };
  }

  private boolean pendingLocked() {
    for (int i = 0; i < NONTERMINAL_CELLS; i++) {
      if (occupiedLocked(i)) return true;
    }
    return false;
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
    int visited = 0;
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
        int cell = -1;
        OrdinaryKind kind = null;
        int lane = -1;
        long lease = 0;
        boolean ping = false;
        int length = 0;
        synchronized (lock) {
          if (handoff != token || !token.running || stopped) break;
          if (terminal != null) {
            if (terminalAttempted) break;
            terminalAttempted = true;
            terminalAction = terminal;
          } else if (ordinaryActions == 2) {
            break; // Reserve the third action exclusively for terminal handling before Error rethrow.
          } else {
            // Bound each claim to one circular scan, and each cell to one claim per turn.
            for (int scanned = 0; scanned < NONTERMINAL_CELLS; scanned++) {
              int candidate = (cursor + scanned) % NONTERMINAL_CELLS;
              if ((visited & (1 << candidate)) == 0 && occupiedLocked(candidate)) {
                cell = candidate;
                break;
              }
            }
            if (cell < 0) break;
            cursor = (cell + 1) % NONTERMINAL_CELLS;
            visited |= 1 << cell;
            runningCells |= 1 << cell;
            if (cell < ORDINARY_CELLS) {
              kind = kinds[cell];
              lane = lanes[cell];
              lease = leases[cell];
            } else if (cell == SEND) {
              sendRunning = true;
              action = send;
            } else if (cell == TIMER) timer = false;
            else if (cell == CONTROL) {
              ping = controlPing;
              length = controlLength;
            }
            ordinaryActions++;
          }
        }
        try {
          if (terminalAction != null) handler.terminal(terminalAction);
          else if (cell < ORDINARY_CELLS) handler.ordinary(kind, lane, lease);
          else if (cell == BUILD) handler.buildTurn();
          else if (cell == CONTROL) handler.controlTurn(ping, length);
          else if (cell == TIMER) handler.timerTurn();
          else action.run();
        } catch (Throwable failure) {
          if (ownerError == null && failure instanceof Error error) ownerError = error;
          synchronized (lock) { invalidateLocked(Reason.ACTION_FAILED); }
          notifyInvalidated();
        } finally {
          if (cell >= 0) {
            synchronized (lock) {
              runningCells &= ~(1 << cell);
              boolean dirty = terminal == null && (dirtyCells & (1 << cell)) != 0;
              dirtyCells &= ~(1 << cell);
              if (cell < ORDINARY_CELLS) { if (!dirty) clearOrdinaryLocked(cell); }
              else if (cell == BUILD) build = dirty;
              else if (cell == CONTROL) control = false;
              else if (cell == SEND) { sendRunning = false; send = null; }
            }
          }
        }
      }
    } finally {
      Handoff next = null;
      synchronized (lock) {
        if (handoff == token) {
          handoff = null;
          token.running = false;
          if (canRearm && !stopped && (terminal != null ? !terminalAttempted : pendingLocked())) {
            next = reserveLocked();
          }
        }
      }
      if (next != null) dispatch(next);
    }
    if (ownerError != null) throw ownerError;
  }
}
