package io.github.jinyiwei2012.luosandbox.contract;

import static io.github.jinyiwei2012.luosandbox.contract.SemanticTypes.requireDifferent;
import static io.github.jinyiwei2012.luosandbox.contract.SemanticTypes.requireText;

import java.util.Objects;
import java.util.UUID;

/** An authoritative terminal outcome; receiving a request is deliberately not an outcome. */
public record ActionReceipt(
    ContractHeader header, UUID requestMessageId, TerminalStatus status, StableError error) {
  public enum TerminalStatus {
    SUCCEEDED,
    FAILED,
    TIMED_OUT,
    CANCELLED,
    DISCONNECTED,
    UNKNOWN
  }

  public record StableError(String code, String detail) {
    public StableError {
      requireText(code, "code");
      requireText(detail, "detail");
    }
  }

  public ActionReceipt {
    Objects.requireNonNull(header, "header");
    Objects.requireNonNull(requestMessageId, "requestMessageId");
    Objects.requireNonNull(status, "status");
    requireDifferent(header.messageId(), requestMessageId, "requestMessageId");
    if (status == TerminalStatus.SUCCEEDED && error != null) {
      throw new IllegalArgumentException("A succeeded receipt must not include an error");
    }
  }
}
