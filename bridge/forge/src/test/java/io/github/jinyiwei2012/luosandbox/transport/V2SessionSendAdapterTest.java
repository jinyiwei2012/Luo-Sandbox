package io.github.jinyiwei2012.luosandbox.transport;

import static org.junit.jupiter.api.Assertions.*;

import io.github.jinyiwei2012.luosandbox.security.ControlAuth;
import io.github.jinyiwei2012.luosandbox.security.PostAuthFrameCodec;
import io.github.jinyiwei2012.luosandbox.security.PostAuthSessionKeys;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;

class V2SessionSendAdapterTest {
  private static final class Harness implements V2OwnerMailbox.Handler, V2SessionSendAdapter.Containment {
    final ArrayDeque<Runnable> queue = new ArrayDeque<>();
    final List<byte[]> physicalFrames = new ArrayList<>();
    final List<V2OwnerMailbox.Reason> terminals = new ArrayList<>();
    final List<CompletableFuture<Void>> physicalFutures = new ArrayList<>();
    int invalidations;
    int failures;
    int gateCalls;
    int envelopeCalls;
    int flushes;
    boolean owner = true;
    BooleanSupplier gate = () -> owner;
    Runnable notifyAction = () -> {};
    Runnable terminalAction = () -> {};
    V2OwnerMailbox.OwnerEnqueue enqueue = r -> { queue.add(r); return V2OwnerMailbox.EnqueueResult.ACCEPTED; };
    V2SessionSendAdapter.PhysicalSender physical = frame -> {
      physicalFrames.add(frame);
      var future = new CompletableFuture<Void>(); physicalFutures.add(future); return future;
    };
    V2SessionSendAdapter.OwnerEnvelope envelope = action -> {
      envelopeCalls++;
      try { action.run(); } finally { flushes++; }
    };
    final V2OwnerMailbox mailbox = new V2OwnerMailbox(() -> owner, r -> enqueue.enqueue(r), this);
    final V2SessionSendAdapter adapter = new V2SessionSendAdapter(
        () -> { gateCalls++; return gate.getAsBoolean(); }, mailbox, frame -> physical.send(frame),
        action -> envelope.run(action), this);
    @Override public void timerTurn() {}
    @Override public void terminal(V2OwnerMailbox.Reason reason) { terminals.add(reason); terminalAction.run(); }
    @Override public void invalidated(V2OwnerMailbox.Reason reason) {}
    @Override public void invalidate() { invalidations++; notifyAction.run(); }
    @Override public void sendFailure() { failures++; notifyAction.run(); }
    void turn() { queue.removeFirst().run(); }
  }

  /** Deliberately hostile completion registration/duplicate callback source, not a session replica. */
  private static final class ControlledFuture extends CompletableFuture<Void> {
    BiConsumer<? super Void, ? super Throwable> callback;
    Runnable duringRegistration = () -> {};
    Throwable registrationFailure;
    @Override public CompletableFuture<Void> whenComplete(BiConsumer<? super Void, ? super Throwable> action) {
      callback = action;
      duringRegistration.run();
      if (registrationFailure instanceof Error error) throw error;
      if (registrationFailure instanceof RuntimeException error) throw error;
      return this;
    }
    void completeCallback(Throwable failure) { callback.accept(null, failure); }
  }

  @Test void constructorAndStableAccessorsAreInertAndValidateReferences() {
    Harness h = new Harness();
    assertSame(h.adapter.sender(), h.adapter.sender());
    assertSame(h.adapter.ownerPost(), h.adapter.ownerPost());
    assertSame(h.adapter.ownerAbort(), h.adapter.ownerAbort());
    assertEquals(0, h.gateCalls); assertEquals(0, h.physicalFrames.size()); assertTrue(h.queue.isEmpty());
    for (int selected = 0; selected < 5; selected++) {
      int i = selected;
      assertThrows(NullPointerException.class, () -> new V2SessionSendAdapter(i == 0 ? null : () -> true,
          i == 1 ? null : h.mailbox, i == 2 ? null : h.physical,
          i == 3 ? null : h.envelope, i == 4 ? null : h));
    }
  }

  @Test void wrongOwnerThrowingGateAndInvalidInputsNeverCallPhysical() {
    for (int mode = 0; mode < 3; mode++) {
      Harness h = new Harness(); int selected = mode;
      h.gate = () -> {
        if (selected == 1) throw new IllegalStateException("secret");
        if (selected == 2) throw new AssertionError("secret");
        return false;
      };
      assertSanitized(h.adapter.sender().send(new byte[4096]));
      assertEquals(0, h.physicalFrames.size()); assertEquals(1, h.invalidations); assertNull(active(h));
    }
    for (byte[] input : new byte[][] {null, new byte[0], new byte[4097]}) {
      Harness h = new Harness(); assertSanitized(h.adapter.sender().send(input));
      assertEquals(0, h.physicalFrames.size()); assertEquals(1, h.invalidations); assertNull(active(h));
    }
  }

  @Test void frameClonedOnceAndReleasedOnlyOnObservedPhysicalSettlement() {
    Harness h = new Harness(); byte[] source = new byte[4096]; source[0] = 7;
    CompletionStage<Void> result = h.adapter.sender().send(source); Object operation = active(h);
    assertNotSame(source, h.physicalFrames.get(0));
    assertSame(value(operation, "frame"), h.physicalFrames.get(0));
    source[0] = 9; assertEquals(7, h.physicalFrames.get(0)[0]);
    assertFalse(result.toCompletableFuture().isDone());
    h.physicalFutures.get(0).complete(null);
    assertNull(value(operation, "frame")); assertNull(active(h));
    assertDoesNotThrow(() -> result.toCompletableFuture().join());
  }

  @Test void unsettledPhysicalSendQueryTracksAdmissionThroughObservedSettlement() {
    Harness h = new Harness();
    assertFalse(h.adapter.hasUnsettledPhysicalSend());
    ControlledFuture future = new ControlledFuture();
    h.physical = frame -> {
      assertTrue(h.adapter.hasUnsettledPhysicalSend());
      return future;
    };
    future.duringRegistration = () -> assertTrue(h.adapter.hasUnsettledPhysicalSend());
    h.adapter.sender().send(new byte[] {1});
    assertTrue(h.adapter.hasUnsettledPhysicalSend());
    future.completeCallback(null);
    assertFalse(h.adapter.hasUnsettledPhysicalSend());
  }

  @Test void unsettledPhysicalSendQueryIgnoresLogicalCancellationAndClearsOnPhysicalFailure() {
    Harness h = new Harness();
    var result = h.adapter.sender().send(new byte[] {1});
    result.toCompletableFuture().cancel(false);
    assertTrue(h.adapter.hasUnsettledPhysicalSend());
    h.physicalFutures.get(0).completeExceptionally(new AssertionError("physical"));
    assertFalse(h.adapter.hasUnsettledPhysicalSend());
    assertEquals(1, h.failures);
  }

  @Test void settledPhysicalSendDoesNotImplyOwnerWorkOrMailboxQuiescence() {
    Harness h = new Harness(); AtomicInteger actions = new AtomicInteger();
    h.adapter.sender().send(new byte[] {1}).whenComplete(
        (ignored, failure) -> h.adapter.ownerPost().post(actions::incrementAndGet));
    h.physicalFutures.get(0).complete(null);
    assertFalse(h.adapter.hasUnsettledPhysicalSend());
    assertEquals(0, actions.get()); assertEquals(1, h.queue.size());
    h.turn(); assertEquals(1, actions.get());
  }

  @Test void unsettledPhysicalSendQueryRemainsConservativeAfterUnknownPhysicalOutcomes() {
    for (int mode = 0; mode < 3; mode++) {
      Harness h = new Harness(); int selected = mode; ControlledFuture future = new ControlledFuture();
      future.registrationFailure = new AssertionError("registration");
      h.physical = frame -> {
        if (selected == 0) throw new AssertionError("physical");
        if (selected == 1) return null;
        return future;
      };
      assertSanitized(h.adapter.sender().send(new byte[] {1}));
      assertTrue(h.adapter.hasUnsettledPhysicalSend());
      assertEquals(1, h.failures); assertEquals(0, h.invalidations);
      if (selected == 2) {
        future.completeCallback(null);
        assertFalse(h.adapter.hasUnsettledPhysicalSend());
      }
    }
  }

  @Test void unsettledPhysicalSendQueryRejectsForeignOrFailingOwnerGateWithoutPhysicalSend() {
    for (int mode = 0; mode < 3; mode++) {
      Harness h = new Harness(); int selected = mode;
      h.gate = () -> {
        if (selected == 1) throw new IllegalStateException("gate");
        if (selected == 2) throw new AssertionError("gate");
        return false;
      };
      assertTrue(h.adapter.hasUnsettledPhysicalSend());
      assertTrue(h.adapter.hasUnsettledPhysicalSend());
      assertEquals(1, h.invalidations);
      assertEquals(0, h.physicalFrames.size());
      assertEquals(0, h.failures); assertNull(active(h));
      assertTrue(h.queue.isEmpty()); assertEquals(0, h.envelopeCalls);
      h.gate = () -> true;
      assertFalse(h.adapter.hasUnsettledPhysicalSend());
      assertSanitized(h.adapter.sender().send(new byte[] {1}));
      assertEquals(0, h.physicalFrames.size());
      assertTrue(h.adapter.ownerAbort().request(() -> fail("supplied abort ignored")));
      h.turn(); assertEquals(List.of(V2OwnerMailbox.Reason.EXTERNAL_ABORT), h.terminals);
    }
  }

  @Test void overlapUntilObservedSettlementPermanentlyInvalidatesWithoutSecondFlight() {
    Harness h = new Harness(); CompletionStage<Void> first = h.adapter.sender().send(new byte[] {1});
    assertSanitized(h.adapter.sender().send(new byte[] {2}));
    assertEquals(1, h.physicalFrames.size()); assertEquals(1, h.invalidations);
    h.physicalFutures.get(0).complete(null); assertSanitized(first); assertNull(active(h));
    assertSanitized(h.adapter.sender().send(new byte[] {3})); assertEquals(1, h.physicalFrames.size());
    assertEquals(0, h.failures); assertEquals(1, h.invalidations);
  }

  @Test void immediateAndAsyncPhysicalSuccessOnlyPostOwnerWorkWithoutExecutingIt() {
    for (boolean immediate : new boolean[] {true, false}) {
      Harness h = new Harness(); AtomicInteger actions = new AtomicInteger();
      if (immediate) h.physical = frame -> CompletableFuture.completedFuture(null);
      var stage = h.adapter.sender().send(new byte[] {1});
      stage.whenComplete((ignored, failure) -> h.adapter.ownerPost().post(actions::incrementAndGet));
      if (!immediate) h.physicalFutures.get(0).complete(null);
      assertEquals(0, actions.get()); assertEquals(1, h.queue.size());
      h.turn(); assertEquals(1, actions.get()); assertEquals(1, h.flushes);
    }
  }

  @Test void callbackBeforeRegistrationReturnSettlesButCallbackThenRegistrationThrowInvalidates() {
    for (boolean throwsAfter : new boolean[] {false, true}) {
      Harness h = new Harness(); ControlledFuture physical = new ControlledFuture();
      physical.duringRegistration = () -> physical.completeCallback(null);
      if (throwsAfter) physical.registrationFailure = new AssertionError("secret registration");
      h.physical = frame -> physical;
      var result = h.adapter.sender().send(new byte[] {1});
      assertDoesNotThrow(() -> result.toCompletableFuture().join()); assertNull(active(h));
      assertEquals(throwsAfter ? 1 : 0, h.failures);
      if (throwsAfter) assertSanitized(h.adapter.sender().send(new byte[] {2}));
    }
  }

  @Test void duplicateConflictingAndOldCallbacksCannotAffectNewPhysicalOperation() {
    Harness h = new Harness(); ControlledFuture old = new ControlledFuture();
    h.physical = frame -> old;
    var first = h.adapter.sender().send(new byte[] {1}); old.completeCallback(null);
    assertDoesNotThrow(() -> first.toCompletableFuture().join());
    ControlledFuture current = new ControlledFuture(); h.physical = frame -> current;
    var second = h.adapter.sender().send(new byte[] {2}); Object currentOperation = active(h);
    old.completeCallback(new AssertionError("old conflict")); old.completeCallback(null);
    assertSame(currentOperation, active(h)); assertEquals(0, h.failures); assertEquals(0, h.invalidations);
    assertFalse(second.toCompletableFuture().isDone());
    current.completeCallback(null); assertDoesNotThrow(() -> second.toCompletableFuture().join());
    current.completeCallback(new IllegalStateException("late conflict")); assertEquals(0, h.failures);
  }

  @Test void physicalThrowNullAndRegistrationThrowRetainUnsettledFrameAndSanitizeFailures() {
    for (int mode = 0; mode < 4; mode++) {
      Harness h = new Harness(); int selected = mode; ControlledFuture future = new ControlledFuture();
      future.registrationFailure = new AssertionError("secret registration", new Exception("secret cause"));
      h.physical = frame -> {
        if (selected == 0) throw new IllegalStateException("secret physical", new Exception("secret cause"));
        if (selected == 1) throw new AssertionError("secret physical");
        if (selected == 2) return null;
        return future;
      };
      assertSanitized(h.adapter.sender().send(new byte[] {1}));
      Object operation = active(h); assertNotNull(operation); assertNotNull(value(operation, "frame"));
      assertEquals(1, h.failures); assertEquals(0, h.invalidations);
      assertSanitized(h.adapter.sender().send(new byte[] {2})); assertSame(operation, active(h));
      if (mode == 3) {
        assertDoesNotThrow(() -> future.completeCallback(null));
        assertNull(value(operation, "frame")); assertNull(active(h));
      }
    }
  }

  @Test void externalFailureNeverRetainsThrowableAndContainmentFailureCannotEscapeCallback() {
    Harness h = new Harness(); var result = h.adapter.sender().send(new byte[] {1});
    h.notifyAction = () -> { throw new AssertionError("notification"); };
    assertDoesNotThrow(() -> h.physicalFutures.get(0).completeExceptionally(
        new IllegalArgumentException("secret", new Exception("secret chain"))));
    assertSanitized(result); assertEquals(1, h.failures); assertEquals(0, h.invalidations);
    assertNull(active(h));
  }

  @Test void physicalReentryIsFailClosedAndCannotInstallSecondOperation() {
    Harness h = new Harness(); ControlledFuture future = new ControlledFuture();
    AtomicInteger physicalCalls = new AtomicInteger();
    h.physical = frame -> {
      physicalCalls.incrementAndGet(); assertSanitized(h.adapter.sender().send(new byte[] {2})); return future;
    };
    var first = h.adapter.sender().send(new byte[] {1});
    future.completeCallback(null); assertSanitized(first);
    assertEquals(1, physicalCalls.get()); assertEquals(1, h.invalidations); assertEquals(0, h.failures);
  }

  @Test void sameActionPostTwiceCreatesDifferentWrappersAndMaySaturateMailbox() {
    Harness h = new Harness(); Runnable action = () -> fail("must be discarded");
    assertTrue(h.adapter.ownerPost().post(action)); assertFalse(h.adapter.ownerPost().post(action));
    assertEquals(1, h.invalidations); assertFalse(h.mailbox.isValid());
    h.turn(); assertEquals(List.of(V2OwnerMailbox.Reason.SEND_CELL_FULL), h.terminals);
    assertEquals(0, h.envelopeCalls);
  }

  @Test void acceptedButInvalidMailboxPostIsRejectedAfterInlineStoppedNullOrThrowEnqueue() {
    for (int mode = 0; mode < 4; mode++) {
      Harness h = new Harness(); int selected = mode;
      h.enqueue = r -> {
        if (selected == 0) { r.run(); return V2OwnerMailbox.EnqueueResult.ACCEPTED; }
        if (selected == 1) return V2OwnerMailbox.EnqueueResult.STOPPED;
        if (selected == 2) return null;
        throw new AssertionError("enqueue");
      };
      assertFalse(h.adapter.ownerPost().post(() -> fail()));
      assertEquals(1, h.invalidations); assertEquals(0, h.envelopeCalls);
    }
  }

  @Test void ownerAbortNeverRunsOrRetainsSuppliedCleanupAndUsesMailboxTerminalOnly() {
    Harness h = new Harness(); assertTrue(h.adapter.ownerAbort().request(() -> fail("not adapter cleanup")));
    assertEquals(0, h.terminals.size()); assertEquals(1, h.invalidations); h.turn();
    assertEquals(List.of(V2OwnerMailbox.Reason.EXTERNAL_ABORT), h.terminals);
    assertFalse(h.adapter.ownerAbort().request(() -> fail())); assertEquals(1, h.invalidations);
  }

  @Test void wrapperRechecksOwnerAndGateErrorsDoNotAuthorizeEnvelopeOrCleanup() {
    for (int mode = 0; mode < 3; mode++) {
      Harness h = new Harness(); int selected = mode;
      assertTrue(h.adapter.ownerPost().post(() -> fail()));
      h.gate = () -> {
        if (selected == 1) throw new IllegalArgumentException("gate");
        if (selected == 2) throw new AssertionError("gate");
        return false;
      };
      h.turn(); assertEquals(0, h.envelopeCalls); assertEquals(1, h.invalidations);
      assertEquals(List.of(V2OwnerMailbox.Reason.EXTERNAL_ABORT), h.terminals);
    }
  }

  @Test void actualOwnerPumpEnvelopeFlushesAndPreservesActionErrorOverFlushError() {
    for (boolean fatal : new boolean[] {false, true}) {
      Harness h = new Harness(); AssertionError original = new AssertionError("operation");
      h.envelope = action -> {
        Error primary = null;
        try { action.run(); }
        catch (Error error) { primary = error; throw error; }
        finally {
          h.flushes++;
          try { throw new LinkageError("flush"); }
          catch (Error flushFailure) { if (primary == null) throw flushFailure; }
        }
      };
      h.adapter.ownerPost().post(() -> {
        if (fatal) throw original;
        throw new IllegalStateException("operation runtime");
      });
      if (fatal) assertSame(original, assertThrows(AssertionError.class, h::turn));
      else assertThrows(LinkageError.class, h::turn);
      assertEquals(1, h.flushes); assertEquals(1, h.terminals.size());
    }
    Harness runtime = new Harness(); runtime.adapter.ownerPost().post(() -> { throw new IllegalStateException(); });
    assertDoesNotThrow(runtime::turn); assertEquals(1, runtime.flushes); assertEquals(1, runtime.terminals.size());
  }

  @Test void logicalFutureCancellationDoesNotClaimPhysicalSettlementOrPermitReuse() {
    Harness h = new Harness(); var result = h.adapter.sender().send(new byte[] {1}); Object operation = active(h);
    result.toCompletableFuture().cancel(false); assertSame(operation, active(h));
    assertNotNull(value(operation, "frame"));
    assertSanitized(h.adapter.sender().send(new byte[] {2}));
    h.physicalFutures.get(0).complete(null); assertNull(value(operation, "frame"));
  }

  @Test void foreignSettlementPublishesOnlyAndNeverCallsOwnerEnvelope() throws Exception {
    Harness h = new Harness(); var result = h.adapter.sender().send(new byte[] {1});
    CountDownLatch start = new CountDownLatch(1); AtomicReference<Throwable> failure = new AtomicReference<>();
    Thread callback = new Thread(() -> {
      try { await(start); h.physicalFutures.get(0).completeExceptionally(new AssertionError("physical")); }
      catch (Throwable error) { failure.set(error); }
    }); callback.start();
    try { start.countDown(); } finally { join(callback); }
    assertNull(failure.get()); assertSanitized(result); assertEquals(0, h.envelopeCalls);
    assertTrue(h.terminals.isEmpty()); assertEquals(1, h.failures);
  }

  @Test void realSessionOrdinaryLaterCompletionWorksAndImmediateNextCompletionSaturates() {
    for (boolean immediateSecond : new boolean[] {false, true}) {
      Harness h = new Harness(); CompletableFuture<Void> first = new CompletableFuture<>();
      CompletableFuture<Void> second = new CompletableFuture<>(); AtomicInteger sends = new AtomicInteger();
      h.physical = frame -> {
        h.physicalFrames.add(frame);
        return sends.incrementAndGet() == 1 ? first
            : immediateSecond ? CompletableFuture.completedFuture(null) : second;
      };
      byte[] psk = new byte[32]; byte[] hash = new byte[32];
      var transcript = new ControlAuth.Transcript(ControlAuth.Role.CLIENT_CONTROLLER, "client", "hub", "key",
          "LUO_CONTROL_POSTAUTH_V2", hash, hash, hash, "10000000-0000-4000-8000-000000000001",
          Instant.parse("2026-10-23T00:00:00Z"), Instant.parse("2026-10-23T00:00:05Z"), hash, hash);
      // Caller-trusted test transcript is not authenticated-handshake provenance.
      var keys = PostAuthSessionKeys.derive(psk, transcript);
      var session = new PostAuthPeerSession(transcript, keys, 1, () -> true, () -> 0,
          h.adapter.ownerPost(), h.adapter.ownerAbort(), reason -> {}, h.adapter.sender(), frame -> fail(),
          new PostAuthPeerSession.Lifecycle() {
            public void fence(String reason) {}
            public void physicalClose(String reason) {}
          });
      h.terminalAction = session::close;
      String connection = "20000000-0000-4000-8000-000000000001";
      try (var hubKeys = PostAuthSessionKeys.derive(psk, transcript)) {
        var open = new PostAuthFrameCodec.Frame(connection, transcript.challengeId(), 0, "SESSION_OPEN",
            "", "", "", "", "", PostAuthFrameCodec.sessionOpenPayload(connection).getBytes(StandardCharsets.US_ASCII), "");
        session.receive(PostAuthFrameCodec.encode(open, new PostAuthFrameCodec.Context(transcript, null),
            PostAuthFrameCodec.Direction.HUB_TO_PEER, hubKeys));
      }
      var ack = PostAuthFrameCodec.decode(h.physicalFrames.get(0),
          new PostAuthFrameCodec.Context(transcript, connection), PostAuthFrameCodec.Direction.PEER_TO_HUB, keys);
      assertEquals(0, ack.sequence()); assertEquals("HEARTBEAT", ack.messageKind());
      session.sendBusiness("HEARTBEAT", "{}".getBytes(StandardCharsets.US_ASCII), "", "", "", "", "");
      first.complete(null); h.turn(); assertEquals(2, sends.get());
      if (immediateSecond) {
        assertFalse(h.mailbox.isValid()); assertEquals(List.of(V2OwnerMailbox.Reason.SEND_CELL_FULL), h.terminals);
        assertEquals(1, h.invalidations); assertEquals(PostAuthPeerSession.State.CLOSED, session.state());
      } else {
        assertTrue(h.mailbox.isValid()); assertEquals(1, session.queuedFrames());
        second.complete(null); h.turn(); assertEquals(0, session.queuedFrames()); session.close();
      }
    }
  }

  @Test void concurrentOverlapWhilePhysicalPortIsBlockedRetainsOneFlightAndLateSuccessFails() throws Exception {
    Harness h = new Harness(); CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1); ControlledFuture future = new ControlledFuture();
    AtomicInteger calls = new AtomicInteger(); AtomicReference<CompletionStage<Void>> result = new AtomicReference<>();
    h.physical = frame -> { calls.incrementAndGet(); entered.countDown(); await(release); return future; };
    Thread sending = new Thread(() -> result.set(h.adapter.sender().send(new byte[] {1}))); sending.start();
    try {
      await(entered); assertSanitized(h.adapter.sender().send(new byte[] {2}));
      assertEquals(1, calls.get()); assertEquals(1, h.invalidations);
    } finally { release.countDown(); join(sending); }
    Object operation = active(h); future.completeCallback(null);
    assertSanitized(result.get()); assertNull(value(operation, "frame")); assertNull(active(h));
  }

  @Test void physicalAndContainmentPortsAreCalledOutsideAdapterLock() {
    Harness h = new Harness();
    h.physical = frame -> {
      AtomicReference<Object> observed = new AtomicReference<>();
      Thread inspector = new Thread(() -> {
        synchronized (value(h.adapter, "lock")) { observed.set(active(h)); }
      }); inspector.start(); join(inspector); assertNotNull(observed.get());
      return CompletableFuture.failedFuture(new AssertionError("physical"));
    };
    h.notifyAction = () -> {
      Thread inspector = new Thread(() -> {
        synchronized (value(h.adapter, "lock")) { assertNull(active(h)); }
      }); inspector.start(); join(inspector);
    };
    assertSanitized(h.adapter.sender().send(new byte[] {1})); assertEquals(1, h.failures);
  }

  @Test void invalidMailboxRejectsSendBeforePhysicalAndNullPostCannotRunAction() {
    Harness stopped = new Harness(); stopped.mailbox.publishStopped();
    assertSanitized(stopped.adapter.sender().send(new byte[] {1}));
    assertEquals(0, stopped.physicalFrames.size()); assertEquals(1, stopped.invalidations);
    Harness nullPost = new Harness(); assertFalse(nullPost.adapter.ownerPost().post(null));
    assertEquals(1, nullPost.invalidations); assertTrue(nullPost.queue.isEmpty());
  }

  @Test void failureFirstDuplicateSuccessCannotRewriteFutureOrRepeatContainment() {
    Harness h = new Harness(); ControlledFuture future = new ControlledFuture();
    h.physical = frame -> future;
    var result = h.adapter.sender().send(new byte[] {1}); Object operation = active(h);
    future.completeCallback(new AssertionError("secret failure"));
    future.completeCallback(null); future.completeCallback(new IllegalStateException("conflict"));
    assertSanitized(result); assertEquals(1, h.failures); assertEquals(0, h.invalidations);
    assertNull(active(h)); assertNull(value(operation, "frame"));
    assertFalse(h.adapter.ownerPost().post(() -> fail()));
    assertEquals(1, h.failures); assertEquals(0, h.invalidations);
  }

  @Test void realSessionPhysicalFailureRequestsOwnerTerminalDespiteInvalidAdapter() throws Exception {
    Harness h = new Harness(); Thread owner = Thread.currentThread();
    byte[] psk = new byte[32]; byte[] hash = new byte[32];
    var transcript = new ControlAuth.Transcript(ControlAuth.Role.CLIENT_CONTROLLER, "client", "hub", "key",
        "LUO_CONTROL_POSTAUTH_V2", hash, hash, hash, "10000000-0000-4000-8000-000000000001",
        Instant.parse("2026-10-23T00:00:00Z"), Instant.parse("2026-10-23T00:00:05Z"), hash, hash);
    var keys = PostAuthSessionKeys.derive(psk, transcript);
    AtomicInteger fences = new AtomicInteger(); AtomicInteger closes = new AtomicInteger();
    var session = new PostAuthPeerSession(transcript, keys, 1, () -> Thread.currentThread() == owner,
        () -> 0, h.adapter.ownerPost(), h.adapter.ownerAbort(), reason -> {}, h.adapter.sender(),
        frame -> fail(), new PostAuthPeerSession.Lifecycle() {
          public void fence(String reason) { assertSame(owner, Thread.currentThread()); fences.incrementAndGet(); }
          public void physicalClose(String reason) { assertSame(owner, Thread.currentThread()); closes.incrementAndGet(); }
        });
    AtomicInteger terminalCloses = new AtomicInteger();
    h.terminalAction = () -> { terminalCloses.incrementAndGet(); session.close(); };
    String connection = "20000000-0000-4000-8000-000000000001";
    try (var hubKeys = PostAuthSessionKeys.derive(psk, transcript)) {
      var open = new PostAuthFrameCodec.Frame(connection, transcript.challengeId(), 0, "SESSION_OPEN",
          "", "", "", "", "", PostAuthFrameCodec.sessionOpenPayload(connection).getBytes(StandardCharsets.US_ASCII), "");
      session.receive(PostAuthFrameCodec.encode(open, new PostAuthFrameCodec.Context(transcript, null),
          PostAuthFrameCodec.Direction.HUB_TO_PEER, hubKeys));
    }
    Thread physicalCallback = new Thread(() -> h.physicalFutures.get(0).completeExceptionally(
        new AssertionError("secret physical failure")));
    physicalCallback.start(); join(physicalCallback);
    assertEquals(1, h.failures); assertEquals(0, h.invalidations);
    assertEquals(0, fences.get()); assertEquals(0, closes.get()); assertEquals(0, terminalCloses.get());
    assertEquals(PostAuthPeerSession.State.OPEN, session.state()); assertEquals(1, h.queue.size());
    assertDoesNotThrow(() -> keys.mac(PostAuthFrameCodec.Direction.PEER_TO_HUB, new byte[0]));
    h.turn();
    assertEquals(1, terminalCloses.get()); assertEquals(1, fences.get()); assertEquals(1, closes.get());
    assertEquals(PostAuthPeerSession.State.CLOSED, session.state());
    assertThrows(IllegalStateException.class, () -> keys.mac(PostAuthFrameCodec.Direction.PEER_TO_HUB, new byte[0]));
    assertFalse(h.adapter.ownerAbort().request(() -> fail("supplied abort must never run")));
    assertTrue(h.queue.isEmpty()); assertEquals(1, h.failures); assertEquals(0, h.invalidations);
  }

  @Test void adapterAbortAfterEnqueueFailureCanArrangeOneRecoveryWhileReturningFalse() {
    Harness h = new Harness(); h.enqueue = r -> { throw new AssertionError("enqueue"); };
    assertFalse(h.adapter.ownerPost().post(() -> fail()));
    assertEquals(1, h.invalidations); assertTrue(h.queue.isEmpty());
    h.enqueue = r -> { h.queue.add(r); return V2OwnerMailbox.EnqueueResult.ACCEPTED; };
    Runnable forbidden = () -> fail("supplied abort ignored");
    assertFalse(h.adapter.ownerAbort().request(forbidden));
    assertEquals(1, h.queue.size()); assertFalse(h.adapter.ownerAbort().request(forbidden));
    assertEquals(1, h.queue.size()); h.turn();
    assertEquals(List.of(V2OwnerMailbox.Reason.ENQUEUE_FAILED), h.terminals);
    assertEquals(1, h.invalidations); assertEquals(0, h.failures);
    assertFalse(h.adapter.ownerAbort().request(forbidden)); assertTrue(h.queue.isEmpty());
  }

  @Test void failedRecoveryEnqueueNeverRetriesAndStoppedNeverGainsOwnerPermission() {
    Harness h = new Harness(); AtomicInteger enqueues = new AtomicInteger();
    h.enqueue = r -> { enqueues.incrementAndGet(); return null; };
    assertFalse(h.adapter.ownerPost().post(() -> fail()));
    assertFalse(h.adapter.ownerAbort().request(() -> fail())); assertEquals(2, enqueues.get());
    h.enqueue = r -> { enqueues.incrementAndGet(); h.queue.add(r); return V2OwnerMailbox.EnqueueResult.ACCEPTED; };
    assertFalse(h.adapter.ownerAbort().request(() -> fail()));
    assertEquals(2, enqueues.get()); assertTrue(h.queue.isEmpty()); assertTrue(h.terminals.isEmpty());
    assertEquals(1, h.invalidations);
    Harness stopped = new Harness(); stopped.mailbox.publishStopped();
    assertFalse(stopped.adapter.ownerAbort().request(() -> fail()));
    assertTrue(stopped.queue.isEmpty()); assertTrue(stopped.terminals.isEmpty());
    assertEquals(1, stopped.invalidations);
  }

  private static void assertSanitized(CompletionStage<Void> stage) {
    CompletionException thrown = assertThrows(CompletionException.class, () -> stage.toCompletableFuture().join());
    Throwable marker = thrown.getCause(); assertEquals("v2 send unavailable", marker.getMessage());
    assertNull(marker.getCause()); assertEquals(0, marker.getSuppressed().length); assertEquals(0, marker.getStackTrace().length);
  }
  private static Object active(Harness h) { return value(h.adapter, "active"); }
  private static Object value(Object target, String name) {
    try { Field field = target.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(target); }
    catch (ReflectiveOperationException error) { throw new AssertionError(error); }
  }
  private static void await(CountDownLatch latch) {
    try { assertTrue(latch.await(5, TimeUnit.SECONDS)); }
    catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new AssertionError(error); }
  }
  private static void join(Thread t) {
    try { t.join(5000); assertFalse(t.isAlive()); }
    catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new AssertionError(error); }
  }
}
