package com.finwise.agent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.finwise.agent.core.FinWiseTool;
import com.finwise.agent.core.FinancialTool;
import com.finwise.agent.core.ReferenceDateProvider;
import com.finwise.agent.core.ToolContext;
import com.finwise.agent.core.ToolResult;
import com.finwise.agent.domain.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@FinWiseTool(
    description = "Project realistic goal completion dates using actual savings rate with scenario analysis",
    category    = "ontology",
    version     = "1.0.0"
)
@Component
@RequiredArgsConstructor
public class GetGoalProjectionTool implements FinancialTool {

    private final FinancialGoalRepository goalRepository;
    private final TransactionRepository   transactionRepository;
    private final ReferenceDateProvider   refDate;

    @Override
    public String getName() { return "get_goal_projection"; }

    @Override
    public Object getDefinition() {
        return Map.of(
            "name", getName(),
            "description", """
                Projects when the user will reach their financial goals based on
                actual recent savings rate — not just the stated monthly contribution.
                Compares the planned contribution vs actual average monthly savings,
                shows best/worst/expected case scenarios, and flags if goals are at risk.
                Use this when the user asks when they'll reach a goal, if they're on track,
                or wants a realistic projection of their financial future.
                """,
            "input_schema", Map.of(
                "type",       "object",
                "properties", Map.of(
                    "goal_name", Map.of(
                        "type",        "string",
                        "description", "Optional: project a specific goal by name"
                    ),
                    "months_history", Map.of(
                        "type",        "integer",
                        "description", "Months of history to use for actual savings rate (default: 3)"
                    )
                ),
                "required", List.of()
            )
        );
    }

    @Override
    public ToolResult execute(JsonNode input, ToolContext context) {
        try {
            long start    = System.currentTimeMillis();
            UUID userUUID = UUID.fromString(context.userId());

            String goalNameFilter = input.has("goal_name")
                ? input.get("goal_name").asText(null) : null;
            int monthsHistory = input.has("months_history")
                ? input.get("months_history").asInt(3) : 3;

            BigDecimal actualMonthlySavings =
                computeActualMonthlySavings(userUUID, monthsHistory);

            List<FinancialGoal> goals =
                goalRepository.findByUserIdAndStatus(userUUID, "ACTIVE");

            if (goals.isEmpty()) {
                return ToolResult.okWithTiming(Map.of(
                    "message",                "No active financial goals found.",
                    "actual_monthly_savings", actualMonthlySavings
                ), start);
            }

            LocalDate today = refDate.today();

            List<Map<String, Object>> projections = goals.stream()
                .filter(g -> goalNameFilter == null
                    || g.getName().toLowerCase().contains(goalNameFilter.toLowerCase()))
                .map(goal -> {
                    BigDecimal target    = goal.getTargetAmount();
                    BigDecimal current   = goal.getCurrentAmount() != null
                        ? goal.getCurrentAmount() : BigDecimal.ZERO;
                    BigDecimal remaining = target.subtract(current);

                    Map<String, Object> projection = new LinkedHashMap<>();
                    projection.put("goal_name",              goal.getName());
                    projection.put("target_amount",          target);
                    projection.put("current_amount",         current);
                    projection.put("remaining_amount",       remaining);
                    projection.put("actual_monthly_savings", actualMonthlySavings);

                    if (remaining.compareTo(BigDecimal.ZERO) <= 0) {
                        projection.put("status",  "ACHIEVED");
                        projection.put("message", "Goal already reached!");
                        return projection;
                    }

                    // ── Planned projection ────────────────────────
                    if (goal.getMonthlyContribution() != null
                            && goal.getMonthlyContribution().compareTo(BigDecimal.ZERO) > 0) {

                        double plannedMonths = remaining
                            .divide(goal.getMonthlyContribution(), 4, RoundingMode.CEILING)
                            .doubleValue();
                        LocalDate plannedDate = today.plusMonths((long) Math.ceil(plannedMonths));

                        projection.put("planned_monthly_contribution", goal.getMonthlyContribution());
                        projection.put("planned_months_to_goal",       (long) Math.ceil(plannedMonths));
                        projection.put("planned_completion_date",      plannedDate.toString());
                    }

                    // ── Actual projection ─────────────────────────
                    if (actualMonthlySavings.compareTo(BigDecimal.ZERO) > 0) {
                        double actualMonths = remaining
                            .divide(actualMonthlySavings, 4, RoundingMode.CEILING)
                            .doubleValue();
                        LocalDate actualDate = today.plusMonths((long) Math.ceil(actualMonths));

                        projection.put("actual_months_to_goal",  (long) Math.ceil(actualMonths));
                        projection.put("actual_completion_date", actualDate.toString());

                        // Best case: +20% savings
                        double bestMonths = remaining.divide(
                            actualMonthlySavings.multiply(BigDecimal.valueOf(1.2)),
                            4, RoundingMode.CEILING).doubleValue();
                        projection.put("best_case_date",
                            today.plusMonths((long) Math.ceil(bestMonths)).toString());

                        // Worst case: -20% savings
                        BigDecimal worstSavings = actualMonthlySavings
                            .multiply(BigDecimal.valueOf(0.8));
                        if (worstSavings.compareTo(BigDecimal.ZERO) > 0) {
                            double worstMonths = remaining
                                .divide(worstSavings, 4, RoundingMode.CEILING).doubleValue();
                            projection.put("worst_case_date",
                                today.plusMonths((long) Math.ceil(worstMonths)).toString());
                        }
                    } else {
                        projection.put("warning",
                            "No positive savings detected in the last " + monthsHistory
                            + " months. Spending exceeds income — goal may not be reachable at current rate.");
                    }

                    // ── On-track assessment ───────────────────────
                    if (goal.getTargetDate() != null && goal.getMonthlyContribution() != null) {
                        long daysLeft = ChronoUnit.DAYS.between(today, goal.getTargetDate());
                        double monthsLeft = daysLeft / 30.0;

                        BigDecimal projectedAtDeadline = current.add(
                            actualMonthlySavings.multiply(BigDecimal.valueOf(monthsLeft)));
                        boolean onTrack = projectedAtDeadline.compareTo(target) >= 0;

                        projection.put("target_date", goal.getTargetDate().toString());
                        projection.put("on_track",    onTrack);

                        if (!onTrack) {
                            BigDecimal shortfall = target.subtract(projectedAtDeadline);
                            BigDecimal neededMonthly = daysLeft > 0
                                ? remaining.divide(BigDecimal.valueOf(monthsLeft), 2, RoundingMode.CEILING)
                                : remaining;
                            projection.put("projected_shortfall",          shortfall);
                            projection.put("monthly_needed_to_hit_target", neededMonthly);
                        }
                    }

                    return projection;
                })
                .collect(Collectors.toList());

            return ToolResult.okWithTiming(Map.of(
                "projections",            projections,
                "actual_monthly_savings", actualMonthlySavings,
                "months_analyzed",        monthsHistory
            ), start);

        } catch (Exception e) {
            log.error("GetGoalProjectionTool error: {}", e.getMessage());
            return ToolResult.error("Failed to project goals: " + e.getMessage());
        }
    }

    private BigDecimal computeActualMonthlySavings(UUID userId, int months) {
        try {
            Instant to   = refDate.now();
            Instant from = to.minusSeconds((long) months * 30 * 86400);

            BigDecimal income   = transactionRepository.totalReceived(userId, from, to);
            BigDecimal spending = transactionRepository.totalSpent(userId, from, to);

            return income.subtract(spending)
                .divide(BigDecimal.valueOf(months), 2, RoundingMode.HALF_UP);
        } catch (Exception e) {
            log.warn("Could not compute actual savings rate: {}", e.getMessage());
            return BigDecimal.ZERO;
        }
    }
}