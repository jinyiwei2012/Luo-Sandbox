package io.github.jinyiwei2012.luosandbox.contract;

import io.github.jinyiwei2012.luosandbox.contract.ActionIntent.BoundedWalk;
import io.github.jinyiwei2012.luosandbox.contract.ActionIntent.Look;
import io.github.jinyiwei2012.luosandbox.contract.ActionIntent.PublicSay;
import io.github.jinyiwei2012.luosandbox.contract.ActionIntent.Query;
import io.github.jinyiwei2012.luosandbox.contract.ActionIntent.Stop;
import io.github.jinyiwei2012.luosandbox.contract.ActionReceipt.StableError;
import io.github.jinyiwei2012.luosandbox.contract.SemanticTypes.Audience;
import io.github.jinyiwei2012.luosandbox.contract.SemanticTypes.Capability;
import io.github.jinyiwei2012.luosandbox.contract.SemanticTypes.CapabilityAvailability;
import io.github.jinyiwei2012.luosandbox.contract.SemanticTypes.CapabilitySnapshot;
import io.github.jinyiwei2012.luosandbox.contract.SemanticTypes.ContractVersion;
import io.github.jinyiwei2012.luosandbox.contract.SemanticTypes.Freshness;
import io.github.jinyiwei2012.luosandbox.contract.SemanticTypes.ObservationSource;
import io.github.jinyiwei2012.luosandbox.contract.SemanticTypes.Position;
import io.github.jinyiwei2012.luosandbox.contract.SemanticTypes.Provenance;
import io.github.jinyiwei2012.luosandbox.contract.SemanticTypes.QueryKind;
import io.github.jinyiwei2012.luosandbox.contract.SemanticTypes.ReadOnlyWorldSummary;
import io.github.jinyiwei2012.luosandbox.contract.SemanticTypes.UntrustedText;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Strict Map document fixture codec, not a network protocol or runtime transport. */
public final class ContractDocumentCodec {
  private ContractDocumentCodec() {}

  public static Map<String, Object> encodeObservation(WorldObservation observation) {
    Map<String, Object> document = encodeHeader(observation.header());
    document.put("source", observation.source().name());
    document.put("freshness", observation.freshness().name());
    document.put("audience", observation.audience().name());
    document.put("provenance", observation.provenance().name());

    Map<String, Object> capabilities = new LinkedHashMap<>();
    observation.capabilities().states().forEach((capability, availability) ->
        capabilities.put(capability.name(), availability.name()));
    document.put("capabilities", Map.copyOf(capabilities));
    document.put("summary", Map.of(
        "dimensionId", observation.summary().dimensionId(),
        "position", encodePosition(observation.summary().position())));
    document.put("untrustedText", observation.untrustedText().stream().map(UntrustedText::value).toList());
    return Map.copyOf(document);
  }

  public static WorldObservation decodeObservation(Map<String, Object> document) {
    requireKeys(document, "observation", OBSERVATION_KEYS);
    Map<String, Object> summary = map(document.get("summary"), "summary");
    requireKeys(summary, "summary", "dimensionId", "position");
    Map<String, Object> capabilityDocument = map(document.get("capabilities"), "capabilities");
    Map<Capability, CapabilityAvailability> capabilities = decodeCapabilities(capabilityDocument);
    List<UntrustedText> untrustedText = new ArrayList<>();
    for (Object item : list(document.get("untrustedText"), "untrustedText")) {
      untrustedText.add(new UntrustedText(string(item, "untrustedText item")));
    }
    return new WorldObservation(
        header(document),
        enumValue(ObservationSource.class, document.get("source"), "source"),
        enumValue(Freshness.class, document.get("freshness"), "freshness"),
        enumValue(Audience.class, document.get("audience"), "audience"),
        enumValue(Provenance.class, document.get("provenance"), "provenance"),
        new CapabilitySnapshot(capabilities),
        new ReadOnlyWorldSummary(
            string(summary.get("dimensionId"), "summary.dimensionId"),
            position(summary.get("position"), "summary.position")),
        untrustedText);
  }

  public static Map<String, Object> encodeRequest(ActionIntent.Request request) {
    Map<String, Object> document = encodeHeader(request.header());
    document.put("deadline", request.deadline().toString());
    document.put("intent", encodeIntent(request.intent()));
    return Map.copyOf(document);
  }

  public static ActionIntent.Request decodeRequest(Map<String, Object> document) {
    requireKeys(document, "request", REQUEST_KEYS);
    return new ActionIntent.Request(
        header(document),
        instant(document.get("deadline"), "deadline"),
        decodeIntent(map(document.get("intent"), "intent")));
  }

  public static Map<String, Object> encodeReceipt(ActionReceipt receipt) {
    Map<String, Object> document = encodeHeader(receipt.header());
    document.put("requestMessageId", receipt.requestMessageId().toString());
    document.put("status", receipt.status().name());
    if (receipt.error() != null) {
      document.put("error", Map.of("code", receipt.error().code(), "detail", receipt.error().detail()));
    }
    return Map.copyOf(document);
  }

  public static ActionReceipt decodeReceipt(Map<String, Object> document) {
    requireReceiptKeys(document);
    StableError error = null;
    if (document.containsKey("error")) {
      Map<String, Object> errorDocument = map(document.get("error"), "error");
      requireKeys(errorDocument, "error", "code", "detail");
      error = new StableError(
          string(errorDocument.get("code"), "error.code"),
          string(errorDocument.get("detail"), "error.detail"));
    }
    return new ActionReceipt(
        header(document),
        uuid(document.get("requestMessageId"), "requestMessageId"),
        enumValue(ActionReceipt.TerminalStatus.class, document.get("status"), "status"),
        error);
  }

  private static final String[] HEADER_KEYS = {
      "version", "actorId", "worldId", "sessionId", "sessionEpoch", "messageId", "timestamp"
  };
  private static final String[] OBSERVATION_KEYS = {
      "version", "actorId", "worldId", "sessionId", "sessionEpoch", "messageId", "timestamp",
      "source", "freshness", "audience", "provenance", "capabilities", "summary", "untrustedText"
  };
  private static final String[] REQUEST_KEYS = {
      "version", "actorId", "worldId", "sessionId", "sessionEpoch", "messageId", "timestamp",
      "deadline", "intent"
  };

  private static Map<String, Object> encodeHeader(ContractHeader header) {
    Map<String, Object> document = new LinkedHashMap<>();
    document.put("version", header.version().name());
    document.put("actorId", header.actorId());
    document.put("worldId", header.worldId());
    document.put("sessionId", header.sessionId());
    document.put("sessionEpoch", header.sessionEpoch());
    document.put("messageId", header.messageId().toString());
    document.put("timestamp", header.timestamp().toString());
    return document;
  }

  private static ContractHeader header(Map<String, Object> document) {
    return new ContractHeader(
        enumValue(ContractVersion.class, document.get("version"), "version"),
        string(document.get("actorId"), "actorId"),
        string(document.get("worldId"), "worldId"),
        string(document.get("sessionId"), "sessionId"),
        wholeLong(document.get("sessionEpoch"), "sessionEpoch"),
        uuid(document.get("messageId"), "messageId"),
        instant(document.get("timestamp"), "timestamp"));
  }

  private static Map<Capability, CapabilityAvailability> decodeCapabilities(
      Map<String, Object> document) {
    Map<Capability, CapabilityAvailability> states = new EnumMap<>(Capability.class);
    for (Capability capability : Capability.values()) {
      states.put(capability, enumValue(
          CapabilityAvailability.class,
          document.get(capability.name()),
          "capabilities." + capability.name()));
    }
    if (document.size() != states.size()) {
      throw invalid("capabilities contains an unknown key");
    }
    return states;
  }

  private static Map<String, Object> encodeIntent(ActionIntent intent) {
    Map<String, Object> document = new LinkedHashMap<>();
    if (intent instanceof Query query) {
      document.put("kind", "QUERY");
      document.put("queryKind", query.kind().name());
    } else if (intent instanceof PublicSay publicSay) {
      document.put("kind", "PUBLIC_SAY");
      document.put("message", publicSay.message());
    } else if (intent instanceof Look look) {
      document.put("kind", "LOOK");
      document.put("target", encodePosition(look.target()));
    } else if (intent instanceof BoundedWalk boundedWalk) {
      document.put("kind", "BOUNDED_WALK");
      document.put("target", encodePosition(boundedWalk.target()));
      document.put("maximumDistance", boundedWalk.maximumDistance());
    } else if (intent instanceof Stop stop) {
      document.put("kind", "STOP");
      document.put("targetRequestMessageId", stop.targetRequestMessageId().toString());
    } else {
      throw invalid("unsupported intent");
    }
    return document;
  }

  private static ActionIntent decodeIntent(Map<String, Object> document) {
    String kind = string(document.get("kind"), "intent.kind");
    return switch (kind) {
      case "QUERY" -> {
        requireKeys(document, "intent", "kind", "queryKind");
        yield new Query(enumValue(QueryKind.class, document.get("queryKind"), "intent.queryKind"));
      }
      case "PUBLIC_SAY" -> {
        requireKeys(document, "intent", "kind", "message");
        yield new PublicSay(string(document.get("message"), "intent.message"));
      }
      case "LOOK" -> {
        requireKeys(document, "intent", "kind", "target");
        yield new Look(position(document.get("target"), "intent.target"));
      }
      case "BOUNDED_WALK" -> {
        requireKeys(document, "intent", "kind", "target", "maximumDistance");
        yield new BoundedWalk(
            position(document.get("target"), "intent.target"),
            finite(document.get("maximumDistance"), "intent.maximumDistance"));
      }
      case "STOP" -> {
        requireKeys(document, "intent", "kind", "targetRequestMessageId");
        yield new Stop(uuid(document.get("targetRequestMessageId"), "intent.targetRequestMessageId"));
      }
      default -> throw invalid("intent.kind has an unknown value");
    };
  }

  private static Map<String, Object> encodePosition(Position position) {
    return Map.of("x", position.x(), "y", position.y(), "z", position.z());
  }

  private static Position position(Object value, String field) {
    Map<String, Object> document = map(value, field);
    requireKeys(document, field, "x", "y", "z");
    return new Position(
        finite(document.get("x"), field + ".x"),
        finite(document.get("y"), field + ".y"),
        finite(document.get("z"), field + ".z"));
  }

  private static void requireKeys(Map<String, Object> document, String name, String... keys) {
    if (document == null) {
      throw invalid(name + " must be a map");
    }
    if (document.size() != keys.length) {
      throw invalid(name + " has missing or unknown fields");
    }
    for (String key : keys) {
      if (!document.containsKey(key)) {
        throw invalid(name + "." + key + " is required");
      }
    }
  }

  private static void requireReceiptKeys(Map<String, Object> document) {
    if (document == null) {
      throw invalid("receipt must be a map");
    }
    for (String key : List.of(HEADER_KEYS)) {
      if (!document.containsKey(key)) {
        throw invalid("receipt." + key + " is required");
      }
    }
    if (!document.containsKey("requestMessageId") || !document.containsKey("status")) {
      throw invalid("receipt requires requestMessageId and status");
    }
    if (document.size() != 9 && (document.size() != 10 || !document.containsKey("error"))) {
      throw invalid("receipt has missing or unknown fields");
    }
  }

  private static Map<String, Object> map(Object value, String field) {
    if (!(value instanceof Map<?, ?> raw)) {
      throw invalid(field + " must be a map");
    }
    Map<String, Object> result = new LinkedHashMap<>();
    for (Map.Entry<?, ?> entry : raw.entrySet()) {
      if (!(entry.getKey() instanceof String key)) {
        throw invalid(field + " has a non-string key");
      }
      result.put(key, entry.getValue());
    }
    return result;
  }

  private static List<?> list(Object value, String field) {
    if (!(value instanceof List<?> result)) {
      throw invalid(field + " must be a list");
    }
    return result;
  }

  private static String string(Object value, String field) {
    if (!(value instanceof String result) || result.isBlank()) {
      throw invalid(field + " must be a non-blank string");
    }
    return result;
  }

  private static UUID uuid(Object value, String field) {
    String text = string(value, field);
    try {
      UUID parsed = UUID.fromString(text);
      if (!parsed.toString().equalsIgnoreCase(text)) {
        throw invalid(field + " must be a canonical UUID");
      }
      return parsed;
    } catch (IllegalArgumentException exception) {
      throw invalid(field + " must be a canonical UUID");
    }
  }

  private static Instant instant(Object value, String field) {
    try {
      return Instant.parse(string(value, field));
    } catch (DateTimeParseException exception) {
      throw invalid(field + " must be an Instant string");
    }
  }

  private static long wholeLong(Object value, String field) {
    if (!(value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long)) {
      throw invalid(field + " must be an integer without fractional loss");
    }
    return ((Number) value).longValue();
  }

  private static double finite(Object value, String field) {
    if (!(value instanceof Number number) || !Double.isFinite(number.doubleValue())) {
      throw invalid(field + " must be finite number");
    }
    return number.doubleValue();
  }

  private static <T extends Enum<T>> T enumValue(Class<T> type, Object value, String field) {
    try {
      return Enum.valueOf(type, string(value, field));
    } catch (IllegalArgumentException exception) {
      throw invalid(field + " has an unknown value");
    }
  }

  private static IllegalArgumentException invalid(String message) {
    return new IllegalArgumentException(message);
  }
}
