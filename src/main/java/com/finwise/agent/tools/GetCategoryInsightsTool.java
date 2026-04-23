package com.finwise.agent.tools;

import com.fasterxml.jackson.databind.JsonNode;

import com.finwise.agent.core.FeatureFlags;
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
import java.time.temporal.TemporalAdjusters;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Tool: get_category_insights - Phase 6B
 *
 * Improvements over Phase 5:
 *  - Month-over-month delta per category ($ and %)
 *  - 3-month rolling average for context
 *  - Trend direction (INCREASING / DECREASING / STABLE)
 *  - Actionable one-line insight per category
 *  - Budget comparison per category
 */
@Slf4j
@FinWiseTool(
    description = "Deep-dive category spending analysis with month-over-month trend and actionable insights",
    category    = "analytics",
    version     = "1.0.0"
)
@Component
@RequiredArgsConstructor
public class GetCategoryInsightsTool implements FinancialTool {

    private final TransactionEnrichmentRepository enrichmentRepository;
    private final TransactionRepository           transactionRepository;
    private final SpendingCategoryRepository      categoryRepository;
    private final BudgetRepository                budgetRepository;
    private final ReferenceDateProvider           refDate;
    private final FeatureFlags                    flags;

    @Override
    public String getName() { return "get_category_insights"; }

    @Override
    public Object getDefinition() {
        return Map.of(
            "name", getName(),
            "description", """
                Returns spending broken down by category with month-over-month dollar
                and percent change, 3-month rolling average, trend direction
                (INCREASING/DECREASING/STABLE), budget comparison, and a one-line
                actionable insight per category.
                Use this when the user asks about category spending, which categories
                are growing, why spending changed, or wants a category breakdown.
                """,
            "input_schema", Map.of(
                "type", "object",
                "properties", Map.of(
                    "category_name", Map.of(
                        "type",        "string",
                        "description", "Optional: focus on a specific category (e.g. 'Grocery')."
                    ),
                    "months_back", Map.of(
                        "type",        "integer",
                        "description", "How many months of history to analyze. Default 3. Max 6."
                    )
                ),
                "required", List.of()
            )
        );
    }

    @Override
    public ToolResult execute(JsonNode input, ToolContext context) {
        try {
            long startTime  = System.currentTimeMillis();
            UUID userUUID   = UUID.fromString(context.userId());
            LocalDate today = refDate.today();

            int monthsBack = 3;
            if (input.has("months_back") && !input.get("months_back").isNull()) {
                monthsBack = Math.min(input.get("months_back").asInt(3), 6);
            }

            String categoryFilter = input.has("category_name") && !input.get("category_name").isNull()
                ? input.get("category_name").asText(null) : null;

            // Build monthly spend maps: index 0 = current, 1 = last month, etc.
            List<Map<String, BigDecimal>> monthlyData = new ArrayList<>();
            List<String> monthLabels = new ArrayList<>();

            for (int i = 0; i <= monthsBack; i++) {
                LocalDate month = today.minusMonths(i);
                Instant mStart  = month.with(TemporalAdjusters.firstDayOfMonth())
                    .atStartOfDay().toInstant(ZoneOffset.UTC);
                Instant mEnd    = month.with(TemporalAdjusters.lastDayOfMonth())
                    .atTime(23, 59, 59).toInstant(ZoneOffset.UTC);
                monthlyData.add(flags.ontologyToolsEnabled()
                    ? buildCategorySpend(userUUID, mStart, mEnd)
                    : buildCategorySpendRaw(userUUID, mStart, mEnd));
                monthLabels.add(month.getYear() + "-" + String.format("%02d", month.getMonthValue()));
            }

            // Load budgets
            List<Budget> budgets = budgetRepository.findByUserId(userUUID);
            Map<String, BigDecimal> budgetByCategory = new HashMap<>();
            for (Budget b : budgets) {
                budgetByCategory.put(b.getSpendingCategory().getName(), b.getMonthlyLimit());
            }

            // Collect all categories seen across all months
            Set<String> allCategories = new LinkedHashSet<>();
            for (Map<String, BigDecimal> m : monthlyData) allCategories.addAll(m.keySet());

            List<Map<String, Object>> categoryInsights = new ArrayList<>();
            BigDecimal grandTotalCurrent = BigDecimal.ZERO;
            BigDecimal grandTotalPrior   = BigDecimal.ZERO;

            for (String catName : allCategories) {
                if (categoryFilter != null && !catName.equalsIgnoreCase(categoryFilter)) continue;

                BigDecimal current = monthlyData.get(0).getOrDefault(catName, BigDecimal.ZERO);
                BigDecimal prior   = monthlyData.size() > 1
                    ? monthlyData.get(1).getOrDefault(catName, BigDecimal.ZERO) : BigDecimal.ZERO;
                BigDecimal twoAgo  = monthlyData.size() > 2
                    ? monthlyData.get(2).getOrDefault(catName, BigDecimal.ZERO) : BigDecimal.ZERO;

                if (current.compareTo(BigDecimal.ZERO) == 0
                        && prior.compareTo(BigDecimal.ZERO) == 0) continue;

                // Month-over-month
                BigDecimal momDelta = current.subtract(prior);
                BigDecimal momPct   = prior.compareTo(BigDecimal.ZERO) > 0
                    ? momDelta.divide(prior, 4, RoundingMode.HALF_UP)
                              .multiply(BigDecimal.valueOf(100)).setScale(1, RoundingMode.HALF_UP)
                    : BigDecimal.ZERO;

                // 3-month rolling average (prior months only)
                List<BigDecimal> priorList = new ArrayList<>();
                for (int i = 1; i < Math.min(4, monthlyData.size()); i++) {
                    BigDecimal v = monthlyData.get(i).getOrDefault(catName, BigDecimal.ZERO);
                    if (v.compareTo(BigDecimal.ZERO) > 0) priorList.add(v);
                }
                BigDecimal rollingAvg = priorList.isEmpty() ? BigDecimal.ZERO
                    : priorList.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                               .divide(BigDecimal.valueOf(priorList.size()), 2, RoundingMode.HALF_UP);

                BigDecimal vsAvgPct = rollingAvg.compareTo(BigDecimal.ZERO) > 0
                    ? current.subtract(rollingAvg).divide(rollingAvg, 4, RoundingMode.HALF_UP)
                             .multiply(BigDecimal.valueOf(100)).setScale(1, RoundingMode.HALF_UP)
                    : BigDecimal.ZERO;

                String trend = computeTrend(current, prior, twoAgo);

                // Budget info
                Map<String, Object> budgetInfo = new LinkedHashMap<>();
                BigDecimal budgetLimit = budgetByCategory.get(catName);
                if (budgetLimit != null) {
                    BigDecimal remaining = budgetLimit.subtract(current);
                    BigDecimal utilPct   = budgetLimit.compareTo(BigDecimal.ZERO) > 0
                        ? current.divide(budgetLimit, 4, RoundingMode.HALF_UP)
                                 .multiply(BigDecimal.valueOf(100)).setScale(1, RoundingMode.HALF_UP)
                        : BigDecimal.ZERO;
                    budgetInfo.put("limit",           budgetLimit);
                    budgetInfo.put("remaining",       remaining);
                    budgetInfo.put("utilization_pct", utilPct);
                    budgetInfo.put("status",
                        current.compareTo(budgetLimit) > 0 ? "OVER_BUDGET"
                        : utilPct.doubleValue() > 80 ? "WARNING" : "ON_TRACK");
                }

                // Monthly history
                List<Map<String, Object>> history = new ArrayList<>();
                for (int i = 0; i < Math.min(monthlyData.size(), monthLabels.size()); i++) {
                    Map<String, Object> point = new LinkedHashMap<>();
                    point.put("month",  monthLabels.get(i));
                    point.put("amount", monthlyData.get(i).getOrDefault(catName, BigDecimal.ZERO));
                    history.add(point);
                }

                String insight = buildInsight(momPct, vsAvgPct, rollingAvg, budgetLimit, current);

                grandTotalCurrent = grandTotalCurrent.add(current);
                grandTotalPrior   = grandTotalPrior.add(prior);

                Map<String, Object> catMap = new LinkedHashMap<>();
                catMap.put("category",       catName);
                catMap.put("current_month",  current);
                catMap.put("prior_month",    prior);
                catMap.put("mom_delta",      momDelta);
                catMap.put("mom_pct",        momPct);
                catMap.put("rolling_avg_3mo",rollingAvg);
                catMap.put("vs_avg_pct",     vsAvgPct);
                catMap.put("trend",          trend);
                catMap.put("budget",         budgetInfo);
                catMap.put("monthly_history",history);
                catMap.put("insight",        insight);
                categoryInsights.add(catMap);
            }

            // Sort by current month spend desc
            categoryInsights.sort((a, b) ->
                ((BigDecimal) b.get("current_month")).compareTo((BigDecimal) a.get("current_month")));

            // Add pct_of_total
            if (grandTotalCurrent.compareTo(BigDecimal.ZERO) > 0) {
                for (Map<String, Object> cat : categoryInsights) {
                    BigDecimal amt = (BigDecimal) cat.get("current_month");
                    cat.put("pct_of_total", amt.divide(grandTotalCurrent, 4, RoundingMode.HALF_UP)
                        .multiply(BigDecimal.valueOf(100)).setScale(1, RoundingMode.HALF_UP));
                }
            }

            String fastestGrowing = categoryInsights.stream()
                .filter(c -> ((BigDecimal) c.get("mom_pct")).compareTo(BigDecimal.ZERO) > 0)
                .max(Comparator.comparing(c -> (BigDecimal) c.get("mom_pct")))
                .map(c -> (String) c.get("category"))
                .orElse("none");

            BigDecimal totalDelta = grandTotalCurrent.subtract(grandTotalPrior);
            BigDecimal totalDeltaPct = grandTotalPrior.compareTo(BigDecimal.ZERO) > 0
                ? totalDelta.divide(grandTotalPrior, 4, RoundingMode.HALF_UP)
                            .multiply(BigDecimal.valueOf(100)).setScale(1, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("categories",          categoryInsights);
            result.put("total_current_month", grandTotalCurrent);
            result.put("total_prior_month",   grandTotalPrior);
            result.put("total_mom_delta",     totalDelta);
            result.put("total_mom_pct",       totalDeltaPct);
            result.put("fastest_growing",     fastestGrowing);
            result.put("months_analyzed",     monthsBack);
            return ToolResult.ok(result);

        } catch (Exception e) {
            log.error("GetCategoryInsightsTool error: {}", e.getMessage());
            return ToolResult.error("Failed to compute category insights: " + e.getMessage());
        }
    }

    // Ablation path: no ontology joins. Read category from transactions.metadata['category'].
    private Map<String, BigDecimal> buildCategorySpendRaw(UUID userUUID, Instant from, Instant to) {
        List<Transaction> txns = transactionRepository
            .findByUserIdAndPostDateBetweenOrderByPostDateDesc(userUUID, from, to);
        Map<String, BigDecimal> result = new LinkedHashMap<>();
        for (Transaction tx : txns) {
            if (!"DEBIT".equals(tx.getTransactionType())) continue;
            if (tx.getMetadata() == null) continue;
            Object cat = tx.getMetadata().get("category");
            if (cat == null) continue;
            result.merge(cat.toString(), tx.getAmount(), BigDecimal::add);
        }
        return result;
    }

    // Uses the same pattern as the original tool: findByUserIdAndDateRange + findById
    private Map<String, BigDecimal> buildCategorySpend(UUID userUUID, Instant from, Instant to) {
        List<TransactionEnrichment> enrichments =
            enrichmentRepository.findByUserIdAndDateRange(userUUID, from, to);
        Map<String, BigDecimal> result = new LinkedHashMap<>();
        for (TransactionEnrichment e : enrichments) {
            if (e.getSpendingCategory() == null) continue;
            Optional<Transaction> txOpt = transactionRepository.findById(e.getTransactionId());
            if (txOpt.isEmpty()) continue;
            Transaction tx = txOpt.get();
            if (!"DEBIT".equals(tx.getTransactionType())) continue;
            result.merge(e.getSpendingCategory().getName(), tx.getAmount(), BigDecimal::add);
        }
        return result;
    }

    private String computeTrend(BigDecimal m0, BigDecimal m1, BigDecimal m2) {
        if (m1.compareTo(BigDecimal.ZERO) == 0) return "STABLE";
        boolean upRecent   = m0.compareTo(m1) > 0;
        boolean upPrevious = m1.compareTo(m2) > 0;
        if (upRecent && upPrevious)   return "INCREASING";
        if (!upRecent && !upPrevious) return "DECREASING";
        return "STABLE";
    }

    private String buildInsight(BigDecimal momPct, BigDecimal vsAvgPct,
                                  BigDecimal rollingAvg, BigDecimal budgetLimit,
                                  BigDecimal current) {
        List<String> parts = new ArrayList<>();
        double pct = momPct.doubleValue();
        if (Math.abs(pct) >= 10)
            parts.add(String.format("%s%.0f%% vs last month", pct > 0 ? "Up " : "Down ", Math.abs(pct)));
        double avgPct = vsAvgPct.doubleValue();
        if (Math.abs(avgPct) >= 10 && rollingAvg.compareTo(BigDecimal.ZERO) > 0)
            parts.add(String.format("%.0f%% %s 3-month average", Math.abs(avgPct), avgPct > 0 ? "above" : "below"));
        if (budgetLimit != null && current.compareTo(budgetLimit) > 0)
            parts.add(String.format("$%.0f over $%.0f budget",
                current.subtract(budgetLimit).doubleValue(), budgetLimit.doubleValue()));
        return parts.isEmpty() ? "Consistent with recent history." : String.join("; ", parts) + ".";
    }
}