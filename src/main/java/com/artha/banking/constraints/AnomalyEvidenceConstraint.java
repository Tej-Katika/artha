package com.artha.banking.constraints;

import com.artha.banking.ontology.TransactionEnrichmentRepository;
import com.artha.core.constraint.Constraint;
import com.artha.core.constraint.ConstraintGrade;
import com.artha.core.constraint.ConstraintResult;
import com.artha.core.constraint.EvaluationContext;
import com.artha.core.constraint.FactualClaim;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Set;

/**
 * SOFT claim-driven check: when the agent claims a specific number
 * of anomalies for the user, verify the count matches the detector's
 * flagged set on the {@code transaction_enrichments} table.
 *
 * Per research/ONTOLOGY_V2_SPEC.md §6.5 / §6.7. Operates only on
 * {@code anomaly_count} claims surfaced by the extractor. If the
 * agent makes no anomaly-count claim the constraint passes
 * vacuously.
 *
 * Tolerance: claims must match the actual count exactly. The
 * extractor only fires on integer counts so there is no rounding
 * ambiguity to absorb.
 */
@Component
@RequiredArgsConstructor
public class AnomalyEvidenceConstraint implements Constraint {

    private final TransactionEnrichmentRepository enrichRepo;

    @Override public String name()              { return "AnomalyEvidence"; }
    @Override public String domain()            { return "banking"; }
    @Override public ConstraintGrade grade()    { return ConstraintGrade.SOFT; }
    @Override public String repairHintTemplate() {
        return "The number of anomalies you cited does not match the "
             + "detector. Re-run get_anomalies and quote the actual "
             + "count returned.";
    }

    @Override
    public ConstraintResult evaluate(EvaluationContext ctx, Set<FactualClaim> claims) {
        for (FactualClaim claim : claims) {
            if (!"anomaly_count".equals(claim.kind())) continue;
            if (claim.value() == null) continue;

            long claimed = claim.value().longValueExact();
            long actual  = enrichRepo.countAnomaliesByUserId(ctx.userId());

            if (claimed != actual) {
                return new ConstraintResult.Violated(
                    "Agent claimed " + claimed + " anomalies but the "
                    + "detector reports " + actual + " for user "
                    + ctx.userId(),
                    repairHintTemplate());
            }
        }
        return ConstraintResult.Satisfied.INSTANCE;
    }

    /** Exposed for tests that pre-compute the expected count. */
    public BigDecimal currentCount(EvaluationContext ctx) {
        return BigDecimal.valueOf(
            enrichRepo.countAnomaliesByUserId(ctx.userId()));
    }
}
