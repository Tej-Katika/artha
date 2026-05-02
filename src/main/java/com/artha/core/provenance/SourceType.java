package com.artha.core.provenance;

/**
 * Provenance source-type lattice (research/ONTOLOGY_V2_SPEC.md §5.3).
 *
 * Trustworthiness ordering (most → least trusted):
 *
 *   USER_INPUT ≻ RULES ≻ COMPUTED ≻ METADATA ≻ FALLBACK
 *
 * AGENT_ACTION is orthogonal to the trust lattice — it tracks "the
 * agent wrote this," and confidence comes from the action's
 * self-assessment plus constraint validation.
 *
 * The {@link #rank()} method returns the lattice position used by
 * {@link ProvenanceCombiner#combineHierarchical}.
 */
public enum SourceType {

    USER_INPUT   (5),
    RULES        (4),
    COMPUTED     (3),
    METADATA     (2),
    FALLBACK     (1),
    AGENT_ACTION (0);   // orthogonal; rank used only as tie-breaker

    private final int rank;

    SourceType(int rank) {
        this.rank = rank;
    }

    /** Higher = more trusted in the trust lattice. */
    public int rank() {
        return rank;
    }

    /** True if `this` is a strictly stronger source than `other`. */
    public boolean dominates(SourceType other) {
        return this.rank > other.rank;
    }
}
