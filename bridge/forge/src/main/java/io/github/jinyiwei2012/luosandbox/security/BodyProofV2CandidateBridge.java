package io.github.jinyiwei2012.luosandbox.security;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import io.github.jinyiwei2012.luosandbox.transport.PostAuthPeerSession;
import io.github.jinyiwei2012.luosandbox.wire.StrictJson;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Unregistered owner-thread bridge from verifier-minted BODY proof capabilities to one exact
 * authenticated Server v2 session. It creates non-authoritative candidates only.
 */
public final class BodyProofV2CandidateBridge<C> {
  @FunctionalInterface public interface OwnerThreadGate { boolean isOwnerThread(); }

  public static final class CandidateBridgeException extends IllegalStateException {
    public CandidateBridgeException() { super("BODY candidate bridge rejected input"); }
  }

  private record Retained<C>(PendingBodyProof.VerifiedGameConnectionProof<C> proof,
      C connection) {}

  private static final JsonFactory JSON = new JsonFactory();
  private static final Set<String> NOTICE_KEYS = Set.of(
      "security", "kind", "serverBootId", "gameConnectionId", "bodyId", "gameNonce");

  @FunctionalInterface interface ProofConsumer<C> {
    boolean consume(PendingBodyProof.VerifiedGameConnectionProof<C> proof, C connection);
  }
  @FunctionalInterface interface ProofInvalidator<C> {
    boolean invalidate(PendingBodyProof.VerifiedGameConnectionProof<C> proof);
  }

  private final PostAuthPeerSession session;
  private final OwnerThreadGate thread;
  private final ProofConsumer<C> consumer;
  private final ProofInvalidator<C> invalidator;
  private final long sessionGeneration;
  private final IdentityHashMap<PendingBodyProof.VerifiedGameConnectionProof<C>, Retained<C>>
      retained = new IdentityHashMap<>();
  private boolean closed;

  public BodyProofV2CandidateBridge(BodyProofIngress<C> ingress,
      PostAuthPeerSession session, OwnerThreadGate thread) {
    Objects.requireNonNull(ingress);
    this.session = Objects.requireNonNull(session);
    this.thread = Objects.requireNonNull(thread);
    consumer = (proof, connection) -> ingress.acceptVerified(proof, () -> connection);
    invalidator = ingress::invalidateVerified;
    requireOwner();
    if (session.authenticatedRole() != ControlAuth.Role.SERVER_AUTHORITY) throw rejected();
    sessionGeneration = session.generation();
  }

  public void verified(PendingBodyProof.VerifiedGameConnectionProof<C> proof,
      C exactConnection) {
    requireOwner();
    if (closed || proof == null || exactConnection == null
        || session.state() != PostAuthPeerSession.State.OPEN
        || session.generation() != sessionGeneration
        || !proof.matchesConnection(exactConnection)
        || !proof.serverInstallId().equals(session.authenticatedPeerInstallId())
        || retained.containsKey(proof) || retained.size() >= PendingBodyProof.MAX_PENDING) {
      throw rejected();
    }
    retained.put(proof, new Retained<>(proof, exactConnection));
  }

  public void receive(PostAuthFrameCodec.Frame frame) {
    requireOwner();
    validateRoute(frame);
    Map<String, Object> notice = notice(frame.payload());
    Retained<C> match = null;
    for (Retained<C> value : retained.values()) {
      PendingBodyProof.BodyChallenge challenge = value.proof().challenge();
      if (value.proof().clientInstallId().equals(frame.sourceInstallId())
          && challenge.serverBootId().equals(notice.get("serverBootId"))
          && challenge.gameConnectionId().equals(notice.get("gameConnectionId"))
          && challenge.bodyId().equals(notice.get("bodyId"))
          && challenge.gameNonce().equals(notice.get("gameNonce"))) {
        if (match != null) throw rejected();
        match = value;
      }
    }
    if (match == null) throw rejected();
    retained.remove(match.proof());
    if (!consumer.consume(match.proof(), match.connection())) throw rejected();
    if (closed || session.state() != PostAuthPeerSession.State.OPEN
        || session.generation() != sessionGeneration) throw rejected();
    PendingBodyProof.BodyChallenge challenge = match.proof().challenge();
    byte[] candidate = candidate(challenge);
    session.sendBodyBoundCandidate(frame.routeId(), candidate, challenge.expiresAt());
    if (session.state() != PostAuthPeerSession.State.OPEN) throw rejected();
  }

  public void disconnect(C exactConnection) {
    requireOwner();
    if (exactConnection == null) return;
    var removed = new java.util.ArrayList<Retained<C>>();
    for (Retained<C> value : retained.values()) {
      if (value.connection() == exactConnection) removed.add(value);
    }
    for (Retained<C> value : removed) retained.remove(value.proof());
    Throwable first = null;
    for (Retained<C> value : removed) {
      try { invalidator.invalidate(value.proof()); }
      catch (Throwable error) { first = remember(first, error); }
    }
    rethrowFatal(first);
  }

  public void close() {
    requireOwner();
    if (closed) return;
    closed = true;
    var values = new java.util.ArrayList<>(retained.values());
    retained.clear();
    Throwable first = null;
    for (Retained<C> value : values) {
      try { invalidator.invalidate(value.proof()); }
      catch (Throwable error) { first = remember(first, error); }
    }
    rethrowFatal(first);
  }

  private void validateRoute(PostAuthFrameCodec.Frame frame) {
    if (closed || frame == null || session.state() != PostAuthPeerSession.State.OPEN
        || session.generation() != sessionGeneration
        || session.authenticatedRole() != ControlAuth.Role.SERVER_AUTHORITY
        || !"BODY_BIND_ROUTE".equals(frame.messageKind())
        || !frame.connectionId().equals(session.connectionId())
        || !frame.recipientConnectionId().equals(session.connectionId())
        || !frame.sourceInstallId().matches("[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}")
        || !uuid(frame.routeId()) || !uuid(frame.sourceConnectionId())
        || !frame.challengeExpiresAt().isEmpty()) throw rejected();
  }

  private static Map<String, Object> notice(byte[] raw) {
    try {
      Map<String, Object> value = StrictJson.decodeObject(raw);
      if (!value.keySet().equals(NOTICE_KEYS)
          || value.values().stream().anyMatch(item -> !(item instanceof String))
          || !"luosandbox.control-auth.v1".equals(value.get("security"))
          || !"BODY_BIND_NOTICE".equals(value.get("kind"))
          || !uuid((String) value.get("serverBootId"))
          || !uuid((String) value.get("gameConnectionId"))
          || !uuid((String) value.get("bodyId"))) throw rejected();
      String nonce = (String) value.get("gameNonce");
      byte[] decoded = ControlAuth.decodeB32(nonce);
      if (!ControlAuth.b32(decoded).equals(nonce)) throw rejected();
      return value;
    } catch (Exception error) {
      throw rejected();
    }
  }

  private static byte[] candidate(PendingBodyProof.BodyChallenge challenge) {
    try (ByteArrayOutputStream output = new ByteArrayOutputStream();
         JsonGenerator json = JSON.createGenerator(output)) {
      json.writeStartObject();
      json.writeStringField("security", "luosandbox.control-auth.v1");
      json.writeStringField("kind", "BODY_BOUND");
      json.writeStringField("serverBootId", challenge.serverBootId());
      json.writeStringField("gameConnectionId", challenge.gameConnectionId());
      json.writeStringField("bodyId", challenge.bodyId());
      json.writeEndObject();
      json.close();
      return output.toByteArray();
    } catch (IOException error) {
      throw rejected();
    }
  }

  private void requireOwner() {
    boolean owner;
    try { owner = thread.isOwnerThread(); }
    catch (RuntimeException error) { owner = false; }
    if (!owner) throw new CandidateBridgeException();
  }

  private static boolean uuid(String value) {
    try { ControlAuth.requireUuid(value); return !"00000000-0000-0000-0000-000000000000".equals(value); }
    catch (IllegalArgumentException error) { return false; }
  }
  private static CandidateBridgeException rejected() { return new CandidateBridgeException(); }
  private static Throwable remember(Throwable first, Throwable next) {
    if (first == null) return next;
    if (!(first instanceof Error) && next instanceof Error) { next.addSuppressed(first); return next; }
    first.addSuppressed(next); return first;
  }
  private static void rethrowFatal(Throwable value) { if (value instanceof Error fatal) throw fatal; }
  @Override public String toString() { return "BodyProofV2CandidateBridge[redacted]"; }
}
