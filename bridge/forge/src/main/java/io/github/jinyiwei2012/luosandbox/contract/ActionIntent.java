package io.github.jinyiwei2012.luosandbox.contract;

import static io.github.jinyiwei2012.luosandbox.contract.SemanticTypes.requireDifferent;
import static io.github.jinyiwei2012.luosandbox.contract.SemanticTypes.requireText;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import io.github.jinyiwei2012.luosandbox.contract.SemanticTypes.Position;
import io.github.jinyiwei2012.luosandbox.contract.SemanticTypes.QueryKind;

/** Allowlisted typed intents. No subtype accepts commands, NBT, Java, or arbitrary payload maps. */
public sealed interface ActionIntent
    permits ActionIntent.Query, ActionIntent.PublicSay, ActionIntent.Look, ActionIntent.BoundedWalk, ActionIntent.Stop {
  record Query(QueryKind kind) implements ActionIntent {
    public Query {
      Objects.requireNonNull(kind, "kind");
    }
  }

  record PublicSay(String message) implements ActionIntent {
    public PublicSay {
      requireText(message, "message");
    }
  }

  record Look(Position target) implements ActionIntent {
    public Look {
      Objects.requireNonNull(target, "target");
    }
  }

  record BoundedWalk(Position target, double maximumDistance) implements ActionIntent {
    public BoundedWalk {
      Objects.requireNonNull(target, "target");
      if (!Double.isFinite(maximumDistance) || maximumDistance <= 0) {
        throw new IllegalArgumentException("maximumDistance must be finite and positive");
      }
    }
  }

  record Stop(UUID targetRequestMessageId) implements ActionIntent {
    public Stop {
      Objects.requireNonNull(targetRequestMessageId, "targetRequestMessageId");
    }
  }

  /** A deadline is an absolute UTC instant, not a scheduler or execution guarantee. */
  record Request(ContractHeader header, Instant deadline, ActionIntent intent) {
    public Request {
      Objects.requireNonNull(header, "header");
      Objects.requireNonNull(deadline, "deadline");
      Objects.requireNonNull(intent, "intent");
      if (!deadline.isAfter(header.timestamp())) {
        throw new IllegalArgumentException("deadline must be after the request timestamp");
      }
      if (intent instanceof Stop stop) {
        requireDifferent(header.messageId(), stop.targetRequestMessageId(), "targetRequestMessageId");
      }
    }
  }
}
