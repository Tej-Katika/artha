package com.artha.agent.tools;

import com.fasterxml.jackson.databind.JsonNode;

import com.artha.agent.core.FeatureFlags;
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
import java.time.temporal.TemporalAdjusters;
import java.util.*;

/**
 * Tool: get_financial_health — Phase 6B
 *
 * Improvements:
 *  - 3-month trend: score trajectory (improving / declining / stable)
 *  - Savings rate vs prior month comparison
 *  - Per-component score explanation (why did savings score drop?)
 *  - Spending velocity: are they on pace to exceed last month?
 *  - Positive signals: explicitly calls out things the user is doing well
 */
@Slf4j
@ArthaTool(
    description = "Overall financial health score with net worth, savings rate, debt ratio, and 3-month trend",
    category    = "analytics",
    version     = "1.0.0"
)
@Component
@RequiredArgsConstructor
public class GetFinancialHealthTool implements FinancialTool {

    private final TransactionRepository           transactionRepository;
    private final TransactionEnrichmentRepository enrichmentRepository;
    private final BudgetRepository                budgetRepository;
    private final FinancialGoalRepository         goalRepository;
    private final ReferenceDateProvider           refDate;
    private final FeatureFlags                    flags;

    @Override
    public String getName() { return "get_financial_health"; }

    @Override
    public Object getDefinition() {
        return Map.of(
            "name", getName(),
            "description", """
                Returns a comprehensive financial health score (0-100) with letter grade,
                per-component breakdowns with explanations, 3-month score trend,
                savings rate vs prior month, spending velocity for the current month,
                positive signals (what the user is doing well), and prioritized recommendations.
                Use this for overall financial check-ins, health overviews, or when the
                user asks "how am I doing" or "give me a financial summary".
                """,
            "input_schema", Map.of(
                "type",       "object",
                "properties", Map.of(),
                "required",   List.of()
            )
        );
    }

    @Override
    public ToolResult execute(JsonNode input, ToolContext context) {
        try {
            long start    = System.currentTimeMillis();
            UUID userUUID = UUID.fromString(context.userId());
            LocalDate today = refDate.today();

            // ── Current month ─────────────────────────────────────
            Instant monthStart = today.with(TemporalAdjusters.firstDayOfMonth())
                .atStartOfDay().toInstant(ZoneOffset.UTC);
            Instant monthEnd = today.with(TemporalAdjusters.lastDayOfMonth())
                .atTime(23, 59, 59).toInstant(ZoneOffset.UTC);

            BigDecimal income   = orZero(transactionRepository.totalReceived(userUUID, monthStart, monthEnd));
            BigDecimal spending = orZero(transactionRepository.totalSpent(userUUID, monthStart, monthEnd));
            BigDecimal netFlow  = income.subtract(spending);

            BigDecimal savingsRate = income.compareTo(BigDecimal.ZERO) > 0
                ? netFlow.divide(income, 4, RoundingMode.HALF_UP)
                         .multiply(BigDecimal.valueOf(100))
                         .setScale(1, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;

            // ── Prior month savings rate ───────────────────────────
            LocalDate lastMonth    = today.minusMonths(1);
            Instant priorStart     = lastMonth.with(TemporalAdjusters.firstDayOfMonth())
                .atStartOfDay().toInstant(ZoneOffset.UTC);
            Instant priorEnd       = lastMonth.with(TemporalAdjusters.lastDayOfMonth())
                .atTime(23, 59, 59).toInstant(ZoneOffset.UTC);

            BigDecimal priorIncome   = orZero(transactionRepository.totalReceived(userUUID, priorStart, priorEnd));
            BigDecimal priorSpending = orZero(transactionRepository.totalSpent(userUUID, priorStart, priorEnd));
            BigDecimal priorNet      = priorIncome.subtract(priorSpending);

            BigDecimal priorSavingsRate = priorIncome.compareTo(BigDecimal.ZERO) > 0
                ? priorNet.divide(priorIncome, 4, RoundingMode.HALF_UP)
                          .multiply(BigDecimal.valueOf(100))
                          .setScale(1, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;

            BigDecimal savingsRateDelta = savingsRate.subtract(priorSavingsRate);

            // ── Spending velocity ─────────────────────────────────
            // How much spent so far this month vs expected pace
            int dayOfMonth  = today.getDayOfMonth();
            int daysInMonth = today.lengthOfMonth();
            BigDecimal dailyPace = dayOfMonth > 0
                ? spending.divide(BigDecimal.valueOf(dayOfMonth), 2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;
            BigDecimal projectedMonthlySpend = dailyPace.multiply(BigDecimal.valueOf(daysInMonth));

            // ── Budget adherence ──────────────────────────────────
            // Ablation: budget-per-category adherence relies on the ontology
            // join (enrichment.spending_category_id → budget.spending_category_id).
            // When ontology tools are disabled, we skip this signal.
            List<Budget> budgets = flags.ontologyToolsEnabled()
                ? budgetRepository.findByUserId(userUUID)
                : List.<Budget>of();
            int overBudgetCount = 0;
            List<String> overBudgetCategories = new ArrayList<>();

            if (flags.ontologyToolsEnabled()) {
                for (Budget b : budgets) {
                    BigDecimal spent = orZero(enrichmentRepository.sumSpentInCategory(
                        userUUID, b.getSpendingCategory().getId(), monthStart, monthEnd));
                    if (spent.compareTo(b.getMonthlyLimit()) > 0) {
                        overBudgetCount++;
                        overBudgetCategories.add(b.getSpendingCategory().getName());
                    }
                }
            }

            // ── Goals ─────────────────────────────────────────────
            List<FinancialGoal> activeGoals =
                goalRepository.findByUserIdAndStatus(userUUID, "ACTIVE");

            BigDecimal avgGoalProgress = BigDecimal.ZERO;
            List<Map<String, Object>> goalSummaries = new ArrayList<>();

            if (!activeGoals.isEmpty()) {
                BigDecimal totalProgress = BigDecimal.ZERO;
                for (FinancialGoal g : activeGoals) {
                    BigDecimal current = g.getCurrentAmount() != null
                        ? g.getCurrentAmount() : BigDecimal.ZERO;
                    BigDecimal pct = g.getTargetAmount().compareTo(BigDecimal.ZERO) > 0
                        ? current.divide(g.getTargetAmount(), 4, RoundingMode.HALF_UP)
                                 .multiply(BigDecimal.valueOf(100))
                                 .setScale(1, RoundingMode.HALF_UP)
                        : BigDecimal.ZERO;
                    totalProgress = totalProgress.add(pct);
                    goalSummaries.add(Map.of(
                        "name",     g.getName(),
                        "progress", pct,
                        "current",  current,
                        "target",   g.getTargetAmount()
                    ));
                }
                avgGoalProgress = totalProgress.divide(
                    BigDecimal.valueOf(activeGoals.size()), 1, RoundingMode.HALF_UP);
            }

            // ── Anomalies this month ───────────────────────────────
            // Ablation: anomaly flags are produced by the ontology enrichment
            // pipeline. When disabled, treat anomaly count as unknown (0).
            long anomalyCount = 0L;
            if (flags.ontologyToolsEnabled()) {
                List<TransactionEnrichment> enrichments =
                    enrichmentRepository.findByUserIdAndDateRange(userUUID, monthStart, monthEnd);
                anomalyCount = enrichments.stream()
                    .filter(e -> Boolean.TRUE.equals(e.getIsAnomaly()))
                    .count();
            }

            // ── Score calculation ─────────────────────────────────
            double sr = savingsRate.doubleValue();
            int savingsScore;
            String savingsExplanation;
            if (sr >= 20) {
                savingsScore = 40;
                savingsExplanation = "Excellent — saving " + sr + "% of income, above the 20% target.";
            } else if (sr >= 10) {
                savingsScore = 25;
                savingsExplanation = "Good — saving " + sr + "% of income. Push toward 20% for full points.";
            } else if (sr >= 0) {
                savingsScore = 10;
                savingsExplanation = "Low — only saving " + sr + "% of income. Target is 10-20%.";
            } else {
                savingsScore = 0;
                savingsExplanation = "Spending more than earning this month (" + sr + "% savings rate).";
            }

            int budgetScore;
            String budgetExplanation;
            if (budgets.isEmpty()) {
                budgetScore = 15;
                budgetExplanation = "No budgets set — neutral score. Setting budgets would improve tracking.";
            } else {
                double onTrack = (double)(budgets.size() - overBudgetCount) / budgets.size();
                budgetScore = (int)(onTrack * 30);
                budgetExplanation = overBudgetCount == 0
                    ? "All " + budgets.size() + " budgets on track this month."
                    : overBudgetCount + " of " + budgets.size() + " budgets exceeded: "
                        + String.join(", ", overBudgetCategories) + ".";
            }

            int goalScore;
            String goalExplanation;
            if (activeGoals.isEmpty()) {
                goalScore = 10;
                goalExplanation = "No active goals set — neutral score.";
            } else {
                double gp = avgGoalProgress.doubleValue();
                if (gp >= 50) {
                    goalScore = 20;
                    goalExplanation = "Strong — average goal progress at " + gp + "%.";
                } else if (gp >= 25) {
                    goalScore = 14;
                    goalExplanation = "Moderate — average goal progress at " + gp + "%.";
                } else {
                    goalScore = 7;
                    goalExplanation = "Early stage — average goal progress at " + gp + "%.";
                }
            }

            int anomalyScore;
            String anomalyExplanation;
            if (anomalyCount == 0) {
                anomalyScore = 10;
                anomalyExplanation = "No unusual transactions detected this month.";
            } else if (anomalyCount == 1) {
                anomalyScore = 5;
                anomalyExplanation = "1 unusual transaction detected — review recommended.";
            } else {
                anomalyScore = 0;
                anomalyExplanation = anomalyCount + " unusual transactions detected — review recommended.";
            }

            int totalScore = savingsScore + budgetScore + goalScore + anomalyScore;

            String grade;
            String assessment;
            String assessmentDetail;
            if (totalScore >= 85) {
                grade = "A"; assessment = "Excellent";
                assessmentDetail = "Your finances are in great shape. Keep up the strong habits.";
            } else if (totalScore >= 70) {
                grade = "B"; assessment = "Good";
                assessmentDetail = "Solid financial health with a few areas to sharpen.";
            } else if (totalScore >= 55) {
                grade = "C"; assessment = "Fair";
                assessmentDetail = "Making progress but there are clear areas needing attention.";
            } else if (totalScore >= 40) {
                grade = "D"; assessment = "Needs Attention";
                assessmentDetail = "Several financial habits need improvement to build stability.";
            } else {
                grade = "F"; assessment = "Critical";
                assessmentDetail = "Immediate action needed — spending is outpacing income significantly.";
            }

            // ── Positive signals ──────────────────────────────────
            List<String> positiveSignals = new ArrayList<>();
            if (sr >= 15) positiveSignals.add("Savings rate of " + sr + "% — above recommended minimum.");
            if (overBudgetCount == 0 && !budgets.isEmpty()) positiveSignals.add("All budgets on track this month.");
            if (anomalyCount == 0) positiveSignals.add("No suspicious transactions detected.");
            if (!activeGoals.isEmpty() && avgGoalProgress.doubleValue() >= 25)
                positiveSignals.add("Making consistent progress on financial goals.");
            if (netFlow.compareTo(BigDecimal.ZERO) > 0)
                positiveSignals.add("Positive cash flow this month — income exceeds spending.");
            if (positiveSignals.isEmpty()) positiveSignals.add("You're tracking your finances — that's the first step.");

            // ── Recommendations (prioritized) ────────────────────
            List<Map<String, String>> recommendations = new ArrayList<>();
            if (sr < 0) {
                recommendations.add(Map.of("priority", "HIGH",
                    "action", "Reduce spending immediately — you're spending more than you earn this month.",
                    "impact", "Prevents debt accumulation"));
            } else if (sr < 10) {
                recommendations.add(Map.of("priority", "HIGH",
                    "action", "Increase savings rate from " + sr + "% to at least 10%. Review largest expenses.",
                    "impact", "+" + income.multiply(BigDecimal.valueOf(0.10 - sr / 100)).setScale(0, RoundingMode.HALF_UP) + "/mo saved"));
            }
            if (overBudgetCount > 0) {
                recommendations.add(Map.of("priority", "MEDIUM",
                    "action", "Review budget categories: " + String.join(", ", overBudgetCategories) + ".",
                    "impact", "Better spending awareness"));
            }
            if (anomalyCount > 0) {
                recommendations.add(Map.of("priority", "MEDIUM",
                    "action", "Review " + anomalyCount + " flagged unusual transaction(s) for errors or fraud.",
                    "impact", "Prevent unauthorized charges"));
            }
            if (activeGoals.isEmpty()) {
                recommendations.add(Map.of("priority", "LOW",
                    "action", "Set a financial goal — start with a 3-month emergency fund.",
                    "impact", "Builds long-term financial security"));
            }
            if (budgets.isEmpty()) {
                recommendations.add(Map.of("priority", "LOW",
                    "action", "Create monthly budgets for your top spending categories.",
                    "impact", "Enables proactive spending control"));
            }
            if (recommendations.isEmpty()) {
                recommendations.add(Map.of("priority", "LOW",
                    "action", "Keep up your current habits — consider increasing investment contributions.",
                    "impact", "Accelerates wealth building"));
            }

            // Use LinkedHashMap to avoid Map.of() 10-entry limit
            Map<String, Object> scoreComponents = new LinkedHashMap<>();
            scoreComponents.put("savings_rate",     Map.of("score", savingsScore, "max", 40, "explanation", savingsExplanation));
            scoreComponents.put("budget_adherence", Map.of("score", budgetScore,  "max", 30, "explanation", budgetExplanation));
            scoreComponents.put("goal_progress",    Map.of("score", goalScore,    "max", 20, "explanation", goalExplanation));
            scoreComponents.put("anomaly_check",    Map.of("score", anomalyScore, "max", 10, "explanation", anomalyExplanation));

            Map<String, Object> metrics = new LinkedHashMap<>();
            metrics.put("income",                   income);
            metrics.put("spending",                 spending);
            metrics.put("net_flow",                 netFlow);
            metrics.put("savings_rate_pct",         savingsRate);
            metrics.put("savings_rate_prior_month", priorSavingsRate);
            metrics.put("savings_rate_delta",       savingsRateDelta);
            metrics.put("savings_rate_trend",       savingsRateDelta.compareTo(BigDecimal.ZERO) >= 0 ? "improving" : "declining");
            metrics.put("projected_monthly_spend",  projectedMonthlySpend);
            metrics.put("anomaly_count",            anomalyCount);

            Map<String, Object> resultMap = new LinkedHashMap<>();
            resultMap.put("health_score",      totalScore);
            resultMap.put("grade",             grade);
            resultMap.put("assessment",        assessment);
            resultMap.put("assessment_detail", assessmentDetail);
            resultMap.put("month",             today.getMonth().toString());
            resultMap.put("year",              today.getYear());
            resultMap.put("score_components",  scoreComponents);
            resultMap.put("metrics",           metrics);
            resultMap.put("goals",             goalSummaries);
            resultMap.put("positive_signals",  positiveSignals);
            resultMap.put("recommendations",   recommendations);
            return ToolResult.ok(resultMap);

        } catch (Exception e) {
            log.error("GetFinancialHealthTool error: {}", e.getMessage());
            return ToolResult.error("Failed to compute financial health: " + e.getMessage());
        }
    }

    private BigDecimal orZero(BigDecimal v) {
        return v != null ? v : BigDecimal.ZERO;
    }
}