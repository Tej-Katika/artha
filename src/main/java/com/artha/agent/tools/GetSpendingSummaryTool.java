package com.artha.agent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.artha.agent.core.ArthaTool;
import com.artha.agent.core.FinancialTool;
import com.artha.agent.core.ReferenceDateProvider;
import com.artha.agent.core.ToolContext;
import com.artha.agent.core.ToolResult;
import com.artha.agent.domain.Transaction;
import com.artha.agent.domain.TransactionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.*;

/**
 * Tool: get_spending_summary
 * Returns total spending broken down by category for a period.
 */
@Slf4j
@ArthaTool(
    description = "Category-level spending totals and income summary for a time period",
    category    = "data",
    version     = "1.0.0"
)
@Component
@RequiredArgsConstructor
public class GetSpendingSummaryTool implements FinancialTool {

    private final TransactionRepository transactionRepository;
    private final ObjectMapper          objectMapper;
    private final ReferenceDateProvider refDate;

    @Override
    public String getName() { return "get_spending_summary"; }

    @Override
    public Object getDefinition() {
        return Map.of(
            "name", getName(),
            "description", """
                Get total spending broken down by category across ALL transactions for a period.
                Use this for ANY question about category totals, monthly spend amounts,
                loan payments, business expenses, savings rate, or spending patterns.
                This covers all transactions — not just recent ones.
                """,
            "input_schema", Map.of(
                "type", "object",
                "properties", Map.of(
                    "month",       Map.of("type", "string",  "description", "Month YYYY-MM. Defaults to most recent."),
                    "months_back", Map.of("type", "integer", "description", "How many months to include. Default 1.")
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
            int monthsBack = input.hasNonNull("months_back") ? Math.min(input.get("months_back").asInt(1), 12) : 1;

            YearMonth endMonth = resolveEndMonth(input, uid);
            YearMonth startMonth = endMonth.minusMonths(monthsBack - 1);

            Instant startDate = startMonth.atDay(1).atStartOfDay().toInstant(ZoneOffset.UTC);
            Instant endDate   = endMonth.atEndOfMonth().atTime(23, 59, 59).toInstant(ZoneOffset.UTC);

            List<Transaction> txns = transactionRepository
                .findByUserIdAndPostDateBetweenOrderByPostDateDesc(uid, startDate, endDate);

            // Aggregate
            Map<String, Double> spendByCategory = new LinkedHashMap<>();
            double totalIncome = 0;
            double totalSpend  = 0;

            for (Transaction t : txns) {
                double amount = t.getAmount().doubleValue();
                if ("CREDIT".equals(t.getTransactionType())) {
                    totalIncome += amount;
                } else if ("DEBIT".equals(t.getTransactionType())) {
                    String cat = extractCategory(t);
                    spendByCategory.merge(cat, amount, Double::sum);
                    totalSpend += amount;
                }
            }

            // Build sorted category list
            final double finalTotalSpend = totalSpend;
            List<Map<String, Object>> categories = new ArrayList<>();
            spendByCategory.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .forEach(e -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("category", e.getKey());
                    row.put("total",    Math.round(e.getValue() * 100.0) / 100.0);
                    row.put("pct_of_spend", finalTotalSpend > 0
                        ? Math.round((e.getValue() / finalTotalSpend) * 10000.0) / 100.0
                        : 0.0);
                    categories.add(row);
                });

            double net         = totalIncome - totalSpend;
            double savingsRate = totalIncome > 0 ? (net / totalIncome) * 100.0 : 0.0;

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("period_start",          startMonth.atDay(1).toString());
            result.put("period_end",            endMonth.atEndOfMonth().toString());
            result.put("months_covered",        monthsBack);
            result.put("total_income",          Math.round(totalIncome * 100.0) / 100.0);
            result.put("total_spending",        Math.round(totalSpend  * 100.0) / 100.0);
            result.put("net_cash_flow",         Math.round(net * 100.0) / 100.0);
            result.put("savings_rate_pct",      Math.round(savingsRate * 100.0) / 100.0);
            result.put("spending_by_category",  categories);

            return objectMapper.writeValueAsString(result);

        } catch (Exception e) {
            log.error("get_spending_summary error - userId={} error={}", userId, e.getMessage(), e);
            return "{\"error\":\"" + e.getMessage().replace("\"", "'") + "\"}";
        }
    }

    private YearMonth resolveEndMonth(JsonNode input, UUID userId) {
        if (input.hasNonNull("month")) {
            try {
                return YearMonth.parse(input.get("month").asText());
            } catch (DateTimeParseException ignored) {}
        }
        // Find most recent month with data
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