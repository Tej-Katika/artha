package com.artha.banking.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.artha.core.agent.ArthaTool;
import com.artha.core.agent.FinancialTool;
import com.artha.core.ReferenceDateProvider;
import com.artha.core.agent.ToolContext;
import com.artha.core.agent.ToolResult;
import com.artha.banking.ontology.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@ArthaTool(
    description = "Get progress toward financial goals with projected completion dates",
    category    = "ontology",
    version     = "1.0.0"
)
@Component
@RequiredArgsConstructor
public class GetGoalProgressTool implements FinancialTool {

    private final FinancialGoalRepository goalRepository;
    private final ReferenceDateProvider   refDate;

    @Override
    public String getName() { return "get_goal_progress"; }

    @Override
    public Object getDefinition() {
        return Map.of(
            "name", getName(),
            "description", """
                Get progress toward financial goals such as emergency funds,
                savings targets, or debt payoff plans.
                Shows current amount saved, target amount, progress percentage,
                projected completion date, and monthly contribution needed.
                Use this when the user asks about savings goals, financial targets,
                how long until they reach a goal, or their savings progress.
                """,
            "input_schema", Map.of(
                "type", "object",
                "properties", Map.of(
                    "goal_name", Map.of(
                        "type",        "string",
                        "description", "Optional: name of a specific goal to check"
                    ),
                    "status", Map.of(
                        "type",        "string",
                        "description", "Filter by status: ACTIVE, ACHIEVED, PAUSED (default: ACTIVE)"
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

            String statusFilter   = input.has("status")
                ? input.get("status").asText("ACTIVE") : "ACTIVE";
            String goalNameFilter = input.has("goal_name")
                ? input.get("goal_name").asText(null) : null;

            List<FinancialGoal> goals =
                goalRepository.findByUserIdAndStatus(userUUID, statusFilter);

            if (goals.isEmpty()) {
                return ToolResult.okWithTiming(Map.of(
                    "message", "No " + statusFilter.toLowerCase() + " financial goals found.",
                    "goals",   List.of(),
                    "tip",     "Set a financial goal like an emergency fund or vacation savings to track your progress."
                ), start);
            }

            LocalDate today = refDate.today();

            List<Map<String, Object>> goalSummaries = goals.stream()
                .filter(g -> goalNameFilter == null
                    || g.getName().toLowerCase().contains(goalNameFilter.toLowerCase()))
                .map(goal -> {
                    BigDecimal target    = goal.getTargetAmount();
                    BigDecimal current   = goal.getCurrentAmount() != null
                        ? goal.getCurrentAmount() : BigDecimal.ZERO;
                    BigDecimal remaining = target.subtract(current);

                    BigDecimal progressPct = target.compareTo(BigDecimal.ZERO) > 0
                        ? current.divide(target, 4, RoundingMode.HALF_UP)
                                 .multiply(BigDecimal.valueOf(100))
                                 .setScale(1, RoundingMode.HALF_UP)
                        : BigDecimal.ZERO;

                    Map<String, Object> summary = new LinkedHashMap<>();
                    summary.put("goal_name",       goal.getName());
                    summary.put("goal_type",        goal.getGoalType());
                    summary.put("target_amount",    target);
                    summary.put("current_amount",   current);
                    summary.put("remaining_amount", remaining);
                    summary.put("progress_pct",     progressPct);
                    summary.put("status",           goal.getStatus());
                    summary.put("priority",         goal.getPriority());

                    if (goal.getMonthlyContribution() != null
                            && goal.getMonthlyContribution().compareTo(BigDecimal.ZERO) > 0
                            && remaining.compareTo(BigDecimal.ZERO) > 0) {

                        double monthsNeeded = remaining
                            .divide(goal.getMonthlyContribution(), 4, RoundingMode.CEILING)
                            .doubleValue();
                        LocalDate projectedDate = today.plusMonths((long) Math.ceil(monthsNeeded));

                        summary.put("months_to_goal",         (long) Math.ceil(monthsNeeded));
                        summary.put("projected_date",          projectedDate.toString());
                        summary.put("monthly_contribution",    goal.getMonthlyContribution());
                    }

                    if (goal.getTargetDate() != null) {
                        long daysLeft = ChronoUnit.DAYS.between(today, goal.getTargetDate());
                        summary.put("target_date",    goal.getTargetDate().toString());
                        summary.put("days_remaining", daysLeft);

                        if (goal.getMonthlyContribution() != null && daysLeft > 0) {
                            double monthsLeft = daysLeft / 30.0;
                            BigDecimal projectedTotal = current.add(
                                goal.getMonthlyContribution()
                                    .multiply(BigDecimal.valueOf(monthsLeft)));
                            summary.put("on_track", projectedTotal.compareTo(target) >= 0);
                        }
                    }

                    return summary;
                })
                .collect(Collectors.toList());

            goalSummaries.sort(Comparator.comparingInt(g -> (Integer) g.get("priority")));

            BigDecimal totalTargets = goals.stream()
                .map(FinancialGoal::getTargetAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

            BigDecimal totalSaved = goals.stream()
                .map(g -> g.getCurrentAmount() != null ? g.getCurrentAmount() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

            return ToolResult.okWithTiming(Map.of(
                "goals",         goalSummaries,
                "goal_count",    goalSummaries.size(),
                "total_targets", totalTargets,
                "total_saved",   totalSaved
            ), start);

        } catch (Exception e) {
            log.error("GetGoalProgressTool error: {}", e.getMessage());
            return ToolResult.error("Failed to get goal progress: " + e.getMessage());
        }
    }
}