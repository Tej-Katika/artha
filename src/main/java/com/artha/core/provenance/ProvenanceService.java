package com.artha.core.provenance;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Aggregates per-domain {@link ProvenanceResolver}s and answers
 * fact-level provenance queries.
 *
 * Resolvers are auto-discovered at startup via Spring component
 * scanning. {@link #why(UUID)} consults each resolver in turn and
 * returns the first match. Order is unspecified — fact ids are
 * UUIDs and naturally non-overlapping across entity types.
 *
 * The Week-2 stub returned {@link Optional#empty()} for everything;
 * Week-5 wires this up against a real list of resolvers (banking's
 * {@code TransactionEnrichmentProvenanceResolver} is the first).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProvenanceService {

    private final List<ProvenanceResolver> resolvers;

    /**
     * Single-step provenance for a fact.
     *
     * @return the assembled Provenance if any registered resolver
     *         claims this fact id; otherwise empty.
     */
    public Optional<Provenance> why(UUID factId) {
        for (ProvenanceResolver r : resolvers) {
            Optional<Provenance> hit = r.resolve(factId);
            if (hit.isPresent()) return hit;
        }
        log.debug("ProvenanceService.why({}) — no resolver claimed the id", factId);
        return Optional.empty();
    }

    /**
     * Full derivation tree (depth-first walk through deps).
     * Implementation pending in Week 6 once Constraints need it.
     */
    public Optional<DerivationTree> trace(UUID factId) {
        return why(factId).map(p -> new DerivationTree(factId, p, List.of()));
    }
}
