package io.github.jinyiwei2012.luosandbox.policy;

import java.util.Objects;

/** Pure proposal checks. Trusted ports must supply bindings and observations. */
public final class AuthorityPolicy {
  public static final long OBSERVATION_TTL_MILLIS = 1_000;

  private AuthorityPolicy() {}

  public enum Capability { QUERY, PUBLIC_SAY, LOOK, BOUNDED_WALK }
  public enum Code { ACCEPTED, BINDING_MISMATCH, STALE_EPOCH, DEADLINE_EXPIRED, BACKPRESSURE,
    ID_CONFLICT, STALE_BASIS, CAPABILITY_UNAVAILABLE, REGION_DENIED, UNSAFE_TARGET, QUARANTINED }

  public record Binding(String actorId, String worldId, String bodyId, String sessionId, long epoch) {
    public Binding { require(actorId); require(worldId); require(bodyId); require(sessionId); if (epoch < 0) throw new IllegalArgumentException("negative epoch"); }
  }

  public record Position(double x, double y, double z) {
    public Position { if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) throw new IllegalArgumentException("non-finite position"); }
  }

  public record Region(String dimensionId, double minX, double maxX, double minY, double maxY, double minZ, double maxZ) {
    public Region { require(dimensionId); if (!(minX <= maxX && minY <= maxY && minZ <= maxZ)) throw new IllegalArgumentException("invalid region"); }
    public boolean contains(String dimension, Position position) { return dimensionId.equals(dimension) && position.x() >= minX && position.x() <= maxX && position.y() >= minY && position.y() <= maxY && position.z() >= minZ && position.z() <= maxZ; }
  }

  public record Observation(String id, String bootId, long revision, boolean current, String dimensionId,
      Position position, long observedAtEpochMillis) {
    public Observation {
      require(id);
      require(bootId);
      require(dimensionId);
      Objects.requireNonNull(position);
      if (revision < 0 || observedAtEpochMillis < 0 && observedAtEpochMillis != Long.MIN_VALUE) {
        throw new IllegalArgumentException("negative observation time");
      }
    }

    public Observation(String id, String bootId, long revision, boolean current, String dimensionId,
        Position position) {
      this(id, bootId, revision, current, dimensionId, position, Long.MIN_VALUE);
    }
  }

  public record Basis(String observationId, String bootId, long revision) {
    public Basis { require(observationId); require(bootId); if (revision < 0) throw new IllegalArgumentException("negative revision"); }
  }

  public record Proposal(String requestId, Binding binding, Capability capability,
      long timestampEpochMillis, long deadlineEpochMillis, Basis basis, String dimensionId, Position target,
      double maximumDistance) {
    public Proposal { require(requestId); Objects.requireNonNull(binding); Objects.requireNonNull(capability); if (timestampEpochMillis < 0 || deadlineEpochMillis < 0) throw new IllegalArgumentException("negative time"); }
    public boolean effectful() { return capability != Capability.QUERY; }
  }

  /** Checks request-envelope constraints and admission-time authority from the wall-clock snapshot. */
  public static Code checkAdmission(Proposal proposal, Binding current, Observation observation, Region region,
      java.util.Set<Capability> capabilities, boolean quarantined, long wallNowMillis) {
    if (!proposal.binding().equals(current)) return Code.BINDING_MISMATCH;
    if (proposal.deadlineEpochMillis() <= proposal.timestampEpochMillis()
        || proposal.deadlineEpochMillis() - proposal.timestampEpochMillis() > 5_000
        || proposal.deadlineEpochMillis() <= wallNowMillis) return Code.DEADLINE_EXPIRED;
    if (!capabilities.contains(proposal.capability())) return Code.CAPABILITY_UNAVAILABLE;
    if (!proposal.effectful()) return Code.ACCEPTED;
    if (quarantined) return Code.QUARANTINED;
    return checkEffectAuthority(proposal, observation, region, wallNowMillis);
  }

  /** Rechecks dispatch authority without reevaluating an already-converted request deadline. */
  public static Code checkDispatch(Proposal proposal, Binding current, Observation observation, Region region,
      java.util.Set<Capability> capabilities, boolean quarantined, long observationNowMillis) {
    if (!proposal.binding().equals(current)) return Code.BINDING_MISMATCH;
    if (!capabilities.contains(proposal.capability())) return Code.CAPABILITY_UNAVAILABLE;
    if (!proposal.effectful()) return Code.ACCEPTED;
    if (quarantined) return Code.QUARANTINED;
    return checkEffectAuthority(proposal, observation, region, observationNowMillis);
  }

  /** @deprecated Use {@link #checkAdmission} or {@link #checkDispatch} for the lifecycle phase. */
  @Deprecated
  public static Code check(Proposal proposal, Binding current, Observation observation, Region region,
      java.util.Set<Capability> capabilities, boolean quarantined, long wallNowMillis) {
    return checkAdmission(proposal, current, observation, region, capabilities, quarantined, wallNowMillis);
  }

  private static Code checkEffectAuthority(Proposal proposal, Observation observation, Region region,
      long observationNowMillis) {
    if (proposal.basis() == null || observation == null || !observation.current()
        || !isFresh(observation, observationNowMillis)
        || !proposal.basis().observationId().equals(observation.id())
        || !proposal.basis().bootId().equals(observation.bootId())
        || proposal.basis().revision() != observation.revision()) return Code.STALE_BASIS;
    if (proposal.capability() == Capability.PUBLIC_SAY) return Code.ACCEPTED;
    if (proposal.target() == null || proposal.dimensionId() == null
        || !proposal.dimensionId().equals(observation.dimensionId())
        || !region.contains(proposal.dimensionId(), proposal.target())) return Code.REGION_DENIED;
    double dx = proposal.target().x() - observation.position().x();
    double dz = proposal.target().z() - observation.position().z();
    double planarDistance = Math.hypot(dx, dz);
    if (planarDistance > 4) return Code.UNSAFE_TARGET;
    if (proposal.capability() == Capability.BOUNDED_WALK
        && (!(proposal.maximumDistance() > 0) || proposal.maximumDistance() > 4
        || planarDistance > proposal.maximumDistance())) return Code.UNSAFE_TARGET;
    return Code.ACCEPTED;
  }

  private static boolean isFresh(Observation observation, long nowMillis) {
    if (observation.observedAtEpochMillis() == Long.MIN_VALUE) return true;
    return nowMillis >= observation.observedAtEpochMillis()
        && nowMillis - observation.observedAtEpochMillis() <= OBSERVATION_TTL_MILLIS;
  }

  private static void require(String value) { if (value == null || value.isBlank()) throw new IllegalArgumentException("blank field"); }
}
