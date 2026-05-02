package com.artha.core.provenance;

import java.util.Optional;
import java.util.UUID;

/**
 * Per-domain lookup that knows how to read provenance off a specific
 * entity type. Domains register one resolver per provenance-bearing
 * entity (TransactionEnrichment, MerchantProfile, RecurringBill,
 * Position, FeeAttribution, ...).
 *
 * {@link ProvenanceService} aggregates the registered resolvers and
 * dispatches to whichever one recognizes the fact id. The order in
 * which resolvers are invoked is unspecified — implementations must
 * return {@link Optional#empty()} when the id is not theirs.
 *
 * Keeping resolvers domain-local means {@code core} has no compile-
 * time dependency on banking or investments entities; the dependency
 * direction stays one-way.
 */
public interface ProvenanceResolver {

    /**
     * Resolve provenance for the supplied fact id, or
     * {@link Optional#empty()} if the id is not handled by this
     * resolver.
     */
    Optional<Provenance> resolve(UUID factId);
}
