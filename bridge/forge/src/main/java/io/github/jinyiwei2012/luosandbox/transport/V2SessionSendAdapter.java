package io.github.jinyiwei2012.luosandbox.transport;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.BooleanSupplier;

/** Unwired single-flight session send boundary; no socket currentness or cleanup guarantee. */
final class V2SessionSendAdapter {
  @FunctionalInterface interface PhysicalSender {
    /** Owns the supplied clone until observed settlement; must not mutate it before consumption. */
    CompletionStage<Void> send(byte[] ownedFrame);
  }
  @FunctionalInterface interface OwnerEnvelope {
    /** Owner operation plus finally-flush; must preserve the operation Error over flush failures. */
    void run(Runnable operation);
  }
  interface Containment {
    /** Arbitrary-thread bounded publication only, never owner cleanup. */
    void invalidate();
    void sendFailure();
  }

  private static final class SendFailure extends RuntimeException {
    SendFailure() { super("v2 send unavailable", null, false, false); }
  }
  private static final class Operation {
    byte[] frame;
    boolean settled;
    final CompletableFuture<Void> result = new CompletableFuture<>();
    Operation(byte[] frame) { this.frame = frame; }
  }

  private final Object lock = new Object();
  private final BooleanSupplier ownerGate;
  private final V2OwnerMailbox mailbox;
  private final PhysicalSender physical;
  private final OwnerEnvelope envelope;
  private final Containment containment;
  private final PostAuthPeerSession.AsyncSender senderPort = this::send;
  private final PostAuthPeerSession.OwnerPost postPort = this::post;
  private final PostAuthPeerSession.OwnerAbort abortPort = this::abort;
  private boolean invalid;
  private Operation active;

  V2SessionSendAdapter(BooleanSupplier ownerGate, V2OwnerMailbox mailbox, PhysicalSender physical,
      OwnerEnvelope envelope, Containment containment) {
    this.ownerGate = Objects.requireNonNull(ownerGate);
    this.mailbox = Objects.requireNonNull(mailbox);
    this.physical = Objects.requireNonNull(physical);
    this.envelope = Objects.requireNonNull(envelope);
    this.containment = Objects.requireNonNull(containment);
  }

  PostAuthPeerSession.AsyncSender sender() { return senderPort; }
  PostAuthPeerSession.OwnerPost ownerPost() { return postPort; }
  PostAuthPeerSession.OwnerAbort ownerAbort() { return abortPort; }

  /**
   * Owner-only snapshot from admission until observed physical settlement, including unknown outcomes.
   * A rejected owner check invalidates and returns true; false is not resource quiescence.
   */
  boolean hasUnsettledPhysicalSend() {
    if (!owner()) {
      invalidate(false);
      return true;
    }
    synchronized (lock) { return active != null; }
  }

  private boolean owner() {
    try { return ownerGate.getAsBoolean(); }
    catch (Throwable ignored) { return false; }
  }

  private boolean usable() {
    synchronized (lock) { if (invalid) return false; }
    return mailbox.isValid();
  }

  private boolean invalidateLocked() {
    if (invalid) return false;
    invalid = true;
    return true;
  }

  private void notifyContainment(boolean claimed, boolean sendFailure) {
    if (!claimed) return;
    try {
      if (sendFailure) containment.sendFailure(); else containment.invalidate();
    } catch (Throwable ignored) { /* Notification failure cannot escape a physical callback. */ }
  }

  private void invalidate(boolean sendFailure) {
    boolean claimed;
    synchronized (lock) { claimed = invalidateLocked(); }
    notifyContainment(claimed, sendFailure);
  }

  private static CompletionStage<Void> failed() {
    return CompletableFuture.failedFuture(new SendFailure());
  }

  private CompletionStage<Void> send(byte[] frame) {
    if (!owner() || !usable() || frame == null || frame.length == 0 || frame.length > 4096) {
      invalidate(false);
      return failed();
    }
    Operation operation = null;
    boolean claimed = false;
    synchronized (lock) {
      if (invalid || active != null) claimed = invalidateLocked();
      else {
        operation = new Operation(frame.clone());
        active = operation;
      }
    }
    if (operation == null) {
      notifyContainment(claimed, false);
      return failed();
    }
    Operation captured = operation;
    try {
      CompletionStage<Void> stage = physical.send(operation.frame);
      if (stage == null) throw new IllegalStateException("missing physical completion");
      stage.whenComplete((ignored, failure) -> settle(captured, failure != null));
    } catch (Throwable ignored) {
      // A throw does not prove physical consumption ended. Keep active/frame until a real callback.
      invalidate(true);
      operation.result.completeExceptionally(new SendFailure());
    }
    return operation.result;
  }

  private void settle(Operation operation, boolean failure) {
    boolean claimed = false;
    boolean success;
    synchronized (lock) {
      if (operation.settled) return;
      operation.settled = true;
      operation.frame = null;
      if (active == operation) active = null;
      if (failure) claimed = invalidateLocked();
      success = !failure && !invalid;
    }
    notifyContainment(claimed, true);
    if (success) operation.result.complete(null);
    else operation.result.completeExceptionally(new SendFailure());
  }

  private boolean post(Runnable action) {
    if (action == null || !usable()) { invalidate(false); return false; }
    Runnable wrapper = () -> {
      if (!owner()) {
        invalidate(false);
        mailbox.requestAbort(V2OwnerMailbox.Reason.EXTERNAL_ABORT);
        return;
      }
      envelope.run(action); // Owner Error belongs to the mailbox owner policy, not this adapter.
    };
    boolean accepted = mailbox.postSendCompletion(wrapper);
    if (!accepted || !mailbox.isValid()) { invalidate(false); return false; }
    return true;
  }

  private boolean abort(Runnable ignored) {
    // Invalid adapter state must not hide the mailbox's one explicit terminal-handoff opportunity.
    // False can still mean a recovery handoff was scheduled without recording a new first reason.
    boolean recorded = mailbox.requestAbort(V2OwnerMailbox.Reason.EXTERNAL_ABORT);
    if (!mailbox.isValid()) invalidate(false);
    return recorded;
  }
}
