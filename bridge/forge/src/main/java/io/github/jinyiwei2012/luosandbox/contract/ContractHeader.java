package io.github.jinyiwei2012.luosandbox.contract;

import static io.github.jinyiwei2012.luosandbox.contract.SemanticTypes.requireText;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import io.github.jinyiwei2012.luosandbox.contract.SemanticTypes.ContractVersion;

/** Correlation and time metadata for one semantic document. Times are UTC {@link Instant}s. */
public record ContractHeader(
    ContractVersion version,
    String actorId,
    String worldId,
    String sessionId,
    long sessionEpoch,
    UUID messageId,
    Instant timestamp) {
  public ContractHeader {
    Objects.requireNonNull(version, "version");
    requireText(actorId, "actorId");
    requireText(worldId, "worldId");
    requireText(sessionId, "sessionId");
    if (sessionEpoch < 0) {
      throw new IllegalArgumentException("sessionEpoch must not be negative");
    }
    Objects.requireNonNull(messageId, "messageId");
    Objects.requireNonNull(timestamp, "timestamp");
  }
}
