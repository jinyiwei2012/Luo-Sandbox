package io.github.jinyiwei2012.luosandbox.transport;

import static org.junit.jupiter.api.Assertions.*;

import io.github.jinyiwei2012.luosandbox.security.ControlAuth;
import io.github.jinyiwei2012.luosandbox.security.ControlDeploymentManifest;
import io.github.jinyiwei2012.luosandbox.security.PostAuthSessionKeys;
import io.github.jinyiwei2012.luosandbox.wire.StrictJson;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/** Test-only concurrent two-role route/candidate socket bridge over actual v2 sessions. */
class PostAuthV2BodyRouteLoopbackTest {
  private static final byte[] MOD = "public-mod-manifest".getBytes(StandardCharsets.US_ASCII);
  private static final byte[] POLICY = "public-policy-config".getBytes(StandardCharsets.US_ASCII);
  private static final byte[] BINDING = "public-binding-manifest".getBytes(StandardCharsets.US_ASCII);
  private static final byte[] NOTICE = ("{\"security\":\"luosandbox.control-auth.v1\","
      + "\"kind\":\"BODY_BIND_NOTICE\","
      + "\"serverBootId\":\"50000000-0000-4000-8000-000000000001\","
      + "\"gameConnectionId\":\"60000000-0000-4000-8000-000000000001\","
      + "\"bodyId\":\"70000000-0000-4000-8000-000000000001\","
      + "\"gameNonce\":\"AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8\"}")
      .getBytes(StandardCharsets.US_ASCII);

  @Test void actualNoticeRouteAndMacValidSyntheticCandidate() throws Exception {
    Assumptions.assumeTrue("1".equals(System.getenv("RUN_MINECRAFT_V2_BODY_ROUTE_LOOPBACK")));
    URI endpoint = URI.create(required("MINECRAFT_V2_LOOPBACK_URL"));
    Peer server = connect(endpoint, ControlAuth.Role.SERVER_AUTHORITY, "loopback-server", bytes(0));
    Peer client = connect(endpoint, ControlAuth.Role.CLIENT_CONTROLLER, "loopback-client", bytes(32));
    try {
      server.open(frame -> {
        if (!frame.messageKind().equals("BODY_BIND_ROUTE")) return;
        assertArrayEquals(NOTICE, frame.payload());
        assertEquals("loopback-client", frame.sourceInstallId());
        assertEquals(client.connectionId(), frame.sourceConnectionId());
        assertEquals(server.connectionId(), frame.recipientConnectionId());
        assertEquals("", frame.challengeExpiresAt());
        byte[] candidate = ("{\"security\":\"luosandbox.control-auth.v1\","
            + "\"kind\":\"BODY_BOUND\","
            + "\"serverBootId\":\"50000000-0000-4000-8000-000000000001\","
            + "\"gameConnectionId\":\"60000000-0000-4000-8000-000000000001\","
            + "\"bodyId\":\"70000000-0000-4000-8000-000000000001\"}")
            .getBytes(StandardCharsets.US_ASCII);
        server.session().sendBusiness("BODY_BOUND_CANDIDATE", candidate, frame.routeId(), "", "",
            server.connectionId(), Instant.now().plusSeconds(4).truncatedTo(ChronoUnit.MILLIS).toString());
      });
      client.open(frame -> { throw new IllegalStateException("unexpected client application frame"); });
      onOwner(client.owner(), () -> {
        client.session().sendBusiness("BODY_BIND_NOTICE", NOTICE, "", "", "", "", "");
        return null;
      });
      Map<String, Object> status = waitStatus(required("MINECRAFT_V2_STATUS_URL"));
      assertEquals(1L, status.get("nonAuthoritativeCandidateCount"));
      assertEquals(Boolean.TRUE, status.get("routeRawPayloadMatched"));
      System.out.println("V2_BODY_ROUTE_METRIC {\"scenario\":\"NOTICE_ROUTE_SYNTHETIC_CANDIDATE\","
          + "\"candidateCount\":1,\"rawPayloadMatched\":true}");
    } finally {
      client.close(); server.close();
    }
  }

  private static Peer connect(URI endpoint, ControlAuth.Role role, String peerId, byte[] psk) throws Exception {
    String keyId = "loopback-" + role.name().toLowerCase();
    ControlDeploymentManifest manifest = manifest(role, peerId);
    var artifacts = manifest.verifyArtifacts(role, peerId, "loopback-hub", manifest.configHash(), MOD, POLICY, BINDING);
    BlockingQueue<String> inbound = new LinkedBlockingQueue<>();
    WebSocket socket = HttpClient.newHttpClient().newWebSocketBuilder()
        .subprotocols(ControlDeploymentManifest.PROTOCOL).buildAsync(endpoint, new Listener(inbound)).get(6, TimeUnit.SECONDS);
    byte[] nonce = bytes(role == ControlAuth.Role.SERVER_AUTHORITY ? 64 : 96);
    socket.sendText(hello(role, peerId, keyId, artifacts, nonce), true).get(2, TimeUnit.SECONDS);
    Map<String,Object> challenge = object(take(inbound));
    var transcript = new ControlAuth.Transcript(role, peerId, "loopback-hub", keyId,
        ControlDeploymentManifest.PROTOCOL, artifacts.modManifestHash(), nonce,
        ControlAuth.decodeB32(string(challenge,"hubNonce")), string(challenge,"challengeId"),
        Instant.parse(string(challenge,"issuedAt")), Instant.parse(string(challenge,"expiresAt")),
        artifacts.configHash(), artifacts.bindingManifestHash());
    String proof = ControlAuth.b32(ControlAuth.hmac(psk, ControlAuth.transcript(ControlAuth.Label.PEER_PROOF, transcript)));
    socket.sendText("{\"security\":\"luosandbox.control-auth.v1\",\"kind\":\"PROOF\",\"challengeId\":\""
        + transcript.challengeId() + "\",\"peerProof\":\"" + proof + "\"}", true).get(2, TimeUnit.SECONDS);
    object(take(inbound));
    return new Peer(socket, inbound, transcript, psk);
  }

  private static final class Peer {
    private final WebSocket socket;
    private final BlockingQueue<String> inbound;
    private final ControlAuth.Transcript transcript;
    private final byte[] psk;
    private final ScheduledExecutorService owner;
    private final AtomicReference<Throwable> failure = new AtomicReference<>();
    private final AtomicReference<String> connectionId = new AtomicReference<>();
    private PostAuthPeerSession session;
    Peer(WebSocket socket, BlockingQueue<String> inbound, ControlAuth.Transcript transcript, byte[] psk) {
      this.socket = socket; this.inbound = inbound; this.transcript = transcript; this.psk = psk;
      this.owner = Executors.newSingleThreadScheduledExecutor();
    }
    Peer open(Consumer<io.github.jinyiwei2012.luosandbox.security.PostAuthFrameCodec.Frame> observer) throws Exception {
      PostAuthSessionKeys keys = PostAuthSessionKeys.derive(psk, transcript);
      AtomicReference<Thread> thread = new AtomicReference<>();
      PostAuthPeerSession actual = onOwner(owner, () -> {
        thread.set(Thread.currentThread());
        return new PostAuthPeerSession(transcript, keys, 1, () -> Thread.currentThread() == thread.get(),
            () -> System.nanoTime()/1_000_000, event -> { owner.execute(event); return true; },
            abort -> { owner.execute(abort); return true; }, reason -> failure.compareAndSet(null,new RuntimeException()),
            raw -> socket.sendText(new String(raw, StandardCharsets.US_ASCII), true).thenApply(x -> null), observer::accept,
            new PostAuthPeerSession.Lifecycle(){public void fence(String reason){} public void physicalClose(String reason){}});
      });
      session = actual;
      owner.scheduleAtFixedRate(() -> { try { String raw; while((raw=inbound.poll())!=null) {
        Map<String,Object> outer=object(raw); if("SESSION_OPEN".equals(outer.get("messageKind"))) connectionId.compareAndSet(null,string(outer,"connectionId"));
        actual.receive(raw.getBytes(StandardCharsets.UTF_8)); } actual.tick(); }
        catch(Throwable error){failure.compareAndSet(null,error);} },0,20,TimeUnit.MILLISECONDS);
      long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
      while(onOwner(owner, actual::state)!=PostAuthPeerSession.State.OPEN && System.nanoTime()<deadline) Thread.sleep(10);
      assertEquals(PostAuthPeerSession.State.OPEN, onOwner(owner, actual::state)); return this;
    }
    ScheduledExecutorService owner(){ return owner; }
    PostAuthPeerSession session(){ return session; }
    String connectionId(){ return connectionId.get(); }
    void close() throws Exception { if(session!=null) onOwner(owner,()->{session.close();return null;}); socket.sendClose(1000,"done"); owner.shutdownNow(); owner.awaitTermination(3,TimeUnit.SECONDS); }
  }

  private static Map<String,Object> waitStatus(String url) throws Exception { long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(6); while(System.nanoTime()<end){
    var response=HttpClient.newHttpClient().send(java.net.http.HttpRequest.newBuilder(URI.create(url)).GET().build(),java.net.http.HttpResponse.BodyHandlers.ofString());
    Map<String,Object> value=object(response.body()); if(Long.valueOf(1).equals(value.get("nonAuthoritativeCandidateCount"))) return value; Thread.sleep(50);} throw new AssertionError("route status timeout"); }
  private static String hello(ControlAuth.Role role,String peer,String key,ControlDeploymentManifest.ArtifactMatch a,byte[] nonce){return "{\"security\":\"luosandbox.control-auth.v1\",\"kind\":\"HELLO\",\"peerRole\":\""+role+"\",\"peerInstallId\":\""+peer+"\",\"hubInstallId\":\"loopback-hub\",\"keyId\":\""+key+"\",\"protocol\":\"LUO_CONTROL_POSTAUTH_V2\",\"modHash\":\""+ControlAuth.b32(a.modManifestHash())+"\",\"configHash\":\""+ControlAuth.b32(a.configHash())+"\",\"bindingHash\":\""+ControlAuth.b32(a.bindingManifestHash())+"\",\"peerNonce\":\""+ControlAuth.b32(nonce)+"\"}";}
  private static ControlDeploymentManifest manifest(ControlAuth.Role role,String peer)throws Exception{String v="{\"schema\":\"luosandbox.minecraft-control-deployment.v2\",\"security\":\"luosandbox.control-auth.v1\",\"peerRole\":\""+role+"\",\"peerInstallId\":\""+peer+"\",\"hubInstallId\":\"loopback-hub\",\"protocol\":\"LUO_CONTROL_POSTAUTH_V2\",\"postAuthProfile\":\"LUO_CONTROL_POSTAUTH_V2\",\"sessionKdfDomain\":\"LUO_CONTROL_SESSION_KEY_V2\",\"frameMacDomain\":\"LUO_CONTROL_FRAME_V2\",\"frameMaxUtf8Bytes\":4096,\"payloadMaxRawBytes\":2048,\"asciiIdMaxBytes\":128,\"outboundQueueFrames\":8,\"openTimeoutMs\":5000,\"heartbeatIntervalMs\":500,\"heartbeatTimeoutMs\":2000,\"routeCapacity\":2,\"routeTtlMs\":5000,\"bindingPendingCapacity\":8,\"storeOutstandingCapacity\":2,\"modManifestSha256\":\""+hex(MOD)+"\",\"policyConfigSha256\":\""+hex(POLICY)+"\",\"bindingManifestSha256\":\""+hex(BINDING)+"\"}\n";return ControlDeploymentManifest.parse(v.getBytes(StandardCharsets.US_ASCII));}
  private static String hex(byte[] v)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(v));}
  private static Map<String,Object> object(String v)throws Exception{return StrictJson.decodeObject(v.getBytes(StandardCharsets.UTF_8));}
  private static String string(Map<String,Object> v,String k){return(String)v.get(k);} private static String take(BlockingQueue<String> q)throws Exception{return q.poll(6,TimeUnit.SECONDS);}
  private static byte[] bytes(int start){byte[]r=new byte[32];for(int i=0;i<32;i++)r[i]=(byte)(start+i);return r;}
  private static String required(String n){String v=System.getenv(n);if(v==null||v.isBlank())throw new IllegalStateException();return v;}
  private static <T>T onOwner(ScheduledExecutorService o,Supplier<T>a)throws Exception{return o.submit(a::get).get(3,TimeUnit.SECONDS);}
  private static final class Listener implements WebSocket.Listener{final BlockingQueue<String>q;final StringBuilder b=new StringBuilder();Listener(BlockingQueue<String>q){this.q=q;}public void onOpen(WebSocket w){w.request(1);}public CompletionStage<?> onText(WebSocket w,CharSequence d,boolean last){b.append(d);if(last){q.add(b.toString());b.setLength(0);}w.request(1);return CompletableFuture.completedFuture(null);}public CompletionStage<?> onBinary(WebSocket w,ByteBuffer d,boolean l){return CompletableFuture.failedFuture(new IllegalStateException());}}
}
