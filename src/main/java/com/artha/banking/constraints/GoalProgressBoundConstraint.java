package com.artha.banking.constraints;

import com.artha.banking.ontology.FinancialGoal;
import com.artha.banking.ontology.FinancialGoalRepository;
import com.artha.core.constraint.Constraint;
import com.artha.core.constraint.ConstraintGrade;
import com.artha.core.constraint.ConstraintResult;
import com.artha.core.constraint.EvaluationContext;
import com.artha.core.constraint.FactualClaim;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

/**
 * HARD ontology-integrity check: every active goal for the user
 * must have {@code current_amount ∈ [0, target_amount]}.
 *
 * Per research/ONTOLOGY_V2_SPEC.md §6.5. Pure ontology check — does
 * not consult agent claims. A violation indicates a data corruption
 * upstream (a misbehaving Action, a manual SQL update, a bug in
 * goal-progress tracking). Treating it as HARD forces the agent to
 * abandon the response rather than reason over invalid state.
 */
@Component
@RequiredArgsConstructor
public class GoalProgressBoundConstraint implements Constraint {

    private final FinancialGoalRepository goalRepo;

    @Override public String name()              { return "GoalProgressBound"; }
    @Override public String domain()            { return "banking"; }
    @Override public ConstraintGrade grade()    { return ConstraintGrade.HARD; }
    @Override public String repairHintTemplate() {
        return "Goal progress is out of [0, target] range — data is invalid; "
             + "abort the response and surface the integrity issue to the user.";
    }

    @Override
    public ConstraintResult evaluate(EvaluationContext ctx, Set<FactualClaim> claims) {
        List<FinancialGoal> goals =
            goalRepo.findByUserIdOrderByPriorityAsc(ctx.userId());

        for (FinancialGoal g : goals) {
            BigDecimal current = g.getCurrentAmount();
            BigDecimal target  = g.getTargetAmount();
            if (current == null || target == null) continue;

            if (current.signum() < 0) {
                return new ConstraintResult.Violated(
                    "Goal " + g.getId() + " (" + g.getName()
                    + ") has negative currentAmount: " + current,
                    repairHintTemplate());
            }
            if (current.compareTo(target) > 0) {
                return new ConstraintResult.Violated(
                    "Goal " + g.getId() + " (" + g.getName()
                    + ") currentAmount " + current
                    + " exceeds target " + target,
                    repairHintTemplate());
            }
        }
        return ConstraintResult.Satisfied.INSTANCE;
    }
}
