package io.github.jinyiwei2012.luosandbox.transport;

import io.github.jinyiwei2012.luosandbox.security.ControlAuth;
import io.github.jinyiwei2012.luosandbox.wire.StrictJson;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.ArrayDeque;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** Disabled-by-default outbound local transport. This slice authenticates and carries heartbeats only. */
public final class ControlWebSocketTransport implements AutoCloseable {
  public enum State { NOT_STARTED, CONNECTING, PENDING, AUTHENTICATED, CLOSED }
  public enum StopReason { DISABLED, CLOSED, PROTOCOL_REJECTED, AUTH_REJECTED, BACKPRESSURE, DISCONNECTED, HEARTBEAT_TIMEOUT }
  public interface Listener { void onState(State state, StopReason reason); }
  interface Socket { String subprotocol(); CompletableFuture<?> sendText(String text); void request(long count); void close(StopReason reason); void abort(); }
  interface Connector { CompletableFuture<Socket> connect(ControlTransportConfig config, Callbacks callbacks); }
  interface Ticker { long nanoTime(); }
  interface Scheduler { Task schedule(Runnable task, long delayMillis); Task periodic(Runnable task, long initialDelayMillis, long periodMillis); void shutdown(); }
  interface Task { void cancel(); }
  interface Callbacks { void opened(Socket socket); void text(CharSequence text, boolean last); void binary(); void closed(); void failed(); }

  private static final int SECURITY_LIMIT = 4_096, FRAME_LIMIT = 65_536, QUEUE_LIMIT = 64;
  private static final long HANDSHAKE_MS = 5_000, HEARTBEAT_MS = 500, WATCHDOG_MS = 2_000;
  private final ControlTransportConfig config;
  private final Connector connector;
  private final Clock clock;
  private final Ticker ticker;
  private final Scheduler scheduler;
  private final Listener listener;
  private final SecureRandom random;
  private final StringBuilder fragments = new StringBuilder();
  private State state = State.NOT_STARTED;
  private StopReason reason = StopReason.DISABLED;
  private Socket socket;
  private ControlAuth.Transcript transcript;
  private byte[] peerNonce;
  private long handshakeDeadlineNanos, lastInboundNanos, generation, outboundSequence, lastInboundSequence = -1;
  private Instant lastUtc;
  private final ArrayDeque<SendEntry> pendingSends = new ArrayDeque<>();
  private SendEntry inFlight;
  private Task handshakeTask, heartbeatTask, watchdogTask;

  public ControlWebSocketTransport(ControlTransportConfig config, Listener listener) {
    this(config, new JdkConnector(), Clock.systemUTC(), System::nanoTime, new ExecutorScheduler(), listener, new SecureRandom());
  }

  ControlWebSocketTransport(ControlTransportConfig config, Connector connector, Clock clock, Ticker ticker,
      Scheduler scheduler, Listener listener, SecureRandom random) {
    this.config = Objects.requireNonNull(config); this.connector = Objects.requireNonNull(connector);
    this.clock = Objects.requireNonNull(clock); this.ticker = Objects.requireNonNull(ticker);
    this.scheduler = Objects.requireNonNull(scheduler); this.listener = Objects.requireNonNull(listener);
    this.random = Objects.requireNonNull(random);
  }

  public synchronized boolean start() {
    if (!config.enabled() || state != State.NOT_STARTED) return false;
    transition(State.CONNECTING, StopReason.DISABLED);
    long current = ++generation;
    connector.connect(config, callbacks(current)).whenComplete((ignored, error) -> { if (error != null) failIfCurrent(current, StopReason.DISCONNECTED); });
    return true;
  }
  public synchronized State state() { return state; }
  public synchronized StopReason reason() { return reason; }
  public synchronized boolean sendHeartbeat() { return state == State.AUTHENTICATED && send(heartbeatJson(++outboundSequence)); }
  @Override public void close() { stop(StopReason.CLOSED); }

  private Callbacks callbacks(long current) {
    return new Callbacks() {
      @Override public void opened(Socket value) { connected(current, value); }
      @Override public void text(CharSequence text, boolean last) { receivedFragment(current, text, last); }
      @Override public void binary() { failIfCurrent(current, StopReason.PROTOCOL_REJECTED); }
      @Override public void closed() { failIfCurrent(current, StopReason.DISCONNECTED); }
      @Override public void failed() { failIfCurrent(current, StopReason.DISCONNECTED); }
    };
  }
  private synchronized void connected(long current, Socket value) {
    if (current != generation || state != State.CONNECTING) { value.abort(); return; }
    if (!ControlTransportConfig.PROTOCOL.equals(value.subprotocol())) { value.abort(); stop(StopReason.PROTOCOL_REJECTED); return; }
    socket = value; peerNonce = new byte[32]; random.nextBytes(peerNonce);
    long now = ticker.nanoTime(); handshakeDeadlineNanos = now + TimeUnit.MILLISECONDS.toNanos(HANDSHAKE_MS);
    if (((now ^ handshakeDeadlineNanos) & (TimeUnit.MILLISECONDS.toNanos(HANDSHAKE_MS) ^ handshakeDeadlineNanos)) < 0) { stop(StopReason.AUTH_REJECTED); return; }
    acceptUtc(currentUtc()); transition(State.PENDING, StopReason.DISABLED);
    handshakeTask = scheduler.schedule(() -> failIfExpired(current), HANDSHAKE_MS);
    if (!send(helloJson())) stop(StopReason.BACKPRESSURE);
    value.request(1);
  }
  private synchronized void receivedFragment(long current, CharSequence text, boolean last) {
    if (current != generation || state == State.CLOSED) return;
    if (text.length() > FRAME_LIMIT - fragments.length()) { stop(StopReason.PROTOCOL_REJECTED); return; }
    fragments.append(text);
    if (!last) { socket.request(1); return; }
    if (!validScalars(fragments)) { fragments.setLength(0); stop(StopReason.PROTOCOL_REJECTED); return; }
    int length = StandardCharsets.UTF_8.encode(fragments.toString()).remaining();
    if (length > FRAME_LIMIT || (state == State.PENDING && length > SECURITY_LIMIT)) { fragments.setLength(0); stop(StopReason.PROTOCOL_REJECTED); return; }
    byte[] bytes = fragments.toString().getBytes(StandardCharsets.UTF_8); fragments.setLength(0);
    try { receive(bytes); } catch (RuntimeException | StrictJson.DecodeException error) { stop(state == State.PENDING ? StopReason.AUTH_REJECTED : StopReason.PROTOCOL_REJECTED); }
    if (state != State.CLOSED && socket != null) socket.request(1);
  }
  private void receive(byte[] bytes) throws StrictJson.DecodeException {
    Map<String, Object> message = StrictJson.decodeObject(bytes); depth(message, 1);
    String kind = string(message, "kind");
    if (state == State.PENDING) pending(kind, message);
    else if (state == State.AUTHENTICATED && "HEARTBEAT".equals(kind)) inboundHeartbeat(message);
    else throw new IllegalArgumentException();
  }
  private void pending(String kind, Map<String, Object> message) {
    Instant observed = currentUtc();
    if (expired()) throw new IllegalArgumentException();
    if ("CHALLENGE".equals(kind) && transcript == null) {
      exact(message, "security", "kind", "challengeId", "hubNonce", "issuedAt", "expiresAt", "hubProof");
      Instant issued = parseUtc(string(message, "issuedAt")), expires = parseUtc(string(message, "expiresAt"));
      if (!expires.equals(issued.plusMillis(HANDSHAKE_MS)) || observed.isBefore(issued.minusMillis(1_000)) || observed.isAfter(expires)) throw new IllegalArgumentException();
      transcript = new ControlAuth.Transcript(config.peerRole(), config.peerInstallId(), config.hubInstallId(), config.keyId(), config.protocol(), config.modHash(), peerNonce, ControlAuth.decodeB32(string(message, "hubNonce")), string(message, "challengeId"), issued, expires, config.configHash(), config.bindingHash());
      if (!ControlAuth.verify(config.credential(), ControlAuth.Label.HUB_PROOF, transcript, string(message, "hubProof"))) throw new IllegalArgumentException();
      acceptUtc(observed); if (!send(proofJson())) throw new IllegalArgumentException();
    } else if ("READY".equals(kind) && transcript != null) {
      exact(message, "security", "kind", "challengeId", "readyProof");
      if (!transcript.challengeId().equals(string(message, "challengeId")) || !ControlAuth.verify(config.credential(), ControlAuth.Label.READY_PROOF, transcript, string(message, "readyProof"))) throw new IllegalArgumentException();
      acceptUtc(observed);
      cancel(handshakeTask); handshakeTask = null; lastInboundSequence = -1; lastInboundNanos = ticker.nanoTime(); transition(State.AUTHENTICATED, StopReason.DISABLED);
      long current = generation;
      heartbeatTask = scheduler.periodic(() -> synchronizedRun(current, () -> { if (!sendHeartbeat()) stop(StopReason.BACKPRESSURE); }), HEARTBEAT_MS, HEARTBEAT_MS);
      watchdogTask = scheduler.periodic(() -> synchronizedRun(current, () -> { if (elapsedSinceInbound() >= TimeUnit.MILLISECONDS.toNanos(WATCHDOG_MS)) stop(StopReason.HEARTBEAT_TIMEOUT); }), WATCHDOG_MS, HEARTBEAT_MS);
    } else throw new IllegalArgumentException();
  }
  private void inboundHeartbeat(Map<String, Object> message) {
    exact(message, "security", "kind", "sequence"); Object raw = message.get("sequence");
    if (!(raw instanceof Long number) || number < 0 || number <= lastInboundSequence) throw new IllegalArgumentException();
    acceptUtc(currentUtc()); lastInboundSequence = number; lastInboundNanos = ticker.nanoTime();
  }
  private long elapsedSinceInbound() { return ticker.nanoTime() - lastInboundNanos; }
  private void failIfExpired(long current) { synchronizedRun(current, () -> { if (state == State.PENDING && expired()) stop(StopReason.AUTH_REJECTED); }); }
  private void failIfCurrent(long current, StopReason value) { synchronizedRun(current, () -> stop(value)); }
  private synchronized void synchronizedRun(long current, Runnable action) { if (current == generation && state != State.CLOSED) action.run(); }
  private boolean expired() { return ticker.nanoTime() - handshakeDeadlineNanos >= 0; }
  private boolean send(String message) {
    if (socket == null || pendingSends.size() >= QUEUE_LIMIT) {
      stop(StopReason.BACKPRESSURE);
      return false;
    }
    pendingSends.addLast(new SendEntry(message, generation, socket));
    startNextSend();
    return state != State.CLOSED;
  }
  private void startNextSend() {
    if (inFlight != null || state == State.CLOSED) return;
    SendEntry entry = pendingSends.peekFirst();
    if (entry == null) return;
    inFlight = entry;
    CompletableFuture<?> future;
    try { future = entry.socket().sendText(entry.message()); }
    catch (RuntimeException error) { completeSend(entry, error); return; }
    future.whenComplete((ignored, error) -> completeSend(entry, error));
  }
  private synchronized void completeSend(SendEntry entry, Throwable error) {
    if (entry.generation() != generation || entry.socket() != socket || inFlight != entry) return;
    pendingSends.removeFirst(); inFlight = null;
    if (error != null) { stop(StopReason.DISCONNECTED); return; }
    startNextSend();
  }
  private synchronized void stop(StopReason value) {
    if (state == State.CLOSED) return;
    Socket active = socket; socket = null; ++generation; fragments.setLength(0); transcript = null; peerNonce = null; pendingSends.clear(); inFlight = null;
    cancel(handshakeTask); cancel(heartbeatTask); cancel(watchdogTask); handshakeTask = heartbeatTask = watchdogTask = null;
    transition(State.CLOSED, value); scheduler.shutdown(); if (active != null) active.close(value);
  }
  private void transition(State next, StopReason why) { state = next; reason = why; listener.onState(next, why); }
  private Instant currentUtc() { Instant now = clock.instant(); if (lastUtc != null && now.isBefore(lastUtc)) throw new IllegalArgumentException(); return now; }
  private void acceptUtc(Instant now) { lastUtc = now; }
  private static Instant parseUtc(String value) { Instant parsed = Instant.parse(value); if (!ControlAuth.formatUtc(parsed).equals(value)) throw new IllegalArgumentException(); return parsed; }
  private String helloJson() { Map<String, Object> fields = new LinkedHashMap<>(); fields.put("security", ControlAuth.DOMAIN); fields.put("kind", "HELLO"); fields.put("peerRole", config.peerRole().name()); fields.put("peerInstallId", config.peerInstallId()); fields.put("hubInstallId", config.hubInstallId()); fields.put("keyId", config.keyId()); fields.put("protocol", config.protocol()); fields.put("modHash", ControlAuth.b32(config.modHash())); fields.put("configHash", ControlAuth.b32(config.configHash())); fields.put("bindingHash", ControlAuth.b32(config.bindingHash())); fields.put("peerNonce", ControlAuth.b32(peerNonce)); return json(fields); }
  private String proofJson() { return json(Map.of("security", ControlAuth.DOMAIN, "kind", "PROOF", "challengeId", transcript.challengeId(), "peerProof", ControlAuth.b32(ControlAuth.hmac(config.credential(), ControlAuth.transcript(ControlAuth.Label.PEER_PROOF, transcript))))); }
  private String heartbeatJson(long number) { return json(Map.of("security", ControlAuth.DOMAIN, "kind", "HEARTBEAT", "sequence", number)); }
  private static String string(Map<String, Object> values, String key) { if (!ControlAuth.DOMAIN.equals(values.get("security")) || !(values.get(key) instanceof String result)) throw new IllegalArgumentException(); return result; }
  private static void exact(Map<String, Object> values, String... names) { if (values.size() != names.length) throw new IllegalArgumentException(); for (String name : names) if (!values.containsKey(name)) throw new IllegalArgumentException(); string(values, "kind"); }
  private static void depth(Object value, int level) { if (level > 4) throw new IllegalArgumentException(); if (value instanceof Map<?, ?> map) for (Object child : map.values()) depth(child, level + 1); if (value instanceof Iterable<?> items) for (Object child : items) depth(child, level + 1); }
  private static boolean validScalars(CharSequence text) { for (int index = 0; index < text.length(); index++) { char value = text.charAt(index); if (Character.isHighSurrogate(value)) { if (++index >= text.length() || !Character.isLowSurrogate(text.charAt(index))) return false; } else if (Character.isLowSurrogate(value)) return false; } return true; }
  private static String json(Map<String, Object> values) { StringBuilder out = new StringBuilder("{"); for (Map.Entry<String, Object> entry : values.entrySet()) { if (out.length() > 1) out.append(','); out.append('"').append(entry.getKey()).append("\":"); if (entry.getValue() instanceof String text) out.append('"').append(text).append('"'); else out.append(entry.getValue()); } return out.append('}').toString(); }
  private static void cancel(Task task) { if (task != null) task.cancel(); }
  private record SendEntry(String message, long generation, Socket socket) {}

  private static final class ExecutorScheduler implements Scheduler {
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> { Thread thread = new Thread(r, "luosandbox-control-ws"); thread.setDaemon(true); return thread; });
    @Override public Task schedule(Runnable task, long delay) { ScheduledFuture<?> future = executor.schedule(task, delay, TimeUnit.MILLISECONDS); return () -> future.cancel(false); }
    @Override public Task periodic(Runnable task, long initial, long period) { ScheduledFuture<?> future = executor.scheduleAtFixedRate(task, initial, period, TimeUnit.MILLISECONDS); return () -> future.cancel(false); }
    @Override public void shutdown() { executor.shutdownNow(); }
  }
  private static final class JdkConnector implements Connector {
    @Override public CompletableFuture<Socket> connect(ControlTransportConfig config, Callbacks callbacks) {
      CompletableFuture<Socket> result = new CompletableFuture<>();
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build().newWebSocketBuilder().subprotocols(ControlTransportConfig.PROTOCOL).buildAsync(config.endpoint(), new WebSocket.Listener() {
        @Override public void onOpen(WebSocket value) { Socket socket = new JdkSocket(value); result.complete(socket); callbacks.opened(socket); }
        @Override public CompletionStage<?> onText(WebSocket value, CharSequence text, boolean last) { callbacks.text(text, last); return CompletableFuture.completedFuture(null); }
        @Override public CompletionStage<?> onBinary(WebSocket value, ByteBuffer data, boolean last) { callbacks.binary(); return CompletableFuture.completedFuture(null); }
        @Override public CompletionStage<?> onClose(WebSocket value, int code, String why) { callbacks.closed(); return CompletableFuture.completedFuture(null); }
        @Override public void onError(WebSocket value, Throwable error) { callbacks.failed(); }
      }).whenComplete((value, error) -> { if (error != null) result.completeExceptionally(error); });
      return result;
    }
  }
  private record JdkSocket(WebSocket value) implements Socket {
    @Override public String subprotocol() { return value.getSubprotocol(); }
    @Override public CompletableFuture<?> sendText(String text) { return value.sendText(text, true).toCompletableFuture(); }
    @Override public void request(long count) { value.request(count); }
    @Override public void close(StopReason reason) { value.sendClose(WebSocket.NORMAL_CLOSURE, reason.name()); }
    @Override public void abort() { value.abort(); }
  }
}
