package io.github.jinyiwei2012.luosandbox.transport;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import io.github.jinyiwei2012.luosandbox.security.ControlAuth;
import io.github.jinyiwei2012.luosandbox.security.ControlDeploymentManifest;
import io.github.jinyiwei2012.luosandbox.security.PostAuthFrameCodec;
import io.github.jinyiwei2012.luosandbox.security.PostAuthSessionKeys;
import io.github.jinyiwei2012.luosandbox.wire.StrictJson;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

/** Actual Java peer half of fixture-backed continuous session interop. */
class PostAuthSessionInteropTest {
  private static final String SCHEMA = "postauth-session-v2-interop-v1";
  private static final HexFormat HEX = HexFormat.of();

  @Test void consumesPythonHubFramesAndExportsPeerCausality() throws Exception {
    String inputValue=System.getenv("LUOSANDBOX_POSTAUTH_SESSION_INPUT"); String outputValue=System.getenv("LUOSANDBOX_POSTAUTH_SESSION_OUTPUT");
    if(inputValue==null||inputValue.isBlank()||outputValue==null||outputValue.isBlank()) return;
    Path input=approved(Path.of(inputValue)),output=approved(Path.of(outputValue)); Map<String,Object> doc=readObject(input); assertEquals(SCHEMA,doc.get("schema")); List<?> traces=assertInstanceOf(List.class,doc.get("traces")); assertEquals(2,traces.size()); List<Result> results=new ArrayList<>(); for(Object raw:traces) results.add(run(cast(raw)));
    Files.createDirectories(output.getParent()); try(JsonGenerator json=new JsonFactory().createGenerator(Files.newOutputStream(output))){json.writeStartObject();json.writeStringField("schema",SCHEMA);json.writeObjectFieldStart("invocation");json.writeStringField("testClass",getClass().getName());json.writeBooleanField("forcedRerunRequired",true);json.writeStringField("executionAttestation","SEPARATE_RUNNER_OBSERVATION_REQUIRED");json.writeEndObject();json.writeObjectFieldStart("loadedClasses");for(Class<?> type:new Class<?>[]{ControlAuth.class,ControlDeploymentManifest.class,PostAuthSessionKeys.class,PostAuthFrameCodec.class,PostAuthPeerSession.class,StrictJson.class})writeClass(json,type);json.writeEndObject();json.writeArrayFieldStart("traces");for(Result result:results)result.write(json);json.writeEndArray();json.writeEndObject();}
  }

  private static Result run(Map<String,Object> value){String name=string(value,"name");ControlAuth.Transcript transcript=transcript(cast(value.get("transcript")));byte[] psk=HEX.parseHex(string(value,"pskHex"));Harness harness=new Harness(transcript,psk);PostAuthPeerSession session=harness.session();session.receive(HEX.parseHex(string(value,"pythonOpenHex")));String ack=HEX.formatHex(harness.sent.get(0));harness.complete();harness.run();session.receive(HEX.parseHex(string(value,"pythonHeartbeatHex")));harness.now=500;session.tick();String heartbeat=HEX.formatHex(harness.sent.get(1));harness.completeAt(1);harness.run();String business=null;if(transcript.peerRole()==ControlAuth.Role.CLIENT_CONTROLLER){session.sendBusiness("BODY_BIND_NOTICE",notice(),"","","","","");business=HEX.formatHex(harness.sent.get(2));}
    Harness timeout=new Harness(transcript,psk);PostAuthPeerSession timeoutSession=timeout.session();timeoutSession.receive(HEX.parseHex(string(value,"pythonOpenHex")));timeout.now=1999;if(value.get("pythonBusinessHex") instanceof String businessHex)timeoutSession.receive(HEX.parseHex(businessHex));timeout.now=2000;boolean timeoutClosed=false;try{timeoutSession.tick();}catch(IllegalStateException expected){timeoutClosed=timeoutSession.state()==PostAuthPeerSession.State.CLOSED;}
    Harness failed=new Harness(transcript,psk);PostAuthPeerSession failedSession=failed.session();failedSession.receive(HEX.parseHex(string(value,"pythonOpenHex")));failed.futures.get(0).completeExceptionally(new RuntimeException("fail"));failed.run();boolean sendFailureClosed=failedSession.state()==PostAuthPeerSession.State.CLOSED;return new Result(name,ack,heartbeat,business,timeoutClosed,sendFailureClosed);}

  private static byte[] notice(){return("{\"security\":\"luosandbox.control-auth.v1\",\"kind\":\"BODY_BIND_NOTICE\",\"serverBootId\":\"50000000-0000-4000-8000-000000000001\",\"gameConnectionId\":\"60000000-0000-4000-8000-000000000001\",\"bodyId\":\"70000000-0000-4000-8000-000000000001\",\"gameNonce\":\"AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8\"}").getBytes(StandardCharsets.US_ASCII);}
  private static void writeClass(JsonGenerator json,Class<?> type)throws Exception{String name=type.getSimpleName();json.writeObjectFieldStart(name);json.writeStringField("resource",String.valueOf(type.getResource(name+".class")));try(var stream=type.getResourceAsStream(name+".class")){assertNotNull(stream);json.writeStringField("sha256",HEX.formatHex(MessageDigest.getInstance("SHA-256").digest(stream.readAllBytes())));}json.writeEndObject();}
  private static ControlAuth.Transcript transcript(Map<String,Object> v){return new ControlAuth.Transcript(ControlAuth.Role.valueOf(string(v,"role")),string(v,"peerInstallId"),string(v,"hubInstallId"),string(v,"keyId"),string(v,"protocol"),HEX.parseHex(string(v,"modHashHex")),HEX.parseHex(string(v,"peerNonceHex")),HEX.parseHex(string(v,"hubNonceHex")),string(v,"challengeId"),Instant.parse(string(v,"issuedAt")),Instant.parse(string(v,"expiresAt")),HEX.parseHex(string(v,"configHashHex")),HEX.parseHex(string(v,"bindingHashHex")));}
  private static final class Harness{long now;final ControlAuth.Transcript transcript;final byte[]psk;final List<byte[]>sent=new ArrayList<>();final List<CompletableFuture<Void>>futures=new ArrayList<>();final ArrayDeque<Runnable>posted=new ArrayDeque<>();PostAuthSessionKeys keys;Harness(ControlAuth.Transcript transcript,byte[]psk){this.transcript=transcript;this.psk=psk;}PostAuthPeerSession session(){keys=PostAuthSessionKeys.derive(psk,transcript);return new PostAuthPeerSession(transcript,keys,1,()->true,()->now,event->{posted.add(event);return true;},abort->{posted.add(abort);return true;},reason->{},raw->{sent.add(raw.clone());var f=new CompletableFuture<Void>();futures.add(f);return f;},frame->{},new PostAuthPeerSession.Lifecycle(){public void fence(String reason){}public void physicalClose(String reason){}});}void complete(){completeAt(0);}void completeAt(int index){futures.get(index).complete(null);}void run(){while(!posted.isEmpty())posted.removeFirst().run();}}
  private record Result(String name,String peerAckHex,String peerHeartbeatHex,String peerBusinessHex,boolean peerTimeoutClosed,boolean peerSendFailureClosed){void write(JsonGenerator j)throws Exception{j.writeStartObject();j.writeStringField("name",name);j.writeStringField("peerAckHex",peerAckHex);j.writeStringField("peerHeartbeatHex",peerHeartbeatHex);if(peerBusinessHex==null)j.writeNullField("peerBusinessHex");else j.writeStringField("peerBusinessHex",peerBusinessHex);j.writeBooleanField("peerTimeoutClosed",peerTimeoutClosed);j.writeBooleanField("peerSendFailureClosed",peerSendFailureClosed);j.writeEndObject();}}
  private static Path approved(Path p){Path path=p.toAbsolutePath().normalize(),root=Path.of("..","..",".gradle-user-home","interop").toAbsolutePath().normalize();assertTrue(path.startsWith(root));return path;}
  private static Map<String,Object>readObject(Path p)throws Exception{try(JsonParser parser=new JsonFactory().createParser(Files.readAllBytes(p))){assertEquals(JsonToken.START_OBJECT,parser.nextToken());return object(parser);}}
  private static Map<String,Object>object(JsonParser p)throws Exception{Map<String,Object>r=new LinkedHashMap<>();while(p.nextToken()!=JsonToken.END_OBJECT){String n=p.currentName();JsonToken t=p.nextToken();assertNull(r.put(n,value(p,t)));}return r;}
  private static List<Object>array(JsonParser p)throws Exception{List<Object>r=new ArrayList<>();JsonToken t;while((t=p.nextToken())!=JsonToken.END_ARRAY)r.add(value(p,t));return r;}
  private static Object value(JsonParser p,JsonToken t)throws Exception{return switch(t){case START_OBJECT->object(p);case START_ARRAY->array(p);case VALUE_STRING->p.getText();case VALUE_NUMBER_INT->p.getLongValue();case VALUE_TRUE->true;case VALUE_FALSE->false;case VALUE_NULL->null;default->throw new IllegalArgumentException();};}
  @SuppressWarnings("unchecked")private static Map<String,Object>cast(Object v){return(Map<String,Object>)assertInstanceOf(Map.class,v);}private static String string(Map<String,Object>v,String n){return assertInstanceOf(String.class,v.get(n));}
}
