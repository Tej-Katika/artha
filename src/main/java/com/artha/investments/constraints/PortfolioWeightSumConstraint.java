package com.artha.investments.constraints;

import com.artha.core.constraint.Constraint;
import com.artha.core.constraint.ConstraintGrade;
import com.artha.core.constraint.ConstraintResult;
import com.artha.core.constraint.EvaluationContext;
import com.artha.core.constraint.FactualClaim;
import com.artha.investments.ontology.Portfolio;
import com.artha.investments.ontology.PortfolioRepository;
import com.artha.investments.ontology.RiskProfile;
import com.artha.investments.ontology.RiskProfileRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Set;

/**
 * HARD ontology-integrity check: every {@code risk_profiles} row's
 * target_equity_pct + target_bond_pct + target_alt_pct must sum to
 * 100.0 within {@link #TOLERANCE} percentage points.
 *
 * The V4 schema has a CHECK constraint enforcing the same invariant,
 * but this constraint catches the same bug if it ever surfaces from
 * a manual SQL surgery, a bypassed action, or future generator
 * tweaks. Tightening the tolerance here would let us flag drift
 * before it would have failed the schema check on the next write.
 */
@Component
@RequiredArgsConstructor
public class PortfolioWeightSumConstraint implements Constraint {

    private static final BigDecimal TARGET    = new BigDecimal("100.00");
    private static final BigDecimal TOLERANCE = new BigDecimal("0.50");

    private final PortfolioRepository    portfolioRepo;
    private final RiskProfileRepository  riskRepo;

    @Override public String name()              { return "PortfolioWeightSum"; }
    @Override public String domain()            { return "investments"; }
    @Override public ConstraintGrade grade()    { return ConstraintGrade.HARD; }
    @Override public String repairHintTemplate() {
        return "A portfolio's target allocation does not sum to 100% — "
             + "the risk profile is corrupted; abort the response and "
             + "surface the integrity issue.";
    }

    @Override
    @Transactional(readOnly = true)
    public ConstraintResult evaluate(EvaluationContext ctx, Set<FactualClaim> claims) {
        for (Portfolio p : portfolioRepo.findByUserId(ctx.userId())) {
            RiskProfile risk = riskRepo.findByPortfolioId(p.getId()).orElse(null);
            if (risk == null) continue;
            BigDecimal sum = risk.getTargetEquityPct()
                .add(risk.getTargetBondPct())
                .add(risk.getTargetAltPct());
            if (sum.subtract(TARGET).abs().compareTo(TOLERANCE) > 0) {
                return new ConstraintResult.Violated(
                    "Portfolio " + p.getId() + " (" + p.getName()
                    + ") target weights sum to " + sum + ", expected 100 ± "
                    + TOLERANCE,
                    repairHintTemplate());
            }
        }
        return ConstraintResult.Satisfied.INSTANCE;
    }
}
