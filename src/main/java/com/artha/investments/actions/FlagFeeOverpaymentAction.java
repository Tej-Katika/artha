package com.artha.investments.actions;

import com.artha.core.action.Action;
import com.artha.core.action.PostconditionViolation;
import com.artha.core.action.PreconditionViolation;
import com.artha.investments.ontology.Fee;
import com.artha.investments.ontology.FeeRepository;
import com.artha.investments.ontology.Portfolio;
import com.artha.investments.ontology.PortfolioRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;

/**
 * Record a fee that the agent has flagged as a likely overpayment.
 *
 * "Annotation write": appends a new {@link Fee} row tagged with the
 * caller-supplied notes. Existing fee rows are never modified — the
 * audit trail of what the agent flagged stays intact even when the
 * underlying ground truth changes.
 */
@Component
@RequiredArgsConstructor
public class FlagFeeOverpaymentAction
        implements Action<FlagFeeOverpaymentAction.Input, FlagFeeOverpaymentAction.Output> {

    public static final Set<String> ALLOWED_KINDS =
        Set.of("ADVISORY", "EXPENSE_RATIO", "COMMISSION", "SLIPPAGE");

    private final PortfolioRepository portfolioRepo;
    private final FeeRepository       feeRepo;

    public record Input(
        UUID       actorUserId,
        UUID       portfolioId,
        String     kind,
        BigDecimal amount,
        LocalDate  periodStart,
        LocalDate  periodEnd,
        String     notes
    ) {
        public Input {
            if (actorUserId == null) throw new IllegalArgumentException("actorUserId required");
            if (portfolioId == null) throw new IllegalArgumentException("portfolioId required");
            if (kind == null || kind.isBlank())
                throw new IllegalArgumentException("kind required");
            if (amount == null || amount.signum() <= 0)
                throw new IllegalArgumentException("amount must be positive");
            if (periodStart == null) throw new IllegalArgumentException("periodStart required");
            if (periodEnd   == null) throw new IllegalArgumentException("periodEnd required");
            if (periodEnd.isBefore(periodStart))
                throw new IllegalArgumentException("periodEnd may not precede periodStart");
        }
    }

    public record Output(UUID feeId) {}

    @Override public String name()   { return "FlagFeeOverpayment"; }
    @Override public String domain() { return "investments"; }

    @Override
    public void precondition(Input input) {
        if (!ALLOWED_KINDS.contains(input.kind())) {
            throw new PreconditionViolation(
                "kind must be one of " + ALLOWED_KINDS + "; got " + input.kind());
        }
        Portfolio portfolio = portfolioRepo.findById(input.portfolioId())
            .orElseThrow(() -> new PreconditionViolation(
                "Portfolio not found: " + input.portfolioId()));
        if (!input.actorUserId().equals(portfolio.getUserId())) {
            throw new PreconditionViolation(
                "Actor " + input.actorUserId()
                + " does not own portfolio " + input.portfolioId());
        }
    }

    @Override
    public Output execute(Input input) {
        Portfolio portfolio = portfolioRepo.findById(input.portfolioId()).orElseThrow();
        Fee fee = new Fee();
        fee.setPortfolio(portfolio);
        fee.setKind(input.kind());
        fee.setAmount(input.amount());
        fee.setPeriodStart(input.periodStart());
        fee.setPeriodEnd(input.periodEnd());
        String notes = input.notes() != null ? input.notes() : "";
        fee.setNotes("AGENT_FLAGGED: " + notes);
        return new Output(feeRepo.save(fee).getId());
    }

    @Override
    public void postcondition(Input input, Output output) {
        Fee saved = feeRepo.findById(output.feeId())
            .orElseThrow(() -> new PostconditionViolation(
                "Fee row not found after save: " + output.feeId()));
        if (!input.kind().equals(saved.getKind())) {
            throw new PostconditionViolation("kind not persisted as expected");
        }
        if (input.amount().compareTo(saved.getAmount()) != 0) {
            throw new PostconditionViolation("amount not persisted as expected");
        }
        if (saved.getNotes() == null || !saved.getNotes().startsWith("AGENT_FLAGGED:")) {
            throw new PostconditionViolation(
                "notes must be prefixed AGENT_FLAGGED: for downstream audit");
        }
    }
}
