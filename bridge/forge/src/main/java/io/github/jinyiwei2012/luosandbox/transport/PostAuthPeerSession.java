package io.github.jinyiwei2012.luosandbox.transport;

import io.github.jinyiwei2012.luosandbox.security.ControlAuth;
import io.github.jinyiwei2012.luosandbox.security.PostAuthFrameCodec;
import io.github.jinyiwei2012.luosandbox.security.PostAuthSessionKeys;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Unassembled peer-side v2 session owner. It owns ordering/liveness only; no socket, role registry,
 * BODY authority, coordinator, or activation is installed by this class.
 */
public final class PostAuthPeerSession {
  public static final long OPEN_TIMEOUT_MILLIS = 5_000;
  public static final long HEARTBEAT_INTERVAL_MILLIS = 500;
  public static final long HEARTBEAT_TIMEOUT_MILLIS = 2_000;
  public static final int OUTBOUND_CAPACITY = 8;

  @FunctionalInterface public interface OwnerThreadGate { boolean isOwnerThread(); }
  @FunctionalInterface public interface MonotonicMillis { long now(); }
  @FunctionalInterface public interface OwnerPost { boolean post(Runnable event); }
  @FunctionalInterface public interface OwnerAbort { boolean request(Runnable abort); }
  @FunctionalInterface public interface CleanupDiagnostics { void cleanupUnknown(String reason); }
  @FunctionalInterface public interface AsyncSender { CompletionStage<Void> send(byte[] immutableFrame); }
  @FunctionalInterface public interface ApplicationFrames {
    void receive(PostAuthFrameCodec.Frame frame);
  }
  public interface Lifecycle {
    void fence(String reason);
    void physicalClose(String reason);
  }

  public enum State { WAIT_SESSION_OPEN, OPEN, CLOSED }

  private record Entry(long generation, long identity, long sequence, byte[] encoded) {
    private Entry { encoded = encoded.clone(); }
    @Override public byte[] encoded() { return encoded.clone(); }
  }

  private final ControlAuth.Transcript transcript;
  private final PostAuthSessionKeys keys;
  private final OwnerThreadGate ownerThread;
  private final MonotonicMillis clock;
  private final OwnerPost ownerPost;
  private final OwnerAbort ownerAbort;
  private final CleanupDiagnostics diagnostics;
  private final AsyncSender sender;
  private final ApplicationFrames application;
  private final Lifecycle lifecycle;
  private final long generation;
  private final ArrayDeque<Entry> outbound = new ArrayDeque<>();
  private State state = State.WAIT_SESSION_OPEN;
  private String connectionId;
  private long openDeadline;
  private long heartbeatDeadline;
  private long nextHeartbeat;
  private long nextOutbound;
  private long expectedInbound;
  private long nextEntryIdentity;
  private long lastNow;
  private boolean outboundExhausted;
  private boolean inboundExhausted;
  private boolean inFlight;
  private final AtomicBoolean abortRequested = new AtomicBoolean();

  public PostAuthPeerSession(ControlAuth.Transcript transcript, PostAuthSessionKeys keys,
      long generation, OwnerThreadGate ownerThread, MonotonicMillis clock, OwnerPost ownerPost,
      OwnerAbort ownerAbort, CleanupDiagnostics diagnostics, AsyncSender sender,
      ApplicationFrames application, Lifecycle lifecycle) {
    this.transcript = Objects.requireNonNull(transcript);
    this.keys = Objects.requireNonNull(keys);
    if (generation < 0) throw new IllegalArgumentException("invalid generation");
    this.generation = generation;
    this.ownerThread = Objects.requireNonNull(ownerThread);
    this.clock = Objects.requireNonNull(clock);
    this.ownerPost = Objects.requireNonNull(ownerPost);
    this.ownerAbort = Objects.requireNonNull(ownerAbort);
    this.diagnostics = Objects.requireNonNull(diagnostics);
    this.sender = Objects.requireNonNull(sender);
    this.application = Objects.requireNonNull(application);
    this.lifecycle = Objects.requireNonNull(lifecycle);
    requireOwner();
    long now = checkedNow();
    lastNow = now;
    openDeadline = checkedDeadline(now, OPEN_TIMEOUT_MILLIS);
  }

  public State state() { requireOwner(); return state; }
  public String connectionId() { requireOwner(); return connectionId; }
  public int queuedFrames() { requireOwner(); return outbound.size(); }
  public long nextOutboundSequence() { requireOwner(); return nextOutbound; }
  public long expectedInboundSequence() { requireOwner(); return expectedInbound; }
  public ControlAuth.Role authenticatedRole() { requireOwner(); return transcript.peerRole(); }
  public String authenticatedPeerInstallId() { requireOwner(); return transcript.peerInstallId(); }
  public long generation() { requireOwner(); return generation; }

  /** Receive one Hub-to-peer frame. All transport currentness remains caller-owned. */
  public void receive(byte[] raw) {
    requireOwner();
    long now = beforeOperation();
    try {
      if (state == State.WAIT_SESSION_OPEN) {
        PostAuthFrameCodec.Context context = new PostAuthFrameCodec.Context(transcript, null);
        PostAuthFrameCodec.Frame frame = PostAuthFrameCodec.decode(
            snapshot(raw), context, PostAuthFrameCodec.Direction.HUB_TO_PEER, keys);
        if (!frame.messageKind().equals("SESSION_OPEN") || frame.sequence() != 0) {
          fatal("invalid_session_open"); return;
        }
        connectionId = frame.connectionId();
        state = State.OPEN;
        expectedInbound = 1;
        heartbeatDeadline = checkedDeadline(now, HEARTBEAT_TIMEOUT_MILLIS);
        nextHeartbeat = checkedDeadline(now, HEARTBEAT_INTERVAL_MILLIS);
        admit("HEARTBEAT", new byte[] {'{', '}'}, "", "", "", "", "");
        return;
      }
      if (inboundExhausted) { fatal("inbound_sequence_overflow"); return; }
      PostAuthFrameCodec.Context context = new PostAuthFrameCodec.Context(transcript, connectionId);
      PostAuthFrameCodec.Frame frame = PostAuthFrameCodec.decode(
          snapshot(raw), context, PostAuthFrameCodec.Direction.HUB_TO_PEER, keys);
      if (frame.sequence() != expectedInbound) { fatal("invalid_inbound_sequence"); return; }
      if (expectedInbound == Long.MAX_VALUE) inboundExhausted = true;
      else expectedInbound++;
      if (frame.messageKind().equals("HEARTBEAT")) {
        heartbeatDeadline = checkedDeadline(now, HEARTBEAT_TIMEOUT_MILLIS);
        return;
      }
      application.receive(frame);
      if (state != State.OPEN) return;
    } catch (Throwable error) {
      failFromExternal("invalid_inbound_frame", error);
    }
  }

  /** Admit one peer-to-Hub business frame behind the already-admitted open ACK. */
  public void sendBusiness(String messageKind, byte[] payload, String routeId,
      String sourceInstallId, String sourceConnectionId, String recipientConnectionId,
      String challengeExpiresAt) {
    requireOwner();
    beforeOperation();
    if (state != State.OPEN) { fatal("business_before_open"); return; }
    admit(messageKind, snapshot(payload), routeId, sourceInstallId, sourceConnectionId,
        recipientConnectionId, challengeExpiresAt);
  }

  /** Narrow Server-only candidate admission; no generic application authority is granted. */
  public void sendBodyBoundCandidate(String routeId, byte[] payload, String challengeExpiresAt) {
    requireOwner();
    beforeOperation();
    if (state != State.OPEN || transcript.peerRole() != ControlAuth.Role.SERVER_AUTHORITY
        || connectionId == null) {
      fatal("candidate_session_unavailable"); return;
    }
    admit("BODY_BOUND_CANDIDATE", snapshot(payload), routeId, "", "", connectionId,
        challengeExpiresAt);
  }

  /** Explicit owner-driven timer turn; no scheduler or background thread is created here. */
  public void tick() {
    requireOwner();
    long now = beforeOperation();
    if (state != State.OPEN) return;
    while (state == State.OPEN && now >= nextHeartbeat) {
      long scheduled = nextHeartbeat;
      nextHeartbeat = checkedDeadline(scheduled, HEARTBEAT_INTERVAL_MILLIS);
      admit("HEARTBEAT", new byte[] {'{', '}'}, "", "", "", "", "");
    }
  }

  public void close() {
    requireOwner();
    fatal("owner_close");
  }

  private long beforeOperation() {
    if (state == State.CLOSED) throw new IllegalStateException("session closed");
    long now = checkedNow();
    long expiry = state == State.WAIT_SESSION_OPEN ? openDeadline : heartbeatDeadline;
    if (now >= expiry) {
      fatal(state == State.WAIT_SESSION_OPEN ? "session_open_timeout" : "heartbeat_timeout");
      throw new IllegalStateException("session closed");
    }
    return now;
  }

  private void admit(String messageKind, byte[] payload, String routeId, String sourceInstallId,
      String sourceConnectionId, String recipientConnectionId, String challengeExpiresAt) {
    if (outboundExhausted) { fatal("outbound_sequence_overflow"); return; }
    long sequence = nextOutbound;
    try {
      PostAuthFrameCodec.Frame frame = new PostAuthFrameCodec.Frame(connectionId,
          transcript.challengeId(), sequence, messageKind, routeId, sourceInstallId,
          sourceConnectionId, recipientConnectionId, challengeExpiresAt, snapshot(payload), "");
      byte[] encoded = PostAuthFrameCodec.encode(frame,
          new PostAuthFrameCodec.Context(transcript, connectionId),
          PostAuthFrameCodec.Direction.PEER_TO_HUB, keys);
      if (outbound.size() >= OUTBOUND_CAPACITY) { fatal("outbound_queue_full"); return; }
      Entry entry = new Entry(generation, nextEntryIdentity++, sequence, encoded);
      outbound.addLast(entry);
      if (nextOutbound == Long.MAX_VALUE) outboundExhausted = true;
      else nextOutbound++;
      drain();
    } catch (RuntimeException error) {
      fatal("outbound_admission_failed");
    }
  }

  private void drain() {
    if (inFlight || outbound.isEmpty() || state == State.CLOSED) return;
    Entry entry = outbound.peekFirst();
    inFlight = true;
    CompletionStage<Void> stage;
    try {
      stage = Objects.requireNonNull(sender.send(entry.encoded()));
    } catch (Throwable error) {
      inFlight = false;
      failFromExternal("send_start_failed", error);
      return;
    }
    try {
      stage.whenComplete((ignored, failure) -> {
        Runnable event = () -> {
          requireOwner();
          complete(entry.generation(), entry.identity(), failure);
        };
        boolean posted;
        Throwable postFailure = null;
        try { posted = ownerPost.post(event); }
        catch (Throwable error) { posted = false; postFailure = error; }
        if (!posted) requestOwnerAbort(entry.generation(), "owner_post_rejected", postFailure);
      });
    } catch (Throwable error) {
      inFlight = false;
      failFromExternal("completion_registration_failed", error);
    }
  }

  private void complete(long capturedGeneration, long identity, Throwable failure) {
    requireOwner();
    if (state == State.CLOSED || capturedGeneration != generation) return;
    Entry current = outbound.peekFirst();
    if (!inFlight || current == null || current.identity() != identity) return;
    if (failure != null) { inFlight = false; fatal("send_completion_failed"); return; }
    outbound.removeFirst();
    inFlight = false;
    drain();
  }

  private void fatal(String reason) {
    if (state == State.CLOSED) return;
    state = State.CLOSED;
    outbound.clear();
    inFlight = false;
    Throwable first = null;
    try { lifecycle.fence(reason); }
    catch (Throwable error) { first = remember(first, error); reportCleanupUnknown(reason + ":fence"); }
    try { keys.close(); }
    catch (Throwable error) { first = remember(first, error); reportCleanupUnknown(reason + ":keys"); }
    try { lifecycle.physicalClose(reason); }
    catch (Throwable error) { first = remember(first, error); reportCleanupUnknown(reason + ":physical_close"); }
    rethrowFatal(first);
  }

  private void requestOwnerAbort(long capturedGeneration, String reason, Throwable originalFailure) {
    if (!abortRequested.compareAndSet(false, true)) return;
    Runnable abort = () -> {
      requireOwner();
      if (capturedGeneration != generation || state == State.CLOSED) return;
      fatal(reason);
    };
    boolean accepted;
    Throwable abortFailure = null;
    try { accepted = ownerAbort.request(abort); }
    catch (Throwable error) { accepted = false; abortFailure = error; }
    if (!accepted) reportCleanupUnknown(reason);
    if (originalFailure != null && abortFailure != null && originalFailure != abortFailure) {
      originalFailure.addSuppressed(abortFailure);
    }
    rethrowFatal(originalFailure);
    rethrowFatal(abortFailure);
  }

  private long checkedNow() {
    long now;
    try { now = clock.now(); }
    catch (Throwable error) {
      failFromExternal("monotonic_clock_failed", error);
      throw new IllegalStateException("session closed");
    }
    if (now < 0) { fatal("invalid_monotonic_clock"); throw new IllegalStateException("session closed"); }
    if (now < lastNow) { fatal("monotonic_clock_rollback"); throw new IllegalStateException("session closed"); }
    lastNow = now;
    return now;
  }

  private long checkedDeadline(long now, long delta) {
    try { return Math.addExact(now, delta); }
    catch (ArithmeticException error) {
      fatal("monotonic_deadline_overflow");
      throw new IllegalStateException("session closed");
    }
  }

  private void requireOwner() {
    boolean owner;
    try { owner = ownerThread.isOwnerThread(); }
    catch (RuntimeException error) { owner = false; }
    if (!owner) throw new IllegalStateException("wrong session owner thread");
  }

  private void failFromExternal(String reason, Throwable original) {
    Throwable cleanupFailure = null;
    try { fatal(reason); }
    catch (Throwable error) { cleanupFailure = error; }
    if (original != null && cleanupFailure != null && original != cleanupFailure) {
      original.addSuppressed(cleanupFailure);
    }
    rethrowFatal(original);
    rethrowFatal(cleanupFailure);
  }

  private void reportCleanupUnknown(String reason) {
    try { diagnostics.cleanupUnknown(reason); }
    catch (Throwable ignored) { /* Diagnostic failure cannot skip or retry cleanup. */ }
  }

  private static Throwable remember(Throwable first, Throwable next) {
    if (first == null) return next;
    if (first == next) return first;
    if (!(first instanceof Error) && next instanceof Error) {
      next.addSuppressed(first);
      return next;
    }
    first.addSuppressed(next);
    return first;
  }

  private static void rethrowFatal(Throwable error) {
    if (error instanceof Error fatal) throw fatal;
  }

  private static byte[] snapshot(byte[] value) {
    if (value == null) throw new IllegalArgumentException("bytes required");
    return value.clone();
  }
}
