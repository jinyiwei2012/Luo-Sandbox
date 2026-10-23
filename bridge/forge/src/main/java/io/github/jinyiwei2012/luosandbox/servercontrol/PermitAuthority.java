package io.github.jinyiwei2012.luosandbox.servercontrol;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/** Single-owner server permit table. It grants packet filtering authority, not a world result. */
public final class PermitAuthority {
  public static final long MAX_LEASE_MILLIS = 750;
  private static final SecureRandom RANDOM = new SecureRandom();

  public enum Intent { LOOK, BOUNDED_WALK }
  public enum Gate { ALLOW, DENY }
  public enum RevokeReason { CANCEL, DEADLINE, CONTROL_LOST, QUARANTINE, REBIND, ROTATE, EMERGENCY }
  public record Binding(String actorId, String worldId, String bodyId, String sessionId, long epoch,
      String serverBootId, String gameConnectionId) {
    public Binding {
      require(actorId); require(worldId); require(bodyId); require(sessionId); require(serverBootId);
      require(gameConnectionId); if (epoch < 0) throw new IllegalArgumentException("negative epoch");
    }
  }
  public record Position(double x, double y, double z) {
    public Position { if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) throw new IllegalArgumentException("non-finite position"); }
  }
  public record Bounds(String dimensionId, Position min, Position max, double maximumDistance) {
    public Bounds {
      require(dimensionId); Objects.requireNonNull(min); Objects.requireNonNull(max);
      if (!(min.x <= max.x && min.y <= max.y && min.z <= max.z) || !Double.isFinite(maximumDistance)
          || maximumDistance < 0 || maximumDistance > 4) throw new IllegalArgumentException("invalid bounds");
    }
    public boolean contains(Position value) { return value.x >= min.x && value.x <= max.x && value.y >= min.y && value.y <= max.y && value.z >= min.z && value.z <= max.z; }
  }
  public record ApprovedOrientation(double yaw, double pitch) {
    public ApprovedOrientation {
      if (!Double.isFinite(yaw) || yaw < -180 || yaw >= 180
          || !Double.isFinite(pitch) || pitch < -90 || pitch > 90) {
        throw new IllegalArgumentException("invalid approved orientation");
      }
    }
  }
  /** Created only by the lifecycle/dispatch owner after a canonical ACCEPTED admission. */
  public record VerifiedAdmission(String requestId, Intent intent, long requestDeadlineUtcMillis,
      long requestDeadlineMonotonicMillis, Bounds approvedBounds,
      ApprovedOrientation approvedOrientation) {
    public VerifiedAdmission {
      require(requestId); Objects.requireNonNull(intent); Objects.requireNonNull(approvedBounds);
      if (requestDeadlineUtcMillis < 0 || requestDeadlineMonotonicMillis < 0
      || intent == Intent.LOOK && approvedBounds.maximumDistance != 0
          || intent == Intent.LOOK && approvedOrientation == null
          || intent == Intent.BOUNDED_WALK && (approvedBounds.maximumDistance <= 0
              || approvedOrientation != null)) throw new IllegalArgumentException("invalid admitted request");
    }
  }
  /** Trusted live connection, basis, region and control-health observation from server-owned ports. */
  public record CurrentContext(Binding binding, String dimensionId, Position position, long monotonicNow,
      long grantDeadlineMonotonicMillis, long freshBasisUntilMonotonicMillis) {
    public CurrentContext {
      Objects.requireNonNull(binding); require(dimensionId); Objects.requireNonNull(position);
      if (monotonicNow < 0 || grantDeadlineMonotonicMillis < monotonicNow || freshBasisUntilMonotonicMillis < monotonicNow) throw new IllegalArgumentException("invalid current context");
    }
  }
  public record Permit(String permitId, Binding binding, String requestId, Intent intent, Bounds bounds,
      long issuedAtUtcMillis, long expiresAtUtcMillis, long expiresAtMonotonicMillis,
      long requestDeadlineMonotonicMillis, long grantDeadlineMonotonicMillis, double travelledDistance,
      ApprovedOrientation approvedOrientation) {}

  private final Supplier<String> permitIds;
  private Binding currentBinding;
  private Permit active;
  private boolean revoked;
  private Binding revokedBinding;
  private long lastMonotonic = -1;

  public PermitAuthority(Binding initialBinding) { this(initialBinding, PermitAuthority::randomPermitId); }
  PermitAuthority(Binding initialBinding, Supplier<String> permitIds) {
    currentBinding = Objects.requireNonNull(initialBinding);
    this.permitIds = Objects.requireNonNull(permitIds);
  }

  public Optional<Permit> issue(VerifiedAdmission admission, CurrentContext context, long wallNowMillis) {
    requireClock(context.monotonicNow());
    if (wallNowMillis < 0 || revoked || !currentBinding.equals(context.binding())
        || revokedBinding != null && revokedBinding.equals(context.binding())) return Optional.empty();
    if (admission.requestDeadlineMonotonicMillis() <= context.monotonicNow()
        || context.grantDeadlineMonotonicMillis() <= context.monotonicNow()
        || context.freshBasisUntilMonotonicMillis() <= context.monotonicNow()
        || !admission.approvedBounds().dimensionId().equals(context.dimensionId())
        || !admission.approvedBounds().contains(context.position())) return Optional.empty();
    long requestDeadline = admission.requestDeadlineMonotonicMillis();
    long grantDeadline = context.grantDeadlineMonotonicMillis();
    double travelled = 0;
    if (active != null && active.expiresAtMonotonicMillis() <= context.monotonicNow()) active = null;
    if (active != null) {
      if (!active.binding().equals(context.binding()) || !active.requestId().equals(admission.requestId())
          || active.intent() != admission.intent() || !active.bounds().equals(admission.approvedBounds())
          || !Objects.equals(active.approvedOrientation(), admission.approvedOrientation())
          || active.requestDeadlineMonotonicMillis() != requestDeadline
          || active.grantDeadlineMonotonicMillis() != grantDeadline) {
        return Optional.empty();
      }
      travelled = active.travelledDistance();
      active = null;
    }
    long expiry = Math.min(requestDeadline,
        Math.min(grantDeadline, safeAdd(context.monotonicNow(), MAX_LEASE_MILLIS)));
    if (expiry <= context.monotonicNow()) return Optional.empty();
    long lease = expiry - context.monotonicNow();
    long expiresUtc = safeAdd(wallNowMillis, lease);
    if (expiresUtc < 0) return Optional.empty();
    String permitId = permitIds.get();
    require(permitId);
    active = new Permit(permitId, context.binding(), admission.requestId(), admission.intent(), admission.approvedBounds(),
        wallNowMillis, expiresUtc, expiry, requestDeadline, grantDeadline, travelled,
        admission.approvedOrientation());
    return Optional.of(active);
  }

  public Gate gate(String permitId, CurrentContext context, Position observedPosition, double yaw, double pitch) {
    requireClock(context.monotonicNow());
    if (active != null && active.expiresAtMonotonicMillis() <= context.monotonicNow()) active = null;
    if (active == null || revoked || !currentBinding.equals(context.binding()) || !active.permitId().equals(permitId) || !active.binding().equals(context.binding())
        || !active.bounds().dimensionId().equals(context.dimensionId())
        || !active.bounds().contains(observedPosition) || !validRotation(yaw, pitch)) return Gate.DENY;
    if (active.intent() == Intent.LOOK) {
      ApprovedOrientation approved = active.approvedOrientation();
      return samePosition(context.position(), observedPosition)
          && shortestYawDifference(yaw, approved.yaw()) <= 2
          && Math.abs(pitch - approved.pitch()) <= 2 ? Gate.ALLOW : Gate.DENY;
    }
    double step = distance(context.position(), observedPosition);
    if (active.travelledDistance() + step > active.bounds().maximumDistance()) return Gate.DENY;
    active = new Permit(active.permitId(), active.binding(), active.requestId(), active.intent(), active.bounds(),
        active.issuedAtUtcMillis(), active.expiresAtUtcMillis(), active.expiresAtMonotonicMillis(),
        active.requestDeadlineMonotonicMillis(), active.grantDeadlineMonotonicMillis(),
        active.travelledDistance() + step, active.approvedOrientation());
    return Gate.ALLOW;
  }

  public void revoke(Binding binding, RevokeReason reason) { Objects.requireNonNull(reason); if (!currentBinding.equals(binding)) return; active = null; revoked = true; revokedBinding = binding; }
  public Binding binding() { return currentBinding; }
  public boolean canRebind(Binding next, boolean quiescent) { return next != null && quiescent && revokedBinding != null && sameBody(revokedBinding, next) && next.epoch() > revokedBinding.epoch(); }
  public boolean canRebind(Binding next) { return canRebind(next, true); }
  public boolean rebind(Binding next, boolean quiescent) { if (!canRebind(next, quiescent)) return false; active = null; revoked = false; revokedBinding = null; currentBinding = next; return true; }
  public Optional<Permit> activePermit() { return Optional.ofNullable(active); }

  private void requireClock(long now) { if (now < 0 || now < lastMonotonic) { revoked = true; active = null; throw new IllegalStateException("monotonic clock rollback"); } lastMonotonic = now; }
  private static long safeAdd(long left, long right) { return left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right; }
  private static boolean samePosition(Position left, Position right) { return Double.compare(left.x, right.x) == 0 && Double.compare(left.y, right.y) == 0 && Double.compare(left.z, right.z) == 0; }
  private static double distance(Position left, Position right) { return Math.sqrt(Math.pow(left.x - right.x, 2) + Math.pow(left.y - right.y, 2) + Math.pow(left.z - right.z, 2)); }
  private static boolean validRotation(double yaw, double pitch) { return Double.isFinite(yaw) && yaw >= -180 && yaw < 180 && Double.isFinite(pitch) && pitch >= -90 && pitch <= 90; }
  private static double shortestYawDifference(double left, double right) { double difference = Math.abs(left - right) % 360; return difference > 180 ? 360 - difference : difference; }
  private static boolean sameBody(Binding left, Binding right) { return left.actorId.equals(right.actorId) && left.worldId.equals(right.worldId) && left.bodyId.equals(right.bodyId); }
  private static String randomPermitId() { byte[] value = new byte[32]; RANDOM.nextBytes(value); return Base64.getUrlEncoder().withoutPadding().encodeToString(value); }
  private static void require(String value) { if (value == null || value.isBlank()) throw new IllegalArgumentException("blank field"); }
}
