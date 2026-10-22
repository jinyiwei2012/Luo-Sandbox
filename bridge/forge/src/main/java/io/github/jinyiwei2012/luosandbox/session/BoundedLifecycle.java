package io.github.jinyiwei2012.luosandbox.session;

import io.github.jinyiwei2012.luosandbox.policy.AuthorityPolicy;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Bounded process-local lifecycle. Evidence and fences are caller-supplied, not authentication. */
public final class BoundedLifecycle {
  public static final int MAX_RECORDS = 32;
  public static final int MAX_EFFECT_WAITING = 8;
  public static final int MAX_CONTROL_RESERVATIONS = 8;
  public static final int MAX_ADMISSION_DECISIONS = 64;
  public static final long HEARTBEAT_TIMEOUT_MILLIS = 2_000;
  public static final long TERMINAL_RETENTION_MILLIS = 300_000;

  public enum Phase { ADMITTED, EXECUTING, CANCEL_REQUESTED, TERMINAL }
  public enum Effect { NONE, PARTIAL, COMPLETE, UNKNOWN }
  public enum Terminal { SUCCEEDED, FAILED, TIMED_OUT, CANCELLED, DISCONNECTED, UNKNOWN }
  public enum PermitFence { REVOKED, EXPIRED }
  public enum BodyFence { QUIESCENT }

  /** canonical is false when finite memory cannot promise an identical replay decision. */
  public record Admission(boolean accepted, AuthorityPolicy.Code code, Phase phase, boolean canonical) {}
  public record AuthorityEvidence(String id) { public AuthorityEvidence { if (id == null || id.isBlank()) throw new IllegalArgumentException("missing evidence"); } }
  public record FenceProof(AuthorityEvidence evidence, PermitFence permits, BodyFence body) { public FenceProof { if (evidence == null || permits == null || body == null) throw new IllegalArgumentException("incomplete proof"); } }
  public record Record(AuthorityPolicy.Proposal proposal, Admission admission, Phase phase, long deadline, Terminal terminal, Effect effect, long terminalAt) {}
  private record AdmissionDecision(AuthorityPolicy.Proposal proposal, Admission admission, long decidedAt) {}

  private AuthorityPolicy.Binding binding;
  private final AuthorityPolicy.Region region;
  private final Set<AuthorityPolicy.Capability> capabilities;
  private final Map<String, Record> records = new LinkedHashMap<>();
  private final Map<String, AdmissionDecision> admissionDecisions = new LinkedHashMap<>();
  private final ArrayDeque<String> waiting = new ArrayDeque<>();
  private final Set<String> controlReservations = new java.util.HashSet<>();
  private String active;
  private boolean quarantined;
  private boolean emergencyRevoked;
  private long lastHeartbeat;

  public BoundedLifecycle(AuthorityPolicy.Binding binding, AuthorityPolicy.Region region, Set<AuthorityPolicy.Capability> capabilities, long monotonicNow) {
    this.binding = binding;
    this.region = region;
    this.capabilities = Set.copyOf(capabilities);
    lastHeartbeat = monotonicNow;
  }

  public Admission admit(AuthorityPolicy.Proposal proposal, AuthorityPolicy.Observation observation, long wallNow, long monotonicNow) {
    prune(monotonicNow);
    expirePending(monotonicNow);
    if (!proposal.binding().equals(binding)) return transientRejected(AuthorityPolicy.Code.BINDING_MISMATCH);
    AdmissionDecision prior = admissionDecisions.get(proposal.requestId());
    if (prior != null) {
      return prior.proposal().equals(proposal)
          ? prior.admission()
          : transientRejected(AuthorityPolicy.Code.ID_CONFLICT);
    }
    if (admissionDecisions.size() >= MAX_ADMISSION_DECISIONS) {
      return transientRejected(AuthorityPolicy.Code.BACKPRESSURE);
    }
    AuthorityPolicy.Code decision = AuthorityPolicy.checkAdmission(proposal, binding, observation, region,
        capabilities, quarantined || emergencyRevoked || controlLost(monotonicNow), wallNow);
    if (decision != AuthorityPolicy.Code.ACCEPTED) return storeRejected(proposal, decision, monotonicNow);
    if (records.size() >= MAX_RECORDS
        || proposal.effectful() && waiting.size() >= MAX_EFFECT_WAITING
        || !proposal.effectful() && controlReservations.size() >= MAX_CONTROL_RESERVATIONS) {
      return storeRejected(proposal, AuthorityPolicy.Code.BACKPRESSURE, monotonicNow);
    }
    long remaining = proposal.deadlineEpochMillis() - wallNow;
    if (remaining <= 0 || remaining > 5_000 || monotonicNow > Long.MAX_VALUE - remaining) return storeRejected(proposal, AuthorityPolicy.Code.DEADLINE_EXPIRED, monotonicNow);
    Admission admission = new Admission(true, AuthorityPolicy.Code.ACCEPTED, Phase.ADMITTED, true);
    rememberDecision(proposal, admission, monotonicNow);
    records.put(proposal.requestId(), new Record(proposal, admission, Phase.ADMITTED, monotonicNow + remaining, null, null, -1));
    if (proposal.effectful()) waiting.addLast(proposal.requestId());
    else controlReservations.add(proposal.requestId());
    return admission;
  }

  public Optional<Record> beginNextEffect(AuthorityPolicy.Observation observation, long wallNow, long monotonicNow) {
    expirePending(monotonicNow);
    if (active != null || quarantined || emergencyRevoked || controlLost(monotonicNow)) return Optional.empty();
    while (!waiting.isEmpty()) {
      String id = waiting.removeFirst();
      Record record = records.get(id);
      if (record == null || record.phase() != Phase.ADMITTED) continue;
      if (record.deadline() <= monotonicNow) { terminal(id, Terminal.TIMED_OUT, Effect.NONE, monotonicNow); continue; }
      AuthorityPolicy.Code check = AuthorityPolicy.checkDispatch(record.proposal(), binding, observation, region,
          capabilities, false, wallNow);
      if (check != AuthorityPolicy.Code.ACCEPTED) { terminal(id, Terminal.FAILED, Effect.NONE, monotonicNow); continue; }
      active = id;
      Record executing = new Record(record.proposal(), record.admission(), Phase.EXECUTING, record.deadline(), null, null, -1);
      records.put(id, executing);
      return Optional.of(executing);
    }
    return Optional.empty();
  }

  public void cancelTarget(String requestId, long monotonicNow) {
    Record record = records.get(requestId);
    if (record == null || record.phase() == Phase.TERMINAL) return;
    if (requestId.equals(active)) { revoke(requestId); return; }
    waiting.remove(requestId);
    terminal(requestId, Terminal.CANCELLED, Effect.NONE, monotonicNow);
  }

  public void emergencyRevoke() { emergencyRevoked = true; if (active != null) revoke(active); }
  public boolean emergencyRevoked() { return emergencyRevoked; }
  public void heartbeat(long monotonicNow) { lastHeartbeat = monotonicNow; }
  public boolean controlLost(long monotonicNow) { return monotonicNow - lastHeartbeat > HEARTBEAT_TIMEOUT_MILLIS; }
  public boolean quarantined() { return quarantined; }
  public Optional<Record> record(String id) { return Optional.ofNullable(records.get(id)); }
  public AuthorityPolicy.Binding binding() { return binding; }

  public boolean canAdvanceEpoch(AuthorityPolicy.Binding next, FenceProof proof) {
    return next != null && proof != null && next.epoch() > binding.epoch() && sameBody(next, binding);
  }

  public boolean activeDeadlineElapsed(long monotonicNow) {
    if (active == null || records.get(active).deadline() > monotonicNow) return false;
    revoke(active);
    return true;
  }

  public boolean complete(String requestId, Terminal terminal, Effect effect, AuthorityEvidence evidence, long monotonicNow) {
    if (evidence == null || terminal == null || effect == null) throw new IllegalArgumentException("missing completion");
    Record record = records.get(requestId);
    if (record == null || record.phase() == Phase.TERMINAL) return false;
    if (record.proposal().effectful() && !requestId.equals(active)) return false;
    if (terminal == Terminal.UNKNOWN && effect != Effect.UNKNOWN || terminal != Terminal.UNKNOWN && effect == Effect.UNKNOWN) throw new IllegalArgumentException("invalid unknown effect");
    terminal(requestId, terminal, effect, monotonicNow);
    if (terminal == Terminal.UNKNOWN) quarantined = true;
    return true;
  }

  public boolean markUnknown(String requestId, AuthorityEvidence evidence, long monotonicNow) {
    return requestId.equals(active) && complete(requestId, Terminal.UNKNOWN, Effect.UNKNOWN, evidence, monotonicNow);
  }

  /** Fences new effects after trusted control loss; an active effect becomes immutable UNKNOWN. */
  public void fenceControlLoss(AuthorityEvidence evidence, long monotonicNow) {
    if (evidence == null) throw new IllegalArgumentException("missing control-loss evidence");
    if (active != null) markUnknown(active, evidence, monotonicNow);
    quarantined = true;
    emergencyRevoked = true;
  }

  public boolean advanceEpoch(AuthorityPolicy.Binding next, FenceProof proof, long monotonicNow) {
    if (!canAdvanceEpoch(next, proof)) return false;
    for (String id : new ArrayDeque<>(waiting)) cancelTarget(id, monotonicNow);
    for (Map.Entry<String, Record> entry : new LinkedHashMap<>(records).entrySet()) {
      if (!entry.getValue().proposal().effectful() && entry.getValue().phase() == Phase.ADMITTED) {
        terminal(entry.getKey(), Terminal.CANCELLED, Effect.NONE, monotonicNow);
      }
    }
    if (active != null) revoke(active);
    binding = next;
    quarantined = false;
    emergencyRevoked = false;
    waiting.clear();
    return true;
  }

  private Admission storeRejected(AuthorityPolicy.Proposal proposal, AuthorityPolicy.Code code, long monotonicNow) {
    Admission admission = rejected(code);
    rememberDecision(proposal, admission, monotonicNow);
    if (records.size() < MAX_RECORDS) {
      records.put(proposal.requestId(), new Record(proposal, admission, Phase.TERMINAL, monotonicNow, null,
          null, monotonicNow));
    }
    return admission;
  }

  private void expirePending(long monotonicNow) {
    for (Map.Entry<String, Record> entry : new LinkedHashMap<>(records).entrySet()) {
      Record record = entry.getValue();
      if (record.phase() == Phase.ADMITTED && record.deadline() <= monotonicNow) {
        waiting.remove(entry.getKey());
        terminal(entry.getKey(), Terminal.TIMED_OUT, Effect.NONE, monotonicNow);
      }
    }
  }

  private void revoke(String id) {
    Record record = records.get(id);
    if (record != null && record.phase() != Phase.TERMINAL) records.put(id, new Record(record.proposal(), record.admission(), Phase.CANCEL_REQUESTED, record.deadline(), null, null, -1));
  }

  private void terminal(String id, Terminal terminal, Effect effect, long monotonicNow) {
    Record record = records.get(id);
    if (record == null || record.phase() == Phase.TERMINAL) return;
    records.put(id, new Record(record.proposal(), record.admission(), Phase.TERMINAL, record.deadline(), terminal, effect, monotonicNow));
    if (id.equals(active)) active = null;
    controlReservations.remove(id);
  }

  private void rememberDecision(AuthorityPolicy.Proposal proposal, Admission admission, long monotonicNow) {
    admissionDecisions.put(proposal.requestId(), new AdmissionDecision(proposal, admission, monotonicNow));
  }
  private void prune(long monotonicNow) {
    records.entrySet().removeIf(entry -> entry.getValue().phase() == Phase.TERMINAL
        && monotonicNow - entry.getValue().terminalAt() >= TERMINAL_RETENTION_MILLIS);
    admissionDecisions.entrySet().removeIf(entry -> !records.containsKey(entry.getKey())
        && monotonicNow - entry.getValue().decidedAt() >= TERMINAL_RETENTION_MILLIS);
  }
  private static boolean sameBody(AuthorityPolicy.Binding first, AuthorityPolicy.Binding second) { return first.actorId().equals(second.actorId()) && first.worldId().equals(second.worldId()) && first.bodyId().equals(second.bodyId()); }
  private static Admission rejected(AuthorityPolicy.Code code) { return new Admission(false, code, Phase.TERMINAL, true); }
  private static Admission transientRejected(AuthorityPolicy.Code code) { return new Admission(false, code, Phase.TERMINAL, false); }
}
