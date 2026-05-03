package com.artha.banking.constraints;

import com.artha.banking.ontology.TransactionEnrichmentRepository;
import com.artha.core.constraint.Constraint;
import com.artha.core.constraint.ConstraintGrade;
import com.artha.core.constraint.ConstraintResult;
import com.artha.core.constraint.EvaluationContext;
import com.artha.core.constraint.FactualClaim;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * HARD ontology canary: a transaction has exactly one primary
 * categorization. Per research/ONTOLOGY_V2_SPEC.md §6.5.
 *
 * The DB schema enforces this via a {@code UNIQUE(transaction_id)}
 * index on {@code transaction_enrichments}, so this constraint is
 * structurally vacuous in normal operation. We keep the Java check
 * deliberately, for two reasons:
 *
 * <ol>
 *   <li>If the v3 schema relaxes the unique index to support split
 *       transactions, the Java invariant catches the regression
 *       immediately rather than letting the agent reason over corrupt
 *       state.</li>
 *   <li>Treating ontology integrity uniformly across HARD constraints
 *       (Java predicate, not DDL silence) gives the eval a consistent
 *       violation-telemetry surface.</li>
 * </ol>
 */
@Component
@RequiredArgsConstructor
public class CategoryMutexConstraint implements Constraint {

    private final TransactionEnrichmentRepository enrichRepo;

    @Override public String name()              { return "CategoryMutex"; }
    @Override public String domain()            { return "banking"; }
    @Override public ConstraintGrade grade()    { return ConstraintGrade.HARD; }
    @Override public String repairHintTemplate() {
        return "One or more transactions have multiple primary "
             + "categorizations — the ontology is corrupt. Abort the "
             + "response and surface the integrity issue.";
    }

    @Override
    public ConstraintResult evaluate(EvaluationContext ctx, Set<FactualClaim> claims) {
        long duplicates =
            enrichRepo.countDuplicateEnrichedTransactionsByUserId(ctx.userId());
        if (duplicates > 0) {
            return new ConstraintResult.Violated(
                duplicates + " transaction(s) for user " + ctx.userId()
                + " have multiple enrichment rows; one primary category "
                + "per transaction is required.",
                repairHintTemplate());
        }
        return ConstraintResult.Satisfied.INSTANCE;
    }
}
