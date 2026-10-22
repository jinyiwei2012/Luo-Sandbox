package io.github.jinyiwei2012.luosandbox.policy;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Set;
import org.junit.jupiter.api.Test;

class AuthorityPolicyTest {
  private static final AuthorityPolicy.Binding BINDING = new AuthorityPolicy.Binding("actor", "world", "body", "session", 1);
  private static final AuthorityPolicy.Region REGION = new AuthorityPolicy.Region("minecraft:overworld", -4, 4, 0, 128, -4, 4);
  private static final AuthorityPolicy.Observation OBSERVATION = new AuthorityPolicy.Observation("obs", "boot", 7, true, "minecraft:overworld", new AuthorityPolicy.Position(0, 64, 0));

  @Test
  void effectRequiresCurrentKnownBasisAndSafeBoundedTarget() {
    AuthorityPolicy.Proposal walk = proposal(AuthorityPolicy.Capability.BOUNDED_WALK, new AuthorityPolicy.Basis("obs", "boot", 7), new AuthorityPolicy.Position(3, 64, 0), 4);
    assertEquals(AuthorityPolicy.Code.ACCEPTED, check(walk, OBSERVATION, false));
    assertEquals(AuthorityPolicy.Code.STALE_BASIS, check(proposal(AuthorityPolicy.Capability.LOOK, null, new AuthorityPolicy.Position(1, 64, 0), 0), OBSERVATION, false));
    assertEquals(AuthorityPolicy.Code.UNSAFE_TARGET, check(proposal(AuthorityPolicy.Capability.BOUNDED_WALK, new AuthorityPolicy.Basis("obs", "boot", 7), new AuthorityPolicy.Position(4, 64, 4), 4), OBSERVATION, false));
    assertEquals(AuthorityPolicy.Code.QUARANTINED, check(walk, OBSERVATION, true));
  }

  @Test
  void queryIsIndependentOfEffectBusyAndQuarantineButNotBindingOrDeadline() {
    AuthorityPolicy.Proposal query = proposal(AuthorityPolicy.Capability.QUERY, null, null, 0);
    assertEquals(AuthorityPolicy.Code.ACCEPTED, check(query, null, true));
    AuthorityPolicy.Proposal wrongBinding = new AuthorityPolicy.Proposal("q2", new AuthorityPolicy.Binding("actor", "world", "body", "other", 1), AuthorityPolicy.Capability.QUERY, 100, 200, null, null, null, 0);
    assertEquals(AuthorityPolicy.Code.BINDING_MISMATCH, check(wrongBinding, null, true));
    AuthorityPolicy.Proposal expired = new AuthorityPolicy.Proposal("q3", BINDING, AuthorityPolicy.Capability.QUERY, 100, 100, null, null, null, 0);
    assertEquals(AuthorityPolicy.Code.DEADLINE_EXPIRED, check(expired, null, true));
  }

  @Test
  void publicSayNeedsCurrentBasisButNoInventedTargetAndGeometryStaysSameDimension() {
    AuthorityPolicy.Proposal say = new AuthorityPolicy.Proposal("say", BINDING,
        AuthorityPolicy.Capability.PUBLIC_SAY, 100, 200,
        new AuthorityPolicy.Basis("obs", "boot", 7), null, null, 0);
    assertEquals(AuthorityPolicy.Code.ACCEPTED, check(say, OBSERVATION, false));
    AuthorityPolicy.Proposal crossDimension = new AuthorityPolicy.Proposal("cross", BINDING,
        AuthorityPolicy.Capability.LOOK, 100, 200,
        new AuthorityPolicy.Basis("obs", "boot", 7), "minecraft:nether",
        new AuthorityPolicy.Position(1, 64, 0), 0);
    assertEquals(AuthorityPolicy.Code.REGION_DENIED, check(crossDimension, OBSERVATION, false));
  }

  @Test
  void dispatchDoesNotReevaluateAdmissionDeadlineButRequiresFreshAuthority() {
    AuthorityPolicy.Proposal look = proposal(AuthorityPolicy.Capability.LOOK,
        new AuthorityPolicy.Basis("obs", "boot", 7), new AuthorityPolicy.Position(1, 64, 0), 0);
    AuthorityPolicy.Observation freshAtAdmission = new AuthorityPolicy.Observation("obs", "boot", 7, true,
        "minecraft:overworld", new AuthorityPolicy.Position(0, 64, 0), 100);

    assertEquals(AuthorityPolicy.Code.ACCEPTED, AuthorityPolicy.checkAdmission(look, BINDING,
        freshAtAdmission, REGION, Set.of(AuthorityPolicy.Capability.values()), false, 100));
    assertEquals(AuthorityPolicy.Code.ACCEPTED, AuthorityPolicy.checkDispatch(look, BINDING,
        freshAtAdmission, REGION, Set.of(AuthorityPolicy.Capability.values()), false, 201));

    AuthorityPolicy.Observation stale = new AuthorityPolicy.Observation("obs", "boot", 7, true,
        "minecraft:overworld", new AuthorityPolicy.Position(0, 64, 0), 100);
    assertEquals(AuthorityPolicy.Code.STALE_BASIS, AuthorityPolicy.checkDispatch(look, BINDING, stale,
        REGION, Set.of(AuthorityPolicy.Capability.values()), false, 1_101));
  }

  private static AuthorityPolicy.Code check(AuthorityPolicy.Proposal proposal, AuthorityPolicy.Observation observation, boolean quarantine) {
    return AuthorityPolicy.check(proposal, BINDING, observation, REGION, Set.of(AuthorityPolicy.Capability.values()), quarantine, 100);
  }

  private static AuthorityPolicy.Proposal proposal(AuthorityPolicy.Capability capability, AuthorityPolicy.Basis basis, AuthorityPolicy.Position target, double distance) {
    return new AuthorityPolicy.Proposal("request-" + capability, BINDING, capability, 100, 200, basis, "minecraft:overworld", target, distance);
  }
}
