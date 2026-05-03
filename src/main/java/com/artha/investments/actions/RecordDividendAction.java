package com.artha.investments.actions;

import com.artha.core.action.Action;
import com.artha.core.action.PostconditionViolation;
import com.artha.core.action.PreconditionViolation;
import com.artha.investments.ontology.Dividend;
import com.artha.investments.ontology.DividendRepository;
import com.artha.investments.ontology.Position;
import com.artha.investments.ontology.PositionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Record a cash dividend paid on a position.
 *
 * Append-only: every event is one new {@link Dividend} row. No
 * existing rows are mutated. Ownership precondition checks the
 * position's portfolio belongs to the actor.
 */
@Component
@RequiredArgsConstructor
public class RecordDividendAction
        implements Action<RecordDividendAction.Input, RecordDividendAction.Output> {

    private final PositionRepository  positionRepo;
    private final DividendRepository  dividendRepo;

    public record Input(
        UUID       actorUserId,
        UUID       positionId,
        BigDecimal amount,
        String     currency,    // optional; defaults to USD
        LocalDate  exDate,
        Instant    paidAt
    ) {
        public Input {
            if (actorUserId == null) throw new IllegalArgumentException("actorUserId required");
            if (positionId  == null) throw new IllegalArgumentException("positionId required");
            if (amount == null || amount.signum() <= 0)
                throw new IllegalArgumentException("amount must be positive");
            if (exDate  == null) throw new IllegalArgumentException("exDate required");
            if (paidAt  == null) throw new IllegalArgumentException("paidAt required");
        }
    }

    public record Output(UUID dividendId) {}

    @Override public String name()   { return "RecordDividend"; }
    @Override public String domain() { return "investments"; }

    @Override
    public void precondition(Input input) {
        Position position = positionRepo.findById(input.positionId())
            .orElseThrow(() -> new PreconditionViolation(
                "Position not found: " + input.positionId()));
        if (!input.actorUserId().equals(position.getPortfolio().getUserId())) {
            throw new PreconditionViolation(
                "Actor " + input.actorUserId()
                + " does not own portfolio for position " + input.positionId());
        }
    }

    @Override
    public Output execute(Input input) {
        Position position = positionRepo.findById(input.positionId()).orElseThrow();
        Dividend d = new Dividend();
        d.setPosition(position);
        d.setAmount(input.amount());
        d.setCurrency(input.currency() != null ? input.currency() : "USD");
        d.setExDate(input.exDate());
        d.setPaidAt(input.paidAt());
        return new Output(dividendRepo.save(d).getId());
    }

    @Override
    public void postcondition(Input input, Output output) {
        Dividend saved = dividendRepo.findById(output.dividendId())
            .orElseThrow(() -> new PostconditionViolation(
                "Dividend row not found after save: " + output.dividendId()));
        if (input.amount().compareTo(saved.getAmount()) != 0) {
            throw new PostconditionViolation("amount not persisted as expected");
        }
        if (!input.exDate().equals(saved.getExDate())) {
            throw new PostconditionViolation("exDate not persisted as expected");
        }
    }
}
