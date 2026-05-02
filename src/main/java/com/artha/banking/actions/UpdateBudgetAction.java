package com.artha.banking.actions;

import com.artha.banking.ontology.Budget;
import com.artha.banking.ontology.BudgetRepository;
import com.artha.core.action.Action;
import com.artha.core.action.PostconditionViolation;
import com.artha.core.action.PreconditionViolation;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Adjust the monthly limit on an existing {@link Budget}.
 *
 * Per research/ONTOLOGY_V2_SPEC.md §4.7. The action only touches the
 * {@code monthly_limit} column — rollover, effective dates, and
 * category linkage are out of scope. Future actions can be added for
 * those concerns once usage patterns warrant them.
 */
@Component
@RequiredArgsConstructor
public class UpdateBudgetAction
        implements Action<UpdateBudgetAction.Input, UpdateBudgetAction.Output> {

    private final BudgetRepository budgetRepo;

    public record Input(UUID budgetId, BigDecimal newMonthlyLimit, UUID actorUserId) {
        public Input {
            if (budgetId         == null) throw new IllegalArgumentException("budgetId required");
            if (newMonthlyLimit  == null) throw new IllegalArgumentException("newMonthlyLimit required");
            if (actorUserId      == null) throw new IllegalArgumentException("actorUserId required");
        }
    }

    public record Output(UUID budgetId, BigDecimal previousMonthlyLimit) {}

    @Override public String name()   { return "UpdateBudget"; }
    @Override public String domain() { return "banking"; }

    @Override
    public void precondition(Input input) {
        Budget b = budgetRepo.findById(input.budgetId())
            .orElseThrow(() -> new PreconditionViolation(
                "Budget not found: " + input.budgetId()));

        if (!b.getUserId().equals(input.actorUserId())) {
            throw new PreconditionViolation(
                "Actor " + input.actorUserId()
                + " does not own budget " + input.budgetId());
        }
        if (input.newMonthlyLimit().signum() <= 0) {
            throw new PreconditionViolation(
                "newMonthlyLimit must be positive; got " + input.newMonthlyLimit());
        }
    }

    @Override
    public Output execute(Input input) {
        Budget b = budgetRepo.findById(input.budgetId())
            .orElseThrow(() -> new IllegalStateException(
                "Budget vanished between precondition and execute: "
                + input.budgetId()));

        BigDecimal previous = b.getMonthlyLimit();
        b.setMonthlyLimit(input.newMonthlyLimit());
        budgetRepo.save(b);
        return new Output(b.getId(), previous);
    }

    @Override
    public void postcondition(Input input, Output output) {
        Budget fresh = budgetRepo.findById(input.budgetId())
            .orElseThrow(() -> new PostconditionViolation(
                "Budget row missing after update: " + input.budgetId()));

        if (input.newMonthlyLimit().compareTo(fresh.getMonthlyLimit()) != 0) {
            throw new PostconditionViolation(
                "monthly_limit not persisted — expected "
                + input.newMonthlyLimit() + ", got " + fresh.getMonthlyLimit());
        }
    }
}
