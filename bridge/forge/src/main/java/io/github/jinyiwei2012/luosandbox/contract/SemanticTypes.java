package io.github.jinyiwei2012.luosandbox.contract;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Transport-neutral value types shared by the semantic contract. */
public final class SemanticTypes {
  private SemanticTypes() {}

  public enum ContractVersion {
    V1
  }

  public enum ObservationSource {
    SERVER_AUTHORITATIVE,
    CLIENT_LOCAL
  }

  public enum Freshness {
    CURRENT,
    STALE,
    UNKNOWN
  }

  public enum Audience {
    PUBLIC,
    CONTROL_SESSION
  }

  public enum Provenance {
    SERVER_AUTHORITY,
    CLIENT_RUNTIME
  }

  public enum Capability {
    QUERY,
    PUBLIC_SAY,
    LOOK,
    BOUNDED_WALK,
    STOP
  }

  public enum CapabilityAvailability {
    AVAILABLE,
    UNAVAILABLE,
    UNKNOWN
  }

  public enum QueryKind {
    READ_ONLY_SUMMARY
  }

  /** Text is data only; it is never an executable command or authorization input. */
  public record UntrustedText(String value) {
    public UntrustedText {
      requireText(value, "value");
    }
  }

  /** A finite game-space coordinate, deliberately without a generic fact map. */
  public record Position(double x, double y, double z) {
    public Position {
      requireFinite(x, "x");
      requireFinite(y, "y");
      requireFinite(z, "z");
    }
  }

  /** Minimal initial read-only world state; fuller gameplay facts need separate approval. */
  public record ReadOnlyWorldSummary(String dimensionId, Position position) {
    public ReadOnlyWorldSummary {
      requireText(dimensionId, "dimensionId");
      Objects.requireNonNull(position, "position");
    }
  }

  /** Every known capability has an explicit state; omitted entries cannot imply permission. */
  public record CapabilitySnapshot(Map<Capability, CapabilityAvailability> states) {
    public CapabilitySnapshot {
      Objects.requireNonNull(states, "states");
      states = Map.copyOf(states);
      for (Capability capability : Capability.values()) {
        if (states.get(capability) == null) {
          throw new IllegalArgumentException("Missing explicit capability state: " + capability);
        }
      }
      if (states.size() != Capability.values().length) {
        throw new IllegalArgumentException("Capability snapshot contains an unsupported capability");
      }
    }
  }

  public static void requireText(String value, String fieldName) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(fieldName + " must not be blank");
    }
  }

  public static void requireFinite(double value, String fieldName) {
    if (!Double.isFinite(value)) {
      throw new IllegalArgumentException(fieldName + " must be finite");
    }
  }

  public static void requireDifferent(UUID first, UUID second, String fieldName) {
    if (Objects.equals(first, second)) {
      throw new IllegalArgumentException(fieldName + " must not equal the enclosing message ID");
    }
  }
}
