package io.github.jinyiwei2012.luosandbox.contract;

import java.util.List;
import java.util.Objects;
import io.github.jinyiwei2012.luosandbox.contract.SemanticTypes.Audience;
import io.github.jinyiwei2012.luosandbox.contract.SemanticTypes.CapabilitySnapshot;
import io.github.jinyiwei2012.luosandbox.contract.SemanticTypes.Freshness;
import io.github.jinyiwei2012.luosandbox.contract.SemanticTypes.ObservationSource;
import io.github.jinyiwei2012.luosandbox.contract.SemanticTypes.Provenance;
import io.github.jinyiwei2012.luosandbox.contract.SemanticTypes.ReadOnlyWorldSummary;
import io.github.jinyiwei2012.luosandbox.contract.SemanticTypes.UntrustedText;

/** A source-labelled observation. Text is intentionally separate from trusted summary fields. */
public record WorldObservation(
    ContractHeader header,
    ObservationSource source,
    Freshness freshness,
    Audience audience,
    Provenance provenance,
    CapabilitySnapshot capabilities,
    ReadOnlyWorldSummary summary,
    List<UntrustedText> untrustedText) {
  public WorldObservation {
    Objects.requireNonNull(header, "header");
    Objects.requireNonNull(source, "source");
    Objects.requireNonNull(freshness, "freshness");
    Objects.requireNonNull(audience, "audience");
    Objects.requireNonNull(provenance, "provenance");
    Objects.requireNonNull(capabilities, "capabilities");
    Objects.requireNonNull(summary, "summary");
    untrustedText = List.copyOf(Objects.requireNonNull(untrustedText, "untrustedText"));
    if (source == ObservationSource.SERVER_AUTHORITATIVE
        && provenance != Provenance.SERVER_AUTHORITY) {
      throw new IllegalArgumentException("Server-authoritative observations require server provenance");
    }
    if (source == ObservationSource.CLIENT_LOCAL && provenance != Provenance.CLIENT_RUNTIME) {
      throw new IllegalArgumentException("Client-local observations require client provenance");
    }
  }
}
