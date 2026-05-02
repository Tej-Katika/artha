package com.artha.banking.provenance;

import com.artha.banking.ontology.TransactionEnrichment;
import com.artha.banking.ontology.TransactionEnrichmentRepository;
import com.artha.core.provenance.Provenance;
import com.artha.core.provenance.ProvenanceResolver;
import com.artha.core.provenance.SourceType;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Banking-domain {@link ProvenanceResolver} for
 * {@link TransactionEnrichment}.
 *
 * The fact id may be either:
 * <ul>
 *   <li>The {@code TransactionEnrichment.id} (preferred — unambiguous)</li>
 *   <li>The {@code Transaction.id} the enrichment is one-to-one with —
 *       a useful shortcut because the agent often has transaction
 *       ids on hand</li>
 * </ul>
 *
 * For pre-Week-5 ("legacy") rows the new provenance columns are
 * NULL; the resolver still returns a well-formed Provenance using
 * the existing {@code enrichment_source} / {@code enrichment_confidence}
 * columns and falls back to {@code updated_at} for the timestamp.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TransactionEnrichmentProvenanceResolver
        implements ProvenanceResolver {

    private final TransactionEnrichmentRepository enrichRepo;
    private final ObjectMapper                    objectMapper;

    @Override
    public Optional<Provenance> resolve(UUID factId) {
        Optional<TransactionEnrichment> direct = enrichRepo.findById(factId);
        if (direct.isPresent()) {
            return Optional.of(toProvenance(direct.get()));
        }
        return enrichRepo.findByTransactionId(factId).map(this::toProvenance);
    }

    private Provenance toProvenance(TransactionEnrichment e) {
        SourceType source = mapSourceType(e.getEnrichmentSource());

        BigDecimal confidence = e.getEnrichmentConfidence() != null
            ? e.getEnrichmentConfidence()
            : BigDecimal.ZERO;
        if (confidence.compareTo(BigDecimal.ONE) > 0) {
            confidence = BigDecimal.ONE;
        }
        if (confidence.signum() < 0) {
            confidence = BigDecimal.ZERO;
        }

        Instant asof = e.getProvenanceAsof() != null
            ? e.getProvenanceAsof()
            : (e.getUpdatedAt() != null
                ? e.getUpdatedAt()
                : (e.getCreatedAt() != null
                    ? e.getCreatedAt()
                    : Instant.now()));

        return new Provenance(
            source,
            e.getProvenanceRuleId(),
            confidence,
            parseDeps(e.getProvenanceDepsJson()),
            asof
        );
    }

    private static SourceType mapSourceType(String raw) {
        if (raw == null || raw.isBlank()) return SourceType.FALLBACK;
        try {
            return SourceType.valueOf(raw);
        } catch (IllegalArgumentException ex) {
            // v1 strings outside the spec vocab — common case "AI"
            return switch (raw) {
                case "AI"     -> SourceType.COMPUTED;
                case "MANUAL" -> SourceType.USER_INPUT;
                default       -> SourceType.FALLBACK;
            };
        }
    }

    private List<UUID> parseDeps(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            List<String> raw = objectMapper.readValue(
                json, new TypeReference<List<String>>() {});
            return raw.stream().map(UUID::fromString).toList();
        } catch (Exception ex) {
            log.warn("Could not parse provenance_deps_json='{}': {}",
                json, ex.getMessage());
            return List.of();
        }
    }
}
