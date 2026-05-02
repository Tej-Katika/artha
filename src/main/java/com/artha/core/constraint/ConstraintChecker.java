package com.artha.core.constraint;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Runs all active constraints for a domain over a candidate agent
 * response and aggregates their results.
 *
 * Per research/ONTOLOGY_V2_SPEC.md §6.4, the orchestrator's
 * candidate-response loop uses the {@link CheckResult} returned here
 * to decide:
 *   - any HARD violation → reject and force revision
 *   - any SOFT violation (within retry budget) → inject repair hint
 *     and request revision
 *   - ADVISORY violations → log only
 *
 * The Week-2 scaffold wires registry + checker; ClaimExtractor is
 * injected as Optional so the bean can come up before extractors are
 * implemented in Week 6.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConstraintChecker {

    private final ConstraintRegistry registry;

    /** Optional until Week 6. When absent, claims default to empty. */
    @Autowired(required = false)
    private ClaimExtractor claimExtractor;

    /**
     * Evaluate every constraint registered for `domain` and return
     * a structured summary.
     */
    public CheckResult check(String domain,
                             UUID userId,
                             Instant referenceDate,
                             String responseText) {

        EvaluationContext ctx = new EvaluationContext(userId, referenceDate, responseText);
        Set<FactualClaim> claims =
            claimExtractor != null
                ? claimExtractor.extract(responseText, domain)
                : Set.of();

        List<Violation> violations = new ArrayList<>();
        for (Constraint c : registry.forDomain(domain)) {
            ConstraintResult r;
            try {
                r = c.evaluate(ctx, claims);
            } catch (RuntimeException ex) {
                log.warn("Constraint {} threw — treating as Indeterminate: {}",
                    c.name(), ex.getMessage());
                r = new ConstraintResult.Indeterminate(
                    "constraint threw: " + ex.getMessage());
            }
            if (r instanceof ConstraintResult.Violated v) {
                violations.add(new Violation(c.name(), c.grade(), v.message(), v.repairHint()));
            }
        }
        return new CheckResult(violations, claims.size());
    }

    // ── result types ─────────────────────────────────────────────

    public record CheckResult(List<Violation> violations, int claimsExtracted) {
        public boolean hasHard()    { return any(ConstraintGrade.HARD); }
        public boolean hasSoft()    { return any(ConstraintGrade.SOFT); }
        public boolean satisfied()  { return violations.isEmpty(); }
        private boolean any(ConstraintGrade g) {
            for (Violation v : violations) if (v.grade() == g) return true;
            return false;
        }
    }

    public record Violation(String constraintName,
                            ConstraintGrade grade,
                            String message,
                            String repairHint) {}
}
