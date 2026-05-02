package com.artha.core.provenance;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Implements the provenance combination algebra defined in
 * research/ONTOLOGY_V2_SPEC.md §5.4.
 *
 * Three operations:
 *
 * 1. combineIndependent(p1, p2) — two sources independently support
 *    the same conclusion. Confidence under noisy-or:
 *        c = 1 - (1 - c1)(1 - c2)
 *
 * 2. combineHierarchical(p1, p2) — sources at different lattice
 *    levels both support the same conclusion. The stronger source's
 *    claim wins; the weaker source contributes to the dependency set
 *    but does not raise confidence above the stronger source's value.
 *
 * 3. inherit(parents, alpha) — derived fact whose confidence is
 *    bounded by its weakest parent, with optional decay alpha:
 *        c = min(c_i) * alpha   (default alpha = 0.95)
 */
@Component
public class ProvenanceCombiner {

    /** Default per-step decay multiplier for derivation chains. */
    public static final BigDecimal DEFAULT_ALPHA =
        new BigDecimal("0.95");

    private static final MathContext MC = MathContext.DECIMAL64;

    /**
     * Independent reinforcement (noisy-or).
     *
     * Use when two sources independently arrived at the same
     * conclusion (e.g., a RULES match AND a METADATA match for the
     * same category).
     *
     * Returns a new Provenance with:
     *   sourceType = stronger of the two (lattice winner)
     *   ruleId     = stronger source's ruleId
     *   confidence = 1 − (1 − c1)(1 − c2)
     *   deps       = union of both
     *   asof       = now
     */
    public Provenance combineIndependent(Provenance p1, Provenance p2) {
        Provenance stronger = p1.sourceType().dominates(p2.sourceType()) ? p1 : p2;
        Provenance weaker   = (stronger == p1) ? p2 : p1;

        BigDecimal c = noisyOr(p1.confidence(), p2.confidence());
        return new Provenance(
            stronger.sourceType(),
            stronger.ruleId(),
            c,
            mergeDeps(p1.deps(), p2.deps()),
            Instant.now()
        );
    }

    /**
     * Hierarchical override.
     *
     * Use when one source clearly dominates the other in the trust
     * lattice (e.g., a USER_INPUT correction over a RULES match). The
     * dominant source's claim and confidence are kept; the weaker
     * source's id flows into the dependency list as supporting
     * evidence but does not raise confidence.
     */
    public Provenance combineHierarchical(Provenance p1, Provenance p2) {
        Provenance stronger = p1.sourceType().dominates(p2.sourceType()) ? p1 : p2;
        Provenance weaker   = (stronger == p1) ? p2 : p1;

        return new Provenance(
            stronger.sourceType(),
            stronger.ruleId(),
            stronger.confidence(),
            mergeDeps(stronger.deps(), weaker.deps()),
            Instant.now()
        );
    }

    /**
     * Inheritance: a derived fact's confidence is bounded by the
     * weakest of its parents, multiplied by an optional decay.
     *
     * @param parents the provenance of every fact this derivation depends on
     * @param alpha   decay multiplier in (0, 1]; pass DEFAULT_ALPHA for the standard 0.95
     */
    public BigDecimal inheritedConfidence(List<Provenance> parents, BigDecimal alpha) {
        if (parents == null || parents.isEmpty()) {
            return BigDecimal.ONE;
        }
        BigDecimal min = parents.get(0).confidence();
        for (int i = 1; i < parents.size(); i++) {
            BigDecimal ci = parents.get(i).confidence();
            if (ci.compareTo(min) < 0) min = ci;
        }
        return clamp01(min.multiply(alpha == null ? DEFAULT_ALPHA : alpha, MC));
    }

    // ── helpers ──────────────────────────────────────────────────

    private static BigDecimal noisyOr(BigDecimal c1, BigDecimal c2) {
        BigDecimal one = BigDecimal.ONE;
        BigDecimal joint = one.subtract(c1).multiply(one.subtract(c2), MC);
        return clamp01(one.subtract(joint).round(MC));
    }

    private static BigDecimal clamp01(BigDecimal x) {
        if (x.signum() < 0) return BigDecimal.ZERO;
        if (x.compareTo(BigDecimal.ONE) > 0) return BigDecimal.ONE;
        return x.setScale(4, RoundingMode.HALF_UP);
    }

    private static List<java.util.UUID> mergeDeps(List<java.util.UUID> a, List<java.util.UUID> b) {
        if (a.isEmpty() && b.isEmpty()) return List.of();
        List<java.util.UUID> merged = new ArrayList<>(a.size() + b.size());
        merged.addAll(a);
        for (java.util.UUID id : b) {
            if (!merged.contains(id)) merged.add(id);
        }
        return List.copyOf(merged);
    }
}
