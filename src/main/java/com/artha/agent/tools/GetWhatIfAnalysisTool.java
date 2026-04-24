package com.artha.agent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.artha.agent.core.ArthaTool;
import com.artha.agent.core.FinancialTool;
import com.artha.agent.core.ReferenceDateProvider;
import com.artha.agent.core.ToolContext;
import com.artha.agent.core.ToolResult;
import com.artha.agent.domain.*;
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

/**
 * Tool: get_what_if_analysis — Phase 7A
 *
 * Counterfactual reasoning: "What happens to my goals if I change X?"
 *
 * Supports scenarios:
 *   - cut_spending:    reduce spending in a category by $N/month
 *   - eliminate_debt:  stop making payday loan / high-interest payments
 *   - increase_income: add $N/month to income
 *   - stop_fees:       eliminate overdraft/late fees
 *
 * For each scenario, shows:
 *   - monthly savings increase
 *   - how many months earlier each goal would be reached
 *   - annual dollar impact
 *   - difficulty rating (EASY / MEDIUM / HARD)
 */
@Slf4j
@ArthaTool(
    description = "Scenario modeling: what-if I cut spending in a category or eliminate debt",
    category    = "planning",
    version     = "1.0.0"
)
@Component
@RequiredArgsConstructor
public class GetWhatIfAnalysisTool implements FinancialTool {

    private final FinancialGoalRepository goalRepository;
    private final TransactionRepository   transactionRepository;
    private final ReferenceDateProvider   refDate;

    @Override
    public String getName() { return "get_what_if_analysis"; }

    @Override
    public Object getDefinition() {
        return Map.of(
            "name", getName(),
            "description", """
                Runs counterfactual "what if" scenarios to show how behavior changes
                would impact financial goals. For example:
                - "What if I cut dining spending by $200/month?"
                - "What if I stopped paying payday loans?"
                - "What if I earned $500 more per month?"
                Shows how many months earlier each goal would be reached and
                the annual dollar impact of the change.
                Use this when the user asks about trade-offs, wants motivation,
                or asks "what difference would it make if I..."
                """,
            "input_schema", Map.of(
                "type", "object",
                "properties", Map.of(
                    "scenario_type", Map.of(
                        "type",        "string",
                        "description", "Type of scenario: cut_spending, eliminate_debt, increase_income, stop_fees",
                        "enum",        List.of("cut_spending", "eliminate_debt", "increase_income", "stop_fees")
                    ),
                    "monthly_amount", Map.of(
                        "type",        "number",
                        "description", "Dollar amount of the monthly change (e.g. 200 for $200/month reduction)"
                    ),
                    "category_name", Map.of(
                        "type",        "string",
                        "description", "For cut_spending: which spending category to reduce (e.g. 'Dining')"
                    )
                ),
                "required", List.of("scenario_type", "monthly_amount")
            )
        );
    }

    @Override
    public ToolResult execute(JsonNode input, ToolContext context) {
        try {
            long startMs  = System.currentTimeMillis();
            UUID userUUID = UUID.fromString(context.userId());

            String scenarioType  = input.path("scenario_type").asText("cut_spending");
            BigDecimal monthlyDelta = BigDecimal.valueOf(
                input.path("monthly_amount").asDouble(0));
            String categoryName  = input.has("category_name")
                ? input.path("category_name").asText(null) : null;

            if (monthlyDelta.compareTo(BigDecimal.ZERO) <= 0) {
                return ToolResult.error("monthly_amount must be greater than 0");
            }

            // Current savings rate (last 30 days)
            Instant to   = refDate.now();
            Instant from = to.minus(30, ChronoUnit.DAYS);
            BigDecimal currentIncome  = orZero(transactionRepository.totalReceived(userUUID, from, to));
            BigDecimal currentSpend   = orZero(transactionRepository.totalSpent(userUUID, from, to));
            BigDecimal currentSavings = currentIncome.subtract(currentSpend);

            // New savings rate after scenario
            BigDecimal newMonthlySavings = switch (scenarioType) {
                case "cut_spending"    -> currentSavings.add(monthlyDelta);
                case "eliminate_debt"  -> currentSavings.add(monthlyDelta);
                case "increase_income" -> currentSavings.add(monthlyDelta);
                case "stop_fees"       -> currentSavings.add(monthlyDelta);
                default                -> currentSavings.add(monthlyDelta);
            };

            BigDecimal annualImpact = monthlyDelta.multiply(BigDecimal.valueOf(12));

            // Savings rate change
            BigDecimal currentRate = currentIncome.compareTo(BigDecimal.ZERO) > 0
                ? currentSavings.divide(currentIncome, 4, RoundingMode.HALF_UP)
                                .multiply(BigDecimal.valueOf(100))
                                .setScale(1, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;

            BigDecimal newIncome = scenarioType.equals("increase_income")
                ? currentIncome.add(monthlyDelta) : currentIncome;
            BigDecimal newRate = newIncome.compareTo(BigDecimal.ZERO) > 0
                ? newMonthlySavings.divide(newIncome, 4, RoundingMode.HALF_UP)
                                   .multiply(BigDecimal.valueOf(100))
                                   .setScale(1, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;

            // Project impact on each active goal
            List<FinancialGoal> goals = goalRepository.findByUserIdAndStatus(userUUID, "ACTIVE");
            List<Map<String, Object>> goalImpacts = new ArrayList<>();

            for (FinancialGoal goal : goals) {
                BigDecimal target    = goal.getTargetAmount();
                BigDecimal current   = orZero(goal.getCurrentAmount());
                BigDecimal remaining = target.subtract(current);

                if (remaining.compareTo(BigDecimal.ZERO) <= 0) continue;

                Map<String, Object> gi = new LinkedHashMap<>();
                gi.put("goal_name", goal.getName());
                gi.put("remaining", remaining);

                // Current timeline
                if (currentSavings.compareTo(BigDecimal.ZERO) > 0) {
                    long currentMonths = remaining.divide(currentSavings, 0, RoundingMode.CEILING).longValue();
                    LocalDate currentDate = refDate.today().plusMonths(currentMonths);
                    gi.put("current_completion_date",  currentDate.toString());
                    gi.put("current_months_remaining", currentMonths);

                    // New timeline
                    if (newMonthlySavings.compareTo(BigDecimal.ZERO) > 0) {
                        long newMonths = remaining.divide(newMonthlySavings, 0, RoundingMode.CEILING).longValue();
                        LocalDate newDate = refDate.today().plusMonths(newMonths);
                        long monthsSaved = currentMonths - newMonths;

                        gi.put("new_completion_date",  newDate.toString());
                        gi.put("new_months_remaining", newMonths);
                        gi.put("months_earlier",       Math.max(0, monthsSaved));

                        if (monthsSaved > 0) {
                            gi.put("impact_summary", String.format(
                                "Reach goal %d month%s earlier (%s instead of %s)",
                                monthsSaved, monthsSaved == 1 ? "" : "s",
                                newDate, currentDate));
                        } else {
                            gi.put("impact_summary", "Goal timeline unchanged — savings already sufficient.");
                        }
                    } else {
                        gi.put("impact_summary", "Still no positive savings after this change.");
                    }
                } else {
                    gi.put("current_completion_date", "Unknown — currently not saving");
                    if (newMonthlySavings.compareTo(BigDecimal.ZERO) > 0) {
                        long newMonths = remaining.divide(newMonthlySavings, 0, RoundingMode.CEILING).longValue();
                        gi.put("new_completion_date",  refDate.today().plusMonths(newMonths).toString());
                        gi.put("new_months_remaining", newMonths);
                        gi.put("impact_summary", String.format(
                            "This change would make the goal achievable in %d months!", newMonths));
                    }
                }
                goalImpacts.add(gi);
            }

            // Difficulty rating
            String difficulty = switch (scenarioType) {
                case "stop_fees"       -> "EASY";
                case "cut_spending"    -> monthlyDelta.compareTo(BigDecimal.valueOf(100)) <= 0 ? "EASY" : "MEDIUM";
                case "eliminate_debt"  -> "HARD";
                case "increase_income" -> "HARD";
                default                -> "MEDIUM";
            };

            // Human-readable scenario description
            String scenarioDesc = switch (scenarioType) {
                case "cut_spending"    -> "Reduce " + (categoryName != null ? categoryName : "spending")
                                         + " by $" + monthlyDelta.setScale(0, RoundingMode.HALF_UP) + "/month";
                case "eliminate_debt"  -> "Eliminate $" + monthlyDelta.setScale(0, RoundingMode.HALF_UP)
                                         + "/month in high-interest debt payments";
                case "increase_income" -> "Earn $" + monthlyDelta.setScale(0, RoundingMode.HALF_UP)
                                         + " more per month";
                case "stop_fees"       -> "Eliminate $" + monthlyDelta.setScale(0, RoundingMode.HALF_UP)
                                         + "/month in bank fees";
                default                -> "Save $" + monthlyDelta.setScale(0, RoundingMode.HALF_UP) + " more/month";
            };

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("scenario",             scenarioDesc);
            result.put("scenario_type",        scenarioType);
            result.put("monthly_improvement",  monthlyDelta);
            result.put("annual_impact",        annualImpact);
            result.put("difficulty",           difficulty);
            result.put("current_savings_rate", currentRate);
            result.put("new_savings_rate",     newRate);
            result.put("current_monthly_savings", currentSavings);
            result.put("new_monthly_savings",     newMonthlySavings);
            result.put("goal_impacts",         goalImpacts);
            return ToolResult.okWithTiming(result, startMs);

        } catch (Exception e) {
            log.error("GetWhatIfAnalysisTool error: {}", e.getMessage());
            return ToolResult.error("Failed to run what-if analysis: " + e.getMessage());
        }
    }

    private BigDecimal orZero(BigDecimal v) {
        return v != null ? v : BigDecimal.ZERO;
    }
}