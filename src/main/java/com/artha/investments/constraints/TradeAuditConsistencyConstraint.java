package com.artha.investments.constraints;

import com.artha.core.constraint.Constraint;
import com.artha.core.constraint.ConstraintGrade;
import com.artha.core.constraint.ConstraintResult;
import com.artha.core.constraint.EvaluationContext;
import com.artha.core.constraint.FactualClaim;
import com.artha.investments.ontology.Lot;
import com.artha.investments.ontology.LotRepository;
import com.artha.investments.ontology.Portfolio;
import com.artha.investments.ontology.PortfolioRepository;
import com.artha.investments.ontology.Position;
import com.artha.investments.ontology.PositionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Set;

/**
 * HARD ontology-integrity check: for every {@link Position}, the sum
 * of its open {@link Lot} quantities must equal the position's
 * aggregate quantity.
 *
 * RecordTradeAction enforces this in its postcondition, so a violation
 * here means either a bypassed action (manual SQL, buggy import), a
 * future SELL implementation that mis-closed lots, or rounding drift
 * that exceeds the BigDecimal tolerance. Either way the agent cannot
 * safely report position-level data, so the violation is HARD.
 */
@Component
@RequiredArgsConstructor
public class TradeAuditConsistencyConstraint implements Constraint {

    private static final BigDecimal TOLERANCE = new BigDecimal("0.00000001");

    private final PortfolioRepository portfolioRepo;
    private final PositionRepository  positionRepo;
    private final LotRepository       lotRepo;

    @Override public String name()              { return "TradeAuditConsistency"; }
    @Override public String domain()            { return "investments"; }
    @Override public ConstraintGrade grade()    { return ConstraintGrade.HARD; }
    @Override public String repairHintTemplate() {
        return "Position quantity does not match the sum of open lots — "
             + "the trade ledger is inconsistent; abort and surface the "
             + "integrity issue.";
    }

    @Override
    @Transactional(readOnly = true)
    public ConstraintResult evaluate(EvaluationContext ctx, Set<FactualClaim> claims) {
        for (Portfolio p : portfolioRepo.findByUserId(ctx.userId())) {
            for (Position pos : positionRepo.findByPortfolioId(p.getId())) {
                BigDecimal openLotsTotal = lotRepo
                    .findByPositionIdAndClosedAtIsNullOrderByAcquiredAtAsc(pos.getId())
                    .stream()
                    .map(Lot::getQuantity)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

                if (openLotsTotal.subtract(pos.getQuantity()).abs().compareTo(TOLERANCE) > 0) {
                    return new ConstraintResult.Violated(
                        "Position " + pos.getId()
                        + " (" + pos.getSecurity().getTicker()
                        + ") quantity=" + pos.getQuantity()
                        + " but sum of open lots=" + openLotsTotal,
                        repairHintTemplate());
                }
            }
        }
        return ConstraintResult.Satisfied.INSTANCE;
    }
}
