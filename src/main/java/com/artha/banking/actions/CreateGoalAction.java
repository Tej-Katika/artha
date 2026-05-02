package com.artha.banking.actions;

import com.artha.banking.ontology.FinancialGoal;
import com.artha.banking.ontology.FinancialGoalRepository;
import com.artha.core.action.Action;
import com.artha.core.action.PostconditionViolation;
import com.artha.core.action.PreconditionViolation;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;

/**
 * Create a new {@link FinancialGoal} on behalf of the user.
 *
 * Per research/ONTOLOGY_V2_SPEC.md §4.7 this is one of the six v2
 * banking write Actions. Unlike RecategorizeTransaction it touches no
 * existing rows — the postcondition just verifies the new goal was
 * persisted with the requested fields and the standard initial state
 * (currentAmount = 0, status = ACTIVE).
 */
@Component
@RequiredArgsConstructor
public class CreateGoalAction
        implements Action<CreateGoalAction.Input, CreateGoalAction.Output> {

    /** Allowed values for {@link FinancialGoal#getGoalType()}. */
    public static final Set<String> ALLOWED_GOAL_TYPES =
        Set.of("SAVINGS", "DEBT_PAYOFF", "PURCHASE");

    private final FinancialGoalRepository goalRepo;

    public record Input(
        UUID       actorUserId,
        String     name,
        String     goalType,
        BigDecimal targetAmount,
        LocalDate  targetDate,            // optional
        BigDecimal monthlyContribution,   // optional
        String     notes                  // optional
    ) {
        public Input {
            if (actorUserId  == null) throw new IllegalArgumentException("actorUserId required");
            if (name         == null || name.isBlank())
                throw new IllegalArgumentException("name required");
            if (goalType     == null || goalType.isBlank())
                throw new IllegalArgumentException("goalType required");
            if (targetAmount == null)
                throw new IllegalArgumentException("targetAmount required");
        }
    }

    public record Output(UUID goalId) {}

    @Override public String name()   { return "CreateGoal"; }
    @Override public String domain() { return "banking"; }

    @Override
    public void precondition(Input input) {
        if (!ALLOWED_GOAL_TYPES.contains(input.goalType())) {
            throw new PreconditionViolation(
                "goalType must be one of " + ALLOWED_GOAL_TYPES
                + "; got " + input.goalType());
        }
        if (input.targetAmount().signum() <= 0) {
            throw new PreconditionViolation(
                "targetAmount must be positive; got " + input.targetAmount());
        }
        if (input.monthlyContribution() != null
                && input.monthlyContribution().signum() < 0) {
            throw new PreconditionViolation(
                "monthlyContribution may not be negative");
        }
        if (input.targetDate() != null
                && input.targetDate().isBefore(LocalDate.now())) {
            throw new PreconditionViolation(
                "targetDate may not be in the past: " + input.targetDate());
        }
    }

    @Override
    public Output execute(Input input) {
        FinancialGoal goal = new FinancialGoal();
        goal.setUserId(input.actorUserId());
        goal.setName(input.name());
        goal.setGoalType(input.goalType());
        goal.setTargetAmount(input.targetAmount());
        goal.setCurrentAmount(BigDecimal.ZERO);
        goal.setMonthlyContribution(input.monthlyContribution());
        goal.setTargetDate(input.targetDate());
        goal.setNotes(input.notes());
        goal.setStatus("ACTIVE");
        goal.setPriority(1);

        FinancialGoal saved = goalRepo.save(goal);
        return new Output(saved.getId());
    }

    @Override
    public void postcondition(Input input, Output output) {
        FinancialGoal saved = goalRepo.findById(output.goalId())
            .orElseThrow(() -> new PostconditionViolation(
                "Goal row not found after save: " + output.goalId()));

        if (!input.actorUserId().equals(saved.getUserId())) {
            throw new PostconditionViolation(
                "Goal user_id mismatch — expected " + input.actorUserId()
                + ", got " + saved.getUserId());
        }
        if (!input.name().equals(saved.getName())) {
            throw new PostconditionViolation(
                "Goal name not persisted as expected");
        }
        if (!input.goalType().equals(saved.getGoalType())) {
            throw new PostconditionViolation(
                "Goal type not persisted as expected");
        }
        if (input.targetAmount().compareTo(saved.getTargetAmount()) != 0) {
            throw new PostconditionViolation(
                "targetAmount not persisted as expected");
        }
        if (saved.getCurrentAmount() == null
                || saved.getCurrentAmount().signum() != 0) {
            throw new PostconditionViolation(
                "New goals must start at currentAmount = 0");
        }
        if (!"ACTIVE".equals(saved.getStatus())) {
            throw new PostconditionViolation(
                "New goals must start in ACTIVE status; got " + saved.getStatus());
        }
    }
}
