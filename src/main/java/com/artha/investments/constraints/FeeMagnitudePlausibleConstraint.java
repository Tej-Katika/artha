package com.artha.investments.constraints;

import com.artha.core.constraint.Constraint;
import com.artha.core.constraint.ConstraintGrade;
import com.artha.core.constraint.ConstraintResult;
import com.artha.core.constraint.EvaluationContext;
import com.artha.core.constraint.FactualClaim;
import com.artha.investments.ontology.Fee;
import com.artha.investments.ontology.FeeRepository;
import com.artha.investments.ontology.Portfolio;
import com.artha.investments.ontology.PortfolioRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Set;

/**
 * SOFT claim-driven check: any {@code fee_amount} claim in the
 * response must be within {@link #TOLERANCE_FACTOR} of the user's
 * actual lifetime fee total across all portfolios. Catches the
 * common hallucination shape where the agent invents a fee figure
 * that has no support in the user's data.
 *
 * Tolerance is generous (5×) because the agent might quote a single
 * line item ("$50 advisory fee") while the actual is a much larger
 * aggregate; we only want to flag clear fabrications. Per-period
 * comparisons land in v3 once eval coverage exposes the false-
 * positive cost of the tighter check.
 */
@Component
@RequiredArgsConstructor
public class FeeMagnitudePlausibleConstraint implements Constraint {

    private static final BigDecimal TOLERANCE_FACTOR = new BigDecimal("5.00");

    private final PortfolioRepository portfolioRepo;
    private final FeeRepository       feeRepo;

    @Override public String name()              { return "FeeMagnitudePlausible"; }
    @Override public String domain()            { return "investments"; }
    @Override public ConstraintGrade grade()    { return ConstraintGrade.SOFT; }
    @Override public String repairHintTemplate() {
        return "The fee figure you cited is much larger than the user's "
             + "fee history supports. Re-derive it from get_fee_breakdown "
             + "and only quote what the tool returns.";
    }

    @Override
    @Transactional(readOnly = true)
    public ConstraintResult evaluate(EvaluationContext ctx, Set<FactualClaim> claims) {
        BigDecimal cachedTotal = null;

        for (FactualClaim claim : claims) {
            if (!"fee_amount".equals(claim.kind())) continue;
            if (claim.value() == null) continue;

            if (cachedTotal == null) {
                cachedTotal = BigDecimal.ZERO;
                for (Portfolio p : portfolioRepo.findByUserId(ctx.userId())) {
                    for (Fee f : feeRepo.findByPortfolioId(p.getId())) {
                        cachedTotal = cachedTotal.add(f.getAmount());
                    }
                }
            }

            BigDecimal cap = cachedTotal.multiply(TOLERANCE_FACTOR);
            if (cachedTotal.signum() == 0 && claim.value().signum() > 0) {
                return new ConstraintResult.Violated(
                    "Claimed fee_amount " + claim.value()
                    + " has no support: user has zero recorded fees",
                    repairHintTemplate());
            }
            if (claim.value().compareTo(cap) > 0) {
                return new ConstraintResult.Violated(
                    "Claimed fee_amount " + claim.value()
                    + " exceeds " + TOLERANCE_FACTOR + "× the user's "
                    + "lifetime fee total " + cachedTotal,
                    repairHintTemplate());
            }
        }
        return ConstraintResult.Satisfied.INSTANCE;
    }
}
