package io.github.jinyiwei2012.luosandbox.transport;

import static org.junit.jupiter.api.Assertions.*;

import io.github.jinyiwei2012.luosandbox.security.ControlAuth;
import io.github.jinyiwei2012.luosandbox.security.ControlDeploymentManifest;
import io.github.jinyiwei2012.luosandbox.security.PostAuthFrameCodec;
import io.github.jinyiwei2012.luosandbox.security.PostAuthSessionKeys;
import io.github.jinyiwei2012.luosandbox.wire.StrictJson;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** Test-only JDK WebSocket adapter over the actual v2 auth, codec, keys and peer session. */
class PostAuthV2LoopbackTest {
  private static final byte[] MOD = "public-mod-manifest".getBytes(StandardCharsets.US_ASCII);
  private static final byte[] POLICY = "public-policy-config".getBytes(StandardCharsets.US_ASCII);
  private static final byte[] BINDING = "public-binding-manifest".getBytes(StandardCharsets.US_ASCII);
  private static final long REQUIRED_MILLIS = 2300;

  @ParameterizedTest(name = "{0}")
  @EnumSource(value = ControlAuth.Role.class, names = {"SERVER_AUTHORITY", "CLIENT_CONTROLLER"})
  void actualV2HandshakeAndHeartbeatSession(ControlAuth.Role role) throws Exception {
    Assumptions.assumeTrue("1".equals(System.getenv("RUN_MINECRAFT_V2_LOOPBACK")));
    URI endpoint = URI.create(required("MINECRAFT_V2_LOOPBACK_URL"));
    byte[] psk = range(role == ControlAuth.Role.SERVER_AUTHORITY ? 0 : 32);
    String peerId = role == ControlAuth.Role.SERVER_AUTHORITY ? "loopback-server" : "loopback-client";
    String keyId = "loopback-" + role.name().toLowerCase();
    ControlDeploymentManifest manifest = manifest(role, peerId);
    ControlDeploymentManifest.ArtifactMatch artifacts = manifest.verifyArtifacts(
        role, peerId, "loopback-hub", manifest.configHash(), MOD, POLICY, BINDING);
    BlockingQueue<String> inbound = new LinkedBlockingQueue<>();
    SocketListener listener = new SocketListener(inbound);
    WebSocket socket = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
        .newWebSocketBuilder().subprotocols(ControlDeploymentManifest.PROTOCOL)
        .connectTimeout(Duration.ofSeconds(5)).buildAsync(endpoint, listener).get(6, TimeUnit.SECONDS);
    assertEquals(ControlDeploymentManifest.PROTOCOL, socket.getSubprotocol());
    byte[] peerNonce = range(role == ControlAuth.Role.SERVER_AUTHORITY ? 64 : 96);
    socket.sendText(hello(role, peerId, keyId, artifacts, peerNonce), true).get(2, TimeUnit.SECONDS);
    Map<String, Object> challenge = object(take(inbound));
    assertExact(challenge, "CHALLENGE", "challengeId", "hubNonce", "issuedAt", "expiresAt", "hubProof");
    ControlAuth.Transcript transcript = new ControlAuth.Transcript(
        role, peerId, "loopback-hub", keyId, ControlDeploymentManifest.PROTOCOL,
        artifacts.modManifestHash(), peerNonce, ControlAuth.decodeB32(string(challenge, "hubNonce")),
        string(challenge, "challengeId"), Instant.parse(string(challenge, "issuedAt")),
        Instant.parse(string(challenge, "expiresAt")), artifacts.configHash(), artifacts.bindingManifestHash());
    assertTrue(ControlAuth.verify(psk, ControlAuth.Label.HUB_PROOF, transcript, string(challenge, "hubProof")));
    String peerProof = ControlAuth.b32(ControlAuth.hmac(psk,
        ControlAuth.transcript(ControlAuth.Label.PEER_PROOF, transcript)));
    socket.sendText("{\"security\":\"luosandbox.control-auth.v1\",\"kind\":\"PROOF\","
        + "\"challengeId\":\"" + transcript.challengeId() + "\",\"peerProof\":\"" + peerProof + "\"}", true)
        .get(2, TimeUnit.SECONDS);
    Map<String, Object> ready = object(take(inbound));
    assertExact(ready, "READY", "challengeId", "readyProof");
    assertEquals(transcript.challengeId(), string(ready, "challengeId"));
    assertTrue(ControlAuth.verify(psk, ControlAuth.Label.READY_PROOF, transcript, string(ready, "readyProof")));

    ScheduledExecutorService owner = Executors.newSingleThreadScheduledExecutor(runnable -> {
      Thread thread = new Thread(runnable, "v2-loopback-owner-" + role.name());
      thread.setDaemon(false);
      return thread;
    });
    AtomicReference<Thread> ownerThread = new AtomicReference<>();
    AtomicReference<PostAuthPeerSession> sessionRef = new AtomicReference<>();
    AtomicReference<Throwable> failure = new AtomicReference<>();
    try (PostAuthSessionKeys keys = PostAuthSessionKeys.derive(psk, transcript)) {
      PostAuthPeerSession session = onOwner(owner, () -> {
        ownerThread.set(Thread.currentThread());
        return new PostAuthPeerSession(transcript, keys, 1,
            () -> Thread.currentThread() == ownerThread.get(),
            () -> System.nanoTime() / 1_000_000,
            event -> { owner.execute(event); return true; },
            abort -> { owner.execute(abort); return true; },
            reason -> failure.compareAndSet(null, new IllegalStateException("cleanup_unknown")),
            raw -> socket.sendText(new String(raw, StandardCharsets.US_ASCII), true).thenApply(ignored -> null),
            frame -> { throw new IllegalStateException("application authority disabled"); },
            new PostAuthPeerSession.Lifecycle() {
              @Override public void fence(String reason) { }
              @Override public void physicalClose(String reason) { socket.sendClose(1000, "test_complete"); }
            });
      });
      sessionRef.set(session);
      long heartbeatStarted = System.nanoTime();
      var driver = owner.scheduleAtFixedRate(() -> {
        try {
          String raw;
          while ((raw = inbound.poll()) != null) session.receive(raw.getBytes(StandardCharsets.UTF_8));
          session.tick();
        } catch (Throwable error) {
          failure.compareAndSet(null, error);
        }
      }, 0, 25, TimeUnit.MILLISECONDS);
      long deadline = heartbeatStarted + Duration.ofMillis(REQUIRED_MILLIS).toNanos();
      while (System.nanoTime() < deadline && failure.get() == null) Thread.sleep(20);
      long measured = Duration.ofNanos(System.nanoTime() - heartbeatStarted).toMillis();
      assertNull(failure.get(), () -> "peer session failed: " + failure.get());
      assertEquals(PostAuthPeerSession.State.OPEN, onOwner(owner, session::state));
      assertTrue(measured >= REQUIRED_MILLIS);
      driver.cancel(false);
      onOwner(owner, () -> { session.close(); return null; });
      System.out.println("V2_LOOPBACK_METRIC {\"role\":\"" + role.name()
          + "\",\"requiredDurationMs\":2300,\"measuredDurationMs\":" + measured
          + ",\"applicationFrames\":0}");
    } finally {
      socket.sendClose(1000, "test_complete").exceptionally(error -> null).get(2, TimeUnit.SECONDS);
      owner.shutdownNow();
      assertTrue(owner.awaitTermination(3, TimeUnit.SECONDS));
      Arrays.fill(psk, (byte) 0);
    }
  }

  private static String hello(ControlAuth.Role role, String peerId, String keyId,
      ControlDeploymentManifest.ArtifactMatch artifacts, byte[] peerNonce) {
    return "{\"security\":\"luosandbox.control-auth.v1\",\"kind\":\"HELLO\","
        + "\"peerRole\":\"" + role.name() + "\",\"peerInstallId\":\"" + peerId
        + "\",\"hubInstallId\":\"loopback-hub\",\"keyId\":\"" + keyId
        + "\",\"protocol\":\"LUO_CONTROL_POSTAUTH_V2\",\"modHash\":\""
        + ControlAuth.b32(artifacts.modManifestHash()) + "\",\"configHash\":\""
        + ControlAuth.b32(artifacts.configHash()) + "\",\"bindingHash\":\""
        + ControlAuth.b32(artifacts.bindingManifestHash()) + "\",\"peerNonce\":\""
        + ControlAuth.b32(peerNonce) + "\"}";
  }

  private static ControlDeploymentManifest manifest(ControlAuth.Role role, String peerId) throws Exception {
    String value = "{\"schema\":\"luosandbox.minecraft-control-deployment.v2\","
        + "\"security\":\"luosandbox.control-auth.v1\",\"peerRole\":\"" + role.name()
        + "\",\"peerInstallId\":\"" + peerId + "\",\"hubInstallId\":\"loopback-hub\","
        + "\"protocol\":\"LUO_CONTROL_POSTAUTH_V2\",\"postAuthProfile\":\"LUO_CONTROL_POSTAUTH_V2\","
        + "\"sessionKdfDomain\":\"LUO_CONTROL_SESSION_KEY_V2\",\"frameMacDomain\":\"LUO_CONTROL_FRAME_V2\","
        + "\"frameMaxUtf8Bytes\":4096,\"payloadMaxRawBytes\":2048,\"asciiIdMaxBytes\":128,"
        + "\"outboundQueueFrames\":8,\"openTimeoutMs\":5000,\"heartbeatIntervalMs\":500,"
        + "\"heartbeatTimeoutMs\":2000,\"routeCapacity\":2,\"routeTtlMs\":5000,"
        + "\"bindingPendingCapacity\":8,\"storeOutstandingCapacity\":2,\"modManifestSha256\":\""
        + hex(MOD) + "\",\"policyConfigSha256\":\"" + hex(POLICY)
        + "\",\"bindingManifestSha256\":\"" + hex(BINDING) + "\"}\n";
    return ControlDeploymentManifest.parse(value.getBytes(StandardCharsets.US_ASCII));
  }

  private static String hex(byte[] value) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
  }

  private static Map<String, Object> object(String value) throws Exception {
    return StrictJson.decodeObject(value.getBytes(StandardCharsets.UTF_8));
  }

  private static void assertExact(Map<String, Object> value, String kind, String... rest) {
    java.util.HashSet<String> expected = new java.util.HashSet<>();
    expected.add("security"); expected.add("kind"); expected.addAll(java.util.List.of(rest));
    assertEquals(expected, value.keySet());
    assertEquals("luosandbox.control-auth.v1", value.get("security"));
    assertEquals(kind, value.get("kind"));
  }

  private static String string(Map<String, Object> value, String key) {
    assertInstanceOf(String.class, value.get(key));
    return (String) value.get(key);
  }

  private static String take(BlockingQueue<String> inbound) throws Exception {
    String value = inbound.poll(6, TimeUnit.SECONDS);
    assertNotNull(value, "timed out waiting for loopback frame");
    return value;
  }

  private static <T> T onOwner(ScheduledExecutorService owner, Supplier<T> action) throws Exception {
    return owner.submit(action::get).get(3, TimeUnit.SECONDS);
  }

  private static String required(String name) {
    String value = System.getenv(name);
    if (value == null || value.isBlank()) throw new IllegalStateException(name + " is required");
    return value;
  }

  private static byte[] range(int start) {
    byte[] result = new byte[32];
    for (int index = 0; index < result.length; index++) result[index] = (byte) (start + index);
    return result;
  }

  private static final class SocketListener implements WebSocket.Listener {
    private final BlockingQueue<String> inbound;
    private final StringBuilder fragments = new StringBuilder();
    SocketListener(BlockingQueue<String> inbound) { this.inbound = inbound; }
    @Override public void onOpen(WebSocket webSocket) { webSocket.request(1); }
    @Override public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
      fragments.append(data);
      if (fragments.toString().getBytes(StandardCharsets.UTF_8).length > 4096) {
        return CompletableFuture.failedFuture(new IllegalStateException("frame too large"));
      }
      if (last) { inbound.add(fragments.toString()); fragments.setLength(0); }
      webSocket.request(1);
      return CompletableFuture.completedFuture(null);
    }
    @Override public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
      return CompletableFuture.failedFuture(new IllegalStateException("binary frame rejected"));
    }
  }
}
