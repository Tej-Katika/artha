package com.finwise.agent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.*;

/**
 * Tool: get_budget_status
 * Compares actual spending to 50/30/20 rule based on user's real income.
 */
@Slf4j
@FinWiseTool(
    description = "Compare spending to 50/30/20 budget benchmarks based on the user's actual income",
    category    = "analytics",
    version     = "1.0.0"
)
@Component
@RequiredArgsConstructor
public class GetBudgetStatusTool implements FinancialTool {

    private final TransactionRepository transactionRepository;
    private final ObjectMapper          objectMapper;
    private final ReferenceDateProvider refDate;

    private static final Map<String, String> CATEGORY_BUCKET = new LinkedHashMap<>();
    static {
        CATEGORY_BUCKET.put("HOUSING",          "needs");
        CATEGORY_BUCKET.put("BILLS_UTILITIES",  "needs");
        CATEGORY_BUCKET.put("GROCERIES",        "needs");
        CATEGORY_BUCKET.put("HEALTHCARE",       "needs");
        CATEGORY_BUCKET.put("TRANSPORTATION",   "needs");
        CATEGORY_BUCKET.put("LOAN_PAYMENT",     "needs");
        CATEGORY_BUCKET.put("FOOD_AND_DRINK",   "wants");
        CATEGORY_BUCKET.put("SHOPPING",         "wants");
        CATEGORY_BUCKET.put("ENTERTAINMENT",    "wants");
        CATEGORY_BUCKET.put("TRAVEL",           "wants");
        CATEGORY_BUCKET.put("PERSONAL_CARE",    "wants");
        CATEGORY_BUCKET.put("INVESTMENTS",      "savings");
        CATEGORY_BUCKET.put("TAXES",            "savings");
        CATEGORY_BUCKET.put("BUSINESS_EXPENSE", "savings");
        CATEGORY_BUCKET.put("CHILDCARE",        "savings");
        CATEGORY_BUCKET.put("MARKETING",        "savings");
    }

    @Override
    public String getName() { return "get_budget_status"; }

    @Override
    public Object getDefinition() {
        return Map.of(
            "name", getName(),
            "description", "Compare spending to 50/30/20 budget benchmarks based on the user's actual income.",
            "input_schema", Map.of(
                "type", "object",
                "properties", Map.of(
                    "month", Map.of("type", "string", "description", "Month YYYY-MM. Defaults to most recent.")
                ),
                "required", List.of()
            )
        );
    }

    @Override
    public ToolResult execute(JsonNode input, ToolContext context) {
        long start = System.currentTimeMillis();
        String json = executeInternal(input, context.userId());
        try {
            JsonNode data = objectMapper.readTree(json);
            if (data.has("error")) return ToolResult.error(data.get("error").asText());
            return ToolResult.okWithTiming(data, start);
        } catch (Exception e) {
            return ToolResult.error("Failed to parse result: " + e.getMessage());
        }
    }

    private String executeInternal(JsonNode input, String userId) {
        try {
            UUID uid = UUID.fromString(userId);

            YearMonth month = resolveMonth(input, uid);
            Instant startDate = month.atDay(1).atStartOfDay().toInstant(ZoneOffset.UTC);
            Instant endDate   = month.atEndOfMonth().atTime(23, 59, 59).toInstant(ZoneOffset.UTC);

            List<Transaction> txns = transactionRepository
                .findByUserIdAndPostDateBetweenOrderByPostDateDesc(uid, startDate, endDate);

            double income = 0;
            Map<String, Double> spendByCategory = new LinkedHashMap<>();

            for (Transaction t : txns) {
                double amount = t.getAmount().doubleValue();
                if ("CREDIT".equals(t.getTransactionType())) {
                    income += amount;
                } else if ("DEBIT".equals(t.getTransactionType())) {
                    String cat = extractCategory(t);
                    spendByCategory.merge(cat, amount, Double::sum);
                }
            }

            if (income <= 0) {
                return "{\"error\":\"No income found for " + month + "\"}";
            }

            // Sum by bucket
            Map<String, Double> actualByBucket = new LinkedHashMap<>();
            actualByBucket.put("needs",   0.0);
            actualByBucket.put("wants",   0.0);
            actualByBucket.put("savings", 0.0);
            actualByBucket.put("other",   0.0);

            for (Map.Entry<String, Double> e : spendByCategory.entrySet()) {
                String bucket = CATEGORY_BUCKET.getOrDefault(e.getKey(), "other");
                actualByBucket.merge(bucket, e.getValue(), Double::sum);
            }

            double targetNeeds   = income * 0.50;
            double targetWants   = income * 0.30;
            double targetSavings = income * 0.20;

            List<Map<String, Object>> buckets = new ArrayList<>();
            buckets.add(bucketRow("needs",   actualByBucket.get("needs"),   targetNeeds,   income));
            buckets.add(bucketRow("wants",   actualByBucket.get("wants"),   targetWants,   income));
            buckets.add(bucketRow("savings", actualByBucket.get("savings"), targetSavings, income));

            // Per-category detail sorted by spend
            final double finalIncome = income;
            List<Map<String, Object>> categoryDetail = new ArrayList<>();
            spendByCategory.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .forEach(e -> {
                    Map<String, Object> cd = new LinkedHashMap<>();
                    cd.put("category",      e.getKey());
                    cd.put("bucket",        CATEGORY_BUCKET.getOrDefault(e.getKey(), "other"));
                    cd.put("actual",        Math.round(e.getValue() * 100.0) / 100.0);
                    cd.put("pct_of_income", Math.round((e.getValue() / finalIncome) * 10000.0) / 100.0);
                    categoryDetail.add(cd);
                });

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("month",           month.toString());
            result.put("income",          Math.round(income * 100.0) / 100.0);
            result.put("budget_rule",     "50/30/20");
            result.put("bucket_summary",  buckets);
            result.put("category_detail", categoryDetail);

            return objectMapper.writeValueAsString(result);

        } catch (Exception e) {
            log.error("get_budget_status error - userId={} error={}", userId, e.getMessage(), e);
            return "{\"error\":\"" + e.getMessage().replace("\"", "'") + "\"}";
        }
    }

    private Map<String, Object> bucketRow(String name, double actual, double target, double income) {
        double diff = actual - target;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("bucket",        name);
        m.put("target_pct",    name.equals("needs") ? 50 : name.equals("wants") ? 30 : 20);
        m.put("target_amount", Math.round(target * 100.0) / 100.0);
        m.put("actual_amount", Math.round(actual * 100.0) / 100.0);
        m.put("actual_pct",    Math.round((actual / income) * 10000.0) / 100.0);
        m.put("status",        diff > 0 ? "OVER_BUDGET" : "UNDER_BUDGET");
        m.put("over_by",       diff > 0 ? Math.round(diff  * 100.0) / 100.0 : 0.0);
        m.put("under_by",      diff < 0 ? Math.round(-diff * 100.0) / 100.0 : 0.0);
        return m;
    }

    private YearMonth resolveMonth(JsonNode input, UUID userId) {
        if (input.hasNonNull("month")) {
            try { return YearMonth.parse(input.get("month").asText()); }
            catch (DateTimeParseException ignored) {}
        }
        List<Transaction> recent = transactionRepository.findByUserIdOrderByPostDateDesc(userId);
        if (!recent.isEmpty()) {
            return YearMonth.from(recent.get(0).getPostDate().atZone(ZoneOffset.UTC).toLocalDate());
        }
        return refDate.yearMonth();
    }

    private String extractCategory(Transaction t) {
        try {
            if (t.getMetadata() == null) return "UNCATEGORIZED";
            Object cat = t.getMetadata().get("category");
            return cat != null ? cat.toString() : "UNCATEGORIZED";
        } catch (Exception e) {
            return "UNCATEGORIZED";
        }
    }
}