package com.artha.investments.actions;

import com.artha.core.action.Action;
import com.artha.core.action.PostconditionViolation;
import com.artha.core.action.PreconditionViolation;
import com.artha.investments.ontology.Lot;
import com.artha.investments.ontology.LotRepository;
import com.artha.investments.ontology.Portfolio;
import com.artha.investments.ontology.PortfolioRepository;
import com.artha.investments.ontology.Position;
import com.artha.investments.ontology.PositionRepository;
import com.artha.investments.ontology.Security;
import com.artha.investments.ontology.SecurityRepository;
import com.artha.investments.ontology.Trade;
import com.artha.investments.ontology.TradeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Append a {@link Trade} row, open the corresponding {@link Lot},
 * and upsert the rolling {@link Position}.
 *
 * v2 scope: BUY trades only. SELL is rejected at precondition; lot-
 * close FIFO accounting lands in v3 once the eval surfaces a real
 * need for divestment scenarios. The synthetic generator only writes
 * BUYs, so this constraint matches the eval data shape.
 *
 * Postcondition asserts ledger consistency: the position's quantity
 * equals the sum of its open-lot quantities, which TradeAuditConsistency
 * also enforces at constraint-check time.
 */
@Component
@RequiredArgsConstructor
public class RecordTradeAction implements Action<RecordTradeAction.Input, RecordTradeAction.Output> {

    public static final Set<String> ALLOWED_SIDES = Set.of("BUY");

    private final PortfolioRepository portfolioRepo;
    private final SecurityRepository  securityRepo;
    private final PositionRepository  positionRepo;
    private final LotRepository       lotRepo;
    private final TradeRepository     tradeRepo;

    public record Input(
        UUID       actorUserId,
        UUID       portfolioId,
        UUID       securityId,
        String     side,           // "BUY" only in v2
        BigDecimal quantity,
        BigDecimal price,
        BigDecimal fees,            // optional, defaults to 0
        Instant    executedAt
    ) {
        public Input {
            if (actorUserId == null) throw new IllegalArgumentException("actorUserId required");
            if (portfolioId == null) throw new IllegalArgumentException("portfolioId required");
            if (securityId  == null) throw new IllegalArgumentException("securityId required");
            if (side == null || side.isBlank()) throw new IllegalArgumentException("side required");
            if (quantity == null || quantity.signum() <= 0)
                throw new IllegalArgumentException("quantity must be positive");
            if (price == null || price.signum() <= 0)
                throw new IllegalArgumentException("price must be positive");
            if (executedAt == null) throw new IllegalArgumentException("executedAt required");
        }
    }

    public record Output(UUID tradeId, UUID lotId, UUID positionId) {}

    @Override public String name()   { return "RecordTrade"; }
    @Override public String domain() { return "investments"; }

    @Override
    public void precondition(Input input) {
        if (!ALLOWED_SIDES.contains(input.side())) {
            throw new PreconditionViolation(
                "side must be BUY (SELL is deferred to v3); got " + input.side());
        }
        Portfolio portfolio = portfolioRepo.findById(input.portfolioId())
            .orElseThrow(() -> new PreconditionViolation(
                "Portfolio not found: " + input.portfolioId()));
        if (!input.actorUserId().equals(portfolio.getUserId())) {
            throw new PreconditionViolation(
                "Actor " + input.actorUserId()
                + " does not own portfolio " + input.portfolioId());
        }
        if (!securityRepo.existsById(input.securityId())) {
            throw new PreconditionViolation(
                "Security not found: " + input.securityId());
        }
    }

    @Override
    public Output execute(Input input) {
        Portfolio portfolio = portfolioRepo.findById(input.portfolioId()).orElseThrow();
        Security  security  = securityRepo.findById(input.securityId()).orElseThrow();
        BigDecimal fees = input.fees() != null ? input.fees() : BigDecimal.ZERO;

        Trade trade = new Trade();
        trade.setPortfolio(portfolio);
        trade.setSecurity(security);
        trade.setSide(input.side());
        trade.setQuantity(input.quantity());
        trade.setPrice(input.price());
        trade.setFees(fees);
        trade.setExecutedAt(input.executedAt());
        Trade savedTrade = tradeRepo.save(trade);

        // Upsert Position: insert if no row yet, else recompute avg_cost.
        Optional<Position> existing = positionRepo
            .findByPortfolioIdAndSecurityId(portfolio.getId(), security.getId());
        Position position = existing.orElseGet(Position::new);
        if (existing.isEmpty()) {
            position.setPortfolio(portfolio);
            position.setSecurity(security);
            position.setQuantity(input.quantity());
            position.setAvgCost(input.price());
        } else {
            BigDecimal newQty = position.getQuantity().add(input.quantity());
            BigDecimal newCostBasis =
                position.getQuantity().multiply(position.getAvgCost())
                    .add(input.quantity().multiply(input.price()));
            position.setAvgCost(newQty.signum() == 0
                ? BigDecimal.ZERO
                : newCostBasis.divide(newQty, 4, RoundingMode.HALF_UP));
            position.setQuantity(newQty);
        }
        Position savedPosition = positionRepo.save(position);

        // Open a new lot for this BUY.
        Lot lot = new Lot();
        lot.setPosition(savedPosition);
        lot.setQuantity(input.quantity());
        lot.setCostBasis(input.price());
        lot.setAcquiredAt(input.executedAt());
        Lot savedLot = lotRepo.save(lot);

        return new Output(savedTrade.getId(), savedLot.getId(), savedPosition.getId());
    }

    @Override
    public void postcondition(Input input, Output output) {
        Trade trade = tradeRepo.findById(output.tradeId())
            .orElseThrow(() -> new PostconditionViolation(
                "Trade row not found after save: " + output.tradeId()));
        if (!input.side().equals(trade.getSide())) {
            throw new PostconditionViolation("trade.side not persisted as expected");
        }
        if (input.quantity().compareTo(trade.getQuantity()) != 0) {
            throw new PostconditionViolation("trade.quantity not persisted as expected");
        }

        Position position = positionRepo.findById(output.positionId())
            .orElseThrow(() -> new PostconditionViolation(
                "Position row not found after save: " + output.positionId()));

        // Ledger consistency: sum of open lots == position.quantity
        BigDecimal openLotsTotal = lotRepo
            .findByPositionIdAndClosedAtIsNullOrderByAcquiredAtAsc(position.getId())
            .stream()
            .map(Lot::getQuantity)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (openLotsTotal.compareTo(position.getQuantity()) != 0) {
            throw new PostconditionViolation(
                "Ledger drift: position.quantity=" + position.getQuantity()
                + " but sum of open lots=" + openLotsTotal);
        }
    }
}
