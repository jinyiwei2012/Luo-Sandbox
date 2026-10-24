package io.github.jinyiwei2012.luosandbox.transport;

import static org.junit.jupiter.api.Assertions.*;

import io.github.jinyiwei2012.luosandbox.security.ControlAuth;
import io.github.jinyiwei2012.luosandbox.security.PostAuthFrameCodec;
import io.github.jinyiwei2012.luosandbox.security.PostAuthSessionKeys;
import java.net.URI;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.BiConsumer;
import org.junit.jupiter.api.Test;

class V2JdkWebSocketAdapterTest {
  static class Client extends java.net.http.HttpClient {
    int calls;
    final V2JdkWebSocketAdapterTest.Builder builder = new V2JdkWebSocketAdapterTest.Builder();
    public WebSocket.Builder newWebSocketBuilder() { calls++; return builder; }
    public java.util.Optional<java.net.CookieHandler> cookieHandler() { throw new AssertionError(); }
    public java.util.Optional<Duration> connectTimeout() { throw new AssertionError(); }
    public Redirect followRedirects() { throw new AssertionError(); }
    public java.util.Optional<java.net.ProxySelector> proxy() { throw new AssertionError(); }
    public javax.net.ssl.SSLContext sslContext() { throw new AssertionError(); }
    public javax.net.ssl.SSLParameters sslParameters() { throw new AssertionError(); }
    public java.util.Optional<java.net.Authenticator> authenticator() { throw new AssertionError(); }
    public Version version() { throw new AssertionError(); }
    public java.util.Optional<java.util.concurrent.Executor> executor() { throw new AssertionError(); }
    public <T> java.net.http.HttpResponse<T> send(java.net.http.HttpRequest request,
        java.net.http.HttpResponse.BodyHandler<T> handler) { throw new AssertionError(); }
    public <T> CompletableFuture<java.net.http.HttpResponse<T>> sendAsync(java.net.http.HttpRequest request,
        java.net.http.HttpResponse.BodyHandler<T> handler) { throw new AssertionError(); }
    public <T> CompletableFuture<java.net.http.HttpResponse<T>> sendAsync(java.net.http.HttpRequest request,
        java.net.http.HttpResponse.BodyHandler<T> handler,
        java.net.http.HttpResponse.PushPromiseHandler<T> push) { throw new AssertionError(); }
  }
  static V2PeerConnector.Context context() {
    return new V2PeerConnector.Context(true, URI.create("ws://127.0.0.1:12345/control"),
        V2PeerConnector.PROFILE, ControlAuth.Role.SERVER_AUTHORITY, "peer", "hub", "key",
        new byte[32], new byte[32], new byte[32]);
  }
  static class Future extends CompletableFuture<WebSocket> {
    boolean throwRegistration;
    boolean callbackThenThrow;
    boolean refuseCancel;
    WebSocket callbackValue;
    public boolean cancel(boolean interrupt) { return !refuseCancel && super.cancel(interrupt); }
    public CompletableFuture<WebSocket> whenComplete(BiConsumer<? super WebSocket, ? super Throwable> action) {
      if (callbackThenThrow) action.accept(callbackValue, null);
      if (throwRegistration || callbackThenThrow) throw new AssertionError("registration");
      return super.whenComplete(action);
    }
  }
  static class Builder implements WebSocket.Builder {
    final Future future = new Future();
    WebSocket.Listener listener;
    URI endpoint;
    Duration timeout;
    String protocol;
    int calls;
    Runnable duringBuild = () -> {};
    boolean nullBuild;
    public WebSocket.Builder header(String name, String value) { fail("no headers"); return this; }
    public WebSocket.Builder connectTimeout(Duration value) { calls++; timeout = value; return this; }
    public WebSocket.Builder subprotocols(String first, String... rest) {
      calls++; assertEquals(0, rest.length); protocol = first; return this;
    }
    public CompletableFuture<WebSocket> buildAsync(URI uri, WebSocket.Listener value) {
      calls++; endpoint = uri; listener = value; duringBuild.run(); return nullBuild ? null : future;
    }
  }
  static class Raw implements WebSocket {
    String protocol = V2PeerConnector.PROFILE;
    final List<String> texts = new ArrayList<>();
    final List<String> trace = new ArrayList<>();
    final List<CompletableFuture<WebSocket>> sends = new ArrayList<>();
    CompletionStage<WebSocket> closing = new CompletableFuture<>();
    int requests;
    int aborts;
    boolean abortThrows;
    boolean closeThrows;
    Future sendOverride;
    public CompletableFuture<WebSocket> sendText(CharSequence text, boolean last) {
      assertTrue(last); texts.add(text.toString());
      if (sendOverride != null) return sendOverride;
      var f = new CompletableFuture<WebSocket>(); sends.add(f); return f;
    }
    public CompletableFuture<WebSocket> sendBinary(ByteBuffer data, boolean last) { throw new AssertionError(); }
    public CompletableFuture<WebSocket> sendPing(ByteBuffer data) { throw new AssertionError(); }
    public CompletableFuture<WebSocket> sendPong(ByteBuffer data) { throw new AssertionError(); }
    public CompletableFuture<WebSocket> sendClose(int code, String reason) {
      assertEquals(NORMAL_CLOSURE, code); assertEquals("", reason); trace.add("close");
      if (closeThrows) throw new AssertionError("close"); return (CompletableFuture<WebSocket>) closing;
    }
    public void request(long count) { assertEquals(1, count); requests++; }
    public String getSubprotocol() { return protocol; }
    public boolean isOutputClosed() { return false; }
    public boolean isInputClosed() { return false; }
    public void abort() { aborts++; trace.add("abort"); if (abortThrows) throw new AssertionError("abort"); }
  }
  static class Sink implements V2PeerConnector.Events {
    V2PeerConnector.Socket opened;
    int opens;
    int failures;
    int closed;
    int ping;
    int pong;
    String text;
    boolean throwFailure;
    boolean throwText;
    public void opened(V2PeerConnector.Socket socket) { opened = socket; opens++; }
    public void textFragment(V2PeerConnector.Socket socket, CharSequence data, boolean last) {
      assertSame(opened, socket); if (throwText) throw new AssertionError("text"); text = data.toString();
    }
    public void ping(V2PeerConnector.Socket socket, int length) { ping = length; }
    public void pong(V2PeerConnector.Socket socket, int length) { pong = length; }
    public void closed(V2PeerConnector.Socket socket) { closed++; }
    public void failed(V2PeerConnector.Socket socket) { failures++; if (throwFailure) throw new AssertionError("failed"); }
  }
  static V2PeerConnector.Socket connected(Builder builder, Raw raw, Sink sink) {
    var adapter = new V2JdkWebSocketAdapter(context(), () -> builder);
    var result = adapter.connect(sink).toCompletableFuture();
    builder.listener.onOpen(raw); builder.future.complete(raw); return result.join();
  }

  @Test void inertConstructorExactBuilderAndSingleUse() {
    Builder b = new Builder(); int[] suppliers = {0};
    var adapter = new V2JdkWebSocketAdapter(context(), () -> { suppliers[0]++; return b; });
    assertEquals(0, suppliers[0]); assertEquals(0, b.calls);
    adapter.connect(new Sink()); assertEquals(1, suppliers[0]); assertEquals(3, b.calls);
    assertEquals(context().endpoint(), b.endpoint); assertEquals(Duration.ofMillis(5000), b.timeout);
    assertEquals(V2PeerConnector.PROFILE, b.protocol);
    assertThrows(IllegalStateException.class, () -> adapter.connect(new Sink()));
  }
  @Test void externallyOwnedClientIsInertUntilConnectAndOnlyBuilderIsUsed() {
    Client client = new Client(); var adapter = new V2JdkWebSocketAdapter(context(), client);
    assertEquals(0, client.calls); adapter.connect(new Sink()); assertEquals(1, client.calls);
    assertEquals(3, client.builder.calls);
  }
  @Test void discoveryOrdersShareWrapperAndBuildDoesNotInventOpen() {
    for (boolean first : new boolean[] {true, false}) {
      Builder b = new Builder(); Raw r = new Raw(); Sink s = new Sink();
      var result = new V2JdkWebSocketAdapter(context(), () -> b).connect(s).toCompletableFuture();
      if (first) b.listener.onOpen(r); else { b.future.complete(r); assertEquals(0, s.opens); }
      if (first) b.future.complete(r); else b.listener.onOpen(r);
      b.listener.onOpen(r); assertEquals(1, s.opens); assertSame(result.join(), s.opened);
      assertEquals(0, r.requests); s.opened.requestOne(); assertEquals(1, r.requests);
    }
  }
  @Test void wrongProtocolMismatchAndLateSocketsAreContained() {
    for (int mode = 0; mode < 3; mode++) {
      Builder b = new Builder(); Raw r = new Raw(); Sink s = new Sink();
      var result = new V2JdkWebSocketAdapter(context(), () -> b).connect(s).toCompletableFuture();
      if (mode == 0) { r.protocol = ""; b.listener.onOpen(r); }
      if (mode == 1) { b.listener.onOpen(r); Raw other = new Raw(); b.future.complete(other); assertEquals(1, other.aborts); }
      if (mode == 2) { result.cancel(true); b.listener.onOpen(r); }
      assertTrue(r.aborts >= 1); assertEquals(1, s.failures); assertTrue(result.isCompletedExceptionally());
    }
  }
  @Test void cancellationBeforeBuildPublicationCancelsAndAbortsLateDiscovery() {
    Builder b = new Builder(); Raw r = new Raw(); Sink s = new Sink();
    // Reentrant supplier can observe the returned future only through onOpen/result completion;
    // cross-thread cancellation after connect is covered without assuming cancellation drains callbacks.
    b.future.refuseCancel = true;
    var result = new V2JdkWebSocketAdapter(context(), () -> b).connect(s).toCompletableFuture();
    assertTrue(result.cancel(true)); b.future.complete(r); b.listener.onOpen(r);
    assertEquals(2, r.aborts); assertEquals(1, s.failures);
  }
  @Test void cancellationDuringBuildBeforeFuturePublicationIsHonored() throws Exception {
    Builder b = new Builder(); Sink sink = new Sink(); Raw raw = new Raw();
    var adapter = new V2JdkWebSocketAdapter(context(), () -> b);
    var field = V2JdkWebSocketAdapter.class.getDeclaredField("result"); field.setAccessible(true);
    CompletableFuture<?> result = (CompletableFuture<?>) field.get(adapter);
    b.duringBuild = () -> assertTrue(result.cancel(true));
    adapter.connect(sink);
    assertTrue(b.future.isCancelled()); assertEquals(1, sink.failures);
    b.listener.onOpen(raw); assertEquals(1, raw.aborts);
  }
  @Test void successfulResultCancelDoesNotReplaceExplicitAbort() {
    Builder b = new Builder(); Raw raw = new Raw(); Sink sink = new Sink();
    var result = new V2JdkWebSocketAdapter(context(), () -> b).connect(sink).toCompletableFuture();
    b.future.complete(raw); assertFalse(result.cancel(true)); assertEquals(0, raw.aborts);
    result.join().abort(); assertEquals(1, raw.aborts);
  }
  @Test void sendIdentityAndRegistrationFailuresNeverReportSuccess() {
    for (int mode = 0; mode < 3; mode++) {
      Builder b = new Builder(); Raw raw = new Raw(); Sink sink = new Sink(); var socket = connected(b, raw, sink);
      if (mode == 0) {
        var sent = socket.send(new byte[] {65}); raw.sends.get(0).complete(new Raw());
        assertTrue(sent.toCompletableFuture().isCompletedExceptionally());
      } else {
        raw.sendOverride = new Future(); raw.sendOverride.throwRegistration = true;
        if (mode == 2) { raw.sendOverride.callbackThenThrow = true; raw.sendOverride.callbackValue = raw; }
        assertThrows(AssertionError.class, () -> socket.send(new byte[] {65}));
      }
      assertEquals(1, sink.failures); assertEquals(1, raw.aborts);
    }
  }
  @Test void wrongCloseIdentityAndNullStageLeaveGatePending() {
    for (boolean missing : new boolean[] {false, true}) {
      Builder b = new Builder(); Raw raw = new Raw(); var socket = connected(b, raw, new Sink());
      var gate = b.listener.onClose(raw, 1000, "").toCompletableFuture();
      if (missing) raw.closing = null;
      var output = socket.close();
      if (!missing) ((CompletableFuture<WebSocket>) raw.closing).complete(new Raw());
      assertTrue(output.toCompletableFuture().isCompletedExceptionally()); assertFalse(gate.isDone());
      assertEquals(0, raw.aborts);
    }
  }
  @Test void buildNullThrowRegistrationAndCallbackThenThrowFailPermanently() {
    for (int mode = 0; mode < 4; mode++) {
      Builder b = new Builder(); Sink s = new Sink(); Raw r = new Raw();
      if (mode == 0) b.nullBuild = true;
      if (mode == 1) b.duringBuild = () -> { throw new AssertionError(); };
      if (mode == 2) b.future.throwRegistration = true;
      if (mode == 3) { b.future.callbackThenThrow = true; b.future.callbackValue = r; }
      var result = new V2JdkWebSocketAdapter(context(), () -> b).connect(s).toCompletableFuture();
      assertEquals(1, s.failures);
      if (mode == 3) { assertNotNull(result.join()); assertEquals(1, r.aborts); }
      else assertTrue(result.isCompletedExceptionally());
    }
  }
  @Test void strictUtf8AndNoJsonRewriteOrDefaultCredit() {
    Builder b = new Builder(); Raw r = new Raw(); Sink s = new Sink(); var socket = connected(b, r, s);
    String value = " { \"x\" : \"é\" } "; socket.send(value.getBytes(StandardCharsets.UTF_8));
    assertEquals(value, r.texts.get(0)); socket.send("a".repeat(4096).getBytes(StandardCharsets.UTF_8));
    assertEquals(4096, r.texts.get(1).length());
    assertTrue(socket.send(new byte[] {(byte) 0xc0, (byte) 0x80}).toCompletableFuture().isCompletedExceptionally());
    assertEquals(2, r.texts.size()); assertEquals(0, r.requests);
    Builder extra = new Builder(); Raw x = new Raw();
    var xs = connected(extra, x, new Sink());
    assertTrue(xs.send(new byte[4097]).toCompletableFuture().isCompletedExceptionally()); assertTrue(x.texts.isEmpty());
  }
  @Test void controlsMetadataOnlyAndListenerErrorsNeverEscape() {
    Builder b = new Builder(); Raw r = new Raw(); Sink s = new Sink(); connected(b, r, s);
    ByteBuffer data = ByteBuffer.wrap(new byte[9]); data.position(2);
    b.listener.onPing(r, data); b.listener.onPong(r, data);
    assertEquals(7, s.ping); assertEquals(7, s.pong); assertEquals(2, data.position()); assertEquals(0, r.requests);
    s.throwText = true; s.throwFailure = true;
    assertDoesNotThrow(() -> b.listener.onText(r, "x", true)); assertEquals(1, s.failures); assertEquals(1, r.aborts);
    assertDoesNotThrow(() -> b.listener.onError(r, new AssertionError()));
    Builder binary = new Builder(); Raw raw = new Raw(); Sink sink = new Sink(); connected(binary, raw, sink);
    binary.listener.onBinary(raw, data, true); assertEquals(1, sink.failures); assertEquals(1, raw.aborts);
  }
  @Test void foreignListenerFailureContainsRuntimeAndErrorEvenWhenFailureSinkThrows() throws Exception {
    for (boolean error : new boolean[] {false, true}) {
      Builder b = new Builder(); Raw raw = new Raw();
      Sink sink = new Sink() {
        public void textFragment(V2PeerConnector.Socket socket, CharSequence text, boolean last) {
          if (error) throw new AssertionError("callback");
          throw new IllegalStateException("callback");
        }
      };
      sink.throwFailure = true; connected(b, raw, sink);
      var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
      Thread thread = new Thread(() -> {
        try { b.listener.onText(raw, "data", true); }
        catch (Throwable escaped) { failure.set(escaped); }
      });
      thread.start(); thread.join(2000); assertFalse(thread.isAlive()); assertNull(failure.get());
      assertEquals(1, sink.failures); assertEquals(1, raw.aborts);
    }
  }
  @Test void closeGateRequiresObservedExplicitCloseOrSuccessfulAbort() {
    for (boolean abort : new boolean[] {false, true}) {
      Builder b = new Builder(); Raw r = new Raw(); Sink s = new Sink(); var socket = connected(b, r, s);
      var gate = b.listener.onClose(r, 1000, "ignored").toCompletableFuture();
      assertEquals(1, s.closed); assertFalse(gate.isDone()); assertTrue(r.trace.isEmpty());
      if (abort) socket.abort();
      else { var output = socket.close(); assertFalse(gate.isDone()); ((CompletableFuture<WebSocket>) r.closing).complete(r); output.toCompletableFuture().join(); }
      assertTrue(gate.isDone()); assertEquals(1, s.closed);
    }
  }
  @Test void failedUnknownCloseAndAbortDoNotReleaseGateAndOwnerErrorPropagates() {
    for (int mode = 0; mode < 4; mode++) {
      Builder b = new Builder(); Raw r = new Raw(); Sink s = new Sink(); var socket = connected(b, r, s);
      var gate = b.listener.onClose(r, 1000, "").toCompletableFuture();
      if (mode == 0) { r.abortThrows = true; assertThrows(AssertionError.class, socket::abort); }
      if (mode == 1) { r.closeThrows = true; assertThrows(AssertionError.class, socket::close); }
      if (mode == 2) { Future f = new Future(); f.callbackThenThrow = true; f.callbackValue = r; r.closing = f; assertThrows(AssertionError.class, socket::close); }
      if (mode == 3) { var out = socket.close(); ((CompletableFuture<WebSocket>) r.closing).completeExceptionally(new AssertionError()); assertTrue(out.toCompletableFuture().isCompletedExceptionally()); }
      assertFalse(gate.isDone());
    }
  }

  @Test void actualCoreCryptoBootstrapAndCallbackCopyWithFakeJdk() throws Exception {
    Builder b = new Builder(); Raw raw = new Raw(); ArrayDeque<Runnable> queue = new ArrayDeque<>();
    var adapter = new V2JdkWebSocketAdapter(context(), () -> b);
    byte[] key = new byte[32]; byte[] peer = new byte[32]; peer[0] = 1; byte[] hub = new byte[32]; hub[0] = 2;
    Instant utc = Instant.parse("2026-10-24T00:00:00.000Z");
    var core = new V2PeerConnector(context(), () -> true,
        task -> { queue.add(task); return V2OwnerMailbox.EnqueueResult.ACCEPTED; }, () -> 100,
        () -> utc, () -> peer, new V2PeerConnector.CredentialOwner() {
          public byte[] copy() { return key.clone(); } public void close() {}
        }, adapter, () -> raw.trace.add("fence"), reason -> {});
    core.start(); b.listener.onOpen(raw); b.future.complete(raw); drain(queue);
    assertEquals(1, raw.texts.size()); raw.sends.get(0).complete(raw); drain(queue);
    String challenge = "11111111-1111-4111-8111-111111111111";
    var transcript = new ControlAuth.Transcript(ControlAuth.Role.SERVER_AUTHORITY, "peer", "hub", "key",
        V2PeerConnector.PROFILE, key, peer, hub, challenge, utc, utc.plusMillis(5000), key, key);
    String challengeText = json(Map.of("security", ControlAuth.DOMAIN, "kind", "CHALLENGE", "challengeId", challenge,
        "hubNonce", ControlAuth.b32(hub), "issuedAt", ControlAuth.formatUtc(utc),
        "expiresAt", ControlAuth.formatUtc(utc.plusMillis(5000)), "hubProof", proof(key, transcript, ControlAuth.Label.HUB_PROOF)));
    char[] callback = challengeText.toCharArray(); b.listener.onText(raw, CharBuffer.wrap(callback), true);
    java.util.Arrays.fill(callback, 'x'); drain(queue); assertEquals(2, raw.texts.size());
    raw.sends.get(1).complete(raw); drain(queue);
    b.listener.onText(raw, json(Map.of("security", ControlAuth.DOMAIN, "kind", "READY", "challengeId", challenge,
        "readyProof", proof(key, transcript, ControlAuth.Label.READY_PROOF))), true); drain(queue);
    String connection = "22222222-2222-4222-8222-222222222222";
    try (var keys = PostAuthSessionKeys.derive(key, transcript)) {
      byte[] open = PostAuthFrameCodec.encode(new PostAuthFrameCodec.Frame(connection, challenge, 0, "SESSION_OPEN",
          "", "", "", "", "", PostAuthFrameCodec.sessionOpenPayload(connection).getBytes(StandardCharsets.US_ASCII), ""),
          new PostAuthFrameCodec.Context(transcript, null), PostAuthFrameCodec.Direction.HUB_TO_PEER, keys);
      b.listener.onText(raw, new String(open, StandardCharsets.UTF_8), true); drain(queue);
      var ack = PostAuthFrameCodec.decode(raw.texts.get(2).getBytes(StandardCharsets.UTF_8),
          new PostAuthFrameCodec.Context(transcript, connection), PostAuthFrameCodec.Direction.PEER_TO_HUB, keys);
      assertEquals(0, ack.sequence()); assertEquals("HEARTBEAT", ack.messageKind());
    }
    raw.sends.get(2).complete(raw); drain(queue); assertEquals(V2PeerConnector.State.OPEN, core.state());
    var gate = b.listener.onClose(raw, 1000, "").toCompletableFuture(); assertFalse(gate.isDone());
    drain(queue); assertEquals(List.of("fence", "close"), raw.trace);
    ((CompletableFuture<WebSocket>) raw.closing).complete(raw); assertTrue(gate.isDone());
    core.tick(); drain(queue); assertEquals(V2PeerConnector.State.TERMINAL_API_CLOSED, core.state());
  }
  static void drain(ArrayDeque<Runnable> queue) {
    int count = 0; while (!queue.isEmpty()) { assertTrue(++count < 100); queue.remove().run(); }
  }
  static String proof(byte[] key, ControlAuth.Transcript t, ControlAuth.Label label) {
    return ControlAuth.b32(ControlAuth.hmac(key, ControlAuth.transcript(label, t)));
  }
  static String json(Map<String, String> fields) {
    var out = new StringBuilder("{"); fields.forEach((k, v) -> {
      if (out.length() > 1) out.append(','); out.append('"').append(k).append("\":\"").append(v).append('"');
    }); return out.append('}').toString();
  }
}
