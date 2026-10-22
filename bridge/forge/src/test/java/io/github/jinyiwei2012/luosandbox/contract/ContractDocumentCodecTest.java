package io.github.jinyiwei2012.luosandbox.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.jinyiwei2012.luosandbox.contract.ActionIntent.BoundedWalk;
import io.github.jinyiwei2012.luosandbox.contract.ActionIntent.Look;
import io.github.jinyiwei2012.luosandbox.contract.ActionIntent.PublicSay;
import io.github.jinyiwei2012.luosandbox.contract.ActionIntent.Query;
import io.github.jinyiwei2012.luosandbox.contract.ActionIntent.Stop;
import io.github.jinyiwei2012.luosandbox.contract.ActionReceipt.StableError;
import io.github.jinyiwei2012.luosandbox.contract.ActionReceipt.TerminalStatus;
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
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

class ContractDocumentCodecTest {
  private static final Instant TIME = Instant.parse("2026-10-22T12:00:00Z");

  @org.junit.jupiter.api.Test
  void roundTripsObservationAllIntentTypesAndTerminalReceipts() {
    WorldObservation observation = new WorldObservation(header(), ObservationSource.SERVER_AUTHORITATIVE,
        Freshness.UNKNOWN, Audience.CONTROL_SESSION, Provenance.SERVER_AUTHORITY, capabilities(),
        new ReadOnlyWorldSummary("minecraft:overworld", new Position(1, 64, -2)), List.of(new UntrustedText("hello")));
    assertEquals(observation, ContractDocumentCodec.decodeObservation(ContractDocumentCodec.encodeObservation(observation)));

    for (ActionIntent intent : List.of(new Query(QueryKind.READ_ONLY_SUMMARY), new PublicSay("hello"),
        new Look(new Position(1, 2, 3)), new BoundedWalk(new Position(4, 5, 6), 7), new Stop(UUID.randomUUID()))) {
      ActionIntent.Request request = new ActionIntent.Request(header(), TIME.plusSeconds(10), intent);
      assertEquals(request, ContractDocumentCodec.decodeRequest(ContractDocumentCodec.encodeRequest(request)));
    }
    for (TerminalStatus status : TerminalStatus.values()) {
      ActionReceipt receipt = new ActionReceipt(header(), UUID.randomUUID(), status,
          status == TerminalStatus.SUCCEEDED ? null : new StableError("TEST", "test detail"));
      assertEquals(receipt, ContractDocumentCodec.decodeReceipt(ContractDocumentCodec.encodeReceipt(receipt)));
    }
  }

  @org.junit.jupiter.api.Test
  void strictDocumentsRejectMissingUnknownVersionFractionalEpochAndInvalidDeadline() {
    Map<String, Object> document = requestDocument();
    Map<String, Object> missing = new java.util.LinkedHashMap<>(document);
    missing.remove("actorId");
    assertThrows(IllegalArgumentException.class, () -> ContractDocumentCodec.decodeRequest(missing));
    Map<String, Object> version = new java.util.LinkedHashMap<>(document);
    version.put("version", "V99");
    assertThrows(IllegalArgumentException.class, () -> ContractDocumentCodec.decodeRequest(version));
    Map<String, Object> fractionalEpoch = new java.util.LinkedHashMap<>(document);
    fractionalEpoch.put("sessionEpoch", 1.5d);
    assertThrows(IllegalArgumentException.class, () -> ContractDocumentCodec.decodeRequest(fractionalEpoch));
    Map<String, Object> badDeadline = new java.util.LinkedHashMap<>(document);
    badDeadline.put("deadline", TIME.toString());
    assertThrows(IllegalArgumentException.class, () -> ContractDocumentCodec.decodeRequest(badDeadline));
  }

  @org.junit.jupiter.api.Test
  void strictDocumentsRejectUnknownFieldsNullRootAndNoncanonicalUuid() {
    assertThrows(IllegalArgumentException.class, () -> ContractDocumentCodec.decodeRequest(null));
    Map<String, Object> document = requestDocument();
    Map<String, Object> unknown = new java.util.LinkedHashMap<>(document);
    unknown.put("unrecognized", "value");
    assertThrows(IllegalArgumentException.class, () -> ContractDocumentCodec.decodeRequest(unknown));
    Map<String, Object> shortUuid = new java.util.LinkedHashMap<>(document);
    shortUuid.put("messageId", "1-1-1-1-1");
    assertThrows(IllegalArgumentException.class, () -> ContractDocumentCodec.decodeRequest(shortUuid));
    Map<String, Object> receipt = new java.util.LinkedHashMap<>(ContractDocumentCodec.encodeReceipt(
        new ActionReceipt(header(), UUID.randomUUID(), TerminalStatus.FAILED, null)));
    receipt.put("error", null);
    assertThrows(IllegalArgumentException.class, () -> ContractDocumentCodec.decodeReceipt(receipt));
  }

  @org.junit.jupiter.api.Test
  void validatesIdentityCorrelationAndInputDomain() {
    assertThrows(IllegalArgumentException.class, () -> new ContractHeader(ContractVersion.V1, "actor", "world", "session", -1, UUID.randomUUID(), TIME));
    assertThrows(IllegalArgumentException.class, () -> new Position(Double.NaN, 0, 0));
    assertThrows(IllegalArgumentException.class, () -> new BoundedWalk(new Position(0, 0, 0), 0));
    ContractHeader header = header();
    assertThrows(IllegalArgumentException.class, () -> new ActionReceipt(header, header.messageId(), TerminalStatus.FAILED, null));
    assertThrows(IllegalArgumentException.class, () -> new ActionIntent.Request(header, TIME.plusSeconds(1), new Stop(header.messageId())));
    assertThrows(IllegalArgumentException.class, () -> new ActionReceipt(header, UUID.randomUUID(), TerminalStatus.SUCCEEDED, new StableError("X", "x")));
  }

  @org.junit.jupiter.api.Test
  void unknownCapabilityRemainsUnknownAndDoesNotClaimAvailability() {
    EnumMap<Capability, CapabilityAvailability> states = stateMap();
    states.put(Capability.BOUNDED_WALK, CapabilityAvailability.UNKNOWN);
    CapabilitySnapshot snapshot = new CapabilitySnapshot(states);
    assertEquals(CapabilityAvailability.UNKNOWN, snapshot.states().get(Capability.BOUNDED_WALK));
    assertTrue(snapshot.states().values().stream().noneMatch(value -> value == CapabilityAvailability.AVAILABLE));
    states.put(Capability.QUERY, CapabilityAvailability.AVAILABLE);
    assertEquals(CapabilityAvailability.UNKNOWN, snapshot.states().get(Capability.BOUNDED_WALK));
  }

  @org.junit.jupiter.api.Test
  void rejectsUnknownCapabilityAndMismatchedSourceProvenance() {
    Map<String, Object> document = ContractDocumentCodec.encodeObservation(new WorldObservation(
        header(), ObservationSource.SERVER_AUTHORITATIVE, Freshness.CURRENT, Audience.PUBLIC,
        Provenance.SERVER_AUTHORITY, capabilities(),
        new ReadOnlyWorldSummary("minecraft:overworld", new Position(0, 64, 0)), List.of()));
    Map<String, Object> malformed = new java.util.LinkedHashMap<>(document);
    Map<?, ?> rawCapabilities = (Map<?, ?>) malformed.get("capabilities");
    Map<String, Object> capabilityMap = new java.util.LinkedHashMap<>();
    rawCapabilities.forEach((key, value) -> capabilityMap.put((String) key, value));
    capabilityMap.put("ARBITRARY_EXECUTION", "AVAILABLE");
    malformed.put("capabilities", capabilityMap);
    assertThrows(IllegalArgumentException.class, () -> ContractDocumentCodec.decodeObservation(malformed));
    assertThrows(IllegalArgumentException.class, () -> new WorldObservation(
        header(), ObservationSource.CLIENT_LOCAL, Freshness.CURRENT, Audience.PUBLIC,
        Provenance.SERVER_AUTHORITY, capabilities(),
        new ReadOnlyWorldSummary("minecraft:overworld", new Position(0, 64, 0)), List.of()));
  }

  private static Map<String, Object> requestDocument() {
    return ContractDocumentCodec.encodeRequest(
        new ActionIntent.Request(header(), TIME.plusSeconds(1), new PublicSay("hi")));
  }

  private static ContractHeader header() {
    return new ContractHeader(
        ContractVersion.V1, "agent-lu", "world-a", "session-a", 4, UUID.randomUUID(), TIME);
  }

  private static CapabilitySnapshot capabilities() {
    return new CapabilitySnapshot(stateMap());
  }

  private static EnumMap<Capability, CapabilityAvailability> stateMap() {
    EnumMap<Capability, CapabilityAvailability> states = new EnumMap<>(Capability.class);
    for (Capability capability : Capability.values()) {
      states.put(capability, CapabilityAvailability.UNKNOWN);
    }
    return states;
  }
}
