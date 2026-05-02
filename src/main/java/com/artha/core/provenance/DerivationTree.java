package com.artha.core.provenance;

import java.util.List;
import java.util.UUID;

/**
 * Recursive view of a fact's full derivation chain.
 *
 * Returned by {@link ProvenanceService#trace(java.util.UUID)}. Each
 * node carries the fact's id, its provenance, and its parent
 * subtrees — letting consumers (the constraint checker, the human-
 * evaluator UI) walk the full derivation.
 *
 * Cycles are not permitted; the underlying provenance store is
 * append-only and a fact's deps point to *earlier* facts only.
 */
public record DerivationTree(
    UUID                  factId,
    Provenance            provenance,
    List<DerivationTree>  parents
) {

    public DerivationTree {
        if (parents == null) parents = List.of();
        else                  parents = List.copyOf(parents);
    }

    public boolean isLeaf() {
        return parents.isEmpty();
    }

    /** Maximum depth from this node to any leaf, inclusive. */
    public int depth() {
        if (isLeaf()) return 1;
        int max = 0;
        for (DerivationTree p : parents) {
            max = Math.max(max, p.depth());
        }
        return 1 + max;
    }
}
