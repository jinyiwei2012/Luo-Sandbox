package io.github.jinyiwei2012.luosandbox.transport;

import static org.junit.jupiter.api.Assertions.*;

import io.github.jinyiwei2012.luosandbox.security.ControlAuth;
import io.github.jinyiwei2012.luosandbox.security.PostAuthFrameCodec;
import io.github.jinyiwei2012.luosandbox.security.PostAuthSessionKeys;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;

class V2OwnerMailboxTest {
  @Test void runningBuildAndProgressRepublicationSurviveUntilNextTurn() {
    Harness h = new Harness();
    h.buildAction = () -> { if (h.cells.size() == 1) assertTrue(h.mailbox.postBuild()); };
    h.mailbox.postBuild(); h.turn();
    assertEquals(List.of("build"), h.cells); assertEquals(1, h.queue.size());
    h.turn(); assertEquals(List.of("build", "build"), h.cells); assertTrue(h.queue.isEmpty());
    Harness p = new Harness();
    p.ordinaryAction = () -> {
      if (p.cells.size() == 1) assertTrue(p.mailbox.postOrdinary(V2OwnerMailbox.OrdinaryKind.PROGRESS, -1, 0));
    };
    p.mailbox.postOrdinary(V2OwnerMailbox.OrdinaryKind.PROGRESS, -1, 0); p.turn();
    assertEquals(1, p.cells.size()); assertEquals(1, p.queue.size());
    p.turn(); assertEquals(2, p.cells.size()); assertTrue(p.queue.isEmpty());
  }
  private static final class Harness implements V2OwnerMailbox.Handler {
    final ArrayDeque<Runnable> queue = new ArrayDeque<>();
    final List<V2OwnerMailbox.Reason> notifications = new ArrayList<>();
    final List<V2OwnerMailbox.Reason> terminals = new ArrayList<>();
    int enqueues;
    int timers;
    int gates;
    final List<String> cells = new ArrayList<>();
    Runnable ordinaryAction = () -> {};
    Runnable buildAction = () -> {};
    Runnable controlAction = () -> {};
    boolean owner = true;
    Runnable timerAction = () -> {};
    Runnable terminalAction = () -> {};
    Runnable notificationAction = () -> {};
    V2OwnerMailbox.OwnerEnqueue port = task -> { queue.add(task); return V2OwnerMailbox.EnqueueResult.ACCEPTED; };
    BooleanSupplier gate = () -> { gates++; return owner; };
    final V2OwnerMailbox mailbox = new V2OwnerMailbox(() -> gate.getAsBoolean(), task -> {
      enqueues++; return port.enqueue(task);
    }, this);
    @Override public void timerTurn() { timers++; timerAction.run(); }
    @Override public void ordinary(V2OwnerMailbox.OrdinaryKind kind, int lane, long lease) {
      cells.add(kind + ":" + lane + ":" + lease); ordinaryAction.run();
    }
    @Override public void buildTurn() { cells.add("build"); buildAction.run(); }
    @Override public void controlTurn(boolean ping, int length) {
      cells.add("control:" + ping + ":" + length); controlAction.run();
    }
    @Override public void terminal(V2OwnerMailbox.Reason reason) { terminals.add(reason); terminalAction.run(); }
    @Override public void invalidated(V2OwnerMailbox.Reason reason) { notifications.add(reason); notificationAction.run(); }
    void turn() { queue.removeFirst().run(); }
  }

  @Test void constructionIsInertAndRequiredReferencesAreValidated() {
    Harness h = new Harness();
    assertEquals(0, h.gates); assertEquals(0, h.enqueues); assertTrue(h.mailbox.isValid());
    assertThrows(NullPointerException.class, () -> new V2OwnerMailbox(null, h.port, h));
    assertThrows(NullPointerException.class, () -> new V2OwnerMailbox(() -> true, null, h));
    assertThrows(NullPointerException.class, () -> new V2OwnerMailbox(() -> true, h.port, null));
  }

  @Test void acceptedCompletionAndSameIdentityCoalesceUntilRelease() {
    Harness h = new Harness(); AtomicInteger calls = new AtomicInteger();
    Runnable action = calls::incrementAndGet;
    assertTrue(h.mailbox.postSendCompletion(action));
    assertTrue(h.mailbox.postSendCompletion(action)); assertEquals(1, h.enqueues);
    h.turn(); assertEquals(1, calls.get());
    assertTrue(h.mailbox.postSendCompletion(action)); h.turn(); assertEquals(2, calls.get());
  }

  @Test void runningCellRemainsOccupiedAndDifferentIdentityPermanentlyInvalidates() {
    Harness h = new Harness(); AtomicReference<Runnable> self = new AtomicReference<>();
    self.set(() -> {
      assertTrue(h.mailbox.postSendCompletion(self.get()));
      assertFalse(h.mailbox.postSendCompletion(() -> fail("must not run")));
      assertFalse(h.mailbox.isValid());
    });
    assertTrue(h.mailbox.postSendCompletion(self.get())); h.turn();
    assertEquals(List.of(V2OwnerMailbox.Reason.SEND_CELL_FULL), h.terminals);
    assertEquals(1, h.notifications.size()); assertTrue(h.queue.isEmpty());
  }

  @Test void queuedDifferentCompletionClearsOrdinaryAndKeepsTerminalPriority() {
    Harness h = new Harness();
    h.mailbox.postSendCompletion(() -> fail()); h.mailbox.signalTimer();
    assertFalse(h.mailbox.postSendCompletion(() -> fail())); h.turn();
    assertEquals(0, h.timers); assertEquals(List.of(V2OwnerMailbox.Reason.SEND_CELL_FULL), h.terminals);
  }

  @Test void timerFloodIsOnePerTurnAndReentrantOrdinaryWorkIsBoundedToTwoActions() {
    Harness h = new Harness(); AtomicInteger actions = new AtomicInteger();
    h.timerAction = () -> {
      for (int i = 0; i < 100; i++) h.mailbox.signalTimer();
      h.mailbox.postSendCompletion(actions::incrementAndGet);
    };
    for (int i = 0; i < 100; i++) h.mailbox.signalTimer();
    assertEquals(1, h.enqueues); h.turn();
    assertEquals(1, h.timers); assertEquals(1, actions.get()); assertEquals(1, h.queue.size());
    h.mailbox.publishStopped(); h.turn(); assertEquals(1, h.timers);
  }

  @Test void explicitAbortHasOneTerminalHandoffAndAttemptGuardBeforeReentry() {
    Harness h = new Harness();
    h.terminalAction = () -> {
      assertFalse(h.mailbox.requestAbort(V2OwnerMailbox.Reason.ACTION_FAILED));
      h.mailbox.signalTimer(); throw new IllegalStateException("terminal");
    };
    assertTrue(h.mailbox.requestAbort(V2OwnerMailbox.Reason.EXTERNAL_ABORT));
    assertFalse(h.mailbox.requestAbort(V2OwnerMailbox.Reason.EXTERNAL_ABORT));
    h.turn(); assertEquals(1, h.terminals.size()); assertEquals(1, h.enqueues);
    assertTrue(h.queue.isEmpty());
  }

  @Test void failedTerminalEnqueueNeverRecursivelyRetriesAndProducerErrorsAreContained() {
    for (int mode = 0; mode < 4; mode++) {
      Harness h = new Harness(); int selected = mode;
      h.port = task -> {
        if (selected == 0) return V2OwnerMailbox.EnqueueResult.STOPPED;
        if (selected == 1) return null;
        if (selected == 2) throw new IllegalStateException("enqueue");
        throw new AssertionError("enqueue");
      };
      assertDoesNotThrow(() -> assertTrue(h.mailbox.requestAbort(V2OwnerMailbox.Reason.EXTERNAL_ABORT)));
      assertEquals(1, h.enqueues); assertFalse(h.mailbox.isValid());
      assertFalse(h.mailbox.requestAbort(V2OwnerMailbox.Reason.EXTERNAL_ABORT));
      assertEquals(0, h.terminals.size()); assertEquals(1, h.notifications.size());
    }
  }

  @Test void inlineAndLegitimateAsyncPreacceptanceFailClosedWithoutOwnerAccess() throws Exception {
    Harness inline = new Harness(); inline.port = task -> { task.run(); return V2OwnerMailbox.EnqueueResult.ACCEPTED; };
    inline.mailbox.signalTimer(); assertEquals(0, inline.gates); assertEquals(0, inline.timers);
    assertEquals(List.of(V2OwnerMailbox.Reason.EARLY_INLINE), inline.notifications);
    Harness early = new Harness();
    early.port = task -> {
      Thread t = new Thread(task); t.start(); join(t);
      return V2OwnerMailbox.EnqueueResult.ACCEPTED;
    };
    early.mailbox.signalTimer(); assertFalse(early.mailbox.isValid());
    assertEquals(0, early.gates); assertEquals(0, early.timers); assertEquals(1, early.enqueues);
  }

  @Test void wrongOwnerAndThrowingGateNeverAuthorizeTerminalCleanup() {
    for (int mode = 0; mode < 3; mode++) {
      Harness h = new Harness(); int selected = mode;
      h.gate = () -> {
        if (selected == 1) throw new IllegalStateException("gate");
        if (selected == 2) throw new AssertionError("gate");
        return false;
      };
      h.mailbox.signalTimer(); h.turn();
      assertEquals(List.of(V2OwnerMailbox.Reason.WRONG_OWNER), h.notifications);
      assertTrue(h.terminals.isEmpty()); assertTrue(h.queue.isEmpty());
    }
  }

  @Test void oldDuplicateCannotConsumeSuccessorWorkOrClearItsToken() {
    Harness h = new Harness(); h.mailbox.signalTimer(); Runnable old = h.queue.peek(); h.turn();
    AtomicInteger actions = new AtomicInteger(); h.mailbox.postSendCompletion(actions::incrementAndGet);
    old.run(); assertFalse(h.mailbox.isValid()); assertEquals(1, h.queue.size());
    h.turn(); assertEquals(0, actions.get());
    assertEquals(List.of(V2OwnerMailbox.Reason.DUPLICATE_PUMP), h.terminals);
  }

  @Test void actionRuntimeStopsOrdinaryAndErrorPrioritySurvivesTerminalAndNotificationErrors() {
    Harness runtime = new Harness(); runtime.mailbox.signalTimer();
    runtime.mailbox.postSendCompletion(() -> { throw new IllegalStateException("action"); });
    runtime.turn(); assertEquals(0, runtime.timers); assertEquals(1, runtime.terminals.size());
    Harness fatal = new Harness(); AssertionError original = new AssertionError("action");
    fatal.notificationAction = () -> { throw new LinkageError("notification"); };
    fatal.terminalAction = () -> { throw new LinkageError("terminal"); };
    fatal.mailbox.postSendCompletion(() -> { throw original; });
    assertSame(original, assertThrows(AssertionError.class, fatal::turn));
    assertTrue(fatal.queue.isEmpty()); assertEquals(1, fatal.terminals.size());
    Harness terminal = new Harness(); LinkageError terminalError = new LinkageError("terminal");
    terminal.terminalAction = () -> { throw terminalError; };
    terminal.mailbox.requestAbort(V2OwnerMailbox.Reason.EXTERNAL_ABORT);
    assertSame(terminalError, assertThrows(LinkageError.class, terminal::turn));
    assertTrue(terminal.queue.isEmpty()); assertEquals(1, terminal.enqueues);
  }

  @Test void stoppedPublicationAfterGateBeforeClaimBlocksAllActions() throws Exception {
    Harness h = new Harness(); CountDownLatch gateEntered = new CountDownLatch(1);
    CountDownLatch releaseGate = new CountDownLatch(1);
    h.gate = () -> { gateEntered.countDown(); await(releaseGate); return true; };
    h.mailbox.signalTimer(); Runnable task = h.queue.remove(); Thread owner = new Thread(task);
    owner.start();
    try {
      await(gateEntered); h.mailbox.publishStopped();
      assertFalse(h.mailbox.postSendCompletion(() -> fail()));
    } finally { releaseGate.countDown(); join(owner); }
    assertEquals(0, h.timers); assertTrue(h.terminals.isEmpty());
  }

  @Test void claimedActionMayFinishAfterStopButNoFollowingActionIsClaimed() throws Exception {
    Harness h = new Harness(); CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1); AtomicInteger completed = new AtomicInteger();
    h.mailbox.postSendCompletion(() -> { entered.countDown(); await(release); completed.incrementAndGet(); });
    h.mailbox.signalTimer(); Thread owner = new Thread(h.queue.remove()); owner.start();
    try { await(entered); h.mailbox.publishStopped(); }
    finally { release.countDown(); join(owner); }
    assertEquals(1, completed.get()); assertEquals(0, h.timers); assertTrue(h.terminals.isEmpty());
  }

  @Test void concurrentSameIdentityProducersShareOneAcceptedCell() throws Exception {
    Harness h = new Harness(); AtomicInteger actions = new AtomicInteger(); Runnable action = actions::incrementAndGet;
    CountDownLatch start = new CountDownLatch(1); List<Thread> producers = new ArrayList<>();
    AtomicInteger accepted = new AtomicInteger();
    for (int i = 0; i < 8; i++) {
      Thread t = new Thread(() -> { await(start); if (h.mailbox.postSendCompletion(action)) accepted.incrementAndGet(); });
      producers.add(t); t.start();
    }
    try { start.countDown(); } finally { for (Thread t : producers) join(t); }
    assertEquals(8, accepted.get()); assertEquals(1, h.enqueues); h.turn(); assertEquals(1, actions.get());
  }

  @Test void invalidatedHandlerIsLockFreeAndNullInputsFailClosedNonthrowing() throws Exception {
    Harness h = new Harness(); h.notificationAction = () -> {
      Thread t = new Thread(() -> assertFalse(h.mailbox.isValid())); t.start(); join(t);
      h.mailbox.publishStopped(); throw new AssertionError("notification");
    };
    assertFalse(h.mailbox.postSendCompletion(null)); assertEquals(1, h.notifications.size());
    Harness abort = new Harness(); assertTrue(abort.mailbox.requestAbort(null)); abort.turn();
    assertEquals(List.of(V2OwnerMailbox.Reason.INVALID_INPUT), abort.terminals);
  }

  @Test void realSessionImmediateNextCompletionWhileFirstRunningSaturatesRatherThanAddsCell() {
    byte[] psk = new byte[32]; byte[] hash = new byte[32];
    var transcript = new ControlAuth.Transcript(ControlAuth.Role.CLIENT_CONTROLLER, "client", "hub", "key",
        "LUO_CONTROL_POSTAUTH_V2", hash, hash, hash, "10000000-0000-4000-8000-000000000001",
        Instant.parse("2026-10-23T00:00:00Z"), Instant.parse("2026-10-23T00:00:05Z"), hash, hash);
    Harness h = new Harness(); CompletableFuture<Void> first = new CompletableFuture<>();
    AtomicInteger sends = new AtomicInteger();
    var keys = PostAuthSessionKeys.derive(psk, transcript);
    var session = new PostAuthPeerSession(transcript, keys, 1, () -> true, () -> 0,
        h.mailbox::postSendCompletion, r -> h.mailbox.requestAbort(V2OwnerMailbox.Reason.EXTERNAL_ABORT),
        reason -> {}, raw -> sends.incrementAndGet() == 1 ? first : CompletableFuture.completedFuture(null),
        frame -> fail(), new PostAuthPeerSession.Lifecycle() {
          public void fence(String reason) {}
          public void physicalClose(String reason) {}
        });
    String connection = "20000000-0000-4000-8000-000000000001";
    try (var hubKeys = PostAuthSessionKeys.derive(psk, transcript)) {
      var open = new PostAuthFrameCodec.Frame(connection, transcript.challengeId(), 0, "SESSION_OPEN",
          "", "", "", "", "", PostAuthFrameCodec.sessionOpenPayload(connection).getBytes(StandardCharsets.US_ASCII), "");
      session.receive(PostAuthFrameCodec.encode(open, new PostAuthFrameCodec.Context(transcript, null),
          PostAuthFrameCodec.Direction.HUB_TO_PEER, hubKeys));
    }
    session.sendBusiness("HEARTBEAT", "{}".getBytes(StandardCharsets.US_ASCII), "", "", "", "", "");
    first.complete(null); h.turn();
    assertEquals(2, sends.get()); assertFalse(h.mailbox.isValid());
    assertEquals(List.of(V2OwnerMailbox.Reason.SEND_CELL_FULL), h.notifications);
    assertEquals(1, h.terminals.size()); session.close();
  }

  @Test void rearmInlineFailsWithoutRecursiveTerminalScheduling() {
    Harness h = new Harness();
    h.timerAction = () -> {
      h.mailbox.signalTimer();
      h.port = task -> { task.run(); return V2OwnerMailbox.EnqueueResult.ACCEPTED; };
    };
    h.mailbox.signalTimer(); h.turn();
    assertEquals(2, h.enqueues); assertEquals(1, h.timers);
    assertEquals(List.of(V2OwnerMailbox.Reason.EARLY_INLINE), h.notifications);
    assertTrue(h.terminals.isEmpty());
  }

  @Test void producerDuringTurnExitGetsSuccessorWithoutLostWakeAndStopDuringRearmRejects() throws Exception {
    Harness h = new Harness(); CountDownLatch enqueueEntered = new CountDownLatch(1);
    CountDownLatch releaseEnqueue = new CountDownLatch(1);
    h.timerAction = h.mailbox::signalTimer;
    h.mailbox.signalTimer(); Runnable initial = h.queue.remove();
    h.port = task -> { enqueueEntered.countDown(); await(releaseEnqueue); return V2OwnerMailbox.EnqueueResult.STOPPED; };
    Thread owner = new Thread(initial); owner.start();
    try {
      await(enqueueEntered);
      assertTrue(h.mailbox.postSendCompletion(() -> fail("stopped successor")));
      h.mailbox.publishStopped();
    } finally { releaseEnqueue.countDown(); join(owner); }
    assertEquals(1, h.timers); assertFalse(h.mailbox.isValid()); assertTrue(h.terminals.isEmpty());
    assertEquals(2, h.enqueues);
  }

  @Test void producerDuringRunningActionIsNotLostAtExitAndExternalPortsAreLockFree() throws Exception {
    Harness h = new Harness(); CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    h.mailbox.postSendCompletion(() -> { entered.countDown(); await(release); });
    Thread owner = new Thread(h.queue.remove()); owner.start();
    try { await(entered); h.mailbox.signalTimer(); }
    finally { release.countDown(); join(owner); }
    assertEquals(1, h.timers); assertTrue(h.queue.isEmpty());
    h.port = task -> {
      Thread observer = new Thread(() -> assertTrue(h.mailbox.isValid())); observer.start(); join(observer);
      h.queue.add(task); return V2OwnerMailbox.EnqueueResult.ACCEPTED;
    };
    h.gate = () -> {
      Thread observer = new Thread(h.mailbox::isValid); observer.start(); join(observer); return true;
    };
    h.mailbox.signalTimer(); h.turn(); assertEquals(2, h.timers);
  }

  @Test void stopDuringInitialEnqueueReturnCannotRestoreValidityOrExecuteAcceptedTask() throws Exception {
    Harness h = new Harness(); CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1); AtomicReference<Runnable> pending = new AtomicReference<>();
    h.port = task -> { pending.set(task); entered.countDown(); await(release); return V2OwnerMailbox.EnqueueResult.ACCEPTED; };
    Thread producer = new Thread(h.mailbox::signalTimer); producer.start();
    try { await(entered); h.mailbox.publishStopped(); }
    finally { release.countDown(); join(producer); }
    pending.get().run(); assertEquals(0, h.timers); assertTrue(h.terminals.isEmpty());
    assertFalse(h.mailbox.isValid()); assertEquals(1, h.notifications.size());
  }

  @Test void ordinaryEnqueueFailuresContainErrorsAndNeverRunTerminalOnUnvalidatedThread() {
    for (int mode = 0; mode < 4; mode++) {
      Harness h = new Harness(); int selected = mode;
      h.port = task -> {
        if (selected == 0) return V2OwnerMailbox.EnqueueResult.STOPPED;
        if (selected == 1) return null;
        if (selected == 2) throw new IllegalStateException("enqueue");
        throw new AssertionError("enqueue");
      };
      assertDoesNotThrow(h.mailbox::signalTimer);
      assertFalse(h.mailbox.isValid()); assertEquals(1, h.notifications.size());
      assertTrue(h.terminals.isEmpty()); assertEquals(0, h.gates); assertEquals(1, h.enqueues);
    }
  }

  @Test void gateFailureBetweenActionsDoesNotRearmTerminalOrGrantCleanupPermission() {
    Harness h = new Harness();
    h.mailbox.postSendCompletion(() -> h.owner = false);
    h.mailbox.signalTimer(); h.turn();
    assertFalse(h.mailbox.isValid()); assertEquals(0, h.timers); assertTrue(h.terminals.isEmpty());
    assertEquals(1, h.enqueues); assertTrue(h.queue.isEmpty());
  }

  @Test void thirdOrdinaryActionIsDeferredAndItsErrorAttemptsTerminalBeforeRethrow() {
    Harness h = new Harness(); List<String> trace = new ArrayList<>();
    AssertionError original = new AssertionError("third ordinary");
    h.mailbox.postSendCompletion(() -> trace.add("first-send"));
    h.timerAction = () -> {
      trace.add("timer");
      assertTrue(h.mailbox.postSendCompletion(() -> { trace.add("throwing-send"); throw original; }));
    };
    h.terminalAction = () -> { trace.add("terminal"); throw new LinkageError("secondary"); };
    h.mailbox.signalTimer(); h.turn();
    assertEquals(List.of("first-send", "timer"), trace);
    assertEquals(1, h.queue.size()); assertTrue(h.mailbox.isValid());
    assertSame(original, assertThrows(AssertionError.class, h::turn));
    trace.add("caught");
    assertEquals(List.of("first-send", "timer", "throwing-send", "terminal", "caught"), trace);
    assertTrue(h.queue.isEmpty()); assertEquals(1, h.terminals.size());
    assertFalse(h.mailbox.requestAbort(V2OwnerMailbox.Reason.EXTERNAL_ABORT));
    assertEquals(2, h.enqueues);
  }

  @Test void secondOrdinaryErrorUsesReservedTerminalSlotBeforeSameErrorEscapes() {
    Harness h = new Harness(); List<String> trace = new ArrayList<>();
    AssertionError original = new AssertionError("second ordinary");
    h.mailbox.postSendCompletion(() -> trace.add("send"));
    h.timerAction = () -> { trace.add("timer-error"); throw original; };
    h.terminalAction = () -> trace.add("terminal");
    h.mailbox.signalTimer();
    assertSame(original, assertThrows(AssertionError.class, h::turn));
    trace.add("caught");
    assertEquals(List.of("send", "timer-error", "terminal", "caught"), trace);
    assertTrue(h.queue.isEmpty()); assertEquals(1, h.enqueues);
  }

  @Test void errorDoesNotForceTerminalAcrossStopOrFailedGate() {
    for (boolean stop : new boolean[] {false, true}) {
      Harness h = new Harness(); AssertionError original = new AssertionError("owner error");
      h.mailbox.postSendCompletion(() -> {});
      h.timerAction = () -> {
        if (stop) h.mailbox.publishStopped(); else h.owner = false;
        throw original;
      };
      h.mailbox.signalTimer();
      assertSame(original, assertThrows(AssertionError.class, h::turn));
      assertFalse(h.mailbox.isValid()); assertTrue(h.terminals.isEmpty());
      assertTrue(h.queue.isEmpty()); assertEquals(1, h.enqueues);
    }
  }

  @Test void infrastructureFaultCanUseOneLaterExplicitTerminalHandoffPreservingFirstReason() {
    for (int mode = 0; mode < 4; mode++) {
      Harness h = new Harness(); int selected = mode;
      h.port = task -> {
        if (selected == 0) throw new IllegalStateException("enqueue");
        if (selected == 1) return null;
        if (selected == 2) { task.run(); return V2OwnerMailbox.EnqueueResult.ACCEPTED; }
        h.queue.add(task); return V2OwnerMailbox.EnqueueResult.ACCEPTED;
      };
      if (mode == 3) h.owner = false;
      h.mailbox.signalTimer();
      if (mode == 3) h.turn();
      V2OwnerMailbox.Reason originalReason = h.notifications.get(0);
      assertFalse(h.mailbox.isValid()); assertEquals(1, h.enqueues);
      assertTrue(h.terminals.isEmpty());
      h.owner = true;
      h.port = task -> { h.queue.add(task); return V2OwnerMailbox.EnqueueResult.ACCEPTED; };
      // False means no new first reason, not that a terminal handoff was not scheduled.
      assertFalse(h.mailbox.requestAbort(V2OwnerMailbox.Reason.EXTERNAL_ABORT));
      assertEquals(2, h.enqueues); assertEquals(1, h.queue.size());
      assertFalse(h.mailbox.requestAbort(V2OwnerMailbox.Reason.INVALID_INPUT));
      h.turn();
      assertEquals(List.of(originalReason), h.notifications);
      assertEquals(List.of(originalReason), h.terminals);
      assertFalse(h.mailbox.requestAbort(V2OwnerMailbox.Reason.EXTERNAL_ABORT));
      assertEquals(2, h.enqueues); assertTrue(h.queue.isEmpty());
    }
  }

  @Test void recoveryTerminalEnqueueFailureDoesNotRetryAndStoppedNeverSchedulesRecovery() {
    Harness h = new Harness(); h.port = task -> null;
    h.mailbox.signalTimer();
    assertFalse(h.mailbox.requestAbort(V2OwnerMailbox.Reason.EXTERNAL_ABORT));
    assertEquals(2, h.enqueues);
    h.port = task -> { h.queue.add(task); return V2OwnerMailbox.EnqueueResult.ACCEPTED; };
    assertFalse(h.mailbox.requestAbort(V2OwnerMailbox.Reason.EXTERNAL_ABORT));
    assertEquals(2, h.enqueues); assertTrue(h.terminals.isEmpty());
    Harness stopped = new Harness(); stopped.port = task -> V2OwnerMailbox.EnqueueResult.STOPPED;
    stopped.mailbox.signalTimer();
    assertFalse(stopped.mailbox.requestAbort(V2OwnerMailbox.Reason.EXTERNAL_ABORT));
    assertEquals(1, stopped.enqueues); assertTrue(stopped.terminals.isEmpty());
  }

  @Test void concurrentExplicitRecoveryRequestsReserveOnlyOneIdentityAndStalePumpCannotClearIt() throws Exception {
    Harness h = new Harness(); h.mailbox.signalTimer(); Runnable stale = h.queue.remove();
    h.owner = false; stale.run(); h.owner = true;
    CountDownLatch start = new CountDownLatch(1); List<Thread> producers = new ArrayList<>();
    for (int i = 0; i < 8; i++) {
      Thread t = new Thread(() -> { await(start); assertFalse(h.mailbox.requestAbort(V2OwnerMailbox.Reason.EXTERNAL_ABORT)); });
      producers.add(t); t.start();
    }
    try { start.countDown(); } finally { for (Thread t : producers) join(t); }
    assertEquals(2, h.enqueues); assertEquals(1, h.queue.size());
    stale.run(); h.turn();
    assertEquals(List.of(V2OwnerMailbox.Reason.WRONG_OWNER), h.notifications);
    assertEquals(List.of(V2OwnerMailbox.Reason.WRONG_OWNER), h.terminals);
    assertTrue(h.queue.isEmpty());
  }

  @Test void allThirteenCellsShareOnePumpWithTwoNonterminalClaimsPerTurn() {
    Harness h = new Harness();
    assertTrue(h.mailbox.postOrdinary(V2OwnerMailbox.OrdinaryKind.TEXT, 0, 1));
    assertTrue(h.mailbox.postOrdinary(V2OwnerMailbox.OrdinaryKind.TEXT, 1, 2));
    for (int i = 0; i < 6; i++) {
      assertTrue(h.mailbox.postOrdinary(V2OwnerMailbox.OrdinaryKind.CLOSED, -1, 0));
    }
    assertTrue(h.mailbox.postBuild());
    assertTrue(h.mailbox.postControl(true, 125));
    assertTrue(h.mailbox.postSendCompletion(() -> h.cells.add("send")));
    h.timerAction = () -> h.cells.add("timer");
    h.mailbox.signalTimer();
    assertEquals(1, h.enqueues);
    for (int turn = 1; turn <= 6; turn++) {
      h.turn(); assertEquals(turn * 2, h.cells.size());
      assertEquals(turn == 6 ? 0 : 1, h.queue.size());
    }
    assertEquals(List.of("TEXT:0:1", "TEXT:1:2", "CLOSED:-1:0", "CLOSED:-1:0",
        "CLOSED:-1:0", "CLOSED:-1:0", "CLOSED:-1:0", "CLOSED:-1:0",
        "build", "control:true:125", "send", "timer"), h.cells);
    assertTrue(h.mailbox.requestAbort(V2OwnerMailbox.Reason.EXTERNAL_ABORT));
    h.turn(); assertEquals(1, h.terminals.size());
  }

  @Test void ninthOrdinaryInvalidatesWithoutOverwritingAnyOccupiedCell() {
    Harness h = new Harness();
    for (int i = 0; i < 8; i++) {
      assertTrue(h.mailbox.postOrdinary(V2OwnerMailbox.OrdinaryKind.FAILED, -1, 0));
    }
    assertFalse(h.mailbox.postOrdinary(V2OwnerMailbox.OrdinaryKind.CLOSED, -1, 0));
    h.turn();
    assertTrue(h.cells.isEmpty());
    assertEquals(List.of(V2OwnerMailbox.Reason.ORDINARY_CELL_FULL), h.terminals);
  }

  @Test void progressAndBuildWakeCoalesceWhileQueuedAndRunning() {
    Harness h = new Harness();
    h.ordinaryAction = () -> {
      assertTrue(h.mailbox.postOrdinary(V2OwnerMailbox.OrdinaryKind.PROGRESS, -1, 0));
      assertTrue(h.mailbox.postOrdinary(V2OwnerMailbox.OrdinaryKind.PROGRESS, -1, 0));
    };
    h.buildAction = () -> { assertTrue(h.mailbox.postBuild()); assertTrue(h.mailbox.postBuild()); };
    for (int i = 0; i < 100; i++) {
      assertTrue(h.mailbox.postOrdinary(V2OwnerMailbox.OrdinaryKind.PROGRESS, -1, 0));
      assertTrue(h.mailbox.postBuild());
    }
    h.turn();
    assertEquals(List.of("PROGRESS:-1:0", "build"), h.cells);
    assertEquals(1, h.queue.size()); assertTrue(h.mailbox.isValid());
    h.ordinaryAction = () -> {}; h.buildAction = () -> {};
    h.turn(); assertEquals(4, h.cells.size()); assertTrue(h.queue.isEmpty());
  }

  @Test void runningOrdinaryCellRemainsOccupiedUntilHandlerReturns() {
    Harness h = new Harness();
    for (int i = 0; i < 8; i++) {
      assertTrue(h.mailbox.postOrdinary(V2OwnerMailbox.OrdinaryKind.CLOSED, -1, 0));
    }
    h.ordinaryAction = () -> assertFalse(
        h.mailbox.postOrdinary(V2OwnerMailbox.OrdinaryKind.FAILED, -1, 0));
    h.turn();
    assertEquals(List.of("CLOSED:-1:0"), h.cells);
    assertEquals(List.of(V2OwnerMailbox.Reason.ORDINARY_CELL_FULL), h.terminals);
  }

  @Test void laneReferencesCannotBeOverwrittenEvenWhileProcessing() {
    for (boolean running : new boolean[] {false, true}) {
      Harness h = new Harness();
      assertTrue(h.mailbox.postOrdinary(V2OwnerMailbox.OrdinaryKind.TEXT, 0, 7));
      Runnable conflict = () -> assertFalse(
          h.mailbox.postOrdinary(V2OwnerMailbox.OrdinaryKind.TEXT, 0, 8));
      if (running) h.ordinaryAction = conflict; else conflict.run();
      h.turn();
      assertEquals(running ? List.of("TEXT:0:7") : List.of(), h.cells);
      assertEquals(List.of(V2OwnerMailbox.Reason.ORDINARY_CELL_FULL), h.terminals);
    }
  }

  @Test void controlIdentityCoalescesButConflictsInvalidateQueuedAndRunningCells() {
    Harness same = new Harness();
    same.controlAction = () -> assertTrue(same.mailbox.postControl(false, 0));
    assertTrue(same.mailbox.postControl(false, 0));
    assertTrue(same.mailbox.postControl(false, 0));
    same.turn(); assertEquals(List.of("control:false:0"), same.cells);
    assertTrue(same.queue.isEmpty()); assertTrue(same.mailbox.isValid());
    for (boolean running : new boolean[] {false, true}) {
      for (boolean changePing : new boolean[] {false, true}) {
        Harness h = new Harness();
        assertTrue(h.mailbox.postControl(true, 3));
        Runnable conflict = () -> assertFalse(
            h.mailbox.postControl(!changePing, changePing ? 3 : 4));
        if (running) h.controlAction = conflict; else conflict.run();
        h.turn();
        assertEquals(running ? List.of("control:true:3") : List.of(), h.cells);
        assertEquals(List.of(V2OwnerMailbox.Reason.CONTROL_CELL_FULL), h.terminals);
      }
    }
  }

  @Test void invalidTypedMetadataFailsClosedAndStoppedRejectsEveryTypedProducer() {
    for (int mode = 0; mode < 8; mode++) {
      Harness h = new Harness();
      boolean accepted = switch (mode) {
        case 0 -> h.mailbox.postOrdinary(null, -1, 0);
        case 1 -> h.mailbox.postOrdinary(V2OwnerMailbox.OrdinaryKind.TEXT, -1, 1);
        case 2 -> h.mailbox.postOrdinary(V2OwnerMailbox.OrdinaryKind.TEXT, 2, 1);
        case 3 -> h.mailbox.postOrdinary(V2OwnerMailbox.OrdinaryKind.TEXT, 0, 0);
        case 4 -> h.mailbox.postOrdinary(V2OwnerMailbox.OrdinaryKind.PROGRESS, 0, 0);
        case 5 -> h.mailbox.postOrdinary(V2OwnerMailbox.OrdinaryKind.CLOSED, -1, 1);
        case 6 -> h.mailbox.postControl(true, -1);
        default -> h.mailbox.postControl(true, 126);
      };
      assertFalse(accepted); assertFalse(h.mailbox.isValid());
      assertEquals(List.of(V2OwnerMailbox.Reason.INVALID_INPUT), h.notifications);
    }
    Harness stopped = new Harness(); stopped.mailbox.publishStopped();
    assertFalse(stopped.mailbox.postOrdinary(V2OwnerMailbox.OrdinaryKind.FAILED, -1, 0));
    assertFalse(stopped.mailbox.postBuild()); assertFalse(stopped.mailbox.postControl(true, 0));
    assertEquals(0, stopped.enqueues);
  }

  @Test void refilledLowOrdinaryCellsCannotStarveBuildControlSendOrTimer() {
    Harness h = new Harness();
    h.ordinaryAction = () -> assertTrue(
        h.mailbox.postOrdinary(V2OwnerMailbox.OrdinaryKind.CLOSED, -1, 0));
    assertTrue(h.mailbox.postOrdinary(V2OwnerMailbox.OrdinaryKind.CLOSED, -1, 0));
    assertTrue(h.mailbox.postBuild()); assertTrue(h.mailbox.postControl(true, 1));
    assertTrue(h.mailbox.postSendCompletion(() -> h.cells.add("send")));
    h.timerAction = () -> h.cells.add("timer"); h.mailbox.signalTimer();
    h.turn(); h.turn(); h.turn();
    assertEquals(List.of("CLOSED:-1:0", "CLOSED:-1:0", "build", "control:true:1",
        "send", "timer"), h.cells);
    assertEquals(1, h.queue.size());
    h.mailbox.publishStopped(); h.turn();
  }

  @Test void freedAndRefilledSameCellWaitsUntilNextTurn() {
    Harness h = new Harness();
    assertTrue(h.mailbox.postOrdinary(V2OwnerMailbox.OrdinaryKind.CLOSED, -1, 0));
    h.buildAction = () -> assertTrue(
        h.mailbox.postOrdinary(V2OwnerMailbox.OrdinaryKind.FAILED, -1, 0));
    assertTrue(h.mailbox.postBuild()); h.turn();
    assertEquals(List.of("CLOSED:-1:0", "build"), h.cells);
    assertEquals(1, h.queue.size()); h.turn();
    assertEquals(List.of("CLOSED:-1:0", "build", "FAILED:-1:0"), h.cells);
    assertTrue(h.queue.isEmpty());
  }

  @Test void secondTypedActionErrorUsesReservedTerminalClaimBeforeOriginalErrorEscapes() {
    Harness h = new Harness(); AssertionError original = new AssertionError("control");
    assertTrue(h.mailbox.postBuild()); assertTrue(h.mailbox.postControl(true, 0));
    h.controlAction = () -> { throw original; };
    h.terminalAction = () -> { throw new LinkageError("terminal"); };
    assertSame(original, assertThrows(AssertionError.class, h::turn));
    assertEquals(List.of("build", "control:true:0"), h.cells);
    assertEquals(List.of(V2OwnerMailbox.Reason.ACTION_FAILED), h.terminals);
    assertEquals(1, h.enqueues); assertTrue(h.queue.isEmpty());
  }

  @Test void terminalTakesPriorityOverEveryRemainingTypedCellAfterEachClaim() {
    Harness h = new Harness();
    h.ordinaryAction = () -> assertTrue(
        h.mailbox.requestAbort(V2OwnerMailbox.Reason.EXTERNAL_ABORT));
    assertTrue(h.mailbox.postOrdinary(V2OwnerMailbox.OrdinaryKind.PROGRESS, -1, 0));
    assertTrue(h.mailbox.postOrdinary(V2OwnerMailbox.OrdinaryKind.TEXT, 1, 4));
    assertTrue(h.mailbox.postBuild()); assertTrue(h.mailbox.postControl(false, 10));
    assertTrue(h.mailbox.postSendCompletion(() -> fail("terminal priority")));
    h.mailbox.signalTimer(); h.turn();
    assertEquals(List.of("PROGRESS:-1:0"), h.cells); assertEquals(0, h.timers);
    assertEquals(List.of(V2OwnerMailbox.Reason.EXTERNAL_ABORT), h.terminals);
    assertEquals(1, h.enqueues); assertTrue(h.queue.isEmpty());
  }

  private static void await(CountDownLatch latch) {
    try { assertTrue(latch.await(5, TimeUnit.SECONDS), "bounded latch timeout"); }
    catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
  }
  private static void join(Thread thread) {
    try { thread.join(5000); assertFalse(thread.isAlive(), "bounded join timeout"); }
    catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
  }
}
