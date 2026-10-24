package io.github.jinyiwei2012.luosandbox.transport;

import static org.junit.jupiter.api.Assertions.*;

import io.github.jinyiwei2012.luosandbox.security.ControlAuth;
import io.github.jinyiwei2012.luosandbox.security.PostAuthFrameCodec;
import io.github.jinyiwei2012.luosandbox.security.PostAuthSessionKeys;
import io.github.jinyiwei2012.luosandbox.wire.StrictJson;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.junit.jupiter.api.Test;

class V2PeerConnectorTest {
  @Test void cleanupUnknownSurvivesSuccessfulApiClosure() {
    for (boolean credentialFailure : new boolean[] {false, true}) {
      Harness h = new Harness(true, ControlAuth.Role.SERVER_AUTHORITY); h.connected(true); h.complete(0);
      Runnable failure = () -> { throw new IllegalStateException("cleanup"); };
      if (credentialFailure) h.releaseAction = failure; else h.fenceAction = failure;
      h.connector.close(); h.wire.closing.complete(null); h.events.closed(h.wire); h.connector.tick();
      assertEquals(V2PeerConnector.State.TERMINAL_API_CLOSED, h.connector.state());
      assertTrue(h.connector.cleanupSnapshot().ownerUnknown());
      assertTrue(h.connector.cleanupSnapshot().inputClosed());
      assertTrue(h.connector.cleanupSnapshot().outputClosed());
      assertEquals(1, h.fences); assertEquals(1, h.releases); assertEquals(1, h.wire.closes);
    }
  }
  @Test void firstOwnerErrorWinsAfterAllIndependentCleanupAttempts() {
    Harness h = new Harness(true, ControlAuth.Role.SERVER_AUTHORITY); h.connected(true); h.complete(0);
    AssertionError first = new AssertionError("first");
    h.fenceAction = () -> { throw first; };
    h.releaseAction = () -> { throw new AssertionError("second"); };
    h.wire.closeAction = () -> { throw new AssertionError("third"); };
    assertSame(first, assertThrows(AssertionError.class, h.connector::close));
    assertEquals(1, h.fences); assertEquals(1, h.releases); assertEquals(1, h.wire.closes);
    assertTrue(h.connector.cleanupSnapshot().ownerUnknown());
    assertTrue(h.connector.cleanupSnapshot().physicalCloseFailed());
  }
  @Test void closeRegistrationErrorAndAsyncFailureRemainUnknown() {
    Harness h = new Harness(true, ControlAuth.Role.SERVER_AUTHORITY); h.connected(true); h.complete(0);
    h.wire.registrationError = true;
    assertThrows(AssertionError.class, h.connector::close);
    assertTrue(h.connector.cleanupSnapshot().ownerUnknown());
    Harness async = new Harness(true, ControlAuth.Role.SERVER_AUTHORITY); async.connected(true); async.complete(0);
    async.connector.close();
    async.wire.closing.completeExceptionally(new AssertionError("external"));
    assertFalse(async.connector.cleanupSnapshot().ownerUnknown());
    assertTrue(async.connector.cleanupSnapshot().physicalCloseFailed());
    async.connector.tick(); assertTrue(async.connector.cleanupSnapshot().ownerUnknown());
  }
  @Test void startExternalReentryErasesReturnedSecretAndNeverDials() {
    for (boolean atNonce : new boolean[] {false, true}) {
      Harness h = new Harness(true, ControlAuth.Role.SERVER_AUTHORITY);
      if (atNonce) h.nonceAction = h.connector::close; else h.copyAction = h.connector::close;
      h.connector.start();
      assertNull(h.events); assertArrayEquals(new byte[32], h.copied);
      assertArrayEquals(PEER_NONCE, h.suppliedNonce);
      assertNotEquals(V2PeerConnector.State.CONNECTING, h.connector.state());
      assertEquals(1, h.releases); assertEquals(1, h.fences);
    }
  }
  @Test void subprotocolReentryDoesNotSendHello() {
    Harness h = new Harness(true, ControlAuth.Role.SERVER_AUTHORITY);
    h.wire.profileAction = h.connector::close; h.connected(true);
    assertTrue(h.wire.frames.isEmpty()); assertEquals(1, h.fences);
  }
  @Test void constructorClockAndGateReentryCannotPublishSession() {
    for (boolean clock : new boolean[] {false, true}) {
      Harness h = new Harness(true, ControlAuth.Role.SERVER_AUTHORITY); h.connected(true); h.complete(0);
      var t = h.transcript(ControlAuth.Role.SERVER_AUTHORITY); h.challenge(t); h.complete(1);
      h.releaseAction = () -> {
        Runnable reenter = () -> {
          h.clockAction = () -> {}; h.gateAction = () -> {}; h.connector.close();
        };
        if (clock) h.clockAction = reenter; else h.gateAction = reenter;
      };
      h.ready(t);
      assertNotEquals(V2PeerConnector.State.WAIT_SESSION_OPEN, h.connector.state());
      assertEquals(1, h.fences); assertEquals(1, h.releases); assertEquals(1, h.wire.closes);
    }
  }
  @Test void zeroPeerAndHubNoncesAreRejectedBeforeDialOrProof() {
    Harness peer = new Harness(true, ControlAuth.Role.SERVER_AUTHORITY); peer.suppliedNonce = new byte[32];
    peer.connector.start(); assertNull(peer.events); assertEquals(1, peer.releases);
    Harness hub = new Harness(true, ControlAuth.Role.SERVER_AUTHORITY); hub.connected(true); hub.complete(0);
    var t = hub.transcript(ControlAuth.Role.SERVER_AUTHORITY);
    var zero = new ControlAuth.Transcript(t.peerRole(), t.peerInstallId(), t.hubInstallId(), t.keyId(),
        t.protocol(), t.modHash(), t.peerNonce(), new byte[32], t.challengeId(), t.issuedAt(), t.expiresAt(),
        t.configHash(), t.bindingHash());
    hub.challenge(zero); assertEquals(1, hub.wire.frames.size()); assertEquals(1, hub.fences);
  }
  @Test void strictProofContextAndDuplicateKeyRejectionsUseRealVerifier() {
    for (int mode = 0; mode < 5; mode++) {
      Harness h = new Harness(true, ControlAuth.Role.SERVER_AUTHORITY); h.connected(true); h.complete(0);
      var t = h.transcript(ControlAuth.Role.SERVER_AUTHORITY);
      if (mode == 0) t = h.transcript(ControlAuth.Role.CLIENT_CONTROLLER);
      if (mode == 1) t = new ControlAuth.Transcript(t.peerRole(), t.peerInstallId(), t.hubInstallId(),
          t.keyId(), t.protocol(), nonceBytes(9), t.peerNonce(), t.hubNonce(), t.challengeId(),
          t.issuedAt(), t.expiresAt(), t.configHash(), t.bindingHash());
      if (mode < 2) h.challenge(t);
      else if (mode == 2) h.text("{\"security\":\"x\",\"security\":\"x\"}");
      else {
        h.challenge(t); h.complete(1);
        h.text(message(Map.of("security", ControlAuth.DOMAIN, "kind", "READY", "challengeId", CHALLENGE,
            "readyProof", mode == 3 ? ControlAuth.b32(KEY) : proof(ControlAuth.Label.HUB_PROOF, t))));
      }
      assertEquals(1, h.fences, "mode " + mode); assertEquals(1, h.releases);
    }
  }
  @Test void utf8BoundaryIsBytesNotCharacters() {
    for (int extra : new int[] {0, 1}) {
      Harness h = new Harness(true, ControlAuth.Role.SERVER_AUTHORITY); h.connected(true); h.complete(0);
      h.events.textFragment(h.wire, "\u00e9".repeat(2048) + "a".repeat(extra), false); h.drain();
      assertEquals(extra, h.fences); h.connector.close();
    }
  }
  @Test void readyCaptureAtOriginalDeadlineCannotCreateFreshOpenWindow() {
    Harness h = new Harness(true, ControlAuth.Role.SERVER_AUTHORITY); h.connected(true); h.complete(0);
    var t = h.transcript(ControlAuth.Role.SERVER_AUTHORITY); h.challenge(t); h.complete(1);
    h.events.textFragment(h.wire, message(Map.of("security", ControlAuth.DOMAIN, "kind", "READY",
        "challengeId", CHALLENGE, "readyProof", proof(ControlAuth.Label.READY_PROOF, t))), true);
    int[] reads = {0}; h.mono = 5099;
    h.clockAction = () -> { if (++reads[0] == 4) h.mono = 5100; };
    h.drain();
    assertTrue(reads[0] >= 4); assertEquals(1, h.fences); assertEquals(1, h.releases);
    assertNotEquals(V2PeerConnector.State.WAIT_SESSION_OPEN, h.connector.state());
  }
  @Test void foreignInvalidationDuringCredentialReleaseDoesNotPublishSession() throws Exception {
    Harness h = new Harness(true, ControlAuth.Role.SERVER_AUTHORITY); h.connected(true); h.complete(0);
    var t = h.transcript(ControlAuth.Role.SERVER_AUTHORITY); h.challenge(t); h.complete(1);
    var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
    h.releaseAction = () -> {
      Thread foreign = new Thread(() -> {
        try { h.connector.publishStopped(); } catch (Throwable error) { failure.set(error); }
      });
      foreign.start();
      try { foreign.join(2000); } catch (InterruptedException error) { throw new AssertionError(error); }
      assertFalse(foreign.isAlive()); assertNull(failure.get());
      assertEquals(0, h.fences);
    };
    h.ready(t);
    assertEquals(1, h.fences); assertEquals(1, h.releases);
    assertNotEquals(V2PeerConnector.State.WAIT_SESSION_OPEN, h.connector.state());
  }
  static final byte[] KEY = nonceBytes(3);
  static final byte[] PEER_NONCE = nonceBytes(1);
  static final byte[] HUB_NONCE = nonceBytes(2);
  static byte[] nonceBytes(int value) { byte[] bytes = new byte[32]; bytes[0] = (byte) value; return bytes; }
  static final Instant ORIGIN = Instant.parse("2026-10-24T00:00:00.000Z");
  static final String CHALLENGE = "11111111-1111-4111-8111-111111111111";
  static final String CONNECTION = "22222222-2222-4222-8222-222222222222";
  static final class Wire implements V2PeerConnector.Socket {
    final List<byte[]> frames = new ArrayList<>();
    final List<CompletableFuture<Void>> futures = new ArrayList<>();
    final CompletableFuture<Void> closing = new CompletableFuture<>();
    int requests;
    int aborts;
    int closes;
    boolean unknown;
    Runnable profileAction = () -> {};
    Runnable closeAction = () -> {};
    boolean registrationError;
    public String subprotocol() { profileAction.run(); return V2PeerConnector.PROFILE; }
    public CompletionStage<Void> send(byte[] frame) {
      frames.add(frame);
      if (unknown) return null;
      var future = new CompletableFuture<Void>(); futures.add(future); return future;
    }
    public CompletionStage<Void> close() {
      closes++; closeAction.run();
      if (registrationError) return new CompletableFuture<Void>() {
        @Override public CompletableFuture<Void> whenComplete(
            java.util.function.BiConsumer<? super Void, ? super Throwable> action) {
          throw new AssertionError("registration");
        }
      };
      return closing;
    }
    public void requestOne() { requests++; }
    public void abort() { aborts++; }
  }
  static final class Harness {
    final ArrayDeque<Runnable> queue = new ArrayDeque<>();
    final Wire wire = new Wire();
    final CompletableFuture<V2PeerConnector.Socket> build = new CompletableFuture<>();
    V2PeerConnector.Events events;
    long mono = 100;
    Instant utc = ORIGIN;
    int calls;
    int fences;
    int releases;
    boolean owner = true;
    Runnable fenceAction = () -> {};
    Runnable releaseAction = () -> {};
    Runnable copyAction = () -> {};
    Runnable nonceAction = () -> {};
    Runnable clockAction = () -> {};
    Runnable gateAction = () -> {};
    byte[] copied;
    byte[] suppliedNonce = PEER_NONCE.clone();
    final V2PeerConnector connector;
    Harness(boolean enabled, ControlAuth.Role role) {
      connector = new V2PeerConnector(new V2PeerConnector.Context(enabled,
          URI.create("ws://127.0.0.1:12345/control"), V2PeerConnector.PROFILE, role,
          "peer", "hub", "key", KEY, KEY, KEY), () -> { calls++; gateAction.run(); return owner; },
          action -> { calls++; queue.add(action); return V2OwnerMailbox.EnqueueResult.ACCEPTED; },
          () -> { calls++; clockAction.run(); return mono; }, () -> { calls++; return utc; },
          () -> { calls++; nonceAction.run(); return suppliedNonce; }, new V2PeerConnector.CredentialOwner() {
            public byte[] copy() { calls++; copied = KEY.clone(); copyAction.run(); return copied; }
            public void close() { releases++; releaseAction.run(); }
          }, listener -> { calls++; events = listener; return build; },
          () -> { fences++; fenceAction.run(); }, reason -> {});
    }
    void drain() {
      int turns = 0;
      while (!queue.isEmpty()) {
        assertTrue(++turns < 100, "bounded pump"); queue.remove().run();
      }
    }
    void connected(boolean buildFirst) {
      connector.start();
      if (buildFirst) { build.complete(wire); drain(); events.opened(wire); }
      else { events.opened(wire); drain(); build.complete(wire); }
      drain();
    }
    void complete(int send) { wire.futures.get(send).complete(null); drain(); }
    void text(String text) { events.textFragment(wire, text, true); drain(); }
    ControlAuth.Transcript transcript(ControlAuth.Role role) {
      return new ControlAuth.Transcript(role, "peer", "hub", "key", V2PeerConnector.PROFILE,
          KEY, PEER_NONCE, HUB_NONCE, CHALLENGE, ORIGIN, ORIGIN.plusMillis(5000), KEY, KEY);
    }
    void challenge(ControlAuth.Transcript t) {
      text(message(Map.of("security", ControlAuth.DOMAIN, "kind", "CHALLENGE",
          "challengeId", CHALLENGE, "hubNonce", ControlAuth.b32(t.hubNonce()),
          "issuedAt", ControlAuth.formatUtc(t.issuedAt()), "expiresAt", ControlAuth.formatUtc(t.expiresAt()),
          "hubProof", proof(ControlAuth.Label.HUB_PROOF, t))));
    }
    void ready(ControlAuth.Transcript t) {
      text(message(Map.of("security", ControlAuth.DOMAIN, "kind", "READY",
          "challengeId", CHALLENGE, "readyProof", proof(ControlAuth.Label.READY_PROOF, t))));
    }
    void open(ControlAuth.Transcript t) {
      try (PostAuthSessionKeys keys = PostAuthSessionKeys.derive(KEY, t)) {
        byte[] raw = PostAuthFrameCodec.encode(new PostAuthFrameCodec.Frame(CONNECTION, CHALLENGE,
            0, "SESSION_OPEN", "", "", "", "", "",
            PostAuthFrameCodec.sessionOpenPayload(CONNECTION).getBytes(StandardCharsets.US_ASCII), ""),
            new PostAuthFrameCodec.Context(t, null), PostAuthFrameCodec.Direction.HUB_TO_PEER, keys);
        text(new String(raw, StandardCharsets.UTF_8));
      }
    }
  }
  static String proof(ControlAuth.Label label, ControlAuth.Transcript t) {
    return ControlAuth.b32(ControlAuth.hmac(KEY, ControlAuth.transcript(label, t)));
  }
  static String message(Map<String, String> values) {
    StringBuilder out = new StringBuilder("{");
    values.forEach((k, v) -> {
      if (out.length() > 1) out.append(','); out.append('"').append(k).append("\":\"").append(v).append('"');
    });
    return out.append('}').toString();
  }

  @Test void disabledConstructionAndStartMakeZeroDependencyCalls() {
    Harness h = new Harness(false, ControlAuth.Role.SERVER_AUTHORITY);
    assertEquals(0, h.calls); h.connector.start(); assertEquals(0, h.calls);
    assertThrows(IllegalStateException.class, h.connector::start); assertEquals(0, h.calls);
  }
  @Test void enabledStartIsOwnerFirst() {
    Harness h = new Harness(true, ControlAuth.Role.SERVER_AUTHORITY); h.owner = false;
    assertThrows(IllegalStateException.class, h.connector::start);
    assertEquals(1, h.calls); assertEquals(0, h.releases); assertNull(h.events);
  }
  @Test void realCryptoBothRolesEarlyReadyAndOpenRetainWireOrder() throws Exception {
    for (ControlAuth.Role role : new ControlAuth.Role[] {
        ControlAuth.Role.SERVER_AUTHORITY, ControlAuth.Role.CLIENT_CONTROLLER}) {
      Harness h = new Harness(true, role); h.connected(role == ControlAuth.Role.SERVER_AUTHORITY);
      assertEquals(1, h.wire.frames.size());
      Map<String, Object> hello = StrictJson.decodeObject(h.wire.frames.get(0));
      assertEquals(role.name(), hello.get("peerRole"));
      h.complete(0);
      ControlAuth.Transcript t = h.transcript(role); h.challenge(t);
      assertEquals(2, h.wire.frames.size());
      Map<String, Object> peerProof = StrictJson.decodeObject(h.wire.frames.get(1));
      assertTrue(ControlAuth.verify(KEY, ControlAuth.Label.PEER_PROOF, t, (String) peerProof.get("peerProof")));
      h.ready(t); h.open(t);
      assertEquals(0, h.releases); assertEquals(2, h.wire.frames.size());
      h.complete(1);
      assertEquals(V2PeerConnector.State.OPEN, h.connector.state());
      assertEquals(1, h.releases); assertEquals(3, h.wire.frames.size());
      try (PostAuthSessionKeys keys = PostAuthSessionKeys.derive(KEY, t)) {
        PostAuthFrameCodec.Frame ack = PostAuthFrameCodec.decode(h.wire.frames.get(2),
            new PostAuthFrameCodec.Context(t, CONNECTION), PostAuthFrameCodec.Direction.PEER_TO_HUB, keys);
        assertEquals(0, ack.sequence()); assertEquals("HEARTBEAT", ack.messageKind());
        assertArrayEquals(new byte[] {'{', '}'}, ack.payload());
      }
      h.complete(2); h.connector.close();
      assertEquals(1, h.fences); assertEquals(1, h.wire.closes);
      h.wire.closing.complete(null); h.connector.tick(); h.drain();
      assertEquals(V2PeerConnector.State.STOPPING, h.connector.state());
      h.events.closed(h.wire); h.connector.tick();
      assertEquals(V2PeerConnector.State.TERMINAL_API_CLOSED, h.connector.state());
    }
  }
  @Test void originalHandshakeDeadlineEqualityAndRollbackFailClosed() {
    for (int mode = 0; mode < 4; mode++) {
      Harness h = new Harness(true, ControlAuth.Role.SERVER_AUTHORITY); h.connected(true); h.complete(0);
      if (mode == 0) h.mono = 5100;
      if (mode == 1) h.mono = 99;
      if (mode == 2) h.utc = ORIGIN.minusMillis(1);
      if (mode == 3) h.utc = ORIGIN.plusMillis(5000);
      h.challenge(h.transcript(ControlAuth.Role.SERVER_AUTHORITY));
      assertEquals(1, h.fences, "fence mode " + mode);
      assertEquals(1, h.releases, "release mode " + mode);
      if (mode == 1) assertEquals(1, h.wire.aborts);
      else assertEquals(1, h.wire.closes, "close mode " + mode);
    }
  }
  @Test void mismatchAndLateBuildSocketsAreAborted() {
    Harness h = new Harness(true, ControlAuth.Role.SERVER_AUTHORITY); h.connector.start();
    h.events.opened(h.wire); Wire other = new Wire(); h.build.complete(other); h.drain();
    assertEquals(1, other.aborts); assertEquals(1, h.wire.aborts); assertEquals(1, h.fences);
    Harness late = new Harness(true, ControlAuth.Role.SERVER_AUTHORITY); late.connector.start();
    late.connector.close(); late.connector.publishStopped(); late.build.complete(late.wire);
    assertEquals(1, late.wire.aborts);
  }
  @Test void unknownPhysicalFlightWaitsOriginalCloseDeadlineThenAborts() {
    Harness h = new Harness(true, ControlAuth.Role.SERVER_AUTHORITY); h.wire.unknown = true;
    h.connected(true); assertEquals(0, h.wire.closes); assertEquals(1, h.fences);
    h.mono = 1099; h.connector.tick(); assertEquals(0, h.wire.closes);
    h.mono = 1100; h.connector.tick();
    assertEquals(V2PeerConnector.State.TERMINAL_UNKNOWN, h.connector.state());
    assertEquals(1, h.wire.aborts); assertEquals(0, h.wire.closes);
  }
  @Test void fragmentsLimitsAndSplitSurrogatesUseActualUtf8() {
    for (int mode = 0; mode < 6; mode++) {
      Harness h = new Harness(true, ControlAuth.Role.SERVER_AUTHORITY); h.connected(true); h.complete(0);
      if (mode == 0 || mode == 1) {
        h.events.textFragment(h.wire, "a".repeat(mode == 0 ? 4096 : 4097), false); h.drain();
        assertEquals(mode == 0 ? 0 : 1, h.fences);
      } else if (mode == 2 || mode == 3) {
        for (int i = 0; i < (mode == 2 ? 64 : 65); i++) {
          h.events.textFragment(h.wire, "", false); h.drain();
        }
        assertEquals(mode == 2 ? 0 : 1, h.fences);
      } else {
        h.events.textFragment(h.wire, "\uD83D", false); h.drain();
        h.events.textFragment(h.wire, mode == 4 ? "\uDE00" : "x", false); h.drain();
        assertEquals(mode == 4 ? 0 : 1, h.fences);
      }
      h.connector.close();
    }
  }
  @Test void assemblyOriginalDeadlineAndCleanupReentrancy() {
    Harness h = new Harness(true, ControlAuth.Role.SERVER_AUTHORITY); h.connected(true); h.complete(0);
    h.events.textFragment(h.wire, "{", false); h.drain();
    h.fenceAction = () -> { h.connector.close(); throw new AssertionError("fence"); };
    h.mono = 5100; h.connector.tick();
    assertThrows(AssertionError.class, h::drain);
    assertEquals(1, h.fences); assertEquals(1, h.releases); assertEquals(1, h.wire.closes);
  }
  @Test void stoppedForeignContainmentDoesNotPerformOwnerCleanup() {
    Harness h = new Harness(true, ControlAuth.Role.SERVER_AUTHORITY); h.connected(true);
    h.owner = false; h.connector.publishStopped();
    assertEquals(1, h.wire.aborts); assertEquals(0, h.fences); assertEquals(0, h.releases);
  }
  @Test void readyExpiryRetainedDuringProofDoesNotRefreshChallengeDeadline() {
    Harness h = new Harness(true, ControlAuth.Role.SERVER_AUTHORITY); h.connected(true); h.complete(0);
    var t = h.transcript(ControlAuth.Role.SERVER_AUTHORITY); h.challenge(t); h.ready(t);
    h.mono = 5100; h.complete(1);
    assertEquals(1, h.fences); assertEquals(0, h.wire.frames.size() - 2);
    assertEquals(1, h.releases);
  }
  @Test void openUsesAbsoluteReadyVerificationDeadline() {
    Harness h = new Harness(true, ControlAuth.Role.SERVER_AUTHORITY); h.connected(true); h.complete(0);
    var t = h.transcript(ControlAuth.Role.SERVER_AUTHORITY); h.challenge(t); h.complete(1);
    h.mono = 200; h.ready(t);
    assertEquals(V2PeerConnector.State.WAIT_SESSION_OPEN, h.connector.state());
    h.mono = 5200; h.connector.tick(); h.drain();
    assertEquals(1, h.fences); assertEquals(1, h.wire.closes); assertEquals(1, h.releases);
  }
  @Test void macValidApplicationFrameIsRejectedWithoutAuthority() {
    Harness h = new Harness(true, ControlAuth.Role.SERVER_AUTHORITY); h.connected(true); h.complete(0);
    var t = h.transcript(ControlAuth.Role.SERVER_AUTHORITY); h.challenge(t); h.complete(1);
    h.ready(t); h.open(t); h.complete(2);
    // A second SESSION_OPEN is codec-valid but not an admitted application operation.
    h.open(t);
    assertEquals(1, h.fences); assertEquals(1, h.wire.closes); assertEquals(1, h.releases);
  }
  @Test void closeNeverOverlapsUnsettledSessionSend() {
    Harness h = new Harness(true, ControlAuth.Role.SERVER_AUTHORITY); h.connected(true); h.complete(0);
    var t = h.transcript(ControlAuth.Role.SERVER_AUTHORITY); h.challenge(t); h.complete(1);
    h.ready(t); h.open(t); h.connector.close();
    assertEquals(0, h.wire.closes); assertEquals(1, h.fences);
    h.wire.futures.get(2).complete(null); h.drain(); h.connector.tick();
    assertEquals(1, h.wire.closes); assertEquals(1, h.releases);
  }
  @Test void callbackOnForeignThreadCopiesBeforeReturnAndOwnerAloneCleans() throws Exception {
    Harness h = new Harness(true, ControlAuth.Role.SERVER_AUTHORITY); h.connected(true); h.complete(0);
    var error = new java.util.concurrent.atomic.AtomicReference<Throwable>();
    char[] input = {'{'};
    Thread thread = new Thread(() -> {
      try { h.events.textFragment(h.wire, java.nio.CharBuffer.wrap(input), false); input[0] = 'x'; }
      catch (Throwable failure) { error.set(failure); }
    });
    thread.start(); thread.join(2000); assertFalse(thread.isAlive()); assertNull(error.get());
    assertEquals(0, h.fences); h.drain();
    h.connector.close(); assertEquals(1, h.fences); assertEquals(1, h.releases);
  }
  @Test void installedSessionFenceReentryDefersPhysicalCloseToOutermostEnvelope() {
    Harness h = new Harness(true, ControlAuth.Role.SERVER_AUTHORITY); h.connected(true); h.complete(0);
    var t = h.transcript(ControlAuth.Role.SERVER_AUTHORITY); h.challenge(t); h.complete(1); h.ready(t);
    h.fenceAction = () -> { h.connector.close(); assertEquals(0, h.wire.closes); };
    h.connector.close(); assertEquals(1, h.wire.closes); assertEquals(1, h.fences);
  }
  @Test void credentialReleaseReentryCannotInstallAfterCleanup() {
    Harness h = new Harness(true, ControlAuth.Role.SERVER_AUTHORITY); h.connected(true); h.complete(0);
    var t = h.transcript(ControlAuth.Role.SERVER_AUTHORITY); h.challenge(t); h.complete(1);
    h.releaseAction = h.connector::close; h.ready(t);
    assertEquals(V2PeerConnector.State.STOPPING, h.connector.state());
    assertEquals(1, h.fences); assertEquals(1, h.releases); assertEquals(1, h.wire.closes);
  }
}
