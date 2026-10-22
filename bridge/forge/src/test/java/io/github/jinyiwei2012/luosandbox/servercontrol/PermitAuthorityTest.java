package io.github.jinyiwei2012.luosandbox.servercontrol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class PermitAuthorityTest {
  private static final PermitAuthority.Binding BINDING = new PermitAuthority.Binding("actor", "world", "body", "session", 1, "boot", "connection");
  private static final PermitAuthority.Bounds WALK = new PermitAuthority.Bounds("minecraft:overworld", new PermitAuthority.Position(-4, 0, -4), new PermitAuthority.Position(4, 128, 4), 4);
  private static final PermitAuthority.Bounds LOOK = new PermitAuthority.Bounds("minecraft:overworld", new PermitAuthority.Position(-4, 0, -4), new PermitAuthority.Position(4, 128, 4), 0);

  @Test void issuesBoundedLeaseAndRejectsQueryLikeOrExpiredContexts() {
    PermitAuthority authority = authority();
    PermitAuthority.Permit permit = authority.issue(admission("walk", PermitAuthority.Intent.BOUNDED_WALK, WALK, 2_000), context(BINDING, 100), 1_000).orElseThrow();
    assertEquals(750, permit.expiresAtMonotonicMillis() - 100);
    assertEquals(PermitAuthority.Gate.ALLOW, authority.gate("id-1", context(BINDING, 101), new PermitAuthority.Position(1, 64, 0), 0, 0));
    assertEquals(PermitAuthority.Gate.DENY, authority.gate("id-1", context(BINDING, 850), new PermitAuthority.Position(1, 64, 0), 0, 0));
    authority.revoke(BINDING, PermitAuthority.RevokeReason.DEADLINE);
    assertTrue(authority.rebind(nextBinding(), true));
    assertFalse(authority.issue(admission("look", PermitAuthority.Intent.LOOK, LOOK, 101), context(nextBinding(), 850), 1_000).isPresent());
  }

  @Test void renewalInvalidatesOldIdAndPreservesDeadlineAndCumulativeDistance() {
    PermitAuthority authority = authority();
    authority.issue(admission("walk", PermitAuthority.Intent.BOUNDED_WALK, WALK, 500), context(BINDING, 0), 1_000).orElseThrow();
    assertEquals(PermitAuthority.Gate.ALLOW, authority.gate("id-1", context(BINDING, 1), new PermitAuthority.Position(3, 64, 0), 0, 0));
    PermitAuthority.Permit renewal = authority.issue(admission("walk", PermitAuthority.Intent.BOUNDED_WALK, WALK, 500), context(BINDING, 2), 1_002).orElseThrow();
    assertEquals(500, renewal.expiresAtMonotonicMillis());
    assertEquals(PermitAuthority.Gate.DENY, authority.gate("id-1", context(BINDING, 3), new PermitAuthority.Position(3, 64, 0), 0, 0));
    assertEquals(PermitAuthority.Gate.ALLOW, authority.gate("id-2", contextAt(BINDING, 3, 3), new PermitAuthority.Position(3, 64, 0), 0, 0));
    assertEquals(PermitAuthority.Gate.DENY, authority.gate("id-2", contextAt(BINDING, 4, 3), new PermitAuthority.Position(-2, 64, 0), 0, 0));
  }

  @Test void rejectsBindingEmergencyLookMovementAndUnsafeObservedPath() {
    PermitAuthority authority = authority();
    PermitAuthority.Permit look = authority.issue(admission("look", PermitAuthority.Intent.LOOK, LOOK, 900), context(BINDING, 0), 1_000).orElseThrow();
    assertEquals(PermitAuthority.Gate.DENY, authority.gate(look.permitId(), context(BINDING, 1), new PermitAuthority.Position(1, 64, 0), 0, 0));
    authority.revoke(BINDING, PermitAuthority.RevokeReason.EMERGENCY);
    assertEquals(PermitAuthority.Gate.DENY, authority.gate(look.permitId(), context(BINDING, 2), new PermitAuthority.Position(0, 64, 0), 0, 0));
    PermitAuthority.Binding next = new PermitAuthority.Binding("actor", "world", "body", "next", 2, "next-boot", "next-connection");
    assertTrue(authority.rebind(next, true));
    PermitAuthority.Permit walk = authority.issue(admission("walk", PermitAuthority.Intent.BOUNDED_WALK, WALK, 900), context(next, 3), 1_003).orElseThrow();
    assertFalse(authority.issue(admission("walk", PermitAuthority.Intent.BOUNDED_WALK, WALK, 900), context(BINDING, 4), 1_004).isPresent());
    assertEquals(PermitAuthority.Gate.DENY, authority.gate(walk.permitId(), context(BINDING, 4), new PermitAuthority.Position(0, 64, 0), 0, 0));
    PermitAuthority.Binding wrongBoot = new PermitAuthority.Binding("actor", "world", "body", "next", 2, "old-boot", "next-connection");
    assertEquals(PermitAuthority.Gate.DENY, authority.gate(walk.permitId(), context(wrongBoot, 4), new PermitAuthority.Position(0, 64, 0), 0, 0));
    PermitAuthority.Binding wrongConnection = new PermitAuthority.Binding("actor", "world", "body", "next", 2, "next-boot", "old-connection");
    assertEquals(PermitAuthority.Gate.DENY, authority.gate(walk.permitId(), context(wrongConnection, 4), new PermitAuthority.Position(0, 64, 0), 0, 0));
    assertEquals(PermitAuthority.Gate.DENY, authority.gate(walk.permitId(), context(next, 4), new PermitAuthority.Position(5, 64, 0), 0, 0));
  }

  @Test void monotonicRollbackFailsClosedAndOnlyOnePermitIsActive() {
    PermitAuthority authority = authority();
    authority.issue(admission("one", PermitAuthority.Intent.BOUNDED_WALK, WALK, 900), context(BINDING, 100), 1_000);
    assertFalse(authority.issue(admission("two", PermitAuthority.Intent.BOUNDED_WALK, WALK, 900), context(BINDING, 101), 1_001).isPresent());
    assertEquals("id-1", authority.activePermit().orElseThrow().permitId());
    assertEquals(PermitAuthority.Gate.ALLOW, authority.gate("id-1", context(BINDING, 102), new PermitAuthority.Position(0, 64, 0), 0, 0));
    try { authority.gate("id-1", context(BINDING, 99), new PermitAuthority.Position(0, 64, 0), 0, 0); } catch (IllegalStateException expected) { }
    assertFalse(authority.activePermit().isPresent());
  }

  private static PermitAuthority authority() { AtomicInteger ids = new AtomicInteger(); return new PermitAuthority(BINDING, () -> "id-" + ids.incrementAndGet()); }
  private static PermitAuthority.VerifiedAdmission admission(String id, PermitAuthority.Intent intent, PermitAuthority.Bounds bounds, long deadline) { return new PermitAuthority.VerifiedAdmission(id, intent, 1_000 + deadline, deadline, bounds); }
  private static PermitAuthority.CurrentContext context(PermitAuthority.Binding binding, long now) { return new PermitAuthority.CurrentContext(binding, "minecraft:overworld", new PermitAuthority.Position(0, 64, 0), now, 1_000, 1_000); }
  private static PermitAuthority.CurrentContext contextAt(PermitAuthority.Binding binding, long now, double x) { return new PermitAuthority.CurrentContext(binding, "minecraft:overworld", new PermitAuthority.Position(x, 64, 0), now, 1_000, 1_000); }
  private static PermitAuthority.Binding nextBinding() { return new PermitAuthority.Binding("actor", "world", "body", "next", 2, "next-boot", "next-connection"); }
}
