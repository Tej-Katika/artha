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
 * SOFT behavioral check: in any calendar month covered by an active
 * Budget, the sum of DEBITs in that category must not exceed the
 * budget's allowance (monthlyLimit + rolloverAmount when rollover is
 * enabled), beyond a small tolerance.
 *
 * Per research/ONTOLOGY_V2_SPEC.md §6.5. The spec's literal
 * "{@code budget.used = Σ(transactions in category in period)}" is
 * tautological in this schema — Budget has no denormalized {@code used}
 * column. The constraint reframes the spirit of the rule as
 * over-budget detection, treated as a SOFT actionable signal rather
 * than a HARD integrity abort: a real user overspending a real budget
 * is the agent's most important opportunity to add value, not a data
 * error to refuse over. The repair hint nudges the agent to surface
 * the overshoot prominently in the response.
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
    @Override public ConstraintGrade grade()    { return ConstraintGrade.SOFT; }
    @Override public String repairHintTemplate() {
        return "The user has exceeded one or more of their monthly "
             + "budgets. Acknowledge each overshoot prominently in the "
             + "response — name the category and cite the dollar "
             + "amount over budget. This is meaningful actionable "
             + "financial signal, not a data error: the numbers in the "
             + "spending tools are accurate.";
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
                String categoryName = b.getSpendingCategory().getName();
                BigDecimal overshoot = sum.subtract(allowance);
                return new ConstraintResult.Violated(
                    "Budget exceeded: " + categoryName + " spent $" + sum
                    + " in " + monthStart.getMonth() + " "
                    + monthStart.getYear() + " vs allowance $" + allowance
                    + " — overshoot $" + overshoot,
                    repairHintTemplate());
            }
        }
        return ConstraintResult.Satisfied.INSTANCE;
    }
}
