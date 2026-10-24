package io.github.jinyiwei2012.luosandbox.server;

import java.util.IdentityHashMap;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Bounded accounting for this mod's container-close submissions only; never control authority. */
final class ModContainerCloseWorkTracker {
  private static final int CAPACITY = 8;

  enum Availability { UNOBSERVED, TRACKING, UNKNOWN }
  enum Classification { CURRENT_BODY, NON_CURRENT_PROTECTED_CONNECTION }
  private enum Submission { CALLING_EXECUTE, RETURNED, THREW }
  private enum Execution { NOT_ENTERED, RUNNING, RETURNED_NORMALLY, THREW }

  record Summary(Availability availability, int outstanding, int running,
                 boolean noOutstandingObservedSubmissions) {}

  private static final class Work {
    final Object server;
    final Object player;
    final Classification classification;
    final ControlledIdentityRegistry.ContainerCloseCapture<?> capture;
    Submission submission = Submission.CALLING_EXECUTE;
    Execution execution = Execution.NOT_ENTERED;

    Work(Object server, Object player, ControlledIdentityRegistry.ContainerCloseCapture<?> capture) {
      this.server = server;
      this.player = player;
      this.capture = capture;
      classification = capture == null ? Classification.NON_CURRENT_PROTECTED_CONNECTION
          : Classification.CURRENT_BODY;
    }
  }

  private final IdentityHashMap<Work, Boolean> active = new IdentityHashMap<>();
  private volatile boolean invalidated;
  private boolean coverageLost;
  private boolean observed;

  /** Opaque call-local handle; it cannot be constructed outside this ledger. */
  final class SubmissionCall {
    private final BooleanSupplier gate;
    private final Work work;
    private final Runnable runnable;

    private SubmissionCall(BooleanSupplier gate, Work work, Runnable delegate) {
      this.gate = gate;
      this.work = work;
      runnable = work == null ? delegate : () -> run(gate, work, delegate);
    }

    Runnable runnable() { return runnable; }

    void returned() { finish(Submission.RETURNED); }

    void threw() { finish(Submission.THREW); }

    private void finish(Submission result) {
      if (work != null) endSubmission(gate, work, result);
    }
  }

  static boolean owner(BooleanSupplier gate) {
    try {
      return gate != null && gate.getAsBoolean();
    } catch (RuntimeException | Error failure) {
      return false;
    }
  }

  static void requireOwner(BooleanSupplier gate) {
    if (!owner(gate)) throw new IllegalStateException("container-close work requires server owner thread");
  }

  void invalidate() {
    invalidated = true;
  }

  Summary summary(BooleanSupplier gate) {
    requireOwner(gate);
    int running = 0;
    for (Work work : active.keySet()) if (work.execution == Execution.RUNNING) running++;
    Availability availability = invalidated || coverageLost ? Availability.UNKNOWN
        : observed ? Availability.TRACKING : Availability.UNOBSERVED;
    return new Summary(availability, active.size(), running,
        availability == Availability.TRACKING && active.isEmpty());
  }

  void submit(BooleanSupplier gate, Object server, Object player,
      Optional<? extends ControlledIdentityRegistry.ContainerCloseCapture<?>> capture,
      Consumer<Runnable> execute, Runnable delegate) {
    SubmissionCall call = register(gate, server, player, capture, delegate);
    try {
      execute.accept(call.runnable());
    } catch (RuntimeException | Error failure) {
      call.threw();
      throw failure;
    }
    call.returned();
  }

  SubmissionCall register(BooleanSupplier gate, Object server, Object player,
      Optional<? extends ControlledIdentityRegistry.ContainerCloseCapture<?>> capture,
      Runnable delegate) {
    requireOwner(gate);
    if (active.size() == CAPACITY) {
      coverageLost = true;
      return new SubmissionCall(gate, null, delegate);
    }
    Work work = new Work(server, player, capture.orElse(null));
    active.put(work, Boolean.TRUE);
    observed = true;
    return new SubmissionCall(gate, work, delegate);
  }

  private void endSubmission(BooleanSupplier gate, Work work, Submission result) {
    if (!owner(gate)) {
      invalidate();
      return;
    }
    work.submission = result;
    if (result == Submission.THREW) coverageLost = true;
    retire(work);
  }

  private void run(BooleanSupplier gate, Work work, Runnable delegate) {
    if (!owner(gate)) {
      invalidate();
      delegate.run();
      return;
    }
    if (work.execution != Execution.NOT_ENTERED) {
      coverageLost = true;
      delegate.run();
      return;
    }
    work.execution = Execution.RUNNING;
    try {
      delegate.run();
    } catch (RuntimeException | Error failure) {
      endExecution(gate, work, Execution.THREW);
      throw failure;
    }
    endExecution(gate, work, Execution.RETURNED_NORMALLY);
  }

  private void endExecution(BooleanSupplier gate, Work work, Execution result) {
    if (!owner(gate)) {
      invalidate();
      return;
    }
    work.execution = result;
    if (result == Execution.THREW) coverageLost = true;
    retire(work);
  }

  private void retire(Work work) {
    if (work.submission != Submission.CALLING_EXECUTE
        && (work.execution == Execution.RETURNED_NORMALLY || work.execution == Execution.THREW)) {
      active.remove(work);
    }
  }
}
