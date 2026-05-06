package com.artha.investments.constraints;

import com.artha.core.constraint.Constraint;
import com.artha.core.constraint.ConstraintGrade;
import com.artha.core.constraint.ConstraintResult;
import com.artha.core.constraint.EvaluationContext;
import com.artha.core.constraint.FactualClaim;
import com.artha.investments.ontology.Portfolio;
import com.artha.investments.ontology.PortfolioRepository;
import com.artha.investments.ontology.Position;
import com.artha.investments.ontology.PositionRepository;
import com.artha.investments.ontology.Trade;
import com.artha.investments.ontology.TradeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Set;

/**
 * HARD ontology-integrity check: for every {@link Position}, the
 * recorded quantity must equal the signed sum of its trades —
 * Σ(BUY.quantity) − Σ(SELL.quantity) — for the same (portfolio, security)
 * pair.
 *
 * <p>v2 scope: trade-based audit. The position-vs-trade reconstruction
 * is the right ledger invariant for v2 because the synthetic generator
 * (and the BUY-only RecordTradeAction) populates trades but does not
 * maintain a lot-level FIFO ledger. Lot-level audit (sum of open lots
 * == position.quantity) is reinstated in v3 alongside SELL accounting,
 * where lot closures need their own consistency check distinct from
 * trade-vs-position drift.
 *
 * <p>A violation here means a bypassed write path (manual SQL, buggy
 * import) or rounding drift exceeding the BigDecimal tolerance — the
 * agent cannot safely report position-level data, so the violation is
 * HARD.
 */
@Component
@RequiredArgsConstructor
public class TradeAuditConsistencyConstraint implements Constraint {

    private static final BigDecimal TOLERANCE = new BigDecimal("0.00000001");

    private final PortfolioRepository portfolioRepo;
    private final PositionRepository  positionRepo;
    private final TradeRepository     tradeRepo;

    @Override public String name()              { return "TradeAuditConsistency"; }
    @Override public String domain()            { return "investments"; }
    @Override public ConstraintGrade grade()    { return ConstraintGrade.HARD; }
    @Override public String repairHintTemplate() {
        return "Position quantity does not match the signed sum of "
             + "trades — the trade ledger is inconsistent; abort and "
             + "surface the integrity issue.";
    }

    @Override
    @Transactional(readOnly = true)
    public ConstraintResult evaluate(EvaluationContext ctx, Set<FactualClaim> claims) {
        for (Portfolio p : portfolioRepo.findByUserId(ctx.userId())) {
            for (Position pos : positionRepo.findByPortfolioId(p.getId())) {
                BigDecimal tradeSignedSum = tradeRepo
                    .findByPortfolioIdAndSecurityId(p.getId(), pos.getSecurity().getId())
                    .stream()
                    .map(t -> "SELL".equals(t.getSide())
                        ? t.getQuantity().negate()
                        : t.getQuantity())
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

                if (tradeSignedSum.subtract(pos.getQuantity()).abs().compareTo(TOLERANCE) > 0) {
                    return new ConstraintResult.Violated(
                        "Position " + pos.getId()
                        + " (" + pos.getSecurity().getTicker()
                        + ") quantity=" + pos.getQuantity()
                        + " but signed sum of trades=" + tradeSignedSum,
                        repairHintTemplate());
                }
            }
        }
        return ConstraintResult.Satisfied.INSTANCE;
    }
}
