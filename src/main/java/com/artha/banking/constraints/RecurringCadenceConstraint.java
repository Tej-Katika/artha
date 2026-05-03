package com.artha.banking.constraints;

import com.artha.banking.ontology.RecurringBill;
import com.artha.banking.ontology.RecurringBillRepository;
import com.artha.core.constraint.Constraint;
import com.artha.core.constraint.ConstraintGrade;
import com.artha.core.constraint.ConstraintResult;
import com.artha.core.constraint.EvaluationContext;
import com.artha.core.constraint.FactualClaim;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/**
 * HARD ontology check: every active {@link RecurringBill} must have a
 * billing cycle drawn from the canonical enum
 * {@code {WEEKLY, BIWEEKLY, MONTHLY, QUARTERLY, ANNUAL}}.
 *
 * Per research/ONTOLOGY_V2_SPEC.md §6.5. The check runs over ontology
 * state, not agent claims — a violation indicates a bad write upstream
 * (a misbehaving Action, manual SQL, ETL drift) that would corrupt
 * downstream cadence-aware reasoning.
 */
@Component
@RequiredArgsConstructor
public class RecurringCadenceConstraint implements Constraint {

    private static final Set<String> VALID_CADENCES = Set.of(
        "WEEKLY", "BIWEEKLY", "MONTHLY", "QUARTERLY", "ANNUAL");

    private final RecurringBillRepository billRepo;

    @Override public String name()              { return "RecurringCadence"; }
    @Override public String domain()            { return "banking"; }
    @Override public ConstraintGrade grade()    { return ConstraintGrade.HARD; }
    @Override public String repairHintTemplate() {
        return "A recurring bill has an unrecognized cadence; only "
             + "WEEKLY, BIWEEKLY, MONTHLY, QUARTERLY, or ANNUAL are "
             + "valid. Abort the response and surface the integrity issue.";
    }

    @Override
    public ConstraintResult evaluate(EvaluationContext ctx, Set<FactualClaim> claims) {
        List<RecurringBill> bills = billRepo.findByUserIdAndIsActiveTrue(ctx.userId());

        for (RecurringBill b : bills) {
            String cadence = b.getBillingCycle();
            if (cadence == null || !VALID_CADENCES.contains(cadence)) {
                return new ConstraintResult.Violated(
                    "RecurringBill " + b.getId() + " (" + b.getName()
                    + ") has invalid billingCycle: "
                    + (cadence == null ? "<null>" : "'" + cadence + "'"),
                    repairHintTemplate());
            }
        }
        return ConstraintResult.Satisfied.INSTANCE;
    }
}
