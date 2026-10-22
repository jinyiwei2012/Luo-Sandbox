package io.github.jinyiwei2012.luosandbox.wire;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Canonical v1 message syntax and closed payload validation, not binding or runtime authorization. */
public final class CanonicalMessageCodec {
  private static final String PROTOCOL = "luosandbox.minecraft.v1";
  private static final Set<String> ENVELOPE = Set.of("protocol", "type", "actorId", "worldId", "bodyId", "sessionId", "sessionEpoch", "messageId", "timestamp", "payload");
  private static final Set<String> TYPES = Set.of("observation", "request", "admission", "cancel", "cancel_result", "receipt", "reconcile", "reconcile_result");
  private static final Set<String> REASONS = Set.of("BINDING_MISMATCH", "STALE_EPOCH", "DEADLINE_EXPIRED", "ID_CONFLICT", "RATE_LIMIT", "BACKPRESSURE", "STALE_BASIS", "AUDIENCE_DENIED", "CAPABILITY_UNAVAILABLE", "REGION_DENIED", "UNSAFE_TARGET", "BODY_BUSY", "QUARANTINED", "TARGET_TERMINAL", "TARGET_NOT_FOUND", "OUTCOME_UNPROVEN", "HISTORY_UNAVAILABLE", "QUIESCENCE_UNPROVEN", "STUCK", "EXECUTION_REJECTED", "CANCEL_CONFIRMED", "CONTROL_LOST");
  private static final Set<String> GENERIC = Set.of("BINDING_MISMATCH", "STALE_EPOCH", "DEADLINE_EXPIRED", "ID_CONFLICT", "RATE_LIMIT", "BACKPRESSURE");
  private static final Pattern ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}");
  private static final Pattern UUID = Pattern.compile("(?!00000000-0000-0000-0000-000000000000$)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
  private static final Pattern DIMENSION = Pattern.compile("[a-z0-9_.-]+:[a-z0-9/._-]+");
  private static final Pattern UTC = Pattern.compile("(?!0000)(\\d{4})-(\\d{2})-(\\d{2})T(\\d{2}):(\\d{2}):(\\d{2})\\.(\\d{3})Z");
  private static final DateTimeFormatter UTC_FORMAT = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);
  private static final JsonFactory GENERATOR = JsonFactory.builder().disable(JsonFactory.Feature.CHARSET_DETECTION).build();

  private CanonicalMessageCodec() {}

  public static CanonicalEnvelope decode(byte[] bytes) throws WireException {
    try {
      return validate(copyObject(StrictJson.decodeObject(bytes)));
    } catch (StrictJson.DecodeException error) {
      throw new WireException(ErrorCategory.LEXICAL, "Invalid JSON frame");
    }
  }

  public static byte[] encode(CanonicalEnvelope envelope) throws WireException {
    if (envelope == null) throw semantic("Envelope is required");
    Map<String, Object> root = new LinkedHashMap<>();
    root.put("protocol", envelope.protocol()); root.put("type", envelope.type()); root.put("actorId", envelope.actorId());
    root.put("worldId", envelope.worldId()); root.put("bodyId", envelope.bodyId()); root.put("sessionId", envelope.sessionId());
    root.put("sessionEpoch", envelope.sessionEpoch()); root.put("messageId", envelope.messageId()); root.put("timestamp", formatUtc(envelope.timestamp())); root.put("payload", thaw(envelope.payload()));
    CanonicalEnvelope normalized = validate(root);
    try (ByteArrayOutputStream output = new ByteArrayOutputStream(); JsonGenerator generator = GENERATOR.createGenerator(output)) {
      write(generator, toMap(normalized)); generator.flush();
      byte[] result = output.toByteArray();
      if (result.length > StrictJson.MAX_FRAME_BYTES) throw lexical("Frame exceeds byte limit");
      return result;
    } catch (IOException error) { throw semantic("Cannot encode envelope"); }
  }

  private static CanonicalEnvelope validate(Map<String, Object> root) throws WireException {
    exact(root, ENVELOPE); String type = string(root.get("type"));
    if (!PROTOCOL.equals(root.get("protocol")) || !TYPES.contains(type)) throw semantic("Unsupported protocol or type");
    id(root.get("actorId")); id(root.get("worldId")); uuid(root.get("bodyId")); uuid(root.get("sessionId")); uuid(root.get("messageId"));
    long epoch = i63(root.get("sessionEpoch")); Instant timestamp = utc(root.get("timestamp"));
    Map<String, Object> payload = object(root.get("payload"));
    switch (type) {
      case "observation" -> observation(payload);
      case "request" -> request(payload);
      case "admission" -> admission(payload);
      case "cancel" -> cancel(payload);
      case "cancel_result" -> cancelResult(payload);
      case "receipt" -> receipt(payload);
      case "reconcile" -> reconcile(payload);
      case "reconcile_result" -> reconcileResult(payload);
      default -> throw semantic("Unsupported type");
    }
    if (Set.of("request", "cancel", "reconcile").contains(type)) deadline(timestamp, utc(payload.get("deadline")));
    if ((type.equals("cancel") && equal(payload.get("targetRequestId"), root.get("messageId")))
        || (type.equals("admission") && equal(payload.get("requestId"), root.get("messageId")))
        || (type.equals("receipt") && equal(payload.get("requestId"), root.get("messageId")))
        || (type.equals("cancel_result") && equal(payload.get("cancelId"), root.get("messageId")))
        || (type.equals("reconcile_result") && equal(payload.get("reconcileId"), root.get("messageId")))) throw semantic("Message cannot correlate to itself");
    return new CanonicalEnvelope(PROTOCOL, type, (String) root.get("actorId"), (String) root.get("worldId"), (String) root.get("bodyId"), (String) root.get("sessionId"), epoch, (String) root.get("messageId"), timestamp, freeze(payload));
  }

  private static void observation(Map<String, Object> p) throws WireException {
    exact(p, Set.of("stamp", "audience", "source", "freshness", "state", "capabilities", "untrustedText"));
    if (!"PUBLIC".equals(p.get("audience")) || !"SERVER_AUTHORITATIVE".equals(p.get("source")) || !Set.of("CURRENT", "STALE", "UNKNOWN").contains(p.get("freshness"))) throw semantic("Invalid observation labels");
    stamp(p.get("stamp")); state(p.get("state")); Map<String,Object> caps=object(p.get("capabilities")); exact(caps, Set.of("QUERY","PUBLIC_SAY","LOOK","BOUNDED_WALK","STOP")); for(Object v:caps.values()) enumValue(v, Set.of("AVAILABLE","UNAVAILABLE","UNKNOWN"));
    List<Object> text=list(p.get("untrustedText")); if(text.size()>8) throw semantic("Too many text items"); for(Object v:text){Map<String,Object>x=object(v);exact(x,Set.of("eventId","text"));uuid(x.get("eventId"));text(x.get("text"),1024,4096,true);}
  }
  private static void request(Map<String,Object> p) throws WireException { exact(p,Set.of("deadline","basis","intent")); utc(p.get("deadline")); if(p.get("basis")!=null) basis(p.get("basis")); Map<String,Object> i=object(p.get("intent")); String kind=string(i.get("kind")); Set<String> keys=switch(kind){case "QUERY"->Set.of("kind","queryKind");case "PUBLIC_SAY"->Set.of("kind","message");case "LOOK"->Set.of("kind","dimensionId","target");case "BOUNDED_WALK"->Set.of("kind","dimensionId","target","maximumDistance");default->throw semantic("Invalid intent");}; exact(i,keys); if(!kind.equals("QUERY")&&p.get("basis")==null)throw semantic("Effect intent requires basis"); if(kind.equals("QUERY")&&!"READ_ONLY_SUMMARY".equals(i.get("queryKind")))throw semantic("Invalid query"); if(kind.equals("PUBLIC_SAY")){text(i.get("message"),256,1024,true);String s=(String)i.get("message");if(s.contains("\r")||s.contains("\n")||firstNonWhitespaceIsSlash(s))throw semantic("Invalid public message");} if(kind.equals("LOOK")||kind.equals("BOUNDED_WALK")){dimension(i.get("dimensionId"));position(i.get("target"));}if(kind.equals("BOUNDED_WALK")){double d=f64(i.get("maximumDistance"));if(!(d>0&&d<=4))throw semantic("Invalid walk distance");i.put("maximumDistance",d);}}
  private static void admission(Map<String,Object> p) throws WireException { exact(p,Set.of("requestId","decision","reason","stamp"));uuid(p.get("requestId"));stamp(p.get("stamp"));reason(p.get("reason"));if(!Set.of("ACCEPTED","REJECTED").contains(p.get("decision")))throw semantic("Invalid admission");if("ACCEPTED".equals(p.get("decision"))?p.get("reason")!=null:!union(GENERIC,Set.of("STALE_BASIS","AUDIENCE_DENIED","CAPABILITY_UNAVAILABLE","REGION_DENIED","UNSAFE_TARGET","BODY_BUSY","QUARANTINED")).contains(p.get("reason")))throw semantic("Invalid admission reason");}
  private static void cancel(Map<String,Object> p)throws WireException{exact(p,Set.of("mode","targetRequestId","targetEpoch","deadline"));target(p.get("mode"),p.get("targetRequestId"));i63(p.get("targetEpoch"));utc(p.get("deadline"));}
  private static void cancelResult(Map<String,Object> p)throws WireException{exact(p,Set.of("cancelId","mode","targetRequestId","targetEpoch","result","reason","stamp"));uuid(p.get("cancelId"));target(p.get("mode"),p.get("targetRequestId"));i63(p.get("targetEpoch"));stamp(p.get("stamp"));reason(p.get("reason"));String r=string(p.get("result"));Set<String> allowed="TARGET".equals(p.get("mode"))?Set.of("REQUESTED","ALREADY_TERMINAL","NOT_FOUND","REJECTED","UNKNOWN"):Set.of("REVOKED","REJECTED","UNKNOWN");if(!allowed.contains(r))throw semantic("Invalid cancel result");Object reason=p.get("reason");if((r.equals("REQUESTED")||r.equals("REVOKED"))&&reason!=null)throw semantic("Invalid cancel reason");if(r.equals("ALREADY_TERMINAL")&&!"TARGET_TERMINAL".equals(reason))throw semantic("Invalid cancel reason");if(r.equals("NOT_FOUND")&&!"TARGET_NOT_FOUND".equals(reason))throw semantic("Invalid cancel reason");if(r.equals("UNKNOWN")&&!Set.of("OUTCOME_UNPROVEN","HISTORY_UNAVAILABLE").contains(reason))throw semantic("Invalid cancel reason");if(r.equals("REJECTED")&&!GENERIC.contains(reason))throw semantic("Invalid cancel reason");}
  private static void receipt(Map<String,Object> p)throws WireException{exact(p,Set.of("requestId","status","reason","stamp","outcome"));uuid(p.get("requestId"));stamp(p.get("stamp"));reason(p.get("reason"));Map<String,Object>o=outcome(p.get("outcome"));String s=string(p.get("status"));if("SUCCEEDED".equals(s)){if(p.get("reason")!=null||!"COMPLETE".equals(o.get("effect"))||Set.of("UNDETERMINED","STOPPED").contains(o.get("kind")))throw semantic("Invalid success");}else{Set<Object>x=switch(s){case "FAILED"->Set.of("UNSAFE_TARGET","STUCK","EXECUTION_REJECTED");case "TIMED_OUT"->Set.of("DEADLINE_EXPIRED");case "CANCELLED"->Set.of("CANCEL_CONFIRMED");case "DISCONNECTED"->Set.of("CONTROL_LOST");case "UNKNOWN"->Set.of("OUTCOME_UNPROVEN","HISTORY_UNAVAILABLE");default->throw semantic("Invalid status");};if(!x.contains(p.get("reason")))throw semantic("Invalid receipt reason");}if("UNKNOWN".equals(s)&&(!"UNDETERMINED".equals(o.get("kind"))||!"UNKNOWN".equals(o.get("effect"))))throw semantic("Invalid unknown outcome");if(!Set.of("SUCCEEDED","UNKNOWN").contains(s)&&!Set.of("NONE","PARTIAL").contains(o.get("effect")))throw semantic("Invalid effect");if(Set.of("SUCCEEDED","CANCELLED","TIMED_OUT","DISCONNECTED").contains(s)&&list(object(p.get("stamp")).get("evidence")).isEmpty())throw semantic("Evidence required");}
  private static void reconcile(Map<String,Object>p)throws WireException{exact(p,Set.of("targetRequestId","targetEpoch","deadline"));optionalUuid(p.get("targetRequestId"));i63(p.get("targetEpoch"));utc(p.get("deadline"));}
  private static void reconcileResult(Map<String,Object>p)throws WireException{exact(p,Set.of("reconcileId","targetRequestId","targetEpoch","result","reason","quiescent","observationId","admissionId","receiptId","stamp"));uuid(p.get("reconcileId"));i63(p.get("targetEpoch"));stamp(p.get("stamp"));reason(p.get("reason"));for(String k:List.of("targetRequestId","observationId","admissionId","receiptId"))optionalUuid(p.get(k));if(!(p.get("quiescent") instanceof Boolean))throw semantic("Invalid quiescent");String r=string(p.get("result"));boolean target=p.get("targetRequestId")!=null,a=p.get("admissionId")!=null,receipt=p.get("receiptId")!=null,obs=p.get("observationId")!=null,q=(Boolean)p.get("quiescent"); Object[] expected=switch(r){case "ADMISSION_REJECTED"->new Object[]{true,true,false,false,false,null};case "IN_FLIGHT"->new Object[]{true,true,false,false,false,null};case "KNOWN"->new Object[]{true,true,true,false,false,null};case "QUIESCENT"->new Object[]{false,false,false,true,true,null};case "BLOCKED"->new Object[]{false,false,false,false,false,"QUIESCENCE_UNPROVEN"};case "UNKNOWN"->new Object[]{true,false,false,false,false,Set.of("OUTCOME_UNPROVEN","HISTORY_UNAVAILABLE")};case "REJECTED"->new Object[]{null,false,false,false,false,GENERIC};default->throw semantic("Invalid reconcile result");};boolean[] actual={target,a,receipt,obs,q};for(int n=0;n<5;n++)if(expected[n]!=null&&!expected[n].equals(actual[n]))throw semantic("Invalid reconcile matrix");Object reason=expected[5];if(reason instanceof Set<?> set){if(!set.contains(p.get("reason")))throw semantic("Invalid reconcile reason");}else if(!equal(reason,p.get("reason")))throw semantic("Invalid reconcile reason");}

  private static Map<String,Object> outcome(Object v)throws WireException{Map<String,Object>o=object(v);exact(o,Set.of("kind","effect","state","observationId"));enumValue(o.get("kind"),Set.of("SUMMARY","PUBLIC_BROADCAST","ORIENTATION","POSITION","STOPPED","UNDETERMINED"));enumValue(o.get("effect"),Set.of("NONE","PARTIAL","COMPLETE","UNKNOWN"));if(o.get("state")!=null)state(o.get("state"));optionalUuid(o.get("observationId"));if(!Set.of("UNDETERMINED","PUBLIC_BROADCAST").contains(o.get("kind"))&&(o.get("state")==null||o.get("observationId")==null))throw semantic("Outcome state required");return o;}
  private static void stamp(Object v)throws WireException{Map<String,Object>o=object(v);exact(o,Set.of("serverBootId","serverRevision","serverTick","eventId","evidence"));uuid(o.get("serverBootId"));uuid(o.get("eventId"));i63(o.get("serverRevision"));i63(o.get("serverTick"));List<Object>e=list(o.get("evidence"));if(e.size()>8)throw semantic("Evidence limit");Set<Object>unique=new LinkedHashSet<>(e);if(unique.size()!=e.size())throw semantic("Duplicate evidence");for(Object x:e)uuid(x);}
  private static void basis(Object v)throws WireException{Map<String,Object>o=object(v);exact(o,Set.of("observationId","serverBootId","serverRevision"));uuid(o.get("observationId"));uuid(o.get("serverBootId"));i63(o.get("serverRevision"));}
  private static void state(Object v)throws WireException{Map<String,Object>o=object(v);exact(o,Set.of("dimensionId","position","yaw","pitch"));dimension(o.get("dimensionId"));position(o.get("position"));double yaw=f64(o.get("yaw")),pitch=f64(o.get("pitch"));if(yaw< -180||yaw>=180||pitch< -90||pitch>90)throw semantic("Invalid orientation");o.put("yaw",yaw);o.put("pitch",pitch);}
  private static void position(Object v)throws WireException{Map<String,Object>o=object(v);exact(o,Set.of("x","y","z"));for(String k:List.of("x","y","z")){double d=f64(o.get(k));if(Math.abs(d)>30_000_000)throw semantic("Position out of bounds");o.put(k,d);}}
  private static void deadline(Instant at,Instant deadline)throws WireException{long delta=deadline.toEpochMilli()-at.toEpochMilli();if(delta<=0||delta>5000)throw semantic("Invalid deadline");}
  private static long i63(Object v)throws WireException{if(!(v instanceof Long l)||l<0)throw semantic("Invalid I63");return l;}
  private static double f64(Object v) throws WireException {
    if (!(v instanceof Long || v instanceof BigDecimal || v instanceof Double)) {
      throw semantic("Invalid F64");
    }
    double d = v instanceof Long l ? l.doubleValue()
        : v instanceof BigDecimal decimal ? decimal.doubleValue() : (Double) v;
    if (!Double.isFinite(d)) throw semantic("Invalid F64");
    return d == 0 ? 0 : d;
  }
  private static Instant utc(Object v)throws WireException{String s=string(v);if(!UTC.matcher(s).matches())throw semantic("Invalid UTC");try{return LocalDateTime.parse(s,DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'")).toInstant(ZoneOffset.UTC);}catch(DateTimeException e){throw semantic("Invalid UTC");}}
  private static String formatUtc(Instant v)throws WireException{if(v==null||v.getNano()%1_000_000!=0)throw semantic("Timestamp must have millisecond precision");try{return UTC_FORMAT.format(v);}catch(DateTimeException e){throw semantic("Timestamp outside supported year range");}}
  private static void id(Object v)throws WireException{if(!ID.matcher(string(v)).matches())throw semantic("Invalid Id");}
  private static void dimension(Object v)throws WireException{if(!DIMENSION.matcher(string(v)).matches())throw semantic("Invalid dimension");}
  private static void uuid(Object v)throws WireException{if(!UUID.matcher(string(v)).matches())throw semantic("Invalid UUID");}
  private static void optionalUuid(Object v)throws WireException{if(v!=null)uuid(v);}
  private static void reason(Object v)throws WireException{if(v!=null&&!REASONS.contains(v))throw semantic("Invalid reason");}
  private static void target(Object mode,Object target)throws WireException{if("TARGET".equals(mode)&&target!=null)uuid(target);else if(!"EMERGENCY_REVOKE".equals(mode)||target!=null)throw semantic("Invalid cancel target");}
  private static void text(Object v,int chars,int bytes,boolean rejectNul)throws WireException{String s=string(v);ensureUnicodeScalars(s);if(s.isEmpty()||s.codePointCount(0,s.length())>chars||s.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>bytes||(rejectNul&&s.indexOf('\0')>=0))throw semantic("Invalid text");}
  private static void ensureUnicodeScalars(String value) throws WireException { for(int index=0;index<value.length();index++){char current=value.charAt(index);if(Character.isHighSurrogate(current)){if(index+1>=value.length()||!Character.isLowSurrogate(value.charAt(++index)))throw semantic("Invalid Unicode scalar");}else if(Character.isLowSurrogate(current))throw semantic("Invalid Unicode scalar");} }
  private static boolean firstNonWhitespaceIsSlash(String value) { for(int index=0;index<value.length();){int codePoint=value.codePointAt(index);if(!isPythonWhitespace(codePoint))return codePoint=='/';index+=Character.charCount(codePoint);}return false; }
  private static boolean isPythonWhitespace(int codePoint) { return Character.isWhitespace(codePoint)||Character.isSpaceChar(codePoint)||codePoint==0x85; }
  @SuppressWarnings("unchecked")
  private static Map<String,Object> object(Object v) throws WireException {
    if (!(v instanceof Map<?, ?> raw)) throw structure("Expected object");
    for (Object key : raw.keySet()) if (!(key instanceof String)) throw structure("Object key is not text");
    return (Map<String, Object>) raw;
  }

  @SuppressWarnings("unchecked")
  private static List<Object> list(Object v) throws WireException {
    if (!(v instanceof List<?>)) throw structure("Expected array");
    return (List<Object>) v;
  }
  private static String string(Object v)throws WireException{if(!(v instanceof String s))throw structure("Expected string");return s;}
  private static void exact(Map<String,Object>o,Set<String>keys)throws WireException{if(!o.keySet().equals(keys))throw structure("Object keys do not match");}
  private static void enumValue(Object v,Set<String> allowed)throws WireException{if(!(v instanceof String s)||!allowed.contains(s))throw semantic("Invalid enum");}
  private static boolean equal(Object a,Object b){return java.util.Objects.equals(a,b);}
  private static Set<String> union(Set<String>a,Set<String>b){Set<String>x=new LinkedHashSet<>(a);x.addAll(b);return x;}
  private static Map<String,Object> copyObject(Map<String,Object> source)throws WireException{return object(copy(source));}
  private static Object copy(Object v){if(v instanceof Map<?,?>m){Map<String,Object>x=new LinkedHashMap<>();m.forEach((k,n)->x.put((String)k,copy(n)));return x;}if(v instanceof List<?>l){List<Object>x=new ArrayList<>();for(Object n:l)x.add(copy(n));return x;}return v;}
  private static Map<String,Object> freeze(Map<String,Object> source){Map<String,Object>x=new LinkedHashMap<>();source.forEach((k,v)->x.put(k,freezeValue(v)));return Collections.unmodifiableMap(x);}
  private static Object freezeValue(Object v){if(v instanceof Map<?,?>m){Map<String,Object>x=new LinkedHashMap<>();m.forEach((k,n)->x.put((String)k,freezeValue(n)));return Collections.unmodifiableMap(x);}if(v instanceof List<?>l){List<Object>x=new ArrayList<>();for(Object n:l)x.add(freezeValue(n));return Collections.unmodifiableList(x);}return v;}
  private static Object thaw(Object v){if(v instanceof Map<?,?>m){Map<String,Object>x=new LinkedHashMap<>();m.forEach((k,n)->x.put((String)k,thaw(n)));return x;}if(v instanceof List<?>l){List<Object>x=new ArrayList<>();for(Object n:l)x.add(thaw(n));return x;}return v;}
  private static Map<String,Object> toMap(CanonicalEnvelope e){Map<String,Object>m=new LinkedHashMap<>();m.put("protocol",e.protocol());m.put("type",e.type());m.put("actorId",e.actorId());m.put("worldId",e.worldId());m.put("bodyId",e.bodyId());m.put("sessionId",e.sessionId());m.put("sessionEpoch",e.sessionEpoch());m.put("messageId",e.messageId());m.put("timestamp",formatUnchecked(e.timestamp()));m.put("payload",thaw(e.payload()));return m;}
  private static String formatUnchecked(Instant value){try{return formatUtc(value);}catch(WireException e){throw new IllegalArgumentException(e);}}
  private static void write(JsonGenerator g,Object v)throws IOException,WireException{if(v==null)g.writeNull();else if(v instanceof Map<?,?>m){g.writeStartObject();for(Map.Entry<?,?>e:m.entrySet()){g.writeFieldName((String)e.getKey());write(g,e.getValue());}g.writeEndObject();}else if(v instanceof List<?>l){g.writeStartArray();for(Object n:l)write(g,n);g.writeEndArray();}else if(v instanceof String s)g.writeString(s);else if(v instanceof Long l)g.writeNumber(l);else if(v instanceof BigDecimal d)g.writeNumber(d);else if(v instanceof Double d)g.writeNumber(d==0?0:d);else if(v instanceof Boolean b)g.writeBoolean(b);else throw semantic("Unsupported value");}
  private static WireException lexical(String s){return new WireException(ErrorCategory.LEXICAL,s);} private static WireException structure(String s){return new WireException(ErrorCategory.STRUCTURE,s);} private static WireException semantic(String s){return new WireException(ErrorCategory.SEMANTIC,s);}
  public enum ErrorCategory { LEXICAL, STRUCTURE, SEMANTIC }
  public record CanonicalEnvelope(String protocol,String type,String actorId,String worldId,String bodyId,String sessionId,long sessionEpoch,String messageId,Instant timestamp,Map<String,Object> payload) {
    public CanonicalEnvelope {
      payload = payload == null ? null : freeze(payload);
    }
  }
  public static final class WireException extends Exception { private final ErrorCategory category; WireException(ErrorCategory category,String message){super(message);this.category=category;} public ErrorCategory category(){return category;} }
}
