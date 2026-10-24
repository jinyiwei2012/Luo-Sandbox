package io.github.jinyiwei2012.luosandbox.transport;

import static org.junit.jupiter.api.Assertions.*;

import io.github.jinyiwei2012.luosandbox.security.ControlAuth;
import io.github.jinyiwei2012.luosandbox.security.PostAuthFrameCodec;
import io.github.jinyiwei2012.luosandbox.security.PostAuthSessionKeys;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.lang.reflect.Field;
import org.junit.jupiter.api.Test;

class PostAuthPeerSessionTest {
  private static final String CONNECTION = "20000000-0000-4000-8000-000000000001";
  private final Harness harness = new Harness();

  @Test void openAdoptsIdAndAckIsFirstThenBusinessSequenceOne() {
    PostAuthPeerSession session = harness.session();
    session.receive(harness.hubFrame(null, 0, "SESSION_OPEN",
        PostAuthFrameCodec.sessionOpenPayload(CONNECTION).getBytes(StandardCharsets.US_ASCII)));
    assertEquals(PostAuthPeerSession.State.OPEN, session.state());
    assertEquals(CONNECTION, session.connectionId());
    session.sendBusiness("BODY_BIND_NOTICE", notice(), "", "", "", "", "");
    assertEquals(2, session.queuedFrames());
    assertEquals(1, harness.sent.size());
    assertEquals(0, harness.decodePeer(harness.sent.get(0)).sequence());
    harness.completeSuccess(); harness.runOwnerEvents();
    assertEquals(2, harness.sent.size());
    assertEquals(1, harness.decodePeer(harness.sent.get(1)).sequence());
  }

  @Test void openAndHeartbeatDeadlinesAreExactAndBusinessDoesNotRefresh() {
    PostAuthPeerSession unopened = harness.session();
    harness.now = 5_000;
    assertThrows(IllegalStateException.class, unopened::tick);
    assertEquals(List.of("fence:session_open_timeout", "close:session_open_timeout"), harness.lifecycle);

    Harness active = new Harness(ControlAuth.Role.SERVER_AUTHORITY); PostAuthPeerSession session = active.open();
    active.now = 1_999;
    session.receive(active.hubBusiness(1));
    active.now = 2_000;
    assertThrows(IllegalStateException.class, session::tick);
    assertTrue(active.lifecycle.contains("fence:heartbeat_timeout"));
  }

  @Test void onlyValidHeartbeatRefreshesAndLateHeartbeatCannotRevive() {
    PostAuthPeerSession session = harness.open();
    for (long due : new long[] {500, 1000, 1500}) {
      harness.now = due; session.tick();
    }
    harness.now = 1_999; session.receive(harness.hubHeartbeat(1));
    harness.completeSuccess(); harness.runOwnerEvents();
    for (long due : new long[] {2000, 2500, 3000, 3500}) {
      harness.now = due; session.tick();
      while (!harness.futures.get(harness.futures.size() - 1).isDone()) {
        harness.futures.get(harness.futures.size() - 1).complete(null); harness.runOwnerEvents();
      }
    }
    harness.now = 3_998; session.tick();
    harness.now = 3_999;
    assertThrows(IllegalStateException.class, () -> session.receive(harness.hubHeartbeat(2)));
    assertEquals(PostAuthPeerSession.State.CLOSED, session.state());
    assertTrue(harness.lifecycle.contains("fence:heartbeat_timeout"));
  }

  @Test void absoluteDeadlineIsOriginalDespiteDelayedConstructionAndExactExpiry() {
    for (long construction : new long[] {100, 1000, 5099}) {
      Harness h = new Harness(); h.now = construction;
      PostAuthPeerSession session = h.absoluteSession(5100);
      assertEquals(1, h.clockReads);
      assertEquals(5100, getLong(session, "openDeadline"));
      h.now = 5099; session.tick();
      assertEquals(PostAuthPeerSession.State.WAIT_SESSION_OPEN, session.state());
      h.now = 5100;
      assertThrows(IllegalStateException.class, session::tick);
      assertTrue(h.lifecycle.contains("fence:session_open_timeout"));
      assertThrows(IllegalStateException.class, () -> h.keys.mac(
          PostAuthFrameCodec.Direction.PEER_TO_HUB, new byte[0]));
    }
    for (long now : new long[] {5100, 5101}) {
      Harness h = new Harness(); h.now = now;
      assertThrows(IllegalArgumentException.class, () -> h.absoluteSession(5100));
      assertCallerOwnsKeys(h);
    }
  }

  @Test void absoluteConstructionRejectsBoundsRollbackAndClockFailuresWithoutTakingKeys() {
    long[][] invalid = {{-1, 5000}, {99, 5100}, {0, -1}, {0, 0}, {0, 4999},
        {0, Long.MIN_VALUE}, {0, Long.MAX_VALUE}};
    for (long[] pair : invalid) {
      Harness h = new Harness(); h.now = pair[0];
      assertThrows(IllegalArgumentException.class, () -> h.absoluteSession(pair[1]));
      assertTrue(h.clockReads <= 1);
      assertCallerOwnsKeys(h);
    }
    for (Throwable failure : List.of(new IllegalStateException("clock"), new AssertionError("clock"))) {
      Harness h = new Harness(); h.clockFailure = failure;
      assertSame(failure, assertThrows(failure.getClass(), () -> h.absoluteSession(5000)));
      assertEquals(1, h.clockReads);
      assertCallerOwnsKeys(h);
    }
  }

  @Test void absoluteConstructionChecksReferencesGenerationAndOwnerBeforeClockOrLifecycle() {
    for (int invalid = 0; invalid < 12; invalid++) {
      Harness h = new Harness();
      h.keys = PostAuthSessionKeys.derive(Harness.bytes(0), h.transcript);
      int selected = invalid;
      assertThrows(RuntimeException.class, () -> new PostAuthPeerSession(
          selected == 0 ? null : h.transcript, selected == 1 ? null : h.keys,
          selected == 2 ? -1 : 7, selected == 3 ? null : () -> h.owner,
          selected == 4 ? null : h::clockNow, selected == 5 ? null : r -> true,
          selected == 6 ? null : r -> true, selected == 7 ? null : r -> {},
          selected == 8 ? null : raw -> CompletableFuture.completedFuture(null),
          selected == 9 ? null : frame -> {}, selected == 10 ? null : h.testLifecycle(),
          selected == 11 ? -1 : 5000));
      assertEquals(0, h.clockReads);
      assertCallerOwnsKeys(h);
    }
    for (boolean throwingGate : new boolean[] {false, true}) {
      Harness h = new Harness();
      h.keys = PostAuthSessionKeys.derive(Harness.bytes(0), h.transcript);
      assertThrows(IllegalStateException.class, () -> new PostAuthPeerSession(h.transcript, h.keys,
          7, () -> { if (throwingGate) throw new IllegalArgumentException("gate"); return false; },
          h::clockNow, r -> true, r -> true, r -> {}, raw -> CompletableFuture.completedFuture(null),
          frame -> {}, h.testLifecycle(), 5000));
      assertEquals(0, h.clockReads);
      assertCallerOwnsKeys(h);
    }
  }

  @Test void absoluteSuccessTransfersKeysAndLegacyConstructionFailureRetainsCleanupBehavior() {
    Harness h = new Harness(); PostAuthPeerSession session = h.absoluteSession(5000);
    assertDoesNotThrow(() -> h.keys.mac(PostAuthFrameCodec.Direction.PEER_TO_HUB, new byte[0]));
    session.close();
    assertThrows(IllegalStateException.class, () -> h.keys.mac(
        PostAuthFrameCodec.Direction.PEER_TO_HUB, new byte[0]));
    for (long now : new long[] {-1, Long.MAX_VALUE - 100}) {
      Harness legacy = new Harness(); legacy.now = now;
      assertThrows(IllegalStateException.class, legacy::session);
      assertEquals(1, legacy.clockReads);
      assertEquals(2, legacy.lifecycle.size());
      assertThrows(IllegalStateException.class, () -> legacy.keys.mac(
          PostAuthFrameCodec.Direction.PEER_TO_HUB, new byte[0]));
    }
    Harness legacy = new Harness(); legacy.clockFailure = new IllegalArgumentException("clock");
    assertThrows(IllegalStateException.class, legacy::session);
    assertEquals(List.of("fence:monotonic_clock_failed", "close:monotonic_clock_failed"), legacy.lifecycle);
    assertThrows(IllegalStateException.class, () -> legacy.keys.mac(
        PostAuthFrameCodec.Direction.PEER_TO_HUB, new byte[0]));
  }

  @Test void legacyDeadlineRemainsConstructionRelativeAndAbsoluteOriginCanEqualNow() {
    Harness legacy = new Harness(); legacy.now = 700;
    PostAuthPeerSession relative = legacy.session();
    assertEquals(5700, getLong(relative, "openDeadline"));
    assertEquals(1, legacy.clockReads);
    relative.close();
    Harness absolute = new Harness(); absolute.now = 700;
    PostAuthPeerSession original = absolute.absoluteSession(5700);
    assertEquals(5700, getLong(original, "openDeadline"));
    assertEquals(1, absolute.clockReads);
    original.close();
  }

  @Test void tickCadenceBoundariesReadClockOnceAndNeverReplayHistory() {
    for (long now : new long[] {499, 500, 999, 1000, 1500}) {
      Harness h = new Harness(); PostAuthPeerSession session = h.open();
      int reads = h.clockReads; h.now = now;
      session.tick();
      assertEquals(reads + 1, h.clockReads);
      if (now < 500) {
        assertEquals(1, session.queuedFrames()); assertEquals(500, getLong(session, "nextHeartbeat"));
      } else if (now < 1000) {
        assertEquals(2, session.queuedFrames()); assertEquals(1000, getLong(session, "nextHeartbeat"));
        assertEquals(1, h.sent.size()); // ACK remains in flight; queue includes exactly one heartbeat
      } else {
        assertEquals(PostAuthPeerSession.State.CLOSED, session.state());
        assertEquals(1, h.sent.size());
        assertEquals(List.of("fence:cadence_missed", "close:cadence_missed"), h.lifecycle);
      }
    }
  }

  @Test void tickIsOpenAnchoredAndWatchdogWinsOverMissedCadence() {
    Harness delayed = new Harness(); PostAuthPeerSession session = delayed.session();
    delayed.now = 123;
    session.receive(delayed.hubFrame(null, 0, "SESSION_OPEN",
        PostAuthFrameCodec.sessionOpenPayload(CONNECTION).getBytes(StandardCharsets.US_ASCII)));
    delayed.now = 622; session.tick(); assertEquals(1, session.queuedFrames());
    delayed.now = 623; session.tick(); assertEquals(2, session.queuedFrames());
    assertEquals(1123, getLong(session, "nextHeartbeat"));
    Harness expired = new Harness(); PostAuthPeerSession old = expired.open();
    int reads = expired.clockReads; expired.now = 2000;
    assertThrows(IllegalStateException.class, old::tick);
    assertEquals(reads + 1, expired.clockReads);
    assertEquals(List.of("fence:heartbeat_timeout", "close:heartbeat_timeout"), expired.lifecycle);
    assertEquals(1, expired.sent.size());
  }

  @Test void tickNextDeadlineOverflowFailsClosedWithoutAdmission() throws Exception {
    Harness h = new Harness(); PostAuthPeerSession session = h.open();
    setLong(session, "heartbeatDeadline", Long.MAX_VALUE);
    setLong(session, "nextHeartbeat", Long.MAX_VALUE - 100);
    h.now = Long.MAX_VALUE - 100;
    int reads = h.clockReads;
    assertThrows(IllegalStateException.class, session::tick);
    assertEquals(reads + 1, h.clockReads);
    assertEquals(List.of("fence:monotonic_deadline_overflow", "close:monotonic_deadline_overflow"), h.lifecycle);
    assertEquals(1, h.sent.size());
    assertEquals(1, session.nextOutboundSequence());
  }

  private static void assertCallerOwnsKeys(Harness h) {
    assertTrue(h.lifecycle.isEmpty());
    assertDoesNotThrow(() -> h.keys.mac(PostAuthFrameCodec.Direction.PEER_TO_HUB, new byte[0]));
    h.keys.close();
  }

  private static long getLong(Object target, String name) {
    try {
      Field field = target.getClass().getDeclaredField(name); field.setAccessible(true);
      return field.getLong(target);
    } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
  }

  @Test void tickProducesHeartbeatsEveryFiveHundredDespiteBusiness() {
    PostAuthPeerSession session = harness.open();
    for (int index = 1; index <= 3; index++) {
      harness.now = index * 500L - 1;
      session.sendBusiness("BODY_BIND_NOTICE", notice(), "", "", "", "", "");
      harness.now = index * 500L;
      session.tick();
    }
    assertEquals(7, session.queuedFrames()); // ACK + 3 business + 3 heartbeat
  }

  @Test void queueCapacityIncludesInflightAndFullAdmissionFencesBeforeCloseWithoutSequenceUse() {
    PostAuthPeerSession session = harness.open();
    for (int index = 0; index < 7; index++)
      session.sendBusiness("BODY_BIND_NOTICE", notice(), "", "", "", "", "");
    assertEquals(8, session.queuedFrames());
    assertEquals(8, session.nextOutboundSequence());
    session.sendBusiness("BODY_BIND_NOTICE", notice(), "", "", "", "", "");
    assertEquals(PostAuthPeerSession.State.CLOSED, session.state());
    assertEquals(8, session.nextOutboundSequence());
    assertBefore(harness.lifecycle, "fence:outbound_queue_full", "close:outbound_queue_full");
  }

  @Test void narrowServerCandidateUsesExactMetadataAndSharedOrderedQueue() {
    Harness server = new Harness(ControlAuth.Role.SERVER_AUTHORITY); PostAuthPeerSession session = server.open();
    assertEquals(ControlAuth.Role.SERVER_AUTHORITY, session.authenticatedRole());
    assertEquals("server", session.authenticatedPeerInstallId()); assertEquals(7, session.generation());
    byte[] payload = "{\"security\":\"luosandbox.control-auth.v1\",\"kind\":\"BODY_BOUND\",\"serverBootId\":\"50000000-0000-4000-8000-000000000001\",\"gameConnectionId\":\"60000000-0000-4000-8000-000000000001\",\"bodyId\":\"70000000-0000-4000-8000-000000000001\"}".getBytes(StandardCharsets.US_ASCII);
    session.sendBodyBoundCandidate("30000000-0000-4000-8000-000000000001", payload,
        "2026-10-23T00:00:05.000Z");
    assertEquals(2, session.queuedFrames()); assertEquals(2, session.nextOutboundSequence());
    server.completeSuccess(); server.runOwnerEvents();
    var frame=server.decodePeer(server.sent.get(1)); assertEquals(1,frame.sequence());
    assertEquals("BODY_BOUND_CANDIDATE",frame.messageKind()); assertEquals(CONNECTION,frame.recipientConnectionId());
    assertEquals("",frame.sourceInstallId()); assertEquals("",frame.sourceConnectionId());
    assertArrayEquals(payload,frame.payload());
  }

  @Test void candidateWrongRoleAndQueueFullFailClosedWithoutSequenceHole() {
    Harness client=new Harness(); PostAuthPeerSession clientSession=client.open();
    clientSession.sendBodyBoundCandidate("30000000-0000-4000-8000-000000000001",new byte[0],"2026-10-23T00:00:05.000Z");
    assertEquals(PostAuthPeerSession.State.CLOSED,clientSession.state());
    Harness full=new Harness(ControlAuth.Role.SERVER_AUTHORITY);PostAuthPeerSession session=full.open();
    for(int i=0;i<7;i++)session.sendBusiness("BODY_BOUND_CANDIDATE",candidate(),"30000000-0000-4000-8000-000000000001","","",CONNECTION,"2026-10-23T00:00:05.000Z");
    assertEquals(8,session.nextOutboundSequence());
    session.sendBodyBoundCandidate("30000000-0000-4000-8000-000000000001",candidate(),"2026-10-23T00:00:05.000Z");
    assertEquals(PostAuthPeerSession.State.CLOSED,session.state());assertEquals(8,session.nextOutboundSequence());
  }

  @Test void invalidAdmissionReplayGapAndSendFailuresAreFatalWithoutRetry() {
    PostAuthPeerSession session = harness.open();
    session.sendBusiness("UNKNOWN", new byte[0], "", "", "", "", "");
    assertEquals(PostAuthPeerSession.State.CLOSED, session.state());
    assertEquals(1, harness.sent.size());

    Harness replay = new Harness(); PostAuthPeerSession replaySession = replay.open();
    replaySession.receive(replay.hubHeartbeat(2));
    assertEquals(PostAuthPeerSession.State.CLOSED, replaySession.state());

    Harness failed = new Harness(); PostAuthPeerSession failedSession = failed.open();
    failed.completeFailure(); failed.runOwnerEvents();
    assertEquals(PostAuthPeerSession.State.CLOSED, failedSession.state());
    assertEquals(1, failed.sent.size());
  }

  @Test void callbacksPostToOwnerReentrantCloseCannotReviveAndPostFailureEmergencyCloses() {
    PostAuthPeerSession session = harness.open();
    harness.completeSuccess();
    session.close();
    harness.runOwnerEvents();
    assertEquals(PostAuthPeerSession.State.CLOSED, session.state());

    Harness rejected = new Harness(); rejected.acceptPosts = false; PostAuthPeerSession rejectedSession = rejected.open();
    rejected.completeSuccess();
    assertEquals(PostAuthPeerSession.State.OPEN, rejectedSession.state());
    assertFalse(rejected.lifecycle.stream().anyMatch(value -> value.startsWith("fence:")));
    rejected.runAbortEvents();
    assertEquals(PostAuthPeerSession.State.CLOSED, rejectedSession.state());
    assertEquals(0, rejectedSession.queuedFrames());
    assertBefore(rejected.lifecycle, "fence:owner_post_rejected", "close:owner_post_rejected");
  }

  @Test void inlineWrongThreadPostDefersCleanupAndAbortRejectionReportsUnknown() {
    Harness inline = new Harness(); inline.inlinePosts = true; PostAuthPeerSession inlineSession = inline.open();
    inline.owner = false;
    inline.completeSuccess();
    inline.owner = true;
    assertEquals(PostAuthPeerSession.State.OPEN, inlineSession.state());
    assertTrue(inline.lifecycle.isEmpty());
    inline.runAbortEvents();
    assertEquals(PostAuthPeerSession.State.CLOSED, inlineSession.state());
    assertBefore(inline.lifecycle, "fence:owner_post_rejected", "close:owner_post_rejected");

    Harness unknown = new Harness(); unknown.acceptPosts = false; unknown.acceptAborts = false;
    PostAuthPeerSession unknownSession = unknown.open();
    unknown.completeSuccess();
    assertEquals(PostAuthPeerSession.State.OPEN, unknownSession.state());
    assertEquals(List.of("unknown:owner_post_rejected"), unknown.diagnostics);
    assertTrue(unknown.lifecycle.isEmpty());
    unknownSession.close();
    assertEquals(PostAuthPeerSession.State.CLOSED, unknownSession.state());
  }

  @Test void senderAndCompletionRegistrationErrorsCloseAndPreserveFatalError() {
    Harness sender = new Harness(); sender.senderError = new AssertionError("sender fatal");
    AssertionError senderError = assertThrows(AssertionError.class, sender::open);
    assertEquals("sender fatal", senderError.getMessage());
    assertBefore(sender.lifecycle, "fence:send_start_failed", "close:send_start_failed");

    Harness registration = new Harness(); registration.registrationError = new LinkageError("registration fatal");
    LinkageError registrationError = assertThrows(LinkageError.class, registration::open);
    assertEquals("registration fatal", registrationError.getMessage());
    assertBefore(registration.lifecycle, "fence:completion_registration_failed",
        "close:completion_registration_failed");
  }

  @Test void ownerPostAndAbortErrorsNeverMutateOffOwnerAndReportUnknown() {
    Harness post = new Harness(); post.postError = new AssertionError("post fatal");
    PostAuthPeerSession postSession = post.open();
    assertDoesNotThrow(post::completeSuccess); // CompletionStage captures callback Error.
    assertEquals(PostAuthPeerSession.State.OPEN, postSession.state());
    assertTrue(post.lifecycle.isEmpty());
    post.runAbortEvents();
    assertEquals(PostAuthPeerSession.State.CLOSED, postSession.state());

    Harness abort = new Harness(); abort.acceptPosts = false; abort.abortError = new LinkageError("abort fatal");
    PostAuthPeerSession abortSession = abort.open();
    assertDoesNotThrow(abort::completeSuccess); // CompletionStage captures callback Error.
    assertEquals(PostAuthPeerSession.State.OPEN, abortSession.state());
    assertEquals(List.of("unknown:owner_post_rejected"), abort.diagnostics);
  }

  @Test void fenceAndPhysicalCloseErrorsCannotSkipKeysOrReviveAndDiagnosticsErrorsAreContained() {
    Harness fence = new Harness(); fence.fenceError = new AssertionError("fence fatal");
    PostAuthPeerSession fenceSession = fence.open();
    AssertionError error = assertThrows(AssertionError.class, fenceSession::close);
    assertEquals("fence fatal", error.getMessage());
    assertEquals(PostAuthPeerSession.State.CLOSED, fenceSession.state());
    assertTrue(fence.lifecycle.contains("close:owner_close"));
    assertThrows(IllegalStateException.class, () -> fence.keys.mac(
        PostAuthFrameCodec.Direction.PEER_TO_HUB, new byte[0]));

    Harness physical = new Harness(); physical.physicalError = new LinkageError("physical fatal");
    physical.diagnosticError = new AssertionError("diagnostic fatal");
    PostAuthPeerSession physicalSession = physical.open();
    assertThrows(LinkageError.class, physicalSession::close);
    assertEquals(PostAuthPeerSession.State.CLOSED, physicalSession.state());
    assertThrows(IllegalStateException.class, () -> physical.keys.mac(
        PostAuthFrameCodec.Direction.PEER_TO_HUB, new byte[0]));
    assertDoesNotThrow(physicalSession::close);
  }

  @Test void laterFatalCleanupErrorOutranksEarlierOrdinaryFailureAndRetainsSuppressedCause() {
    Harness harness = new Harness();
    harness.fenceRuntime = new IllegalStateException("ordinary fence failure");
    harness.physicalError = new LinkageError("fatal physical failure");
    PostAuthPeerSession session = harness.open();
    LinkageError fatal = assertThrows(LinkageError.class, session::close);
    assertEquals("fatal physical failure", fatal.getMessage());
    assertEquals(1, fatal.getSuppressed().length);
    assertEquals("ordinary fence failure", fatal.getSuppressed()[0].getMessage());
    assertEquals(PostAuthPeerSession.State.CLOSED, session.state());
    assertTrue(harness.lifecycle.contains("close:owner_close"));
    assertThrows(IllegalStateException.class, () -> harness.keys.mac(
        PostAuthFrameCodec.Direction.PEER_TO_HUB, new byte[0]));
  }

  @Test void observerFailureAndWrongThreadFailClosedWithoutWrongThreadCallbacks() {
    Harness observer = new Harness(ControlAuth.Role.SERVER_AUTHORITY); observer.observerFails = true;
    PostAuthPeerSession session = observer.open();
    session.receive(observer.hubBusiness(1));
    assertEquals(PostAuthPeerSession.State.CLOSED, session.state());

    Harness wrong = new Harness(); PostAuthPeerSession wrongSession = wrong.session();
    wrong.owner = false;
    assertThrows(IllegalStateException.class, wrongSession::tick);
    assertTrue(wrong.lifecycle.isEmpty());
  }

  @Test void rollbackDeadlineOverflowAndSequenceOverflowFailClosedWithoutWrap() throws Exception {
    Harness rollback = new Harness(); PostAuthPeerSession rollbackSession = rollback.open();
    rollback.now = 10; rollbackSession.tick(); rollback.now = 9;
    assertThrows(IllegalStateException.class, rollbackSession::tick);
    assertTrue(rollback.lifecycle.contains("fence:monotonic_clock_rollback"));

    Harness deadline = new Harness(); deadline.now = Long.MAX_VALUE - 100;
    assertThrows(IllegalStateException.class, deadline::session);
    assertTrue(deadline.lifecycle.contains("fence:monotonic_deadline_overflow"));

    Harness sequence = new Harness(); PostAuthPeerSession session = sequence.open();
    setLong(session, "nextOutbound", Long.MAX_VALUE);
    sequence.completeSuccess(); sequence.runOwnerEvents();
    session.sendBusiness("BODY_BIND_NOTICE", notice(), "", "", "", "", "");
    assertEquals(Long.MAX_VALUE, sequence.decodePeer(sequence.sent.get(1)).sequence());
    sequence.futures.get(1).complete(null); sequence.runOwnerEvents();
    session.sendBusiness("BODY_BIND_NOTICE", notice(), "", "", "", "", "");
    assertEquals(PostAuthPeerSession.State.CLOSED, session.state());
    assertTrue(sequence.lifecycle.contains("fence:outbound_sequence_overflow"));
  }

  private static void assertBefore(List<String> values, String first, String second) {
    assertTrue(values.indexOf(first) >= 0 && values.indexOf(first) < values.indexOf(second));
  }

  private static void setLong(Object target, String name, long value) throws Exception {
    Field field = target.getClass().getDeclaredField(name); field.setAccessible(true); field.setLong(target, value);
  }

  private static byte[] notice() {
    return ("{\"security\":\"luosandbox.control-auth.v1\",\"kind\":\"BODY_BIND_NOTICE\","
        + "\"serverBootId\":\"50000000-0000-4000-8000-000000000001\","
        + "\"gameConnectionId\":\"60000000-0000-4000-8000-000000000001\","
        + "\"bodyId\":\"70000000-0000-4000-8000-000000000001\","
        + "\"gameNonce\":\"AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8\"}")
        .getBytes(StandardCharsets.US_ASCII);
  }
  private static byte[] candidate() { return ("{\"security\":\"luosandbox.control-auth.v1\",\"kind\":\"BODY_BOUND\","
      + "\"serverBootId\":\"50000000-0000-4000-8000-000000000001\","
      + "\"gameConnectionId\":\"60000000-0000-4000-8000-000000000001\","
      + "\"bodyId\":\"70000000-0000-4000-8000-000000000001\"}").getBytes(StandardCharsets.US_ASCII); }

  private static final class Harness {
    long now;
    int clockReads;
    Throwable clockFailure;
    boolean owner = true, acceptPosts = true, acceptAborts = true, inlinePosts, observerFails;
    Error senderError, registrationError, postError, abortError, fenceError, physicalError, diagnosticError;
    RuntimeException fenceRuntime;
    final ArrayDeque<Runnable> ownerEvents = new ArrayDeque<>();
    final ArrayDeque<Runnable> abortEvents = new ArrayDeque<>();
    final ArrayList<byte[]> sent = new ArrayList<>();
    final ArrayList<CompletableFuture<Void>> futures = new ArrayList<>();
    final ArrayList<String> lifecycle = new ArrayList<>();
    final ArrayList<String> diagnostics = new ArrayList<>();
    final ControlAuth.Role role;
    final ControlAuth.Transcript transcript;
    PostAuthSessionKeys keys;

    Harness() { this(ControlAuth.Role.CLIENT_CONTROLLER); }
    Harness(ControlAuth.Role role) { this.role = role; this.transcript = transcript(role); }

    PostAuthPeerSession session() {
      keys = PostAuthSessionKeys.derive(bytes(role == ControlAuth.Role.SERVER_AUTHORITY ? 96 : 0), transcript);
      return new PostAuthPeerSession(transcript, keys, 7, () -> owner, this::clockNow,
          event -> { if (postError != null) throw postError; if (!acceptPosts) return false; if (inlinePosts) event.run(); else ownerEvents.add(event); return true; },
          abort -> { if (abortError != null) throw abortError; if (!acceptAborts) return false; abortEvents.add(abort); return true; },
          reason -> { diagnostics.add("unknown:" + reason); if (diagnosticError != null) throw diagnosticError; },
          raw -> { if (senderError != null) throw senderError; sent.add(raw.clone());
            var future = registrationError == null ? new CompletableFuture<Void>() : new RegistrationFailureFuture(registrationError);
            futures.add(future); return future; },
          frame -> { if (observerFails) throw new IllegalStateException("observer failed"); },
          new PostAuthPeerSession.Lifecycle() {
            @Override public void fence(String reason) { lifecycle.add("fence:" + reason);
              if (fenceError != null) throw fenceError; if (fenceRuntime != null) throw fenceRuntime; }
            @Override public void physicalClose(String reason) { lifecycle.add("close:" + reason); if (physicalError != null) throw physicalError; }
          });
    }

    long clockNow() {
      clockReads++;
      if (clockFailure instanceof Error error) throw error;
      if (clockFailure instanceof RuntimeException error) throw error;
      return now;
    }

    PostAuthPeerSession.Lifecycle testLifecycle() {
      return new PostAuthPeerSession.Lifecycle() {
        @Override public void fence(String reason) { lifecycle.add("fence:" + reason); }
        @Override public void physicalClose(String reason) { lifecycle.add("close:" + reason); }
      };
    }

    PostAuthPeerSession absoluteSession(long deadline) {
      keys = PostAuthSessionKeys.derive(bytes(0), transcript);
      return new PostAuthPeerSession(transcript, keys, 7, () -> owner, this::clockNow,
          r -> true, r -> true, reason -> {}, raw -> CompletableFuture.completedFuture(null),
          frame -> {}, testLifecycle(), deadline);
    }

    PostAuthPeerSession open() {
      PostAuthPeerSession session = session();
      session.receive(hubFrame(null, 0, "SESSION_OPEN",
          PostAuthFrameCodec.sessionOpenPayload(CONNECTION).getBytes(StandardCharsets.US_ASCII)));
      return session;
    }

    byte[] hubHeartbeat(long sequence) { return hubFrame(CONNECTION, sequence, "HEARTBEAT", "{}".getBytes(StandardCharsets.US_ASCII)); }
    byte[] hubBusiness(long sequence) {
      return hubFrame(CONNECTION, sequence, "BODY_BIND_ROUTE", notice());
    }

    byte[] hubFrame(String current, long sequence, String kind, byte[] payload) {
      try (PostAuthSessionKeys hubKeys = PostAuthSessionKeys.derive(
          bytes(role == ControlAuth.Role.SERVER_AUTHORITY ? 96 : 0), transcript)) {
        String route = kind.equals("BODY_BIND_ROUTE") ? "30000000-0000-4000-8000-000000000001" : "";
        String sourceInstall = kind.equals("BODY_BIND_ROUTE") ? "client" : "";
        String sourceConnection = kind.equals("BODY_BIND_ROUTE") ? "40000000-0000-4000-8000-000000000001" : "";
        String recipient = kind.equals("BODY_BIND_ROUTE") ? CONNECTION : "";
        var frame = new PostAuthFrameCodec.Frame(CONNECTION, transcript.challengeId(), sequence, kind,
            route, sourceInstall, sourceConnection, recipient, "", payload, "");
        return PostAuthFrameCodec.encode(frame, new PostAuthFrameCodec.Context(transcript, current),
            PostAuthFrameCodec.Direction.HUB_TO_PEER, hubKeys);
      }
    }

    PostAuthFrameCodec.Frame decodePeer(byte[] raw) {
      return PostAuthFrameCodec.decode(raw, new PostAuthFrameCodec.Context(transcript, CONNECTION),
          PostAuthFrameCodec.Direction.PEER_TO_HUB, keys);
    }
    void completeSuccess() { futures.get(0).complete(null); }
    void completeFailure() { futures.get(0).completeExceptionally(new RuntimeException("send failed")); }
    void runOwnerEvents() { while (!ownerEvents.isEmpty()) ownerEvents.removeFirst().run(); }
    void runAbortEvents() { while (!abortEvents.isEmpty()) abortEvents.removeFirst().run(); }

    private static ControlAuth.Transcript transcript(ControlAuth.Role role) {
      return new ControlAuth.Transcript(role,
          role == ControlAuth.Role.SERVER_AUTHORITY ? "server" : "client", "hub", "key",
          "LUO_CONTROL_POSTAUTH_V2", bytes(32), bytes(64), bytes(96),
          "10000000-0000-4000-8000-000000000001", Instant.parse("2026-10-23T00:00:00Z"),
          Instant.parse("2026-10-23T00:00:05Z"), bytes(128), bytes(160));
    }
    private static byte[] bytes(int start) { byte[] value = new byte[32]; for (int i=0;i<32;i++) value[i]=(byte)(start+i); return value; }
  }

  private static final class RegistrationFailureFuture extends CompletableFuture<Void> {
    private final Error failure;
    RegistrationFailureFuture(Error failure) { this.failure = failure; }
    @Override public CompletableFuture<Void> whenComplete(
        java.util.function.BiConsumer<? super Void, ? super Throwable> action) { throw failure; }
  }
}
