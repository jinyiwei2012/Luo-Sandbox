package io.github.jinyiwei2012.luosandbox.security.forge;

import io.github.jinyiwei2012.luosandbox.security.BodyProofMessageCodec;
import io.github.jinyiwei2012.luosandbox.security.PendingBodyProof;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/** Synchronous bounded submission port only: SENT is not remote receipt, proof or authority. */
public final class BodyProofChallengeIssuer<S, C> {
  @FunctionalInterface public interface ChallengeSender<C> {
    void send(C exactConnection, byte[] strictFrame) throws Exception;
  }
  @FunctionalInterface public interface ConnectionCloser<C> { void close(C exactConnection); }
  public enum Status { SENT, REJECTED, SEND_UNKNOWN }
  public record Result(Status status, Optional<PendingBodyProof.BodyChallenge> challenge) {
    public Result {
      Objects.requireNonNull(status); Objects.requireNonNull(challenge);
      if ((status == Status.SENT) != challenge.isPresent()) {
        throw new IllegalArgumentException("inconsistent challenge send result");
      }
    }
    @Override public String toString() { return "BodyChallengeSend[" + status + "]"; }
  }

  private final BodyProofServerLifecycle<S, C> lifecycle;
  private final ChallengeSender<C> sender;
  private final ConnectionCloser<C> closer;

  public BodyProofChallengeIssuer(BodyProofServerLifecycle<S, C> lifecycle,
      ChallengeSender<C> sender, ConnectionCloser<C> closer) {
    this.lifecycle = Objects.requireNonNull(lifecycle);
    this.sender = Objects.requireNonNull(sender);
    this.closer = Objects.requireNonNull(closer);
  }

  /** No lifecycle/global lock is held while invoking the external bounded sender port. */
  public Result issueAndSend(S server, BodyProofServerLifecycle.OwnerHandle<S, C> owner,
      Supplier<C> exactConnection, String bootId) {
    var issued = lifecycle.beginChallenge(server, owner, exactConnection, bootId);
    if (issued.isEmpty()) return new Result(Status.REJECTED, Optional.empty());
    var token = issued.get();
    byte[] frame;
    try {
      frame = BodyProofMessageCodec.encodeChallenge(token.challenge());
    } catch (RuntimeException error) {
      lifecycle.discardIssued(server, owner, token);
      return new Result(Status.REJECTED, Optional.empty());
    }
    try {
      sender.send(token.exactConnection(), frame);
      return new Result(Status.SENT, Optional.of(token.challenge()));
    } catch (Exception error) {
      if (error instanceof InterruptedException) Thread.currentThread().interrupt();
      lifecycle.discardIssued(server, owner, token);
      try { closer.close(token.exactConnection()); }
      catch (RuntimeException ignored) { /* Local failure cannot restore proof state or imply delivery */ }
      return new Result(Status.SEND_UNKNOWN, Optional.empty());
    }
  }
}
