package io.github.jinyiwei2012.luosandbox.transport;

import static org.junit.jupiter.api.Assertions.*;

import io.github.jinyiwei2012.luosandbox.security.ControlAuth;
import io.github.jinyiwei2012.luosandbox.security.ControlDeploymentManifest;
import io.github.jinyiwei2012.luosandbox.wire.StrictJson;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

/** Explicit opt-in only. No production activation or authority inference. */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class V2ConnectorLoopbackTest {
  @Test @Order(1) void SERVER_normal() throws Exception { scenario(ControlAuth.Role.SERVER_AUTHORITY, false); }
  @Test @Order(2) void CLIENT_normal() throws Exception { scenario(ControlAuth.Role.CLIENT_CONTROLLER, false); }
  @Test @Order(3) void SERVER_wrongPsk() throws Exception {
    scenario(ControlAuth.Role.SERVER_AUTHORITY, true);
    System.out.println("CONNECTOR_RUN_COMPLETE " + required("V2_CONNECTOR_RUN_NONCE"));
  }

  private static String required(String name) {
    String value = System.getenv(name); assertNotNull(value, name); assertFalse(value.isBlank(), name); return value;
  }
  private static long now() { return System.nanoTime() / 1_000_000; }
  private static byte[] hash(String text) throws Exception {
    return MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.US_ASCII));
  }
  private static HttpClient client() {
    return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).proxy(new ProxySelector() {
      public List<Proxy> select(URI uri) { return List.of(Proxy.NO_PROXY); }
      public void connectFailed(URI uri, SocketAddress address, java.io.IOException failure) {}
    }).build();
  }

  private static void scenario(ControlAuth.Role role, boolean wrong) throws Exception {
    Assumptions.assumeTrue("1".equals(System.getenv("RUN_V2_CONNECTOR_LOOPBACK")), "explicit runner opt-in required");
    URI endpoint = URI.create(required("V2_CONNECTOR_LOOPBACK_URL"));
    assertEquals("ws", endpoint.getScheme()); assertEquals("127.0.0.1", endpoint.getHost());
    assertTrue(endpoint.getPort() > 0 && endpoint.getPort() <= 65535);
    assertEquals("/minecraft/v2/ws", endpoint.getRawPath());
    assertNull(endpoint.getRawUserInfo()); assertNull(endpoint.getRawQuery()); assertNull(endpoint.getRawFragment());
    String nonce = required("V2_CONNECTOR_RUN_NONCE"); assertTrue(nonce.matches("[0-9a-f]{32}"));
    Path evidence = Path.of(required("V2_CONNECTOR_EVIDENCE_DIR"));
    assertEquals(evidence.normalize(), evidence);
    for (Path part = evidence; part != null; part = part.getParent()) {
      assertFalse(Files.isSymbolicLink(part));
      assertFalse(Files.readAttributes(part, java.nio.file.attribute.BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).isOther());
    }
    assertTrue(evidence.isAbsolute()); assertTrue(Files.isDirectory(evidence, LinkOption.NOFOLLOW_LINKS));
    assertFalse(Files.isSymbolicLink(evidence));
    Path approved = Path.of("C:\\Users\\Administrator\\AppData\\Local\\Temp\\opencode").toRealPath();
    assertTrue(evidence.toRealPath().startsWith(approved));
    String label = wrong ? "SERVER_wrongPsk" : role == ControlAuth.Role.SERVER_AUTHORITY ? "SERVER_normal" : "CLIENT_normal";
    Path traceFile = evidence.resolve(label + ".trace"); assertFalse(Files.exists(traceFile));
    String peer = role == ControlAuth.Role.SERVER_AUTHORITY ? "loopback-server" : "loopback-client";
    var manifest = manifest(role, peer);
    byte[] key = new byte[32]; for (int i = 0; i < 32; i++) key[i] = (byte) (i + (role == ControlAuth.Role.SERVER_AUTHORITY ? 0 : 32));
    if (wrong) key[0] ^= 1;
    HttpClient client = client(); // JDK17 client has no close contract; no custom executor is owned here.
    assertTrue(client.authenticator().isEmpty()); assertTrue(client.cookieHandler().isEmpty());
    var context = new V2PeerConnector.Context(true, endpoint, V2PeerConnector.PROFILE, role, peer,
        "loopback-hub", "loopback-" + role.name().toLowerCase(java.util.Locale.ROOT),
        hash("public-mod-manifest"), manifest.configHash(), hash("public-binding-manifest"));
    Thread owner = Thread.currentThread(); ArrayBlockingQueue<Runnable> queue = new ArrayBlockingQueue<>(4);
    Trace trace = new Trace(); int[] fences = {0}; int[] releases = {0};
    var physical = new V2JdkWebSocketAdapter(context, client);
    var core = new V2PeerConnector(context, () -> Thread.currentThread() == owner,
        task -> queue.offer(task) ? V2OwnerMailbox.EnqueueResult.ACCEPTED : V2OwnerMailbox.EnqueueResult.STOPPED,
        V2ConnectorLoopbackTest::now, () -> Instant.now().truncatedTo(ChronoUnit.MILLIS),
        () -> { byte[] bytes = new byte[32]; do { new SecureRandom().nextBytes(bytes); } while (java.util.Arrays.equals(bytes, new byte[32])); return bytes; },
        new V2PeerConnector.CredentialOwner() {
          public byte[] copy() { return key.clone(); }
          public void close() { assertSame(owner, Thread.currentThread()); releases[0]++; }
        }, events -> physical.connect(trace.events(events)).thenApply(trace::socket),
        () -> { assertSame(owner, Thread.currentThread()); fences[0]++; trace.add("FENCE"); },
        reason -> trace.add("UNKNOWN " + reason));
    long openAt = -1; long measured = 0; boolean roleSeen = false; boolean everOpen = false;
    var finished = new java.util.concurrent.CountDownLatch(1);
    var supervisorFailure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
    Thread supervisor = new Thread(() -> {
      try { if (!finished.await(20, TimeUnit.SECONDS)) core.publishStopped(); }
      catch (Throwable failure) { supervisorFailure.set(failure); }
    }, "connector-test-timeout");
    supervisor.setDaemon(true); supervisor.start();
    try {
      core.start(); long deadline = now() + 10_000;
      while (now() < deadline) {
        service(core, queue);
        V2PeerConnector.State state = core.state();
        if (state == V2PeerConnector.State.OPEN) {
          everOpen = true; assertFalse(wrong, "wrong PSK reached OPEN");
          if (openAt < 0) openAt = now();
          if (!roleSeen) {
            Map<String, Object> status = status(client, endpoint);
            assertTrue(((List<?>) status.get("activeRoles")).contains(role.name())); roleSeen = true;
          }
          measured = now() - openAt;
          if (measured >= 2300) break;
        } else if (everOpen) fail("OPEN continuity lost");
        else if (wrong && fences[0] == 1) break;
      }
      if (wrong) { assertFalse(everOpen); assertEquals(0, trace.outboundSessionFrames()); }
      else { assertTrue(everOpen); assertTrue(roleSeen); assertTrue(measured >= 2300); assertTrue(trace.outboundSessionFrames() >= 5); }
      core.close(); long closeDeadline = now() + 5000;
      while (now() < closeDeadline && core.state() != V2PeerConnector.State.TERMINAL_API_CLOSED) service(core, queue);
      var cleanup = core.cleanupSnapshot();
      assertTrue(cleanup.attempted()); assertFalse(cleanup.ownerUnknown()); assertFalse(cleanup.physicalCloseFailed());
      if (!wrong) { assertTrue(cleanup.inputClosed()); assertTrue(cleanup.outputClosed()); }
      assertEquals(1, fences[0]); assertEquals(1, releases[0]);
      if (!wrong) assertTrue(trace.before("FENCE", "CORE_CLOSE"));
      long drainDeadline = now() + 3000; Map<String, Object> hub;
      do { hub = status(client, endpoint); if (((List<?>) hub.get("activeRoles")).isEmpty() && ((Number) hub.get("pendingCount")).intValue() == 0) break; service(core, queue); } while (now() < drainDeadline);
      assertEquals(List.of(), hub.get("activeRoles")); assertEquals(0, ((Number) hub.get("pendingCount")).intValue());
      trace.add("JAVA_OPEN_MS " + measured); trace.add("CLEANUP " + cleanup);
      trace.add("OUTBOUND_ATTEMPTS_ONLY; APPLICATION_FRAMES NOT_MEASURED; HUB_ACK_HEARTBEATS NOT_EXPOSED; PING " + trace.pingCount());
    } finally {
      independentCleanup(core::close, core::publishStopped,
          () -> java.util.Arrays.fill(key, (byte) 0), finished::countDown,
          () -> {
            try { supervisor.join(2000); } catch (InterruptedException error) {
              Thread.currentThread().interrupt(); throw new AssertionError(error);
            }
            assertFalse(supervisor.isAlive()); assertNull(supervisorFailure.get());
          }, () -> {
            try { Files.write(traceFile, trace.lines(), StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW); }
            catch (java.io.IOException error) { throw new java.io.UncheckedIOException(error); }
          });
    }
  }
  static void independentCleanup(Runnable... steps) {
    Throwable first = null;
    for (Runnable step : steps) {
      try { step.run(); } catch (Throwable failure) {
        if (first == null || !(first instanceof Error) && failure instanceof Error) first = failure;
      }
    }
    if (first instanceof Error error) throw error;
    if (first instanceof RuntimeException error) throw error;
  }
  private static void service(V2PeerConnector core, ArrayBlockingQueue<Runnable> queue) throws Exception {
    core.tick();
    for (int i = 0; i < 4; i++) { Runnable action = queue.poll(i == 0 ? 25 : 0, TimeUnit.MILLISECONDS); if (action == null) break; action.run(); }
  }
  private static Map<String, Object> status(HttpClient client, URI endpoint) throws Exception {
    URI url = URI.create("http://127.0.0.1:" + endpoint.getPort() + "/minecraft/v2/test-status");
    var response = client.send(HttpRequest.newBuilder(url).timeout(Duration.ofSeconds(1)).GET().build(), HttpResponse.BodyHandlers.ofInputStream());
    assertEquals(200, response.statusCode());
    try (var input = response.body()) { byte[] bytes = input.readNBytes(65_537); assertTrue(bytes.length <= 65_536); return StrictJson.decodeObject(bytes); }
  }
  static final class Trace {
    private final List<String> values = new ArrayList<>(); private int pings;
    private boolean identityFailed;
    synchronized void add(String value) { assertTrue(values.size() < 256); values.add(value); }
    synchronized List<String> lines() { assertFalse(identityFailed, "different physical identity"); return List.copyOf(values); }
    synchronized int pingCount() { return pings; }
    synchronized int outboundSessionFrames() { return (int) values.stream().filter(s -> s.startsWith("OUT ") && s.contains("\"kind\":\"AUTHENTICATED_FRAME\"")).count(); }
    synchronized boolean before(String first, String second) { return values.indexOf(first) >= 0 && values.indexOf(second) > values.indexOf(first); }
    V2PeerConnector.Socket socket(V2PeerConnector.Socket raw) {
      if (raw == null) return null;
      synchronized (this) {
        if (rawIdentity == null) { rawIdentity = raw; decorated = wrap(raw); }
        if (rawIdentity == raw && !identityFailed) return decorated;
        identityFailed = true;
      }
      // Independent mismatch containment outside the trace lock; never fold it into bound identity.
      try { raw.abort(); } finally { throw new AssertionError("different physical identity"); }
    }
    private V2PeerConnector.Socket rawIdentity;
    private V2PeerConnector.Socket decorated;
    private V2PeerConnector.Socket wrap(V2PeerConnector.Socket raw) {
      return new V2PeerConnector.Socket() {
        public String subprotocol() { return raw.subprotocol(); }
        public CompletionStage<Void> send(byte[] bytes) { add("OUT " + new String(bytes, StandardCharsets.UTF_8)); return raw.send(bytes); }
        public CompletionStage<Void> close() { add("CORE_CLOSE"); return raw.close(); }
        public void requestOne() { raw.requestOne(); }
        public void abort() { add("ABORT"); raw.abort(); }
      };
    }
    V2PeerConnector.Events events(V2PeerConnector.Events target) {
      return new V2PeerConnector.Events() {
        public void opened(V2PeerConnector.Socket socket) { target.opened(Trace.this.socket(socket)); }
        public void textFragment(V2PeerConnector.Socket socket, CharSequence text, boolean last) { add("IN_FRAGMENT " + text); target.textFragment(Trace.this.socket(socket), text, last); }
        public void ping(V2PeerConnector.Socket socket, int length) { synchronized (Trace.this) { pings++; } add("PING " + length); target.ping(Trace.this.socket(socket), length); }
        public void pong(V2PeerConnector.Socket socket, int length) { add("PONG " + length); target.pong(Trace.this.socket(socket), length); }
        public void closed(V2PeerConnector.Socket socket) { add("INPUT_CLOSED"); target.closed(Trace.this.socket(socket)); }
        public void failed(V2PeerConnector.Socket socket) { add("FAILED"); target.failed(socket == null ? null : Trace.this.socket(socket)); }
      };
    }
  }
  private static ControlDeploymentManifest manifest(ControlAuth.Role role, String peer) throws Exception {
    String json = "{\"schema\":\"luosandbox.minecraft-control-deployment.v2\",\"security\":\"luosandbox.control-auth.v1\","
        + "\"peerRole\":\"" + role.name() + "\",\"peerInstallId\":\"" + peer + "\",\"hubInstallId\":\"loopback-hub\","
        + "\"protocol\":\"LUO_CONTROL_POSTAUTH_V2\",\"postAuthProfile\":\"LUO_CONTROL_POSTAUTH_V2\","
        + "\"sessionKdfDomain\":\"LUO_CONTROL_SESSION_KEY_V2\",\"frameMacDomain\":\"LUO_CONTROL_FRAME_V2\","
        + "\"frameMaxUtf8Bytes\":4096,\"payloadMaxRawBytes\":2048,\"asciiIdMaxBytes\":128,\"outboundQueueFrames\":8,"
        + "\"openTimeoutMs\":5000,\"heartbeatIntervalMs\":500,\"heartbeatTimeoutMs\":2000,\"routeCapacity\":2,"
        + "\"routeTtlMs\":5000,\"bindingPendingCapacity\":8,\"storeOutstandingCapacity\":2,\"modManifestSha256\":\""
        + HexFormat.of().formatHex(hash("public-mod-manifest")) + "\",\"policyConfigSha256\":\""
        + HexFormat.of().formatHex(hash("public-policy-config")) + "\",\"bindingManifestSha256\":\""
        + HexFormat.of().formatHex(hash("public-binding-manifest")) + "\"}\n";
    return ControlDeploymentManifest.parse(json.getBytes(StandardCharsets.US_ASCII));
  }
}

/** Offline helper regressions, independently discovered without enabling the three network cases. */
class V2ConnectorLoopbackSafetyTest {
  private static final class Socket implements V2PeerConnector.Socket {
    int aborts;
    public String subprotocol() { return V2PeerConnector.PROFILE; }
    public CompletionStage<Void> send(byte[] bytes) { throw new AssertionError(); }
    public CompletionStage<Void> close() { throw new AssertionError(); }
    public void requestOne() {}
    public void abort() { aborts++; }
  }
  @Test void exactIdentityNeverFoldsDifferentSocketAndFailureIsSticky() {
    var trace = new V2ConnectorLoopbackTest.Trace(); var first = new Socket(); var other = new Socket();
    var wrapper = trace.socket(first); assertSame(wrapper, trace.socket(first));
    assertNull(trace.socket(null));
    assertThrows(AssertionError.class, () -> trace.socket(other)); assertEquals(1, other.aborts);
    assertThrows(AssertionError.class, trace::lines);
  }
  @Test void closeErrorCannotSkipIndependentCleanupAndEvidence() {
    var calls = new ArrayList<String>(); var first = new AssertionError("close");
    assertSame(first, assertThrows(AssertionError.class, () -> V2ConnectorLoopbackTest.independentCleanup(
        () -> { calls.add("close"); throw first; },
        () -> { calls.add("stopped"); throw new AssertionError("later"); },
        () -> calls.add("wipe"), () -> calls.add("latch"), () -> calls.add("join"), () -> calls.add("trace"))));
    assertEquals(List.of("close", "stopped", "wipe", "latch", "join", "trace"), calls);
  }
}
