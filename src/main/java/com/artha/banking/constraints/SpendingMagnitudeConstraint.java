package com.artha.banking.constraints;

import com.artha.banking.ontology.TransactionRepository;
import com.artha.core.constraint.Constraint;
import com.artha.core.constraint.ConstraintGrade;
import com.artha.core.constraint.ConstraintResult;
import com.artha.core.constraint.EvaluationContext;
import com.artha.core.constraint.FactualClaim;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Set;

/**
 * SOFT claim-driven check: a spending_amount claim must be plausible
 * given the user's actual debit history.
 *
 * Concretely: for each {@code spending_amount} claim of value X, the
 * sum of the user's DEBIT transactions over the past 365 days must
 * be at least {@code TOLERANCE_FACTOR * X}. Catches the most common
 * hallucination shape — the agent inventing a dollar figure that
 * has no support in the user's data.
 *
 * Tolerance is generous (50%) because regex extraction may pick up
 * partial figures (e.g., a per-month amount cited next to an annual
 * total) and we want to keep false-positive rate low. The HARD
 * BudgetArithmetic constraint (planned for the second batch) will
 * provide tight numeric checks against budget rows.
 *
 * Per research/ONTOLOGY_V2_SPEC.md §6.5; replaces the spec's
 * IncomeExpenseSign placeholder with a check that fires meaningfully
 * given the regex extractor's actual coverage.
 */
@Component
@RequiredArgsConstructor
public class SpendingMagnitudeConstraint implements Constraint {

    private static final BigDecimal TOLERANCE_FACTOR = new BigDecimal("0.50");
    private static final int        LOOKBACK_DAYS    = 365;

    private final TransactionRepository txRepo;

    @Override public String name()              { return "SpendingMagnitude"; }
    @Override public String domain()            { return "banking"; }
    @Override public ConstraintGrade grade()    { return ConstraintGrade.SOFT; }
    @Override public String repairHintTemplate() {
        return "The spending figure you cited is much larger than the "
             + "user's debit history supports. Re-derive it from "
             + "get_spending_summary and only quote what the tool "
             + "returns.";
    }

    @Override
    public ConstraintResult evaluate(EvaluationContext ctx, Set<FactualClaim> claims) {
        BigDecimal cachedTotal = null;

        for (FactualClaim claim : claims) {
            if (!"spending_amount".equals(claim.kind())) continue;
            if (claim.value() == null) continue;

            if (cachedTotal == null) {
                Instant from = ctx.referenceDate()
                    .minus(LOOKBACK_DAYS, ChronoUnit.DAYS);
                cachedTotal = txRepo.totalSpent(
                    ctx.userId(), from, ctx.referenceDate());
                if (cachedTotal == null) cachedTotal = BigDecimal.ZERO;
            }

            BigDecimal threshold = claim.value().multiply(TOLERANCE_FACTOR);
            if (cachedTotal.compareTo(threshold) < 0) {
                return new ConstraintResult.Violated(
                    "Claimed spending " + claim.value()
                    + " is implausible given last-365d debit total "
                    + cachedTotal,
                    repairHintTemplate());
            }
        }
        return ConstraintResult.Satisfied.INSTANCE;
    }
}
