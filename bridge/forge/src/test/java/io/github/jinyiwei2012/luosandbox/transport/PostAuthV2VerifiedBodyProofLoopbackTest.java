package io.github.jinyiwei2012.luosandbox.transport;

import static org.junit.jupiter.api.Assertions.*;

import io.github.jinyiwei2012.luosandbox.security.*;
import io.github.jinyiwei2012.luosandbox.security.forge.BodyProofChannelHandler;
import io.github.jinyiwei2012.luosandbox.security.forge.BodyProofNetworkFrame;
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
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import java.io.ByteArrayOutputStream;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/** Real LP11/HMAC proof -> exact local capability -> authenticated route -> Python candidate. */
class PostAuthV2VerifiedBodyProofLoopbackTest {
  private static final byte[] MOD="public-mod-manifest".getBytes(StandardCharsets.US_ASCII);
  private static final byte[] POLICY="public-policy-config".getBytes(StandardCharsets.US_ASCII);
  private static final byte[] BINDING="public-binding-manifest".getBytes(StandardCharsets.US_ASCII);
  private static final byte[] BODY_KEY=new byte[32];
  private static final String BOOT="50000000-0000-4000-8000-000000000001";
  private static final String BODY="70000000-0000-4000-8000-000000000001";

  @Test void verifiedLp11NoticeRouteCandidate() throws Exception {
    Assumptions.assumeTrue("1".equals(System.getenv("RUN_MINECRAFT_V2_VERIFIED_BODY_PROOF_LOOPBACK")));
    URI endpoint=URI.create(required("MINECRAFT_V2_LOOPBACK_URL"));
    Peer server=connect(endpoint,ControlAuth.Role.SERVER_AUTHORITY,"loopback-server",bytes(0));
    Peer client=connect(endpoint,ControlAuth.Role.CLIENT_CONTROLLER,"loopback-client",bytes(32));
    AtomicReference<BodyProofV2CandidateBridge<Object>> bridgeRef=new AtomicReference<>();
    AtomicReference<BodyProofIngress<Object>> ingressRef=new AtomicReference<>();
    AtomicReference<PendingBodyProof.VerifiedGameConnectionProof<Object>> proofRef=new AtomicReference<>();
    AtomicReference<String> originalExpiry=new AtomicReference<>();
    AtomicBoolean rawPayloadMatched=new AtomicBoolean();
    AtomicBoolean expiryOriginal=new AtomicBoolean();
    AtomicBoolean candidateObserved=new AtomicBoolean();
    AtomicInteger candidateAttempts=new AtomicInteger();
    AtomicBoolean proofVerified=new AtomicBoolean();
    Object gameConnection=new Object();
    try {
      server.open(frame -> {
        if(frame.messageKind().equals("BODY_BIND_ROUTE")) {
          byte[] expected=notice(proofRef.get().challenge());
          rawPayloadMatched.set(java.util.Arrays.equals(expected,frame.payload()));
          assertTrue(rawPayloadMatched.get());
        }
        bridgeRef.get().receive(frame);
      }, raw -> {
        PostAuthFrameCodec.Frame decoded=server.decodePeer(raw);
        if(decoded.messageKind().equals("BODY_BOUND_CANDIDATE")) {
          candidateAttempts.incrementAndGet();
          assertEquals("",decoded.sourceInstallId());assertEquals("",decoded.sourceConnectionId());
          assertEquals(server.connectionId(),decoded.recipientConnectionId());
          expiryOriginal.set(decoded.challengeExpiresAt().equals(originalExpiry.get()));
          assertTrue(expiryOriginal.get());
          Map<String,Object> payload;
          try { payload=StrictJson.decodeObject(decoded.payload()); }
          catch(StrictJson.DecodeException error){throw new IllegalStateException(error);}
          assertEquals(java.util.Set.of("security","kind","serverBootId","gameConnectionId","bodyId"),payload.keySet());
          assertEquals("BODY_BOUND",payload.get("kind"));assertEquals(BOOT,payload.get("serverBootId"));
          assertEquals(BODY,payload.get("bodyId"));candidateObserved.set(true);
        }
      });
      client.open(frame -> { throw new IllegalStateException("unexpected client application frame"); });
      PendingBodyProof.BodyChallenge challenge=onOwner(server.owner(),()->{
        BodyProofIngress<Object> ingress=new BodyProofIngress<>("loopback-server","loopback-client",BODY,
            new byte[32],BODY_KEY,server::isOwner,()->System.nanoTime()/1_000_000,
            ()->Instant.now().truncatedTo(ChronoUnit.MILLIS));
        BodyProofV2CandidateBridge<Object> bridge=new BodyProofV2CandidateBridge<>(ingress,server.session(),server::isOwner);
        BodyProofChannelHandler<Object,Object> handler=new BodyProofChannelHandler<>(server::isOwner,ignored->{});
        Object owner=new Object();var handle=handler.install(owner,ingress,(proof,connection)->{
          proofRef.set(proof);bridge.verified(proof,connection);
        }).orElseThrow();
        var issued=ingress.begin(()->gameConnection,BOOT).orElseThrow();
        var transcript=new BodyProofAuth.Transcript("loopback-server","loopback-client",BODY,BOOT,
            issued.gameConnectionId(),ControlAuth.decodeB32(issued.gameNonce()),Instant.parse(issued.issuedAt()),
            Instant.parse(issued.expiresAt()),new byte[32]);
        byte[] raw=BodyProofMessageCodec.encodeProof(new BodyProofMessageCodec.Proof(BOOT,
            issued.gameConnectionId(),BODY,"loopback-client",issued.gameNonce(),issued.issuedAt(),
            issued.expiresAt(),BodyProofAuth.proof(BODY_KEY,transcript)));
        var decision=handler.dispatch(owner,handle,
            ()->new BodyProofChannelHandler.Context<>(BodyProofChannelHandler.Direction.PLAY_TO_SERVER,gameConnection),
            BodyProofNetworkFrame.raw(raw));
        proofVerified.set(decision==BodyProofChannelHandler.Decision.VERIFIED);assertTrue(proofVerified.get());
        ingressRef.set(ingress);bridgeRef.set(bridge);
        originalExpiry.set(issued.expiresAt());
        return issued;
      });
      byte[] notice=notice(challenge);
      onOwner(client.owner(),()->{client.session().sendBusiness("BODY_BIND_NOTICE",notice,"","","","","");return null;});
      Map<String,Object> status=waitStatus(required("MINECRAFT_V2_STATUS_URL"));
      int candidateCount=Math.toIntExact((Long)status.get("nonAuthoritativeCandidateCount"));
      assertEquals(1,candidateCount);assertTrue(candidateObserved.get());assertEquals(1,candidateAttempts.get());
      boolean consumed=onOwner(server.owner(),()->{
        // The verifier-minted proof retained by the bridge is no longer acceptable after candidate emission.
        return proofRef.get()==null || !BodyProofIngressTestAccess.accept(ingressRef.get(),proofRef.get(),()->gameConnection);
      });
      assertTrue(consumed);
      System.out.println("V2_VERIFIED_BODY_PROOF_METRIC "+metric(proofVerified.get(),consumed,
          candidateCount,rawPayloadMatched.get(),expiryOriginal.get()));
    } finally {client.close();server.close();}
  }

  private static byte[] notice(PendingBodyProof.BodyChallenge c){return("{\"security\":\"luosandbox.control-auth.v1\","
      +"\"kind\":\"BODY_BIND_NOTICE\",\"serverBootId\":\""+c.serverBootId()+"\","
      +"\"gameConnectionId\":\""+c.gameConnectionId()+"\",\"bodyId\":\""+c.bodyId()+"\","
      +"\"gameNonce\":\""+c.gameNonce()+"\"}").getBytes(StandardCharsets.US_ASCII);}

  private static Peer connect(URI endpoint,ControlAuth.Role role,String peerId,byte[]psk)throws Exception{
    String keyId="loopback-"+role.name().toLowerCase();var manifest=manifest(role,peerId);var artifacts=manifest.verifyArtifacts(role,peerId,"loopback-hub",manifest.configHash(),MOD,POLICY,BINDING);
    BlockingQueue<String>inbound=new LinkedBlockingQueue<>();WebSocket socket=HttpClient.newHttpClient().newWebSocketBuilder().subprotocols(ControlDeploymentManifest.PROTOCOL).buildAsync(endpoint,new Listener(inbound)).get(6,TimeUnit.SECONDS);
    byte[]nonce=bytes(role==ControlAuth.Role.SERVER_AUTHORITY?64:96);socket.sendText(hello(role,peerId,keyId,artifacts,nonce),true).get(2,TimeUnit.SECONDS);Map<String,Object>challenge=object(take(inbound));
    var transcript=new ControlAuth.Transcript(role,peerId,"loopback-hub",keyId,ControlDeploymentManifest.PROTOCOL,artifacts.modManifestHash(),nonce,ControlAuth.decodeB32(string(challenge,"hubNonce")),string(challenge,"challengeId"),Instant.parse(string(challenge,"issuedAt")),Instant.parse(string(challenge,"expiresAt")),artifacts.configHash(),artifacts.bindingManifestHash());
    String proof=ControlAuth.b32(ControlAuth.hmac(psk,ControlAuth.transcript(ControlAuth.Label.PEER_PROOF,transcript)));socket.sendText("{\"security\":\"luosandbox.control-auth.v1\",\"kind\":\"PROOF\",\"challengeId\":\""+transcript.challengeId()+"\",\"peerProof\":\""+proof+"\"}",true).get(2,TimeUnit.SECONDS);object(take(inbound));return new Peer(socket,inbound,transcript,psk);}

  private static final class Peer{final WebSocket socket;final BlockingQueue<String>inbound;final ControlAuth.Transcript transcript;final byte[]psk;final ScheduledExecutorService owner=Executors.newSingleThreadScheduledExecutor();final AtomicReference<Thread>thread=new AtomicReference<>();PostAuthPeerSession session;
    Peer(WebSocket s,BlockingQueue<String>i,ControlAuth.Transcript t,byte[]p){socket=s;inbound=i;transcript=t;psk=p;}
    boolean isOwner(){return Thread.currentThread()==thread.get();}ScheduledExecutorService owner(){return owner;}PostAuthPeerSession session(){return session;}String connectionId(){if(isOwner())return session.connectionId();try{return onOwner(owner,session::connectionId);}catch(Exception error){throw new IllegalStateException(error);}}
    void open(Consumer<PostAuthFrameCodec.Frame>application)throws Exception{open(application,raw->{});}void open(Consumer<PostAuthFrameCodec.Frame>application,Consumer<byte[]>outbound)throws Exception{PostAuthSessionKeys keys=PostAuthSessionKeys.derive(psk,transcript);session=onOwner(owner,()->{thread.set(Thread.currentThread());return new PostAuthPeerSession(transcript,keys,1,this::isOwner,()->System.nanoTime()/1_000_000,event->{owner.execute(event);return true;},abort->{owner.execute(abort);return true;},reason->{},raw->{outbound.accept(raw.clone());return socket.sendText(new String(raw,StandardCharsets.US_ASCII),true).thenApply(x->null);},application::accept,new PostAuthPeerSession.Lifecycle(){public void fence(String r){}public void physicalClose(String r){}});});owner.scheduleAtFixedRate(()->{try{String raw;while((raw=inbound.poll())!=null)session.receive(raw.getBytes(StandardCharsets.UTF_8));session.tick();}catch(Throwable ignored){}},0,20,TimeUnit.MILLISECONDS);long d=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);while(onOwner(owner,session::state)!=PostAuthPeerSession.State.OPEN&&System.nanoTime()<d)Thread.sleep(10);assertEquals(PostAuthPeerSession.State.OPEN,onOwner(owner,session::state));}
    PostAuthFrameCodec.Frame decodePeer(byte[]raw){try(var keys=PostAuthSessionKeys.derive(psk,transcript)){return PostAuthFrameCodec.decode(raw,new PostAuthFrameCodec.Context(transcript,connectionId()),PostAuthFrameCodec.Direction.PEER_TO_HUB,keys);}}
    void close()throws Exception{if(session!=null)onOwner(owner,()->{session.close();return null;});socket.sendClose(1000,"done");owner.shutdownNow();owner.awaitTermination(3,TimeUnit.SECONDS);}}

  private static Map<String,Object>waitStatus(String url)throws Exception{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(6);while(System.nanoTime()<end){var response=HttpClient.newHttpClient().send(java.net.http.HttpRequest.newBuilder(URI.create(url)).GET().build(),java.net.http.HttpResponse.BodyHandlers.ofString());Map<String,Object>value=object(response.body());if(Long.valueOf(1).equals(value.get("nonAuthoritativeCandidateCount")))return value;Thread.sleep(50);}throw new AssertionError("verified proof status timeout");}
  private static String metric(boolean verified,boolean consumed,int count,boolean raw,boolean expiry)throws Exception{try(ByteArrayOutputStream out=new ByteArrayOutputStream();JsonGenerator json=new JsonFactory().createGenerator(out)){json.writeStartObject();json.writeStringField("scenario","VERIFIED_LP11_NOTICE_ROUTE_CANDIDATE");json.writeBooleanField("proofVerified",verified);json.writeBooleanField("proofConsumed",consumed);json.writeNumberField("candidateCount",count);json.writeBooleanField("rawPayloadMatched",raw);json.writeBooleanField("expiryOriginal",expiry);json.writeEndObject();json.close();return out.toString(StandardCharsets.UTF_8);}}
  private static String hello(ControlAuth.Role r,String p,String k,ControlDeploymentManifest.ArtifactMatch a,byte[]n){return"{\"security\":\"luosandbox.control-auth.v1\",\"kind\":\"HELLO\",\"peerRole\":\""+r+"\",\"peerInstallId\":\""+p+"\",\"hubInstallId\":\"loopback-hub\",\"keyId\":\""+k+"\",\"protocol\":\"LUO_CONTROL_POSTAUTH_V2\",\"modHash\":\""+ControlAuth.b32(a.modManifestHash())+"\",\"configHash\":\""+ControlAuth.b32(a.configHash())+"\",\"bindingHash\":\""+ControlAuth.b32(a.bindingManifestHash())+"\",\"peerNonce\":\""+ControlAuth.b32(n)+"\"}";}
  private static ControlDeploymentManifest manifest(ControlAuth.Role r,String p)throws Exception{String v="{\"schema\":\"luosandbox.minecraft-control-deployment.v2\",\"security\":\"luosandbox.control-auth.v1\",\"peerRole\":\""+r+"\",\"peerInstallId\":\""+p+"\",\"hubInstallId\":\"loopback-hub\",\"protocol\":\"LUO_CONTROL_POSTAUTH_V2\",\"postAuthProfile\":\"LUO_CONTROL_POSTAUTH_V2\",\"sessionKdfDomain\":\"LUO_CONTROL_SESSION_KEY_V2\",\"frameMacDomain\":\"LUO_CONTROL_FRAME_V2\",\"frameMaxUtf8Bytes\":4096,\"payloadMaxRawBytes\":2048,\"asciiIdMaxBytes\":128,\"outboundQueueFrames\":8,\"openTimeoutMs\":5000,\"heartbeatIntervalMs\":500,\"heartbeatTimeoutMs\":2000,\"routeCapacity\":2,\"routeTtlMs\":5000,\"bindingPendingCapacity\":8,\"storeOutstandingCapacity\":2,\"modManifestSha256\":\""+hex(MOD)+"\",\"policyConfigSha256\":\""+hex(POLICY)+"\",\"bindingManifestSha256\":\""+hex(BINDING)+"\"}\n";return ControlDeploymentManifest.parse(v.getBytes(StandardCharsets.US_ASCII));}
  private static String hex(byte[]v)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(v));}private static Map<String,Object>object(String v)throws Exception{return StrictJson.decodeObject(v.getBytes(StandardCharsets.UTF_8));}private static String string(Map<String,Object>v,String k){return(String)v.get(k);}private static String take(BlockingQueue<String>q)throws Exception{return q.poll(6,TimeUnit.SECONDS);}private static byte[]bytes(int s){byte[]v=new byte[32];for(int i=0;i<32;i++)v[i]=(byte)(s+i);return v;}private static String required(String n){String v=System.getenv(n);if(v==null||v.isBlank())throw new IllegalStateException();return v;}private static<T>T onOwner(ScheduledExecutorService o,Supplier<T>a)throws Exception{return o.submit(a::get).get(3,TimeUnit.SECONDS);}
  private static final class Listener implements WebSocket.Listener{final BlockingQueue<String>q;final StringBuilder b=new StringBuilder();Listener(BlockingQueue<String>q){this.q=q;}public void onOpen(WebSocket w){w.request(1);}public CompletionStage<?>onText(WebSocket w,CharSequence d,boolean l){b.append(d);if(l){q.add(b.toString());b.setLength(0);}w.request(1);return CompletableFuture.completedFuture(null);}public CompletionStage<?>onBinary(WebSocket w,ByteBuffer d,boolean l){return CompletableFuture.failedFuture(new IllegalStateException());}}
}
