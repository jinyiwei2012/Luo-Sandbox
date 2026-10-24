package io.github.jinyiwei2012.luosandbox.security;

import static org.junit.jupiter.api.Assertions.*;

import io.github.jinyiwei2012.luosandbox.transport.PostAuthPeerSession;
import io.github.jinyiwei2012.luosandbox.wire.StrictJson;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.lang.reflect.Field;
import org.junit.jupiter.api.Test;

class BodyProofV2CandidateBridgeTest {
  private static final String BOOT = "50000000-0000-4000-8000-000000000001";
  private static final String BODY = "70000000-0000-4000-8000-000000000001";
  private static final String ROUTE = "30000000-0000-4000-8000-000000000001";
  private static final String SOURCE = "40000000-0000-4000-8000-000000000001";
  private static final String CONNECTION = "20000000-0000-4000-8000-000000000001";
  private static final Instant NOW = Instant.parse("2026-10-23T00:00:00.000Z");

  @Test void verifierMintedProofConsumesOnceAndEmitsExactCandidate() throws Exception {
    Fixture f = new Fixture(); Object game = new Object();
    var proof = f.proof(game); f.bridge.verified(proof, game);
    byte[] route = f.routeRaw(proof, 1);
    f.session.receive(route);
    assertFalse(BodyProofIngressTestAccess.accept(f.ingress, proof, () -> game));
    assertEquals(2, f.session.queuedFrames()); // open ACK in-flight plus queued candidate
    f.complete(); f.run();
    assertEquals(2, f.sent.size());
    PostAuthFrameCodec.Frame candidate = f.decode(f.sent.get(1));
    assertEquals("BODY_BOUND_CANDIDATE", candidate.messageKind());
    assertEquals(ROUTE, candidate.routeId());
    assertEquals("", candidate.sourceInstallId()); assertEquals("", candidate.sourceConnectionId());
    assertEquals(CONNECTION, candidate.recipientConnectionId());
    assertEquals(proof.challenge().expiresAt(), candidate.challengeExpiresAt());
    Map<String,Object> payload = StrictJson.decodeObject(candidate.payload());
    assertEquals(java.util.Set.of("security","kind","serverBootId","gameConnectionId","bodyId"), payload.keySet());
    assertEquals("BODY_BOUND", payload.get("kind")); assertEquals(BODY, payload.get("bodyId"));
    int sent = f.sent.size();
    assertDoesNotThrow(() -> f.session.receive(route));
    assertEquals(PostAuthPeerSession.State.CLOSED, f.session.state());
    assertEquals(sent, f.sent.size());
  }

  @Test void bridgeCandidateQueueFullConsumesProofThenFencesWithoutSequenceHole() {
    Fixture f = new Fixture(); Object game = new Object(); var proof = f.proof(game);
    f.bridge.verified(proof, game);
    for (int index = 0; index < 7; index++) {
      f.session.sendBusiness("BODY_BOUND_CANDIDATE", candidatePayload(), ROUTE, "", "",
          CONNECTION, proof.challenge().expiresAt());
    }
    assertEquals(8, f.session.queuedFrames()); assertEquals(8, f.session.nextOutboundSequence());
    assertDoesNotThrow(() -> f.session.receive(f.routeRaw(proof, 1)));
    assertFalse(BodyProofIngressTestAccess.accept(f.ingress, proof, () -> game));
    assertEquals(PostAuthPeerSession.State.CLOSED, f.session.state());
    assertEquals(8, f.session.nextOutboundSequence());
    assertBefore(f.lifecycle, "fence:outbound_queue_full", "close:outbound_queue_full");
    assertEquals(1, f.sent.size());
  }

  @Test void bridgeCandidateSynchronousSendFailureConsumesProofAndDoesNotRetry() {
    Fixture f = new Fixture(); Object game = new Object(); var proof = f.proof(game);
    f.bridge.verified(proof, game); f.complete(); f.run();
    f.failSendAt = 1;
    assertThrows(AssertionError.class, () -> f.session.receive(f.routeRaw(proof, 1)));
    assertFalse(BodyProofIngressTestAccess.accept(f.ingress, proof, () -> game));
    assertEquals(2, f.sendAttempts); assertEquals(1, f.sent.size());
    assertEquals(PostAuthPeerSession.State.CLOSED, f.session.state());
    assertBefore(f.lifecycle, "fence:send_start_failed", "close:send_start_failed");
    assertThrows(IllegalStateException.class, () -> f.session.receive(f.routeRaw(proof, 2)));
    assertEquals(2, f.sendAttempts);
  }

  @Test void bridgeCandidateAsyncFailureAndCancellationConsumeProofWithoutRetry() {
    for (boolean cancelled : new boolean[] {false, true}) {
      Fixture f = new Fixture(); Object game = new Object(); var proof = f.proof(game);
      f.bridge.verified(proof, game); f.complete(); f.run();
      f.session.receive(f.routeRaw(proof, 1));
      assertFalse(BodyProofIngressTestAccess.accept(f.ingress, proof, () -> game));
      assertEquals(2, f.sent.size()); assertEquals(2, f.sendAttempts);
      if (cancelled) f.futures.get(1).cancel(false);
      else f.futures.get(1).completeExceptionally(new RuntimeException("candidate send failed"));
      f.run();
      assertEquals(PostAuthPeerSession.State.CLOSED, f.session.state());
      assertBefore(f.lifecycle, "fence:send_completion_failed", "close:send_completion_failed");
      assertEquals(2, f.sent.size()); assertEquals(2, f.sendAttempts);
      assertThrows(IllegalStateException.class, () -> f.session.receive(f.routeRaw(proof, 2)));
      assertEquals(2, f.sendAttempts);
    }
  }

  @Test void exactDeadlineWrongConnectionMetadataAndRouteFirstFailClosed() {
    Fixture f = new Fixture(); Object actual = new EqualConnection(); Object equal = new EqualConnection();
    var proof = f.proof(actual);
    assertThrows(BodyProofV2CandidateBridge.CandidateBridgeException.class,
        () -> f.bridge.verified(proof, equal));
    assertThrows(BodyProofV2CandidateBridge.CandidateBridgeException.class,
        () -> f.bridge.receive(f.route(proof)));

    Fixture expired = new Fixture(); Object game = new Object(); var expiring = expired.proof(game);
    expired.bridge.verified(expiring, game); expired.bodyNow = 5_000;
    assertThrows(BodyProofV2CandidateBridge.CandidateBridgeException.class,
        () -> expired.bridge.receive(expired.route(expiring)));
    assertEquals(1, expired.sent.size());
  }

  @Test void disconnectUsesIdentityAndCloseInvalidatesAllBoundedProofs() {
    Fixture f = new Fixture(); Object first = new EqualConnection(); Object equal = new EqualConnection();
    var proof = f.proof(first); f.bridge.verified(proof, first);
    f.bridge.disconnect(equal);
    f.bridge.receive(f.route(proof));
    Fixture closed = new Fixture(); Object a = new Object(); Object b = new Object();
    var pa = closed.proof(a); var pb = closed.proof(b);
    closed.bridge.verified(pa, a); closed.bridge.verified(pb, b);
    closed.bridge.close(); closed.bridge.close();
    assertThrows(BodyProofV2CandidateBridge.CandidateBridgeException.class,
        () -> closed.bridge.receive(closed.route(pa)));
  }

  @Test void disconnectFatalInvalidationRemovesAllBeforeAttemptsAndRetriesNone() throws Exception {
    Fixture f = new Fixture(); Object connection = new Object();
    var old = f.proof(connection); f.bridge.verified(old, connection);
    var fresh = f.proof(connection); f.bridge.verified(fresh, connection);
    Field field = BodyProofV2CandidateBridge.class.getDeclaredField("invalidator"); field.setAccessible(true);
    var attempted = new ArrayList<PendingBodyProof.VerifiedGameConnectionProof<Object>>();
    Object invalidator = java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
        new Class<?>[] {field.getType()}, (proxy, method, args) -> {
          attempted.add((PendingBodyProof.VerifiedGameConnectionProof<Object>) args[0]);
          if (attempted.size() == 1) throw new AssertionError("fatal invalidation");
          return Boolean.TRUE;
        });
    field.set(f.bridge, invalidator);
    assertThrows(AssertionError.class, () -> f.bridge.disconnect(connection));
    assertEquals(2, attempted.size());
    assertDoesNotThrow(() -> f.bridge.disconnect(connection));
    assertEquals(2, attempted.size());
    assertThrows(BodyProofV2CandidateBridge.CandidateBridgeException.class,
        () -> f.bridge.receive(f.route(fresh)));
  }

  @Test void capacityDuplicateForeignIngressAndAmbiguousMatchesFailClosed() throws Exception {
    Fixture f = new Fixture(); Object a = new Object(); Object b = new Object(); Object c = new Object();
    var pa=f.proof(a);var pb=f.proof(b);var pc=new Fixture().proof(c);
    f.bridge.verified(pa,a);f.bridge.verified(pb,b);
    assertThrows(BodyProofV2CandidateBridge.CandidateBridgeException.class,()->f.bridge.verified(pc,c));
    assertThrows(BodyProofV2CandidateBridge.CandidateBridgeException.class,()->f.bridge.verified(pa,a));

    Fixture foreign = new Fixture(); Object x=new Object(); var foreignProof=foreign.proof(x);
    f.bridge.close(); Fixture target=new Fixture(); target.bridge.verified(foreignProof,x);
    assertThrows(BodyProofV2CandidateBridge.CandidateBridgeException.class,
        ()->target.bridge.receive(target.route(foreignProof)));

    Fixture ambiguous=new Fixture();Object one=new Object();Object two=new Object();
    var first=ambiguous.proof(one);var second=ambiguous.proof(two);
    Field challenge=PendingBodyProof.VerifiedGameConnectionProof.class.getDeclaredField("challenge");
    challenge.setAccessible(true);challenge.set(second,first.challenge());
    ambiguous.bridge.verified(first,one);ambiguous.bridge.verified(second,two);
    assertThrows(BodyProofV2CandidateBridge.CandidateBridgeException.class,
        ()->ambiguous.bridge.receive(ambiguous.route(first)));
    assertEquals(1,ambiguous.sent.size());
  }

  @Test void deadlineMinusOneEmitsAfterHeartbeatsAndExactDeadlineRejects() {
    Fixture valid=new Fixture();Object game=new Object();var proof=valid.proof(game);valid.bridge.verified(proof,game);
    valid.now=1_999;valid.session.receive(valid.hubHeartbeat(1));
    valid.now=3_998;valid.session.receive(valid.hubHeartbeat(2));
    valid.now=4_999;valid.bodyNow=4_999;valid.bridge.receive(valid.route(proof));
    assertEquals(2,valid.session.queuedFrames());
    Fixture expired=new Fixture();Object other=new Object();var late=expired.proof(other);expired.bridge.verified(late,other);
    expired.now=1_999;expired.session.receive(expired.hubHeartbeat(1));
    expired.now=3_998;expired.session.receive(expired.hubHeartbeat(2));
    expired.now=4_999;expired.bodyNow=5_000;
    assertThrows(BodyProofV2CandidateBridge.CandidateBridgeException.class,()->expired.bridge.receive(expired.route(late)));
    assertEquals(1,expired.sent.size());
  }

  @Test void wrongRouteMetadataAndTupleFieldsNeverEmitCandidate() {
    java.util.List<java.util.function.BiFunction<Fixture,
        PendingBodyProof.VerifiedGameConnectionProof<Object>,PostAuthFrameCodec.Frame>> variants = java.util.List.of(
        (f,p)->f.route(p,"other",SOURCE,CONNECTION,"",ROUTE),
        (f,p)->f.route(p,"client","bad",CONNECTION,"",ROUTE),
        (f,p)->f.route(p,"client",SOURCE,SOURCE,"",ROUTE),
        (f,p)->f.route(p,"client",SOURCE,CONNECTION,"", "bad"),
        (f,p)->f.route(p,"client",SOURCE,CONNECTION,"2026-10-23T00:00:01.000Z",ROUTE),
        (f,p)->f.routePayload(p,"serverBootId",BODY),
        (f,p)->f.routePayload(p,"gameConnectionId",BODY),
        (f,p)->f.routePayload(p,"bodyId",BOOT),
        (f,p)->f.routePayload(p,"gameNonce",ControlAuth.b32(new byte[32])));
    for(var variant:variants){Fixture f=new Fixture();Object game=new Object();var proof=f.proof(game);f.bridge.verified(proof,game);
      assertThrows(BodyProofV2CandidateBridge.CandidateBridgeException.class,()->f.bridge.receive(variant.apply(f,proof)));
      assertEquals(1,f.sent.size());}
  }

  private static final class Fixture {
    long now; long bodyNow; boolean owner = true;
    int sendAttempts; int failSendAt = -1;
    final ArrayDeque<Runnable> posted = new ArrayDeque<>(); final List<byte[]> sent = new ArrayList<>();
    final List<CompletableFuture<Void>> futures = new ArrayList<>();
    final List<String> lifecycle = new ArrayList<>();
    final ControlAuth.Transcript transcript = transcript(); final PostAuthSessionKeys keys = PostAuthSessionKeys.derive(bytes(96), transcript);
    final BodyProofIngress<Object> ingress = new BodyProofIngress<>("server", "client", BODY, new byte[32], new byte[32],
        () -> owner, () -> bodyNow, () -> NOW.plusMillis(bodyNow));
    final AtomicReference<BodyProofV2CandidateBridge<Object>> holder = new AtomicReference<>();
    final PostAuthPeerSession session = new PostAuthPeerSession(transcript, keys, 7, () -> owner, () -> now,
        event -> { posted.add(event); return true; }, abort -> { posted.add(abort); return true; }, reason -> {},
        raw -> { int attempt=sendAttempts++; if(attempt==failSendAt)throw new AssertionError("candidate sender fatal"); sent.add(raw.clone()); var future=new CompletableFuture<Void>(); futures.add(future); return future; },
        frame -> holder.get().receive(frame), new PostAuthPeerSession.Lifecycle(){public void fence(String reason){lifecycle.add("fence:"+reason);} public void physicalClose(String reason){lifecycle.add("close:"+reason);}});
    final BodyProofV2CandidateBridge<Object> bridge;
    Fixture(){ session.receive(hubOpen()); bridge=new BodyProofV2CandidateBridge<>(ingress,session,()->owner);holder.set(bridge); }
    PendingBodyProof.VerifiedGameConnectionProof<Object> proof(Object connection){
      var challenge=ingress.begin(()->connection,BOOT).orElseThrow();
      var t=new BodyProofAuth.Transcript("server","client",BODY,BOOT,challenge.gameConnectionId(),ControlAuth.decodeB32(challenge.gameNonce()),Instant.parse(challenge.issuedAt()),Instant.parse(challenge.expiresAt()),new byte[32]);
      var p=new BodyProofMessageCodec.Proof(BOOT,challenge.gameConnectionId(),BODY,"client",challenge.gameNonce(),challenge.issuedAt(),challenge.expiresAt(),BodyProofAuth.proof(new byte[32],t));
      return ingress.handleProof(()->connection,BodyProofMessageCodec.encodeProof(p)).proof().orElseThrow();
    }
    PostAuthFrameCodec.Frame route(PendingBodyProof.VerifiedGameConnectionProof<Object> proof){
      return route(proof,"client",SOURCE,CONNECTION,"",ROUTE);
    }
    byte[] routeRaw(PendingBodyProof.VerifiedGameConnectionProof<Object> proof,long sequence){
      var frame=route(proof);var sequenced=new PostAuthFrameCodec.Frame(frame.connectionId(),frame.challengeId(),sequence,frame.messageKind(),frame.routeId(),frame.sourceInstallId(),frame.sourceConnectionId(),frame.recipientConnectionId(),frame.challengeExpiresAt(),frame.payload(),"");
      try(var hub=PostAuthSessionKeys.derive(bytes(96),transcript)){return PostAuthFrameCodec.encode(sequenced,new PostAuthFrameCodec.Context(transcript,CONNECTION),PostAuthFrameCodec.Direction.HUB_TO_PEER,hub);}}
    PostAuthFrameCodec.Frame route(PendingBodyProof.VerifiedGameConnectionProof<Object> proof,String install,String source,String recipient,String expiry,String route){
      String payload="{\"security\":\"luosandbox.control-auth.v1\",\"kind\":\"BODY_BIND_NOTICE\",\"serverBootId\":\""+proof.challenge().serverBootId()+"\",\"gameConnectionId\":\""+proof.challenge().gameConnectionId()+"\",\"bodyId\":\""+proof.challenge().bodyId()+"\",\"gameNonce\":\""+proof.challenge().gameNonce()+"\"}";
      return new PostAuthFrameCodec.Frame(CONNECTION,transcript.challengeId(),1,"BODY_BIND_ROUTE",route,install,source,recipient,expiry,payload.getBytes(StandardCharsets.US_ASCII),"");
    }
    PostAuthFrameCodec.Frame routePayload(PendingBodyProof.VerifiedGameConnectionProof<Object> proof,String key,String replacement){
      String payload="{\"security\":\"luosandbox.control-auth.v1\",\"kind\":\"BODY_BIND_NOTICE\",\"serverBootId\":\""+(key.equals("serverBootId")?replacement:proof.challenge().serverBootId())+"\",\"gameConnectionId\":\""+(key.equals("gameConnectionId")?replacement:proof.challenge().gameConnectionId())+"\",\"bodyId\":\""+(key.equals("bodyId")?replacement:proof.challenge().bodyId())+"\",\"gameNonce\":\""+(key.equals("gameNonce")?replacement:proof.challenge().gameNonce())+"\"}";
      return new PostAuthFrameCodec.Frame(CONNECTION,transcript.challengeId(),1,"BODY_BIND_ROUTE",ROUTE,"client",SOURCE,CONNECTION,"",payload.getBytes(StandardCharsets.US_ASCII),"");}
    byte[] hubOpen(){try(var hub=PostAuthSessionKeys.derive(bytes(96),transcript)){var frame=new PostAuthFrameCodec.Frame(CONNECTION,transcript.challengeId(),0,"SESSION_OPEN","","","","","",PostAuthFrameCodec.sessionOpenPayload(CONNECTION).getBytes(StandardCharsets.US_ASCII),"");return PostAuthFrameCodec.encode(frame,new PostAuthFrameCodec.Context(transcript,null),PostAuthFrameCodec.Direction.HUB_TO_PEER,hub);}}
    byte[] hubHeartbeat(long sequence){try(var hub=PostAuthSessionKeys.derive(bytes(96),transcript)){var frame=new PostAuthFrameCodec.Frame(CONNECTION,transcript.challengeId(),sequence,"HEARTBEAT","","","","","","{}".getBytes(StandardCharsets.US_ASCII),"");return PostAuthFrameCodec.encode(frame,new PostAuthFrameCodec.Context(transcript,CONNECTION),PostAuthFrameCodec.Direction.HUB_TO_PEER,hub);}}
    PostAuthFrameCodec.Frame decode(byte[] raw){return PostAuthFrameCodec.decode(raw,new PostAuthFrameCodec.Context(transcript,CONNECTION),PostAuthFrameCodec.Direction.PEER_TO_HUB,keys);}
    void complete(){futures.get(0).complete(null);} void run(){while(!posted.isEmpty())posted.removeFirst().run();}
  }
  private static ControlAuth.Transcript transcript(){return new ControlAuth.Transcript(ControlAuth.Role.SERVER_AUTHORITY,"server","hub","key","LUO_CONTROL_POSTAUTH_V2",bytes(32),bytes(64),bytes(96),"10000000-0000-4000-8000-000000000001",NOW,NOW.plusSeconds(5),bytes(128),bytes(160));}
  private static byte[] bytes(int start){byte[] value=new byte[32];for(int i=0;i<32;i++)value[i]=(byte)(start+i);return value;}
  private static byte[] candidatePayload(){return("{\"security\":\"luosandbox.control-auth.v1\",\"kind\":\"BODY_BOUND\",\"serverBootId\":\""+BOOT+"\",\"gameConnectionId\":\"60000000-0000-4000-8000-000000000001\",\"bodyId\":\""+BODY+"\"}").getBytes(StandardCharsets.US_ASCII);}
  private static void assertBefore(List<String> values,String first,String second){assertTrue(values.indexOf(first)>=0&&values.indexOf(first)<values.indexOf(second));}
  private static final class EqualConnection { public boolean equals(Object other){return other instanceof EqualConnection;} public int hashCode(){return 1;} }
}
