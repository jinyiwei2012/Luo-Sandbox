package io.github.jinyiwei2012.luosandbox.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class PendingHandshakeTest {
  @Test
  void completionReturnsImmutableContextAndConsumesProofOnce() {
    byte[] key = bytes(7);
    ControlAuth.Transcript transcript = transcript();
    PendingHandshake pending = new PendingHandshake(Map.of("client", transcript), Map.of("client", key));
    String peerProof = ControlAuth.b32(ControlAuth.hmac(key, ControlAuth.transcript(ControlAuth.Label.PEER_PROOF, transcript)));
    assertTrue(pending.begin("a", "client", 100));
    PendingHandshake.AuthenticatedHandshake result = pending.consumePeerProof("a", peerProof, 101).orElseThrow();
    assertEquals("a", result.connectionId());
    assertEquals(ControlAuth.Role.CLIENT_CONTROLLER, result.role());
    assertTrue(ControlAuth.verify(key, ControlAuth.Label.READY_PROOF, transcript, ControlAuth.b32(result.readyProof())));
    assertFalse(pending.consumePeerProof("a", peerProof, 102).isPresent());
    byte[] hash = result.modHash(); hash[0] = 99;
    assertEquals(0, result.modHash()[0]);
  }

  @Test
  void malformedOrForeignProofConsumesExactlyOneChallengeAndExpiryFreesSlots() {
    byte[] key = bytes(7);
    ControlAuth.Transcript transcript = transcript();
    PendingHandshake pending = new PendingHandshake(Map.of("client", transcript), Map.of("client", key));
    String proof = ControlAuth.b32(ControlAuth.hmac(key, ControlAuth.transcript(ControlAuth.Label.PEER_PROOF, transcript)));
    assertTrue(pending.begin("a", "client", 100));
    assertFalse(pending.consumePeerProof("b", proof, 101).isPresent());
    assertFalse(pending.consumePeerProof("a", "bad", 101).isPresent());
    assertTrue(pending.begin("a", "client", 100));
    assertFalse(pending.consumePeerProof("a", proof, 5_100).isPresent());
    for (int index = 0; index < 4; index++) assertTrue(pending.begin("stale-" + index, "client", 100));
    assertTrue(pending.begin("fresh", "client", 5_100));
    assertEquals(1, pending.pendingCount(5_100));
  }

  @Test
  void synchronizedCapacityAllowsAtMostFourConcurrentBegins() throws Exception {
    PendingHandshake pending = new PendingHandshake(Map.of("client", transcript()), Map.of("client", bytes(7)));
    CountDownLatch start = new CountDownLatch(1);
    AtomicInteger accepted = new AtomicInteger();
    Thread[] workers = new Thread[12];
    for (int index = 0; index < workers.length; index++) {
      int current = index;
      workers[index] = new Thread(() -> { try { start.await(); if (pending.begin("c" + current, "client", 100)) accepted.incrementAndGet(); } catch (InterruptedException error) { Thread.currentThread().interrupt(); } });
      workers[index].start();
    }
    start.countDown();
    for (Thread worker : workers) worker.join();
    assertEquals(4, accepted.get());
    assertEquals(4, pending.pendingCount(101));
  }

  private static ControlAuth.Transcript transcript() { return new ControlAuth.Transcript(ControlAuth.Role.CLIENT_CONTROLLER, "client", "hub", "key", "proto", new byte[32], bytes(1), bytes(2), "00000000-0000-4000-8000-000000000001", Instant.parse("2026-10-23T00:00:00Z"), Instant.parse("2026-10-23T00:00:05Z"), bytes(3), bytes(4)); }
  private static byte[] bytes(int value) { byte[] result = new byte[32]; java.util.Arrays.fill(result, (byte) value); return result; }
}
