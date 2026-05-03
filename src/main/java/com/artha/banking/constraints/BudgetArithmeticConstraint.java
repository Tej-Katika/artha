package com.artha.banking.constraints;

import com.artha.banking.ontology.Budget;
import com.artha.banking.ontology.BudgetRepository;
import com.artha.banking.ontology.TransactionEnrichmentRepository;
import com.artha.core.constraint.Constraint;
import com.artha.core.constraint.ConstraintGrade;
import com.artha.core.constraint.ConstraintResult;
import com.artha.core.constraint.EvaluationContext;
import com.artha.core.constraint.FactualClaim;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * HARD numeric check: in any calendar month covered by an active
 * Budget, the sum of DEBITs in that category must not exceed the
 * budget's allowance (monthlyLimit + rolloverAmount when rollover is
 * enabled), beyond a small tolerance.
 *
 * Per research/ONTOLOGY_V2_SPEC.md §6.5. The spec phrasing
 * "{@code budget.used = Σ(transactions in category in period)}" is
 * tautological in this schema — Budget has no denormalized {@code used}
 * column, so the constraint reframes the spirit of the rule as a
 * budget-overshoot check. A violation indicates either ETL drift, a
 * misbehaving Action, or a misconfigured budget — the agent should
 * surface the integrity issue rather than reason over it.
 *
 * Tolerance is {@code max($0.01, 0.1% of monthlyLimit)} to absorb
 * rounding without masking real overshoots.
 */
@Component
@RequiredArgsConstructor
public class BudgetArithmeticConstraint implements Constraint {

    private static final BigDecimal TOLERANCE_FLOOR  = new BigDecimal("0.01");
    private static final BigDecimal TOLERANCE_FACTOR = new BigDecimal("0.001");

    private final BudgetRepository                budgetRepo;
    private final TransactionEnrichmentRepository enrichRepo;

    @Override public String name()              { return "BudgetArithmetic"; }
    @Override public String domain()            { return "banking"; }
    @Override public ConstraintGrade grade()    { return ConstraintGrade.HARD; }
    @Override public String repairHintTemplate() {
        return "Spending in a budgeted category exceeds the budget's "
             + "allowance for the current period. Abort the response "
             + "and surface the budget-integrity issue rather than "
             + "reasoning over inconsistent state.";
    }

    @Override
    @Transactional(readOnly = true)
    public ConstraintResult evaluate(EvaluationContext ctx, Set<FactualClaim> claims) {
        List<Budget> budgets = budgetRepo.findByUserId(ctx.userId());

        LocalDate refDate = LocalDate.ofInstant(ctx.referenceDate(), ZoneOffset.UTC);
        LocalDate monthStart        = refDate.withDayOfMonth(1);
        LocalDate monthEndInclusive = monthStart.plusMonths(1).minusDays(1);

        for (Budget b : budgets) {
            if (b.getMonthlyLimit() == null) continue;
            if (b.getEffectiveFrom() == null) continue;

            // Skip budgets whose effective window doesn't overlap the
            // reference month at all.
            if (b.getEffectiveFrom().isAfter(monthEndInclusive)) continue;
            if (b.getEffectiveTo() != null
                && b.getEffectiveTo().isBefore(monthStart)) continue;

            LocalDate fromDate = b.getEffectiveFrom().isAfter(monthStart)
                ? b.getEffectiveFrom() : monthStart;
            LocalDate toDate = (b.getEffectiveTo() != null
                                && b.getEffectiveTo().isBefore(monthEndInclusive))
                ? b.getEffectiveTo() : monthEndInclusive;

            Instant fromI = fromDate.atStartOfDay(ZoneOffset.UTC).toInstant();
            Instant toI   = toDate.atTime(23, 59, 59, 999_999_999)
                                  .atZone(ZoneOffset.UTC).toInstant();

            UUID categoryId = b.getSpendingCategory() == null
                ? null : b.getSpendingCategory().getId();
            if (categoryId == null) continue;

            BigDecimal sum = enrichRepo.sumSpentInCategory(
                ctx.userId(), categoryId, fromI, toI);
            if (sum == null) sum = BigDecimal.ZERO;

            BigDecimal allowance = b.getMonthlyLimit();
            if (Boolean.TRUE.equals(b.getRolloverEnabled())
                && b.getRolloverAmount() != null) {
                allowance = allowance.add(b.getRolloverAmount());
            }
            BigDecimal tolerance = b.getMonthlyLimit()
                .multiply(TOLERANCE_FACTOR).max(TOLERANCE_FLOOR);
            BigDecimal cap = allowance.add(tolerance);

            if (sum.compareTo(cap) > 0) {
                return new ConstraintResult.Violated(
                    "Budget " + b.getId() + " (category " + categoryId
                    + ") spent " + sum + " in " + monthStart.getMonth()
                    + " " + monthStart.getYear() + " exceeds allowance "
                    + allowance + " (limit " + b.getMonthlyLimit()
                    + (Boolean.TRUE.equals(b.getRolloverEnabled())
                        ? " + rollover " + b.getRolloverAmount() : "")
                    + ", tolerance " + tolerance + ")",
                    repairHintTemplate());
            }
        }
        return ConstraintResult.Satisfied.INSTANCE;
    }
}
