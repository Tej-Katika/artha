package com.artha.agent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.artha.agent.core.ArthaTool;
import com.artha.agent.core.FinancialTool;
import com.artha.agent.core.ToolContext;
import com.artha.agent.core.ToolResult;
import com.artha.agent.domain.*;
import com.artha.agent.enrichment.GoalImpactEnrichmentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Tool: get_goal_impact — Phase 7A
 *
 * Shows how the user's actual transactions are helping or hurting
 * each of their financial goals. Answers questions like:
 *   "Why am I not making progress on my emergency fund?"
 *   "What's eating into my savings?"
 *   "Are my spending habits aligned with my goals?"
 */
@Slf4j
@ArthaTool(
    description = "Analyze how spending categories are helping or hurting each financial goal",
    category    = "planning",
    version     = "1.0.0"
)
@Component
@RequiredArgsConstructor
public class GetGoalImpactTool implements FinancialTool {

    private final GoalImpactEnrichmentService impactService;
    private final FinancialGoalRepository     goalRepository;
    private final TransactionRepository       transactionRepository;

    @Override
    public String getName() { return "get_goal_impact"; }

    @Override
    public Object getDefinition() {
        return Map.of(
            "name", getName(),
            "description", """
                Analyzes how the user's actual transactions are helping or hurting
                each of their financial goals. For each goal, shows:
                - assessment (ON_TRACK / DEBT_CONSTRAINED / LEAKING / STALLED)
                - which transactions are directly funding the goal
                - which transactions are draining savings capacity (debt, fees)
                - discretionary spending competing with the goal
                - net impact score for the period
                Use this when the user asks why they're not making progress,
                what's hurting their savings, or how their spending aligns with goals.
                """,
            "input_schema", Map.of(
                "type", "object",
                "properties", Map.of(
                    "days_back", Map.of(
                        "type",        "integer",
                        "description", "How many days to analyze. Default 30. Max 90."
                    ),
                    "goal_name", Map.of(
                        "type",        "string",
                        "description", "Optional: focus on a specific goal by name."
                    )
                ),
                "required", List.of()
            )
        );
    }

    @Override
    public ToolResult execute(JsonNode input, ToolContext context) {
        try {
            long startMs = System.currentTimeMillis();
            UUID userUUID = UUID.fromString(context.userId());

            int daysBack = 30;
            if (input.has("days_back") && !input.get("days_back").isNull()) {
                daysBack = Math.min(input.get("days_back").asInt(30), 90);
            }
            String goalFilter = input.has("goal_name") && !input.get("goal_name").isNull()
                ? input.get("goal_name").asText(null) : null;

            // Get goal impacts from enrichment service
            List<Map<String, Object>> impacts =
                impactService.analyzeGoalImpacts(userUUID, daysBack);

            if (impacts.isEmpty()) {
                // Check why — no goals or no transactions?
                List<FinancialGoal> goals =
                    goalRepository.findByUserIdAndStatus(userUUID, "ACTIVE");
                if (goals.isEmpty()) {
                    return ToolResult.ok(Map.of(
                        "message", "No active financial goals found.",
                        "tip",     "Set up financial goals to see how your spending aligns with them."
                    ));
                }
                return ToolResult.ok(Map.of(
                    "message", "No transactions found in the last " + daysBack + " days."
                ));
            }

            // Apply goal filter if specified
            if (goalFilter != null) {
                String filter = goalFilter.toLowerCase();
                impacts = impacts.stream()
                    .filter(i -> ((String) i.get("goal_name")).toLowerCase().contains(filter))
                    .toList();
            }

            // Build summary across all goals
            long constrainedCount = impacts.stream()
                .filter(i -> "DEBT_CONSTRAINED".equals(i.get("assessment"))
                          || "LEAKING".equals(i.get("assessment")))
                .count();

            long onTrackCount = impacts.stream()
                .filter(i -> "ON_TRACK".equals(i.get("assessment")))
                .count();

            BigDecimal totalDebtDrain = impacts.stream()
                .map(i -> (BigDecimal) i.get("debt_drain"))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

            BigDecimal totalCashLeaks = impacts.stream()
                .map(i -> (BigDecimal) i.get("cash_leaks"))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

            // Overall health signal
            String overallSignal;
            String overallInsight;
            if (totalDebtDrain.compareTo(BigDecimal.valueOf(200)) > 0) {
                overallSignal = "DEBT_CONSTRAINING_GOALS";
                overallInsight = String.format(
                    "$%.0f/month going to high-interest debt is preventing goal progress. " +
                    "Eliminating these payments would be the single biggest improvement.",
                    totalDebtDrain);
            } else if (totalCashLeaks.compareTo(BigDecimal.valueOf(50)) > 0) {
                overallSignal = "FEES_REDUCING_PROGRESS";
                overallInsight = String.format(
                    "$%.0f in avoidable fees (overdrafts, late fees) are reducing savings capacity.",
                    totalCashLeaks);
            } else if (onTrackCount == impacts.size()) {
                overallSignal = "ALL_GOALS_ON_TRACK";
                overallInsight = "Spending patterns are well-aligned with your financial goals.";
            } else {
                overallSignal = "MIXED";
                overallInsight = onTrackCount + " of " + impacts.size()
                    + " goals are on track. Review the constrained goals below.";
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("period_days",      daysBack);
            result.put("goals_analyzed",   impacts.size());
            result.put("on_track_count",   onTrackCount);
            result.put("constrained_count",constrainedCount);
            result.put("total_debt_drain", totalDebtDrain);
            result.put("total_cash_leaks", totalCashLeaks);
            result.put("overall_signal",   overallSignal);
            result.put("overall_insight",  overallInsight);
            result.put("goal_impacts",     impacts);
            return ToolResult.okWithTiming(result, startMs);

        } catch (Exception e) {
            log.error("GetGoalImpactTool error: {}", e.getMessage());
            return ToolResult.error("Failed to analyze goal impacts: " + e.getMessage());
        }
    }
}