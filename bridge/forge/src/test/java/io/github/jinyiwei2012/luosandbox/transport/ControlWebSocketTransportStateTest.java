package io.github.jinyiwei2012.luosandbox.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.jinyiwei2012.luosandbox.security.ControlAuth;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

class ControlWebSocketTransportStateTest {
  private static final byte[] KEY = bytes(7);
  private static final byte[] HASH = new byte[32];

  @Test
  void authenticatesThenSchedulesHeartbeatAndWatchdog() {
    Fixture fixture = fixture();
    fixture.open();
    ControlAuth.Transcript transcript = fixture.challenge("00000000-0000-4000-8000-000000000001", true);
    assertEquals(ControlWebSocketTransport.State.PENDING, fixture.transport.state());
    fixture.ready(transcript);
    assertEquals(ControlWebSocketTransport.State.AUTHENTICATED, fixture.transport.state());
    fixture.scheduler.advance(500);
    assertTrue(fixture.socket.sent.stream().anyMatch(text -> text.contains("\"HEARTBEAT\"")));
    fixture = authenticated();
    fixture.scheduler.advance(1_999);
    assertEquals(ControlWebSocketTransport.State.AUTHENTICATED, fixture.transport.state());
    fixture.scheduler.advance(1);
    assertEquals(ControlWebSocketTransport.StopReason.HEARTBEAT_TIMEOUT, fixture.transport.reason());
  }

  @Test
  void rejectsRepeatedChallengeBadReadyAndUtcRollback() {
    Fixture fixture = fixture(); fixture.open();
    ControlAuth.Transcript transcript = fixture.challenge("00000000-0000-4000-8000-000000000001", true);
    fixture.challenge("00000000-0000-4000-8000-000000000002", true);
    assertEquals(ControlWebSocketTransport.StopReason.AUTH_REJECTED, fixture.transport.reason());

    fixture = fixture(); fixture.open(); fixture.clock.now = fixture.clock.now.plusSeconds(4);
    transcript = fixture.challenge("00000000-0000-4000-8000-000000000001", true);
    fixture.clock.now = Instant.parse("2026-10-23T00:00:01.000Z");
    fixture.ready(transcript);
    assertEquals(ControlWebSocketTransport.StopReason.AUTH_REJECTED, fixture.transport.reason());

    fixture = fixture(); fixture.open(); transcript = fixture.challenge("00000000-0000-4000-8000-000000000001", true);
    fixture.text("{\"security\":\"luosandbox.control-auth.v1\",\"kind\":\"READY\",\"challengeId\":\"00000000-0000-4000-8000-000000000001\",\"readyProof\":\"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA\"}");
    assertEquals(ControlWebSocketTransport.StopReason.AUTH_REJECTED, fixture.transport.reason());

    fixture = fixture(); fixture.open(); fixture.clock.now = fixture.clock.now.minusSeconds(1);
    fixture.challenge("00000000-0000-4000-8000-000000000001", true);
    assertEquals(ControlWebSocketTransport.StopReason.AUTH_REJECTED, fixture.transport.reason());
  }

  @Test
  void rejectsBinaryAndDuplicateInboundSequenceWithoutWatchdogRefresh() {
    Fixture fixture = authenticated();
    fixture.binary();
    assertEquals(ControlWebSocketTransport.StopReason.PROTOCOL_REJECTED, fixture.transport.reason());

    fixture = authenticated();
    fixture.text("{\"security\":\"luosandbox.control-auth.v1\",\"kind\":\"HEARTBEAT\",\"sequence\":0}");
    fixture.text("{\"security\":\"luosandbox.control-auth.v1\",\"kind\":\"HEARTBEAT\",\"sequence\":0}");
    assertEquals(ControlWebSocketTransport.StopReason.PROTOCOL_REJECTED, fixture.transport.reason());
  }

  @Test
  void fragmentsAndCloseCancelOwnedTasksAndStaleCallbacks() {
    Fixture fixture = fixture(); fixture.open();
    fixture.callbacks.text("{\"security\":\"luosandbox.control-auth.v1\",\"kind\":\"", false);
    fixture.callbacks.text("HEARTBEAT\"}", true);
    assertEquals(ControlWebSocketTransport.StopReason.AUTH_REJECTED, fixture.transport.reason());

    fixture = authenticated();
    ControlWebSocketTransport.Callbacks old = fixture.callbacks;
    fixture.transport.close();
    assertEquals(0, fixture.scheduler.live());
    old.opened(fixture.socket);
    assertEquals(ControlWebSocketTransport.State.CLOSED, fixture.transport.state());
  }

  @Test
  void heldSendQueueIsBoundedAndBackpressureFencesFollowers() {
    Fixture fixture = authenticated();
    fixture.socket.holdNext();
    int callsBefore = fixture.socket.sent.size();
    assertTrue(fixture.transport.sendHeartbeat());
    for (int index = 0; index < 63; index++) assertTrue(fixture.transport.sendHeartbeat());
    assertFalse(fixture.transport.sendHeartbeat());
    assertEquals(ControlWebSocketTransport.StopReason.BACKPRESSURE, fixture.transport.reason());
    assertEquals(0, fixture.scheduler.live());
    fixture.socket.completeHeld();
    assertEquals(callsBefore + 1, fixture.socket.sent.size());
  }

  @Test
  void failedInFlightSendClosesBeforeQueuedFollowersCanStart() {
    Fixture fixture = authenticated();
    fixture.socket.holdNext();
    assertTrue(fixture.transport.sendHeartbeat());
    assertTrue(fixture.transport.sendHeartbeat());
    int callsBeforeFailure = fixture.socket.sent.size();
    fixture.socket.failHeld();
    assertEquals(ControlWebSocketTransport.StopReason.DISCONNECTED, fixture.transport.reason());
    assertEquals(callsBeforeFailure, fixture.socket.sent.size());
    assertEquals(0, fixture.scheduler.live());
  }

  private static Fixture authenticated() { Fixture fixture = fixture(); fixture.open(); ControlAuth.Transcript transcript = fixture.challenge("00000000-0000-4000-8000-000000000001", true); fixture.ready(transcript); return fixture; }
  private static Fixture fixture() { return new Fixture(); }
  private static byte[] bytes(int fill) { byte[] value = new byte[32]; java.util.Arrays.fill(value, (byte) fill); return value; }

  private static final class Fixture {
    final MutableClock clock = new MutableClock(Instant.parse("2026-10-23T00:00:00.000Z"));
    final ManualScheduler scheduler = new ManualScheduler(); final FakeConnector connector = new FakeConnector(); final FakeSocket socket = new FakeSocket();
    final ControlWebSocketTransport transport = new ControlWebSocketTransport(config(), connector, clock, () -> scheduler.now * 1_000_000L, scheduler, (state, reason) -> {}, new java.security.SecureRandom(new byte[] {1}));
    ControlWebSocketTransport.Callbacks callbacks;
    void open() { assertTrue(transport.start()); callbacks = connector.callbacks; callbacks.opened(socket); }
    void binary() { callbacks.binary(); }
    void text(String value) { callbacks.text(value, true); }
    ControlAuth.Transcript challenge(String id, boolean valid) {
      String nonce = ControlAuth.b32(bytes(9)); Instant issued = clock.now, expires = issued.plusSeconds(5);
      String peerNonce = field(socket.sent.get(0), "peerNonce");
      ControlAuth.Transcript transcript = new ControlAuth.Transcript(ControlAuth.Role.CLIENT_CONTROLLER, "client-1", "hub-1", "key-1", ControlTransportConfig.PROTOCOL, HASH, ControlAuth.decodeB32(peerNonce), ControlAuth.decodeB32(nonce), id, issued, expires, HASH, HASH);
      String proof = ControlAuth.b32(ControlAuth.hmac(KEY, ControlAuth.transcript(ControlAuth.Label.HUB_PROOF, transcript)));
      if (!valid) proof = ControlAuth.b32(bytes(1));
      text("{\"security\":\"luosandbox.control-auth.v1\",\"kind\":\"CHALLENGE\",\"challengeId\":\"" + id + "\",\"hubNonce\":\"" + nonce + "\",\"issuedAt\":\"" + ControlAuth.formatUtc(issued) + "\",\"expiresAt\":\"" + ControlAuth.formatUtc(expires) + "\",\"hubProof\":\"" + proof + "\"}");
      return transcript;
    }
    void ready(ControlAuth.Transcript transcript) { String proof = ControlAuth.b32(ControlAuth.hmac(KEY, ControlAuth.transcript(ControlAuth.Label.READY_PROOF, transcript))); text("{\"security\":\"luosandbox.control-auth.v1\",\"kind\":\"READY\",\"challengeId\":\"" + transcript.challengeId() + "\",\"readyProof\":\"" + proof + "\"}"); }
  }
  private static ControlTransportConfig config() { return new ControlTransportConfig(true, URI.create("ws://127.0.0.1:8765"), ControlAuth.Role.CLIENT_CONTROLLER, "client-1", "hub-1", "key-1", ControlTransportConfig.PROTOCOL, HASH, HASH, HASH, KEY); }
  private static String field(String json, String key) { String prefix = "\"" + key + "\":\""; int start = json.indexOf(prefix) + prefix.length(); return json.substring(start, json.indexOf('"', start)); }
  private static final class MutableClock extends Clock { Instant now; MutableClock(Instant now) { this.now = now; } @Override public ZoneOffset getZone() { return ZoneOffset.UTC; } @Override public Clock withZone(java.time.ZoneId zone) { return this; } @Override public Instant instant() { return now; } }
  private static final class FakeConnector implements ControlWebSocketTransport.Connector { ControlWebSocketTransport.Callbacks callbacks; @Override public CompletableFuture<ControlWebSocketTransport.Socket> connect(ControlTransportConfig config, ControlWebSocketTransport.Callbacks value) { callbacks = value; return new CompletableFuture<>(); } }
  private static final class FakeSocket implements ControlWebSocketTransport.Socket { final List<String> sent = new ArrayList<>(); CompletableFuture<Void> held; boolean hold; @Override public String subprotocol() { return ControlTransportConfig.PROTOCOL; } @Override public CompletableFuture<?> sendText(String text) { sent.add(text); if (hold) { hold = false; held = new CompletableFuture<>(); return held; } return CompletableFuture.completedFuture(null); } void holdNext() { hold = true; } void completeHeld() { held.complete(null); } void failHeld() { held.completeExceptionally(new IllegalStateException()); } @Override public void request(long count) {} @Override public void close(ControlWebSocketTransport.StopReason reason) {} @Override public void abort() {} }
  private static final class ManualScheduler implements ControlWebSocketTransport.Scheduler { long now; final List<Job> jobs = new ArrayList<>(); @Override public ControlWebSocketTransport.Task schedule(Runnable task, long delay) { return add(task, delay, 0); } @Override public ControlWebSocketTransport.Task periodic(Runnable task, long initial, long period) { return add(task, initial, period); } private ControlWebSocketTransport.Task add(Runnable task, long due, long period) { Job job = new Job(task, now + due, period); jobs.add(job); return () -> job.cancelled = true; } void advance(long millis) { now += millis; for (Job job : new ArrayList<>(jobs)) while (!job.cancelled && job.due <= now) { job.task.run(); if (job.period == 0) job.cancelled = true; else job.due += job.period; } } int live() { return (int) jobs.stream().filter(job -> !job.cancelled).count(); } @Override public void shutdown() { jobs.forEach(job -> job.cancelled = true); } private static final class Job { final Runnable task; long due; final long period; boolean cancelled; Job(Runnable task, long due, long period) { this.task = task; this.due = due; this.period = period; } } }
}
