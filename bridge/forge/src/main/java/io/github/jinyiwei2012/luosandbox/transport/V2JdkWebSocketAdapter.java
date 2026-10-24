package io.github.jinyiwei2012.luosandbox.transport;

import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;

/** Unwired JDK boundary. The supplied client, executor and client policy remain externally owned. */
final class V2JdkWebSocketAdapter implements V2PeerConnector.Dialer {
  private static final class Unavailable extends RuntimeException {
    Unavailable() { super("v2 physical transport unavailable", null, false, false); }
  }

  private final Object lock = new Object();
  private final V2PeerConnector.Context context;
  private final Supplier<WebSocket.Builder> builders;
  private final CompletableFuture<Void> closeGate = new CompletableFuture<>();
  // Trusted package callers must not externally complete/obtrude this adapter-owned future.
  private final CompletableFuture<V2PeerConnector.Socket> result = new CompletableFuture<>();
  private final WebSocket.Listener listener = new Listener();
  private V2PeerConnector.Events events;
  private CompletableFuture<WebSocket> build;
  private WebSocket raw;
  private Socket socket;
  private boolean started;
  private boolean terminal;
  private boolean opened;
  private boolean notified;
  private boolean abortClaimed;
  private boolean cancelClaimed;

  V2JdkWebSocketAdapter(V2PeerConnector.Context context, HttpClient client) {
    this.context = Objects.requireNonNull(context);
    Objects.requireNonNull(client);
    this.builders = client::newWebSocketBuilder;
  }

  V2JdkWebSocketAdapter(V2PeerConnector.Context context, Supplier<WebSocket.Builder> builders) {
    this.context = Objects.requireNonNull(context);
    this.builders = Objects.requireNonNull(builders);
  }

  @Override public CompletionStage<V2PeerConnector.Socket> connect(V2PeerConnector.Events sink) {
    Objects.requireNonNull(sink);
    synchronized (lock) {
      if (started) throw new IllegalStateException("single use");
      started = true;
      events = sink;
    }
    result.whenComplete((ignored, failure) -> { if (result.isCancelled()) terminate(); });
    try {
      WebSocket.Builder builder = Objects.requireNonNull(builders.get());
      if (!live()) return result;
      builder.connectTimeout(Duration.ofMillis(5000));
      if (!live()) return result;
      builder.subprotocols(V2PeerConnector.PROFILE);
      if (!live()) return result;
      CompletableFuture<WebSocket> pending = Objects.requireNonNull(
          builder.buildAsync(context.endpoint(), listener));
      boolean cancel;
      synchronized (lock) { build = pending; cancel = terminal && !cancelClaimed; if (cancel) cancelClaimed = true; }
      if (cancel) cancel(pending);
      // Register even after cancellation: cancellation is not late-socket containment.
      pending.whenComplete((found, failure) -> {
        try {
          if (failure != null || found == null) terminate();
          else {
            Socket bound = bind(found);
            if (bound != null && live()) result.complete(bound);
          }
        } catch (Throwable ignored) { terminate(); }
      });
    } catch (Throwable ignored) { terminate(); }
    return result;
  }

  private boolean live() { synchronized (lock) { return !terminal; } }

  /** External subprotocol access and every abort occur outside the identity lock. */
  private Socket bind(WebSocket found) {
    if (found == null) { terminate(); return null; }
    if (!live()) { abortOther(found); return null; }
    String protocol;
    try { protocol = found.getSubprotocol(); }
    catch (Throwable ignored) { terminate(); abortOther(found); return null; }
    if (!V2PeerConnector.PROFILE.equals(protocol)) {
      terminate(); abortOther(found); return null;
    }
    Socket bound;
    synchronized (lock) {
      if (terminal || raw != null && raw != found) bound = null;
      else {
        if (raw == null) { raw = found; socket = new Socket(found); }
        bound = socket;
      }
    }
    if (bound == null) { terminate(); abortOther(found); }
    return bound;
  }

  private Socket current(WebSocket found) {
    Socket bound;
    synchronized (lock) { bound = !terminal && raw == found ? socket : null; }
    if (bound == null) { terminate(); abortOther(found); }
    return bound;
  }

  private static void cancel(CompletableFuture<?> future) {
    try { future.cancel(true); } catch (Throwable ignored) { }
  }

  /** Nonrecursive arbitrary-thread containment; never owner cleanup or raw Throwable retention. */
  private void terminate() {
    V2PeerConnector.Events sink;
    Socket bound;
    CompletableFuture<WebSocket> pending;
    boolean notify;
    synchronized (lock) {
      terminal = true;
      notify = !notified;
      notified = true;
      sink = events; bound = socket; pending = cancelClaimed ? null : build;
      if (pending != null) cancelClaimed = true;
    }
    if (pending != null) cancel(pending);
    try { if (notify && sink != null) sink.failed(bound); }
    catch (Throwable ignored) { }
    finally {
      abortBound();
      result.completeExceptionally(new Unavailable());
    }
  }

  private void abortOther(WebSocket found) {
    if (found == null) return;
    boolean same;
    synchronized (lock) { same = found == raw; }
    if (same) abortBound();
    else try { found.abort(); } catch (Throwable ignored) { }
  }

  private void abortBound() {
    WebSocket found;
    synchronized (lock) {
      if (raw == null || abortClaimed) return;
      abortClaimed = true; found = raw;
    }
    try { found.abort(); closeGate.complete(null); }
    catch (Throwable ignored) { /* Unknown abort cannot release the close gate. */ }
  }

  private final class Socket implements V2PeerConnector.Socket {
    private final WebSocket identity;
    Socket(WebSocket identity) { this.identity = identity; }
    public String subprotocol() { return V2PeerConnector.PROFILE; }
    public CompletionStage<Void> send(byte[] bytes) {
      try {
        if (!live() || bytes == null || bytes.length == 0 || bytes.length > 4096) throw new Unavailable();
        String text = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString();
        return mapped(identity.sendText(text, true), false);
      } catch (Throwable failure) {
        terminate();
        if (failure instanceof Error error) throw error;
        return CompletableFuture.failedFuture(new Unavailable());
      }
    }
    public CompletionStage<Void> close() {
      try { return mapped(identity.sendClose(WebSocket.NORMAL_CLOSURE, ""), true); }
      catch (Throwable failure) {
        // An unknown close registration/call is not permission to release the private gate.
        if (failure instanceof Error error) throw error;
        return CompletableFuture.failedFuture(new Unavailable());
      }
    }
    private CompletionStage<Void> mapped(CompletionStage<WebSocket> stage, boolean closing) {
      CompletableFuture<Void> mapped = new CompletableFuture<>();
      class Observation {
        boolean registered;
        boolean observed;
        boolean good;
        void observed(WebSocket returned, boolean failed) {
          boolean publish;
          synchronized (this) {
            if (observed) return;
            observed = true; good = !failed && returned == identity; publish = registered;
          }
          if (publish) publish();
        }
        void publish() {
          if (good) {
            if (closing) closeGate.complete(null);
            mapped.complete(null);
          } else {
            mapped.completeExceptionally(new Unavailable());
            if (!closing) terminate();
          }
        }
      }
      Observation observation = new Observation();
      Objects.requireNonNull(stage).whenComplete((returned, failure) -> observation.observed(returned, failure != null));
      boolean publish;
      synchronized (observation) { observation.registered = true; publish = observation.observed; }
      if (publish) observation.publish();
      return mapped;
    }
    public void requestOne() {
      if (!live()) return;
      try { identity.request(1); }
      catch (Throwable failure) { terminate(); if (failure instanceof Error error) throw error; }
    }
    public void abort() {
      synchronized (lock) { terminal = true; }
      WebSocket found;
      synchronized (lock) {
        if (abortClaimed) return;
        abortClaimed = true; found = identity;
      }
      found.abort();
      closeGate.complete(null);
    }
  }

  private final class Listener implements WebSocket.Listener {
    @Override public void onOpen(WebSocket found) {
      try {
        Socket bound = bind(found);
        if (bound == null) return;
        boolean notify;
        synchronized (lock) { notify = !terminal && !opened; if (notify) opened = true; }
        if (notify) events.opened(bound);
      } catch (Throwable ignored) { terminate(); }
    }
    @Override public CompletionStage<?> onText(WebSocket found, CharSequence data, boolean last) {
      try { Socket bound = current(found); if (bound != null) events.textFragment(bound, data, last); }
      catch (Throwable ignored) { terminate(); }
      return null;
    }
    @Override public CompletionStage<?> onBinary(WebSocket found, ByteBuffer data, boolean last) {
      try { current(found); } catch (Throwable ignored) { }
      terminate(); return null;
    }
    @Override public CompletionStage<?> onPing(WebSocket found, ByteBuffer data) {
      try { Socket bound = current(found); if (bound != null) events.ping(bound, data.remaining()); }
      catch (Throwable ignored) { terminate(); }
      return null;
    }
    @Override public CompletionStage<?> onPong(WebSocket found, ByteBuffer data) {
      try { Socket bound = current(found); if (bound != null) events.pong(bound, data.remaining()); }
      catch (Throwable ignored) { terminate(); }
      return null;
    }
    @Override public CompletionStage<?> onClose(WebSocket found, int status, String reason) {
      try { Socket bound = current(found); if (bound != null) events.closed(bound); }
      catch (Throwable ignored) { terminate(); }
      return closeGate;
    }
    @Override public void onError(WebSocket found, Throwable error) {
      try { current(found); } catch (Throwable ignored) { }
      terminate();
    }
  }
}
