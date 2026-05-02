package com.artha.core.provenance;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Provenance record attached to every fact in the v2 ontology.
 *
 * Defined in research/ONTOLOGY_V2_SPEC.md §5.2 as the 5-tuple
 * ⟨source_type, rule_id?, confidence, deps, t_asof⟩.
 *
 * Immutable; instances are produced by enrichment pipelines and by
 * Action executions, and combined via {@link ProvenanceCombiner}.
 *
 * @param sourceType  How this fact was derived. See {@link SourceType}.
 * @param ruleId      The classifier rule that fired, if sourceType = RULES.
 *                    Null otherwise.
 * @param confidence  Strength of the derivation, in [0.00, 1.00].
 * @param deps        Other facts this one depends on (their fact ids).
 *                    Empty list for leaf facts derived solely from raw input.
 * @param asof        When the derivation was performed.
 */
public record Provenance(
    SourceType sourceType,
    String     ruleId,
    BigDecimal confidence,
    List<UUID> deps,
    Instant    asof
) {

    /** Compact constructor enforces the [0,1] confidence invariant. */
    public Provenance {
        if (confidence == null) {
            throw new IllegalArgumentException("confidence must not be null");
        }
        if (confidence.signum() < 0 || confidence.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException(
                "confidence must be in [0.00, 1.00]; got " + confidence);
        }
        if (sourceType == null) {
            throw new IllegalArgumentException("sourceType must not be null");
        }
        if (asof == null) {
            throw new IllegalArgumentException("asof must not be null");
        }
        if (deps == null) {
            deps = Collections.emptyList();
        } else {
            deps = List.copyOf(deps);   // defensive copy → truly immutable
        }
    }

    /** Leaf provenance with no dependencies, asof = now. */
    public static Provenance leaf(SourceType source, String ruleId, BigDecimal confidence) {
        return new Provenance(source, ruleId, confidence, List.of(), Instant.now());
    }

    /** Provenance for a fact derived from `parents`. */
    public static Provenance derived(SourceType source,
                                     String ruleId,
                                     BigDecimal confidence,
                                     List<UUID> parents) {
        return new Provenance(source, ruleId, confidence, parents, Instant.now());
    }
}
