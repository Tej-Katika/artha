package com.artha.core.provenance;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.UUID;

/**
 * Read interface for fact provenance.
 *
 * The Week-2 scaffold defines the surface; the implementations are
 * filled in during Week 5 (research/IEEE_PLAN.md) when the schema
 * migration adds provenance columns to enrichment-bearing tables.
 *
 * The service intentionally does not own a store of provenance
 * records itself — it dispatches to per-entity-type lookups against
 * domain repositories. Concrete dispatch is a Week-5 decision once
 * we see the actual lookup patterns the constraint checker needs.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProvenanceService {

    /**
     * Single-step provenance for a fact.
     *
     * @param factId UUID of any provenance-bearing entity
     * @return Provenance if the fact has been recorded; empty if the
     *         fact predates the v2 migration or the entity type is
     *         not yet provenance-tracked.
     */
    public Optional<Provenance> why(UUID factId) {
        // Week-5: dispatch to (TransactionEnrichment | MerchantProfile |
        // RecurringBill | Anomaly | Position | FeeAttribution).provenance
        // by querying each domain repository. Stub returns empty.
        log.debug("ProvenanceService.why({}) called — Week-5 stub returns empty", factId);
        return Optional.empty();
    }

    /**
     * Full derivation tree (depth-first walk through deps).
     *
     * Cycles are not possible by construction (provenance is append-
     * only and refers only to earlier facts).
     */
    public Optional<DerivationTree> trace(UUID factId) {
        log.debug("ProvenanceService.trace({}) called — Week-5 stub returns empty", factId);
        return Optional.empty();
    }
}
