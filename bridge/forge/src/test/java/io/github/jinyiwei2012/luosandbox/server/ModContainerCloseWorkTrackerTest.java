package io.github.jinyiwei2012.luosandbox.server;

import static org.junit.jupiter.api.Assertions.*;

import io.github.jinyiwei2012.luosandbox.server.ForgePacketDefenseBridgeTest.Fixture;
import io.github.jinyiwei2012.luosandbox.servercontrol.PermitAuthority;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class ModContainerCloseWorkTrackerTest {
  private static final BooleanSupplier OWNER = () -> true;
  private static final Object SERVER = new Object();
  private static final Object PLAYER = new Object();

  private static void submit(ModContainerCloseWorkTracker tracker, Consumer<Runnable> scheduler,
      Runnable action) {
    tracker.submit(OWNER, SERVER, PLAYER, Optional.empty(), scheduler, action);
  }

  private static void assertSummary(ModContainerCloseWorkTracker tracker,
      ModContainerCloseWorkTracker.Availability availability, int outstanding, int running,
      boolean none) {
    var summary = tracker.summary(OWNER);
    assertEquals(availability, summary.availability());
    assertEquals(outstanding, summary.outstanding());
    assertEquals(running, summary.running());
    assertEquals(none, summary.noOutstandingObservedSubmissions());
  }

  @Test
  void initialAndInvalidatedAreNeverSuccessfulObservations() {
    var tracker = new ModContainerCloseWorkTracker();
    assertSummary(tracker, ModContainerCloseWorkTracker.Availability.UNOBSERVED, 0, 0, false);
    tracker.invalidate();
    assertSummary(tracker, ModContainerCloseWorkTracker.Availability.UNKNOWN, 0, 0, false);
    submit(tracker, Runnable::run, () -> {});
    assertSummary(tracker, ModContainerCloseWorkTracker.Availability.UNKNOWN, 0, 0, false);
  }

  @Test
  void inlineRegistersBeforeRunAndRetainsSlotUntilExecuteReturns() {
    var tracker = new ModContainerCloseWorkTracker();
    submit(tracker, wrapper -> {
      assertSummary(tracker, ModContainerCloseWorkTracker.Availability.TRACKING, 1, 0, false);
      wrapper.run();
      assertSummary(tracker, ModContainerCloseWorkTracker.Availability.TRACKING, 1, 0, false);
    }, () -> assertSummary(tracker, ModContainerCloseWorkTracker.Availability.TRACKING, 1, 1, false));
    assertSummary(tracker, ModContainerCloseWorkTracker.Availability.TRACKING, 0, 0, true);
  }

  @Test
  void queuedCompletionAndNeverRunSubmissionHaveNoTimeRelease() {
    var tracker = new ModContainerCloseWorkTracker();
    List<Runnable> queue = new ArrayList<>();
    submit(tracker, queue::add, () -> {});
    submit(tracker, queue::add, () -> {});
    assertSummary(tracker, ModContainerCloseWorkTracker.Availability.TRACKING, 2, 0, false);
    queue.remove(0).run();
    for (int i = 0; i < 20; i++) {
      assertSummary(tracker, ModContainerCloseWorkTracker.Availability.TRACKING, 1, 0, false);
    }
    queue.clear();
    assertEquals(1, tracker.summary(OWNER).outstanding());
  }

  @Test
  void distinctReentrantSubmissionHasIndependentOccupancy() {
    var tracker = new ModContainerCloseWorkTracker();
    submit(tracker, Runnable::run, () -> {
      submit(tracker, Runnable::run, () ->
          assertSummary(tracker, ModContainerCloseWorkTracker.Availability.TRACKING, 2, 2, false));
      assertSummary(tracker, ModContainerCloseWorkTracker.Availability.TRACKING, 1, 1, false);
    });
    assertTrue(tracker.summary(OWNER).noOutstandingObservedSubmissions());
  }

  @Test
  void submissionThrowBeforeRunRemainsOutstandingAndExact() {
    for (Throwable failure : List.of(new IllegalArgumentException("submit"), new AssertionError("submit"))) {
      var tracker = new ModContainerCloseWorkTracker();
      Throwable actual = assertThrows(failure.getClass(), () -> submit(tracker, r -> raise(failure), () -> fail()));
      assertSame(failure, actual);
      assertSummary(tracker, ModContainerCloseWorkTracker.Availability.UNKNOWN, 1, 0, false);
    }
  }

  @Test
  void enqueueThenThrowLaterRunRetiresWithoutRewritingSubmissionOrRepairingFault() {
    var tracker = new ModContainerCloseWorkTracker();
    AtomicReference<Runnable> queued = new AtomicReference<>();
    var failure = new IllegalStateException("accepted then failed");
    assertSame(failure, assertThrows(IllegalStateException.class, () -> submit(tracker, r -> {
      queued.set(r);
      throw failure;
    }, () -> {})));
    Object work = activeWork(tracker);
    assertEquals("THREW", value(work, "submission").toString());
    queued.get().run();
    assertEquals("THREW", value(work, "submission").toString());
    assertSummary(tracker, ModContainerCloseWorkTracker.Availability.UNKNOWN, 0, 0, false);
    queued.get().run();
    assertEquals(0, tracker.summary(OWNER).outstanding());
  }

  @Test
  void inlineCompletionThenSubmitThrowKeepsSeparateOutcomes() {
    var tracker = new ModContainerCloseWorkTracker();
    AtomicReference<Object> work = new AtomicReference<>();
    var failure = new AssertionError("after inline completion");
    assertSame(failure, assertThrows(AssertionError.class, () -> submit(tracker, r -> {
      work.set(activeWork(tracker));
      r.run();
      assertEquals("RETURNED_NORMALLY", value(work.get(), "execution").toString());
      assertEquals("CALLING_EXECUTE", value(work.get(), "submission").toString());
      throw failure;
    }, () -> {})));
    assertEquals("THREW", value(work.get(), "submission").toString());
    assertSummary(tracker, ModContainerCloseWorkTracker.Availability.UNKNOWN, 0, 0, false);
  }

  @Test
  void actionExceptionsPreserveExactRuntimeAndErrorForInlineAndQueued() {
    for (boolean inline : new boolean[] {true, false}) {
      for (Throwable failure : List.of(new IllegalStateException("action"), new AssertionError("action"))) {
        var tracker = new ModContainerCloseWorkTracker();
        if (inline) {
          assertSame(failure, assertThrows(failure.getClass(), () ->
              submit(tracker, Runnable::run, () -> raise(failure))));
        } else {
          AtomicReference<Runnable> queued = new AtomicReference<>();
          submit(tracker, queued::set, () -> raise(failure));
          assertSame(failure, assertThrows(failure.getClass(), () -> queued.get().run()));
        }
        assertSummary(tracker, ModContainerCloseWorkTracker.Availability.UNKNOWN, 0, 0, false);
      }
    }
  }

  @Test
  void overflowStillExecutesNinthAndCoverageLossIsPermanent() {
    var tracker = new ModContainerCloseWorkTracker();
    List<Runnable> queue = new ArrayList<>();
    AtomicInteger actions = new AtomicInteger();
    for (int i = 0; i < 9; i++) submit(tracker, queue::add, actions::incrementAndGet);
    assertEquals(9, queue.size());
    assertSummary(tracker, ModContainerCloseWorkTracker.Availability.UNKNOWN, 8, 0, false);
    queue.forEach(Runnable::run);
    assertEquals(9, actions.get());
    assertSummary(tracker, ModContainerCloseWorkTracker.Availability.UNKNOWN, 0, 0, false);
  }

  @Test
  void duplicateLateAndQueuedAlwaysRunDelegateWithoutReleasingAnotherSlot() {
    var tracker = new ModContainerCloseWorkTracker();
    List<Runnable> queue = new ArrayList<>();
    AtomicInteger actions = new AtomicInteger();
    submit(tracker, queue::add, actions::incrementAndGet);
    submit(tracker, queue::add, actions::incrementAndGet);
    queue.get(0).run();
    queue.get(0).run();
    assertSummary(tracker, ModContainerCloseWorkTracker.Availability.UNKNOWN, 1, 0, false);
    queue.get(1).run();
    queue.get(0).run();
    assertEquals(4, actions.get());
    assertSummary(tracker, ModContainerCloseWorkTracker.Availability.UNKNOWN, 0, 0, false);
  }

  @Test
  void reentrantDuplicateStillRunsDelegateButDoesNotDoubleRelease() {
    var tracker = new ModContainerCloseWorkTracker();
    AtomicReference<Runnable> queued = new AtomicReference<>();
    AtomicInteger actions = new AtomicInteger();
    submit(tracker, queued::set, () -> {
      if (actions.incrementAndGet() == 1) queued.get().run();
    });
    queued.get().run();
    assertEquals(2, actions.get());
    assertSummary(tracker, ModContainerCloseWorkTracker.Availability.UNKNOWN, 0, 0, false);
  }

  @Test
  void offOwnerRunOnlyInvalidatesStillExecutesAndPreservesException() throws Exception {
    Thread owner = Thread.currentThread();
    BooleanSupplier gate = () -> Thread.currentThread() == owner;
    var tracker = new ModContainerCloseWorkTracker();
    AtomicReference<Runnable> queued = new AtomicReference<>();
    AtomicInteger actions = new AtomicInteger();
    var failure = new AssertionError("foreign action");
    tracker.submit(gate, SERVER, PLAYER, Optional.empty(), queued::set, () -> {
      actions.incrementAndGet();
      throw failure;
    });
    Object work = activeWork(tracker);
    AtomicReference<Throwable> thrown = new AtomicReference<>();
    Thread foreign = new Thread(() -> {
      try { queued.get().run(); } catch (Throwable error) { thrown.set(error); }
    });
    foreign.start();
    foreign.join();
    assertSame(failure, thrown.get());
    assertEquals(1, actions.get());
    assertEquals("NOT_ENTERED", value(work, "execution").toString());
    assertSummary(tracker, ModContainerCloseWorkTracker.Availability.UNKNOWN, 1, 0, false);
  }

  @Test
  void ownerGateFirstForEventSubmissionAndObservationIncludingNullAndThrowingGates() {
    var fixture = new Fixture();
    var tracker = new ModContainerCloseWorkTracker();
    BooleanSupplier[] gates = {null, () -> false, () -> { throw new AssertionError("gate"); }};
    for (BooleanSupplier gate : gates) {
      assertThrows(IllegalStateException.class, () -> ForgeServerDefense.submitContainerClose(
          fixture.registry, tracker, gate, SERVER, fixture.player, r -> fail("submit"), () -> fail("run")));
      assertThrows(IllegalStateException.class, () -> tracker.summary(gate));
      assertEquals(0, fixture.identityReads);
    }
    assertSummary(tracker, ModContainerCloseWorkTracker.Availability.UNOBSERVED, 0, 0, false);
  }

  @Test
  void realRegistryCaptureBeforeFencePreservesExactOldBodyAndGeneration() {
    var fixture = new Fixture();
    fixture.issueLook();
    var body = fixture.registry.current().orElseThrow();
    var capture = fixture.registry.captureContainerClose(fixture.player).orElseThrow();
    var tracker = new ModContainerCloseWorkTracker();
    AtomicReference<Runnable> queued = new AtomicReference<>();
    ForgeServerDefense.submitContainerClose(fixture.registry, tracker, OWNER, SERVER,
        fixture.player, queued::set, () -> {});
    assertTrue(fixture.registry.current().isEmpty());
    Object work = activeWork(tracker);
    assertSame(SERVER, value(work, "server"));
    assertSame(fixture.player, value(work, "player"));
    assertEquals("CURRENT_BODY", value(work, "classification").toString());
    var stored = (ControlledIdentityRegistry.ContainerCloseCapture<?>) value(work, "capture");
    assertSame(body, stored.body());
    assertEquals(capture.preFenceGeneration(), stored.preFenceGeneration());
    assertEquals(body.binding(), stored.body().binding());
    assertTrue(fixture.registry.bindTrusted(fixture.player, body.binding(), fixture.coordinator));
    var replacement = fixture.registry.captureContainerClose(fixture.player).orElseThrow();
    assertNotSame(body, replacement.body());
    assertTrue(replacement.preFenceGeneration() > stored.preFenceGeneration());
    int sends = fixture.sends;
    int revokes = fixture.revokes;
    queued.get().run();
    assertEquals(sends, fixture.sends);
    assertEquals(revokes, fixture.revokes);
    assertSame(replacement.body(), fixture.registry.current().orElseThrow());
  }

  @Test
  void protectedCandidateWithoutCurrentAndFalseFenceStillClosesWithoutInventingBody() {
    var fixture = new Fixture();
    assertTrue(fixture.registry.protectCandidate(fixture.player));
    var tracker = new ModContainerCloseWorkTracker();
    AtomicInteger actions = new AtomicInteger();
    ForgeServerDefense.submitContainerClose(fixture.registry, tracker, OWNER, SERVER,
        fixture.player, r -> {
          Object work = activeWork(tracker);
          assertEquals("NON_CURRENT_PROTECTED_CONNECTION", value(work, "classification").toString());
          assertNull(value(work, "capture"));
          r.run();
        }, actions::incrementAndGet);
    assertEquals(1, actions.get());
    assertTrue(tracker.summary(OWNER).noOutstandingObservedSubmissions());
    assertTrue(fixture.registry.current().isEmpty());
  }

  @Test
  void fenceExceptionsPropagateExactlyWithoutRegistrationOrSubmission() {
    for (Throwable failure : List.of(new IllegalStateException("fence"), new AssertionError("fence"))) {
      var fixture = new Fixture();
      fixture.issueLook();
      AtomicInteger identities = new AtomicInteger();
      fixture.identityRead = () -> {
        // candidate predicate, capture, then the original fence's resolve
        if (identities.incrementAndGet() == 3) raise(failure);
      };
      var tracker = new ModContainerCloseWorkTracker();
      assertSame(failure, assertThrows(failure.getClass(), () -> ForgeServerDefense.submitContainerClose(
          fixture.registry, tracker, OWNER, SERVER, fixture.player, r -> fail("submit"), () -> fail("run"))));
      assertEquals(3, identities.get());
      assertSummary(tracker, ModContainerCloseWorkTracker.Availability.UNOBSERVED, 0, 0, false);
    }
  }

  @Test
  void captureRejectsReentrantReplacementForeignReferenceAndChangedConnection() {
    var fixture = new Fixture();
    fixture.issueLook();
    var original = fixture.registry.current().orElseThrow();
    assertTrue(fixture.registry.captureContainerClose(
        new ForgePacketDefenseBridgeTest.PlayerHandle(fixture.player.connection())).isEmpty());
    fixture.connectionOverride = new Object();
    assertTrue(fixture.registry.captureContainerClose(fixture.player).isEmpty());
    fixture.connectionOverride = null;
    fixture.identityRead = () -> {
      fixture.identityRead = () -> {};
      assertTrue(fixture.registry.bindTrusted(fixture.player, original.binding(), fixture.coordinator));
    };
    assertTrue(fixture.registry.captureContainerClose(fixture.player).isEmpty());
    assertNotSame(original, fixture.registry.current().orElseThrow());
  }

  @Test
  void overflowAtRealSubmitSiteStillFencesAndSubmitsNinth() {
    var fixture = new Fixture();
    var tracker = new ModContainerCloseWorkTracker();
    List<Runnable> queue = new ArrayList<>();
    AtomicInteger actions = new AtomicInteger();
    for (int i = 0; i < 9; i++) {
      fixture.bind();
      ForgeServerDefense.submitContainerClose(fixture.registry, tracker, OWNER, SERVER,
          fixture.player, queue::add, actions::incrementAndGet);
      assertTrue(fixture.registry.current().isEmpty());
    }
    assertEquals(9, queue.size());
    assertEquals(8, tracker.summary(OWNER).outstanding());
    queue.forEach(Runnable::run);
    assertEquals(9, actions.get());
    assertSummary(tracker, ModContainerCloseWorkTracker.Availability.UNKNOWN, 0, 0, false);
  }

  @Test
  void arbitraryThreadInvalidationDoesNotTouchOwnerState() throws Exception {
    var tracker = new ModContainerCloseWorkTracker();
    AtomicReference<Runnable> queued = new AtomicReference<>();
    submit(tracker, queued::set, () -> {});
    Object work = activeWork(tracker);
    Thread foreign = new Thread(tracker::invalidate);
    foreign.start();
    foreign.join();
    assertEquals("NOT_ENTERED", value(work, "execution").toString());
    assertSummary(tracker, ModContainerCloseWorkTracker.Availability.UNKNOWN, 1, 0, false);
    queued.get().run();
    assertSummary(tracker, ModContainerCloseWorkTracker.Availability.UNKNOWN, 0, 0, false);
  }

  @Test
  void throwingAccountingGateCannotMaskOriginalActionException() {
    var tracker = new ModContainerCloseWorkTracker();
    java.util.concurrent.atomic.AtomicBoolean broken = new java.util.concurrent.atomic.AtomicBoolean();
    BooleanSupplier gate = () -> {
      if (broken.get()) throw new AssertionError("accounting gate");
      return true;
    };
    var failure = new IllegalArgumentException("original action");
    assertSame(failure, assertThrows(IllegalArgumentException.class, () -> tracker.submit(
        gate, SERVER, PLAYER, Optional.empty(), Runnable::run, () -> {
          broken.set(true);
          throw failure;
        })));
    assertSummary(tracker, ModContainerCloseWorkTracker.Availability.UNKNOWN, 1, 1, false);
  }

  @Test
  void captureIsReadOnlyAndDoesNotInvalidateExistingCurrentOrCreateFenceReceipt() {
    var fixture = new Fixture();
    fixture.issueLook();
    var first = fixture.registry.captureContainerClose(fixture.player).orElseThrow();
    var second = fixture.registry.captureContainerClose(fixture.player).orElseThrow();
    assertSame(first.body(), second.body());
    assertEquals(first.preFenceGeneration(), second.preFenceGeneration());
    assertSame(first.body(), fixture.registry.current().orElseThrow());
    int sends = fixture.sends;
    int revokes = fixture.revokes;
    assertTrue(fixture.registry.beginBindingPrepare(
        fixture.player, first.body().binding(), 200).isPresent());
    assertEquals(sends, fixture.sends);
    assertTrue(fixture.revokes > revokes);
    assertTrue(fixture.registry.captureContainerClose(fixture.player).isEmpty());
  }

  @Test
  void captureRuntimeFailureStillFencesExecutesAndPermanentlyReportsUnknown() {
    var fixture = new Fixture();
    fixture.issueLook();
    failCaptureOnce(fixture, new IllegalArgumentException("capture"));
    var tracker = new ModContainerCloseWorkTracker();
    AtomicInteger actions = new AtomicInteger();
    int revokes = fixture.revokes;
    ForgeServerDefense.submitContainerClose(fixture.registry, tracker, OWNER, SERVER,
        fixture.player, Runnable::run, actions::incrementAndGet);
    assertEquals(1, actions.get());
    assertTrue(fixture.registry.current().isEmpty());
    assertTrue(fixture.revokes > revokes);
    assertSummary(tracker, ModContainerCloseWorkTracker.Availability.UNKNOWN, 0, 0, false);
  }

  @Test
  void captureFailureFollowedByFalseFenceStillExecutesWithoutFakeClassification() {
    var fixture = new Fixture();
    fixture.issueLook();
    AtomicInteger identities = new AtomicInteger();
    fixture.identityRead = () -> {
      if (identities.incrementAndGet() == 2) {
        fixture.identityRead = () -> {};
        assertTrue(fixture.registry.releaseOffline(
            fixture.player, PermitAuthority.RevokeReason.CONTROL_LOST));
        throw new IllegalStateException("capture after release");
      }
    };
    var tracker = new ModContainerCloseWorkTracker();
    AtomicInteger actions = new AtomicInteger();
    ForgeServerDefense.submitContainerClose(fixture.registry, tracker, OWNER, SERVER,
        fixture.player, Runnable::run, actions::incrementAndGet);
    assertEquals(1, actions.get());
    assertSummary(tracker, ModContainerCloseWorkTracker.Availability.UNKNOWN, 0, 0, false);
  }

  @Test
  void captureErrorWithFalseFenceStillSubmitsCloseThenRethrowsSameError() {
    var fixture = new Fixture();
    fixture.issueLook();
    var fatal = new AssertionError("capture");
    AtomicInteger identities = new AtomicInteger();
    fixture.identityRead = () -> {
      if (identities.incrementAndGet() == 2) {
        fixture.identityRead = () -> {};
        assertTrue(fixture.registry.releaseOffline(
            fixture.player, PermitAuthority.RevokeReason.CONTROL_LOST));
        throw fatal;
      }
    };
    var tracker = new ModContainerCloseWorkTracker();
    AtomicInteger actions = new AtomicInteger();
    assertSame(fatal, assertThrows(AssertionError.class, () ->
        ForgeServerDefense.submitContainerClose(fixture.registry, tracker, OWNER, SERVER,
            fixture.player, Runnable::run, actions::incrementAndGet)));
    assertEquals(1, actions.get());
    assertSummary(tracker, ModContainerCloseWorkTracker.Availability.UNKNOWN, 0, 0, false);
  }

  @Test
  void captureRuntimeFailureDoesNotReplaceFenceOrExecuteFailure() {
    for (boolean fenceFails : new boolean[] {true, false}) {
      for (Throwable defenseFailure : List.of(new IllegalStateException("defense"), new AssertionError("defense"))) {
        var fixture = new Fixture();
        fixture.issueLook();
        var captureFailure = new IllegalArgumentException("capture");
        AtomicInteger reads = new AtomicInteger();
        fixture.identityRead = () -> {
          int read = reads.incrementAndGet();
          if (read == 2) throw captureFailure;
          if (read == 3 && fenceFails) raise(defenseFailure);
        };
        var tracker = new ModContainerCloseWorkTracker();
        AtomicInteger submissions = new AtomicInteger();
        assertSame(defenseFailure, assertThrows(defenseFailure.getClass(), () ->
            ForgeServerDefense.submitContainerClose(fixture.registry, tracker, OWNER, SERVER,
                fixture.player, r -> { submissions.incrementAndGet(); raise(defenseFailure); }, () -> fail())));
        assertEquals(fenceFails ? 0 : 1, submissions.get());
        assertSummary(tracker, ModContainerCloseWorkTracker.Availability.UNKNOWN, 0, 0, false);
      }
    }
  }

  @Test
  void captureErrorRunsBestEffortDefenseAndAlwaysWinsExceptionPriority() {
    for (String outcome : List.of("success", "fence-runtime", "fence-error", "execute-runtime", "execute-error")) {
      var fixture = new Fixture();
      fixture.issueLook();
      var captureError = new AssertionError("original capture");
      AtomicInteger reads = new AtomicInteger();
      fixture.identityRead = () -> {
        int read = reads.incrementAndGet();
        if (read == 2) throw captureError;
        if (read == 3 && outcome.startsWith("fence")) {
          if (outcome.endsWith("error")) throw new AssertionError("fence");
          throw new IllegalStateException("fence");
        }
      };
      var tracker = new ModContainerCloseWorkTracker();
      AtomicInteger submissions = new AtomicInteger();
      AtomicInteger actions = new AtomicInteger();
      assertSame(captureError, assertThrows(AssertionError.class, () ->
          ForgeServerDefense.submitContainerClose(fixture.registry, tracker, OWNER, SERVER,
              fixture.player, r -> {
                submissions.incrementAndGet();
                if (outcome.equals("execute-runtime")) throw new IllegalStateException("execute");
                if (outcome.equals("execute-error")) throw new AssertionError("execute");
                r.run();
              }, actions::incrementAndGet)));
      assertEquals(outcome.startsWith("fence") ? 0 : 1, submissions.get());
      assertEquals(outcome.equals("success") ? 1 : 0, actions.get());
      assertSummary(tracker, ModContainerCloseWorkTracker.Availability.UNKNOWN, 0, 0, false);
    }
  }

  @Test
  void productionAndSeamRetainCaptureFenceExecuteOrderAndFatalPriorityStructure() throws Exception {
    var node = new org.objectweb.asm.tree.ClassNode();
    try (var input = ForgeServerDefense.class.getResourceAsStream("ForgeServerDefense.class")) {
      assertNotNull(input);
      new org.objectweb.asm.ClassReader(input.readAllBytes()).accept(node, 0);
    }
    for (String name : List.of("onContainerOpen", "submitContainerClose")) {
      var method = node.methods.stream().filter(m -> m.name.equals(name)).findFirst().orElseThrow();
      int capture = -1;
      int fence = -1;
      int execute = -1;
      int fatalReads = 0;
      for (var instruction : method.instructions) {
        if (instruction instanceof org.objectweb.asm.tree.MethodInsnNode call) {
          int index = method.instructions.indexOf(call);
          if (call.name.equals("prepareContainerClose")) capture = index;
          if (call.name.equals("fence")) fence = index;
          if (call.name.equals(name.equals("onContainerOpen") ? "execute" : "accept")) execute = index;
          if (call.name.equals("fatal")) fatalReads++;
        }
      }
      assertTrue(capture >= 0 && fence > capture && execute > fence, name);
      assertTrue(fatalReads >= 4, "both fence catch and execute finally check original fatal: " + name);
      assertTrue(method.tryCatchBlocks.stream().anyMatch(b -> "java/lang/RuntimeException".equals(b.type)));
      assertTrue(method.tryCatchBlocks.stream().anyMatch(b -> "java/lang/Error".equals(b.type)));
      assertTrue(method.tryCatchBlocks.stream().anyMatch(b -> b.type == null));
    }
  }

  private static void failCaptureOnce(Fixture fixture, RuntimeException failure) {
    AtomicInteger identities = new AtomicInteger();
    fixture.identityRead = () -> {
      if (identities.incrementAndGet() == 2) throw failure;
    };
  }

  @Test
  void unprotectedEventDoesNotRegisterOrExecute() {
    var fixture = new Fixture();
    var tracker = new ModContainerCloseWorkTracker();
    ForgeServerDefense.submitContainerClose(fixture.registry, tracker, OWNER, SERVER,
        fixture.player, r -> fail(), () -> fail());
    assertSummary(tracker, ModContainerCloseWorkTracker.Availability.UNOBSERVED, 0, 0, false);
  }

  private static void raise(Throwable failure) {
    if (failure instanceof Error error) throw error;
    throw (RuntimeException) failure;
  }

  private static Object activeWork(ModContainerCloseWorkTracker tracker) {
    var active = (java.util.Map<?, ?>) value(tracker, "active");
    assertEquals(1, active.size());
    return active.keySet().iterator().next();
  }

  private static Object value(Object target, String name) {
    try {
      Field field = target.getClass().getDeclaredField(name);
      field.setAccessible(true);
      return field.get(target);
    } catch (ReflectiveOperationException error) {
      throw new AssertionError(error);
    }
  }
}
