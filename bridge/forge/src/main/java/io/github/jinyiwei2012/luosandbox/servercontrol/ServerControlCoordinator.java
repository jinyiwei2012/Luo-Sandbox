package io.github.jinyiwei2012.luosandbox.servercontrol;

import io.github.jinyiwei2012.luosandbox.policy.AuthorityPolicy;
import io.github.jinyiwei2012.luosandbox.session.BoundedLifecycle;
import java.util.Objects;
import java.util.Optional;

/** Single-owner composition seam for lifecycle admission, permit grants, packet gates and terminal evidence. */
public final class ServerControlCoordinator {
  public interface PermitNotifier {
    void send(PermitAuthority.Permit permit) throws Exception;
    void revoke(PermitAuthority.Binding binding, PermitAuthority.RevokeReason reason);
  }

  /** Server-owned dispatch inputs, not client payload claims. */
  public record DispatchContext(
      PermitAuthority.CurrentContext permitContext,
      AuthorityPolicy.Observation observation,
      long wallNowMillis,
      double currentYaw,
      double currentPitch) {
    public DispatchContext {
      Objects.requireNonNull(permitContext);
      Objects.requireNonNull(observation);
      if (wallNowMillis < 0 || !validRotation(currentYaw, currentPitch)) {
        throw new IllegalArgumentException("invalid dispatch context");
      }
    }
  }

  /** Region and request shape approved by the server dispatch owner. */
  public record PermitGrant(PermitAuthority.Bounds bounds, long grantDeadlineMonotonicMillis) {
    public PermitGrant {
      Objects.requireNonNull(bounds);
      if (grantDeadlineMonotonicMillis < 0) throw new IllegalArgumentException("negative grant deadline");
    }
  }

  /** Immutable server-observed completion evidence. It is not a client cleanup acknowledgement. */
  public record TerminalEvidence(String serverEventId, BoundedLifecycle.Terminal terminal,
      BoundedLifecycle.Effect effect) {
    public TerminalEvidence {
      if (serverEventId == null || serverEventId.isBlank() || terminal == null || effect == null) {
        throw new IllegalArgumentException("incomplete terminal evidence");
      }
    }
  }
  public record QuiescenceProof(String authorityEventId) {
    public QuiescenceProof { if (authorityEventId == null || authorityEventId.isBlank()) throw new IllegalArgumentException("missing quiescence proof"); }
  }
  public record RebindProof(BoundedLifecycle.FenceProof lifecycleProof, QuiescenceProof quiescenceProof) {
    public RebindProof { Objects.requireNonNull(lifecycleProof); Objects.requireNonNull(quiescenceProof); }
  }
  public enum DispatchStatus { NO_WORK, PERMIT_ISSUED, DISPATCH_FAILED, UNKNOWN }
  public record DispatchResult(DispatchStatus status, Optional<PermitAuthority.Permit> permit) {
    public DispatchResult { Objects.requireNonNull(status); Objects.requireNonNull(permit); }
  }

  private final BoundedLifecycle lifecycle;
  private final PermitAuthority permits;
  private final PermitNotifier notifier;
  private PermitAuthority.Binding binding;
  private String permittedRequestId;

  public ServerControlCoordinator(BoundedLifecycle lifecycle, PermitAuthority permits,
      PermitAuthority.Binding binding, PermitNotifier notifier) {
    this.lifecycle = Objects.requireNonNull(lifecycle);
    this.permits = Objects.requireNonNull(permits);
    this.binding = Objects.requireNonNull(binding);
    this.notifier = Objects.requireNonNull(notifier);
    if (!sameBinding(lifecycle.binding(), binding) || !permits.binding().equals(binding)) {
      throw new IllegalArgumentException("inconsistent trusted binding");
    }
  }

  public BoundedLifecycle.Admission admit(AuthorityPolicy.Proposal proposal,
      AuthorityPolicy.Observation observation, long wallNowMillis, long monotonicNowMillis) {
    return lifecycle.admit(proposal, observation, wallNowMillis, monotonicNowMillis);
  }
  public Optional<BoundedLifecycle.Record> record(String requestId) { return lifecycle.record(requestId); }
  public boolean quarantined() { return lifecycle.quarantined(); }

  public void cancel(String requestId, long monotonicNowMillis) {
    lifecycle.cancelTarget(requestId, monotonicNowMillis);
    if (requestId.equals(permittedRequestId)) revoke(PermitAuthority.RevokeReason.CANCEL);
  }

  /** Dispatches the next effect only after lifecycle authority recheck. QUERY and SAY never create permits. */
  public DispatchResult dispatchNext(DispatchContext context, PermitGrant grant) {
    if (!binding.equals(context.permitContext().binding())) return noWork();
    Optional<BoundedLifecycle.Record> next = lifecycle.beginNextEffect(
        context.observation(), context.wallNowMillis(), context.permitContext().monotonicNow());
    if (next.isEmpty()) return noWork();
    BoundedLifecycle.Record record = next.get();
    if (!record.admission().accepted() || record.phase() != BoundedLifecycle.Phase.EXECUTING) return fail(record, context);
    Optional<PermitAuthority.VerifiedAdmission> admission = toPermitAdmission(record, grant, context);
    if (admission.isEmpty()) return fail(record, context);
    PermitAuthority.CurrentContext permitContext = withGrantDeadline(context.permitContext(), grant);
    Optional<PermitAuthority.Permit> issued = permits.issue(admission.get(), permitContext, context.wallNowMillis());
    if (issued.isEmpty()) return fail(record, context);
    try {
      notifier.send(issued.get());
      permittedRequestId = record.proposal().requestId();
      return new DispatchResult(DispatchStatus.PERMIT_ISSUED, issued);
    } catch (Exception error) {
      fenceControlLoss(new BoundedLifecycle.AuthorityEvidence("permit-notification-failed"),
          context.permitContext().monotonicNow(), PermitAuthority.RevokeReason.CONTROL_LOST);
      return new DispatchResult(DispatchStatus.UNKNOWN, Optional.empty());
    }
  }

  /** Server packet handler calls this with actual observed movement, never a client success claim. */
  public PermitAuthority.Gate gate(String permitId, PermitAuthority.CurrentContext context,
      PermitAuthority.Position observedPosition, double yaw, double pitch) {
    if (!binding.equals(context.binding())) return PermitAuthority.Gate.DENY;
    return permits.gate(permitId, context, observedPosition, yaw, pitch);
  }

  public boolean complete(String requestId, TerminalEvidence evidence, long monotonicNowMillis) {
    if (evidence.terminal() == BoundedLifecycle.Terminal.SUCCEEDED
        && evidence.effect() != BoundedLifecycle.Effect.COMPLETE) return false;
    boolean completed = lifecycle.complete(requestId, evidence.terminal(), evidence.effect(),
        new BoundedLifecycle.AuthorityEvidence(evidence.serverEventId()), monotonicNowMillis);
    if (completed) revoke(PermitAuthority.RevokeReason.CANCEL);
    return completed;
  }

  public void revoke(PermitAuthority.RevokeReason reason) {
    permits.revoke(binding, reason);
    permittedRequestId = null;
    notifyRevoke(reason);
  }

  public void controlLost(BoundedLifecycle.AuthorityEvidence evidence, long monotonicNowMillis) {
    fenceControlLoss(evidence, monotonicNowMillis, PermitAuthority.RevokeReason.CONTROL_LOST);
  }

  /**
   * Synchronously removes the exact current binding's local authority before a binding PREPARE may
   * be reported. This is a revocation fence only; it is not quiescence or READY evidence.
   */
  public boolean fenceForBindingPrepare(
      PermitAuthority.Binding expectedBinding,
      BoundedLifecycle.AuthorityEvidence evidence,
      long monotonicNowMillis) {
    Objects.requireNonNull(expectedBinding);
    Objects.requireNonNull(evidence);
    if (monotonicNowMillis < 0 || !binding.equals(expectedBinding)) return false;

    Throwable failure = null;
    try {
      lifecycle.fenceControlLoss(evidence, monotonicNowMillis);
    } catch (Throwable error) {
      failure = error;
    }
    permittedRequestId = null;
    try {
      permits.revoke(expectedBinding, PermitAuthority.RevokeReason.REBIND);
    } catch (Throwable error) {
      if (failure == null || error instanceof Error) failure = error;
    }
    try {
      notifier.revoke(expectedBinding, PermitAuthority.RevokeReason.REBIND);
    } catch (Throwable error) {
      if (failure == null || error instanceof Error) failure = error;
    }

    boolean fenced =
        binding.equals(expectedBinding)
            && lifecycle.quarantined()
            && permits.binding().equals(expectedBinding)
            && permits.activePermit().isEmpty()
            && permittedRequestId == null;
    if (failure instanceof Error fatal) throw fatal;
    return failure == null && fenced;
  }
  public boolean deadlineElapsed(long monotonicNowMillis) {
    if (!lifecycle.activeDeadlineElapsed(monotonicNowMillis)) return false;
    revoke(PermitAuthority.RevokeReason.DEADLINE);
    return true;
  }
  public void emergencyRevoke() {
    lifecycle.emergencyRevoke();
    permits.revoke(binding, PermitAuthority.RevokeReason.EMERGENCY);
    permittedRequestId = null;
    notifyRevoke(PermitAuthority.RevokeReason.EMERGENCY);
  }

  /** Trusted binding owner supplies both external fence proofs; payload booleans cannot advance epochs. */
  public boolean rebind(AuthorityPolicy.Binding nextPolicy, PermitAuthority.Binding nextPermit,
      RebindProof proof, long monotonicNowMillis) {
    if (proof == null || !sameBinding(nextPolicy, nextPermit) || !lifecycle.canAdvanceEpoch(nextPolicy,
        proof.lifecycleProof()) || !permits.canRebind(nextPermit)) return false;
    try {
      if (!lifecycle.advanceEpoch(nextPolicy, proof.lifecycleProof(), monotonicNowMillis)) return false;
      if (!permits.rebind(nextPermit, true)) throw new IllegalStateException("permit rebind failed");
    } catch (RuntimeException error) {
      revoke(PermitAuthority.RevokeReason.REBIND);
      return false;
    }
    binding = nextPermit;
    return true;
  }

  private DispatchResult fail(BoundedLifecycle.Record record, DispatchContext context) {
    lifecycle.complete(record.proposal().requestId(), BoundedLifecycle.Terminal.FAILED,
        BoundedLifecycle.Effect.NONE, new BoundedLifecycle.AuthorityEvidence("dispatch-without-permit"),
        context.permitContext().monotonicNow());
    revoke(PermitAuthority.RevokeReason.CANCEL);
    return new DispatchResult(DispatchStatus.DISPATCH_FAILED, Optional.empty());
  }

  private void fenceControlLoss(BoundedLifecycle.AuthorityEvidence evidence, long monotonicNowMillis,
      PermitAuthority.RevokeReason reason) {
    lifecycle.fenceControlLoss(evidence, monotonicNowMillis);
    permits.revoke(binding, reason);
    permittedRequestId = null;
    notifyRevoke(reason);
  }

  private void notifyRevoke(PermitAuthority.RevokeReason reason) {
    try {
      notifier.revoke(binding, reason);
    } catch (RuntimeException ignored) {
      // Local server fences are already installed before best-effort notification
    }
  }

  private static DispatchResult noWork() { return new DispatchResult(DispatchStatus.NO_WORK, Optional.empty()); }

  private static Optional<PermitAuthority.VerifiedAdmission> toPermitAdmission(
      BoundedLifecycle.Record record, PermitGrant grant, DispatchContext context) {
    AuthorityPolicy.Proposal proposal = record.proposal();
    PermitAuthority.Intent intent;
    if (proposal.capability() == AuthorityPolicy.Capability.LOOK) intent = PermitAuthority.Intent.LOOK;
    else if (proposal.capability() == AuthorityPolicy.Capability.BOUNDED_WALK) intent = PermitAuthority.Intent.BOUNDED_WALK;
    else return Optional.empty();
    if (!proposal.requestId().equals(record.proposal().requestId())
        || !proposal.dimensionId().equals(grant.bounds().dimensionId())
        || intent == PermitAuthority.Intent.LOOK && grant.bounds().maximumDistance() != 0
        || intent == PermitAuthority.Intent.BOUNDED_WALK
            && Double.compare(proposal.maximumDistance(), grant.bounds().maximumDistance()) != 0) {
      return Optional.empty();
    }
    AuthorityPolicy.Position target = proposal.target();
    if (target == null
        || !grant.bounds().contains(
            new PermitAuthority.Position(target.x(), target.y(), target.z()))) {
      return Optional.empty();
    }
    PermitAuthority.ApprovedOrientation orientation = null;
    if (intent == PermitAuthority.Intent.LOOK) {
      orientation = approvedLookOrientation(
          context.permitContext().position(), proposal.target(), context.currentYaw()).orElse(null);
      if (orientation == null) return Optional.empty();
    }
    return Optional.of(new PermitAuthority.VerifiedAdmission(proposal.requestId(), intent,
        proposal.deadlineEpochMillis(), record.deadline(), grant.bounds(), orientation));
  }

  private static PermitAuthority.CurrentContext withGrantDeadline(
      PermitAuthority.CurrentContext context, PermitGrant grant) {
    return new PermitAuthority.CurrentContext(context.binding(), context.dimensionId(), context.position(),
        context.monotonicNow(), grant.grantDeadlineMonotonicMillis(),
        context.freshBasisUntilMonotonicMillis());
  }
  private static boolean sameBinding(AuthorityPolicy.Binding policy, PermitAuthority.Binding permit) {
    return policy != null && permit != null && policy.actorId().equals(permit.actorId())
        && policy.worldId().equals(permit.worldId()) && policy.bodyId().equals(permit.bodyId())
        && policy.sessionId().equals(permit.sessionId()) && policy.epoch() == permit.epoch();
  }
  private static Optional<PermitAuthority.ApprovedOrientation> approvedLookOrientation(
      PermitAuthority.Position origin, AuthorityPolicy.Position target, double currentYaw) {
    double dx = target.x() - origin.x();
    double dy = target.y() - origin.y();
    double dz = target.z() - origin.z();
    double horizontal = Math.hypot(dx, dz);
    if (horizontal == 0 && dy == 0) return Optional.empty();
    double yaw = horizontal == 0 ? normalizeYaw(currentYaw)
        : normalizeYaw(Math.toDegrees(Math.atan2(-dx, dz)));
    double pitch = -Math.toDegrees(Math.atan2(dy, horizontal));
    return Optional.of(new PermitAuthority.ApprovedOrientation(yaw, pitch));
  }
  private static double normalizeYaw(double yaw) { double normalized = yaw % 360; if (normalized >= 180) normalized -= 360; if (normalized < -180) normalized += 360; return normalized; }
  private static boolean validRotation(double yaw, double pitch) { return Double.isFinite(yaw) && yaw >= -180 && yaw < 180 && Double.isFinite(pitch) && pitch >= -90 && pitch <= 90; }
}
