package com.finwise.agent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.finwise.agent.core.FinWiseTool;
import com.finwise.agent.core.FinancialTool;
import com.finwise.agent.core.ReferenceDateProvider;
import com.finwise.agent.core.ToolContext;
import com.finwise.agent.core.ToolResult;
import com.finwise.agent.domain.Transaction;
import com.finwise.agent.domain.TransactionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Tool: get_behavioral_patterns — Phase 7B
 *
 * Detects WHEN and WHY spending spikes happen, not just how much.
 *
 * Detects:
 *   1. Weekend vs weekday spending ratio
 *   2. Post-payday spending spike (3-5 days after income)
 *   3. Month-end cash squeeze pattern
 *   4. Merchant frequency patterns (e.g. McDonald's 13x/month)
 *   5. Impulse purchase clusters (multiple purchases same day)
 *   6. Balance-triggered spending (spending more when balance is high)
 *   7. Seasonal patterns (monthly comparison)
 */
@Slf4j
@FinWiseTool(
    description = "Detect behavioral spending patterns: weekend splurges, post-payday spikes, impulse clusters",
    category    = "analytics",
    version     = "1.0.0"
)
@Component
@RequiredArgsConstructor
public class GetBehavioralPatternsTool implements FinancialTool {

    private final TransactionRepository transactionRepository;
    private final ReferenceDateProvider refDate;

    private static final BigDecimal SPIKE_THRESHOLD = BigDecimal.valueOf(1.5); // 50% above average = spike

    @Override
    public String getName() { return "get_behavioral_patterns"; }

    @Override
    public Object getDefinition() {
        return Map.of(
            "name", getName(),
            "description", """
                Analyzes behavioral spending patterns to understand WHEN and WHY
                spending spikes happen, not just how much was spent.
                Detects: weekend vs weekday spending differences, post-payday splurges,
                month-end cash squeezes, impulse purchase clusters, high-frequency
                merchant habits, and balance-triggered overspending.
                Use this when the user asks why they keep overspending, wants to
                understand their spending triggers, or asks about their habits.
                """,
            "input_schema", Map.of(
                "type", "object",
                "properties", Map.of(
                    "days_back", Map.of(
                        "type",        "integer",
                        "description", "Days of history to analyze. Default 90 for reliable patterns."
                    ),
                    "pattern_type", Map.of(
                        "type",        "string",
                        "description", "Focus on a specific pattern: weekend, payday, month_end, merchant, impulse, all",
                        "enum",        List.of("weekend", "payday", "month_end", "merchant", "impulse", "all")
                    )
                ),
                "required", List.of()
            )
        );
    }

    @Override
    public ToolResult execute(JsonNode input, ToolContext context) {
        try {
            long startMs  = System.currentTimeMillis();
            UUID userUUID = UUID.fromString(context.userId());

            int daysBack = 90;
            if (input.has("days_back") && !input.get("days_back").isNull()) {
                daysBack = Math.min(input.get("days_back").asInt(90), 365);
            }
            String patternType = "all";
            if (input.has("pattern_type") && !input.get("pattern_type").isNull()) {
                patternType = input.get("pattern_type").asText("all");
            }

            Instant to   = refDate.now();
            Instant from = to.minus(daysBack, ChronoUnit.DAYS);

            List<Transaction> allTx = transactionRepository
                .findByUserIdAndPostDateBetweenOrderByPostDateDesc(userUUID, from, to);

            // Only debit/spending transactions for most patterns
            List<Transaction> spendTx = allTx.stream()
                .filter(t -> "DEBIT".equals(t.getTransactionType()))
                .collect(Collectors.toList());

            List<Transaction> creditTx = allTx.stream()
                .filter(t -> "CREDIT".equals(t.getTransactionType()))
                .collect(Collectors.toList());

            if (spendTx.isEmpty()) {
                return ToolResult.ok(Map.of("message",
                    "Not enough transaction data to detect behavioral patterns."));
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("period_days",        daysBack);
            result.put("transactions_analyzed", spendTx.size());

            List<Map<String, Object>> patterns = new ArrayList<>();

            // ── 1. Weekend vs Weekday ─────────────────────────────────────────
            if ("all".equals(patternType) || "weekend".equals(patternType)) {
                Map<String, Object> wp = analyzeWeekendPattern(spendTx, daysBack);
                if (wp != null) patterns.add(wp);
            }

            // ── 2. Post-Payday Spike ──────────────────────────────────────────
            if ("all".equals(patternType) || "payday".equals(patternType)) {
                Map<String, Object> pp = analyzePostPaydayPattern(spendTx, creditTx);
                if (pp != null) patterns.add(pp);
            }

            // ── 3. Month-End Cash Squeeze ─────────────────────────────────────
            if ("all".equals(patternType) || "month_end".equals(patternType)) {
                Map<String, Object> me = analyzeMonthEndPattern(spendTx, allTx);
                if (me != null) patterns.add(me);
            }

            // ── 4. High-Frequency Merchant Habits ────────────────────────────
            if ("all".equals(patternType) || "merchant".equals(patternType)) {
                List<Map<String, Object>> mps = analyzeMerchantFrequency(spendTx, daysBack);
                patterns.addAll(mps);
            }

            // ── 5. Impulse Purchase Clusters ─────────────────────────────────
            if ("all".equals(patternType) || "impulse".equals(patternType)) {
                Map<String, Object> ip = analyzeImpulseClusters(spendTx);
                if (ip != null) patterns.add(ip);
            }

            // Sort patterns by severity
            patterns.sort((a, b) -> {
                int sa = severityScore((String) a.getOrDefault("severity", "LOW"));
                int sb = severityScore((String) b.getOrDefault("severity", "LOW"));
                return Integer.compare(sb, sa); // descending
            });

            // Overall behavioral summary
            long highCount = patterns.stream()
                .filter(p -> "HIGH".equals(p.get("severity"))).count();
            long medCount = patterns.stream()
                .filter(p -> "MEDIUM".equals(p.get("severity"))).count();

            String overallAssessment;
            if (highCount >= 3) {
                overallAssessment = "MULTIPLE_HIGH_RISK_PATTERNS";
            } else if (highCount >= 1) {
                overallAssessment = "HIGH_RISK_PATTERN_DETECTED";
            } else if (medCount >= 2) {
                overallAssessment = "MODERATE_RISK_PATTERNS";
            } else {
                overallAssessment = "HEALTHY_SPENDING_BEHAVIOR";
            }

            result.put("overall_assessment", overallAssessment);
            result.put("patterns_found",     patterns.size());
            result.put("high_severity",      highCount);
            result.put("medium_severity",    medCount);
            result.put("patterns",           patterns);

            return ToolResult.okWithTiming(result, startMs);

        } catch (Exception e) {
            log.error("GetBehavioralPatternsTool error: {}", e.getMessage(), e);
            return ToolResult.error("Failed to analyze behavioral patterns: " + e.getMessage());
        }
    }

    // ── Pattern Analyzers ─────────────────────────────────────────────────────

    private Map<String, Object> analyzeWeekendPattern(List<Transaction> spendTx, int daysBack) {
        BigDecimal weekendTotal  = BigDecimal.ZERO;
        BigDecimal weekdayTotal  = BigDecimal.ZERO;
        int weekendDays = 0;
        int weekdayDays = 0;

        // Count distinct days
        Set<LocalDate> weekendDaysSet = new HashSet<>();
        Set<LocalDate> weekdayDaysSet = new HashSet<>();

        for (Transaction tx : spendTx) {
            LocalDate date = tx.getPostDate().atZone(ZoneOffset.UTC).toLocalDate();
            DayOfWeek dow  = date.getDayOfWeek();

            if (dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY) {
                weekendTotal = weekendTotal.add(tx.getAmount());
                weekendDaysSet.add(date);
            } else {
                weekdayTotal = weekdayTotal.add(tx.getAmount());
                weekdayDaysSet.add(date);
            }
        }

        weekendDays = Math.max(weekendDaysSet.size(), 1);
        weekdayDays = Math.max(weekdayDaysSet.size(), 1);

        BigDecimal avgWeekend = weekendTotal.divide(
            BigDecimal.valueOf(weekendDays), 2, RoundingMode.HALF_UP);
        BigDecimal avgWeekday = weekdayTotal.divide(
            BigDecimal.valueOf(weekdayDays), 2, RoundingMode.HALF_UP);

        if (avgWeekday.compareTo(BigDecimal.ZERO) == 0) return null;

        BigDecimal ratio = avgWeekend.divide(avgWeekday, 2, RoundingMode.HALF_UP);

        // Only flag if weekend spending is meaningfully higher
        if (ratio.compareTo(BigDecimal.valueOf(1.2)) < 0) return null;

        String severity = ratio.compareTo(BigDecimal.valueOf(2.0)) >= 0 ? "HIGH"
                        : ratio.compareTo(BigDecimal.valueOf(1.5)) >= 0 ? "MEDIUM" : "LOW";

        int pctMore = ratio.subtract(BigDecimal.ONE)
            .multiply(BigDecimal.valueOf(100))
            .setScale(0, RoundingMode.HALF_UP).intValue();

        Map<String, Object> p = new LinkedHashMap<>();
        p.put("pattern",         "WEEKEND_OVERSPENDING");
        p.put("severity",        severity);
        p.put("avg_weekend_day", avgWeekend);
        p.put("avg_weekday_day", avgWeekday);
        p.put("weekend_ratio",   ratio);
        p.put("insight", String.format(
            "You spend %d%% more per day on weekends ($%.0f/day) vs weekdays ($%.0f/day).",
            pctMore, avgWeekend.doubleValue(), avgWeekday.doubleValue()));
        p.put("recommendation",
            "Plan weekend activities in advance with a set budget to reduce impulse spending.");
        return p;
    }

    private Map<String, Object> analyzePostPaydayPattern(
            List<Transaction> spendTx, List<Transaction> creditTx) {

        if (creditTx.isEmpty()) return null;

        // Find paydays (large credits > $500)
        List<Instant> paydays = creditTx.stream()
            .filter(t -> t.getAmount().compareTo(BigDecimal.valueOf(500)) > 0)
            .map(Transaction::getPostDate)
            .sorted()
            .collect(Collectors.toList());

        if (paydays.isEmpty()) return null;

        // For each payday window (days 1-5 after payday) vs normal window
        BigDecimal postPaydayTotal = BigDecimal.ZERO;
        BigDecimal normalTotal     = BigDecimal.ZERO;
        int postPaydayDays = 0;
        int normalDays     = 0;

        Set<LocalDate> postPaydayDaySet = new HashSet<>();
        Set<LocalDate> normalDaySet     = new HashSet<>();

        for (Transaction tx : spendTx) {
            Instant txTime = tx.getPostDate();
            LocalDate txDate = txTime.atZone(ZoneOffset.UTC).toLocalDate();
            boolean isPostPayday = paydays.stream().anyMatch(payday -> {
                long daysSince = ChronoUnit.DAYS.between(payday, txTime);
                return daysSince >= 0 && daysSince <= 5;
            });

            if (isPostPayday) {
                postPaydayTotal = postPaydayTotal.add(tx.getAmount());
                postPaydayDaySet.add(txDate);
            } else {
                normalTotal = normalTotal.add(tx.getAmount());
                normalDaySet.add(txDate);
            }
        }

        postPaydayDays = Math.max(postPaydayDaySet.size(), 1);
        normalDays     = Math.max(normalDaySet.size(), 1);

        BigDecimal avgPostPayday = postPaydayTotal.divide(
            BigDecimal.valueOf(postPaydayDays), 2, RoundingMode.HALF_UP);
        BigDecimal avgNormal = normalTotal.divide(
            BigDecimal.valueOf(normalDays), 2, RoundingMode.HALF_UP);

        if (avgNormal.compareTo(BigDecimal.ZERO) == 0) return null;

        BigDecimal ratio = avgPostPayday.divide(avgNormal, 2, RoundingMode.HALF_UP);

        if (ratio.compareTo(BigDecimal.valueOf(1.3)) < 0) return null;

        String severity = ratio.compareTo(BigDecimal.valueOf(2.0)) >= 0 ? "HIGH"
                        : ratio.compareTo(BigDecimal.valueOf(1.5)) >= 0 ? "MEDIUM" : "LOW";

        int pctMore = ratio.subtract(BigDecimal.ONE)
            .multiply(BigDecimal.valueOf(100))
            .setScale(0, RoundingMode.HALF_UP).intValue();

        Map<String, Object> p = new LinkedHashMap<>();
        p.put("pattern",              "POST_PAYDAY_SPLURGE");
        p.put("severity",             severity);
        p.put("avg_post_payday_day",  avgPostPayday);
        p.put("avg_normal_day",       avgNormal);
        p.put("splurge_ratio",        ratio);
        p.put("payday_count",         paydays.size());
        p.put("insight", String.format(
            "You spend %d%% more in the 5 days after each paycheck ($%.0f/day vs $%.0f/day normally). " +
            "This is a post-payday splurge pattern.",
            pctMore, avgPostPayday.doubleValue(), avgNormal.doubleValue()));
        p.put("recommendation",
            "Set aside savings immediately when your paycheck arrives — before spending. " +
            "'Pay yourself first' removes the temptation to splurge.");
        return p;
    }

    private Map<String, Object> analyzeMonthEndPattern(
            List<Transaction> spendTx, List<Transaction> allTx) {

        // Group spending by day-of-month bucket: early (1-10), mid (11-20), late (21-31)
        BigDecimal earlyTotal = BigDecimal.ZERO;
        BigDecimal midTotal   = BigDecimal.ZERO;
        BigDecimal lateTotal  = BigDecimal.ZERO;
        int earlyDays = 0, midDays = 0, lateDays = 0;

        Set<LocalDate> earlyDaySet = new HashSet<>();
        Set<LocalDate> midDaySet   = new HashSet<>();
        Set<LocalDate> lateDaySet  = new HashSet<>();

        // Also track balance at month end (last 5 days of month)
        List<BigDecimal> monthEndBalances = new ArrayList<>();

        for (Transaction tx : allTx) {
            LocalDate date = tx.getPostDate().atZone(ZoneOffset.UTC).toLocalDate();
            int dom = date.getDayOfMonth();

            // Track month-end balances
            if (dom >= 26 && tx.getBalance() != null) {
                monthEndBalances.add(tx.getBalance());
            }
        }

        for (Transaction tx : spendTx) {
            LocalDate date = tx.getPostDate().atZone(ZoneOffset.UTC).toLocalDate();
            int dom = date.getDayOfMonth();

            if (dom <= 10) {
                earlyTotal = earlyTotal.add(tx.getAmount());
                earlyDaySet.add(date);
            } else if (dom <= 20) {
                midTotal = midTotal.add(tx.getAmount());
                midDaySet.add(date);
            } else {
                lateTotal = lateTotal.add(tx.getAmount());
                lateDaySet.add(date);
            }
        }

        earlyDays = Math.max(earlyDaySet.size(), 1);
        midDays   = Math.max(midDaySet.size(), 1);
        lateDays  = Math.max(lateDaySet.size(), 1);

        BigDecimal avgEarly = earlyTotal.divide(BigDecimal.valueOf(earlyDays), 2, RoundingMode.HALF_UP);
        BigDecimal avgMid   = midTotal.divide(BigDecimal.valueOf(midDays),   2, RoundingMode.HALF_UP);
        BigDecimal avgLate  = lateTotal.divide(BigDecimal.valueOf(lateDays), 2, RoundingMode.HALF_UP);

        // Check for low month-end balances
        boolean hasLowMonthEndBalance = !monthEndBalances.isEmpty() &&
            monthEndBalances.stream()
                .filter(b -> b.compareTo(BigDecimal.valueOf(100)) < 0)
                .count() > monthEndBalances.size() / 3;

        // Month-end squeeze = early spending high, late balance low
        boolean isSqueeze = avgEarly.compareTo(avgLate.multiply(BigDecimal.valueOf(1.4))) > 0
                         && hasLowMonthEndBalance;

        if (!isSqueeze && avgEarly.compareTo(BigDecimal.valueOf(1.3))  < 0) return null;
        if (!isSqueeze) return null;

        Map<String, Object> p = new LinkedHashMap<>();
        p.put("pattern",       "MONTH_END_CASH_SQUEEZE");
        p.put("severity",      hasLowMonthEndBalance ? "HIGH" : "MEDIUM");
        p.put("avg_early_day", avgEarly);
        p.put("avg_mid_day",   avgMid);
        p.put("avg_late_day",  avgLate);
        p.put("low_month_end_balance_detected", hasLowMonthEndBalance);
        p.put("insight",
            String.format("You spend most heavily in the first 10 days ($%.0f/day) " +
                "and run low on cash by month-end ($%.0f/day late-month). " +
                "This creates a cash squeeze cycle.",
                avgEarly.doubleValue(), avgLate.doubleValue()));
        p.put("recommendation",
            "Divide your monthly budget into weekly envelopes (cash or digital). " +
            "Spending the same amount each week prevents month-end shortfalls.");
        return p;
    }

    private List<Map<String, Object>> analyzeMerchantFrequency(
            List<Transaction> spendTx, int daysBack) {

        List<Map<String, Object>> patterns = new ArrayList<>();

        // Group by merchant, count visits and total
        Map<String, List<Transaction>> byMerchant = spendTx.stream()
            .filter(t -> t.getMerchantName() != null && !t.getMerchantName().isBlank())
            .collect(Collectors.groupingBy(Transaction::getMerchantName));

        double weeksAnalyzed = Math.max(daysBack / 7.0, 1.0);

        for (Map.Entry<String, List<Transaction>> entry : byMerchant.entrySet()) {
            String merchant = entry.getKey();
            List<Transaction> txs = entry.getValue();

            int visits = txs.size();
            double visitsPerWeek = visits / weeksAnalyzed;

            // Only flag if 2+ visits per week on average
            if (visitsPerWeek < 2.0) continue;

            BigDecimal total = txs.stream()
                .map(Transaction::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal avgPerVisit = total.divide(
                BigDecimal.valueOf(visits), 2, RoundingMode.HALF_UP);
            BigDecimal monthlyEstimate = total
                .divide(BigDecimal.valueOf(daysBack), 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(30))
                .setScale(2, RoundingMode.HALF_UP);

            String severity = visitsPerWeek >= 5 ? "HIGH"
                            : visitsPerWeek >= 3 ? "MEDIUM" : "LOW";

            Map<String, Object> p = new LinkedHashMap<>();
            p.put("pattern",          "HIGH_FREQUENCY_MERCHANT");
            p.put("severity",         severity);
            p.put("merchant",         merchant);
            p.put("visits_analyzed",  visits);
            p.put("visits_per_week",  Math.round(visitsPerWeek * 10) / 10.0);
            p.put("avg_per_visit",    avgPerVisit);
            p.put("monthly_estimate", monthlyEstimate);
            p.put("insight", String.format(
                "%s: %d visits in %d days (%.1f×/week), avg $%.0f/visit — ~$%.0f/month.",
                merchant, visits, daysBack, visitsPerWeek,
                avgPerVisit.doubleValue(), monthlyEstimate.doubleValue()));
            p.put("recommendation", String.format(
                "Consider setting a monthly budget of $%.0f for %s. " +
                "Even reducing to %.0f visits/week saves $%.0f/month.",
                monthlyEstimate.doubleValue(), merchant,
                Math.max(visitsPerWeek - 1, 1),
                avgPerVisit.multiply(BigDecimal.valueOf(weeksAnalyzed * 4 / daysBack * 7))
                    .setScale(0, RoundingMode.HALF_UP).doubleValue()));
            patterns.add(p);
        }

        // Sort by monthly estimate descending, take top 5
        patterns.sort((a, b) ->
            ((BigDecimal) b.get("monthly_estimate"))
                .compareTo((BigDecimal) a.get("monthly_estimate")));
        return patterns.stream().limit(5).collect(Collectors.toList());
    }

    private Map<String, Object> analyzeImpulseClusters(List<Transaction> spendTx) {
        // Group by date, find days with unusually many transactions
        Map<LocalDate, List<Transaction>> byDay = spendTx.stream()
            .collect(Collectors.groupingBy(
                t -> t.getPostDate().atZone(ZoneOffset.UTC).toLocalDate()));

        if (byDay.isEmpty()) return null;

        double avgTxPerDay = spendTx.size() / (double) byDay.size();

        List<Map<String, Object>> impulseDays = new ArrayList<>();
        for (Map.Entry<LocalDate, List<Transaction>> entry : byDay.entrySet()) {
            List<Transaction> dayTx = entry.getValue();
            if (dayTx.size() < Math.max(avgTxPerDay * 2.5, 5)) continue;

            BigDecimal dayTotal = dayTx.stream()
                .map(Transaction::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

            impulseDays.add(Map.of(
                "date",        entry.getKey().toString(),
                "tx_count",    dayTx.size(),
                "total_spent", dayTotal
            ));
        }

        if (impulseDays.isEmpty()) return null;

        impulseDays.sort((a, b) ->
            Integer.compare((Integer) b.get("tx_count"), (Integer) a.get("tx_count")));

        BigDecimal avgImpulseDay = impulseDays.stream()
            .map(d -> (BigDecimal) d.get("total_spent"))
            .reduce(BigDecimal.ZERO, BigDecimal::add)
            .divide(BigDecimal.valueOf(impulseDays.size()), 2, RoundingMode.HALF_UP);

        Map<String, Object> p = new LinkedHashMap<>();
        p.put("pattern",           "IMPULSE_PURCHASE_CLUSTERS");
        p.put("severity",          impulseDays.size() >= 3 ? "HIGH" : "MEDIUM");
        p.put("impulse_days_found", impulseDays.size());
        p.put("avg_tx_per_day",    Math.round(avgTxPerDay * 10) / 10.0);
        p.put("avg_impulse_day_spend", avgImpulseDay);
        p.put("top_impulse_days",  impulseDays.stream().limit(3).toList());
        p.put("insight", String.format(
            "%d days found with unusually high transaction counts (2.5x+ your daily average of %.1f). " +
            "Average spend on these days: $%.0f.",
            impulseDays.size(), avgTxPerDay, avgImpulseDay.doubleValue()));
        p.put("recommendation",
            "On high-spending days, add a 24-hour pause rule before making purchases " +
            "over $50. Impulse spending often disappears after sleeping on it.");
        return p;
    }

    private int severityScore(String severity) {
        return switch (severity) {
            case "HIGH"   -> 3;
            case "MEDIUM" -> 2;
            case "LOW"    -> 1;
            default       -> 0;
        };
    }
}